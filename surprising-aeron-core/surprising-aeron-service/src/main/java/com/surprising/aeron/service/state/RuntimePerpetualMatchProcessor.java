package com.surprising.aeron.service.state;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class RuntimePerpetualMatchProcessor {

    private RuntimePerpetualMatchProcessor() {
    }

    public static TradingRuntimeState simulate(TradingCoreState before, long takerOrderId,
                                               List<MatcherEvent> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyRuntime(takerOrderId, matches, runtime, identities);
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
        if (instrument == null || instrument.version() != taker.instrumentVersion()) {
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

    static void validateAndPrepareBatch(List<Long> takerOrderIds, List<CoreMatchingResult> matchingResults,
                                        TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        BatchValidationScratch scratch) {
        if (takerOrderIds == null || matchingResults == null || takerOrderIds.isEmpty()
                || takerOrderIds.size() != matchingResults.size() || scratch == null) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement batch");
        }
        LongLongHashMap remainingByOrderId = scratch.remainingByOrderId;
        LongHashSet terminalOrderIds = scratch.terminalOrderIds;
        scratch.clear();
        try {
            for (int index = 0; index < takerOrderIds.size(); index++) {
                long takerOrderId = takerOrderIds.get(index);
                List<MatcherEvent> matches = matchingResults.get(index).matcherEvents();
                OrderRuntime taker = requireOpen(runtime, takerOrderId);
                if (terminalOrderIds.contains(takerOrderId)) {
                    throw new IllegalStateException("runtime matched order is not open: " + takerOrderId);
                }
                CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
                if (instrument == null || instrument.version() != taker.instrumentVersion()) {
                    throw new IllegalStateException("runtime match instrument is missing");
                }
                identities.positionKey(taker.userId(), positionKey(instrument.symbol(), taker.positionSide()));
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
                    identities.positionKey(maker.userId(),
                            positionKey(instrument.symbol(), maker.positionSide()));
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
                          RuntimeTreasuryDelta treasuryDelta) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid perpetual matcher settlement plan");
        }
        OrderRuntime localTaker = runtime.order(takerOrderId);
        for (int index = 0; index < plan.tradeEventCount(); index++) {
            if (!plan.tradeTouchesLane(index, laneId)) continue;
            MatcherEvent match = plan.tradeEvent(index);
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

    static final class BatchValidationScratch {
        private final LongLongHashMap remainingByOrderId = new LongLongHashMap();
        private final LongHashSet terminalOrderIds = new LongHashSet();

        private void clear() {
            remainingByOrderId.clear();
            terminalOrderIds.clear();
        }
    }

    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        long takerRemaining = taker.remainingQuantitySteps();
        Map<Long, Long> makerRemaining = new HashMap<>();
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
            long remaining = makerRemaining.getOrDefault(maker.orderId(), maker.remainingQuantitySteps());
            remaining = Math.subtractExact(remaining, match.size());
            if (takerRemaining < 0 || remaining < 0) {
                throw new IllegalStateException("fill exceeds runtime order remaining quantity");
            }
            makerRemaining.put(maker.orderId(), remaining);
        }
    }

    /**
     * Executes the native fill and applies the reducer-owned user revision plan for an asynchronous match batch.
     * User revisions encode command-lifecycle transitions (including reservation release), which cannot be inferred
     * from the fill alone once the reservation was created by an earlier command.
     */
    public static TradingRuntimeState simulateTransition(TradingCoreState before, TradingCoreState expected,
                                                         long takerOrderId, List<MatcherEvent> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<MatcherEvent> matches,
                                                       TradingRuntimeState runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isDerivative()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
        for (Long userId : expected.changedUserIds()) {
            CoreUserState planned = expected.users().get(userId);
            if (planned == null) {
                throw new IllegalStateException("runtime match changed user is missing: " + userId);
            }
            UserRuntime actual = runtime.user(userId);
            if (actual == null || actual.productLine() != planned.productLine()) {
                throw new IllegalStateException("runtime match user is missing: " + userId);
            }
            if (actual.revision() != planned.revision()) {
                runtime.putUser(new UserRuntime(actual.productLine(), userId, planned.revision(),
                        actual.positionMode()));
            }
        }
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }

    private static void applyFill(TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId, RuntimeTreasuryDelta treasuryDelta) {
        Long configuredLeverage = runtime.leverage(
                new CoreLeverageKey(order.userId(), instrument.symbol(), order.marginMode()));
        long leverage = configuredLeverage == null ? instrument.maxLeveragePpm() : configuredLeverage;
        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, order,
                identities.preparedPositionKey(order.userId(), positionKey(instrument.symbol(), order.positionSide())),
                priceTicks, quantitySteps, taker, leverage, settleAssetId, treasuryDelta);
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
