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
        if (runtime == null || identities == null || instrument == null || order == null
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0 || leveragePpm <= 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("invalid perpetual fill arguments");
        }
        if (!instrument.contractType().productLine().isDerivative()
                || order.symbolId() != identities.symbolId(instrument.symbol())
                || settleAssetId != identities.assetId(instrument.settleAsset())) {
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
        long premiumDelta = instrument.contractType().isOption()
                ? (order.side() == CoreOrderSide.BUY
                ? Math.negateExact(CoreContractMath.optionPremiumUnits(
                instrument, fillPriceTicks, fillQuantitySteps))
                : CoreContractMath.optionPremiumUnits(instrument, fillPriceTicks, fillQuantitySteps))
                : 0;
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
        long nextAvailable = balance.availableUnits();
        long nextLocked = balance.lockedUnits();
        long marginReleaseUnits = Math.subtractExact(releasedMargin, releasedMarginDebit);
        if (marginReleaseUnits > 0) {
            nextAvailable = Math.addExact(nextAvailable, marginReleaseUnits);
            nextLocked = Math.subtractExact(nextLocked, marginReleaseUnits);
        }

        long realizedPnl = 0;
        if (closeSteps > 0 && !instrument.contractType().isOption()) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = CoreContractMath.pnlUnits(instrument, signedClose,
                    current.entryPriceTicks(), fillPriceTicks);
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
        long feeUnits = Math.addExact(runtime.treasury().fee(settleAssetId), Math.negateExact(feeDelta));
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
        PositionRuntime next = new PositionRuntime(order.userId(), identities.symbolId(instrument.symbol()), settleAssetId,
                order.marginMode(), order.positionSide(), nextQuantity == 0 ? 0 : order.instrumentVersion(),
                nextQuantity, nextEntryPrice, nextEntryValue,
                Math.addExact(current == null ? 0 : current.realizedPnlUnits(), realizedPnl),
                Math.addExact(remainingMargin, marginIncrease));
        long nextRemainingQuantity = Math.subtractExact(order.remainingQuantitySteps(), fillQuantitySteps);
        OrderRuntime nextOrder = order.withExecution(
                Math.addExact(order.executedQuantitySteps(), fillQuantitySteps), nextRemainingQuantity,
                nextRemainingQuantity == 0 ? CoreOrderStatus.FILLED : order.status(),
                Math.incrementExact(order.revision()));

        runtime.replaceReservation(reservation.consume(orderReservationDebit));
        runtime.replaceBalance(new BalanceRuntime(order.userId(), settleAssetId, nextAvailable, nextLocked));
        runtime.treasury().setFee(settleAssetId, feeUnits);
        runtime.treasury().adjustInsurance(settleAssetId, Math.negateExact(appliedPnl));
        runtime.replacePosition(positionKey, next);
        runtime.replaceOrder(nextOrder);
        runtime.advanceUserRevision(order.userId());
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
        if (openSteps == 0) return 0;
        long projectedNotional = CoreContractMath.notionalUnits(
                instrument, Math.absExact(projectedQuantitySteps), priceTicks);
        long bracketRate = CoreContractMath.maintenanceRiskBracket(
                instrument, projectedNotional).initialMarginRatePpm();
        java.math.BigInteger numerator = java.math.BigInteger.valueOf(1_000_000L)
                .multiply(java.math.BigInteger.valueOf(1_000_000L));
        java.math.BigInteger[] division = numerator.divideAndRemainder(java.math.BigInteger.valueOf(leveragePpm));
        long leverageRate = division[1].signum() == 0 ? division[0].longValueExact()
                : division[0].add(java.math.BigInteger.ONE).longValueExact();
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracketRate), leverageRate);
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                priceTicks, openSteps, effectiveRate);
    }
}
