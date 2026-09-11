package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class RuntimeDerivativeMatchProcessor {

    private static final ThreadLocal<DerivativeSettlementAccumulator> ACCUMULATOR =
            ThreadLocal.withInitial(DerivativeSettlementAccumulator::new);

    private RuntimeDerivativeMatchProcessor() {
    }


    public static TradingRuntimeState apply(TradingCoreState before, long takerOrderId,
                                            List<MatcherEvent> matches, TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (matches == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        runtime.assertOwner();
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        if (matches.isEmpty() && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return runtime;
        }
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
        if (instrument == null || instrument.changeId() != taker.instrumentChangeId()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        validateAndPrepare(takerOrderId, matches, runtime, identities);
        int settleAssetId = identities.assetId(instrument.settleAsset());
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            taker = requireOpen(runtime, takerOrderId);
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                    || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                throw new IllegalStateException("runtime match does not match authoritative orders");
            }
            applyFill(runtime, identities, instrument, taker, match.price(),
                    match.size(), true, settleAssetId, treasuryDelta);
            applyFill(runtime, identities, instrument, maker, match.price(),
                    match.size(), false, settleAssetId, treasuryDelta);
            if (runtime.order(maker.orderId()).canceled()) {
                long releaseUnits = runtime.reservation(maker.orderId()).reservedUnits();
                runtime.releaseTerminalReservation(maker.orderId());
                if (releaseUnits > 0) runtime.advanceUserRevision(maker.userId());
            }
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(terminal(taker));
        }
        if (runtime.order(takerOrderId).canceled()) {
            long releaseUnits = runtime.reservation(takerOrderId).reservedUnits();
            runtime.releaseTerminalReservation(takerOrderId);
            if (releaseUnits > 0) runtime.advanceUserRevision(runtime.order(takerOrderId).userId());
        }
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return runtime;
    }

    static void validateAndPrepare(long takerOrderId, List<MatcherEvent> matches,
                                   TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        validateMatches(runtime, taker, matches);
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
        identities.positionKey(taker.userId(), positionKey(instrument.symbol(), taker.positionSide()));
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            identities.positionKey(maker.userId(), positionKey(instrument.symbol(), maker.positionSide()));
        }
    }

    static void applyLane(long takerOrderId, List<MatcherEvent> matches,
                          TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                          CoreInstrumentState instrument, int settleAssetId,
                          RuntimeTreasuryDelta treasuryDelta) {
        if (treasuryDelta == null) throw new IllegalArgumentException("treasury delta is required");
        OrderRuntime localTaker = runtime.order(takerOrderId);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            if (localTaker != null) {
                localTaker = requireOpen(runtime, takerOrderId);
                applyFill(runtime, identities, instrument, localTaker, match.price(), match.size(), true,
                        settleAssetId, treasuryDelta);
            }
            OrderRuntime maker = runtime.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(runtime, maker.orderId());
                applyFill(runtime, identities, instrument, maker, match.price(), match.size(), false,
                        settleAssetId, treasuryDelta);
                if (runtime.order(maker.orderId()).canceled()) {
                    long releaseUnits = runtime.reservation(maker.orderId()).reservedUnits();
                    runtime.releaseTerminalReservation(maker.orderId());
                    if (releaseUnits > 0) runtime.advanceUserRevision(maker.userId());
                }
            }
        }
        localTaker = runtime.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.replaceOrder(terminal(localTaker));
            }
            if (runtime.order(takerOrderId).canceled()) {
                long releaseUnits = runtime.reservation(takerOrderId).reservedUnits();
                runtime.releaseTerminalReservation(takerOrderId);
                if (releaseUnits > 0) runtime.advanceUserRevision(localTaker.userId());
            }
        }
    }

    static void applyLane(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                          TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                          CoreInstrumentState instrument, int settleAssetId,
                          RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement plan");
        }
        OrderRuntime localTaker = runtime.order(takerOrderId);
        if (plan.tradeCount() <= 1) {
            applyLaneDirect(takerOrderId, plan, laneId, runtime, identities, instrument,
                    settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
        } else {
            applyLaneAccumulated(takerOrderId, plan, laneId, runtime, identities, instrument,
                    settleAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        }
        localTaker = runtime.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.replaceOrder(localTaker.withStatus(CoreOrderStatus.CANCELED,
                        Math.incrementExact(localTaker.revision()), commitTimestamp, commitPosition));
            }
            if (runtime.order(takerOrderId).canceled()) {
                long releaseUnits = runtime.reservation(takerOrderId).reservedUnits();
                runtime.releaseTerminalReservation(takerOrderId);
                if (releaseUnits > 0) runtime.advanceUserRevision(localTaker.userId());
            }
        }
    }

    private static void applyLaneDirect(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        CoreInstrumentState instrument, int settleAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition) {
        OrderRuntime localTaker = runtime.order(takerOrderId);
        for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(runtime, takerOrderId);
                applyFill(runtime, identities, instrument, localTaker, match.price(), match.size(), true,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            OrderRuntime maker = runtime.order(match.matchedOrderId());
            if (maker != null) {
                maker = requireOpen(runtime, maker.orderId());
                applyFill(runtime, identities, instrument, maker, match.price(), match.size(), false,
                        settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
                if (runtime.order(maker.orderId()).canceled()) {
                    long releaseUnits = runtime.reservation(maker.orderId()).reservedUnits();
                    runtime.releaseTerminalReservation(maker.orderId());
                    if (releaseUnits > 0) runtime.advanceUserRevision(maker.userId());
                }
            }
        }
    }

    private static void applyLaneAccumulated(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                                             TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             CoreInstrumentState instrument, int settleAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             OrderRuntime localTaker) {
        DerivativeSettlementAccumulator accumulator = ACCUMULATOR.get();
        accumulator.reset(runtime, identities, instrument, settleAssetId, commitTimestamp, commitPosition);
        try {
            for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                    index = plan.nextMatcherEvent(index, laneId)) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE
                        || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
                if (localTaker != null) {
                    accumulator.apply(takerOrderId, true, match.price(), match.size(), treasuryDelta);
                }
                OrderRuntime maker = runtime.order(match.matchedOrderId());
                if (maker != null) {
                    accumulator.apply(maker.orderId(), false, match.price(), match.size(), treasuryDelta);
                }
            }
            accumulator.publish();
        } finally { accumulator.clear(); }
    }

    /** Coalesces derivative maker and taker fills into one immutable publish per order. */
    private static final class DerivativeSettlementAccumulator {
        private final LongObjectHashMap<RuntimeDerivativeFillCalculator.FillCursor> cursors =
                new LongObjectHashMap<>();
        private final LongLongHashMap activeOrderByUser = new LongLongHashMap();
        private final java.util.ArrayDeque<RuntimeDerivativeFillCalculator.FillCursor> free =
                new java.util.ArrayDeque<>();
        private TradingRuntimeState runtime;
        private RuntimeIdentityRegistry identities;
        private CoreInstrumentState instrument;
        private int settleAssetId;
        private long commitTimestamp;
        private long commitPosition;

        void reset(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                   CoreInstrumentState instrument, int settleAssetId,
                   long commitTimestamp, long commitPosition) {
            this.runtime = runtime;
            this.identities = identities;
            this.instrument = instrument;
            this.settleAssetId = settleAssetId;
            this.commitTimestamp = commitTimestamp;
            this.commitPosition = commitPosition;
        }

        void apply(long orderId, boolean taker, long price, long quantity,
                   RuntimeTreasuryDelta treasury) {
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                OrderRuntime order = requireOpen(runtime, orderId);
                long activeOrder = activeOrderByUser.get(order.userId());
                if (activeOrder != 0 && activeOrder != orderId) flush(activeOrder);
                Long configured = runtime.leverage(
                        new CoreLeverageKey(order.userId(), instrument.symbol(), order.marginMode()));
                long leverage = configured == null ? instrument.maxLeveragePpm() : configured;
                long key = identities.preparedPositionKey(order.userId(),
                        positionKey(instrument.symbol(), order.positionSide()));
                cursor = free.pollFirst();
                if (cursor == null) cursor = new RuntimeDerivativeFillCalculator.FillCursor();
                RuntimeDerivativeFillCalculator.begin(cursor, runtime, instrument, order, key,
                        leverage, settleAssetId, commitTimestamp, commitPosition);
                cursors.put(orderId, cursor);
                activeOrderByUser.put(order.userId(), orderId);
            }
            cursor.applyNext(price, quantity, taker, treasury);
        }

        void publish() {
            cursors.forEachKeyValue((orderId, cursor) -> publishCursor(cursor));
        }

        void clear() {
            cursors.forEachKeyValue((orderId, cursor) -> {
                cursor.clear();
                free.addFirst(cursor);
            });
            cursors.clear();
            activeOrderByUser.clear();
            runtime = null;
            identities = null;
            instrument = null;
        }

        private void flush(long orderId) {
            RuntimeDerivativeFillCalculator.FillCursor cursor = cursors.remove(orderId);
            if (cursor == null) throw new IllegalStateException("active derivative cursor is missing");
            publishCursor(cursor);
            cursor.clear();
            free.addFirst(cursor);
        }

        private void publishCursor(RuntimeDerivativeFillCalculator.FillCursor cursor) {
            OrderRuntime order = cursor.order();
            cursor.publish(runtime, cursor.positionKey(), null);
            if (order.canceled()) {
                long releaseUnits = runtime.reservation(order.orderId()).reservedUnits();
                runtime.releaseTerminalReservation(order.orderId());
                if (releaseUnits > 0) runtime.advanceUserRevision(order.userId());
            }
        }
    }

    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        long takerRemaining = taker.remainingQuantitySteps();
        LongLongHashMap makerRemaining = runtime.matcherSettlementRemainingScratch();
        makerRemaining.clear();
        try {
            for (MatcherEvent match : matches) {
                if (match == null) {
                    throw new IllegalArgumentException("runtime match is required");
                }
                if (match.eventType() != MatcherEventType.TRADE) continue;
                OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
                if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                if (match.price() <= 0 || match.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                takerRemaining = Math.subtractExact(takerRemaining, match.size());
                long remaining = makerRemaining.containsKey(maker.orderId())
                        ? makerRemaining.get(maker.orderId()) : maker.remainingQuantitySteps();
                remaining = Math.subtractExact(remaining, match.size());
                if (takerRemaining < 0 || remaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                makerRemaining.put(maker.orderId(), remaining);
            }
        } finally {
            makerRemaining.clear();
        }
    }



    private static void applyFill(TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta) {
        applyFill(runtime, identities, instrument, order, priceTicks, quantitySteps, taker,
                settleAssetId, treasuryDelta, -1, -1);
    }

    private static void applyFill(TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
        Long configuredLeverage = runtime.leverage(
                new CoreLeverageKey(order.userId(), instrument.symbol(), order.marginMode()));
        long leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        RuntimeDerivativeFillCalculator.apply(runtime, identities, instrument, order,
                identities.preparedPositionKey(order.userId(), positionKey(instrument.symbol(), order.positionSide())),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta, commitTimestamp, commitPosition);
    }

    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }

    private static String positionKey(String symbol,
                                      com.surprising.aeron.protocol.CorePositionSide positionSide) {
        return positionSide == com.surprising.aeron.protocol.CorePositionSide.NET
                ? symbol : symbol + ':' + positionSide.name();
    }

    private static OrderRuntime terminal(OrderRuntime order) {
        return order.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(order.revision()));
    }
}
