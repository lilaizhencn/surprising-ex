package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class MatcherSettlementPlan {
    private final long coreSequence;
    private final long takerOrderId;
    private final long requiredLaneMask;
    private final long[] userIds;
    private final long[] orderIds;
    private final List<MatcherEvent> tradeEvents;
    private final List<MatcherEvent>[] laneEvents;

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long requiredLaneMask,
                                  long[] userIds, long[] orderIds, List<MatcherEvent> tradeEvents,
                                  List<MatcherEvent>[] laneEvents) {
        this.coreSequence = coreSequence;
        this.takerOrderId = takerOrderId;
        this.requiredLaneMask = requiredLaneMask;
        this.userIds = userIds;
        this.orderIds = orderIds;
        this.tradeEvents = tradeEvents;
        this.laneEvents = laneEvents;
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
        int laneCount = runtime.topology().accountLaneCount();
        @SuppressWarnings("unchecked")
        ArrayList<MatcherEvent>[] routed = new ArrayList[laneCount];
        int expectedChanges = Math.max(2, result.matcherEvents().size() + initialOrderIds.length + 1);
        LongArrayList users = new LongArrayList(expectedChanges);
        LongArrayList orders = new LongArrayList(expectedChanges);
        addUnique(users, activeUserId);
        for (long orderId : initialOrderIds) if (orderId > 0) addUnique(orders, orderId);
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        RemainingQuantities remaining = new RemainingQuantities(expectedChanges);
        remaining.put(takerOrderId, taker.remainingQuantitySteps());
        ArrayList<MatcherEvent> trades = new ArrayList<>(result.matcherEvents().size());
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
            long takerRemaining = Math.subtractExact(remaining.get(takerOrderId), event.size());
            long makerBefore = remaining.getOrDefault(maker.orderId(), maker.remainingQuantitySteps());
            long makerRemaining = Math.subtractExact(makerBefore, event.size());
            if (takerRemaining < 0 || makerRemaining < 0) {
                throw new IllegalStateException("fill exceeds runtime order remaining quantity");
            }
            remaining.put(takerOrderId, takerRemaining);
            remaining.put(maker.orderId(), makerRemaining);
            preparePositionIdentity(runtime, identities, instrument, maker);
            addUnique(users, maker.userId());
            addUnique(orders, maker.orderId());
            trades.add(event);
            int takerLane = runtime.topology().accountLaneId(taker.userId());
            int makerLane = runtime.topology().accountLaneId(maker.userId());
            addLaneEvent(routed, takerLane, event);
            if (makerLane != takerLane) addLaneEvent(routed, makerLane, event);
            laneMask |= 1L << makerLane;
        }
        for (var cancellation : result.cancellations()) {
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                addUnique(users, order.userId());
                addUnique(orders, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        @SuppressWarnings("unchecked")
        List<MatcherEvent>[] frozen = new List[laneCount];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            frozen[laneId] = routed[laneId] == null ? List.of() : List.copyOf(routed[laneId]);
        }
        return new MatcherSettlementPlan(coreSequence, takerOrderId, laneMask,
                users.toArray(), orders.toArray(), List.copyOf(trades), frozen);
    }

    public static MatcherSettlementPlan empty(long coreSequence, long activeUserId, long[] orderIds,
                                               TradingRuntimeState runtime) {
        if (coreSequence <= 0 || activeUserId <= 0 || orderIds == null || runtime == null) {
            throw new IllegalArgumentException("invalid empty matcher settlement plan input");
        }
        @SuppressWarnings("unchecked")
        List<MatcherEvent>[] lanes = new List[runtime.topology().accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++) lanes[laneId] = List.of();
        return new MatcherSettlementPlan(coreSequence, orderIds.length == 0 ? 0 : orderIds[orderIds.length - 1],
                runtime.topology().accountLaneMask(activeUserId), new long[]{activeUserId}, orderIds.clone(),
                List.of(), lanes);
    }

    private static void addUnique(LongArrayList values, long value) {
        if (!values.contains(value)) values.add(value);
    }

    private static void addLaneEvent(ArrayList<MatcherEvent>[] lanes, int laneId, MatcherEvent event) {
        ArrayList<MatcherEvent> events = lanes[laneId];
        if (events == null) {
            events = new ArrayList<>();
            lanes[laneId] = events;
        }
        events.add(event);
    }

    private static final class RemainingQuantities {
        private long[] orderIds;
        private long[] quantities;
        private int size;

        private RemainingQuantities(int capacity) {
            orderIds = new long[capacity];
            quantities = new long[capacity];
        }

        private long get(long orderId) {
            int index = indexOf(orderId);
            return index < 0 ? 0 : quantities[index];
        }

        private long getOrDefault(long orderId, long defaultValue) {
            int index = indexOf(orderId);
            return index < 0 ? defaultValue : quantities[index];
        }

        private void put(long orderId, long quantity) {
            int index = indexOf(orderId);
            if (index >= 0) {
                quantities[index] = quantity;
                return;
            }
            if (size == orderIds.length) {
                int capacity = Math.multiplyExact(orderIds.length, 2);
                orderIds = java.util.Arrays.copyOf(orderIds, capacity);
                quantities = java.util.Arrays.copyOf(quantities, capacity);
            }
            orderIds[size] = orderId;
            quantities[size++] = quantity;
        }

        private int indexOf(long orderId) {
            for (int index = 0; index < size; index++) if (orderIds[index] == orderId) return index;
            return -1;
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
    public List<MatcherEvent> tradeEvents() { return tradeEvents; }
    public List<MatcherEvent> laneEvents(int laneId) { return laneEvents[laneId]; }
}
