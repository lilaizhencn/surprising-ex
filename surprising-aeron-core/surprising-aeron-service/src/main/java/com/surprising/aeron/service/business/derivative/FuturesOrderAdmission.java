package com.surprising.aeron.service.business.derivative;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.business.OrderAdmissionMath;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CorePositionState;

/** Perpetual and delivery order reservation rules shared by all four futures product lines. */
public final class FuturesOrderAdmission {
    private FuturesOrderAdmission() {
    }

    public static long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                        ResolvedPlaceOrder order, long leverage,
                                        long pendingQuantitySteps) {
        long current = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        long projectedSigned = signedOrder > 0 ? projectedRisk : Math.negateExact(projectedRisk);
        long margin = openingMargin(instrument, projectedSigned, signedOrder, openSteps,
                order.reservationPriceTicks(), leverage, order.indexPriceTicks(), order.forwardPriceTicks());
        long feeDebit = OrderAdmissionMath.fragmentationSafeFeeDebit(instrument, order);
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

    /** Deterministic replay entry point for futures order reservation. */
    public static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
        ActiveOrderIndex activeOrderIndex) {
        CorePositionState position = user.positions().get(
                positionKey(instrument.symbol(), command.positionSide()));
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());

        long openSteps = command.reduceOnly() ? 0 : command.quantitySteps();
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.symbol(), command.marginMode()),
                instrument.maxLeveragePpm());
        long projectedRiskQuantity = Math.addExact(Math.absExact(currentQuantity), command.quantitySteps());
        long projectedSteps = signedOrder > 0 ? projectedRiskQuantity : Math.negateExact(projectedRiskQuantity);
        long margin = openingMargin(instrument, projectedSteps, signedOrder, openSteps,
                command.reservationPriceTicks(), leverage, command.indexPriceTicks(),
                command.forwardPriceTicks());
        long premium = instrument.contractType().isOption() && command.side() == CoreOrderSide.BUY
                ? OptionContractMath.optionPremiumUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps()) : 0;
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), Math.max(0, Math.negateExact(fee))));
    }

    private static String positionKey(String symbol, CorePositionSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }
}
