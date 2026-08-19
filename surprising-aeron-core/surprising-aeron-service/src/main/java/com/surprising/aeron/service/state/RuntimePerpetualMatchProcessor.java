package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatch;
import java.util.List;

/** Runs a complete perpetual match batch on a discardable Runtime projection. */
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
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        CoreOrderState takerState = before.orders().get(takerOrderId);
        CoreInstrumentState instrument = before.instruments().get(takerState.symbol());
        if (instrument == null || instrument.version() != taker.instrumentVersion()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
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
            if (runtime.order(maker.orderId()).canceled()) runtime.releaseTerminalReservation(maker.orderId());
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(terminal(taker));
        }
        if (runtime.order(takerOrderId).canceled()) runtime.releaseTerminalReservation(takerOrderId);
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
        return new OrderRuntime(order.orderId(), order.userId(), order.symbolId(), order.instrumentVersion(),
                order.side(), order.priceTicks(), order.reduceOnly(), order.marginMode(), order.positionSide(),
                order.orderType(), order.timeInForce(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(), true);
    }
}
