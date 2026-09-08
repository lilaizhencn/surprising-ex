package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.protocol.CoreOrderSide;

/** Calculates one derivative fill against the owner-thread Runtime. */
public final class RuntimeDerivativeFillCalculator {

    private RuntimeDerivativeFillCalculator() {
    }

    public static void apply(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                             CoreInstrumentState instrument, OrderRuntime order,
                             long positionKey, long fillPriceTicks, long fillQuantitySteps,
                             boolean taker, long leveragePpm, int settleAssetId) {
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        apply(runtime, identities, instrument, order, positionKey, fillPriceTicks, fillQuantitySteps,
                taker, leveragePpm, settleAssetId, treasuryDelta);
        treasuryDelta.apply(runtime.treasury());
    }

    static void apply(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                      CoreInstrumentState instrument, OrderRuntime order,
                      long positionKey, long fillPriceTicks, long fillQuantitySteps,
                      boolean taker, long leveragePpm, int settleAssetId,
                      RuntimeTreasuryDelta treasuryDelta) {
        apply(runtime, identities, instrument, order, positionKey, fillPriceTicks, fillQuantitySteps,
                taker, leveragePpm, settleAssetId, treasuryDelta, -1, -1);
    }

    static void apply(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                      CoreInstrumentState instrument, OrderRuntime order,
                      long positionKey, long fillPriceTicks, long fillQuantitySteps,
                      boolean taker, long leveragePpm, int settleAssetId,
                      RuntimeTreasuryDelta treasuryDelta, long commitTimestamp, long commitPosition) {
        if (runtime == null || identities == null || instrument == null || order == null || treasuryDelta == null
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0 || leveragePpm <= 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("invalid perpetual fill arguments");
        }
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        if (kernel.productLine() == com.surprising.product.api.ProductLine.SPOT
                || order.symbolId() < 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("runtime fill instrument identity mismatch");
        }
        if (order.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET
                && order.priceTicks() < 0) {
            throw new IllegalArgumentException("invalid market order price");
        }
        if (fillQuantitySteps > order.remainingQuantitySteps()) {
            throw new IllegalStateException("fill exceeds runtime order remaining quantity");
        }
        ReservationRuntime reservation = runtime.reservation(order.orderId());
        BalanceRuntime balance = runtime.balance(order.userId(), settleAssetId);
        if (reservation == null || balance == null || reservation.userId() != order.userId()
                || reservation.assetId() != settleAssetId) {
            throw new IllegalStateException("runtime fill entities are missing: " + order.orderId());
        }
        PositionRuntime current = runtime.position(positionKey);
        if (current != null && current.userId() != order.userId()) {
            throw new IllegalStateException("runtime position owner mismatch: " + positionKey);
        }

        MarkPriceRuntime riskMark = runtime.markPrice(order.symbolId());
        if (instrument.contractType().isOption()) OptionFillCalculator.requireRiskMark(riskMark);
        FillCursor cursor = SINGLE.get();
        try {
            cursor.reset(instrument, order, reservation, current, balance.availableUnits(),
                    balance.lockedUnits(), settleAssetId, riskMark);
            cursor.step(fillPriceTicks, fillQuantitySteps, taker, leveragePpm, commitTimestamp, commitPosition);
            cursor.publish(runtime, positionKey, treasuryDelta);
        } finally { cursor.clear(); }
    }

    static FillResult calculate(CoreInstrumentState instrument, OrderRuntime order,
                                ReservationRuntime reservation, PositionRuntime current,
                                long availableUnits, long lockedUnits, long fillPriceTicks,
                                long fillQuantitySteps, boolean taker, long leveragePpm,
                                int settleAssetId, MarkPriceRuntime riskMark) {
        return calculate(instrument, order, reservation, current, availableUnits, lockedUnits,
                fillPriceTicks, fillQuantitySteps, taker, leveragePpm, settleAssetId, riskMark, -1, -1);
    }

    static FillResult calculate(CoreInstrumentState instrument, OrderRuntime order,
                                ReservationRuntime reservation, PositionRuntime current,
                                long availableUnits, long lockedUnits, long fillPriceTicks,
                                long fillQuantitySteps, boolean taker, long leveragePpm,
                                int settleAssetId, MarkPriceRuntime riskMark,
                                long commitTimestamp, long commitPosition) {
        FillCursor cursor = SINGLE.get();
        try {
            cursor.reset(instrument, order, reservation, current, availableUnits, lockedUnits, settleAssetId, riskMark);
            cursor.step(fillPriceTicks, fillQuantitySteps, taker, leveragePpm, commitTimestamp, commitPosition);
            return new FillResult(cursor.order(), cursor.reservation(), cursor.position(), cursor.available,
                    cursor.locked, cursor.feeTreasuryUnits, cursor.clearingTreasuryUnits);
        } finally { cursor.clear(); }
    }

    private static final ThreadLocal<FillCursor> SINGLE = ThreadLocal.withInitial(FillCursor::new);
    private static final ThreadLocal<FillCursor> TAKER = ThreadLocal.withInitial(FillCursor::new);

    /** Lane-local scalar state for one taker order; never published or shared with another Lane. */
    static final class FillCursor {
        private CoreInstrumentState instrument;
        private OrderRuntime originalOrder;
        private ReservationRuntime originalReservation;
        private MarkPriceRuntime riskMark;
        private int settleAssetId;
        private boolean hasPosition;
        private long available, locked, quantity, entryPrice, entryValue, realizedPnl, margin;
        private long executed, remaining, cumulativeFee, orderRevision, consumed, fills;
        private long timestamp, clusterPosition;
        private long feeTreasuryUnits, clearingTreasuryUnits;
        private long positionKey, leveragePpm;

        void reset(CoreInstrumentState instrument, OrderRuntime order, ReservationRuntime reservation,
                   PositionRuntime current, long available, long locked, int assetId, MarkPriceRuntime mark) {
            if (instrument == null || order == null || reservation == null || available < 0 || locked < 0
                    || assetId < 0 || reservation.userId() != order.userId() || reservation.assetId() != assetId
                    || current != null && current.userId() != order.userId()) {
                throw new IllegalArgumentException("invalid perpetual fill calculation");
            }
            this.instrument = instrument; originalOrder = order; originalReservation = reservation;
            riskMark = mark; settleAssetId = assetId; this.available = available; this.locked = locked;
            hasPosition = current != null;
            quantity = current == null ? 0 : current.signedQuantitySteps();
            entryPrice = current == null ? 0 : current.entryPriceTicks();
            entryValue = current == null ? 0 : current.entryValueTicks();
            realizedPnl = current == null ? 0 : current.realizedPnlUnits();
            margin = current == null ? 0 : current.positionMarginUnits();
            executed = order.executedQuantitySteps(); remaining = order.remainingQuantitySteps();
            cumulativeFee = order.cumulativeFeeUnits(); orderRevision = order.revision();
            consumed = reservation.consumedUnits(); fills = 0;
        }
        long reservedUnits() {
            return Math.subtractExact(originalReservation.totalReservedUnits(),
                    Math.addExact(originalReservation.releasedUnits(), consumed));
        }
        void step(long price, long size, boolean taker, long leverage, long timestamp, long position) {
            calculateInto(this, price, size, taker, leverage, timestamp, position);
        }
        void applyNext(long price, long size, RuntimeTreasuryDelta treasury) {
            step(price, size, true, leveragePpm, timestamp, clusterPosition);
            // Preserve the exact per-fill interleaving with maker treasury deltas.
            treasury.addFee(settleAssetId, feeTreasuryUnits);
            treasury.addClearing(settleAssetId, clearingTreasuryUnits);
        }
        OrderRuntime order() {
            OrderRuntime o = originalOrder;
            return new OrderRuntime(o.orderId(), o.productLine(), o.userId(), o.symbolId(), o.instrumentChangeId(),
                    o.side(), o.priceTicks(), o.matchingPriceTicks(), o.quantitySteps(), executed, remaining,
                    o.reduceOnly(), o.marginMode(), o.positionSide(), o.orderType(), o.timeInForce(), o.postOnly(),
                    o.clientOrderId(), o.commandId(), o.makerFeeRatePpm(), o.takerFeeRatePpm(), cumulativeFee,
                    timestamp < 0 ? o.createdAtEpochMillis() : timestamp,
                    timestamp < 0 ? o.updatedAtEpochMillis() : timestamp,
                    timestamp < 0 ? o.clusterPosition() : clusterPosition,
                    remaining == 0 ? CoreOrderStatus.FILLED : o.status(), orderRevision);
        }
        ReservationRuntime reservation() {
            ReservationRuntime r = originalReservation;
            return new ReservationRuntime(r.orderId(), r.userId(), r.symbolId(), r.instrumentChangeId(),
                    r.kind(), r.assetId(), r.totalReservedUnits(), r.releasedUnits(), consumed, r.orderQuantitySteps());
        }
        PositionRuntime position() {
            OrderRuntime o = originalOrder;
            return new PositionRuntime(o.userId(), o.symbolId(), settleAssetId, o.marginMode(), o.positionSide(),
                    quantity == 0 ? 0 : o.instrumentChangeId(), quantity, entryPrice, entryValue, realizedPnl, margin);
        }
        void publish(TradingRuntimeState runtime, long positionKey, RuntimeTreasuryDelta treasury) {
            if (fills == 0) return;
            runtime.replaceReservation(reservation());
            runtime.replaceBalance(new BalanceRuntime(originalOrder.userId(), settleAssetId, available, locked));
            if (treasury != null) {
                treasury.addFee(settleAssetId, feeTreasuryUnits);
                treasury.addClearing(settleAssetId, clearingTreasuryUnits);
            }
            runtime.replacePosition(positionKey, position());
            runtime.replaceOrder(order());
            runtime.advanceUserRevision(originalOrder.userId(), fills);
        }
        void publish(TradingRuntimeState runtime) { publish(runtime, positionKey, null); }
        void clear() {
            instrument = null; originalOrder = null; originalReservation = null; riskMark = null;
            fills = 0; positionKey = 0; leveragePpm = 0;
        }
    }

    static FillCursor beginTaker(TradingRuntimeState runtime, CoreInstrumentState instrument,
                                OrderRuntime order, long positionKey, long leverage, int assetId,
                                long timestamp, long position) {
        FillCursor cursor = TAKER.get();
        if (cursor.originalOrder != null) throw new IllegalStateException("nested taker fill cursor");
        try {
            BalanceRuntime balance = runtime.balance(order.userId(), assetId);
            if (balance == null || order.symbolId() < 0 || leverage <= 0
                    || instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
                throw new IllegalArgumentException("invalid taker fill cursor");
            }
            MarkPriceRuntime mark = runtime.markPrice(order.symbolId());
            if (instrument.contractType().isOption()) OptionFillCalculator.requireRiskMark(mark);
            cursor.reset(instrument, order, runtime.reservation(order.orderId()), runtime.position(positionKey),
                    balance.availableUnits(), balance.lockedUnits(), assetId, mark);
            cursor.positionKey = positionKey; cursor.leveragePpm = leverage;
            cursor.timestamp = timestamp; cursor.clusterPosition = position;
            return cursor;
        } catch (RuntimeException | Error failure) { cursor.clear(); throw failure; }
    }

    private static void calculateInto(FillCursor state, long fillPriceTicks, long fillQuantitySteps,
                                      boolean taker, long leveragePpm, long commitTimestamp, long commitPosition) {
        CoreInstrumentState instrument = state.instrument;
        OrderRuntime order = state.originalOrder;
        ReservationRuntime reservation = state.originalReservation;
        MarkPriceRuntime riskMark = state.riskMark;
        long availableUnits = state.available, lockedUnits = state.locked;
        if (instrument == null || fillPriceTicks <= 0 || fillQuantitySteps <= 0 || leveragePpm <= 0
                || fillQuantitySteps > state.remaining) throw new IllegalArgumentException("invalid perpetual fill calculation");
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        long currentQuantity = state.quantity;
        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }

        long releasedMargin = !state.hasPosition || closeSteps == 0 ? 0
                : proportional(state.margin, closeSteps, currentAbs);
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long remainingMargin = Math.subtractExact(state.margin, releasedMargin);
        long marginIncrease = openingMarginForFill(instrument, nextQuantity, signedFill, openSteps,
                fillPriceTicks, leveragePpm, riskMark);
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long premiumDelta = kernel.premiumDeltaUnits(instrument, order.side(), fillPriceTicks, fillQuantitySteps);
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        long premiumDebit = Math.max(0, Math.negateExact(premiumDelta));
        long feeDebit = Math.max(0, Math.negateExact(feeDelta));
        long premiumMarginFunding = instrument.contractType().isOption()
                ? OptionFillCalculator.premiumMarginFunding(
                        instrument, premiumDelta, openSteps, marginIncrease, fillPriceTicks) : 0;
        long proportionalBudget = proportional(state.reservedUnits(), fillQuantitySteps,
                state.remaining);
        long fillReservationBudget = Math.min(state.reservedUnits(),
                Math.max(feeDebit, proportionalBudget));
        // Allocate the accepted budget across equal-price partial fills as well: rounding
        // each fill upward must not consume more margin than the whole order reserved.
        // Worse prices still require the normal shortfall checks below.
        boolean betterFill = order.side() == CoreOrderSide.BUY
                ? fillPriceTicks < order.matchingPriceTicks()
                : fillPriceTicks > order.matchingPriceTicks();
        boolean samePriceRounding = fillPriceTicks == order.matchingPriceTicks() && closeSteps == 0
                && marginIncrease > Math.max(0, fillReservationBudget - feeDebit)
                && !instrument.contractType().isOption()
                && reservation.totalReservedUnits() >= Math.addExact(
                        openingMarginForFill(instrument, nextQuantity, signedFill, order.quantitySteps(),
                                fillPriceTicks, leveragePpm, riskMark),
                        Math.max(0, Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                                fillPriceTicks, order.quantitySteps(), Math.max(order.makerFeeRatePpm(), order.takerFeeRatePpm())))));
        if ((betterFill || samePriceRounding) && !instrument.contractType().isOption()) {
            marginIncrease = Math.min(marginIncrease, Math.max(0,
                    Math.subtractExact(fillReservationBudget, Math.addExact(premiumDebit, feeDebit))));
        }
        long reservationDebit = Math.addExact(Math.subtractExact(marginIncrease, premiumMarginFunding),
                Math.addExact(premiumDebit, feeDebit));
        long reservationShortfall = Math.max(0,
                Math.subtractExact(reservationDebit, state.reservedUnits()));
        if (reservationShortfall > 0 && closeSteps == 0) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation is insufficient: orderId=" + order.orderId()
                            + ", required=" + reservationDebit
                            + ", remaining=" + state.reservedUnits());
        }
        long releasedMarginDebit = reservationShortfall;
        if (releasedMarginDebit > releasedMargin) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation and released position margin are insufficient");
        }
        long orderReservationDebit = Math.subtractExact(reservationDebit, releasedMarginDebit);
        long nextAvailable = availableUnits;
        long nextLocked = lockedUnits;
        long marginReleaseUnits = Math.subtractExact(releasedMargin, releasedMarginDebit);
        if (marginReleaseUnits > 0) {
            nextAvailable = Math.addExact(nextAvailable, marginReleaseUnits);
            nextLocked = Math.subtractExact(nextLocked, marginReleaseUnits);
        }

        long realizedPnl = 0;
        if (closeSteps > 0) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = kernel.realizedPnlUnits(
                    instrument, signedClose, state.entryPrice, fillPriceTicks);
        }
        long appliedPnl;
        if (realizedPnl >= 0) {
            nextAvailable = Math.addExact(nextAvailable, realizedPnl);
            appliedPnl = realizedPnl;
        } else {
            long debit = Math.min(nextAvailable, Math.negateExact(realizedPnl));
            nextAvailable = Math.subtractExact(nextAvailable, debit);
            appliedPnl = Math.negateExact(debit);
        }
        if (premiumDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(premiumDelta));
        else if (premiumDelta > 0) {
            nextLocked = Math.addExact(nextLocked, premiumMarginFunding);
            nextAvailable = Math.addExact(nextAvailable,
                    Math.subtractExact(premiumDelta, premiumMarginFunding));
        }
        if (feeDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(feeDelta));
        else if (feeDelta > 0) nextAvailable = Math.addExact(nextAvailable, feeDelta);
        if (nextAvailable < 0 || nextLocked < 0) {
            throw new IllegalStateException("runtime fill balance would become negative");
        }
        long nextEntryPrice;
        long nextEntryValue;
        if (nextQuantity == 0) {
            nextEntryPrice = 0;
            nextEntryValue = 0;
        } else if (currentQuantity == 0 || Long.signum(nextQuantity) != Long.signum(currentQuantity)) {
            nextEntryPrice = fillPriceTicks;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), fillPriceTicks);
        } else if (Long.signum(signedFill) == Long.signum(currentQuantity)) {
            nextEntryPrice = CoreContractMath.weightedEntryPrice(instrument, currentAbs,
                    state.entryPrice, fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = state.entryPrice;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        long nextMargin = Math.addExact(remainingMargin, marginIncrease);
        long nextRealizedPnl = Math.addExact(state.realizedPnl, realizedPnl);
        long nextRemaining = Math.subtractExact(state.remaining, fillQuantitySteps);
        long nextExecuted = Math.addExact(state.executed, fillQuantitySteps);
        long nextFee = Math.addExact(state.cumulativeFee, Math.negateExact(feeDelta));
        long nextRevision = Math.incrementExact(state.orderRevision);
        long nextConsumed = Math.addExact(state.consumed, orderReservationDebit);
        if (nextMargin < 0 || nextQuantity == 0 && (nextMargin != 0 || nextEntryPrice != 0 || nextEntryValue != 0)
                || nextQuantity != 0 && (nextEntryPrice <= 0 || nextEntryValue <= 0)
                || orderReservationDebit < 0 || orderReservationDebit > state.reservedUnits()) {
            throw new IllegalArgumentException("invalid runtime fill state");
        }
        if (commitTimestamp >= 0 && commitPosition < 0) throw new IllegalArgumentException("invalid runtime order");
        state.available = nextAvailable; state.locked = nextLocked;
        state.quantity = nextQuantity; state.entryPrice = nextEntryPrice; state.entryValue = nextEntryValue;
        state.realizedPnl = nextRealizedPnl; state.margin = nextMargin; state.hasPosition = true;
        state.executed = nextExecuted; state.remaining = nextRemaining; state.cumulativeFee = nextFee;
        state.orderRevision = nextRevision; state.consumed = nextConsumed;
        state.fills = Math.incrementExact(state.fills);
        state.timestamp = commitTimestamp; state.clusterPosition = commitPosition;
        state.feeTreasuryUnits = Math.negateExact(feeDelta);
        state.clearingTreasuryUnits = Math.negateExact(appliedPnl);
    }

    record FillResult(OrderRuntime order, ReservationRuntime reservation, PositionRuntime position,
                      long availableUnits, long lockedUnits, long feeTreasuryUnits,
                      long clearingTreasuryUnits) {
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private static long openingMarginForFill(CoreInstrumentState instrument,
                                             long projectedQuantitySteps,
                                             long signedFillSteps,
                                             long openSteps,
                                             long priceTicks,
                                             long leveragePpm,
                                             MarkPriceRuntime riskMark) {
        return instrument.contractType().isOption()
                ? OptionFillCalculator.openingMarginForFill(instrument, projectedQuantitySteps, signedFillSteps,
                openSteps, priceTicks, leveragePpm, riskMark)
                : FuturesFillCalculator.openingMarginForFill(instrument, projectedQuantitySteps, signedFillSteps,
                openSteps, priceTicks, leveragePpm, riskMark);
    }
}
