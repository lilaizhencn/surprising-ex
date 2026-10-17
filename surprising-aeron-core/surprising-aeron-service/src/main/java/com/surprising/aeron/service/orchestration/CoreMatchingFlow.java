package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;

import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.MatchingResult;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import exchange.core2.core.common.MatcherEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 撮合命令的在途生命周期。
 *
 * <p>该边界拥有环槽登记、固定 Matcher 路由、提交、Lane 结算事件收集和完成结果读取。
 * {@link TradingCoreRuntime} 仍是唯一状态 owner；本类只组织跨线程交接，不维护第二份订单、
 * 余额或持仓状态。</p>
 */
final class CoreMatchingFlow {

    private final TradingCoreRuntime owner;

    CoreMatchingFlow(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, TradingCoreRuntime.SourceKey sourceKey,
                                        CommandFingerprint fingerprint, CoreResultCode resultCode,
                                        CommandSlot deferredPending) {
        return deferredPending == null
                ? recordRejectedMatching(message, sourceKey, fingerprint, resultCode)
                : owner.admissions.recordRejectedDeferredMatching(deferredPending, resultCode);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, TradingCoreRuntime.SourceKey sourceKey,
                                        CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!owner.pendingMatching.isEmpty()) {
            long sequence = owner.nextAppliedSequence();
            CommandSlot pending = owner.admissions.newPendingMatching(sequence,
                    MatchingCommandAdmission.matchingOperation(message.header().messageType()),
                    message, fingerprint);
            putPendingMatching(pending);
            pending.rejectMatching(resultCode);
            owner.pendingMatching.completeSubmission(sequence);
            owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                    TradingCoreRuntime.matchingPendingCode(), sequence,
                    TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        }
        long sequence = owner.nextAppliedSequence();
        owner.recordAppliedSequence(sequence, sourceKey, message.header().sourceSequence());
        owner.resultLedger.storeOwnedResult(message.header().commandId(), fingerprint,
                ResponseStatus.REJECTED, resultCode, sequence,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, TradingCoreRuntime.EMPTY_RESPONSE_DATA);
    }

    CommandSlot removePendingMatching(long sequence) {
        owner.admissions.pendingLifecycleScopes.remove(sequence);
        int shard = owner.pendingMatching.submissionShard(sequence);
        boolean releasesSubmissionHead = shard >= 0
                && owner.pendingMatching.isSubmissionHead(sequence, shard);
        CommandSlot current = owner.pendingMatching.get(sequence);
        if (current != null) {
            if (current.replacementAdmission() != null) {
                if (!current.replacementAdmission().complete())
                    throw new IllegalStateException("terminal command has incomplete replacement admission");
                owner.runtimeState.releaseReplacementAdmission(current.takeReplacementAdmission());
            }
            if (current.placeAdmission() != null && !current.placeAdmissionOwnerCollected()
                    && !collectPlaceAdmissionIfReady(current)) {
                throw new IllegalStateException("terminal command has an incomplete place admission");
            }
            current.committed();
            if (current.placeAdmission() != null && !current.placeAdmission().matcherConsumed()) {
                // Explicit/test completion can provide the terminal matcher fact without running
                // the queued continuation. Terminal retirement proves that continuation is dead.
                current.placeAdmission().cancelMatcherWait();
            }
            releasePlaceAdmissionIfConsumed(current);
            if (current.placeAdmission() != null) {
                throw new IllegalStateException("terminal command still owns an unconsumed place admission");
            }
        }
        CommandSlot removed = owner.pendingMatching.remove(sequence);
        if (releasesSubmissionHead) owner.matchingProgress.submissionHeadReleased(shard);
        refreshCommittedCoreSequence();
        return removed;
    }

    void refreshCommittedCoreSequence() {
        owner.refreshCommittedCoreSequence();
    }

    void commitMatchingSequence(long sequence) {
        if (!owner.pendingMatching.contains(sequence) || sequence <= owner.committedCoreSequence) {
            throw new IllegalStateException("matching sequence is not pending above the committed watermark");
        }
    }

    void putPendingMatching(CommandSlot pending) {
        pending.clusterIndependent = owner.clusterPipelineAdmission();
        if (owner.pendingMatching.size() >= owner.pendingMatching.capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        try {
            if (owner.clusterPipelineAdmission()
                    && owner.preparedRouteUserLaneBit() != 0
                    && owner.preparedRouteMatcherShard() >= 0) {
                pending.cachedMatcherShard(owner.preparedRouteMatcherShard());
            }
            owner.pendingMatching.put(pending);
            CoreMessageType type = pending.command().header().messageType();
            if (!TradingCoreRuntime.isOrderBatchCommand(type)) {
                owner.pendingMatching.registerSubmission(pending.sequence(), matcherShard(pending));
            }
        } catch (RuntimeException failure) {
            if (owner.pendingMatching.remove(pending.sequence()) == null) {
                owner.pendingMatching.discardPrepared(pending.sequence());
            }
            throw failure;
        }
    }

    int matcherShard(CommandSlot pending) {
        int cached = pending.cachedMatcherShard();
        if (cached >= 0) return cached;
        String instrumentId = pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT
                ? owner.admissions.pendingLifecycleSymbol(pending)
                : owner.admissions.matchingSymbol(pending.command(), pending.operation(), pending.decodedCommand());
        int shard = instrumentId == null || instrumentId.isBlank() ? 0 : owner.matchingAdapter.matcherShardId(instrumentId);
        pending.cachedMatcherShard(shard);
        return shard;
    }

    TradingCoreRuntime.LifecycleOrderChunk lifecycleOrders(
            long userId, String instrumentId, long cursorOrderId, int maxOrders) {
        var page = owner.activeOrderIndex.page(userId, instrumentId, cursorOrderId, maxOrders);
        var selected = page.orderIds().stream().map(owner.activeOrderIndex::activeOrderRoute)
                .filter(java.util.Objects::nonNull)
                .map(route -> new CancellationOrder(
                        route.orderId(), route.userId(), route.instrumentId())).toList();
        return new TradingCoreRuntime.LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    List<CancellationOrder> batchCancellationOrders(CommandSlot pending) {
        var command = pending.decodedCommand().liquidationBatch();
        List<CancellationOrder> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (int index = 0; index < command.actions().size(); index++) {
            if (remaining == 0) break;
            if (!pending.liquidationCheck().cancelsOrders(index)) continue;
            var action = command.actions().get(index);
            var liquidation = pending.liquidationCheck().liquidation(index);
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            TradingCoreRuntime.LifecycleOrderChunk chunk = lifecycleOrders(
                    liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        return List.copyOf(orders);
    }

    void submitMatching(CommandSlot pending) {
        // A cluster command can lose its shard submission head and be resumed by a later Owner
        // progress turn.  Keep the command's durable admission mode authoritative: the transient
        // ingress scope is no longer active in that turn, but submission must still reserve the
        // pooled Matcher-to-Lane settlement instead of falling back to CoreMatchingResult.
        boolean enteredAsynchronousScope = pending.clusterIndependent
                && !owner.runtimeState.asynchronousCommands();
        if (enteredAsynchronousScope) owner.runtimeState.enterAsynchronousCommandScope();
        try {
            submitMatchingInCommandScope(pending);
        } finally {
            if (enteredAsynchronousScope) owner.runtimeState.exitAsynchronousCommandScope();
        }
    }

    private void submitMatchingInCommandScope(CommandSlot pending) {
        // A batch keeps its Core sequence across items. Once ordered commit owns its item
        // cursor, later submissions must not re-enter the initial shard admission gate.
        boolean batchContinuation = pending.orderBatch != null && pending.orderBatch.commitStarted()
                && pending.orderBatch.nextIndex > 0 && pending.isMatchingSubmitted();
        if (pending.crossShardCancellationStarted
                || !batchContinuation && owner.matchingProgress.submissionDeferred(pending.sequence())) return;
        if (!collectReplacementAdmissionIfReady(pending) || hasPendingMatchingRejection(pending.sequence())) return;
        if (!collectLiquidationChecksIfReady(pending) || pending.hasMatchingRejection()) return;
        pending.prepareLaneResultTarget(owner.responseArena);
        if (pending.placeAdmission() != null && !owner.runtimeState.asynchronousCommands()) {
            if (!collectPlaceAdmissionIfReady(pending)
                    || hasPendingMatchingRejection(pending.sequence())) return;
        }
        if (pending.orderBatch != null) {
            owner.batches.submitOrderBatchMatching(pending);
            // A batch keeps its shard fence while more Matcher chunks may be submitted.
            if (pending.isMatchingSubmitted()
                    && owner.pendingMatching.submissionShard(pending.sequence()) >= 0
                    && owner.batches.remainingMatcherShardMask(pending.orderBatch) == 0)
                owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                && owner.matchingAdapter.topology().matchingEngineCount() > 1) {
            List<CancellationOrder> orders = batchCancellationOrders(pending);
            int shardId = singleMatcherShard(orders);
            if (shardId == -2) {
                owner.crossShardCancellations.start(pending, orders);
                return;
            }
            owner.matcherPipeline.submit(shardId < 0 ? matcherShard(pending) : shardId,
                    pending.sequence(), prepareMatchingCommand(pending, null));
            pending.matchingSubmitted();
            owner.matchingProgress.submissionCompleted(pending);
            return;
        }
        var direct = owner.directMatcherSettlements.prepareForMatching(pending);
        var command = prepareMatchingCommand(pending, direct);
        java.util.function.Supplier<?> matcherSubmission = command;
        if (direct != null && pending.placeAdmission() != null) {
            matcherSubmission = pending.gateAdmission(owner, pending.placeAdmission(), matcherShard(pending),
                    direct, matcherSubmission);
        }
        owner.matcherPipeline.submit(matcherShard(pending), pending.sequence(), matcherSubmission, direct);
        pending.matchingSubmitted();
        if (pending.placeAdmission() == null) owner.matchingProgress.submissionCompleted(pending);
    }

    int singleMatcherShard(List<CancellationOrder> orders) {
        int shardId = -1;
        for (var order : orders) {
            int orderShard = owner.matchingAdapter.matcherShardId(order.instrumentId());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    int pendingSubmissionShard(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : owner.batches.orderBatchMatcherShard(batch);
    }

    void publishMatchingCompletion(long sequence, CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        owner.pendingMatching.required(sequence).publishMatchingCompletion(result);
        owner.pendingMatching.progressChanged();
    }

    PlaceOrderCommand replacementFor(CoreMessage message, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String instrumentId = owner.runtimeOrderSymbol(order);
        if (owner.runtimeState.instrument(instrumentId) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        if (message.header().messageType() == CoreMessageType.REPLACE_ORDER) {
            return TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe()).replacement();
        }
        var command = TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe());
        return replacementForAmend(command, order);
    }

    PlaceOrderCommand replacementFor(DecodedMatchingCommand decodedCommand,
                                     CommandSlot.Operation operation, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (owner.runtimeState.instrument(owner.runtimeOrderSymbol(order)) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        return operation == CommandSlot.Operation.REPLACE
                ? decodedCommand.replaceOrder().replacement()
                : replacementForAmend(decodedCommand.amendOrder(), order);
    }

    PlaceOrderCommand replacementFor(CommandSlot pending, OrderRuntime order) {
        if (pending.operation() == CommandSlot.Operation.REPLACE) {
            if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
            if (owner.runtimeState.instrument(owner.runtimeOrderSymbol(order)) == null) {
                throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
            }
            return pending.decodedCommand().replaceOrder().replacement();
        }
        return replacementForAmend(pending.decodedCommand().amendOrder(), order);
    }

    PlaceOrderCommand replacementForAmend(AmendOrderCommand command, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String instrumentId = owner.runtimeOrderSymbol(order);
        if (owner.runtimeState.instrument(instrumentId) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), instrumentId,
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(),
                order.positionSide(), order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    long currentMarkPriceTicks(String instrumentId, long referencePriceTicks) {
        Integer symbolId = owner.identities.findSymbolId(instrumentId);
        var markPrice = symbolId == null ? null : owner.runtimeState.markPrice(symbolId);
        long value = markPrice == null ? referencePriceTicks : markPrice.markPriceTicks();
        if (value <= 0) throw new CoreStateRejectedException("MARK_PRICE_MISSING", "mark price is required");
        return value;
    }

    MatchingResult takeMatchingResult(long sequence) {
        if (owner.fatalFailure != null || !owner.pendingMatching.contains(sequence)) return null;
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null) return null;
        if (pending.matchingResult() != null) return pending.matchingResult();
        var direct = pending.settlementEvent();
        if (direct == null && pending.orderBatch != null) {
            direct = pending.orderBatch.itemSettlementEvent != null
                    ? pending.orderBatch.itemSettlementEvent : pending.orderBatch.settlementEvent;
        }
        if (direct != null && direct.direct()) return direct.ready() ? direct.directResult() : null;
        return pending.takeMatchingCompletion();
    }

    boolean establishMatchingCommitFence(long sequence, long clusterTimestamp, long clusterPosition) {
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null) return false;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        return true;
    }

    boolean placeAdmissionOutstanding(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        return batch != null && batch.placeBatchAdmissionEvent != null && !pending.isMatchingSubmitted();
    }

    boolean collectReplacementAdmissionIfReady(CommandSlot pending) {
        var event = pending.replacementAdmission();
        if (event == null || pending.matchingAdmission() != null || pending.hasMatchingRejection()) return true;
        if (!event.complete()) { owner.runtimeState.assertAccountLanesHealthy(); return false; }
        RuntimeException rejection = event.rejection();
        try {
            if (rejection != null) throw rejection;
            var admission = event.admission();
            if (admission.resolved().orderId() != admission.originalOrderId())
                owner.requireOrderIdentityAvailable(admission.userId(), admission.command());
            pending.withAdmission(admission);
            pending.withPreMatchingCancellations(pending.replacementAdmission().closeCapacityCancellations());
        } catch (com.surprising.aeron.service.exception.CoreStateRejectedException | ArithmeticException | IllegalArgumentException failure) {
            pending.rejectMatching(failure instanceof com.surprising.aeron.service.exception.CoreStateRejectedException rejected
                    ? CoreResultCode.fromRejectionCode(rejected.code())
                    : failure instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
            owner.matchingProgress.submissionCompleted(pending);
        }
        return true;
    }

    /** 账户校验完成后才撤Matcher订单；Owner只读本槽的动作结果。 */
    private boolean collectLiquidationChecksIfReady(CommandSlot pending) {
        if (pending.operation() != CommandSlot.Operation.LIQUIDATION
                && pending.operation() != CommandSlot.Operation.LIQUIDATION_BATCH) return true;
        var check = pending.liquidationCheck();
        if (!check.prepared()) {
            if (pending.operation() == CommandSlot.Operation.LIQUIDATION)
                check.prepare(pending.decodedCommand().liquidation(), owner.runtimeState, owner.identities);
            else check.prepare(pending.decodedCommand().liquidationBatch(), owner.runtimeState, owner.identities);
        }
        try { if (!check.poll()) return false; }
        catch (CoreStateRejectedException rejection) {
            pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
            owner.matchingProgress.submissionCompleted(pending);
            return true;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION) {
            try { check.executable(0); }
            catch (CoreStateRejectedException rejection) {
                pending.rejectMatching(CoreResultCode.fromRejectionCode(rejection.code()));
                owner.matchingProgress.submissionCompleted(pending);
            }
        }
        return true;
    }

    /** Collect a completed PLACE admission once before ordered commit. */
    boolean collectPlaceAdmissionIfReady(CommandSlot pending) {
        if (pending == null || pending.placeAdmission() == null) return true;
        var admission = pending.placeAdmission();
        if (!admission.complete()) return false;
        RuntimeException rejection = admission.rejection();
        if (!pending.placeAdmissionOwnerCollected()) {
            owner.identities.recordLaneClientAllocations(admission.takeIdentityAllocations());
            if (rejection != null) {
                CoreResultCode resultCode = rejection instanceof CoreStateRejectedException rejected
                        ? CoreResultCode.fromRejectionCode(rejected.code())
                        : rejection instanceof ArithmeticException ? CoreResultCode.ARITHMETIC_OVERFLOW
                        : CoreResultCode.INVALID_COMMAND;
                pending.rejectMatching(resultCode);
            } else {
                ResolvedPlaceOrder admitted = owner.runtimeState.collectPlaceAdmission(admission);
                pending.admissionCompleted(admitted);
                if (owner.realtimeCapture != null) pending.place.realtimeResolvedTakerOrder = admitted;
            }
            pending.markPlaceAdmissionOwnerCollected();
        }
        completePlaceAdmissionSubmission(pending);
        if (!pending.isMatchingSubmitted() && rejection != null && !admission.matcherConsumed()) {
            admission.cancelMatcherWait();
        }
        return true;
    }

    void releasePlaceAdmissionIfConsumed(CommandSlot pending) {
        if (pending != null && pending.placeAdmissionOwnerCollected()
                && pending.placeAdmission() != null && pending.placeAdmission().matcherConsumed()) {
            owner.runtimeState.releasePlaceAdmission(pending.takePlaceAdmission());
        }
    }

    private void completePlaceAdmissionSubmission(CommandSlot pending) {
        if (pending != null && pending.isMatchingSubmitted()
                && owner.pendingMatching.submissionShard(pending.sequence()) >= 0) {
            owner.matchingProgress.submissionCompleted(pending);
        }
    }

    boolean hasPendingMatchingRejection(long sequence) {
        return owner.pendingMatching.claimed(sequence)
                && owner.pendingMatching.required(sequence).hasMatchingRejection();
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        owner.assertOwner();
        try {
            return owner.matchingAdapter.topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline.readAtSubmissionFence(0,
                    () -> owner.matchingAdapter.orderBooksStateHashAsync().join())
                    : owner.matcherPipeline.readEachAsync(owner.matchingAdapter::stateHashShard)
                    .thenApply(owner.matchingAdapter::aggregateBookHash);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    Supplier<?> prepareMatchingCommand(CommandSlot pending,
            com.surprising.aeron.service.state.MatcherSettlementEvent direct) {
        if (isControlCommand(pending)) return prepareControlCancellations(pending);
        java.util.Objects.requireNonNull(direct, "trading command requires its direct settlement slot");
        pending.matcherPreCancellations(pending.operation() == CommandSlot.Operation.PLACE
                && pending.placeAdmission() != null ? List.of() : preMatchingCancellationOrders(pending));
        long userId = pending.command().header().userId();
        int shard = matcherShard(pending);
        return switch (pending.operation()) {
            case PLACE -> pending.preparePlaceMatching(owner, shard, userId,
                    java.util.Objects.requireNonNull(pending.placeAdmission(), "place admission receipt").matchingOrder(),
                    null, direct);
            case CANCEL -> {
                var command = pending.decodedCommand().cancelOrder();
                var route = java.util.Objects.requireNonNull(owner.activeOrderIndex.activeOrderRoute(command.orderId()),
                        "admitted cancellation route");
                yield pending.prepareCancelMatching(owner, shard, command.orderId(), userId, route.instrumentId(), direct);
            }
            case REPLACE, AMEND -> {
                var admission = owner.requireMatchingAdmission(pending);
                yield pending.prepareReplaceMatching(owner, shard, admission.resolved().orderId(), userId,
                        admission.originalOrderId(), admission.command().instrumentId(), admission.resolved(), direct);
            }
            case TRIGGER -> pending.preparePlaceMatching(owner, shard, userId, null,
                    java.util.Objects.requireNonNull(pending.admittedMatchingOrder(), "trigger admission receipt"), direct);
            default -> throw new IllegalStateException("unsupported trading operation " + pending.operation());
        };
    }

    private boolean isControlCommand(CommandSlot pending) {
        return pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT;
    }

    private Supplier<?> prepareControlCancellations(CommandSlot pending) {
        long logicalOrderId = 0;
        List<CancellationOrder> orders;
        switch (pending.operation()) {
            case LIQUIDATION -> {
                var command = pending.decodedCommand().liquidation();
                logicalOrderId = command.liquidationId();
                var liquidation = pending.liquidationCheck().liquidation(0);
                orders = liquidation == null || !pending.liquidationCheck().executable(0) ? List.of()
                        : lifecycleOrders(liquidation.userId(), owner.runtimeLiquidationSymbol(liquidation),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            case LIQUIDATION_BATCH -> orders = batchCancellationOrders(pending);
            case SETTLEMENT -> {
                var command = pending.decodedCommand().settlement();
                var progress = owner.runtimeLifecycleProgress(command.instrumentId());
                orders = progress != null && progress.ordersComplete() ? List.of()
                        : lifecycleOrders(0, command.instrumentId(),
                                command.cursorOrderId(), command.maxOrders()).orders();
            }
            default -> throw new IllegalStateException("unsupported cancellation control");
        }
        var prefix = preMatchingCancellationOrders(pending);
        if (!prefix.isEmpty()) {
            var combined = new ArrayList<CancellationOrder>(prefix.size() + orders.size());
            combined.addAll(prefix); combined.addAll(orders); orders = combined;
        }
        return pending.prepareControlMatching(owner, logicalOrderId, orders);
    }

    List<CancellationOrder> preMatchingCancellationOrders(CommandSlot pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<CancellationOrder> orders = new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            var order = owner.activeOrderIndex.activeOrderRoute(orderId);
            if (order != null) {
                orders.add(new CancellationOrder(order.orderId(), order.userId(), order.instrumentId()));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }
}
