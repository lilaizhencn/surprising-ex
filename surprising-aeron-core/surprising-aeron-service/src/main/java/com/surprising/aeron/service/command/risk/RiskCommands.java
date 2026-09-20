package com.surprising.aeron.service.command.risk;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.RiskScanCoordinator;
import com.surprising.aeron.service.state.RuntimeRiskStateTransitions;
import com.surprising.aeron.service.state.RuntimeDerivativeRiskProcessor;

/** 衍生品标记价、风险续扫和风险扫描控制命令。 */
public final class RiskCommands {
    private final RiskCommandContext owner;
    private static final java.util.function.BooleanSupplier COMPLETE = () -> true;

    public RiskCommands(RiskCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeApplyMarkPrice(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe());
        int pendingBefore = owner.pendingRiskScanCount();
        long startedAt = System.nanoTime();
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(command, owner.runtimeState(), owner.identities());
        owner.initializeTriggerScan(command);
        owner.requestCommitPublication();
        owner.logRiskScan("mark-price", command.symbol(), owner.runtimeState().riskScanControl().scanBatchSize(),
                pendingBefore, startedAt);
    }

    public void executeContinueRiskScan(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe());
        var control = owner.runtimeState().riskScanControl();
        if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "risk scan continuation exceeds current control");
        }
        var activeRiskScan = owner.runtimeState().firstRiskIncompleteScan();
        var activeScan = activeRiskScan == null
                ? owner.runtimeState().firstIncompleteRiskScan() : activeRiskScan;
        if (activeScan == null) return;
        String symbol = owner.identities().symbol(activeScan.symbolId());
        int pendingBefore = owner.pendingRiskScanCount();
        long startedAt = System.nanoTime();
        long beforeRevision = owner.runtimeState().revision();
        if (owner.asynchronousCommands()) {
            RiskScanCoordinator risk = null;
            if (!activeScan.riskComplete()) {
                risk = owner.reusableRiskScanCoordinator(command.maxUsers());
            }
            owner.deferRiskScanControl(owner, risk, activeScan.symbolId(), symbol, command.maxUsers(),
                    pendingBefore, startedAt, beforeRevision);
            return;
        }
        int completedRiskWork = 0;
        if (!activeScan.riskComplete()) {
            completedRiskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(command.maxUsers(),
                    owner.positionUserIndex(), owner.runtimeState(), owner.identities());
        }
        if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
        int remainingWork = command.maxUsers() - completedRiskWork;
        if (remainingWork > 0 && owner.runtimeState().riskScan(activeScan.symbolId()).riskComplete()) {
            owner.evaluatePendingTriggerScan(symbol, remainingWork);
        }
        owner.logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
    }

    public void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        RuntimeRiskStateTransitions.updateScanControl(owner.runtimeState(), command, clusterTimestamp);
        owner.requestCommitPublication();
        owner.setCommandRiskScanControl(owner.runtimeState().riskScanControl());
    }
}
