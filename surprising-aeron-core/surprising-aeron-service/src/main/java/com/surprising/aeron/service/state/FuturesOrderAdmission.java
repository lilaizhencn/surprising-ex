package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary;
import static com.surprising.aeron.service.state.OrderAdmissionMath.fragmentationSafeFeeDebit;

final class FuturesOrderAdmission {
    private FuturesOrderAdmission() {}

    static long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage, AdmissionSummary admissionSummary) {
        long current = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        long projectedSigned = signedOrder > 0 ? projectedRisk : Math.negateExact(projectedRisk);
        long margin = openingMargin(instrument, projectedSigned, signedOrder, openSteps,
                order.reservationPriceTicks(), leverage, order.indexPriceTicks(), order.forwardPriceTicks());
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.max(1, Math.addExact(margin, feeDebit));

    }
    private static long openingMargin(
            CoreInstrumentState instrument, long projectedQuantity, long signedFill, long openSteps,
            long priceTicks, long leveragePpm, long indexPriceTicks, long forwardPriceTicks) {
        if (openSteps == 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantity), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long rate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                CoreContractMath.initialMarginRateFromLeverage(leveragePpm));
        return CoreContractMath.openingMarginUnits(instrument,
                signedFill > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL, priceTicks, openSteps, rate,
                indexPriceTicks, forwardPriceTicks, bracket.optionMarginFactorPpm());
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

        long openSteps = command.reduceOnly() ? 0 : command.quantitySteps();
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.symbol(), command.marginMode()),
                instrument.maxLeveragePpm());
        long projectedRiskQuantity = Math.addExact(Math.absExact(currentQuantity), command.quantitySteps());
        long projectedSteps = signedOrder > 0 ? projectedRiskQuantity : Math.negateExact(projectedRiskQuantity);
        long margin = openingMarginForFill(instrument, projectedSteps, signedOrder, openSteps,
                command.reservationPriceTicks(), leverage, command.indexPriceTicks(),
                command.forwardPriceTicks());
        long premium = instrument.contractType().isOption() && command.side() == CoreOrderSide.BUY
                ? OptionContractMath.optionPremiumUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps()) : 0;
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), Math.max(0, Math.negateExact(fee))));
    }
}
