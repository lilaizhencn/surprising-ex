package com.surprising.aeron.service.command.risk;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RiskScanCoordinator;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.RuntimeDerivativeRiskProcessor;

/** 衍生品标记价、风险续扫和风险扫描控制命令。 */
public final class RiskCommands {
    private final RiskCommandContext owner;
    /** Single owner-thread continuation reused by asynchronous scan commands. */
    private final RiskScanContinuation scanContinuation = new RiskScanContinuation();
    /** Lazily created after TradingCoreRuntime has projected its state; reused between scans. */
    private RiskScanCoordinator scanCoordinator;

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
                if (scanCoordinator == null) scanCoordinator = new RiskScanCoordinator(command.maxUsers(),
                        owner.positionUserIndex(), owner.runtimeState(), owner.identities());
                else scanCoordinator.resetForCommand(command.maxUsers());
                risk = scanCoordinator;
            }
            scanContinuation.prepare(risk, activeScan.symbolId(), symbol, command.maxUsers(),
                    pendingBefore, startedAt, beforeRevision);
            owner.deferControl(scanContinuation);
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

    /**
     * Reusable asynchronous owner continuation. It carries only primitive command metadata and
     * the pooled coordinator; no per-command anonymous BooleanSupplier or captured object graph
     * is allocated on the hot control path.
     */
    private final class RiskScanContinuation implements java.util.function.BooleanSupplier {
        private RiskScanCoordinator risk;
        private int symbolId;
        private String symbol;
        private int maxUsers;
        private int pendingBefore;
        private long startedAt;
        private long beforeRevision;
        private java.util.function.BooleanSupplier triggers;

        void prepare(RiskScanCoordinator risk, int symbolId, String symbol, int maxUsers,
                     int pendingBefore, long startedAt, long beforeRevision) {
            this.risk = risk;
            this.symbolId = symbolId;
            this.symbol = symbol;
            this.maxUsers = maxUsers;
            this.pendingBefore = pendingBefore;
            this.startedAt = startedAt;
            this.beforeRevision = beforeRevision;
            this.triggers = null;
        }

        @Override public boolean getAsBoolean() {
            if (triggers == null) {
                if (risk != null && !risk.poll()) return false;
                if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
                int remaining = maxUsers - (risk == null ? 0 : risk.completedWork());
                var completedScan = owner.runtimeState().riskScan(symbolId);
                triggers = remaining > 0 && completedScan != null && completedScan.riskComplete()
                        && !completedScan.triggerComplete()
                        ? owner.pendingTriggerScan(symbol, remaining) : COMPLETE;
            }
            if (!triggers.getAsBoolean()) return false;
            owner.logRiskScan("continuation", symbol, maxUsers, pendingBefore, startedAt);
            return true;
        }
    }

    public void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        RuntimeCommandProcessor.updateRiskScanControl(owner.runtimeState(), command, clusterTimestamp);
        owner.requestCommitPublication();
        owner.setCommandRiskScanControl(owner.runtimeState().riskScanControl());
    }
}
