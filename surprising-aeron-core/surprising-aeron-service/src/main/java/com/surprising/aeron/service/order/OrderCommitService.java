package com.surprising.aeron.service.order;

import com.surprising.aeron.service.liquidation.LiquidationService;
import com.surprising.aeron.service.settlement.ContractExpirySettlementService;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlement;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

public final class OrderCommitService {
    /** 唯一所属交易 Owner，串行访问命令槽和发布边界。借用本提交边界所属的 Owner 运行时。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final TradingCoreRuntime owner;

    /** 保存所属 Owner 并创建同一运行时的发布边界，不另建账户或账本状态。 */
    public OrderCommitService(TradingCoreRuntime owner) {
        this.owner = owner;
        this.publication = new Publication(owner);
    }

    long[] appliedMatcherSequences;

    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int dispatchedSettlementInFlight;

    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int dispatchedSettlementHighWaterMark;

    /** 同一 Owner 使用的索引及变化发布边界，不另存账户事实。 */
    public final Publication publication;

    /** 只由 Owner 收尾仍在途的拒单。保存幂等拒绝，必要时校验并推进尚未应用的 Matcher 证据，最后回收事件、退休槽并结束事实作用域。 */
    public CoreResponse completeRejectedMatching(long sequence) {
        owner.assertOwner();
        OrderCommand pending = owner.pendingMatching().get(sequence);
        if (pending == null || !owner.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
        var direct = pending.settlementEvent();
        if (pending.isMatchingSubmitted()) {
            // 账户拒绝可能与 Matcher 准入门闩并发到达。
            // 等在途 Matcher 令牌发布结束后再回收槽，防止工作线程写进下一代命令。
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
            // 回收直达事件前推进 Owner 的该分片水位，使在线状态与恢复后的游标一致。
            if (direct.directResultAvailable()) {
                OrderMatchingResult matcherResult = direct.directResult();
                int shard = matcherResult.nativeMatcherShardId();
                if (matcherSequence(shard) != matcherResult.nativeMatcherSequence()) {
                    validateMatchingEvidence(pending, matcherResult);
                    applyMatcherProgress(matcherResult);
                }
            }
            owner.runtimeState().discardMatcherSettlement(pending.takeSettlementEvent());
        } else if (pending.isMatchingSubmitted()) {
            OrderMatchingResult matcherResult = pending.matchingResult();
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

    /** 要求 Owner 身份，按完整 Core 序号找到槽后进入同一完成入口。 */
    public CoreResponse completeMatching(long sequence,
                                  OrderMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        return completeMatching(owner.pendingMatching().get(sequence), matchingResult, clusterTimestamp, clusterPosition);
    }

    /** 检查 Lane 健康和证据可恢复性，收下单准入，等待财务、Matcher 路由及账户完成。按原提交边界恢复控制或批次续步。不能协调的结果按故障恢复处理。 */
    CoreResponse completeMatching(OrderCommand pending,
                                  OrderMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        if (pending == null || matchingResult == null) return null;
        long sequence = pending.sequence();
        OrderBatchProcessor.PendingBatch waitingBatch = pending.orderBatch() != null ? pending.orderBatch() : owner.batches().batch(sequence);
        try {
            owner.runtimeState().assertAccountLanesHealthy();
        } catch (RuntimeException failure) {
            if (waitingBatch != null) throw owner.batches().failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required", failure);
        }
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchProcessor.PendingBatch failedBatch = waitingBatch != null ? waitingBatch : owner.batches().batch(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure();
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (failedBatch != null) throw owner.batches().failOrderBatch(failedBatch, pending, detail, failure);
            owner.matchingAdapter().poisonFromOwner("unreconciled matcher outcome sequence=" + sequence
                    + " result=" + matchingResult.resultCode());
            throw owner.failMatching(pending, detail, failure);
        }
        if (pending.operation() == OrderCommand.Operation.PLACE && pending.placeAdmission() != null) {
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
        // 生产者仍持有槽时不能回收，传输槽退休另有完成边界。
        if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                && pending.submittedMatcherShard() != -1) return null;
        if (pending.controlPending()) {
            owner.restoreMatchingCommitContext(pending);
            try {
                if (pending.controlWork() != null && !pending.controlWork().getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                pending.controlWork(null);
                if (!pending.controlAccountsCommitted() && pending.commitEvent() == null) {
                    owner.runtimeState().pendingReservations().completePendingReservations(sequence);
                    owner.resultBuilder.materializeChangeAccumulators();
                    for (long userId : owner.resultBuilder.commandChangedUserIds)
                        pending.includeControlLanes(owner.matchingAdapter().topology().accountLaneMask(userId),
                                validAccountLaneMask());
                    pending.commitEvent(owner.runtimeState().dispatchLaneMutation(sequence,
                            owner.resultBuilder.commandChangedUserIds,
                            publication.dirty() ? owner.runtimeState().metadataOrderUsers(owner.resultBuilder.commandChangedOrderIds) : null, null,
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
            OrderBatchProcessor.PlaceBatchAdmissionEvent admission = waitingBatch.placeBatchAdmissionEvent();
            boolean preparedCancel = pending.clusterIndependent() && waitingBatch.kind() == OrderBatchProcessor.Kind.CANCEL;
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
                && waitingBatch.kind() == OrderBatchProcessor.Kind.CANCEL && !waitingBatch.commitStarted()) {
            owner.batches().beginPipelinedOrderBatchCommit(waitingBatch, pending);
        }
        // 顺序批次子项已持有预备直达事件，Lane 可能先于 Owner 保存 Matcher 事实而完成。
        // 恢复挂起上下文后沿正常批次入口应用一次。
        if (waitingBatch != null && waitingBatch.itemSettlementEvent() != null
                && waitingBatch.itemSettlementEvent().direct()
                && waitingBatch.lastMatchingResult() == null) {
            if (!waitingBatch.itemSettlementEvent().complete()) {
                waitingBatch.lastMatchingResult(matchingResult);
                return null;
            }
            OrderMatchingResult directResult = waitingBatch.itemSettlementEvent().directResult();
            validateMatchingEvidence(pending, directResult);
            applyMatcherProgress(directResult);
            return owner.batches().completeOrderBatchMatching(
                    sequence, directResult,
                    clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemSettlementEvent() != null) {
            if (waitingBatch.itemSettlementEvent().direct()) {
                OrderMatchingResult directResult = waitingBatch.itemSettlementEvent().directResult();
                validateMatchingEvidence(pending, directResult);
                applyMatcherProgress(directResult);
                waitingBatch.lastMatchingResult(directResult);
                if (waitingBatch.kind() == OrderBatchProcessor.Kind.CANCEL) {
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
            // 拒单没有结算变更，不能开启或恢复发布批次。
            // Matcher/Lane 可先于 owner 完成，拒单也必须走同一个有序终态边界。
            if (pending.operation() == OrderCommand.Operation.PLACE && pending.placeAdmission() != null) {
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
            OrderBatchProcessor.PendingBatch failedBatch = owner.batches().batch(sequence);
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
                    ActiveOrderIndex.LifecycleOrderChunk chunk = !executable ? new ActiveOrderIndex.LifecycleOrderChunk(List.of(), 0)
                            : owner.activeOrderIndex().lifecycleOrders(liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                            command.cursorOrderId(),
                            command.maxOrders());
                    owner.resultBuilder.addLifecycleChanges(chunk.orders());
                    if (liquidation == null) owner.resultBuilder.commandChangedUserIds = List.of();
                    else owner.resultBuilder.setSingleChangedUser(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                        var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                        var progress = new CoreLiquidationProgressView(nextCursor == 0, nextCursor,
                                chunk.orders().size());
                        var work = com.surprising.aeron.service.liquidation.LiquidationService
                                 .beginExecution(pending.liquidationWork(), command, orders, nextCursor,
                                        owner.runtimeState(), owner.identities());
                        pending.liquidationWork(work);
                        pending.deferLiquidationExecutionControl(this, work, progress);
                    }
                }
                case LIQUIDATION_BATCH -> {
                    var command = pending.decodedCommand().liquidationBatch();
                    applyLiquidationBatch(command, matchingResult, pending);
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    if (matchingResult.accepted()) {
                        applySettlementChangedIds(command);
                        var work = com.surprising.aeron.service.settlement.ContractExpirySettlementService.prepare(
                                pending.settlementWork(), command,
                                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()),
                                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
                        pending.settlementWork(work);
                        pending.deferSettlementControl(this, work);
                    }
                }
            }
            if (pending.controlWork() != null) {
                pending.controlPending(true);
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState().pendingReservations().completePendingReservations(pending.sequence());
        } catch (CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        }
        return finishAppliedMatching(pending, matchingResult, false);
    }

    /** 核对序号可提交，编码或使用已有响应切片，累计成交数并保存幂等终态，随后移交构建器及可选 Lane 响应租约。 */
    private CoreResponse storeTerminalResponse(OrderCommand pending, OrderMatchingResult matchingResult,
            ResponseStatus status, CoreResultCode resultCode) {
        long applied = pending.sequence();
        owner.matchingFlow.commitMatchingSequence(applied);
        boolean builderEncoded = pending.resultData() == null;
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

    /** 完成发布、检查资金守恒、关闭资金派发顺序边界并保存终态。 */
    private CoreResponse finishTerminalMatching(OrderCommand pending, OrderMatchingResult matchingResult) {
        publication.complete();
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
        // 先结束当前命令事实作用域，再恢复后继批次的挂起上下文。
        owner.finishFactContext(response);
        if (owner.pendingMatching().hasDeferred() || owner.batches().hasPendingBatches()) {
            owner.matchingProgress.submitDeferredMatchingAfterBatch();
        }
        return response;
    }

    private CoreResponse finishAppliedMatching(OrderCommand pending, OrderMatchingResult matchingResult,
            boolean lanesCommitted) {
        long sequence = pending.sequence();
        long clusterTimestamp = pending.commitFenceTimestamp();
        long clusterPosition = pending.commitFenceClusterPosition();
        if (!lanesCommitted && pending.settlementEvent() == null) {
            owner.resultBuilder.materializeChangeAccumulators();
            pending.commitEvent(owner.runtimeState().dispatchLaneMutation(sequence,
                    owner.resultBuilder.commandChangedUserIds,
                    publication.dirty() ? owner.runtimeState().metadataOrderUsers(owner.resultBuilder.commandChangedOrderIds) : null, null,
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
        owner.resultBuilder.materializeChangeAccumulators();
        if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()) {
            owner.resultBuilder.materializeCommandOrderViews(pending);
        }
        if ((pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT)) {
            pending.controlAccountsCommitted(true);
            owner.runtimeState().validateControlFunds(false);
        }
        return finishTerminalMatching(pending, matchingResult);
    }

    /** 直达事件完整后收集资金和实体变化，标记 Lane 完成并按操作整理成交与触发结果，接入 Treasury、推进既有修订并物化视图。收集失败转为需要恢复的撮合故障。 */
    CoreResponse completeDispatchedMatcherSettlement(
            OrderCommand pending,
            OrderMatchingResult matchingResult) {
        com.surprising.aeron.service.settlement.TradeSettlement event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
        owner.captureRealtimeTrades(pending);
        try {
            owner.runtimeState().collectMatcherSettlement(
                    event, owner.terminalRetention(),
                    owner.resultBuilder.changedUserIds(), owner.resultBuilder.changedOrderIds());
            owner.resultBuilder.markLaneCommitIdsSeeded();
            pending.completeLanes(event.requiredLaneMask());
            switch (pending.operation()) {
                case CANCEL -> { }
                case PLACE -> {
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case REPLACE, AMEND -> {
                    owner.requireMatchingAdmission(pending);
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case TRIGGER -> {
                    var trigger = pending.sourceTrigger();
                    if (trigger == null) {
                        throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
                    }
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                default -> throw new IllegalStateException(
                        "operation cannot own an asynchronous matcher settlement: " + pending.operation());
            }
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
            if (pending.completedLaneMask() != pending.expectedLaneMask()) {
                throw owner.failMatching(pending, "account lane completion differs from immutable matcher fact", null);
            }
            requireCompleteAccountLanes(pending);
            if (!owner.resultBuilder.commandChangedOrderIds.isEmpty()
                    || pending.operation() == OrderCommand.Operation.CANCEL
                    || pending.operation() == OrderCommand.Operation.REPLACE
                    || pending.operation() == OrderCommand.Operation.AMEND)
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

    /** 从请求用户、成交 maker、成功或失败撤单所对应的现有路由和强平动作确定初始参与账户位图。结算操作人不代表持仓所有者，生命周期实际用户由控制业务完成后补入。 */
    long expectedLaneMask(
            OrderCommand pending,
            OrderMatchingResult result) {
        long mask = 0;
        long activeUserId = pending.command().header().userId();
        // 品种结算改变的是实际持仓和订单所有者的账户，操作人不因此成为参与账户。
        if (activeUserId > 0 && pending.operation() != OrderCommand.Operation.SETTLEMENT)
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
            OrderCancelResult cancellation = cancellations.get(index);
            var order = owner.activeOrderIndex().activeOrderRoute(cancellation.orderId());
            if (order != null) mask |= owner.matchingAdapter().topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH) {
            for (var action : pending.decodedCommand().liquidationBatch().actions()) {
                mask |= owner.matchingAdapter().topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == OrderCommand.Operation.LIQUIDATION) {
            var liquidation = pending.liquidationCheck().liquidation(0);
            if (liquidation != null) mask |= owner.matchingAdapter().topology().accountLaneMask(liquidation.userId());
        }
        // 生命周期实际用户由既有账本和账户工作选出，再补入控制 Lane 位图，不能提前猜测扫描业务索引。
        return mask;
    }

    /** 按账户 Lane 数返回全部合法位。六十四个 Lane 用全一位图，避免位移回绕。 */
    public long validAccountLaneMask() {
        int count = owner.matchingAdapter().topology().accountLaneCount();
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    /** 要求槽已满足它登记的全部账户完成条件，否则抛错。 */
    public void requireCompleteAccountLanes(OrderCommand context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }

    /** 字节租约在终态移交前仍归原构建器。 */
    private void retainControlResponse(OrderCommand pending, OrderMatchingResult matchingResult) {
        if (pending.resultData() == null && (owner.resultBuilder.commandRiskScanControl != null
                || owner.resultBuilder.commandLiquidationProgress != null
                || owner.resultBuilder.commandLiquidationBatchResult != null)) {
            pending.resultData(owner.resultBuilder.commandResultData(pending, matchingResult));
        }
    }

    /** 按完整序号检查槽池仍被占用，再读取控制挂起标记。 */
    public boolean controlPending(long sequence) {
        if (!owner.pendingMatching().claimed(sequence)) return false;
        OrderCommand slot = owner.pendingMatching().required(sequence);
        return slot.controlPending();
    }

    /** 先登记全部动作用户以完成 Lane 回执。 */
    void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            OrderMatchingResult matchingResult,
            OrderCommand pending) {
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
            RiskScanner riskWork;
            long beforeRiskRevision;
            /** 是否已经判断并启动本批风险续扫，避免重复准备。 */
            boolean riskStarted;

            /** 先收上次账户工作，再逐动作跳过失效计划、选择撤单页和准备强平执行。有异步账户批次就让出 Owner。 */
            @Override public boolean getAsBoolean() {
                if (accountWork != null) {
                    if (!accountWork.getAsBoolean()) return false;
                    accountWork = null;
                    publication.request();
                }
                List<com.surprising.aeron.service.liquidation.LiquidationService.ExecutionRequest>
                        executionRequests = index < batch.actions().size() ? new ArrayList<>() : List.of();
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
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    boolean executable;
                    try {
                        executable = pending.liquidationCheck().executable(index - 1);
                    } catch (CoreStateRejectedException rejection) {
                        if (!"STALE_MARK_PRICE".equals(rejection.code())) throw rejection;
                        obsolete++;
                        continue;
                    }
                    var chunk = executable ? owner.activeOrderIndex().lifecycleOrders(liquidation.userId(), owner.identities().instrumentId(liquidation.symbolId()),
                            action.cursorOrderId(), remaining) : new ActiveOrderIndex.LifecycleOrderChunk(List.of(), 0);
                    for (var order : chunk.orders()) owner.resultBuilder.markOrderChanged(order.orderId());
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                    var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                    if (executable) {
                        processedOrders += orders.size();
                        remaining -= orders.size();
                    }
                    if (nextCursor == 0) applied++; else pendingCount++;
                    executionRequests.add(new com.surprising.aeron.service.liquidation.LiquidationService
                            .ExecutionRequest(action, orders, nextCursor));
                }
                if (!executionRequests.isEmpty()) {
                    accountWork = com.surprising.aeron.service.liquidation.LiquidationService
                            .beginExecutionBatch(executionRequests, batch.liquidationFeeRatePpm(), owner.runtimeState(), owner.identities());
                    return false;
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
                        riskWork = new RiskScanner(batch.maxRiskScanUsers(), owner.positionUserIndex(),
                                owner.runtimeState(), owner.identities());
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
        pending.deferControl(work);
    }

    /** 完整应用或无变化拒绝可正常完成。已知前缀只允许授权撤单或替换旧单且不能含成交，检查的是允许集合而非重建全部前缀。强平或结算的部分前缀以及致命分歧要求恢复。 */
    public boolean matchingResultNeedsRecovery(OrderCommand pending,
                                                OrderMatchingResult result) {
        if (result.outcome() == OrderMatchingResult.Outcome.APPLIED
                || result.outcome()
                == OrderMatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == OrderMatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            List<Long> expected = result instanceof com.surprising.aeron.service.settlement.TradeSettlement direct
                    ? direct.completedPreMatchingCancellations() : pending.preMatchingCancellationOrderIds();
            var replacement = pending.orderBatch() != null
                    ? pending.orderBatch().replacementAdmission() : pending.matchingAdmission();
            long replacedOrderId = replacement != null
                    && (pending.operation() == OrderCommand.Operation.REPLACE
                    || pending.operation() == OrderCommand.Operation.AMEND)
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
                OrderCancelResult cancellation = cancellations.get(index);
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
        if (pending.operation() == OrderCommand.Operation.LIQUIDATION
                || pending.operation() == OrderCommand.Operation.LIQUIDATION_BATCH
                || pending.operation() == OrderCommand.Operation.SETTLEMENT) return true;
        return result.outcome()
                == OrderMatchingResult.Outcome.FATAL_DIVERGENCE;
    }

    /** 核对 Core 序号、命令 UUID，以及结果所属逻辑分片的序号严格大于已应用值。 */
    void validateMatchingEvidence(
            OrderCommand pending,
            OrderMatchingResult result) {
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

    /** 把快照中提供的各分片进度写入已分配数组，空快照不处理，未提供的分片不在此清零。 */
    public void initializeMatcherProgress(OrderBookSnapshot snapshot) {
        if (snapshot == null) return;
        for (com.surprising.aeron.service.matcher.OrderBookSnapshot.ShardProgress progress
                : snapshot.matcherShardProgress()) {
            int index = matcherProgressIndex(progress.matcherShardId());
            appliedMatcherSequences[index] = progress.matcherSequence();
        }
    }

    /** 保存已经由调用方核验的结果分片序号。 */
    void applyMatcherProgress(
            OrderMatchingResult result) {
        int index = matcherProgressIndex(result.nativeMatcherShardId());
        appliedMatcherSequences[index] = result.nativeMatcherSequence();
    }

    /** 保存直达事件最后一个 Matcher 序号，适用于事件内包含多个撮合动作的路径。 */
    void applyMatcherProgress(com.surprising.aeron.service.settlement.TradeSettlement event) {
        int index = matcherProgressIndex(event.directMatcherShard());
        appliedMatcherSequences[index] = event.directLastMatcherSequence();
    }

    /** 读取指定逻辑分片已经应用的 Matcher 序号。 */
    long matcherSequence(int matcherShardId) {
        return appliedMatcherSequences[matcherProgressIndex(matcherShardId)];
    }

    /** 逻辑分片加一映射到数组，负一控制分片占零下标，普通分片从一下标开始。 */
    int matcherProgressIndex(int matcherShardId) {
        int index = matcherShardId + 1;
        if (index < 0 || index >= appliedMatcherSequences.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return index;
    }

    /** 在允许序号内激活队首批次、排空已有完成通知并推进账本资金 FIFO，不提交终态。 */
    public void advanceMatchingProgress(long throughSequence) {
        long first = owner.pendingMatching().firstSequence();
        if (first > throughSequence) return;
        OrderCommand pending = owner.pendingMatching().get(first);
        OrderBatchProcessor.PendingBatch head = pending == null ? null : pending.orderBatch();
        if (head != null && !head.activated()) {
            owner.batches().activateOrderBatch(head, pending, true);
        }
        if (owner.matchingProgress.hasDrainWork()) owner.matchingProgress.drainMatchingCompletions();
        dispatchReadyMatcherFinancialSettlements(throughSequence);
    }

    /** 按既有账本游标顺序派发就绪且已投递的资金事件，不等待响应退休。邮箱满或事实未就绪就返回，顺序批次未结束前保留当前资金游标。 */
    public void dispatchReadyMatcherFinancialSettlements(long throughSequence) {
        OrderCommand pending;
        while ((pending = owner.pendingMatching().ledgerDispatchHead()) != null
                && pending.sequence() <= throughSequence) {
            if (pending.ledgerDispatchClosed()) {
                owner.pendingMatching().completeLedgerDispatch(pending.sequence());
                continue;
            }
            TradeSettlement event = financialDispatchEvent(pending);
            if (event == null) return;
            boolean sequential = pending.orderBatch() != null && !pending.orderBatch().pipelined();
            owner.runtimeState().dispatchMatcherFinancialSettlement(event);
            // 顺序批次还会产生后续子项。
            // 在最后子项或显式业务关闭前保留资金游标，响应槽租约也继续保留。
            if (sequential && !pending.orderBatch().matchingApplied()) return;
            owner.pendingMatching().completeLedgerDispatch(pending.sequence());
        }
    }

    /** 检查账本游标是否有可重试的关闭或派发工作，让邮箱曾满的 Owner 即使没有新通知也能再推进。 */
    public boolean hasFinancialDispatchWork() {
        OrderCommand pending = owner.pendingMatching().ledgerDispatchHead();
        if (pending == null) return false;
        if (pending.ledgerDispatchClosed()) return true;
        TradeSettlement event = financialDispatchEvent(pending);
        return event != null && (!event.hasLedgerSettlement()
                || pending.orderBatch() == null || pending.orderBatch().matchingApplied());
    }

    /** 否则优先批次当前项，再选整批或普通事件，要求事实 ready 且已派发账户。 */
    private TradeSettlement financialDispatchEvent(OrderCommand pending) {
        // 控制命令和顺序批次在同一 Core 命令内还可能产生后续资金。
        if (pending == null || pending.controlPending() || pending.ledgerDispatchClosed()) return null;
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch();
        TradeSettlement event = batch == null ? pending.settlementEvent()
                : batch.itemSettlementEvent() != null ? batch.itemSettlementEvent() : batch.settlementEvent();
        return event != null && event.ready() && event.dispatched() ? event : null;
    }

    /** 关闭本命令资金顺序边界并尝试推进后继资金，不释放发布或响应租约。 */
    public void closeFinancialDispatch(OrderCommand pending) {
        pending.ledgerDispatchClosed(true);
        dispatchReadyMatcherFinancialSettlements(Long.MAX_VALUE);
    }

    private boolean financialHeadReady(OrderCommand pending) {
        if (pending.sequence() != owner.pendingMatching().firstSequence()) return false;
        if (pending.hasMatchingRejection() || owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) return true;
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch();
        if (batch == null) return owner.runtimeState().pollMatcherFinancialSettlement(pending.settlementEvent());
        if (batch.itemSettlementEvent() != null
                && !owner.runtimeState().pollMatcherFinancialSettlement(batch.itemSettlementEvent())) return false;
        return owner.runtimeState().pollMatcherFinancialSettlement(batch.settlementEvent());
    }

    /** 必要时先收普通下单准入，再检查拒单、财务、批次账户工作及事件完成条件。 */
    public boolean matchingCommitReady(OrderCommand pending) {
        if (pending == null) return false;
        if (pending.operation() == OrderCommand.Operation.PLACE && pending.placeAdmission() != null) {
            if (!owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) return false;
        }
        if (owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) {
            if (!pending.isMatchingSubmitted()) return true;
            if (pending.settlementEvent() != null && pending.settlementEvent().direct())
                return pending.settlementEvent().complete();
            return pending.matchingResult() != null || pending.hasMatchingCompletion();
        }
        if (!financialHeadReady(pending)) return false;
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch();
        // 批次账户工作尚未完成时，仅有撮合结果不能作为提交就绪。
        if (batch != null && (!batch.activated() || !batch.laneWorkComplete())) return false;
        if (batch != null && batch.itemAdmission() != null) return true;
        if (batch != null && (batch.itemSettlementEvent() != null || batch.laneCommitEvent() != null)) return true;
        if (batch != null && batch.settlementEvent() != null && batch.settlementEvent().direct())
            return batch.settlementEvent().complete();
        if (batch != null && batch.placeBatchAdmissionEvent() != null
                && (!batch.placeBatchAdmissionEvent().complete() || !pending.isMatchingSubmitted())) return false;
        // 直达事件不另建 Owner 撮合结果槽。
        if (pending.settlementEvent() != null && pending.settlementEvent().direct())
            return pending.settlementEvent().complete();
        if (pending.hasLaneContinuation()) return pending.laneContinuationComplete();
        return pending.hasMatchingCompletion() || pending.matchingResult() != null;
    }

    /** 消费直达就绪位并增加一次调度修订，业务结算事实由队首从原事件收集。 */
    public void drainMatcherSettlementCompletions() {
        if (owner.runtimeState().takeDirectMatcherSettlementReadyLaneMask() != 0)
            owner.pendingMatching().progressChanged();
    }

    /** 只看全局队首并检查是否能尝试提交，未就绪返回空。 */
    OrderCommand pollReadyPending() {
        OrderCommand pending = owner.pendingMatching().head();
        return matchingCommitReady(pending) ? pending : null;
    }

    /** 确认队首在上限内，推进一次已有完成源后再探测队首，不阻塞等待 Matcher 或账户线程。 */
    OrderCommand pollReadyHead(long throughSequence) {
        if (owner.pendingMatching().isEmpty() || owner.pendingMatching().firstSequence() > throughSequence) {
            return null;
        }
        advanceMatchingProgress(throughSequence);
        return pollReadyPending();
    }

    private OrderMatchingResult matchingResult(OrderCommand pending) {
        OrderMatchingResult matching =
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

    /** 使用不受限的序号上限，按给定尝试预算推进队首并回调实际完成响应。 */
    public int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst, Long.MAX_VALUE, handler);
    }

    /** 使用同一个上限控制本轮资金派发及终态提交，进入完整推进入口。 */
    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst,
                throughSequence, throughSequence, true, handler);
    }

    /** 要求 Owner 健康、正尝试预算和非空处理器，可先推进资金到派发上限，再逐队首尝试提交。 */
    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, long dispatchThroughSequence,
                            boolean advanceProgress, TradingCoreRuntime.MatchingCommitHandler handler) {
        owner.assertOwner();
        owner.assertHealthy();
        if (maxCompletions <= 0 || handler == null) {
            throw new IllegalArgumentException("matching commit batch requires a positive limit and handler");
        }
        if (advanceProgress) advanceMatchingProgress(dispatchThroughSequence);
        int completed = 0;
        int attempts = 0;
        while (attempts < maxCompletions) {
            if (owner.pendingMatching().firstSequence() > throughSequence) break;
            OrderCommand pending = pollReadyPending();
            if (pending == null && attempts == 0 && awaitFirst) {
                pending = pollReadyHead(throughSequence);
            }
            if (pending == null) break;
            long sequence = pending.sequence();
            CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
            attempts++;
            owner.pendingMatching().progressChanged();
            // 保留未完成队首，让下一轮 Owner 调度重试，不在这里阻塞。
            if (response == null) {
                break;
            }
            handler.onCommitted(sequence, response);
            completed++;
        }
        return completed;
    }

    /** 在正提交上限内只尝试一个有序队首，把可空响应直接交窗口持有者。 */
    public CoreResponse commitReadyMatchingHead(long clusterTimestamp, long clusterPosition,
                                         long throughSequence, long dispatchThroughSequence,
                                         boolean advanceProgress) {
        owner.assertOwner();
        owner.assertHealthy();
        if (throughSequence <= 0) throw new IllegalArgumentException("matching head sequence must be positive");
        if (advanceProgress) {
            advanceMatchingProgress(dispatchThroughSequence);
        }
        if (owner.pendingMatching().firstSequence() > throughSequence) return null;
        OrderCommand pending = pollReadyPending();
        if (pending == null || pending.sequence() > throughSequence) return null;
        CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
        owner.pendingMatching().progressChanged();
        return response;
    }

    private CoreResponse commitReadyPending(OrderCommand pending, long clusterTimestamp, long clusterPosition) {
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
        OrderMatchingResult matching = matchingResult(pending);
        return matching == null ? null
                : completeMatching(pending, matching, clusterTimestamp, clusterPosition);
    }

    /** 按调用方给定长度新建零进度数组，调用方须包含控制分片位置。 */
    public void initializeMatcherProgress(int count) { appliedMatcherSequences = new long[count]; }
    private void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = owner.lifecycleProgress(command.instrumentId());
        if (progress == null || !progress.ordersComplete()) {
            ActiveOrderIndex.LifecycleOrderChunk chunk = owner.activeOrderIndex().lifecycleOrders(
                    0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
            owner.resultBuilder.addLifecycleChanges(chunk.orders());
            return;
        }
        owner.resultBuilder.commandChangedOrderIds = List.of();
    }

public static final class Publication {
    /** 只由 Owner 推进的所属交易运行时。 */
    private final TradingCoreRuntime owner;
    private long runtimePatchRevision;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean deferred;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean dirty;
    /** 当前是否正在 publish 中，防止重入。 */
    private boolean active;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long publishedSequence;
    @Accessors(fluent = true, chain = false)
    @Getter
    private boolean activated;
    private boolean closed;

    /** 保存非空所属运行时，不启动线程，也不复制账户状态。 */
    Publication(TradingCoreRuntime owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    /** 要求没有积累或正在执行的发布，且发布序号非负，再恢复索引修订水位及发布序号。 */
    public void initialize(long runtimePatchRevision, long publishedSequence) {
        if (deferred || dirty || active) {
            throw new IllegalStateException("cannot initialize an active commit publication");
        }
        if (publishedSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        this.runtimePatchRevision = runtimePatchRevision;
        this.publishedSequence = publishedSequence;
    }

    /** 拒绝激活已关闭发布器，否则标记可使用。 */
    public void activate() {
        if (closed) throw new IllegalStateException("cannot activate closed commit publication");
        activated = true;
    }

    /** 未激活或已关闭时抛错，防止在无效生命周期内发布。 */
    public void assertHealthy() {
        if (closed) throw new IllegalStateException("commit publication is closed");
        if (!activated) throw new IllegalStateException("commit publication is not activated");
    }

    /** 只标记关闭，不自行补发当前变化或停止其他线程。 */
    public void close() { closed = true; }

    /** 积累模式只记脏。 */
    public void request() {
        if (deferred) {
            dirty = true;
            return;
        }
        publish();
    }

    /** 拒绝重复开始积累，然后开启命令发布批次并清空待发布标记。 */
    public void begin() {
        if (deferred) throw new IllegalStateException("snapshot projection batch is already active");
        deferred = true;
        dirty = false;
    }

    /** 清理本批标记。 */
    public void complete() {
        boolean changed = dirty;
        clearBatchState();
        if (changed) publish();
    }

    /** 拒绝在已有积累或发布执行中覆盖标记，再恢复调用方保存的待发布状态。 */
    public void restore(boolean dirty) {
        if (deferred || active) throw new IllegalStateException("another commit publication is active");
        deferred = true;
        this.dirty = dirty;
    }

    /** 拒绝重入并检查生命周期，计算下一发布序号。内部运行异常记录恢复故障后继续抛出，不回滚已发生的部分变化。 */
    void publish() {
        if (active) throw new IllegalStateException("owner commit publisher is already active");
        active = true;
        try {
            assertHealthy();
            long sequence = Math.incrementExact(publishedSequence);
            try {
                if (!owner.factContextActive()) {
                    throw new IllegalStateException("runtime commit requires an active command scope");
                }
                if (owner.realtimeCapture() != null) owner.runtimeState().captureRealtimeChanges(owner.realtimeCapture());
                if (owner.runtimeState().committedRevision() < runtimePatchRevision)
                    throw new IllegalStateException("runtime changed-index commit is out of order");
                owner.runtimeState().applyChangedIndexes();
                publishedSequence = sequence;
                runtimePatchRevision = owner.runtimeState().committedRevision();
                owner.runtimeState().clearCommittedChanges(owner.identities());
            } catch (RuntimeException failure) {
                owner.commitPublicationFailure(new IllegalStateException(
                        "owner commit failed after deterministic mutation; restart from snapshot and log is required",
                        failure));
                throw failure;
            }
        } finally {
            active = false;
        }
    }

    /** 清空积累及待发布标记，不改变发布水位、生命周期或账户状态。 */
    public void clearBatchState() {
        deferred = false;
        dirty = false;
    }
}

}
