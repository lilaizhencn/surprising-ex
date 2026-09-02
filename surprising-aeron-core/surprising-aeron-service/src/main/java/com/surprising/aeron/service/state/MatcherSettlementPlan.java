package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

public final class MatcherSettlementPlan {
    private final long coreSequence;
    private final long takerOrderId;
    private final long requiredLaneMask;
    private final long[] userIds;
    private final long[] orderIds;
    private final MatcherEvent[] tradeEvents;
    private final long[] tradeLaneMasks;
    private final List<MatcherEvent> tradeEventView;

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long requiredLaneMask,
                                  long[] userIds, long[] orderIds, MatcherEvent[] tradeEvents,
                                  long[] tradeLaneMasks) {
        this.coreSequence = coreSequence;
        this.takerOrderId = takerOrderId;
        this.requiredLaneMask = requiredLaneMask;
        this.userIds = userIds;
        this.orderIds = orderIds;
        this.tradeEvents = tradeEvents;
        this.tradeLaneMasks = tradeLaneMasks;
        this.tradeEventView = tradeEvents.length == 0 ? List.of() : new TradeEventList(tradeEvents);
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
        long[] users = new long[expectedChanges];
        int userCount = addUnique(users, 0, activeUserId);
        long[] orders = new long[expectedChanges];
        int orderCount = 0;
        for (long orderId : initialOrderIds) {
            if (orderId > 0) orderCount = addUnique(orders, orderCount, orderId);
        }
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        long[] remainingOrderIds = new long[Math.max(2, result.matcherEvents().size() + 1)];
        long[] remainingQuantities = new long[remainingOrderIds.length];
        int remainingCount = 1;
        remainingOrderIds[0] = takerOrderId;
        remainingQuantities[0] = taker.remainingQuantitySteps();
        MatcherEvent[] trades = new MatcherEvent[result.matcherEvents().size()];
        long[] tradeLaneMasks = new long[trades.length];
        int tradeCount = 0;
        preparePositionIdentity(runtime, identities, instrument, taker);
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
            int takerIndex = indexOf(remainingOrderIds, remainingCount, takerOrderId);
            int makerIndex = indexOf(remainingOrderIds, remainingCount, maker.orderId());
            long takerRemaining = Math.subtractExact(remainingQuantities[takerIndex], event.size());
            long makerBefore = makerIndex < 0
                    ? maker.remainingQuantitySteps() : remainingQuantities[makerIndex];
            long makerRemaining = Math.subtractExact(makerBefore, event.size());
            if (takerRemaining < 0 || makerRemaining < 0) {
                throw new IllegalStateException("fill exceeds runtime order remaining quantity");
            }
            remainingQuantities[takerIndex] = takerRemaining;
            if (makerIndex < 0) {
                makerIndex = remainingCount++;
                remainingOrderIds[makerIndex] = maker.orderId();
            }
            remainingQuantities[makerIndex] = makerRemaining;
            preparePositionIdentity(runtime, identities, instrument, maker);
            userCount = addUnique(users, userCount, maker.userId());
            orderCount = addUnique(orders, orderCount, maker.orderId());
            int takerLane = runtime.topology().accountLaneId(taker.userId());
            int makerLane = runtime.topology().accountLaneId(maker.userId());
            trades[tradeCount] = event;
            tradeLaneMasks[tradeCount] = 1L << takerLane | 1L << makerLane;
            tradeCount++;
            laneMask |= 1L << makerLane;
        }
        for (var cancellation : result.cancellations()) {
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                userCount = addUnique(users, userCount, order.userId());
                orderCount = addUnique(orders, orderCount, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        return new MatcherSettlementPlan(coreSequence, takerOrderId, laneMask,
                Arrays.copyOf(users, userCount), Arrays.copyOf(orders, orderCount),
                Arrays.copyOf(trades, tradeCount), Arrays.copyOf(tradeLaneMasks, tradeCount));
    }

    public static MatcherSettlementPlan empty(long coreSequence, long activeUserId, long[] orderIds,
                                               TradingRuntimeState runtime) {
        if (coreSequence <= 0 || activeUserId <= 0 || orderIds == null || runtime == null) {
            throw new IllegalArgumentException("invalid empty matcher settlement plan input");
        }
        return new MatcherSettlementPlan(coreSequence, orderIds.length == 0 ? 0 : orderIds[orderIds.length - 1],
                runtime.topology().accountLaneMask(activeUserId), new long[]{activeUserId}, orderIds.clone(),
                new MatcherEvent[0], new long[0]);
    }

    private static int addUnique(long[] values, int size, long value) {
        for (int index = 0; index < size; index++) {
            if (values[index] == value) return size;
        }
        values[size] = value;
        return size + 1;
    }

    private static int indexOf(long[] values, int size, long value) {
        for (int index = 0; index < size; index++) if (values[index] == value) return index;
        return -1;
    }

    private static final class TradeEventList extends AbstractList<MatcherEvent> implements RandomAccess {
        private final MatcherEvent[] events;

        private TradeEventList(MatcherEvent[] events) {
            this.events = events;
        }

        @Override
        public MatcherEvent get(int index) {
            return events[index];
        }

        @Override
        public int size() {
            return events.length;
        }
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
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }

    public long coreSequence() { return coreSequence; }
    public long takerOrderId() { return takerOrderId; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public long[] userIds() { return userIds; }
    public long[] orderIds() { return orderIds; }
    public List<MatcherEvent> tradeEvents() { return tradeEventView; }
    public int tradeEventCount() { return tradeEvents.length; }
    public MatcherEvent tradeEvent(int index) { return tradeEvents[index]; }
    public boolean tradeTouchesLane(int index, int laneId) {
        return (tradeLaneMasks[index] & 1L << laneId) != 0;
    }
}
