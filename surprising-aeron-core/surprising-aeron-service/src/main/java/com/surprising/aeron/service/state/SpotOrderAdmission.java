package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.*;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionSummary;
import static com.surprising.aeron.service.state.OrderAdmissionMath.fragmentationSafeFeeDebit;

final class SpotOrderAdmission {
    private SpotOrderAdmission() {}

    static long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage, AdmissionSummary admissionSummary) {
        if (order.side() == CoreOrderSide.SELL) return order.quantitySteps();
        long notional = Math.multiplyExact(order.reservationPriceTicks(), order.quantitySteps());
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);

    }
    static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrumentState instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        if (command.side() == CoreOrderSide.SELL) return command.quantitySteps();
        long notional = Math.multiplyExact(command.reservationPriceTicks(), command.quantitySteps());
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.addExact(notional, Math.max(0, Math.negateExact(fee)));
        }
}
