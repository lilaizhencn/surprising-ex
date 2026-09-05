package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

public final class MatcherSettlementPlan {
    private final long coreSequence;
    private final long takerOrderId;
    private final long activeUserId;
    private final long requiredLaneMask;
    private final long[] orderIds;
    private final int orderCount;
    private final List<MatcherEvent> matcherEvents;
    private final int tradeCount;
    private final long[] preCancellationOrderIds;
    private int[] makerLaneHeads;
    private int[] makerLaneNext;
    private int takerLaneId;

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount) {
        this(coreSequence, takerOrderId, activeUserId, requiredLaneMask, orderIds, orderCount,
                matcherEvents, tradeCount, new long[0]);
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

    static void validateAndPrepareBatch(long[] takerOrderIds, List<CoreMatchingResult> matchingResults,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        BatchValidationScratch scratch) {
        if (takerOrderIds == null || matchingResults == null || takerOrderIds.length == 0
                || takerOrderIds.length != matchingResults.size() || scratch == null) {
            throw new IllegalArgumentException("invalid matcher settlement batch");
        }
        LongLongHashMap remainingByOrderId = scratch.remainingByOrderId;
        LongHashSet terminalOrderIds = scratch.terminalOrderIds;
        scratch.clear();
        try {
            for (int index = 0; index < takerOrderIds.length; index++) {
                long takerOrderId = takerOrderIds[index];
                List<MatcherEvent> matches = matchingResults.get(index).matcherEvents();
                OrderRuntime taker = requireOpen(runtime, takerOrderId);
                if (terminalOrderIds.contains(takerOrderId)) {
                    throw new IllegalStateException("runtime matched order is not open: " + takerOrderId);
                }
                CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
                if (instrument == null || instrument.version() != taker.instrumentVersion()) {
                    throw new IllegalStateException("runtime match instrument is missing");
                }
                preparePositionIdentity(runtime, identities, instrument, taker);
                long takerRemaining = remainingByOrderId.containsKey(takerOrderId)
                        ? remainingByOrderId.get(takerOrderId) : taker.remainingQuantitySteps();
                for (MatcherEvent match : matches) {
                    if (match == null) throw new IllegalArgumentException("runtime match is required");
                    if (match.eventType() != MatcherEventType.TRADE) continue;
                    OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
                    if (terminalOrderIds.contains(maker.orderId())
                            || maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                            || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                        throw new IllegalStateException("runtime match does not match authoritative orders");
                    }
                    if (match.price() <= 0 || match.size() <= 0) {
                        throw new IllegalArgumentException("invalid runtime match price or quantity");
                    }
                    takerRemaining = Math.subtractExact(takerRemaining, match.size());
                    long makerRemaining = remainingByOrderId.containsKey(maker.orderId())
                            ? remainingByOrderId.get(maker.orderId()) : maker.remainingQuantitySteps();
                    makerRemaining = Math.subtractExact(makerRemaining, match.size());
                    if (takerRemaining < 0 || makerRemaining < 0) {
                        throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                    }
                    remainingByOrderId.put(maker.orderId(), makerRemaining);
                    if (makerRemaining == 0) terminalOrderIds.add(maker.orderId());
                    preparePositionIdentity(runtime, identities, instrument, maker);
                }
                remainingByOrderId.put(takerOrderId, takerRemaining);
                if (takerRemaining == 0 || taker.timeInForce().immediate()
                        || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET) {
                    terminalOrderIds.add(takerOrderId);
                }
            }
        } finally {
            scratch.clear();
        }
    }

    static final class BatchValidationScratch {
        private final LongLongHashMap remainingByOrderId = new LongLongHashMap();
        private final LongHashSet terminalOrderIds = new LongHashSet();

        private void clear() {
            remainingByOrderId.clear();
            terminalOrderIds.clear();
        }
    }

    public static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, CoreMatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (coreSequence <= 0 || takerOrderId <= 0 || activeUserId <= 0 || initialOrderIds == null
                || result == null || runtime == null || identities == null
                || result.nativeCommand().coreSequence() != coreSequence) {
            throw new IllegalArgumentException("invalid matcher settlement plan input");
        }
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
        if (instrument == null || instrument.version() != taker.instrumentVersion()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        int expectedChanges = Math.max(2, result.matcherEvents().size() + result.cancellations().size()
                + initialOrderIds.length + 1);
        long[] orders = new long[expectedChanges];
        int orderCount = 0;
        LongHashSet uniqueOrders = runtime.matcherSettlementOrderScratch();
        for (long orderId : initialOrderIds) {
            if (orderId > 0) orderCount = addUnique(uniqueOrders, orders, orderCount, orderId);
        }
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        preparePositionIdentity(runtime, identities, instrument, taker);
        if (result.matcherEvents().isEmpty()) {
            for (var cancellation : result.cancellations()) {
                OrderRuntime order = runtime.order(cancellation.orderId());
                if (order != null) {
                    orderCount = addUnique(uniqueOrders, orders, orderCount, order.orderId());
                    laneMask |= runtime.topology().accountLaneMask(order.userId());
                }
            }
            return new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId, laneMask,
                    orders, orderCount, result.matcherEvents(), 0);
        }
        LongLongHashMap remainingByOrderId = runtime.matcherSettlementRemainingScratch();
        remainingByOrderId.clear();
        remainingByOrderId.put(takerOrderId, taker.remainingQuantitySteps());
        int tradeCount = 0;
        try {
            for (MatcherEvent event : result.matcherEvents()) {
                if (event == null) throw new IllegalArgumentException("runtime match is required");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                OrderRuntime maker = requireOpen(runtime, event.matchedOrderId());
                if (maker.userId() != event.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                long takerRemaining = Math.subtractExact(remainingByOrderId.get(takerOrderId), event.size());
                long makerBefore = remainingByOrderId.containsKey(maker.orderId())
                        ? remainingByOrderId.get(maker.orderId()) : maker.remainingQuantitySteps();
                long makerRemaining = Math.subtractExact(makerBefore, event.size());
                if (takerRemaining < 0 || makerRemaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                remainingByOrderId.put(takerOrderId, takerRemaining);
                remainingByOrderId.put(maker.orderId(), makerRemaining);
                preparePositionIdentity(runtime, identities, instrument, maker);
                orderCount = addUnique(uniqueOrders, orders, orderCount, maker.orderId());
                int makerLane = runtime.topology().accountLaneId(maker.userId());
                tradeCount++;
                laneMask |= 1L << makerLane;
            }
        } finally {
            remainingByOrderId.clear();
        }
        for (var cancellation : result.cancellations()) {
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                orderCount = addUnique(uniqueOrders, orders, orderCount, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        return new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId, laneMask,
                orders, orderCount, result.matcherEvents(), tradeCount).indexLaneEvents(runtime);
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
        return copy;
    }
    public int preCancellationCount() { return preCancellationOrderIds.length; }
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    private MatcherSettlementPlan indexLaneEvents(TradingRuntimeState runtime) {
        // Small fills need no index. For deep fills, each maker lane follows only its events;
        // the taker lane still consumes the original order, without copying any fill.
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        makerLaneHeads = new int[runtime.topology().accountLaneCount()];
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
        if (makerLaneHeads != null && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    int nextMatcherEvent(int index, int laneId) {
        if (makerLaneHeads != null && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    public boolean matcherEventTouchesLane(int index, int laneId, TradingRuntimeState runtime) {
        MatcherEvent event = matcherEvents.get(index);
        return runtime.topology().accountLaneId(activeUserId) == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}
