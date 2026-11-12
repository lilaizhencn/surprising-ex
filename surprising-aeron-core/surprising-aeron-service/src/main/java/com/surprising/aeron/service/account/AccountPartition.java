package com.surprising.aeron.service.account;

import com.surprising.aeron.service.risk.RiskMarginCalculator;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.LeverageLookup;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderCheckService.OrderCheckSummary;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.DerivativeTradeSettlementService;
import com.surprising.aeron.service.settlement.SpotTradeSettlementService;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 一个账户分片的权威数据与派生索引。工作线程或受控交接后的持有者串行修改用户、余额、订单、预留和持仓。部分入口会核对或认领线程，许多直接读写方法只依赖调用方已进入命令或查询保护范围，返回的表和缓冲并非线程安全副本。 */
public final class AccountPartition {
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int laneId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int queueCapacity;
    private final LongHashSet userIds;
    final LongObjectHashMap<UserAccount> users;
    final LongObjectHashMap<IntObjectHashMap<AccountBalance>> balances;
    /** 订单编号到分片权威订单的表，采用后移删除避免删除标记累积触发整表重建。 */
    final Long2ObjectHashMap<Order> orders;
    final Long2ObjectHashMap<OrderFundsReservation> reservations;
    final LongObjectHashMap<Position> positions;
    @Accessors(fluent = true, chain = false)
    @Getter
    final ClientOrderIdRegistry clientIdentities;
    final AccountExposureIndex orderCheckOrderIndex;
    final LaneColdState cold = new LaneColdState(this);
    long revision;
    long appliedSequence;
    long committedSequence;
    long commandOperations;
    long matcherSettlementOperations;
    /** 当前实际拥有分片状态的线程，受控交接期间允许为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    volatile Thread owner;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    TradeSettlement.Changes matcherSettlementChanges;

    /** 创建一个分片的账户状态和派生索引，检查编号与队列容量，并计算初始空状态哈希。此时不启动工作线程。 */
    public AccountPartition(int laneId, int queueCapacity) {
        this(laneId, queueCapacity,
                com.surprising.aeron.config.CoreRuntimeConfiguration.standalone().laneInitialEntities());
    }

    public AccountPartition(int laneId, int queueCapacity, int initialEntityCapacity) {
        if (initialEntityCapacity < 16) throw new IllegalArgumentException("initial entity capacity must be at least 16");
        this.userIds = new LongHashSet(initialEntityCapacity);
        this.users = new LongObjectHashMap<>(initialEntityCapacity);
        this.balances = new LongObjectHashMap<>(initialEntityCapacity);
        this.orders = new Long2ObjectHashMap<>(initialEntityCapacity, 0.65f, false);
        this.reservations = new Long2ObjectHashMap<>(initialEntityCapacity, 0.65f, false);
        this.positions = new LongObjectHashMap<>(initialEntityCapacity);
        this.clientIdentities = new ClientOrderIdRegistry(this, initialEntityCapacity);
        this.orderCheckOrderIndex = new AccountExposureIndex(this, initialEntityCapacity);
        if (laneId < 0 || laneId >= Long.SIZE || queueCapacity <= 0) {
            throw new IllegalArgumentException("invalid account lane");
        }
        this.laneId = laneId;
        this.queueCapacity = queueCapacity;
        this.cold.localStateHash = computeStateHash();
        this.cold.localFundsHash = computeFundsHash();
    }

    public int orderCheckIndexCapacity() { return orderCheckOrderIndex.orderCheckIndexCapacity; }
    public void orderCheckIndexCapacity(int orderCheckIndexCapacity) { orderCheckOrderIndex.orderCheckIndexCapacity = orderCheckIndexCapacity; }
    public RiskScanner.LiquidationBatch riskLiquidationBatch() { return cold.riskLiquidationBatch; }
    public void riskLiquidationBatch(RiskScanner.LiquidationBatch riskLiquidationBatch) { cold.riskLiquidationBatch = riskLiquidationBatch; }
    public ClientOrderIdRegistry.OrderKeyIndex clientKeysByOrderId() { return clientIdentities.clientKeysByOrderId(); }
    public LeverageLookup leverageLookup() { return cold.leverageLookup; }
    public IdIndex.LookupScratch identityLookupScratch() { return cold.identityLookupScratch; }
    public DerivativeTradeSettlementService.DerivativeSettlementAccumulator derivativeSettlementAccumulator() { return cold.derivativeSettlementAccumulator; }
    public SpotTradeSettlementService.SpotSettlementAccumulator spotSettlementAccumulator() { return cold.spotSettlementAccumulator; }
    public RiskScanner.PositionRiskScratch positionRiskScratch() { return cold.positionRiskScratch; }
    public DerivativeTradeSettlementService.FillCursor singleFillCursor() { return cold.singleFillCursor; }
    public boolean commandScopeActive() { return cold.commandScopeActive; }
    public void commandScopeActive(boolean commandScopeActive) { cold.commandScopeActive = commandScopeActive; }
    public boolean queryScopeActive() { return cold.queryScopeActive; }
    public void queryScopeActive(boolean queryScopeActive) { cold.queryScopeActive = queryScopeActive; }

    /** 在串行写入约定下保留见过的最大队列深度并返回它。这里只用 volatile，不是支持多写者竞争的原子取最大值。 */
    public int updateQueueHighWaterMark(int depth) {
        int current = cold.queueHighWaterMark;
        if (depth > current) {
            cold.queueHighWaterMark = depth;
            return depth;
        }
        return current;
    }

    /** 按触发编号写入条件记录并维护用户成员索引。 */
    public CoreTriggerOrderState putTrigger(CoreTriggerOrderState value) {
        CoreTriggerOrderState previous = cold.triggerOrders.put(value.triggerOrderId(), value);
        if (previous != null && previous.userId() != value.userId()) unindexTrigger(previous);
        cold.triggerIdsByUser.getIfAbsentPut(value.userId(), LongHashSet::new).add(value.triggerOrderId());
        return previous;
    }

    /** 删除条件记录并解除对应用户关联，返回原记录。 */
    public CoreTriggerOrderState removeTrigger(long id) {
        CoreTriggerOrderState previous = cold.triggerOrders.remove(id);
        if (previous != null) unindexTrigger(previous);
        return previous;
    }

    private void unindexTrigger(CoreTriggerOrderState value) {
        LongHashSet ids = cold.triggerIdsByUser.get(value.userId());
        if (ids == null) throw new IllegalStateException("trigger user index is missing");
        ids.remove(value.triggerOrderId());
        if (ids.isEmpty()) cold.triggerIdsByUser.remove(value.userId());
    }

    /** 接收 Owner 交回的已脱离业务表的订单，交给单生产者单消费者回收队列。 */
    public void returnRetiredOrder(Order order) {
        cold.retiredOrders.offer(order);
    }

    /** 在所属线程收取已跨过最终退休水位的订单，清除旧引用后放入有界复用池，再准备本次订单。 */
    public Order preparedOrder(com.surprising.product.api.ProductLine product, long userId, OrderCommand.ResolvedPlaceOrder intent,
            java.util.UUID commandId, int symbolId, long timestamp, long position, long retiredOwnerSequence) {
        assertOwner();
        Order ready;
        while ((ready = cold.retiredOrders.peek()) != null && ready.retiredAfterSequence() <= retiredOwnerSequence) {
            cold.retiredOrders.poll();
            ready.clearRetiredReferences();
            if (cold.reusableOrders.size() < 4_096) cold.reusableOrders.addLast(ready);
        }
        return Order.preparePooled(cold.reusableOrders.pollLast(), product, userId, intent, commandId, symbolId, timestamp, position);
    }

    /** 借用当前命令收据，逐用户应用结算带来的修订增量并写入收据。禁止嵌套遍历，结束后释放借用。 */
    public void applyUserRevisions(LongLongHashMap revisions, AccountPartitionCommit.Delta changes) {
        assertOwner();
        if (cold.userRevisionChanges != null) throw new IllegalStateException("nested Lane user revision traversal");
        cold.userRevisionChanges = java.util.Objects.requireNonNull(changes, "user revision receipt");
        try { revisions.forEachKeyValue(cold.advanceUserRevision); }
        finally { cold.userRevisionChanges = null; }
    }

    void advanceUserRevision(long userId, long count) {
        UserAccount current = users.get(userId);
        if (current == null)
            throw new IllegalStateException("runtime user disappeared during matcher settlement: " + userId);
        long nextRevision = Math.addExact(current.revision(), count);
        UserAccount advanced = current.laneValue().withState(nextRevision, current.positionMode());
        users.put(userId, advanced);
        cold.userRevisionChanges.putUser(userId, advanced);
    }

    public void releaseOrderClientIdentities(IdIndex identities, long orderId, long userId) {
        clientIdentities.releaseOrderClientIdentities(identities, orderId, userId);
    }

    public void removeClientOrdersForOrder(long userId, long orderId) {
        clientIdentities.removeClientOrdersForOrder(userId, orderId);
    }

    public void bindWorker(Thread worker) {
        assertOwner();
        if (worker == null || worker.getState() != Thread.State.NEW)
            throw new IllegalArgumentException("Account Lane worker must not have started");
        owner = worker;
    }

    public void releaseOwnerForHandoff() {
        assertOwner();
        owner = null;
    }

    public void assertOwner() {
        Thread current = Thread.currentThread();
        Thread bound = owner;
        if (bound == null && LaneColdState.OWNER.compareAndSet(this, null, current)) return;
        if (owner != current)
            throw new IllegalStateException("account lane " + laneId + " is bound to another thread");
    }

    public void recordCommandOperation() {
        commandOperations++;
    }

    public void recordSettlementOperation() {
        matcherSettlementOperations++;
    }

    public void writeMetrics(com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder,
                      int depth, int capacity, int highWater) {
        long committed = observedCommittedSequence();
        long applied = Math.max(committed, observedAppliedSequence());
        encoder.writeLane(laneId, observedRevision(), applied, committed,
                depth, capacity, highWater, 0, 0);
        encoder.writeOperations(laneId, LaneColdState.EMPTY_METRIC_OPERATIONS, LaneColdState.EMPTY_METRIC_OPERATIONS, LaneColdState.EMPTY_METRIC_OPERATIONS, LaneColdState.EMPTY_METRIC_OPERATIONS);
        long commands = observedOperationCount(0);
        if (commands != 0) {
            encoder.addOperation(laneId, 0, commands, 0, 0, 0);
        }
        long settlements = observedOperationCount(1);
        if (settlements != 0) {
            encoder.addOperation(laneId, 1, settlements, 0, 0, 0);
        }
    }

    public long observedRevision() { return (long) LaneColdState.METRIC_REVISION.getOpaque(this); }
    public long observedAppliedSequence() { return (long) LaneColdState.METRIC_APPLIED.getOpaque(this); }
    public long observedCommittedSequence() { return (long) LaneColdState.METRIC_COMMITTED.getOpaque(this); }
    public long observedOperationCount(int operation) {
        return switch (operation) {
            case 0 -> (long) LaneColdState.METRIC_COMMANDS.getOpaque(this);
            case 1 -> (long) LaneColdState.METRIC_SETTLEMENTS.getOpaque(this);
            case 2, 3 -> 0;
            default -> throw new IllegalArgumentException("invalid Lane metric operation");
        };
    }

    public long revision() { assertOwner(); return revision; }
    public long appliedSequence() { assertOwner(); return appliedSequence; }
    public long committedSequence() { assertOwner(); return committedSequence; }
    public long localStateHash() { assertOwner(); return cold.localStateHash; }
    public long localFundsHash() { assertOwner(); return cold.localFundsHash; }
    public boolean owns(long userId) { assertOwner(); return userIds.contains(userId); }
    boolean hasUserId(long userId) { return userIds.contains(userId); }

    public void registerUser(long userId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        userIds.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        userIds.remove(userId);
        orderCheckOrderIndex.removeUnusedUser(userId);
    }

    public void markPendingReservation(long orderId, long coreSequence) {
        assertOwner();
        if (orderId <= 0 || coreSequence <= committedSequence) {
            throw new IllegalArgumentException("invalid pending reservation identity");
        }
        if (orderCheckOrderIndex.pendingReservationSequences.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already pending");
        }
        OrderFundsReservation reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int nextUserCount = Math.addExact(orderCheckOrderIndex.pendingReservationCountsByUser.get(reservation.userId()), 1);
        int nextTotal = Math.addExact(orderCheckOrderIndex.totalPendingReservations, 1);
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long nextUnits = Math.addExact(previousUnits, reservation.reservedUnits());
        orderCheckOrderIndex.pendingReservationSequences.put(orderId, coreSequence);
        orderCheckOrderIndex.pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        orderCheckOrderIndex.setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        orderCheckOrderIndex.totalPendingReservations = nextTotal;
    }

    public void completePendingReservation(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, true);
    }

    public void requirePendingReservationCompletion(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, false);
    }

    private void pendingReservationCompletion(long orderId, long coreSequence, boolean apply) {
        assertOwner();
        long pendingSequence = orderCheckOrderIndex.pendingReservationSequences.get(orderId);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        OrderFundsReservation reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int userCount = orderCheckOrderIndex.pendingReservationCountsByUser.get(reservation.userId());
        int nextUserCount = Math.max(0, userCount - 1);
        int nextTotal = Math.max(0, orderCheckOrderIndex.totalPendingReservations - 1);
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long nextUnits = Math.max(0L, previousUnits - reservation.reservedUnits());
        if (!apply) return;
        orderCheckOrderIndex.pendingReservationSequences.remove(orderId);
        if (nextUserCount == 0) orderCheckOrderIndex.pendingReservationCountsByUser.removeKey(reservation.userId());
        else orderCheckOrderIndex.pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        orderCheckOrderIndex.setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        orderCheckOrderIndex.totalPendingReservations = nextTotal;
    }

    public void replacePendingReservation(OrderFundsReservation previous, OrderFundsReservation replacement) {
        assertOwner();
        if (!orderCheckOrderIndex.pendingReservationSequences.containsKey(previous.orderId())) return;
        if (previous.orderId() != replacement.orderId() || previous.userId() != replacement.userId()) {
            throw new IllegalStateException("pending reservation owner cannot change");
        }
        long currentUnits = pendingReservedUnits(previous.userId(), previous.assetId());
        long remainingUnits = Math.subtractExact(currentUnits, previous.reservedUnits());
        if (remainingUnits < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        if (previous.assetId() == replacement.assetId()) {
            long nextUnits = Math.addExact(remainingUnits, replacement.reservedUnits());
            orderCheckOrderIndex.setPendingReservedUnits(previous.userId(), previous.assetId(), nextUnits);
        } else {
            long replacementUnits = Math.addExact(
                    pendingReservedUnits(replacement.userId(), replacement.assetId()), replacement.reservedUnits());
            orderCheckOrderIndex.setPendingReservedUnits(previous.userId(), previous.assetId(), remainingUnits);
            orderCheckOrderIndex.setPendingReservedUnits(replacement.userId(), replacement.assetId(), replacementUnits);
        }
    }

    public boolean pendingReservation(long orderId) {
        assertOwner();
        return orderCheckOrderIndex.pendingReservationSequences.containsKey(orderId);
    }

    public long pendingReservationSequence(long orderId) {
        assertOwner();
        return orderCheckOrderIndex.pendingReservationSequences.get(orderId);
    }

    public void updatePendingReservationInPlace(OrderFundsReservation reservation, long previousReservedUnits) {
        assertOwner();
        if (reservation == null || previousReservedUnits < 0) throw new IllegalArgumentException("invalid reservation update");
        if (!orderCheckOrderIndex.pendingReservationSequences.containsKey(reservation.orderId())) return;
        long current = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long next = Math.addExact(Math.subtractExact(current, previousReservedUnits), reservation.reservedUnits());
        if (next < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        orderCheckOrderIndex.setPendingReservedUnits(reservation.userId(), reservation.assetId(), next);
    }

    public long pendingReservedUnits(long userId, int assetId) {
        LongLongHashMap unitsByUser = orderCheckOrderIndex.pendingReservedUnitsByAsset.get(assetId);
        return unitsByUser == null ? 0 : unitsByUser.get(userId);
    }

    public int pendingReservationCount(long userId) {
        assertOwner();
        return orderCheckOrderIndex.pendingReservationCountsByUser.get(userId);
    }

    public int pendingReservationCount() {
        assertOwner();
        return orderCheckOrderIndex.totalPendingReservations;
    }

    boolean hasPendingReservations() {
        assertOwner();
        return orderCheckOrderIndex.totalPendingReservations != 0;
    }

    public java.util.List<Long> preMatchingCancellations(long userId, int symbolId,
            com.surprising.aeron.protocol.CoreOrderSide side, long matchingPrice,
            long excludedOrderId, long newOrderId, java.util.List<Long> closeCapacity) {
        assertOwner();
        var active = orderCheckOrderIndex.activeOrderIdsByUser.get(userId);
        if (active == null || active.isEmpty()) return closeCapacity;
        LongHashSet cancellations = null;
        var iterator = active.iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextValue();
            if (id == excludedOrderId || id == newOrderId || pendingReservation(id)) continue;
            Order order = orders.get(id);
            if (order == null || order.symbolId() != symbolId || order.side() == side) continue;
            boolean crosses = side == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? matchingPrice >= order.priceTicks() : matchingPrice <= order.priceTicks();
            if (crosses) {
                if (cancellations == null) {
                    cancellations = new LongHashSet();
                    for (long closeId : closeCapacity) cancellations.add(closeId);
                }
                cancellations.add(id);
            }
        }
        if (cancellations == null) return closeCapacity;
        long[] ids = cancellations.toArray();
        java.util.Arrays.sort(ids);
        return com.surprising.aeron.support.collection.ImmutableLongArrayList.takeOwnership(ids);
    }

    public OrderCheckSummary orderCheckSummary(int symbolId, long userId, CorePositionSide positionSide,
            CoreOrderSide side, CoreMarginMode conflictingMarginMode) {
        assertOwner();
        if (symbolId < 0) throw new IllegalArgumentException("invalid admission instrumentId");
        orderCheckOrderIndex.symbolId = symbolId;
        return orderCheckOrderIndex.inspect(userId, positionSide, side, conflictingMarginMode);
    }

    public void putOrder(Order order) {
        assertOwner();
        if (order == null) throw new IllegalArgumentException("order is required");
        order = order.laneValue();
        Order previous = orders.put(order.orderId(), order);
        orderCheckOrderIndex.replaceActiveOrder(previous, order);
        orderCheckOrderIndex.replace(previous, order);
    }

    public Order removeOrder(long orderId) {
        assertOwner();
        Order previous = orders.remove(orderId);
        orderCheckOrderIndex.replaceActiveOrder(previous, null);
        orderCheckOrderIndex.replace(previous, null);
        return previous;
    }

    public Order updateOrderInPlace(long orderId, long executed, long remaining, long feeUnits,
                                    CoreOrderStatus nextStatus, long nextRevision,
                                    long commitTimestamp, long commitPosition) {
        assertOwner();
        Order order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        boolean previousActive = AccountExposureIndex.active(order);
        long previousRemaining = order.remainingQuantitySteps();
        order.applyFillInPlace(executed, remaining, feeUnits, nextStatus, nextRevision,
                commitTimestamp, commitPosition);
        if (previousActive && !AccountExposureIndex.active(order)) orderCheckOrderIndex.removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && AccountExposureIndex.active(order)) orderCheckOrderIndex.addActiveOrder(order.userId(), order.orderId());
        orderCheckOrderIndex.updateInPlace(order, previousActive, previousRemaining);
        return order;
    }

    public Order updateOrderStatusInPlace(long orderId, CoreOrderStatus nextStatus, long nextRevision,
                                          long commitTimestamp, long commitPosition) {
        Order order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        boolean previousActive = AccountExposureIndex.active(order);
        order.applyStatusInPlace(nextStatus, nextRevision, commitTimestamp, commitPosition);
        if (previousActive && !AccountExposureIndex.active(order)) orderCheckOrderIndex.removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && AccountExposureIndex.active(order)) orderCheckOrderIndex.addActiveOrder(order.userId(), order.orderId());
        orderCheckOrderIndex.updateInPlace(order, previousActive, order.remainingQuantitySteps());
        return order;
    }

    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        var orderIds = orderCheckOrderIndex.activeOrderIdsByUser.get(userId);
        if (orderIds == null) return 0;
        long total = 0;
        var iterator = orderIds.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            Order order = orders.get(orderId);
            if (order != null && order.symbolId() == symbolId && order.reduceOnly()
                    && order.positionSide() == positionSide && order.side() == side
                    && order.marginMode() == marginMode) {
                total = Math.addExact(total, order.remainingQuantitySteps());
            }
        }
        return total;
    }

    public void addReservationId(long userId, long orderId) {
        orderCheckOrderIndex.addReservationId(userId, orderId);
    }

    public void removeReservationId(long userId, long orderId) {
        orderCheckOrderIndex.removeReservationId(userId, orderId);
    }

    public void applied(long coreSequence) {
        applied(0, coreSequence);
    }

    public void applied(int shard, long coreSequence) {
        assertOwner();
        requireApplySequence(shard, coreSequence);
        if (shard >= 0 && shard < cold.appliedSequencesByShard.length) {
            cold.appliedSequencesByShard[shard] = coreSequence;
        }
        appliedSequence = Math.max(appliedSequence, coreSequence);
        revision = Math.incrementExact(revision);
    }

    void requireApplySequence(long coreSequence) {
        requireApplySequence(0, coreSequence);
    }

    void requireApplySequence(int shard, long coreSequence) {
        assertOwner();
        if (shard >= 0 && shard < cold.appliedSequencesByShard.length) {
            long lastShardSeq = cold.appliedSequencesByShard[shard];
            if (coreSequence <= lastShardSeq) {
                throw new IllegalStateException("account lane apply is out of order: lane=" + laneId
                        + ", shard=" + shard
                        + ", incoming=" + coreSequence + ", applied=" + lastShardSeq
                        + ", committed=" + cold.committedSequencesByShard[shard]);
            }
        } else if (coreSequence <= appliedSequence) {
            throw new IllegalStateException("account lane apply is out of order: lane=" + laneId
                    + ", incoming=" + coreSequence + ", applied=" + appliedSequence
                    + ", committed=" + committedSequence);
        }
    }

    public void committed(long coreSequence) {
        committed(0, coreSequence);
    }

    public void committed(int shard, long coreSequence) {
        assertOwner();
        requireCommit(shard, coreSequence);
        if (shard >= 0 && shard < cold.committedSequencesByShard.length) {
            cold.committedSequencesByShard[shard] = coreSequence;
        }
        committedSequence = Math.max(committedSequence, coreSequence);
    }

    void requireCommit(long coreSequence) {
        requireCommit(0, coreSequence);
    }

    void requireCommit(int shard, long coreSequence) {
        assertOwner();
        if (shard >= 0 && shard < cold.committedSequencesByShard.length) {
            long lastShardCommit = cold.committedSequencesByShard[shard];
            long lastShardApply = cold.appliedSequencesByShard[shard];
            if (coreSequence < lastShardCommit || coreSequence > lastShardApply) {
                throw new IllegalStateException("account lane commit is out of order: lane=" + laneId
                        + ", shard=" + shard + ", incoming=" + coreSequence
                        + ", committed=" + lastShardCommit + ", applied=" + lastShardApply);
            }
        } else if (coreSequence < committedSequence || coreSequence > appliedSequence) {
            throw new IllegalStateException("account lane commit is out of order");
        }
    }

    public void readFence(long coreSequence) {
        assertOwner();
        requireReadFence(coreSequence);
        advanceReadFence(coreSequence);
    }

    public void requireReadFence(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane read fence crossed uncommitted work");
        }
    }

    public void advanceReadFence(long coreSequence) {
        assertOwner();
        appliedSequence = coreSequence;
        committedSequence = coreSequence;
        java.util.Arrays.fill(cold.appliedSequencesByShard, coreSequence);
        java.util.Arrays.fill(cold.committedSequencesByShard, coreSequence);
    }

    public void restore(long revision, long appliedSequence, long committedSequence,
                 long localStateHash, long localFundsHash, LongHashSet restoredUsers) {
        assertOwner();
        if (revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0 || restoredUsers == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        restoreMetadata(revision, appliedSequence, committedSequence, localStateHash, localFundsHash);
        userIds.clear();
        userIds.addAll(restoredUsers);
    }

    private void restoreMetadata(long revision, long appliedSequence, long committedSequence,
            long localStateHash, long localFundsHash) {
        if (revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        this.revision = revision;
        this.appliedSequence = appliedSequence;
        this.committedSequence = committedSequence;
        java.util.Arrays.fill(cold.appliedSequencesByShard, appliedSequence);
        java.util.Arrays.fill(cold.committedSequencesByShard, committedSequence);
        this.cold.localStateHash = localStateHash;
        this.cold.localFundsHash = localFundsHash;
        orderCheckOrderIndex.clearPendingReservations();
    }

    public AccountLaneSnapshot snapshot(long fenceSequence) {
        assertOwner();
        requireSnapshot(fenceSequence);
        appliedSequence = fenceSequence;
        committedSequence = fenceSequence;
        java.util.List<Long> usersList = new java.util.ArrayList<>(userIds.size());
        userIds.forEach(usersList::add);
        usersList.sort(Long::compare);
        return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                cold.localStateHash, cold.localFundsHash, usersList);
    }

    public void requireSnapshot(long fenceSequence) {
        assertOwner();
        if (!orderCheckOrderIndex.pendingReservationSequences.isEmpty()) {
            throw new IllegalStateException("pending reservation cannot cross snapshot fence");
        }
        if (fenceSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane snapshot crossed an incomplete commit");
        }
    }

    public void restore(AccountLaneSnapshot snapshot) {
        assertOwner();
        if (snapshot == null || snapshot.laneId() != laneId) {
            throw new IllegalArgumentException("account lane snapshot route mismatch");
        }
        restoreMetadata(snapshot.revision(), snapshot.appliedSequence(), snapshot.committedSequence(),
                snapshot.localStateHash(), snapshot.localFundsHash());
        userIds.clear();
        for (int index = 0; index < snapshot.userIds().size(); index++) {
            userIds.add(snapshot.userIds().get(index));
        }
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    private long computeStateHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long userSum = 0;
        var userIterator = userIds.longIterator();
        while (userIterator.hasNext()) {
            long userId = userIterator.next();
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), mixValue(0, users.get(userId)));
        }
        hash = mix(hash, userSum);
        hash = mix(hash, mixMapUnordered(orders));
        hash = mix(hash, mixMapUnordered(reservations));
        hash = mix(hash, mixPrimitiveMapUnordered(positions));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.liquidations));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.riskSnapshots));
        long leverageSum = 0;
        for (Map.Entry<CoreLeverageKey, Long> entry : cold.leverages.entrySet()) {
            leverageSum += mix(mixValue(0, entry.getKey()), entry.getValue());
        }
        hash = mix(hash, leverageSum);
        hash = mix(hash, mixPrimitiveMapUnordered(cold.algoOrders));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.triggerOrders));
        return hash == 0 ? 1 : hash;
    }

    private <T> long mixPrimitiveMapUnordered(LongObjectHashMap<T> values) {
        long sum = 0;
        var iterator = values.keySet().longIterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    private long computeFundsHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long userSum = 0;
        var userIterator = balances.keySet().longIterator();
        while (userIterator.hasNext()) {
            long userId = userIterator.next();
            long assetSum = 0;
            IntObjectHashMap<AccountBalance> userBalances = balances.get(userId);
            if (userBalances != null) {
                var assetIterator = userBalances.keySet().intIterator();
                while (assetIterator.hasNext()) {
                    int assetId = assetIterator.next();
                    AccountBalance balance = userBalances.get(assetId);
                    long itemHash = mix(mix(mix(0x9e3779b97f4a7c15L, assetId), balance.availableUnits()), balance.lockedUnits());
                    assetSum += itemHash;
                }
            }
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), assetSum);
        }
        hash = mix(hash, userSum);
        return hash == 0 ? 1 : hash;
    }

    public void rebuildLocalHashes() {
        assertOwner();
        cold.localStateHash = computeStateHash();
        cold.localFundsHash = computeFundsHash();
    }

    private long mixMapUnordered(Long2ObjectHashMap<?> values) {
        long sum = 0;
        var iterator = values.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    private static long mixValue(long hash, Object value) {
        if (value == null) return mix(hash, 0);
        if (value instanceof CharSequence seq) {
            long mixed = mix(hash, seq.length());
            for (int index = 0; index < seq.length(); index++) {
                char character = seq.charAt(index);
                mixed ^= (character & 0xff);
                if (character > 0x7f) {
                    mixed ^= (character >>> 8);
                }
                mixed *= 0x100000001b3L;
            }
            return mixed;
        }
        return mix(hash, Integer.toUnsignedLong(value.hashCode()));
    }

    public OrderFundsReservation reservation(long key) { return reservations.get(key); }
    public OrderFundsReservation removeReservationValue(long key) { return reservations.remove(key); }
    public UserAccount user(long key) { return users.get(key); }
    public UserAccount storeUser(long key, UserAccount value) { return users.put(key, value); }
    public Order order(long key) { return orders.get(key); }
    public void forEachUserId(org.eclipse.collections.api.block.procedure.primitive.LongProcedure consumer) { users.forEachKey(consumer); }
    public Position position(long key) { return positions.get(key); }
    public Long leverage(Object key) { return cold.leverages.get(key); }
    public RiskScanner.LiquidationOrder liquidation(long key) { return cold.liquidations.get(key); }
    public boolean anyLiquidation(org.eclipse.collections.api.block.predicate.Predicate<? super RiskScanner.LiquidationOrder> predicate) { return cold.liquidations.anySatisfy(predicate); }
    public com.surprising.aeron.service.risk.RiskMarginCalculator.Snapshot riskSnapshot(long key) { return cold.riskSnapshots.get(key); }
    public Long storeLeverage(CoreLeverageKey key, Long value) { return cold.leverages.put(key, value); }
    public com.surprising.aeron.model.CoreAlgoOrderState algoOrder(long key) { return cold.algoOrders.get(key); }
    public boolean anyAlgoOrder(org.eclipse.collections.api.block.predicate.Predicate<? super com.surprising.aeron.model.CoreAlgoOrderState> predicate) { return cold.algoOrders.anySatisfy(predicate); }
    public com.surprising.aeron.model.CoreAlgoOrderState storeAlgoOrder(long key, com.surprising.aeron.model.CoreAlgoOrderState value) { return cold.algoOrders.put(key, value); }
    public CoreTriggerOrderState triggerOrder(long key) { return cold.triggerOrders.get(key); }
    public void forEachAlgoOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.model.CoreAlgoOrderState> consumer) { cold.algoOrders.forEachKeyValue(consumer); }
    public void forEachTriggerOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachKeyValue(consumer); }
    public com.surprising.aeron.protocol.TransferFundsCommand pendingTransfer(long key) { return cold.pendingTransfers.get(key); }
    public void forEachPendingTransfer(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.protocol.TransferFundsCommand> consumer) { cold.pendingTransfers.forEachKeyValue(consumer); }
    public void clearPendingTransferValues() { cold.pendingTransfers.clear(); }
    public com.surprising.aeron.protocol.TransferFundsCommand storePendingTransfer(long key, com.surprising.aeron.protocol.TransferFundsCommand value) { return cold.pendingTransfers.put(key, value); }
    public boolean hasPendingTransfer(long key) { return cold.pendingTransfers.containsKey(key); }
    public com.surprising.aeron.protocol.TransferFundsCommand removePendingTransferValue(long key) { return cold.pendingTransfers.remove(key); }
    public void clearLeverageValues() { cold.leverages.clear(); }
    public void clearAlgoOrderValues() { cold.algoOrders.clear(); }
    public void clearTriggerOrderValues() { cold.triggerOrders.clear(); }
    public OrderFundsReservation storeReservation(long key, OrderFundsReservation value) { return reservations.put(key, value); }
    public Position storePosition(long key, Position value) { return positions.put(key, value); }
    public RiskScanner.LiquidationOrder storeLiquidation(long key, RiskScanner.LiquidationOrder value) { return cold.liquidations.put(key, value); }
    public com.surprising.aeron.service.risk.RiskMarginCalculator.Snapshot storeRiskSnapshot(long key, com.surprising.aeron.service.risk.RiskMarginCalculator.Snapshot value) { return cold.riskSnapshots.put(key, value); }
    public RiskScanner.LiquidationOrder removeLiquidationValue(long key) { return cold.liquidations.remove(key); }
    @SuppressWarnings("deprecation")
    public void forEachOrder(org.agrona.collections.LongObjConsumer<Order> consumer) { orders.longForEach(consumer); }
    @SuppressWarnings("deprecation")
    public void forEachReservation(org.agrona.collections.LongObjConsumer<OrderFundsReservation> consumer) { reservations.longForEach(consumer); }
    public boolean hasOrder(long key) { return orders.containsKey(key); }
    public void forEachOrderEntry(java.util.function.BiConsumer<? super Long, ? super Order> consumer) { orders.forEach(consumer); }
    public void forEachPositionValue(org.eclipse.collections.api.block.procedure.Procedure<? super Position> consumer) { positions.forEachValue(consumer); }
    public void forEachTriggerOrderValue(org.eclipse.collections.api.block.procedure.Procedure<? super CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachValue(consumer); }
    public boolean leverageValuesEmpty() { return cold.leverages.isEmpty(); }
    public IntObjectHashMap<AccountBalance> storeUserBalances(long key, IntObjectHashMap<AccountBalance> value) { return balances.put(key, value); }
    public IntObjectHashMap<AccountBalance> userBalances(long key) { return balances.get(key); }
    public org.agrona.collections.LongHashSet reservationIds(long key) { return orderCheckOrderIndex.reservationIdsByUser.get(key); }
    public LongHashSet positionKeys(long key) { return orderCheckOrderIndex.positionKeysByUser.get(key); }
    public LongHashSet removePositionKeys(long key) { return orderCheckOrderIndex.positionKeysByUser.remove(key); }
    public org.agrona.collections.LongHashSet activeOrderIds(long key) { return orderCheckOrderIndex.activeOrderIdsByUser.get(key); }
    public java.util.HashSet<CoreLeverageKey> storeLeverageKeys(long key, java.util.HashSet<CoreLeverageKey> value) { return cold.leverageKeysByUser.put(key, value); }
    public java.util.HashSet<CoreLeverageKey> leverageKeys(long key) { return cold.leverageKeysByUser.get(key); }
    public void clearLeverageKeys() { cold.leverageKeysByUser.clear(); }
    public LongObjectHashMap<LongArrayList> storeSymbolPositionKeys(int key, LongObjectHashMap<LongArrayList> value) { return orderCheckOrderIndex.positionKeysBySymbolAndUser.put(key, value); }
    public LongObjectHashMap<LongArrayList> symbolPositionKeys(int key) { return orderCheckOrderIndex.positionKeysBySymbolAndUser.get(key); }
    public LongObjectHashMap<LongArrayList> removeSymbolPositionKeys(int key) { return orderCheckOrderIndex.positionKeysBySymbolAndUser.remove(key); }
    public IntObjectHashMap<LongObjectHashMap<Long>> storeActiveLiquidations(long key, IntObjectHashMap<LongObjectHashMap<Long>> value) { return cold.activeLiquidationIndex.put(key, value); }
    public IntObjectHashMap<LongObjectHashMap<Long>> activeLiquidations(long key) { return cold.activeLiquidationIndex.get(key); }
    public IntObjectHashMap<LongObjectHashMap<Long>> removeActiveLiquidations(long key) { return cold.activeLiquidationIndex.remove(key); }
    public LongHashSet triggerIds(long key) { return cold.triggerIdsByUser.get(key); }
    public void clearTriggerIds() { cold.triggerIdsByUser.clear(); }
    public Long2LongHashMap storeUserClientOrders(long key, Long2LongHashMap value) { return clientIdentities.clientOrderIndex.put(key, value); }
    public Long2LongHashMap userClientOrders(long key) { return clientIdentities.clientOrderIndex.get(key); }

    public void indexPositionKey(long userId, long key) {
        LongHashSet keys = orderCheckOrderIndex.positionKeysByUser.get(userId);
        if (keys == null) { keys = new LongHashSet(16); orderCheckOrderIndex.positionKeysByUser.put(userId, keys); }
        keys.add(key);
    }

    public void unindexPositionKey(long userId, long key) {
        LongHashSet keys = orderCheckOrderIndex.positionKeysByUser.get(userId);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) orderCheckOrderIndex.positionKeysByUser.remove(userId);
    }

    public LongObjectHashMap<UserAccount> usersSnapshot() {
        LongObjectHashMap<UserAccount> copy = new LongObjectHashMap<>(users.size());
        users.forEachKeyValue((userId, user) -> copy.put(userId, user.publicationValue()));
        return copy;
    }

    public LongObjectHashMap<IntObjectHashMap<AccountBalance>> balancesSnapshot() {
        return AccountBook.copyAllBalances(balances);
    }

    public LongObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap> clientOrdersSnapshot() {
        return AccountBook.copyClientOrderIndex(clientIdentities.clientOrderIndex);
    }

    public LongObjectHashMap<Position> positionsSnapshot() { return new LongObjectHashMap<>(positions); }
    public LongObjectHashMap<RiskScanner.LiquidationOrder> liquidationsSnapshot() { return new LongObjectHashMap<>(cold.liquidations); }
    public LongObjectHashMap<com.surprising.aeron.service.risk.RiskMarginCalculator.Snapshot> riskSnapshotsSnapshot() { return new LongObjectHashMap<>(cold.riskSnapshots); }
    public java.util.TreeMap<CoreLeverageKey, Long> leveragesSnapshot() { return new java.util.TreeMap<>(cold.leverages); }

    public void requireAlgoChildOrderCheck(long userId, com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder child) {
        String key = child.clientOrderId();
        if (key == null || !key.startsWith("algo-")) return;
        int separator = key.lastIndexOf('-');
        long id; int slice;
        try {
            id = Long.parseLong(key.substring(5, separator));
            slice = Integer.parseInt(key.substring(separator + 1));
        } catch (RuntimeException invalid) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_CHILD", "invalid algo child identity");
        }
        var parent = algoOrder(id);
        if (parent == null || !("algo-" + id + "-" + slice).equals(key)
                || parent.userId() != userId || parent.statusCode() >= 2
                || !parent.instrumentId().equals(child.instrumentId()) || parent.side() != child.side()
                || slice != parent.childOrderIds().size() || parent.pendingClosedChildId() != 0
                || child.quantitySteps() > parent.childQuantitySteps()
                || child.quantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_CHILD", "algo parent cannot admit this child");
        }
        var active = order(parent.currentOrderId());
        if (active != null && !active.status().terminal())
            throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child is still active");
    }

    public void retireAlgoChild(IdIndex identities,
                                Order child,
                                AccountPartitionCommit.Delta changes) {
        assertOwner();
        String client = child.clientOrderId();
        if (client == null || !client.startsWith("algo-")) return;
        int separator = client.lastIndexOf('-');
        if (separator <= 5) return;
        final long parentId;
        final int slice;
        try {
            parentId = Long.parseLong(client.substring(5, separator));
            slice = Integer.parseInt(client.substring(separator + 1));
        } catch (NumberFormatException malformed) { return; }
        var parent = algoOrder(parentId);
        if (parent == null || parent.userId() != child.userId() || slice < 0
                || !parent.instrumentId().equals(identities.instrumentId(child.symbolId()))) return;
        boolean linked = slice < parent.childOrderIds().size() && parent.childOrderIds().get(slice) == child.orderId();
        boolean pending = slice == parent.childOrderIds().size()
                && (parent.pendingClosedChildId() == 0 || parent.pendingClosedChildId() == child.orderId());
        if ((!linked && !pending) || child.executedQuantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) return;
        long executed = Math.addExact(parent.retiredExecutedQuantitySteps(), child.executedQuantitySteps());
        var updated = parent.withRetiredProgress(executed, pending ? child.orderId() : parent.pendingClosedChildId());
        storeAlgoOrder(updated.algoOrderId(), updated);
        changes.putAlgo(updated.algoOrderId(), updated);
    }
}
