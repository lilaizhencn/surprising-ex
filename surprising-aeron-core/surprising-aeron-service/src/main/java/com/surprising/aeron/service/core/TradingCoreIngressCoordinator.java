package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.result.CommandResultLog;
import com.surprising.aeron.service.result.FundsCommandIndex;
import com.surprising.aeron.service.order.DecodedOrderCommand;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCommand;
import com.surprising.aeron.service.order.OrderCommandQueue;
import com.surprising.aeron.service.settlement.TradeSettlement;
import java.nio.BufferUnderflowException;
import java.util.List;
import java.util.Objects;

/** 交易命令准入、幂等去重、直接控制命令收尾与撮合结算准备协调器。 */
final class TradingCoreIngressCoordinator {
    private final TradingCore core;

    TradingCoreIngressCoordinator(TradingCore core) {
        this.core = core;
    }

    boolean prepareClusterPipelineScope(CoreMessage message, OrderCommandQueue.IngressWindow window) {
        if (!core.activated()) core.activate();
        core.assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != core.productLine || user <= 0) {
            return rememberPipelineRoute(window, false);
        }
        window.resetCandidate(user);
        try {
            DecodedOrderCommand decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    var command = decoded.placeOrder();
                    var instrument = core.accountBook.instrument(command.instrumentId());
                    if (instrument != null && instrument.usesOpenInterestForUserLimit() && !command.reduceOnly())
                        return rememberPipelineRoute(window, false);
                    window.route(decoded.matcherShard(core.matchingAdapter, command.instrumentId()),
                            core.accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    long orderId = decoded.cancelOrder().orderId();
                    var route = core.bizIndex.activeOrders().activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.instrumentId() == null
                            || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                    window.route(core.matchingAdapter.matcherShardId(route.instrumentId()),
                            core.accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.placeOrderBatch().orders()) {
                        var instrument = core.accountBook.instrument(order.instrumentId());
                        if (instrument != null && instrument.usesOpenInterestForUserLimit() && !order.reduceOnly())
                            return rememberPipelineRoute(window, false);
                        int current = decoded.matcherShard(core.matchingAdapter, order.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        if (shard < 0) {
                            shard = current;
                            window.route(shard, core.accountBook.topology().accountLaneMask(user));
                        }
                    }
                    return rememberPipelineRoute(window, shard >= 0);
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.cancelOrderBatch().orders()) {
                        var route = core.bizIndex.activeOrders().activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.instrumentId() == null
                                || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                        int current = core.matchingAdapter.matcherShardId(route.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, core.accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (IllegalArgumentException | BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    private boolean rememberPipelineRoute(OrderCommandQueue.IngressWindow window, boolean eligible) {
        core.state.preparedRouteUserLaneBit = eligible ? window.routeUserLaneBit() : 0;
        core.state.preparedRouteMatcherShard = eligible ? window.routeMatcherShard() : -1;
        return eligible;
    }

    CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CommandFingerprint fingerprint = message == core.state.decodedIngressMessage
                && core.state.preparedIngressFingerprint != null
                ? core.state.preparedIngressFingerprint : core.fingerprints.of(message);
        CoreResponse replayResponse = checkCommandReplay(message, fingerprint);
        if (replayResponse != null) return replayResponse;

        long lastSourceSequence = core.sourceSequenceIndex.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        TradingCore.SourceKey sourceKey = core.sourceSequenceIndex.lastLookupKey();
        if (sourceKey == null) {
            sourceKey = new TradingCore.SourceKey(message.header().source(), message.header().sourceId());
        }
        if (TradingCore.isFundsIdempotencyCommand(message.header().messageType())) {
            CoreResponse fundsReplay = fundsReplayResponse(core.completedOrderArchive.fundsCommandMatch(
                    message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint));
            if (fundsReplay != null) return fundsReplay;
        }
        CoreResponse sourceSequenceResponse = checkSourceSequence(message, lastSourceSequence);
        if (sourceSequenceResponse != null) return sourceSequenceResponse;

        if (TradingCore.isFundsIdempotencyCommand(message.header().messageType())) {
            core.completedOrderArchive.reserveFundsCommandIdentity();
        }

        if (TradingCore.isMatchingCommand(message.header().messageType())) {
            if (core.pendingMatching.size() >= core.pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (TradingCore.isOrderBatchCommand(message.header().messageType())) {
                return core.batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition,
                        sourceKey, fingerprint);
            }
            return core.orderChecks.beginMatching(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint);
        }
        return applyDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
    }

    private CoreResponse applyDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                            TradingCore.SourceKey sourceKey, CommandFingerprint fingerprint) {
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        core.activateFactContext(message, fingerprint);
        long beforeRuntimeRevision = core.accountBook.revision();
        long positionIdentityCheckpoint = core.identities.positionCheckpoint();
        core.directCommand.initialize(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforeRuntimeRevision, beforeRuntimeRevision, positionIdentityCheckpoint);
        core.resultBuilder.beginCommand();
        core.orderChecks.clearQueuedMatching();
        core.commits.publication.begin();
        try {
            if (core.directCommands.dispatch(message, clusterTimestamp)) {
                status = ResponseStatus.APPLIED;
            } else {
                switch (message.header().messageType()) {
                    case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                            PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                            EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                            throw new IllegalStateException("matching command must use async continuation");
                    default -> status = null;
                }
            }
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (ArithmeticException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
        } catch (IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        if (core.directCommand.hasControlWork()) return null;
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforeRuntimeRevision, beforeRuntimeRevision, positionIdentityCheckpoint, status, resultCode);
    }

    private CoreResponse checkCommandReplay(CoreMessage message, CommandFingerprint fingerprint) {
        if (core.commandResultLog.isEmpty() && core.pendingMatching.isEmpty()) return null;
        long msb = message.header().commandIdMsb();
        long lsb = message.header().commandIdLsb();
        if (!core.commandResultLog.isEmpty()) {
            CommandResultLog.StoredResult terminalDuplicate = core.commandResultLog.get(msb, lsb);
            if (terminalDuplicate != null) {
                return core.commandResultLog.duplicateResponse(terminalDuplicate, fingerprint,
                        core.state.appliedCommandCount);
            }
        }
        if (!core.pendingMatching.isEmpty()) {
            OrderCommand pendingDuplicate = core.pendingMatching.findByCommandId(msb, lsb);
            if (pendingDuplicate != null) {
                if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.IDEMPOTENCY_CONFLICT, core.state.appliedCommandCount,
                            TradingCore.EMPTY_RESPONSE_DATA);
                }
                return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.OK,
                        CoreResultCode.MATCHING_PENDING, pendingDuplicate.sequence(),
                        TradingCore.EMPTY_RESPONSE_DATA);
            }
        }
        return null;
    }

    private CoreResponse fundsReplayResponse(FundsCommandIndex.Match match) {
        if (match == FundsCommandIndex.Match.ABSENT) return null;
        if (match == FundsCommandIndex.Match.CONFLICT) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, core.state.appliedCommandCount,
                    TradingCore.EMPTY_RESPONSE_DATA);
        }
        return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.APPLIED, CoreResultCode.NONE,
                core.state.appliedCommandCount, TradingCore.EMPTY_RESPONSE_DATA);
    }

    private CoreResponse checkSourceSequence(CoreMessage message, long lastSourceSequence) {
        if (lastSourceSequence >= 0 && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, core.state.appliedCommandCount);
        }
        if (lastSourceSequence < 0 && core.sourceSequenceIndex.size() >= TradingCore.Limits.MAX_SOURCE_SEQUENCES) {
            return core.rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        return null;
    }

    CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            TradingCore.SourceKey sourceKey, CommandFingerprint fingerprint,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(core.state.appliedCommandCount);
        if (!core.directCommand.finalizationPrepared()) {
            if (status != ResponseStatus.APPLIED
                    && (core.commits.publication.dirty()
                    || core.accountBook.revision() != beforeRuntimeRevision
                    || core.accountBook.hasUncommittedCommandChanges())) {
                core.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(core.state.appliedCommandCount));
            }
            if (status == null) {
                core.commits.publication.clearBatchState();
                return finishDirectCommandContext(core.rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                try {
                    if (!core.triggers.collectClosingTriggerIds().isEmpty()) {
                        core.commits.publication.request();
                    }
                } catch (ArithmeticException failure) {
                    if (deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW)) return null;
                    throw failure;
                }
                if (core.pendingMatching.size() + core.orderChecks.queuedMatchingCount()
                        > core.pendingMatching.capacity()) {
                    core.orderChecks.clearQueuedMatching();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    core.rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(core.state.appliedCommandCount));
                    return finishDirectCommandContext(core.rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            core.resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !core.resultBuilder.commandChangedUserIds.isEmpty()) {
                core.accountBook.releaseCompletedSequentialLaneStage();
                core.directCommand.commitEvent(core.accountBook.dispatchLaneMutation(
                        nextAppliedCommandCount, core.resultBuilder.commandChangedUserIds,
                        core.accountBook.metadataOrderUsers(core.resultBuilder.commandChangedOrderIds),
                        core.triggers.closingTriggerIds(), clusterTimestamp, clusterPosition));
            }
            core.directCommand.markFinalizationPrepared();
        }
        if (core.directCommand.commitEvent() != null) {
            if (!core.accountBook.laneCommitComplete(core.directCommand.commitEvent())) {
                core.directCommand.result(status, resultCode);
                return null;
            }
            core.accountBook.releaseLaneCommit(core.directCommand.commitEvent());
            core.directCommand.clearCommitEvent();
        }
        if (status == ResponseStatus.APPLIED) {
            core.accountBook.validateControlFunds(
                    message.header().messageType() == CoreMessageType.ADJUST_BALANCE
                    || message.header().messageType() == CoreMessageType.TRANSFER_OUT
                    || message.header().messageType() == CoreMessageType.TRANSFER_IN
                    || message.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND);
        }
        core.directCommand.clearFinalizationPrepared();
        core.commits.publication.complete();
        return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
    }

    private CoreResponse finishResponseAfterFundsRetention(CoreMessage message, long clusterTimestamp,
            long clusterPosition, TradingCore.SourceKey sourceKey, CommandFingerprint fingerprint,
            ResponseStatus status, CoreResultCode resultCode, long nextAppliedCommandCount) {
        if (TradingCore.isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            core.completedOrderArchive.retainFundsCommand(message.header().commandIdMsb(),
                    message.header().commandIdLsb(), fingerprint);
        }
        core.recordAppliedSequence(nextAppliedCommandCount, sourceKey, message.header().sourceSequence());
        core.orderChecks.appendQueuedMatching(clusterTimestamp, clusterPosition);
        byte[] responseData = core.resultBuilder.commandResultData();
        int responseOffset = core.resultBuilder.responseDataOffset();
        int responseLength = core.resultBuilder.responseDataLength();
        core.commandResultLog.storeOwnedResult(message.header().commandIdMsb(), message.header().commandIdLsb(),
                fingerprint, status, resultCode, core.state.appliedCommandCount, responseData,
                responseOffset, responseLength);
        CoreResponse response = message.ownedResponse(status, status, resultCode, core.state.appliedCommandCount,
                responseData, responseOffset, responseLength);
        core.resultBuilder.transferResponseOwnership();
        return finishDirectCommandContext(response);
    }

    private CoreResponse finishDirectCommandContext(CoreResponse response) {
        core.directCommand.clear();
        return core.finishFactContext(response);
    }

    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!core.directCommand.active()) {
            throw new IllegalStateException("asynchronous finalization has no command context");
        }
        core.directCommand.result(ResponseStatus.REJECTED, code);
        return true;
    }

    TradeSettlement prepareForMatching(OrderCommand pending) {
        if (pending.operation() == OrderCommand.Operation.REPLACE
                || pending.operation() == OrderCommand.Operation.AMEND) {
            return prepareReplacement(pending);
        }
        return switch (pending.operation()) {
            case PLACE -> preparePlace(pending);
            case TRIGGER -> prepareTrigger(pending);
            case CANCEL -> prepareCancellation(pending);
            default -> null;
        };
    }

    private TradeSettlement preparePlace(OrderCommand pending) {
        var orderCheck = Objects.requireNonNull(pending.placeOrderCheck(), "place admission receipt");
        TradeSettlement direct = core.accountBook.prepareDirectMatcherSettlement(
                pending.sequence(), directInitialLaneMask(orderCheck.userId()), orderCheck,
                pending.command().header().commandId(), core.matchingFlow.matcherShard(pending),
                core.identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                List.of(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    private TradeSettlement prepareReplacement(OrderCommand pending) {
        var orderCheck = core.requireMatchingOrderCheck(pending);
        TradeSettlement direct = core.accountBook.prepareDirectReplacement(
                pending.sequence(), pending.sequence(), directInitialLaneMask(orderCheck.userId()), orderCheck,
                pending.command().header().commandId(), core.matchingFlow.matcherShard(pending), core.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        if (core.state.realtimeCapture != null) pending.realtimeTakerOrder(direct.admittedOrder());
        return direct;
    }

    private TradeSettlement prepareTrigger(OrderCommand pending) {
        long[] execute = pending.decodedCommand().trigger();
        var trigger = Objects.requireNonNull(pending.sourceTrigger(), "admitted trigger is missing");
        Order triggerOrder = Objects.requireNonNull(pending.triggerOrderCheckOrder(), "trigger admission receipt is missing");
        TradeSettlement direct = core.accountBook.prepareDirectMatcherSettlement(pending.sequence(),
                directInitialLaneMask(triggerOrder.userId()), triggerOrder, null, 1,
                pending.command().header().commandId(), core.matchingFlow.matcherShard(pending), core.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        direct.triggerCompletion(trigger, execute[3]);
        reserveMatcherPublication(pending, direct);
        if (core.state.realtimeCapture != null) pending.realtimeTakerOrder(triggerOrder);
        return direct;
    }

    private TradeSettlement prepareCancellation(OrderCommand pending) {
        TradeSettlement direct = core.accountBook.prepareDirectCancellation(pending.sequence(),
                pending.command().header().userId(), pending.decodedCommand().cancelOrder().orderId(),
                pending.command().header().commandId(), core.matchingFlow.matcherShard(pending), core.identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    private void reserveMatcherPublication(OrderCommand pending, TradeSettlement direct) {
        pending.settlement(direct, direct.plan());
        direct.reserveMatcherPublication();
    }

    private long directInitialLaneMask(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("direct settlement user is required");
        return core.accountBook.topology().accountLaneMask(userId);
    }
}
