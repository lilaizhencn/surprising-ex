package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuntimePerpetualMatchProcessor {

    private RuntimePerpetualMatchProcessor() {
    }

    public static TradingRuntimeState simulate(TradingCoreState before, long takerOrderId,
                                               List<CoreMatch> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return apply(before, takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState apply(TradingCoreState before, long takerOrderId,
                                            List<CoreMatch> matches, TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || runtime == null || identities == null
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid perpetual match apply");
        }
        runtime.assertOwner();
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        CoreOrderState takerState = before.orders().get(takerOrderId);
        if (takerState == null) {
            throw new IllegalStateException("runtime match taker is missing from authoritative orders");
        }
        CoreInstrumentState instrument = before.instruments().get(takerState.symbol());
        if (instrument == null || instrument.version() != taker.instrumentVersion()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        validateMatches(runtime, taker, matches);
        int settleAssetId = identities.assetId(instrument.settleAsset());
        for (CoreMatch match : matches) {
            taker = requireOpen(runtime, takerOrderId);
            OrderRuntime maker = requireOpen(runtime, match.makerOrderId());
            if (maker.userId() != match.makerUserId() || maker.symbolId() != taker.symbolId()
                    || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                throw new IllegalStateException("runtime match does not match authoritative orders");
            }
            applyFill(before, runtime, identities, instrument, taker, match.priceTicks(),
                    match.quantitySteps(), true, settleAssetId);
            applyFill(before, runtime, identities, instrument, maker, match.priceTicks(),
                    match.quantitySteps(), false, settleAssetId);
            if (runtime.order(maker.orderId()).canceled()) {
                runtime.releaseTerminalReservation(maker.orderId());
                runtime.advanceUserRevision(maker.userId());
            }
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(terminal(taker));
        }
        if (runtime.order(takerOrderId).canceled()) {
            runtime.releaseTerminalReservation(takerOrderId);
            runtime.advanceUserRevision(runtime.order(takerOrderId).userId());
        }
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
        return runtime;
    }

    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<CoreMatch> matches) {
        long takerRemaining = taker.remainingQuantitySteps();
        Map<Long, Long> makerRemaining = new HashMap<>();
        for (CoreMatch match : matches) {
            if (match == null) {
                throw new IllegalArgumentException("runtime match is required");
            }
            OrderRuntime maker = requireOpen(runtime, match.makerOrderId());
            if (maker.userId() != match.makerUserId() || maker.symbolId() != taker.symbolId()
                    || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                throw new IllegalStateException("runtime match does not match authoritative orders");
            }
            if (match.priceTicks() <= 0 || match.quantitySteps() <= 0) {
                throw new IllegalArgumentException("invalid runtime match price or quantity");
            }
            takerRemaining = Math.subtractExact(takerRemaining, match.quantitySteps());
            long remaining = makerRemaining.getOrDefault(maker.orderId(), maker.remainingQuantitySteps());
            remaining = Math.subtractExact(remaining, match.quantitySteps());
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
                                                         long takerOrderId, List<CoreMatch> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<CoreMatch> matches,
                                                       TradingRuntimeState runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isFundingProduct()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        apply(before, takerOrderId, matches, runtime, identities);
        for (Map.Entry<Long, CoreUserState> entry : expected.users().entrySet()) {
            UserRuntime actual = runtime.user(entry.getKey());
            CoreUserState planned = entry.getValue();
            if (actual == null || actual.productLine() != planned.productLine()) {
                throw new IllegalStateException("runtime match user is missing: " + entry.getKey());
            }
            if (actual.revision() != planned.revision()) {
                runtime.putUser(new UserRuntime(actual.productLine(), actual.userId(), planned.revision(),
                        actual.positionMode()));
            }
        }
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }

    private static void applyFill(TradingCoreState before, TradingRuntimeState runtime,
                                  RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                  OrderRuntime order, long priceTicks, long quantitySteps,
                                  boolean taker, int settleAssetId) {
        long leverage = before.leverages().getOrDefault(
                new CoreLeverageKey(order.userId(), instrument.symbol(), order.marginMode()),
                instrument.maxLeveragePpm());
        RuntimePerpetualFillCalculator.apply(runtime, identities, instrument, order,
                identities.positionKey(order.userId(), positionKey(instrument.symbol(), order.positionSide())),
                priceTicks, quantitySteps, taker, leverage, settleAssetId);
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
