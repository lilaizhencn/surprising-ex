package com.surprising.aeron.service.order;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.order.command.OrderBatchKind;
import com.surprising.aeron.service.order.command.ResolvedMatchingAdmission;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** 批量订单从准入、撮合到整批终态的推进入口。Owner 用一条 Core 序号管理全部项：满足条件的同分片下单可流水推进，其余按项等待账户准入和结算，撤单还可合并连续同分片片段。每项保留自己的成功或拒绝结果；所有账户与财务条件收齐后才保存整批终态。 构造约定：保存所属运行时，共用原有命令槽、账户和发布边界。 参数 owner：所属交易 Owner 运行时 */
@RequiredArgsConstructor
public final class OrderBatchExecutor {
    /** 唯一所属交易 Owner，共用原有账户及提交状态。 */
    final TradingCoreRuntime owner;

    /**
     * firstBatch：按 Core 序号递增排列的最早未退休批次。
     * lastBatch：批次排序链尾部，用于校验并追加新批。
     */
    private OrderBatchPending firstBatch, lastBatch;
    /** 批次排序链中已登记的批次数量，序号查找仍走原命令环。 */
    private int batchCount;

    /**
     * 返回排序链是否还有未退休批次，不表示其已开始执行。
     */
    public boolean hasPendingBatches() { return firstBatch != null; }
    /**
     * 借用当前最早批次，空链返回空。
     */
    OrderBatchPending firstBatch() { return firstBatch; }
    /**
     * 返回已登记排序链的批次数量。
     */
    int pendingBatchCount() { return batchCount; }
    /**
     * 按完整 Core 序号从已有命令环取得批次，不另建序号到批次的索引。
     * @param sequence 整批共用的完整 Core 序号
     */
    public OrderBatchPending batch(long sequence) {
        // 按原序号取得或随当前项更新的命令槽
        CommandSlot pending = owner.pendingMatching().get(sequence);
        return pending == null ? null : pending.orderBatch;
    }

    /**
     * 核对命令槽、批次序号和未占用链接，要求序号递增，再把批次连到尾部并绑定原槽。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     */
    void registerBatch(CommandSlot pending, OrderBatchPending batch) {
        if (pending.orderBatch != null || pending.sequence() != batch.sequence
                || owner.pendingMatching().get(batch.sequence) != pending
                || batch.previousBatch != null || batch.nextBatch != null
                || lastBatch != null && lastBatch.sequence >= batch.sequence)
            throw new IllegalStateException("invalid batch command registration");
        pending.orderBatch = batch;
        batch.previousBatch = lastBatch;
        if (lastBatch == null) firstBatch = batch;
        else lastBatch.nextBatch = batch;
        lastBatch = batch;
        batchCount++;
    }

    /**
     * 核对原登记关系，摘除前后链接并解除命令槽绑定，更新首尾及数量；对象清理和归池另行完成。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     */
    void unregisterBatch(CommandSlot pending, OrderBatchPending batch) {
        if (pending.orderBatch != batch || batchCount == 0
                || batch.previousBatch == null && firstBatch != batch
                || batch.nextBatch == null && lastBatch != batch)
            throw new IllegalStateException("batch command registration is missing");
        if (batch.previousBatch == null) firstBatch = batch.nextBatch;
        else batch.previousBatch.nextBatch = batch.nextBatch;
        if (batch.nextBatch == null) lastBatch = batch.previousBatch;
        else batch.nextBatch.previousBatch = batch.previousBatch;
        batch.previousBatch = null;
        batch.nextBatch = null;
        pending.orderBatch = null;
        batchCount--;
    }

    /**
     * 逐个解除排序链与命令槽的登记，不在此清对象、归池或回滚账户。
     */
    public void clearPendingBatches() {
        while (firstBatch != null)
            unregisterBatch(owner.pendingMatching().get(firstBatch.sequence), firstBatch);
    }

    /** 各品种最新在途流水批次引用，辅助保持既有依赖，不保存订单事实。 */
    final HashMap<String, OrderBatchPending> pipelinedBatchBySymbol = new HashMap<>();

    /** 已经登记品种依赖而尚未退出的流水批次数。 */
    int activePipelinedOrderBatches;

    /** Owner 持有的已退休批次复用池，Matcher 或账户仍借用时不能归还。 */
    final java.util.ArrayDeque<OrderBatchPending> orderBatchPendingPool = new java.util.ArrayDeque<>();

    /**
     * 解码并检查整批订单身份，已知业务错误释放预备批次并拒绝；其他输入错误按原撮合顺序记拒绝。成功分配整批序号并登记依赖，尝试流水或队首激活，尚未完成则释放 Owner 账户访问权并返回待处理状态。
     * @param message 当前整批原始 Core 命令
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     * @param sourceKey 原命令来源类型与实例标识
     * @param fingerprint 整批原命令的完整指纹
     */
    public CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, TradingCoreRuntime.SourceKey sourceKey,
                                                  CommandFingerprint fingerprint) {
        // 新借、查询或当前正在处理的批次上下文
        OrderBatchPending batch = null;
        // 已解码的整批类型化输入
        DecodedMatchingCommand decodedCommand;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
            batch = decodeOrderBatch(message, decodedCommand, clusterTimestamp, clusterPosition);
            validateOrderBatchIdentity(batch, message.header().userId());
        } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ CoreStateRejectedException exception) {
            releaseOrderBatchPending(batch);
            return owner.rejected(CoreResultCode.fromRejectionCode(exception.code()));
        } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ ArithmeticException | IllegalArgumentException exception) {
            releaseOrderBatchPending(batch);
            return owner.matchingFlow.recordRejectedMatching(message, sourceKey, fingerprint,
                    exception instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        // 本批新占用的 Core 序号
        long sequence = owner.nextAppliedSequence();
        // 按原序号取得或随当前项更新的命令槽
        CommandSlot pending = owner.admissions().newPendingMatching(sequence, batch.operation(), message, fingerprint, decodedCommand);
        batch.sequence = sequence;
        owner.matchingFlow.putPendingMatching(pending);
        owner.admissions().registerPendingLifecycle(pending);
        registerBatch(pending, batch);
        owner.pendingMatching().registerSubmission(sequence, orderBatchMatcherShard(batch));
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        // 激活时直接得到的可空整批终态
        CoreResponse completed = tryActivatePipelinedOrderBatch(batch, pending)
                ? null
                : !pending.clusterIndependent && owner.pendingMatching().firstSequence() == sequence
                ? activateOrderBatch(batch, pending, false) : null;
        if (completed != null) return completed;
        owner.runtimeState().releaseOwnerLaneAccess();
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /**
     * 首次建立本批提交上下文，否则恢复原上下文，再标记激活；能整批准入就派发并挂起，其他批次从当前项开始，未完成时保留本批进度。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param deferCompletion 后台激活时是否把整批终态留给有序提交读取
     */
    public CoreResponse activateOrderBatch(OrderBatchPending batch, CommandSlot pending,
                                             boolean deferCompletion) {
        // 本批是否尚未开始有序提交，需要先建立原命令上下文
        boolean firstActivation = !batch.commitStarted();
        if (firstActivation) beginOrderBatchCommitContext(batch, pending);
        else if (pending.hasCommitContext())
            owner.restoreMatchingCommitContext(pending);
        else owner.activateFactContext(pending.command(), pending.fingerprint());
        batch.activated(true);
        if (preparePipelinedPlaceBatch(batch, pending)) {
            registerPipelinedBatchSymbols(batch, pending);
            dispatchPipelinedPlaceBatchAdmission(pending, batch);
            owner.runtimeState().releaseOwnerLaneAccess();
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        // 本轮已完成时返回的整批终态
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp,
                batch.clusterPosition, deferCompletion);
        if (response == null) owner.clearFactContext();
        return response;
    }

    /**
     * 独立批量撤单可先尝试提交；PLACE 还要没有更早同用户命令并满足既有依赖检查，再准备整批准入。条件不符返回假，交现有逐项路径推进。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    boolean tryActivatePipelinedOrderBatch(OrderBatchPending batch, CommandSlot pending) {
        if (pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL && !batch.sequentialAdmission) {
            batch.activated(true);
            owner.matchingFlow.submitMatching(pending);
            return true;
        }
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE
                || owner.pendingMatching().hasEarlierUser(batch.sequence, pending.command().header().userId())
                || !pending.clusterIndependent && conflictsWithEarlierPipelinedBatch(batch)) {
            return false;
        }
        batch.activated(true);
        if (!preparePipelinedPlaceBatch(batch, pending)) {
            batch.activated(false);
            return false;
        }
        registerPipelinedBatchSymbols(batch, pending);
        dispatchPipelinedPlaceBatchAdmission(pending, batch);
        return true;
    }

    /**
     * 首次登记本批各品种的最新批次引用并计数，独立模式沿用入口已经核对的依赖；违背所有权约束抛错，已写入的前缀不在此回滚。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    void registerPipelinedBatchSymbols(OrderBatchPending batch, CommandSlot pending) {
        if (batch.pipelineRegistered()) return;
        for (String /* 当前品种名称，可能来自输入、活动路由或较早成功项 */ instrumentId : batch.preparedSymbols) {
            // 品种依赖表保留最新批次，前序会按有序完成先移除。
            // Cluster 窗口已经核对了账户及撮合依赖。
            // 当前品种原先登记的批次、较早成功项或其品种，具体类型见声明
            OrderBatchPending previous = pipelinedBatchBySymbol.put(instrumentId, batch);
            if (previous != null && previous != batch && !pending.clusterIndependent) {
                throw new IllegalStateException("pipelined instrumentId ownership conflict");
            }
        }
        batch.pipelineRegistered(true);
        activePipelinedOrderBatches++;
    }

    /**
     * 只移除仍指向本批的品种条目，清登记位并递减活动数，计数为负时抛错。
     * @param batch 本条批量命令的复用上下文
     */
    void unregisterPipelinedBatchSymbols(OrderBatchPending batch) {
        if (!batch.pipelineRegistered()) return;
        for (String /* 当前品种名称，可能来自输入、活动路由或较早成功项 */ instrumentId : batch.preparedSymbols) {
            pipelinedBatchBySymbol.remove(instrumentId, batch);
        }
        batch.pipelineRegistered(false);
        activePipelinedOrderBatches--;
        if (activePipelinedOrderBatches < 0) {
            throw new IllegalStateException("active pipelined order batch count underflow");
        }
    }

    /**
     * 检查本批能否整批准入：必须 PLACE、全部同 Matcher 分片、没有只减仓项及需要逐项 OI 判断的产品，并校验订单身份；按品种准备共享配置。已知不适用或输入失败清判断缓存，返回假。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    boolean preparePipelinedPlaceBatch(OrderBatchPending batch, CommandSlot pending) {
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE) {
            return false;
        }
        // 整批命令请求用户
        long userId = pending.command().header().userId();
        try {
            // 已选整批唯一分片，负一表示尚未遇到第一项
            int batchMatcherShard = -1;
            batch.clearPreparedDecisionCache();
            for (/* 当前批内项或变更用户的遍历下标 */ int index = 0; index < batch.items.size(); index++) {
                // 当前准备、撮合、收集或编码的批内项
                OrderBatchItem item = batch.items.get(index);
                // 按当前种类取出的单项或整批解码请求，具体类型见声明
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                // 当前项品种路由到的 Matcher 分片，用于检查整批是否同分片
                int itemMatcherShard = batch.decodedCommand == null
                        ? owner.matchingAdapter().matcherShardId(command.instrumentId())
                        : batch.decodedCommand.matcherShard(owner.matchingAdapter(), command.instrumentId());
                if (batchMatcherShard == -1) batchMatcherShard = itemMatcherShard;
                else if (batchMatcherShard != itemMatcherShard) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                if (command.reduceOnly()) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                owner.requireOrderIdentityAvailable(userId, command);
                // 本品种共享的配置判断输入或其上下文
                var decision = prepareBatchDecision(batch, command.instrumentId());
                if (decision.context().instrument().usesOpenInterestForUserLimit())
                    throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                batch.preparedDecisions[index] = decision;
            }
            batch.pipelined = true;
            return true;
        } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | TradingCoreRuntime.PipelinedBatchNotApplicable exception) {
            batch.currentPreMatchingCancellationOrderIds = List.of();
            batch.clearPreparedDecisionCache();
            return false;
        }
    }

    /**
     * 确定本批唯一分片，把整批输入列、客户键列、订单回执列和原日志元数据交所属账户做整批准入。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     */
    void dispatchPipelinedPlaceBatchAdmission(
            CommandSlot pending, OrderBatchPending batch) {
        // 本次任务固定使用的 Matcher 分片
        int shard = batch.decodedCommand == null
                ? owner.matchingAdapter().matcherShardId(batch.preparedSymbols.getFirst())
                : batch.decodedCommand.matcherShard(owner.matchingAdapter(), batch.preparedSymbols.getFirst());
        batch.placeBatchAdmissionEvent = owner.runtimeState().dispatchPlaceBatchAdmission(
                pending.sequence(), pending.command().header().userId(), pending.command().header().commandId(),
                batch.preparedOrders, batch.preparedClientKeyValues,
                batch.preparedAdmittedOrders,
                batch.items.size(), shard, owner.identities(), batch,
                batch.clusterTimestamp, batch.clusterPosition);
    }

    /**
     * 固定提交边界并准备整批直达事件，绑定固定 Matcher 任务后提交；更新调度进度、预派发阶段和在途观测计数，不在此等待整批完成。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     */
    void submitPipelinedPlaceBatch(CommandSlot pending, OrderBatchPending batch) {
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        // 整批命令请求用户
        long userId = pending.command().header().userId();
        // 本次任务固定使用的 Matcher 分片
        int shard = owner.matchingAdapter().matcherShardId(batch.preparedSymbols.getFirst());
        batch.settlementEvent = preparePipelinedPlaceBatch(pending, batch, shard);
        batch.pipelinedPlaceBatchSubmission.prepare(this, pending, batch, userId, shard);
        owner.matcherPipeline().submit(shard, pending.sequence(), batch.pipelinedPlaceBatchSubmission, batch.settlementEvent);
        owner.pendingMatching().progressChanged();
        batch.markPredispatched();
        pending.countPipelinedSettlement();
        owner.commits.dispatchedSettlementInFlight(owner.commits.dispatchedSettlementInFlight() + 1);
        owner.commits.dispatchedSettlementHighWaterMark(Math.max(
                owner.commits.dispatchedSettlementHighWaterMark(),
                owner.commits.dispatchedSettlementInFlight()));
    }

    /**
     * 在 Matcher 按批内顺序执行各已准入下单并写同一直达事件，保留本项事件和状态、累计成交数；整批事实保存在事件中，返回首个原生结果供管线交接。运行异常保留到批次后继续抛出。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     * @param userId 本批请求用户，账户按该用户路由
     * @param shard 当前已确定的 Matcher 分片
     */
    Object submitPreparedPipelinedPlaceBatch(
            CommandSlot pending, OrderBatchPending batch, long userId, int shard) {
        try {
            // 本任务第一个原生 Matcher 结果，后续完整事实仍在直达事件
            exchange.core2.core.common.MatcherResult firstNative = null;
            java.util.Objects.requireNonNull(batch.settlementEvent, "direct batch settlement");
            for (/* 当前批内项或变更用户的遍历下标 */ int index = 0; index < batch.items.size(); index++) {
                // 当前准备、撮合、收集或编码的批内项
                OrderBatchItem item = batch.items.get(index);
                // 本项原始下单输入局部引用，实际撮合使用 preparedOrders，后续不读取此引用
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                // 账户已经完成准入的本项解析订单输入
                ResolvedPlaceOrder matchingOrder = batch.preparedOrders[index];
                // 当前项原生 Matcher 执行结果
                var result = owner.matchingAdapter().placeDirectBatchItem(
                        shard, index, pending.sequence(), pending.command().header().commandId(),
                        pending.command().header().submittedAtEpochMillis(), userId, matchingOrder,
                        batch.settlementEvent);
                if (firstNative == null) firstNative = result;
                item.realtimeTakerOrder = item.admittedOrder;
                // 当前项复制的成交事件数量
                int trades = item.captureExecutions(result.events());
                item.executionTakerUserId = userId;
                if (trades != 0) {
                    item.executionCount += trades;
                    batch.tradeCount = Math.addExact(batch.tradeCount, trades);
                }
                // 原生结果码是否为 SUCCESS 或 ACCEPTED
                boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                        || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
                item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
                item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
            }
            batch.lastMatchingResult = batch.settlementEvent;
            return firstNative;
        } catch (/* 当前 Matcher、账户结算或实时捕获运行异常，按所在方法保存、报告恢复或标记实时失败 */ RuntimeException failure) {
            batch.pipelinedMatchingFailure = failure;
            throw failure;
        }
    }

    /**
     * 从当前游标逐项准入，账户或改单回执未齐就挂起。已知单项错误保存该项拒绝后继续；账户已处理并回滚的准入失败不拿全局修订作无变化判断。成功项交 Matcher 后让出 Owner，全部无原生动作的后台激活也保留到有序提交发布终态。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     * @param deferCompletion 后台激活时是否把整批终态留给有序提交读取
     */
    CoreResponse startOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                             long clusterTimestamp, long clusterPosition,
                                             boolean deferCompletion) {
        batch.sequentialAdmission = true;
        while (batch.nextIndex < batch.items.size()) {
            // 当前准备、撮合、收集或编码的批内项
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            // 当前项是否已经派发下单预留或改单准入，决定是否重用原修订和输入
            boolean admissionPending = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
            // 本项首次准入前保存的全局修订，跨轮询沿用
            long runtimeRevisionBefore = !admissionPending
                    ? owner.runtimeState().revision() : batch.itemAdmissionRevision;
            try {
                if (!admissionPending) {
                    batch.itemAdmissionRevision = runtimeRevisionBefore;
                    prepareOrderBatchItem(batch, item, pending.command().header().userId(),
                            pending.command().header().commandId());
                }
                if (batch.replacementAdmissionEvent != null && !collectBatchAmendAdmission(batch, pending.command().header().userId())) {
                    batch.sequentialAdmission = true;
                    batch.activated(false);
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                if (batch.itemAdmission != null) {
                    if (!batch.itemAdmission.getAsBoolean()) {
                        batch.sequentialAdmission = true;
                        batch.activated(false);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    collectBatchPlaceReservation(batch);
                    batch.itemAdmission = null;
                }
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                owner.pendingMatching().put(pending);
                owner.matchingFlow.submitMatching(pending);
                // 撮合跨回调完成；变更上下文归本序号持有，不能占用下一条命令的 owner。
                owner.suspendMatchingCommitContext(pending);
                return null;
            } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ CoreStateRejectedException exception) {
                // 预留失败由所属账户校验并回滚后报告。
                // 其他 Lane 同时可能已提交，不能用全局修订是否变化来判断这项账户工作有没有回滚。
                // 错误是否来自已派发账户准入，避免拿其他 Lane 可变化的全局修订判断其回滚结果
                boolean laneAdmissionFailure = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
                releaseBatchAmendAdmission(batch);
                batch.itemAdmission = null;
                if (!laneAdmissionFailure)
                    requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()));
                batch.nextIndex++;
            } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ ArithmeticException | IllegalArgumentException exception) {
                // 错误是否来自已派发账户准入，避免拿其他 Lane 可变化的全局修订判断其回滚结果
                boolean laneAdmissionFailure = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
                releaseBatchAmendAdmission(batch);
                batch.itemAdmission = null;
                if (!laneAdmissionFailure)
                    requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        exception instanceof ArithmeticException
                                ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
                batch.nextIndex++;
            }
        }
        if (deferCompletion) {
            // 后台激活没有响应接收方。
            // 即使全部项都没有原生撮合动作，也要保留到有序提交推进器发布整批终态。
            batch.matchingApplied(true);
            initializeOrderBatchLaneContext(batch, pending);
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    /**
     * 本批同品种复用已捕获的只读配置、标记价和资产编号；首次使用才解析并保存，账户资金及 OI 判断仍由每项准入完成。
     * @param batch 本条批量命令的复用上下文
     * @param instrumentId 本项准入对应的品种名称
     */
    com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent.PlaceBatchIntentSource.Decision prepareBatchDecision(
            OrderBatchPending batch, String instrumentId) {
        // 当前批内项或变更用户的遍历下标
        int index = batch.preparedSymbols.indexOf(instrumentId);
        if (index >= 0) return batch.preparedContextDecisions[index];
        // 按整批业务时间捕获的品种配置、价格与产品判断上下文
        var context = CoreOrderDecisionResolver.context(owner.runtimeState(), owner.identities(), instrumentId, batch.clusterTimestamp);
        // 该上下文中的品种配置
        var instrument = context.instrument();
        // 品种基础资产名称
        String baseAsset = instrument.baseAsset();
        // 品种报价资产名称
        String quoteAsset = instrument.quoteAsset();
        // 品种结算资产名称
        String settleAsset = instrument.settleAsset();
        // 基础资产永久编号
        int baseAssetId = owner.identities().assetId(baseAsset);
        // 报价资产永久编号，与基础资产同名时直接复用
        int quoteAssetId = baseAsset.equals(quoteAsset) ? baseAssetId : owner.identities().assetId(quoteAsset);
        // 结算资产永久编号，与前两资产同名时复用已有编号
        int settleAssetId = quoteAsset.equals(settleAsset) ? quoteAssetId
                : baseAsset.equals(settleAsset) ? baseAssetId : owner.identities().assetId(settleAsset);
        // 本品种共享的配置判断输入或其上下文
        var decision = new com.surprising.aeron.service.order.admission.PlaceBatchAdmissionEvent.PlaceBatchIntentSource.Decision(context, 0,
                baseAssetId, quoteAssetId, settleAssetId);
        index = batch.preparedSymbols.size();
        batch.preparedSymbols.add(instrumentId);
        batch.preparedContextDecisions[index] = decision;
        return decision;
    }

    /**
     * 用本批品种判断输入准备固定账户预留工作，绑定当前 Core 序号和项下标，返回同一工作供 Owner 轮询。
     * @param batch 本条批量命令的复用上下文
     * @param userId 本批请求用户，账户按该用户路由
     * @param command 当前单项下单输入
     * @param commandId 整批原命令 UUID
     */
    private java.util.function.BooleanSupplier beginBatchReservation(OrderBatchPending batch, long userId,
                                                                    PlaceOrderCommand command, UUID commandId) {
        // 本品种共享的配置判断输入或其上下文
        var decision = prepareBatchDecision(batch, command.instrumentId());
        // 本批复用的顺序下单账户预留工作
        var work = batch.placeReservation;
        work.prepare(owner.runtimeState(), owner.identities(), batch.sequence, userId, commandId,
                command, decision, batch, batch.nextIndex);
        return work;
    }

    /**
     * 收集已经完成的账户预留，绑定本项账户冻结副本；保存先撤列表，再请求发布。
     * @param batch 本条批量命令的复用上下文
     */
    private void collectBatchPlaceReservation(OrderBatchPending batch) {
        // 本批复用的顺序下单账户预留工作
        var work = batch.placeReservation;
        // 账户已经冻结并交给本项的订单副本
        OrderRuntime admitted = work.admittedOrder();
        owner.runtimeState().collectControlReservation(admitted, batch.sequence);
        // 所属账户已经准备好本项持有的固定副本，Owner 只绑定其引用。
        batch.items.get(batch.nextIndex).admittedOrder = admitted;
        batch.currentPreMatchingCancellationOrderIds = work.closeCapacityCancellations();
        owner.commits.publication.request();
    }

    /**
     * 临时切到整批原时间和日志位置；PLACE 检查身份并准备预留，CANCEL 检查活动原单归属，AMEND 找原合约并派发替换准入。finally 恢复原 Owner 时间位置，账户工作仍由所属 Lane 完成。
     * @param batch 本条批量命令的复用上下文
     * @param item 本批当前单项输入与结果槽
     * @param userId 本批请求用户，账户按该用户路由
     * @param commandId 整批原命令 UUID
     */
    void prepareOrderBatchItem(OrderBatchPending batch, OrderBatchItem item, long userId,
                                       UUID commandId) {
        // 进入本项准入前的 Owner 业务时间，finally 恢复
        long previousTimestamp = owner.currentClusterTimestamp();
        // 进入本项准入前的 Owner 日志位置，finally 恢复
        long previousPosition = owner.currentClusterPosition();
        owner.currentClusterTimestamp(batch.clusterTimestamp);
        owner.currentClusterPosition(batch.clusterPosition);
        try {
            switch (batch.kind) {
                case PLACE -> {
                    // 按当前种类取出的单项或整批解码请求，具体类型见声明
                    PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                    owner.requireOrderIdentityAvailable(userId, command);
                    if (owner.activeOrderIndex().activeOrderRoute(command.orderId()) != null)
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "order already exists");
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    batch.itemAdmission = beginBatchReservation(batch, userId, command, commandId);
                }
                case CANCEL -> {
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    // 按当前种类取出的单项或整批解码请求，具体类型见声明
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    // Owner 已有活动订单索引给出的可空稳定路由
                    var route = owner.activeOrderIndex().activeOrderRoute(command.orderId());
                    // 当前用于校验撤单归属的同一活动路由引用
                    var order = route;
                    if (order == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "active order does not exist");
                    if (order.userId() != userId) {
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    }
                }
                case AMEND -> {
                    // 按当前种类取出的单项或整批解码请求，具体类型见声明
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    // Owner 已有活动订单索引给出的可空稳定路由
                    var route = owner.activeOrderIndex().activeOrderRoute(command.originalOrderId());
                    if (route != null && route.userId() != userId)
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    // 当前品种名称，可能来自输入、活动路由或较早成功项
                    String instrumentId = route == null
                            ? previousBatchOrderInstrument(batch, command.originalOrderId()) : route.instrumentId();
                    if (instrumentId == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                    if (command.replacementOrderId() == command.originalOrderId())
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
                    // 本品种共享的配置判断输入或其上下文
                    var decision = prepareBatchDecision(batch, instrumentId).context();
                    batch.replacementAdmissionEvent = owner.runtimeState().dispatchReplacementAdmission(
                            batch.sequence, userId, command.originalOrderId(), command, null, decision,
                            owner.matchingAdapter().matcherShardId(instrumentId), owner.identities());
                }
            }
        } finally {
            owner.currentClusterTimestamp(previousTimestamp);
            owner.currentClusterPosition(previousPosition);
        }
    }

    /**
     * 只查当前游标之前已成功且保有准入订单的项，用其订单编号找品种；找不到返回空，供批内后续改单识别原合约。
     * @param batch 本条批量命令的复用上下文
     * @param orderId 需要找本批较早准入记录的订单编号
     */
    private String previousBatchOrderInstrument(OrderBatchPending batch, long orderId) {
        for (/* 当前批内项或变更用户的遍历下标 */ int index = 0; index < batch.nextIndex; index++) {
            // 当前品种原先登记的批次、较早成功项或其品种，具体类型见声明
            OrderBatchItem previous = batch.items.get(index);
            if (previous.orderId() == orderId && previous.status == ResponseStatus.APPLIED
                    && previous.admittedOrder != null)
                return previous.admittedOrder.instrument().instrumentId();
        }
        return null;
    }

    /**
     * 事件未完成检查 Lane 健康后返回假；完成则抛出已有拒绝，再用 Owner 路由索引核对全局订单号和客户身份，保存替换准入及先撤列表。
     * @param batch 本条批量命令的复用上下文
     * @param userId 本批请求用户，账户按该用户路由
     */
    private boolean collectBatchAmendAdmission(OrderBatchPending batch, long userId) {
        // 当前改单准入或撤单直达事件，类型见声明
        var event = batch.replacementAdmissionEvent;
        if (!event.complete()) { owner.runtimeState().assertAccountLanesHealthy(); return false; }
        if (event.rejection() != null) throw event.rejection();
        // 事件已经解析的改单准入，后续仍需 Owner 核对全局订单身份
        var admission = event.admission();
        // 账户准入只见本 Lane 状态，订单号却要求全局唯一。
        // Owner 用已有活动路由索引再检查其他 Lane 是否已有同编号订单。
        if (owner.activeOrderIndex().activeOrderRoute(admission.command().orderId()) != null)
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
        owner.requireOrderIdentityAvailable(userId, admission.command());
        batch.replacementAdmission = admission;
        batch.currentPreMatchingCancellationOrderIds = event.closeCapacityCancellations();
        return true;
    }

    /**
     * 若有改单准入事件就归还并清事件及解析准入引用；调用方保证已经满足释放条件。
     * @param batch 本条批量命令的复用上下文
     */
    private void releaseBatchAmendAdmission(OrderBatchPending batch) {
        if (batch.replacementAdmissionEvent == null) return;
        owner.runtimeState().releaseReplacementAdmission(batch.replacementAdmissionEvent);
        batch.replacementAdmissionEvent = null;
        batch.replacementAdmission = null;
    }

    /**
     * 撤单交连续同分片片段路径；顺序下单或改单用当前已准入项预先借直达事件，再把固定任务交 Matcher。账户结算事件在撮合前已存在，Owner 后续只收集它。
     * @param pending 本批仍占用的命令槽
     */
    void submitOrderBatchMatching(CommandSlot pending) {
        // 新借、查询或当前正在处理的批次上下文
        OrderBatchPending batch = pending.orderBatch;
        if (batch == null) return;
        batch.lastMatchingResult = null;
        if (batch.kind == OrderBatchKind.CANCEL) {
            submitCancelBatchChunk(pending, batch);
            if (pending.clusterIndependent && batch.activated() && !pending.isMatchingSubmitted())
                pending.matchingSubmitted();
            return;
        }
        // 本次任务固定使用的 Matcher 分片
        int shard = orderBatchMatcherShard(batch);
        // 当前任务预先借用的直达结算事件
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = null;
        // 顺序批次各项依赖上一项账户准入，不能提前准备全批聚合结算。
        // 当前项已准入，先借好它的直达事件交 Matcher 发布给账户；Owner 只保留原有序终态边界。
        if (batch.kind == OrderBatchKind.PLACE) {
            // 当前准备、撮合、收集或编码的批内项
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            // 账户已经冻结并交给本项的订单副本
            OrderRuntime admitted = item.admittedOrder;
            if (admitted == null) throw new IllegalStateException("batch item admission order is missing");
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = prepareBatchPlaceItem(pending, batch, admitted, shard);
            batch.itemSettlementEvent = direct;
        }
        if (batch.kind == OrderBatchKind.AMEND) {
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = prepareBatchAmend(pending,
                    java.util.Objects.requireNonNull(batch.replacementAdmission), shard);
            batch.itemSettlementEvent = direct;
            batch.items.get(batch.nextIndex).captureAdmittedOrder(direct.admittedOrder());
            if (owner.realtimeCapture() != null) batch.items.get(batch.nextIndex).realtimeTakerOrder = batch.items.get(batch.nextIndex).admittedOrder;
        }
        owner.matcherPipeline().submit(shard, pending.sequence(),
                prepareOrderBatchMatchingCommand(
                        pending, batch, batch.items.get(batch.nextIndex), shard, direct), direct);
    }

    /**
     * 从当前项起合并连续且路由存在的同分片撤单，保留原项顺序；没有可提交项则退回队首逐项拒绝路径。判断剩余后缀有无活动订单，据此让最后实际片段携带最终账户水位，再准备事件及固定任务提交。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     */
    void submitCancelBatchChunk(CommandSlot pending, OrderBatchPending batch) {
        batch.lastMatchingResult = null;
        // 本次任务固定使用的 Matcher 分片
        int shard = orderBatchMatcherShard(batch);
        // 本次撤单片段包含起点
        int start = batch.nextIndex;
        // 逐项探查后确定的片段排他终点
        int end = start;
        // 只合并连续同分片的独立撤单，缺失订单留给 Owner 拒绝路径；跨分片仍保持原项顺序。
        while (end < batch.items.size()) {
            // 当前准备、撮合、收集或编码的批内项
            OrderBatchItem item = batch.items.get(end);
            // 入口已校验过该订单身份；此处从已有活动路由取得当前品种，准备固定同分片片段。
            // Owner 已有活动订单索引给出的可空稳定路由
            var route = owner.activeOrderIndex().activeOrderRoute(item.orderId);
            if (route == null) break;
            // 当前品种名称，可能来自输入、活动路由或较早成功项
            String instrumentId = route.instrumentId();
            if (owner.matchingAdapter().matcherShardId(instrumentId) != shard) break;
            batch.preparedCancellationOrderIds[end - start] = item.orderId;
            item.cancelSymbol = instrumentId;
            end++;
        }
        if (end == start) {
            // 前序命令完成后，入口曾见过的路由可能已消失。
            // 让批次回到有序队首，由单项准入拒绝缺失订单，避免无效账户修改或撮合提交。
            batch.sequentialAdmission = true;
            batch.activated(false);
            return;
        }
        batch.cancellationChunkStart = start;
        batch.cancellationChunkEnd = end;
        // 复制保存的片段排他终点，供固定任务之后使用
        int chunkEnd = end;
        // 后缀缺失订单不会产生资金或订单变化。
        // 最后一个实际撤单片段可直接推进请求用户 Lane 的最终序号，无需额外空任务。
        // 当前片段后是否已无仍有活动路由的订单，决定最终账户水位
        boolean finalChunk = true;
        for (/* 当前批内项或变更用户的遍历下标 */ int index = end; index < batch.items.size(); index++) {
            if (owner.activeOrderIndex().activeOrderRoute(batch.items.get(index).orderId) != null) {
                finalChunk = false;
                break;
            }
        }
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        // 当前任务预先借用的直达结算事件
        var direct = prepareCancelBatch(
                pending, batch, shard, finalChunk, end - start);
        batch.itemSettlementEvent = direct;
        batch.cancelBatchChunkSubmission.prepare(this, pending, batch, shard, start, chunkEnd, direct);
        owner.matcherPipeline().submit(shard, pending.sequence(), batch.cancelBatchChunkSubmission, direct);
    }

    /**
     * 要求已有槽、批次及 Matcher 事实，不能协调的结果进入故障恢复。流水批次要求自身直达事件；撤单收片段账户结果后逐项推进，其余应用当前项事实，再收已完成账户事件或挂起等待。
     * @param sequence 整批共用的完整 Core 序号
     * @param matchingResult 当前已有的 Matcher 证据或直达事件
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     */
    public CoreResponse completeOrderBatchMatching(long sequence,
                                                    MatchingResult matchingResult,
                                                    long clusterTimestamp, long clusterPosition) {
        // 按原序号取得或随当前项更新的命令槽
        CommandSlot pending = owner.pendingMatching().get(sequence);
        // 新借、查询或当前正在处理的批次上下文
        OrderBatchPending batch = batch(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
            // 当前故障对应的可空批次上下文
            OrderBatchPending failedBatch = batch(sequence);
            // 当前 Matcher、账户结算或实时捕获运行异常，按所在方法保存、报告恢复或标记实时失败
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            // Matcher 结果码与可选失败原因合成的恢复说明
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            throw failOrderBatch(batch, pending, detail, failure);
        }
        if (batch.pipelined) {
            if (matchingResult != batch.settlementEvent || !batch.settlementEvent.hasDirectNativeResult())
                throw failOrderBatch(batch, pending, "pipelined batch requires its direct Matcher fact", null);
            return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
        }
        if (batch.kind == OrderBatchKind.CANCEL) {
            if (matchingResult instanceof com.surprising.aeron.service.settlement.MatcherSettlementEvent /* 当前改单准入或撤单直达事件，类型见声明 */ event
                    && event == batch.itemSettlementEvent && event.hasDirectNativeResult()) {
                if (!event.complete()) throw new IllegalStateException("cancel chunk Lane is incomplete");
                batch.observedSettlementLaneMask |= event.requiredLaneMask();
                batch.mergeTreasuryDelta(owner.runtimeState().collectOrderBatchMatcherSettlement(
                        event, null, owner.terminalRetention()));
                owner.commits.publication.request();
                if (event.commitSequence() != 0) batch.laneCommitCompleted(true);
                owner.runtimeState().releaseMatcherSettlement(event);
                batch.itemSettlementEvent = null;
                batch.lastMatchingResult = null;
                // 当前片段尚未完成最终账户水位时是否继续登记最终变化身份
                boolean trackChanges = !batch.laneCommitCompleted();
                if (trackChanges) {
                    batch.changedUserIds.add(pending.command().header().userId());
                }
                while (batch.nextIndex < batch.cancellationChunkEnd) {
                    // 当前准备、撮合、收集或编码的批内项
                    OrderBatchItem item = batch.items.get(batch.nextIndex);
                    if (trackChanges && item.status == ResponseStatus.APPLIED)
                        batch.runtimeChangedOrderIds.add(item.orderId());
                    item.cancelSymbol = null;
                    batch.nextIndex++;
                }
                return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition, false);
            }
            throw failOrderBatch(batch, pending, "cancel chunk requires its direct Matcher fact", null);
        } else {
            applyCompletedOrderBatchItem(batch, pending, matchingResult);
        }
        if (batch.itemSettlementEvent != null) {
            if (batch.itemSettlementEvent.complete()) {
                // Owner 尚未回到提交轮询时，直达事件可能已经完成。
                // 当前轮直接收集，避免对已恢复上下文再次挂起而丢掉发布边界。
                return completeOrderBatchItemSettlement(batch, pending, clusterTimestamp, clusterPosition);
            }
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        if (batch.itemAdmission != null) {
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition, false);
    }

    /**
     * 收当前项账户结算并合并财库差额；成功改单保留既有修订计数，现货还计原单撤销。随后完成项结果、回收事件并开始下一项，运行异常转为批次恢复故障。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param timestamp 继续当前批次使用的业务时间
     * @param position 继续当前批次使用的日志位置
     */
    public CoreResponse completeOrderBatchItemSettlement(OrderBatchPending batch, CommandSlot pending,
                                                 long timestamp, long position) {
        try {
            // 当前已就绪并待收集的单项结算事件
            var settledEvent = batch.itemSettlementEvent;
            batch.mergeTreasuryDelta(owner.runtimeState().collectOrderBatchMatcherSettlement(
                    settledEvent, null, owner.terminalRetention()));
            if (batch.kind == OrderBatchKind.AMEND && batch.lastMatchingResult.accepted()) {
                // 保留原有预留修订计数；现货同时计算原单撤销，所以增加两次，其他产品增加一次。
                owner.runtimeState().setMetadata(owner.productLine(), Math.addExact(owner.runtimeState().revision(),
                        owner.productLine() == ProductLine.SPOT ? 2 : 1));
            }
            if (batch.kind != OrderBatchKind.CANCEL) finishOrderBatchItem(batch, pending, batch.lastMatchingResult);
            owner.runtimeState().releaseMatcherSettlement(settledEvent);
            batch.itemSettlementEvent = null;
            batch.lastMatchingResult = null;
            return startOrderBatchItem(batch, pending, timestamp, position, false);
        } catch (/* 当前 Matcher、账户结算或实时捕获运行异常，按所在方法保存、报告恢复或标记实时失败 */ RuntimeException failure) {
            throw failOrderBatch(batch, pending, "batch item Lane settlement failed", failure);
        }
    }

    /**
     * 保存当前项 Matcher 事实并校验直达事件、收集执行明细；账户或准入仍待完成则返回，否则结束该项。应用失败按撮合与 Core 分歧处理。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param matchingResult 当前已有的 Matcher 证据或直达事件
     */
    void applyCompletedOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                      MatchingResult matchingResult) {
        batch.lastMatchingResult = matchingResult;
        // 当前准备、撮合、收集或编码的批内项
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        try {
            applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (batch.itemSettlementEvent != null || batch.itemAdmission != null) return;
            finishOrderBatchItem(batch, pending, matchingResult);
        } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ RuntimeException exception) {
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    /**
     * 从 Matcher 接受与否决定该项状态，收变化身份并保存非撤单项结果，释放改单准入后推进游标；撮合已发生后的异常必须恢复，不能伪装普通拒单或重试同一修改。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param matchingResult 当前已有的 Matcher 证据或直达事件
     */
    private void finishOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                      MatchingResult matchingResult) {
        // 当前准备、撮合、收集或编码的批内项
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        // 由当前项 Matcher 接受与否决定的状态
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        // 与该项状态配套的结果码
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
            if (batch.kind != OrderBatchKind.CANCEL) appendOrderBatchResult(batch, item, status, resultCode);
            releaseBatchAmendAdmission(batch);
            item.cancelSymbol = null;
            batch.nextIndex++;
        } catch (/* 当前输入、准入或应用错误，按所在阶段拒绝单项、放弃流水准备或报告恢复故障 */ RuntimeException exception) {
            // 该项撮合已经应用，结算运行失败也需要从快照与日志恢复；不能改成单项拒绝或重做同一修改。
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    /**
     * 标记 Matcher 故障，清未登记触发子单并停止仍占用槽的撮合续步，结束批次账户作用域，再交原运行时生成致命恢复异常。调用方抛出该异常，不能继续当普通项拒绝处理。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param detail 需要恢复的故障说明
     * @param cause 可空原始失败原因
     */
    public FatalMatchingDivergenceException failOrderBatch(
            OrderBatchPending batch, CommandSlot pending, String detail, Throwable cause) {
        // 已发生的撮合事实不能在此撤销。
        // 标记故障并停止续步，将原命令交恢复边界处理，不能继续当成普通拒单。
        owner.matchingAdapter().poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        owner.admissions().clearQueuedMatching();
        owner.pendingMatching().forEach(/* 仍占用原序号的命令槽，发生批次故障时停止其原撮合续步 */ value -> {
            if (value.claimed) {
                value.resetMatchingContinuation();
            }
        });
        owner.runtimeState().endOrderBatchMutationScope();
        return owner.failMatching(pending, detail, cause);
    }

    /**
     * 要求 PLACE 有准入副本，必要时绑定实时身份并复制非撤单项执行明细；PLACE 和 AMEND 必须使用当前项的直达事件，账户资金事实仍由事件执行，撤单变化由片段收集入口处理。
     * @param batch 本条批量命令的复用上下文
     * @param item 本批当前单项输入与结果槽
     * @param pending 本批仍占用的命令槽
     * @param matchingResult 当前已有的 Matcher 证据或直达事件
     */
    void applyOrderBatchMatcherResult(
            OrderBatchPending batch, OrderBatchItem item, CommandSlot pending,
            MatchingResult matchingResult) {
        if (item.admittedOrder == null && batch.kind == OrderBatchKind.PLACE)
            throw new IllegalStateException("batch place admission receipt is missing");
        if (item.realtimeTakerOrder == null && owner.realtimeCapture() != null
                && owner.realtimeCapture().active()) item.realtimeTakerOrder = item.admittedOrder;

        if (batch.kind != OrderBatchKind.CANCEL) {
            // 当前项复制的成交事件数量
            int trades = item.captureExecutions(matchingResult.matcherEvents());
            item.executionTakerUserId = pending.command().header().userId();
            if (trades != 0) {
                item.executionCount += trades;
                batch.tradeCount = Math.addExact(batch.tradeCount, trades);
            }
        }
        switch (batch.kind) {
            case PLACE -> {
                if (batch.itemSettlementEvent != matchingResult || !batch.itemSettlementEvent.direct())
                    throw new IllegalStateException("batch place requires its direct Lane event");
                return;
            }
            case CANCEL -> { return; } // 撤单片段的账户变化已经在整理各项结果之前收集。

            case AMEND -> {
                if (batch.itemSettlementEvent != matchingResult || !batch.itemSettlementEvent.direct())
                    throw new IllegalStateException("batch amendment requires a direct Lane event");
                return;
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
    }

    /**
     * 只把已经决定的状态和结果码写入该项，不编码整批或重新执行账户业务。
     * @param batch 本条批量命令的复用上下文
     * @param item 本批当前单项输入与结果槽
     * @param status 本项已经决定的响应状态
     * @param resultCode 本项已经决定的结果码
     */
    void appendOrderBatchResult(OrderBatchPending batch, OrderBatchItem item,
                                        ResponseStatus status, CoreResultCode resultCode) {
        item.status = status;
        item.resultCode = resultCode;
    }

    /**
     * 开始整批收尾并建立账户位图，收整批结算与财库差额，必要时等待最终账户盖戳和响应编码；核对参与 Lane、资金及终态响应后发布、保存整批 APPLIED 终态。再摘排序和品种依赖、退休命令并归池，各项成功与否仍保存在响应内。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     */
    public CoreResponse finishOrderBatch(OrderBatchPending batch, CommandSlot pending,
                                          long clusterTimestamp, long clusterPosition) {
        if (!batch.finishing()) {
            owner.activateFactContext(pending.command(), pending.fingerprint());
            batch.finishing(true);
        }
        initializeOrderBatchLaneContext(batch, pending);
        if (batch.settlementEvent != null && !batch.settlementsCollected()) {
            // 整批事件实际参与账户位图
            long settlementLaneMask = batch.settlementEvent.requiredLaneMask();
            // 整批事件是否携带非零最终账户提交序号
            boolean finalLaneCommit = batch.settlementEvent.commitSequence() != 0;
            // 整批事件已经收集的可空财库差额，未就绪时为空
            RuntimeTreasuryDelta delta = owner.runtimeState().collectMatcherSettlement(
                    batch.settlementEvent, null, owner.terminalRetention());
            if (delta == null) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            batch.mergeTreasuryDelta(delta);
            owner.runtimeState().releaseMatcherSettlement(batch.settlementEvent);
            batch.settlementsCollected(true);
            if (finalLaneCommit) {
                pending.completeLanes(settlementLaneMask);
                batch.laneCommitCompleted(true);
            }
            owner.commits.publication.request();
        }
        owner.runtimeState().completePendingReservations(batch.sequence);
        if (!batch.laneCommitCompleted() || batch.preparedResponse == null) {
            if (batch.laneCommitEvent == null) {
                owner.runtimeState().releaseOwnerLaneAccess();
                batch.laneCommitEvent = owner.runtimeState().dispatchBatchCommit(batch.sequence,
                        batch.laneCommitCompleted() ? List.of() : batch.changedUserIds,
                        batch.laneCommitCompleted() ? List.of() : batch.runtimeChangedOrderIds,
                        clusterTimestamp, clusterPosition, pending.command().header().userId(), batch, owner.identities());
            }
            if (batch.laneCommitEvent != null) {
                if (!owner.runtimeState().laneCommitComplete(batch.laneCommitEvent)) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                pending.completeLanes(batch.laneCommitEvent.committedLaneMask());
                owner.runtimeState().releaseLaneCommit(batch.laneCommitEvent);
                batch.laneCommitEvent = null;
                owner.commits.publication.request();
            }
            batch.laneCommitCompleted(true);
        }
        // 批量响应直接消费 batch；全局发布消费 runtime 变更，不能再复制到普通单结果容器。
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        if (pending.expectedLaneMask() != batch.actualLaneMask) {
            throw failOrderBatch(batch, pending, "order batch account lane mask mismatch", null);
        }
        try {
            owner.runtimeState().applyCollectedTreasuryBeforeActivation(batch.treasuryDelta);
            owner.runtimeState().setMetadata(owner.productLine(),
                    Math.incrementExact(owner.runtimeState().revision()));
            if (pending.completedLaneMask() != pending.expectedLaneMask()) {
                throw new IllegalStateException("order batch account lane mask mismatch");
            }
            owner.commits.requireCompleteAccountLanes(pending);
        } catch (/* 最终账户位图或修订核验失败，必须报告批次恢复故障 */ RuntimeException validationFailure) {
            throw failOrderBatch(batch, pending, "order batch final validation failed", validationFailure);
        }
        captureCommittedBatchTrades(batch);
        owner.commits.publication.complete();
        owner.commits.closeFinancialDispatch(pending);
        if (batch.preparedResponse == null)
            throw failOrderBatch(batch, pending, "final account Lane did not prepare the batch response", null);
        // 最终账户已经编码好的整批响应缓冲
        byte[] responseData = batch.preparedResponse;
        // 整批响应缓冲中的有效字节数
        int responseLength = batch.preparedResponseLength;
        owner.terminalTradeCount(Math.addExact(owner.terminalTradeCount(), batch.tradeCount));
        owner.matchingFlow.commitMatchingSequence(batch.sequence);
        owner.resultLedger().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(),
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, responseData, 0, responseLength);
        if (batch.hasPreparedResponseSlot()) batch.transferPreparedResponseOwnership();
        owner.runtimeState().endOrderBatchMutationScope();
        if (pending.takePipelinedSettlementCounted()) {
            owner.commits.dispatchedSettlementInFlight(owner.commits.dispatchedSettlementInFlight() - 1);
            if (owner.commits.dispatchedSettlementInFlight() < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        // 退休命令槽前保留的原消息，用于之后构造响应
        CoreMessage responseCommand = pending.command();
        unregisterBatch(pending, batch);
        owner.matchingFlow.removePendingMatching(batch.sequence);
        unregisterPipelinedBatchSymbols(batch);
        owner.matchingProgress.batchCompleted();
        owner.matchingProgress.submitDeferredMatchingAfterBatch();
        // 本轮已完成时返回的整批终态
        CoreResponse response = responseCommand.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence,
                responseData, 0, responseLength);
        releaseOrderBatchPending(batch);
        return owner.finishFactContext(response);
    }

    /**
     * 只初始化一次，从已观察结算位、整批事件和变化用户汇总实际参与账户；没有原生动作则绑定复用占位事实。登记到命令槽后按既有最终账户提交情况补完成位。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    void initializeOrderBatchLaneContext(OrderBatchPending batch, CommandSlot pending) {
        if (batch.laneContextInitialized()) return;
        // 从本批已有结算事实与变化用户汇总的账户位图
        long expectedLaneMask = batch.observedSettlementLaneMask;
        if (batch.settlementEvent != null) {
            expectedLaneMask |= batch.settlementEvent.requiredLaneMask();
        }
        for (/* 当前批内项或变更用户的遍历下标 */ int index = 0; index < batch.changedUserIds.size(); index++) {
            expectedLaneMask |= owner.matchingAdapter().topology().accountLaneMask(
                    batch.changedUserIds.valueAt(index));
        }
        batch.actualLaneMask = expectedLaneMask;
        // 本批最后 Matcher 事实，完全没有原生动作时使用复用占位结果
        var finalMatchingResult = batch.lastMatchingResult == null
                ? batch.noNativeCommandResult.withCoreSequenceInPlace(batch.sequence)
                : batch.lastMatchingResult;
        pending.result(
                finalMatchingResult, expectedLaneMask, owner.commits.validAccountLaneMask());
        if (batch.laneCommitCompleted() && expectedLaneMask != 0) pending.completeLanes(expectedLaneMask);
        batch.laneContextInitialized(true);
    }

    /**
     * 要求当前批次及项游标有效，返回当前项需要的 Matcher 分片。
     * @param batch 本条批量命令的复用上下文
     */
    int orderBatchMatcherShard(OrderBatchPending batch) {
        if (batch == null || batch.nextIndex < 0 || batch.nextIndex >= batch.items.size()) {
            throw new IllegalArgumentException("order batch matcher route is unavailable");
        }
        return matcherShardForBatchItem(batch, batch.items.get(batch.nextIndex));
    }

    /**
     * 汇总尚未提交项还会用到的分片，已提交的流水整批直接为空；当前直达子项或撤单片段不重复计入，撤改单路由已消失的项跳过。
     * @param batch 本条批量命令的复用上下文
     */
    long remainingMatcherShardMask(OrderBatchPending batch) {
        // 尚未提交后缀仍可能访问的 Matcher 分片位图
        long mask = 0;
        if (batch.pipelined && batch.sequence != 0
                && owner.pendingMatching().get(batch.sequence).isMatchingSubmitted()) return 0;
        // 排除当前已派发子项或撤单片段后的第一个后缀下标
        int firstUnsubmitted = batch.nextIndex;
        if (batch.itemSettlementEvent != null) {
            firstUnsubmitted = batch.kind == OrderBatchKind.CANCEL
                    ? batch.cancellationChunkEnd : batch.nextIndex + 1;
        }
        for (/* 当前批内项或变更用户的遍历下标 */ int index = firstUnsubmitted; index < batch.items.size(); index++) {
            if (batch.kind != OrderBatchKind.PLACE && owner.activeOrderIndex().activeOrderRoute(
                    batch.kind == OrderBatchKind.CANCEL ? batch.items.get(index).orderId()
                            : batch.items.get(index).originalOrderId()) == null) continue;
            mask |= 1L << matcherShardForBatchItem(batch, batch.items.get(index));
        }
        return mask;
    }

    /**
     * PLACE 取输入品种，撤改单取现有路由；改单原单不在路由时查本批较早成功项。空品种按既有规则走零分片，其余使用解码缓存或原路由器。
     * @param batch 本条批量命令的复用上下文
     * @param item 本批当前单项输入与结果槽
     */
    private int matcherShardForBatchItem(OrderBatchPending batch, OrderBatchItem item) {
        // 当前品种名称，可能来自输入、活动路由或较早成功项
        String instrumentId = switch (batch.kind) {
            case PLACE -> ((PlaceOrderCommand) item.command).instrumentId();
            case CANCEL -> {
                // Owner 已有活动订单索引给出的可空稳定路由
                var route = owner.activeOrderIndex().activeOrderRoute(((CancelOrderCommand) item.command).orderId());
                yield route == null ? "" : route.instrumentId();
            }
            case AMEND -> {
                // Owner 已有活动订单索引给出的可空稳定路由
                var route = owner.activeOrderIndex().activeOrderRoute(((AmendOrderCommand) item.command).originalOrderId());
                // 当前品种原先登记的批次、较早成功项或其品种，具体类型见声明
                String previous = previousBatchOrderInstrument(batch, item.originalOrderId());
                yield route == null ? previous == null ? "" : previous : route.instrumentId();
            }
        };
        return instrumentId.isBlank() ? 0 : batch.decodedCommand == null
                ? owner.matchingAdapter().matcherShardId(instrumentId)
                : batch.decodedCommand.matcherShard(owner.matchingAdapter(), instrumentId);
    }

    /**
     * 开启原命令事实及批次变更作用域，取得整批提交权并开始发布积累。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    void beginOrderBatchCommitContext(OrderBatchPending batch, CommandSlot pending) {
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.resultBuilder.beginCommand();
        owner.runtimeState().beginOrderBatchMutationScope();
        batch.beginCommit();
        owner.commits.publication.begin();
    }

    /**
     * 独立预备撤单可直接建上下文；其他流水批次要求 Owner 已登记准入且事件成功完成，再收集释放整批准入并请求发布。
     * @param batch 本条批量命令的复用上下文
     * @param pending 本批仍占用的命令槽
     */
    public void beginPipelinedOrderBatchCommit(OrderBatchPending batch, CommandSlot pending) {
        // 事件已经解析的改单准入，后续仍需 Owner 核对全局订单身份
        PlaceBatchAdmissionEvent admission = batch.placeBatchAdmissionEvent;
        // 当前是否为可直接建立提交上下文的独立预备批量撤单
        boolean preparedCancel = pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL;
        if (!preparedCancel && (!batch.admissionCollected() || admission == null || !admission.complete()
                || admission.rejection() != null)) {
            throw new IllegalStateException("pipelined order batch admission is not ready to commit");
        }
        beginOrderBatchCommitContext(batch, pending);
        if (!preparedCancel) {
            owner.runtimeState().collectPlaceBatchAdmission(admission, null);
            owner.runtimeState().releasePlaceBatchAdmission(admission);
            batch.placeBatchAdmissionEvent = null;
            owner.commits.publication.request();
        }
    }

    /**
     * 只有前面各批均已登记流水品种且候选全部品种不重叠时返回假；否则按既有依赖规则认为冲突。
     * @param candidate 待判断能否与前批次并行的下单批次
     */
    boolean conflictsWithEarlierPipelinedBatch(OrderBatchPending candidate) {
        if (activePipelinedOrderBatches != pendingBatchCount() - 1) return true;
        for (OrderBatchItem /* 当前准备、撮合、收集或编码的批内项 */ item : candidate.items) {
            if (pipelinedBatchBySymbol.containsKey(((PlaceOrderCommand) item.command).instrumentId())) return true;
        }
        return false;
    }

    /**
     * 仅在当前批有序提交范围内读取保留的主动单及成交事件，逐项写实时捕获并解除本项身份引用；捕获异常标记实时失败，不重复执行成交。
     * @param batch 本条批量命令的复用上下文
     */
    private void captureCommittedBatchTrades(OrderBatchPending batch) {
        if (owner.realtimeCapture() == null) return;
        for (/* 当前整理实时成交的批内下标 */ int itemIndex = 0; itemIndex < batch.items.size(); itemIndex++) {
            // 当前准备、撮合、收集或编码的批内项
            OrderBatchItem item = batch.items.get(itemIndex);
            // 本项保留到提交时的主动订单身份
            var taker = item.realtimeTakerOrder;
            item.realtimeTakerOrder = null;
            if (taker == null || owner.realtimeCapture() == null || !owner.realtimeCapture().active()) continue;
            try {
                // 当前项实时成交的从零递增编号
                int fill = 0;
                for (/* 当前项 Matcher 事件下标 */ int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
                    // 当前改单准入或撤单直达事件，类型见声明
                    MatcherEvent event = item.executionEvents.get(eventIndex);
                    if (event.eventType() == MatcherEventType.TRADE)
                        owner.realtimeCapture().trade(taker, batch.sequence, fill++, event.price(), event.size(),
                                event.matchedOrderId(), event.matchedOrderUid());
                }
            } catch (/* 当前 Matcher、账户结算或实时捕获运行异常，按所在方法保存、报告恢复或标记实时失败 */ RuntimeException failure) { owner.realtimeCapture().failed(); }
        }
    }

    /**
     * 按 PLACE、CANCEL 或 AMEND 已解码批次借上下文并逐项保存输入；改单项结果编号使用新单编号，整批时间和操作一并绑定，其他消息类型抛错。
     * @param message 当前整批原始 Core 命令
     * @param decodedCommand 已经按消息类型解码的整批输入
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     */
    OrderBatchPending decodeOrderBatch(CoreMessage message, DecodedMatchingCommand decodedCommand,
                                               long clusterTimestamp, long clusterPosition) {
        // 整批原消息的类型
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            // 按当前种类取出的单项或整批解码请求，具体类型见声明
            PlaceOrderBatchCommand command = decodedCommand.placeOrderBatch();
            List<PlaceOrderCommand> orders = command.orders();
            int count = orders.size();
            // 新借、查询或当前正在处理的批次上下文
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.PLACE, count,
                    clusterTimestamp, clusterPosition);
            batch.decodedCommand = decodedCommand;
            for (int i = 0; i < count; i++) {
                PlaceOrderCommand value = orders.get(i);
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            // 按当前种类取出的单项或整批解码请求，具体类型见声明
            CancelOrderBatchCommand command = decodedCommand.cancelOrderBatch();
            List<CancelOrderCommand> orders = command.orders();
            int count = orders.size();
            // 新借、查询或当前正在处理的批次上下文
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.CANCEL, count,
                    clusterTimestamp, clusterPosition);
            batch.decodedCommand = decodedCommand;
            for (int i = 0; i < count; i++) {
                CancelOrderCommand value = orders.get(i);
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            // 按当前种类取出的单项或整批解码请求，具体类型见声明
            AmendOrderBatchCommand command = decodedCommand.amendOrderBatch();
            List<AmendOrderCommand> orders = command.orders();
            int count = orders.size();
            // 新借、查询或当前正在处理的批次上下文
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.AMEND, count,
                    clusterTimestamp, clusterPosition);
            batch.decodedCommand = decodedCommand;
            for (int i = 0; i < count; i++) {
                AmendOrderCommand value = orders.get(i);
                batch.addItem(value.replacementOrderId(), value.originalOrderId(),
                        value.replacementOrderId(), value);
            }
            return batch;
        }
        throw new IllegalArgumentException("unsupported order batch type");
    }

    /**
     * 从 Owner 池中取第一个容量足够的上下文并移出池，没有则按项数创建，再绑定本批种类和原日志元数据。
     * @param kind 待借批次的下单、撤单或改单类别
     * @param itemCount 本批需要容纳的项数量
     * @param clusterTimestamp 整批固定使用的 Cluster 业务时间
     * @param clusterPosition 整批固定使用的复制日志位置
     */
    OrderBatchPending acquireOrderBatchPending(
            OrderBatchKind kind, int itemCount, long clusterTimestamp, long clusterPosition) {
        // 本次从池中取出的可空批次上下文
        OrderBatchPending selected = null;
        // Owner 批次复用池迭代器，选中足够容量的对象后移出
        Iterator<OrderBatchPending> iterator = orderBatchPendingPool.iterator();
        while (iterator.hasNext()) {
            // 池内当前候选批次，容量足够才借用
            OrderBatchPending candidate = iterator.next();
            if (candidate.capacity() < itemCount) continue;
            iterator.remove();
            selected = candidate;
            break;
        }
        if (selected == null) selected = new OrderBatchPending(itemCount, owner.responseArena());
        return selected.initialize(kind, clusterTimestamp, clusterPosition);
    }

    /**
     * 空批次直接返回；否则解除品种登记及改单事件，清理对象后在池未满时归池。排序链接和所有异步借用由调用方提前处理。
     * @param batch 本条批量命令的复用上下文
     */
    void releaseOrderBatchPending(OrderBatchPending batch) {
        if (batch == null) return;
        unregisterPipelinedBatchSymbols(batch);
        releaseBatchAmendAdmission(batch);
        batch.clear();
        if (orderBatchPendingPool.size() < owner.configuration().maxPendingMatching()) orderBatchPendingPool.addFirst(batch);
    }

    /**
     * PLACE 留到实际准入检查；撤改单只用 Owner 稳定路由核对已有原单归属，缺失订单留到各项准入判定，不跨线程读账户订单表。
     * @param batch 本条批量命令的复用上下文
     * @param userId 本批请求用户，账户按该用户路由
     */
    void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem /* 当前准备、撮合、收集或编码的批内项 */ item : batch.items) {
            // 撤单或改单本次需要检查归属的原订单编号
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            // Owner 只用已有路由索引核对稳定订单身份。
            // 账户订单表由工作线程修改，入口不能跨线程直接探查。
            // Owner 已有活动订单索引给出的可空稳定路由
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            if (route != null && route.userId() != userId) {
                throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH",
                        "order batch contains another user's order");
            }
        }
    }

    /**
     * 对尚未交账户处理的拒绝项检查全局修订未变；若已经变化则结束批次作用域并报告恢复故障。批次参数在当前实现中不读取。
     * @param batch 沿用调用签名的批次引用，当前检查不读取
     * @param pending 本批仍占用的命令槽
     * @param runtimeRevisionBefore 本项尚未交账户处理前保存的全局修订
     * @param exception 本项业务拒绝或输入异常，用作恢复故障原因
     */
    void requireUnchangedRejectedBatchItem(OrderBatchPending batch, CommandSlot pending,
                                                   long runtimeRevisionBefore,
                                                   RuntimeException exception) {
        if (owner.runtimeState().revision() != runtimeRevisionBefore) {
            owner.runtimeState().endOrderBatchMutationScope();
            throw owner.failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    /**
     * 要求当前项已有直达事件，保存原先撤路由，再按下单准入副本或改单解析准入准备槽内固定 Matcher 任务。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     * @param item 本批当前单项输入与结果槽
     * @param shard 当前已确定的 Matcher 分片
     * @param direct 当前项已准备的直达结算事件
     */
    java.util.function.Supplier<?> prepareOrderBatchMatchingCommand(
            CommandSlot pending, OrderBatchPending batch, OrderBatchItem item, int shard,
            com.surprising.aeron.service.settlement.MatcherSettlementEvent direct) {
        java.util.Objects.requireNonNull(direct, "batch item requires its direct settlement");
        pending.matcherPreCancellations(owner.matchingFlow.preMatchingCancellationOrders(pending));
        // 整批命令请求用户
        long userId = pending.command().header().userId();
        return switch (batch.kind) {
            case PLACE -> pending.prepareAdmittedPlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(item.admittedOrder, "batch admission receipt"), direct);
            case AMEND -> {
                // 事件已经解析的改单准入，后续仍需 Owner 核对全局订单身份
                var admission = java.util.Objects.requireNonNull(batch.replacementAdmission, "batch amend admission");
                yield pending.prepareReplaceMatching(owner, shard, admission.resolved().orderId(), userId,
                        admission.originalOrderId(), admission.command().instrumentId(), admission.resolved(), direct);
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        };
    }

    /**
     * 为整批已准入订单准备带最终 Core 提交水位的直达结算事件，并保留 Matcher 发布租约；实际参与 Lane 随撮合事实确定。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     * @param shard 当前已确定的 Matcher 分片
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent preparePipelinedPlaceBatch(
            CommandSlot pending, OrderBatchPending batch, int shard) {
        // 当前任务预先借用的直达结算事件
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = owner.runtimeState().prepareDirectMatcherSettlement(
                pending.sequence(), owner.commits.validAccountLaneMask(),
                null, batch.preparedAdmittedOrders, batch.items.size(), pending.command().header().commandId(),
                shard, owner.identities(), pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), batch);
        direct.reserveMatcherPublication();
        return direct;
    }

    /**
     * 为顺序下单的当前准入项借直达事件，账户提交序号用零保留到整批最后，再取得 Matcher 发布租约。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     * @param admitted 当前项由账户提供的准入订单副本
     * @param shard 当前已确定的 Matcher 分片
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareBatchPlaceItem(
            CommandSlot pending, OrderBatchPending batch, OrderRuntime admitted, int shard) {
        // 当前任务预先借用的直达结算事件
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = owner.runtimeState().prepareDirectMatcherSettlement(
                pending.sequence(), 0, owner.commits.validAccountLaneMask(), admitted, null, 1,
                pending.command().header().commandId(), shard, owner.identities(),
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), batch);
        direct.reserveMatcherPublication();
        return direct;
    }

    /**
     * 为顺序改单的解析准入借直达事件，同样把最终账户提交留到批末，并取得 Matcher 发布租约。
     * @param pending 本批仍占用的命令槽
     * @param admission 已收齐的改单解析准入
     * @param shard 当前已确定的 Matcher 分片
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareBatchAmend(
            CommandSlot pending, ResolvedMatchingAdmission admission, int shard) {
        // 当前任务预先借用的直达结算事件
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = owner.runtimeState().prepareDirectReplacement(
                pending.sequence(), 0, owner.commits.validAccountLaneMask(), admission,
                pending.command().header().commandId(), shard, owner.identities(),
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), null);
        direct.reserveMatcherPublication();
        return direct;
    }

    /**
     * 用当前片段有效订单前缀准备直达撤单事件，只有最后实际片段推进最终账户水位，再保留 Matcher 发布租约。
     * @param pending 本批仍占用的命令槽
     * @param batch 本条批量命令的复用上下文
     * @param shard 当前已确定的 Matcher 分片
     * @param finalChunk 是否为最后实际撤单片段，决定能否推进最终账户水位
     * @param count 撤单原语缓冲中本次有效项数
     */
    private com.surprising.aeron.service.settlement.MatcherSettlementEvent prepareCancelBatch(
            CommandSlot pending, OrderBatchPending batch, int shard, boolean finalChunk, int count) {
        // 当前任务预先借用的直达结算事件
        com.surprising.aeron.service.settlement.MatcherSettlementEvent direct = owner.runtimeState().prepareDirectCancelBatch(
                pending.sequence(), finalChunk, pending.command().header().userId(),
                batch.preparedCancellationOrderIds, count, pending.command().header().commandId(), shard,
                owner.identities(), pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), batch);
        direct.reserveMatcherPublication();
        return direct;
    }

    /**
     * 整批流水下单的可复用 Matcher 任务，Owner 绑定输入，Matcher 顺序执行，交接结束后由批次清理。
     */
    static final class PipelinedPlaceBatchSubmission implements java.util.function.Supplier<Object> {
        /** 当前固定任务所属的批次执行入口。 */
        private OrderBatchExecutor executor;
        /** 当前仍在生命周期内的原命令槽。 */
        private CommandSlot pending;
        /** 当前任务借用的整批输入及结果槽。 */
        private OrderBatchPending batch;
        /** 流水下单共用的账户用户编号。 */
        private long userId;
        /** 当前固定任务唯一 Matcher 分片。 */
        private int shard;

        /**
         * 把已固定的执行器、槽、批次、用户和分片放进本批复用任务，供 Matcher 之后调用。
         * @param executor 当前固定任务使用的批次执行器
         * @param pending 本批仍占用的命令槽
         * @param batch 本条批量命令的复用上下文
         * @param userId 本批请求用户，账户按该用户路由
         * @param shard 当前已确定的 Matcher 分片
         */
        void prepare(OrderBatchExecutor executor, CommandSlot pending, OrderBatchPending batch, long userId, int shard) {
            this.executor = executor;
            this.pending = pending;
            this.batch = batch;
            this.userId = userId;
            this.shard = shard;
        }

        /**
         * 在任务无人借用后解除本轮引用并清原语参数，保留对象。
         */
        void clear() {
            executor = null;
            pending = null;
            batch = null;
            userId = 0;
            shard = 0;
        }

        /**
         * 在 Matcher 准备该用户的品种路由，再逐项执行已准入流水批次；任务执行前必须已取得完整输入。
         */
        @Override
        public Object get() {
            executor.owner.matchingAdapter().prepareOrderRoutes(userId, batch.preparedSymbols);
            return executor.submitPreparedPipelinedPlaceBatch(pending, batch, userId, shard);
        }
    }

    /**
     * 同分片连续撤单片段的固定 Matcher 任务，借用整批项槽和直达事件，保留原请求顺序。
     */
    static final class CancelBatchChunkSubmission implements java.util.function.Supplier<Object> {
        /** 当前固定任务所属的批次执行入口。 */
        private OrderBatchExecutor executor;
        /** 当前仍在生命周期内的原命令槽。 */
        private CommandSlot pending;
        /** 当前任务借用的整批输入及结果槽。 */
        private OrderBatchPending batch;
        /** 当前固定任务唯一 Matcher 分片。 */
        private int shard;
        /** 撤单片段在整批中的包含起点。 */
        private int start;
        /** 撤单片段在整批中的排他终点。 */
        private int chunkEnd;
        /** 撤单片段预先准备并持有发布租约的直达结算事件。 */
        private com.surprising.aeron.service.settlement.MatcherSettlementEvent direct;

        /**
         * 保存本次同分片撤单片段的起止游标、命令槽及直达事件，供 Matcher 顺序执行。
         * @param executor 当前固定任务使用的批次执行器
         * @param pending 本批仍占用的命令槽
         * @param batch 本条批量命令的复用上下文
         * @param shard 当前已确定的 Matcher 分片
         * @param start 撤单片段包含起点
         * @param chunkEnd 撤单片段排他终点
         * @param direct 当前项已准备的直达结算事件
         */
        void prepare(OrderBatchExecutor executor, CommandSlot pending, OrderBatchPending batch, int shard,
                     int start, int chunkEnd, com.surprising.aeron.service.settlement.MatcherSettlementEvent direct) {
            this.executor = executor;
            this.pending = pending;
            this.batch = batch;
            this.shard = shard;
            this.start = start;
            this.chunkEnd = chunkEnd;
            this.direct = direct;
        }

        /**
         * 任务交接结束后清执行器、槽、批次、事件及范围，供下一片段复用。
         */
        void clear() {
            executor = null;
            pending = null;
            batch = null;
            shard = 0;
            start = 0;
            chunkEnd = 0;
            direct = null;
        }

        /**
         * 在 Matcher 按片段原顺序逐项撤单，局部下标从零写直达事件；保留该事件为批次事实，返回首个原生结果供管线交接。
         */
        @Override
        public Object get() {
            // 本任务第一个原生 Matcher 结果，后续完整事实仍在直达事件
            exchange.core2.core.common.MatcherResult firstNative = null;
            for (/* 当前批内项或变更用户的遍历下标 */ int index = start; index < chunkEnd; index++) {
                // 当前准备、撮合、收集或编码的批内项
                OrderBatchItem item = batch.items.get(index);
                // 当前项原生 Matcher 执行结果
                var result = executor.owner.matchingAdapter().cancelDirectBatchItem(
                        shard, index - start, pending.sequence(), pending.command().header().commandId(), item.orderId,
                        pending.command().header().submittedAtEpochMillis(),
                        pending.command().header().userId(), item.cancelSymbol, direct);
                if (firstNative == null) firstNative = result;
            }
            batch.lastMatchingResult = direct;
            return firstNative;
        }
    }
}
