package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.AssetBalance;

/** Deterministic order input. Ordinary PLACE stores these fields in its pooled admission event. */
public class ResolvedPlaceOrder {
    private PlaceOrderCommand intent;
    private CoreInstrument instrument;
    private int symbolId;
    private long matchingPriceTicks;
    private long reservationPriceTicks;
    private long markPriceTicks;
    private long indexPriceTicks;
    private long forwardPriceTicks;
    private ReservationKind reservationKind;
    private String reservationAsset;
    private long makerFeeRatePpm;
    private long takerFeeRatePpm;

    /** Only a pooled subtype may start empty and initialize before publication. */
    protected ResolvedPlaceOrder() {
    }

    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              long indexPriceTicks, long forwardPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        initializeResolvedOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFeeRatePpm, takerFeeRatePpm);
    }

    public ResolvedPlaceOrder(PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
                              long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
                              ReservationKind reservationKind, String reservationAsset,
                              long makerFeeRatePpm, long takerFeeRatePpm) {
        this(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks, markPriceTicks,
                0, 0, reservationKind, reservationAsset, makerFeeRatePpm, takerFeeRatePpm);
    }

    protected final void initializeResolvedOrder(
            PlaceOrderCommand intent, CoreInstrument instrument, int symbolId,
            long matchingPriceTicks, long reservationPriceTicks, long markPriceTicks,
            long indexPriceTicks, long forwardPriceTicks, ReservationKind reservationKind,
            String reservationAsset, long makerFeeRatePpm, long takerFeeRatePpm) {
        if (this.intent != null || intent == null || instrument == null || symbolId < -1
                || matchingPriceTicks <= 0 || reservationPriceTicks <= 0
                || markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                || (indexPriceTicks == 0) != (forwardPriceTicks == 0)
                || reservationKind == null || reservationAsset == null || reservationAsset.isBlank()
                || makerFeeRatePpm < -1_000_000 || makerFeeRatePpm > 1_000_000
                || takerFeeRatePpm < -1_000_000 || takerFeeRatePpm > 1_000_000
                || makerFeeRatePpm > takerFeeRatePpm) {
            throw new IllegalArgumentException("invalid resolved place order");
        }
        this.intent = intent;
        this.instrument = instrument;
        this.symbolId = symbolId;
        this.matchingPriceTicks = matchingPriceTicks;
        this.reservationPriceTicks = reservationPriceTicks;
        this.markPriceTicks = markPriceTicks;
        this.indexPriceTicks = indexPriceTicks;
        this.forwardPriceTicks = forwardPriceTicks;
        this.reservationKind = reservationKind;
        this.reservationAsset = AssetBalance.normalizeAsset(reservationAsset);
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
    }

    protected final void clearResolvedOrder() {
        intent = null;
        instrument = null;
        symbolId = 0;
        matchingPriceTicks = reservationPriceTicks = markPriceTicks = 0;
        indexPriceTicks = forwardPriceTicks = 0;
        reservationKind = null;
        reservationAsset = null;
        makerFeeRatePpm = takerFeeRatePpm = 0;
    }

    protected final boolean resolvedOrderInitialized() { return intent != null; }
    public PlaceOrderCommand intent() { return intent; }
    public CoreInstrument instrument() { return instrument; }
    public int symbolId() { return symbolId; }
    public long matchingPriceTicks() { return matchingPriceTicks; }
    public long reservationPriceTicks() { return reservationPriceTicks; }
    public long markPriceTicks() { return markPriceTicks; }
    public long indexPriceTicks() { return indexPriceTicks; }
    public long forwardPriceTicks() { return forwardPriceTicks; }
    public ReservationKind reservationKind() { return reservationKind; }
    public String reservationAsset() { return reservationAsset; }
    public long makerFeeRatePpm() { return makerFeeRatePpm; }
    public long takerFeeRatePpm() { return takerFeeRatePpm; }
    public long orderId() { return intent.orderId(); }
    public String symbol() { return intent.symbol(); }
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
