package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.state.RiskScanCoordinator;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.service.execution.CommandResultLedger.StoredResult;

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

    OrderedCommitCoordinator(TradingCoreRuntime owner) { this.owner = owner; }

    /** 各撮合分片已核验的序号；提交时逐分片验证连续性。 */
    long[] appliedMatcherSequences;

    /** 各撮合分片已核验的前缀摘要；与序号一起验证重放一致性。 */
    long[] appliedMatcherPrefixDigests;

    /** 已预派发且尚未收集完成的结算命令数量。 */
    int dispatchedSettlementInFlight;

    /** 结算在途数量峰值，仅用于运行观测。 */
    int dispatchedSettlementHighWaterMark;

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
        owner.resultLedger.storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence, requiredExportSequence, stateHash,
                new byte[0], 0));
        owner.removePendingMatching(sequence);
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, requiredExportSequence, stateHash, new byte[0]);
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
        if (controlContinuationSequence == sequence) {
            owner.restoreMatchingCommitContext(pending);
            try {
                if (!controlContinuation.getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                controlContinuation = null;
                controlContinuationSequence = 0;
                owner.runtimeState.completePendingReservations(sequence);
                return finishAppliedMatching(pending, matchingResult, laneContext, null, controlApplyStartNanos);
            } catch (RuntimeException failure) {
                throw owner.failMatching(pending, "control Lane continuation failed", failure);
            }
        }
        OrderBatchPending waitingBatch = owner.batches.pendingOrderBatches.get(sequence);
        if (waitingBatch != null && pending.clusterIndependent
                && waitingBatch.kind == OrderBatchKind.CANCEL && !waitingBatch.commitStarted()) {
            owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        if (waitingBatch != null && waitingBatch.hasPendingLaneWork()) {
            if (!waitingBatch.laneWorkComplete()) return null;
            if (waitingBatch.pipelined && !waitingBatch.commitStarted()) {
                owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
            }
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return owner.batches.finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.matchingApplied) {
            if (!waitingBatch.commitStarted()) owner.batches.beginPipelinedOrderBatchCommit(waitingBatch, pending);
            return owner.batches.finishOrderBatch(waitingBatch, pending, clusterTimestamp, clusterPosition);
        }
        if (pending.replaceEvent() != null) {
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedReplacePreparation(pending, matchingResult, laneContext);
        }
        if (pending.cancelEvent() != null) {
            if (laneContext.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
            return completeDispatchedCancel(pending, matchingResult, laneContext);
        }
        if (pending.settlementEvent() != null) {
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
            OrderBatchPending failedBatch = owner.batches.pendingOrderBatches.get(sequence);
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
        if (owner.batches.pendingOrderBatches.containsKey(sequence)) {
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
        owner.resultBuilder.commandOrderViews = List.of();
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
                    if (matchingResult.accepted()) {
                        boolean dispatchOnly = pending.isDispatchOnly();
                        settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                        pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                        pending.takeDispatchOnly();
                        if (settlementTreasuryDelta == null || dispatchOnly) {
                            owner.suspendMatchingCommitContext(pending);
                            return null;
                        }
                    } else {
                        applyPreMatchingCancellations(pending, matchingResult);
                        owner.rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId(), sequence);
                    }
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(settlementPlan);
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    owner.resultBuilder.commandChangedUserIds = List.of(pending.command().header().userId());
                    owner.resultBuilder.commandChangedOrderIds = List.of(command.orderId());
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
                        var preparedClientKey = owner.identities.prepareClientKey(
                                admission.userId(), admission.resolved().clientOrderId());
                        int symbolId = admission.resolved().symbolId();
                        int assetId = owner.identities.assetId(admission.resolved().reservationAsset());
                        var replaceEvent = owner.runtimeState.dispatchReplace(
                                sequence, admission.userId(), originalOrderId,
                                acceptedPreMatchingCancellationIds(pending, matchingResult),
                                admission.resolved(), pending.command().header().commandId(),
                                admission.requiredReservationUnits(), preparedClientKey.key(), symbolId, assetId,
                                clusterTimestamp, clusterPosition, owner.identities);
                        pending.replace(replaceEvent, applyStartNanos);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    } else {
                        applyPreMatchingCancellations(pending, matchingResult);
                        owner.addChangedUsers(settlementPlan);
                        owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
                    }
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = owner.runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    var command = owner.triggerPlacement(trigger, execute[2],
                            owner.responseOrder(settlementPlan.takerOrderId()));
                    owner.addChangedUsers(settlementPlan);
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(settlementPlan);
                    if (matchingResult.accepted()) {
                        settlementTreasuryDelta = owner.applyMatchesOnAccountLanes(
                                pending, settlementPlan, sequence, matchingResult, laneContext, applyStartNanos);
                        if (settlementTreasuryDelta == null) return null;
                    } else {
                        applyPreMatchingCancellations(pending, matchingResult);
                        owner.rejectPlaceOrderRuntime(trigger.userId(), command.orderId(), sequence);
                    }
                    owner.triggers.completeTriggerOrderRuntime(trigger.triggerOrderId(), matchingResult.accepted(),
                            matchingResult.accepted() ? command.orderId() : 0,
                            matchingResult.accepted() ? "" : matchingResult.resultCode(), execute[3]);
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(settlementPlan);
                    owner.resultBuilder.commandOrderViews = owner.resultBuilder.commandChangedOrderIds.stream().map(owner::runtimeOrder)
                            .filter(java.util.Objects::nonNull).map(owner::orderView).toList();
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = owner.runtimeState.liquidation(command.liquidationId());
                    TradingCoreRuntime.LifecycleOrderChunk chunk = liquidation == null ? new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0)
                            : owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(),
                            command.maxOrders());
                    owner.resultBuilder.commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
                    owner.resultBuilder.commandChangedUserIds = liquidation == null ? List.of() : List.of(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        if (!com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                                .isExecutable(owner.runtimeState, owner.identities, command)) {
                            owner.derivativeRisk.executeLiquidationRuntime(command, List.of());
                            owner.resultBuilder.commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
                        } else if (chunk.more()) {
                            owner.derivativeRisk.advanceLiquidationCancellationRuntime(
                                    command, chunk.orders(), chunk.nextCursorOrderId());
                            owner.resultBuilder.commandLiquidationProgress = new CoreLiquidationProgressView(false,
                                    chunk.nextCursorOrderId(), chunk.orders().size());
                        } else {
                            owner.derivativeRisk.executeLiquidationRuntime(command, chunk.orders());
                            owner.resultBuilder.commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
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
                        owner.instrumentSettlement.settleInstrumentRuntime(command, pending.command().header().commandId());
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
        long sequence = pending.sequence();
        long clusterTimestamp = pending.commitFenceTimestamp();
        long clusterPosition = pending.commitFenceClusterPosition();
        RuntimeProjectionPoint beforeProjection = pending.beforeProjection();
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(sequence);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        RuntimeTreasuryDelta settledTreasuryDelta = settlementTreasuryDelta;
            if (settledTreasuryDelta != null) {
                settledTreasuryDelta.apply(owner.runtimeState.treasury());
                owner.runtimeState.setMetadata(owner.productLine,
                        Math.incrementExact(owner.runtimeState.revision()));
                requestCommitPublication();
            }
        if (commitPublicationDirty) {
            owner.stampOrderChangesRuntime(clusterTimestamp, clusterPosition, owner.resultBuilder.commandChangedOrderIds);
        }
        owner.resultBuilder.materializeChangeAccumulators();
            long committedLaneMask = pending.settlementEvent() == null
                    ? owner.stageLaneMutation(sequence, owner.resultBuilder.changedUserIds.toPrimitiveArray(), laneContext)
                    : pending.settlementEvent().requiredLaneMask();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane mask differs from immutable matcher result", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) {
                owner.resultBuilder.materializeCommandOrderViews(pending);
            }
            completeCommitPublicationBatch(committedLaneMask);
        owner.validateFundsConservation(pending.command());
        if (TradingCoreRuntime.MATCHING_PHASE_METRICS_ENABLED) {
            owner.matchingPhaseMetrics.recordApply(System.nanoTime() - applyStartNanos);
            owner.completedMatchingCount++;
            if (owner.completedMatchingCount % TradingCoreRuntime.MATCHING_PHASE_LOG_INTERVAL == 0) {
                TradingCoreRuntime.LOG.log(System.Logger.Level.DEBUG, "matching phases count=" + owner.completedMatchingCount + " "
                        + owner.matchingPhaseMetrics.reportAndReset());
            }
        }
        long businessStateHash = owner.currentProjectionPoint == beforeProjection
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        long applied = sequence;
        owner.commitMatchingSequence(sequence);
        long requiredExportSequence = 0;
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = owner.stateHash(businessStateHash, pending.command().header().commandId(), status, resultCode, applied);
        byte[] responseData = owner.resultBuilder.commandResultData(pending, matchingResult);
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, owner.resultBuilder.commandTradeCount);
        owner.resultLedger.storeResult(pending.command().header().commandId(), StoredResult.owned(pending.fingerprint(),
                status, resultCode, applied, requiredExportSequence, stateHash, responseData));
        owner.runtimeState.releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.removePendingMatching(sequence);
        if (!owner.admissions.deferredMatching.isEmpty() || !owner.batches.pendingOrderBatches.isEmpty()) {
            owner.submitDeferredMatchingAfterBatch();
        }
        CoreResponse response = CoreResponse.owned(
                status, status, resultCode, applied, requiredExportSequence, stateHash, responseData);
        return owner.releaseAdmission(capacityReservation, response);
    }

    CoreResponse completeDispatchedMatcherSettlement(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        com.surprising.aeron.service.state.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
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
                    var command = owner.triggerPlacement(trigger, execute[2],
                            owner.responseOrder(pending.settlementPlan().takerOrderId()));
                    owner.triggers.completeTriggerOrderRuntime(trigger.triggerOrderId(), true, command.orderId(), "", execute[3]);
                    owner.resultBuilder.commandTradeCount = TradingCoreRuntime.tradeCount(pending.settlementPlan());
                    owner.resultBuilder.commandOrderViews = owner.resultBuilder.commandChangedOrderIds.stream().map(owner::runtimeOrder)
                            .filter(java.util.Objects::nonNull).map(owner::orderView).toList();
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
                            Math.addExact(1, pending.settlementPlan().preCancellationCount())));
            requestCommitPublication();
            owner.resultBuilder.materializeChangeAccumulators();
            long committedLaneMask = event.requiredLaneMask();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from immutable matcher fact", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) owner.resultBuilder.materializeCommandOrderViews(pending);
            completeCommitPublicationBatch(committedLaneMask);
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
        RuntimeProjectionPoint beforeProjection = pending.beforeProjection();
        long businessStateHash = owner.currentProjectionPoint == beforeProjection
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        long applied = pending.sequence();
        owner.commitMatchingSequence(applied);
        long requiredExportSequence = 0;
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = owner.stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, applied);
        byte[] responseData = owner.resultBuilder.commandResultData(pending, matchingResult);
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, owner.resultBuilder.commandTradeCount);
        owner.resultLedger.storeResult(pending.command().header().commandId(), StoredResult.owned(pending.fingerprint(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, applied, requiredExportSequence,
                stateHash, responseData));
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        if (pending.takePipelinedSettlementCounted()) {
            dispatchedSettlementInFlight--;
            if (dispatchedSettlementInFlight < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        owner.runtimeState.releaseMatcherSettlement(pending.takeSettlementEvent());
        owner.removePendingMatching(applied);
        if (!owner.admissions.deferredMatching.isEmpty() || !owner.batches.pendingOrderBatches.isEmpty()) owner.submitDeferredMatchingAfterBatch();
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, applied, requiredExportSequence, stateHash, responseData);
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
                    Math.incrementExact(owner.runtimeState.revision()));
            requestCommitPublication();
            owner.resultBuilder.materializeChangeAccumulators();
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from cancel fact", null);
            }
            requireCompleteAccountLanes(laneContext);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) owner.resultBuilder.materializeCommandOrderViews(pending);
            completeCommitPublicationBatch(event.requiredLaneMask());
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
        RuntimeProjectionPoint beforeProjection = pending.beforeProjection();
        long businessStateHash = owner.currentProjectionPoint == beforeProjection
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        long applied = pending.sequence();
        owner.commitMatchingSequence(applied);
        long requiredExportSequence = 0;
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = owner.stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, applied);
        byte[] responseData = owner.resultBuilder.commandResultData(pending, matchingResult);
        owner.resultLedger.storeResult(pending.command().header().commandId(), StoredResult.owned(pending.fingerprint(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, applied, requiredExportSequence,
                stateHash, responseData));
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(applied);
        owner.runtimeState.releaseCancel(pending.takeCancelEvent());
        owner.removePendingMatching(applied);
        if (!owner.admissions.deferredMatching.isEmpty() || !owner.batches.pendingOrderBatches.isEmpty()) owner.submitDeferredMatchingAfterBatch();
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, applied, requiredExportSequence, stateHash, responseData);
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
                    com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                            pending.sequence(), admission.command().orderId(), admission.userId(),
                            new long[]{admission.originalOrderId(), admission.command().orderId()}, matchingResult,
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
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                        pending.sequence(), orderId, userId, new long[]{orderId}, result,
                        owner.runtimeState, owner.identities)
                        .preCancellations(acceptedPreMatchingCancellationIds(pending, result));
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                if (result.accepted()) yield null;
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.empty(
                        pending.sequence(), userId,
                        new long[]{admission.originalOrderId(), admission.command().orderId()},
                        owner.runtimeState);
            }
            case TRIGGER -> {
                long[] execute = pending.decodedCommand().trigger();
                var trigger = owner.runtimeState.triggerOrder(execute[0]);
                if (trigger == null) yield null;
                long orderId = owner.triggerPlacement(trigger, execute[2]).orderId();
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                        pending.sequence(), orderId, trigger.userId(), new long[]{orderId}, result,
                        owner.runtimeState, owner.identities)
                        .preCancellations(acceptedPreMatchingCancellationIds(pending, result));
            }
            default -> null;
        };
    }

    void requireCompleteAccountLanes(LaneCommandContextRing.Context context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }

    /** 控制命令窗口只有一个在途命令；无需另建按序号索引或任务队列。 */
    private java.util.function.BooleanSupplier controlContinuation;
    /** 跨回调保留的原始撮合序号及开始时刻。 */
    private long controlContinuationSequence, controlApplyStartNanos;

    boolean controlPending(long sequence) { return controlContinuation != null && controlContinuationSequence == sequence; }

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
        int remaining = batch.maxCancelOrders();
        int applied = 0;
        int pending = 0;
        int obsolete = 0;
        int processedOrders = 0;
        List<Long> changedOrders = new ArrayList<>(owner.resultBuilder.commandChangedOrderIds);
        List<Long> changedUsers = new ArrayList<>(owner.resultBuilder.commandChangedUserIds);
        for (var action : batch.actions()) {
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
            TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            changedOrders.addAll(chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList());
            changedUsers.add(liquidation.userId());
            if (!com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                    .isExecutable(owner.runtimeState, owner.identities, single)) {
                owner.derivativeRisk.executeLiquidationRuntime(single, List.of());
                applied++;
                continue;
            }
            processedOrders += chunk.orders().size();
            remaining -= chunk.orders().size();
            if (chunk.more()) {
                owner.derivativeRisk.advanceLiquidationCancellationRuntime(single, chunk.orders(), chunk.nextCursorOrderId());
                pending++;
                continue;
            }
            owner.derivativeRisk.executeLiquidationRuntime(single, chunk.orders());
            applied++;
        }
        owner.resultBuilder.commandChangedOrderIds = changedOrders.stream().distinct().toList();
        owner.resultBuilder.commandChangedUserIds = changedUsers.stream().distinct().toList();
        owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied, pending,
                obsolete, processedOrders, 0);
        if (batch.riskScanContinuation() != null) {
            var scan = owner.runtimeState.firstRiskIncompleteScan();
            var continuation = batch.riskScanContinuation();
            if (scan != null
                    && owner.identities.symbol(scan.symbolId()).equals(continuation.symbol())
                    && scan.priceSequence() == continuation.priceSequence()
                    && scan.lastUserId() == continuation.lastUserId()) {
                long beforeRevision = owner.runtimeState.revision();
                if (owner.runtimeState.asynchronousCommands()) {
                    var work = new RiskScanCoordinator(batch.maxRiskScanUsers(),
                            owner.positionUserIndex, owner.runtimeState, owner.identities);
                    var result = owner.resultBuilder.commandLiquidationBatchResult;
                    deferControl(() -> {
                        if (!work.poll()) return false;
                        if (!owner.runtimeState.tryAcquireOwnerLaneAccess()) return false;
                        if (work.completedWork() > 0) owner.runtimeState.acceptChangedUserIds(userId ->
                                laneContext.includeControlLanes(owner.matchingAdapter.topology().accountLaneMask(userId),
                                        validAccountLaneMask()));
                        if (owner.runtimeState.revision() != beforeRevision) requestCommitPublication();
                        owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(
                                batch.actions().size(), result.appliedActions(), result.pendingActions(),
                                result.obsoleteActions(), result.processedOrders(), work.completedWork());
                        return true;
                    });
                    return;
                }
                int riskWork = RuntimeDerivativeRiskProcessor.continueRiskBudget(batch.maxRiskScanUsers(),
                        owner.positionUserIndex, owner.runtimeState, owner.identities);
                if (riskWork > 0) {
                    // These synchronous control mutations are additional to immutable matcher fanout.
                    owner.runtimeState.acceptChangedUserIds(userId -> laneContext.includeControlLanes(
                            owner.matchingAdapter.topology().accountLaneMask(userId), validAccountLaneMask()));
                }
                if (owner.runtimeState.revision() != beforeRevision) requestCommitPublication();
                owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied,
                        pending, obsolete, processedOrders, riskWork);
            }
        }
    }

    PendingMatching applyBatchSuccessfulPrefix(PendingMatching pending,
                                                        com.surprising.aeron.service.matching.CoreMatchingResult result,
                                                        long clusterTimestamp, long clusterPosition) {
        var batch = pending.decodedCommand().liquidationBatch();
        int successful = result.successfulPrefixCount();
        int remaining = batch.maxCancelOrders();
        int successLeft = successful;
        List<ExecuteLiquidationBatchAction> nextActions = new ArrayList<>(batch.actions());
        List<Long> changedOrders = new ArrayList<>();
        List<Long> changedUsers = new ArrayList<>();
        int actionIndex = 0;
        for (var action : batch.actions()) {
            if (remaining == 0 || successLeft == 0) break;
            var liquidation = owner.runtimeState.liquidation(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) {
                actionIndex++;
                continue;
            }
            TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            int count = Math.min(successLeft, chunk.orders().size());
            if (count == 0) break;
            List<CoreOrderState> prefix = chunk.orders().subList(0, count);
            changedOrders.addAll(prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList());
            changedUsers.add(liquidation.userId());
            remaining -= count;
            successLeft -= count;
            ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                    action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                    action.cursorOrderId(), Math.min(remaining + count, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
            if (chunk.more() || count < chunk.orders().size()) {
                long nextCursor = count < chunk.orders().size()
                        ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
                owner.derivativeRisk.advanceLiquidationCancellationRuntime(single, prefix, nextCursor);
            } else {
                owner.derivativeRisk.executeLiquidationRuntime(single, prefix);
            }
            var next = owner.runtimeState.liquidation(action.liquidationId());
            long nextCursor = next != null && next.status() == CoreLiquidationState.Status.ORDERED
                    ? next.nextCancelOrderId() : action.cursorOrderId();
            nextActions.set(actionIndex, new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                    action.symbol(), action.instrumentChangeId(), action.triggerPriceSequence(),
                    action.executionPriceTicks(), nextCursor));
            actionIndex++;
        }
        owner.resultBuilder.commandChangedOrderIds = changedOrders.stream().distinct().toList();
        owner.resultBuilder.commandChangedUserIds = changedUsers.stream().distinct().toList();
        if (!nextActions.equals(batch.actions())) {
            var nextBatch = new ExecuteLiquidationBatchCommand(nextActions, batch.maxCancelOrders(),
                    batch.liquidationFeeRatePpm(), batch.riskScanContinuation(), batch.maxRiskScanUsers());
            pending = pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeExecuteLiquidationBatch(nextBatch)));
        }
        return pending;
    }

    PendingMatching applySuccessfulCancellationPrefix(PendingMatching pending,
                                                               com.surprising.aeron.service.matching.CoreMatchingResult result) {
        return switch (pending.operation()) {
            case LIQUIDATION_BATCH -> applyBatchSuccessfulPrefix(pending, result, 0, 0);
            case LIQUIDATION -> applyLiquidationSuccessfulPrefix(pending, result);
            case SETTLEMENT -> applySettlementSuccessfulPrefix(pending, result);
            default -> pending;
        };
    }

    PendingMatching applyLiquidationSuccessfulPrefix(PendingMatching pending,
                                                              com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var command = pending.decodedCommand().liquidation();
        var liquidation = owner.runtimeState.liquidation(command.liquidationId());
        if (liquidation == null || !com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                .isExecutable(owner.runtimeState, owner.identities, command)) return pending;
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                command.cursorOrderId(), command.maxOrders());
        int count = Math.min(result.successfulPrefixCount(), chunk.orders().size());
        if (count == 0) return pending;
        List<CoreOrderState> prefix = chunk.orders().subList(0, count);
        owner.resultBuilder.commandChangedUserIds = List.of(liquidation.userId());
        owner.resultBuilder.commandChangedOrderIds = prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        if (chunk.more() || count < chunk.orders().size()) {
            long nextCursor = count < chunk.orders().size() ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
            owner.derivativeRisk.advanceLiquidationCancellationRuntime(command, prefix, nextCursor);
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            command.liquidationId(), command.triggerPriceSequence(), command.executionPriceTicks(),
                            command.liquidationFeeRatePpm(), nextCursor, command.maxOrders()))));
        }
        owner.derivativeRisk.executeLiquidationRuntime(command, prefix);
        return pending;
    }

    PendingMatching applySettlementSuccessfulPrefix(PendingMatching pending,
                                                              com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var command = pending.decodedCommand().settlement();
        var progress = owner.runtimeLifecycleProgress(command.symbol());
        if (progress != null && progress.ordersComplete()) {
            return pending;
        }
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        int count = Math.min(result.successfulPrefixCount(), chunk.orders().size());
        if (count == 0) return pending;
        List<CoreOrderState> prefix = chunk.orders().subList(0, count);
        owner.resultBuilder.commandChangedUserIds = prefix.stream().map(CoreOrderState::userId).distinct().toList();
        owner.resultBuilder.commandChangedOrderIds = prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        if (chunk.more() || count < chunk.orders().size()) {
            long nextCursor = count < chunk.orders().size() ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
            owner.instrumentSettlement.advanceSettlementCancellationRuntime(
                    command, prefix, nextCursor, pending.command().header().commandId());
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeSettleInstrument(new com.surprising.aeron.protocol.SettleInstrumentCommand(
                            command.settlementId(), command.symbol(), command.instrumentChangeId(),
                            command.settlementPriceTicks(), command.optionCashUnitsPerContract(), command.cursorUserId(),
                            command.maxUsers(), nextCursor, command.maxOrders()))));
        }
        owner.instrumentSettlement.settleInstrumentRuntime(command, pending.command().header().commandId());
        return pending;
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
            java.util.Set<Long> expected = java.util.Set.copyOf(pending.preMatchingCancellationOrderIds());
            boolean knownPrefix = !expected.isEmpty() && result.matcherEvents().stream()
                    .noneMatch(event -> event.eventType() == MatcherEventType.TRADE)
                    && result.cancellations().stream().filter(CoreCancellationResult::accepted)
                    .map(CoreCancellationResult::orderId).allMatch(expected::contains);
            if (!knownPrefix) return true;
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT) return true;
        return result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.FATAL_DIVERGENCE;
    }

    void applyPreMatchingCancellations(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (pending.preMatchingCancellationOrderIds().isEmpty()) return;
        java.util.Set<Long> expected = java.util.Set.copyOf(pending.preMatchingCancellationOrderIds());
        LinkedHashSet<Long> changedOrders = new LinkedHashSet<>(owner.resultBuilder.commandChangedOrderIds);
        LinkedHashSet<Long> changedUsers = new LinkedHashSet<>(owner.resultBuilder.commandChangedUserIds);
        for (CoreCancellationResult cancellation : result.cancellations()) {
            if (!cancellation.accepted() || !expected.contains(cancellation.orderId())) continue;
            OrderRuntime order = owner.runtimeOrder(cancellation.orderId());
            if (order == null || order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                continue;
            }
            owner.cancelOrderRuntime(order.userId(), order.orderId());
            changedOrders.add(order.orderId());
            changedUsers.add(order.userId());
        }
        owner.resultBuilder.commandChangedOrderIds = List.copyOf(changedOrders);
        owner.resultBuilder.commandChangedUserIds = List.copyOf(changedUsers);
    }

    static long[] acceptedPreMatchingCancellationIds(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        List<Long> expected = pending.preMatchingCancellationOrderIds();
        if (expected.isEmpty()) return TradingCoreRuntime.EMPTY_ORDER_IDS;
        long[] accepted = new long[expected.size()];
        int count = 0;
        for (Long orderId : expected) {
            if (orderId == null) continue;
            for (CoreCancellationResult cancellation : result.cancellations()) {
                if (cancellation.accepted() && cancellation.orderId() == orderId.longValue()) {
                    accepted[count++] = orderId.longValue();
                    break;
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

    void pumpMatchingCommitCompletions(long clusterTimestamp, long clusterPosition, long throughSequence) {
        long first = owner.pendingMatching.firstSequence();
        if (first > throughSequence) return;
        OrderBatchPending head = owner.batches.pendingOrderBatches.get(first);
        if (head != null && !head.started) {
            owner.batches.activateOrderBatch(head, owner.pendingMatching.get(first), true);
        }
        owner.drainMatchingCompletions();
        if (controlPending(first)) signalPendingMatchingReady(first);
        dispatchReadyPlaceSettlements(clusterTimestamp, clusterPosition, throughSequence);
    }

    boolean matchingCommitReady(PendingMatching pending) {
        if (pending == null) return false;
        OrderBatchPending batch = owner.batches.pendingOrderBatches.get(pending.sequenceKey());
        // A matcher result alone does not make a batch ready while its Lane work is outstanding.
        if (batch != null && !batch.laneWorkComplete()) return false;
        if (batch != null && batch.placeBatchAdmissionEvent != null
                && (!batch.placeBatchAdmissionEvent.complete() || !pending.isMatchingSubmitted())) return false;
        if (owner.hasPendingMatchingRejection(pending.sequence())) return true;
        if (pending.settlementEvent() != null || pending.cancelEvent() != null || pending.replaceEvent() != null) {
            return pending.settlementReady();
        }
        LaneCommandContextRing.Context context = owner.laneCommandContexts.required(pending.sequence());
        return context.hasMatchingCompletion() || context.matchingResult() != null;
    }

    void signalPendingMatchingReady(long sequence) {
        owner.pendingMatching.markReady(sequence);
    }

    void drainMatcherSettlementCompletions() {
        long readyLaneMask = owner.runtimeState.takeMatcherSettlementReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = owner.runtimeState.pollMatcherSettlementReady(laneId)) != 0) {
                owner.matchingProgressSequence++;
                PendingMatching pending = owner.pendingMatching.get(sequence);
                if (pending == null) continue;
                if (pending.settlementEvent() != null && !pending.settlementEvent().complete()) continue;
                // Batch items can collect their Lane event inline before this notification
                // is drained. Their continuation belongs to OrderBatchPending, not PendingMatching.
                if (!owner.batches.pendingOrderBatches.containsKey(sequence)) pending.markSettlementReady();
                signalPendingMatchingReady(sequence);
            }
        }
    }

    PendingMatching pollReadyPending() {
        PendingMatching pending = owner.pendingMatching.pollReadyHead();
        return matchingCommitReady(pending) ? pending : null;
    }

    PendingMatching awaitAnyMatchingCommitReady(
            long timeoutNanos, long clusterTimestamp, long clusterPosition, long throughSequence) {
        long deadline = System.nanoTime() + timeoutNanos;
        int idle = 0;
        while (!owner.pendingMatching.isEmpty() && owner.pendingMatching.firstSequence() <= throughSequence) {
            pumpMatchingCommitCompletions(clusterTimestamp, clusterPosition, throughSequence);
            PendingMatching ready = pollReadyPending();
            if (ready != null) return ready;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            if (idle++ < 1_024) {
                Thread.onSpinWait();
            } else {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        owner, Math.min(remaining, 1_000L));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("matching completion wait was interrupted");
                }
            }
        }
        return null;
    }

    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst, Long.MAX_VALUE, handler);
    }

    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, TradingCoreRuntime.MatchingCommitHandler handler) {
        owner.assertOwner();
        owner.assertHealthy();
        if (maxCompletions <= 0 || handler == null) {
            throw new IllegalArgumentException("matching commit batch requires a positive limit and handler");
        }
        owner.beginDownstreamPublicationBatch();
        try {
            pumpMatchingCommitCompletions(clusterTimestamp, clusterPosition, throughSequence);
            int completed = 0;
            int attempts = 0;
            while (attempts < maxCompletions) {
                if (owner.pendingMatching.firstSequence() > throughSequence) break;
                PendingMatching pending = pollReadyPending();
                if (pending == null && attempts == 0 && awaitFirst) {
                    pending = awaitAnyMatchingCommitReady(
                            TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS, clusterTimestamp, clusterPosition, throughSequence);
                }
                if (pending == null) break;
                long sequence = pending.sequence();
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                CoreResponse response;
                if (owner.hasPendingMatchingRejection(sequence)) {
                    response = completeRejectedMatching(sequence);
                } else {
                    LaneCommandContextRing.Context context = owner.laneCommandContexts.required(sequence);
                    com.surprising.aeron.service.matching.CoreMatchingResult matching = context.matchingResult();
                    if (matching == null && pending.settlementEvent() == null && pending.cancelEvent() == null
                            && pending.replaceEvent() == null) {
                        matching = context.takeMatchingCompletion();
                    }
                    if (matching == null) break;
                    response = completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
                    if (response == null && awaitFirst) {
                        long deadline = System.nanoTime() + TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS;
                        int idle = 0;
                        while (response == null && System.nanoTime() < deadline) {
                            pumpMatchingCommitCompletions(clusterTimestamp, clusterPosition, throughSequence);
                            if (!matchingCommitReady(pending)) {
                                if (idle++ < 1_024) Thread.onSpinWait();
                                else java.util.concurrent.locks.LockSupport.parkNanos(owner, 1_000L);
                                continue;
                            }
                            com.surprising.aeron.service.matching.CoreMatchingResult readyMatching =
                                    context.matchingResult();
                            if (readyMatching == null && pending.settlementEvent() == null
                                    && pending.cancelEvent() == null && pending.replaceEvent() == null) {
                                readyMatching = context.takeMatchingCompletion();
                            }
                            if (readyMatching == null) continue;
                            matching = readyMatching;
                            response = completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
                        }
                    }
                }
                attempts++;
                owner.matchingProgressSequence++;
                if (response == null) {
                    pumpMatchingCommitCompletions(clusterTimestamp, clusterPosition, throughSequence);
                    continue;
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
        while (true) {
            PendingMatching pending = owner.pendingMatching.dispatchHead();
            if (pending == null || pending.sequence() > throughSequence || pending.operation() != PendingMatching.Operation.PLACE
                    || pending.settlementEvent() != null || owner.hasPendingMatchingRejection(pending.sequence())) {
                return;
            }
            OrderBatchPending batch = owner.batches.pendingOrderBatches.get(pending.sequenceKey());
            if (batch != null) {
                // The ordinary commit loop can reach this batch after completing a cancel
                // without another dispatch pass. Once it owns the commit, it also owns Lane
                // dispatch; pumping while its event is pending must not submit it a second time.
                if (!batch.pipelined || !batch.admissionCollected || !batch.canPredispatch()) return;
                if (!batch.matchingApplied) {
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
                    batch.matcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                            matching.nativeCommand().matcherSequence() - 1, matching.matcherPrefix().before());
                    owner.batches.applyPipelinedPlaceBatchResults(batch, pending, matching);
                    owner.batches.initializeOrderBatchLaneContext(batch, pending);
                }
                owner.batches.dispatchOrderBatchLaneWork(batch, pending,
                        pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
                batch.markPredispatched();
                owner.matchingProgressSequence++;
                owner.pendingMatching.completeDispatch(pending.sequence());
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
            owner.pendingMatching.completeDispatch(pending.sequence());
            owner.matchingProgressSequence++;
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
        completeCommitPublicationBatch(0);
    }

    void completeCommitPublicationBatch(long committedLaneMask) {
        boolean dirty = commitPublicationDirty;
        boolean provisionalOnly = commitPublicationProvisionalOnly;
        commitPublicationDeferred = false;
        commitPublicationDirty = false;
        commitPublicationProvisionalOnly = false;
        if (dirty && provisionalOnly) owner.runtimeState.clearChangedKeys();
        else if (dirty) publishCommittedChanges(committedLaneMask);
    }

    void abortCommitPublicationBatch() {
        commitPublicationDeferred = false;
        commitPublicationDirty = false;
        commitPublicationProvisionalOnly = false;
    }

    void publishCommittedChanges() {
        publishCommittedChanges(0);
    }

    void publishCommittedChanges(long committedLaneMask) {
        ownerCommitPublisher.execute(committedLaneMask);
    }

    final class OwnerCommitPublisher {
        /** 发布方法重入保护，只在执行期间置为 true。 */
        boolean active;

        void execute(long committedLaneMask) {
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
