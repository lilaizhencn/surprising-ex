package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.exception.CoreStateRejectedException;

/**
 * 直接控制命令的终结边界。
 *
 * <p>直接命令可能在 Account Lane 上异步完成。此类拥有“准备变更—等待 Lane—发布结果—
 * 守恒校验”的完整生命周期，撮合命令仍由 {@link MatchingCommandAdmission} 和
 * {@link OrderedCommitCoordinator} 负责。</p>
 */
final class CoreDirectCommandFlow {

    private final TradingCoreRuntime runtime;

    CoreDirectCommandFlow(TradingCoreRuntime runtime) {
        this.runtime = runtime;
    }

    CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition,
                       TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint) {
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        runtime.activateFactContext(message, fingerprint);
        long beforePublicationSequence = runtime.publicationSequence;
        long beforeRuntimeRevision = runtime.runtimeState.revision();
        long runtimeCommandCheckpoint = runtime.runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = runtime.identities.positionCheckpoint();
        runtime.directCommand.initialize(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforePublicationSequence, beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
        runtime.resultBuilder.beginCommand();
        runtime.admissions.queuedMatching.clear();
        runtime.commits.beginCommitPublicationBatch();
        try {
            status = runtime.applyCommand(message, clusterTimestamp);
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (ArithmeticException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
        } catch (IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        if (!runtime.directCommand.hasControlWork() && runtime.runtimeState.asynchronousCommands()
                && !runtime.commandIngress.clusterPipelineAdmission()) {
            runtime.directCommand.result(status, resultCode);
        }
        if (runtime.directCommand.hasControlWork() || runtime.directCommand.status() != null) return null;
        return finish(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforePublicationSequence, beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }

    CoreResponse finish(CoreMessage message, long clusterTimestamp, long clusterPosition,
                        TradingCoreRuntime.SourceKey sourceKey, CommandFingerprint fingerprint,
                        long beforePublicationSequence, long beforeRuntimeRevision,
                        long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
                        ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(runtime.appliedCommandCount);
        if (!runtime.directCommand.finalizationPrepared()) {
            if (status != ResponseStatus.APPLIED
                    && (runtime.commits.commitPublicationDirty()
                    || runtime.runtimeState.revision() != beforeRuntimeRevision
                    || runtime.runtimeState.hasUncommittedCommandChanges())) {
                runtime.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(runtime.appliedCommandCount));
            }
            if (status == null) {
                runtime.commits.abortCommitPublicationBatch();
                return finishContext(runtime.rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                if (runtime.runtimeState.asynchronousCommands()) {
                    try {
                        if (!runtime.triggers.collectClosingTriggerIds().isEmpty()) {
                            runtime.commits.requestCommitPublication();
                        }
                    } catch (ArithmeticException failure) {
                        if (deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW)) return null;
                        throw failure;
                    }
                } else {
                    runtime.triggers.cancelTriggersForClosedPositions();
                }
                if (runtime.pendingMatching.size() + runtime.admissions.queuedMatching.size()
                        > runtime.pendingMatching.capacity()) {
                    runtime.admissions.queuedMatching.clear();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    runtime.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(runtime.appliedCommandCount));
                    return finishContext(runtime.rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            if (status == ResponseStatus.APPLIED) {
                java.util.List<Long> changedOrderIds = runtime.resultBuilder.commandChangedOrderIds == null
                        ? java.util.List.of() : runtime.resultBuilder.commandChangedOrderIds;
                try {
                    if (runtime.runtimeState.asynchronousCommands()) {
                        com.surprising.aeron.service.state.RuntimeOrderCommitStateTransitions.validateStampInputs(
                                clusterTimestamp, clusterPosition, changedOrderIds);
                    } else {
                        runtime.stampOrderChangesRuntime(clusterTimestamp, clusterPosition, changedOrderIds);
                    }
                } catch (IllegalStateException exception) {
                    if (deferFinalizationRejection(CoreResultCode.INVALID_COMMAND)) return null;
                    runtime.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(runtime.appliedCommandCount));
                    return finishContext(runtime.rejected(CoreResultCode.INVALID_COMMAND));
                }
            }
            runtime.resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !runtime.resultBuilder.commandChangedUserIds.isEmpty()) {
                if (runtime.runtimeState.asynchronousCommands()) {
                    runtime.runtimeState.releaseCompletedSequentialLaneStage();
                    runtime.directCommand.commitEvent(runtime.runtimeState.dispatchLaneMutation(
                            nextAppliedCommandCount, runtime.resultBuilder.commandChangedUserIds,
                            runtime.resultBuilder.commandChangedOrderIds, runtime.triggers.closingTriggerIds(),
                            clusterTimestamp, clusterPosition));
                } else {
                    runtime.runtimeState.stageLaneMutation(nextAppliedCommandCount,
                            runtime.resultBuilder.commandChangedUserIds);
                }
            }
            runtime.directCommand.markFinalizationPrepared();
        }
        if (runtime.directCommand.commitEvent() != null) {
            if (!runtime.runtimeState.laneCommitComplete(runtime.directCommand.commitEvent())) {
                runtime.directCommand.result(status, resultCode);
                return null;
            }
            runtime.runtimeState.releaseLaneCommit(runtime.directCommand.commitEvent());
            runtime.directCommand.clearCommitEvent();
        }
        runtime.directCommand.clearFinalizationPrepared();
        runtime.commits.completeCommitPublicationBatch();
        if (status == ResponseStatus.APPLIED) runtime.validateFundsConservation(message);
        boolean tradingStateChanged = status == ResponseStatus.APPLIED
                && runtime.publicationSequence != beforePublicationSequence;
        long businessStateHash = tradingStateChanged
                ? runtime.currentBusinessStateHash() : runtime.cachedBusinessStateHash;
        runtime.appliedCommandCount = nextAppliedCommandCount;
        runtime.refreshCommittedCoreSequence();
        runtime.cachedBusinessStateHash = businessStateHash;
        runtime.admissions.appendQueuedMatching(clusterTimestamp, clusterPosition);
        runtime.lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (TradingCoreRuntime.isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            runtime.terminalRetention.retainFundsCommand(message.header().commandId(), fingerprint);
        }
        long stateHash = businessStateHash;
        byte[] responseData = runtime.resultBuilder.commandResultData();
        int responseOffset = runtime.resultBuilder.responseDataOffset();
        int responseLength = runtime.resultBuilder.responseDataLength();
        runtime.resultLedger.storeOwnedResult(message.header().commandId(), fingerprint, status, resultCode,
                runtime.appliedCommandCount, stateHash, responseData,
                responseOffset, responseLength);
        CoreResponse response = CoreResponse.owned(status, status, resultCode, runtime.appliedCommandCount,
                stateHash, responseData, responseOffset, responseLength);
        runtime.resultBuilder.transferResponseOwnership();
        return finishContext(response);
    }

    private CoreResponse finishContext(CoreResponse response) {
        runtime.directCommand.clear();
        return runtime.finishFactContext(response);
    }

    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!runtime.runtimeState.asynchronousCommands()) return false;
        if (!runtime.directCommand.active()) {
            throw new IllegalStateException("asynchronous finalization has no command context");
        }
        runtime.directCommand.result(ResponseStatus.REJECTED, code);
        return true;
    }
}
