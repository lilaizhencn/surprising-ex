package com.surprising.aeron.service.order.commit;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.CoreCancellationResult;
import com.surprising.aeron.service.matcher.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import com.surprising.aeron.service.order.OrderBatchPending;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.command.OrderBatchKind;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.risk.RiskScanCoordinator;
import com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor;
import com.surprising.aeron.service.settlement.MatcherSettlementEvent;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * 交易步骤五：Owner 校验 Matcher 证据，收齐账户及财务完成条件，再保存终态、发布变化并退休队首。普通交易收集已经执行的直达结算事实，不重新撮合；强平和到期控制还需续接既有账户业务工作。账本资金按独立 FIFO 提前派发，响应仍按命令队首退休，两套顺序不能混为一谈。
 */
@Slf4j
public final class OrderedCommitCoordinator {
    /**  唯一所属交易 Owner，串行访问命令槽和发布边界。 借用本提交边界所属的 Owner 运行时。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final TradingCoreRuntime owner;

    /**
     * 保存所属 Owner 并创建同一运行时的发布边界，不另建账户或账本状态。
     * @param owner 所属交易 Owner 运行时
     */
    public OrderedCommitCoordinator(TradingCoreRuntime owner) {
        this.owner = owner;
        this.publication = new CommitPublication(owner);
    }

    /** 各逻辑分片已核验并应用的序号，控制分片负一映射零下标；后续证据要求严格递增。 */
    long[] appliedMatcherSequences;

    /**
     *  已经计入预派发且尚未终态退休的结算命令数量。
     * 返回当前预派发且尚未退休的结算计数。
     * 直接保存调用方更新的在途计数，不在此做增减或范围检查。
     * @param dispatchedSettlementInFlight 调用方已计算的计数或观测值，此处直接保存
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int dispatchedSettlementInFlight;

    /**
     *  在途结算数量的观测峰值。
     * 返回已记录的结算在途数量峰值，仅供观测。
     * 直接保存调用方计算的观测峰值，不在此检查单调性或范围。
     * @param dispatchedSettlementHighWaterMark 调用方已计算的计数或观测值，此处直接保存
     */
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    int dispatchedSettlementHighWaterMark;

    /** 同一 Owner 使用的索引及变化发布边界，不另存账户事实。 */
    public final CommitPublication publication;

    /**
     * 只由 Owner 收尾仍在途的拒单；已提交 Matcher 的项须先等生产者完成。保存幂等拒绝，必要时校验并推进尚未应用的 Matcher 证据，最后回收事件、退休槽并结束事实作用域。
     * @param sequence 仍在槽池中的完整 Core 序号
     */
    public CoreResponse completeRejectedMatching(long sequence) {
        owner.assertOwner();
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null || !owner.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
        // 当前直达事件或匹配到的直达事实，类型见声明
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
        // 本轮终态需要保存的拒绝或匹配结果码
        CoreResultCode resultCode = pending.matchingRejection();
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.matchingFlow.commitMatchingSequence(sequence);
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(), pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA, 0, 0);
        if (direct != null && direct.direct()) {
            // 准入拒绝也可能消费 Matcher 证据序号。
            // 回收直达事件前推进 Owner 的该分片水位，使在线状态与恢复后的游标一致。
            if (direct.directResultAvailable()) {
                // 拒单仍消费的 Matcher 证据，退休前核对其进度
                MatchingResult matcherResult = direct.directResult();
                // 普通直达完成路径可能已经核验应用了相同事实。
                // 账户拒绝较晚被观察到时，不再次应用同一证据。
                // 该证据自身的逻辑 Matcher 分片
                int shard = matcherResult.nativeMatcherShardId();
                if (matcherSequence(shard) != matcherResult.nativeMatcherSequence()) {
                    validateMatchingEvidence(pending, matcherResult);
                    applyMatcherProgress(matcherResult);
                }
            }
            owner.runtimeState().discardMatcherSettlement(pending.takeSettlementEvent());
        } else if (pending.isMatchingSubmitted()) {
            // 拒单仍消费的 Matcher 证据，退休前核对其进度
            MatchingResult matcherResult = pending.matchingResult();
            if (matcherResult == null) matcherResult = pending.matchingCompletion();
            if (matcherResult != null) {
                validateMatchingEvidence(pending, matcherResult);
                applyMatcherProgress(matcherResult);
            }
        }
        owner.matchingFlow.removePendingMatching(sequence);
        // 本轮完成时交给窗口的终态响应，未完成可为空
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return owner.finishFactContext(response);
    }

    /**
     * 要求 Owner 身份，按完整 Core 序号找到槽后进入同一完成入口；未就绪可返回空。
     * @param sequence 仍在槽池中的完整 Core 序号
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     */
    public CoreResponse completeMatching(long sequence,
                                  MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        return completeMatching(owner.pendingMatching.get(sequence), matchingResult, clusterTimestamp, clusterPosition);
    }

    /**
     * 检查 Lane 健康和证据可恢复性，收下单准入，等待财务、Matcher 路由及账户完成；按原提交边界恢复控制或批次续步。普通交易收集直达事实，强平和到期接既有控制业务，收齐后走统一终态；不能协调的结果按故障恢复处理。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     */
    CoreResponse completeMatching(CommandSlot pending,
                                  MatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        owner.assertOwner();
        if (pending == null || matchingResult == null) return null;
        // 当前命令完整 Core 序号
        long sequence = pending.sequence();
        // 当前命令关联的批次上下文，普通单笔为空
        OrderBatchPending waitingBatch = pending.orderBatch() != null ? pending.orderBatch() : owner.batches().batch(sequence);
        try {
            owner.runtimeState().assertAccountLanesHealthy();
        } catch (/* 当前账户、批次或不可协调 Matcher 失败，按所在边界记录恢复故障 */ RuntimeException failure) {
            if (waitingBatch != null) throw owner.batches().failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required", failure);
        }
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            // 发生故障的可空批次上下文
            OrderBatchPending failedBatch = waitingBatch != null ? waitingBatch : owner.batches().batch(sequence);
            // 当前账户、批次或不可协调 Matcher 失败，按所在边界记录恢复故障
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure();
            // 带结果码及可选原因的恢复故障说明
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
        // Matcher 先释放路由再发布 Lane 就绪。生产者仍持有槽时不能回收，传输槽退休另有完成边界。
        if (pending.settlementEvent() != null && pending.settlementEvent().direct()
                && pending.submittedMatcherShard() != -1) return null;
        if (pending.controlPending()) {
            owner.restoreMatchingCommitContext(pending);
            try {
                // 风险续步完成时也会补回挂起期间需要的响应摘要。
                if (pending.controlWork() != null && !pending.controlWork().getAsBoolean()) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                // 账户业务工作只消费一次；后续发布和提交可能还需多轮推进。
                pending.controlWork(null);
                if (!pending.controlAccountsCommitted() && pending.commitEvent() == null) {
                    owner.runtimeState().completePendingReservations(sequence);
                    owner.resultBuilder.materializeChangeAccumulators();
                    // 挂起控制命令可跨多个风险页累积用户。
                    // 登记完整提交输入，不能只看最后一页变化。
                    for (long /* 当前实际变化用户，用于补充其账户 Lane 回执位 */ userId : owner.resultBuilder.commandChangedUserIds)
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
                    // 当前完成事件登记的必要账户 Lane 位图
                    long laneMask = pending.commitEvent().requiredLaneMask();
                    owner.runtimeState().releaseLaneCommit(pending.commitEvent());
                    pending.commitEvent(null);
                    pending.completeLanes(laneMask);
                }
                pending.controlWork(null);
                pending.controlPending(false);
                return finishAppliedMatching(pending, matchingResult, true);
            } catch (/* 当前账户、批次或不可协调 Matcher 失败，按所在边界记录恢复故障 */ RuntimeException failure) {
                throw owner.failMatching(pending, "control Lane continuation failed", failure);
            }
        }
        if (waitingBatch != null && waitingBatch.pipelined() && !waitingBatch.commitStarted()) {
            // 当前准入对象，类型和所处阶段见声明
            PlaceBatchAdmissionEvent admission = waitingBatch.placeBatchAdmissionEvent();
            // 当前独立批量撤单已具备预备提交条件
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
            } catch (/* 当前账户、批次或不可协调 Matcher 失败，按所在边界记录恢复故障 */ RuntimeException failure) {
                throw owner.batches().failOrderBatch(waitingBatch, pending, "batch item Lane failed", failure);
            }
        }
        if (waitingBatch != null && pending.hasCommitContext()) owner.restoreMatchingCommitContext(pending);
        if (waitingBatch != null && pending.clusterIndependent()
                && waitingBatch.kind() == OrderBatchKind.CANCEL && !waitingBatch.commitStarted()) {
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
            // 直达事件所持有的已完成 Matcher 事实
            MatchingResult directResult = waitingBatch.itemSettlementEvent().directResult();
            validateMatchingEvidence(pending, directResult);
            applyMatcherProgress(directResult);
            return owner.batches().completeOrderBatchMatching(
                    sequence, directResult,
                    clusterTimestamp, clusterPosition);
        }
        if (waitingBatch != null && waitingBatch.itemSettlementEvent() != null) {
            if (waitingBatch.itemSettlementEvent().direct()) {
                // 直达事件所持有的已完成 Matcher 事实
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
            // 发生故障的可空批次上下文
            OrderBatchPending failedBatch = owner.batches().batch(sequence);
            // 当前账户、批次或不可协调 Matcher 失败，按所在边界记录恢复故障
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure();
            // 带结果码及可选原因的恢复故障说明
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
                    // 按当前操作取出的解码输入，具体用途见所在分支
                    var command = pending.decodedCommand().liquidation();
                    // 本次账户执行检查或派生索引给出的可空强平计划
                    var liquidation = pending.liquidationCheck().liquidation(0);
                    // 当前动作是否通过账户强平执行资格检查
                    boolean executable = matchingResult.accepted() && liquidation != null && pending.liquidationCheck().executable(0);
                    // 本次实际选择的撤单页，不可执行时为空页
                    TradingCoreRuntime.LifecycleOrderChunk chunk = !executable ? new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0)
                            : owner.matchingFlow.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(),
                            command.maxOrders());
                    owner.resultBuilder.commandChangedOrderIds = TradingCoreRuntime.boxedOrderIds(chunk.orders());
                    if (liquidation == null) owner.resultBuilder.commandChangedUserIds = List.of();
                    else owner.resultBuilder.setSingleChangedUser(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        // 仍有后续撤单时保留的排他订单游标，否则为零
                        long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                        // 本次传给强平账户工作的撤单身份列表
                        var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                        // 当前强平撤单进度或待恢复分片进度，具体类型见声明
                        var progress = new CoreLiquidationProgressView(nextCursor == 0, nextCursor,
                                chunk.orders().size());
                        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                            // 本命令要挂起轮询的强平、到期或批次控制工作
                            var work = com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor
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
                    // 按当前操作取出的解码输入，具体用途见所在分支
                    var command = pending.decodedCommand().liquidationBatch();
                    applyLiquidationBatch(command, matchingResult, pending);
                }
                case SETTLEMENT -> {
                    // 按当前操作取出的解码输入，具体用途见所在分支
                    var command = pending.decodedCommand().settlement();
                    if (matchingResult.accepted()) {
                        owner.instrumentSettlement().applySettlementChangedIds(command);
                        if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                            // 本命令要挂起轮询的强平、到期或批次控制工作
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
        } catch (/* 当前事实应用、账户收集或资金计算错误，转为需要恢复的命令故障 */ CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (/* 当前事实应用、账户收集或资金计算错误，转为需要恢复的命令故障 */ ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        }
        return finishAppliedMatching(pending, matchingResult, false);
    }

    /**
     * 核对序号可提交，编码或使用已有响应切片，累计成交数并保存幂等终态，随后移交构建器及可选 Lane 响应租约。这里只保存响应，不移除命令槽。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     * @param status 需要保存的终态响应状态
     * @param resultCode 与终态状态配套的结果码
     */
    private CoreResponse storeTerminalResponse(CommandSlot pending, MatchingResult matchingResult,
            ResponseStatus status, CoreResultCode resultCode) {
        // 当前待保存终态的原 Core 序号
        long applied = pending.sequence();
        owner.matchingFlow.commitMatchingSequence(applied);
        // 是否需要由结果构建器新编码响应，已有保留响应时为假
        boolean builderEncoded = pending.resultData() == null;
        // 控制续步可能已经编码 resultData。
        // 交给命令窗口前该字节租约仍归复用构建器，不能因本次未编码就漏掉移交。
        // 终态要保存并移交租约的响应字节数组
        byte[] responseData = builderEncoded
                ? owner.resultBuilder.commandResultData(pending, matchingResult) : pending.resultData();
        // 构建器响应切片起点，已有独立响应从零读取
        int responseOffset = builderEncoded ? owner.resultBuilder.responseDataOffset() : 0;
        // 构建器有效切片长度或已有响应完整长度
        int responseLength = builderEncoded ? owner.resultBuilder.responseDataLength() : responseData.length;
        // 响应数组是否正是账户 Lane 预备缓冲，需要额外移交该租约
        boolean laneResponse = responseData != null && responseData == pending.lanePreparedResponse();
        pending.resultData(null);
        owner.terminalTradeCount(Math.addExact(owner.terminalTradeCount(), owner.resultBuilder.commandTradeCount()));
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(), pending.fingerprint(),
                status, resultCode, applied, responseData,
                responseOffset, responseLength);
        // 本轮完成时交给窗口的终态响应，未完成可为空
        CoreResponse response = pending.command().ownedResponse(status, status, resultCode, applied,
                responseData, responseOffset, responseLength);
        owner.resultBuilder.transferResponseOwnership();
        if (laneResponse) pending.transferLaneResponseOwnership();
        return response;
    }

    /**
     * 完成发布、检查资金守恒、关闭资金派发顺序边界并保存终态；再扣在途计数、回收结算事件、退休槽和结束事实作用域，最后才推进后继批次。后续异常不代表此前操作自动撤销。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     */
    private CoreResponse finishTerminalMatching(CommandSlot pending, MatchingResult matchingResult) {
        publication.complete();
        owner.validateFundsConservation(pending.command());
        // 按 Matcher 接受与否确定的终态状态
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        // 本轮终态需要保存的拒绝或匹配结果码
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        closeFinancialDispatch(pending);
        // 本轮完成时交给窗口的终态响应，未完成可为空
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
        if (owner.pendingMatching.hasDeferred() || owner.batches().hasPendingBatches()) {
            owner.matchingProgress.submitDeferredMatchingAfterBatch();
        }
        return response;
    }

    /**
     * 需要时派发账户变化并等待提交回执，核对完整 Lane 位图并整理视图；强平和到期还需等控制资金核验。未完成则保存响应及挂起上下文，全部就绪才进入统一终态收尾。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     * @param lanesCommitted 当前控制路径是否已经收齐账户提交回执
     */
    private CoreResponse finishAppliedMatching(CommandSlot pending, MatchingResult matchingResult,
            boolean lanesCommitted) {
        // 当前命令完整 Core 序号
        long sequence = pending.sequence();
        // 命令槽首次固定的提交业务时间，恢复后沿用
        long clusterTimestamp = pending.commitFenceTimestamp();
        // 命令槽首次固定的提交日志位置，恢复后沿用
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
                // 当前完成事件登记的必要账户 Lane 位图
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
        // 本轮立即完成的结算只登记了原语变化，还没有经过挂起完成路径的视图整理，需要在此补齐。
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

    /**
     * 直达事件完整后收集资金和实体变化，标记 Lane 完成并按操作整理成交与触发结果，接入 Treasury、推进既有修订并物化视图。这里不再撮合或重复做账户资金计算；收集失败转为需要恢复的撮合故障。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     */
    CoreResponse completeDispatchedMatcherSettlement(
            CommandSlot pending,
            MatchingResult matchingResult) {
        // 当前结算事件或遍历的 Matcher 事件，类型见声明
        com.surprising.aeron.service.settlement.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null || !event.complete()) return null;
        owner.captureRealtimeTrades(pending);
        // 已完成账户收集得到的财库变化，在激活前按原规则接入
        com.surprising.aeron.service.treasury.RuntimeTreasuryDelta settlementTreasuryDelta;
        try {
            settlementTreasuryDelta = owner.runtimeState().collectMatcherSettlement(
                    event, owner.runtimeState().coldCommandFunds(), owner.terminalRetention(),
                    owner.resultBuilder.changedUserIds(), owner.resultBuilder.changedOrderIds());
            owner.resultBuilder.markLaneCommitIdsSeeded();
            pending.completeLanes(event.requiredLaneMask());
            switch (pending.operation()) {
                case CANCEL -> { /* Lane 已按撮合结论完成解冻和撤单。 */ }
                case PLACE -> {
                    // 原有 PLACE 输入读取，此收集分支随后不使用该局部值
                    var command = pending.decodedCommand().placeOrder();
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case REPLACE, AMEND -> {
                    // 核对存在的替换准入，后续成交统计不再读取此局部引用
                    ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                    owner.resultBuilder.commandTradeCount(TradingCoreRuntime.tradeCount(pending.settlementPlan()));
                }
                case TRIGGER -> {
                    // 触发输入的原语数组，此收集分支只保留原有读取，不使用其内容
                    long[] execute = pending.decodedCommand().trigger();
                    // 原槽持有的源触发单，必须仍可取得
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
            // 按现有操作及接受结果确定的运行时修订增量，之后再加前置撤单数量
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
            // 事件登记的参与 Lane 位图局部值，后续判断直接读取槽位图，不使用此局部值
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
        } catch (/* 当前事实应用、账户收集或资金计算错误，转为需要恢复的命令故障 */ CoreStateRejectedException exception) {
            throw owner.failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (/* 当前事实应用、账户收集或资金计算错误，转为需要恢复的命令故障 */ ArithmeticException | IllegalArgumentException exception) {
            throw owner.failMatching(pending, "Core and matcher state diverged", exception);
        } catch (/* 当前事实应用、账户收集或资金计算错误，转为需要恢复的命令故障 */ RuntimeException exception) {
            throw owner.failMatching(pending, "account lane settlement failed; snapshot/log recovery is required",
                    exception);
        }
        return finishTerminalMatching(pending, matchingResult);
    }

    /**
     * 从请求用户、成交 maker、成功或失败撤单所对应的现有路由和强平动作确定初始参与账户位图；结算操作人不代表持仓所有者，生命周期实际用户由控制业务完成后补入。
     * @param pending 当前仍占用的命令槽
     * @param result 已有的 Matcher 结果证据
     */
    long expectedLaneMask(
            CommandSlot pending,
            MatchingResult result) {
        // 由实际命令、成交及路由逐步汇总的初始账户 Lane 位图
        long mask = 0;
        // 请求消息头的用户，结算操作人不纳入持仓账户位图
        long activeUserId = pending.command().header().userId();
        // 品种结算改变的是实际持仓和订单所有者的账户，操作人不因此成为参与账户。
        if (activeUserId > 0 && pending.operation() != CommandSlot.Operation.SETTLEMENT)
            mask |= owner.matchingAdapter().topology().accountLaneMask(activeUserId);
        // 当前 Matcher 结果自带的事件列表
        var matcherEvents = result.matcherEvents();
        for (/* 当前遍历下标或逻辑分片映射后的数组下标 */ int index = 0; index < matcherEvents.size(); index++) {
            // 当前 Matcher 事件，只有成交才增加 maker 所属 Lane
            MatcherEvent match = matcherEvents.get(index);
            if (match.eventType() == MatcherEventType.TRADE) {
                mask |= owner.matchingAdapter().topology().accountLaneMask(match.matchedOrderUid());
            }
        }
        // 结果中的有序撤单事实列表
        var cancellations = result.cancellations();
        for (/* 当前遍历下标或逻辑分片映射后的数组下标 */ int index = 0; index < cancellations.size(); index++) {
            // 当前撤单事实，核对路由或已知前缀授权
            CoreCancellationResult cancellation = cancellations.get(index);
            // 当前活动订单路由或撤单页身份
            var order = owner.activeOrderIndex().activeOrderRoute(cancellation.orderId());
            if (order != null) mask |= owner.matchingAdapter().topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH) {
            for (var /* 当前批次强平动作，含计划编号和撤单游标 */ action : pending.decodedCommand().liquidationBatch().actions()) {
                mask |= owner.matchingAdapter().topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == CommandSlot.Operation.LIQUIDATION) {
            // 原有单笔强平输入读取，此位图分支随后不使用该局部值
            var command = pending.decodedCommand().liquidation();
            // 本次账户执行检查或派生索引给出的可空强平计划
            var liquidation = pending.liquidationCheck().liquidation(0);
            if (liquidation != null) mask |= owner.matchingAdapter().topology().accountLaneMask(liquidation.userId());
        }
        // 生命周期实际用户由既有账本和账户工作选出，再补入控制 Lane 位图，不能提前猜测扫描业务索引。
        return mask;
    }

    /**
     * 按账户 Lane 数返回全部合法位；六十四个 Lane 用全一位图，避免位移回绕。
     */
    public long validAccountLaneMask() {
        // 当前拓扑账户 Lane 数量
        int count = owner.matchingAdapter().topology().accountLaneCount();
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    /**
     * 要求槽已满足它登记的全部账户完成条件，否则抛错。
     * @param context 需要核对账户完成条件的原命令槽
     */
    public void requireCompleteAccountLanes(CommandSlot context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
    }

    /**
     * 控制结果已有进度摘要且尚未保留响应时先编码保存，供挂起后继续；字节租约在终态移交前仍归原构建器。
     * @param pending 当前仍占用的命令槽
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     */
    private void retainControlResponse(CommandSlot pending, MatchingResult matchingResult) {
        if (pending.resultData() == null && (owner.resultBuilder.commandRiskScanControl != null
                || owner.resultBuilder.commandLiquidationProgress != null
                || owner.resultBuilder.commandLiquidationBatchResult != null)) {
            pending.resultData(owner.resultBuilder.commandResultData(pending, matchingResult));
        }
    }

    /**
     * 按完整序号检查槽池仍被占用，再读取控制挂起标记；不要求此时已链接全局队列。
     * @param sequence 仍在槽池中的完整 Core 序号
     */
    public boolean controlPending(long sequence) {
        if (!owner.pendingMatching.claimed(sequence)) return false;
        // 仍由指定完整序号占用的命令槽
        CommandSlot slot = owner.pendingMatching.required(sequence);
        return slot.controlPending();
    }

    /**
     * 先登记全部动作用户以完成 Lane 回执；拒绝结果只整理摘要。接受结果用一个续步按撤单预算推进有效动作、账户强平和可选风险续扫，再整理批次结果；异步模式挂到原命令，同步模式要求一次完成。
     * @param batch 已解码的批次强平动作与撤单及风险预算
     * @param matchingResult 该命令已有的不可变 Matcher 证据
     * @param pending 当前仍占用的命令槽
     */
    void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            MatchingResult matchingResult,
            CommandSlot pending) {
        // 全部动作都已进入固定结果的 Lane 位图。
        // 失效或受预算限制跳过的动作也须完成对应账户回执，即使余额及订单未变。
        for (var /* 当前批次强平动作，含计划编号和撤单游标 */ action : batch.actions()) owner.resultBuilder.markUserChanged(action.userId());
        if (!matchingResult.accepted()) {
            // 拒绝批次中已不存在或不可执行的动作数量
            int obsolete = 0;
            for (var /* 当前批次强平动作，含计划编号和撤单游标 */ action : batch.actions()) {
                // 本次账户执行检查或派生索引给出的可空强平计划
                var liquidation = owner.liquidationIndex().route(action.liquidationId());
                if (liquidation == null || !liquidation.executable()) {
                    obsolete++;
                }
            }
            owner.resultBuilder.commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), 0,
                    batch.actions().size() - obsolete, obsolete, 0, 0);
            return;
        }
        // 本命令要挂起轮询的强平、到期或批次控制工作
        var work = /* 当前批次强平的既有控制续步，保留动作游标、撤单预算及账户和风险等待状态；只在本命令生命周期内由 Owner 推进。 */ new java.util.function.BooleanSupplier() {
            /**
             * index：批次强平下一个动作下标，当前动作取出后立即递增。
             * applied：本轮撤单页已无后续游标的强平动作数量。
             * pendingCount：因预算用完或仍需撤单续页而待后续处理的动作数。
             * obsolete：计划已终止、缺失或标记价过期而跳过的动作数量。
             * processedOrders：本轮有效动作已经选择的撤单数量。
             */
            int index, applied, pendingCount, obsolete, processedOrders;
            /** 当前批次剩余可选撤单预算。 */
            int remaining = batch.maxCancelOrders();
            /** 本轮强平账户批次的可空续步，完成后请求发布。 */
            java.util.function.BooleanSupplier accountWork;
            /** 已选择撤单页并准备交账户执行的强平请求集合，派发后换成空集合。 */
            List<com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor.ExecutionRequest>
                    executionRequests;
            /** 账户强平后可选的风险续扫工作。 */
            RiskScanCoordinator riskWork;
            /** 开始风险续扫前的修订，用于决定是否请求发布。 */
            long beforeRiskRevision;
            /** 是否已经判断并启动本批风险续扫，避免重复准备。 */
            boolean riskStarted;

            /**
             * 先收上次账户工作，再逐动作跳过失效计划、选择撤单页和准备强平执行；有异步账户批次就让出 Owner。账户完成后只在风险游标仍匹配时续扫，最后填写本轮摘要。过期标记价可跳过，其他业务拒绝继续传播。
             */
            @Override public boolean getAsBoolean() {
                if (accountWork != null) {
                    if (!accountWork.getAsBoolean()) return false;
                    accountWork = null;
                    publication.request();
                }
                if (executionRequests == null) executionRequests = new ArrayList<>();
                while (index < batch.actions().size()) {
                    // 当前批次强平动作，含计划编号和撤单游标
                    var action = batch.actions().get(index++);
                    // 本次账户执行检查或派生索引给出的可空强平计划
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
                    // 从当前批次动作构造的单笔强平执行请求
                    ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                            action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                            action.cursorOrderId(), Math.min(remaining, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    // 当前动作是否通过账户强平执行资格检查
                    boolean executable;
                    try {
                        executable = pending.liquidationCheck().executable(index - 1);
                    } catch (/* 账户强平执行检查的业务拒绝；只跳过过期标记价，其余继续传播 */ CoreStateRejectedException rejection) {
                        if (!"STALE_MARK_PRICE".equals(rejection.code())) throw rejection;
                        // 动作查询后到当前提交边界前可能标记价已过期。
                        // 跳过该动作，不再修改其订单或余额，后续新风险结果会取代它。
                        obsolete++;
                        continue;
                    }
                    // 本次实际选择的撤单页，不可执行时为空页
                    var chunk = executable ? owner.matchingFlow.lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                            action.cursorOrderId(), remaining) : new TradingCoreRuntime.LifecycleOrderChunk(List.of(), 0);
                    for (var /* 当前活动订单路由或撤单页身份 */ order : chunk.orders()) owner.resultBuilder.markOrderChanged(order.orderId());
                    owner.resultBuilder.markUserChanged(liquidation.userId());
                    // 仍有后续撤单时保留的排他订单游标，否则为零
                    long nextCursor = executable && chunk.more() ? chunk.nextCursorOrderId() : 0;
                    // 本次传给强平账户工作的撤单身份列表
                    var orders = executable ? chunk.orders() : List.<CancellationOrder>of();
                    if (executable) {
                        processedOrders += orders.size();
                        remaining -= orders.size();
                    }
                    if (nextCursor == 0) applied++; else pendingCount++;
                    if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                        executionRequests.add(new com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor
                                .ExecutionRequest(single, orders, nextCursor));
                        continue;
                    }
                    if (nextCursor != 0) owner.liquidations().advanceLiquidationCancellationRuntime(single, orders, nextCursor);
                    else owner.liquidations().executeLiquidationRuntime(single, orders);
                }
                if (!executionRequests.isEmpty()) {
                    if (owner.runtimeState().systemLedgerStarted() || owner.runtimeState().asynchronousCommands()) {
                        accountWork = com.surprising.aeron.service.liquidation.RuntimeDerivativeLiquidationProcessor
                                .beginExecutionBatch(executionRequests, owner.runtimeState(), owner.identities());
                        executionRequests = new ArrayList<>();
                        return false;
                    }
                    executionRequests.clear();
                }
                // 本轮实际完成的风险续扫工作量
                int completedRiskWork = 0;
                if (!riskStarted) {
                    riskStarted = true;
                    // 当前第一个尚未完成的风险扫描记录
                    var scan = owner.runtimeState().firstRiskIncompleteScan();
                    // 批次请求的可空风险续扫游标
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
                            if (completedRiskWork > 0) owner.runtimeState().acceptChangedUserIds(/* 当前实际变化用户，用于补充其账户 Lane 回执位 */ userId ->
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

    /**
     * 完整应用或无变化拒绝可正常完成；已知前缀只允许授权撤单或替换旧单且不能含成交，检查的是允许集合而非重建全部前缀。强平或结算的部分前缀以及致命分歧要求恢复。
     * @param pending 当前仍占用的命令槽
     * @param result 已有的 Matcher 结果证据
     */
    public boolean matchingResultNeedsRecovery(CommandSlot pending,
                                                MatchingResult result) {
        if (result.outcome() == MatchingResult.Outcome.APPLIED
                || result.outcome()
                == MatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == MatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
            // 准入授权的前置撤单编号集合
            List<Long> expected = result instanceof com.surprising.aeron.service.settlement.MatcherSettlementEvent /* 当前直达事件或匹配到的直达事实，类型见声明 */ direct
                    ? direct.completedPreMatchingCancellations() : pending.preMatchingCancellationOrderIds();
            // 批次或单笔已经保存的可空替换准入
            var replacement = pending.orderBatch() != null
                    ? pending.orderBatch().replacementAdmission() : pending.matchingAdmission();
            // AMEND 或 REPLACE 的原单编号，无可用替换准入时为零
            long replacedOrderId = replacement != null
                    && (pending.operation() == CommandSlot.Operation.REPLACE
                    || pending.operation() == CommandSlot.Operation.AMEND)
                    ? replacement.originalOrderId() : 0;
            // 部分前缀事实中是否含成交，含成交不能按仅撤单前缀收尾
            boolean containsTrade = false;
            // 当前 Matcher 结果自带的事件列表
            var matcherEvents = result.matcherEvents();
            for (/* 当前遍历下标或逻辑分片映射后的数组下标 */ int index = 0; index < matcherEvents.size(); index++) {
                // 当前结算事件或遍历的 Matcher 事件，类型见声明
                MatcherEvent event = matcherEvents.get(index);
                if (event.eventType() == MatcherEventType.TRADE) {
                    containsTrade = true;
                    break;
                }
            }
            // 所有成功撤单是否都位于授权前置列表或替换原单
            boolean cancellationsValid = true;
            // 结果中的有序撤单事实列表
            var cancellations = result.cancellations();
            for (/* 当前遍历下标或逻辑分片映射后的数组下标 */ int index = 0; index < cancellations.size(); index++) {
                // 当前撤单事实，核对路由或已知前缀授权
                CoreCancellationResult cancellation = cancellations.get(index);
                // 当前核对授权范围的撤单编号
                long orderId = cancellation.orderId();
                if (cancellation.accepted() && !expected.contains(orderId) && orderId != replacedOrderId) {
                    cancellationsValid = false;
                    break;
                }
            }
            // 至少有一条授权来源、没有成交且成功撤单均获授权的结果
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

    /**
     * 核对 Core 序号、命令 UUID，以及结果所属逻辑分片的序号严格大于已应用值；不要求恰好加一。分片越界或证据不符按现有规则抛错。
     * @param pending 当前仍占用的命令槽
     * @param result 已有的 Matcher 结果证据
     */
    void validateMatchingEvidence(
            CommandSlot pending,
            MatchingResult result) {
        // 结果自身的逻辑分片号
        int matcherShardId = result.nativeMatcherShardId();
        // Owner 此前已经核验应用的该分片序号
        long appliedMatcherSequence = matcherSequence(matcherShardId);
        if (result.nativeCoreSequence() != pending.sequence()
                || !result.nativeMatches(pending.command().header().commandIdMsb(), pending.command().header().commandIdLsb())
                || result.nativeMatcherSequence() <= appliedMatcherSequence) {
            throw owner.failMatching(pending, "matcher result does not continue the applied sequence"
                    + " expectedSequenceAfter=" + appliedMatcherSequence
                    + " actualSequence=" + result.nativeMatcherSequence(), null);
        }
    }

    /**
     * 把快照中提供的各分片进度写入已分配数组，空快照不处理，未提供的分片不在此清零。
     * @param snapshot 可空 Matcher 快照，其中提供了要恢复的分片进度
     */
    public void initializeMatcherProgress(MatcherSnapshot snapshot) {
        if (snapshot == null) return;
        for (com.surprising.aeron.service.matcher.MatcherShardProgress /* 当前强平撤单进度或待恢复分片进度，具体类型见声明 */ progress
                : snapshot.matcherShardProgress()) {
            // 当前遍历下标或逻辑分片映射后的数组下标
            int index = matcherProgressIndex(progress.matcherShardId());
            appliedMatcherSequences[index] = progress.matcherSequence();
        }
    }

    /**
     * 保存已经由调用方核验的结果分片序号；本方法不重复检查证据。
     * @param result 已有的 Matcher 结果证据
     */
    void applyMatcherProgress(
            MatchingResult result) {
        // 当前遍历下标或逻辑分片映射后的数组下标
        int index = matcherProgressIndex(result.nativeMatcherShardId());
        appliedMatcherSequences[index] = result.nativeMatcherSequence();
    }

    /**
     * 保存直达事件最后一个 Matcher 序号，适用于事件内包含多个撮合动作的路径；证据由调用方先核验。
     * @param event 已完成并由调用方核验的直达结算事件
     */
    void applyMatcherProgress(com.surprising.aeron.service.settlement.MatcherSettlementEvent event) {
        // 当前遍历下标或逻辑分片映射后的数组下标
        int index = matcherProgressIndex(event.directMatcherShard());
        appliedMatcherSequences[index] = event.directLastMatcherSequence();
    }

    /**
     * 读取指定逻辑分片已经应用的 Matcher 序号。
     * @param matcherShardId 逻辑 Matcher 分片号，负一用于控制证据
     */
    long matcherSequence(int matcherShardId) {
        return appliedMatcherSequences[matcherProgressIndex(matcherShardId)];
    }

    /**
     * 逻辑分片加一映射到数组，负一控制分片占零下标，普通分片从一下标开始；越界抛错。
     * @param matcherShardId 逻辑 Matcher 分片号，负一用于控制证据
     */
    int matcherProgressIndex(int matcherShardId) {
        // 当前遍历下标或逻辑分片映射后的数组下标
        int index = matcherShardId + 1;
        if (index < 0 || index >= appliedMatcherSequences.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return index;
    }

    /**
     * 在允许序号内激活队首批次、排空已有完成通知并推进账本资金 FIFO，不提交终态；时间和日志位置沿用入口签名，此方法体不读取。
     * @param clusterTimestamp 沿用调用签名的 Cluster 时间，当前方法不读取
     * @param clusterPosition 沿用调用签名的日志位置，当前方法不读取
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
     */
    public void advanceMatchingProgress(long clusterTimestamp, long clusterPosition, long throughSequence) {
        // 当前待命令全局最小序号
        long first = owner.pendingMatching.firstSequence();
        if (first > throughSequence) return;
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending = owner.pendingMatching.get(first);
        // 连续轮询复用命令已有的Map键，不为同一在途序号反复分配Long。
        OrderBatchPending head = pending == null ? null : pending.orderBatch();
        if (head != null && !head.activated()) {
            owner.batches().activateOrderBatch(head, pending, true);
        }
        if (owner.matchingProgress.hasDrainWork()) owner.matchingProgress.drainMatchingCompletions();
        dispatchReadyMatcherFinancialSettlements(throughSequence);
    }

    /**
     * 按既有账本游标顺序派发就绪且已投递的资金事件，不等待响应退休；邮箱满或事实未就绪就返回，顺序批次未结束前保留当前资金游标。
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
     */
    public void dispatchReadyMatcherFinancialSettlements(long throughSequence) {
        if (!owner.runtimeState().systemLedgerStarted()) return;
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending;
        while ((pending = owner.pendingMatching.ledgerDispatchHead()) != null
                && pending.sequence() <= throughSequence) {
            if (pending.ledgerDispatchClosed()) {
                owner.pendingMatching.completeLedgerDispatch(pending.sequence());
                continue;
            }
            // 当前结算事件或遍历的 Matcher 事件，类型见声明
            MatcherSettlementEvent event = financialDispatchEvent(pending);
            if (event == null) return;
            // 当前是否为仍可能继续产生子项的顺序批次
            boolean sequential = pending.orderBatch() != null && !pending.orderBatch().pipelined();
            if (!owner.runtimeState().dispatchMatcherFinancialSettlement(event)) return;
            // 顺序批次还会产生后续子项。
            // 在最后子项或显式业务关闭前保留资金游标，响应槽租约也继续保留。
            if (sequential && !pending.orderBatch().matchingApplied()) return;
            owner.pendingMatching.completeLedgerDispatch(pending.sequence());
        }
    }

    /**
     * 检查账本游标是否有可重试的关闭或派发工作，让邮箱曾满的 Owner 即使没有新通知也能再推进。
     */
    public boolean hasFinancialDispatchWork() {
        if (!owner.runtimeState().systemLedgerStarted()) return false;
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending = owner.pendingMatching.ledgerDispatchHead();
        if (pending == null) return false;
        if (pending.ledgerDispatchClosed()) return true;
        // 当前结算事件或遍历的 Matcher 事件，类型见声明
        MatcherSettlementEvent event = financialDispatchEvent(pending);
        return event != null && (!event.hasLedgerSettlement()
                || pending.orderBatch() == null || pending.orderBatch().matchingApplied());
    }

    /**
     * 已关闭、控制仍挂起或没有槽时不选事件；否则优先批次当前项，再选整批或普通事件，要求事实 ready 且已派发账户。
     * @param pending 当前仍占用的命令槽
     */
    private MatcherSettlementEvent financialDispatchEvent(CommandSlot pending) {
        // 控制命令和顺序批次在同一 Core 命令内还可能产生后续资金。
        // 当前有序续步未结束前，不让后续命令资金先进入账本。
        if (pending == null || pending.controlPending() || pending.ledgerDispatchClosed()) return null;
        // 当前命令关联的可空批次
        OrderBatchPending batch = pending.orderBatch();
        // 当前结算事件或遍历的 Matcher 事件，类型见声明
        MatcherSettlementEvent event = batch == null ? pending.settlementEvent()
                : batch.itemSettlementEvent() != null ? batch.itemSettlementEvent() : batch.settlementEvent();
        return event != null && event.ready() && event.dispatched() ? event : null;
    }

    /**
     * 只在账本启用时关闭本命令资金顺序边界并尝试推进后继资金，不释放发布或响应租约。
     * @param pending 当前仍占用的命令槽
     */
    public void closeFinancialDispatch(CommandSlot pending) {
        if (!owner.runtimeState().systemLedgerStarted()) return;
        pending.ledgerDispatchClosed(true);
        dispatchReadyMatcherFinancialSettlements(Long.MAX_VALUE);
    }

    /**
     * 无独立账本时直接通过；账本启用时要求全局队首，并等当前普通或批次资金结算，已登记拒单无需再等资金事实。
     * @param pending 当前仍占用的命令槽
     */
    private boolean financialHeadReady(CommandSlot pending) {
        if (!owner.runtimeState().systemLedgerStarted()) return true;
        if (pending.sequence() != owner.pendingMatching.firstSequence()) return false;
        if (pending.hasMatchingRejection() || owner.matchingFlow.hasPendingMatchingRejection(pending.sequence())) return true;
        // 当前命令关联的可空批次
        OrderBatchPending batch = pending.orderBatch();
        if (batch == null) return owner.runtimeState().pollMatcherFinancialSettlement(pending.settlementEvent());
        if (batch.itemSettlementEvent() != null
                && !owner.runtimeState().pollMatcherFinancialSettlement(batch.itemSettlementEvent())) return false;
        return owner.runtimeState().pollMatcherFinancialSettlement(batch.settlementEvent());
    }

    /**
     * 必要时先收普通下单准入，再检查拒单、财务、批次账户工作及事件完成条件；这不是纯查询，返回真也只表示可尝试推进提交。
     * @param pending 当前仍占用的命令槽
     */
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
        // 当前命令关联的可空批次
        OrderBatchPending batch = pending.orderBatch();
        // 批次账户工作尚未完成时，仅有撮合结果不能作为提交就绪。
        if (batch != null && (!batch.activated() || !batch.laneWorkComplete())) return false;
        if (batch != null && batch.itemAdmission() != null) return true;
        if (batch != null && (batch.itemSettlementEvent() != null || batch.laneCommitEvent() != null)) return true;
        if (batch != null && batch.settlementEvent() != null && batch.settlementEvent().direct())
            return batch.settlementEvent().complete();
        if (batch != null && batch.placeBatchAdmissionEvent() != null
                && (!batch.placeBatchAdmissionEvent().complete() || !pending.isMatchingSubmitted())) return false;
        // 直达事件不另建 Owner 撮合结果槽。
        // 应以该事件完整完成条件判断是否可进入有序提交。
        if (pending.settlementEvent() != null && pending.settlementEvent().direct())
            return pending.settlementEvent().complete();
        if (pending.hasLaneContinuation()) return pending.laneContinuationComplete();
        return pending.hasMatchingCompletion() || pending.matchingResult() != null;
    }

    /**
     * 消耗直达就绪位和各 Lane 通知游标，只要有变化就增加一次调度修订；业务结算事实仍留在事件，由队首提交收集。
     */
    public void drainMatcherSettlementCompletions() {
        // 本轮是否消费过任何结算就绪提示，用于只增加一次调度修订
        boolean changed = owner.runtimeState().takeDirectMatcherSettlementReadyLaneMask() != 0;
        // 仍需排空通知游标的账户 Lane 位图
        long readyLaneMask = owner.runtimeState().takeMatcherSettlementReadyLaneMask();
        while (readyLaneMask != 0) {
            // 当前就绪位对应的账户 Lane 编号
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            // 当前命令完整 Core 序号
            long sequence;
            while ((sequence = owner.runtimeState().pollMatcherSettlementReady(laneId)) != 0) {
                // 完成事实保存在事件内；Owner 在此只消费通知游标，队首提交才收集业务结果。
                changed = true;
            }
        }
        // 直达事件没有完成队列游标。
        // 增加一次调度修订即可提示队首重试，不必按 Lane 重复通知。
        if (changed) owner.pendingMatching.progressChanged();
    }

    /**
     * 只看全局队首并检查是否能尝试提交，未就绪返回空。
     */
    CommandSlot pollReadyPending() {
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending = owner.pendingMatching.head();
        return matchingCommitReady(pending) ? pending : null;
    }

    /**
     * 确认队首在上限内，推进一次已有完成源后再探测队首，不阻塞等待 Matcher 或账户线程。
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
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
     * 按当前批次项或普通槽查结果，再尝试直达事件和普通完成引用；只借用已有权威事实，不重新执行 Matcher。
     * @param pending 当前仍占用的命令槽
     */
    private MatchingResult matchingResult(CommandSlot pending) {
        // 当前从槽或事件取得的可空 Matcher 事实
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
     * 把已有事实交同一完成入口；账户或财务未齐时返回空，留到下轮 Owner 推进。
     * @param pending 当前仍占用的命令槽
     * @param matching 当前可用的 Matcher 事实
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     */
    private CoreResponse tryMatchingCommit(
            CommandSlot pending,
            MatchingResult matching,
            long clusterTimestamp,
            long clusterPosition) {
        return completeMatching(pending, matching, clusterTimestamp, clusterPosition);
    }

    /**
     * 使用不受限的序号上限，按给定尝试预算推进队首并回调实际完成响应。
     * @param maxCompletions 本轮最多尝试的队首次数，实际完成数可能更小
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     * @param awaitFirst 首轮无就绪项时是否再探测一次，不表示阻塞等待
     * @param handler 接收实际完成序号及终态响应的处理器
     */
    public int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst, Long.MAX_VALUE, handler);
    }

    /**
     * 使用同一个上限控制本轮资金派发及终态提交，进入完整推进入口。
     * @param maxCompletions 本轮最多尝试的队首次数，实际完成数可能更小
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     * @param awaitFirst 首轮无就绪项时是否再探测一次，不表示阻塞等待
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
     * @param handler 接收实际完成序号及终态响应的处理器
     */
    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, long throughSequence, TradingCoreRuntime.MatchingCommitHandler handler) {
        return commitReadyMatching(maxCompletions, clusterTimestamp, clusterPosition, awaitFirst,
                throughSequence, throughSequence, true, handler);
    }

    /**
     * 要求 Owner 健康、正尝试预算和非空处理器，可先推进资金到派发上限，再逐队首尝试提交。awaitFirst 只允许首轮多探测一次，不阻塞；任何未完成项保留原位，下轮再试，返回本轮实际完成数。
     * @param maxCompletions 本轮最多尝试的队首次数，实际完成数可能更小
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     * @param awaitFirst 首轮无就绪项时是否再探测一次，不表示阻塞等待
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
     * @param dispatchThroughSequence 本轮允许资金及完成源推进的上限，可以大于终态提交上限
     * @param advanceProgress 是否先推进公共完成源及资金派发
     * @param handler 接收实际完成序号及终态响应的处理器
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
        // 本轮实际完成并回调的终态数量
        int completed = 0;
        // 本轮已尝试推进的队首次数，未完成的一次也计入预算
        int attempts = 0;
        while (attempts < maxCompletions) {
            if (owner.pendingMatching.firstSequence() > throughSequence) break;
            // 当前按完整序号或有序游标取得的命令槽
            CommandSlot pending = pollReadyPending();
            if (pending == null && attempts == 0 && awaitFirst) {
                pending = pollReadyHead(clusterTimestamp, clusterPosition, throughSequence);
            }
            if (pending == null) break;
            // 当前命令完整 Core 序号
            long sequence = pending.sequence();
            // 本轮完成时交给窗口的终态响应，未完成可为空
            CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
            attempts++;
            owner.pendingMatching.progressChanged();
            // 撮合或账户通知可能稍后才到。
            // 保留未完成队首，让下一轮 Owner 调度重试，不在这里阻塞。
            if (response == null) {
                break;
            }
            handler.onCommitted(sequence, response);
            completed++;
        }
        return completed;
    }

    /**
     * 在正提交上限内只尝试一个有序队首，把可空响应直接交窗口持有者；序号零的准入即完成命令不走这里。
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     * @param throughSequence 本轮允许推进或提交的 Core 序号上限
     * @param dispatchThroughSequence 本轮允许资金及完成源推进的上限，可以大于终态提交上限
     * @param advanceProgress 是否先推进公共完成源及资金派发
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
        // 当前按完整序号或有序游标取得的命令槽
        CommandSlot pending = pollReadyPending();
        if (pending == null || pending.sequence() > throughSequence) return null;
        // 本轮完成时交给窗口的终态响应，未完成可为空
        CoreResponse response = commitReadyPending(pending, clusterTimestamp, clusterPosition);
        owner.pendingMatching.progressChanged();
        return response;
    }

    /**
     * 固定首次提交边界，拒单仍需等已派发 Matcher 完成才走拒绝收尾；其他项取已有事实并尝试统一提交，不改变原响应路由。
     * @param pending 当前仍占用的命令槽
     * @param clusterTimestamp 本次推进可用于首次固定提交边界的 Cluster 时间
     * @param clusterPosition 本次推进可用于首次固定提交边界的日志位置
     */
    private CoreResponse commitReadyPending(CommandSlot pending, long clusterTimestamp, long clusterPosition) {
        // 当前命令完整 Core 序号
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
        // 当前从槽或事件取得的可空 Matcher 事实
        MatchingResult matching = matchingResult(pending);
        return matching == null ? null
                : tryMatchingCommit(pending, matching, clusterTimestamp, clusterPosition);
    }

    /**
     * 按调用方给定长度新建零进度数组，调用方须包含控制分片位置；此入口不自行推导拓扑。
     * @param count 进度数组总长度，调用方须计入控制分片
     */
    public void initializeMatcherProgress(int count) { appliedMatcherSequences = new long[count]; }
}
