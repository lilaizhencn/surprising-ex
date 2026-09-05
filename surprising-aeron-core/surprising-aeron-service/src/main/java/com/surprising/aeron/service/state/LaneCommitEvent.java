package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** Sequence-local fan-out that advances every affected Account Lane without an owner-side per-lane barrier. */
public final class LaneCommitEvent implements SettlementLaneWorker.Command {
    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private final LongArrayList[] usersByLane;
    private final long[] completedLanes;
    private TradingRuntimeState runtime;
    private long coreSequence;
    private long requiredLaneMask;

    LaneCommitEvent(int laneCount) {
        usersByLane = new LongArrayList[laneCount];
        completedLanes = new long[Math.multiplyExact(laneCount, CACHE_LINE_LONGS)];
        for (int laneId = 0; laneId < laneCount; laneId++) usersByLane[laneId] = new LongArrayList(4);
    }

    LaneCommitEvent prepare(long sequence, long laneMask, LongArrayList[] routedUsers,
                            TradingRuntimeState owner) {
        if (sequence <= 0 || laneMask == 0 || routedUsers == null || owner == null
                || coreSequence != 0 || completedLaneMask() != 0) {
            throw new IllegalStateException("invalid Account Lane commit event");
        }
        coreSequence = sequence;
        requiredLaneMask = laneMask;
        runtime = owner;
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
            LongArrayList target = usersByLane[laneId];
            target.clear();
            if ((laneMask & 1L << laneId) != 0) target.addAll(routedUsers[laneId]);
        }
        return this;
    }

    @Override
    public void execute(AccountLaneState lane) {
        int laneId = lane.laneId();
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("Account Lane commit reached an unrelated lane");
        }
        long startedNanos = System.nanoTime();
        runtime.applyLaneUsers(lane, usersByLane[laneId], coreSequence);
        runtime.recordSequenceCommitLaneOperation(laneId, System.nanoTime() - startedNanos);
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("Account Lane committed the same sequence twice");
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
    }

    public long coreSequence() { return coreSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long completedLaneMask() {
        long mask = 0;
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0) {
                mask |= 1L << laneId;
            }
        }
        return mask;
    }
    public boolean complete() { return completedLaneMask() == requiredLaneMask; }

    void clear() {
        if (!complete()) throw new IllegalStateException("incomplete Account Lane commit event");
        reset();
    }

    void discard() {
        if (completedLaneMask() != 0) throw new IllegalStateException("started Account Lane commit cannot be discarded");
        reset();
    }

    private void reset() {
        for (LongArrayList users : usersByLane) users.clear();
        runtime = null;
        coreSequence = 0;
        requiredLaneMask = 0;
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
        }
    }
}
