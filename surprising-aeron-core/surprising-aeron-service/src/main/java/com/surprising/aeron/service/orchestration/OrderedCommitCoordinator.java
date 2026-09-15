package com.surprising.aeron.service.orchestration;


import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.state.RiskScanCoordinator;

import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
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
import java.util.LinkedHashSet;
import java.util.List;

/** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
final class OrderedCommitCoordinator {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    /** 上次派发检查的输入水位；只有外部完成、本地推进或依赖顺序变化才重扫。 */
    private long checkedDispatchRevision = Long.MIN_VALUE;
    private long checkedDispatchThrough = Long.MIN_VALUE;

    OrderedCommitCoordinator(TradingCoreRuntime owner) { this.owner = owner; }

    /** 各撮合分片已核验的序号；提交时逐分片验证连续性。 */
    long[] appliedMatcherSequences;

    /** 各撮合分片已核验的前缀摘要；与序号一起验证重放一致性。 */
    long[] appliedMatcherPrefixDigests;

    /** 已预派发且尚未收集完成的结算命令数量。 */
    int dispatchedSettlementInFlight;

    /** 结算在途数量峰值，仅用于运行观测。 */
    int dispatchedSettlementHighWaterMark;

    /** Reusable partition candidate storage; discovery must not allocate per Owner poll. */
    private PendingMatching[] partitionDispatchHeads;

    /** 已发布变更的运行时 revision，禁止发布倒退。 */
    long runtimePatchRevision;

    /** 唯一增量发布位置；同步更新资金增量、索引与提交水位。 */
    final OwnerCommitPublisher ownerCommitPublisher = new OwnerCommitPublisher();

    /** 当前范围内延后发布，等待完整的有序提交边界。 */
    boolean commitPublicationDeferred;

    /** 当前范围存在尚未发布的变更。 */
    boolean commitPublicationDirty;

    /** 当前变更仅为暂存准入，尚不能作为终态对外发布。 */
    boolean commitPublicationProvisionalOnly;

    CoreResponse completeRejectedMatching(long sequence) {
        owner.assertOwner();
        PendingMatching pending = owner.pendingMatching.get(sequence);
        if (pending == null || !owner.hasPendingMatchingRejection(sequence)) return null;
        CoreResultCode resultCode = owner.laneCommandContexts.required(sequence).matchingRejection();
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        if (capacityReservation == null) {
            throw new IllegalStateException("rejected matching admission reservation is missing");
        }
        owner.activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
        owner.commitMatchingSequence(sequence);
        long requiredExportSequence = 0;
        long stateHash = owner.stateHash(owner.cachedBusinessStateHash, pending.command().header().commandId(),
                ResponseStatus.REJECTED, resultCode, sequence);
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence, requiredExportSequence, stateHash,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        owner.removePendingMatching(sequence);
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, requiredExportSequence, stateHash, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return owner.releaseAdmission(capacityReservation, response);
    }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        PendingMatching pending = owner.pendingMatching.get(sequence);
        if (pending == null || matchingResult == null) return null;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        clusterTimestamp = pending.commitFenceTimestamp();
        clusterPosition = pending.commitFenceClusterPosition();
        owner.currentClusterPosition = clusterPosition;
        LaneCommandContextRing.Context laneContext = owner.laneCommandContexts.required(sequence);
        // A direct Lane event can finish before an earlier command on the same Matcher SPSC
        // shard becomes consumable.  Consume only already-published Matcher slots here; if the
        // shard prefix is not yet available, leave the ordered head untouched for the next Owner
        // turn so context recycling never races an outstanding matcher token.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                && laneContext.submittedMatcherShard() != -1) {
            if (owner.hasMatchingDrainWork()) owner.drainMatchingCompletions();
            if (!owner.transferMatchingCompletion(sequence)) return null;
        }
        if (controlContinuationSequence == sequence) {
            owner.restoreMatchingCommitContext(pending);
            try {
                // The completed risk continuation also restores the response summary after suspension.
                if (controlContinuation != null && !controlContinuation.getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                // Account work is consumed once; commit publication may require later polls.
                controlContinuation = null;
                if (controlCommitEvent == null) {
                    owner.runtimeState.completePendingReservations(sequence);
                    owner.resultBuilder.materializeChangeAccumulators();
                    // A suspended control may accumulate users across several risk pages/Lanes.
                    // Register the complete commit input, not just the last page's runtime delta.
                    for (long userId : owner.resultBuilder.commandChangedUserIds)
                        laneContext.includeControlLanes(owner.matchingAdapter.topology().accountLaneMask(userId),
                                validAccountLaneMask());
                    controlCommitEvent = owner.runtimeState.dispatchLaneMutation(sequence,
                            owner.resultBuilder.commandChangedUserIds,
                            commitPublicationDirty ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                            clusterTimestamp, clusterPosition);
                }
                if (controlCommitEvent != null) {
                    if (!owner.runtimeState.laneCommitComplete(controlCommitEvent)) {
                        retainControlResponse(pending, matchingResult);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    long laneMask = controlCommitEvent.requiredLaneMask();
                    owner.runtimeState.releaseLaneCommit(controlCommitEvent);
                    controlCommitEvent = null;
                    laneContext.completeLanes(laneMask);
                }
                controlContinuation = null;
                controlContinuationSequence = 0;
                return finishAppliedMatching(pending, matchingResult, laneContext, null, controlApplyStartNanos, true);
            } catch (RuntimeException failure) {
                throw owner.failMatching(pending, "control Lane continuation failed", failure);
            }
        }
        OrderBatchPending waitingBatch = owner.batches.batch(sequence);
        if (waitingBatch != null && waitingBatch.settlementEvent != null && waitingBatch.settlementEvent.direct()
                && !waitingBatch.matchingApplied()) {
            if (!waitingBatch.settlementEvent.complete()) return null;
            validateMatchingEvidence(pending, waitingBatch.settlementEvent.firstDirectResult());
            applyMatcherProgress(waitingBatch.settlementEvent.lastDirectResult());
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
        // A sequential batch item now owns a preconstructed direct event.  The event can be
        // Lane-complete before the Owner has copied the Matcher token into the batch context;
        // apply the authoritative result through the normal batch path exactly once, after the
        // suspended commit context has been restored.
        if (waitingBatch != null && waitingBatch.itemSettlementEvent != null
                && waitingBatch.itemSettlementEvent.direct()
                && waitingBatch.lastMatchingResult == null) {
            if (!waitingBatch.itemSettlementEvent.complete()) return null;
            CoreMatchingResult directResult = waitingBatch.itemSettlementEvent.firstDirectResult();
            validateMatchingEvidence(pending, directResult);
            applyMatcherProgress(directResult);
            return owner.batches.completeOrderBatchMatching(
                    sequence, directResult,
                    clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemAdmission != null && waitingBatch.activated()) {
            return owner.batches.completeAmendReservation(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemSettlementEvent != null) {
            if (waitingBatch.itemSettlementEvent.direct()) {
                CoreMatchingResult directResult = waitingBatch.itemSettlementEvent.firstDirectResult();
                validateMatchingEvidence(pending, directResult);
                applyMatcherProgress(directResult);
                waitingBatch.lastMatchingResult = directResult;
            }
            return owner.batches.completeOrderBatchItemSettlement(
                    waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && pending.clusterIndependent
                && waitingBatch.kind == OrderBatchKind.CANCEL && !waitingBatch.commitStarted()) {
            owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
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
        if (pending.replaceEvent() != null) {
            if (!pending.replaceEvent().complete()) return null;
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedReplacePreparation(pending, matchingResult, laneContext);
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
                owner.activateFactContext(owner.sequenceAdmission(sequence), pending.command(), pending.fingerprint());
                beginCommitPublicationBatch();
                owner.addChangedUsers(pending.settlementPlan());
                owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(pending.settlementPlan());
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
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        if (capacityReservation == null) {
            throw new IllegalStateException("matching admission reservation is missing");
        }
        owner.activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
        beginCommitPublicationBatch();
        com.surprising.aeron.service.state.RuntimeTreasuryDelta settlementTreasuryDelta = null;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    owner.addChangedUsers(settlementPlan);
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
                    boolean dispatchOnly = pending.isDispatchOnly();
                    settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                            pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                    pending.takeDispatchOnly();
                    if (settlementTreasuryDelta == null || dispatchOnly) {
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
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
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                    owner.requireUnchangedAdmissionState(admission);
                    var command = admission.command();
                    long originalOrderId = admission.originalOrderId();
                    if (matchingResult.accepted()) {
                        int symbolId = admission.resolved().symbolId();
                        int assetId = owner.identities.assetId(admission.resolved().reservationAsset());
                        var replaceEvent = owner.runtimeState.dispatchReplace(
                                sequence, admission.userId(), originalOrderId,
                                acceptedPreMatchingCancellationIds(pending, matchingResult),
                                admission.resolved(), pending.command().header().commandId(),
                                admission.requiredReservationUnits(), symbolId, assetId,
                                clusterTimestamp, clusterPosition, owner.identities);
                        pending.replace(replaceEvent, applyStartNanos);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    } else {
                        owner.addChangedUsers(settlementPlan);
                        owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
                        long[] cancellations = acceptedReplacementCancellationIds(pending, matchingResult);
                        if (cancellations.length != 0) {
                            owner.resultBuilder.commandChangedOrderIds = ImmutableLongArrayList.takeOwnership(cancellations);
                            owner.runtimeState.releaseOwnerLaneAccess();
                            var cancelEvent = owner.runtimeState.dispatchCancelBatch(sequence, admission.userId(),
                                    cancellations, clusterTimestamp, clusterPosition, owner.identities, true);
                            pending.cancel(cancelEvent, applyStartNanos);
                            owner.suspendMatchingCommitContext(pending);
                            return null;
                        }
                    }
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = owner.runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    owner.addChangedUsers(settlementPlan);
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
                    settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                            pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                    if (settlementTreasuryDelta == null) return null;
                    requestCommitPublication();
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
                                    .beginExecution(command, orders, nextCursor, owner.runtimeState, owner.identities);
                            deferControl(() -> {
                                if (!work.getAsBoolean()) return false;
                                requestCommitPublication();
                                owner.resultBuilder.commandLiquidationProgress = progress;
                                return true;
                            });
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
                                    command, pending.command().header().commandId());
                            deferControl(() -> {
                                if (!work.poll()) return false;
                                owner.resultBuilder.commandSettlementProgress = work.result();
                                requestCommitPublication();
                                return true;
                            });
                        } else {
                            owner.instrumentSettlement.settleInstrumentRuntime(command,
                                    pending.command().header().commandId());
                        }
                    }
                }
            }
            if (controlContinuation != null) {
                controlContinuationSequence = sequence;
                controlApplyStartNanos = applyStartNanos;
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState.completePendingReservations(pending.sequence());
            if (pending.operation() == PendingMatching.Operation.PLACE
                    || pending.operation() == PendingMatching.Operation.REPLACE
                    || pending.operation() == PendingMatching.Operation.AMEND
                    || pending.operation() == PendingMatching.Operation.TRIGGER) {
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
    private CoreResponse finishAppliedMatching(PendingMatching pending, CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext, RuntimeTreasuryDelta settlementTreasuryDelta,
            long applyStartNanos) {
        return finishAppliedMatching(pending, matchingResult, laneContext, settlementTreasuryDelta,
                applyStartNanos, false);
    }

    /**
     * Common deterministic tail for every terminal matching path.  Collection and Lane event
     * release remain operation-specific, but sequence/hash/ledger/response work must have one
     * implementation so the Owner does not repeat it for place, cancel and direct settlement.
     */
    private CoreResponse storeTerminalResponse(PendingMatching pending, CoreMatchingResult matchingResult,
            ResponseStatus status, CoreResultCode resultCode) {
        RuntimeProjectionPoint beforeProjection = pending.beforeProjection();
        long applied = pending.sequence();
        long businessStateHash = owner.currentProjectionPoint == beforeProjection
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        owner.commitMatchingSequence(applied);
        long requiredExportSequence = 0;
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = owner.stateHash(businessStateHash, pending.command().header().commandId(),
                status, resultCode, applied);
        byte[] responseData = controlResultData == null
                ? owner.resultBuilder.commandResultData(pending, matchingResult) : controlResultData;
        controlResultData = null;
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, owner.resultBuilder.commandTradeCount);
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(), pending.fingerprint(),
                status, resultCode, applied, requiredExportSequence, stateHash, responseData);
        return CoreResponse.owned(status, status, resultCode, applied, requiredExportSequence, stateHash, responseData);
    }

    private CoreResponse finishAppliedMatching(PendingMatching pending, CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext, RuntimeTreasuryDelta settlementTreasuryDelta,
            long applyStartNanos, boolean lanesCommitted) {
        long sequence = pending.sequence();
        long clusterTimestamp = pending.commitFenceTimestamp();
        long clusterPosition = pending.commitFenceClusterPosition();
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(sequence);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        RuntimeTreasuryDelta settledTreasuryDelta = settlementTreasuryDelta;
            if (settledTreasuryDelta != null) {
                settledTreasuryDelta.apply(owner.runtimeState.treasury());
                owner.runtimeState.setMetadata(owner.productLine,
                        Math.addExact(owner.runtimeState.revision(),
                                pending.operation() == PendingMatching.Operation.TRIGGER && !matchingResult.accepted() ? 2 : 1));
                requestCommitPublication();
            }
        if (!lanesCommitted && pending.settlementEvent() == null) {
            owner.resultBuilder.materializeChangeAccumulators();
            controlCommitEvent = owner.runtimeState.dispatchLaneMutation(sequence,
                    owner.resultBuilder.commandChangedUserIds,
                    commitPublicationDirty ? owner.resultBuilder.commandChangedOrderIds : List.of(),
                    clusterTimestamp, clusterPosition);
            if (controlCommitEvent != null) {
                if (!owner.runtimeState.laneCommitComplete(controlCommitEvent)) {
                    controlContinuationSequence = sequence;
                    controlApplyStartNanos = applyStartNanos;
                    retainControlResponse(pending, matchingResult);
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                long laneMask = controlCommitEvent.requiredLaneMask();
                owner.runtimeState.releaseLaneCommit(controlCommitEvent);
                controlCommitEvent = null;
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
            completeCommitPublicationBatch();
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
        if (!owner.admissions.deferredMatching.isEmpty() || owner.batches.hasPendingBatches()) {
            owner.submitDeferredMatchingAfterBatch();
        }
        return owner.releaseAdmission(capacityReservation, response);
    }

    CoreResponse completeDispatchedMatcherSettlement(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        com.surprising.aeron.service.state.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
        owner.captureRealtimeTrades(pending);
        com.surprising.aeron.service.state.RuntimeTreasuryDelta settlementTreasuryDelta;
        try {
            settlementTreasuryDelta = owner.runtimeState.collectMatcherSettlement(
                    event, owner.commandFundsAccumulator, owner.terminalRetention);
            laneContext.completeLanes(event.requiredLaneMask());
            switch (pending.operation()) {
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
            if (pending.operation() == PendingMatching.Operation.PLACE
                    || pending.operation() == PendingMatching.Operation.REPLACE
                    || pending.operation() == PendingMatching.Operation.AMEND
                    || pending.operation() == PendingMatching.Operation.TRIGGER) {
                requestCommitPublication();
            }
            settlementTreasuryDelta.apply(owner.runtimeState.treasury());
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.addExact(owner.runtimeState.revision(),
                            Math.addExact(pending.operation() == PendingMatching.Operation.TRIGGER && !matchingResult.accepted() ? 2 : 1, pending.settlementPlan().preCancellationCount())));
            requestCommitPublication();
            owner.resultBuilder.materializeChangeAccumulators();
            long committedLaneMask = event.requiredLaneMask();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from immutable matcher fact", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) owner.resultBuilder.materializeCommandOrderViews(pending);
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
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        if (pending.takePipelinedSettlementCounted()) {
            dispatchedSettlementInFlight--;
            if (dispatchedSettlementInFlight < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        // The direct event is the authoritative Matcher fact, but the SPSC matcher slot still
        // must be consumed by the Owner before the sequence context can be recycled.
        owner.transferMatchingCompletion(pending.sequence());
        owner.runtimeState.releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.removePendingMatching(pending.sequence());
        if (!owner.admissions.deferredMatching.isEmpty() || owner.batches.hasPendingBatches()) owner.submitDeferredMatchingAfterBatch();
        return owner.releaseAdmission(capacityReservation, response);
    }

    CoreResponse completeDispatchedCancel(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        com.surprising.aeron.service.state.LaneCancelEvent event = pending.cancelEvent();
        if (event == null || !event.complete()) return null;
        try {
            owner.runtimeState.collectCancel(event, owner.commandFundsAccumulator, owner.terminalRetention);
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
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        CoreResponse response = storeTerminalResponse(
                pending, matchingResult, status, resultCode);
        owner.runtimeState.releaseCancel(pending.takeCancelEvent());
        owner.removePendingMatching(pending.sequence());
        if (!owner.admissions.deferredMatching.isEmpty() || owner.batches.hasPendingBatches()) owner.submitDeferredMatchingAfterBatch();
        return owner.releaseAdmission(capacityReservation, response);
    }

    CoreResponse completeDispatchedReplacePreparation(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        com.surprising.aeron.service.state.LaneReplaceEvent event = pending.replaceEvent();
        if (event == null || !event.complete()) return null;
        try {
            owner.runtimeState.collectReplace(event, owner.commandFundsAccumulator, owner.terminalRetention);
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.addExact(owner.runtimeState.revision(),
                            Math.addExact(2, event.preCancellationCount())));
            owner.runtimeState.releaseReplace(pending.takeReplaceEvent());
            ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
            com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan =
                    com.surprising.aeron.service.state.MatcherSettlementPlan.buildInto(
                            laneContext.settlementPlanBuffer(), pending.sequence(), admission.command().orderId(), admission.userId(),
                            admission.originalOrderId(), admission.command().orderId(), matchingResult,
                            owner.runtimeState, owner.identities);
            owner.addChangedUsers(settlementPlan);
            owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
            requestCommitPublication();
            pending.dispatchOnly();
            owner.applyMatchesOnAccountLanes(pending, settlementPlan, pending.sequence(), matchingResult,
                    laneContext, pending.settlementApplyStartNanos());
            pending.takeDispatchOnly();
            owner.suspendMatchingCommitContext(pending);
            return null;
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted replace result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher replace state diverged", exception);
        } catch (RuntimeException exception) {
            throw owner.failMatching(pending, "account lane replace failed; snapshot/log recovery is required",
                    exception);
        }
    }

    long expectedLaneMask(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        long mask = 0;
        long activeUserId = pending.command().header().userId();
        // Instrument settlement changes the selected position/order owners, not its operator.
        if (activeUserId > 0 && pending.operation() != PendingMatching.Operation.SETTLEMENT)
            mask |= owner.matchingAdapter.topology().accountLaneMask(activeUserId);
        for (MatcherEvent match : result.matcherEvents()) {
            if (match.eventType() == MatcherEventType.TRADE) {
                mask |= owner.matchingAdapter.topology().accountLaneMask(match.matchedOrderUid());
            }
        }
        for (CoreCancellationResult cancellation : result.cancellations()) {
            OrderRuntime order = owner.runtimeOrder(cancellation.orderId());
            if (order != null) mask |= owner.matchingAdapter.topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            for (var action : TradingCommandCodec.decodeExecuteLiquidationBatch(
                    pending.command().payloadUnsafe()).actions()) {
                mask |= owner.matchingAdapter.topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == PendingMatching.Operation.LIQUIDATION) {
            var command = pending.decodedCommand().liquidation();
            var liquidation = owner.runtimeState.liquidation(command.liquidationId());
            if (liquidation != null) mask |= owner.matchingAdapter.topology().accountLaneMask(liquidation.userId());
        } else if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
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
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        long userId = pending.command().header().userId();
        return switch (pending.operation()) {
            case PLACE -> {
                long orderId = pending.decodedCommand().placeOrder().orderId();
                var plan = com.surprising.aeron.service.state.MatcherSettlementPlan.buildInto(
                        owner.laneCommandContexts.required(pending.sequence()).settlementPlanBuffer(), pending.sequence(), orderId, userId, orderId, 0, result,
                        owner.runtimeState, owner.identities);
                plan.preCancellationsFromExpected(pending.preMatchingCancellationOrderIds(), result.cancellations());
                yield plan.rejectTaker(!result.accepted());
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                if (result.accepted()) yield null;
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.emptyInto(
                        owner.laneCommandContexts.required(pending.sequence()).settlementPlanBuffer(), pending.sequence(), userId,
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
                        owner.laneCommandContexts.required(pending.sequence()).settlementPlanBuffer(), pending.sequence(), orderId, trigger.userId(), orderId, 0, result,
                        owner.runtimeState, owner.identities);
                plan.preCancellationsFromExpected(pending.preMatchingCancellationOrderIds(), result.cancellations());
                plan.rejectTaker(!result.accepted()).completeTrigger(result.accepted()
                        ? com.surprising.aeron.service.state.RuntimeCommandProcessor.prepareMatchedTriggerCompletion(
                                trigger, orderId, execute[3])
                        : com.surprising.aeron.service.state.RuntimeCommandProcessor.prepareRejectedTriggerCompletion(
                                trigger, result.resultCode(), execute[3]));
                yield plan;
            }
            default -> null;
        };
    }

    void requireCompleteAccountLanes(LaneCommandContextRing.Context context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }

    /** Final control response is encoded once before its DTO context is released while awaiting Lane commit. */
    private byte[] controlResultData;

    private void retainControlResponse(PendingMatching pending, CoreMatchingResult matchingResult) {
        if (controlResultData == null && (owner.resultBuilder.commandRiskScanControl != null
                || owner.resultBuilder.commandLiquidationProgress != null
                || owner.resultBuilder.commandLiquidationBatchResult != null)) {
            controlResultData = owner.resultBuilder.commandResultData(pending, matchingResult);
        }
    }

    /** 控制命令窗口只有一个在途命令；无需另建按序号索引或任务队列。 */
    private java.util.function.BooleanSupplier controlContinuation;
    /** Existing control continuation owns this event until every participating Lane publishes. */
    private com.surprising.aeron.service.state.LaneCommitEvent controlCommitEvent;
    /** 跨回调保留的原始撮合序号及开始时刻。 */
    private long controlContinuationSequence, controlApplyStartNanos;

    boolean controlPending(long sequence) { return controlContinuationSequence == sequence
            && (controlContinuation != null || controlCommitEvent != null); }

    void deferControl(java.util.function.BooleanSupplier continuation) {
        if (controlContinuation != null) throw new IllegalStateException("control continuation already active");
        controlContinuation = java.util.Objects.requireNonNull(continuation);
    }

    void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
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
            RiskScanCoordinator riskWork;
            long beforeRiskRevision;
            boolean riskStarted;

            @Override public boolean getAsBoolean() {
                if (accountWork != null) {
                    if (!accountWork.getAsBoolean()) return false;
                    accountWork = null;
                    requestCommitPublication();
                }
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
                        accountWork = com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor
                                .beginExecution(single, orders, nextCursor, owner.runtimeState, owner.identities);
                        return false;
                    }
                    if (nextCursor != 0) owner.liquidations.advanceLiquidationCancellationRuntime(single, orders, nextCursor);
                    else owner.liquidations.executeLiquidationRuntime(single, orders);
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
        if (owner.runtimeState.asynchronousCommands()) deferControl(work);
        else if (!work.getAsBoolean()) throw new IllegalStateException("synchronous liquidation batch suspended");
    }

    boolean matchingResultNeedsRecovery(PendingMatching pending,
                                                com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (result.outcome() == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.APPLIED
                || result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            List<Long> expected = pending.preMatchingCancellationOrderIds();
            long replacedOrderId = pending.matchingAdmission() != null
                    && (pending.operation() == PendingMatching.Operation.REPLACE
                    || pending.operation() == PendingMatching.Operation.AMEND)
                    ? pending.matchingAdmission().originalOrderId() : 0;
            boolean containsTrade = false;
            for (MatcherEvent event : result.matcherEvents()) {
                if (event.eventType() == MatcherEventType.TRADE) {
                    containsTrade = true;
                    break;
                }
            }
            boolean cancellationsValid = true;
            for (CoreCancellationResult cancellation : result.cancellations()) {
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
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT) return true;
        return result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.FATAL_DIVERGENCE;
    }

    private long[] acceptedReplacementCancellationIds(PendingMatching pending, CoreMatchingResult result) {
        long original = owner.requireMatchingAdmission(pending).originalOrderId();
        long[] ids = new long[result.cancellations().size()];
        int count = 0;
        List<Long> expected = pending.preMatchingCancellationOrderIds();
        for (CoreCancellationResult cancellation : result.cancellations()) {
            if (!cancellation.accepted()) continue;
            long id = cancellation.orderId();
            boolean expectedCancellation = expected instanceof ImmutableLongArrayList primitive
                    ? primitive.containsLong(id) : expected.contains(id);
            if (id != original && !expectedCancellation)
                throw new IllegalStateException("replacement canceled an unplanned order");
            boolean duplicate = false;
            for (int index = 0; index < count; index++) if (ids[index] == id) duplicate = true;
            if (!duplicate) ids[count++] = id;
        }
        return count == ids.length ? ids : java.util.Arrays.copyOf(ids, count);
    }

    static long[] acceptedPreMatchingCancellationIds(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        List<Long> expected = pending.preMatchingCancellationOrderIds();
        if (expected.isEmpty()) return TradingCoreRuntime.EMPTY_ORDER_IDS;
        long[] accepted = new long[expected.size()];
        int count = 0;
        if (expected instanceof ImmutableLongArrayList primitive) {
            for (int expectedIndex = 0; expectedIndex < primitive.size(); expectedIndex++) {
                long orderId = primitive.valueAt(expectedIndex);
                for (CoreCancellationResult cancellation : result.cancellations()) {
                    if (cancellation.accepted() && cancellation.orderId() == orderId) {
                        accepted[count++] = orderId;
                        break;
                    }
                }
            }
        } else {
            for (Long orderId : expected) {
                if (orderId == null) continue;
                for (CoreCancellationResult cancellation : result.cancellations()) {
                    if (cancellation.accepted() && cancellation.orderId() == orderId) {
                        accepted[count++] = orderId;
                        break;
                    }
                }
            }
        }
        return count == accepted.length ? accepted : java.util.Arrays.copyOf(accepted, count);
    }

    void validateMatchingEvidence(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var nativeCommand = result.nativeCommand();
        var prefix = result.matcherPrefix();
        int matcherShardId = nativeCommand.matcherShardId();
        long appliedMatcherSequence = matcherSequence(matcherShardId);
        long appliedMatcherPrefixDigest = matcherPrefixDigest(matcherShardId);
        if (nativeCommand.coreSequence() != pending.sequence()
                || !nativeCommand.matches(pending.command().header().commandId())
                || nativeCommand.matcherSequence() <= appliedMatcherSequence
                || !prefix.bound()
                || prefix.before() != appliedMatcherPrefixDigest
                || prefix.after() == prefix.before()) {
            throw owner.failMatching(pending, "matcher result prefix does not continue the applied prefix"
                    + " expectedSequenceAfter=" + appliedMatcherSequence
                    + " actualSequence=" + nativeCommand.matcherSequence()
                    + " expectedPrefix=" + appliedMatcherPrefixDigest
                    + " actualBefore=" + prefix.before()
                    + " actualAfter=" + prefix.after(), null);
        }
    }

    void initializeMatcherProgress(MatcherSnapshot snapshot) {
        long initialDigest = com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix.initialDigest();
        java.util.Arrays.fill(appliedMatcherPrefixDigests, initialDigest);
        if (snapshot == null) return;
        for (com.surprising.aeron.service.matching.MatcherShardProgress progress
                : snapshot.matcherShardProgress()) {
            int index = matcherProgressIndex(progress.matcherShardId());
            appliedMatcherSequences[index] = progress.matcherSequence();
            appliedMatcherPrefixDigests[index] = progress.prefixDigest();
        }
    }

    void applyMatcherProgress(
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        int index = matcherProgressIndex(result.nativeCommand().matcherShardId());
        appliedMatcherSequences[index] = result.nativeCommand().matcherSequence();
        appliedMatcherPrefixDigests[index] = result.matcherPrefix().after();
    }

    long matcherSequence(int matcherShardId) {
        return appliedMatcherSequences[matcherProgressIndex(matcherShardId)];
    }

    long matcherPrefixDigest(int matcherShardId) {
        return appliedMatcherPrefixDigests[matcherProgressIndex(matcherShardId)];
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
        PendingMatching pending = owner.pendingMatching.get(first);
        // 连续轮询复用命令已有的Map键，不为同一在途序号反复分配Long。
        OrderBatchPending head = pending == null ? null : pending.orderBatch;
        if (head != null && !head.activated()) {
            owner.batches.activateOrderBatch(head, pending, true);
        }
        if (owner.hasMatchingDrainWork()) owner.drainMatchingCompletions();
        dispatchReadyPlaceSettlements(clusterTimestamp, clusterPosition, throughSequence);
    }

    boolean matchingCommitReady(PendingMatching pending) {
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
        if (owner.hasPendingMatchingRejection(pending.sequence())) return true;
        // Direct Matcher->Lane events have no Owner matching-result slot.  Their Lane completion
        // bit is the authoritative ordered-commit readiness signal.
        if (pending.settlementEvent() != null && pending.settlementEvent().direct())
            return pending.settlementEvent().complete();
        if (pending.hasLaneContinuation()) return pending.laneContinuationComplete();
        LaneCommandContextRing.Context context = owner.laneCommandContexts.required(pending.sequence());
        return context.hasMatchingCompletion() || context.matchingResult() != null;
    }

    void drainMatcherSettlementCompletions() {
        long readyLaneMask = owner.runtimeState.takeMatcherSettlementReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = owner.runtimeState.pollMatcherSettlementReady(laneId)) != 0) {
                // The event owns the completion bits. Owner only retires the notification cursor;
                // ordered commit reads the event at the head and performs the business collect.
                owner.pendingMatching.progressChanged();
            }
        }
    }

    PendingMatching pollReadyPending() {
        PendingMatching pending = owner.pendingMatching.head();
        return matchingCommitReady(pending) ? pending : null;
    }

    /**
     * Performs one non-blocking ordered-head probe.  Matcher and Lane workers publish ready
     * notifications independently; the Owner must return to the cluster agent when the head is
     * not ready instead of spinning or parking on either worker.
     */
    PendingMatching pollReadyHead(
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
    private com.surprising.aeron.service.matching.CoreMatchingResult matchingResult(
            PendingMatching pending, LaneCommandContextRing.Context context) {
        com.surprising.aeron.service.matching.CoreMatchingResult matching =
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
                && pending.cancelEvent() == null && pending.replaceEvent() == null) {
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
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matching,
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
                throughSequence, throughSequence, handler);
    }

    /** 派发上限只覆盖已准入的独立命令；最终提交上限与每命令的日志时间不变。 */
    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, long dispatchThroughSequence,
                            TradingCoreRuntime.MatchingCommitHandler handler) {
        owner.assertOwner();
        owner.assertHealthy();
        if (maxCompletions <= 0 || handler == null) {
            throw new IllegalArgumentException("matching commit batch requires a positive limit and handler");
        }
        owner.beginDownstreamPublicationBatch();
        try {
            advanceMatchingProgress(clusterTimestamp, clusterPosition, dispatchThroughSequence);
            int completed = 0;
            int attempts = 0;
            while (attempts < maxCompletions) {
                if (owner.pendingMatching.firstSequence() > throughSequence) break;
                PendingMatching pending = pollReadyPending();
                if (pending == null && attempts == 0 && awaitFirst) {
                    pending = pollReadyHead(clusterTimestamp, clusterPosition, throughSequence);
                }
                if (pending == null) break;
                long sequence = pending.sequence();
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                CoreResponse response;
                if (owner.hasPendingMatchingRejection(sequence)) {
                    response = completeRejectedMatching(sequence);
                } else {
                    LaneCommandContextRing.Context context = owner.laneCommandContexts.required(sequence);
                    com.surprising.aeron.service.matching.CoreMatchingResult matching = matchingResult(pending, context);
                    if (matching == null) {
                        // The completion queue can be observed before the immutable Lane fact
                        // is visible (or while a retryable continuation is still outstanding).
                        // Keep the ordered head for the next Owner turn.
                        break;
                    }
                    response = tryMatchingCommit(pending, matching, clusterTimestamp, clusterPosition);
                }
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

    void dispatchReadyPlaceSettlements(long clusterTimestamp, long clusterPosition, long throughSequence) {
        long revision = owner.pendingMatching.dispatchRevision();
        if (checkedDispatchRevision == revision && checkedDispatchThrough == throughSequence) return;
        // 保存检查前的输入；本次派发若释放了跨分区依赖，下次仍会继续推进。
        checkedDispatchRevision = revision;
        checkedDispatchThrough = throughSequence;
        // 每个订单簿分区独立推进；先用一次全局前缀扫描得到所有候选，避免按分区重复
        // 扫描同一段 64/128 窗口。候选数组在 Owner 内复用，不进入分配热路径。
        int shardCount = owner.matchingAdapter.topology().matchingEngineCount();
        if (partitionDispatchHeads == null || partitionDispatchHeads.length != shardCount)
            partitionDispatchHeads = new PendingMatching[shardCount];
        owner.pendingMatching.collectPartitionDispatchHeads(throughSequence, partitionDispatchHeads);
        for (int shard = 0; shard < shardCount; shard++) {
            PendingMatching candidate = partitionDispatchHeads[shard];
            if (candidate != null)
                dispatchPartitionSettlements(shard, clusterTimestamp, clusterPosition, throughSequence, candidate);
        }
    }

    private void dispatchPartitionSettlements(int shard, long clusterTimestamp, long clusterPosition,
                                             long throughSequence, PendingMatching firstCandidate) {
        PendingMatching first = firstCandidate;
        while (true) {
            PendingMatching pending = first == null
                    ? owner.pendingMatching.partitionDispatchHead(shard) : first;
            first = null;
            if (pending != null && pending.sequence() <= throughSequence && pending.settlementEvent() != null
                    && pending.settlementEvent().direct() && !pending.settlementEvent().dispatched()) {
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                var event = pending.settlementEvent();
                event.commitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                owner.runtimeState.dispatchDirectMatcherSettlement(event);
                owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
                owner.pendingMatching.progressChanged();
                pending.countPipelinedSettlement();
                dispatchedSettlementInFlight++;
                dispatchedSettlementHighWaterMark = Math.max(dispatchedSettlementHighWaterMark, dispatchedSettlementInFlight);
                continue;
            }
            if (pending == null || pending.sequence() > throughSequence || pending.operation() != PendingMatching.Operation.PLACE
                    || pending.settlementEvent() != null || owner.hasPendingMatchingRejection(pending.sequence())) {
                return;
            }
            OrderBatchPending batch = pending.orderBatch;
            if (batch != null) {
                if (batch.settlementEvent != null && batch.settlementEvent.direct()
                        && !batch.settlementEvent.dispatched()) {
                    if (!batch.admissionCollected() || !batch.canPredispatch()) return;
                    pending.establishCommitFence(clusterTimestamp, clusterPosition);
                    batch.settlementEvent.commitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                    owner.runtimeState.dispatchDirectMatcherSettlement(batch.settlementEvent);
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
                    LaneCommandContextRing.Context context = owner.laneCommandContexts.required(pending.sequence());
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
            LaneCommandContextRing.Context laneContext = owner.laneCommandContexts.required(pending.sequence());
            com.surprising.aeron.service.matching.CoreMatchingResult matching = laneContext.matchingCompletion();
            if (matching == null || !matching.accepted()) return;
            matching = laneContext.takeMatchingCompletion();
            pending.establishCommitFence(clusterTimestamp, clusterPosition);
            pending.dispatchOnly();
            CoreResponse response = completeMatching(
                    pending.sequence(), matching, clusterTimestamp, clusterPosition);
            if (response != null || pending.settlementEvent() == null
                    || !owner.laneCommandContexts.required(pending.sequence()).hasCommitContext()) {
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

    void requestCommitPublication() {
        if (commitPublicationDeferred) {
            commitPublicationDirty = true;
            commitPublicationProvisionalOnly = false;
            return;
        }
        publishCommittedChanges();
    }

    void beginCommitPublicationBatch() {
        if (commitPublicationDeferred) {
            throw new IllegalStateException("snapshot projection batch is already active");
        }
        commitPublicationDeferred = true;
        commitPublicationDirty = false;
        commitPublicationProvisionalOnly = false;
    }

    void completeCommitPublicationBatch() {
        boolean dirty = commitPublicationDirty;
        boolean provisionalOnly = commitPublicationProvisionalOnly;
        commitPublicationDeferred = false;
        commitPublicationDirty = false;
        commitPublicationProvisionalOnly = false;
        if (dirty && provisionalOnly) owner.runtimeState.clearChangedKeys();
        else if (dirty) publishCommittedChanges();
    }

    void abortCommitPublicationBatch() {
        commitPublicationDeferred = false;
        commitPublicationDirty = false;
        commitPublicationProvisionalOnly = false;
    }

    void publishCommittedChanges() {
        ownerCommitPublisher.execute();
    }

    final class OwnerCommitPublisher {
        /** 发布方法重入保护，只在执行期间置为 true。 */
        boolean active;

        void execute() {
            if (active) throw new IllegalStateException("owner commit publisher is already active");
            active = true;
            try {
                long sequence = Math.incrementExact(owner.runtimeProjectionJournal.publishedSequence());
                try {
                    if (owner.currentAdmission == null) {
                        throw new IllegalStateException("runtime commit must be admitted before mutation");
                    }
                    owner.runtimeState.appendFundsDelta(owner.commandFundsAccumulator);
                    if (owner.realtimeCapture != null) owner.runtimeState.captureRealtimeChanges(owner.realtimeCapture);
                    if (owner.runtimeState.committedRevision() < runtimePatchRevision)
                        throw new IllegalStateException("runtime changed-index commit is out of order");
                    owner.factIndexes.applyCurrent(owner.runtimeState, owner.identities);
                    owner.currentAdmission.publish(sequence);
                    owner.currentProjectionPoint = new RuntimeProjectionPoint(sequence, null);
                    owner.currentProjectionPoint.completeSequence();
                    runtimePatchRevision = owner.runtimeState.committedRevision();
                    owner.runtimeState.releaseRetiredPositionIdentities(owner.identities);
                    owner.runtimeState.clearChangedKeys();
                } catch (RuntimeException failure) {
                    owner.commitPublicationFailure = new IllegalStateException(
                            "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                            failure);
                    throw failure;
                }
            } finally {
                active = false;
            }
        }
    }
}
