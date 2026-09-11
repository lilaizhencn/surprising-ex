package com.surprising.aeron.service.execution;
import com.surprising.aeron.service.execution.CommandResultLedger.StoredResult;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;



import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.matching.CoreCancellationResult;

/** 批量订单执行阶段：准备、撮合结果应用、Lane 派发和有序终态提交。 */
final class OrderBatchExecutor {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    OrderBatchExecutor(TradingCoreRuntime owner) { this.owner = owner; }

    /** 在途序号到批量上下文的唯一登记表；终态后移除。 */
    LinkedHashMap<Long, OrderBatchPending> pendingOrderBatches;

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
            return owner.recordRejectedMatching(message, sourceKey, fingerprint,
                    exception instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        CoreAdmissionReservation capacityReservation;
        try {
            capacityReservation = owner.reserveAdmission(
                    CoreAdmissionReservation.AdmissionDemand.matching(message, owner.admissions.matchingOrderBound(message, decodedCommand),
                            decodedCommand));
        } catch (CoreStateRejectedException rejection) {
            releaseOrderBatchPending(batch);
            return owner.admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        }
        long sequence = Math.incrementExact(owner.appliedCommandCount);
        PendingMatching pending = owner.admissions.newPendingMatching(sequence, batch.operation, message, fingerprint, decodedCommand)
                .withCapacityReservation(capacityReservation);
        batch.sequence = sequence;
        owner.putPendingMatching(pending);
        owner.admissions.registerPendingLifecycle(pending);
        pendingOrderBatches.put(pending.sequenceKey(), batch);
        owner.pendingMatching.registerSubmission(sequence, orderBatchMatcherShard(batch));
        owner.appliedCommandCount = sequence;
        owner.refreshCommittedCoreSequence();
        owner.recordSourceSequence(sourceKey, message.header().sourceSequence());
        long pendingStateHash = owner.stateHash(owner.cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
        pending.withPendingStateHash(pendingStateHash);
        CoreResponse completed = tryActivatePipelinedOrderBatch(batch, pending)
                ? null
                : !pending.clusterIndependent && owner.pendingMatching.firstSequence() == sequence
                ? activateOrderBatch(batch, pending, false) : null;
        if (completed != null) return completed;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount, 0, pendingStateHash, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    CoreResponse activateOrderBatch(OrderBatchPending batch, PendingMatching pending,
                                             boolean deferCompletion) {
        boolean firstActivation = !batch.commitStarted();
        if (firstActivation) beginOrderBatchCommitContext(batch, pending);
        else if (owner.laneCommandContexts.required(pending.sequence()).hasCommitContext())
            owner.restoreMatchingCommitContext(pending);
        else owner.activateFactContext(owner.sequenceAdmission(pending.sequence()),
                pending.command(), pending.fingerprint());
        if (firstActivation && batch.admissionOrderIndex != null) batch.admissionOrderIndex.reset(pending.command().header().userId());
        batch.started = true;
        if (preparePipelinedPlaceBatch(batch, pending)) {
            registerPipelinedBatchSymbols(batch, pending);
            dispatchPipelinedPlaceBatchAdmission(pending, batch);
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp,
                batch.clusterPosition, deferCompletion);
        if (response == null) owner.clearFactContext();
        return response;
    }

    boolean tryActivatePipelinedOrderBatch(OrderBatchPending batch, PendingMatching pending) {
        if (pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL) {
            if (batch.admissionOrderIndex == null)
                batch.admissionOrderIndex = new BatchAdmissionOrderIndex(owner.activeOrderIndex, owner.identities, batch.items.size());
            batch.admissionOrderIndex.reset(pending.command().header().userId());
            batch.started = true;
            owner.submitMatching(pending);
            return true;
        }
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE || batch.items.size() < 2
                || owner.pendingMatching.hasEarlierUser(batch.sequence, pending.command().header().userId())
                || !pending.clusterIndependent && conflictsWithEarlierPipelinedBatch(batch)) {
            return false;
        }
        if (batch.admissionOrderIndex != null) batch.admissionOrderIndex.reset(pending.command().header().userId());
        batch.started = true;
        if (!preparePipelinedPlaceBatch(batch, pending)) {
            batch.started = false;
            return false;
        }
        registerPipelinedBatchSymbols(batch, pending);
        dispatchPipelinedPlaceBatchAdmission(pending, batch);
        return true;
    }

    void registerPipelinedBatchSymbols(OrderBatchPending batch, PendingMatching pending) {
        if (batch.pipelineRegistered) return;
        for (String symbol : batch.preparedSymbols) {
            // Retain the newest batch: ordered completion removes all predecessors first.
            // The cluster window has already checked exact account and matching dependencies.
            OrderBatchPending previous = pipelinedBatchBySymbol.put(symbol, batch);
            if (previous != null && previous != batch && !pending.clusterIndependent) {
                throw new IllegalStateException("pipelined symbol ownership conflict");
            }
        }
        batch.pipelineRegistered = true;
        activePipelinedOrderBatches++;
    }

    void unregisterPipelinedBatchSymbols(OrderBatchPending batch) {
        if (!batch.pipelineRegistered) return;
        for (String symbol : batch.preparedSymbols) {
            pipelinedBatchBySymbol.remove(symbol, batch);
        }
        batch.pipelineRegistered = false;
        activePipelinedOrderBatches--;
        if (activePipelinedOrderBatches < 0) {
            throw new IllegalStateException("active pipelined order batch count underflow");
        }
    }

    boolean preparePipelinedPlaceBatch(OrderBatchPending batch, PendingMatching pending) {
        if (batch.sequentialAdmission || batch.kind != OrderBatchKind.PLACE || batch.items.size() < 2) {
            return false;
        }
        long userId = pending.command().header().userId();
        try {
            int batchMatcherShard = -1;
            batch.preparedSymbols.clear();
            batch.preparedContexts.clear();
            for (int index = 0; index < batch.items.size(); index++) {
                OrderBatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                int itemMatcherShard = batch.decodedCommand == null
                        ? owner.matchingAdapter.matcherShardId(command.symbol())
                        : batch.decodedCommand.matcherShard(owner.matchingAdapter, command.symbol());
                if (batchMatcherShard == -1) batchMatcherShard = itemMatcherShard;
                else if (batchMatcherShard != itemMatcherShard) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                if (command.reduceOnly()) throw new TradingCoreRuntime.PipelinedBatchNotApplicable();
                owner.requireOrderIdentityAvailable(userId, command);
                var decision = batch.preparedContexts.get(command.symbol());
                if (decision == null) {
                    var context = CoreOrderDecisionResolver.context(owner.runtimeState, owner.identities,
                            userId, command.symbol(), owner.currentClusterTimestamp);
                    var instrument = context.instrument();
                    decision = new com.surprising.aeron.service.state.PlaceBatchIntentSource.Decision(context,
                            batchOpenInterestSteps(batch, command.symbol()),
                            owner.identities.assetId(instrument.baseAsset()), owner.identities.assetId(instrument.quoteAsset()),
                            owner.identities.assetId(instrument.settleAsset()));
                    batch.preparedContexts.put(command.symbol(), decision);
                    batch.preparedSymbols.add(command.symbol());
                }
                batch.preparedDecisions[index] = decision;
            }
            batch.pipelined = true;
            return true;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | TradingCoreRuntime.PipelinedBatchNotApplicable exception) {
            batch.rollbackPreparedClientKeys(owner.identities);
            if (batch.admissionOrderIndex != null) batch.admissionOrderIndex.reset(pending.command().header().userId());
            batch.currentPreMatchingCancellationOrderIds = List.of();
            batch.preparedSymbols.clear();
            batch.preparedContexts.clear();
            return false;
        }
    }

    void dispatchPipelinedPlaceBatchAdmission(
            PendingMatching pending, OrderBatchPending batch) {
        batch.placeBatchAdmissionEvent = owner.runtimeState.dispatchPlaceBatchAdmission(
                pending.sequence(), pending.command().header().userId(), pending.command().header().commandId(),
                batch.preparedOrders, batch.preparedOpenInterestSteps, batch.preparedAdmissionIdentities,
                batch.preparedClientKeyValues, batch.preparedSymbolIds, batch.preparedAssetIds,
                batch.preparedMatchingOrders, batch.preparedAdmittedOrders,
                batch.preparedAdmittedReservations, batch.items.size(), owner.identities, batch);
    }

    void submitPipelinedPlaceBatch(PendingMatching pending, OrderBatchPending batch) {
        long userId = pending.command().header().userId();
        int shard = owner.matchingAdapter.matcherShardId(batch.preparedSymbols.getFirst());
        owner.matcherPipeline.submit(shard, pending.sequence(), () -> {
            owner.matchingAdapter.prepareOrderRoutes(userId, batch.preparedSymbols);
            return submitPreparedPipelinedPlaceBatch(pending, batch, userId, shard);
        });
    }

    com.surprising.aeron.service.matching.CoreMatchingResult
            submitPreparedPipelinedPlaceBatch(
                    PendingMatching pending, OrderBatchPending batch, long userId, int shard) {
        List<com.surprising.aeron.service.matching.CoreMatchingResult> results =
                batch.pipelinedMatchingResults;
        results.clear();
        try {
            for (int index = 0; index < batch.items.size(); index++) {
                OrderBatchItem item = batch.items.get(index);
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                com.surprising.aeron.service.matching.CoreMatchingOrder matchingOrder =
                        batch.preparedMatchingOrders[index];
                results.add(owner.matchingAdapter.executeShardWithEvidenceSync(
                        shard, pending.sequence(), pending.command().header().commandId(),
                        command.orderId(), command.instrumentChangeId(),
                        pending.command().header().submittedAtEpochMillis(),
                        () -> owner.matchingAdapter.place(userId, matchingOrder)));
            }
            return results.getFirst();
        } catch (RuntimeException failure) {
            batch.pipelinedMatchingFailure = failure;
            throw failure;
        }
    }

    CoreResponse startOrderBatchItem(OrderBatchPending batch, PendingMatching pending,
                                             long clusterTimestamp, long clusterPosition,
                                             boolean deferCompletion) {
        if (batch.kind != OrderBatchKind.CANCEL && !owner.runtimeState.tryEnterSequentialLaneStage()) {
            batch.sequentialAdmission = true;
            batch.started = false;
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        while (batch.nextIndex < batch.items.size()) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            long runtimeRevisionBefore = owner.runtimeState.revision();
            try {
                prepareOrderBatchItem(batch, item, pending.command().header().userId(),
                        pending.command().header().commandId());
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                owner.pendingMatching.put(pending);
                owner.submitMatching(pending);
                // 撮合跨回调完成；变更上下文归本序号持有，不能占用下一条命令的 owner。
                owner.suspendMatchingCommitContext(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()));
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
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
            batch.matchingApplied = true;
            initializeOrderBatchLaneContext(batch, pending);
            owner.commits.signalPendingMatchingReady(batch.sequence);
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    void prepareOrderBatchItem(OrderBatchPending batch, OrderBatchItem item, long userId,
                                       UUID commandId) {
        if (batch.admissionOrderIndex == null) {
            batch.admissionOrderIndex = new BatchAdmissionOrderIndex(owner.activeOrderIndex, owner.identities, batch.items.size());
            batch.admissionOrderIndex.reset(userId);
        }
        long previousTimestamp = owner.currentClusterTimestamp;
        long previousPosition = owner.currentClusterPosition;
        owner.currentClusterTimestamp = batch.clusterTimestamp;
        owner.currentClusterPosition = batch.clusterPosition;
        try {
            switch (batch.kind) {
                case PLACE -> {
                    PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                    owner.requireOrderIdentityAvailable(userId, command);
                    owner.reservePlaceOrderRuntime(userId, command, commandId, batch.sequence,
                            batchOpenInterestSteps(batch, command.symbol()), batch.admissionOrderIndex, batch);
                    batch.currentPreMatchingCancellationOrderIds = owner.admissions.preMatchingCloseCapacityCancellations(
                            userId, command, command.orderId());
                }
                case CANCEL -> {
                    batch.currentPreMatchingCancellationOrderIds = List.of();
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    OrderRuntime order = owner.runtimeOrder(command.orderId());
                    if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                    if (order.userId() != userId) {
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    }
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    OrderRuntime order = owner.runtimeOrder(command.originalOrderId());
                    if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                    if (order.userId() != userId) {
                        throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                    }
                    if (order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN
                            || order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
                        throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
                    }
                    if (owner.runtimeState.order(command.replacementOrderId()) != null) {
                        throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
                    }
                    PlaceOrderCommand replacement = owner.replacementForAmend(command, order);
                    ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(owner.runtimeState,
                            owner.identities, userId, replacement, owner.currentClusterTimestamp);
                    long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                            owner.runtimeState, owner.identities, userId, resolved,
                            batchOpenInterestSteps(batch, replacement.symbol()), batch.admissionOrderIndex,
                            command.originalOrderId());
                    if (owner.productLine == ProductLine.SPOT) {
                        var reservation = owner.runtimeState.reservation(command.originalOrderId());
                        int assetId = owner.identities.assetId(resolved.reservationAsset());
                        if (reservation == null || reservation.assetId() != assetId) {
                            throw new IllegalStateException("spot amend original reservation is missing or mismatched");
                        }
                        // The previous item has crossed its completion fence; its published scalar
                        // is current. Do not enqueue another Lane task just to preflight available funds.
                        long available = owner.runtimeState.publishedAvailableBalance(userId, assetId);
                        if (available == Long.MIN_VALUE || requiredReservation > Math.addExact(
                                available, reservation.reservedUnits())) {
                            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                                    "available balance is insufficient for amended order");
                        }
                    }
                    batch.currentPreMatchingCancellationOrderIds = owner.admissions.preMatchingCloseCapacityCancellations(
                            userId, replacement, command.originalOrderId());
                }
            }
        } finally {
            owner.currentClusterTimestamp = previousTimestamp;
            owner.currentClusterPosition = previousPosition;
        }
    }

    void submitOrderBatchMatching(PendingMatching pending) {
        OrderBatchPending batch = pendingOrderBatches.get(pending.sequenceKey());
        if (batch == null) return;
        if (batch.kind == OrderBatchKind.CANCEL) {
            submitCancelBatchChunk(pending, batch);
            if (pending.clusterIndependent) pending.matchingSubmitted();
            return;
        }
        owner.matcherPipeline.submit(orderBatchMatcherShard(batch), pending.sequence(),
                prepareOrderBatchMatchingCommand(pending, batch, batch.items.get(batch.nextIndex)));
    }

    void submitCancelBatchChunk(PendingMatching pending, OrderBatchPending batch) {
        int shard = orderBatchMatcherShard(batch);
        int start = batch.nextIndex;
        int end = start;
        // Only independent cancellations on the same matcher are coalesced. Missing orders
        // stay on the owner rejection path; cross-shard chunks keep original item order.
        while (end < batch.items.size()) {
            OrderBatchItem item = batch.items.get(end);
            OrderRuntime order = owner.runtimeOrder(item.orderId);
            if (order == null || owner.matchingAdapter.matcherShardId(owner.runtimeOrderSymbol(order)) != shard) break;
            item.matchingSubmission = prepareOrderBatchMatchingCommand(pending, batch, item);
            end++;
        }
        if (end == start) throw new IllegalStateException("validated cancel chunk is empty");
        batch.cancellationChunkEnd = end;
        int chunkEnd = end;
        owner.matcherPipeline.submit(shard, pending.sequence(), () -> {
            batch.pipelinedMatchingResults.clear();
            for (int index = start; index < chunkEnd; index++) {
                var result = batch.items.get(index).matchingSubmission.get();
                batch.pipelinedMatchingResults.add(result);
                if (owner.commits.matchingResultNeedsRecovery(pending, result)) break;
            }
            return batch.pipelinedMatchingResults.getFirst();
        });
    }

    CoreResponse completeOrderBatchMatching(long sequence,
                                                    com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                                    long clusterTimestamp, long clusterPosition) {
        PendingMatching pending = owner.pendingMatching.get(sequence);
        OrderBatchPending batch = pendingOrderBatches.get(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = pendingOrderBatches.get(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            throw failOrderBatch(batch, pending, detail, failure);
        }
        if (batch.pipelined) {
            return completePipelinedPlaceBatch(
                    batch, pending, matchingResult, clusterTimestamp, clusterPosition);
        }
        if (batch.kind == OrderBatchKind.CANCEL) {
            if (batch.pipelinedMatchingResults.isEmpty()
                    || batch.pipelinedMatchingResults.getFirst() != matchingResult) {
                throw failOrderBatch(batch, pending, "cancel chunk completion identity mismatch", null);
            }
            for (var result : batch.pipelinedMatchingResults) {
                if (owner.commits.matchingResultNeedsRecovery(pending, result)) {
                    throw failOrderBatch(batch, pending, "cancel chunk matcher divergence", null);
                }
                if (result != matchingResult) {
                    owner.commits.validateMatchingEvidence(pending, result);
                    owner.commits.applyMatcherProgress(result);
                }
                applyCompletedOrderBatchItem(batch, pending, result);
            }
            if (batch.nextIndex != batch.cancellationChunkEnd) {
                throw failOrderBatch(batch, pending, "cancel chunk completion count mismatch", null);
            }
            batch.pipelinedMatchingResults.clear();
        } else {
            applyCompletedOrderBatchItem(batch, pending, matchingResult);
        }
        return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition, false);
    }

    void applyCompletedOrderBatchItem(OrderBatchPending batch, PendingMatching pending,
                                              CoreMatchingResult matchingResult) {
        batch.lastMatchingResult = matchingResult;
        batch.retainMatchingResult(matchingResult);
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
            for (int index = 0; index < batch.itemChangedOrderIds.size(); index++) {
                long orderId = batch.itemChangedOrderIds.valueAt(index);
                batch.admissionOrderIndex.update(owner.runtimeState.currentPatchOrderBefore(orderId),
                        owner.runtimeOrder(orderId));
            }
            appendOrderBatchResult(batch, item, status, resultCode);
            item.matchingSubmission = null;
            batch.nextIndex++;
        } catch (RuntimeException exception) {
            // The matcher already applied this item. Operational settlement failures also require
            // snapshot/log recovery; never turn them into an item rejection or retry this mutation.
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
    }

    CoreResponse completePipelinedPlaceBatch(
            OrderBatchPending batch, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult firstMatchingResult,
            long clusterTimestamp, long clusterPosition) {
        applyPipelinedPlaceBatchResults(batch, pending, firstMatchingResult);
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    void applyPipelinedPlaceBatchResults(
            OrderBatchPending batch, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult firstMatchingResult) {
        List<com.surprising.aeron.service.matching.CoreMatchingResult> matchingResults =
                batch.pipelinedMatchingResults;
        if (matchingResults == null || matchingResults.size() != batch.items.size()
                || matchingResults.getFirst().nativeCommand().matcherSequence()
                != firstMatchingResult.nativeCommand().matcherSequence()) {
            throw failOrderBatch(batch, pending, "pipelined matcher batch result is incomplete", null);
        }
        for (int index = 0; index < matchingResults.size(); index++) {
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult = matchingResults.get(index);
            if (owner.commits.matchingResultNeedsRecovery(pending, matchingResult)) {
                throw failOrderBatch(batch, pending,
                        "matcher continuation returned " + matchingResult.resultCode(), null);
            }
            if (index > 0) {
                owner.commits.validateMatchingEvidence(pending, matchingResult);
                owner.commits.applyMatcherProgress(matchingResult);
            }
            batch.lastMatchingResult = matchingResult;
            batch.retainMatchingResult(matchingResult);
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
            CoreResultCode resultCode = matchingResult.accepted()
                    ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
            try {
                applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
                batch.collectChangedOrderIds(item, pending.command().header().userId(), matchingResult);
                appendOrderBatchResult(batch, item, status, resultCode);
                batch.nextIndex++;
            } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
                throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
            }
        }
        batch.matchingApplied = true;
    }

    long batchOpenInterestSteps(OrderBatchPending batch, String symbol) {
        long longQuantity = owner.openInterestIndex.longQuantityNormalized(symbol);
        long shortQuantity = owner.openInterestIndex.shortQuantityNormalized(symbol);
        for (int index = 0; index < batch.changedUserIds.size(); index++) {
            long userId = batch.changedUserIds.valueAt(index);
            long before = batchPositionQuantityBefore(userId, symbol);
            long current = owner.runtimePositionQuantity(userId, symbol);
            if (before > 0) longQuantity = Math.subtractExact(longQuantity, before);
            else if (before < 0) shortQuantity = Math.subtractExact(shortQuantity, Math.negateExact(before));
            if (current > 0) longQuantity = Math.addExact(longQuantity, current);
            else if (current < 0) shortQuantity = Math.addExact(shortQuantity, Math.negateExact(current));
        }
        return Math.max(longQuantity, shortQuantity);
    }

    long batchPositionQuantityBefore(long userId, String symbol) {
        long quantity = 0;
        for (com.surprising.aeron.protocol.CorePositionSide side
                : com.surprising.aeron.protocol.CorePositionSide.values()) {
            String key = side == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + side.name();
            Long positionKey = owner.identities.findPositionKey(userId, key);
            if (positionKey == null) continue;
            com.surprising.aeron.service.state.PositionRuntime position =
                    owner.runtimeState.currentPatchPositionBefore(positionKey);
            if (position != null) quantity = Math.addExact(quantity, position.signedQuantitySteps());
        }
        return quantity;
    }

    void rollbackOrderBatchMutations(OrderBatchPending batch, boolean endScope) {
        owner.commits.abortCommitPublicationBatch();
        owner.runtimeState.rollbackActiveCommand(batch.runtimeCheckpoint, batch.sequence);
        batch.rollbackPreparedClientKeys(owner.identities);
        owner.identities.rollbackPositionKeys(batch.positionIdentityCheckpoint);
        if (endScope) owner.runtimeState.endOrderBatchMutationScope();
    }

    com.surprising.aeron.service.matching.FatalMatchingDivergenceException failOrderBatch(
            OrderBatchPending batch, PendingMatching pending, String detail, Throwable cause) {
        // Exchange-core facts are irreversible here. Preserve their observed sequence/prefix for replay evidence,
        // stop every continuation, and roll back only the unpublished Product Core runtime command.
        owner.matchingAdapter.poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        owner.admissions.queuedMatching.clear();
        owner.admissions.deferredMatching.clear();
        owner.pendingMatching.forEach(value -> {
            if (owner.laneCommandContexts.claimed(value.sequence())) {
                owner.laneCommandContexts.required(value.sequence()).resetMatchingContinuation();
            }
        });
        owner.runtimeState.endOrderBatchMutationScope();
        return owner.failMatching(pending, detail, cause);
    }

    void applyOrderBatchMatcherResult(
            OrderBatchPending batch, OrderBatchItem item, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (owner.realtimeCapture != null && owner.realtimeCapture.active() && matchingResult.accepted()) {
            item.realtimeTakerOrder = owner.runtimeOrder(item.orderId());
        }

        if (batch.kind != OrderBatchKind.CANCEL) {
            item.executionEvents = matchingResult.matcherEvents();
            item.executionTakerUserId = pending.command().header().userId();
            for (MatcherEvent event : item.executionEvents) {
                if (event.eventType() == MatcherEventType.TRADE) {
                    item.executionCount++;
                    batch.tradeCount = Math.incrementExact(batch.tradeCount);
                }
            }
        }
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                deferOrderBatchPreMatchingCancellations(batch, pending, matchingResult);
                if (matchingResult.accepted()) {
                    if (owner.productLine.isDerivative() || batch.pipelined) {
                        batch.deferredSettlementOrderIds.add(command.orderId());
                        batch.deferredSettlementExpectedLaneMasks.add(owner.commits.expectedLaneMask(pending, matchingResult));
                        batch.deferredSettlementMatchingResults.add(matchingResult);
                    } else {
                        batch.mergeTreasuryDelta(owner.runtimeState.applyOrderBatchMatcherSettlement(
                                pending.sequence(), owner.commits.expectedLaneMask(pending, matchingResult), command.orderId(),
                                matchingResult, owner.identities,
                                owner.terminalRetention));
                    }
                } else {
                    owner.rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId(), pending.sequence());
                }
                return;
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                if (matchingResult.accepted()) {
                    batch.deferredCancellationOrderIds.add(command.orderId());
                }
                return;
                }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                PlaceOrderCommand replacement = owner.replacementForAmend(command,
                        owner.runtimeOrder(command.originalOrderId()));
                deferOrderBatchPreMatchingCancellations(batch, pending, matchingResult);
                if (matchingResult.accepted()) {
                    // The final cancel event also stamps and retires the original terminal order.
                    // SPOT releases its funds earlier in the replacement reservation task.
                    batch.deferredCancellationOrderIds.add(command.originalOrderId());
                    owner.requireOrderIdentityAvailable(pending.command().header().userId(), replacement);
                    owner.reservePlaceOrderRuntime(pending.command().header().userId(), replacement,
                            pending.command().header().commandId(), pending.sequence(),
                            batchOpenInterestSteps(batch, replacement.symbol()), batch.admissionOrderIndex, batch);
                    if (owner.productLine.isDerivative()) {
                        batch.deferredSettlementOrderIds.add(replacement.orderId());
                        batch.deferredSettlementExpectedLaneMasks.add(owner.commits.expectedLaneMask(pending, matchingResult));
                        batch.deferredSettlementMatchingResults.add(matchingResult);
                    } else {
                        batch.mergeTreasuryDelta(owner.runtimeState.applyOrderBatchMatcherSettlement(
                                pending.sequence(), owner.commits.expectedLaneMask(pending, matchingResult), replacement.orderId(),
                                matchingResult, owner.identities,
                                owner.terminalRetention));
                    }
                }
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

    boolean dispatchOrderBatchLaneWork(OrderBatchPending batch, PendingMatching pending,
                                               long timestamp, long position) {
        if (batch.cancelEvent == null && !batch.deferredCancellationOrderIds.isEmpty()) {
            batch.cancelEvent = owner.runtimeState.dispatchCancelBatch(
                    batch.sequence, pending.command().header().userId(),
                    batch.deferredCancellationOrderIds.toPrimitiveArray(), timestamp, position,
                    owner.identities, batch.kind == OrderBatchKind.CANCEL, batch);
        }
        if (batch.settlementEvent != null || batch.deferredSettlementOrderIds.isEmpty()) return false;
        batch.settlementEvent = owner.runtimeState.dispatchMatcherSettlementBatch(
                batch.sequence, batch,
                owner.identities, timestamp, position);
        return true;
    }

    CoreResponse finishOrderBatch(OrderBatchPending batch, PendingMatching pending,
                                          long clusterTimestamp, long clusterPosition) {
        CoreAdmissionReservation capacityReservation = owner.sequenceAdmission(pending.sequence());
        if (capacityReservation == null) {
            throw new IllegalStateException("order batch admission reservation is missing");
        }
        if (!batch.finishing) {
            owner.activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
            owner.setCommandFundsDelta(pending.fundsDelta());
            batch.finishing = true;
        }
        LaneCommandContextRing.Context laneContext = owner.laneCommandContexts.required(batch.sequence);
        initializeOrderBatchLaneContext(batch, pending);
        if (dispatchOrderBatchLaneWork(batch, pending, clusterTimestamp, clusterPosition)) {
            owner.suspendMatchingCommitContext(pending);
            return null;
        }
        if (batch.cancelEvent != null && !batch.cancellationsCollected) {
            if (!batch.cancelEvent.complete()) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            owner.runtimeState.collectCancel(
                    batch.cancelEvent, owner.commandFundsAccumulator, owner.terminalRetention);
            if (batch.cancelEvent.commitsLane()) {
                laneContext.completeLanes(batch.cancelEvent.requiredLaneMask());
                batch.laneCommitCompleted = true;
            }
            owner.runtimeState.releaseCancel(batch.cancelEvent);
            batch.cancellationsCollected = true;
            owner.commits.requestCommitPublication();
        }
        if (batch.settlementEvent != null && !batch.settlementsCollected) {
            long settlementLaneMask = batch.settlementEvent.requiredLaneMask();
            boolean finalLaneCommit = batch.settlementEvent.commitSequence() != 0;
            RuntimeTreasuryDelta delta = owner.runtimeState.collectMatcherSettlement(
                    batch.settlementEvent, owner.commandFundsAccumulator, owner.terminalRetention);
            if (delta == null) {
                owner.suspendMatchingCommitContext(pending);
                return null;
            }
            batch.mergeTreasuryDelta(delta);
            owner.runtimeState.releaseMatcherSettlement(batch.settlementEvent);
            batch.settlementsCollected = true;
            if (finalLaneCommit) {
                laneContext.completeLanes(settlementLaneMask);
                batch.laneCommitCompleted = true;
            }
            owner.commits.requestCommitPublication();
        }
        owner.runtimeState.completePendingReservations(batch.sequence);
        if (!batch.laneCommitCompleted && !batch.runtimeChangedOrderIds.isEmpty()) {
            if (RuntimeCommandProcessor.stampChangedOrdersByLane(owner.runtimeState,
                    clusterTimestamp, clusterPosition, batch.runtimeChangedOrderIds, batch.changedUserIds)) {
                owner.commits.requestCommitPublication();
            }
        }
        // 批量响应直接消费 batch；全局发布消费 runtime 变更，不能再复制到普通单结果容器。
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        if (laneContext.expectedLaneMask() != batch.actualLaneMask) {
            throw failOrderBatch(batch, pending, "order batch account lane mask mismatch", null);
        }
        long committedLaneMask;
        try {
            if (batch.treasuryDelta != null) batch.treasuryDelta.apply(owner.runtimeState.treasury());
            owner.runtimeState.setMetadata(owner.productLine,
                    Math.incrementExact(owner.runtimeState.revision()));
            committedLaneMask = batch.laneCommitCompleted
                    ? batch.actualLaneMask
                    : owner.stageLaneMutation(batch.sequence, batch.changedUserIds, laneContext);
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw new IllegalStateException("order batch account lane mask mismatch");
            }
            owner.commits.requireCompleteAccountLanes(laneContext);
        } catch (RuntimeException validationFailure) {
            throw failOrderBatch(batch, pending, "order batch final validation failed", validationFailure);
        }
        captureCommittedBatchTrades(batch);
        owner.commits.completeCommitPublicationBatch();
        if (batch.preparedResponse == null) for (OrderBatchItem item : batch.items) {
            if (item.laneResultPrepared) continue;
            OrderRuntime order = owner.runtimeOrder(item.orderId());
            if (order == null && item.originalOrderId() > 0) order = owner.runtimeOrder(item.originalOrderId());
            item.resultOrder = order;
            item.resultOrderSymbol = order == null ? null : owner.runtimeOrderSymbol(order);
        }
        byte[] responseData = batch.preparedResponse != null
                ? batch.preparedResponse : TradingOrderBatchCodec.encodeResultSource(batch);
        owner.terminalTradeCount = Math.addExact(owner.terminalTradeCount, batch.tradeCount);
        owner.validateFundsConservation(pending.command());
        owner.commitMatchingSequence(batch.sequence);
        long businessStateHash = owner.currentProjectionPoint == batch.beforeProjection
                ? owner.cachedBusinessStateHash : owner.currentBusinessStateHash();
        long requiredExportSequence = 0;
        owner.cachedBusinessStateHash = businessStateHash;
        long stateHash = owner.stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, batch.sequence);
        owner.resultLedger.storeResult(pending.command().header().commandId(), StoredResult.owned(
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, requiredExportSequence, stateHash, responseData));
        owner.runtimeState.endOrderBatchMutationScope();
        if (pending.takePipelinedSettlementCounted()) {
            owner.commits.dispatchedSettlementInFlight--;
            if (owner.commits.dispatchedSettlementInFlight < 0) {
                throw new IllegalStateException("matcher settlement in-flight count underflow");
            }
        }
        owner.removePendingMatching(batch.sequence);
        unregisterPipelinedBatchSymbols(batch);
        pendingOrderBatches.remove(batch.sequence);
        owner.submitDeferredMatchingAfterBatch();
        CoreResponse response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence, requiredExportSequence, stateHash, responseData);
        releaseOrderBatchPending(batch);
        return owner.releaseAdmission(capacityReservation, response);
    }

    void initializeOrderBatchLaneContext(OrderBatchPending batch, PendingMatching pending) {
        if (batch.laneContextInitialized) return;
        long expectedLaneMask = 0;
        for (int index = 0; index < batch.changedUserIds.size(); index++) {
            expectedLaneMask |= owner.matchingAdapter.topology().accountLaneMask(
                    batch.changedUserIds.valueAt(index));
        }
        batch.actualLaneMask = expectedLaneMask;
        var finalMatchingResult = batch.lastMatchingResult == null
                ? new com.surprising.aeron.service.matching.CoreMatchingResult(true, "NO_NATIVE_COMMAND")
                .withCoreSequence(batch.sequence)
                : batch.lastMatchingResult;
        owner.laneCommandContexts.required(pending.sequence()).result(
                finalMatchingResult, expectedLaneMask, owner.commits.validAccountLaneMask());
        batch.laneContextInitialized = true;
    }

    int orderBatchMatcherShard(OrderBatchPending batch) {
        if (batch == null || batch.nextIndex < 0 || batch.nextIndex >= batch.items.size()) {
            throw new IllegalArgumentException("order batch matcher route is unavailable");
        }
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        String symbol = switch (batch.kind) {
            case PLACE -> ((PlaceOrderCommand) item.command).symbol();
            case CANCEL -> {
                OrderRuntime order = owner.runtimeOrder(((CancelOrderCommand) item.command).orderId());
                yield order == null ? "" : owner.identities.symbol(order.symbolId());
            }
            case AMEND -> {
                OrderRuntime order = owner.runtimeOrder(((AmendOrderCommand) item.command).originalOrderId());
                yield order == null ? "" : owner.identities.symbol(order.symbolId());
            }
        };
        return symbol.isBlank() ? 0 : batch.decodedCommand == null
                ? owner.matchingAdapter.matcherShardId(symbol)
                : batch.decodedCommand.matcherShard(owner.matchingAdapter, symbol);
    }

    void beginOrderBatchCommitContext(OrderBatchPending batch, PendingMatching pending) {
        CoreAdmissionReservation reservation = owner.sequenceAdmission(pending.sequence());
        if (reservation == null) throw new IllegalStateException("order batch admission reservation is missing");
        owner.activateFactContext(reservation, pending.command(), pending.fingerprint());
        owner.resultBuilder.commandOrderViews = List.of();
        owner.resultBuilder.commandChangedUserIds = List.of();
        owner.resultBuilder.commandChangedOrderIds = List.of();
        owner.resultBuilder.commandTradeCount = 0;
        owner.resultBuilder.commandFundingProgress = null;
        owner.resultBuilder.commandLiquidationProgress = null;
        owner.resultBuilder.commandLiquidationBatchResult = null;
        owner.resultBuilder.commandSettlementProgress = null;
        owner.resultBuilder.commandRiskScanControl = null;
        owner.resultBuilder.resetChangeAccumulators();
        owner.runtimeState.beginOrderBatchMutationScope();
        batch.beforeProjection = owner.currentProjectionPoint;
        batch.runtimeCheckpoint = owner.runtimeState.commandRevisionCheckpoint();
        batch.positionIdentityCheckpoint = owner.identities.positionCheckpoint();
        batch.beginCommit();
        owner.commits.beginCommitPublicationBatch();
    }

    void beginPipelinedOrderBatchCommit(OrderBatchPending batch, PendingMatching pending) {
        PlaceBatchAdmissionEvent admission = batch.placeBatchAdmissionEvent;
        boolean preparedCancel = pending.clusterIndependent && batch.kind == OrderBatchKind.CANCEL;
        if (!preparedCancel && (!batch.admissionCollected || admission == null || !admission.complete()
                || admission.rejection() != null)) {
            throw new IllegalStateException("pipelined order batch admission is not ready to commit");
        }
        beginOrderBatchCommitContext(batch, pending);
        if (!preparedCancel) {
            owner.runtimeState.collectPlaceBatchAdmission(admission, owner.commandFundsAccumulator);
            owner.runtimeState.releasePlaceBatchAdmission(admission);
            batch.placeBatchAdmissionEvent = null;
            owner.commits.requestCommitPublication();
        }
    }

    void deferOrderBatchPreMatchingCancellations(
            OrderBatchPending batch, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        List<Long> expected = pending.preMatchingCancellationOrderIds();
        if (expected.isEmpty()) return;
        for (CoreCancellationResult cancellation : result.cancellations()) {
            if (!cancellation.accepted() || !expected.contains(cancellation.orderId())) continue;
            OrderRuntime order = owner.runtimeOrder(cancellation.orderId());
            if (order != null && order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                batch.deferredCancellationOrderIds.add(order.orderId());
            }
        }
    }

    boolean conflictsWithEarlierPipelinedBatch(OrderBatchPending candidate) {
        if (activePipelinedOrderBatches != pendingOrderBatches.size() - 1) return true;
        for (OrderBatchItem item : candidate.items) {
            if (pipelinedBatchBySymbol.containsKey(((PlaceOrderCommand) item.command).symbol())) return true;
        }
        return false;
    }

    /** 每批仅在有序提交点发布成交，不能写入前一条命令的实时捕获范围。 */
    private void captureCommittedBatchTrades(OrderBatchPending batch) {
        if (owner.realtimeCapture == null) return;
        for (OrderBatchItem item : batch.items) {
            var taker = item.realtimeTakerOrder;
            item.realtimeTakerOrder = null;
            if (taker == null || owner.realtimeCapture == null || !owner.realtimeCapture.active()) continue;
            try {
                int fill = 0;
                for (MatcherEvent event : item.executionEvents) {
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
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.PLACE);
            batch.decodedCommand = decodedCommand;
            for (PlaceOrderCommand value : command.orders()) {
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = decodedCommand.cancelOrderBatch();
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.CANCEL, command.orders().size(),
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.CANCEL);
            batch.decodedCommand = decodedCommand;
            for (CancelOrderCommand value : command.orders()) {
                batch.addItem(value.orderId(), 0, 0, value);
            }
            return batch;
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = decodedCommand.amendOrderBatch();
            OrderBatchPending batch = acquireOrderBatchPending(OrderBatchKind.AMEND, command.orders().size(),
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.AMEND);
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
            PendingMatching.Operation operation) {
        OrderBatchPending selected = null;
        Iterator<OrderBatchPending> iterator = orderBatchPendingPool.iterator();
        while (iterator.hasNext()) {
            OrderBatchPending candidate = iterator.next();
            if (candidate.capacity() < itemCount) continue;
            iterator.remove();
            selected = candidate;
            break;
        }
        if (selected == null) selected = new OrderBatchPending(itemCount);
        return selected.initialize(kind, clusterTimestamp, clusterPosition, operation);
    }

    void releaseOrderBatchPending(OrderBatchPending batch) {
        if (batch == null) return;
        unregisterPipelinedBatchSymbols(batch);
        batch.clear();
        if (orderBatchPendingPool.size() < TradingCoreRuntime.MAX_PENDING_MATCHING) orderBatchPendingPool.addFirst(batch);
    }

    void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            OrderRuntime order = owner.runtimeOrder(orderId);
            if (order != null && order.userId() != userId) {
                throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH",
                        "order batch contains another user's order");
            }
        }
    }

    void requireUnchangedRejectedBatchItem(OrderBatchPending batch, PendingMatching pending,
                                                   long runtimeRevisionBefore,
                                                   RuntimeException exception) {
        if (owner.runtimeState.revision() != runtimeRevisionBefore) {
            owner.runtimeState.endOrderBatchMutationScope();
            throw owner.failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult>
            prepareOrderBatchMatchingCommand(
            PendingMatching pending, OrderBatchPending batch, OrderBatchItem item) {
        long orderId;
        long instrumentChangeId;
        java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> submission;
        List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellations =
                owner.preMatchingCancellationOrders(pending);
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                orderId = command.orderId();
                instrumentChangeId = command.instrumentChangeId();
                var matchingOrder = owner.matchingOrder(item.orderId());
                submission = () -> owner.matchingAdapter.place(
                        pending.command().header().userId(), matchingOrder);
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                var order = owner.runtimeState.order(command.orderId());
                orderId = command.orderId();
                instrumentChangeId = order == null ? 0 : order.instrumentChangeId();
                String symbol = order == null ? "" : owner.identities.symbol(order.symbolId());
                submission = () -> owner.matchingAdapter.cancelForContinuation(
                        pending.command().header().userId(), command.orderId(), symbol);
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                OrderRuntime order = owner.runtimeOrder(command.originalOrderId());
                PlaceOrderCommand replacement = owner.replacementForAmend(command, order);
                orderId = replacement.orderId();
                instrumentChangeId = replacement.instrumentChangeId();
                String symbol = owner.runtimeOrderSymbol(order);
                var matchingOrder = owner.matchingOrder(pending.command().header().userId(), replacement);
                submission = () -> owner.matchingAdapter.replaceOrder(
                        pending.command().header().userId(), command.originalOrderId(), symbol, matchingOrder);
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
        java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> guarded = () -> {
            try {
                return owner.matchingAdapter.executeAfterCancellationsSync(preMatchingCancellations, submission);
            } catch (RuntimeException exception) {
                return new com.surprising.aeron.service.matching.CoreMatchingResult(
                        false, "EXCHANGE_CORE_FAILURE");
            }
        };
        int shard = orderBatchMatcherShard(batch);
        return () -> owner.matchingAdapter.executeShardWithEvidenceSync(shard, pending.sequence(),
                pending.command().header().commandId(), orderId, instrumentChangeId,
                pending.command().header().submittedAtEpochMillis(), guarded);
    }
}
