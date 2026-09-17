package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.state.model.CoreRiskState;

/** Owns version-checked risk scan control changes without changing scan progress. */
final class RiskScanControlStateTransitions {

    private RiskScanControlStateTransitions() {
    }

    static TradingCoreState update(
            TradingCoreState state, UpdateRiskScanControlCommand command, long updatedAtEpochMillis) {
        CoreRiskScanControlView current = state.riskState().scanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        CoreRiskScanControlView updated = new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis));
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                state.riskState().liquidations(), state.riskState().scans(), state.riskState().nextLiquidationId(),
                updated, state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), risk, state.treasuryState(), state.leverages(),
                state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(), state.triggerOrders());
    }
}
