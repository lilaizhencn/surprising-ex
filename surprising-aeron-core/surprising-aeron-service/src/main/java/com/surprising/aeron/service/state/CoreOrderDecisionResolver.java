package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;

public final class CoreOrderDecisionResolver {

    static final long MARKET_MAX_SLIPPAGE_PPM = 10_000L;
    static final long MARKET_MAX_MARK_AGE_MILLIS = 5_000L;
    private static final long PPM = 1_000_000L;

    private CoreOrderDecisionResolver() {
    }

    public static ResolvedPlaceOrder resolve(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (runtime == null || identities == null || intent == null || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        CoreInstrumentState instrument = runtime.instrument(intent.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.version() != intent.instrumentVersion()) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }

        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis()) {
            Integer symbolId = identities.findSymbolId(instrument.symbol());
            boolean settled = symbolId != null && runtime.treasury().lifecycleSettlement(symbolId) != 0;
            throw new CoreStateRejectedException(settled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND",
                    "expired instrument cannot accept new orders");
        }

        boolean spotLimit = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                && intent.orderType() == CoreOrderType.LIMIT;
        Integer preparedSymbolId = identities.findSymbolId(instrument.symbol());
        if (preparedSymbolId == null) {
            throw new IllegalStateException("instrument symbol identity is missing");
        }
        MarkPriceRuntime mark = spotLimit ? null : runtime.markPrice(preparedSymbolId);
        if (!spotLimit) requireFreshMark(mark, instrument, clusterTimestamp);
        long markPriceTicks = spotLimit ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spotLimit ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spotLimit ? 0 : mark.forwardPriceTicks();
        long markPriceSequence = spotLimit ? 0 : mark.priceSequence();
        long matchingPriceTicks = intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        CoreFeeRate fee = runtime.resolveFee(userId, instrument.symbol(), clusterTimestamp, instrument);
        return new ResolvedPlaceOrder(intent, instrument, preparedSymbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, markPriceSequence, reservationKind, reservationAsset,
                fee.makerFeeRatePpm(), fee.takerFeeRatePpm(), fee.policyVersion());
    }

    public static ResolvedPlaceOrder resolve(TradingCoreState state, PlaceOrderCommand intent) {
        if (state == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(intent.symbol()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.version() != intent.instrumentVersion()) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        boolean spotLimit = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                && intent.orderType() == CoreOrderType.LIMIT;
        CoreMarkPriceState mark = spotLimit ? null : state.riskState().markPrices().get(instrument.symbol());
        if (!spotLimit && (mark == null || mark.instrumentVersion() != instrument.version())) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long markPriceTicks = spotLimit ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spotLimit ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spotLimit ? 0 : mark.forwardPriceTicks();
        long markPriceSequence = spotLimit ? 0 : mark.priceSequence();
        long matchingPriceTicks = intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        return new ResolvedPlaceOrder(intent, instrument, -1, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, markPriceSequence, reservationKind, reservationAsset,
                instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm(), 0);
    }

    private static void requireFreshMark(MarkPriceRuntime mark, CoreInstrumentState instrument,
                                         long clusterTimestamp) {
        if (mark == null || mark.instrumentVersion() != instrument.version()) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long age = Math.subtractExact(clusterTimestamp, mark.generatedAtEpochMillis());
        if (age < 0 || age > MARKET_MAX_MARK_AGE_MILLIS) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the Core freshness bound");
        }
    }

    private static long protectedPrice(CoreOrderSide side, long markPriceTicks) {
        long factor = side == CoreOrderSide.BUY
                ? 1_000_000L + MARKET_MAX_SLIPPAGE_PPM
                : 1_000_000L - MARKET_MAX_SLIPPAGE_PPM;
        return Math.max(1, scalePpm(markPriceTicks, factor, side == CoreOrderSide.BUY));
    }

    private static long reservationPrice(PlaceOrderCommand intent, CoreInstrumentState instrument,
                                         long markPriceTicks, long matchingPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return matchingPriceTicks;
        }
        if (instrument.contractType().isOption()) {
            return matchingPriceTicks;
        }
        long lower = boundedMark(markPriceTicks, 1_000_000L - MARKET_MAX_SLIPPAGE_PPM, false);
        long upper = boundedMark(markPriceTicks, 1_000_000L + MARKET_MAX_SLIPPAGE_PPM, true);
        if (intent.orderType() == CoreOrderType.MARKET) {
            return instrument.contractType().isInverse() ? lower : upper;
        }
        if (instrument.contractType().isInverse() && intent.side() == CoreOrderSide.BUY) {
            return Math.min(intent.limitPriceTicks(), lower);
        }
        if (instrument.contractType().isLinear() && intent.side() == CoreOrderSide.SELL) {
            return Math.max(intent.limitPriceTicks(), upper);
        }
        return intent.limitPriceTicks();
    }

    private static long boundedMark(long markPriceTicks, long factor, boolean ceiling) {
        return Math.max(1, scalePpm(markPriceTicks, factor, ceiling));
    }

    static long scalePpm(long value, long factor, boolean ceiling) {
        long whole = Math.multiplyExact(value / PPM, factor);
        long remainderProduct = Math.multiplyExact(value % PPM, factor);
        long scaled = Math.addExact(whole, remainderProduct / PPM);
        return ceiling && remainderProduct % PPM != 0 ? Math.incrementExact(scaled) : scaled;
    }
}
