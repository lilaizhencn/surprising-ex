package com.surprising.aeron.service.orchestration;

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
            long sequence = Math.incrementExact(owner.appliedCommandCount);
            CommandSlot pending = owner.admissions.newPendingMatching(sequence,
                    MatchingCommandAdmission.matchingOperation(message.header().messageType()),
                    message, fingerprint);
            putPendingMatching(pending);
            owner.laneCommandContexts.required(sequence).rejectMatching(resultCode);
            owner.pendingMatching.completeSubmission(sequence);
            owner.appliedCommandCount = sequence;
            owner.recordSourceSequence(sourceKey, message.header().sourceSequence());
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK,
                    TradingCoreRuntime.matchingPendingCode(), sequence,
                    TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        }
        long sequence = Math.incrementExact(owner.appliedCommandCount);
        owner.appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        owner.lastSourceSequences.put(sourceKey, message.header().sourceSequence());
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
        long candidate = owner.pendingMatching.isEmpty()
                ? owner.appliedCommandCount
                : Math.subtractExact(owner.pendingMatching.firstSequence(), 1);
        if (candidate > owner.committedCoreSequence) owner.committedCoreSequence = candidate;
    }

    void commitMatchingSequence(long sequence) {
        if (!owner.pendingMatching.contains(sequence) || sequence <= owner.committedCoreSequence) {
            throw new IllegalStateException("matching sequence is not pending above the committed watermark");
        }
    }

    void putPendingMatching(CommandSlot pending) {
        pending.clusterIndependent = owner.commandIngress.clusterPipelineAdmission();
        if (owner.pendingMatching.size() >= owner.pendingMatching.capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        try {
            if (owner.commandIngress.clusterPipelineAdmission()
                    && owner.commandIngress.preparedRouteUserLaneBit() != 0
                    && owner.commandIngress.preparedRouteMatcherShard() >= 0) {
                pending.partitionLaneMask = owner.commandIngress.preparedRouteUserLaneBit();
                pending.cachedMatcherShard(owner.commandIngress.preparedRouteMatcherShard());
            } else {
                pending.partitionLaneMask = 0;
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
        String symbol = pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT
                ? owner.admissions.pendingLifecycleSymbol(pending)
                : owner.admissions.matchingSymbol(pending.command(), pending.operation(), pending.decodedCommand());
        int shard = symbol == null || symbol.isBlank() ? 0 : owner.matchingAdapter.matcherShardId(symbol);
        pending.cachedMatcherShard(shard);
        return shard;
    }

    TradingCoreRuntime.LifecycleOrderChunk lifecycleOrders(
            long userId, String symbol, long cursorOrderId, int maxOrders) {
        var page = owner.activeOrderIndex.page(userId, symbol, cursorOrderId, maxOrders);
        List<CoreOrderState> selected = page.orderIds().stream()
                .map(owner.runtimeState::order)
                .filter(order -> order != null && order.status() == CoreOrderStatus.OPEN)
                .map(order -> RuntimeStateMaterializer.orderSnapshot(order, owner.identities))
                .toList();
        return new TradingCoreRuntime.LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    List<CoreOrderState> batchCancellationOrders(CommandSlot pending) {
        var command = pending.decodedCommand().liquidationBatch();
        List<CoreOrderState> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            if (remaining == 0) break;
            var liquidation = owner.runtimeState.liquidation(action.liquidationId());
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
        if (pending.crossShardCancellationStarted || matchingSubmissionDeferred(pending.sequence())) return;
        pending.prepareLaneResultTarget(owner.responseArena);
        if (pending.placeAdmission() != null && !owner.runtimeState.asynchronousCommands()) {
            if (!collectPlaceAdmissionIfReady(pending)
                    || hasPendingMatchingRejection(pending.sequence())) return;
        }
        if (pending.orderBatch != null) {
            owner.batches.submitOrderBatchMatching(pending);
            if (pending.isMatchingSubmitted()) matchingSubmissionCompleted(pending);
            return;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                && owner.matchingAdapter.topology().matchingEngineCount() > 1) {
            List<CoreOrderState> orders = batchCancellationOrders(pending);
            int shardId = singleMatcherShard(orders);
            if (shardId == -2) {
                owner.crossShardCancellations.start(pending, orders);
                return;
            }
            owner.matcherPipeline.submit(shardId < 0 ? matcherShard(pending) : shardId,
                    pending.sequence(), owner.matcherCommands.prepareMatchingCommand(pending, null));
            pending.matchingSubmitted();
            matchingSubmissionCompleted(pending);
            return;
        }
        var direct = owner.directMatcherSettlements.prepareForMatching(pending);
        var command = owner.matcherCommands.prepareMatchingCommand(pending, direct);
        java.util.function.Supplier<?> matcherSubmission = command;
        if (direct != null && pending.placeAdmission() != null) {
            matcherSubmission = pending.gateAdmission(owner, pending.placeAdmission(), matcherShard(pending),
                    direct, matcherSubmission);
        }
        owner.matcherPipeline.submit(matcherShard(pending), pending.sequence(), matcherSubmission, direct);
        pending.matchingSubmitted();
        if (pending.placeAdmission() == null) matchingSubmissionCompleted(pending);
        if (direct != null && !direct.matcherOwnedPublication()) {
            predispatchDirectSettlement(pending, direct);
        }
    }

    boolean predispatchDirectSettlement(CommandSlot pending,
                                        com.surprising.aeron.service.state.MatcherSettlementEvent event) {
        if (pending == null || event == null || !event.direct() || event.dispatched()) return false;
        if (!pending.commitFenceEstablished()) return false;
        int shard = pendingSubmissionShard(pending);
        owner.pendingMatching.readyPartitionMask(pending.sequence());
        if (owner.pendingMatching.readyPartitionHead(shard) != pending) return false;
        pending.establishCommitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
        owner.runtimeState.dispatchOwnerControlledSettlement(event);
        owner.pendingMatching.completePartitionDispatchKnown(pending.sequence(), shard);
        owner.pendingMatching.progressChanged();
        pending.countPipelinedSettlement();
        owner.commits.dispatchedSettlementInFlight++;
        owner.commits.dispatchedSettlementHighWaterMark = Math.max(
                owner.commits.dispatchedSettlementHighWaterMark,
                owner.commits.dispatchedSettlementInFlight);
        return true;
    }

    int singleMatcherShard(List<CoreOrderState> orders) {
        int shardId = -1;
        for (CoreOrderState order : orders) {
            int orderShard = owner.matchingAdapter.matcherShardId(order.symbol());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    void progressPlaceBatchAdmissions() { owner.matchingProgress.progressPlaceBatchAdmissions(); }
    void drainPlaceAdmissionNotifications() { owner.matchingProgress.drainPlaceAdmissionNotifications(); }
    boolean matchingSubmissionDeferred(long sequence) {
        return owner.matchingProgress.submissionDeferred(sequence);
    }
    void matchingSubmissionCompleted(CommandSlot pending) {
        owner.matchingProgress.submissionCompleted(pending);
    }
    int pendingSubmissionShard(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : owner.batches.orderBatchMatcherShard(batch);
    }
    void submitDeferredMatchingAfterBatch() { owner.matchingProgress.submitDeferredMatchingAfterBatch(); }

    void publishMatchingCompletion(long sequence, CoreMatchingResult result) {
        if (result == null || result.nativeCoreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        owner.laneCommandContexts.required(sequence).publishMatchingCompletion(result);
        owner.pendingMatching.progressChanged();
    }

    PlaceOrderCommand replacementFor(CoreMessage message, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String symbol = owner.runtimeOrderSymbol(order);
        if (owner.runtimeState.instrument(symbol) == null) {
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
        String symbol = owner.runtimeOrderSymbol(order);
        if (owner.runtimeState.instrument(symbol) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), symbol,
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(),
                order.positionSide(), order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
            long triggeredPriceTicks) {
        Long clientKey = owner.identities.findClientKey(trigger.userId(), "TRIGGER:" + trigger.triggerOrderId());
        Long orderId = clientKey == null ? null : owner.runtimeState.orderIdByClient(trigger.userId(), clientKey);
        OrderRuntime order = orderId == null ? null : owner.runtimeState.order(orderId);
        return triggerPlacement(trigger, triggeredPriceTicks, order);
    }

    PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
            long triggeredPriceTicks, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "trigger child order not found");
        if (owner.runtimeState.instrument(trigger.symbol()) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        long limitPriceTicks = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (order.priceTicks() > 0 ? order.priceTicks() : triggeredPriceTicks) : 0;
        return new PlaceOrderCommand(order.orderId(), trigger.symbol(),
                trigger.side(), limitPriceTicks, order.quantitySteps(), order.reduceOnly(),
                trigger.marginMode(), trigger.positionSide(), trigger.orderType(), trigger.timeInForce(),
                false, order.clientOrderId());
    }

    long currentMarkPriceTicks(String symbol, long referencePriceTicks) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        var markPrice = symbolId == null ? null : owner.runtimeState.markPrice(symbolId);
        long value = markPrice == null ? referencePriceTicks : markPrice.markPriceTicks();
        if (value <= 0) throw new CoreStateRejectedException("MARK_PRICE_MISSING", "mark price is required");
        return value;
    }

    MatchingResult takeMatchingResult(long sequence) {
        if (owner.fatalFailure != null || !owner.pendingMatching.contains(sequence)) return null;
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending == null) return null;
        CommandSlot context = owner.laneCommandContexts.required(sequence);
        if (context.matchingResult() != null) return context.matchingResult();
        var direct = pending.settlementEvent();
        if (direct == null && pending.orderBatch != null) {
            direct = pending.orderBatch.itemSettlementEvent != null
                    ? pending.orderBatch.itemSettlementEvent : pending.orderBatch.settlementEvent;
        }
        if (direct != null && direct.direct()) return direct.ready() ? direct.directResult() : null;
        return context.takeMatchingCompletion();
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
                if (owner.realtimeCapture != null) pending.realtimeResolvedTakerOrder = admitted;
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
            matchingSubmissionCompleted(pending);
        }
    }

    boolean hasPendingMatchingRejection(long sequence) {
        return owner.laneCommandContexts.claimed(sequence)
                && owner.laneCommandContexts.required(sequence).hasMatchingRejection();
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
}
