package com.surprising.aeron.service.state;

import org.agrona.collections.Long2LongHashMap;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import java.util.ArrayList;
import java.util.List;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/** 尚未完成的订单预留索引；owner 维护序号和用户计数，在 Lane 完成后释放。 */
final class PendingReservationTracker {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingRuntimeState owner;

    PendingReservationTracker(TradingRuntimeState owner) { this.owner = owner; }

    /** 命令序号到待完成订单预留的索引。 */
    final PendingReservationSequenceIndex pendingReservationsBySequence =
            new PendingReservationSequenceIndex(4_096);

    /** 待完成订单 ID 到用户 ID 的索引，0 表示不存在。 */
    final Long2LongHashMap pendingReservationUsers = new Long2LongHashMap(4_096, 0.65f, 0);

    /** 每个用户尚未完成的订单预留计数。 */
    final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(4_096);

    /** 全部用户待完成订单预留总数。 */
    int totalPendingReservations;

    /** 批量准入按命令保存一次收据，逐订单预留索引仅由 Account Lane 维护。 */
    final LongObjectHashMap<PendingBatch> batches = new LongObjectHashMap<>();

    static final class PendingBatch {
        final long userId;
        /** 借用批量命令数组；本收据必须在命令归还对象池前移除。 */
        final OrderRuntime[] orders;
        final int count;
        /** 尚未由 Lane 完成的预留数，仅 Owner 根据回执递减。 */
        int remaining;
        PendingBatch(long userId, OrderRuntime[] orders, int count) {
            this.userId = userId; this.orders = orders; this.count = count; this.remaining = count;
        }
    }

    void registerBatch(long sequence, long userId, OrderRuntime[] orders, int count) {
        if (batches.containsKey(sequence) || pendingReservationsBySequence.containsKey(sequence))
            throw new IllegalStateException("batch reservation was registered twice");
        batches.put(sequence, new PendingBatch(userId, orders, count));
        pendingReservationCountsByUser.addToValue(userId, count);
        totalPendingReservations = Math.addExact(totalPendingReservations, count);
    }

    void completedBatchItems(long sequence, int count) {
        PendingBatch batch = batches.get(sequence);
        if (batch == null || count == 0) return;
        if (count > batch.remaining) throw new IllegalStateException("batch reservation completion overflow");
        batch.remaining -= count;
        totalPendingReservations = Math.subtractExact(totalPendingReservations, count);
        int remaining = pendingReservationCountsByUser.addToValue(batch.userId, -count);
        if (remaining == 0) pendingReservationCountsByUser.removeKey(batch.userId);
        if (batch.remaining == 0) batches.removeKey(sequence);
    }

    /** 部分拒绝等路径仍须结束未进入结算计划的预留；正常批量结算在收据计数归零后直接返回。 */
    private void completeBatchRemainder(long sequence, PendingBatch batch) {
        int completed = owner.onLane(batch.userId, lane -> {
            int count = 0;
            for (int i = 0; i < batch.count; i++) {
                OrderRuntime order = batch.orders[i];
                if (order == null || lane.pendingReservationSequences.getIfAbsent(order.orderId(), 0) != sequence) continue;
                ReservationRuntime reservation = lane.reservations.get(order.orderId());
                owner.captureBalanceBefore(batch.userId, reservation.assetId());
                lane.completePendingReservation(order.orderId(), sequence);
                owner.captureBalanceAfter(lane, batch.userId, reservation.assetId());
                owner.changedOrder(order.orderId());
                owner.changedReservations.add(order.orderId());
                owner.changedUsers.add(batch.userId);
                owner.markBalancesChanged();
                count++;
            }
            return count;
        });
        if (completed != batch.remaining) throw new IllegalStateException("batch Lane reservation count mismatch");
        completedBatchItems(sequence, completed);
    }

    record PendingReservationCompletion(ReservationRuntime reservation) {}

    record PendingReservationRef(long orderId, long userId) {
    }

    record PendingReservationBatchCompletion(
            long orderId, long userId, ReservationRuntime reservation) {
        PendingReservationBatchCompletion {
            if (orderId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("invalid pending reservation completion");
            }
        }
    }

    public void markPendingReservation(long userId, long orderId, long coreSequence) {
        owner.assertOwner();
        owner.captureReservationBefore(orderId);
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already indexed as pending");
        }
        int nextTotalPendingReservations = Math.addExact(totalPendingReservations, 1);
        owner.onLane(userId, lane -> {
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || reservation.userId() != userId) {
                throw new IllegalStateException("pending reservation is missing");
            }
            owner.captureBalanceBefore(userId, reservation.assetId());
            lane.markPendingReservation(orderId, coreSequence);
            owner.captureBalanceAfter(lane, userId, reservation.assetId());
            return null;
        });
        indexPendingReservation(userId, orderId, coreSequence, nextTotalPendingReservations);
    }

    void indexPendingReservation(long userId, long orderId, long coreSequence,
                                         int nextTotalPendingReservations) {
        pendingReservationsBySequence.add(coreSequence, orderId);
        pendingReservationUsers.put(orderId, userId);
        pendingReservationCountsByUser.addToValue(userId, 1);
        totalPendingReservations = nextTotalPendingReservations;
    }

    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        owner.assertOwner();
        owner.captureUserBefore(userId);
        owner.captureOrderBefore(orderId);
        owner.captureReservationBefore(orderId);
        int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
        if (nextTotalPendingReservations < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        requirePendingReservationIndex(orderId, coreSequence, userId);
        PendingReservationCompletion completion = owner.onLane(userId, accountLane -> {
            ReservationRuntime reservation = accountLane.reservations.get(orderId);
            if (reservation != null) owner.captureBalanceBefore(userId, reservation.assetId());
            accountLane.clientKeysByOrderId.forEach(orderId, clientKey -> owner.captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            if (reservation != null) owner.captureBalanceAfter(accountLane, userId, reservation.assetId());
            return new PendingReservationCompletion(reservation);
        });
        owner.changedOrder(orderId);
        owner.changedReservations.add(orderId);
        owner.changedUsers.add(userId);
        if (completion.reservation() != null) owner.markBalancesChanged();
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    public void completePendingReservations(long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        PendingBatch batch = batches.get(coreSequence);
        if (batch != null) { completeBatchRemainder(coreSequence, batch); return; }
        long[] pending = pendingReservationsBySequence.orderIds(coreSequence);
        if (pending.length == 0) return;
        List<PendingReservationRef> refs = new ArrayList<>(pending.length);
        for (long orderId : pending) {
            long userId = pendingReservationUsers.getOrDefault(orderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            refs.add(new PendingReservationRef(orderId, userId));
        }
        List<PendingReservationBatchCompletion> completions = preflightPendingReservationCompletions(
                coreSequence, refs);
        owner.executeOwnerSettlements(completions, PendingReservationBatchCompletion::userId, laneId -> {
            AccountLaneState lane = owner.laneCommandScope.get();
            for (PendingReservationBatchCompletion completion : completions) {
                if (owner.topology.accountLaneId(completion.userId()) != laneId) continue;
                lane.completePendingReservation(completion.orderId(), coreSequence);
                owner.captureBalanceAfter(lane, completion.userId(), completion.reservation().assetId());
            }
            return null;
        });
        for (PendingReservationBatchCompletion completion : completions) {
            owner.changedOrder(completion.orderId());
            owner.changedReservations.add(completion.orderId());
            owner.changedUsers.add(completion.userId());
            owner.markBalancesChanged();
            int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
            unindexPendingReservation(completion.orderId(), coreSequence, completion.userId(),
                    nextTotalPendingReservations);
        }
    }

    List<PendingReservationBatchCompletion> preflightPendingReservationCompletions(
            long coreSequence, List<PendingReservationRef> refs) {
        int remainingPendingReservations = totalPendingReservations;
        List<PendingReservationBatchCompletion> completions = new ArrayList<>(refs.size());
        for (PendingReservationRef ref : refs) {
            owner.captureUserBefore(ref.userId());
            owner.captureOrderBefore(ref.orderId());
            owner.captureReservationBefore(ref.orderId());
            requirePendingReservationIndex(ref.orderId(), coreSequence, ref.userId());
            PendingReservationBatchCompletion completion = owner.onLane(ref.userId(), lane -> {
                ReservationRuntime reservation = lane.reservations.get(ref.orderId());
                if (reservation != null) owner.captureBalanceBefore(ref.userId(), reservation.assetId());
                lane.clientKeysByOrderId.forEach(ref.orderId(),
                        clientKey -> owner.captureClientOrderBefore(ref.userId(), clientKey));
                lane.requirePendingReservationCompletion(ref.orderId(), coreSequence);
                return new PendingReservationBatchCompletion(ref.orderId(), ref.userId(), reservation);
            });
            remainingPendingReservations = Math.subtractExact(remainingPendingReservations, 1);
            if (remainingPendingReservations < 0) {
                throw new IllegalStateException("pending reservation counters are inconsistent");
            }
            completions.add(completion);
        }
        return List.copyOf(completions);
    }

    void requirePendingReservationIndex(long orderId, long coreSequence, long userId) {
        long indexedUserId = pendingReservationUsers.getOrDefault(orderId, 0);
        if (indexedUserId != userId || !pendingReservationsBySequence.contains(coreSequence, orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
    }

    void unindexPendingReservation(long orderId, long coreSequence, long userId,
                                           int nextTotalPendingReservations) {
        requirePendingReservationIndex(orderId, coreSequence, userId);
        pendingReservationsBySequence.remove(coreSequence, orderId);
        pendingReservationUsers.remove(orderId);
        int nextUserCount = Math.subtractExact(pendingReservationCountsByUser.get(userId), 1);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(userId);
        else pendingReservationCountsByUser.put(userId, nextUserCount);
        totalPendingReservations = nextTotalPendingReservations;
    }

    boolean pendingReservation(long orderId, long userId) {
        owner.assertOwner();
        AccountLaneState scoped = owner.laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology.accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation query crossed its owner lane");
            }
            return scoped.pendingReservation(orderId);
        }
        if (pendingReservationUsers.getOrDefault(orderId, 0) == userId) return true;
        long sequence = owner.publishedOrders.admissionSequence(orderId);
        PendingBatch batch = batches.get(sequence);
        return batch != null && batch.userId == userId;
    }

    long pendingReservedUnits(long userId, int assetId) {
        owner.assertOwner();
        return owner.onLane(userId, lane -> lane.pendingReservedUnits(userId, assetId));
    }

    int pendingReservationCount(long userId) {
        owner.assertOwner();
        AccountLaneState scoped = owner.laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology.accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation count crossed its owner lane");
            }
            return scoped.pendingReservationCount(userId);
        }
        return pendingReservationCountsByUser.get(userId);
    }

    int pendingReservationCount() {
        owner.assertOwner();
        return totalPendingReservations;
    }

    public boolean hasPendingReservations() {
        owner.assertOwner();
        return totalPendingReservations != 0;
    }

    void assertPendingReservationCounts() {
        int lanePendingReservations = 0;
        for (int laneId = 0; laneId < owner.accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            lanePendingReservations = Math.addExact(lanePendingReservations,
                    owner.onLane(currentLaneId, AccountLaneState::pendingReservationCount));
        }
        if (lanePendingReservations != totalPendingReservations) {
            throw new IllegalStateException("pending reservation counters differ from account lanes");
        }
    }

    static final class PendingReservationSequenceIndex {
        /** 无订单结果共用的空数组，不得写入。 */
        static final long[] EMPTY_ORDER_IDS = new long[0];

        /** 每个命令序号的首个预留订单，避免单元素集合分配。 */
        final LongLongHashMap firstOrderBySequence;
        /** 同一命令的其余预留订单，仅多订单场景使用。 */
        final LongObjectHashMap<LongHashSet> additionalOrdersBySequence;

        PendingReservationSequenceIndex(int initialCapacity) {
            firstOrderBySequence = new LongLongHashMap(initialCapacity);
            additionalOrdersBySequence = new LongObjectHashMap<>();
        }

        void add(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) {
                firstOrderBySequence.put(coreSequence, orderId);
                return;
            }
            if (firstOrderId == orderId) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null) {
                additionalOrderIds = new LongHashSet();
                additionalOrdersBySequence.put(coreSequence, additionalOrderIds);
            }
            if (!additionalOrderIds.add(orderId)) {
                throw new IllegalStateException("reservation is already indexed for sequence");
            }
        }

        boolean containsKey(long coreSequence) {
            return firstOrderBySequence.containsKey(coreSequence);
        }

        boolean contains(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == orderId) return orderId != 0;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            return additionalOrderIds != null && additionalOrderIds.contains(orderId);
        }

        long[] orderIds(long coreSequence) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) return EMPTY_ORDER_IDS;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                return new long[]{firstOrderId};
            }
            long[] additional = additionalOrderIds.toArray();
            long[] orderIds = new long[additional.length + 1];
            orderIds[0] = firstOrderId;
            System.arraycopy(additional, 0, orderIds, 1, additional.length);
            return orderIds;
        }

        void remove(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.getIfAbsent(coreSequence, 0);
            if (firstOrderId == 0) {
                throw new IllegalStateException("pending reservation sequence is missing");
            }
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            if (firstOrderId != orderId) {
                if (additionalOrderIds == null || !additionalOrderIds.remove(orderId)) {
                    throw new IllegalStateException("pending reservation is missing from sequence");
                }
                if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
                return;
            }
            if (additionalOrderIds == null || additionalOrderIds.isEmpty()) {
                firstOrderBySequence.removeKey(coreSequence);
                return;
            }
            long promotedOrderId = additionalOrderIds.detectIfNone(value -> true, 0);
            additionalOrderIds.remove(promotedOrderId);
            firstOrderBySequence.put(coreSequence, promotedOrderId);
            if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
        }

        boolean isEmpty() {
            return firstOrderBySequence.isEmpty();
        }

        void clear() {
            firstOrderBySequence.clear();
            additionalOrdersBySequence.clear();
        }
    }
}
