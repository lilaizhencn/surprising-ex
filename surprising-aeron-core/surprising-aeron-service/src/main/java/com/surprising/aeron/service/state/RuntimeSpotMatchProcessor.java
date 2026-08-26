package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeSpotMatchProcessor {

    private RuntimeSpotMatchProcessor() {
    }

    public static void apply(TradingCoreState before, long takerOrderId, String baseAsset, String quoteAsset,
                             List<MatcherEvent> matches, TradingRuntimeState runtime,
                             RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid spot match apply");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
    }

    public static void applyRuntime(long takerOrderId, List<MatcherEvent> matches,
                                    TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (matches == null || runtime == null || identities == null || runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid spot match apply");
        }
        runtime.assertOwner();
        OrderRuntime taker = requireOpen(runtime, takerOrderId);
        if (matches.isEmpty() && !taker.timeInForce().immediate()
                && taker.orderType() != com.surprising.aeron.protocol.CoreOrderType.MARKET) {
            return;
        }
        CoreInstrumentState instrument = runtime.instrument(identities.symbol(taker.symbolId()));
        if (instrument == null || instrument.version() != taker.instrumentVersion()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        if (SettlementKernels.forInstrument(instrument).productLine()
                != com.surprising.product.api.ProductLine.SPOT) {
            throw new IllegalStateException("spot matcher received a non-spot settlement kernel");
        }
        int baseAssetId = identities.assetId(instrument.baseAsset());
        int quoteAssetId = identities.assetId(instrument.quoteAsset());
        validateMatches(runtime, taker, matches);
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            taker = requireOpen(runtime, takerOrderId);
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            OrderRuntime buyer = taker.side() == CoreOrderSide.BUY ? taker : maker;
            OrderRuntime seller = taker.side() == CoreOrderSide.SELL ? taker : maker;
            applyFill(runtime, instrument, buyer, match.price(), match.size(),
                    buyer.orderId() == taker.orderId(), baseAssetId, quoteAssetId, treasuryDelta);
            applyFill(runtime, instrument, seller, match.price(), match.size(),
                    seller.orderId() == taker.orderId(), baseAssetId, quoteAssetId, treasuryDelta);
            releaseTerminalReservation(runtime, maker.orderId());
        }
        taker = runtime.order(takerOrderId);
        if (!taker.canceled() && (taker.timeInForce().immediate()
                || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
            runtime.replaceOrder(taker.withStatus(CoreOrderStatus.CANCELED, Math.incrementExact(taker.revision())));
        }
        releaseTerminalReservation(runtime, takerOrderId);
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    static RuntimeTreasuryDelta applyLane(long takerOrderId, List<MatcherEvent> matches,
                                          TradingRuntimeState runtime, CoreInstrumentState instrument,
                                          int baseAssetId, int quoteAssetId) {
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        OrderRuntime localTaker = runtime.order(takerOrderId);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            if (localTaker != null) {
                localTaker = requireOpen(runtime, takerOrderId);
                applyFill(runtime, instrument, localTaker, match.price(), match.size(), true,
                        baseAssetId, quoteAssetId, treasuryDelta);
            }
            OrderRuntime maker = runtime.order(match.matchedOrderId());
            if (maker != null) {
                applyFill(runtime, instrument, requireOpen(runtime, maker.orderId()), match.price(), match.size(),
                        false, baseAssetId, quoteAssetId, treasuryDelta);
                releaseTerminalReservation(runtime, maker.orderId());
            }
        }
        localTaker = runtime.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.replaceOrder(localTaker.withStatus(CoreOrderStatus.CANCELED,
                        Math.incrementExact(localTaker.revision())));
            }
            releaseTerminalReservation(runtime, takerOrderId);
        }
        return treasuryDelta;
    }

    static void validate(long takerOrderId, List<MatcherEvent> matches, TradingRuntimeState runtime) {
        validateMatches(runtime, requireOpen(runtime, takerOrderId), matches);
    }

    private static void applyFill(TradingRuntimeState runtime, CoreInstrumentState instrument, OrderRuntime order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta) {
        ReservationRuntime reservation = runtime.reservation(order.orderId());
        if (reservation == null || reservation.userId() != order.userId()) {
            throw new IllegalStateException("runtime spot reservation is missing");
        }
        long quoteUnits = Math.multiplyExact(fillPriceTicks, fillQuantitySteps);
        long feeRate = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRate);
        int debitAssetId = order.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
        if (reservation.assetId() != debitAssetId) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        BalanceRuntime debit = runtime.balance(order.userId(), debitAssetId);
        if (debit == null) throw new IllegalStateException("runtime spot debit balance is missing");
        BalanceRuntime base = runtime.balance(order.userId(), baseAssetId);
        BalanceRuntime quote = runtime.balance(order.userId(), quoteAssetId);
        long nextBaseAvailable = base == null ? 0 : base.availableUnits();
        long nextBaseLocked = base == null ? 0 : base.lockedUnits();
        long nextQuoteAvailable = quote == null ? 0 : quote.availableUnits();
        long nextQuoteLocked = quote == null ? 0 : quote.lockedUnits();
        long reservationDebit;
        if (order.side() == CoreOrderSide.BUY) {
            reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
            nextQuoteLocked = Math.subtractExact(nextQuoteLocked, quoteUnits);
            if (feeDelta < 0) nextQuoteLocked = Math.subtractExact(nextQuoteLocked, Math.negateExact(feeDelta));
            else if (feeDelta > 0) nextQuoteAvailable = Math.addExact(nextQuoteAvailable, feeDelta);
            nextBaseAvailable = Math.addExact(nextBaseAvailable, fillQuantitySteps);
        } else {
            reservationDebit = fillQuantitySteps;
            nextBaseLocked = Math.subtractExact(nextBaseLocked, fillQuantitySteps);
            nextQuoteAvailable = Math.addExact(nextQuoteAvailable, quoteUnits);
            nextQuoteAvailable = Math.addExact(nextQuoteAvailable, feeDelta);
        }
        if (nextBaseAvailable < 0 || nextBaseLocked < 0 || nextQuoteAvailable < 0 || nextQuoteLocked < 0) {
            throw new IllegalStateException("runtime spot fill balance would become negative");
        }
        ReservationRuntime nextReservation = reservation.consume(reservationDebit);
        OrderRuntime nextOrder = order.withFill(
                Math.addExact(order.executedQuantitySteps(), fillQuantitySteps),
                Math.subtractExact(order.remainingQuantitySteps(), fillQuantitySteps),
                Math.negateExact(feeDelta),
                order.remainingQuantitySteps() == fillQuantitySteps ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN,
                Math.incrementExact(order.revision()));
        replaceBalance(runtime, order.userId(), baseAssetId, base, nextBaseAvailable, nextBaseLocked);
        replaceBalance(runtime, order.userId(), quoteAssetId, quote, nextQuoteAvailable, nextQuoteLocked);
        runtime.replaceReservation(nextReservation);
        runtime.replaceOrder(nextOrder);
        treasuryDelta.addFee(quoteAssetId, Math.negateExact(feeDelta));
        runtime.advanceUserRevision(order.userId());
    }

    private static void replaceBalance(TradingRuntimeState runtime, long userId, int assetId,
                                       BalanceRuntime current, long available, long locked) {
        if (current == null) runtime.putBalance(new BalanceRuntime(userId, assetId, available, locked));
        else runtime.replaceBalance(new BalanceRuntime(userId, assetId, available, locked));
    }

    private static void releaseTerminalReservation(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || !order.canceled()) return;
        long releaseUnits = runtime.reservation(orderId).reservedUnits();
        runtime.releaseTerminalReservation(orderId);
        if (releaseUnits > 0) runtime.advanceUserRevision(order.userId());
    }

    private static void validateMatches(TradingRuntimeState runtime, OrderRuntime taker,
                                        List<MatcherEvent> matches) {
        long takerRemaining = taker.remainingQuantitySteps();
        Map<Long, Long> makerRemaining = new HashMap<>();
        for (MatcherEvent match : matches) {
            if (match == null) {
                throw new IllegalArgumentException("invalid runtime match");
            }
            if (match.eventType() != MatcherEventType.TRADE) continue;
            if (match.price() <= 0 || match.size() <= 0) {
                throw new IllegalArgumentException("invalid runtime match");
            }
            OrderRuntime maker = requireOpen(runtime, match.matchedOrderId());
            if (maker.userId() != match.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                    || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                throw new IllegalStateException("runtime match does not match authoritative orders");
            }
            takerRemaining = Math.subtractExact(takerRemaining, match.size());
            long remaining = Math.subtractExact(
                    makerRemaining.getOrDefault(maker.orderId(), maker.remainingQuantitySteps()),
                    match.size());
            if (takerRemaining < 0 || remaining < 0) {
                throw new IllegalStateException("fill exceeds runtime order remaining quantity");
            }
            makerRemaining.put(maker.orderId(), remaining);
        }
    }

    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.canceled() || order.remainingQuantitySteps() == 0) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId);
        }
        return order;
    }
}
