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
                if (RuntimeCommandProcessor.cancelPendingTriggerForCommit(runtime, ids.get(index)))
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
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
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
                runtime.captureUserBefore(order.userId());
                runtime.captureOrderBefore(id);
                OrderRuntime stamped = order.withCommitMetadata(metadataTimestamp, metadataPosition);
                lane.putOrder(stamped);
                stampedOrders[laneId][index] = stamped;
            }
        } finally { runtime.exitLaneCommandScope(lane); }
    }

    private void publishMetadata() {
        if (triggerCancelIds != null) {
            int count = 0;
            for (int laneId = 0; laneId < usersByLane.length; laneId++) {
                if (canceledTriggers[laneId] == 0) continue;
                runtime.flushPublishedChanges(laneId);
                count = Math.addExact(count, canceledTriggers[laneId]);
            }
            if (count != 0) runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), count));
        }
        if (metadataOrderIds == null) return;
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
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
        cancelClosingTriggers(lane);
        stampMetadata(lane);
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
        if (triggerCancelIds != null) {
            for (int laneId = 0; laneId < triggerCancelIds.length; laneId++) {
                if (triggerCancelIds[laneId] != null) triggerCancelIds[laneId].clear();
                canceledTriggers[laneId] = 0;
            }
        }

        if (metadataOrderIds != null) {
            for (int laneId = 0; laneId < metadataOrderIds.length; laneId++) {
                if (metadataOrderIds[laneId] == null) continue;
                if (stampedOrders[laneId] != null)
                    java.util.Arrays.fill(stampedOrders[laneId], 0,
                            Math.min(metadataOrderIds[laneId].size(), stampedOrders[laneId].length), null);
                metadataOrderIds[laneId].clear();
            }
        }
        metadataTimestamp = metadataPosition = 0;

        for (LongArrayList users : usersByLane) users.clear();
        runtime = null;
        coreSequence = 0;
        requiredLaneMask = 0;
        for (int laneId = 0; laneId < usersByLane.length; laneId++) {
            LONGS.setRelease(completedLanes, laneId * CACHE_LINE_LONGS, 0L);
        }
    }
}
