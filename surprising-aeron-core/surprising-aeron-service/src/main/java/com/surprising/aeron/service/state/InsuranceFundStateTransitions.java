package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/** Owns direct insurance-fund balance adjustments in the authoritative treasury state. */
final class InsuranceFundStateTransitions {

    private InsuranceFundStateTransitions() {
    }

    static TradingCoreState adjust(TradingCoreState state, AdjustInsuranceFundCommand command) {
        long current = state.treasuryState().insuranceBalances().getOrDefault(command.asset(), 0L);
        if (command.deltaUnits() < 0 && Math.negateExact(command.deltaUnits()) > current) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "insurance fund balance is insufficient");
        }
        CoreTreasuryState treasury = state.treasuryState().adjustInsurance(command.asset(), command.deltaUnits());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), state.riskState(), treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }
}
