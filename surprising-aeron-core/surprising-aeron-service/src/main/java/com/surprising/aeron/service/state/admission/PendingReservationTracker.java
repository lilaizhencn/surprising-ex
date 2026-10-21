package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.settlement.MatcherSettlementPlan;
import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import static com.surprising.aeron.service.state.TradingRuntimeState.*;

/** 尚未完成的订单预留索引；owner 维护序号和用户计数，在 Lane 完成后释放。 */
public final class PendingReservationTracker {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingRuntimeState owner;

    public PendingReservationTracker(TradingRuntimeState owner) { this.owner = owner; }

    /** 命令序号到待完成订单预留的索引。 */
    final PendingReservationSequenceIndex pendingReservationsBySequence =
            new PendingReservationSequenceIndex(4_096);

    /** 待完成订单 ID 到用户 ID 的索引，0 表示不存在。 */
    final Long2LongHashMap pendingReservationUsers = new Long2LongHashMap(4_096, 0.65f, 0);

    /** 每个用户尚未完成的订单预留计数。 */
    final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(4_096);

    /** matcher sequence 批量撤销时复用的用户计数缓冲。 */
    private final LongIntHashMap matcherRemovedByUserScratch = new LongIntHashMap(64);

    /** 全部用户待完成订单预留总数。 */
    int totalPendingReservations;

    /** 批量准入按命令保存一次收据，逐订单预留索引仅由 Account Lane 维护。 */
    final LongObjectHashMap<PendingBatch> batches = new LongObjectHashMap<>();

    public static final class PendingBatch {
        final long userId;
        /** 借用批量命令数组；本收据必须在命令归还对象池前移除。 */
        final OrderRuntime[] orders;
        final int count;
        /** 尚未由 Lane 完成的预留数，仅 Owner 根据回执递减。 */
        int remaining;
        PendingBatch(long userId, OrderRuntime[] orders, int count) {
            this.userId = userId; this.orders = orders; this.count = count; this.remaining = count;
        }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public int remaining() { return remaining; }
    public long userId() { return userId; }
    public int count() { return count; }

    public OrderRuntime order(int index) { return orders[index]; }

}

    public void registerBatch(long sequence, long userId, OrderRuntime[] orders, int count) {
        if (batches.containsKey(sequence) || pendingReservationsBySequence.containsKey(sequence))
            throw new IllegalStateException("batch reservation was registered twice");
        batches.put(sequence, new PendingBatch(userId, orders, count));
        pendingReservationCountsByUser.addToValue(userId, count);
        totalPendingReservations = Math.addExact(totalPendingReservations, count);
    }

    public void completedBatchItems(long sequence, int count) {
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
                if (order == null || lane.pendingReservationSequence(order.orderId()) != sequence) continue;
                ReservationRuntime reservation = lane.reservation(order.orderId());
                owner.accountRollback().captureBalanceBefore(batch.userId, reservation.assetId());
                lane.completePendingReservation(order.orderId(), sequence);
                owner.accountRollback().captureBalanceAfter(lane, batch.userId, reservation.assetId());
                owner.changedOrder(order.orderId());
                owner.changedReservation(order.orderId());
                owner.changedUser(batch.userId);
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
        owner.accountRollback().captureReservationBefore(orderId);
        if (pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already indexed as pending");
        }
        int nextTotalPendingReservations = Math.addExact(totalPendingReservations, 1);
        owner.onLane(userId, lane -> {
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || reservation.userId() != userId) {
                throw new IllegalStateException("pending reservation is missing");
            }
            owner.accountRollback().captureBalanceBefore(userId, reservation.assetId());
            lane.markPendingReservation(orderId, coreSequence);
            owner.accountRollback().captureBalanceAfter(lane, userId, reservation.assetId());
            return null;
        });
        indexPendingReservation(userId, orderId, coreSequence, nextTotalPendingReservations);
    }

    /** Register a completed Lane admission without reading any live Account Lane table. */
    public void collectControlReservation(OrderRuntime order, long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0 || order == null || order.userId() <= 0)
            throw new IllegalStateException("control reservation receipt is missing");
        long orderId = order.orderId();
        long userId = order.userId();
        if (pendingReservationUsers.containsKey(orderId))
            throw new IllegalStateException("control reservation receipt was collected twice");
        indexPendingReservation(userId, orderId, coreSequence, Math.incrementExact(totalPendingReservations));
        // pollControlLanes already adopted the frozen order buffer. Registering this
        // identity must not copy the same order into a second Owner buffer.
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        owner.markBalancesChanged();
    }

    public void indexPendingReservation(long userId, long orderId, long coreSequence,
                                         int nextTotalPendingReservations) {
        pendingReservationsBySequence.add(coreSequence, orderId);
        pendingReservationUsers.put(orderId, userId);
        pendingReservationCountsByUser.addToValue(userId, 1);
        totalPendingReservations = nextTotalPendingReservations;
    }

    public void completePendingReservation(long userId, long orderId, long coreSequence) {
        owner.assertOwner();
        owner.accountRollback().captureUserBefore(userId);
        owner.accountRollback().captureOrderBefore(orderId);
        owner.accountRollback().captureReservationBefore(orderId);
        int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
        if (nextTotalPendingReservations < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        requirePendingReservationIndex(orderId, coreSequence, userId);
        PendingReservationCompletion completion = owner.onLane(userId, accountLane -> {
            ReservationRuntime reservation = accountLane.reservation(orderId);
            if (reservation != null) owner.accountRollback().captureBalanceBefore(userId, reservation.assetId());
            accountLane.clientKeysByOrderId().forEach(orderId, clientKey -> owner.accountRollback().captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            if (reservation != null) owner.accountRollback().captureBalanceAfter(accountLane, userId, reservation.assetId());
            return new PendingReservationCompletion(reservation);
        });
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        if (completion.reservation() != null) owner.markBalancesChanged();
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    public void completePendingReservations(long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        PendingBatch batch = batches.get(coreSequence);
        if (batch != null) { completeBatchRemainder(coreSequence, batch); return; }
        long firstOrderId = pendingReservationsBySequence.firstOrderId(coreSequence);
        if (firstOrderId == 0) return;
        // A normal single-order command is the hot case. Complete it through the existing
        // scalar path instead of materialising a one-element long[] and two temporary lists.
        if (!pendingReservationsBySequence.hasAdditional(coreSequence)) {
            long userId = pendingReservationUsers.getOrDefault(firstOrderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            completePendingReservation(userId, firstOrderId, coreSequence);
            return;
        }
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
            AccountLaneState lane = owner.currentLaneCommandScope();
            for (PendingReservationBatchCompletion completion : completions) {
                if (owner.topology().accountLaneId(completion.userId()) != laneId) continue;
                lane.completePendingReservation(completion.orderId(), coreSequence);
                owner.accountRollback().captureBalanceAfter(lane, completion.userId(), completion.reservation().assetId());
            }
            return null;
        });
        for (PendingReservationBatchCompletion completion : completions) {
            owner.changedOrder(completion.orderId());
            owner.changedReservation(completion.orderId());
            owner.changedUser(completion.userId());
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
            owner.accountRollback().captureUserBefore(ref.userId());
            owner.accountRollback().captureOrderBefore(ref.orderId());
            owner.accountRollback().captureReservationBefore(ref.orderId());
            requirePendingReservationIndex(ref.orderId(), coreSequence, ref.userId());
            PendingReservationBatchCompletion completion = owner.onLane(ref.userId(), lane -> {
                ReservationRuntime reservation = lane.reservation(ref.orderId());
                if (reservation != null) owner.accountRollback().captureBalanceBefore(ref.userId(), reservation.assetId());
                lane.clientKeysByOrderId().forEach(ref.orderId(),
                        clientKey -> owner.accountRollback().captureClientOrderBefore(ref.userId(), clientKey));
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

    public void unindexPendingReservation(long orderId, long coreSequence, long userId,
                                           int nextTotalPendingReservations) {
        requirePendingReservationIndex(orderId, coreSequence, userId);
        pendingReservationsBySequence.remove(coreSequence, orderId);
        pendingReservationUsers.remove(orderId);
        int nextUserCount = Math.subtractExact(pendingReservationCountsByUser.get(userId), 1);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(userId);
        else pendingReservationCountsByUser.put(userId, nextUserCount);
        totalPendingReservations = nextTotalPendingReservations;
    }

    /**
     * 撮合计划一次性撤销同一 sequence 的镜像预留。按用户合并计数更新，避免每个成交都
     * 对 pendingReservationCountsByUser 做 get/put，并避免重复维护 totalPendingReservations。
     */
    public void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        if (plan == null || !pendingReservationsBySequence.containsKey(plan.coreSequence())) return;
        LongIntHashMap removedByUser = matcherRemovedByUserScratch;
        removedByUser.clear();
        int removed = 0;
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (!pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            long userId = pendingReservationUsers.getOrDefault(orderId, 0);
            if (userId == 0) throw new IllegalStateException("matcher pending reservation owner is missing");
            removedByUser.addToValue(userId, 1);
            removed++;
        }
        if (removed == 0) return;
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (!pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            pendingReservationsBySequence.remove(plan.coreSequence(), orderId);
            pendingReservationUsers.remove(orderId);
        }
        removedByUser.forEachKeyValue((userId, count) -> {
            int next = Math.subtractExact(pendingReservationCountsByUser.get(userId), count);
            if (next == 0) pendingReservationCountsByUser.removeKey(userId);
            else if (next > 0) pendingReservationCountsByUser.put(userId, next);
            else throw new IllegalStateException("pending reservation counters are inconsistent");
        });
        totalPendingReservations = Math.subtractExact(totalPendingReservations, removed);
        removedByUser.clear();
    }

    public boolean pendingReservation(long orderId, long userId) {
        owner.assertOwner();
        AccountLaneState scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation query crossed its owner lane");
            }
            return scoped.pendingReservation(orderId);
        }
        if (pendingReservationUsers.getOrDefault(orderId, 0) == userId) return true;
        // Batch receipts already retain their admitted order array. Keeping a second
        // orderId -> admissionSequence map in the Owner publication view only duplicated
        // this state and forced a hash update on every Lane publication.
        for (PendingBatch batch : batches.values()) {
            if (batch.userId != userId) continue;
            for (int index = 0; index < batch.count; index++) {
                OrderRuntime order = batch.orders[index];
                if (order != null && order.orderId() == orderId) return true;
            }
        }
        return false;
    }

    public long pendingReservedUnits(long userId, int assetId) {
        owner.assertOwner();
        return owner.onLaneReadOnly(owner.topology().accountLaneId(userId), lane -> lane.pendingReservedUnits(userId, assetId));
    }

    public int pendingReservationCount(long userId) {
        owner.assertOwner();
        AccountLaneState scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation count crossed its owner lane");
            }
            return scoped.pendingReservationCount(userId);
        }
        return pendingReservationCountsByUser.get(userId);
    }

    public int pendingReservationCount() {
        owner.assertOwner();
        return totalPendingReservations;
    }

    public boolean hasPendingReservations() {
        owner.assertOwner();
        return totalPendingReservations != 0;
    }

    public void assertPendingReservationCounts() {
        if (owner.accountLanesStarted() && !owner.ownerLaneAccess) {
            int tracked = pendingReservationUsers.size();
            for (PendingBatch batch : batches.values()) tracked = Math.addExact(tracked, batch.remaining);
            if (tracked != totalPendingReservations
                    || pendingReservationCountsByUser.sum() != totalPendingReservations)
                throw new IllegalStateException("Owner pending reservation routing counts differ");
            return;
        }
        int lanePendingReservations = 0;
        for (int laneId = 0; laneId < owner.accountLaneCount(); laneId++) {
            int currentLaneId = laneId;
            lanePendingReservations = Math.addExact(lanePendingReservations,
                    owner.onLane(currentLaneId, AccountLaneState::pendingReservationCount));
        }
        if (lanePendingReservations != totalPendingReservations) {
            throw new IllegalStateException("pending reservation counters differ from account lanes, lane="
                    + lanePendingReservations + ", total=" + totalPendingReservations);
        }
    }

    static final class PendingReservationSequenceIndex {
        /** 无订单结果共用的空数组，不得写入。 */
        static final long[] EMPTY_ORDER_IDS = new long[0];

        /** 每个命令序号的首个预留订单，避免单元素集合分配。 */
        final Long2LongHashMap firstOrderBySequence;
        /** 同一命令的其余预留订单，仅多订单场景使用。 */
        final LongObjectHashMap<LongHashSet> additionalOrdersBySequence;

        PendingReservationSequenceIndex(int initialCapacity) {
            firstOrderBySequence = new Long2LongHashMap(initialCapacity, 0.65f, 0);
            additionalOrdersBySequence = new LongObjectHashMap<>();
        }

        long firstOrderId(long coreSequence) {
            return firstOrderBySequence.get(coreSequence);
        }

        boolean hasAdditional(long coreSequence) {
            LongHashSet additional = additionalOrdersBySequence.get(coreSequence);
            return additional != null && !additional.isEmpty();
        }

        void add(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.get(coreSequence);
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
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == orderId) return orderId != 0;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            return additionalOrderIds != null && additionalOrderIds.contains(orderId);
        }

        long[] orderIds(long coreSequence) {
            long firstOrderId = firstOrderBySequence.get(coreSequence);
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
            long firstOrderId = firstOrderBySequence.get(coreSequence);
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
                firstOrderBySequence.remove(coreSequence);
                return;
            }
            // The set is known to be non-empty.  Use its primitive iterator directly so
            // promoting a replacement reservation does not create a predicate/adapter.
            long promotedOrderId = additionalOrderIds.longIterator().next();
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

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public int totalPendingReservations() { return totalPendingReservations; }

    public long pendingUser(long key) { return pendingReservationUsers.get(key); }

    public boolean hasPendingUser(long key) { return pendingReservationUsers.containsKey(key); }
    public boolean pendingUserIndexEmpty() { return pendingReservationUsers.isEmpty(); }
    public PendingBatch batch(long key) { return batches.get(key); }

    public boolean hasBatch(long key) { return batches.containsKey(key); }
    public boolean containsSequence(long sequence) { return pendingReservationsBySequence.containsKey(sequence); }
    public boolean sequenceIndexEmpty() { return pendingReservationsBySequence.isEmpty(); }
    public long[] pendingOrderIds(long sequence) { return pendingReservationsBySequence.orderIds(sequence); }
    public void clearRestoredPendingReservations() {
        pendingReservationsBySequence.clear(); batches.clear(); pendingReservationUsers.clear();
        pendingReservationCountsByUser.clear(); totalPendingReservations = 0; assertPendingReservationCounts();
    }

}
