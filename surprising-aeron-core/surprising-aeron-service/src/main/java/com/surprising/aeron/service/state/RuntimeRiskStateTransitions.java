package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreRiskState;

/** Owns runtime risk-scan control and the persisted scan cursor projection. */
public final class RuntimeRiskStateTransitions {

    private RuntimeRiskStateTransitions() {
    }

    public static void updateScanControl(TradingRuntimeState runtime, UpdateRiskScanControlCommand command,
                                  long updatedAtEpochMillis) {
        if (runtime == null || command == null) {
            throw new IllegalArgumentException("invalid runtime risk scan control update");
        }
        runtime.assertOwner();
        CoreRiskScanControlView current = runtime.riskScanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        runtime.setRiskScanControl(new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis)));
        runtime.incrementCommandRevision();
    }

    public static void replaceScan(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                            CoreRiskState.RiskScan scan) {
        if (runtime == null || identities == null || scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        runtime.putRiskScan(new RiskScanRuntime(identities.symbolId(scan.symbol()), scan.accountLaneId(),
                scan.priceSequence(), scan.scanStartPriceSequence(), scan.lastUserId(),
                scan.riskComplete(), scan.riskUserId(), scan.riskPhase(), scan.riskPositionCursor(),
                scan.riskReservationCursor(), scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(),
                scan.riskIsolatedMarginUnits(), scan.riskIsolatedReservationUnits(), scan.triggerComplete(),
                scan.triggerPhase(), scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor(), scan.lastScheduledRevision(), scan.laneProgress()));
        runtime.incrementCommandRevision();
    }

    public static void replaceScan(TradingRuntimeState runtime, RiskScanRuntime scan) {
        if (runtime == null || scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        runtime.putRiskScan(scan);
        runtime.incrementCommandRevision();
    }
}
