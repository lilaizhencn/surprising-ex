package com.surprising.aeron.service.settlement;

import com.surprising.aeron.service.order.ReplaceOrderCheck;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderResultTarget;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.service.treasury.SystemFundsChange;
import com.surprising.aeron.service.treasury.SystemFunds;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 下单步骤 4.2–4.6 的可复用结算事件；ready 还等待发布方交出租约，清理前须交出 changes。 */

/** 下单步骤 4.2：Matcher 根据原生成交事实构建受影响订单和账户 Lane 路由。数组、事件和准入引用受在途槽租约约束，不能当成永久快照。 */
public final class TradeSettlementPlan {
    /** 本代 taker 或撤单请求是否被拒绝，不能与已计入成交并存。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private boolean rejectedTaker;
    /** 保存撮合拒单标记并返回本计划。已有成交时禁止标记拒绝，防止用拒单覆盖已发生的成交事实。 */
    public TradeSettlementPlan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }

    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long takerOrderId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long activeUserId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long requiredLaneMask;
    private long[] orderIds;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int orderCount;
    private List<MatcherEvent> matcherEvents;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int tradeCount;
    private long[] preCancellationOrderIds;
    private int preCancellationSize;
    private long[] preCancellationStorage;
    private int[] makerLaneHeads;
    private int[] makerLaneNext;
    /** 构建时缓存的主动账户 Lane，深度索引中该 Lane 仍读取完整原事件顺序。 */
    private int takerLaneId = -1;
    private boolean laneEventsIndexed;
    private static final long[] NO_ORDERS = new long[0];
    /** 构建时保留的准入订单值或命令槽引用，消费者遵守该代租约。返回构建时保留的准入订单引用，借用期受计划租约限制，可能为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    private Order admittedTaker;
    private com.surprising.aeron.protocol.CoreOrderSide directTakerSide;
    private int directTakerSymbolId;
    private CoreInstrument directTakerInstrument;
    private final LongHashSet directOrderKeys = new LongHashSet();

    /** 创建可复用计划，先保留八个订单编号槽，事件和前置撤单使用空值。 */
    public TradeSettlementPlan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    /** 清空本代事件、触发终态、订单引用、有效计数和直达校验字段。 */
    public void clearReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        preCancellationSize = 0;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
        admittedTaker = null; takerLaneId = -1;
        directTakerSide = null;
        directTakerSymbolId = 0;
        directTakerInstrument = null;
    }

    /** 由已准入运行时订单及原生 MatcherResult 构建直达计划，按订单剩余量校验成交。 */
    void buildDirectNative(long sequence, Order taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        if (taker == null || instrument == null || result == null || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), taker.userId(), taker.side(), taker.symbolId(),
                taker.instrument(), taker.remainingQuantitySteps(), taker, result, accountBook);
    }

    /** 由普通下单不可变准入值构建原生事实，使用完整准入数量，保留无需跨线程读取账户的校验字段。 */
    void buildDirectNative(long sequence, long userId, ResolvedPlaceOrder taker, CoreInstrument instrument,
                           exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        if (userId <= 0 || taker == null || instrument == null || result == null
                || instrument != taker.instrument())
            throw new IllegalArgumentException("invalid direct native matcher fact");
        buildDirectNative(sequence, taker.orderId(), userId, taker.side(), taker.symbolId(),
                taker.instrument(), taker.quantitySteps(), null, result, accountBook);
    }

    private void buildDirectNative(long sequence, long orderId, long userId,
                                   com.surprising.aeron.protocol.CoreOrderSide side, int symbolId,
                                   CoreInstrument takerInstrument, long remainingQuantity, Order taker,
                                   exchange.core2.core.common.MatcherResult result, AccountBook accountBook) {
        clearReferences();
        coreSequence = sequence; admittedTaker = taker;
        directTakerSide = side; directTakerSymbolId = symbolId; directTakerInstrument = takerInstrument;
        takerOrderId = orderId; activeUserId = userId;
        takerLaneId = accountBook.topology().accountLaneId(activeUserId);
        requiredLaneMask = accountBook.topology().accountLaneMask(activeUserId);
        matcherEvents = result.events();
        int eventCount = matcherEvents.size();
        if (eventCount == 0) {
            orderIds[0] = takerOrderId;
            orderCount = 1;
            rejectTaker(!TradeSettlement.nativeAccepted(result));
            return;
        }
        int capacity = Math.addExact(eventCount, 1);
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        if (eventCount <= 4) {
            orderIds[0] = takerOrderId;
            int count = 1;
            long remaining = remainingQuantity;
            for (int index = 0; index < eventCount; index++) {
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0
                        || (event.matchedOrderUid() == activeUserId && !accountBook.selfTradeAllowed(activeUserId)))
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                count = addUniqueLinear(orderIds, count, event.matchedOrderId());
                requiredLaneMask |= accountBook.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            orderCount = count;
            rejectTaker(!TradeSettlement.nativeAccepted(result));
            return;
        }
        LongHashSet keys = directOrderKeys;
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
            long remaining = remainingQuantity;
            for (int index = 0; index < eventCount; index++) {
                MatcherEvent event = matcherEvents.get(index);
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0
                        || (event.matchedOrderUid() == activeUserId && !accountBook.selfTradeAllowed(activeUserId)))
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                orderCount = addUnique(keys, orderIds, orderCount, event.matchedOrderId());
                requiredLaneMask |= accountBook.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            rejectTaker(!TradeSettlement.nativeAccepted(result));
            indexLaneEvents(accountBook);
        } finally { keys.clear(); }
    }

    private static int addUniqueLinear(long[] values, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) return size;
        }
        values[size] = value;
        return size + 1;
    }

    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    /** 检查原生撤单不夹带成交，重置并保存目标用户路由和接受标记。 */
    void buildDirectNativeCancellation(long sequence, long userId, long orderId,
                                       exchange.core2.core.common.MatcherResult result,
                                       AccountBook accountBook) {
        if (orderId <= 0 || result == null) throw new IllegalArgumentException("invalid direct cancellation fact");
        for (int index = 0; index < result.events().size(); index++) {
            MatcherEvent event = result.events().get(index);
            if (event == null || event.eventType() == MatcherEventType.TRADE)
                throw new IllegalStateException("cancel result contains a trade");
        }
        clearReferences();
        boolean accepted = TradeSettlement.nativeAccepted(result);
        coreSequence = sequence; takerOrderId = orderId; activeUserId = userId;
        takerLaneId = accountBook.topology().accountLaneId(activeUserId);
        rejectedTaker = !accepted;
        requiredLaneMask = accountBook.topology().accountLaneMask(activeUserId);
        orderCount = accepted ? 1 : 0;
        if (orderCount != 0) orderIds[0] = orderId;
    }

    /** 为没有原生结果的准入拒绝只保留身份与路由，让已登记的账户路径能够交回完成回执。 */
    void prepareRejectedDirect(long sequence, long userId, long orderId, long laneMask) {
        if (sequence <= 0 || userId <= 0 || orderId <= 0 || laneMask == 0)
            throw new IllegalArgumentException("invalid rejected direct matcher fact");
        clearReferences();
        coreSequence = sequence;
        takerOrderId = orderId;
        activeUserId = userId;
        requiredLaneMask = laneMask;
        rejectedTaker = true;
        orderCount = tradeCount = 0;
    }

    /** 原地移除本计划编号前缀中的未成功下出的 taker，保留其他已发生变化的订单编号及原有顺序。 */
    void omitUnplacedOrder() {
        int count = 0;
        for (int index = 0; index < orderCount; index++)
            if (orderIds[index] != takerOrderId) orderIds[count++] = orderIds[index];
        orderCount = count;
    }

    /** 所属 Lane 以当前权威订单校验直达成交身份及 maker 单次剩余量，必要时保留持仓身份。深度成交的非 taker Lane 只沿自己的 maker 链遍历，不跨线程读其他账户。 */
    void validateDirectLane(AccountPartition lane, AccountBook accountBook,
                            IdIndex identities, CoreInstrument instrument) {
        if (directTakerSide == null) return;
        if (accountBook.topology().accountLaneId(activeUserId) == lane.laneId()) {
            Order taker = lane.order(takerOrderId);
            requireDirectOrder(taker, activeUserId, directTakerSide);
            if (tradeCount != 0) accountBook.retainDirectPositionIdentity(lane, identities, instrument, taker);
        }
        for (int index = firstMatcherEvent(lane.laneId()); index >= 0;
                index = nextMatcherEvent(index, lane.laneId())) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE
                    || !matcherEventTouchesLane(index, lane.laneId(), accountBook)
                    || accountBook.topology().accountLaneId(event.matchedOrderUid()) != lane.laneId()) continue;
            Order maker = lane.order(event.matchedOrderId());
            requireDirectOrder(maker, event.matchedOrderUid(), directTakerSide
                    == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? com.surprising.aeron.protocol.CoreOrderSide.SELL
                    : com.surprising.aeron.protocol.CoreOrderSide.BUY);
            if (maker.remainingQuantitySteps() < event.size())
                throw new IllegalStateException("fill exceeds Lane maker remaining quantity");
            accountBook.retainDirectPositionIdentity(lane, identities, instrument, maker);
        }
    }

    private void requireDirectOrder(Order order, long userId,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
        if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                || order.symbolId() != directTakerSymbolId || order.side() != side
                || order.instrument() != directTakerInstrument)
            throw new IllegalStateException("matcher fact does not match Lane-owned order");
    }
    @Accessors(fluent = true, chain = false)
    @Getter
    private com.surprising.aeron.model.CoreTriggerOrderState completedTrigger;

    /** 把触发终态附到同一结算事实：用户必须匹配，拒单要求失败状态且无子订单，非拒单要求子订单编号匹配。 */
    public TradeSettlementPlan completeTrigger(com.surprising.aeron.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }

    /** 直接读取原语订单编号。 */
    public long orderId(int index) { return orderIds[index]; }
    /** 返回本代 Matcher 事件总数，包括非成交事件。 */
    public int matcherEventCount() { return matcherEvents.size(); }
    /** 按列表下标读取本代原始 Matcher 事件引用。 */
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    /** 将已经接受的替换原单撤单保存为唯一前置撤单项。 */
    void acceptedReplacementCancellation(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("replacement cancellation is required");
        ensurePreCancellationCapacity(1);
        preCancellationStorage[0] = orderId;
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = 1;
    }

    /** 把调用方已经确认成功的撤单前缀追加到现有自有缓冲，不覆盖已有替换撤单。 */
    void acceptedPreCancellations(long[] orderIds, int count) {
        if (count < 0 || count != 0 && (orderIds == null || count > orderIds.length))
            throw new IllegalArgumentException("accepted cancellations are invalid");
        if (count == 0) return;
        ensurePreCancellationCapacity(preCancellationSize + count);
        System.arraycopy(orderIds, 0, preCancellationStorage, preCancellationSize, count);
        preCancellationSize += count;
        preCancellationOrderIds = preCancellationStorage;
    }

    private void ensurePreCancellationCapacity(int required) {
        if (required <= 0) return;
        if (preCancellationStorage == null) {
            preCancellationStorage = new long[Math.max(4, required)];
        } else if (preCancellationStorage.length < required) {
            preCancellationStorage = java.util.Arrays.copyOf(preCancellationStorage,
                    Math.max(required, preCancellationStorage.length * 2));
        }
    }
    /** 返回本代前置撤单有效项数。 */
    public int preCancellationCount() { return preCancellationSize; }
    /** 读取前置撤单原语编号，调用方保证下标小于有效项数。 */
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    /** 事件至少八个且涉及多个 Lane 时，逆序串出各 maker Lane 的事件链，保持原成交顺序。taker Lane 继续顺序读取全部原事件。 */
    private TradeSettlementPlan indexLaneEvents(AccountBook accountBook) {
        // 深度成交时各 maker Lane 只沿自己的事件链读取， taker Lane 仍保留原事件顺序，不复制成交事件。
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != accountBook.topology().accountLaneCount())
            makerLaneHeads = new int[accountBook.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (int index = matcherEvents.size() - 1; index >= 0; index--) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            int lane = accountBook.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    /** 返回当前 Lane 应开始读取的事件下标。 */
    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    /** 沿当前 Lane 的 maker 链或原事件顺序取得下一项，负一表示本 Lane 遍历结束。 */
    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    /** 判断当前成交是否涉及指定 Lane。 */
    public boolean matcherEventTouchesLane(int index, int laneId, AccountBook accountBook) {
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        MatcherEvent event = matcherEvents.get(index);
        return takerLaneId == laneId
                || accountBook.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}

