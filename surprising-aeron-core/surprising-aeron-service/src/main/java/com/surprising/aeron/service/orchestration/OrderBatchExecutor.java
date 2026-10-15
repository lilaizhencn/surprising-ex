package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;


import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.matching.CoreCancellationResult;

/** 批量订单执行阶段：准备、撮合结果应用、Lane 派发和有序终态提交。 */
final class OrderBatchExecutor {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    OrderBatchExecutor(TradingCoreRuntime owner) { this.owner = owner; }

    /** Batch ordering only; sequence lookup and lifetime belong to the existing pending command ring. */
    private OrderBatchPending firstBatch, lastBatch;
    private int batchCount;

    boolean hasPendingBatches() { return firstBatch != null; }
    OrderBatchPending firstBatch() { return firstBatch; }
    int pendingBatchCount() { return batchCount; }
    OrderBatchPending batch(long sequence) {
        CommandSlot pending = owner.pendingMatching.get(sequence);
        return pending == null ? null : pending.orderBatch;
    }

    void registerBatch(CommandSlot pending, OrderBatchPending batch) {
        if (pending.orderBatch != null || pending.sequence() != batch.sequence
                || owner.pendingMatching.get(batch.sequence) != pending
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

    void clearPendingBatches() {
        while (firstBatch != null)
            unregisterBatch(owner.pendingMatching.get(firstBatch.sequence), firstBatch);
    }

    /** 币对对应的在途批量准入，用于保留同币对依赖。 */
    final HashMap<String, OrderBatchPending> pipelinedBatchBySymbol = new HashMap<>();

    /** 已登记且尚未退出的流水批次数。 */
    int activePipelinedOrderBatches;

    /** 终态批量上下文复用池；不得回收仍被 matcher 或 Lane 使用的对象。 */
    final java.util.ArrayDeque<OrderBatchPending> orderBatchPendingPool = new java.util.ArrayDeque<>();

    CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, TradingCoreRuntime.SourceKey sourceKey,
                                                  CommandFingerprint fingerprint) {
        OrderBatchPending batch = null;
        DecodedMatchingCommand decodedCommand;
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
        long sequence = Math.incrementExact(owner.appliedCommandCount);
        CommandSlot pending = owner.admissions.newPendingMatching(sequence, batch.operation, message, fingerprint, decodedCommand);
        batch.sequence = sequence;
        owner.matchingFlow.putPendingMatching(pending);
        owner.admissions.registerPendingLifecycle(pending);
        registerBatch(pending, batch);
        owner.pendingMatching.registerSubmission(sequence, orderBatchMatcherShard(batch));
        owner.appliedCommandCount = sequence;
        owner.matchingFlow.refreshCommittedCoreSequence();
        owner.recordSourceSequence(sourceKey, message.header().sourceSequence());
        CoreResponse completed = tryActivatePipelinedOrderBatch(batch, pending)
                ? null
                : !pending.clusterIndependent && owner.pendingMatching.firstSequence() == sequence
                ? activateOrderBatch(batch, pending, false) : null;
        if (completed != null) return completed;
        owner.runtimeState.releaseOwnerLaneAccess();
        return message.matchingPendingResponse(TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    CoreResponse activateOrderBatch(OrderBatchPending batch, CommandSlot pending,
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
            owner.runtimeState.releaseOwnerLaneAccess();
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp,
                batch.clusterPosition, deferCompletion);
        if (response == null) owner.clearFactContext();
        return response;
    }

    boolean tryActivatePipelinedOrderBatch(OrderBatchPending batch, CommandSlot pending) {
        if (pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL && !batch.sequentialAdmission) {
            batch.activated(true);
            owner.matchingFlow.submitMatching(pending);
            return true;
        }
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE
                || owner.pendingMatching.hasEarlierUser(batch.sequence, pending.command().header().userId())
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

    void registerPipelinedBatchSymbols(OrderBatchPending batch, CommandSlot pending) {
        if (batch.pipelineRegistered()) return;
        for (String instrumentId : batch.preparedSymbols) {
            // Retain the newest batch: ordered completion removes all predecessors first.
            // The cluster window has already checked exact account and matching dependencies.
            OrderBatchPending previous = pipelinedBatchBySymbol.put(instrumentId, batch);
            if (previous != null && previous != batch && !pending.clusterIndependent) {
                throw new IllegalStateException("pipelined instrumentId ownership conflict");
            }
        }
        batch.pipelineRegistered(true);
        activePipelinedOrderBatches++;
    }

    void unregisterPipelinedBatchSymbols(OrderBatchPending batch) {
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

    boolean preparePipelinedPlaceBatch(OrderBatchPending batch, CommandSlot pending) {
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE) {
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
                        ? owner.matchingAdapter.matcherShardId(command.instrumentId())
                        : batch.decodedCommand.matcherShard(owner.matchingAdapter, command.instrumentId());
                if (batchMatcherShard == -1) batchMatcherShard = itemMatcherShard;
                else if (batchMatcherShard != itemMatcherShard) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                if (command.reduceOnly()) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                owner.requireOrderIdentityAvailable(userId, command);
                var decision = prepareBatchDecision(batch, userId, command.instrumentId());
                if (decision.context().instrument().usesOpenInterestForUserLimit())
                    throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                batch.preparedDecisions[index] = decision;
            }
            batch.pipelined = true;
            return true;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | TradingCoreRuntime.PipelinedBatchNotApplicable exception) {
            batch.rollbackPreparedClientKeys(owner.identities);
            batch.currentPreMatchingCancellationOrderIds = List.of();
            batch.clearPreparedDecisionCache();
            return false;
        }
    }

    void dispatchPipelinedPlaceBatchAdmission(
            CommandSlot pending, OrderBatchPending batch) {
        int shard = batch.decodedCommand == null
                ? owner.matchingAdapter.matcherShardId(batch.preparedSymbols.getFirst())
                : batch.decodedCommand.matcherShard(owner.matchingAdapter, batch.preparedSymbols.getFirst());
        batch.placeBatchAdmissionEvent = owner.runtimeState.dispatchPlaceBatchAdmission(
                pending.sequence(), pending.command().header().userId(), pending.command().header().commandId(),
                batch.preparedOrders, batch.preparedOpenInterestSteps,
                batch.preparedLifecycleSettled, batch.preparedFundingInProgress,
                batch.preparedClientKeyValues, batch.preparedSymbolIds, batch.preparedAssetIds,
                batch.preparedAdmittedOrders,
                batch.items.size(), shard, owner.identities, batch,
                batch.clusterTimestamp, batch.clusterPosition);
    }

    void submitPipelinedPlaceBatch(CommandSlot pending, OrderBatchPending batch) {
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        long userId = pending.command().header().userId();
        int shard = owner.matchingAdapter.matcherShardId(batch.preparedSymbols.getFirst());
        batch.settlementEvent = owner.directMatcherSettlements.preparePipelinedPlaceBatch(pending, batch, shard);
        owner.matcherPipeline.submit(shard, pending.sequence(), () -> {
            owner.matchingAdapter.prepareOrderRoutes(userId, batch.preparedSymbols);
            return submitPreparedPipelinedPlaceBatch(pending, batch, userId, shard);
        }, batch.settlementEvent);
        owner.pendingMatching.progressChanged();
        batch.markPredispatched();
        pending.countPipelinedSettlement();
        owner.commits.dispatchedSettlementInFlight++;
        owner.commits.dispatchedSettlementHighWaterMark = Math.max(
                owner.commits.dispatchedSettlementHighWaterMark,
                owner.commits.dispatchedSettlementInFlight);
    }

    Object submitPreparedPipelinedPlaceBatch(
            CommandSlot pending, OrderBatchPending batch, long userId, int shard) {
        try {
            exchange.core2.core.common.MatcherResult firstNative = null;
            java.util.Objects.requireNonNull(batch.settlementEvent, "direct batch settlement");
            for (int index = 0; index < batch.items.size(); index++) {
                OrderBatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                ResolvedPlaceOrder matchingOrder = batch.preparedOrders[index];
                var result = owner.matchingAdapter.placeDirectBatchItem(
                        shard, index, pending.sequence(), pending.command().header().commandId(),
                        pending.command().header().submittedAtEpochMillis(), userId, matchingOrder,
                        batch.settlementEvent);
                if (firstNative == null) firstNative = result;
                item.captureAdmittedOrder(batch.preparedAdmittedOrders[index]);
                item.realtimeTakerOrder = item.admittedOrder;
                item.captureExecutions(result.events());
                item.executionTakerUserId = userId;
                for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
                    if (item.executionEvents.get(eventIndex).eventType() == MatcherEventType.TRADE) {
                        item.executionCount++;
                        batch.tradeCount = Math.incrementExact(batch.tradeCount);
                    }
                }
                boolean accepted = result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.SUCCESS
                        || result.resultCode() == exchange.core2.core.common.cmd.CommandResultCode.ACCEPTED;
                item.status = accepted ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
                item.resultCode = accepted ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
                batch.collectChangedOrderIds(item, userId, result);
            }
            batch.lastMatchingResult = batch.settlementEvent;
            return firstNative;
        } catch (RuntimeException failure) {
            batch.pipelinedMatchingFailure = failure;
            throw failure;
        }
    }

    CoreResponse startOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                             long clusterTimestamp, long clusterPosition,
                                             boolean deferCompletion) {
        batch.sequentialAdmission = true;
        while (batch.nextIndex < batch.items.size()) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            boolean admissionPending = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
            long runtimeRevisionBefore = !admissionPending
                    ? owner.runtimeState.revision() : batch.itemAdmissionRevision;
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
                    collectBatchPlaceReservation(batch, pending.command().header().userId());
                    batch.itemAdmission = null;
                }
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                owner.pendingMatching.put(pending);
                owner.matchingFlow.submitMatching(pending);
                // 撮合跨回调完成；变更上下文归本序号持有，不能占用下一条命令的 owner。
                owner.suspendMatchingCommitContext(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                // A reservation failure is reported by the owning Lane after it has validated
                // and rolled back the item.  Other Lanes may have committed meanwhile, so the
                // global runtime revision is not a valid unchanged-state check here.
                boolean laneAdmissionFailure = batch.itemAdmission != null || batch.replacementAdmissionEvent != null;
                releaseBatchAmendAdmission(batch);
                batch.itemAdmission = null;
                if (!laneAdmissionFailure)
                    requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()));
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
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
            // Background activation has no response consumer. Even a batch with no native
            // command must remain pending until the ordered completion pump publishes it.
            batch.matchingApplied(true);
            initializeOrderBatchLaneContext(batch, pending);
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    /** Read-only configuration is shared only inside this batch; account/OI decisions remain per item. */
    com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision prepareBatchDecision(
            OrderBatchPending batch, long userId, String instrumentId) {
        int index = batch.preparedSymbols.indexOf(instrumentId);
        if (index >= 0) return batch.preparedContextDecisions[index];
        var context = CoreOrderDecisionResolver.context(owner.runtimeState, owner.identities,
                userId, instrumentId, batch.clusterTimestamp);
        var instrument = context.instrument();
        var decision = new com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision(context, 0,
                owner.identities.assetId(instrument.baseAsset()), owner.identities.assetId(instrument.quoteAsset()),
                owner.identities.assetId(instrument.settleAsset()));
        index = batch.preparedSymbols.size();
        batch.preparedSymbols.add(instrumentId);
        batch.preparedContextDecisions[index] = decision;
        return decision;
    }

    private java.util.function.BooleanSupplier beginBatchReservation(OrderBatchPending batch, long userId,
                                                                    PlaceOrderCommand command, UUID commandId) {
        var decision = prepareBatchDecision(batch, userId, command.instrumentId());
        var work = batch.placeReservation;
        work.prepare(owner.runtimeState, owner.identities, batch.sequence, userId, commandId,
                command, decision, batch, batch.nextIndex);
        return work;
    }

    private void collectBatchPlaceReservation(OrderBatchPending batch, long userId) {
        var work = batch.placeReservation;
        long key = work.preparedClientKey();
        if (com.surprising.aeron.service.state.RuntimeIdentityRegistry.newClientIdentity(key))
            owner.identities.recordLaneClientAllocations(1);
        OrderRuntime admitted = work.admittedOrder();
        owner.runtimeState.collectControlReservation(admitted, batch.sequence);
        // The Account Lane already froze this batch-owned item lease. Owner only binds its reference.
        batch.items.get(batch.nextIndex).admittedOrder = admitted;
        batch.currentPreMatchingCancellationOrderIds = work.closeCapacityCancellations();
        batch.retainPreparedClientKey(userId, work.clientOrderId(),
                com.surprising.aeron.service.state.RuntimeIdentityRegistry.clientKeyValue(key));
        owner.commits.requestCommitPublication();
    }

    void prepareOrderBatchItem(OrderBatchPending batch, OrderBatchItem item, long userId,
                                       UUID commandId) {
        long previousTimestamp = owner.currentClusterTimestamp;
        long previousPosition = owner.currentClusterPosition;
        owner.currentClusterTimestamp = batch.clusterTimestamp;
        owner.currentClusterPosition = batch.clusterPosition;
        try {
            switch (batch.kind) {
                case PLACE -> {
                    PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                    owner.requireOrderIdentityAvailable(userId, command);
                    if (owner.activeOrderIndex.activeOrderRoute(command.orderId()) != null)
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "order already exists");
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    batch.itemAdmission = beginBatchReservation(batch, userId, command, commandId);
                }
                case CANCEL -> {
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    var route = owner.activeOrderIndex.activeOrderRoute(command.orderId());
                    var order = route;
                    if (order == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "active order does not exist");
                    if (order.userId() != userId) {
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    }
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    var route = owner.activeOrderIndex.activeOrderRoute(command.originalOrderId());
                    if (route != null && route.userId() != userId)
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    String instrumentId = route == null
                            ? previousBatchOrderInstrument(batch, command.originalOrderId()) : route.instrumentId();
                    if (instrumentId == null)
                        throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                    if (command.replacementOrderId() == command.originalOrderId())
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
                    var decision = prepareBatchDecision(batch, userId, instrumentId).context();
                    batch.replacementAdmissionEvent = owner.runtimeState.dispatchReplacementAdmission(
                            batch.sequence, userId, command.originalOrderId(), command, null, decision,
                            owner.matchingAdapter.matcherShardId(instrumentId), owner.identities);
                }
            }
        } finally {
            owner.currentClusterTimestamp = previousTimestamp;
            owner.currentClusterPosition = previousPosition;
        }
    }

    private String previousBatchOrderInstrument(OrderBatchPending batch, long orderId) {
        for (int index = 0; index < batch.nextIndex; index++) {
            OrderBatchItem previous = batch.items.get(index);
            if (previous.orderId() == orderId && previous.status == ResponseStatus.APPLIED
                    && previous.admittedOrder != null)
                return previous.admittedOrder.instrument().instrumentId();
        }
        return null;
    }

    private boolean collectBatchAmendAdmission(OrderBatchPending batch, long userId) {
        var event = batch.replacementAdmissionEvent;
        if (!event.complete()) { owner.runtimeState.assertAccountLanesHealthy(); return false; }
        if (event.rejection() != null) throw event.rejection();
        var admission = event.admission();
        // Lane admission sees only its own account state. Order IDs are globally unique;
        // the Owner's existing route index catches an active ID owned by another Lane.
        if (owner.activeOrderIndex.activeOrderRoute(admission.command().orderId()) != null)
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
        owner.requireOrderIdentityAvailable(userId, admission.command());
        batch.replacementAdmission = admission;
        batch.currentPreMatchingCancellationOrderIds = event.closeCapacityCancellations();
        return true;
    }

    private void releaseBatchAmendAdmission(OrderBatchPending batch) {
        if (batch.replacementAdmissionEvent == null) return;
        owner.runtimeState.releaseReplacementAdmission(batch.replacementAdmissionEvent);
        batch.replacementAdmissionEvent = null;
        batch.replacementAdmission = null;
    }

    void submitOrderBatchMatching(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        if (batch == null) return;
        batch.lastMatchingResult = null;
        if (batch.kind == OrderBatchKind.CANCEL) {
            submitCancelBatchChunk(pending, batch);
            if (pending.clusterIndependent && batch.activated() && !pending.isMatchingSubmitted())
                pending.matchingSubmitted();
            return;
        }
        int shard = orderBatchMatcherShard(batch);
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = null;
        // Sequential batches cannot prepare one aggregate settlement up front because each
        // item depends on the previous item's Lane admission.  The current item is admitted
        // before this method is called, so reserve its pooled event now and let Matcher publish
        // the fact directly into Lane.  The Owner still owns only the ordered terminal fence;
        // it no longer constructs a settlement event after the Matcher result arrives.
        if (batch.kind == OrderBatchKind.PLACE) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            OrderRuntime admitted = item.admittedOrder;
            if (admitted == null) throw new IllegalStateException("batch item admission order is missing");
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.directMatcherSettlements.prepareBatchPlaceItem(pending, batch, admitted, shard);
            batch.itemSettlementEvent = direct;
        }
        if (batch.kind == OrderBatchKind.AMEND) {
            pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
            direct = owner.directMatcherSettlements.prepareBatchAmend(pending,
                    java.util.Objects.requireNonNull(batch.replacementAdmission), shard);
            batch.itemSettlementEvent = direct;
            batch.items.get(batch.nextIndex).captureAdmittedOrder(direct.admittedOrder());
            if (owner.realtimeCapture != null) batch.items.get(batch.nextIndex).realtimeTakerOrder = batch.items.get(batch.nextIndex).admittedOrder;
        }
        owner.matcherPipeline.submit(shard, pending.sequence(),
                prepareOrderBatchMatchingCommand(
                        pending, batch, batch.items.get(batch.nextIndex), shard, direct), direct);
    }

    void submitCancelBatchChunk(CommandSlot pending, OrderBatchPending batch) {
        batch.lastMatchingResult = null;
        int shard = orderBatchMatcherShard(batch);
        int start = batch.nextIndex;
        int end = start;
        // Only independent cancellations on the same matcher are coalesced. Missing orders
        // stay on the owner rejection path; cross-shard chunks keep original item order.
        while (end < batch.items.size()) {
            OrderBatchItem item = batch.items.get(end);
            // The ingress route already validated this exact order ID. Reuse the
            // route holder's runtime value instead of probing the active-order map
            // a second time while building the fixed same-shard chunk.
            var route = owner.activeOrderIndex.activeOrderRoute(item.orderId);
            if (route == null) break;
            String instrumentId = route.instrumentId();
            if (owner.matchingAdapter.matcherShardId(instrumentId) != shard) break;
            batch.preparedCancellationOrderIds[end - start] = item.orderId;
            item.cancelSymbol = instrumentId;
            end++;
        }
        if (end == start) {
            // The ingress route can become stale while earlier matching commands finish.
            // Retry this batch at the ordered head, where prepareOrderBatchItem can reject
            // the missing order without mutating funds or submitting a matcher command.
            batch.sequentialAdmission = true;
            batch.activated(false);
            return;
        }
        batch.cancellationChunkStart = start;
        batch.cancellationChunkEnd = end;
        int chunkEnd = end;
        // Missing suffix items cannot change funds or orders. The last actual cancellation
        // also publishes the submitting Lane's final sequence; no second Lane task is needed.
        boolean finalChunk = true;
        for (int index = end; index < batch.items.size(); index++) {
            if (owner.activeOrderIndex.activeOrderRoute(batch.items.get(index).orderId) != null) {
                finalChunk = false;
                break;
            }
        }
        pending.establishCommitFence(batch.clusterTimestamp, batch.clusterPosition);
        var direct = owner.directMatcherSettlements.prepareCancelBatch(
                pending, batch, shard, finalChunk, end - start);
        batch.itemSettlementEvent = direct;
        owner.matcherPipeline.submit(shard, pending.sequence(), () -> {
            exchange.core2.core.common.MatcherResult firstNative = null;
            for (int index = start; index < chunkEnd; index++) {
                OrderBatchItem item = batch.items.get(index);
                var result = owner.matchingAdapter.cancelDirectBatchItem(
                        shard, index - start, pending.sequence(), pending.command().header().commandId(), item.orderId,
                        pending.command().header().submittedAtEpochMillis(),
                        pending.command().header().userId(), item.cancelSymbol, direct);
                if (firstNative == null) firstNative = result;
            }
            batch.lastMatchingResult = direct;
            return firstNative;
        }, direct);
    }

    CoreResponse completeOrderBatchMatching(long sequence,
                                                    MatchingResult matchingResult,
                                                    long clusterTimestamp, long clusterPosition) {
        CommandSlot pending = owner.pendingMatching.get(sequence);
        OrderBatchPending batch = batch(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = batch(sequence);
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
            if (matchingResult instanceof com.surprising.aeron.service.state.MatcherSettlementEvent event
                    && event == batch.itemSettlementEvent && event.hasDirectNativeResult()) {
                if (!event.complete()) throw new IllegalStateException("cancel chunk Lane is incomplete");
                batch.mergeTreasuryDelta(owner.runtimeState.collectOrderBatchMatcherSettlement(
                        event, owner.runtimeState.coldCommandFunds(), owner.terminalRetention));
                owner.commits.requestCommitPublication();
                if (event.commitSequence() != 0) batch.laneCommitCompleted(true);
                owner.runtimeState.releaseMatcherSettlement(event);
                batch.itemSettlementEvent = null;
                batch.lastMatchingResult = null;
                while (batch.nextIndex < batch.cancellationChunkEnd) {
                    OrderBatchItem item = batch.items.get(batch.nextIndex);
                    batch.changedUserIds.add(pending.command().header().userId());
                    batch.itemChangedOrderIds.clear();
                    batch.itemChangedOrderIds.add(item.orderId());
                    if (item.status == ResponseStatus.APPLIED)
                        batch.runtimeChangedOrderIds.add(item.orderId());
                    batch.changedOrderIds.add(item.orderId());
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
                // The direct event may have completed while the Owner was away from the
                // commit callback. Collect it in this same ordered turn; suspending an already
                // restored context would lose the commit publication fence.
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

    CoreResponse completeOrderBatchItemSettlement(OrderBatchPending batch, CommandSlot pending,
                                                 long timestamp, long position) {
        try {
            var settledEvent = batch.itemSettlementEvent;
            batch.mergeTreasuryDelta(owner.runtimeState.collectOrderBatchMatcherSettlement(
                    settledEvent, owner.runtimeState.coldCommandFunds(), owner.terminalRetention));
            if (batch.kind == OrderBatchKind.AMEND && batch.lastMatchingResult.accepted()) {
                // Preserve the existing reservation revision; SPOT also counted its original cancellation.
                owner.runtimeState.setMetadata(owner.productLine, Math.addExact(owner.runtimeState.revision(),
                        owner.productLine == ProductLine.SPOT ? 2 : 1));
            }
            if (batch.kind != OrderBatchKind.CANCEL) finishOrderBatchItem(batch, pending, batch.lastMatchingResult);
            owner.runtimeState.releaseMatcherSettlement(settledEvent);
            batch.itemSettlementEvent = null;
            batch.lastMatchingResult = null;
            return startOrderBatchItem(batch, pending, timestamp, position, false);
        } catch (RuntimeException failure) {
            throw failOrderBatch(batch, pending, "batch item Lane settlement failed", failure);
        }
    }

    void applyCompletedOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                      MatchingResult matchingResult) {
        batch.lastMatchingResult = matchingResult;
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        try {
            applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (batch.itemSettlementEvent != null || batch.itemAdmission != null) return;
            finishOrderBatchItem(batch, pending, matchingResult);
        } catch (RuntimeException exception) {
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    private void finishOrderBatchItem(OrderBatchPending batch, CommandSlot pending,
                                      MatchingResult matchingResult) {
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
            if (batch.kind != OrderBatchKind.CANCEL) appendOrderBatchResult(batch, item, status, resultCode);
            releaseBatchAmendAdmission(batch);
            item.cancelSymbol = null;
            batch.nextIndex++;
        } catch (RuntimeException exception) {
            // The matcher already applied this item. Operational settlement failures also require
            // snapshot/log recovery; never turn them into an item rejection or retry this mutation.
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    void rollbackOrderBatchMutations(OrderBatchPending batch, boolean endScope) {
        owner.commits.abortCommitPublicationBatch();
        owner.runtimeState.rollbackActiveCommand(batch.runtimeCheckpoint, batch.sequence);
        batch.rollbackPreparedClientKeys(owner.identities);
        owner.identities.rollbackPositionKeys(batch.positionIdentityCheckpoint);
        if (endScope) owner.runtimeState.endOrderBatchMutationScope();
    }

    FatalMatchingDivergenceException failOrderBatch(
            OrderBatchPending batch, CommandSlot pending, String detail, Throwable cause) {
        // Exchange-core facts are irreversible here. Preserve their observed sequence and accepted
        // cancellation prefix for replay evidence,
        // stop every continuation, and roll back only the unpublished Product Core runtime command.
        owner.matchingAdapter.poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        owner.admissions.queuedMatching.clear();
        owner.pendingMatching.forEach(value -> {
            if (value.claimed) {
                value.resetMatchingContinuation();
            }
        });
        owner.runtimeState.endOrderBatchMutationScope();
        return owner.failMatching(pending, detail, cause);
    }

    void applyOrderBatchMatcherResult(
            OrderBatchPending batch, OrderBatchItem item, CommandSlot pending,
            MatchingResult matchingResult) {
        if (item.admittedOrder == null && batch.kind == OrderBatchKind.PLACE && batch.pipelined) {
            item.captureAdmittedOrder(batch.preparedAdmittedOrders[batch.nextIndex]);
        }
        if (item.admittedOrder == null && batch.kind == OrderBatchKind.PLACE)
            throw new IllegalStateException("batch place admission receipt is missing");
        if (item.realtimeTakerOrder == null && owner.realtimeCapture != null
                && owner.realtimeCapture.active()) item.realtimeTakerOrder = item.admittedOrder;

        if (batch.kind != OrderBatchKind.CANCEL) {
            item.captureExecutions(matchingResult.matcherEvents());
            item.executionTakerUserId = pending.command().header().userId();
            for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
                MatcherEvent event = item.executionEvents.get(eventIndex);
                if (event.eventType() == MatcherEventType.TRADE) {
                    item.executionCount++;
                    batch.tradeCount = Math.incrementExact(batch.tradeCount);
                }
            }
        }
        switch (batch.kind) {
            case PLACE -> {
                if (batch.itemSettlementEvent != matchingResult || !batch.itemSettlementEvent.direct())
                    throw new IllegalStateException("batch place requires its direct Lane event");
                batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
                return;
            }
            case CANCEL -> { return; } // Chunk Lane changes were collected before its item results.

            case AMEND -> {
                if (batch.itemSettlementEvent != matchingResult || !batch.itemSettlementEvent.direct())
                    throw new IllegalStateException("batch amendment requires a direct Lane event");
                batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
                return;
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
    }

    void appendOrderBatchResult(OrderBatchPending batch, OrderBatchItem item,
                                        ResponseStatus status, CoreResultCode resultCode) {
        item.status = status;
        item.resultCode = resultCode;
    }

    CoreResponse finishOrderBatch(OrderBatchPending batch, CommandSlot pending,
                                          long clusterTimestamp, long clusterPosition) {
        if (!batch.finishing()) {
            owner.activateFactContext(pending.command(), pending.fingerprint());
            batch.finishing(true);
        }
        initializeOrderBatchLaneContext(batch, pending);
        if (batch.settlementEvent != null && !batch.settlementsCollected()) {
            long settlementLaneMask = batch.settlementEvent.requiredLaneMask();
            boolean finalLaneCommit = batch.settlementEvent.commitSequence() != 0;
            RuntimeTreasuryDelta delta = owner.runtimeState.collectMatcherSettlement(
                    batch.settlementEvent, owner.runtimeState.coldCommandFunds(), owner.terminalRetention);
            if (delta == null) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            batch.mergeTreasuryDelta(delta);
            owner.runtimeState.releaseMatcherSettlement(batch.settlementEvent);
            batch.settlementsCollected(true);
            if (finalLaneCommit) {
                pending.completeLanes(settlementLaneMask);
                batch.laneCommitCompleted(true);
            }
            owner.commits.requestCommitPublication();
        }
        owner.runtimeState.completePendingReservations(batch.sequence);
        if (!batch.laneCommitCompleted() || batch.preparedResponse == null) {
            if (batch.laneCommitEvent == null) {
                owner.runtimeState.releaseOwnerLaneAccess();
                batch.laneCommitEvent = owner.runtimeState.dispatchBatchCommit(batch.sequence,
                        batch.laneCommitCompleted() ? List.of() : batch.changedUserIds,
                        batch.laneCommitCompleted() ? List.of() : batch.runtimeChangedOrderIds,
                        clusterTimestamp, clusterPosition, pending.command().header().userId(), batch, owner.identities);
            }
            if (batch.laneCommitEvent != null) {
                if (!owner.runtimeState.laneCommitComplete(batch.laneCommitEvent)) {
                    owner.suspendMatchingCommitContext(pending);
                    return null;
                }
                pending.completeLanes(batch.laneCommitEvent.committedLaneMask());
                owner.runtimeState.releaseLaneCommit(batch.laneCommitEvent);
                batch.laneCommitEvent = null;
                owner.commits.requestCommitPublication();
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
            owner.runtimeState.applyCollectedTreasuryBeforeActivation(batch.treasuryDelta);
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.incrementExact(owner.runtimeState.revision()));
            if (pending.completedLaneMask() != pending.expectedLaneMask()) {
                throw new IllegalStateException("order batch account lane mask mismatch");
            }
            owner.commits.requireCompleteAccountLanes(pending);
        } catch (RuntimeException validationFailure) {
            throw failOrderBatch(batch, pending, "order batch final validation failed", validationFailure);
        }
        captureCommittedBatchTrades(batch);
        owner.commits.completeCommitPublicationBatch();
        owner.commits.closeFinancialDispatch(pending);
        if (batch.preparedResponse == null)
            throw failOrderBatch(batch, pending, "final account Lane did not prepare the batch response", null);
        byte[] responseData = batch.preparedResponse;
        int responseLength = batch.preparedResponseLength;
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, batch.tradeCount);
        owner.validateFundsConservation(pending.command());
        owner.matchingFlow.commitMatchingSequence(batch.sequence);
        owner.resultLedger.storeOwnedResult(pending.command().header().commandId(),
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, responseData, 0, responseLength);
        if (batch.hasPreparedResponseSlot()) batch.transferPreparedResponseOwnership();
        owner.runtimeState.endOrderBatchMutationScope();
        if (pending.takePipelinedSettlementCounted()) {
            owner.commits.dispatchedSettlementInFlight--;
            if (owner.commits.dispatchedSettlementInFlight < 0) {
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

    void initializeOrderBatchLaneContext(OrderBatchPending batch, CommandSlot pending) {
        if (batch.laneContextInitialized()) return;
        long expectedLaneMask = 0;
        for (int index = 0; index < batch.changedUserIds.size(); index++) {
            expectedLaneMask |= owner.matchingAdapter.topology().accountLaneMask(
                    batch.changedUserIds.valueAt(index));
        }
        batch.actualLaneMask = expectedLaneMask;
        var finalMatchingResult = batch.lastMatchingResult == null
                ? new com.surprising.aeron.service.matching.CoreMatchingResult(true, "NO_NATIVE_COMMAND")
                .withCoreSequenceInPlace(batch.sequence)
                : batch.lastMatchingResult;
        pending.result(
                finalMatchingResult, expectedLaneMask, owner.commits.validAccountLaneMask());
        if (batch.laneCommitCompleted() && expectedLaneMask != 0) pending.completeLanes(expectedLaneMask);
        batch.laneContextInitialized(true);
    }

    int orderBatchMatcherShard(OrderBatchPending batch) {
        if (batch == null || batch.nextIndex < 0 || batch.nextIndex >= batch.items.size()) {
            throw new IllegalArgumentException("order batch matcher route is unavailable");
        }
        return matcherShardForBatchItem(batch, batch.items.get(batch.nextIndex));
    }

    long remainingMatcherShardMask(OrderBatchPending batch) {
        long mask = 0;
        if (batch.pipelined && batch.sequence != 0
                && owner.pendingMatching.get(batch.sequence).isMatchingSubmitted()) return 0;
        int firstUnsubmitted = batch.nextIndex;
        if (batch.itemSettlementEvent != null) {
            firstUnsubmitted = batch.kind == OrderBatchKind.CANCEL
                    ? batch.cancellationChunkEnd : batch.nextIndex + 1;
        }
        for (int index = firstUnsubmitted; index < batch.items.size(); index++) {
            if (batch.kind != OrderBatchKind.PLACE && owner.activeOrderIndex.activeOrderRoute(
                    batch.kind == OrderBatchKind.CANCEL ? batch.items.get(index).orderId()
                            : batch.items.get(index).originalOrderId()) == null) continue;
            mask |= 1L << matcherShardForBatchItem(batch, batch.items.get(index));
        }
        return mask;
    }

    private int matcherShardForBatchItem(OrderBatchPending batch, OrderBatchItem item) {
        String instrumentId = switch (batch.kind) {
            case PLACE -> ((PlaceOrderCommand) item.command).instrumentId();
            case CANCEL -> {
                var route = owner.activeOrderIndex.activeOrderRoute(((CancelOrderCommand) item.command).orderId());
                yield route == null ? "" : route.instrumentId();
            }
            case AMEND -> {
                var route = owner.activeOrderIndex.activeOrderRoute(((AmendOrderCommand) item.command).originalOrderId());
                String previous = previousBatchOrderInstrument(batch, item.originalOrderId());
                yield route == null ? previous == null ? "" : previous : route.instrumentId();
            }
        };
        return instrumentId.isBlank() ? 0 : batch.decodedCommand == null
                ? owner.matchingAdapter.matcherShardId(instrumentId)
                : batch.decodedCommand.matcherShard(owner.matchingAdapter, instrumentId);
    }

    void beginOrderBatchCommitContext(OrderBatchPending batch, CommandSlot pending) {
        owner.activateFactContext(pending.command(), pending.fingerprint());
        owner.resultBuilder.beginCommand();
        // Seed once before any item receipt is collected. Finalization must retain the Ledger
        // receipts accumulated across sequential items and suspended Owner turns.
        owner.setCommandFunds(pending.fundsAccumulator());
        owner.runtimeState.beginOrderBatchMutationScope();
        batch.runtimeCheckpoint = owner.runtimeState.commandRevisionCheckpoint();
        batch.positionIdentityCheckpoint = owner.identities.positionCheckpoint();
        batch.beginCommit();
        owner.commits.beginCommitPublicationBatch();
    }

    void beginPipelinedOrderBatchCommit(OrderBatchPending batch, CommandSlot pending) {
        PlaceBatchAdmissionEvent admission = batch.placeBatchAdmissionEvent;
        boolean preparedCancel = pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL;
        if (!preparedCancel && (!batch.admissionCollected() || admission == null || !admission.complete()
                || admission.rejection() != null)) {
            throw new IllegalStateException("pipelined order batch admission is not ready to commit");
        }
        beginOrderBatchCommitContext(batch, pending);
        if (!preparedCancel) {
            owner.runtimeState.collectPlaceBatchAdmission(admission, owner.runtimeState.coldCommandFunds());
            owner.runtimeState.releasePlaceBatchAdmission(admission);
            batch.placeBatchAdmissionEvent = null;
            owner.commits.requestCommitPublication();
        }
    }

    boolean conflictsWithEarlierPipelinedBatch(OrderBatchPending candidate) {
        if (activePipelinedOrderBatches != pendingBatchCount() - 1) return true;
        for (OrderBatchItem item : candidate.items) {
            if (pipelinedBatchBySymbol.containsKey(((PlaceOrderCommand) item.command).instrumentId())) return true;
        }
        return false;
    }

    /** 每批仅在有序提交点发布成交，不能写入前一条命令的实时捕获范围。 */
    private void captureCommittedBatchTrades(OrderBatchPending batch) {
        if (owner.realtimeCapture == null) return;
        for (int itemIndex = 0; itemIndex < batch.items.size(); itemIndex++) {
            OrderBatchItem item = batch.items.get(itemIndex);
            var taker = item.realtimeTakerOrder;
            item.realtimeTakerOrder = null;
            if (taker == null || owner.realtimeCapture == null || !owner.realtimeCapture.active()) continue;
            try {
                int fill = 0;
                for (int eventIndex = 0; eventIndex < item.executionEvents.size(); eventIndex++) {
                    MatcherEvent event = item.executionEvents.get(eventIndex);
                    if (event.eventType() == MatcherEventType.TRADE)
                        owner.realtimeCapture.trade(taker, batch.sequence, fill++, event.price(), event.size(),
                                event.matchedOrderId(), event.matchedOrderUid());
                }
            } catch (RuntimeException failure) { owner.realtimeCapture.failed(); }
        }
    }

    OrderBatchPending decodeOrderBatch(CoreMessage message, DecodedMatchingCommand decodedCommand,
                                               long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = decodedCommand.placeOrderBatch();
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.PLACE, command.orders().size(),
                    clusterTimestamp, clusterPosition, CommandSlot.Operation.PLACE);
            batch.decodedCommand = decodedCommand;
            for (PlaceOrderCommand value : command.orders()) {
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = decodedCommand.cancelOrderBatch();
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.CANCEL, command.orders().size(),
                    clusterTimestamp, clusterPosition, CommandSlot.Operation.CANCEL);
            batch.decodedCommand = decodedCommand;
            for (CancelOrderCommand value : command.orders()) {
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = decodedCommand.amendOrderBatch();
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.AMEND, command.orders().size(),
                    clusterTimestamp, clusterPosition, CommandSlot.Operation.AMEND);
            batch.decodedCommand = decodedCommand;
            for (AmendOrderCommand value : command.orders()) {
                batch.addItem(value.replacementOrderId(), value.originalOrderId(),
                        value.replacementOrderId(), value);
            }
            return batch;
        }
        throw new IllegalArgumentException("unsupported order batch type");
    }

    OrderBatchPending acquireOrderBatchPending(
            OrderBatchKind kind, int itemCount, long clusterTimestamp, long clusterPosition,
            CommandSlot.Operation operation) {
        OrderBatchPending selected = null;
        Iterator<OrderBatchPending> iterator = orderBatchPendingPool.iterator();
        while (iterator.hasNext()) {
            OrderBatchPending candidate = iterator.next();
            if (candidate.capacity() < itemCount) continue;
            iterator.remove();
            selected = candidate;
            break;
        }
        if (selected == null) selected = new OrderBatchPending(itemCount, owner.responseArena);
        return selected.initialize(kind, clusterTimestamp, clusterPosition, operation);
    }

    void releaseOrderBatchPending(OrderBatchPending batch) {
        if (batch == null) return;
        unregisterPipelinedBatchSymbols(batch);
        releaseBatchAmendAdmission(batch);
        batch.clear();
        if (orderBatchPendingPool.size() < TradingCoreRuntime.MAX_PENDING_MATCHING) orderBatchPendingPool.addFirst(batch);
    }

    void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            // Owner validates the immutable identity in its existing routing index. Account
            // Lane order tables are mutated by their worker and cannot be probed at ingress.
            var route = owner.activeOrderIndex.activeOrderRoute(orderId);
            if (route != null && route.userId() != userId) {
                throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH",
                        "order batch contains another user's order");
            }
        }
    }

    void requireUnchangedRejectedBatchItem(OrderBatchPending batch, CommandSlot pending,
                                                   long runtimeRevisionBefore,
                                                   RuntimeException exception) {
        if (owner.runtimeState.revision() != runtimeRevisionBefore) {
            owner.runtimeState.endOrderBatchMutationScope();
            throw owner.failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    java.util.function.Supplier<?> prepareOrderBatchMatchingCommand(
            CommandSlot pending, OrderBatchPending batch, OrderBatchItem item, int shard,
            com.surprising.aeron.service.state.MatcherSettlementEvent direct) {
        java.util.Objects.requireNonNull(direct, "batch item requires its direct settlement");
        pending.matcherPreCancellations(owner.matcherCommands.preMatchingCancellationOrders(pending));
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
}
