package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.model.CoreFeeRate;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;

public final class CoreOrderDecisionResolver {

    private static final long PPM = 1_000_000L;

    private CoreOrderDecisionResolver() {
    }

    public static ResolvedPlaceOrder resolve(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (runtime == null || identities == null || intent == null || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        Integer symbolId = identities.findSymbolId(instrument.instrumentId());
        if (symbolId == null) throw new IllegalStateException("instrument instrumentId identity is missing");
        return resolveValues(null, instrument, symbolId, runtime.markPrice(symbolId),
                runtime.resolveFee(userId, intent.instrumentId(), clusterTimestamp, instrument),
                clusterTimestamp, runtime.treasury().lifecycleSettlement(symbolId) != 0, intent);
    }

    /** Resolves the ordinary PLACE directly into its existing pooled admission event. */
    public static PlaceAdmissionEvent resolveInto(PlaceAdmissionEvent target,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
            long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (target == null || runtime == null || identities == null || intent == null
                || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        Integer symbolId = identities.findSymbolId(instrument.instrumentId());
        if (symbolId == null) throw new IllegalStateException("instrument instrumentId identity is missing");
        resolveValues(target, instrument, symbolId, runtime.markPrice(symbolId),
                runtime.resolveFee(userId, intent.instrumentId(), clusterTimestamp, instrument),
                clusterTimestamp, runtime.treasury().lifecycleSettlement(symbolId) != 0, intent);
        return target;
    }

    /** 同一批、同一用户和币对的只读决策上下文；不跨命令复用。 */
    public record Context(CoreInstrument instrument, int symbolId, MarkPriceRuntime mark,
                          CoreFeeRate fee, long clusterTimestamp, boolean lifecycleSettled,
                          boolean fundingInProgress) { }

    public static Context context(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, String instrumentId, long clusterTimestamp) {
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        Integer symbolId = identities.findSymbolId(instrument.instrumentId());
        if (symbolId == null) throw new IllegalStateException("instrument instrumentId identity is missing");
        return new Context(instrument, symbolId, runtime.markPrice(symbolId),
                runtime.resolveFee(userId, instrumentId, clusterTimestamp, instrument), clusterTimestamp,
                runtime.treasury().lifecycleSettlement(symbolId) != 0,
                runtime.treasury().fundingProgress(symbolId) != null);
    }

    public static ResolvedPlaceOrder resolve(Context context, PlaceOrderCommand intent) {
        if (context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        return resolveValues(null, context.instrument(), context.symbolId(), context.mark(), context.fee(),
                context.clusterTimestamp(), context.lifecycleSettled(), intent);
    }

    private static ResolvedPlaceOrder resolveValues(PlaceAdmissionEvent target,
                                                    CoreInstrument instrument, int symbolId,
                                                    MarkPriceRuntime mark, CoreFeeRate fee,
                                                    long clusterTimestamp, boolean lifecycleSettled,
                                                    PlaceOrderCommand intent) {
        if (!instrument.instrumentId().equals(intent.instrumentId())) throw new IllegalArgumentException("decision context instrumentId mismatch");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis())
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        boolean spotLimit = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                && intent.orderType() == CoreOrderType.LIMIT;
        if (!spotLimit) {
            requireFreshMark(mark, instrument, clusterTimestamp);
            if (intent.orderType() == CoreOrderType.LIMIT && instrument.orderProtection().limitPriceProtectionEnabled()
                    && clusterTimestamp - mark.generatedAtEpochMillis() > instrument.orderProtection().limitPriceMaxMarkAgeMs())
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the limit protection freshness bound");
        }
        long markPriceTicks = spotLimit ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spotLimit ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spotLimit ? 0 : mark.forwardPriceTicks();
        long matchingPriceTicks = intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        if (target == null) {
            return new ResolvedPlaceOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                    markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                    fee.makerFeeRatePpm(), fee.takerFeeRatePpm());
        }
        target.resolve(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                fee.makerFeeRatePpm(), fee.takerFeeRatePpm());
        return target;
    }

    public static ResolvedPlaceOrder resolve(TradingCoreState state, PlaceOrderCommand intent) {
        if (state == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        CoreInstrument instrument = state.instruments().get(OrderReservation.requireInstrumentId(intent.instrumentId()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        instrument.requireOrderEnabled(intent);
        boolean spotLimit = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                && intent.orderType() == CoreOrderType.LIMIT;
        CoreMarkPriceState mark = spotLimit ? null : state.riskState().markPrices().get(instrument.instrumentId());
        if (!spotLimit && mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long markPriceTicks = spotLimit ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spotLimit ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spotLimit ? 0 : mark.forwardPriceTicks();
        long matchingPriceTicks = intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        return new ResolvedPlaceOrder(intent, instrument, -1, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm());
    }

    private static void requireLimitPriceBand(PlaceOrderCommand intent, CoreInstrument instrument, long markPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || intent.orderType() != CoreOrderType.LIMIT || !instrument.orderProtection().limitPriceProtectionEnabled()) return;
        long boundary = protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().limitPriceBandPpm());
        if (intent.side() == CoreOrderSide.BUY ? intent.limitPriceTicks() > boundary : intent.limitPriceTicks() < boundary)
            throw new CoreStateRejectedException("INVALID_COMMAND", "limit price exceeds configured mark price band");
    }

    private static void requireFreshMark(MarkPriceRuntime mark, CoreInstrument instrument,
                                         long clusterTimestamp) {
        if (mark == null || mark.instrument() != instrument) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long age = Math.subtractExact(clusterTimestamp, mark.generatedAtEpochMillis());
        if (age < 0 || age > instrument.orderProtection().marketMaxMarkAgeMs()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the Core freshness bound");
        }
    }

    private static long protectedPrice(CoreOrderSide side, long markPriceTicks, long slippagePpm) {
        long factor = side == CoreOrderSide.BUY
                ? 1_000_000L + slippagePpm
                : 1_000_000L - slippagePpm;
        return Math.max(1, scalePpm(markPriceTicks, factor, side == CoreOrderSide.BUY));
    }

    private static long reservationPrice(PlaceOrderCommand intent, CoreInstrument instrument,
                                         long markPriceTicks, long matchingPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return matchingPriceTicks;
        }
        if (instrument.contractType().isOption()) {
            return matchingPriceTicks;
        }
        long slippagePpm = instrument.orderProtection().marketMaxSlippagePpm();
        long lower = boundedMark(markPriceTicks, 1_000_000L - slippagePpm, false);
        long upper = boundedMark(markPriceTicks, 1_000_000L + slippagePpm, true);
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

    public static long scalePpm(long value, long factor, boolean ceiling) {
        long whole = Math.multiplyExact(value / PPM, factor);
        long remainderProduct = Math.multiplyExact(value % PPM, factor);
        long scaled = Math.addExact(whole, remainderProduct / PPM);
        return ceiling && remainderProduct % PPM != 0 ? Math.incrementExact(scaled) : scaled;
    }
}
