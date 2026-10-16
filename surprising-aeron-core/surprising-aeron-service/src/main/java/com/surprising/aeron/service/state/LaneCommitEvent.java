package com.surprising.aeron.service.state;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

import com.surprising.aeron.service.lane.SettlementLaneWorker;

/** Sequence-local fan-out that advances every affected Account Lane without an owner-side per-lane barrier. */
public final class LaneCommitEvent implements SettlementLaneWorker.Command {
    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private final LongArrayList[] usersByLane;
    private final long[] completedLanes;
    private TradingRuntimeState runtime;
    private long coreSequence;
    private long requiredLaneMask;
    private long committedLaneMask;
    private LaneOrderResultTarget resultTarget;
    private RuntimeIdentityRegistry identities;
    private int resultLaneId;

    // Lazily allocated only for controls whose final commit also stamps account orders.
    // The affected Lanes resolve these identities in their own tables. The Owner never reads
    // an account table to discover the order's Lane at this final commit boundary.
    private LongArrayList metadataOrderIds;
    /** Physical output buffers are frozen on Lane and swapped to Owner only after completion. */
    private RuntimeOrderChangeBuffer[] metadataOrders;
    private long metadataTimestamp, metadataPosition;

    private LongArrayList[] triggerCancelIds;
    private int[] canceledTriggers;

    void prepareTriggerCancellations(Iterable<Long> ids) {
        if (ids == null) return;
        int count = 0;
        for (Long id : ids) {
            if (id == null) continue;
            var trigger = runtime.triggerRoute(id);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                continue;
            if (!trigger.cancelRevisionAvailable()) throw new ArithmeticException("trigger revision overflow");
            int laneId = runtime.topology().accountLaneId(trigger.userId());
            if ((requiredLaneMask & (1L << laneId)) == 0)
                throw new IllegalStateException("closing trigger has no account commit Lane");
            if (triggerCancelIds == null) {
                triggerCancelIds = new LongArrayList[usersByLane.length];
                canceledTriggers = new int[usersByLane.length];
            }
            if (triggerCancelIds[laneId] == null) triggerCancelIds[laneId] = new LongArrayList(4);
            triggerCancelIds[laneId].add(id);
            count++;
        }
        Math.addExact(runtime.revision(), count);
    }

    private void cancelClosingTriggers(AccountLaneState lane) {
        int laneId = lane.laneId();
        if (triggerCancelIds == null || triggerCancelIds[laneId] == null) return;
        runtime.enterLaneCommandScope(lane);
        try {
            LongArrayList ids = triggerCancelIds[laneId];
            for (int index = 0; index < ids.size(); index++)
                if (RuntimeTriggerOrderStateTransitions.cancelPendingForCommit(runtime, ids.get(index)))
                    canceledTriggers[laneId]++;
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    void prepareMetadata(Iterable<Long> orderIds, long timestamp, long position) {
        if (orderIds == null) return;
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid commit metadata");
        metadataTimestamp = timestamp;
        metadataPosition = position;
        for (Long orderId : orderIds) {
            if (orderId == null) continue;
            if (metadataOrderIds == null) {
                metadataOrderIds = new LongArrayList(4);
                metadataOrders = new RuntimeOrderChangeBuffer[usersByLane.length];
            }
            metadataOrderIds.add(orderId);
        }
        if (metadataOrderIds == null) return;
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            int count = metadataOrderIds.size();
            if (metadataOrders[laneId] == null) metadataOrders[laneId] = new RuntimeOrderChangeBuffer();
            metadataOrders[laneId].ensureCapacity(count);
        }
    }

    private void stampMetadata(AccountLaneState lane) {
        int laneId = lane.laneId();
        if (metadataOrderIds == null) return;
        LongArrayList ids = metadataOrderIds;
        runtime.enterLaneCommandScope(lane);
        try {
            for (int index = 0; index < ids.size(); index++) {
                long id = ids.get(index);
                OrderRuntime order = lane.orders.get(id);
                if (order == null) continue; // Another affected Lane owns it, or it was retired.
                if (order.updatedAtEpochMillis() == metadataTimestamp && order.clusterPosition() == metadataPosition)
                    continue;
                runtime.accountRollback.captureUserBefore(order.userId());
                runtime.accountRollback.captureOrderMetadataBefore(order);
                order.applyCommitMetadataInPlace(metadataTimestamp, metadataPosition);
                metadataOrders[laneId].put(id, order);
                if (!usersByLane[laneId].contains(order.userId())) usersByLane[laneId].add(order.userId());
            }
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    private void publishMetadata() {
        if (triggerCancelIds != null) {
            int count = 0;
            long lanes = requiredLaneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes);
                lanes &= lanes - 1;
                if (canceledTriggers[laneId] == 0) continue;
                runtime.flushPublishedChanges(laneId);
                count = Math.addExact(count, canceledTriggers[laneId]);
            }
            if (count != 0) runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), count));
        }
        if (metadataOrderIds == null) return;
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (metadataOrders[laneId].isEmpty()) continue;
            runtime.changedOrders.adopt(laneId, metadataOrders[laneId]);
            usersByLane[laneId].forEach(runtime.changedUsers::add);
        }
    }

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
        committedLaneMask = laneMask;
        runtime = owner;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            LongArrayList target = usersByLane[laneId];
            target.clear();
            target.addAll(routedUsers[laneId]);
        }
        return this;
    }

    void prepareResponse(LaneOrderResultTarget target, RuntimeIdentityRegistry registry,
                         int laneId, long commitMask) {
        if (target == null || registry == null || (requiredLaneMask & (1L << laneId)) == 0)
            throw new IllegalArgumentException("batch response requires its account Lane");
        resultTarget = target;
        identities = registry;
        resultLaneId = laneId;
        committedLaneMask = commitMask;
    }

    public void execute(AccountLaneState lane) {
        int laneId = lane.laneId();
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("Account Lane commit reached an unrelated lane");
        }
        cancelClosingTriggers(lane);
        stampMetadata(lane);
        if ((committedLaneMask & laneBit) != 0)
            runtime.applyLaneUsers(lane, usersByLane[laneId], coreSequence);
        if (resultTarget != null && laneId == resultLaneId)
            LaneOrderResultTarget.captureFinal(resultTarget, identities, lane);
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("Account Lane committed the same sequence twice");
        }
        // Capture the callback target before publishing completion.  The Owner may observe the
        // last completion immediately, publish-and-clear this reusable event, and null its
        // runtime field before this Lane performs the wake-up.
        TradingRuntimeState completionRuntime = runtime;
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        // The completion bits are the authoritative hand-off state.  Publish the wake-up only
        // after this Lane's bit is visible; the Owner gate re-reads the event and proceeds only
        // when all required Lane bits have been observed.
        completionRuntime.signalOwnerCompletion();
    }

    public long coreSequence() { return coreSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long committedLaneMask() { return committedLaneMask; }
    public long completedLaneMask() {
        long mask = 0;
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if ((long) LONGS.getAcquire(completedLanes, laneId * CACHE_LINE_LONGS) != 0) {
                mask |= 1L << laneId;
            }
        }
        return mask;
    }
    public boolean complete() {
        return completedLaneMask() == requiredLaneMask;
    }

    void publishAndClear() {
        if (!complete()) throw new IllegalStateException("incomplete Account Lane commit event");
        publishMetadata();
        reset();
    }

    void discard() {
        if (completedLaneMask() != 0) throw new IllegalStateException("started Account Lane commit cannot be discarded");
        reset();
    }

    private void reset() {
        long lanes = requiredLaneMask;
        if (triggerCancelIds != null) {
            long triggerLanes = lanes;
            while (triggerLanes != 0) {
                int laneId = Long.numberOfTrailingZeros(triggerLanes);
                triggerLanes &= triggerLanes - 1;
                if (triggerCancelIds[laneId] != null) triggerCancelIds[laneId].clear();
                canceledTriggers[laneId] = 0;
            }
        }

        if (metadataOrderIds != null) {
            long metadataLanes = lanes;
            while (metadataLanes != 0) {
                int laneId = Long.numberOfTrailingZeros(metadataLanes);
                metadataLanes &= metadataLanes - 1;
                if (metadataOrders[laneId] != null) metadataOrders[laneId].clear();
            }
            metadataOrderIds.clear();
        }
        metadataTimestamp = metadataPosition = 0;

        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            usersByLane[laneId].clear();
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
        }
        runtime = null;
        coreSequence = 0;
        requiredLaneMask = 0;
        committedLaneMask = 0;
        resultTarget = null;
        identities = null;
        resultLaneId = 0;
    }
}
