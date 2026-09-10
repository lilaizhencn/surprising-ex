package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

public final class MatcherSettlementPlan {
    /** 撮合明确拒绝时，由账户 Lane 释放准入冻结并产生拒单终态。 */
    private boolean rejectedTaker;
    public MatcherSettlementPlan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }
    boolean rejectedTaker() { return rejectedTaker; }

    private long coreSequence;
    private long takerOrderId;
    private long activeUserId;
    private long requiredLaneMask;
    private long[] orderIds;
    private int orderCount;
    private List<MatcherEvent> matcherEvents;
    private int tradeCount;
    private long[] preCancellationOrderIds;
    private int[] makerLaneHeads;
    private int[] makerLaneNext;
    private int takerLaneId;
    /** 索引数组归事件所有并复用；只有本代深度成交启用时读取。 */
    private boolean laneEventsIndexed;
    private static final long[] NO_ORDERS = new long[0];

    /** 仅事件池持有的批量槽可复用；Owner准备后只读，所有Lane完成后才能清理。 */
    MatcherSettlementPlan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    void clearBatchReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
    }
    /** 仅触发子订单携带；owner 派发前构造，taker Lane 在同一结算事件中写入。 */
    private com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger;

    public MatcherSettlementPlan completeTrigger(com.surprising.aeron.service.state.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || value.placedOrderId() != takerOrderId || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }
    com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger() { return completedTrigger; }

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount) {
        this(coreSequence, takerOrderId, activeUserId, requiredLaneMask, orderIds, orderCount,
                matcherEvents, tradeCount, NO_ORDERS);
    }

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount,
                                  long[] preCancellationOrderIds) {
        this.coreSequence = coreSequence;
        this.takerOrderId = takerOrderId;
        this.activeUserId = activeUserId;
        this.requiredLaneMask = requiredLaneMask;
        this.orderIds = orderIds;
        this.orderCount = orderCount;
        this.matcherEvents = matcherEvents;
        this.tradeCount = tradeCount;
        this.preCancellationOrderIds = preCancellationOrderIds;
    }

    static final class BatchValidationScratch {
        private final LongLongHashMap remainingByOrderId = new LongLongHashMap();
        private final LongHashSet terminalOrderIds = new LongHashSet();
        /** 本批相邻成交重复maker的只读引用；不额外建立逐订单缓存表。 */
        private OrderRuntime lastMaker;

        OrderRuntime maker(TradingRuntimeState runtime, long id) {
            if (lastMaker == null || lastMaker.orderId() != id) lastMaker = requireOpen(runtime, id);
            return lastMaker;
        }

        void clear() {
            remainingByOrderId.clear();
            terminalOrderIds.clear();
            lastMaker = null;
        }
    }

    public static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, CoreMatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (initialOrderIds == null) throw new IllegalArgumentException("initial orders are required");
        return build(coreSequence, takerOrderId, activeUserId, initialOrderIds, result, runtime, identities,
                null, null, null, null);
    }

    /** 批量构建与累计数量校验共用一次成交遍历；scratch 只在本批 Owner 调用期间使用。 */
    static MatcherSettlementPlan buildBatchItem(long sequence, OrderRuntime taker, CoreInstrumentState instrument,
                                                CoreMatchingResult result, TradingRuntimeState runtime,
                                                RuntimeIdentityRegistry identities, BatchValidationScratch scratch, MatcherSettlementPlan target) {
        return build(sequence, taker.orderId(), taker.userId(), null, result, runtime, identities,
                taker, instrument, scratch, target);
    }

    private static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, CoreMatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                               OrderRuntime preparedTaker, CoreInstrumentState preparedInstrument,
                                               BatchValidationScratch batch, MatcherSettlementPlan target) {
        if (coreSequence <= 0 || takerOrderId <= 0 || activeUserId <= 0 || (initialOrderIds == null && batch == null)
                || result == null || runtime == null || identities == null
                || result.nativeCommand().coreSequence() != coreSequence) {
            throw new IllegalArgumentException("invalid matcher settlement plan input");
        }
        OrderRuntime taker = preparedTaker == null ? requireOpen(runtime, takerOrderId) : preparedTaker;
        if (taker.status() != CoreOrderStatus.OPEN || batch != null && batch.terminalOrderIds.contains(takerOrderId))
            throw new IllegalStateException("runtime matched order is not open: " + takerOrderId);
        CoreInstrumentState instrument = preparedInstrument == null
                ? runtime.instrument(identities.symbol(taker.symbolId())) : preparedInstrument;
        if (instrument == null || instrument.changeId() != taker.instrumentChangeId()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        int expectedChanges = Math.max(2, result.matcherEvents().size() + result.cancellations().size()
                + (initialOrderIds == null ? 1 : initialOrderIds.length) + 1);
        long[] orders;
        if (target == null) orders = new long[expectedChanges];
        else {
            if (target.orderIds.length < expectedChanges)
                target.orderIds = new long[Math.max(expectedChanges, target.orderIds.length * 2)];
            orders = target.orderIds;
        }
        int orderCount = 0;
        LongHashSet uniqueOrders = runtime.matcherSettlementOrderScratch();
        if (initialOrderIds == null) orderCount = addUnique(uniqueOrders, orders, orderCount, takerOrderId);
        else for (long orderId : initialOrderIds) {
            if (orderId > 0) orderCount = addUnique(uniqueOrders, orders, orderCount, orderId);
        }
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        LongLongHashMap remainingByOrderId = batch == null
                ? runtime.matcherSettlementRemainingScratch() : batch.remainingByOrderId;
        if (batch == null) remainingByOrderId.clear();
        if (!remainingByOrderId.containsKey(takerOrderId)) {
            preparePositionIdentity(runtime, identities, instrument, taker);
            remainingByOrderId.put(takerOrderId, taker.remainingQuantitySteps());
        }
        int tradeCount = 0;
        try {
            for (MatcherEvent event : result.matcherEvents()) {
                if (event == null) throw new IllegalArgumentException("runtime match is required");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                OrderRuntime maker = batch == null ? requireOpen(runtime, event.matchedOrderId())
                        : batch.maker(runtime, event.matchedOrderId());
                if (batch != null && batch.terminalOrderIds.contains(maker.orderId())
                        || maker.userId() != event.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                long takerRemaining = Math.subtractExact(remainingByOrderId.get(takerOrderId), event.size());
                boolean knownMaker = remainingByOrderId.containsKey(maker.orderId());
                long makerBefore = knownMaker ? remainingByOrderId.get(maker.orderId()) : maker.remainingQuantitySteps();
                long makerRemaining = Math.subtractExact(makerBefore, event.size());
                if (takerRemaining < 0 || makerRemaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                remainingByOrderId.put(takerOrderId, takerRemaining);
                remainingByOrderId.put(maker.orderId(), makerRemaining);
                if (batch != null && makerRemaining == 0) batch.terminalOrderIds.add(maker.orderId());
                if (!knownMaker)
                    preparePositionIdentity(runtime, identities, instrument, maker);
                orderCount = addUnique(uniqueOrders, orders, orderCount, maker.orderId());
                int makerLane = runtime.topology().accountLaneId(maker.userId());
                tradeCount++;
                laneMask |= 1L << makerLane;
            }
            if (batch != null && (remainingByOrderId.get(takerOrderId) == 0 || taker.timeInForce().immediate()
                    || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET))
                batch.terminalOrderIds.add(takerOrderId);
        } finally {
            if (batch == null) remainingByOrderId.clear();
        }
        for (var cancellation : result.cancellations()) {
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                orderCount = addUnique(uniqueOrders, orders, orderCount, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        MatcherSettlementPlan plan = target == null
                ? new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId, laneMask, orders, orderCount, result.matcherEvents(), tradeCount)
                : target;
        plan.coreSequence = coreSequence; plan.takerOrderId = takerOrderId; plan.activeUserId = activeUserId;
        plan.requiredLaneMask = laneMask; plan.orderIds = orders; plan.orderCount = orderCount;
        plan.matcherEvents = result.matcherEvents(); plan.tradeCount = tradeCount;
        plan.preCancellationOrderIds = NO_ORDERS; plan.completedTrigger = null; plan.rejectedTaker = false;
        plan.laneEventsIndexed = false;
        return plan.indexLaneEvents(runtime);
    }

    public static MatcherSettlementPlan empty(long coreSequence, long activeUserId, long[] orderIds,
                                               TradingRuntimeState runtime) {
        if (coreSequence <= 0 || activeUserId <= 0 || orderIds == null || runtime == null) {
            throw new IllegalArgumentException("invalid empty matcher settlement plan input");
        }
        return new MatcherSettlementPlan(coreSequence,
                orderIds.length == 0 ? 0 : orderIds[orderIds.length - 1], activeUserId,
                runtime.topology().accountLaneMask(activeUserId), orderIds.clone(), orderIds.length,
                List.of(), 0);
    }

    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    private static void preparePositionIdentity(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                CoreInstrumentState instrument, OrderRuntime order) {
        if (!runtime.productLine().isDerivative()) return;
        String positionIdentity = order.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? instrument.symbol() : instrument.symbol() + ':' + order.positionSide().name();
        identities.positionKey(order.userId(), positionIdentity);
    }

    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId
                    + (order == null ? " missing" : " status=" + order.status()
                    + " executed=" + order.executedQuantitySteps()
                    + " remaining=" + order.remainingQuantitySteps()
                    + " revision=" + order.revision()));
        }
        return order;
    }

    public long coreSequence() { return coreSequence; }
    public long takerOrderId() { return takerOrderId; }
    public long activeUserId() { return activeUserId; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public int orderCount() { return orderCount; }
    public long orderId(int index) { return orderIds[index]; }
    public int matcherEventCount() { return matcherEvents.size(); }
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    public int tradeCount() { return tradeCount; }
    public MatcherSettlementPlan preCancellations(long[] orderIds) {
        if (orderIds == null) throw new IllegalArgumentException("pre-cancellation ids are required");
        if (orderIds.length == 0) return this;
        MatcherSettlementPlan copy = new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId,
                requiredLaneMask, this.orderIds, orderCount, matcherEvents, tradeCount, orderIds.clone());
        copy.makerLaneHeads = makerLaneHeads;
        copy.makerLaneNext = makerLaneNext;
        copy.takerLaneId = takerLaneId;
        copy.laneEventsIndexed = laneEventsIndexed;
        copy.completedTrigger = completedTrigger;
        copy.rejectedTaker = rejectedTaker;
        return copy;
    }
    public int preCancellationCount() { return preCancellationOrderIds.length; }
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    private MatcherSettlementPlan indexLaneEvents(TradingRuntimeState runtime) {
        // Small fills need no index. For deep fills, each maker lane follows only its events;
        // the taker lane still consumes the original order, without copying any fill.
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != runtime.topology().accountLaneCount())
            makerLaneHeads = new int[runtime.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (int index = matcherEvents.size() - 1; index >= 0; index--) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            int lane = runtime.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    public boolean matcherEventTouchesLane(int index, int laneId, TradingRuntimeState runtime) {
        MatcherEvent event = matcherEvents.get(index);
        return runtime.topology().accountLaneId(activeUserId) == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}
