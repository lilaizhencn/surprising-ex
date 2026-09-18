package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;

/**
 * 集群日志入口的业务边界。
 *
 * <p>这里按固定顺序完成产品线/查询闸门、幂等和来源序号检查，再把命令交给
 * 撮合准入或直接命令终结流程。它不持有交易状态副本；状态仍归
 * {@link TradingCoreRuntime}，从而恢复、快照和 Lane 所有权只有一个来源。</p>
 */
final class CoreCommandIngress {

    private final TradingCoreRuntime runtime;
    private final CoreDirectCommandFlow directCommands;

    private boolean clusterPipelineAdmission;
    private CoreMessage decodedIngressMessage;
    private DecodedMatchingCommand decodedIngressCommand;
    private CommandFingerprint preparedIngressFingerprint;
    private long preparedRouteUserLaneBit;
    private int preparedRouteMatcherShard = -1;

    CoreCommandIngress(TradingCoreRuntime runtime, CoreDirectCommandFlow directCommands) {
        this.runtime = runtime;
        this.directCommands = directCommands;
    }

    CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!runtime.activated()) runtime.activate();
        runtime.assertOwner();
        if (runtime.directCommand.active()) {
            throw new IllegalStateException("asynchronous control command is still active");
        }
        runtime.admissionPreviousClusterTimestamp = runtime.currentClusterTimestamp;
        runtime.admissionPreviousClusterPosition = runtime.currentClusterPosition;
        runtime.currentClusterTimestamp = clusterTimestamp;
        runtime.currentClusterPosition = clusterPosition;
        runtime.assertHealthy();
        if (runtime.snapshots.snapshotFence != null
                && runtime.snapshots.snapshotFence.encodedSnapshot == null) {
            throw new IllegalStateException("snapshot fence is active");
        }
        if (!runtime.pendingMatching.isEmpty()
                && !TradingCoreRuntime.isCommitCursorSafeWhileMatching(message)) {
            long userId = message.header().userId();
            if (userId <= 0 || runtime.pendingMatching.hasUser(userId)) {
                throw new IllegalStateException("command or query crossed its account-lane matching cursor");
            }
        }
        if (message.header().productLine() != runtime.productLine) {
            return runtime.rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().messageType() == CoreMessageType.ACK_EXPORT
                || message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY
                || message.header().messageType() == CoreMessageType.EXPORT_STATUS_QUERY) {
            return runtime.rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && TradingCoreRuntime.accountLaneReadQuery(message.header().messageType())) {
            if (TradingCoreRuntime.singleUserLaneQuery(message.header().messageType())
                    && message.header().userId() > 0) {
                runtime.runtimeState.readFence(message.header().userId(), runtime.committedCoreSequence);
            } else {
                runtime.runtimeState.readFenceAll(runtime.committedCoreSequence);
            }
        }
        CoreResponse queryResponse = runtime.queryRouter.apply(message, clusterTimestamp);
        if (queryResponse != null) return queryResponse;
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return runtime.rejected(CoreResultCode.INVALID_MESSAGE);
        }
        return applyCommandIngress(message, clusterTimestamp, clusterPosition);
    }

    boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        return switch (message.header().messageType()) {
            case PROBE_INCREMENT, VERIFY_STATE_HASH, UPDATE_CANCEL_ALL_AFTER, ACK_EXPORT -> false;
            case UPSERT_ALGO_ORDER, EXECUTE_TRIGGER_ORDER -> false;
            case PLACE_TRIGGER_ORDER, CANCEL_TRIGGER_ORDER, CLAIM_TRIGGER_ORDER, COMPLETE_TRIGGER_ORDER,
                    UPDATE_TRIGGER_TRAILING, EXPIRE_TRIGGER_ORDER, RETRY_TRIGGER_ORDER,
                    ADJUST_BALANCE, TRANSFER_IN, TRANSFER_OUT, COMPLETE_TRANSFER, UPDATE_LEVERAGE,
                    UPDATE_POSITION_MODE, ADJUST_POSITION_MARGIN, APPLY_FUNDING, APPLY_MARK_PRICE,
                    UPDATE_RISK_SCAN_CONTROL, ADJUST_INSURANCE_FUND, UPSERT_INSTRUMENT,
                    UPDATE_INSTRUMENT_MAINTENANCE, UPSERT_FEE_POLICY -> false;
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER, CANCEL_ORDER_BATCH -> false;
            case CONTINUE_RISK_SCAN, AMEND_ORDER_BATCH, PLACE_ORDER_BATCH, EXECUTE_ADL,
                    RESOLVE_LIQUIDATION, EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH,
                    SETTLE_INSTRUMENT -> false;
            default -> true;
        };
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        boolean entered = !runtime.runtimeState.asynchronousCommands();
        if (entered) runtime.runtimeState.enterAsynchronousCommandScope();
        try {
            return applyDecodedCommand(message, timestamp, position, decoded, true);
        } finally {
            if (entered) runtime.runtimeState.exitAsynchronousCommandScope();
        }
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent) {
        return applyDecodedCommand(message, timestamp, position, decoded, independent, null);
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent,
                                     CommandFingerprint fingerprint) {
        CommandFingerprint previousFingerprint = preparedIngressFingerprint;
        preparedIngressFingerprint = fingerprint;
        boolean previousAdmission = clusterPipelineAdmission;
        CoreMessage previousMessage = decodedIngressMessage;
        DecodedMatchingCommand previousCommand = decodedIngressCommand;
        clusterPipelineAdmission = independent;
        decodedIngressMessage = message;
        decodedIngressCommand = decoded;
        try {
            return apply(message, timestamp, position);
        } finally {
            preparedIngressFingerprint = previousFingerprint;
            clusterPipelineAdmission = previousAdmission;
            decodedIngressMessage = previousMessage;
            decodedIngressCommand = previousCommand;
        }
    }

    DecodedMatchingCommand decodeMatchingCommand(CoreMessage message) {
        return message == decodedIngressMessage && decodedIngressCommand != null
                ? decodedIngressCommand : DecodedMatchingCommand.decode(message);
    }

    boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        if (!runtime.activated()) runtime.activate();
        runtime.assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != runtime.productLine || user <= 0) {
            return rememberPipelineRoute(window, false);
        }
        window.resetCandidate(user);
        try {
            DecodedMatchingCommand decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    var command = decoded.placeOrder();
                    window.route(decoded.matcherShard(runtime.matchingAdapter, command.symbol()),
                            runtime.runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    long orderId = decoded.cancelOrder().orderId();
                    var route = runtime.activeOrderIndex.activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.symbol() == null
                            || route.symbol().isBlank()) return rememberPipelineRoute(window, false);
                    window.route(runtime.matchingAdapter.matcherShardId(route.symbol()),
                            runtime.runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.placeOrderBatch().orders()) {
                        int current = decoded.matcherShard(runtime.matchingAdapter, order.symbol());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        if (shard < 0) {
                            shard = current;
                            window.route(shard, runtime.runtimeState.topology().accountLaneMask(user));
                        }
                    }
                    return rememberPipelineRoute(window, shard >= 0);
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.cancelOrderBatch().orders()) {
                        var route = runtime.activeOrderIndex.activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.symbol() == null
                                || route.symbol().isBlank()) return rememberPipelineRoute(window, false);
                        int current = runtime.matchingAdapter.matcherShardId(route.symbol());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, runtime.runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    private boolean rememberPipelineRoute(ClusterCommandWindow window, boolean eligible) {
        preparedRouteUserLaneBit = eligible ? window.routeUserLaneBit() : 0;
        preparedRouteMatcherShard = eligible ? window.routeMatcherShard() : -1;
        return eligible;
    }

    boolean clusterPipelineAdmission() {
        return clusterPipelineAdmission;
    }

    long preparedRouteUserLaneBit() {
        return preparedRouteUserLaneBit;
    }

    int preparedRouteMatcherShard() {
        return preparedRouteMatcherShard;
    }

    private CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CommandFingerprint fingerprint = message == decodedIngressMessage && preparedIngressFingerprint != null
                ? preparedIngressFingerprint : CommandFingerprint.of(message);
        CoreResponse replayResponse = checkCommandReplay(message, fingerprint);
        if (replayResponse != null) return replayResponse;

        long lastSourceSequence = runtime.lastSourceSequences.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        TradingCoreRuntime.SourceKey sourceKey = runtime.lastSourceSequences.lastLookupKey();
        if (sourceKey == null) {
            sourceKey = new TradingCoreRuntime.SourceKey(message.header().source(), message.header().sourceId());
        }
        CoreResponse sourceSequenceResponse = checkSourceSequence(message, lastSourceSequence);
        if (sourceSequenceResponse != null) return sourceSequenceResponse;

        if (TradingCoreRuntime.isMatchingCommand(message.header().messageType())) {
            if (runtime.pendingMatching.size() >= runtime.pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (TradingCoreRuntime.isOrderBatchCommand(message.header().messageType())) {
                return runtime.batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition,
                        sourceKey, fingerprint);
            }
            return runtime.admissions.beginMatching(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint);
        }
        return directCommands.apply(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
    }

    private CoreResponse checkCommandReplay(CoreMessage message, CommandFingerprint fingerprint) {
        StoredResult terminalDuplicate = runtime.resultLedger.get(message.header().commandId());
        if (terminalDuplicate != null) {
            return runtime.resultLedger.duplicateResponse(terminalDuplicate, fingerprint,
                    runtime.appliedCommandCount, runtime.stateHash());
        }
        CommandSlot pendingDuplicate = runtime.pendingMatching.findByCommandId(message.header().commandId());
        if (pendingDuplicate != null) {
            if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                        com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                        CoreResultCode.IDEMPOTENCY_CONFLICT, runtime.appliedCommandCount, 0,
                        runtime.stateHash(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
            }
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                    pendingDuplicate.sequence(), 0, pendingDuplicate.pendingStateHash(),
                    TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        }
        if (TradingCoreRuntime.isFundsIdempotencyCommand(message.header().messageType())) {
            CommandFingerprint retained = runtime.terminalRetention.fundsCommand(message.header().commandId());
            if (retained != null) {
                if (!retained.equals(fingerprint)) {
                    return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            CoreResultCode.IDEMPOTENCY_CONFLICT, runtime.appliedCommandCount, 0,
                            runtime.stateHash(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
                }
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                        com.surprising.aeron.protocol.ResponseStatus.APPLIED, CoreResultCode.NONE,
                        runtime.appliedCommandCount, 0, runtime.stateHash(), TradingCoreRuntime.EMPTY_RESPONSE_DATA);
            }
            if (!runtime.terminalRetention.hasFundsCommandCapacity(message.header().commandId())) {
                return runtime.rejected(CoreResultCode.FUNDS_IDEMPOTENCY_RETENTION_FULL);
            }
        }
        return null;
    }

    private CoreResponse checkSourceSequence(CoreMessage message, long lastSourceSequence) {
        if (lastSourceSequence >= 0 && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, runtime.appliedCommandCount, runtime.stateHash());
        }
        if (lastSourceSequence < 0
                && runtime.lastSourceSequences.size() >= TradingCoreRuntime.MAX_SOURCE_SEQUENCES) {
            return runtime.rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        return null;
    }
}
