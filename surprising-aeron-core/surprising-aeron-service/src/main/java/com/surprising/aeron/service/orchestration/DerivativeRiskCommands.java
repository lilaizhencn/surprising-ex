package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.state.RiskScanCoordinator;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.Collection;
import java.util.List;
import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.service.state.RuntimeDerivativeRiskProcessor;

/** 衍生品标记价、风险续扫、保险及 ADL 命令。 */
final class DerivativeRiskCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    DerivativeRiskCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void executeApplyMarkPrice(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe());
        int pendingBefore = owner.pendingRiskScanCount();
        long startedAt = System.nanoTime();
        RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime(command, owner.runtimeState,
                owner.identities);
        owner.triggers.initializeTriggerScan(command);
        owner.commits.requestCommitPublication();
        owner.logRiskScan("mark-price", command.symbol(), owner.runtimeState.riskScanControl().scanBatchSize(),
                pendingBefore, startedAt);
    }

    void executeExecuteAdl(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.resultBuilder.setSingleChangedUser(command.targetUserId());
        if (owner.runtimeState.asynchronousCommands()) {
            var work = RuntimeDerivativeLiquidationProcessor.beginAdl(command, owner.runtimeState, owner.identities);
            owner.deferControl(() -> {
                if (!work.getAsBoolean()) return false;
                owner.commits.requestCommitPublication();
                return true;
            });
        } else {
            RuntimeDerivativeLiquidationProcessor.applyAdlRuntime(command, owner.runtimeState, owner.identities);
            owner.commits.requestCommitPublication();
        }
    }

    void executeResolveLiquidation(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        if (owner.runtimeState.asynchronousCommands()) {
            var work = RuntimeDerivativeLiquidationProcessor.beginResolution(command, owner.runtimeState,
                    owner.identities, owner.liquidationIndex.activeIds());
            owner.deferControl(() -> {
                if (!work.getAsBoolean()) return false;
                owner.commits.requestCommitPublication();
                return true;
            });
        } else {
            RuntimeDerivativeLiquidationProcessor.applyResolutionRuntime(command, owner.runtimeState, owner.identities,
                    owner.liquidationIndex.activeIds());
            owner.commits.requestCommitPublication();
        }
    }

    void executeContinueRiskScan(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe());
        var control = owner.runtimeState.riskScanControl();
        if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "risk scan continuation exceeds current control");
        }
        var activeRiskScan = owner.runtimeState.firstRiskIncompleteScan();
        var activeScan = activeRiskScan == null
                ? owner.runtimeState.firstIncompleteRiskScan() : activeRiskScan;
        if (activeScan == null) return;
        String symbol = owner.identities.symbol(activeScan.symbolId());
        int pendingBefore = owner.pendingRiskScanCount();
        long startedAt = System.nanoTime();
        long beforeRevision = owner.runtimeState.revision();
        if (owner.runtimeState.asynchronousCommands()) {
            var risk = activeScan.riskComplete() ? null : new RiskScanCoordinator(command.maxUsers(),
                    owner.positionUserIndex, owner.runtimeState, owner.identities);
            owner.deferControl(new java.util.function.BooleanSupplier() {
                private java.util.function.BooleanSupplier triggers;
                @Override public boolean getAsBoolean() {
                    if (triggers == null) {
                        if (risk != null && !risk.poll()) return false;
                        if (owner.runtimeState.revision() != beforeRevision) owner.commits.requestCommitPublication();
                        int remaining = command.maxUsers() - (risk == null ? 0 : risk.completedWork());
                        var completedScan = owner.runtimeState.riskScan(activeScan.symbolId());
                        triggers = remaining > 0 && completedScan.riskComplete() && !completedScan.triggerComplete()
                                ? owner.triggers.pendingTriggerScan(symbol, remaining) : () -> true;
                    }
                    if (!triggers.getAsBoolean()) return false;
                    owner.logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
                    return true;
                }
            });
            return;
        }
        int completedRiskWork = 0;
        if (!activeScan.riskComplete()) {
            completedRiskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(command.maxUsers(),
                    owner.positionUserIndex,
                    owner.runtimeState, owner.identities);
        }
        if (owner.runtimeState.revision() != beforeRevision) {
            owner.commits.requestCommitPublication();
        }
        int remainingWork = command.maxUsers() - completedRiskWork;
        if (remainingWork > 0 && owner.runtimeState.riskScan(activeScan.symbolId()).riskComplete()) {
            owner.triggers.evaluatePendingTriggerScan(symbol, remainingWork);
        }
        owner.logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
    }

    void executeUpdateRiskScanControl(CoreMessage message, long clusterTimestamp) {
        var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
        RuntimeCommandProcessor.updateRiskScanControl(owner.runtimeState, command, clusterTimestamp);
        owner.commits.requestCommitPublication();
        owner.resultBuilder.commandRiskScanControl = owner.runtimeState.riskScanControl();
    }

    void executeAdjustInsuranceFund(CoreMessage message, long clusterTimestamp) {
        RuntimeCommandProcessor.adjustInsuranceFund(owner.runtimeState, owner.identities,
                TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe()));
        owner.commits.requestCommitPublication();
    }

    void executeLiquidationRuntime(com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
                                           Collection<CoreOrderState> canceledOrders) {
        RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(command, canceledOrders,
                owner.runtimeState, owner.identities);
        owner.commits.requestCommitPublication();
    }

    void advanceLiquidationCancellationRuntime(
            com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId) {
        RuntimeDerivativeLiquidationProcessor.applyCancellationAdvanceRuntime(command, canceledOrders,
                nextCursorOrderId, owner.runtimeState, owner.identities);
        owner.commits.requestCommitPublication();
    }
}
