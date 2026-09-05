package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary;
import static com.surprising.aeron.service.state.OrderAdmissionMath.fragmentationSafeFeeDebit;

final class OptionOrderAdmission {
    private OptionOrderAdmission() {}

    static long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage, AdmissionSummary admissionSummary) {
        long current = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        long currentAbs = Math.absExact(current);
        boolean opposite = current != 0 && Long.signum(current) != Long.signum(signedOrder);
        long closeSteps = opposite ? Math.min(currentAbs, order.quantitySteps()) : 0;
        long openSteps = order.reduceOnly() ? 0 : Math.subtractExact(order.quantitySteps(), closeSteps);
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        if (order.side() == CoreOrderSide.BUY) {
            long premium = OptionContractMath.optionPremiumUnits(
                    instrument, order.reservationPriceTicks(), order.quantitySteps());
            long releasedMargin = position == null || closeSteps == 0 ? 0
                    : proportional(position.positionMarginUnits(), closeSteps, currentAbs);
            return Math.max(1, Math.max(0,
                    Math.subtractExact(Math.addExact(premium, feeDebit), releasedMargin)));
        }
        if (openSteps == 0) return Math.max(1, feeDebit);
        long totalSellOrders = Math.addExact(admissionSummary.pendingQuantity(), order.quantitySteps());
        long projectedSigned = Math.subtractExact(current, totalSellOrders);
        long projectedRisk = Math.max(0, Math.negateExact(projectedSigned));
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedRisk, order.indexPriceTicks());
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long margin = OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                order.reservationPriceTicks(), order.markPriceTicks(), openSteps,
                order.indexPriceTicks(), order.forwardPriceTicks(), bracket);
        return Math.max(1, Math.addExact(margin, feeDebit));

    }
    static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        CorePositionState position = user.positions().get(positionKey(instrument.symbol(), command.positionSide()));
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());

        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0
                && Long.signum(currentQuantity) != Long.signum(signedOrder);
        long closeSteps = opposite ? Math.min(currentAbs, command.quantitySteps()) : 0;
        long openSteps = command.reduceOnly() ? 0
                : Math.subtractExact(command.quantitySteps(), closeSteps);
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        long feeDebit = Math.max(0, Math.negateExact(fee));
        if (command.side() == CoreOrderSide.BUY) {
            long premium = OptionContractMath.optionPremiumUnits(
                    instrument, command.reservationPriceTicks(), command.quantitySteps());
            long releasedMargin = position == null || closeSteps == 0 ? 0
                    : proportional(position.positionMarginUnits(), closeSteps, currentAbs);
            return Math.max(1, Math.max(0,
                    Math.subtractExact(Math.addExact(premium, feeDebit), releasedMargin)));
        }
        if (openSteps == 0) return Math.max(1, feeDebit);
        long pendingSell = activeOrderIndex == null
                ? userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && !order.reduceOnly()
                        && order.symbol().equals(instrument.symbol())
                        && order.positionSide() == command.positionSide()
                        && order.side() == CoreOrderSide.SELL)
                .mapToLong(CoreOrderState::remainingQuantitySteps).reduce(0L, Math::addExact)
                : activeOrderIndex.pendingQuantity(user.userId(), instrument.symbol(),
                command.positionSide(), CoreOrderSide.SELL);
        long totalSellOrders = Math.addExact(pendingSell, command.quantitySteps());
        long projectedSigned = Math.subtractExact(currentQuantity, totalSellOrders);
        long projectedRisk = Math.max(0, Math.negateExact(projectedSigned));
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedRisk, command.indexPriceTicks());
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long margin = OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                command.reservationPriceTicks(), command.markPriceTicks(), openSteps,
                command.indexPriceTicks(), command.forwardPriceTicks(), bracket);
        return Math.max(1, Math.addExact(margin, feeDebit));
        }
}
