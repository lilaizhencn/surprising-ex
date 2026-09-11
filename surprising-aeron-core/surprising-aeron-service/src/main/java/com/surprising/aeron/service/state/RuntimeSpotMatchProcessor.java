package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.protocol.CoreOrderSide;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

public final class RuntimeSpotMatchProcessor {

    private static final ThreadLocal<SpotSettlementAccumulator> ACCUMULATOR =
            ThreadLocal.withInitial(SpotSettlementAccumulator::new);

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
        if (instrument == null || instrument.changeId() != taker.instrumentChangeId()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        if (ProductTradingRulesRegistry.forInstrument(instrument).productLine()
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
                localTaker = applyFill(runtime, instrument, localTaker, match.price(), match.size(), true,
                        baseAssetId, quoteAssetId, treasuryDelta);
            }
            OrderRuntime maker = runtime.order(match.matchedOrderId());
            if (maker != null) {
                maker = applyFill(runtime, instrument, requireOpen(runtime, maker.orderId()), match.price(), match.size(),
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

    static void applyLane(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                          TradingRuntimeState runtime, CoreInstrumentState instrument,
                          int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                          long commitTimestamp, long commitPosition) {
        if (plan == null || treasuryDelta == null || laneId < 0
                || laneId >= runtime.topology().accountLaneCount()) {
            throw new IllegalArgumentException("invalid spot matcher settlement plan");
        }
        OrderRuntime localTaker = runtime.order(takerOrderId);
        if (plan.tradeCount() <= 1) {
            applyLaneDirect(takerOrderId, plan, laneId, runtime, instrument, baseAssetId,
                    quoteAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        } else {
            applyLaneAccumulated(takerOrderId, plan, laneId, runtime, instrument, baseAssetId,
                    quoteAssetId, treasuryDelta, commitTimestamp, commitPosition, localTaker);
        }
        localTaker = runtime.order(takerOrderId);
        if (localTaker != null) {
            if (!localTaker.canceled() && (localTaker.timeInForce().immediate()
                    || localTaker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET)) {
                runtime.replaceOrder(localTaker.withStatus(CoreOrderStatus.CANCELED,
                        Math.incrementExact(localTaker.revision()), commitTimestamp, commitPosition));
            }
            releaseTerminalReservation(runtime, takerOrderId);
        }
    }

    private static void applyLaneDirect(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                                        TradingRuntimeState runtime, CoreInstrumentState instrument,
                                        int baseAssetId, int quoteAssetId,
                                        RuntimeTreasuryDelta treasuryDelta,
                                        long commitTimestamp, long commitPosition,
                                        OrderRuntime localTaker) {
        for (int index = plan.firstMatcherEvent(laneId); index >= 0;
                index = plan.nextMatcherEvent(index, laneId)) {
            MatcherEvent match = plan.matcherEvent(index);
            if (match.eventType() != MatcherEventType.TRADE
                    || !plan.matcherEventTouchesLane(index, laneId, runtime)) continue;
            if (localTaker != null) {
                localTaker = requireOpen(runtime, takerOrderId);
                localTaker = applyFill(runtime, instrument, localTaker, match.price(), match.size(), true,
                        baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
            }
            OrderRuntime maker = runtime.order(match.matchedOrderId());
            if (maker != null) {
                maker = applyFill(runtime, instrument, requireOpen(runtime, maker.orderId()), match.price(), match.size(),
                        false, baseAssetId, quoteAssetId, treasuryDelta, commitTimestamp, commitPosition);
                releaseTerminalReservation(runtime, maker.orderId());
            }
        }
    }

    private static void applyLaneAccumulated(long takerOrderId, MatcherSettlementPlan plan, int laneId,
                                             TradingRuntimeState runtime, CoreInstrumentState instrument,
                                             int baseAssetId, int quoteAssetId,
                                             RuntimeTreasuryDelta treasuryDelta,
                                             long commitTimestamp, long commitPosition,
                                             OrderRuntime localTaker) {
        SpotSettlementAccumulator accumulator = ACCUMULATOR.get();
        accumulator.reset(runtime, instrument, baseAssetId, quoteAssetId, commitTimestamp, commitPosition);
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
        } finally {
            accumulator.clear();
        }
    }

    static void validate(long takerOrderId, List<MatcherEvent> matches, TradingRuntimeState runtime) {
        validateMatches(runtime, requireOpen(runtime, takerOrderId), matches);
    }

    private static OrderRuntime applyFill(TradingRuntimeState runtime, CoreInstrumentState instrument, OrderRuntime order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta) {
        return applyFill(runtime, instrument, order, fillPriceTicks, fillQuantitySteps, taker,
                baseAssetId, quoteAssetId, treasuryDelta, -1, -1);
    }

    private static OrderRuntime applyFill(TradingRuntimeState runtime, CoreInstrumentState instrument, OrderRuntime order,
                                  long fillPriceTicks, long fillQuantitySteps, boolean taker,
                                  int baseAssetId, int quoteAssetId, RuntimeTreasuryDelta treasuryDelta,
                                       long commitTimestamp, long commitPosition) {
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
                Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
        replaceBalance(runtime, order.userId(), baseAssetId, base, nextBaseAvailable, nextBaseLocked);
        replaceBalance(runtime, order.userId(), quoteAssetId, quote, nextQuoteAvailable, nextQuoteLocked);
        runtime.replaceReservation(nextReservation);
        runtime.replaceOrder(nextOrder);
        treasuryDelta.addFee(quoteAssetId, Math.negateExact(feeDelta));
        runtime.advanceUserRevision(order.userId());
        return nextOrder;
    }

    /** Coalesces all fills for a touched spot order before publishing its immutable state. */
    private static final class SpotSettlementAccumulator {
        private final LongObjectHashMap<SpotFillCursor> cursors = new LongObjectHashMap<>();
        private final LongObjectHashMap<IntObjectHashMap<SpotBalanceState>> balances = new LongObjectHashMap<>();
        private final java.util.ArrayDeque<SpotFillCursor> free = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<IntObjectHashMap<SpotBalanceState>> freeBalanceMaps =
                new java.util.ArrayDeque<>();
        private TradingRuntimeState runtime;
        private CoreInstrumentState instrument;
        private int baseAssetId;
        private int quoteAssetId;
        private long commitTimestamp;
        private long commitPosition;

        void reset(TradingRuntimeState runtime, CoreInstrumentState instrument,
                   int baseAssetId, int quoteAssetId, long commitTimestamp, long commitPosition) {
            this.runtime = runtime;
            this.instrument = instrument;
            this.baseAssetId = baseAssetId;
            this.quoteAssetId = quoteAssetId;
            this.commitTimestamp = commitTimestamp;
            this.commitPosition = commitPosition;
        }

        void apply(long orderId, boolean taker, long price, long quantity, RuntimeTreasuryDelta treasury) {
            SpotFillCursor cursor = cursors.get(orderId);
            if (cursor == null) {
                OrderRuntime order = requireOpen(runtime, orderId);
                ReservationRuntime reservation = runtime.reservation(orderId);
                if (reservation == null || reservation.userId() != order.userId()) {
                    throw new IllegalStateException("runtime spot reservation is missing");
                }
                cursor = free.pollFirst();
                if (cursor == null) cursor = new SpotFillCursor();
                cursor.reset(order, reservation, balanceState(order.userId(), baseAssetId),
                        balanceState(order.userId(), quoteAssetId));
                cursors.put(orderId, cursor);
            }
            cursor.apply(price, quantity, taker, treasury);
        }

        void publish() {
            balances.forEachKeyValue((userId, userBalances) ->
                    userBalances.forEachKeyValue((assetId, balance) -> balance.publish(runtime)));
            // Reservation replacement records its balance after-image while the reservation is
            // still pending, so balances must be visible before immutable order/reservation state.
            cursors.forEachKeyValue((orderId, cursor) -> cursor.publish(runtime));
        }

        void clear() {
            cursors.forEachKeyValue((orderId, cursor) -> {
                cursor.clear();
                free.addFirst(cursor);
            });
            cursors.clear();
            balances.forEachKeyValue((userId, userBalances) -> {
                userBalances.clear();
                freeBalanceMaps.addFirst(userBalances);
            });
            balances.clear();
            runtime = null;
            instrument = null;
        }

        private SpotBalanceState balanceState(long userId, int assetId) {
            IntObjectHashMap<SpotBalanceState> userBalances = balances.get(userId);
            if (userBalances == null) {
                userBalances = freeBalanceMaps.pollFirst();
                if (userBalances == null) userBalances = new IntObjectHashMap<>();
                balances.put(userId, userBalances);
            }
            SpotBalanceState state = userBalances.get(assetId);
            if (state == null) {
                BalanceRuntime current = runtime.balance(userId, assetId);
                state = new SpotBalanceState(userId, assetId, current);
                userBalances.put(assetId, state);
            }
            return state;
        }

        private static final class SpotBalanceState {
            private final long userId;
            private final int assetId;
            private final boolean exists;
            private long available;
            private long locked;

            SpotBalanceState(long userId, int assetId, BalanceRuntime current) {
                this.userId = userId;
                this.assetId = assetId;
                exists = current != null;
                available = current == null ? 0 : current.availableUnits();
                locked = current == null ? 0 : current.lockedUnits();
            }

            void publish(TradingRuntimeState runtime) {
                if (exists) runtime.replaceBalance(userId, assetId, available, locked);
                else runtime.putBalance(new BalanceRuntime(userId, assetId, available, locked));
            }
        }

        private final class SpotFillCursor {
            private OrderRuntime originalOrder;
            private ReservationRuntime originalReservation;
            private SpotBalanceState base;
            private SpotBalanceState quote;
            private long executed;
            private long remaining;
            private long cumulativeFee;
            private long consumed;
            private long fills;

            void reset(OrderRuntime order, ReservationRuntime reservation,
                       SpotBalanceState base, SpotBalanceState quote) {
                originalOrder = order;
                originalReservation = reservation;
                this.base = base;
                this.quote = quote;
                executed = order.executedQuantitySteps();
                remaining = order.remainingQuantitySteps();
                cumulativeFee = order.cumulativeFeeUnits();
                consumed = reservation.consumedUnits();
                fills = 0;
            }

            void apply(long price, long quantity, boolean taker, RuntimeTreasuryDelta treasury) {
                if (price <= 0 || quantity <= 0 || quantity > remaining) {
                    throw new IllegalArgumentException("invalid runtime spot fill");
                }
                long quoteUnits = Math.multiplyExact(price, quantity);
                long feeRate = taker ? originalOrder.takerFeeRatePpm() : originalOrder.makerFeeRatePpm();
                long feeDelta = CoreContractMath.feeDeltaUnits(instrument, price, quantity, feeRate);
                int debitAssetId = originalOrder.side() == CoreOrderSide.BUY ? quoteAssetId : baseAssetId;
                if (originalReservation.assetId() != debitAssetId) {
                    throw new IllegalStateException("spot fill debit asset does not match reservation");
                }
                long reservationDebit;
                if (originalOrder.side() == CoreOrderSide.BUY) {
                    reservationDebit = Math.addExact(quoteUnits, Math.max(0, Math.negateExact(feeDelta)));
                    quote.locked = Math.subtractExact(quote.locked, quoteUnits);
                    if (feeDelta < 0) quote.locked = Math.subtractExact(quote.locked, Math.negateExact(feeDelta));
                    else if (feeDelta > 0) quote.available = Math.addExact(quote.available, feeDelta);
                    base.available = Math.addExact(base.available, quantity);
                } else {
                    reservationDebit = quantity;
                    base.locked = Math.subtractExact(base.locked, quantity);
                    quote.available = Math.addExact(quote.available, quoteUnits);
                    quote.available = Math.addExact(quote.available, feeDelta);
                }
                if (base.available < 0 || base.locked < 0 || quote.available < 0 || quote.locked < 0) {
                    throw new IllegalStateException("runtime spot fill balance would become negative");
                }
                consumed = Math.addExact(consumed, reservationDebit);
                long maxConsumed = Math.addExact(originalReservation.consumedUnits(),
                        originalReservation.reservedUnits());
                if (consumed > maxConsumed) throw new IllegalStateException("runtime spot reservation was over-consumed");
                executed = Math.addExact(executed, quantity);
                remaining = Math.subtractExact(remaining, quantity);
                cumulativeFee = Math.addExact(cumulativeFee, Math.negateExact(feeDelta));
                fills = Math.incrementExact(fills);
                treasury.addFee(quoteAssetId, Math.negateExact(feeDelta));
            }

            void publish(TradingRuntimeState state) {
                if (fills == 0) return;
                ReservationRuntime nextReservation = new ReservationRuntime(
                        originalReservation.orderId(), originalReservation.userId(), originalReservation.symbolId(),
                        originalReservation.instrumentChangeId(), originalReservation.kind(),
                        originalReservation.assetId(), originalReservation.totalReservedUnits(),
                        originalReservation.releasedUnits(), consumed, originalReservation.orderQuantitySteps());
                CoreOrderStatus status = remaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN;
                OrderRuntime nextOrder = originalOrder.withFill(executed, remaining,
                        Math.subtractExact(cumulativeFee, originalOrder.cumulativeFeeUnits()), status,
                        Math.addExact(originalOrder.revision(), fills), commitTimestamp, commitPosition);
                state.replaceReservation(nextReservation);
                state.replaceOrder(nextOrder);
                state.advanceUserRevision(originalOrder.userId(), fills);
                if (nextOrder.canceled()) {
                    long releaseUnits = state.reservation(nextOrder.orderId()).reservedUnits();
                    state.releaseTerminalReservation(nextOrder.orderId());
                    if (releaseUnits > 0) state.advanceUserRevision(nextOrder.userId());
                }
            }

            void clear() {
                originalOrder = null;
                originalReservation = null;
                base = null;
                quote = null;
                fills = 0;
            }
        }
    }

    private static void replaceBalance(TradingRuntimeState runtime, long userId, int assetId,
                                       BalanceRuntime current, long available, long locked) {
        if (current == null) runtime.putBalance(new BalanceRuntime(userId, assetId, available, locked));
        else runtime.replaceBalance(userId, assetId, available, locked);
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
        LongLongHashMap makerRemaining = runtime.matcherSettlementRemainingScratch();
        makerRemaining.clear();
        try {
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
                long remaining = Math.subtractExact(makerRemaining.containsKey(maker.orderId())
                        ? makerRemaining.get(maker.orderId()) : maker.remainingQuantitySteps(), match.size());
                if (takerRemaining < 0 || remaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                makerRemaining.put(maker.orderId(), remaining);
            }
        } finally {
            makerRemaining.clear();
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
