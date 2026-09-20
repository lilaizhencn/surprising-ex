package com.surprising.aeron.service.orchestration;


import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.state.RiskScanCoordinator;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import java.util.ArrayList;
import java.util.List;

/** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
final class OrderedCommitCoordinator {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    /** 上次派发检查的输入水位；只有外部完成、本地推进或依赖顺序变化才重扫。 */
    private long checkedDispatchRevision = Long.MIN_VALUE;
    private long checkedDispatchThrough = Long.MIN_VALUE;

    OrderedCommitCoordinator(TradingCoreRuntime owner) {
        this.owner = owner;
        this.publication = new CommitPublication(owner);
    }

    /** 各撮合分片已核验的序号；提交时逐分片验证连续性。 */
    long[] appliedMatcherSequences;

    /** 已预派发且尚未收集完成的结算命令数量。 */
    int dispatchedSettlementInFlight;

    /** 结算在途数量峰值，仅用于运行观测。 */
    int dispatchedSettlementHighWaterMark;

    /** 只拥有撮合完成顺序；提交发布批次由独立的状态 owner 管理。 */
    final CommitPublication publication;

    CoreResponse completeRejectedMatching(long sequence) {
        owner.assertOwner();
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null || !owner.hasPendingMatchingRejection(sequence)) return null;
        CommandSlot laneContext = owner.laneCommandContexts.required(sequence);
        var direct = pending.settlementEvent();
        if (pending.isMatchingSubmitted()) {
            // Lane rejection can race with the Matcher admission fence. Keep the ordered
            // command alive until the in-flight Matcher token has published; recycling the slot
            // earlier lets the worker publish into a newly claimed sequence.
            if (direct != null && direct.direct()) {
                if (!direct.complete()) return null;
            } else if (laneContext.matchingResult() == null && laneContext.matchingCompletion() == null) {
                return null;
            }
        }
        CoreResultCode resultCode = owner.laneCommandContexts.required(sequence).matchingRejection();
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.commitMatchingSequence(sequence);
        long stateHash = owner.cachedBusinessStateHash;
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence, stateHash,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        if (direct != null && direct.direct()) {
            // A rejected admission still consumed one Matcher evidence sequence.  Advance the
            // Advance the Owner's shard sequence before recycling the direct event so restored
            // runtimes see the same Matcher cursor as the live runtime.
            if (direct.directResultAvailable()) {
                MatchingResult matcherResult = direct.firstDirectResult();
                // The normal direct completion path validates and applies the same result before
                // it reaches this rejection helper. Avoid applying that evidence twice when the
                // Lane rejection is observed after the Matcher event has completed.
                int shard = matcherResult.nativeMatcherShardId();
                if (matcherSequence(shard) != matcherResult.nativeMatcherSequence()) {
                    validateMatchingEvidence(pending, matcherResult);
                    applyMatcherProgress(matcherResult);
                }
            }
            owner.runtimeState.discardMatcherSettlement(pending.takeSettlementEvent());
        } else if (pending.isMatchingSubmitted()) {
            MatchingResult matcherResult = laneContext.matchingResult();
            if (matcherResult == null) matcherResult = laneContext.matchingCompletion();
            if (matcherResult != null) {
                validateMatchingEvidence(pending, matcherResult);
                applyMatcherProgress(matcherResult);
            }
        }
        owner.removePendingMatching(sequence);
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, stateHash, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return owner.finishFactContext(response);
    }

    public CoreResponse completeMatching(long sequence,
                                  MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        if (!com.surprising.aeron.service.state.MatcherSettlementEvent.LATENCY_DIAGNOSTICS)
            return completeMatchingCommand(sequence, matchingResult, clusterTimestamp, clusterPosition);
        owner.assertOwner();
        var timing = CoreMatchingPhaseMetrics.beginSettlement(owner.pendingMatching.get(sequence), sequence);
        CoreResponse response = completeMatchingCommand(sequence, matchingResult, clusterTimestamp, clusterPosition);
        if (timing != null && response != null) { timing.end(); timing.commit(); }
        return response;
    }

    private CoreResponse completeMatchingCommand(long sequence, MatchingResult matchingResult,
                                                long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null || matchingResult == null) return null;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        clusterTimestamp = pending.commitFenceTimestamp();
        clusterPosition = pending.commitFenceClusterPosition();
        owner.currentClusterPosition = clusterPosition;
        CommandSlot laneContext = owner.laneCommandContexts.required(sequence);
        // Matcher releases the route before publishing Lane readiness. A command slot cannot
        // be recycled while its producer still owns it; transport retirement is independent.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                && laneContext.submittedMatcherShard() != -1) return null;
        if (pending.controlPending) {
            owner.restoreMatchingCommitContext(pending);
            try {
                // The completed risk continuation also restores the response summary after suspension.
                if (pending.controlWork != null && !pending.controlWork.getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                // Account work is consumed once; commit publication may require later polls.
                pending.controlWork = null;
                if (pending.commitEvent == null) {
                    owner.runtimeState.completePendingReservations(sequence);
                    owner.resultBuilder.materializeChangeAccumulators();
                    // A suspended control may accumulate users across several risk pages/Lanes.
                    // Register the complete commit input, not just the last page's runtime delta.
                    for (long userId : owner.resultBuilder.commandChangedUserIds)
                        laneContext.includeControlLanes(owner.matchingAdapter.topology().accountLaneMask(userId),
                                validAccountLaneMask());
                    pending.commitEvent = owner.runtimeState.dispatchLaneMutation(sequence,
                            owner.resultBuilder.commandChangedUserIds,
                            publication.dirty() ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                            clusterTimestamp, clusterPosition);
                }
                if (pending.commitEvent != null) {
                    if (!owner.runtimeState.laneCommitComplete(pending.commitEvent)) {
                        retainControlResponse(pending, matchingResult);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    long laneMask = pending.commitEvent.requiredLaneMask();
                    owner.runtimeState.releaseLaneCommit(pending.commitEvent);
                    pending.commitEvent = null;
                    laneContext.completeLanes(laneMask);
                }
                pending.controlWork = null;
                pending.controlPending = false;
                return finishAppliedMatching(pending, matchingResult, laneContext, null, pending.controlStartedNanos, true);
            } catch (RuntimeException failure) {
                throw owner.failMatching(pending, "control Lane continuation failed", failure);
            }
        }
        OrderBatchPending waitingBatch = owner.batches.batch(sequence);
        if (waitingBatch != null && waitingBatch.settlementEvent != null && waitingBatch.settlementEvent.direct()
                && !waitingBatch.matchingApplied()) {
            if (!waitingBatch.settlementEvent.complete()) return null;
            validateMatchingEvidence(pending, waitingBatch.settlementEvent.firstDirectResult());
            applyMatcherProgress(waitingBatch.settlementEvent);
            waitingBatch.nextIndex = waitingBatch.items.size();
            waitingBatch.matchingApplied(true);
        }
        if (waitingBatch != null && (waitingBatch.itemSettlementEvent != null
                || waitingBatch.laneCommitEvent != null)) {
            try { owner.runtimeState.assertAccountLanesHealthy(); }
            catch (RuntimeException failure) {
                throw owner.batches.failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            }
        }
        // 尚未完成的 Lane 工作仍拥有挂起的上下文，不能提前占用 owner 发布批次。
        if (waitingBatch != null && !waitingBatch.laneWorkComplete()) return null;
        if (waitingBatch != null && laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
        if (waitingBatch != null && pending.clusterIndependent
                && waitingBatch.kind == OrderBatchKind.CANCEL && !waitingBatch.commitStarted()) {
            owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        // A sequential batch item now owns a preconstructed direct event.  The event can be
        // Lane-complete before the Owner has copied the Matcher token into the batch context;
        // apply the authoritative result through the normal batch path exactly once, after the
        // suspended commit context has been restored.
        if (waitingBatch != null && waitingBatch.itemSettlementEvent != null
                && waitingBatch.itemSettlementEvent.direct()
                && waitingBatch.lastMatchingResult == null) {
            if (!waitingBatch.itemSettlementEvent.complete()) return null;
            MatchingResult directResult = waitingBatch.itemSettlementEvent.firstDirectResult();
            validateMatchingEvidence(pending, directResult);
            applyMatcherProgress(directResult);
            return owner.batches.completeOrderBatchMatching(
                    sequence, directResult,
                    clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemSettlementEvent != null) {
            if (waitingBatch.itemSettlementEvent.direct()) {
                MatchingResult directResult = waitingBatch.itemSettlementEvent.firstDirectResult();
                validateMatchingEvidence(pending, directResult);
                applyMatcherProgress(directResult);
                waitingBatch.lastMatchingResult = directResult;
                if (waitingBatch.kind == OrderBatchKind.CANCEL) {
                    return owner.batches.completeOrderBatchMatching(
                            sequence, directResult, clusterTimestamp, clusterPosition);
                }
            }
            return owner.batches.completeOrderBatchItemSettlement(
                    waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.hasPendingLaneWork()) {
            if (waitingBatch.pipelined && !waitingBatch.commitStarted()) {
                owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
            }
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return owner.batches.finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.matchingApplied()) {
            if (!waitingBatch.commitStarted()) owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
            return owner.batches.finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (pending.cancelEvent() != null) {
            if (!pending.cancelEvent().complete()) return null;
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedCancel(pending, matchingResult, laneContext);
        }
        if (pending.settlementEvent() != null) {
            if (!pending.settlementEvent().complete()) return null;
            if (pending.settlementEvent().direct() && !laneContext.hasCommitContext()) {
                validateMatchingEvidence(pending, matchingResult);
                applyMatcherProgress(matchingResult);
                laneContext.result(matchingResult, pending.settlementEvent().requiredLaneMask(), validAccountLaneMask());
                owner.resultBuilder.clearOrderViews();
                owner.resultBuilder.commandChangedUserIds = List.of();
                owner.resultBuilder.commandChangedOrderIds = List.of();
                owner.resultBuilder.commandTradeCount = 0;
                owner.resultBuilder.commandLiquidationProgress = null;
                owner.resultBuilder.commandLiquidationBatchResult = null;
                owner.resultBuilder.commandRiskScanControl = null;
                owner.resultBuilder.resetChangeAccumulators();
                owner.setCommandFundsDelta(pending.fundsDelta());
                owner.activateFactContext(pending.command(), pending.fingerprint());
                beginCommitPublicationBatch();
                return completeDispatchedMatcherSettlement(pending, matchingResult, laneContext);
            }
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedMatcherSettlement(pending, matchingResult, laneContext);
        }
        if (waitingBatch != null && waitingBatch.pipelined && !waitingBatch.commitStarted()) {
            owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            Long submitNanos = owner.matchingSubmitNanos.remove(sequence);
            if (submitNanos != null) owner.matchingPhaseMetrics.recordExchange(System.nanoTime() - submitNanos);
        }
        long applyStartNanos = System.nanoTime();
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = owner.batches.batch(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (failedBatch != null) throw owner.batches.failOrderBatch(failedBatch, pending, detail, failure);
            owner.matchingAdapter.poisonFromOwner("unreconciled matcher outcome sequence=" + sequence
                    + " result=" + matchingResult.resultCode());
            throw owner.failMatching(pending, detail, failure);
        }
        validateMatchingEvidence(pending, matchingResult);
        applyMatcherProgress(matchingResult);
        if (owner.batches.batch(sequence) != null) {
            return owner.batches.completeOrderBatchMatching(sequence, matchingResult, clusterTimestamp, clusterPosition);
        }
        com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan =
                initialMatcherSettlementPlan(pending, matchingResult);
        if (settlementPlan != null) {
            laneContext.result(matchingResult, settlementPlan.requiredLaneMask(),
                    validAccountLaneMask());
        } else {
            laneContext.result(matchingResult, expectedLaneMask(pending, matchingResult), validAccountLaneMask());
        }
        owner.resultBuilder.clearOrderViews();
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        owner.resultBuilder.commandTradeCount = 0;
        owner.resultBuilder.commandLiquidationProgress = null;
        owner.resultBuilder.commandLiquidationBatchResult = null;
        owner.resultBuilder.commandRiskScanControl = null;
        owner.resultBuilder.resetChangeAccumulators();
        owner.setCommandFundsDelta(pending.fundsDelta());
        owner.activateFactContext(pending.command(), pending.fingerprint());
        beginCommitPublicationBatch();
        com.surprising.aeron.service.state.RuntimeTreasuryDelta settlementTreasuryDelta = null;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    boolean dispatchOnly = pending.isDispatchOnly();
                    settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                            pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                    pending.takeDispatchOnly();
                    if (settlementTreasuryDelta == null || dispatchOnly) {
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    owner.resultBuilder.markLaneCommitIdsSeeded();
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(settlementPlan);
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    owner.resultBuilder.setSingleChangedUser(pending.command().header().userId());
                    owner.resultBuilder.setSingleChangedOrder(command.orderId());
                    if (matchingResult.accepted()) {
                        var cancelEvent = owner.runtimeState.dispatchCancel(
                                sequence, pending.command().header().userId(), command.orderId(),
                                clusterTimestamp, clusterPosition, owner.identities);
                        pending.cancel(cancelEvent, applyStartNanos);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                }
                case REPLACE, AMEND -> throw new IllegalStateException("replacement requires a direct Lane event");
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = owner.runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                            pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                    if (settlementTreasuryDelta == null) return null;
                    requestCommitPublication();
                    owner.resultBuilder.markLaneCommitIdsSeeded();
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(settlementPlan);
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = owner.runtimeState.liquidation(command.liquidationId());
                    TradingCoreRuntime.LifecycleOrderChunk chunk = liquidation == null ? new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0)
                            : owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(),
                            command.maxOrders());
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(chunk.orders());
                    if (liquidation == null) owner.resultBuilder.commandChangedUserIds = List.of();
                    else owner.resultBuilder.setSingleChangedUser(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        boolean executable = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService
                                .isExecutable(owner.runtimeState, owner.identities, command);
                        long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                        var orders = executable ? chunk.orders() : List.<CoreOrderState>of();
                        var progress = new CoreLiquidationProgressView(nextCursor == 0, nextCursor,
                                chunk.orders().size());
                        if (owner.runtimeState.asynchronousCommands()) {
                            var work = com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor
                                    .beginExecution(pending.liquidationWork(), command, orders, nextCursor,
                                            owner.runtimeState, owner.identities);
                            pending.liquidationWork(work);
                            pending.deferLiquidationExecutionControl(this, work, progress);
                        } else {
                            if (nextCursor != 0) owner.liquidations.advanceLiquidationCancellationRuntime(
                                    command, orders, nextCursor);
                            else owner.liquidations.executeLiquidationRuntime(command, orders);
                            owner.resultBuilder.commandLiquidationProgress = progress;
                        }
                    }
                }
                case LIQUIDATION_BATCH -> {
                    var command = pending.decodedCommand().liquidationBatch();
                    applyLiquidationBatch(command, matchingResult, laneContext);
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    if (matchingResult.accepted()) {
                        owner.instrumentSettlement.applySettlementChangedIds(command);
                        if (owner.runtimeState.asynchronousCommands()) {
                            var work = owner.instrumentSettlement.beginAsyncSettlement(
                                    pending.settlementWork(), command, pending.command().header().commandId());
                            pending.settlementWork(work);
                            pending.deferSettlementControl(this, work);
                        } else {
                            owner.instrumentSettlement.settleInstrumentRuntime(command,
                                    pending.command().header().commandId());
                        }
                    }
                }
            }
            if (pending.controlWork != null) {
                pending.controlPending = true;
                pending.controlStartedNanos = applyStartNanos;
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState.completePendingReservations(pending.sequence());
            if (pending.operation() == CommandSlot.Operation.PLACE
                    || pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND
                    || pending.operation() == CommandSlot.Operation.TRIGGER) {
                requestCommitPublication();
            }
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        }
        return finishAppliedMatching(pending, matchingResult, laneContext, settlementTreasuryDelta, applyStartNanos);
    }

    /** 已校验撮合证据，账户阶段完成后只进入一次终态提交。 */
    private CoreResponse finishAppliedMatching(CommandSlot pending, MatchingResult matchingResult,
            CommandSlot laneContext, RuntimeTreasuryDelta settlementTreasuryDelta,
            long applyStartNanos) {
        return finishAppliedMatching(pending, matchingResult, laneContext, settlementTreasuryDelta,
                applyStartNanos, false);
    }

    /**
     * Common deterministic tail for every terminal matching path.  Collection and Lane event
     * release remain operation-specific, but sequence/hash/ledger/response work must have one
     * implementation so the Owner does not repeat it for place, cancel and direct settlement.
     */
    private CoreResponse storeTerminalResponse(CommandSlot pending, MatchingResult matchingResult,
            ResponseStatus status, CoreResultCode resultCode) {
        long beforePublicationSequence = pending.beforePublicationSequence();
        long applied = pending.sequence();
        long businessStateHash = owner.publicationSequence == beforePublicationSequence
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        owner.commitMatchingSequence(applied);
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = businessStateHash;
        boolean builderEncoded = pending.resultData == null;
        // Control continuations may have prepared the response into resultData before the
        // terminal commit.  That byte slice still belongs to the reusable result builder until
        // this response is handed to the command window, even though it is not encoded here.
        boolean builderResponseOwned = builderEncoded || pending.resultData != null;
        byte[] responseData = builderEncoded
                ? owner.resultBuilder.commandResultData(pending, matchingResult) : pending.resultData;
        int responseOffset = builderEncoded ? owner.resultBuilder.responseDataOffset() : 0;
        int responseLength = builderEncoded ? owner.resultBuilder.responseDataLength() : responseData.length;
        boolean laneResponse = responseData != null && responseData == pending.lanePreparedResponse();
        pending.resultData = null;
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, owner.resultBuilder.commandTradeCount);
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                status, resultCode, applied, stateHash, responseData,
                responseOffset, responseLength);
        CoreResponse response = CoreResponse.owned(status, status, resultCode, applied,
                stateHash, responseData, responseOffset, responseLength);
        if (builderResponseOwned) owner.resultBuilder.transferResponseOwnership();
        if (laneResponse) pending.transferLaneResponseOwnership();
        return response;
    }

    private CoreResponse finishAppliedMatching(CommandSlot pending, MatchingResult matchingResult,
            CommandSlot laneContext, RuntimeTreasuryDelta settlementTreasuryDelta,
            long applyStartNanos, boolean lanesCommitted) {
        long sequence = pending.sequence();
        long clusterTimestamp = pending.commitFenceTimestamp();
        long clusterPosition = pending.commitFenceClusterPosition();
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        RuntimeTreasuryDelta settledTreasuryDelta = settlementTreasuryDelta;
            if (settledTreasuryDelta != null) {
                settledTreasuryDelta.apply(owner.runtimeState.treasury());
                owner.runtimeState.setMetadata(owner.productLine,
                        Math.addExact(owner.runtimeState.revision(),
                                pending.operation() == CommandSlot.Operation.TRIGGER && !matchingResult.accepted() ? 2 : 1));
                requestCommitPublication();
            }
        if (!lanesCommitted && pending.settlementEvent() == null) {
            owner.resultBuilder.materializeChangeAccumulators();
            pending.commitEvent = owner.runtimeState.dispatchLaneMutation(sequence,
                    owner.resultBuilder.commandChangedUserIds,
                    publication.dirty() ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                    clusterTimestamp, clusterPosition);
            if (pending.commitEvent != null) {
                if (!owner.runtimeState.laneCommitComplete(pending.commitEvent)) {
                    pending.controlPending = true;
                    pending.controlStartedNanos = applyStartNanos;
                    retainControlResponse(pending, matchingResult);
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                long laneMask = pending.commitEvent.requiredLaneMask();
                owner.runtimeState.releaseLaneCommit(pending.commitEvent);
                pending.commitEvent = null;
                laneContext.completeLanes(laneMask);
            }
        }
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane mask differs from immutable matcher result", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) {
                owner.resultBuilder.materializeCommandOrderViews(pending);
            }
        var timingHeader = pending.command().header();
        long publicationStart = CoreMatchingPhaseMetrics.sampleStart(timingHeader);
        completeCommitPublicationBatch();
        CoreMatchingPhaseMetrics.recordBoundary("ownerFactPublication", timingHeader, publicationStart);
        long terminalStart = CoreMatchingPhaseMetrics.sampleStart(timingHeader);
        owner.validateFundsConservation(pending.command());
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingPhaseMetrics.recordApply(System.nanoTime() - applyStartNanos);
            owner.completedMatchingCount++;
            if (owner.completedMatchingCount % TradingCoreRuntime.MATCHING_PHASE_LOG_INTERVAL == 0) {
                TradingCoreRuntime.LOG.log(System.Logger.Level.DEBUG, "matching phases count=" + owner.completedMatchingCount + " "
                        + owner.matchingPhaseMetrics.reportAndReset());
            }
        }
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        owner.runtimeState.releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.removePendingMatching(sequence);
        if (owner.pendingMatching.hasDeferred() || owner.batches.hasPendingBatches()) {
            owner.submitDeferredMatchingAfterBatch();
        }
        CoreMatchingPhaseMetrics.recordBoundary("ownerTerminalBookkeeping", timingHeader, terminalStart);
        return owner.finishFactContext(response);
    }

    CoreResponse completeDispatchedMatcherSettlement(
            CommandSlot pending,
            MatchingResult matchingResult,
            CommandSlot laneContext) {
        com.surprising.aeron.service.state.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
        // Admission is consumed once, at the ordered terminal boundary. The Matcher has
        // already consumed the primitive Lane receipt; no Owner-side admission poll is needed.
        if (pending.operation() == CommandSlot.Operation.PLACE && pending.placeAdmission() != null) {
            if (!owner.collectPlaceAdmissionIfReady(pending)) return null;
            if (owner.hasPendingMatchingRejection(pending.sequence()))
                return completeRejectedMatching(pending.sequence());
        }
        owner.captureRealtimeTrades(pending);
        com.surprising.aeron.service.state.RuntimeTreasuryDelta settlementTreasuryDelta;
        try {
            settlementTreasuryDelta = owner.runtimeState.collectMatcherSettlement(
                    event, owner.commandFundsAccumulator, owner.terminalRetention,
                    owner.resultBuilder.changedUserIds, owner.resultBuilder.changedOrderIds);
            owner.resultBuilder.markLaneCommitIdsSeeded();
            laneContext.completeLanes(event.requiredLaneMask());
            switch (pending.operation()) {
                case CANCEL -> { /* Lane 已按撮合结论完成解冻和撤单。 */ }
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(pending.settlementPlan());
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(pending.settlementPlan());
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = owner.runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) {
                        throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
                    }
                    requestCommitPublication();
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(pending.settlementPlan());
                }
                default -> throw new IllegalStateException(
                        "operation cannot own an asynchronous matcher settlement: " + pending.operation());
            }
            if (pending.operation() == CommandSlot.Operation.PLACE
                    || pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND
                    || pending.operation() == CommandSlot.Operation.TRIGGER) {
                requestCommitPublication();
            }
            settlementTreasuryDelta.apply(owner.runtimeState.treasury());
            long revisionDelta = switch (pending.operation()) {
                case CANCEL -> matchingResult.accepted() ? 1 : 0;
                case REPLACE, AMEND -> matchingResult.accepted() ? 2 : 0;
                case TRIGGER -> matchingResult.accepted() ? 1 : 2;
                default -> 1;
            };
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.addExact(owner.runtimeState.revision(),
                            Math.addExact(revisionDelta, pending.settlementPlan().preCancellationCount())));
            requestCommitPublication();
            owner.resultBuilder.materializeChangeAccumulators();
            long committedLaneMask = event.requiredLaneMask();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from immutable matcher fact", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()
                    || pending.operation() == CommandSlot.Operation.CANCEL
                    || pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND)
                owner.resultBuilder.materializeCommandOrderViews(pending);
            completeCommitPublicationBatch();
            owner.validateFundsConservation(pending.command());
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        } catch (RuntimeException exception) {
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required",
                    exception);
        }
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingPhaseMetrics.recordApply(System.nanoTime() - pending.settlementApplyStartNanos());
            owner.completedMatchingCount++;
            if (owner.completedMatchingCount % TradingCoreRuntime.MATCHING_PHASE_LOG_INTERVAL == 0) {
                TradingCoreRuntime.LOG.log(System.Logger.Level.DEBUG, "matching phases count=" + owner.completedMatchingCount + " "
                        + owner.matchingPhaseMetrics.reportAndReset());
            }
        }
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        if (pending.takePipelinedSettlementCounted()) {
            dispatchedSettlementInFlight--;
            if (dispatchedSettlementInFlight < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        owner.runtimeState.releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.removePendingMatching(pending.sequence());
        if (owner.pendingMatching.hasDeferred() || owner.batches.hasPendingBatches()) owner.submitDeferredMatchingAfterBatch();
        return owner.finishFactContext(response);
    }

    CoreResponse completeDispatchedCancel(
            CommandSlot pending,
            MatchingResult matchingResult,
            CommandSlot laneContext) {
        com.surprising.aeron.service.state.LaneCancelEvent event = pending.cancelEvent();
        if (event == null || !event.complete()) return null;
        try {
            owner.runtimeState.collectCancel(event, owner.commandFundsAccumulator, owner.terminalRetention,
                    owner.resultBuilder.changedUserIds, owner.resultBuilder.changedOrderIds);
            owner.resultBuilder.markLaneCommitIdsSeeded();
            laneContext.completeLanes(event.requiredLaneMask());
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.addExact(owner.runtimeState.revision(), event.orderCount()));
            requestCommitPublication();
            owner.resultBuilder.materializeChangeAccumulators();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from cancel fact", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) owner.resultBuilder.materializeCommandOrderViews(pending);
            completeCommitPublicationBatch();
            owner.validateFundsConservation(pending.command());
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted cancel result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher cancel state diverged", exception);
        } catch (RuntimeException exception) {
            throw owner.failMatching(pending, "account lane cancel failed; snapshot/log recovery is required",
                    exception);
        }
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingPhaseMetrics.recordApply(System.nanoTime() - pending.settlementApplyStartNanos());
            owner.completedMatchingCount++;
        }
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        owner.runtimeState.releaseCancel(pending.takeCancelEvent());
        owner.removePendingMatching(pending.sequence());
        if (owner.pendingMatching.hasDeferred() || owner.batches.hasPendingBatches()) owner.submitDeferredMatchingAfterBatch();
        return owner.finishFactContext(response);
    }

    long expectedLaneMask(
            CommandSlot pending,
            MatchingResult result) {
        long mask = 0;
        long activeUserId = pending.command().header().userId();
        // Instrument settlement changes the selected position/order owners, not its operator.
        if (activeUserId > 0 && pending.operation() != CommandSlot.Operation.SETTLEMENT)
            mask |= owner.matchingAdapter.topology().accountLaneMask(activeUserId);
        var matcherEvents = result.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherEvent match = matcherEvents.get(index);
            if (match.eventType() == MatcherEventType.TRADE) {
                mask |= owner.matchingAdapter.topology().accountLaneMask(match.matchedOrderUid());
            }
        }
        var cancellations = result.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            CoreCancellationResult cancellation = cancellations.get(index);
            OrderRuntime order = owner.runtimeOrder(cancellation.orderId());
            if (order != null) mask |= owner.matchingAdapter.topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            for (var action : TradingCommandCodec.decodeExecuteLiquidationBatch(
                    pending.command().payloadUnsafe()).actions()) {
                mask |= owner.matchingAdapter.topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == CommandSlot.Operation.LIQUIDATION) {
            var command = pending.decodedCommand().liquidation();
            var liquidation = owner.runtimeState.liquidation(command.liquidationId());
            if (liquidation != null) mask |= owner.matchingAdapter.topology().accountLaneMask(liquidation.userId());
        } else if (pending.operation() == CommandSlot.Operation.SETTLEMENT) {
            var command = pending.decodedCommand().settlement();
            var progress = owner.runtimeLifecycleProgress(command.symbol());
            if (progress != null && progress.ordersComplete()
                    || !owner.lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders()).more()) {
                for (long userId : owner.instrumentSettlement.settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers())) {
                    mask |= owner.matchingAdapter.topology().accountLaneMask(userId);
                }
            }
        }
        return mask;
    }

    long validAccountLaneMask() {
        int count = owner.matchingAdapter.topology().accountLaneCount();
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    com.surprising.aeron.service.state.MatcherSettlementPlan initialMatcherSettlementPlan(
            CommandSlot pending,
            MatchingResult result) {
        long userId = pending.command().header().userId();
        return switch (pending.operation()) {
            case PLACE -> {
                long orderId = pending.decodedCommand().placeOrder().orderId();
                OrderRuntime admitted = pending.realtimeTakerOrder;
                var plan = admitted == null
                        ? com.surprising.aeron.service.state.MatcherSettlementPlan.buildInto(
                                pending.settlementPlanBuffer(), pending.sequence(), orderId, userId,
                                orderId, 0, result, owner.runtimeState, owner.identities)
                        : com.surprising.aeron.service.state.MatcherSettlementPlan.buildAdmittedInto(
                                pending.settlementPlanBuffer(), pending.sequence(), admitted,
                                orderId, 0, result, owner.runtimeState, owner.identities);
                plan.preCancellationsFromExpected(pending.preMatchingCancellationOrderIds(), result.cancellations());
                yield plan.rejectTaker(!result.accepted());
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                if (result.accepted()) yield null;
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.emptyInto(
                        pending.settlementPlanBuffer(), pending.sequence(), userId,
                        admission.originalOrderId(), admission.command().orderId(),
                        owner.runtimeState);
            }
            case TRIGGER -> {
                long[] execute = pending.decodedCommand().trigger();
                var trigger = owner.runtimeState.triggerOrder(execute[0]);
                if (trigger == null) yield null;
                long orderId = java.util.Objects.requireNonNull(pending.admittedMatchingOrder(),
                        "trigger admission is missing").orderId();
                var plan = com.surprising.aeron.service.state.MatcherSettlementPlan.buildInto(
                        pending.settlementPlanBuffer(), pending.sequence(), orderId, trigger.userId(), orderId, 0, result,
                        owner.runtimeState, owner.identities);
                plan.preCancellationsFromExpected(pending.preMatchingCancellationOrderIds(), result.cancellations());
                plan.rejectTaker(!result.accepted()).completeTrigger(result.accepted()
                        ? com.surprising.aeron.service.state.RuntimeTriggerOrderStateTransitions.prepareMatchedCompletion(
                                trigger, orderId, execute[3])
                        : com.surprising.aeron.service.state.RuntimeTriggerOrderStateTransitions.prepareRejectedCompletion(
                                trigger, result.resultCode(), execute[3]));
                yield plan;
            }
            default -> null;
        };
    }

    void requireCompleteAccountLanes(CommandSlot context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }


    private void retainControlResponse(CommandSlot pending, MatchingResult matchingResult) {
        if (pending.resultData == null && (owner.resultBuilder.commandRiskScanControl != null
                || owner.resultBuilder.commandLiquidationProgress != null
                || owner.resultBuilder.commandLiquidationBatchResult != null)) {
            pending.resultData = owner.resultBuilder.commandResultData(pending, matchingResult);
        }
    }

    boolean controlPending(long sequence) {
        if (!owner.laneCommandContexts.claimed(sequence)) return false;
        CommandSlot slot = owner.laneCommandContexts.required(sequence);
        return slot.controlPending && (slot.controlWork != null || slot.commitEvent != null);
    }

    void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            MatchingResult matchingResult,
            CommandSlot laneContext) {
        if (!matchingResult.accepted()) {
            int obsolete = batch.actions().stream()
                    .map(action -> owner.runtimeState.liquidation(action.liquidationId()))
                    .mapToInt(liquidation -> liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                            || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.CANCELED ? 1 : 0)
                    .sum();
            owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), 0,
                    batch.actions().size() - obsolete, obsolete, 0, 0);
            return;
        }
        var work = new java.util.function.BooleanSupplier() {
            int index, applied, pending, obsolete, processedOrders;
            int remaining = batch.maxCancelOrders();
            java.util.function.BooleanSupplier accountWork;
            List<com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.ExecutionRequest>
                    executionRequests;
            RiskScanCoordinator riskWork;
            long beforeRiskRevision;
            boolean riskStarted;

            @Override public boolean getAsBoolean() {
                if (accountWork != null) {
                    if (!accountWork.getAsBoolean()) return false;
                    accountWork = null;
                    requestCommitPublication();
                }
                if (executionRequests == null) executionRequests = new ArrayList<>();
                while (index < batch.actions().size()) {
                    var action = batch.actions().get(index++);
                    var liquidation = owner.runtimeState.liquidation(action.liquidationId());
                    if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                            || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.CANCELED) {
                        obsolete++;
                        continue;
                    }
                    if (remaining == 0) {
                        pending++;
                        continue;
                    }
                    ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                            action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                            action.cursorOrderId(), Math.min(remaining, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
                    var chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            action.cursorOrderId(), remaining);
                    for (CoreOrderState order : chunk.orders()) owner.resultBuilder.markOrderChanged(order.orderId());
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    boolean executable = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService
                            .isExecutable(owner.runtimeState, owner.identities, single);
                    long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                    var orders = executable ? chunk.orders() : List.<CoreOrderState>of();
                    if (executable) {
                        processedOrders += orders.size();
                        remaining -= orders.size();
                    }
                    if (nextCursor == 0) applied++; else pending++;
                    if (owner.runtimeState.asynchronousCommands()) {
                        executionRequests.add(new com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor
                                .ExecutionRequest(single, orders, nextCursor));
                        continue;
                    }
                    if (nextCursor != 0) owner.liquidations.advanceLiquidationCancellationRuntime(single, orders, nextCursor);
                    else owner.liquidations.executeLiquidationRuntime(single, orders);
                }
                if (!executionRequests.isEmpty()) {
                    if (owner.runtimeState.asynchronousCommands()) {
                        accountWork = com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor
                                .beginExecutionBatch(executionRequests, owner.runtimeState, owner.identities);
                        executionRequests = new ArrayList<>();
                        return false;
                    }
                    executionRequests.clear();
                }
                int completedRiskWork = 0;
                if (!riskStarted) {
                    riskStarted = true;
                    var scan = owner.runtimeState.firstRiskIncompleteScan();
                    var continuation = batch.riskScanContinuation();
                    if (scan != null && continuation != null
                            && owner.identities.symbol(scan.symbolId()).equals(continuation.symbol())
                            && scan.priceSequence() == continuation.priceSequence()
                            && scan.lastUserId() == continuation.lastUserId()) {
                        beforeRiskRevision = owner.runtimeState.revision();
                        if (owner.runtimeState.asynchronousCommands()) {
                            riskWork = new RiskScanCoordinator(batch.maxRiskScanUsers(), owner.positionUserIndex,
                                    owner.runtimeState, owner.identities);
                        } else {
                            completedRiskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(batch.maxRiskScanUsers(),
                                    owner.positionUserIndex, owner.runtimeState, owner.identities);
                            if (completedRiskWork > 0) owner.runtimeState.acceptChangedUserIds(userId ->
                                    laneContext.includeControlLanes(owner.matchingAdapter.topology().accountLaneMask(userId),
                                            validAccountLaneMask()));
                            if (owner.runtimeState.revision() != beforeRiskRevision) requestCommitPublication();
                        }
                    }
                }
                if (riskWork != null) {
                    if (!riskWork.poll()) return false;
                    completedRiskWork = riskWork.completedWork();
                    if (owner.runtimeState.revision() != beforeRiskRevision) requestCommitPublication();
                }
                owner.resultBuilder.materializeChangeAccumulators();
                owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(
                        batch.actions().size(), applied, pending, obsolete, processedOrders, completedRiskWork);
                return true;
            }
        };
        if (owner.runtimeState.asynchronousCommands()) laneContext.deferControl(work);
        else if (!work.getAsBoolean()) throw new IllegalStateException("synchronous liquidation batch suspended");
    }

    boolean matchingResultNeedsRecovery(CommandSlot pending,
                                                MatchingResult result) {
        if (result.outcome() == MatchingResult.Outcome.APPLIED
                || result.outcome()
                == MatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == MatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            List<Long> expected = pending.preMatchingCancellationOrderIds();
            var replacement = pending.orderBatch != null
                    ? pending.orderBatch.replacementAdmission : pending.matchingAdmission();
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
                || !result.nativeMatches(pending.command().header().commandId())
                || result.nativeMatcherSequence() <= appliedMatcherSequence) {
            throw owner.failMatching(pending, "matcher result does not continue the applied sequence"
                    + " expectedSequenceAfter=" + appliedMatcherSequence
                    + " actualSequence=" + result.nativeMatcherSequence(), null);
        }
    }

    void initializeMatcherProgress(MatcherSnapshot snapshot) {
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

    void applyMatcherProgress(com.surprising.aeron.service.state.MatcherSettlementEvent event) {
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
    void advanceMatchingProgress(long clusterTimestamp, long clusterPosition, long throughSequence) {
        long first = owner.pendingMatching.firstSequence();
        if (first > throughSequence) return;
        CommandSlot pending = owner.pendingMatching.get(first);
        // 连续轮询复用命令已有的Map键，不为同一在途序号反复分配Long。
        OrderBatchPending head = pending == null ? null : pending.orderBatch;
        if (head != null && !head.activated()) {
            owner.batches.activateOrderBatch(head, pending, true);
        }
        if (owner.hasMatchingDrainWork()) owner.drainMatchingCompletions();
        dispatchReadyPlaceSettlements(clusterTimestamp, clusterPosition, throughSequence);
    }

    boolean matchingCommitReady(CommandSlot pending) {
        if (pending == null) return false;
        OrderBatchPending batch = pending.orderBatch;
        // A matcher result alone does not make a batch ready while its Lane work is outstanding.
        if (batch != null && (!batch.activated() || !batch.laneWorkComplete())) return false;
        if (batch != null && batch.itemAdmission != null) return true;
        if (batch != null && (batch.itemSettlementEvent != null || batch.laneCommitEvent != null)) return true;
        if (batch != null && batch.settlementEvent != null && batch.settlementEvent.direct())
            return batch.settlementEvent.complete();
        if (batch != null && batch.placeBatchAdmissionEvent != null
                && (!batch.placeBatchAdmissionEvent.complete() || !pending.isMatchingSubmitted())) return false;
        if (owner.hasPendingMatchingRejection(pending.sequence())) {
            if (!pending.isMatchingSubmitted()) return true;
            if (pending.settlementEvent() != null && pending.settlementEvent().direct())
                return pending.settlementEvent().complete();
            CommandSlot context = owner.laneCommandContexts.required(pending.sequence());
            return context.matchingResult() != null || context.hasMatchingCompletion();
        }
        // Direct Matcher->Lane events have no Owner matching-result slot.  Their Lane completion
        // bit is the authoritative ordered-commit readiness signal.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct())
            return pending.settlementEvent().complete();
        if (pending.hasLaneContinuation()) return pending.laneContinuationComplete();
        CommandSlot context = pending;
        return context.hasMatchingCompletion() || context.matchingResult() != null;
    }

    /**
     * Collect one completed ordinary PLACE admission at the ordered commit head.  The event is
     * already immutable and complete before this method is called; all reservation/publication
     * semantics remain unchanged while the old per-shard submission poll disappears.
     */
    private boolean collectPlaceAdmissionIfReady(CommandSlot pending) {
        return owner.collectPlaceAdmissionIfReady(pending);
    }

    void drainMatcherSettlementCompletions() {
        boolean changed = owner.runtimeState.takeDirectMatcherSettlementReadyLaneMask() != 0;
        long readyLaneMask = owner.runtimeState.takeMatcherSettlementReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = owner.runtimeState.pollMatcherSettlementReady(laneId)) != 0) {
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
    private MatchingResult matchingResult(
            CommandSlot pending, CommandSlot context) {
        MatchingResult matching =
                pending.orderBatch != null && (pending.orderBatch.itemSettlementEvent != null
                        || pending.orderBatch.itemAdmission != null && pending.orderBatch.activated())
                ? pending.orderBatch.lastMatchingResult : context.matchingResult();
        if (matching == null && pending.settlementEvent() != null && pending.settlementEvent().direct()) {
            matching = pending.settlementEvent().firstDirectResult();
        }
        if (matching == null && pending.orderBatch != null && pending.orderBatch.settlementEvent != null
                && pending.orderBatch.settlementEvent.direct()) {
            matching = pending.orderBatch.settlementEvent.firstDirectResult();
        }
        if (matching == null && pending.orderBatch != null && pending.orderBatch.itemSettlementEvent != null
                && pending.orderBatch.itemSettlementEvent.direct()) {
            matching = pending.orderBatch.itemSettlementEvent.firstDirectResult();
        }
        if (matching == null && (pending.settlementEvent() == null || pending.settlementEvent().direct())
                && pending.cancelEvent() == null) {
            matching = context.takeMatchingCompletion();
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
        return completeMatching(pending.sequence(), matching, clusterTimestamp, clusterPosition);
    }

    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
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
        owner.beginDownstreamPublicationBatch();
        try {
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
        } finally {
            owner.endDownstreamPublicationBatch();
        }
    }

    /**
     * Commits one ordered Matcher head and returns its response directly to the command window
     * owner. Sequence-zero commands never enter this method because they already completed during
     * admission and have no pending Matcher slot.
     */
    CoreResponse commitReadyMatchingHead(long clusterTimestamp, long clusterPosition,
                                         long throughSequence, long dispatchThroughSequence,
                                         boolean advanceProgress) {
        owner.assertOwner();
        owner.assertHealthy();
        if (throughSequence <= 0) throw new IllegalArgumentException("matching head sequence must be positive");
        owner.beginDownstreamPublicationBatch();
        try {
            if (advanceProgress) {
                advanceMatchingProgress(clusterTimestamp, clusterPosition, dispatchThroughSequence);
            }
            if (owner.pendingMatching.firstSequence() > throughSequence) return null;
            CommandSlot pending = pollReadyPending();
            if (pending == null || pending.sequence() > throughSequence) return null;
            CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
            owner.pendingMatching.progressChanged();
            return response;
        } finally {
            owner.endDownstreamPublicationBatch();
        }
    }

    /** Applies one ready slot while preserving the existing response-routing boundary. */
    private CoreResponse commitReadyPending(CommandSlot pending, long clusterTimestamp, long clusterPosition) {
        long sequence = pending.sequence();
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        if (owner.hasPendingMatchingRejection(sequence)) {
            if (pending.isMatchingSubmitted()
                    && pending.settlementEvent() != null && pending.settlementEvent().direct()) {
                if (!pending.settlementEvent().complete()) return null;
            } else if (pending.isMatchingSubmitted()) {
                CommandSlot context = owner.laneCommandContexts.required(sequence);
                if (context.matchingResult() == null && context.takeMatchingCompletion() == null) return null;
            }
            return completeRejectedMatching(sequence);
        }
        CommandSlot context = owner.laneCommandContexts.required(sequence);
        MatchingResult matching = matchingResult(pending, context);
        return matching == null ? null
                : tryMatchingCommit(pending, matching, clusterTimestamp, clusterPosition);
    }

    void dispatchReadyPlaceSettlements(long clusterTimestamp, long clusterPosition, long throughSequence) {
        long revision = owner.pendingMatching.dispatchRevision();
        if (checkedDispatchRevision == revision && checkedDispatchThrough == throughSequence) return;
        // 保存检查前的输入；本次派发若释放了跨分区依赖，下次仍会继续推进。
        checkedDispatchRevision = revision;
        checkedDispatchThrough = throughSequence;
        // The ring maintains one ready cursor per matcher shard and a primitive bitmask.  Owner
        // only visits ready partitions; it does not scan the global dispatch prefix once per
        // shard or build a candidate array.
        long readyShards = owner.pendingMatching.readyPartitionMask(throughSequence);
        while (readyShards != 0) {
            int shard = Long.numberOfTrailingZeros(readyShards);
            readyShards &= readyShards - 1;
            CommandSlot candidate = owner.pendingMatching.readyPartitionHead(shard);
            long beforeRevision = owner.pendingMatching.dispatchRevision();
            if (candidate != null)
                dispatchPartitionSettlements(shard, clusterTimestamp, clusterPosition, throughSequence, candidate);
            if (owner.pendingMatching.dispatchRevision() == beforeRevision) break;
            readyShards = owner.pendingMatching.readyPartitionMask(throughSequence);
        }
    }

    private void dispatchPartitionSettlements(int shard, long clusterTimestamp, long clusterPosition,
                                             long throughSequence, CommandSlot firstCandidate) {
        CommandSlot first = firstCandidate;
        while (true) {
            if (first == null) owner.pendingMatching.readyPartitionMask(throughSequence);
            CommandSlot pending = first == null
                    ? owner.pendingMatching.readyPartitionHead(shard) : first;
            first = null;
            if (pending != null && pending.sequence() <= throughSequence && pending.settlementEvent() != null
                    && pending.settlementEvent().direct() && !pending.settlementEvent().matcherOwnedPublication()
                    && pending.settlementEvent().ready()
                    && !pending.settlementEvent().dispatched()) {
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                var event = pending.settlementEvent();
                event.commitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                owner.runtimeState.dispatchOwnerControlledSettlement(event);
                owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
                owner.pendingMatching.progressChanged();
                pending.countPipelinedSettlement();
                dispatchedSettlementInFlight++;
                dispatchedSettlementHighWaterMark = Math.max(dispatchedSettlementHighWaterMark, dispatchedSettlementInFlight);
                continue;
            }
            if (pending == null || pending.sequence() > throughSequence || pending.operation() != CommandSlot.Operation.PLACE
                    || pending.settlementEvent() != null || owner.hasPendingMatchingRejection(pending.sequence())) {
                return;
            }
            OrderBatchPending batch = pending.orderBatch;
            if (batch != null) {
                if (batch.settlementEvent != null && batch.settlementEvent.direct()
                        && batch.settlementEvent.ready() && !batch.settlementEvent.dispatched()) {
                    if (!batch.admissionCollected() || !batch.canPredispatch()) return;
                    pending.establishCommitFence(clusterTimestamp, clusterPosition);
                    batch.settlementEvent.commitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                    owner.runtimeState.dispatchOwnerControlledSettlement(batch.settlementEvent);
                    batch.markPredispatched();
                    owner.pendingMatching.progressChanged();
                    owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
                    pending.countPipelinedSettlement();
                    dispatchedSettlementInFlight++;
                    dispatchedSettlementHighWaterMark = Math.max(dispatchedSettlementHighWaterMark, dispatchedSettlementInFlight);
                    continue;
                }
                // The ordinary commit loop can reach this batch after completing a cancel
                // without another dispatch pass. Once it owns the commit, it also owns Lane
                // dispatch; pumping while its event is pending must not submit it a second time.
                if (!batch.pipelined || !batch.admissionCollected() || !batch.canPredispatch()) return;
                if (!batch.matchingApplied()) {
                    CommandSlot context = pending;
                    com.surprising.aeron.service.matching.CoreMatchingResult matching = context.matchingCompletion();
                    if (matching == null) return;
                    // Rejections release provisional funds on the owner. Keep those mutations
                    // in the ordered commit context instead of speculative batch dispatch.
                    for (var result : batch.pipelinedMatchingResults) if (!result.accepted()) return;
                    matching = context.takeMatchingCompletion();
                    pending.establishCommitFence(clusterTimestamp, clusterPosition);
                    if (matchingResultNeedsRecovery(pending, matching)) {
                        throw owner.batches.failOrderBatch(batch, pending,
                                "matcher continuation returned " + matching.resultCode(),
                                batch.pipelinedMatchingFailure);
                    }
                    validateMatchingEvidence(pending, matching);
                    applyMatcherProgress(matching);
                    owner.batches.applyPipelinedPlaceBatchResults(batch, pending, matching);
                    owner.batches.initializeOrderBatchLaneContext(batch, pending);
                }
                owner.batches.dispatchOrderBatchLaneWork(batch, pending,
                        pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                batch.markPredispatched();
                owner.pendingMatching.progressChanged();
                owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
                pending.countPipelinedSettlement();
                dispatchedSettlementInFlight++;
                dispatchedSettlementHighWaterMark = Math.max(
                        dispatchedSettlementHighWaterMark, dispatchedSettlementInFlight);
                continue;
            }
            // A normal PLACE without a preconstructed direct settlement is completed strictly at
            // the ordered head. Its Matcher result may already be ready, but applying it from
            // this partition predispatch pass would advance exchange-core past earlier Core
            // sequences whose Lane admission has not yet been collected.
            if (pending.settlementEvent() == null || !pending.settlementEvent().direct()) return;
            CommandSlot laneContext = pending;
            if (pending.placeAdmission() != null && !collectPlaceAdmissionIfReady(pending)) return;
            if (owner.hasPendingMatchingRejection(pending.sequence())) return;
            com.surprising.aeron.service.matching.CoreMatchingResult matching = laneContext.matchingCompletion();
            if (matching == null || !matching.accepted()) return;
            matching = laneContext.takeMatchingCompletion();
            pending.establishCommitFence(clusterTimestamp, clusterPosition);
            pending.dispatchOnly();
            CoreResponse response = completeMatching(
                    pending.sequence(), matching, clusterTimestamp, clusterPosition);
            if (response != null || pending.settlementEvent() == null
                    || !pending.hasCommitContext()) {
                throw new IllegalStateException("pipelined matcher settlement committed during dispatch");
            }
            owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
            owner.pendingMatching.progressChanged();
            pending.countPipelinedSettlement();
            dispatchedSettlementInFlight++;
            dispatchedSettlementHighWaterMark = Math.max(
                    dispatchedSettlementHighWaterMark, dispatchedSettlementInFlight);
        }
    }

    void requestCommitPublication() { publication.request(); }
    void beginCommitPublicationBatch() { publication.begin(); }
    void completeCommitPublicationBatch() { publication.complete(); }
    void abortCommitPublicationBatch() { publication.abort(); }
    void publishCommittedChanges() { publication.publish(); }
    boolean commitPublicationDeferred() { return publication.deferred(); }
    boolean commitPublicationDirty() { return publication.dirty(); }
    boolean commitPublicationProvisionalOnly() { return publication.provisionalOnly(); }
    void initializeCommitPublication(long runtimePatchRevision) {
        publication.initialize(runtimePatchRevision);
    }
    void deferProvisionalCommitPublication() { publication.deferProvisionalProjection(); }
    void suspendCommitPublication() { publication.suspend(); }
    void restoreCommitPublication(boolean dirty, boolean provisionalOnly) {
        publication.restore(dirty, provisionalOnly);
    }
}
