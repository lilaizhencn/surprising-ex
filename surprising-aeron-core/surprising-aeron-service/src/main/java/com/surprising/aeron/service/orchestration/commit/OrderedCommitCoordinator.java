package com.surprising.aeron.service.orchestration.commit;

import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.pipeline.CommandSlot;
import com.surprising.aeron.service.orchestration.pipeline.OrderBatchPending;
import com.surprising.aeron.service.state.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.risk.RiskScanCoordinator;
import com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.state.settlement.MatcherSettlementEvent;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
@Slf4j
public final class OrderedCommitCoordinator {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    public OrderedCommitCoordinator(TradingCoreRuntime owner) {
        this.owner = owner;
        this.publication = new CommitPublication(owner);
    }

    /** 各撮合分片已核验的序号；提交时逐分片验证连续性。 */
    long[] appliedMatcherSequences;

    /** 已预派发且尚未收集完成的结算命令数量。 */
    int dispatchedSettlementInFlight;

    /** 结算在途数量峰值，仅用于运行观测。 */
    int dispatchedSettlementHighWaterMark;

    public int dispatchedSettlementHighWaterMark() {
        return dispatchedSettlementHighWaterMark;
    }

    /** 只拥有撮合完成顺序；提交发布批次由独立的状态 owner 管理。 */
    public final CommitPublication publication;

    public CoreResponse completeRejectedMatching(long sequence) {
        owner.assertOwner();
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null || !owner.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
        var direct = pending.settlementEvent();
        if (pending.isMatchingSubmitted()) {
            // Lane rejection can race with the Matcher admission fence. Keep the ordered
            // command alive until the in-flight Matcher token has published; recycling the slot
            // earlier lets the worker publish into a newly claimed sequence.
            if (direct != null && direct.direct()) {
                if (!direct.complete()) return null;
            } else if (pending.matchingResult() == null && pending.matchingCompletion() == null) {
                return null;
            }
        }
        CoreResultCode resultCode = pending.matchingRejection();
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.matchingFlow.commitMatchingSequence(sequence);
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA, 0, 0);
        if (direct != null && direct.direct()) {
            // A rejected admission still consumed one Matcher evidence sequence.  Advance the
            // Advance the Owner's shard sequence before recycling the direct event so restored
            // runtimes see the same Matcher cursor as the live runtime.
            if (direct.directResultAvailable()) {
                MatchingResult matcherResult = direct.directResult();
                // The normal direct completion path validates and applies the same result before
                // it reaches this rejection helper. Avoid applying that evidence twice when the
                // Lane rejection is observed after the Matcher event has completed.
                int shard = matcherResult.nativeMatcherShardId();
                if (matcherSequence(shard) != matcherResult.nativeMatcherSequence()) {
                    validateMatchingEvidence(pending, matcherResult);
                    applyMatcherProgress(matcherResult);
                }
            }
            owner.runtimeState().discardMatcherSettlement(pending.takeSettlementEvent());
        } else if (pending.isMatchingSubmitted()) {
            MatchingResult matcherResult = pending.matchingResult();
            if (matcherResult == null) matcherResult = pending.matchingCompletion();
            if (matcherResult != null) {
                validateMatchingEvidence(pending, matcherResult);
                applyMatcherProgress(matcherResult);
            }
        }
        owner.matchingFlow.removePendingMatching(sequence);
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return owner.finishFactContext(response);
    }

    public CoreResponse completeMatching(long sequence,
                                  MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        return completeMatching(owner.pendingMatching.get(sequence), matchingResult, clusterTimestamp, clusterPosition);
    }

    CoreResponse completeMatching(CommandSlot pending,
                                  MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        if (pending == null || matchingResult == null) return null;
        long sequence = pending.sequence();
        OrderBatchPending waitingBatch = pending.orderBatch() != null ? pending.orderBatch() : owner.batches().batch(sequence);
        try {
            owner.runtimeState().assertAccountLanesHealthy();
        } catch (RuntimeException failure) {
            if (waitingBatch != null) throw owner.batches().failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required", failure);
        }
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = waitingBatch != null ? waitingBatch : owner.batches().batch(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure();
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (failedBatch != null) throw owner.batches().failOrderBatch(failedBatch, pending, detail, failure);
            owner.matchingAdapter().poisonFromOwner("unreconciled matcher outcome sequence=" + sequence
                    + " result=" + matchingResult.resultCode());
            throw owner.failMatching(pending, detail, failure);
        }
        if (pending.operation() == CommandSlot.Operation.PLACE && pending.placeAdmission() != null) {
            if (!owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) return null;
            if (owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) {
                if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                        && (!pending.settlementEvent().complete() || pending.submittedMatcherShard() != -1)) {
                    return null;
                }
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                return completeRejectedMatching(pending.sequence());
            }
        }
        if (!financialHeadReady(pending)) return null;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        clusterTimestamp = pending.commitFenceTimestamp();
        clusterPosition = pending.commitFenceClusterPosition();
        owner.currentClusterPosition(clusterPosition);
        // Matcher releases the route before publishing Lane readiness. A command slot cannot
        // be recycled while its producer still owns it; transport retirement is independent.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                && pending.submittedMatcherShard() != -1) return null;
        if (pending.controlPending()) {
            owner.restoreMatchingCommitContext(pending);
            try {
                // The completed risk continuation also restores the response summary after suspension.
                if (pending.controlWork() != null && !pending.controlWork().getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                // Account work is consumed once; commit publication may require later polls.
                pending.controlWork(null);
                if (!pending.controlAccountsCommitted() && pending.commitEvent() == null) {
                    owner.runtimeState().completePendingReservations(sequence);
                    owner.resultBuilder.materializeChangeAccumulators();
                    // A suspended control may accumulate users across several risk pages/Lanes.
                    // Register the complete commit input, not just the last page's runtime delta.
                    for (long userId : owner.resultBuilder.commandChangedUserIds)
                        pending.includeControlLanes(owner.matchingAdapter().topology().accountLaneMask(userId),
                                validAccountLaneMask());
                    pending.commitEvent(owner.runtimeState().dispatchLaneMutation(sequence,
                            owner.resultBuilder.commandChangedUserIds,
                            publication.dirty() ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                            clusterTimestamp, clusterPosition));
                }
                if (pending.commitEvent() != null) {
                    if (!owner.runtimeState().laneCommitComplete(pending.commitEvent())) {
                        retainControlResponse(pending, matchingResult);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    long laneMask = pending.commitEvent().requiredLaneMask();
                    owner.runtimeState().releaseLaneCommit(pending.commitEvent());
                    pending.commitEvent(null);
                    pending.completeLanes(laneMask);
                }
                pending.controlWork(null);
                pending.controlPending(false);
                return finishAppliedMatching(pending, matchingResult, true);
            } catch (RuntimeException failure) {
                throw owner.failMatching(pending, "control Lane continuation failed", failure);
            }
        }
        if (waitingBatch != null && waitingBatch.pipelined() && !waitingBatch.commitStarted()) {
            PlaceBatchAdmissionEvent admission = waitingBatch.placeBatchAdmissionEvent();
            boolean preparedCancel = pending.clusterIndependent() && waitingBatch.kind() == OrderBatchKind.CANCEL;
            if (preparedCancel || (admission != null && admission.complete() && admission.rejection() == null)) {
                owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
            }
        }
        if (waitingBatch != null && waitingBatch.settlementEvent() != null && waitingBatch.settlementEvent().direct()
                && !waitingBatch.matchingApplied()) {
            if (!waitingBatch.settlementEvent().complete()) return null;
            validateMatchingEvidence(pending, waitingBatch.settlementEvent().directResult());
            applyMatcherProgress(waitingBatch.settlementEvent());
            waitingBatch.nextIndex(waitingBatch.itemCount());
            waitingBatch.matchingApplied(true);
        }
        if (waitingBatch != null) {
            try {
                // 尚未完成的 Lane 工作仍拥有挂起的上下文，不能提前占用 owner 发布批次。
                if (!waitingBatch.laneWorkComplete()) return null;
            } catch (RuntimeException failure) {
                throw owner.batches().failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            }
        }
        if (waitingBatch != null && pending.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
        if (waitingBatch != null && pending.clusterIndependent()
                && waitingBatch.kind() == OrderBatchKind.CANCEL && !waitingBatch.commitStarted()) {
            owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        // A sequential batch item now owns a preconstructed direct event.  The event can be
        // Lane-complete before the Owner has copied the Matcher token into the batch context;
        // apply the authoritative result through the normal batch path exactly once, after the
        // suspended commit context has been restored.
        if (waitingBatch != null && waitingBatch.itemSettlementEvent() != null
                && waitingBatch.itemSettlementEvent().direct()
                && waitingBatch.lastMatchingResult() == null) {
            if (!waitingBatch.itemSettlementEvent().complete()) {
                waitingBatch.lastMatchingResult(matchingResult);
                return null;
            }
            MatchingResult directResult = waitingBatch.itemSettlementEvent().directResult();
            validateMatchingEvidence(pending, directResult);
            applyMatcherProgress(directResult);
            return owner.batches().completeOrderBatchMatching(
                    sequence, directResult,
                    clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemSettlementEvent() != null) {
            if (waitingBatch.itemSettlementEvent().direct()) {
                MatchingResult directResult = waitingBatch.itemSettlementEvent().directResult();
                validateMatchingEvidence(pending, directResult);
                applyMatcherProgress(directResult);
                waitingBatch.lastMatchingResult(directResult);
                if (waitingBatch.kind() == OrderBatchKind.CANCEL) {
                    return owner.batches().completeOrderBatchMatching(
                            sequence, directResult, clusterTimestamp, clusterPosition);
                }
            }
            return owner.batches().completeOrderBatchItemSettlement(
                    waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.hasPendingLaneWork()) {
            if (waitingBatch.pipelined() && !waitingBatch.commitStarted()) {
                owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
            }
            if (pending.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return owner.batches().finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.matchingApplied()) {
            if (!waitingBatch.commitStarted()) owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
            return owner.batches().finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (pending.settlementEvent() != null) {
            if (!pending.settlementEvent().complete()) return null;
            // 先收取账户准入结果；拒单没有结算变更，不能开启或恢复发布批次。
            // Matcher/Lane 可先于 owner 完成，拒单也必须走同一个有序终态边界。
            if (pending.operation() == CommandSlot.Operation.PLACE && pending.placeAdmission() != null) {
                if (!owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) return null;
                if (owner.matchingFlow.hasPendingMatchingRejection(pending.sequence()))
                    return completeRejectedMatching(pending.sequence());
            }
            if (pending.settlementEvent().direct() && !pending.hasCommitContext()) {
                validateMatchingEvidence(pending, matchingResult);
                applyMatcherProgress(matchingResult);
                pending.result(matchingResult, pending.settlementEvent().requiredLaneMask(), validAccountLaneMask());
                owner.resultBuilder.clearOrderViews();
                owner.resultBuilder.commandChangedUserIds = List.of();
                owner.resultBuilder.commandChangedOrderIds = List.of();
                owner.resultBuilder.commandTradeCount(0);
                owner.resultBuilder.commandLiquidationProgress = null;
                owner.resultBuilder.commandLiquidationBatchResult = null;
                owner.resultBuilder.commandRiskScanControl = null;
                owner.resultBuilder.resetChangeAccumulators();
                owner.setCommandFunds(pending.fundsAccumulator());
                owner.activateFactContext(pending.command(), pending.fingerprint());
                publication.begin();
                return completeDispatchedMatcherSettlement(pending, matchingResult);
            }
            if (pending.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedMatcherSettlement(pending, matchingResult);
        }
        if (waitingBatch != null && waitingBatch.pipelined() && !waitingBatch.commitStarted()) {
            owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = owner.batches().batch(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure();
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (failedBatch != null) throw owner.batches().failOrderBatch(failedBatch, pending, detail, failure);
            owner.matchingAdapter().poisonFromOwner("unreconciled matcher outcome sequence=" + sequence
                    + " result=" + matchingResult.resultCode());
            throw owner.failMatching(pending, detail, failure);
        }
        validateMatchingEvidence(pending, matchingResult);
        applyMatcherProgress(matchingResult);
        if (owner.batches().batch(sequence) != null) {
            return owner.batches().completeOrderBatchMatching(sequence, matchingResult, clusterTimestamp, clusterPosition);
        }
        pending.result(matchingResult, expectedLaneMask(pending, matchingResult), validAccountLaneMask());
        owner.resultBuilder.clearOrderViews();
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        owner.resultBuilder.commandTradeCount(0);
        owner.resultBuilder.commandLiquidationProgress = null;
        owner.resultBuilder.commandLiquidationBatchResult = null;
        owner.resultBuilder.commandRiskScanControl = null;
        owner.resultBuilder.resetChangeAccumulators();
        owner.setCommandFunds(pending.fundsAccumulator());
        owner.activateFactContext(pending.command(), pending.fingerprint());
        publication.begin();
        try {
            switch (pending.operation()) {
                case PLACE, CANCEL, REPLACE, AMEND, TRIGGER ->
                        throw new IllegalStateException("trading completion requires its direct Matcher fact");
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = pending.liquidationCheck().liquidation(0);
                    boolean executable = matchingResult.accepted() && liquidation != null && pending.liquidationCheck().executable(0);
                    TradingCoreRuntime.LifecycleOrderChunk chunk = !executable ? new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0)
                            : owner.matchingFlow.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(),
                            command.maxOrders());
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(chunk.orders());
                    if (liquidation == null) owner.resultBuilder.commandChangedUserIds = List.of();
                    else owner.resultBuilder.setSingleChangedUser(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                        var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                        var progress = new CoreLiquidationProgressView(nextCursor == 0, nextCursor,
                                chunk.orders().size());
                        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                            var work = com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor
                                     .beginExecution(pending.liquidationWork(), command, orders, nextCursor,
                                            owner.runtimeState(), owner.identities());
                            pending.liquidationWork(work);
                            pending.deferLiquidationExecutionControl(this, work, progress);
                        } else {
                            if (nextCursor != 0) owner.liquidations().advanceLiquidationCancellationRuntime(
                                    command, orders, nextCursor);
                            else owner.liquidations().executeLiquidationRuntime(command, orders);
                            owner.resultBuilder.commandLiquidationProgress = progress;
                        }
                    }
                }
                case LIQUIDATION_BATCH -> {
                    var command = pending.decodedCommand().liquidationBatch();
                    applyLiquidationBatch(command, matchingResult, pending);
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    if (matchingResult.accepted()) {
                        owner.instrumentSettlement().applySettlementChangedIds(command);
                        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                            var work = owner.instrumentSettlement().beginAsyncSettlement(
                                    pending.settlementWork(), command, pending.command().header().commandId());
                            pending.settlementWork(work);
                            pending.deferSettlementControl(this, work);
                        } else {
                            owner.instrumentSettlement().settleInstrumentRuntime(command,
                                    pending.command().header().commandId());
                        }
                    }
                }
            }
            if (pending.controlWork() != null) {
                pending.controlPending(true);
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState().completePendingReservations(pending.sequence());
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        }
        return finishAppliedMatching(pending, matchingResult, false);
    }

    /**
     * Common deterministic tail for every terminal matching path.  Collection and Lane event
     * release remain operation-specific, but sequence/hash/ledger/response work must have one
     * implementation so the Owner does not repeat it for place, cancel and direct settlement.
     */
    private CoreResponse storeTerminalResponse(CommandSlot pending, MatchingResult matchingResult,
            ResponseStatus status, CoreResultCode resultCode) {
        long applied = pending.sequence();
        owner.matchingFlow.commitMatchingSequence(applied);
        boolean builderEncoded = pending.resultData() == null;
        // Control continuations may have prepared the response into resultData before the
        // terminal commit.  That byte slice still belongs to the reusable result builder until
        // this response is handed to the command window, even though it is not encoded here.
        byte[] responseData = builderEncoded
                ? owner.resultBuilder.commandResultData(pending, matchingResult) : pending.resultData();
        int responseOffset = builderEncoded ? owner.resultBuilder.responseDataOffset() : 0;
        int responseLength = builderEncoded ? owner.resultBuilder.responseDataLength() : responseData.length;
        boolean laneResponse = responseData != null && responseData == pending.lanePreparedResponse();
        pending.resultData(null);
        owner.terminalTradeCount(Math.addExact(owner.terminalTradeCount(), owner.resultBuilder.commandTradeCount()));
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(), pending.fingerprint(),
                status, resultCode, applied, responseData,
                responseOffset, responseLength);
        CoreResponse response = pending.command().ownedResponse(status, status, resultCode, applied,
                responseData, responseOffset, responseLength);
        owner.resultBuilder.transferResponseOwnership();
        if (laneResponse) pending.transferLaneResponseOwnership();
        return response;
    }

    /**
     * 撮合终态提交统一收尾：完成发布批次、校验资金守恒、关闭财务派发、
     * 记录终态响应、递减在途派发计数、回收结算事件、移除挂起状态、
     * 结束事实上下文并触发后续延期或批量撮合推进。
     */
    private CoreResponse finishTerminalMatching(CommandSlot pending, MatchingResult matchingResult) {
        publication.complete();
        owner.validateFundsConservation(pending.command());
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        closeFinancialDispatch(pending);
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        if (pending.takePipelinedSettlementCounted()) {
            dispatchedSettlementInFlight--;
            if (dispatchedSettlementInFlight < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        owner.runtimeState().releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.matchingFlow.removePendingMatching(pending.sequence());
        // Finish this command before restoring the next batch's suspended commit context.
        owner.finishFactContext(response);
        if (owner.pendingMatching.hasDeferred() || owner.batches().hasPendingBatches()) {
            owner.matchingProgress.submitDeferredMatchingAfterBatch();
        }
        return response;
    }

    private CoreResponse finishAppliedMatching(CommandSlot pending, MatchingResult matchingResult,
            boolean lanesCommitted) {
        long sequence = pending.sequence();
        long clusterTimestamp = pending.commitFenceTimestamp();
        long clusterPosition = pending.commitFenceClusterPosition();
        if (!lanesCommitted && pending.settlementEvent() == null) {
            owner.resultBuilder.materializeChangeAccumulators();
            pending.commitEvent(owner.runtimeState().dispatchLaneMutation(sequence,
                    owner.resultBuilder.commandChangedUserIds,
                    publication.dirty() ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                    clusterTimestamp, clusterPosition));
            if (pending.commitEvent() != null) {
                if (!owner.runtimeState().laneCommitComplete(pending.commitEvent())) {
                    pending.controlPending(true);
                    retainControlResponse(pending, matchingResult);
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                long laneMask = pending.commitEvent().requiredLaneMask();
                owner.runtimeState().releaseLaneCommit(pending.commitEvent());
                pending.commitEvent(null);
                pending.completeLanes(laneMask);
            }
        }
        if (pending.completedLaneMask() != pending.expectedLaneMask()) {
            throw owner.failMatching(pending, "account lane mask differs from immutable matcher result", null);
        }
        requireCompleteAccountLanes(pending);
        // A settlement that finishes in this poll seeded primitive Lane changes, but
        // never took the deferred completion path that publishes these result views.
        owner.resultBuilder.materializeChangeAccumulators();
        if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) {
            owner.resultBuilder.materializeCommandOrderViews(pending);
        }
        if ((pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT)) {
            pending.controlAccountsCommitted(true);
            if (!owner.runtimeState().pollControlFundsValidation(false)) {
                pending.controlPending(true);
                retainControlResponse(pending, matchingResult);
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
        }
        return finishTerminalMatching(pending, matchingResult);
    }

    CoreResponse completeDispatchedMatcherSettlement(
            CommandSlot pending,
            MatchingResult matchingResult) {
        com.surprising.aeron.service.state.settlement.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
        owner.captureRealtimeTrades(pending);
        com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta settlementTreasuryDelta;
        try {
            settlementTreasuryDelta = owner.runtimeState().collectMatcherSettlement(
                    event, owner.runtimeState().coldCommandFunds(), owner.terminalRetention(),
                    owner.resultBuilder.changedUserIds(), owner.resultBuilder.changedOrderIds());
            owner.resultBuilder.markLaneCommitIdsSeeded();
            pending.completeLanes(event.requiredLaneMask());
            switch (pending.operation()) {
                case CANCEL -> { /* Lane 已按撮合结论完成解冻和撤单。 */ }
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = pending.sourceTrigger();
                    if (trigger == null) {
                        throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
                    }
                    publication.request();
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                default -> throw new IllegalStateException(
                        "operation cannot own an asynchronous matcher settlement: " + pending.operation());
            }
            if (pending.operation() == CommandSlot.Operation.PLACE
                    || pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND
                    || pending.operation() == CommandSlot.Operation.TRIGGER) {
                publication.request();
            }
            owner.runtimeState().applyCollectedTreasuryBeforeActivation(settlementTreasuryDelta);
            long revisionDelta = switch (pending.operation()) {
                case CANCEL -> matchingResult.accepted() ? 1 : 0;
                case REPLACE, AMEND -> matchingResult.accepted() ? 2 : 0;
                case TRIGGER -> matchingResult.accepted() ? 1 : 2;
                default -> 1;
            };
            owner.runtimeState().setMetadata(owner.productLine(),
                    Math.addExact(owner.runtimeState().revision(),
                            Math.addExact(revisionDelta, pending.settlementPlan().preCancellationCount())));
            publication.request();
            owner.resultBuilder.materializeChangeAccumulators();
            long committedLaneMask = event.requiredLaneMask();
            if (pending.completedLaneMask() != pending.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from immutable matcher fact", null);
            }
            requireCompleteAccountLanes(pending);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()
                    || pending.operation() == CommandSlot.Operation.CANCEL
                    || pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND)
                owner.resultBuilder.materializeCommandOrderViews(pending);
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        } catch (RuntimeException exception) {
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required",
                    exception);
        }
        return finishTerminalMatching(pending, matchingResult);
    }

    long expectedLaneMask(
            CommandSlot pending,
            MatchingResult result) {
        long mask = 0;
        long activeUserId = pending.command().header().userId();
        // Instrument settlement changes the selected position/order owners, not its operator.
        if (activeUserId > 0 && pending.operation() != CommandSlot.Operation.SETTLEMENT)
            mask |= owner.matchingAdapter().topology().accountLaneMask(activeUserId);
        var matcherEvents = result.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherEvent match = matcherEvents.get(index);
            if (match.eventType() == MatcherEventType.TRADE) {
                mask |= owner.matchingAdapter().topology().accountLaneMask(match.matchedOrderUid());
            }
        }
        var cancellations = result.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            CoreCancellationResult cancellation = cancellations.get(index);
            var order = owner.activeOrderIndex().activeOrderRoute(cancellation.orderId());
            if (order != null) mask |= owner.matchingAdapter().topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            for (var action : pending.decodedCommand().liquidationBatch().actions()) {
                mask |= owner.matchingAdapter().topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == CommandSlot.Operation.LIQUIDATION) {
            var command = pending.decodedCommand().liquidation();
            var liquidation = pending.liquidationCheck().liquidation(0);
            if (liquidation != null) mask |= owner.matchingAdapter().topology().accountLaneMask(liquidation.userId());
        }
        // Lifecycle users are selected by Ledger and registered through includeControlLanes
        // only after the actual Account work completes; no speculative business-index read.
        return mask;
    }

    public long validAccountLaneMask() {
        int count = owner.matchingAdapter().topology().accountLaneCount();
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    public void requireCompleteAccountLanes(CommandSlot context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }

    private void retainControlResponse(CommandSlot pending, MatchingResult matchingResult) {
        if (pending.resultData() == null && (owner.resultBuilder.commandRiskScanControl != null
                || owner.resultBuilder.commandLiquidationProgress != null
                || owner.resultBuilder.commandLiquidationBatchResult != null)) {
            pending.resultData(owner.resultBuilder.commandResultData(pending, matchingResult));
        }
    }

    public boolean controlPending(long sequence) {
        if (!owner.pendingMatching.claimed(sequence)) return false;
        CommandSlot slot = owner.pendingMatching.required(sequence);
        return slot.controlPending();
    }

    void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            MatchingResult matchingResult,
            CommandSlot pending) {
        // Every action contributes to the immutable result's Lane mask, including actions
        // skipped as obsolete or deferred by the cancellation budget. Complete those Lane
        // acknowledgements even when their balances and orders do not change.
        for (var action : batch.actions()) owner.resultBuilder.markUserChanged(action.userId());
        if (!matchingResult.accepted()) {
            int obsolete = 0;
            for (var action : batch.actions()) {
                var liquidation = owner.liquidationIndex().route(action.liquidationId());
                if (liquidation == null || !liquidation.executable()) {
                    obsolete++;
                }
            }
            owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), 0,
                    batch.actions().size() - obsolete, obsolete, 0, 0);
            return;
        }
        var work = new java.util.function.BooleanSupplier() {
            int index, applied, pendingCount, obsolete, processedOrders;
            int remaining = batch.maxCancelOrders();
            java.util.function.BooleanSupplier accountWork;
            List<com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor.ExecutionRequest>
                    executionRequests;
            RiskScanCoordinator riskWork;
            long beforeRiskRevision;
            boolean riskStarted;

            @Override public boolean getAsBoolean() {
                if (accountWork != null) {
                    if (!accountWork.getAsBoolean()) return false;
                    accountWork = null;
                    publication.request();
                }
                if (executionRequests == null) executionRequests = new ArrayList<>();
                while (index < batch.actions().size()) {
                    var action = batch.actions().get(index++);
                    var liquidation = pending.liquidationCheck().liquidation(index - 1);
                    if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                            || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.CANCELED) {
                        obsolete++;
                        continue;
                    }
                    if (remaining == 0) {
                        pendingCount++;
                        continue;
                    }
                    ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                            action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                            action.cursorOrderId(), Math.min(remaining, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    boolean executable;
                    try {
                        executable = pending.liquidationCheck().executable(index - 1);
                    } catch (CoreStateRejectedException rejection) {
                        if (!"STALE_MARK_PRICE".equals(rejection.code())) throw rejection;
                        // A queried liquidation action can expire before this batch reaches its commit fence.
                        // Skip that action without touching orders or balances; fresh risk work will replace it.
                        obsolete++;
                        continue;
                    }
                    var chunk = executable ? owner.matchingFlow.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            action.cursorOrderId(), remaining) : new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0);
                    for (var order : chunk.orders()) owner.resultBuilder.markOrderChanged(order.orderId());
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                    var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                    if (executable) {
                        processedOrders += orders.size();
                        remaining -= orders.size();
                    }
                    if (nextCursor == 0) applied++; else pendingCount++;
                    if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                        executionRequests.add(new com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor
                                .ExecutionRequest(single, orders, nextCursor));
                        continue;
                    }
                    if (nextCursor != 0) owner.liquidations().advanceLiquidationCancellationRuntime(single, orders, nextCursor);
                    else owner.liquidations().executeLiquidationRuntime(single, orders);
                }
                if (!executionRequests.isEmpty()) {
                    if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                        accountWork = com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor
                                .beginExecutionBatch(executionRequests, owner.runtimeState(), owner.identities());
                        executionRequests = new ArrayList<>();
                        return false;
                    }
                    executionRequests.clear();
                }
                int completedRiskWork = 0;
                if (!riskStarted) {
                    riskStarted = true;
                    var scan = owner.runtimeState().firstRiskIncompleteScan();
                    var continuation = batch.riskScanContinuation();
                    if (scan != null && continuation != null
                            && owner.identities().instrumentId(scan.symbolId()).equals(continuation.instrumentId())
                            && scan.priceSequence() == continuation.priceSequence()
                            && scan.lastUserId() == continuation.lastUserId()) {
                        beforeRiskRevision = owner.runtimeState().revision();
                        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                            riskWork = new RiskScanCoordinator(batch.maxRiskScanUsers(), owner.positionUserIndex(),
                                    owner.runtimeState(), owner.identities());
                        } else {
                            completedRiskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(batch.maxRiskScanUsers(),
                                    owner.positionUserIndex(), owner.runtimeState(), owner.identities());
                            if (completedRiskWork > 0) owner.runtimeState().acceptChangedUserIds(userId ->
                                    pending.includeControlLanes(owner.matchingAdapter().topology().accountLaneMask(userId),
                                            validAccountLaneMask()));
                            if (owner.runtimeState().revision() != beforeRiskRevision) publication.request();
                        }
                    }
                }
                if (riskWork != null) {
                    if (!riskWork.poll()) return false;
                    completedRiskWork = riskWork.completedWork();
                    if (owner.runtimeState().revision() != beforeRiskRevision) publication.request();
                }
                owner.resultBuilder.materializeChangeAccumulators();
                owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(
                        batch.actions().size(), applied, pendingCount, obsolete, processedOrders, completedRiskWork);
                return true;
            }
        };
        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) pending.deferControl(work);
        else if (!work.getAsBoolean()) throw new IllegalStateException("synchronous liquidation batch suspended");
    }

    public boolean matchingResultNeedsRecovery(CommandSlot pending,
                                                MatchingResult result) {
        if (result.outcome() == MatchingResult.Outcome.APPLIED
                || result.outcome()
                == MatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == MatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            List<Long> expected = result instanceof com.surprising.aeron.service.state.settlement.MatcherSettlementEvent direct
                    ? direct.completedPreMatchingCancellations() : pending.preMatchingCancellationOrderIds();
            var replacement = pending.orderBatch() != null
                    ? pending.orderBatch().replacementAdmission() : pending.matchingAdmission();
            long replacedOrderId = replacement != null
                    && (pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND)
                    ? replacement.originalOrderId() : 0;
            boolean containsTrade = false;
            var matcherEvents = result.matcherEvents();
            for (int index = 0; index < matcherEvents.size(); index++) {
                MatcherEvent event = matcherEvents.get(index);
                if (event.eventType() == MatcherEventType.TRADE) {
                    containsTrade = true;
                    break;
                }
            }
            boolean cancellationsValid = true;
            var cancellations = result.cancellations();
            for (int index = 0; index < cancellations.size(); index++) {
                CoreCancellationResult cancellation = cancellations.get(index);
                long orderId = cancellation.orderId();
                if (cancellation.accepted() && !expected.contains(orderId) && orderId != replacedOrderId) {
                    cancellationsValid = false;
                    break;
                }
            }
            boolean knownPrefix = (!expected.isEmpty() || replacedOrderId != 0)
                    && !containsTrade && cancellationsValid;
            if (!knownPrefix) return true;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT) return true;
        return result.outcome()
                == MatchingResult.Outcome.FATAL_DIVERGENCE;
    }

    void validateMatchingEvidence(
            CommandSlot pending,
            MatchingResult result) {
        int matcherShardId = result.nativeMatcherShardId();
        long appliedMatcherSequence = matcherSequence(matcherShardId);
        if (result.nativeCoreSequence() != pending.sequence()
                || !result.nativeMatches(pending.command().header().commandIdMsb(), pending.command().header().commandIdLsb())
                || result.nativeMatcherSequence() <= appliedMatcherSequence) {
            throw owner.failMatching(pending, "matcher result does not continue the applied sequence"
                    + " expectedSequenceAfter=" + appliedMatcherSequence
                    + " actualSequence=" + result.nativeMatcherSequence(), null);
        }
    }

    public void initializeMatcherProgress(MatcherSnapshot snapshot) {
        if (snapshot == null) return;
        for (com.surprising.aeron.service.matching.MatcherShardProgress progress
                : snapshot.matcherShardProgress()) {
            int index = matcherProgressIndex(progress.matcherShardId());
            appliedMatcherSequences[index] = progress.matcherSequence();
        }
    }

    void applyMatcherProgress(
            MatchingResult result) {
        int index = matcherProgressIndex(result.nativeMatcherShardId());
        appliedMatcherSequences[index] = result.nativeMatcherSequence();
    }

    void applyMatcherProgress(com.surprising.aeron.service.state.settlement.MatcherSettlementEvent event) {
        int index = matcherProgressIndex(event.directMatcherShard());
        appliedMatcherSequences[index] = event.directLastMatcherSequence();
    }

    long matcherSequence(int matcherShardId) {
        return appliedMatcherSequences[matcherProgressIndex(matcherShardId)];
    }

    int matcherProgressIndex(int matcherShardId) {
        int index = matcherShardId + 1;
        if (index < 0 || index >= appliedMatcherSequences.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return index;
    }

    /**
     * Advances every owner-side completion source once.  This is the only method that moves
     * matcher/Lane notifications toward the ordered commit head; it never commits business state.
     */
    public void advanceMatchingProgress(long clusterTimestamp, long clusterPosition, long throughSequence) {
        long first = owner.pendingMatching.firstSequence();
        if (first > throughSequence) return;
        CommandSlot pending = owner.pendingMatching.get(first);
        // 连续轮询复用命令已有的Map键，不为同一在途序号反复分配Long。
        OrderBatchPending head = pending == null ? null : pending.orderBatch();
        if (head != null && !head.activated()) {
            owner.batches().activateOrderBatch(head, pending, true);
        }
        if (owner.matchingProgress.hasDrainWork()) owner.matchingProgress.drainMatchingCompletions();
        dispatchReadyMatcherFinancialSettlements(throughSequence);
    }

    /** Fill the existing Ledger FIFO in log order without waiting for response retirement. */
    public void dispatchReadyMatcherFinancialSettlements(long throughSequence) {
        if (!owner.runtimeState().systemLedgerStarted()) return;
        CommandSlot pending;
        while ((pending = owner.pendingMatching.ledgerDispatchHead()) != null
                && pending.sequence() <= throughSequence) {
            if (pending.ledgerDispatchClosed()) {
                owner.pendingMatching.completeLedgerDispatch(pending.sequence());
                continue;
            }
            MatcherSettlementEvent event = financialDispatchEvent(pending);
            if (event == null) return;
            boolean sequential = pending.orderBatch() != null && !pending.orderBatch().pipelined();
            if (!owner.runtimeState().dispatchMatcherFinancialSettlement(event)) return;
            // Sequential batches can produce another item. Keep their money cursor until the
            // final item or explicit business close, while the response slot remains leased.
            if (sequential && !pending.orderBatch().matchingApplied()) return;
            owner.pendingMatching.completeLedgerDispatch(pending.sequence());
        }
    }

    /** A full mailbox needs a bounded Owner retry even when no new Matcher notification arrives. */
    public boolean hasFinancialDispatchWork() {
        if (!owner.runtimeState().systemLedgerStarted()) return false;
        CommandSlot pending = owner.pendingMatching.ledgerDispatchHead();
        if (pending == null) return false;
        if (pending.ledgerDispatchClosed()) return true;
        MatcherSettlementEvent event = financialDispatchEvent(pending);
        return event != null && (!event.hasLedgerSettlement()
                || pending.orderBatch() == null || pending.orderBatch().matchingApplied());
    }

    private MatcherSettlementEvent financialDispatchEvent(CommandSlot pending) {
        // Control and sequential batches can produce more money later in the same command.
        // Their existing ordered continuation must finish before later facts enter Ledger.
        if (pending == null || pending.controlPending() || pending.ledgerDispatchClosed()) return null;
        OrderBatchPending batch = pending.orderBatch();
        MatcherSettlementEvent event = batch == null ? pending.settlementEvent()
                : batch.itemSettlementEvent() != null ? batch.itemSettlementEvent() : batch.settlementEvent();
        return event != null && event.ready() && event.dispatched() ? event : null;
    }

    /** Release only the money ordering fence; all publication/response references stay leased. */
    public void closeFinancialDispatch(CommandSlot pending) {
        if (!owner.runtimeState().systemLedgerStarted()) return;
        pending.ledgerDispatchClosed(true);
        dispatchReadyMatcherFinancialSettlements(Long.MAX_VALUE);
    }

    private boolean financialHeadReady(CommandSlot pending) {
        if (!owner.runtimeState().systemLedgerStarted()) return true;
        if (pending.sequence() != owner.pendingMatching.firstSequence()) return false;
        if (pending.hasMatchingRejection() || owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) return true;
        OrderBatchPending batch = pending.orderBatch();
        if (batch == null) return owner.runtimeState().pollMatcherFinancialSettlement(pending.settlementEvent());
        if (batch.itemSettlementEvent() != null
                && !owner.runtimeState().pollMatcherFinancialSettlement(batch.itemSettlementEvent())) return false;
        return owner.runtimeState().pollMatcherFinancialSettlement(batch.settlementEvent());
    }

    public boolean matchingCommitReady(CommandSlot pending) {
        if (pending == null) return false;
        if (pending.operation() == CommandSlot.Operation.PLACE && pending.placeAdmission() != null) {
            if (!owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) return false;
        }
        if (owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) {
            if (!pending.isMatchingSubmitted()) return true;
            if (pending.settlementEvent() != null && pending.settlementEvent().direct())
                return pending.settlementEvent().complete();
            return pending.matchingResult() != null || pending.hasMatchingCompletion();
        }
        if (!financialHeadReady(pending)) return false;
        OrderBatchPending batch = pending.orderBatch();
        // A matcher result alone does not make a batch ready while its Lane work is outstanding.
        if (batch != null && (!batch.activated() || !batch.laneWorkComplete())) return false;
        if (batch != null && batch.itemAdmission() != null) return true;
        if (batch != null && (batch.itemSettlementEvent() != null || batch.laneCommitEvent() != null)) return true;
        if (batch != null && batch.settlementEvent() != null && batch.settlementEvent().direct())
            return batch.settlementEvent().complete();
        if (batch != null && batch.placeBatchAdmissionEvent() != null
                && (!batch.placeBatchAdmissionEvent().complete() || !pending.isMatchingSubmitted())) return false;
        // Direct Matcher->Lane events have no Owner matching-result slot.  Their Lane completion
        // bit is the authoritative ordered-commit readiness signal.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct())
            return pending.settlementEvent().complete();
        if (pending.hasLaneContinuation()) return pending.laneContinuationComplete();
        return pending.hasMatchingCompletion() || pending.matchingResult() != null;
    }

    public void drainMatcherSettlementCompletions() {
        boolean changed = owner.runtimeState().takeDirectMatcherSettlementReadyLaneMask() != 0;
        long readyLaneMask = owner.runtimeState().takeMatcherSettlementReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = owner.runtimeState().pollMatcherSettlementReady(laneId)) != 0) {
                // The event owns the completion bits. Owner only retires the notification cursor;
                // ordered commit reads the event at the head and performs the business collect.
                changed = true;
            }
        }
        // A direct event has no queue cursor to retire.  One revision bump is enough to make
        // the ordered head retry; per-Lane notifications would only duplicate this work.
        if (changed) owner.pendingMatching.progressChanged();
    }

    CommandSlot pollReadyPending() {
        CommandSlot pending = owner.pendingMatching.head();
        return matchingCommitReady(pending) ? pending : null;
    }

    /**
     * Performs one non-blocking ordered-head probe.  Matcher and Lane workers publish ready
     * notifications independently; the Owner must return to the cluster agent when the head is
     * not ready instead of spinning or parking on either worker.
     */
    CommandSlot pollReadyHead(
            long clusterTimestamp, long clusterPosition, long throughSequence) {
        if (owner.pendingMatching.isEmpty() || owner.pendingMatching.firstSequence() > throughSequence) {
            return null;
        }
        advanceMatchingProgress(clusterTimestamp, clusterPosition, throughSequence);
        return pollReadyPending();
    }

    /**
     * Reads the one authoritative matching result for a pending command.  Direct settlements keep
     * the result on the settlement event; ordinary commands take it from the sequence context.
     */
    private MatchingResult matchingResult(CommandSlot pending) {
        MatchingResult matching =
                pending.orderBatch() != null && (pending.orderBatch().itemSettlementEvent() != null
                        || pending.orderBatch().itemAdmission() != null && pending.orderBatch().activated())
                ? pending.orderBatch().lastMatchingResult() : pending.matchingResult();
        if (matching == null && pending.settlementEvent() != null && pending.settlementEvent().direct()) {
            matching = pending.settlementEvent().directResult();
        }
        if (matching == null && pending.orderBatch() != null && pending.orderBatch().settlementEvent() != null
                && pending.orderBatch().settlementEvent().direct()) {
            matching = pending.orderBatch().settlementEvent().directResult();
        }
        if (matching == null && pending.orderBatch() != null && pending.orderBatch().itemSettlementEvent() != null
                && pending.orderBatch().itemSettlementEvent().direct()) {
            matching = pending.orderBatch().itemSettlementEvent().directResult();
        }
        if (matching == null && (pending.settlementEvent() == null || pending.settlementEvent().direct())) {
            matching = pending.takeMatchingCompletion();
        }
        return matching;
    }

    /**
     * Completes a command after its Matcher fact is available.  Lane completion is represented by
     * the event's ready bits; an incomplete event is returned to the next Owner poll rather than
     * waiting on a Lane worker here.
     */
    private CoreResponse tryMatchingCommit(
            CommandSlot pending,
            MatchingResult matching,
            long clusterTimestamp,
            long clusterPosition) {
        return completeMatching(pending, matching, clusterTimestamp, clusterPosition);
    }

    public int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst, Long.MAX_VALUE, handler);
    }

    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst,
                throughSequence, throughSequence, true, handler);
    }

    /**
     * 派发上限只覆盖已准入的独立命令；最终提交上限与每命令的日志时间不变。
     * Owner 连续退休已 ready 队首时可跳过公共推进，但发布作用域仍逐命令保留。
     */
    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, long dispatchThroughSequence,
                            boolean advanceProgress, TradingCoreRuntime.MatchingCommitHandler handler) {
        owner.assertOwner();
        owner.assertHealthy();
        if (maxCompletions <= 0 || handler == null) {
            throw new IllegalArgumentException("matching commit batch requires a positive limit and handler");
        }
        if (advanceProgress) advanceMatchingProgress(clusterTimestamp, clusterPosition, dispatchThroughSequence);
        int completed = 0;
        int attempts = 0;
        while (attempts < maxCompletions) {
            if (owner.pendingMatching.firstSequence() > throughSequence) break;
            CommandSlot pending = pollReadyPending();
            if (pending == null && attempts == 0 && awaitFirst) {
                pending = pollReadyHead(clusterTimestamp, clusterPosition, throughSequence);
            }
            if (pending == null) break;
            long sequence = pending.sequence();
            CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
            attempts++;
            owner.pendingMatching.progressChanged();
            // A Matcher or Lane notification may arrive later.  Leave the pending head in
            // place and let the next cluster-agent poll retry it; never block the Owner.
            if (response == null) {
                break;
            }
            handler.onCommitted(sequence, response);
            completed++;
        }
        return completed;
    }

    /**
     * Commits one ordered Matcher head and returns its response directly to the command window
     * owner. Sequence-zero commands never enter this method because they already completed during
     * admission and have no pending Matcher slot.
     */
    public CoreResponse commitReadyMatchingHead(long clusterTimestamp, long clusterPosition,
                                         long throughSequence, long dispatchThroughSequence,
                                         boolean advanceProgress) {
        owner.assertOwner();
        owner.assertHealthy();
        if (throughSequence <= 0) throw new IllegalArgumentException("matching head sequence must be positive");
        if (advanceProgress) {
            advanceMatchingProgress(clusterTimestamp, clusterPosition, dispatchThroughSequence);
        }
        if (owner.pendingMatching.firstSequence() > throughSequence) return null;
        CommandSlot pending = pollReadyPending();
        if (pending == null || pending.sequence() > throughSequence) return null;
        CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
        owner.pendingMatching.progressChanged();
        return response;
    }

    /** Applies one ready slot while preserving the existing response-routing boundary. */
    private CoreResponse commitReadyPending(CommandSlot pending, long clusterTimestamp, long clusterPosition) {
        long sequence = pending.sequence();
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (owner.matchingFlow.hasPendingMatchingRejection(sequence)) {
            if (pending.isMatchingSubmitted()
                    && pending.settlementEvent() != null && pending.settlementEvent().direct()) {
                if (!pending.settlementEvent().complete()) return null;
            } else if (pending.isMatchingSubmitted()) {
                if (pending.matchingResult() == null && pending.takeMatchingCompletion() == null) return null;
            }
            return completeRejectedMatching(sequence);
        }
        MatchingResult matching = matchingResult(pending);
        return matching == null ? null
                : tryMatchingCommit(pending, matching, clusterTimestamp, clusterPosition);
    }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public void dispatchedSettlementHighWaterMark(int value) { this.dispatchedSettlementHighWaterMark = value; }
    public int dispatchedSettlementInFlight() { return dispatchedSettlementInFlight; }
    public TradingCoreRuntime owner() { return owner; }

    public void dispatchedSettlementInFlight(int value) { dispatchedSettlementInFlight = value; }
    public void initializeMatcherProgress(int count) { appliedMatcherSequences = new long[count]; }
}
