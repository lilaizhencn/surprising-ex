package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;

/** Calculates one linear/inverse perpetual fill against the owner-thread Runtime. */
public final class RuntimePerpetualFillCalculator {

    private RuntimePerpetualFillCalculator() {
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
        if (runtime == null || identities == null || instrument == null || order == null || treasuryDelta == null
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0 || leveragePpm <= 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("invalid perpetual fill arguments");
        }
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
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

        FillResult result = calculate(instrument, order, reservation, current,
                balance.availableUnits(), balance.lockedUnits(), fillPriceTicks, fillQuantitySteps,
                taker, leveragePpm, settleAssetId);
        runtime.replaceReservation(result.reservation());
        runtime.replaceBalance(new BalanceRuntime(order.userId(), settleAssetId,
                result.availableUnits(), result.lockedUnits()));
        treasuryDelta.addFee(settleAssetId, result.feeTreasuryUnits());
        treasuryDelta.addClearing(settleAssetId, result.clearingTreasuryUnits());
        runtime.replacePosition(positionKey, result.position());
        runtime.replaceOrder(result.order());
        runtime.advanceUserRevision(order.userId());
    }

    static FillResult calculate(CoreInstrumentState instrument, OrderRuntime order,
                                ReservationRuntime reservation, PositionRuntime current,
                                long availableUnits, long lockedUnits, long fillPriceTicks,
                                long fillQuantitySteps, boolean taker, long leveragePpm,
                                int settleAssetId) {
        if (instrument == null || order == null || reservation == null || availableUnits < 0 || lockedUnits < 0
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0 || leveragePpm <= 0 || settleAssetId < 0
                || reservation.userId() != order.userId() || reservation.assetId() != settleAssetId
                || current != null && current.userId() != order.userId()
                || fillQuantitySteps > order.remainingQuantitySteps()) {
            throw new IllegalArgumentException("invalid perpetual fill calculation");
        }
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        long currentQuantity = current == null ? 0 : current.signedQuantitySteps();
        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }

        long releasedMargin = current == null || closeSteps == 0 ? 0
                : proportional(current.positionMarginUnits(), closeSteps, currentAbs);
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long remainingMargin = Math.subtractExact(current == null ? 0 : current.positionMarginUnits(), releasedMargin);
        long marginIncrease = openingMarginForFill(instrument, nextQuantity, signedFill, openSteps,
                fillPriceTicks, leveragePpm);
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long premiumDelta = kernel.premiumDeltaUnits(instrument, order.side(), fillPriceTicks, fillQuantitySteps);
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        long premiumDebit = Math.max(0, Math.negateExact(premiumDelta));
        long feeDebit = Math.max(0, Math.negateExact(feeDelta));
        long proportionalBudget = proportional(reservation.reservedUnits(), fillQuantitySteps,
                order.remainingQuantitySteps());
        long fillReservationBudget = Math.min(reservation.reservedUnits(),
                Math.max(feeDebit, proportionalBudget));
        // The accepted reservation is authoritative when a better execution price raises the price-based formula.
        boolean betterFill = order.side() == CoreOrderSide.BUY
                ? fillPriceTicks < order.matchingPriceTicks()
                : fillPriceTicks > order.matchingPriceTicks();
        if (betterFill) {
            marginIncrease = Math.min(marginIncrease, Math.max(0,
                    Math.subtractExact(fillReservationBudget, Math.addExact(premiumDebit, feeDebit))));
        }
        long reservationDebit = Math.addExact(marginIncrease, Math.addExact(premiumDebit, feeDebit));
        long reservationShortfall = Math.max(0,
                Math.subtractExact(reservationDebit, reservation.reservedUnits()));
        if (reservationShortfall > 0
                && (!order.reduceOnly() || closeSteps == 0 || reservationShortfall > feeDebit)) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation is insufficient");
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
                    instrument, signedClose, current.entryPriceTicks(), fillPriceTicks);
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
        else if (premiumDelta > 0) nextAvailable = Math.addExact(nextAvailable, premiumDelta);
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
                    current.entryPriceTicks(), fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = current.entryPriceTicks();
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        PositionRuntime next = new PositionRuntime(order.userId(), order.symbolId(), settleAssetId,
                order.marginMode(), order.positionSide(), nextQuantity == 0 ? 0 : order.instrumentVersion(),
                nextQuantity, nextEntryPrice, nextEntryValue,
                Math.addExact(current == null ? 0 : current.realizedPnlUnits(), realizedPnl),
                Math.addExact(remainingMargin, marginIncrease));
        long nextRemainingQuantity = Math.subtractExact(order.remainingQuantitySteps(), fillQuantitySteps);
        OrderRuntime nextOrder = order.withFill(
                Math.addExact(order.executedQuantitySteps(), fillQuantitySteps), nextRemainingQuantity,
                Math.negateExact(feeDelta),
                nextRemainingQuantity == 0 ? CoreOrderStatus.FILLED : order.status(),
                Math.incrementExact(order.revision()));

        return new FillResult(nextOrder, reservation.consume(orderReservationDebit), next,
                nextAvailable, nextLocked, Math.negateExact(feeDelta), Math.negateExact(appliedPnl));
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
                                             long leveragePpm) {
        if (openSteps == 0 || instrument.contractType().isOption() && signedFillSteps > 0) return 0;
        long projectedNotional = CoreContractMath.notionalUnits(
                instrument, Math.absExact(projectedQuantitySteps), priceTicks);
        long bracketRate = CoreContractMath.maintenanceRiskBracket(
                instrument, projectedNotional).initialMarginRatePpm();
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracketRate), leverageRate);
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                priceTicks, openSteps, effectiveRate);
    }
}
