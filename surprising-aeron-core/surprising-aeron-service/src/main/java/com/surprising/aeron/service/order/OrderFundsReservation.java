package com.surprising.aeron.service.order;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.settlement.TradeSettlement;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import static com.surprising.aeron.service.core.AccountBook.*;

public final class OrderFundsReservation {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long orderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int symbolId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private ReservationKind kind;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int assetId;
    /** 本记录的冻结总额上限，区别于 reservedUnits 返回的剩余额度。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long totalReservedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long releasedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long consumedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long orderQuantitySteps;
    private boolean mutable;
    private boolean orderSlot;
    private boolean publicationSlot;

    /** 校验身份、总额、累计量和订单数量，创建一个普通可变运行态冻结值。 */
    public OrderFundsReservation(long orderId, long userId, int symbolId, ReservationKind kind,
                              int assetId, long totalReservedUnits,
                              long releasedUnits, long consumedUnits, long orderQuantitySteps) {
        validate(orderId, userId, symbolId, kind, assetId, totalReservedUnits,
                releasedUnits, consumedUnits, orderQuantitySteps);
        this.orderId = orderId; this.userId = userId; this.symbolId = symbolId;
        this.kind = kind; this.assetId = assetId;
        this.totalReservedUnits = totalReservedUnits; this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits; this.orderQuantitySteps = orderQuantitySteps;
        this.mutable = true;
    }

    private static void validate(long orderId, long userId, int symbolId, ReservationKind kind,
                                 int assetId, long totalReservedUnits, long releasedUnits,
                                 long consumedUnits, long orderQuantitySteps) {
        if (orderId <= 0 || userId <= 0 || symbolId < 0 || kind == null
                || assetId < 0 || totalReservedUnits <= 0 || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > totalReservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid runtime reservation");
        }
    }

    /** 为权威订单常驻槽准备本次冻结：首次创建并标记订单槽，复用时要求目标确为可变订单槽，重绑身份并把累计量清零。 */
    public static OrderFundsReservation prepareForOrderSlot(OrderFundsReservation target, long orderId, long userId,
            int symbolId, ReservationKind kind, int assetId, long reservedUnits, long quantitySteps) {
        validate(orderId, userId, symbolId, kind, assetId, reservedUnits, 0, 0, quantitySteps);
        if (target == null) {
            target = new OrderFundsReservation(orderId, userId, symbolId, kind, assetId,
                    reservedUnits, 0, 0, quantitySteps);
            target.orderSlot = true;
            return target;
        }
        if (!target.orderSlot || !target.mutable)
            throw new IllegalArgumentException("reservation is not an authoritative order slot");
        target.orderId = orderId; target.userId = userId; target.symbolId = symbolId;
        target.kind = kind; target.assetId = assetId; target.totalReservedUnits = reservedUnits;
        target.releasedUnits = target.consumedUnits = 0;
        target.orderQuantitySteps = quantitySteps;
        return target;
    }

    /** 返回当前剩余额度，即总冻结减累计释放和消耗，不是最初冻结总额。 */
    public long reservedUnits() { return Math.subtractExact(totalReservedUnits, Math.addExact(releasedUnits, consumedUnits)); }

    /** 复制当前金额和身份为独立发布值，并标记为非可变用途。 */
    public OrderFundsReservation snapshot() {
        OrderFundsReservation copy = new OrderFundsReservation(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
        copy.mutable = false;
        return copy;
    }

    /** 复制当前状态到变更缓冲独占的发布槽。首次建立槽，后续只能覆盖非可变且非订单槽的发布目标，调用方先确认消费者已释放。 */
    public OrderFundsReservation copyIntoPublicationSlot(OrderFundsReservation target) {
        if (target == null) {
            target = snapshot();
            target.publicationSlot = true;
            return target;
        }
        if (!target.publicationSlot || target.mutable || target.orderSlot)
            throw new IllegalArgumentException("reservation copy target is not a publication slot");
        target.orderId = orderId; target.userId = userId; target.symbolId = symbolId;
        target.kind = kind; target.assetId = assetId; target.totalReservedUnits = totalReservedUnits;
        target.releasedUnits = releasedUnits; target.consumedUnits = consumedUnits;
        target.orderQuantitySteps = orderQuantitySteps;
        return target;
    }

    /** 只清理发布槽的订单、用户身份和类别引用，保留存储等待下一次覆盖。清理后不能当有效冻结读取。 */
    public void clearPublicationSlot() {
        if (!publicationSlot || mutable || orderSlot)
            throw new IllegalStateException("reservation is not a publication slot");
        orderId = userId = 0;
        kind = null;
    }

    /** 已有可变 Lane 值直接返回，发布值则复制成新的可变运行态值，不把发布对象当权威对象接管。 */
    public OrderFundsReservation laneValue() {
        return mutable ? this : new OrderFundsReservation(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    /** 可变值先复制快照，已有发布值直接返回，供原交接边界按只读契约使用。 */
    public OrderFundsReservation publicationValue() { return mutable ? snapshot() : this; }

    /** 在所属 Lane 原位增加累计释放，允许 0，负数或超过当前剩余额度立即拒绝。 */
    public void releaseInPlace(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        releasedUnits = Math.addExact(releasedUnits, units);
    }

    /** 把累计消耗单调推进到指定值，不能回退或使释放加消耗超过总额。 */
    public void setConsumedInPlace(long nextConsumed) {
        if (nextConsumed < consumedUnits || Math.addExact(releasedUnits, nextConsumed) > totalReservedUnits)
            throw new IllegalArgumentException("invalid runtime consumption");
        consumedUnits = nextConsumed;
    }

    /** 非原位释放路径：0 返回自身，正数返回累计释放增加的新可变值，原对象不变。 */
    public OrderFundsReservation release(long units) {
        if (units < 0 || units > reservedUnits()) throw new IllegalArgumentException("invalid runtime release");
        if (units == 0) return this;
        return new OrderFundsReservation(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, Math.addExact(releasedUnits, units), consumedUnits, orderQuantitySteps);
    }

    /** 只按身份、类别、数量和金额比较业务值，不把槽类型和可变用途标记作为业务差异。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OrderFundsReservation value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && assetId == value.assetId
                && totalReservedUnits == value.totalReservedUnits && releasedUnits == value.releasedUnits
                && consumedUnits == value.consumedUnits && orderQuantitySteps == value.orderQuantitySteps
                && kind == value.kind;
    }

    /** 按与 equals 相同的业务字段组合哈希值，忽略槽类型标记。 */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(orderId, userId, symbolId, kind, assetId,
                totalReservedUnits, releasedUnits, consumedUnits, orderQuantitySteps);
    }

    /** 输出订单、用户、资产和冻结金额，供诊断资金差异。 */
    @Override
    public String toString() {
        return "OrderFundsReservation[orderId=" + orderId + ", userId=" + userId + ", assetId=" + assetId
                + ", totalReservedUnits=" + totalReservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + "]";
    }

public static final class Snapshot {
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long orderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String instrumentId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final ReservationKind kind;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final String asset;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long reservedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long releasedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long consumedUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long orderQuantitySteps;

    /** 校验正数身份、冻结总额和数量，以及累计释放加消耗不超过总额，再校验币对并规范化资产文本。 */
    public Snapshot(long orderId, String instrumentId, ReservationKind kind,
                            String asset, long reservedUnits, long releasedUnits, long consumedUnits,
                            long orderQuantitySteps) {
        if (orderId <= 0 || kind == null || reservedUnits <= 0
                || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > reservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid order reservation");
        }
        this.orderId = orderId;
        this.instrumentId = InstrumentId.requireValid(instrumentId);
        this.kind = kind;
        this.asset = AssetBalance.normalizeAsset(asset);
        this.reservedUnits = reservedUnits;
        this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits;
        this.orderQuantitySteps = orderQuantitySteps;
    }

    /** 按所有身份、类别、资产和金额字段比较业务值，不依赖对象是否为同一实例。 */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Snapshot value
                && orderId == value.orderId
                && kind == value.kind && reservedUnits == value.reservedUnits
                && releasedUnits == value.releasedUnits && consumedUnits == value.consumedUnits
                && orderQuantitySteps == value.orderQuantitySteps
                && instrumentId.equals(value.instrumentId) && asset.equals(value.asset);
    }

    /** 按与 equals 一致的身份和金额字段组合哈希值。 */
    @Override
    public int hashCode() {
        int hash = Long.hashCode(orderId);
        hash = 31 * hash + instrumentId.hashCode();
        hash = 31 * hash + kind.hashCode();
        hash = 31 * hash + asset.hashCode();
        hash = 31 * hash + Long.hashCode(reservedUnits);
        hash = 31 * hash + Long.hashCode(releasedUnits);
        hash = 31 * hash + Long.hashCode(consumedUnits);
        return 31 * hash + Long.hashCode(orderQuantitySteps);
    }

    /** 输出订单、币对、类别、资产和各金额，供日志与断言定位冻结差异。 */
    @Override
    public String toString() {
        return "OrderFundsReservation.Snapshot[orderId=" + orderId + ", instrumentId=" + instrumentId
                + ", kind=" + kind + ", asset=" + asset
                + ", reservedUnits=" + reservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + ", orderQuantitySteps=" + orderQuantitySteps + "]";
    }

    /** 用精确算术从总额扣除累计释放及消耗，返回仍可使用的冻结额度。 */
    public long remainingUnits() {
        return Math.subtractExact(reservedUnits, Math.addExact(releasedUnits, consumedUnits));
    }
}

@RequiredArgsConstructor
public static final class PendingTracker {
    final AccountBook owner;

    final PendingReservationSequenceIndex pendingReservationsBySequence =
            new PendingReservationSequenceIndex(4_096);

    final Long2LongHashMap pendingReservationUsers = new Long2LongHashMap(4_096, 0.65f, 0);

    /** 各用户已收集的单项及批次待完成数量，Owner 按完成回执更新。 */
    final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(4_096);

    private final LongIntHashMap matcherRemovedByUserScratch = new LongIntHashMap(64);

    /** 供 Core 内部在原所有权或有效读取边界内读取全局路由计数，此方法本身不切换线程。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    int totalPendingReservations;

    /** 按 Core 命令序号保留的批次借用收据，不逐订单复制到 Owner 单项路由表。 */
    final LongObjectHashMap<PendingBatch> batches = new LongObjectHashMap<>();

    /** 一批已准入订单的借用收据，保存原命令数组及剩余预留数。Owner 按完成项递减，归零后移除，必须先移除收据再让原批次归还订单租约。 */
    public static final class PendingBatch {
        @Accessors(fluent = true, chain = false)
        @Getter
        final long userId;
        /** 借用原命令的获准订单数组，收据必须在原数组租约回收前移除。 */
        final Order[] orders;
        @Accessors(fluent = true, chain = false)
        @Getter
        final int count;
        /** 批次仍未完成的项数，只有 Owner 按实际 Lane 回执递减。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        int remaining;
        /** 借用指定用户的原订单数组并记录有效前缀，剩余数初始等于本批准入项数。 */
        PendingBatch(long userId, Order[] orders, int count) {
            this.userId = userId; this.orders = orders; this.count = count; this.remaining = count;
        }

    /** 按有效前缀下标借用原批次订单，不复制订单实体。 */
    public Order order(int index) { return orders[index]; }

}

    /** 拒绝与已有批次或单项序号重复登记，保存一次借用收据，并累计用户及全局待完成数量。 */
    public void registerBatch(long sequence, long userId, Order[] orders, int count) {
        if (batches.containsKey(sequence) || pendingReservationsBySequence.containsKey(sequence))
            throw new IllegalStateException("batch reservation was registered twice");
        batches.put(sequence, new PendingBatch(userId, orders, count));
        pendingReservationCountsByUser.addToValue(userId, count);
        totalPendingReservations = Math.addExact(totalPendingReservations, count);
    }

    /** 按 Lane 回执递减本批、用户和全局剩余数，归零移除对应收据或用户计数，不能完成超过本批剩余量的项。 */
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

    private void completeBatchRemainder(long sequence, PendingBatch batch) {
        int completed = owner.onLane(batch.userId, lane -> {
            int count = 0;
            for (int i = 0; i < batch.count; i++) {
                Order order = batch.orders[i];
                if (order == null || lane.pendingReservationSequence(order.orderId()) != sequence) continue;
                OrderFundsReservation reservation = lane.reservation(order.orderId());
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

    /** 消费已经完成的 Lane 准入订单回执，只登记序号、用户和变化标记，不回读活动账户表，也不重复复制已接管的订单缓冲。 */
    public void collectControlReservation(Order order, long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0 || order == null || order.userId() <= 0)
            throw new IllegalStateException("control reservation receipt is missing");
        long orderId = order.orderId();
        long userId = order.userId();
        if (pendingReservationUsers.containsKey(orderId))
            throw new IllegalStateException("control reservation receipt was collected twice");
        indexPendingReservation(userId, orderId, coreSequence, Math.incrementExact(totalPendingReservations));
        // 这里只登记身份路由，不能再向第二个 Owner 缓冲复制相同订单。
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        owner.markBalancesChanged();
    }

    /** 把一笔预留加入序号索引、订单归属和用户计数，再采用调用方已算好的全局总数。 */
    public void indexPendingReservation(long userId, long orderId, long coreSequence,
                                         int nextTotalPendingReservations) {
        pendingReservationsBySequence.add(coreSequence, orderId);
        pendingReservationUsers.put(orderId, userId);
        pendingReservationCountsByUser.addToValue(userId, 1);
        totalPendingReservations = nextTotalPendingReservations;
    }

    /** 先捕获用户、订单和冻结回滚前值并校验路由，在所属 Lane 完成预留及余额前后捕获，再标记变化并移除 Owner 路由计数。 */
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
        OrderFundsReservation reservation = owner.onLane(userId, accountLane -> {
            OrderFundsReservation currentReservation = accountLane.reservation(orderId);
            if (currentReservation != null) owner.accountRollback().captureBalanceBefore(userId, currentReservation.assetId());
            accountLane.clientKeysByOrderId().forEach(orderId, clientKey -> owner.accountRollback().captureClientOrderBefore(userId, clientKey));
            accountLane.completePendingReservation(orderId, coreSequence);
            if (currentReservation != null) owner.accountRollback().captureBalanceAfter(accountLane, userId, currentReservation.assetId());
            return currentReservation;
        });
        owner.changedOrder(orderId);
        owner.changedReservation(orderId);
        owner.changedUser(userId);
        if (reservation != null) owner.markBalancesChanged();
        unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
    }

    /** 按命令完成全部剩余预留：批次走原批次收据，单项直接用标量路径，多项先整体预检再按所属 Lane 完成，最后逐项撤销路由。 */
    public void completePendingReservations(long coreSequence) {
        owner.assertOwner();
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        PendingBatch batch = batches.get(coreSequence);
        if (batch != null) { completeBatchRemainder(coreSequence, batch); return; }
        long firstOrderId = pendingReservationsBySequence.firstOrderId(coreSequence);
        if (firstOrderId == 0) return;
        if (!pendingReservationsBySequence.hasAdditional(coreSequence)) {
            long userId = pendingReservationUsers.getOrDefault(firstOrderId, 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            completePendingReservation(userId, firstOrderId, coreSequence);
            return;
        }
        long[] pending = pendingReservationsBySequence.orderIds(coreSequence);
        if (pending.length == 0) return;
        long[] userIds = new long[pending.length];
        for (int index = 0; index < pending.length; index++) {
            long userId = pendingReservationUsers.getOrDefault(pending[index], 0);
            if (userId == 0) throw new IllegalStateException("pending reservation owner is missing");
            userIds[index] = userId;
        }
        int remainingPendingReservations = totalPendingReservations;
        OrderFundsReservation[] reservations = new OrderFundsReservation[pending.length];
        long selectedLanes = 0;
        for (int index = 0; index < pending.length; index++) {
            long orderId = pending[index];
            long userId = userIds[index];
            if (orderId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("invalid pending reservation completion");
            }
            owner.accountRollback().captureUserBefore(userId);
            owner.accountRollback().captureOrderBefore(orderId);
            owner.accountRollback().captureReservationBefore(orderId);
            requirePendingReservationIndex(orderId, coreSequence, userId);
            reservations[index] = owner.onLane(userId, lane -> {
                OrderFundsReservation reservation = lane.reservation(orderId);
                if (reservation != null) owner.accountRollback().captureBalanceBefore(userId, reservation.assetId());
                lane.clientKeysByOrderId().forEach(orderId,
                        clientKey -> owner.accountRollback().captureClientOrderBefore(userId, clientKey));
                lane.requirePendingReservationCompletion(orderId, coreSequence);
                return reservation;
            });
            remainingPendingReservations = Math.subtractExact(remainingPendingReservations, 1);
            if (remainingPendingReservations < 0) {
                throw new IllegalStateException("pending reservation counters are inconsistent");
            }
            selectedLanes |= owner.topology().accountLaneMask(userId);
        }
        while (selectedLanes != 0) {
            int laneId = Long.numberOfTrailingZeros(selectedLanes);
            selectedLanes &= selectedLanes - 1;
            owner.onLane(laneId, lane -> {
                for (int index = 0; index < pending.length; index++) {
                    long userId = userIds[index];
                    if (owner.topology().accountLaneId(userId) != laneId) continue;
                    lane.completePendingReservation(pending[index], coreSequence);
                    owner.accountRollback().captureBalanceAfter(lane, userId, reservations[index].assetId());
                }
                lane.recordSettlementOperation();
                return null;
            });
        }
        for (int index = 0; index < pending.length; index++) {
            long orderId = pending[index];
            long userId = userIds[index];
            owner.changedOrder(orderId);
            owner.changedReservation(orderId);
            owner.changedUser(userId);
            owner.markBalancesChanged();
            int nextTotalPendingReservations = Math.subtractExact(totalPendingReservations, 1);
            unindexPendingReservation(orderId, coreSequence, userId, nextTotalPendingReservations);
        }
    }

    /** 同时核对订单所属用户和命令序号成员关系，Owner 路由与账户预期不一致就立即失败。 */
    void requirePendingReservationIndex(long orderId, long coreSequence, long userId) {
        long indexedUserId = pendingReservationUsers.getOrDefault(orderId, 0);
        if (indexedUserId != userId || !pendingReservationsBySequence.contains(coreSequence, orderId)) {
            throw new IllegalStateException("pending reservation index differs from account lane state");
        }
    }

    /** 要求路由一致后删除序号成员和订单归属，将用户数减一并在零时移除，再更新调用方准备的全局总数。 */
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

    /** 按已经确定的撮合结算计划撤销同序号的 Owner 路由。 */
    public void unindexMatcherPendingReservations(TradeSettlement.Plan plan) {
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

    /** 有当前 Lane 范围时读取真实待完成标记并禁止跨用户 Lane。Owner 无该范围时只查已登记路由及仍保留的批次订单输入。 */
    public boolean pendingReservation(long orderId, long userId) {
        owner.assertOwner();
        AccountPartition scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation query crossed its owner lane");
            }
            return scoped.pendingReservation(orderId);
        }
        if (pendingReservationUsers.getOrDefault(orderId, 0) == userId) return true;
        for (PendingBatch batch : batches.values()) {
            if (batch.userId != userId) continue;
            for (int index = 0; index < batch.count; index++) {
                Order order = batch.orders[index];
                if (order != null && order.orderId() == orderId) return true;
            }
        }
        return false;
    }

    /** 通过所属 Lane 的原只读边界查询用户某资产的待完成预留金额，不用 Owner 路由估算资金。 */
    public long pendingReservedUnits(long userId, int assetId) {
        owner.assertOwner();
        return owner.onLaneReadOnly(owner.topology().accountLaneId(userId), lane -> lane.pendingReservedUnits(userId, assetId));
    }

    /** 在当前 Lane 范围读取其权威用户计数，否则返回 Owner 已收集的该用户路由计数，禁止跨 Lane 读取。 */
    public int pendingReservationCount(long userId) {
        owner.assertOwner();
        AccountPartition scoped = owner.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != owner.topology().accountLaneId(userId)) {
                throw new IllegalStateException("pending reservation count crossed its owner lane");
            }
            return scoped.pendingReservationCount(userId);
        }
        return pendingReservationCountsByUser.get(userId);
    }

    /** 在原所有权要求下返回全部用户已登记的待完成预留总数。 */
    public int pendingReservationCount() {
        owner.assertOwner();
        return totalPendingReservations;
    }

    /** 活动 Lane 未交接给 Owner 时只核对 Owner 路由、批次剩余和用户总数。 */
    public void assertPendingReservationCounts() {
        if (owner.accountLanesStarted() && !owner.ownerLaneAccess()) {
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
                    owner.onLane(currentLaneId, AccountPartition::pendingReservationCount));
        }
        if (lanePendingReservations != totalPendingReservations) {
            throw new IllegalStateException("pending reservation counters differ from account lanes, lane="
                    + lanePendingReservations + ", total=" + totalPendingReservations);
        }
    }

    /** 命令序号到预留订单编号的 primitive 索引。 */
    static final class PendingReservationSequenceIndex {
        /** 无成员查询共用的空 long 数组，不得由调用方写入。 */
        static final long[] EMPTY_ORDER_IDS = new long[0];

        /** 每个命令序号直接保存的一笔首项订单编号，避免单项路径创建集合。 */
        final Long2LongHashMap firstOrderBySequence;
        final LongObjectHashMap<LongHashSet> additionalOrdersBySequence;

        /** 创建首项的 primitive 序号表与按需附加集合表，0 作为未找到的首项标记。 */
        PendingReservationSequenceIndex(int initialCapacity) {
            firstOrderBySequence = new Long2LongHashMap(initialCapacity, 0.65f, 0);
            additionalOrdersBySequence = new LongObjectHashMap<>();
        }

        /** 读取该命令序号直接保存的首个订单编号，没有成员返回 0。 */
        long firstOrderId(long coreSequence) {
            return firstOrderBySequence.get(coreSequence);
        }

        /** 检查该序号是否还保留首项之外的订单，用来选择单项标量完成路径。 */
        boolean hasAdditional(long coreSequence) {
            LongHashSet additional = additionalOrdersBySequence.get(coreSequence);
            return additional != null && !additional.isEmpty();
        }

        /** 首笔直接存入首项表，多笔才创建附加集合。 */
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

        /** 按首项表判断该命令序号是否有任何待完成成员。 */
        boolean containsKey(long coreSequence) {
            return firstOrderBySequence.containsKey(coreSequence);
        }

        /** 先比较非零首项，再查询附加集合，判断指定订单是否属于该命令。 */
        boolean contains(long coreSequence, long orderId) {
            long firstOrderId = firstOrderBySequence.get(coreSequence);
            if (firstOrderId == orderId) return orderId != 0;
            LongHashSet additionalOrderIds = additionalOrdersBySequence.get(coreSequence);
            return additionalOrderIds != null && additionalOrderIds.contains(orderId);
        }

        /** 导出当前序号成员，空结果共用只读空数组，单项创建一项数组。多项首项在前，其余沿用集合遍历顺序，不进行业务排序。 */
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

        /** 删除指定成员。 */
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
            long promotedOrderId = additionalOrderIds.longIterator().next();
            additionalOrderIds.remove(promotedOrderId);
            firstOrderBySequence.put(coreSequence, promotedOrderId);
            if (additionalOrderIds.isEmpty()) additionalOrdersBySequence.removeKey(coreSequence);
        }

        /** 根据首项表判断整个序号成员索引是否为空。 */
        boolean isEmpty() {
            return firstOrderBySequence.isEmpty();
        }

        /** 清空首项和附加集合两张索引，不执行任何账户资金变化。 */
        void clear() {
            firstOrderBySequence.clear();
            additionalOrdersBySequence.clear();
        }
    }

    /** 按订单编号直接读单项预留归属用户，未登记时返回 primitive 表的 0 标记。 */
    public long pendingUser(long key) { return pendingReservationUsers.get(key); }

    /** 判断单项预留归属表是否包含指定订单编号，批次收据另行管理。 */
    public boolean hasPendingUser(long key) { return pendingReservationUsers.containsKey(key); }
    /** 检查单项订单到用户路由表是否为空，不把它等同于所有批次均已完成。 */
    public boolean pendingUserIndexEmpty() { return pendingReservationUsers.isEmpty(); }
    /** 按命令序号借用当前批次收据，不存在时为 null。 */
    public PendingBatch batch(long key) { return batches.get(key); }

    /** 判断给定命令序号是否仍保留批次收据。 */
    public boolean hasBatch(long key) { return batches.containsKey(key); }
    /** 判断单项序号索引是否包含给定命令，批次收据使用独立表。 */
    public boolean containsSequence(long sequence) { return pendingReservationsBySequence.containsKey(sequence); }
    /** 检查单项序号索引是否为空，批次收据仍需单独检查。 */
    public boolean sequenceIndexEmpty() { return pendingReservationsBySequence.isEmpty(); }
    /** 导出给定命令在单项序号索引中的成员，沿用首项加附加集合的顺序。 */
    public long[] pendingOrderIds(long sequence) { return pendingReservationsBySequence.orderIds(sequence); }
    /** 清空恢复相关的单项、批次和用户路由并把总数归零，再按当前运行条件核对计数，调用方负责处于允许重建的边界。 */
    public void clearRestoredPendingReservations() {
        pendingReservationsBySequence.clear(); batches.clear(); pendingReservationUsers.clear();
        pendingReservationCountsByUser.clear(); totalPendingReservations = 0; assertPendingReservationCounts();
    }

}

}
