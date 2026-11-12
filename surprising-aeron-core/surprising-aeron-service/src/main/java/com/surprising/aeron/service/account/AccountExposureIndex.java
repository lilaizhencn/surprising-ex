package com.surprising.aeron.service.account;

import com.surprising.aeron.service.risk.RiskMarginCalculator;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.LeverageLookup;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.OrderCheckService.OrderCheckSummary;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.DerivativeTradeSettlementService;
import com.surprising.aeron.service.settlement.SpotTradeSettlementService;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import java.util.NavigableSet;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 账户分片的活跃订单敞口统计、预留待完成计数、持仓分组索引及用户分页游标。从活跃订单增量维护准入需要的数量和模式条数，避免逐次扫描订单。 */
public final class AccountExposureIndex {
    private final AccountPartition lane;
    /** 当前准入作用域首次创建用户成员集合时使用的容量提示，默认 2，批量结束后由外层恢复。 */
    int orderCheckIndexCapacity = 2;
    final LongObjectHashMap<org.agrona.collections.LongHashSet> activeOrderIdsByUser;
    final LongObjectHashMap<org.agrona.collections.LongHashSet> reservationIdsByUser;
    final LongObjectHashMap<LongHashSet> positionKeysByUser;
    final IntObjectHashMap<LongObjectHashMap<LongArrayList>> positionKeysBySymbolAndUser = new IntObjectHashMap<>();
    final Long2LongHashMap pendingReservationSequences;
    final LongIntHashMap pendingReservationCountsByUser;
    final IntObjectHashMap<LongLongHashMap> pendingReservedUnitsByAsset = new IntObjectHashMap<>();
    int totalPendingReservations;
    private final Long2ObjectHashMap<Int2ObjectHashMap<OrderCheckAggregate>> summariesByUser =
            new Long2ObjectHashMap<>();
    private final java.util.ArrayDeque<OrderCheckAggregate> emptyAggregates = new java.util.ArrayDeque<>();
    /** 每次 inspect 覆盖的准入统计返回槽，调用方不能保存为长期快照。 */
    private final OrderCheckSummary summary = new OrderCheckSummary();
    int symbolId;

    AccountExposureIndex(AccountPartition lane, int initialEntityCapacity) {
        this.lane = lane;
        this.activeOrderIdsByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.reservationIdsByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.positionKeysByUser = new LongObjectHashMap<>(initialEntityCapacity);
        this.pendingReservationSequences = new Long2LongHashMap(initialEntityCapacity, 0.65f, 0, false);
        this.pendingReservationCountsByUser = new LongIntHashMap(initialEntityCapacity);
    }

    /** 用外层选择的稠密合约编号读取准入汇总，把数量和冲突模式条数写入同一个复用返回槽。 */
    public OrderCheckSummary inspect(
            long userId, CorePositionSide positionSide,
            CoreOrderSide side, CoreMarginMode conflictingMarginMode) {
        Int2ObjectHashMap<OrderCheckAggregate> bySymbol = summariesByUser.get(userId);
        OrderCheckAggregate aggregate = bySymbol == null ? null : bySymbol.get(symbolId);
        return aggregate == null ? summary.set(0, 0, 0)
                : summary.set(aggregate.pending(positionSide, side), aggregate.reduceOnly(side),
                aggregate.marginMode(positionSide, conflictingMarginMode));
    }

    void updateInPlace(Order order, boolean previousActive, long previousRemaining) {
        boolean nextActive = active(order);
        if (previousActive && nextActive) {
            var bySymbol = summariesByUser.get(order.userId());
            var aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
            if (aggregate == null) throw new IllegalStateException("admission order index is missing");
            aggregate.replaceQuantity(previousRemaining, order.remainingQuantitySteps(), order);
            return;
        }
        if (previousActive) update(order, -1, previousRemaining);
        if (nextActive) update(order, 1, order.remainingQuantitySteps());
    }

    void replace(Order previous, Order replacement) {
        if (active(previous) && active(replacement)
                && previous.userId() == replacement.userId() && previous.symbolId() == replacement.symbolId()
                && previous.side() == replacement.side() && previous.positionSide() == replacement.positionSide()
                && previous.marginMode() == replacement.marginMode() && previous.reduceOnly() == replacement.reduceOnly()) {
            var bySymbol = summariesByUser.get(previous.userId());
            var aggregate = bySymbol == null ? null : bySymbol.get(previous.symbolId());
            if (aggregate == null) throw new IllegalStateException("admission order index is missing");
            aggregate.replaceQuantity(previous, replacement);
            return;
        }
        if (active(previous)) update(previous, -1);
        if (active(replacement)) update(replacement, 1);
    }

    private void update(Order order, int direction) {
        update(order, direction, order.remainingQuantitySteps());
    }

    private void update(Order order, int direction, long remainingQuantity) {
        Int2ObjectHashMap<OrderCheckAggregate> bySymbol = summariesByUser.get(order.userId());
        OrderCheckAggregate aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
        if (aggregate == null) {
            if (direction < 0) throw new IllegalStateException("admission order index is missing");
            aggregate = emptyAggregates.pollLast();
            if (aggregate == null) aggregate = new OrderCheckAggregate();
            aggregate.update(order, direction, remainingQuantity);
            bySymbol = bySymbol == null ? new Int2ObjectHashMap<>(8, 0.65f, false) : bySymbol;
            bySymbol.put(order.symbolId(), aggregate);
            summariesByUser.put(order.userId(), bySymbol);
            return;
        }
        aggregate.update(order, direction, remainingQuantity);
        if (aggregate.empty()) {
            bySymbol.remove(order.symbolId());
            if (emptyAggregates.size() < 256) emptyAggregates.addLast(aggregate);
            removeUnusedUser(order.userId());
        }
    }

    void removeUnusedUser(long userId) {
        var bySymbol = summariesByUser.get(userId);
        if (bySymbol != null && bySymbol.isEmpty() && !lane.hasUserId(userId)) summariesByUser.remove(userId);
    }

    void replaceActiveOrder(Order previous, Order replacement) {
        if (active(previous) && active(replacement) && previous.userId() == replacement.userId()) return;
        if (active(previous)) removeActiveOrder(previous.userId(), previous.orderId());
        if (active(replacement)) addActiveOrder(replacement.userId(), replacement.orderId());
    }

    void addActiveOrder(long userId, long orderId) {
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) {
            orderIds = new org.agrona.collections.LongHashSet(orderCheckIndexCapacity, 0.65f, true);
            activeOrderIdsByUser.put(userId, orderIds);
        }
        orderIds.add(orderId);
    }

    void removeActiveOrder(long userId, long orderId) {
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null || !orderIds.remove(orderId)) {
            throw new IllegalStateException("active order index is missing");
        }
    }

    void addReservationId(long userId, long orderId) {
        var ids = reservationIdsByUser.get(userId);
        if (ids == null) {
            ids = new org.agrona.collections.LongHashSet(orderCheckIndexCapacity, 0.65f, true);
            reservationIdsByUser.put(userId, ids);
        }
        ids.add(orderId);
    }

    void removeReservationId(long userId, long orderId) {
        var ids = reservationIdsByUser.get(userId);
        if (ids != null) ids.remove(orderId);
    }

    void setPendingReservedUnits(long userId, int assetId, long units) {
        LongLongHashMap users = pendingReservedUnitsByAsset.get(assetId);
        if (units == 0) {
            if (users != null) users.removeKey(userId);
        } else {
            if (users == null) {
                users = new LongLongHashMap();
                pendingReservedUnitsByAsset.put(assetId, users);
            }
            users.put(userId, units);
        }
    }

    void clearPendingReservations() {
        pendingReservationSequences.clear();
        pendingReservationCountsByUser.clear();
        pendingReservedUnitsByAsset.clear();
        totalPendingReservations = 0;
    }

    static boolean active(Order order) {
        return order != null && !order.status().terminal();
    }

    /** 单个用户、单个合约的活跃订单统计。 */
    static final class OrderCheckAggregate {
        private long netBuy;
        private long netSell;
        private long longBuy;
        private long longSell;
        private long shortBuy;
        private long shortSell;
        private long reduceBuy;
        private long reduceSell;
        private int netCross;
        private int netIsolated;
        private int longCross;
        private int longIsolated;
        private int shortCross;
        private int shortIsolated;
        private int orders;

        void replaceQuantity(Order previous, Order replacement) {
            replaceQuantity(previous.remainingQuantitySteps(), replacement.remainingQuantitySteps(), previous);
        }

        private void replaceQuantity(long previousRemaining, long nextRemaining, Order grouping) {
            CoreOrderSide side = grouping == null ? null : grouping.side();
            CorePositionSide positionSide = grouping == null ? null : grouping.positionSide();
            boolean reduce = grouping != null && grouping.reduceOnly();
            if (grouping == null) throw new IllegalStateException("in-place admission grouping is missing");
            long current = reduce ? reduceOnly(side) : pending(positionSide, side);
            long remaining = Math.subtractExact(current, previousRemaining);
            if (remaining < 0) throw new IllegalStateException("admission order index underflow");
            long next = Math.addExact(remaining, nextRemaining);
            if (reduce) assignReduceOnly(side, next);
            else assignPending(positionSide, side, next);
        }

        private void update(Order order, int direction, long remainingQuantity) {
            long quantityDelta = direction > 0 ? remainingQuantity : Math.negateExact(remainingQuantity);
            long currentQuantity = order.reduceOnly()
                    ? reduceOnly(order.side())
                    : pending(order.positionSide(), order.side());
            long nextQuantity = Math.addExact(currentQuantity, quantityDelta);
            int nextMargin = Math.addExact(
                    marginMode(order.positionSide(), order.marginMode()), direction);
            int nextOrders = Math.addExact(orders, direction);
            if (nextQuantity < 0 || nextMargin < 0 || nextOrders < 0) {
                throw new IllegalStateException("admission order index underflow");
            }
            if (order.reduceOnly()) assignReduceOnly(order.side(), nextQuantity);
            else assignPending(order.positionSide(), order.side(), nextQuantity);
            assignMarginMode(order.positionSide(), order.marginMode(), nextMargin);
            orders = nextOrders;
        }

        long pending(CorePositionSide positionSide, CoreOrderSide side) {
            return switch (positionSide) {
                case NET -> side == CoreOrderSide.BUY ? netBuy : netSell;
                case LONG -> side == CoreOrderSide.BUY ? longBuy : longSell;
                case SHORT -> side == CoreOrderSide.BUY ? shortBuy : shortSell;
            };
        }

        long reduceOnly(CoreOrderSide side) {
            return side == CoreOrderSide.BUY ? reduceBuy : reduceSell;
        }

        int marginMode(CorePositionSide positionSide, CoreMarginMode marginMode) {
            return switch (positionSide) {
                case NET -> marginMode == CoreMarginMode.CROSS ? netCross : netIsolated;
                case LONG -> marginMode == CoreMarginMode.CROSS ? longCross : longIsolated;
                case SHORT -> marginMode == CoreMarginMode.CROSS ? shortCross : shortIsolated;
            };
        }

        boolean empty() { return orders == 0; }

        private void assignPending(CorePositionSide positionSide, CoreOrderSide side, long quantity) {
            switch (positionSide) {
                case NET -> { if (side == CoreOrderSide.BUY) netBuy = quantity; else netSell = quantity; }
                case LONG -> { if (side == CoreOrderSide.BUY) longBuy = quantity; else longSell = quantity; }
                case SHORT -> { if (side == CoreOrderSide.BUY) shortBuy = quantity; else shortSell = quantity; }
            }
        }

        private void assignReduceOnly(CoreOrderSide side, long quantity) {
            if (side == CoreOrderSide.BUY) reduceBuy = quantity;
            else reduceSell = quantity;
        }

        private void assignMarginMode(CorePositionSide positionSide, CoreMarginMode marginMode, int count) {
            switch (positionSide) {
                case NET -> { if (marginMode == CoreMarginMode.CROSS) netCross = count; else netIsolated = count; }
                case LONG -> { if (marginMode == CoreMarginMode.CROSS) longCross = count; else longIsolated = count; }
                case SHORT -> { if (marginMode == CoreMarginMode.CROSS) shortCross = count; else shortIsolated = count; }
            }
        }
    }

    /** 资金费和到期结算共用的账户索引页。 */
    public record Page(ImmutableLongArrayList userIds, long nextCursorUserId, boolean complete) {
        /** 按全局用户编号选择排他游标之后的一页，并额外观察一条记录判断是否还有后页。 */
        public static Page select(Iterable<Long> indexedUserIds, long startCursorUserId, int limit) {
            if (limit <= 0) return new Page(ImmutableLongArrayList.empty(), 0, true);
            ImmutableLongArrayList.Builder selected = new ImmutableLongArrayArrayBuilder(limit).builder();
            Iterable<Long> usersAfterCursor = indexedUserIds;
            if (indexedUserIds instanceof NavigableSet<?> indexedSet) {
                @SuppressWarnings("unchecked")
                NavigableSet<Long> userIds = (NavigableSet<Long>) indexedSet;
                usersAfterCursor = userIds.tailSet(startCursorUserId, false);
            }
            for (Long userId : usersAfterCursor) {
                if (userId == null || userId <= startCursorUserId) continue;
                if (selected.size() == limit) {
                    return new Page(selected.freeze(), selected.last(), false);
                }
                selected.add(userId.longValue());
            }
            return new Page(selected.freeze(), 0, true);
        }

        private record ImmutableLongArrayArrayBuilder(int limit) {
            ImmutableLongArrayList.Builder builder() {
                return new ImmutableLongArrayList.Builder(Math.min(limit, 64));
            }
        }
    }
}

/** 与热订单、余额、预留和持仓分开的冷业务表与本分片复用工作状态集合。仍由同一分片线程或受控读取边界持有，不额外引入锁、线程或第二份资金状态。 */
final class LaneColdState {
    static final java.lang.invoke.VarHandle METRIC_REVISION = metricField("revision");
    static final java.lang.invoke.VarHandle METRIC_APPLIED = metricField("appliedSequence");
    static final java.lang.invoke.VarHandle METRIC_COMMITTED = metricField("committedSequence");
    static final java.lang.invoke.VarHandle METRIC_COMMANDS = metricField("commandOperations");
    static final java.lang.invoke.VarHandle METRIC_SETTLEMENTS = metricField("matcherSettlementOperations");
    static final java.lang.invoke.VarHandle OWNER;
    static final long[] EMPTY_METRIC_OPERATIONS = new long[com.surprising.aeron.protocol.CoreLaneMetricsView.OPERATION_TYPE_COUNT];

    static {
        try { OWNER = java.lang.invoke.MethodHandles.lookup().findVarHandle(AccountPartition.class, "owner", Thread.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static java.lang.invoke.VarHandle metricField(String name) {
        try { return java.lang.invoke.MethodHandles.lookup().findVarHandle(AccountPartition.class, name, long.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private final AccountPartition lane;
    final LongObjectHashMap<RiskScanner.LiquidationOrder> liquidations = new LongObjectHashMap<>();
    final LongObjectHashMap<IntObjectHashMap<LongObjectHashMap<Long>>> activeLiquidationIndex =
            new LongObjectHashMap<>();
    final LongObjectHashMap<com.surprising.aeron.service.risk.RiskMarginCalculator.Snapshot> riskSnapshots =
            new LongObjectHashMap<>();
    final java.util.Map<CoreLeverageKey, Long> leverages = new java.util.HashMap<>();
    final LongObjectHashMap<java.util.HashSet<CoreLeverageKey>> leverageKeysByUser = new LongObjectHashMap<>();
    final LongObjectHashMap<com.surprising.aeron.model.CoreAlgoOrderState> algoOrders = new LongObjectHashMap<>();
    final LongObjectHashMap<com.surprising.aeron.model.CoreTriggerOrderState> triggerOrders = new LongObjectHashMap<>();
    final LongObjectHashMap<LongHashSet> triggerIdsByUser = new LongObjectHashMap<>();
    final LongObjectHashMap<com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers = new LongObjectHashMap<>();
    RiskScanner.LiquidationBatch riskLiquidationBatch;
    final LeverageLookup leverageLookup = new LeverageLookup();
    final IdIndex.LookupScratch identityLookupScratch = new IdIndex.LookupScratch();
    /** 仅遍历用户修订时借用的命令收据，结束后清空，禁止嵌套借用。 */
    AccountPartitionCommit.Delta userRevisionChanges;
    final org.eclipse.collections.api.block.procedure.primitive.LongLongProcedure advanceUserRevision;
    final DerivativeTradeSettlementService.DerivativeSettlementAccumulator derivativeSettlementAccumulator =
            new DerivativeTradeSettlementService.DerivativeSettlementAccumulator();
    final SpotTradeSettlementService.SpotSettlementAccumulator spotSettlementAccumulator =
            new SpotTradeSettlementService.SpotSettlementAccumulator();
    final RiskScanner.PositionRiskScratch positionRiskScratch = new RiskScanner.PositionRiskScratch();
    final DerivativeTradeSettlementService.FillCursor singleFillCursor =
            new DerivativeTradeSettlementService.FillCursor();
    volatile int queueHighWaterMark;
    /** 各 Matcher 分片已应用的 Core 命令水位，支持多 Matcher 并发偏序推进。 */
    final long[] appliedSequencesByShard = new long[Long.SIZE];
    /** 各 Matcher 分片已完成提交的 Core 命令水位，支持多 Matcher 并发偏序推进。 */
    final long[] committedSequencesByShard = new long[Long.SIZE];
    /** 最近重建或恢复的业务状态哈希缓存，不随每次字段写入自动重算。 */
    long localStateHash = 0xcbf29ce484222325L;
    /** 最近重建或恢复的余额哈希缓存，只包含用户、资产及可用冻结金额。 */
    long localFundsHash = 0xcbf29ce484222325L;
    /** 是否处于命令访问作用域的标记，本身不提供锁或跨线程发布。 */
    boolean commandScopeActive;
    /** 是否处于查询访问作用域的标记，不能代替实际读取屏障。 */
    boolean queryScopeActive;
    /** Owner 归还已脱离业务表订单的 SPSC 队列，等最终退休水位后才复用，容量为 4096。 */
    final org.agrona.concurrent.OneToOneConcurrentArrayQueue<Order> retiredOrders =
            new org.agrona.concurrent.OneToOneConcurrentArrayQueue<>(4_096);
    final java.util.ArrayDeque<Order> reusableOrders = new java.util.ArrayDeque<>();

    LaneColdState(AccountPartition lane) {
        this.lane = lane;
        this.advanceUserRevision = this.lane::advanceUserRevision;
    }

    void clear() {
        liquidations.clear();
        activeLiquidationIndex.clear();
        riskSnapshots.clear();
        leverages.clear();
        leverageKeysByUser.clear();
        algoOrders.clear();
        triggerOrders.clear();
        triggerIdsByUser.clear();
        pendingTransfers.clear();
    }
}
