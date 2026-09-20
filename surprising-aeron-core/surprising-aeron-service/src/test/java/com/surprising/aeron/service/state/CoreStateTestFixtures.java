package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class CoreStateTestFixtures {

    private static final CoreInstrument RUNTIME_INSTRUMENT = CoreInstrument.from(
            ProductLine.LINEAR_PERPETUAL,
            instrument(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", "BTC", "USDT", "USDT"));

    private CoreStateTestFixtures() {
    }

    static TradingCoreState withInstrument(TradingCoreReducer reducer, ProductLine productLine) {
        return reducer.registerInstrument(TradingCoreState.empty(productLine), instrument(productLine,
                "BTC-USDT", "BTC", "USDT", settleAsset(productLine)));
    }

    static RegisterInstrumentCommand instrument(
            ProductLine productLine,
            String symbol,
            String baseAsset,
            String quoteAsset,
            String settleAsset) {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        int optionType = type.isOption() ? 0 : -1;
        long strike = type.isOption() ? 100 : 0;
        return new RegisterInstrumentCommand(symbol, type.ordinal(), baseAsset, quoteAsset, settleAsset,
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                expiry, optionType, strike);
    }

    static String settleAsset(ProductLine productLine) {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    static CoreInstrument runtimeInstrument() {
        return RUNTIME_INSTRUMENT;
    }

    static OrderRuntime order(long orderId, long userId, int symbolId, long quantitySteps) {
        return order(orderId, userId, symbolId, quantitySteps, false);
    }

    static OrderRuntime order(long orderId, long userId, int symbolId, long quantitySteps, boolean canceled) {
        return new OrderRuntime(orderId, userId, symbolId, RUNTIME_INSTRUMENT, CoreOrderSide.BUY, 0,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 0, 0, quantitySteps, 0, quantitySteps, canceled);
    }

    static ReservationRuntime reservation(long orderId, long userId, int assetId, long remainingUnits) {
        return new ReservationRuntime(orderId, userId, 0, ReservationKind.DERIVATIVE_MARGIN,
                assetId, Math.max(1, remainingUnits), 0, 0, 1);
    }

    static void reserveOrder(TradingRuntimeState runtime, long orderId, long userId, long clientKey,
                             int symbolId, long quantitySteps, int assetId, long reservedUnits) {
        OrderRuntime order = order(orderId, userId, symbolId, quantitySteps);
        ReservationRuntime reservation = new ReservationRuntime(orderId, userId, symbolId,
                ReservationKind.DERIVATIVE_MARGIN, assetId,
                Math.max(1, reservedUnits), 0, 0, quantitySteps);
        runtime.reserveOrder(order, reservation, clientKey, reservedUnits);
    }
}
