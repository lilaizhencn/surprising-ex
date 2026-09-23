package com.surprising.aeron.service.state;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** Sequence-local fan-out that advances every affected Account Lane without an owner-side per-lane barrier. */
public final class LaneCommitEvent implements SettlementLaneWorker.Command {
    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
    private static final boolean LATENCY_DIAGNOSTICS = MatcherSettlementEvent.LATENCY_DIAGNOSTICS;
    private static final jdk.jfr.EventType LATENCY_EVENT_TYPE =
            jdk.jfr.EventType.getEventType(LaneCommitLatency.class);

    private final LongArrayList[] usersByLane;
    private final long[] completedLanes;
    private TradingRuntimeState runtime;
    private long coreSequence;
    private long requiredLaneMask;
    private long dispatchedNanos;
    private long ownerObservedNanos;

    // Lazily allocated only for controls whose final commit also stamps account orders.
    private LongArrayList[] metadataOrderIds;
    private OrderRuntime[][] stampedOrders;
    private long metadataTimestamp, metadataPosition;

    private LongArrayList[] triggerCancelIds;
    private int[] canceledTriggers;

    void prepareTriggerCancellations(Iterable<Long> ids) {
        if (ids == null) return;
        int count = 0;
        for (Long id : ids) {
            if (id == null) continue;
            var trigger = runtime.triggerOrder(id);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                continue;
            Math.incrementExact(trigger.revision());
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
            OrderRuntime order = runtime.order(orderId);
            if (order == null) continue;
            int laneId = runtime.topology().accountLaneId(order.userId());
            if ((requiredLaneMask & (1L << laneId)) == 0)
                throw new IllegalStateException("metadata order has no account commit Lane");
            if (metadataOrderIds == null) {
                metadataOrderIds = new LongArrayList[usersByLane.length];
                stampedOrders = new OrderRuntime[usersByLane.length][];
            }
            if (metadataOrderIds[laneId] == null) metadataOrderIds[laneId] = new LongArrayList(4);
            metadataOrderIds[laneId].add(orderId);
        }
        if (metadataOrderIds == null) return;
        long lanes = requiredLaneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (metadataOrderIds[laneId] == null) continue;
            int count = metadataOrderIds[laneId].size();
            if (stampedOrders[laneId] == null || stampedOrders[laneId].length < count)
                stampedOrders[laneId] = new OrderRuntime[Math.max(4, count)];
        }
    }

    private void stampMetadata(AccountLaneState lane) {
        int laneId = lane.laneId();
        if (metadataOrderIds == null || metadataOrderIds[laneId] == null) return;
        LongArrayList ids = metadataOrderIds[laneId];
        runtime.enterLaneCommandScope(lane);
        try {
            for (int index = 0; index < ids.size(); index++) {
                long id = ids.get(index);
                OrderRuntime order = lane.orders.get(id);
                if (order == null) throw new IllegalStateException("commit order disappeared from its Lane");
                if (order.updatedAtEpochMillis() == metadataTimestamp && order.clusterPosition() == metadataPosition)
                    continue;
                runtime.accountRollback.captureUserBefore(order.userId());
                runtime.accountRollback.captureOrderBefore(id);
                OrderRuntime stamped = order.withCommitMetadata(metadataTimestamp, metadataPosition);
                lane.putOrder(stamped);
                stampedOrders[laneId][index] = stamped;
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
            if (metadataOrderIds[laneId] == null) continue;
            for (int index = 0; index < metadataOrderIds[laneId].size(); index++) {
                OrderRuntime order = stampedOrders[laneId][index];
                if (order == null) continue;
                runtime.publishOrder(order.orderId(), order);
                runtime.changedOrder(order.orderId(), order);
                runtime.changedUsers.add(order.userId());
            }
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
        runtime = owner;
        if (LATENCY_DIAGNOSTICS) dispatchedNanos = System.nanoTime();
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

    @Override
    public void execute(AccountLaneState lane) {
        int laneId = lane.laneId();
        long laneBit = 1L << laneId;
        if ((requiredLaneMask & laneBit) == 0) {
            throw new IllegalStateException("Account Lane commit reached an unrelated lane");
        }
        long startedNanos = System.nanoTime();
        cancelClosingTriggers(lane);
        stampMetadata(lane);
        runtime.applyLaneUsers(lane, usersByLane[laneId], coreSequence);
        long finishedNanos = System.nanoTime();
        runtime.recordSequenceCommitLaneOperation(laneId, finishedNanos - startedNanos);
        int completionOffset = laneId * CACHE_LINE_LONGS;
        if ((long) LONGS.getAcquire(completedLanes, completionOffset) != 0) {
            throw new IllegalStateException("Account Lane committed the same sequence twice");
        }
        // Capture the callback target before publishing completion.  The Owner may observe the
        // last completion immediately, publish-and-clear this reusable event, and null its
        // runtime field before this Lane performs the wake-up.
        TradingRuntimeState completionRuntime = runtime;
        if (LATENCY_DIAGNOSTICS) {
            LONGS.setOpaque(completedLanes, completionOffset + 1, startedNanos);
            LONGS.setOpaque(completedLanes, completionOffset + 2, finishedNanos);
        }
        LONGS.setRelease(completedLanes, completionOffset, 1L);
        // The completion bits are the authoritative hand-off state.  Publish the wake-up only
        // after this Lane's bit is visible; the Owner gate re-reads the event and proceeds only
        // when all required Lane bits have been observed.
        completionRuntime.signalOwnerCompletion();
    }

    public long coreSequence() { return coreSequence; }
    public long requiredLaneMask() { return requiredLaneMask; }
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
        boolean complete = completedLaneMask() == requiredLaneMask;
        if (complete && LATENCY_DIAGNOSTICS && ownerObservedNanos == 0)
            ownerObservedNanos = System.nanoTime();
        return complete;
    }

    void publishAndClear() {
        if (!complete()) throw new IllegalStateException("incomplete Account Lane commit event");
        publishMetadata();
        if (LATENCY_DIAGNOSTICS && LATENCY_EVENT_TYPE.isEnabled()) recordLatency(System.nanoTime());
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
                if (metadataOrderIds[laneId] == null) continue;
                if (stampedOrders[laneId] != null)
                    java.util.Arrays.fill(stampedOrders[laneId], 0,
                            Math.min(metadataOrderIds[laneId].size(), stampedOrders[laneId].length), null);
                metadataOrderIds[laneId].clear();
            }
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
        dispatchedNanos = 0;
        ownerObservedNanos = 0;
    }

    private void recordLatency(long releasedNanos) {
        long mask = requiredLaneMask;
        long lastStarted = 0;
        long lastFinished = 0;
        int lanes = 0;
        while (mask != 0) {
            int laneId = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            lanes++;
            lastStarted = Math.max(lastStarted,
                    (long) LONGS.getOpaque(completedLanes, laneId * CACHE_LINE_LONGS + 1));
            lastFinished = Math.max(lastFinished,
                    (long) LONGS.getOpaque(completedLanes, laneId * CACHE_LINE_LONGS + 2));
        }
        LaneCommitLatency event = new LaneCommitLatency();
        event.sequence = coreSequence;
        event.lanes = lanes;
        event.dispatchToLastLaneStartNanos = lastStarted - dispatchedNanos;
        event.dispatchToCompleteNanos = lastFinished - dispatchedNanos;
        event.completeToOwnerNanos = ownerObservedNanos - lastFinished;
        event.ownerToReleaseNanos = releasedNanos - ownerObservedNanos;
        event.commit();
    }

    @jdk.jfr.Name("surprising.LaneCommitLatency")
    @jdk.jfr.Label("Lane commit through ordered release")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class LaneCommitLatency extends jdk.jfr.Event {
        public long sequence;
        public int lanes;
        public long dispatchToLastLaneStartNanos;
        public long dispatchToCompleteNanos;
        public long completeToOwnerNanos;
        public long ownerToReleaseNanos;
    }
}
