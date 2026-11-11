package com.surprising.aeron.service.order;

import com.surprising.aeron.service.position.Position;

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
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.result.OrderRuntimeSource;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
public final class OrderBatchProcessor {
    /** 唯一所属交易 Owner，共用原有账户及提交状态。 */
    final TradingCoreRuntime owner;

    private OrderBatchProcessor.PendingBatch firstBatch, lastBatch;
    private int batchCount;

    /** 返回排序链是否还有未退休批次，不表示其已开始执行。 */
    public boolean hasPendingBatches() { return firstBatch != null; }
    /** 借用当前最早批次，空链返回空。 */
    OrderBatchProcessor.PendingBatch firstBatch() { return firstBatch; }
    /** 返回已登记排序链的批次数量。 */
    int pendingBatchCount() { return batchCount; }
    /** 按完整 Core 序号从已有命令环取得批次，不另建序号到批次的索引。 */
    public OrderBatchProcessor.PendingBatch batch(long sequence) {
        OrderCommand pending = owner.pendingMatching().get(sequence);
        return pending == null ? null : pending.orderBatch;
    }

    /** 核对命令槽、批次序号和未占用链接，要求序号递增，再把批次连到尾部并绑定原槽。 */
    void registerBatch(OrderCommand pending, OrderBatchProcessor.PendingBatch batch) {
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

    /** 核对原登记关系，摘除前后链接并解除命令槽绑定，更新首尾及数量。 */
    void unregisterBatch(OrderCommand pending, OrderBatchProcessor.PendingBatch batch) {
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

    /** 逐个解除排序链与命令槽的登记，不在此清对象、归池或回滚账户。 */
    public void clearPendingBatches() {
        while (firstBatch != null)
            unregisterBatch(owner.pendingMatching().get(firstBatch.sequence), firstBatch);
    }

    final HashMap<String, OrderBatchProcessor.PendingBatch> pipelinedBatchBySymbol = new HashMap<>();

    int activePipelinedOrderBatches;

    /** Owner 持有的已退休批次复用池，Matcher 或账户仍借用时不能归还。 */
    final java.util.ArrayDeque<OrderBatchProcessor.PendingBatch> orderBatchPendingPool = new java.util.ArrayDeque<>();

    /** 解码并检查整批订单身份，已知业务错误释放预备批次并拒绝。其他输入错误按原撮合顺序记拒绝。成功分配整批序号并登记依赖，尝试流水或队首激活，尚未完成则释放 Owner 账户访问权并返回待处理状态。 */
    public CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, TradingCoreRuntime.SourceKey sourceKey,
                                                  CommandFingerprint fingerprint) {
        OrderBatchProcessor.PendingBatch batch = null;
        OrderCommand.Decoded decodedCommand;
        try {
            decodedCommand = owner.decodeMatchingCommand(message);
            batch = decodeOrderBatch(message, decodedCommand, clusterTimestamp, clusterPosition);
            validateOrderBatchIdentity(batch, message.header().userId());
        } catch (CoreStateRejectedException exception) {
            releaseOrderBatchPending(batch);
            return owner.rejected(CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            releaseOrderBatchPending(batch);
            return owner.matchingFlow.recordRejectedMatching(message, sourceKey, fingerprint,
                    exception instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        long sequence = owner.nextAppliedSequence();
        OrderCommand pending = owner.admissions().newPendingMatching(sequence, batch.operation(), message, fingerprint, decodedCommand);
        batch.sequence = sequence;
        owner.matchingFlow.putPendingMatching(pending);
        owner.admissions().registerPendingLifecycle(pending);
        registerBatch(pending, batch);
        owner.pendingMatching().registerSubmission(sequence, orderBatchMatcherShard(batch));
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        CoreResponse completed = tryActivatePipelinedOrderBatch(batch, pending)
                ? null
                : !pending.clusterIndependent && owner.pendingMatching().firstSequence() == sequence
                ? activateOrderBatch(batch, pending, false) : null;
        if (completed != null) return completed;
        owner.runtimeState().releaseOwnerLaneAccess();
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 首次建立本批提交上下文，否则恢复原上下文，再标记激活。 */
    public CoreResponse activateOrderBatch(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                             boolean deferCompletion) {
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
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp,
                batch.clusterPosition, deferCompletion);
        if (response == null) owner.clearFactContext();
        return response;
    }

    /** 独立批量撤单可先尝试提交。 */
    boolean tryActivatePipelinedOrderBatch(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        if (pending.clusterIndependent && batch.kind == OrderBatchProcessor.Kind.CANCEL && !batch.sequentialAdmission) {
            batch.activated(true);
            owner.matchingFlow.submitMatching(pending);
            return true;
        }
        if (batch.sequentialAdmission || batch.kind != OrderBatchProcessor.Kind.PLACE
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

    /** 首次登记本批各品种的最新批次引用并计数，独立模式沿用入口已经核对的依赖。违背所有权约束抛错，已写入的前缀不在此回滚。 */
    void registerPipelinedBatchSymbols(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        if (batch.pipelineRegistered()) return;
        for (String instrumentId : batch.preparedSymbols) {
            OrderBatchProcessor.PendingBatch previous = pipelinedBatchBySymbol.put(instrumentId, batch);
            if (previous != null && previous != batch && !pending.clusterIndependent) {
                throw new IllegalStateException("pipelined instrumentId ownership conflict");
            }
        }
        batch.pipelineRegistered(true);
        activePipelinedOrderBatches++;
    }

    /** 只移除仍指向本批的品种条目，清登记位并递减活动数，计数为负时抛错。 */
    void unregisterPipelinedBatchSymbols(OrderBatchProcessor.PendingBatch batch) {
        if (!batch.pipelineRegistered()) return;
        for (String instrumentId : batch.preparedSymbols) {
            pipelinedBatchBySymbol.remove(instrumentId, batch);
        }
        batch.pipelineRegistered(false);
        activePipelinedOrderBatches--;
        if (activePipelinedOrderBatches < 0) {
            throw new IllegalStateException("active pipelined order batch count underflow");
        }
    }

    /** 检查本批能否整批准入：必须 PLACE、全部同 Matcher 分片、没有只减仓项及需要逐项 OI 判断的产品，并校验订单身份。 */
    boolean preparePipelinedPlaceBatch(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        if (batch.sequentialAdmission || batch.kind != OrderBatchProcessor.Kind.PLACE) {
            return false;
        }
        long userId = pending.command().header().userId();
        try {
            int batchMatcherShard = -1;
            batch.clearPreparedDecisionCache();
            for (int index = 0; index < batch.items.size(); index++) {
                BatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                int itemMatcherShard = batch.decodedCommand == null
                        ? owner.matchingAdapter().matcherShardId(command.instrumentId())
                        : batch.decodedCommand.matcherShard(owner.matchingAdapter(), command.instrumentId());
                if (batchMatcherShard == -1) batchMatcherShard = itemMatcherShard;
                else if (batchMatcherShard != itemMatcherShard) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                if (command.reduceOnly()) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                owner.requireOrderIdentityAvailable(userId, command);
                var decision = prepareBatchDecision(batch, command.instrumentId());
                if (decision.context().instrument().usesOpenInterestForUserLimit())
                    throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                batch.preparedDecisions[index] = decision;
            }
            batch.pipelined = true;
            return true;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | TradingCoreRuntime.PipelinedBatchNotApplicable exception) {
            batch.currentPreMatchingCancellationOrderIds = List.of();
            batch.clearPreparedDecisionCache();
            return false;
        }
    }

    /** 确定本批唯一分片，把整批输入列、客户键列、订单回执列和原日志元数据交所属账户做整批准入。 */
    void dispatchPipelinedPlaceBatchAdmission(
            OrderCommand pending, OrderBatchProcessor.PendingBatch batch) {
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

    /** 固定提交边界并准备整批直达事件，绑定固定 Matcher 任务后提交。 */
    void submitPipelinedPlaceBatch(OrderCommand pending, OrderBatchProcessor.PendingBatch batch) {
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        long userId = pending.command().header().userId();
        int shard = owner.matchingAdapter().matcherShardId(batch.preparedSymbols.getFirst());
        batch.settlementEvent = owner.runtimeState().prepareDirectMatcherSettlement(
                pending.sequence(), owner.commits.validAccountLaneMask(),
                null, batch.preparedAdmittedOrders, batch.items.size(), pending.command().header().commandId(),
                shard, owner.identities(), pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), batch);
        batch.settlementEvent.reserveMatcherPublication();
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

    /** 在 Matcher 按批内顺序执行各已准入下单并写同一直达事件，保留本项事件和状态、累计成交数。 */
    Object submitPreparedPipelinedPlaceBatch(
            OrderCommand pending, OrderBatchProcessor.PendingBatch batch, long userId, int shard) {
        try {
            exchange.core2.core.common.MatcherResult firstNative = null;
            java.util.Objects.requireNonNull(batch.settlementEvent, "direct batch settlement");
            for (int index = 0; index < batch.items.size(); index++) {
                BatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                OrderCommand.ResolvedPlaceOrder matchingOrder = batch.preparedOrders[index];
                var result = owner.matchingAdapter().placeDirectBatchItem(
                        shard, index, pending.sequence(), pending.command().header().commandId(),
                        pending.command().header().submittedAtEpochMillis(), userId, matchingOrder,
                        batch.settlementEvent);
                if (firstNative == null) firstNative = result;
                item.realtimeTakerOrder = item.admittedOrder;
                int trades = item.captureExecutions(result.events());
                item.executionTakerUserId = userId;
                if (trades != 0) {
                    item.executionCount += trades;
                    batch.tradeCount = Math.addExact(batch.tradeCount, trades);
                }
                boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                        || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
                item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
                item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
            }
            batch.lastMatchingResult = batch.settlementEvent;
            return firstNative;
        } catch (RuntimeException failure) {
            batch.pipelinedMatchingFailure = failure;
            throw failure;
        }
    }

    /** 从当前游标逐项准入，账户或改单回执未齐就挂起。账户已处理并回滚的准入失败不拿全局修订作无变化判断。成功项交 Matcher 后让出 Owner，全部无原生动作的后台激活也保留到有序提交发布终态。 */
    CoreResponse startOrderBatchItem(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                             long clusterTimestamp, long clusterPosition,
                                             boolean deferCompletion) {
        batch.sequentialAdmission = true;
        while (batch.nextIndex < batch.items.size()) {
            BatchItem item = batch.items.get(batch.nextIndex);
            boolean admissionPending = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
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
                // 变更上下文归本序号持有，不能占用下一条命令的 owner。
                owner.suspendMatchingCommitContext(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                boolean laneAdmissionFailure = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
                releaseBatchAmendAdmission(batch);
                batch.itemAdmission = null;
                if (!laneAdmissionFailure)
                    requireUnchangedRejectedBatchItem(pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()));
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                boolean laneAdmissionFailure = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
                releaseBatchAmendAdmission(batch);
                batch.itemAdmission = null;
                if (!laneAdmissionFailure)
                    requireUnchangedRejectedBatchItem(pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(item, ResponseStatus.REJECTED,
                        exception instanceof ArithmeticException
                                ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
                batch.nextIndex++;
            }
        }
        if (deferCompletion) {
            batch.matchingApplied(true);
            initializeOrderBatchLaneContext(batch, pending);
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    /** 本批同品种复用已捕获的只读配置、标记价和资产编号。 */
    OrderBatchProcessor.PendingBatch.Decision prepareBatchDecision(
            OrderBatchProcessor.PendingBatch batch, String instrumentId) {
        int index = batch.preparedSymbols.indexOf(instrumentId);
        if (index >= 0) return batch.preparedContextDecisions[index];
        var context = OrderAdmissionService.context(owner.runtimeState(), owner.identities(), instrumentId, batch.clusterTimestamp);
        var instrument = context.instrument();
        String baseAsset = instrument.baseAsset();
        String quoteAsset = instrument.quoteAsset();
        String settleAsset = instrument.settleAsset();
        int baseAssetId = owner.identities().assetId(baseAsset);
        int quoteAssetId = baseAsset.equals(quoteAsset) ? baseAssetId : owner.identities().assetId(quoteAsset);
        int settleAssetId = quoteAsset.equals(settleAsset) ? quoteAssetId
                : baseAsset.equals(settleAsset) ? baseAssetId : owner.identities().assetId(settleAsset);
        var decision = new OrderBatchProcessor.PendingBatch.Decision(context, 0,
                baseAssetId, quoteAssetId, settleAssetId);
        index = batch.preparedSymbols.size();
        batch.preparedSymbols.add(instrumentId);
        batch.preparedContextDecisions[index] = decision;
        return decision;
    }

    /** 用本批品种判断输入准备固定账户预留工作，绑定当前 Core 序号和项下标，返回同一工作供 Owner 轮询。 */
    private java.util.function.BooleanSupplier beginBatchReservation(OrderBatchProcessor.PendingBatch batch, long userId,
                                                                    PlaceOrderCommand command, UUID commandId) {
        var decision = prepareBatchDecision(batch, command.instrumentId());
        var work = batch.placeReservation;
        work.prepare(owner.runtimeState(), owner.identities(), batch.sequence, userId, commandId,
                command, decision, batch, batch.nextIndex);
        return work;
    }

    private void collectBatchPlaceReservation(OrderBatchProcessor.PendingBatch batch) {
        var work = batch.placeReservation;
        Order admitted = work.admittedOrder();
        owner.runtimeState().pendingReservations().collectControlReservation(admitted, batch.sequence);
        // 所属账户已经准备好本项持有的固定副本，Owner 只绑定其引用。
        batch.items.get(batch.nextIndex).admittedOrder = admitted;
        batch.currentPreMatchingCancellationOrderIds = work.closeCapacityCancellations();
        owner.commits.publication.request();
    }

    /** 临时切到整批原时间和日志位置。finally 恢复原 Owner 时间位置，账户工作仍由所属 Lane 完成。 */
    void prepareOrderBatchItem(OrderBatchProcessor.PendingBatch batch, BatchItem item, long userId,
                                       UUID commandId) {
        long previousTimestamp = owner.currentClusterTimestamp();
        long previousPosition = owner.currentClusterPosition();
        owner.currentClusterTimestamp(batch.clusterTimestamp);
        owner.currentClusterPosition(batch.clusterPosition);
        try {
            switch (batch.kind) {
                case PLACE -> {
                    PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                    owner.requireOrderIdentityAvailable(userId, command);
                    if (owner.activeOrderIndex().activeOrderRoute(command.orderId()) != null)
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "order already exists");
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    batch.itemAdmission = beginBatchReservation(batch, userId, command, commandId);
                }
                case CANCEL -> {
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    var route = owner.activeOrderIndex().activeOrderRoute(command.orderId());
                    var order = route;
                    if (order == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "active order does not exist");
                    if (order.userId() != userId) {
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    }
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    var route = owner.activeOrderIndex().activeOrderRoute(command.originalOrderId());
                    if (route != null && route.userId() != userId)
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    String instrumentId = route == null
                            ? previousBatchOrderInstrument(batch, command.originalOrderId()) : route.instrumentId();
                    if (instrumentId == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                    if (command.replacementOrderId() == command.originalOrderId())
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
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

    private String previousBatchOrderInstrument(OrderBatchProcessor.PendingBatch batch, long orderId) {
        for (int index = 0; index < batch.nextIndex; index++) {
            BatchItem previous = batch.items.get(index);
            if (previous.orderId() == orderId && previous.status == ResponseStatus.APPLIED
                    && previous.admittedOrder != null)
                return previous.admittedOrder.instrument().instrumentId();
        }
        return null;
    }

    /** 完成则抛出已有拒绝，再用 Owner 路由索引核对全局订单号和客户身份，保存替换准入及先撤列表。 */
    private boolean collectBatchAmendAdmission(OrderBatchProcessor.PendingBatch batch, long userId) {
        var event = batch.replacementAdmissionEvent;
        if (!event.complete()) { owner.runtimeState().assertAccountLanesHealthy(); return false; }
        if (event.rejection() != null) throw event.rejection();
        var admission = event.admission();
        // Owner 用已有活动路由索引再检查其他 Lane 是否已有同编号订单。
        if (owner.activeOrderIndex().activeOrderRoute(admission.command().orderId()) != null)
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
        owner.requireOrderIdentityAvailable(userId, admission.command());
        batch.replacementAdmission = admission;
        batch.currentPreMatchingCancellationOrderIds = event.closeCapacityCancellations();
        return true;
    }

    private void releaseBatchAmendAdmission(OrderBatchProcessor.PendingBatch batch) {
        if (batch.replacementAdmissionEvent == null) return;
        owner.runtimeState().releaseReplacementAdmission(batch.replacementAdmissionEvent);
        batch.replacementAdmissionEvent = null;
        batch.replacementAdmission = null;
    }

    /** 撤单交连续同分片片段路径。顺序下单或改单用当前已准入项预先借直达事件，再把固定任务交 Matcher。账户结算事件在撮合前已存在，Owner 后续只收集它。 */
    void submitOrderBatchMatching(OrderCommand pending) {
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch;
        if (batch == null) return;
        batch.lastMatchingResult = null;
        if (batch.kind == OrderBatchProcessor.Kind.CANCEL) {
            submitCancelBatchChunk(pending, batch);
            if (pending.clusterIndependent && batch.activated() && !pending.isMatchingSubmitted())
                pending.matchingSubmitted();
            return;
        }
        int shard = orderBatchMatcherShard(batch);
        com.surprising.aeron.service.settlement.TradeSettlement direct = null;
        // 顺序批次各项依赖上一项账户准入，不能提前准备全批聚合结算。
        if (batch.kind == OrderBatchProcessor.Kind.PLACE) {
            BatchItem item = batch.items.get(batch.nextIndex);
            Order admitted = item.admittedOrder;
            if (admitted == null) throw new IllegalStateException("batch item admission order is missing");
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.runtimeState().prepareDirectMatcherSettlement(
                    pending.sequence(), 0, owner.commits.validAccountLaneMask(), admitted, null, 1,
                    pending.command().header().commandId(), shard, owner.identities(),
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), batch);
            direct.reserveMatcherPublication();
            batch.itemSettlementEvent = direct;
        }
        if (batch.kind == OrderBatchProcessor.Kind.AMEND) {
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.runtimeState().prepareDirectReplacement(
                    pending.sequence(), 0, owner.commits.validAccountLaneMask(),
                    java.util.Objects.requireNonNull(batch.replacementAdmission),
                    pending.command().header().commandId(), shard, owner.identities(),
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), null);
            direct.reserveMatcherPublication();
            batch.itemSettlementEvent = direct;
            batch.items.get(batch.nextIndex).captureAdmittedOrder(direct.admittedOrder());
            if (owner.realtimeCapture() != null) batch.items.get(batch.nextIndex).realtimeTakerOrder = batch.items.get(batch.nextIndex).admittedOrder;
        }
        owner.matcherPipeline().submit(shard, pending.sequence(),
                prepareOrderBatchMatchingCommand(
                        pending, batch, batch.items.get(batch.nextIndex), shard, direct), direct);
    }

    /** 从当前项起合并连续且路由存在的同分片撤单，保留原项顺序。 */
    void submitCancelBatchChunk(OrderCommand pending, OrderBatchProcessor.PendingBatch batch) {
        batch.lastMatchingResult = null;
        int shard = orderBatchMatcherShard(batch);
        int start = batch.nextIndex;
        int end = start;
        // 只合并连续同分片的独立撤单，缺失订单留给 Owner 拒绝路径。
        // 跨分片仍保持原项顺序。
        while (end < batch.items.size()) {
            BatchItem item = batch.items.get(end);
            var route = owner.activeOrderIndex().activeOrderRoute(item.orderId);
            if (route == null) break;
            String instrumentId = route.instrumentId();
            if (owner.matchingAdapter().matcherShardId(instrumentId) != shard) break;
            batch.preparedCancellationOrderIds[end - start] = item.orderId;
            item.cancelSymbol = instrumentId;
            end++;
        }
        if (end == start) {
            // 让批次回到有序队首，由单项准入拒绝缺失订单，避免无效账户修改或撮合提交。
            batch.sequentialAdmission = true;
            batch.activated(false);
            return;
        }
        batch.cancellationChunkStart = start;
        batch.cancellationChunkEnd = end;
        int chunkEnd = end;
        boolean finalChunk = true;
        for (int index = end; index < batch.items.size(); index++) {
            if (owner.activeOrderIndex().activeOrderRoute(batch.items.get(index).orderId) != null) {
                finalChunk = false;
                break;
            }
        }
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        var direct = owner.runtimeState().prepareDirectCancelBatch(
                pending.sequence(), finalChunk, pending.command().header().userId(),
                batch.preparedCancellationOrderIds, end - start, pending.command().header().commandId(), shard,
                owner.identities(), pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), batch);
        direct.reserveMatcherPublication();
        batch.itemSettlementEvent = direct;
        batch.cancelBatchChunkSubmission.prepare(this, pending, batch, shard, start, chunkEnd, direct);
        owner.matcherPipeline().submit(shard, pending.sequence(), batch.cancelBatchChunkSubmission, direct);
    }

    /** 要求已有槽、批次及 Matcher 事实，不能协调的结果进入故障恢复。 */
    public CoreResponse completeOrderBatchMatching(long sequence,
                                                    OrderMatchingResult matchingResult,
                                                    long clusterTimestamp, long clusterPosition) {
        OrderCommand pending = owner.pendingMatching().get(sequence);
        OrderBatchProcessor.PendingBatch batch = batch(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchProcessor.PendingBatch failedBatch = batch(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            throw failOrderBatch(batch, pending, detail, failure);
        }
        if (batch.pipelined) {
            if (matchingResult != batch.settlementEvent || !batch.settlementEvent.hasDirectNativeResult())
                throw failOrderBatch(batch, pending, "pipelined batch requires its direct Matcher fact", null);
            return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
        }
        if (batch.kind == OrderBatchProcessor.Kind.CANCEL) {
            if (matchingResult instanceof com.surprising.aeron.service.settlement.TradeSettlement event
                    && event == batch.itemSettlementEvent && event.hasDirectNativeResult()) {
                if (!event.complete()) throw new IllegalStateException("cancel chunk Lane is incomplete");
                batch.observedSettlementLaneMask |= event.requiredLaneMask();
                owner.runtimeState().collectOrderBatchMatcherSettlement(event, owner.terminalRetention());
                owner.commits.publication.request();
                if (event.commitSequence() != 0) batch.laneCommitCompleted(true);
                owner.runtimeState().releaseMatcherSettlement(event);
                batch.itemSettlementEvent = null;
                batch.lastMatchingResult = null;
                boolean trackChanges = !batch.laneCommitCompleted();
                if (trackChanges) {
                    batch.changedUserIds.add(pending.command().header().userId());
                }
                while (batch.nextIndex < batch.cancellationChunkEnd) {
                    BatchItem item = batch.items.get(batch.nextIndex);
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

    /** 收当前项已完成财务验证的账户结算。随后完成项结果、回收事件并开始下一项，运行异常转为批次恢复故障。 */
    public CoreResponse completeOrderBatchItemSettlement(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                                 long timestamp, long position) {
        try {
            var settledEvent = batch.itemSettlementEvent;
            owner.runtimeState().collectOrderBatchMatcherSettlement(settledEvent, owner.terminalRetention());
            if (batch.kind == OrderBatchProcessor.Kind.AMEND && batch.lastMatchingResult.accepted()) {
                owner.runtimeState().setMetadata(owner.productLine(), Math.addExact(owner.runtimeState().revision(),
                        owner.productLine() == ProductLine.SPOT ? 2 : 1));
            }
            if (batch.kind != OrderBatchProcessor.Kind.CANCEL) finishOrderBatchItem(batch, pending, batch.lastMatchingResult);
            owner.runtimeState().releaseMatcherSettlement(settledEvent);
            batch.itemSettlementEvent = null;
            batch.lastMatchingResult = null;
            return startOrderBatchItem(batch, pending, timestamp, position, false);
        } catch (RuntimeException failure) {
            throw failOrderBatch(batch, pending, "batch item Lane settlement failed", failure);
        }
    }

    /** 保存当前项 Matcher 事实并校验直达事件、收集执行明细。账户或准入仍待完成则返回，否则结束该项。 */
    void applyCompletedOrderBatchItem(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                      OrderMatchingResult matchingResult) {
        batch.lastMatchingResult = matchingResult;
        BatchItem item = batch.items.get(batch.nextIndex);
        try {
            applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (batch.itemSettlementEvent != null || batch.itemAdmission != null) return;
            finishOrderBatchItem(batch, pending, matchingResult);
        } catch (RuntimeException exception) {
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    /** 撮合已发生后的异常必须恢复，不能伪装普通拒单或重试同一修改。 */
    private void finishOrderBatchItem(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                      OrderMatchingResult matchingResult) {
        BatchItem item = batch.items.get(batch.nextIndex);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
            if (batch.kind != OrderBatchProcessor.Kind.CANCEL) appendOrderBatchResult(item, status, resultCode);
            releaseBatchAmendAdmission(batch);
            item.cancelSymbol = null;
            batch.nextIndex++;
        } catch (RuntimeException exception) {
            // 该项撮合已经应用，结算运行失败也需要从快照与日志恢复。
            // 不能改成单项拒绝或重做同一修改。
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    /** 标记 Matcher 故障，清未登记触发子单并停止仍占用槽的撮合续步，结束批次账户作用域，再交原运行时生成致命恢复异常。调用方抛出该异常，不能继续当普通项拒绝处理。 */
    public FatalMatchingDivergenceException failOrderBatch(
            OrderBatchProcessor.PendingBatch batch, OrderCommand pending, String detail, Throwable cause) {
        // 已发生的撮合事实不能在此撤销。
        // 标记故障并停止续步，将原命令交恢复边界处理，不能继续当成普通拒单。
        owner.matchingAdapter().poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        owner.admissions().clearQueuedMatching();
        owner.pendingMatching().forEach(value -> {
            if (value.claimed) {
                value.resetMatchingContinuation();
            }
        });
        owner.runtimeState().endOrderBatchMutationScope();
        return owner.failMatching(pending, detail, cause);
    }

    /** 要求 PLACE 有准入副本，必要时绑定实时身份并复制非撤单项执行明细。PLACE 和 AMEND 必须使用当前项的直达事件，账户资金事实仍由事件执行，撤单变化由片段收集入口处理。 */
    void applyOrderBatchMatcherResult(
            OrderBatchProcessor.PendingBatch batch, BatchItem item, OrderCommand pending,
            OrderMatchingResult matchingResult) {
        if (item.admittedOrder == null && batch.kind == OrderBatchProcessor.Kind.PLACE)
            throw new IllegalStateException("batch place admission receipt is missing");
        if (item.realtimeTakerOrder == null && owner.realtimeCapture() != null
                && owner.realtimeCapture().active()) item.realtimeTakerOrder = item.admittedOrder;

        if (batch.kind != OrderBatchProcessor.Kind.CANCEL) {
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
            case CANCEL -> { return; }

            case AMEND -> {
                if (batch.itemSettlementEvent != matchingResult || !batch.itemSettlementEvent.direct())
                    throw new IllegalStateException("batch amendment requires a direct Lane event");
                return;
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
    }

    /** 只把已经决定的状态和结果码写入该项，不编码整批或重新执行账户业务。 */
    void appendOrderBatchResult(BatchItem item,
                                ResponseStatus status, CoreResultCode resultCode) {
        item.status = status;
        item.resultCode = resultCode;
    }

    /** 开始整批收尾并建立账户位图，收整批账户及财务结算结果，必要时等待最终账户盖戳和响应编码。 */
    public CoreResponse finishOrderBatch(OrderBatchProcessor.PendingBatch batch, OrderCommand pending,
                                          long clusterTimestamp, long clusterPosition) {
        if (!batch.finishing()) {
            owner.activateFactContext(pending.command(), pending.fingerprint());
            batch.finishing(true);
        }
        initializeOrderBatchLaneContext(batch, pending);
        if (batch.settlementEvent != null && !batch.settlementsCollected()) {
            long settlementLaneMask = batch.settlementEvent.requiredLaneMask();
            boolean finalLaneCommit = batch.settlementEvent.commitSequence() != 0;
            if (!owner.runtimeState().collectMatcherSettlement(
                    batch.settlementEvent, owner.terminalRetention(), null, null)) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState().releaseMatcherSettlement(batch.settlementEvent);
            batch.settlementsCollected(true);
            if (finalLaneCommit) {
                pending.completeLanes(settlementLaneMask);
                batch.laneCommitCompleted(true);
            }
            owner.commits.publication.request();
        }
        owner.runtimeState().pendingReservations().completePendingReservations(batch.sequence);
        if (!batch.laneCommitCompleted() || batch.preparedResponse == null) {
            if (batch.laneCommitEvent == null) {
                owner.runtimeState().releaseOwnerLaneAccess();
                batch.laneCommitEvent = owner.runtimeState().dispatchBatchCommit(batch.sequence,
                        batch.laneCommitCompleted() ? List.of() : batch.changedUserIds,
                        batch.laneCommitCompleted() ? null : owner.runtimeState().metadataOrderUsers(batch.runtimeChangedOrderIds),
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
        // 全局发布消费 runtime 变更，不能再复制到普通单结果容器。
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        if (pending.expectedLaneMask() != batch.actualLaneMask) {
            throw failOrderBatch(batch, pending, "order batch account lane mask mismatch", null);
        }
        try {
            owner.runtimeState().setMetadata(owner.productLine(),
                    Math.incrementExact(owner.runtimeState().revision()));
            if (pending.completedLaneMask() != pending.expectedLaneMask()) {
                throw new IllegalStateException("order batch account lane mask mismatch");
            }
            owner.commits.requireCompleteAccountLanes(pending);
        } catch (RuntimeException validationFailure) {
            throw failOrderBatch(batch, pending, "order batch final validation failed", validationFailure);
        }
        captureCommittedBatchTrades(batch);
        owner.commits.publication.complete();
        owner.commits.closeFinancialDispatch(pending);
        if (batch.preparedResponse == null)
            throw failOrderBatch(batch, pending, "final account Lane did not prepare the batch response", null);
        byte[] responseData = batch.preparedResponse;
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
        CoreMessage responseCommand = pending.command();
        unregisterBatch(pending, batch);
        owner.matchingFlow.removePendingMatching(batch.sequence);
        unregisterPipelinedBatchSymbols(batch);
        owner.matchingProgress.batchCompleted();
        owner.matchingProgress.submitDeferredMatchingAfterBatch();
        CoreResponse response = responseCommand.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence,
                responseData, 0, responseLength);
        releaseOrderBatchPending(batch);
        return owner.finishFactContext(response);
    }

    /** 只初始化一次，从已观察结算位、整批事件和变化用户汇总实际参与账户。 */
    void initializeOrderBatchLaneContext(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        if (batch.laneContextInitialized()) return;
        long expectedLaneMask = batch.observedSettlementLaneMask;
        if (batch.settlementEvent != null) {
            expectedLaneMask |= batch.settlementEvent.requiredLaneMask();
        }
        for (int index = 0; index < batch.changedUserIds.size(); index++) {
            expectedLaneMask |= owner.matchingAdapter().topology().accountLaneMask(
                    batch.changedUserIds.valueAt(index));
        }
        batch.actualLaneMask = expectedLaneMask;
        var finalMatchingResult = batch.lastMatchingResult == null
                ? batch.noNativeCommandResult.withCoreSequenceInPlace(batch.sequence)
                : batch.lastMatchingResult;
        pending.result(
                finalMatchingResult, expectedLaneMask, owner.commits.validAccountLaneMask());
        if (batch.laneCommitCompleted() && expectedLaneMask != 0) pending.completeLanes(expectedLaneMask);
        batch.laneContextInitialized(true);
    }

    /** 要求当前批次及项游标有效，返回当前项需要的 Matcher 分片。 */
    int orderBatchMatcherShard(OrderBatchProcessor.PendingBatch batch) {
        if (batch == null || batch.nextIndex < 0 || batch.nextIndex >= batch.items.size()) {
            throw new IllegalArgumentException("order batch matcher route is unavailable");
        }
        return matcherShardForBatchItem(batch, batch.items.get(batch.nextIndex));
    }

    /** 汇总尚未提交项还会用到的分片，已提交的流水整批直接为空。 */
    long remainingMatcherShardMask(OrderBatchProcessor.PendingBatch batch) {
        long mask = 0;
        if (batch.pipelined && batch.sequence != 0
                && owner.pendingMatching().get(batch.sequence).isMatchingSubmitted()) return 0;
        int firstUnsubmitted = batch.nextIndex;
        if (batch.itemSettlementEvent != null) {
            firstUnsubmitted = batch.kind == OrderBatchProcessor.Kind.CANCEL
                    ? batch.cancellationChunkEnd : batch.nextIndex + 1;
        }
        for (int index = firstUnsubmitted; index < batch.items.size(); index++) {
            if (batch.kind != OrderBatchProcessor.Kind.PLACE && owner.activeOrderIndex().activeOrderRoute(
                    batch.kind == OrderBatchProcessor.Kind.CANCEL ? batch.items.get(index).orderId()
                            : batch.items.get(index).originalOrderId()) == null) continue;
            mask |= 1L << matcherShardForBatchItem(batch, batch.items.get(index));
        }
        return mask;
    }

    private int matcherShardForBatchItem(OrderBatchProcessor.PendingBatch batch, BatchItem item) {
        String instrumentId = switch (batch.kind) {
            case PLACE -> ((PlaceOrderCommand) item.command).instrumentId();
            case CANCEL -> {
                var route = owner.activeOrderIndex().activeOrderRoute(((CancelOrderCommand) item.command).orderId());
                yield route == null ? "" : route.instrumentId();
            }
            case AMEND -> {
                var route = owner.activeOrderIndex().activeOrderRoute(((AmendOrderCommand) item.command).originalOrderId());
                String previous = previousBatchOrderInstrument(batch, item.originalOrderId());
                yield route == null ? previous == null ? "" : previous : route.instrumentId();
            }
        };
        return instrumentId.isBlank() ? 0 : batch.decodedCommand == null
                ? owner.matchingAdapter().matcherShardId(instrumentId)
                : batch.decodedCommand.matcherShard(owner.matchingAdapter(), instrumentId);
    }

    /** 开启原命令事实及批次变更作用域，取得整批提交权并开始发布积累。 */
    void beginOrderBatchCommitContext(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.resultBuilder.beginCommand();
        owner.runtimeState().beginOrderBatchMutationScope();
        batch.beginCommit();
        owner.commits.publication.begin();
    }

    /** 独立预备撤单可直接建上下文。其他流水批次要求 Owner 已登记准入且事件成功完成，再收集释放整批准入并请求发布。 */
    public void beginPipelinedOrderBatchCommit(OrderBatchProcessor.PendingBatch batch, OrderCommand pending) {
        OrderBatchProcessor.PlaceBatchAdmissionEvent admission = batch.placeBatchAdmissionEvent;
        boolean preparedCancel = pending.clusterIndependent && batch.kind == OrderBatchProcessor.Kind.CANCEL;
        if (!preparedCancel && (!batch.admissionCollected() || admission == null || !admission.complete()
                || admission.rejection() != null)) {
            throw new IllegalStateException("pipelined order batch admission is not ready to commit");
        }
        beginOrderBatchCommitContext(batch, pending);
        if (!preparedCancel) {
            owner.runtimeState().collectPlaceBatchAdmission(admission);
            owner.runtimeState().releasePlaceBatchAdmission(admission);
            batch.placeBatchAdmissionEvent = null;
            owner.commits.publication.request();
        }
    }

    /** 只有前面各批均已登记流水品种且候选全部品种不重叠时返回假。否则按既有依赖规则认为冲突。 */
    boolean conflictsWithEarlierPipelinedBatch(OrderBatchProcessor.PendingBatch candidate) {
        if (activePipelinedOrderBatches != pendingBatchCount() - 1) return true;
        for (BatchItem item : candidate.items) {
            if (pipelinedBatchBySymbol.containsKey(((PlaceOrderCommand) item.command).instrumentId())) return true;
        }
        return false;
    }

    private void captureCommittedBatchTrades(OrderBatchProcessor.PendingBatch batch) {
        if (owner.realtimeCapture() == null) return;
        for (int itemIndex = 0; itemIndex < batch.items.size(); itemIndex++) {
            BatchItem item = batch.items.get(itemIndex);
            var taker = item.realtimeTakerOrder;
            item.realtimeTakerOrder = null;
            if (taker == null || owner.realtimeCapture() == null || !owner.realtimeCapture().active()) continue;
            try {
                int fill = 0;
                for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
                    MatcherEvent event = item.executionEvents.get(eventIndex);
                    if (event.eventType() == MatcherEventType.TRADE)
                        owner.realtimeCapture().trade(taker, batch.sequence, fill++, event.price(), event.size(),
                                event.matchedOrderId(), event.matchedOrderUid());
                }
            } catch (RuntimeException failure) { owner.realtimeCapture().failed(); }
        }
    }

    /** 按 PLACE、CANCEL 或 AMEND 已解码批次借上下文并逐项保存输入。 */
    OrderBatchProcessor.PendingBatch decodeOrderBatch(CoreMessage message, OrderCommand.Decoded decodedCommand,
                                               long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = decodedCommand.placeOrderBatch();
            List<PlaceOrderCommand> orders = command.orders();
            int count = orders.size();
            OrderBatchProcessor.PendingBatch batch = acquireOrderBatchPending(OrderBatchProcessor.Kind.PLACE, count,
                    clusterTimestamp, clusterPosition);
            batch.decodedCommand = decodedCommand;
            for (int i = 0; i < count; i++) {
                PlaceOrderCommand value = orders.get(i);
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = decodedCommand.cancelOrderBatch();
            List<CancelOrderCommand> orders = command.orders();
            int count = orders.size();
            OrderBatchProcessor.PendingBatch batch = acquireOrderBatchPending(OrderBatchProcessor.Kind.CANCEL, count,
                    clusterTimestamp, clusterPosition);
            batch.decodedCommand = decodedCommand;
            for (int i = 0; i < count; i++) {
                CancelOrderCommand value = orders.get(i);
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = decodedCommand.amendOrderBatch();
            List<AmendOrderCommand> orders = command.orders();
            int count = orders.size();
            OrderBatchProcessor.PendingBatch batch = acquireOrderBatchPending(OrderBatchProcessor.Kind.AMEND, count,
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

    /** 从 Owner 池中取第一个容量足够的上下文并移出池，没有则按项数创建，再绑定本批种类和原日志元数据。 */
    OrderBatchProcessor.PendingBatch acquireOrderBatchPending(
            OrderBatchProcessor.Kind kind, int itemCount, long clusterTimestamp, long clusterPosition) {
        OrderBatchProcessor.PendingBatch selected = null;
        Iterator<OrderBatchProcessor.PendingBatch> iterator = orderBatchPendingPool.iterator();
        while (iterator.hasNext()) {
            OrderBatchProcessor.PendingBatch candidate = iterator.next();
            if (candidate.capacity() < itemCount) continue;
            iterator.remove();
            selected = candidate;
            break;
        }
        if (selected == null) selected = new OrderBatchProcessor.PendingBatch(itemCount, owner.responseSlotPool());
        return selected.initialize(kind, clusterTimestamp, clusterPosition);
    }

    /** 空批次直接返回。否则解除品种登记及改单事件，清理对象后在池未满时归池。 */
    void releaseOrderBatchPending(OrderBatchProcessor.PendingBatch batch) {
        if (batch == null) return;
        unregisterPipelinedBatchSymbols(batch);
        releaseBatchAmendAdmission(batch);
        batch.clear();
        if (orderBatchPendingPool.size() < owner.configuration().maxPendingMatching()) orderBatchPendingPool.addFirst(batch);
    }

    /** PLACE 留到实际准入检查。撤改单只用 Owner 稳定路由核对已有原单归属，缺失订单留到各项准入判定，不跨线程读账户订单表。 */
    void validateOrderBatchIdentity(OrderBatchProcessor.PendingBatch batch, long userId) {
        if (batch.kind == OrderBatchProcessor.Kind.PLACE) return;
        for (BatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchProcessor.Kind.CANCEL ? item.orderId : item.originalOrderId;
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            if (route != null && route.userId() != userId) {
                throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH",
                        "order batch contains another user's order");
            }
        }
    }

    /** 对尚未交账户处理的拒绝项检查全局修订未变。若已经变化则结束批次作用域并报告恢复故障。 */
    void requireUnchangedRejectedBatchItem(OrderCommand pending,
                                           long runtimeRevisionBefore,
                                           RuntimeException exception) {
        if (owner.runtimeState().revision() != runtimeRevisionBefore) {
            owner.runtimeState().endOrderBatchMutationScope();
            throw owner.failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    /** 要求当前项已有直达事件，保存原先撤路由，再按下单准入副本或改单解析准入准备槽内固定 Matcher 任务。 */
    java.util.function.Supplier<?> prepareOrderBatchMatchingCommand(
            OrderCommand pending, OrderBatchProcessor.PendingBatch batch, BatchItem item, int shard,
            com.surprising.aeron.service.settlement.TradeSettlement direct) {
        java.util.Objects.requireNonNull(direct, "batch item requires its direct settlement");
        pending.matcherPreCancellations(owner.matchingFlow.preMatchingCancellationOrders(pending));
        long userId = pending.command().header().userId();
        return switch (batch.kind) {
            case PLACE -> pending.prepareAdmittedPlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(item.admittedOrder, "batch admission receipt"), direct);
            case AMEND -> {
                var admission = java.util.Objects.requireNonNull(batch.replacementAdmission, "batch amend admission");
                yield pending.prepareReplaceMatching(owner, shard, admission.resolved().orderId(), userId,
                        admission.originalOrderId(), admission.command().instrumentId(), admission.resolved(), direct);
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        };
    }

    /** 整批流水下单的可复用 Matcher 任务，Owner 绑定输入，Matcher 顺序执行，交接结束后由批次清理。 */
    static final class PipelinedPlaceBatchSubmission implements java.util.function.Supplier<Object> {
        private OrderBatchProcessor executor;
        private OrderCommand pending;
        private OrderBatchProcessor.PendingBatch batch;
        private long userId;
        private int shard;

        /** 把已固定的执行器、槽、批次、用户和分片放进本批复用任务，供 Matcher 之后调用。 */
        void prepare(OrderBatchProcessor executor, OrderCommand pending, OrderBatchProcessor.PendingBatch batch, long userId, int shard) {
            this.executor = executor;
            this.pending = pending;
            this.batch = batch;
            this.userId = userId;
            this.shard = shard;
        }

        /** 在任务无人借用后解除本轮引用并清原语参数，保留对象。 */
        void clear() {
            executor = null;
            pending = null;
            batch = null;
            userId = 0;
            shard = 0;
        }

        /** 在 Matcher 准备该用户的品种路由，再逐项执行已准入流水批次。任务执行前必须已取得完整输入。 */
        @Override
        public Object get() {
            executor.owner.matchingAdapter().prepareOrderRoutes(userId, batch.preparedSymbols);
            return executor.submitPreparedPipelinedPlaceBatch(pending, batch, userId, shard);
        }
    }

    /** 同分片连续撤单片段的固定 Matcher 任务，借用整批项槽和直达事件，保留原请求顺序。 */
    static final class CancelBatchChunkSubmission implements java.util.function.Supplier<Object> {
        private OrderBatchProcessor executor;
        private OrderCommand pending;
        private OrderBatchProcessor.PendingBatch batch;
        private int shard;
        private int start;
        private int chunkEnd;
        /** 撤单片段预先准备并持有发布租约的直达结算事件。 */
        private com.surprising.aeron.service.settlement.TradeSettlement direct;

        /** 保存本次同分片撤单片段的起止游标、命令槽及直达事件，供 Matcher 顺序执行。 */
        void prepare(OrderBatchProcessor executor, OrderCommand pending, OrderBatchProcessor.PendingBatch batch, int shard,
                     int start, int chunkEnd, com.surprising.aeron.service.settlement.TradeSettlement direct) {
            this.executor = executor;
            this.pending = pending;
            this.batch = batch;
            this.shard = shard;
            this.start = start;
            this.chunkEnd = chunkEnd;
            this.direct = direct;
        }

        /** 任务交接结束后清执行器、槽、批次、事件及范围，供下一片段复用。 */
        void clear() {
            executor = null;
            pending = null;
            batch = null;
            shard = 0;
            start = 0;
            chunkEnd = 0;
            direct = null;
        }

        /** 在 Matcher 按片段原顺序逐项撤单，局部下标从零写直达事件。 */
        @Override
        public Object get() {
            exchange.core2.core.common.MatcherResult firstNative = null;
            for (int index = start; index < chunkEnd; index++) {
                BatchItem item = batch.items.get(index);
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

public enum Kind {
    /** 批量下单。 */
    PLACE,
    /** 批量撤单。 */
    CANCEL,
    /** 批量改单。 */
    AMEND
}

public static final class PendingBatch implements OrderCommand.ResultTarget, TradingOrderBatchCodec.ResultSource {
    public record Decision(com.surprising.aeron.service.order.OrderAdmissionService.Context context,
            long openInterestSteps, int baseAssetId, int quoteAssetId, int settleAssetId) { }
    /** 按批内下标读取 PLACE 输入。 */
    public com.surprising.aeron.protocol.PlaceOrderCommand intent(int index) {
        return (com.surprising.aeron.protocol.PlaceOrderCommand) items.get(index).command;
    }
    /** 借用该项已经准备的产品判断输入，账户准入前由 Owner 填写。 */
    public Decision decision(int index) { return preparedDecisions[index]; }
    final Decision[] preparedDecisions;

    /** 按有效下标取得本项可复用的解析订单槽，首次使用才创建。 */
    public OrderCommand.ResolvedPlaceOrder resolvedOrderSlot(int index) {
        var slot = preparedOrders[index];
        if (slot == null) preparedOrders[index] = slot = new OrderCommand.ResolvedPlaceOrder();
        return slot;
    }

    /** 把账户准入订单捕获到对应项的固定副本，返回该份可借用回执，整批退休前保留。 */
    public Order captureAdmittedOrder(int index, Order order) {
        BatchItem item = items.get(index);
        item.captureAdmittedOrder(order);
        return item.admittedOrder;
    }
    /** 返回需要生成 Lane 结果的批内项数。 */
    public int resultCount() { return items.size(); }
    /** 返回指定项的结果订单编号。 */
    public long resultOrderId(int index) { return items.get(index).orderId(); }
    /** 返回指定项的改单原订单编号。 */
    public long resultOriginalOrderId(int index) { return items.get(index).originalOrderId(); }
    /** 捕获本项可空的写后订单并保存品种名称，最后标记该项结果已准备。 */
    public void resultOrder(int index, Order order, String instrumentId) {
        var item = items.get(index);
        item.captureResultOrder(order);
        item.resultOrderSymbol = instrumentId;
        item.laneResultPrepared = true;
    }
    /** 要求所属 Lane 也收集未变化或拒绝项，避免 Owner 另查发布表。 */
    @Override public boolean captureUnchangedResults() {
        // 各项由所属账户提供结果，未变化或拒绝项也如此，避免 Owner 再查发布表。
        return true;
    }
    /** 返回指定项是否已经取得所属 Lane 的结果。 */
    @Override public boolean resultPrepared(int index) {
        return items.get(index).laneResultPrepared;
    }
    /** 按 Matcher 接受与否填写单项状态。 */
    @Override public void matcherResult(int index, boolean accepted) {
        int itemIndex = kind == OrderBatchProcessor.Kind.CANCEL ? cancellationChunkStart + index : index;
        BatchItem item = items.get(itemIndex);
        item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
    }
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    byte[] preparedResponse;
    @Accessors(fluent = true, chain = false)
    @Getter(onMethod_ = {@Override})
    int preparedResponseLength;
    private final ResponseSlotPool responseSlotPool;
    /** 本批尚未移交或归还的可空响应槽租约。 */
    private ResponseSlotPool.Slot preparedResponseSlot;
    /** previousBatch：仅由 Owner 访问的前一批排序链接，入池前由外层摘除。nextBatch：仅由 Owner 访问的后一批排序链接，Matcher 和账户不读取。 */
    PendingBatch previousBatch, nextBatch;

    /** 只有全部项均有 Lane 结果和状态才重编码，未齐直接返回。先归还旧租约并清响应引用，再借响应槽编码。 */
    @Override public void prepareResponse() {
        for (BatchItem item : items) if (!item.laneResultPrepared || item.status == null) return;
        if (preparedResponseSlot != null) {
            responseSlotPool.release(preparedResponseSlot);
            preparedResponseSlot = null;
        }
        preparedResponse = null;
        preparedResponseLength = 0;
        try {
            int length = TradingOrderBatchCodec.encodedResultSourceLength(this);
            preparedResponseSlot = responseSlotPool.acquireSlot(length);
            int encodedLength = TradingOrderBatchCodec.encodeResultSourceInto(
                    this, preparedResponseSlot.storage(), 0);
            preparedResponse = preparedResponseSlot.storage();
            preparedResponseLength = encodedLength;
        } catch (RuntimeException | Error failure) {
            if (preparedResponseSlot != null) responseSlotPool.release(preparedResponseSlot);
            preparedResponseSlot = null;
            throw failure;
        } finally {
            responseOrder.clear();
        }
    }

    /** 解除本批对响应槽租约的持有，由终态响应接管。字节引用保留到清批，不能再由本批归还同一槽。 */
    void transferPreparedResponseOwnership() {
        preparedResponseSlot = null;
    }

    /** 返回本批是否还持有需要释放或移交的响应槽租约。 */
    boolean hasPreparedResponseSlot() {
        return preparedResponseSlot != null;
    }

    /** 返回批内实际项数，供结果编码器读取。 */
    public int size() { return items.size(); }
    /** 返回指定项的结果订单编号，改单为新编号。 */
    public long orderId(int index) { return items.get(index).orderId; }
    /** 返回指定项的原单编号，非改单通常为零。 */
    public long originalOrderId(int index) { return items.get(index).originalOrderId; }
    /** 返回指定项的替换后编号，非改单通常为零。 */
    public long replacementOrderId(int index) { return items.get(index).replacementOrderId; }
    /** 返回指定项已决定的响应状态。 */
    public ResponseStatus status(int index) { return items.get(index).status; }
    /** 返回指定项的确定性结果码。 */
    public CoreResultCode resultCode(int index) { return items.get(index).resultCode; }
    private final OrderRuntimeSource responseOrder = new OrderRuntimeSource();

    public com.surprising.aeron.protocol.CoreOrderStateSource order(int index) {
        BatchItem item = items.get(index);
        responseOrder.set(item.resultOrder, item.resultOrderSymbol);
        return item.resultOrder == null ? null : responseOrder;
    }

    /** 返回指定项已统计的成交事件数，编码器用它计算所需长度。 */
    public int executionCount(int index) { return items.get(index).executionCount; }
    /** 只编码指定项中的成交事件，按主动单、被动单、双方用户、价格和数量的固定顺序写入。 */
    public void writeExecutions(int index, java.nio.ByteBuffer output) {
        BatchItem item = items.get(index);
        for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
            MatcherEvent event = item.executionEvents.get(eventIndex);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            output.putLong(item.orderId).putLong(event.matchedOrderId())
                    .putLong(item.executionTakerUserId).putLong(event.matchedOrderUid())
                    .putLong(event.price()).putLong(event.size());
        }
    }

    @Accessors(fluent = true, chain = false)
    @Getter
    OrderBatchProcessor.Kind kind;
    OrderCommand.Decoded decodedCommand;
    /** 各线程只能在约定交接边界访问对应输入或结果。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final ArrayList<BatchItem> items;
    private final BatchItem[] itemSlots;
    long clusterTimestamp;
    long clusterPosition;
    /** 批次种类唯一确定撮合操作，不另存第二份类别。 */
    OrderCommand.Operation operation() {
        return switch (kind) {
            case PLACE -> OrderCommand.Operation.PLACE;
            case CANCEL -> OrderCommand.Operation.CANCEL;
            case AMEND -> OrderCommand.Operation.AMEND;
        };
    }
    final PrimitiveLongChangeSet changedUserIds;
    final PrimitiveLongChangeSet runtimeChangedOrderIds;
    @Accessors(fluent = true, chain = false)
    @Setter
    int nextIndex;
    long tradeCount;
    int cancellationChunkStart;
    int cancellationChunkEnd;
    long sequence;
    /** 当前子项撮合前必须先完成的撤单编号列表。 */
    List<Long> currentPreMatchingCancellationOrderIds = List.of();
    /** 等待、已预派发或有序提交的独占阶段，防止重复派发。 */
    private byte commitState;
    int lifecycleFlags;
    private static final int ADMISSION_COLLECTED = 1;
    private static final int MATCHING_APPLIED = 1 << 1;
    private static final int PIPELINE_REGISTERED = 1 << 2;
    private static final int LANE_COMMIT_COMPLETED = 1 << 3;
    private static final int SETTLEMENTS_COLLECTED = 1 << 4;
    private static final int FINISHING = 1 << 6;
    private static final int LANE_CONTEXT_INITIALIZED = 1 << 7;
    private static final int ACTIVATED = 1 << 8;
    @Accessors(fluent = true, chain = false)
    @Getter
    boolean pipelined;
    /** 本批是否必须按项串行准入，以保留资金及订单依赖。 */
    boolean sequentialAdmission;
    /** Matcher 执行流水批次时保存的失败，交 Owner 决定恢复处理。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    Throwable pipelinedMatchingFailure;
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.TradeSettlement settlementEvent;
    /** 整批下单账户准入事件，Owner 收集后回收。借用整批账户准入事件，Owner 收集回收后为空。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    OrderBatchProcessor.PlaceBatchAdmissionEvent placeBatchAdmissionEvent;
    /** 本批有效项的解析下单输入列，派发后不能提前清理。 */
    final OrderCommand.ResolvedPlaceOrder[] preparedOrders;
    final long[] preparedClientKeyValues;
    final Order[] preparedAdmittedOrders;
    long[] preparedCancellationOrderIds;
    final ArrayList<String> preparedSymbols;
    final Decision[] preparedContextDecisions;

    long observedSettlementLaneMask;
    long actualLaneMask;
    // 顺序路径同一时刻只有一个子项等待账户发布，直接持有其事件，不复制执行状态。
    /** 当前唯一等待账户发布的顺序子项或撤单片段事件，不另复制一份执行状态。借用当前顺序子项或撤单片段的结算事件。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.settlement.TradeSettlement itemSettlementEvent;
    /** 当前顺序改单已经收集的解析准入。借用当前顺序改单的可空解析准入。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.order.OrderCommand.ReplacementAdmission replacementAdmission;
    /** 当前顺序改单仍需保留的账户准入事件。 */
    com.surprising.aeron.service.order.OrderCommand.ReplacementAdmissionEvent replacementAdmissionEvent;
    /** 当前顺序下单的账户预留续步。借用当前顺序下单的可空账户预留续步。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    java.util.function.BooleanSupplier itemAdmission;
    long itemAdmissionRevision;
    @Accessors(fluent = true, chain = false)
    @Getter
    com.surprising.aeron.service.core.change.AccountPartitionCommit laneCommitEvent;
    @Accessors(fluent = true, chain = false)
    @Getter
    @Setter
    OrderMatchingResult lastMatchingResult;
    final com.surprising.aeron.service.matcher.OrderMatchingResult.Control noNativeCommandResult =
            new com.surprising.aeron.service.matcher.OrderMatchingResult.Control(true, "NO_NATIVE_COMMAND");
    /** 顺序下单共用的账户预留工作，上一项完成后才能准备下一项。 */
    final BatchPlaceReservation placeReservation = new BatchPlaceReservation();
    final OrderBatchProcessor.PipelinedPlaceBatchSubmission pipelinedPlaceBatchSubmission =
            new OrderBatchProcessor.PipelinedPlaceBatchSubmission();
    final OrderBatchProcessor.CancelBatchChunkSubmission cancelBatchChunkSubmission =
            new OrderBatchProcessor.CancelBatchChunkSubmission();

    private static final byte COMMIT_WAITING = 0;
    private static final byte COMMIT_PREDISPATCHED = 1;
    private static final byte COMMITTING = 2;

    /** 仅在尚未取得预派发或有序提交权时返回真。 */
    boolean canPredispatch() { return commitState == COMMIT_WAITING; }
    /** 返回是否已经进入本批有序提交阶段。 */
    public boolean commitStarted() { return commitState == COMMITTING; }

    /** 要求仍在等待阶段，再标为已预派发，防止重复取得账户工作派发权。 */
    void markPredispatched() {
        if (!canPredispatch()) throw new IllegalStateException("batch Lane work already owned");
        commitState = COMMIT_PREDISPATCHED;
    }

    /** 拒绝重复开始有序提交，将等待或预派发阶段转为提交阶段。 */
    void beginCommit() {
        if (commitStarted()) throw new IllegalStateException("batch commit already started");
        commitState = COMMITTING;
    }

    private boolean has(int bit) { return (lifecycleFlags & bit) != 0; }
    private void set(int bit, boolean value) {
        if (value) lifecycleFlags |= bit; else lifecycleFlags &= ~bit;
    }
    /** 读取生命周期位，说明Owner 是否已经登记整批准入的身份分配。 */
    boolean admissionCollected() { return has(ADMISSION_COLLECTED); }
    /** 只更新生命周期位，记录Owner 是否已经登记整批准入的身份分配。 */
    void admissionCollected(boolean value) { set(ADMISSION_COLLECTED, value); }
    /** 读取生命周期位，说明当前批次全部撮合输入是否已经应用。 */
    public boolean matchingApplied() { return has(MATCHING_APPLIED); }
    /** 只更新生命周期位，记录当前批次全部撮合输入是否已经应用。 */
    public void matchingApplied(boolean value) { set(MATCHING_APPLIED, value); }
    /** 读取生命周期位，说明是否已在品种依赖表登记本批。 */
    boolean pipelineRegistered() { return has(PIPELINE_REGISTERED); }
    /** 只更新生命周期位，记录是否已在品种依赖表登记本批。 */
    void pipelineRegistered(boolean value) { set(PIPELINE_REGISTERED, value); }
    /** 读取生命周期位，说明本批最终账户提交水位是否已经完成。 */
    boolean laneCommitCompleted() { return has(LANE_COMMIT_COMPLETED); }
    /** 只更新生命周期位，记录本批最终账户提交水位是否已经完成。 */
    void laneCommitCompleted(boolean value) { set(LANE_COMMIT_COMPLETED, value); }
    /** 读取生命周期位，说明整批结算事件是否已经收集。 */
    boolean settlementsCollected() { return has(SETTLEMENTS_COLLECTED); }
    /** 只更新生命周期位，记录整批结算事件是否已经收集。 */
    void settlementsCollected(boolean value) { set(SETTLEMENTS_COLLECTED, value); }
    /** 读取生命周期位，说明是否已进入整批最终收尾。 */
    boolean finishing() { return has(FINISHING); }
    /** 只更新生命周期位，记录是否已进入整批最终收尾。 */
    void finishing(boolean value) { set(FINISHING, value); }
    /** 读取生命周期位，说明是否已经为最终提交建立完整账户完成位图。 */
    boolean laneContextInitialized() { return has(LANE_CONTEXT_INITIALIZED); }
    /** 只更新生命周期位，记录是否已经为最终提交建立完整账户完成位图。 */
    void laneContextInitialized(boolean value) { set(LANE_CONTEXT_INITIALIZED, value); }
    /** 读取生命周期位，说明是否已经取得本批执行上下文。 */
    public boolean activated() { return has(ACTIVATED); }
    /** 只更新生命周期位，记录是否已经取得本批执行上下文。 */
    public void activated(boolean value) { set(ACTIVATED, value); }

    /** 容量至少取一，要求显式响应分配器，分配本批各列和项槽。 */
    public PendingBatch(int requestedCapacity, ResponseSlotPool responseSlotPool) {
        int capacity = Math.max(1, requestedCapacity);
        this.responseSlotPool = java.util.Objects.requireNonNull(responseSlotPool, "response slot pool");
        items = new ArrayList<>(capacity);
        itemSlots = new BatchItem[capacity];
        changedUserIds = new PrimitiveLongChangeSet(capacity * 2);
        runtimeChangedOrderIds = new PrimitiveLongChangeSet(capacity * 2);
        preparedOrders = new OrderCommand.ResolvedPlaceOrder[capacity];
        preparedDecisions = new Decision[capacity];
        preparedClientKeyValues = new long[capacity];
        preparedAdmittedOrders = new Order[capacity];
        preparedCancellationOrderIds = new long[preparedAdmittedOrders.length];
        preparedSymbols = new ArrayList<>(capacity);
        preparedContextDecisions =
                new Decision[capacity];
    }

    /** 绑定非空批次种类及原日志时间位置。 */
    PendingBatch initialize(OrderBatchProcessor.Kind kind, long clusterTimestamp,
                                         long clusterPosition) {
        this.kind = java.util.Objects.requireNonNull(kind, "batch kind");
        this.clusterTimestamp = clusterTimestamp;
        this.clusterPosition = clusterPosition;
        return this;
    }

    /** 返回当前预分配的项容量，不表示已经装入的项数。 */
    int capacity() {
        return preparedOrders.length;
    }

    /** 清空当前品种前缀的共享判断引用和品种列表。仅在准入交出前或整批退休后使用，不能清仍被账户借用的输入。 */
    void clearPreparedDecisionCache() {
        java.util.Arrays.fill(preparedContextDecisions, 0, preparedSymbols.size(), null);
        preparedSymbols.clear();
    }

    /** 使用当前项数作为下标，首次创建或复用该位置项槽，再追加到列表。容量由调用方预先保证，旧项必须已清理。 */
    public void addItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        int index = items.size();
        BatchItem item = itemSlots[index];
        if (item == null) itemSlots[index] = item = new BatchItem(orderId, originalOrderId, replacementOrderId, command);
        else item.initialize(orderId, originalOrderId, replacementOrderId, command);
        items.add(item);
    }

    /** 在所有借用结束后清本批输入、结果、有效前缀和等待标记，归还仍由本批持有的响应槽，保留数组及工作对象容量。 */
    void clear() {
        int preparedCount = items.size();
        for (BatchItem item : items) item.clear();
        items.clear();
        decodedCommand = null;
        changedUserIds.clear();
        runtimeChangedOrderIds.clear();

        nextIndex = 0;
        tradeCount = 0;
        responseOrder.clear();
        if (preparedResponseSlot != null) responseSlotPool.release(preparedResponseSlot);
        preparedResponseSlot = null;
        preparedResponse = null;
        preparedResponseLength = 0;
        cancellationChunkStart = cancellationChunkEnd = 0;
        sequence = 0;
        currentPreMatchingCancellationOrderIds = List.of();
        commitState = COMMIT_WAITING;
        pipelined = false;
        sequentialAdmission = false;
        pipelinedMatchingFailure = null;
        settlementEvent = null;
        placeBatchAdmissionEvent = null;
        for (int index = 0; index < preparedCount; index++) {
            if (preparedOrders[index] != null) preparedOrders[index].clear();
        }
        java.util.Arrays.fill(preparedOrders, 0, preparedCount, null);
        java.util.Arrays.fill(preparedDecisions, 0, preparedCount, null);
        java.util.Arrays.fill(preparedClientKeyValues, 0, preparedCount, 0);
        java.util.Arrays.fill(preparedAdmittedOrders, 0, preparedCount, null);
        clearPreparedDecisionCache();
        lifecycleFlags = 0;
        observedSettlementLaneMask = 0;
        actualLaneMask = 0;
        lastMatchingResult = null;
        noNativeCommandResult.clearCoreSequence();
        itemSettlementEvent = null;
        replacementAdmission = null;
        replacementAdmissionEvent = null;
        itemAdmission = null;
        itemAdmissionRevision = 0;
        laneCommitEvent = null;
        placeReservation.clear();
        pipelinedPlaceBatchSubmission.clear();
        cancelBatchChunkSubmission.clear();
        kind = null;
        clusterTimestamp = 0;
        clusterPosition = 0;
    }

    /** 只检查最终 Lane 提交事件或尚未收集的整批结算事件。 */
    public boolean hasPendingLaneWork() {
        return laneCommitEvent != null
                || settlementEvent != null && !settlementsCollected();
    }

    /** 检查已有单项、最终提交及未收集整批事件是否都完成。 */
    public boolean laneWorkComplete() {
        if (itemSettlementEvent != null && !itemSettlementEvent.complete()) return false;
        if (laneCommitEvent != null && !laneCommitEvent.complete()) return false;
        if (settlementEvent != null && !settlementsCollected() && !settlementEvent.complete()) return false;
        return true;
    }

    /** 登记主动用户及成交 maker。 */
    void collectChangedOrderIds(
            BatchItem item,
            long takerUserId,
            OrderMatchingResult matchingResult) {
        changedUserIds.add(takerUserId);
        boolean recordOrders = kind == OrderBatchProcessor.Kind.PLACE || matchingResult.accepted();
        if (recordOrders) {
            runtimeChangedOrderIds.add(item.orderId());
            if (item.originalOrderId() > 0) runtimeChangedOrderIds.add(item.originalOrderId());
            if (item.replacementOrderId() > 0) runtimeChangedOrderIds.add(item.replacementOrderId());
        }
        var matcherEvents = matchingResult.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() == MatcherEventType.TRADE) {
                changedUserIds.add(event.matchedOrderUid());
                if (recordOrders) runtimeChangedOrderIds.add(event.matchedOrderId());
            }
        }
        var cancellations = matchingResult.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            OrderCancelResult cancellation = cancellations.get(index);
            if (cancellation.accepted()) {
                runtimeChangedOrderIds.add(cancellation.orderId());
            }
        }
    }

    /** 返回实际批内项数，不是数组容量。 */
    public int itemCount() { return items.size(); }

    /** 顺序批量下单中一个当前项的准入和冻结任务。Owner 只推进原账本及控制邮箱，Lane 计算并冻结，订单租约保留在原批次槽。任务可复用，但必须等 Lane 释放并由控制调度器发布结果。 */
    public static final class BatchPlaceReservation extends OrderCommand.ResolvedPlaceOrder implements IntFunction<Object>, BooleanSupplier {
        private com.surprising.aeron.service.core.TradingAccountRuntime runtime;
        private com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities;
        private Decision decision;
        private long sequence, userId, openInterestSteps;
        private UUID commandId;
        private Order admittedOrder;
        private PendingBatch source;
        private int sourceIndex;
        @Accessors(fluent = true, chain = false)
        @Getter
        private List<Long> closeCapacityCancellations = List.of();
        private volatile boolean completed = true;
        private boolean accountDispatched;

        public void prepare(com.surprising.aeron.service.core.TradingAccountRuntime runtime,
                com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities,
                long sequence, long userId, UUID commandId, com.surprising.aeron.protocol.PlaceOrderCommand command,
                Decision decision,
                PendingBatch source, int sourceIndex) {
            if (runtime == null || identities == null || sequence <= 0 || userId <= 0
                    || commandId == null || command == null || decision == null || source == null || sourceIndex < 0)
                throw new IllegalArgumentException("invalid batch place reservation");
            runtime.assertOwner();
            if (!completed) throw new IllegalStateException("Account Lane still owns this batch reservation");
            clear();
            this.decision = decision;
            this.source = source;
            this.sourceIndex = sourceIndex;
            initializeOrderIntent(command, decision.context().instrument(), decision.context().symbolId());
            this.runtime = runtime;
            this.identities = identities;
            this.sequence = sequence;
            this.userId = userId;
            this.commandId = commandId;
            if (decision.context().instrument().usesOpenInterestForUserLimit() && !command.reduceOnly()) {
                this.openInterestSteps = runtime.ledgerAdmissionOpenInterest(decision.context().instrument().instrumentId());
            } else {
                this.openInterestSteps = 0;
            }
            accountDispatched = false;
            completed = false;
        }

        @Override public boolean getAsBoolean() { return pollAdmission(); }

        public boolean pollAdmission() {
            runtime.assertOwner();
            if (!accountDispatched) {
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(userId), this);
                accountDispatched = true;
            }
            return runtime.pollControlLanes();
        }

        @Override public Object apply(int laneId) {
            com.surprising.aeron.service.account.AccountPartition lane = runtime.currentLaneCommandScope();
            if (lane == null || lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId))
                throw new IllegalStateException("batch place reservation requires its Account Lane");
            long identity = 0;
            try {
                com.surprising.aeron.service.order.OrderAdmissionService.resolveInto(this, decision.context(), userId, intent());
                int assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                        : side() == com.surprising.aeron.protocol.CoreOrderSide.BUY ? decision.quoteAssetId() : decision.baseAssetId();
                identity = identities.prepareClientKeyInLane(lane, userId, clientOrderId());
                long clientKey = com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.clientKeyValue(identity);
                long positionKey = identities.findPositionKeyValueInLane(lane, userId, instrument(), positionSide());
                long required = com.surprising.aeron.service.order.OrderAdmissionService.requiredReservationPrepared(runtime, lane, userId, this,
                        openInterestSteps, clientKey, positionKey,
                        decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                com.surprising.aeron.service.position.Position position = positionKey == 0 ? null : lane.position(positionKey);
                if (instrument().contractType().productLine().isDerivative() && !reduceOnly()
                        && position != null && position.signedQuantitySteps() != 0
                        && (position.signedQuantitySteps() > 0) != (side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                    closeCapacityCancellations = com.surprising.aeron.service.position.Position.CloseCapacity.conflictsFor(lane, userId, symbolId(),
                            positionKey, positionSide(), side(), orderId(), quantitySteps());
                }
                runtime.placePreparedOrder(userId, this, commandId,
                        required, clientKey, symbolId(), assetId);
                lane.markPendingReservation(orderId(), sequence);
                Order admitted = runtime.laneDelta(laneId).orders().get(orderId());
                if (admitted == null) throw new IllegalStateException("batch admission order is missing");
                admittedOrder = source.captureAdmittedOrder(sourceIndex, admitted);
                return null;
            } catch (RuntimeException | Error failure) {
                if (identity != 0) identities.rollbackClientKeyInLane(lane, userId, clientOrderId(), identity);
                throw failure;
            } finally {
                completed = true;
            }
        }

        public Order admittedOrder() {
            if (admittedOrder == null) throw new IllegalStateException("batch reservation has not completed");
            return admittedOrder;
        }

        public void clear() {
            if (!completed) return;
            clearResolvedOrder();
            runtime = null;
            identities = null;
            decision = null;
            commandId = null;
            sequence = userId = openInterestSteps = 0;
            admittedOrder = null;
            source = null;
            sourceIndex = 0;
            closeCapacityCancellations = List.of();
            accountDispatched = false;
        }
    }
}

static final class BatchItem {
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long orderId;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long originalOrderId;
    @Accessors(fluent = true, chain = false)
    @Getter(value = AccessLevel.PACKAGE)
    long replacementOrderId;
    Object command;
    ResponseStatus status;
    CoreResultCode resultCode;
    List<MatcherEvent> executionEvents = List.of();
    private exchange.core2.core.common.MatcherResult executionSlot;
    /** 本项持有的准入订单可复用副本，不能当作活跃账户订单修改。 */
    private com.surprising.aeron.service.order.Order admissionCopy;
    private com.surprising.aeron.service.order.Order resultCopy;

    /** 把准入订单复制到本项可复用槽，传空只解除当前结果引用。 */
    void captureAdmittedOrder(com.surprising.aeron.service.order.Order order) {
        admittedOrder = order == null ? null : (admissionCopy = order.copyIntoCommandSlot(admissionCopy));
    }

    /** 把结算后订单复制到本项结果槽，空输入表示响应没有这张订单视图。 */
    void captureResultOrder(com.surprising.aeron.service.order.Order order) {
        resultOrder = order == null ? null : (resultCopy = order.copyIntoCommandSlot(resultCopy));
    }

    /** 空事件表使用共用空表并返回零。否则复制到本项 Matcher 事件槽，保留全部事件供后续读取，并单独统计成交事件数量。 */
    int captureExecutions(List<MatcherEvent> events) {
        if (events.isEmpty()) {
            executionEvents = List.of();
            return 0;
        }
        if (executionSlot == null) executionSlot = exchange.core2.core.common.MatcherResult.commandSlot();
        executionSlot.captureEvents(events);
        executionEvents = executionSlot.events();
        int trades = 0;
        for (int index = 0; index < executionEvents.size(); index++) {
            if (executionEvents.get(index).eventType() == MatcherEventType.TRADE) trades++;
        }
        return trades;
    }
    int executionCount;
    long executionTakerUserId;
    com.surprising.aeron.service.order.Order resultOrder;
    String resultOrderSymbol;
    boolean laneResultPrepared;
    /** 提交前不能归入其他命令的实时范围。 */
    com.surprising.aeron.service.order.Order realtimeTakerOrder;
    com.surprising.aeron.service.order.Order admittedOrder;
    /** Owner 为当前撤单片段解析的品种名称，Matcher 借用后清理。 */
    String cancelSymbol;

    /** 保存本项订单身份及输入。 */
    BatchItem(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        initialize(orderId, originalOrderId, replacementOrderId, command);
    }

    /** 仅重新绑定订单身份与输入，不重置旧结果。 */
    void initialize(long orderId, long originalOrderId, long replacementOrderId, Object command) {
        this.orderId = orderId;
        this.originalOrderId = originalOrderId;
        this.replacementOrderId = replacementOrderId;
        this.command = command;
    }

    /** 清掉本项输入、状态、成交及订单结果引用，清理复用副本内容。 */
    void clear() {
        orderId = originalOrderId = replacementOrderId = 0;
        command = null;
        status = null;
        resultCode = null;
        executionEvents = List.of();
        if (executionSlot != null) executionSlot.clearCommandSlot();
        executionCount = 0;
        executionTakerUserId = 0;
        resultOrder = null;
        resultOrderSymbol = null;
        laneResultPrepared = false;
        realtimeTakerOrder = null;
        admittedOrder = null;
        if (admissionCopy != null) admissionCopy.clearCommandSlot();
        if (resultCopy != null) resultCopy.clearCommandSlot();
        cancelSymbol = null;
    }

}

public static final class PlaceBatchAdmissionEvent implements AccountLaneCommand {
    @Accessors(fluent = true, chain = false)
    @Getter
    private long coreSequence;
    private long timestamp, position;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    private UUID commandId;
    /** Owner 交接的稳定批次输入及原命令槽入口，Lane 完成前不得复用。 */
    private OrderBatchProcessor.PendingBatch source;
    private OrderCommand.ResolvedPlaceOrder[] orders;
    private long[] clientKeys;
    /** 原批次槽保存的逐项获准订单租约数组，后续撮合继续使用。借用本批获准订单租约数组，调用方须遵守原批次生命周期，只使用有效项前缀。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private Order[] admittedOrders;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int itemCount;
    private int admittedCount;
    /** 本批唯一账户 Lane 编号，必须与用户拓扑一致。 */
    private int laneId;
    private int matcherShard;
    private TradingAccountRuntime runtime;
    private TradeSettlement.Changes changes;
    private RuntimeIdentityRegistry identities;
    private UserAccount userBefore;
    private UserAccount userBeforeSlot;
    private RuntimeException rejection;
    /** Lane 写完结果和拒绝信息后发布的完成位，读取结果及回收前必须观察它。 */
    private volatile boolean completed;

    /** 校验有效项数、借用数组、命令和拓扑范围，绑定本批稳定输入及原变更缓冲，重置成功前缀和结果标记。 */
    public OrderBatchProcessor.PlaceBatchAdmissionEvent prepare(
            long coreSequence, long userId, UUID commandId, OrderCommand.ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            Order[] admittedOrders,
            int itemCount, int laneId, int matcherShard,
            TradingAccountRuntime runtime, TradeSettlement.Changes changes, RuntimeIdentityRegistry identities, OrderBatchProcessor.PendingBatch source, long timestamp, long position) {
        if (timestamp < 0 || position < 0 || coreSequence <= 0 || userId <= 0 || commandId == null || orders == null
                || source == null || identities == null || clientKeys == null || admittedOrders == null || itemCount <= 0
                || itemCount > orders.length || itemCount > clientKeys.length
                || itemCount > admittedOrders.length
                || laneId < 0 || matcherShard < 0
                || runtime == null || matcherShard >= runtime.topology().matchingEngineCount() || changes == null) {
            throw new IllegalArgumentException("invalid place batch admission event");
        }
        this.coreSequence = coreSequence;
        this.timestamp = timestamp;
        this.position = position;
        this.userId = userId;
        this.commandId = commandId;
        this.orders = orders;
        this.source = source;
        this.clientKeys = clientKeys;
        this.admittedOrders = admittedOrders;
        this.itemCount = itemCount;
        this.laneId = laneId;
        this.matcherShard = matcherShard;
        this.runtime = runtime;
        this.changes = changes;
        this.identities = identities;
        admittedCount = 0;
        rejection = null;
        completed = false;
        return this;
    }

    /** 在唯一所属 Lane 捕获用户前值，按项解析决策、准备身份、检查风险及容量并冻结。 */
    public void execute(AccountPartition lane) {
        if (lane.laneId() != laneId || laneId != runtime.topology().accountLaneId(userId)) {
            throw new IllegalStateException("place batch admission reached the wrong Account Lane");
        }
        UserAccount currentUser = lane.user(userId);
        userBefore = currentUser == null ? null
                : (userBeforeSlot = currentUser.copyIntoCommandSlot(userBeforeSlot));
        try {
            runtime.enterMatcherSettlementScope(lane, changes);
            lane.admissionIndexCapacity(Math.max(2, itemCount));
            try {
                for (int index = 0; index < itemCount; index++) {
                    var decision = source.decision(index);
                    OrderCommand.ResolvedPlaceOrder order = OrderAdmissionService.resolveInto(
                            source.resolvedOrderSlot(index), decision.context(), userId, source.intent(index));
                    orders[index] = order;
                    int symbolId = order.symbolId();
                    int assetId = runtime.productLine().isDerivative() ? decision.settleAssetId()
                            : order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                            ? decision.quoteAssetId() : decision.baseAssetId();
                    long key = RuntimeIdentityRegistry.clientKeyValue(
                            identities.prepareClientKeyInLane(lane, userId, order.clientOrderId()));
                    clientKeys[index] = key;
                    long positionKey = identities.findPositionKeyValueInLane(
                            lane, userId, order.instrument(), order.positionSide());
                    if (runtime.productLine().isDerivative() && positionKey != 0) {
                        var position = lane.position(positionKey);
                        if (position != null && position.signedQuantitySteps() != 0
                                && (position.signedQuantitySteps() > 0) != (order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
                            var summary = lane.admissionSummary(symbolId, userId,
                                    order.positionSide(), order.side(), order.marginMode());
                            if (summary.reduceOnlyQuantity() > 0 && Math.addExact(Math.addExact(summary.pendingQuantity(), summary.reduceOnlyQuantity()), order.quantitySteps())
                                    > Math.absExact(position.signedQuantitySteps()))
                                throw new CoreStateRejectedException("CLOSE_CAPACITY_REQUIRES_ORDERED_ADMISSION", "close commitments require ordered cancellation");
                        }
                    }
                    long requiredReservation = OrderAdmissionService.requiredReservationPrepared(
                            runtime, lane, userId, order, decision.openInterestSteps(),
                            key, positionKey,
                            decision.context().lifecycleSettled(), decision.context().fundingInProgress());
                    runtime.placeOrderInLane(lane, userId, order, commandId,
                            requiredReservation, clientKeys[index], symbolId, assetId,
                            coreSequence, null, timestamp, position);
                    Order live = lane.order(order.orderId());
                    admittedOrders[index] = source.captureAdmittedOrder(index, live);
                    admittedCount++;
                }
                changes.prepareAdmissionLane(laneId);
            } finally {
                lane.admissionIndexCapacity(2);
                runtime.exitMatcherSettlementScope(lane, changes);
            }
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            runtime.rollbackPlaceBatchAdmissionInLane(
                    lane, userId, coreSequence, orders, clientKeys, admittedCount, userBefore);
            for (int i = 0; i < itemCount; i++) {
                if (clientKeys[i] != 0 && orders[i] != null && orders[i].hasIntent()) {
                    identities.rollbackClientKeyInLane(lane, userId, orders[i].clientOrderId(), clientKeys[i]);
                }
                clientKeys[i] = 0;
            }
            admittedCount = 0;
            rejection = failure;
        }
        TradingAccountRuntime completionRuntime = runtime;
        lane.recordCommandOperation();
        completed = true;
        completionRuntime.publishAccountAdmissionReadyShard(matcherShard);
    }

    /** 要求 Lane 已完成且变更缓冲已交出，清理借用数组和结果引用，清空但保留用户前值槽以便后续复用。 */
    public void clear() {
        if (!complete() || changes != null) {
            throw new IllegalStateException("cannot recycle an incomplete place batch admission");
        }
        source = null;
        orders = null;
        clientKeys = null;
        admittedOrders = null;
        userBefore = null;
        if (userBeforeSlot != null) userBeforeSlot.clearCommandSlot();
        runtime = null;
        identities = null;
        rejection = null;
        itemCount = 0;
        admittedCount = 0;
        matcherShard = 0;
    }

    /** 返回 Lane 是否发布了本批准入完成，成功和已回退的业务拒绝都可完成。 */
    public boolean complete() { return completed; }
    /** 完成后读取本批原始拒绝异常，成功时为 null。 */
    public RuntimeException rejection() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return rejection;
    }
    /** 完成后读取供本批回退的用户前值，原来没有用户时为 null。 */
    public UserAccount userBefore() {
        if (!complete()) throw new IllegalStateException("place batch admission is incomplete");
        return userBefore;
    }
    /** 按有效项下标读取该项获准订单租约，不新建订单副本。 */
    Order admittedOrder(int index) { return admittedOrders[index]; }
    /** 按有效项下标读取 Lane 已解析的订单决策输入，引用仍受原批次生命周期约束。 */
    OrderCommand.ResolvedPlaceOrder order(int index) { return orders[index]; }
    /** 完成后一次性交出原结算变更缓冲并清空事件引用，第二次获取或提前读取都报错。 */
    public TradeSettlement.Changes takeChanges() {
        if (!complete() || changes == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        TradeSettlement.Changes value = changes;
        changes = null;
        return value;
    }

    /** 在事件完成且仍持有变更缓冲时，把本批余额前值交给指定原有补丁缓冲，供后续正确回退使用。 */
    public void copyBalanceBeforeTo(AccountTransactionRollback.BalancePatches target) {
        if (!complete() || changes == null || target == null) {
            throw new IllegalStateException("place batch admission changes are unavailable");
        }
        changes.copyBalanceBeforeTo(target);
    }
    /** 取出并清空当前变更缓冲，同时设置完成位供清理。 */
    public TradeSettlement.Changes discardChanges() {
        TradeSettlement.Changes value = changes;
        changes = null;
        completed = true;
        return value;
    }

}

}
