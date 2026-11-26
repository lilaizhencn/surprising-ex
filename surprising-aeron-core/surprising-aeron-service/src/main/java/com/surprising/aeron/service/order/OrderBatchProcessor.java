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
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.matcher.OrderCancelResult;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.result.OrderSource;
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
    final TradingCore owner;

    private PendingOrderBatch firstBatch, lastBatch;
    private int batchCount;

    /** 返回排序链是否还有未退休批次，不表示其已开始执行。 */
    public boolean hasPendingBatches() { return firstBatch != null; }
    /** 借用当前最早批次，空链返回空。 */
    PendingOrderBatch firstBatch() { return firstBatch; }
    /** 返回已登记排序链的批次数量。 */
    int pendingBatchCount() { return batchCount; }
    /** 按完整 Core 序号从已有命令环取得批次，不另建序号到批次的索引。 */
    public PendingOrderBatch batch(long sequence) {
        OrderCommand pending = owner.pendingMatching().get(sequence);
        return pending == null ? null : pending.orderBatch;
    }

    /** 核对命令槽、批次序号和未占用链接，要求序号递增，再把批次连到尾部并绑定原槽。 */
    void registerBatch(OrderCommand pending, PendingOrderBatch batch) {
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
    void unregisterBatch(OrderCommand pending, PendingOrderBatch batch) {
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

    final HashMap<String, PendingOrderBatch> pipelinedBatchBySymbol = new HashMap<>();

    int activePipelinedOrderBatches;

    /** Owner 持有的已退休批次复用池，Matcher 或账户仍借用时不能归还。 */
    final java.util.ArrayDeque<PendingOrderBatch> orderBatchPendingPool = new java.util.ArrayDeque<>();

    /** 解码并检查整批订单身份，已知业务错误释放预备批次并拒绝。其他输入错误按原撮合顺序记拒绝。成功分配整批序号并登记依赖，尝试流水或队首激活，尚未完成则释放 Owner 账户访问权并返回待处理状态。 */
    public CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, TradingCore.SourceKey sourceKey,
                                                  CommandFingerprint fingerprint) {
        PendingOrderBatch batch = null;
        DecodedOrderCommand decodedCommand;
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
        OrderCommand pending = owner.orderChecks().newPendingMatching(sequence, batch.operation(), message, fingerprint, decodedCommand);
        batch.sequence = sequence;
        owner.matchingFlow.putPendingMatching(pending);
        owner.orderChecks().registerPendingLifecycle(pending);
        registerBatch(pending, batch);
        owner.pendingMatching().registerSubmission(sequence, orderBatchMatcherShard(batch));
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        CoreResponse completed = tryActivatePipelinedOrderBatch(batch, pending)
                ? null
                : !pending.clusterIndependent && owner.pendingMatching().firstSequence() == sequence
                ? activateOrderBatch(batch, pending, false) : null;
        if (completed != null) return completed;
        owner.accountBook().releaseOwnerLaneAccess();
        return message.matchingPendingResponse(com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 首次建立本批提交上下文，否则恢复原上下文，再标记激活。 */
    public CoreResponse activateOrderBatch(PendingOrderBatch batch, OrderCommand pending,
                                             boolean deferCompletion) {
        boolean firstActivation = !batch.commitStarted();
        if (firstActivation) beginOrderBatchCommitContext(batch, pending);
        else if (pending.hasCommitContext())
            owner.restoreMatchingCommitContext(pending);
        else owner.activateFactContext(pending.command(), pending.fingerprint());
        batch.activated(true);
        if (preparePipelinedPlaceBatch(batch, pending)) {
            registerPipelinedBatchSymbols(batch, pending);
            dispatchPipelinedPlaceBatchOrderCheck(pending, batch);
            owner.accountBook().releaseOwnerLaneAccess();
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp,
                batch.clusterPosition, deferCompletion);
        if (response == null) owner.clearFactContext();
        return response;
    }

    /** 独立批量撤单可先尝试提交。 */
    boolean tryActivatePipelinedOrderBatch(PendingOrderBatch batch, OrderCommand pending) {
        if (pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL && !batch.sequentialOrderCheck) {
            batch.activated(true);
            owner.matchingFlow.submitMatching(pending);
            return true;
        }
        if (batch.sequentialOrderCheck || batch.kind != OrderBatchKind.PLACE
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
        dispatchPipelinedPlaceBatchOrderCheck(pending, batch);
        return true;
    }

    /** 首次登记本批各品种的最新批次引用并计数，独立模式沿用入口已经核对的依赖。违背所有权约束抛错，已写入的前缀不在此回滚。 */
    void registerPipelinedBatchSymbols(PendingOrderBatch batch, OrderCommand pending) {
        if (batch.pipelineRegistered()) return;
        for (String instrumentId : batch.preparedSymbols) {
            PendingOrderBatch previous = pipelinedBatchBySymbol.put(instrumentId, batch);
            if (previous != null && previous != batch && !pending.clusterIndependent) {
                throw new IllegalStateException("pipelined instrumentId ownership conflict");
            }
        }
        batch.pipelineRegistered(true);
        activePipelinedOrderBatches++;
    }

    /** 只移除仍指向本批的品种条目，清登记位并递减活动数，计数为负时抛错。 */
    void unregisterPipelinedBatchSymbols(PendingOrderBatch batch) {
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
    boolean preparePipelinedPlaceBatch(PendingOrderBatch batch, OrderCommand pending) {
        if (batch.sequentialOrderCheck || batch.kind != OrderBatchKind.PLACE) {
            return false;
        }
        long userId = pending.command().header().userId();
        try {
            int batchMatcherShard = -1;
            batch.clearPreparedDecisionCache();
            for (int index = 0; index < batch.items.size(); index++) {
                OrderBatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                int itemMatcherShard = batch.decodedCommand == null
                        ? owner.matchingAdapter().matcherShardId(command.instrumentId())
                        : batch.decodedCommand.matcherShard(owner.matchingAdapter(), command.instrumentId());
                if (batchMatcherShard == -1) batchMatcherShard = itemMatcherShard;
                else if (batchMatcherShard != itemMatcherShard) throw new TradingCore.PipelinedBatchNotApplicable();
                if (command.reduceOnly()) throw new TradingCore.PipelinedBatchNotApplicable();
                owner.requireOrderIdentityAvailable(userId, command);
                var decision = prepareBatchDecision(batch, command.instrumentId());
                if (decision.context().instrument().usesOpenInterestForUserLimit())
                    throw new TradingCore.PipelinedBatchNotApplicable();
                batch.preparedDecisions[index] = decision;
            }
            batch.pipelined = true;
            return true;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | TradingCore.PipelinedBatchNotApplicable exception) {
            batch.currentPreMatchingCancellationOrderIds = List.of();
            batch.clearPreparedDecisionCache();
            return false;
        }
    }

    /** 确定本批唯一分片，把整批输入列、客户键列、订单回执列和原日志元数据交所属账户做整批准入。 */
    void dispatchPipelinedPlaceBatchOrderCheck(
            OrderCommand pending, PendingOrderBatch batch) {
        int shard = batch.decodedCommand == null
                ? owner.matchingAdapter().matcherShardId(batch.preparedSymbols.getFirst())
                : batch.decodedCommand.matcherShard(owner.matchingAdapter(), batch.preparedSymbols.getFirst());
        batch.placeBatchOrderCheckEvent = owner.accountBook().dispatchPlaceBatchOrderCheck(
                pending.sequence(), pending.command().header().userId(), pending.command().header().commandId(),
                batch.preparedOrders, batch.preparedClientKeyValues,
                batch.preparedAdmittedOrders,
                batch.items.size(), shard, owner.identities(), batch,
                batch.clusterTimestamp, batch.clusterPosition);
    }

    /** 固定提交边界并准备整批直达事件，绑定固定 Matcher 任务后提交。 */
    void submitPipelinedPlaceBatch(OrderCommand pending, PendingOrderBatch batch) {
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        long userId = pending.command().header().userId();
        int shard = owner.matchingAdapter().matcherShardId(batch.preparedSymbols.getFirst());
        batch.settlementEvent = owner.accountBook().prepareDirectMatcherSettlement(
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
            OrderCommand pending, PendingOrderBatch batch, long userId, int shard) {
        try {
            exchange.core2.core.common.MatcherResult firstNative = null;
            java.util.Objects.requireNonNull(batch.settlementEvent, "direct batch settlement");
            for (int index = 0; index < batch.items.size(); index++) {
                OrderBatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                ResolvedPlaceOrder matchingOrder = batch.preparedOrders[index];
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
    CoreResponse startOrderBatchItem(PendingOrderBatch batch, OrderCommand pending,
                                             long clusterTimestamp, long clusterPosition,
                                             boolean deferCompletion) {
        batch.sequentialOrderCheck = true;
        while (batch.nextIndex < batch.items.size()) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            boolean orderCheckPending = batch.itemOrderCheck != null || batch.replacementOrderCheckEvent != null;
            long runtimeRevisionBefore = !orderCheckPending
                    ? owner.accountBook().revision() : batch.itemOrderCheckRevision;
            try {
                if (!orderCheckPending) {
                    batch.itemOrderCheckRevision = runtimeRevisionBefore;
                    prepareOrderBatchItem(batch, item, pending.command().header().userId(),
                            pending.command().header().commandId());
                }
                if (batch.replacementOrderCheckEvent != null && !collectBatchAmendOrderCheck(batch, pending.command().header().userId())) {
                    batch.sequentialOrderCheck = true;
                    batch.activated(false);
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                if (batch.itemOrderCheck != null) {
                    if (!batch.itemOrderCheck.getAsBoolean()) {
                        batch.sequentialOrderCheck = true;
                        batch.activated(false);
                        owner.suspendMatchingCommitContext(pending);
                        return null;
                    }
                    collectBatchPlaceReservation(batch);
                    batch.itemOrderCheck = null;
                }
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                owner.pendingMatching().put(pending);
                owner.matchingFlow.submitMatching(pending);
                // 变更上下文归本序号持有，不能占用下一条命令的 owner。
                owner.suspendMatchingCommitContext(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                boolean laneOrderCheckFailure = batch.itemOrderCheck != null || batch.replacementOrderCheckEvent != null;
                releaseBatchAmendOrderCheck(batch);
                batch.itemOrderCheck = null;
                if (!laneOrderCheckFailure)
                    requireUnchangedRejectedBatchItem(pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()));
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                boolean laneOrderCheckFailure = batch.itemOrderCheck != null || batch.replacementOrderCheckEvent != null;
                releaseBatchAmendOrderCheck(batch);
                batch.itemOrderCheck = null;
                if (!laneOrderCheckFailure)
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
    PendingOrderBatch.Decision prepareBatchDecision(
            PendingOrderBatch batch, String instrumentId) {
        int index = batch.preparedSymbols.indexOf(instrumentId);
        if (index >= 0) return batch.preparedContextDecisions[index];
        var context = OrderCheckService.context(owner.accountBook(), owner.identities(), instrumentId, batch.clusterTimestamp);
        var instrument = context.instrument();
        String baseAsset = instrument.baseAsset();
        String quoteAsset = instrument.quoteAsset();
        String settleAsset = instrument.settleAsset();
        int baseAssetId = owner.identities().assetId(baseAsset);
        int quoteAssetId = baseAsset.equals(quoteAsset) ? baseAssetId : owner.identities().assetId(quoteAsset);
        int settleAssetId = quoteAsset.equals(settleAsset) ? quoteAssetId
                : baseAsset.equals(settleAsset) ? baseAssetId : owner.identities().assetId(settleAsset);
        var decision = new PendingOrderBatch.Decision(context, 0,
                baseAssetId, quoteAssetId, settleAssetId);
        index = batch.preparedSymbols.size();
        batch.preparedSymbols.add(instrumentId);
        batch.preparedContextDecisions[index] = decision;
        return decision;
    }

    /** 用本批品种判断输入准备固定账户预留工作，绑定当前 Core 序号和项下标，返回同一工作供 Owner 轮询。 */
    private java.util.function.BooleanSupplier beginBatchReservation(PendingOrderBatch batch, long userId,
                                                                    PlaceOrderCommand command, UUID commandId) {
        var decision = prepareBatchDecision(batch, command.instrumentId());
        var work = batch.placeReservation;
        work.prepare(owner.accountBook(), owner.identities(), batch.sequence, userId, commandId,
                command, decision, batch, batch.nextIndex);
        return work;
    }

    private void collectBatchPlaceReservation(PendingOrderBatch batch) {
        var work = batch.placeReservation;
        Order admitted = work.admittedOrder();
        owner.accountBook().pendingReservations().collectControlReservation(admitted, batch.sequence);
        // 所属账户已经准备好本项持有的固定副本，Owner 只绑定其引用。
        batch.items.get(batch.nextIndex).admittedOrder = admitted;
        batch.currentPreMatchingCancellationOrderIds = work.closeCapacityCancellations();
        owner.commits.publication.request();
    }

    /** 临时切到整批原时间和日志位置。finally 恢复原 Owner 时间位置，账户工作仍由所属 Lane 完成。 */
    void prepareOrderBatchItem(PendingOrderBatch batch, OrderBatchItem item, long userId,
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
                    batch.itemOrderCheck = beginBatchReservation(batch, userId, command, commandId);
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
                    batch.replacementOrderCheckEvent = owner.accountBook().dispatchReplaceOrderCheck(
                            batch.sequence, userId, command.originalOrderId(), command, null, decision,
                            owner.matchingAdapter().matcherShardId(instrumentId), owner.identities());
                }
            }
        } finally {
            owner.currentClusterTimestamp(previousTimestamp);
            owner.currentClusterPosition(previousPosition);
        }
    }

    private String previousBatchOrderInstrument(PendingOrderBatch batch, long orderId) {
        for (int index = 0; index < batch.nextIndex; index++) {
            OrderBatchItem previous = batch.items.get(index);
            if (previous.orderId() == orderId && previous.status == ResponseStatus.APPLIED
                    && previous.admittedOrder != null)
                return previous.admittedOrder.instrument().instrumentId();
        }
        return null;
    }

    /** 完成则抛出已有拒绝，再用 Owner 路由索引核对全局订单号和客户身份，保存替换准入及先撤列表。 */
    private boolean collectBatchAmendOrderCheck(PendingOrderBatch batch, long userId) {
        var event = batch.replacementOrderCheckEvent;
        if (!event.complete()) { owner.accountBook().assertAccountLanesHealthy(); return false; }
        if (event.rejection() != null) throw event.rejection();
        var orderCheck = event.orderCheck();
        // Owner 用已有活动路由索引再检查其他 Lane 是否已有同编号订单。
        if (owner.activeOrderIndex().activeOrderRoute(orderCheck.command().orderId()) != null)
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
        owner.requireOrderIdentityAvailable(userId, orderCheck.command());
        batch.replacementOrderCheck = orderCheck;
        batch.currentPreMatchingCancellationOrderIds = event.closeCapacityCancellations();
        return true;
    }

    private void releaseBatchAmendOrderCheck(PendingOrderBatch batch) {
        if (batch.replacementOrderCheckEvent == null) return;
        owner.accountBook().releaseReplaceOrderCheck(batch.replacementOrderCheckEvent);
        batch.replacementOrderCheckEvent = null;
        batch.replacementOrderCheck = null;
    }

    /** 撤单交连续同分片片段路径。顺序下单或改单用当前已准入项预先借直达事件，再把固定任务交 Matcher。账户结算事件在撮合前已存在，Owner 后续只收集它。 */
    void submitOrderBatchMatching(OrderCommand pending) {
        PendingOrderBatch batch = pending.orderBatch;
        if (batch == null) return;
        batch.lastMatchingResult = null;
        if (batch.kind == OrderBatchKind.CANCEL) {
            submitCancelBatchChunk(pending, batch);
            if (pending.clusterIndependent && batch.activated() && !pending.isMatchingSubmitted())
                pending.matchingSubmitted();
            return;
        }
        int shard = orderBatchMatcherShard(batch);
        com.surprising.aeron.service.settlement.TradeSettlement direct = null;
        // 顺序批次各项依赖上一项账户准入，不能提前准备全批聚合结算。
        if (batch.kind == OrderBatchKind.PLACE) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            Order admitted = item.admittedOrder;
            if (admitted == null) throw new IllegalStateException("batch item admission order is missing");
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.accountBook().prepareDirectMatcherSettlement(
                    pending.sequence(), 0, owner.commits.validAccountLaneMask(), admitted, null, 1,
                    pending.command().header().commandId(), shard, owner.identities(),
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), batch);
            direct.reserveMatcherPublication();
            batch.itemSettlementEvent = direct;
        }
        if (batch.kind == OrderBatchKind.AMEND) {
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.accountBook().prepareDirectReplacement(
                    pending.sequence(), 0, owner.commits.validAccountLaneMask(),
                    java.util.Objects.requireNonNull(batch.replacementOrderCheck),
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
    void submitCancelBatchChunk(OrderCommand pending, PendingOrderBatch batch) {
        batch.lastMatchingResult = null;
        int shard = orderBatchMatcherShard(batch);
        int start = batch.nextIndex;
        int end = start;
        // 只合并连续同分片的独立撤单，缺失订单留给 Owner 拒绝路径。
        // 跨分片仍保持原项顺序。
        while (end < batch.items.size()) {
            OrderBatchItem item = batch.items.get(end);
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
            batch.sequentialOrderCheck = true;
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
        var direct = owner.accountBook().prepareDirectCancelBatch(
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
        PendingOrderBatch batch = batch(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
            PendingOrderBatch failedBatch = batch(sequence);
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
        if (batch.kind == OrderBatchKind.CANCEL) {
            if (matchingResult instanceof com.surprising.aeron.service.settlement.TradeSettlement event
                    && event == batch.itemSettlementEvent && event.hasDirectNativeResult()) {
                if (!event.complete()) throw new IllegalStateException("cancel chunk Lane is incomplete");
                batch.observedSettlementLaneMask |= event.requiredLaneMask();
                owner.accountBook().collectOrderBatchMatcherSettlement(event, owner.completedOrderArchive());
                owner.commits.publication.request();
                if (event.commitSequence() != 0) batch.laneCommitCompleted(true);
                owner.accountBook().releaseMatcherSettlement(event);
                batch.itemSettlementEvent = null;
                batch.lastMatchingResult = null;
                boolean trackChanges = !batch.laneCommitCompleted();
                if (trackChanges) {
                    batch.changedUserIds.add(pending.command().header().userId());
                }
                while (batch.nextIndex < batch.cancellationChunkEnd) {
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
        if (batch.itemOrderCheck != null) {
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition, false);
    }

    /** 收当前项已完成财务验证的账户结算。随后完成项结果、回收事件并开始下一项，运行异常转为批次恢复故障。 */
    public CoreResponse completeOrderBatchItemSettlement(PendingOrderBatch batch, OrderCommand pending,
                                                 long timestamp, long position) {
        try {
            var settledEvent = batch.itemSettlementEvent;
            owner.accountBook().collectOrderBatchMatcherSettlement(settledEvent, owner.completedOrderArchive());
            if (batch.kind == OrderBatchKind.AMEND && batch.lastMatchingResult.accepted()) {
                owner.accountBook().setMetadata(owner.productLine(), Math.addExact(owner.accountBook().revision(),
                        owner.productLine() == ProductLine.SPOT ? 2 : 1));
            }
            if (batch.kind != OrderBatchKind.CANCEL) finishOrderBatchItem(batch, pending, batch.lastMatchingResult);
            owner.accountBook().releaseMatcherSettlement(settledEvent);
            batch.itemSettlementEvent = null;
            batch.lastMatchingResult = null;
            return startOrderBatchItem(batch, pending, timestamp, position, false);
        } catch (RuntimeException failure) {
            throw failOrderBatch(batch, pending, "batch item Lane settlement failed", failure);
        }
    }

    /** 保存当前项 Matcher 事实并校验直达事件、收集执行明细。账户或准入仍待完成则返回，否则结束该项。 */
    void applyCompletedOrderBatchItem(PendingOrderBatch batch, OrderCommand pending,
                                      OrderMatchingResult matchingResult) {
        batch.lastMatchingResult = matchingResult;
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        try {
            applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (batch.itemSettlementEvent != null || batch.itemOrderCheck != null) return;
            finishOrderBatchItem(batch, pending, matchingResult);
        } catch (RuntimeException exception) {
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    /** 撮合已发生后的异常必须恢复，不能伪装普通拒单或重试同一修改。 */
    private void finishOrderBatchItem(PendingOrderBatch batch, OrderCommand pending,
                                      OrderMatchingResult matchingResult) {
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
            if (batch.kind != OrderBatchKind.CANCEL) appendOrderBatchResult(item, status, resultCode);
            releaseBatchAmendOrderCheck(batch);
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
            PendingOrderBatch batch, OrderCommand pending, String detail, Throwable cause) {
        // 已发生的撮合事实不能在此撤销。
        // 标记故障并停止续步，将原命令交恢复边界处理，不能继续当成普通拒单。
        owner.matchingAdapter().poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        owner.orderChecks().clearQueuedMatching();
        owner.pendingMatching().forEach(value -> {
            if (value.claimed) {
                value.resetMatchingContinuation();
            }
        });
        owner.accountBook().endOrderBatchMutationScope();
        return owner.failMatching(pending, detail, cause);
    }

    /** 要求 PLACE 有准入副本，必要时绑定实时身份并复制非撤单项执行明细。PLACE 和 AMEND 必须使用当前项的直达事件，账户资金事实仍由事件执行，撤单变化由片段收集入口处理。 */
    void applyOrderBatchMatcherResult(
            PendingOrderBatch batch, OrderBatchItem item, OrderCommand pending,
            OrderMatchingResult matchingResult) {
        if (item.admittedOrder == null && batch.kind == OrderBatchKind.PLACE)
            throw new IllegalStateException("batch place admission receipt is missing");
        if (item.realtimeTakerOrder == null && owner.realtimeCapture() != null
                && owner.realtimeCapture().active()) item.realtimeTakerOrder = item.admittedOrder;

        if (batch.kind != OrderBatchKind.CANCEL) {
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
    void appendOrderBatchResult(OrderBatchItem item,
                                ResponseStatus status, CoreResultCode resultCode) {
        item.status = status;
        item.resultCode = resultCode;
    }

    /** 开始整批收尾并建立账户位图，收整批账户及财务结算结果，必要时等待最终账户盖戳和响应编码。 */
    public CoreResponse finishOrderBatch(PendingOrderBatch batch, OrderCommand pending,
                                          long clusterTimestamp, long clusterPosition) {
        if (!batch.finishing()) {
            owner.activateFactContext(pending.command(), pending.fingerprint());
            batch.finishing(true);
        }
        initializeOrderBatchLaneContext(batch, pending);
        if (batch.settlementEvent != null && !batch.settlementsCollected()) {
            long settlementLaneMask = batch.settlementEvent.requiredLaneMask();
            boolean finalLaneCommit = batch.settlementEvent.commitSequence() != 0;
            if (!owner.accountBook().collectMatcherSettlement(
                    batch.settlementEvent, owner.completedOrderArchive(), null, null)) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.accountBook().releaseMatcherSettlement(batch.settlementEvent);
            batch.settlementsCollected(true);
            if (finalLaneCommit) {
                pending.completeLanes(settlementLaneMask);
                batch.laneCommitCompleted(true);
            }
            owner.commits.publication.request();
        }
        owner.accountBook().pendingReservations().completePendingReservations(batch.sequence);
        if (!batch.laneCommitCompleted() || batch.preparedResponse == null) {
            if (batch.laneCommitEvent == null) {
                owner.accountBook().releaseOwnerLaneAccess();
                batch.laneCommitEvent = owner.accountBook().dispatchBatchCommit(batch.sequence,
                        batch.laneCommitCompleted() ? List.of() : batch.changedUserIds,
                        batch.laneCommitCompleted() ? null : owner.accountBook().metadataOrderUsers(batch.runtimeChangedOrderIds),
                        clusterTimestamp, clusterPosition, pending.command().header().userId(), batch, owner.identities());
            }
            if (batch.laneCommitEvent != null) {
                if (!owner.accountBook().laneCommitComplete(batch.laneCommitEvent)) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                pending.completeLanes(batch.laneCommitEvent.committedLaneMask());
                owner.accountBook().releaseLaneCommit(batch.laneCommitEvent);
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
            owner.accountBook().setMetadata(owner.productLine(),
                    Math.incrementExact(owner.accountBook().revision()));
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
        owner.commandResultLog().storeOwnedResult(pending.command().header().commandIdMsb(),
                pending.command().header().commandIdLsb(),
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, responseData, 0, responseLength);
        if (batch.hasPreparedResponseSlot()) batch.transferPreparedResponseOwnership();
        owner.accountBook().endOrderBatchMutationScope();
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
    void initializeOrderBatchLaneContext(PendingOrderBatch batch, OrderCommand pending) {
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
    int orderBatchMatcherShard(PendingOrderBatch batch) {
        if (batch == null || batch.nextIndex < 0 || batch.nextIndex >= batch.items.size()) {
            throw new IllegalArgumentException("order batch matcher route is unavailable");
        }
        return matcherShardForBatchItem(batch, batch.items.get(batch.nextIndex));
    }

    /** 汇总尚未提交项还会用到的分片，已提交的流水整批直接为空。 */
    long remainingMatcherShardMask(PendingOrderBatch batch) {
        long mask = 0;
        if (batch.pipelined && batch.sequence != 0
                && owner.pendingMatching().get(batch.sequence).isMatchingSubmitted()) return 0;
        int firstUnsubmitted = batch.nextIndex;
        if (batch.itemSettlementEvent != null) {
            firstUnsubmitted = batch.kind == OrderBatchKind.CANCEL
                    ? batch.cancellationChunkEnd : batch.nextIndex + 1;
        }
        for (int index = firstUnsubmitted; index < batch.items.size(); index++) {
            if (batch.kind != OrderBatchKind.PLACE && owner.activeOrderIndex().activeOrderRoute(
                    batch.kind == OrderBatchKind.CANCEL ? batch.items.get(index).orderId()
                            : batch.items.get(index).originalOrderId()) == null) continue;
            mask |= 1L << matcherShardForBatchItem(batch, batch.items.get(index));
        }
        return mask;
    }

    private int matcherShardForBatchItem(PendingOrderBatch batch, OrderBatchItem item) {
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
    void beginOrderBatchCommitContext(PendingOrderBatch batch, OrderCommand pending) {
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.resultBuilder.beginCommand();
        owner.accountBook().beginOrderBatchMutationScope();
        batch.beginCommit();
        owner.commits.publication.begin();
    }

    /** 独立预备撤单可直接建上下文。其他流水批次要求 Owner 已登记准入且事件成功完成，再收集释放整批准入并请求发布。 */
    public void beginPipelinedOrderBatchCommit(PendingOrderBatch batch, OrderCommand pending) {
        PlaceBatchCheckEvent orderCheck = batch.placeBatchOrderCheckEvent;
        boolean preparedCancel = pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL;
        if (!preparedCancel && (!batch.orderCheckCollected() || orderCheck == null || !orderCheck.complete()
                || orderCheck.rejection() != null)) {
            throw new IllegalStateException("pipelined order batch admission is not ready to commit");
        }
        beginOrderBatchCommitContext(batch, pending);
        if (!preparedCancel) {
            owner.accountBook().collectPlaceBatchOrderCheck(orderCheck);
            owner.accountBook().releasePlaceBatchOrderCheck(orderCheck);
            batch.placeBatchOrderCheckEvent = null;
            owner.commits.publication.request();
        }
    }

    /** 只有前面各批均已登记流水品种且候选全部品种不重叠时返回假。否则按既有依赖规则认为冲突。 */
    boolean conflictsWithEarlierPipelinedBatch(PendingOrderBatch candidate) {
        if (activePipelinedOrderBatches != pendingBatchCount() - 1) return true;
        for (OrderBatchItem item : candidate.items) {
            if (pipelinedBatchBySymbol.containsKey(((PlaceOrderCommand) item.command).instrumentId())) return true;
        }
        return false;
    }

    private void captureCommittedBatchTrades(PendingOrderBatch batch) {
        if (owner.realtimeCapture() == null) return;
        for (int itemIndex = 0; itemIndex < batch.items.size(); itemIndex++) {
            OrderBatchItem item = batch.items.get(itemIndex);
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
    PendingOrderBatch decodeOrderBatch(CoreMessage message, DecodedOrderCommand decodedCommand,
                                               long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = decodedCommand.placeOrderBatch();
            List<PlaceOrderCommand> orders = command.orders();
            int count = orders.size();
            PendingOrderBatch batch = acquireOrderBatchPending(OrderBatchKind.PLACE, count,
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
            PendingOrderBatch batch = acquireOrderBatchPending(OrderBatchKind.CANCEL, count,
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
            PendingOrderBatch batch = acquireOrderBatchPending(OrderBatchKind.AMEND, count,
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
    PendingOrderBatch acquireOrderBatchPending(
            OrderBatchKind kind, int itemCount, long clusterTimestamp, long clusterPosition) {
        PendingOrderBatch selected = null;
        Iterator<PendingOrderBatch> iterator = orderBatchPendingPool.iterator();
        while (iterator.hasNext()) {
            PendingOrderBatch candidate = iterator.next();
            if (candidate.capacity() < itemCount) continue;
            iterator.remove();
            selected = candidate;
            break;
        }
        if (selected == null) selected = new PendingOrderBatch(itemCount, owner.responseSlotPool());
        return selected.initialize(kind, clusterTimestamp, clusterPosition);
    }

    /** 空批次直接返回。否则解除品种登记及改单事件，清理对象后在池未满时归池。 */
    void releaseOrderBatchPending(PendingOrderBatch batch) {
        if (batch == null) return;
        unregisterPipelinedBatchSymbols(batch);
        releaseBatchAmendOrderCheck(batch);
        batch.clear();
        if (orderBatchPendingPool.size() < owner.configuration().maxPendingMatching()) orderBatchPendingPool.addFirst(batch);
    }

    /** PLACE 留到实际准入检查。撤改单只用 Owner 稳定路由核对已有原单归属，缺失订单留到各项准入判定，不跨线程读账户订单表。 */
    void validateOrderBatchIdentity(PendingOrderBatch batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
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
        if (owner.accountBook().revision() != runtimeRevisionBefore) {
            owner.accountBook().endOrderBatchMutationScope();
            throw owner.failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    /** 要求当前项已有直达事件，保存原先撤路由，再按下单准入副本或改单解析准入准备槽内固定 Matcher 任务。 */
    java.util.function.Supplier<?> prepareOrderBatchMatchingCommand(
            OrderCommand pending, PendingOrderBatch batch, OrderBatchItem item, int shard,
            com.surprising.aeron.service.settlement.TradeSettlement direct) {
        java.util.Objects.requireNonNull(direct, "batch item requires its direct settlement");
        pending.matcherPreCancellations(owner.matchingFlow.preMatchingCancellationOrders(pending));
        long userId = pending.command().header().userId();
        return switch (batch.kind) {
            case PLACE -> pending.prepareAdmittedPlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(item.admittedOrder, "batch admission receipt"), direct);
            case AMEND -> {
                var orderCheck = java.util.Objects.requireNonNull(batch.replacementOrderCheck, "batch amend admission");
                yield pending.prepareReplaceMatching(owner, shard, orderCheck.resolved().orderId(), userId,
                        orderCheck.originalOrderId(), orderCheck.command().instrumentId(), orderCheck.resolved(), direct);
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        };
    }

    /** 整批流水下单的可复用 Matcher 任务，Owner 绑定输入，Matcher 顺序执行，交接结束后由批次清理。 */
    static final class PipelinedPlaceBatchSubmission implements java.util.function.Supplier<Object> {
        private OrderBatchProcessor executor;
        private OrderCommand pending;
        private PendingOrderBatch batch;
        private long userId;
        private int shard;

        /** 把已固定的执行器、槽、批次、用户和分片放进本批复用任务，供 Matcher 之后调用。 */
        void prepare(OrderBatchProcessor executor, OrderCommand pending, PendingOrderBatch batch, long userId, int shard) {
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
        private PendingOrderBatch batch;
        private int shard;
        private int start;
        private int chunkEnd;
        /** 撤单片段预先准备并持有发布租约的直达结算事件。 */
        private com.surprising.aeron.service.settlement.TradeSettlement direct;

        /** 保存本次同分片撤单片段的起止游标、命令槽及直达事件，供 Matcher 顺序执行。 */
        void prepare(OrderBatchProcessor executor, OrderCommand pending, PendingOrderBatch batch, int shard,
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
                OrderBatchItem item = batch.items.get(index);
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
