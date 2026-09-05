package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import java.util.Map;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;

final class ReducerDerivativeSettlement {

    private ReducerDerivativeSettlement() {
    }

    static DerivativeFillResult applyDerivativeFill(
            CoreUserState user,
            CoreOrderState order,
            CoreInstrumentState instrument,
            CoreMarkPriceState riskMark,
            long fillPriceTicks,
            long fillQuantitySteps,
            boolean taker,
            long leveragePpm,
            CoreTreasuryState treasury) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        String positionKey = positionKey(order.symbol(), order.positionSide());
        CorePositionState current = user.positions().get(positionKey);
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
                instrument.contractType().isOption() ? riskMark.markPriceTicks() : fillPriceTicks, leveragePpm,
                riskMark == null ? 0 : riskMark.indexPriceTicks(),
                riskMark == null ? 0 : riskMark.forwardPriceTicks());
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        long premiumDelta = kernel.premiumDeltaUnits(instrument, order.side(), fillPriceTicks, fillQuantitySteps);
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        long premiumDebit = Math.max(0, Math.negateExact(premiumDelta));
        long feeDebit = Math.max(0, Math.negateExact(feeDelta));
        long cashDebit = Math.addExact(premiumDebit, feeDebit);
        long premiumMarginFunding = instrument.contractType().isOption() && premiumDelta > 0 && openSteps > 0
                ? Math.min(marginIncrease,
                OptionContractMath.optionPremiumUnits(instrument, fillPriceTicks, openSteps)) : 0;
        long proportionalBudget = proportional(reservation.remainingUnits(), fillQuantitySteps,
                order.remainingQuantitySteps());
        long fillReservationBudget = Math.min(reservation.remainingUnits(),
                Math.max(cashDebit, proportionalBudget));
        // Matching has already accepted this execution. A better fill can increase the price-based margin formula,
        // so the reservation accepted with the order remains the authoritative margin ceiling for this fill.
        boolean betterFill = order.side() == CoreOrderSide.BUY
                ? fillPriceTicks < order.matchingPriceTicks()
                : fillPriceTicks > order.matchingPriceTicks();
        if (betterFill && !instrument.contractType().isOption()) {
            marginIncrease = Math.min(marginIncrease, Math.max(0,
                    Math.subtractExact(fillReservationBudget, cashDebit)));
        }
        long reservationDebit = Math.addExact(
                Math.subtractExact(marginIncrease, premiumMarginFunding), cashDebit);
        long reservationShortfall = Math.max(0,
                Math.subtractExact(reservationDebit, reservation.remainingUnits()));
        if (reservationShortfall > 0 && closeSteps == 0) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for fill: orderId=" + order.orderId()
                            + ", required=" + reservationDebit
                            + ", remaining=" + reservation.remainingUnits());
        }
        long releasedMarginDebit = reservationShortfall;
        if (releasedMarginDebit > releasedMargin) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order and released position margin are insufficient for fill");
        }
        long orderReservationDebit = Math.subtractExact(reservationDebit, releasedMarginDebit);
        OrderReservation nextReservation = orderReservationDebit == 0
                ? reservation : reservation.consume(orderReservationDebit);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        long marginReleaseUnits = Math.subtractExact(releasedMargin, releasedMarginDebit);
        if (marginReleaseUnits > 0) {
            balance = balance.release(marginReleaseUnits);
        }
        long realizedPnl = 0;
        if (closeSteps > 0) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = kernel.realizedPnlUnits(instrument, signedClose,
                    current.entryPriceTicks(), fillPriceTicks);
        }
        CashResult pnlCash = applyCash(balance, realizedPnl);
        balance = pnlCash.balance();
        treasury = treasury.adjustClearingPnl(
                instrument.settleAsset(), Math.negateExact(pnlCash.appliedDelta()));
        if (premiumDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(premiumDelta));
        } else if (premiumDelta > 0) {
            balance = balance.credit(premiumDelta);
            if (premiumMarginFunding > 0) balance = balance.reserve(premiumMarginFunding);
        }
        if (feeDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(feeDelta));
        } else if (feeDelta > 0) {
            balance = balance.credit(feeDelta);
        }
        treasury = treasury.adjustFee(instrument.settleAsset(), Math.negateExact(feeDelta));
        balances.put(instrument.settleAsset(), balance);
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
        long nextMargin = Math.addExact(remainingMargin, marginIncrease);
        CorePositionState position = new CorePositionState(order.symbol(), reservation.asset(), order.marginMode(),
                order.positionSide(), nextQuantity == 0 ? 0 : order.instrumentVersion(), nextQuantity,
                nextEntryPrice, nextEntryValue,
                Math.addExact(current == null ? 0 : current.realizedPnlUnits(), realizedPnl), nextMargin);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(positionKey, position);
        return new DerivativeFillResult(user.transition(Math.incrementExact(user.revision()),
                balances, reservations, positions, user.positionMode()), treasury, Math.negateExact(feeDelta));
    }
    record DerivativeFillResult(CoreUserState user, CoreTreasuryState treasury, long feeUnits) {
    }
}
