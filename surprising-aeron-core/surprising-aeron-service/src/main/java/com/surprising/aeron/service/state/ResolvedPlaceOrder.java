package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;

public record ResolvedPlaceOrder(
        PlaceOrderCommand intent,
        CoreInstrumentState instrument,
        int symbolId,
        long matchingPriceTicks,
        long reservationPriceTicks,
        long markPriceTicks,
        long indexPriceTicks,
        long forwardPriceTicks,
        long markPriceSequence,
        ReservationKind reservationKind,
        String reservationAsset,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long feePolicyVersion) {

    public ResolvedPlaceOrder {
        if (intent == null || instrument == null || symbolId < -1
                || matchingPriceTicks <= 0 || reservationPriceTicks <= 0
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || markPriceSequence < 0 || reservationKind == null
                || reservationAsset == null || reservationAsset.isBlank()
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm || feePolicyVersion < 0) {
            throw new IllegalArgumentException("invalid resolved place order");
        }
        reservationAsset = AssetBalance.normalizeAsset(reservationAsset);
    }

    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrumentState instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              long markPriceSequence, ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm, long feePolicyVersion) {
        this(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks, markPriceTicks,
                0, 0, markPriceSequence, reservationKind, reservationAsset, makerFeeRatePpm,
                takerFeeRatePpm, feePolicyVersion);
    }

    public long orderId() { return intent.orderId(); }
    public String symbol() { return intent.symbol(); }
    public long instrumentVersion() { return intent.instrumentVersion(); }
    public com.surprising.aeron.protocol.CoreOrderSide side() { return intent.side(); }
    public long limitPriceTicks() { return intent.limitPriceTicks(); }
    public long quantitySteps() { return intent.quantitySteps(); }
    public boolean reduceOnly() { return intent.reduceOnly(); }
    public com.surprising.aeron.protocol.CoreMarginMode marginMode() { return intent.marginMode(); }
    public com.surprising.aeron.protocol.CorePositionSide positionSide() { return intent.positionSide(); }
    public com.surprising.aeron.protocol.CoreOrderType orderType() { return intent.orderType(); }
    public com.surprising.aeron.protocol.CoreTimeInForce timeInForce() { return intent.timeInForce(); }
    public boolean postOnly() { return intent.postOnly(); }
    public String clientOrderId() { return intent.clientOrderId(); }
}
