package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.PositionCloseCapacity;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.RiskScanRuntime;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimeStateProjector;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.RuntimePerpetualLiquidationProcessor;
import com.surprising.aeron.service.state.RuntimePerpetualRiskProcessor;
import com.surprising.aeron.service.state.RuntimeSettlementProcessor;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.StateMapSupport;
import com.surprising.aeron.service.state.TerminalPruneBatch;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.BookBootstrapSnapshot;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class CoreProbeState implements AutoCloseable {

    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    static final long MAX_RESULT_LEDGER_BYTES = 32L * 1024 * 1024;
    static final int MAX_STORED_RESPONSE_BYTES = Math.toIntExact(MAX_RESULT_LEDGER_BYTES);
    static final int MAX_SOURCE_SEQUENCES = 65_536;
    static final int MAX_BOOK_RESPONSE_LEVELS = 10_000;
    static final int MAX_BOOK_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_BOOK_BOOTSTRAP_SNAPSHOTS = 4;
    private static final int DEFAULT_TRIGGER_SCAN_BATCH_SIZE = 2;
    private static final System.Logger LOG = System.getLogger(CoreProbeState.class.getName());
    private static final boolean BENCHMARK_SKIP_MATCHING_SUBMIT = Boolean.getBoolean(
            "surprising.aeron.benchmark.skip-matching-submit");
    private static final int MATCHING_PHASE_LOG_INTERVAL = Integer.getInteger(
            "surprising.aeron.matching-phase-log-interval", 100);
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final long RESULT_LEDGER_POSITION_BASE = 0x9e3779b97f4a7c15L;
    private static final int MATCHING_PENDING_WIRE_CODE = 66;
    static final int MAX_PENDING_MATCHING = Integer.getInteger(
            "surprising.aeron.max-pending-matching", 4_096);
    private static final int MAX_MATCHING_COMPLETIONS = MAX_PENDING_MATCHING;
    private final ProductLine productLine;
    private final TradingCoreRuntime runtime;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private final LinkedHashMap<UUID, Long> commandResultContributions;
    private long commandResultBytes;
    private long commandResultsDigest;
    private long nextResultRetentionSequence;
    private long nextResultRetentionWeight;
    private long appliedMatcherSequence;
    private long appliedMatcherPrefixDigest;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final LinkedHashMap<Long, PendingMatching> pendingMatching;
    private final Map<UUID, Long> pendingMatchingByCommandId;
    private final LinkedHashMap<Long, List<LifecycleScope>> pendingLifecycleScopes;
    private final LinkedHashMap<Long, OrderBatchPending> pendingOrderBatches;
    private final LinkedHashMap<Long, DeferredMatching> deferredMatching;
    private final LinkedHashMap<Long, CoreResultCode> pendingMatchingRejections = new LinkedHashMap<>();
    private final List<CoreMessage> queuedMatching = new ArrayList<>();
    private final Map<Long, com.surprising.aeron.service.matching.CoreMatchingResult> completedMatching
            = new LinkedHashMap<>();
    private final Map<Long, CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
            matchingFutures = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
            activeMatchingSubmissions = ConcurrentHashMap.newKeySet();
    private final MatchingCompletionQueue matchingCompletions;
    private final LaneCommandContextRing laneCommandContexts;
    private final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    private final MatcherSnapshotCapture matcherSnapshotCapture;
    private final AtomicReference<CompletableFuture<MatcherSnapshot>> inFlightMatcherSnapshot =
            new AtomicReference<>();
    private final PositionUserIndex positionUserIndex;
    private final OpenInterestIndex openInterestIndex;
    private final TriggerOrderIndex triggerOrderIndex;
    private final AlgoOrderIndex algoOrderIndex;
    private final LiquidationIndex liquidationIndex;
    private final CancelAllAfterIndex cancelAllAfterIndex;
    private final ActiveOrderIndex activeOrderIndex;
    private final AdlPositionIndex adlPositionIndex;
    private final RiskSnapshotIndex riskSnapshotIndex;
    private final CoreExportState exportState;
    private final CoreMatchingPhaseMetrics matchingPhaseMetrics = new CoreMatchingPhaseMetrics();
    private final Map<Long, Long> matchingSubmitNanos = new ConcurrentHashMap<>();
    private long completedMatchingCount;
    private final TerminalStateRetention terminalRetention;
    private final com.surprising.aeron.service.state.RollingBusinessStateHash rollingBusinessStateHash;
    private final com.surprising.aeron.service.state.RollingFundsStateHash rollingFundsStateHash;
    private long appliedCommandCount;
    private long committedCoreSequence;
    private long probeValue;
    private long cachedBusinessStateHash;
    private long cachedFeePolicyHash;
    private long cachedTransferHash;
    private long lastSourceSequenceDigest;
    private long nextAsyncQueryId = Long.MIN_VALUE;
    private RuntimeIdentityRegistry runtimePlaceOrderIdentities;
    private TradingRuntimeState runtimePlaceOrderState;
    private boolean snapshotProjectionDeferred;
    private boolean snapshotProjectionDirty;
    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException fatalFailure;
    private SnapshotFence snapshotFence;
    private TradingCoreState snapshotState;
    private List<CoreOrderStateView> commandOrderViews = List.of();
    private List<Long> commandChangedUserIds;
    private List<Long> commandChangedOrderIds;
    private List<Long> commandChangedLiquidationIds;
    private List<Long> commandChangedTriggerOrderIds;
    private List<String> commandChangedTreasuryAssets;
    private final LinkedHashSet<Long> changedUserIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> changedOrderIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> changedLiquidationIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> changedTriggerOrderIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> changedTreasuryAssets = new LinkedHashSet<>();
    private List<com.surprising.aeron.protocol.CoreExecutionView> commandExecutions = List.of();
    private List<com.surprising.aeron.protocol.CoreFundingPaymentView> commandFundingPayments = List.of();
    private CoreFundingProgressView commandFundingProgress;
    private CoreLiquidationProgressView commandLiquidationProgress;
    private CoreLiquidationBatchResultView commandLiquidationBatchResult;
    private CoreSettlementProgressView commandSettlementProgress;
    private CoreRiskScanControlView commandRiskScanControl;
    private com.surprising.aeron.protocol.CoreTriggerOrderStateView commandTriggerOrderView;
    private CoreCommandDelta commandDelta = CoreCommandDelta.empty();
    private long commandBeforeBusinessStateHash;
    private long commandBeforeFundsStateHash;
    private com.surprising.aeron.protocol.CoreMatcherTransition commandMatcherTransition;
    private long currentClusterPosition;
    private long currentClusterTimestamp;

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new CoreExportState(), new TerminalStateRetention(), null, null);
    }

    CoreProbeState(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new CoreExportState(), new TerminalStateRetention(), null,
                matcherSnapshotCapture);
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            MatcherSnapshotCapture matcherSnapshotCapture) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.commandResultContributions = resultLedgerContributions(commandResults);
        this.commandResultBytes = resultLedgerBytes(commandResults);
        this.commandResultsDigest = resultLedgerDigest(commandResultContributions);
        this.nextResultRetentionSequence = nextRetentionSequence(commandResults);
        this.nextResultRetentionWeight = retentionWeight(nextResultRetentionSequence);
        this.lastSourceSequences = lastSourceSequences;
        this.pendingMatching = new LinkedHashMap<>();
        this.pendingMatchingByCommandId = new HashMap<>();
        this.pendingLifecycleScopes = new LinkedHashMap<>();
        this.pendingOrderBatches = new LinkedHashMap<>();
        this.deferredMatching = new LinkedHashMap<>();
        this.snapshotState = snapshotState;
        this.rollingBusinessStateHash = com.surprising.aeron.service.state.RollingBusinessStateHash.create(snapshotState);
        this.rollingFundsStateHash = com.surprising.aeron.service.state.RollingFundsStateHash.create(snapshotState);
        this.cachedBusinessStateHash = rollingBusinessStateHash.value();
        this.lastSourceSequenceDigest = sourceSequenceDigest(lastSourceSequences);
        this.appliedMatcherSequence = matcherSnapshot == null ? 0 : matcherSnapshot.matcherSequence();
        this.appliedMatcherPrefixDigest = matcherSnapshot == null
                ? com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix.initialDigest()
                : matcherSnapshot.matcherPrefixDigest();
        this.exportState = exportState;
        this.terminalRetention = terminalRetention;
        this.runtime = new TradingCoreRuntime(
                productLine, snapshotState, appliedCommandCount, matcherSnapshot);
        this.matchingAdapter = runtime.matcherForConstruction();
        this.matchingCompletions = new MatchingCompletionQueue(
                matchingAdapter.topology().matchingCompletionCapacity());
        this.laneCommandContexts = new LaneCommandContextRing(
                matchingAdapter.topology().matcherWindowSize(),
                matchingAdapter.topology().accountLaneCount());
        this.matcherSnapshotCapture = matcherSnapshotCapture == null
                ? matchingAdapter::snapshotAsync
                : matcherSnapshotCapture;
        this.positionUserIndex = runtime.positionUsersForConstruction();
        this.openInterestIndex = runtime.openInterestForConstruction();
        this.triggerOrderIndex = runtime.triggersForConstruction();
        this.algoOrderIndex = runtime.algosForConstruction();
        this.liquidationIndex = runtime.liquidationsForConstruction();
        this.cancelAllAfterIndex = runtime.timersForConstruction();
        this.activeOrderIndex = runtime.activeOrdersForConstruction();
        this.adlPositionIndex = runtime.adlPositionsForConstruction();
        this.riskSnapshotIndex = runtime.riskSnapshotsForConstruction();
        this.runtimePlaceOrderIdentities = runtime.identitiesForConstruction();
        this.runtimePlaceOrderState = runtime.runtimeStateForConstruction();
        this.cachedFeePolicyHash = 0;
        this.cachedTransferHash = 0;
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState) {
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                snapshotState, exportState, new TerminalStateRetention(), null);
    }

    private static CoreProbeState restoreInternal(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot) {
        if (appliedCommandCount < 0 || commandResults == null || commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || lastSourceSequences == null || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || snapshotState == null || snapshotState.productLine() != productLine || exportState == null
                || terminalRetention == null) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        validateResultLedger(commandResults);
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, exportState, terminalRetention, matcherSnapshot, null);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot) {
        if (matcherSnapshot == null || snapshotState == null || snapshotState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid paired matcher snapshot");
        }
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                snapshotState, exportState, new TerminalStateRetention(), matcherSnapshot);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot) {
        if (matcherSnapshot == null || snapshotState == null || snapshotState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid paired matcher snapshot");
        }
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                snapshotState, exportState, terminalRetention, matcherSnapshot);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        currentClusterTimestamp = clusterTimestamp;
        currentClusterPosition = clusterPosition;
        assertHealthy();
        if (snapshotFence != null) throw new IllegalStateException("snapshot fence is active");
        if (pendingMatching.isEmpty() || !isCommittedExportQuery(message)) {
            ensureRuntimePlaceOrderState();
        }
        if (!pendingMatching.isEmpty() && !isCommitCursorSafeWhileMatching(message)) {
            throw new IllegalStateException("command or query crossed the global matching commit cursor");
        }
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && accountLaneReadQuery(message.header().messageType())) {
            if (singleUserLaneQuery(message.header().messageType()) && message.header().userId() > 0) {
                runtimePlaceOrderState.readFence(message.header().userId(), committedCoreSequence);
            } else {
                runtimePlaceOrderState.readFenceAll(committedCoreSequence);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadUnsafe().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreLaneMetricsCodec.encode(laneMetricsView()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_HASH_QUERY) {
            var query = com.surprising.aeron.service.state.RuntimeStateQueryService.userState(
                    runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId());
            if (query.tooLarge()) return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                    query.found() ? query.stateHash() : 0);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                StoredResult result = commandResults.get(commandId);
                if (result == null) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION, appliedCommandCount,
                            0, cachedBusinessStateHash, new byte[0]);
                }
                return new CoreResponse(ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(), result.requiredExportSequence(), result.stateHash(),
                        result.responseData());
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_HASH_QUERY) {
            try {
                var query = com.surprising.aeron.service.state.RuntimeStateQueryService.orderState(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()));
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                        query.found() ? query.stateHash() : 0);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_QUERY) {
            return userStateResponse(message.header().userId());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_QUERY) {
            try {
                return orderStateResponse(TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CLIENT_ORDER_STATE_QUERY) {
            try {
                var query = com.surprising.aeron.service.state.RuntimeStateQueryService.clientOrderState(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId(),
                        CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()));
                return orderStateResponse(query);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            try {
                var query = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
                long beforeOrderId = query.beforeOrderId() == 0 ? Long.MAX_VALUE : query.beforeOrderId();
                long requestedUserId = message.header().userId();
                var page = activeOrderIndex.page(requestedUserId, query.symbol(), beforeOrderId, query.limit());
                var orders = page.orderIds().stream()
                        .map(orderId -> com.surprising.aeron.service.state.RuntimeStateQueryService.orderState(
                                runtimePlaceOrderState, runtimePlaceOrderIdentities, orderId))
                        .filter(com.surprising.aeron.service.state.RuntimeStateQueryService.OrderQueryResult::found)
                        .map(com.surprising.aeron.service.state.RuntimeStateQueryService.OrderQueryResult::view)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeOpenOrders(
                                new com.surprising.aeron.protocol.CoreOpenOrdersView(orders)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try {
                var query = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
                long before = query.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : query.beforeTriggerOrderId();
                Iterable<Long> source = query.expiresBeforeEpochMillis() > 0
                        ? triggerOrderIndex.expired(query.expiresBeforeEpochMillis(), query.limit())
                        : query.symbol().isEmpty()
                        ? (query.status() != null
                        ? triggerOrderIndex.ids(query.status())
                        : message.header().userId() == 0 ? triggerOrderIndex.ids()
                        : triggerOrderIndex.ids(message.header().userId()))
                        : (query.status() == null ? triggerOrderIndex.ids(query.symbol())
                        : triggerOrderIndex.ids(query.symbol(), query.status()));
                var values = com.surprising.aeron.service.state.RuntimeOperationalQueryService.triggerOrders(
                        runtimePlaceOrderState, source, message.header().userId(), query.symbol(), query.status(),
                        query.triggerOrderId(), before,
                        message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,
                        query.limit());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BOOK_STATE_QUERY) {
            try {
                return beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return beginBookBootstrapQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY) {
            try {
                int maxEvents = CoreExportCodec.decodeBatchQuery(message.payloadUnsafe());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash(),
                        CoreExportCodec.encodeBatchWithStatus(exportState.status(),
                                exportState.batch(maxEvents)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.EXPORT_STATUS_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash(),
                    CoreExportCodec.encodeStatus(exportState.status()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.RuntimeOperationalQueryService.treasuryAssets(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeTreasuryState(views));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                CoreFundingProgressView view = com.surprising.aeron.service.state.RuntimeOperationalQueryService
                        .fundingProgress(runtimePlaceOrderState, runtimePlaceOrderIdentities, symbol);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                CoreSettlementProgressView view = com.surprising.aeron.service.state.RuntimeOperationalQueryService
                        .settlementProgress(runtimePlaceOrderState, runtimePlaceOrderIdentities, symbol);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ADL_CANDIDATE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAdlQueryCodec.encodeCandidates(
                                com.surprising.aeron.service.state.RuntimeRiskQueryService.adlCandidates(
                                        runtimePlaceOrderState, runtimePlaceOrderIdentities, query.asset(),
                                        adlPositionIndex.positions(query.asset()), query.limit())));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.RuntimeRiskQueryService.snapshots(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadUnsafe().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    CoreRiskScanControlCodec.encodeView(runtimePlaceOrderState.riskScanControl()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.OPEN_INTEREST_QUERY) {
            if (openInterestIndex.totals().size()
                    > com.surprising.aeron.service.state.RuntimeOperationalQueryService.MAX_QUERY_ENTITIES) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
            var views = openInterestIndex.totals().entrySet().stream()
                    .map(entry -> new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()))
                    .toList();
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ALGO_ORDER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
                var algoIds = query.algoOrderId() != 0
                        ? List.of(query.algoOrderId())
                        : algoOrderIndex.query(query.userId(), query.symbol(), query.dueAtEpochMillis(),
                                query.limit(), runtimePlaceOrderState::algoOrder);
                var values = com.surprising.aeron.service.state.RuntimeOperationalQueryService.algoOrders(
                        runtimePlaceOrderState, algoIds);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                var keys = cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), runtimePlaceOrderState::cancelAllAfterTimer);
                var values = com.surprising.aeron.service.state.RuntimeOperationalQueryService.cancelAllAfter(
                        runtimePlaceOrderState, keys);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payloadUnsafe());
                if (query.productLine() != productLine) {
                    return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                java.util.NavigableSet<Long> candidates = liquidationIndex.activeIds()
                        .tailSet(query.afterLiquidationId(), false);
                var work = com.surprising.aeron.service.state.RuntimeLiquidationQueryService.work(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, productLine, query, candidates);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (com.surprising.aeron.service.state.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                var command = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
                requireOrderIdentityAvailable(message.header().userId(), command);
                ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                        runtimePlaceOrderIdentities, message.header().userId(), command, clusterTimestamp);
                long reservedUnits = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId(), resolved,
                        openInterestIndex.openInterestSteps(command.symbol()), activeOrderIndex);
                var view = new com.surprising.aeron.protocol.CoreOrderPreflightView(
                        resolved.reservationAsset(), reservedUnits);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(view));
            } catch (CoreStateRejectedException exception) {
                return rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (ArithmeticException exception) {
                return rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.PENDING_TRANSFER_QUERY) {
            try {
                int limit = com.surprising.aeron.protocol.CorePendingTransferCodec.decodeQuery(
                        message.payloadUnsafe());
                var transfers = runtimePlaceOrderState.pendingTransfers(limit).stream()
                        .map(value -> new com.surprising.aeron.protocol.CorePendingTransferView(
                                value.userId(), value.command()))
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CorePendingTransferCodec.encode(transfers));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        CommandFingerprint fingerprint = CommandFingerprint.of(message);
        StoredResult duplicate = commandResults.get(message.header().commandId());
        if (duplicate != null) {
            if (!duplicate.fingerprint().equals(fingerprint)) {
                return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, 0, stateHash(), new byte[0]);
            }
            return new CoreResponse(ResponseStatus.DUPLICATE,
                    duplicate.status(),
                    duplicate.resultCode(),
                    duplicate.appliedCommandCount(), duplicate.requiredExportSequence(), duplicate.stateHash(),
                    duplicate.responseData());
        }
        if (isFundsIdempotencyCommand(message.header().messageType())) {
            CommandFingerprint retained = terminalRetention.fundsCommand(message.header().commandId());
            if (retained != null) {
                if (!retained.equals(fingerprint)) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, 0, stateHash(), new byte[0]);
                }
                return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, appliedCommandCount, 0, stateHash(), new byte[0]);
            }
            if (!terminalRetention.hasFundsCommandCapacity(message.header().commandId())) {
                return rejected(CoreResultCode.FUNDS_IDEMPOTENCY_RETENTION_FULL);
            }
        }
        SourceKey sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        Long lastSourceSequence = lastSourceSequences.get(sourceKey);
        if (lastSourceSequence != null && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, appliedCommandCount, stateHash());
        }
        if (lastSourceSequence == null && lastSourceSequences.size() >= MAX_SOURCE_SEQUENCES) {
            return rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        boolean exportCommand = message.header().messageType() != CoreMessageType.ACK_EXPORT;
        if (exportCommand && !exportState.hasCapacityFor()) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        if (exportCommand && message.payloadUnsafe().length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        commandTriggerOrderView = null;
        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= MAX_PENDING_MATCHING) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return beginOrderBatchMatching(message, clusterTimestamp, clusterPosition, sourceKey);
            }
            return beginMatching(message, clusterTimestamp, clusterPosition, sourceKey);
        }
        TradingCoreState beforeTradingState = snapshotState;
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandFundingPayments = List.of();
        commandFundingProgress = null;
        commandLiquidationProgress = null;
        commandLiquidationBatchResult = null;
        commandSettlementProgress = null;
        commandRiskScanControl = null;
        commandDelta = CoreCommandDelta.empty();
        resetChangeAccumulators();
        queuedMatching.clear();
        beginSnapshotProjectionBatch();
        try {
            status = applyCommand(message, clusterTimestamp);
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
        if (status != ResponseStatus.APPLIED && (snapshotProjectionDirty || snapshotState != beforeTradingState)) {
            restoreCommandState(beforeTradingState);
        }
        if (status == null) {
            abortSnapshotProjectionBatch();
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (status == ResponseStatus.APPLIED) {
            cancelTriggersForClosedPositions(beforeTradingState);
            if (pendingMatching.size() + queuedMatching.size() > MAX_PENDING_MATCHING) {
                restoreCommandState(beforeTradingState);
                queuedMatching.clear();
                return rejected(CoreResultCode.MATCHING_BACKPRESSURE);
            }
            if (exportCommand && !exportState.hasCapacityFor(1 + queuedMatching.size())) {
                restoreCommandState(beforeTradingState);
                queuedMatching.clear();
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        if (status == ResponseStatus.APPLIED) {
            List<Long> changedOrderIds = commandChangedOrderIds == null ? List.of() : commandChangedOrderIds;
            try {
                stampOrderChangesRuntime(beforeTradingState, clusterTimestamp, clusterPosition, changedOrderIds);
            } catch (IllegalStateException exception) {
                restoreCommandState(beforeTradingState);
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        completeSnapshotProjectionBatch();
        materializeChangeAccumulators();
        boolean tradingStateChanged = status == ResponseStatus.APPLIED && snapshotState != beforeTradingState;
        try {
            commandDelta = commandDelta(beforeTradingState, snapshotState, exportCommand);
        } catch (IllegalStateException exception) {
            restoreCommandState(beforeTradingState);
            return rejected(CoreResultCode.INVALID_COMMAND);
        }
        if (status == ResponseStatus.APPLIED) {
            validateFundsConservation(message, beforeTradingState, snapshotState, commandDelta);
        }
        long businessStateHash = tradingStateChanged ? currentBusinessStateHash() : cachedBusinessStateHash;
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        LaneCommandContextRing.Context commandLaneContext = null;
        if (status == ResponseStatus.APPLIED && !commandDelta.userIds().isEmpty()) {
            long expectedLaneMask = 0;
            for (Long userId : commandDelta.userIds()) {
                if (userId != null && userId > 0) {
                    expectedLaneMask |= matchingAdapter.topology().accountLaneMask(userId);
                }
            }
            commandLaneContext = laneCommandContexts.claim(nextAppliedCommandCount);
            commandLaneContext.result(new com.surprising.aeron.service.matching.CoreMatchingResult(
                            true, "NO_NATIVE_COMMAND").withCoreSequence(nextAppliedCommandCount),
                    expectedLaneMask, validAccountLaneMask());
            long appliedLaneMask = runtimePlaceOrderState.applyLaneSequence(nextAppliedCommandCount,
                    commandDelta.userIds(), commandLaneContext.matchingResult(),
                    snapshotState.businessStateHash(), rollingFundsStateHash.value());
            if (appliedLaneMask != expectedLaneMask) {
                throw new IllegalStateException("single command account lane mask mismatch");
            }
            acknowledgeAccountLanes(commandLaneContext, beforeTradingState, snapshotState);
        }
        appliedCommandCount = nextAppliedCommandCount;
        refreshCommittedCoreSequence();
        if (commandLaneContext != null) {
            runtimePlaceOrderState.commitLaneSequence(nextAppliedCommandCount,
                    commandLaneContext.expectedLaneMask());
            laneCommandContexts.release(nextAppliedCommandCount);
        }
        long requiredExportSequence = 0;
        if (exportCommand) {
            try {
                requiredExportSequence = appendCoreFact(message, status, resultCode,
                        nextAppliedCommandCount, businessStateHash,
                        beforeTradingState, snapshotState, commandDelta, commandMatcherTransition);
                if (tradingStateChanged) {
                    terminalRetention.observe(beforeTradingState, snapshotState, requiredExportSequence,
                            commandDelta.orderIds(), commandDelta.liquidationIds(), commandDelta.triggerOrderIds());
                }
            } catch (CoreStateRejectedException exception) {
                if (!"EXPORT_BACKLOG_FULL".equals(exception.code())) throw exception;
                throw new IllegalStateException("export capacity changed after deterministic admission", exception);
            }
        }
        cachedBusinessStateHash = businessStateHash;
        appendQueuedMatching();
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            terminalRetention.retainFundsCommand(message.header().commandId(), fingerprint);
        }
        long stateHash = stateHash(businessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = message.header().messageType() == CoreMessageType.ACK_EXPORT
                && status == ResponseStatus.APPLIED
                ? CoreExportCodec.encodeStatus(exportState.status()) : commandResultData();
        storeResult(message.header().commandId(), new StoredResult(fingerprint, status, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, responseData, 0));
        if (message.header().messageType() == CoreMessageType.ACK_EXPORT && status == ResponseStatus.APPLIED
                && (!deferredMatching.isEmpty() || !pendingOrderBatches.isEmpty())) {
            submitDeferredMatchingAfterBatch();
        }
        return new CoreResponse(status, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData);
    }

    private void appendQueuedMatching() {
        if (queuedMatching.isEmpty()) return;
        for (CoreMessage command : queuedMatching) {
            long sequence = Math.incrementExact(appliedCommandCount);
            PendingMatching pending = newPendingMatching(sequence, PendingMatching.Operation.TRIGGER, command);
            putPendingMatching(pending);
            registerPendingLifecycle(pending);
            appliedCommandCount = sequence;
            refreshCommittedCoreSequence();
            submitMatching(pending);
        }
        queuedMatching.clear();
    }

    private CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, SourceKey sourceKey) {
        OrderBatchPending batch;
        try {
            batch = decodeOrderBatch(message, clusterTimestamp, clusterPosition);
            validateOrderBatchIdentity(batch, message.header().userId());
        } catch (CoreStateRejectedException exception) {
            return rejected(CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey,
                    exception instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        if (!exportState.hasCapacityFor()) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        boolean activateNow = pendingOrderBatches.isEmpty();
        long sequence = Math.incrementExact(appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, batch.operation, message);
        batch.sequence = sequence;
        putPendingMatching(pending);
        registerPendingLifecycle(pending);
        pendingOrderBatches.put(sequence, batch);
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long pendingStateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.OK, matchingPendingCode(), appliedCommandCount);
        storeResult(message.header().commandId(), new StoredResult(CommandFingerprint.of(message),
                ResponseStatus.OK, matchingPendingCode(), appliedCommandCount, 0, pendingStateHash,
                new byte[0], 0));
        CoreResponse completed = activateNow ? activateOrderBatch(batch, pending) : null;
        if (completed != null) return completed;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, 0, pendingStateHash, new byte[0]);
    }

    private CoreResponse activateOrderBatch(OrderBatchPending batch, PendingMatching pending) {
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandFundingPayments = List.of();
        commandFundingProgress = null;
        commandLiquidationProgress = null;
        commandLiquidationBatchResult = null;
        commandSettlementProgress = null;
        commandRiskScanControl = null;
        commandDelta = CoreCommandDelta.empty();
        resetChangeAccumulators();
        batch.beforeState = snapshotState;
        batch.matcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                appliedMatcherSequence, appliedMatcherPrefixDigest);
        batch.started = true;
        return startOrderBatchItem(batch, pending, batch.clusterTimestamp, batch.clusterPosition);
    }

    private OrderBatchPending decodeOrderBatch(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.PLACE, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    snapshotState, clusterTimestamp, clusterPosition, PendingMatching.Operation.PLACE);
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.CANCEL, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    snapshotState, clusterTimestamp, clusterPosition, PendingMatching.Operation.CANCEL);
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.AMEND, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.replacementOrderId(), value.originalOrderId(),
                            value.replacementOrderId(), value)).toList(),
                    snapshotState, clusterTimestamp, clusterPosition, PendingMatching.Operation.AMEND);
        }
        throw new IllegalArgumentException("unsupported order batch type");
    }

    private void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            CoreOrderState order = runtimeOrder(orderId);
            if (order != null && order.userId() != userId) {
                throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH",
                        "order batch contains another user's order");
            }
        }
    }

    private CoreResponse startOrderBatchItem(OrderBatchPending batch, PendingMatching pending,
                                             long clusterTimestamp, long clusterPosition) {
        while (batch.nextIndex < batch.items.size()) {
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            TradingCoreState before = snapshotState;
            beginSnapshotProjectionBatch();
            try {
                prepareOrderBatchItem(batch, item, pending.command().header().userId(), pending.command().header().commandId());
                completeSnapshotProjectionBatch();
                batch.currentBefore = before;
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                pendingMatching.put(pending.sequence(), pending);
                submitMatching(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                if (snapshotProjectionDirty || snapshotState != before) {
                    restoreCommandState(before);
                } else abortSnapshotProjectionBatch();
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()), List.of());
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                if (snapshotProjectionDirty || snapshotState != before) {
                    restoreCommandState(before);
                } else abortSnapshotProjectionBatch();
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        exception instanceof ArithmeticException
                                ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND,
                        List.of());
                batch.nextIndex++;
            }
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    private void prepareOrderBatchItem(OrderBatchPending batch, OrderBatchItem item, long userId,
                                       UUID commandId) {
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                requireOrderIdentityAvailable(userId, command);
                reservePlaceOrderRuntime(userId, command, commandId, batch.sequence);
                batch.currentPreMatchingCancellationOrderIds = preMatchingCloseCapacityCancellations(
                        userId, command, command.orderId());
            }
            case CANCEL -> {
                batch.currentPreMatchingCancellationOrderIds = List.of();
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                CoreOrderState order = runtimeOrder(command.orderId());
                if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                if (order.userId() != userId) {
                    throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                }
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                CoreOrderState order = runtimeOrder(command.originalOrderId());
                if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                if (order.userId() != userId) {
                    throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                }
                if (order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN
                        || order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
                }
                if (runtimePlaceOrderState.order(command.replacementOrderId()) != null) {
                    throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
                }
                PlaceOrderCommand replacement = replacementForAmend(command, order);
                ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                        runtimePlaceOrderIdentities, userId, replacement, currentClusterTimestamp);
                com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, userId, resolved,
                        openInterestIndex.openInterestSteps(replacement.symbol()), activeOrderIndex,
                        command.originalOrderId());
                batch.currentPreMatchingCancellationOrderIds = preMatchingCloseCapacityCancellations(
                        userId, replacement, command.originalOrderId());
            }
        }
    }

    private void submitOrderBatchMatching(PendingMatching pending) {
        OrderBatchPending batch = pendingOrderBatches.get(pending.sequence());
        if (batch == null) return;
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future;
        CompletableFuture<Void> matcherReady = runtime.matcherReady();
        if (matcherReady.isDone() && !matcherReady.isCompletedExceptionally()) {
            future = submitOrderBatchMatchingNow(pending, batch);
        } else {
            future = matcherReady.thenCompose(ignored -> submitOrderBatchMatchingNow(pending, batch));
        }
        trackMatchingFuture(pending.sequence(), future);
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> submitOrderBatchMatchingNow(
            PendingMatching pending, OrderBatchPending batch) {
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        long orderId;
        long instrumentVersion;
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                orderId = command.orderId();
                instrumentVersion = command.instrumentVersion();
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                CoreOrderState order = runtimeOrder(command.orderId());
                orderId = command.orderId();
                instrumentVersion = order == null ? 0 : order.instrumentVersion();
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                CoreOrderState order = runtimeOrder(command.originalOrderId());
                PlaceOrderCommand replacement = replacementForAmend(command, order);
                orderId = replacement.orderId();
                instrumentVersion = replacement.instrumentVersion();
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
        return withMatchingEvidence(pending, orderId, instrumentVersion, () -> {
            try {
                List<CoreOrderState> preMatchingCancellations = pending.preMatchingCancellationOrderIds().stream()
                        .map(this::runtimeOrder)
                        .filter(java.util.Objects::nonNull)
                        .filter(order -> order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                        .toList();
                return matchingAdapter.executeAfterCancellations(preMatchingCancellations, () -> switch (batch.kind) {
                case PLACE -> matchingAdapter.placeAsync(pending.command().header().userId(),
                        matchingOrder(item.orderId()));
                case CANCEL -> {
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    CoreOrderState order = runtimeOrder(command.orderId());
                    yield matchingAdapter.cancelAsyncForContinuation(pending.command().header().userId(),
                            command.orderId(), order == null ? "" : order.symbol());
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    CoreOrderState order = runtimeOrder(command.originalOrderId());
                    yield matchingAdapter.replaceOrderAsync(pending.command().header().userId(),
                            command.originalOrderId(), order.symbol(), matchingOrder(
                                    pending.command().header().userId(), replacementForAmend(command, order)));
                }
                });
            } catch (RuntimeException exception) {
                return CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                        false, "EXCHANGE_CORE_FAILURE"));
            }
        });
    }

    private CoreResponse completeOrderBatchMatching(long sequence,
                                                    com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                                    long clusterTimestamp, long clusterPosition) {
        PendingMatching pending = pendingMatching.get(sequence);
        OrderBatchPending batch = pendingOrderBatches.get(sequence);
        if (pending == null || batch == null || matchingResult == null) return null;
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            throw failMatching(pending, "matcher continuation returned " + matchingResult.resultCode(), null);
        }
        batch.lastMatchingResult = matchingResult;
        batch.retainMatchingResult(matchingResult);
        if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
            try {
                batch.advanceMatcher(matchingResult);
            } catch (IllegalArgumentException exception) {
                throw failMatching(pending, "order batch matcher transition is not contiguous", exception);
            }
        }
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        TradingCoreState before = batch.currentBefore;
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        beginSnapshotProjectionBatch();
        try {
            List<CoreExecutionView> executions = applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (snapshotProjectionDirty || snapshotState != before) {
                stampOrderChangesRuntime(before, clusterTimestamp, clusterPosition,
                        batch.changedOrderIdsFor(item, matchingResult));
            }
            completeSnapshotProjectionBatch();
            batch.changedUserIds.addAll(matchingUserIds(pending.command().header().userId(),
                    matchingResult.matcherEvents()));
            batch.changedOrderIds.addAll(batch.changedOrderIdsFor(item, matchingResult));
            appendOrderBatchResult(batch, item, status, resultCode, executions);
            batch.nextIndex++;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
            restoreCommandState(before);
            throw failMatching(pending, "Core and matcher state diverged", exception);
        }
        return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition);
    }

    private List<CoreExecutionView> applyOrderBatchMatcherResult(
            OrderBatchPending batch, OrderBatchItem item, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                applyPreMatchingCancellations(pending, matchingResult);
                if (matchingResult.accepted()) {
                    batch.mergeTreasuryDeltas(runtimePlaceOrderState.applyMatcherSettlement(
                            pending.sequence(), expectedLaneMask(pending, matchingResult), command.orderId(),
                            matchingResult, runtimePlaceOrderIdentities));
                } else {
                    rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId());
                }
                return executionViews(command.orderId(), pending.command().header().userId(),
                        matchingResult.matcherEvents());
            }
                case CANCEL -> {
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    if (matchingResult.accepted()) {
                        cancelOrderRuntime(pending.command().header().userId(), command.orderId());
                    }
                    return List.of();
                }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                PlaceOrderCommand replacement = replacementForAmend(command,
                        runtimeOrder(command.originalOrderId()));
                applyPreMatchingCancellations(pending, matchingResult);
                if (matchingResult.accepted()) {
                    cancelOrderRuntime(pending.command().header().userId(), command.originalOrderId());
                    requireOrderIdentityAvailable(pending.command().header().userId(), replacement);
                    reservePlaceOrderRuntime(pending.command().header().userId(), replacement,
                            pending.command().header().commandId(), pending.sequence());
                    batch.mergeTreasuryDeltas(runtimePlaceOrderState.applyMatcherSettlement(
                            pending.sequence(), expectedLaneMask(pending, matchingResult), replacement.orderId(),
                            matchingResult, runtimePlaceOrderIdentities));
                }
                return executionViews(replacement.orderId(), pending.command().header().userId(),
                        matchingResult.matcherEvents());
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
    }

    private void appendOrderBatchResult(OrderBatchPending batch, OrderBatchItem item,
                                        ResponseStatus status, CoreResultCode resultCode,
                                        List<CoreExecutionView> executions) {
        CoreOrderState order = runtimeOrder(item.orderId);
        if (order == null && item.originalOrderId > 0) order = runtimeOrder(item.originalOrderId);
        batch.results.add(new CoreOrderBatchResult.Item(batch.results.size(), item.orderId,
                item.originalOrderId, item.replacementOrderId, status, resultCode,
                order == null ? null : orderView(order), executions));
    }

    private CoreResponse finishOrderBatch(OrderBatchPending batch, PendingMatching pending,
                                          long clusterTimestamp, long clusterPosition) {
        runtimePlaceOrderState.completePendingReservations(batch.sequence);
        commandChangedUserIds = List.copyOf(batch.changedUserIds);
        commandChangedOrderIds = List.copyOf(batch.changedOrderIds);
        LaneCommandContextRing.Context laneContext = laneCommandContexts.required(batch.sequence);
        long expectedLaneMask = 0;
        for (Long userId : commandChangedUserIds) {
            expectedLaneMask |= matchingAdapter.topology().accountLaneMask(userId);
        }
        var finalMatchingResult = batch.lastMatchingResult == null
                ? new com.surprising.aeron.service.matching.CoreMatchingResult(true, "NO_NATIVE_COMMAND")
                .withCoreSequence(batch.sequence)
                : batch.lastMatchingResult;
        laneContext.result(finalMatchingResult, expectedLaneMask, validAccountLaneMask());
        projectSnapshotNow();
        materializeChangeAccumulators();
        if (snapshotState != batch.beforeState) {
            stampOrderChangesRuntime(batch.beforeState, clusterTimestamp, clusterPosition, commandChangedOrderIds);
        }
        CoreOrderBatchResult result = new CoreOrderBatchResult(batch.results.stream().map(item -> {
            CoreOrderState order = item.order() == null ? null : runtimeOrder(item.order().orderId());
            return new CoreOrderBatchResult.Item(item.index(), item.orderId(), item.originalOrderId(),
                    item.replacementOrderId(), item.status(), item.resultCode(),
                    order == null ? null : orderView(order), item.executions());
        }).toList());
        byte[] responseData = TradingOrderBatchCodec.encodeResult(result);
        commandExecutions = result.items().stream().flatMap(item -> item.executions().stream()).toList();
        long laneMask = runtimePlaceOrderState.applyLaneSequence(batch.sequence, commandChangedUserIds,
                laneContext.matchingResult(), snapshotState.businessStateHash(), rollingFundsStateHash.value());
        if (laneMask != laneContext.expectedLaneMask()) {
            throw failMatching(pending, "order batch account lane mask mismatch", null);
        }
        AccountLaneAck[] acknowledgements = runtimePlaceOrderState.accountLaneAcks(
                batch.sequence, laneContext.expectedLaneMask(), laneContext.matchingResult(),
                batch.laneTreasuryDeltas);
        for (AccountLaneAck acknowledgement : acknowledgements) {
            if (acknowledgement != null) laneContext.acknowledge(acknowledgement);
        }
        requireCompleteAccountLaneAcks(laneContext);
        laneContext.treasuryDelta().apply(runtimePlaceOrderState.treasury());
        runtimePlaceOrderState.setMetadata(productLine,
                Math.incrementExact(runtimePlaceOrderState.revision()));
        refreshSnapshotProjection();
        materializeChangeAccumulators();
        CoreCommandDelta delta = commandDelta(batch.beforeState, snapshotState, true);
        validateFundsConservation(pending.command(), batch.beforeState, snapshotState, delta);
        commitMatchingSequence(batch.sequence);
        runtimePlaceOrderState.commitLaneSequence(batch.sequence, laneContext.expectedLaneMask());
        long businessStateHash = snapshotState == batch.beforeState
                ? cachedBusinessStateHash : currentBusinessStateHash();
        long requiredExportSequence = appendCoreFact(pending.command(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence, businessStateHash, batch.beforeState, snapshotState, delta,
                batch.matcherTransition);
        if (snapshotState != batch.beforeState) {
            terminalRetention.observe(batch.beforeState, snapshotState, requiredExportSequence,
                    delta.orderIds(), delta.liquidationIds(), delta.triggerOrderIds());
        }
        cachedBusinessStateHash = businessStateHash;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, batch.sequence);
        storeResult(pending.command().header().commandId(), new StoredResult(
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, requiredExportSequence, stateHash, responseData, 0));
        laneCommandContexts.release(batch.sequence);
        removePendingMatching(batch.sequence);
        pendingOrderBatches.remove(batch.sequence);
        submitDeferredMatchingAfterBatch();
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, requiredExportSequence, stateHash, responseData);
    }

    private void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, sourceSequence);
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, sourceSequence);
    }

    static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || isOrderBatchCommand(type)
                || type == CoreMessageType.EXECUTE_LIQUIDATION
                || type == CoreMessageType.EXECUTE_LIQUIDATION_BATCH
                || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    private static boolean isOrderBatchCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER_BATCH || type == CoreMessageType.CANCEL_ORDER_BATCH
                || type == CoreMessageType.AMEND_ORDER_BATCH;
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command) {
        return new PendingMatching(sequence, operation, command, snapshotState,
                currentBusinessStateHash(), rollingFundsStateHash.value());
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations) {
        return new PendingMatching(sequence, operation, command, preMatchingCancellations, snapshotState,
                currentBusinessStateHash(), rollingFundsStateHash.value());
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations,
                                               TradingCoreState beforeState, long beforeBusinessStateHash,
                                               long beforeFundsStateHash) {
        return new PendingMatching(sequence, operation, command, preMatchingCancellations, beforeState,
                beforeBusinessStateHash, beforeFundsStateHash);
    }

    private CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadUnsafe().length == 0) {
            throw new IllegalArgumentException("single-symbol book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        long queryId = nextAsyncQueryId++;
        matchingAdapter.orderBookLevelsAsync(query.symbol(), query.depth()).whenComplete((levels, failure) -> {
            if (failure != null) {
                failedQueries.put(queryId, true);
                return;
            }
            completedBookQueries.put(queryId, CompletedBookQuery.single(levels));
        });
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, cachedBusinessStateHash);
    }

    private CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
            BookBootstrapSession session = bookBootstrapSessions.get(query.snapshotId());
            if (session == null || session.depth() != query.depth()) {
                return rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
            }
            return bootstrapPageResponse(session, query);
        }
        long queryId = nextAsyncQueryId++;
        matchingAdapter.orderBookBootstrapAsync(query.depth()).whenComplete((snapshot, failure) -> {
            if (failure != null) {
                failedQueries.put(queryId, true);
                return;
            }
            completedBookQueries.put(queryId, CompletedBookQuery.bootstrap(
                    message.header().commandId().toString(), query, snapshot));
        });
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, cachedBusinessStateHash);
    }

    private CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       SourceKey sourceKey) {
        PendingMatching.Operation operation = matchingOperation(message.header().messageType());
        if (!pendingOrderBatches.isEmpty()) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, null);
    }

    private static PendingMatching.Operation matchingOperation(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, PLACE_ORDER_BATCH -> PendingMatching.Operation.PLACE;
            case CANCEL_ORDER, CANCEL_ORDER_BATCH -> PendingMatching.Operation.CANCEL;
            case REPLACE_ORDER -> PendingMatching.Operation.REPLACE;
            case AMEND_ORDER, AMEND_ORDER_BATCH -> PendingMatching.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> PendingMatching.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> PendingMatching.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> PendingMatching.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
    }

    private CoreResponse prepareMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                         SourceKey sourceKey, PendingMatching.Operation operation,
                                         PendingMatching deferredPending) {
        long matchingStartNanos = System.nanoTime();
        int requiredExportCapacity = switch (message.header().messageType()) {
            case EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH, SETTLE_INSTRUMENT -> 3;
            default -> 2;
        };
        if (!exportState.hasCapacityFor(requiredExportCapacity)) {
            return deferredPending == null ? rejected(CoreResultCode.EXPORT_BACKLOG_FULL)
                    : null;
        }
        try {
            rejectLifecycleOverlap(message, operation);
        } catch (CoreStateRejectedException exception) {
            return recordRejectedMatching(message, sourceKey, CoreResultCode.fromRejectionCode(exception.code()),
                    deferredPending);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey, exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
        }
        TradingCoreState before = snapshotState;
        long beforeBusinessStateHash = deferredPending == null
                ? currentBusinessStateHash() : deferredPending.beforeBusinessStateHash();
        long beforeFundsStateHash = deferredPending == null
                ? rollingFundsStateHash.value() : deferredPending.beforeFundsStateHash();
        long sequence = deferredPending == null
                ? Math.incrementExact(appliedCommandCount) : deferredPending.sequence();
        List<Long> preMatchingCancellations = List.of();
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandFundingPayments = List.of();
        commandFundingProgress = null;
        commandLiquidationProgress = null;
        commandLiquidationBatchResult = null;
        commandSettlementProgress = null;
        commandRiskScanControl = null;
        commandDelta = CoreCommandDelta.empty();
        resetChangeAccumulators();
        beginSnapshotProjectionBatch();
        try {
            switch (operation) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
                    requireOrderIdentityAvailable(message.header().userId(), command);
                    reservePlaceOrderRuntime(message.header().userId(), command,
                            message.header().commandId(), sequence);
                    commandChangedUserIds = List.of(message.header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    commandOrderViews = List.of(orderView(runtimeOrder(command.orderId())));
                }
                case CANCEL -> validatePendingCancel(message);
                case REPLACE -> validatePendingReplace(message, false);
                case AMEND -> validatePendingReplace(message, true);
                case LIQUIDATION -> validatePendingLiquidation(message);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(message);
                case SETTLEMENT -> validatePendingSettlement(message);
            }
            preMatchingCancellations = preMatchingCloseCapacityCancellations(operation, message);
        } catch (CoreStateRejectedException exception) {
            if (snapshotProjectionDirty) {
                if (!pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                restoreCommandState(before);
            }
            else abortSnapshotProjectionBatch();
            return recordRejectedMatching(message, sourceKey, CoreResultCode.fromRejectionCode(exception.code()),
                    deferredPending);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            if (snapshotProjectionDirty) {
                if (!pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                restoreCommandState(before);
            }
            else abortSnapshotProjectionBatch();
            return recordRejectedMatching(message, sourceKey, exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
        }
        boolean tradingStateChanged = snapshotProjectionDirty || snapshotState != before;
        if (tradingStateChanged) {
            try {
                stampOrderChangesRuntime(before, clusterTimestamp, clusterPosition, commandChangedOrderIds);
            } catch (RuntimeException exception) {
                restoreCommandState(before);
                throw exception;
            }
        }
        completeSnapshotProjectionBatch();
        try {
            commandDelta = commandDelta(before, snapshotState, true);
        } catch (RuntimeException exception) {
            if (tradingStateChanged) {
                restoreCommandState(before);
            }
            return deferredPending == null ? rejected(CoreResultCode.INVALID_COMMAND)
                    : recordRejectedDeferredMatching(deferredPending, CoreResultCode.INVALID_COMMAND);
        }
        long businessStateHash = tradingStateChanged ? currentBusinessStateHash() : cachedBusinessStateHash;
        long requiredExportSequence = 0;
        PendingMatching pending = deferredPending == null
                ? newPendingMatching(sequence, operation, message, preMatchingCancellations, before,
                        beforeBusinessStateHash, beforeFundsStateHash)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations);
        if (deferredPending == null) {
            putPendingMatching(pending);
        } else {
            pendingMatching.put(sequence, pending);
            pendingMatchingByCommandId.put(message.header().commandId(), sequence);
            deferredMatching.remove(sequence);
        }
        registerPendingLifecycle(pending);
        if (deferredPending == null) {
            appliedCommandCount = sequence;
            refreshCommittedCoreSequence();
            recordSourceSequence(sourceKey, message.header().sourceSequence());
        }
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(), ResponseStatus.OK,
                matchingPendingCode(), sequence);
        byte[] responseData = commandResultData();
        storeResult(message.header().commandId(), new StoredResult(CommandFingerprint.of(message), ResponseStatus.OK,
                matchingPendingCode(), sequence, requiredExportSequence, stateHash, responseData, 0));
        matchingPhaseMetrics.recordPrepare(System.nanoTime() - matchingStartNanos);
        submitMatching(pending);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                sequence, requiredExportSequence, stateHash, responseData);
    }

    private CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       SourceKey sourceKey, PendingMatching.Operation operation) {
        long sequence = Math.incrementExact(appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, operation, message);
        putPendingMatching(pending);
        deferredMatching.put(sequence, new DeferredMatching(clusterTimestamp, clusterPosition, sourceKey));
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(), ResponseStatus.OK,
                matchingPendingCode(), sequence);
        storeResult(message.header().commandId(), new StoredResult(pending.fingerprint(), ResponseStatus.OK,
                matchingPendingCode(), sequence, 0, stateHash, new byte[0], 0));
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                sequence, 0, stateHash, new byte[0]);
    }

    private CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CoreResultCode resultCode, PendingMatching deferredPending) {
        return deferredPending == null ? recordRejectedMatching(message, sourceKey, resultCode)
                : recordRejectedDeferredMatching(deferredPending, resultCode);
    }

    private CoreResponse recordRejectedDeferredMatching(PendingMatching pending, CoreResultCode resultCode) {
        commitMatchingSequence(pending.sequence());
        long requiredExportSequence = appendRejectedCoreFact(
                pending.command(), resultCode, pending.sequence());
        long stateHash = stateHash(cachedBusinessStateHash, pending.command().header().commandId(),
                ResponseStatus.REJECTED, resultCode, pending.sequence());
        storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, pending.sequence(), requiredExportSequence, stateHash,
                new byte[0], 0));
        deferredMatching.remove(pending.sequence());
        removePendingMatching(pending.sequence());
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                pending.sequence(), requiredExportSequence, stateHash, new byte[0]);
    }

    private CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CoreResultCode resultCode) {
        if (!pendingMatching.isEmpty()) {
            long sequence = Math.incrementExact(appliedCommandCount);
            PendingMatching pending = newPendingMatching(sequence,
                    matchingOperation(message.header().messageType()), message);
            putPendingMatching(pending);
            pendingMatchingRejections.put(sequence, resultCode);
            appliedCommandCount = sequence;
            recordSourceSequence(sourceKey, message.header().sourceSequence());
            long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                    ResponseStatus.OK, matchingPendingCode(), sequence);
            storeResult(message.header().commandId(), new StoredResult(pending.fingerprint(), ResponseStatus.OK,
                    matchingPendingCode(), sequence, 0, stateHash, new byte[0], 0));
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                    sequence, 0, stateHash, new byte[0]);
        }
        long sequence = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence = appendRejectedCoreFact(message, resultCode, sequence);
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.REJECTED, resultCode, appliedCommandCount);
        storeResult(message.header().commandId(), new StoredResult(CommandFingerprint.of(message),
                ResponseStatus.REJECTED, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                new byte[0], 0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, new byte[0]);
    }

    private void validatePendingCancel(CoreMessage message) {
        var command = TradingCommandCodec.decodeCancelOrder(message.payloadUnsafe());
        var order = runtimePlaceOrderState.order(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(command.orderId());
    }

    private void validatePendingReplace(CoreMessage message, boolean amend) {
        long originalOrderId;
        if (amend) {
            originalOrderId = TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe()).originalOrderId();
        } else {
            originalOrderId = TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe()).originalOrderId();
        }
        var runtimeOrder = runtimePlaceOrderState.order(originalOrderId);
        var order = runtimeOrder == null ? null
                : com.surprising.aeron.service.state.RuntimeStateMaterializer.orderSnapshot(
                        runtimeOrder, runtimePlaceOrderIdentities);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not replaceable");
        }
        if (amend && order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
        }
        PlaceOrderCommand replacement = replacementFor(message, order);
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, message.header().userId(), replacement, currentClusterTimestamp);
        com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId(), resolved,
                openInterestIndex.openInterestSteps(replacement.symbol()), activeOrderIndex, originalOrderId);
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(originalOrderId);
    }

    private List<Long> preMatchingCloseCapacityCancellations(
            PendingMatching.Operation operation,
            CoreMessage message) {
        PlaceOrderCommand placement;
        long excludedOrderId = 0;
        switch (operation) {
            case PLACE -> {
                placement = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
                excludedOrderId = placement.orderId();
            }
            case TRIGGER -> {
                long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                        message.payloadUnsafe());
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                if (trigger == null) return List.of();
                placement = triggerPlacement(trigger, execute[2]);
                excludedOrderId = placement.orderId();
            }
            case REPLACE, AMEND -> {
                excludedOrderId = operation == PendingMatching.Operation.REPLACE
                        ? TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe()).originalOrderId()
                        : TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe()).originalOrderId();
                placement = replacementFor(message, runtimeOrder(excludedOrderId));
            }
            default -> {
                return List.of();
            }
        }
        LinkedHashSet<Long> cancellations = new LinkedHashSet<>();
        cancellations.addAll(preMatchingCloseCapacityCancellations(
                message.header().userId(), placement, excludedOrderId));
        cancellations.addAll(preMatchingSelfTradeCancellations(
                message.header().userId(), placement, excludedOrderId));
        return List.copyOf(cancellations);
    }

    private List<Long> preMatchingSelfTradeCancellations(
            long userId,
            PlaceOrderCommand placement,
            long excludedOrderId) {
        long matchingPrice = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, userId, placement, currentClusterTimestamp).matchingPriceTicks();
        return activeOrderIndex.ids(userId, placement.symbol()).stream()
                .filter(orderId -> orderId != excludedOrderId && orderId != placement.orderId())
                .map(this::runtimeOrder)
                .filter(java.util.Objects::nonNull)
                .filter(order -> order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .filter(order -> order.side() != placement.side())
                .filter(order -> placement.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                        ? matchingPrice >= order.priceTicks()
                        : matchingPrice <= order.priceTicks())
                .map(com.surprising.aeron.service.state.CoreOrderState::orderId)
                .toList();
    }

    private List<Long> preMatchingCloseCapacityCancellations(
            long userId,
            PlaceOrderCommand placement,
            long excludedOrderId) {
        if (!productLine.isDerivative() || placement.reduceOnly()) return List.of();
        var user = runtimePlaceOrderState.user(userId);
        if (user == null) return List.of();
        String symbol = placement.symbol();
        String positionKey = placement.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? symbol : symbol + ':' + placement.positionSide().name();
        Long runtimePositionKey = runtimePlaceOrderIdentities.findPositionKey(userId, positionKey);
        var position = runtimePositionKey == null ? null : runtimePlaceOrderState.position(runtimePositionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0)
                == (placement.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY)) {
            return List.of();
        }
        return PositionCloseCapacity.inspectRuntime(runtimePlaceOrderState, runtimePlaceOrderIdentities, userId,
                symbol, placement.positionSide(), placement.side(), activeOrderIndex, excludedOrderId)
                .conflictsFor(placement.quantitySteps());
    }

    private void validatePendingLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
        var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() == CoreLiquidationState.Status.PLANNED && command.cursorOrderId() != 0
                || liquidation.status() == CoreLiquidationState.Status.ORDERED
                && command.cursorOrderId() != liquidation.nextCancelOrderId()) {
            throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                    "liquidation cancellation cursor does not match state");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not executable");
        }
        commandChangedUserIds = List.of(liquidation.userId());
        LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                command.cursorOrderId(), command.maxOrders());
        commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        commandChangedLiquidationIds = List.of(liquidation.liquidationId());
    }

    private void validatePendingLiquidationBatch(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
        if (command.riskScanContinuation() != null) {
            var control = runtimePlaceOrderState.riskScanControl();
            if (!control.enabled() || command.maxRiskScanUsers() > control.scanBatchSize()) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "risk scan continuation exceeds current control");
            }
            var scan = runtimePlaceOrderState.firstIncompleteRiskScan();
            var continuation = command.riskScanContinuation();
            if (scan == null
                    || !runtimePlaceOrderIdentities.symbol(scan.symbolId()).equals(continuation.symbol())
                    || scan.priceSequence() != continuation.priceSequence()
                    || scan.lastUserId() != continuation.lastUserId()) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "risk scan cursor does not match state");
            }
        }
        java.util.HashSet<String> scopes = new java.util.HashSet<>();
        List<Long> changedUsers = new ArrayList<>();
        List<Long> changedLiquidations = new ArrayList<>();
        List<Long> changedOrders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            var liquidation = runtimePlaceOrderState.liquidation(action.liquidationId());
            changedLiquidations.add(action.liquidationId());
            if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                    || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.CANCELED) continue;
            if (liquidation.userId() != action.userId()
                    || !runtimeLiquidationSymbol(liquidation).equals(action.symbol())
                    || liquidation.instrumentVersion() != action.instrumentVersion()
                    || liquidation.triggerPriceSequence() != action.triggerPriceSequence()
                    || action.executionPriceTicks() <= 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "liquidation batch action does not match state");
            }
            if (liquidation.status() == CoreLiquidationState.Status.PLANNED && action.cursorOrderId() != 0
                    || liquidation.status() == CoreLiquidationState.Status.ORDERED
                    && action.cursorOrderId() != liquidation.nextCancelOrderId()) {
                throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                        "liquidation batch cursor does not match state");
            }
            if (!scopes.add(liquidation.userId() + "\u0000" + runtimeLiquidationSymbol(liquidation))) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "liquidation batch contains overlapping scopes");
            }
            ensureLifecycleScopeAvailable(new LifecycleScope(false, liquidation.userId(),
                    runtimeLiquidationSymbol(liquidation),
                    liquidation.liquidationId(), true, false));
            changedUsers.add(liquidation.userId());
            if (remaining > 0) {
                LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                        action.cursorOrderId(), remaining);
                changedOrders.addAll(chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList());
                remaining -= chunk.orders().size();
            }
        }
        commandChangedUserIds = changedUsers.stream().distinct().toList();
        commandChangedLiquidationIds = changedLiquidations.stream().distinct().toList();
        commandChangedOrderIds = changedOrders.stream().distinct().toList();
    }

    private void validatePendingSettlement(CoreMessage message) {
        var command = TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
        var progress = runtimeLifecycleProgress(command.symbol());
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.instrumentVersion() != command.instrumentVersion()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
        LifecycleOrderChunk orderChunk = lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        boolean orderPhase = progress == null || !progress.ordersComplete();
        if (orderPhase && !orderChunk.more()) {
            commandChangedOrderIds = orderChunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            commandChangedUserIds = orderChunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
            commandChangedUserIds = appendDistinct(commandChangedUserIds,
                    settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers()));
        } else if (orderPhase) {
            commandChangedOrderIds = orderChunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            commandChangedUserIds = orderChunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
        } else {
            commandChangedOrderIds = List.of();
            commandChangedUserIds = settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers());
        }
    }

    private void rejectLifecycleOverlap(CoreMessage message, PendingMatching.Operation operation) {
        LifecycleScope candidate = lifecycleScope(message, operation);
        if (candidate.symbol().isBlank()) return;
        ensureLifecycleScopeAvailable(candidate);
    }

    private void ensureLifecycleScopeAvailable(LifecycleScope candidate) {
        if (candidate.symbol().isBlank()) return;
        if (candidate.lifecycle()) {
            for (PendingMatching pending : pendingMatching.values()) {
                if (pendingLifecycleConflicts(candidate, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            }
        } else {
            for (List<LifecycleScope> scopes : pendingLifecycleScopes.values()) {
                if (scopes.stream().anyMatch(scope -> conflicts(candidate, scope))) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            }
        }
        Integer candidateSymbolId = runtimePlaceOrderIdentities.findSymbolId(candidate.symbol());
        boolean settlementProgress = candidateSymbolId != null
                && runtimePlaceOrderState.treasury().lifecycleProgress(candidateSymbolId) != null;
        if (settlementProgress
                && candidate.orderChanging()) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "settlement lifecycle is in progress");
        }
        if (candidate.lifecycle()) {
            boolean liquidationActive = candidateSymbolId != null
                    && runtimePlaceOrderState.hasActiveLiquidationConflict(
                    candidate.settlement() ? 0 : candidate.userId(), candidateSymbolId, candidate.lifecycleId());
            if (settlementProgress || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    private boolean pendingLifecycleConflicts(LifecycleScope candidate, PendingMatching pending) {
        if (pending.operation() != PendingMatching.Operation.LIQUIDATION_BATCH) {
            return conflicts(candidate, lifecycleScope(pending));
        }
        var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe());
        return batch.actions().stream().map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                        action.liquidationId(), true, false)).anyMatch(scope -> conflicts(candidate, scope));
    }

    private void registerPendingLifecycle(PendingMatching pending) {
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe())
                    .actions().stream()
                    .map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                            action.liquidationId(), true, false))
                    .toList();
            default -> List.of();
        };
        if (!scopes.isEmpty()) pendingLifecycleScopes.put(pending.sequence(), scopes);
    }

    private PendingMatching removePendingMatching(long sequence) {
        pendingLifecycleScopes.remove(sequence);
        pendingMatchingRejections.remove(sequence);
        PendingMatching removed = pendingMatching.remove(sequence);
        if (removed != null) pendingMatchingByCommandId.remove(removed.command().header().commandId());
        if (laneCommandContexts.claimed(sequence)) laneCommandContexts.discard(sequence);
        refreshCommittedCoreSequence();
        return removed;
    }

    private void refreshCommittedCoreSequence() {
        long candidate = pendingMatching.isEmpty()
                ? appliedCommandCount : Math.subtractExact(pendingMatching.keySet().iterator().next(), 1);
        while (committedCoreSequence < candidate) {
            long next = Math.incrementExact(committedCoreSequence);
            runtime.commitCoreSequence(next);
            committedCoreSequence = next;
        }
    }

    private void commitMatchingSequence(long sequence) {
        if (pendingMatching.isEmpty() || pendingMatching.keySet().iterator().next() != sequence
                || sequence != Math.incrementExact(committedCoreSequence)) {
            throw new IllegalStateException("global matching commit sequence gap");
        }
        runtime.commitCoreSequence(sequence);
        committedCoreSequence = sequence;
    }

    private void putPendingMatching(PendingMatching pending) {
        if (pendingMatching.size() >= MAX_PENDING_MATCHING) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        pendingMatching.put(pending.sequence(), pending);
        pendingMatchingByCommandId.put(pending.command().header().commandId(), pending.sequence());
        laneCommandContexts.claim(pending.sequence());
    }

    private LifecycleScope lifecycleScope(CoreMessage message, PendingMatching.Operation operation) {
        return switch (operation) {
            case LIQUIDATION -> {
                var command = TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe());
                var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                                liquidation.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0,
                    TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe()).symbol(), 0, true, false);
            default -> new LifecycleScope(false, message.header().userId(), matchingSymbol(message, operation),
                    0, false, true);
        };
    }

    private LifecycleScope lifecycleScope(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var action = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe()).actions().getFirst();
            return new LifecycleScope(false, action.userId(), action.symbol(), action.liquidationId(), true, false);
        }
        return lifecycleScope(pending.command(), pending.operation());
    }

    private static boolean conflicts(LifecycleScope left, LifecycleScope right) {
        if (left.symbol().isBlank() || !left.symbol().equals(right.symbol())
                || (!left.lifecycle() && !right.lifecycle())) return false;
        return left.settlement() || right.settlement() || left.userId() == right.userId();
    }

    private record LifecycleScope(boolean settlement, long userId, String symbol, long lifecycleId,
                                  boolean lifecycle, boolean orderChanging) {
    }

    private String matchingSymbol(CoreMessage message, PendingMatching.Operation operation) {
        return switch (operation) {
            case PLACE -> TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe()).symbol();
            case CANCEL -> {
                var command = TradingCommandCodec.decodeCancelOrder(message.payloadUnsafe());
                var order = runtimeOrder(command.orderId());
                yield order == null ? "" : order.symbol();
            }
            case REPLACE, AMEND -> {
                long orderId = operation == PendingMatching.Operation.REPLACE
                        ? TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe()).originalOrderId()
                        : TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe()).originalOrderId();
                var order = runtimeOrder(orderId);
                yield order == null ? "" : order.symbol();
            }
            case TRIGGER -> {
                long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                yield trigger == null ? "" : trigger.symbol();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
            }
        };
    }

    private String pendingLifecycleSymbol(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            return TradingCommandCodec.decodeSettleInstrument(pending.command().payloadUnsafe()).symbol();
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe());
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
        }
        var liquidation = runtimePlaceOrderState.liquidation(
                TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe()).liquidationId());
        return liquidation == null ? "" : runtimeLiquidationSymbol(liquidation);
    }

    private void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = runtimeLifecycleProgress(command.symbol());
        LifecycleOrderChunk chunk = lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        if (progress == null || !progress.ordersComplete()) {
            commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            commandChangedUserIds = chunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
            if (!chunk.more()) {
                commandChangedUserIds = appendDistinct(commandChangedUserIds,
                        settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers()));
            }
            return;
        }
        commandChangedOrderIds = List.of();
        commandChangedUserIds = settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers());
    }

    private LifecycleOrderChunk lifecycleOrders(long userId, String symbol, long cursorOrderId, int maxOrders) {
        var page = activeOrderIndex.page(userId, symbol, cursorOrderId, maxOrders);
        List<CoreOrderState> selected = page.orderIds().stream()
                .map(runtimePlaceOrderState::order)
                .filter(order -> order != null
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .map(order -> RuntimeStateMaterializer.orderSnapshot(order, runtimePlaceOrderIdentities))
                .toList();
        return new LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    private List<CoreOrderState> batchCancellationOrders(PendingMatching pending) {
        var command = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe());
        List<CoreOrderState> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            if (remaining == 0) break;
            var liquidation = runtimePlaceOrderState.liquidation(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        return List.copyOf(orders);
    }

    private List<Long> settlementUsers(String symbol, long cursorUserId, int maxUsers) {
        List<Long> selected = new ArrayList<>(Math.min(maxUsers, 64));
        for (Long userId : positionUserIndex.users(symbol)) {
            if (userId == null || userId <= cursorUserId) continue;
            if (selected.size() == maxUsers) break;
            selected.add(userId);
        }
        return List.copyOf(selected);
    }

    private record LifecycleOrderChunk(List<CoreOrderState> orders, long nextCursorOrderId) {
        private boolean more() {
            return nextCursorOrderId != 0;
        }
    }

    private void submitMatching(PendingMatching pending) {
        if (matchingSubmissionDeferred(pending.sequence())) return;
        if (BENCHMARK_SKIP_MATCHING_SUBMIT) {
            completedMatching.put(pending.sequence(),
                    new com.surprising.aeron.service.matching.CoreMatchingResult(true, "BENCHMARK_SKIPPED"));
            return;
        }
        if (pendingOrderBatches.containsKey(pending.sequence())) {
            submitOrderBatchMatching(pending);
            return;
        }
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future;
        CompletableFuture<Void> matcherReady = runtime.matcherReady();
        if (matcherReady.isDone() && !matcherReady.isCompletedExceptionally()) {
            future = submitMatchingNow(pending);
        } else {
            future = matcherReady.thenCompose(ignored -> submitMatchingNow(pending));
        }
        trackMatchingFuture(pending.sequence(), future);
    }

    private boolean matchingSubmissionDeferred(long sequence) {
        if (pendingOrderBatches.isEmpty()) return false;
        return sequence > pendingOrderBatches.keySet().iterator().next();
    }

    private void submitDeferredMatchingAfterBatch() {
        while (!pendingMatching.isEmpty()) {
            long throughSequence = pendingOrderBatches.isEmpty()
                    ? Long.MAX_VALUE : pendingOrderBatches.keySet().iterator().next();
            PendingMatching pending = pendingMatching.values().stream()
                    .filter(value -> value.sequence() <= throughSequence)
                    .filter(value -> {
                        OrderBatchPending batch = pendingOrderBatches.get(value.sequence());
                        return batch == null ? deferredMatching.containsKey(value.sequence()) : !batch.started;
                    })
                    .findFirst().orElse(null);
            if (pending == null) return;
            OrderBatchPending batch = pendingOrderBatches.get(pending.sequence());
            if (batch != null && !batch.started) {
                activateOrderBatch(batch, pending);
                return;
            }
            DeferredMatching deferred = deferredMatching.get(pending.sequence());
            if (deferred != null) {
                CoreResponse response = prepareMatching(pending.command(), deferred.clusterTimestamp,
                        deferred.clusterPosition, deferred.sourceKey, pending.operation(), pending);
                if (response == null) return;
                if (response.status() == ResponseStatus.REJECTED) continue;
                continue;
            }
        }
    }

    void publishMatchingCompletion(
            long sequence,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        matchingCompletions.offer(result.withCoreSequence(sequence));
    }

    private void trackMatchingFuture(
            long sequence,
            CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future) {
        activeMatchingSubmissions.add(future);
        matchingFutures.put(sequence, future);
        future.whenComplete((result, failure) -> {
            publishMatchingCompletion(sequence, matchingResult(sequence, result, failure));
            activeMatchingSubmissions.remove(future);
        });
    }

    static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingCompletion(
            CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future) {
        return future.getNow(null);
    }

    private static com.surprising.aeron.service.matching.CoreMatchingResult matchingResult(
            long sequence,
            com.surprising.aeron.service.matching.CoreMatchingResult result,
            Throwable failure) {
        return failure == null && result != null ? result.withCoreSequence(sequence)
                : new com.surprising.aeron.service.matching.CoreMatchingResult(
                        false, "EXCHANGE_CORE_FAILURE").withCoreSequence(sequence);
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> submitMatchingNow(
            PendingMatching pending) {
        matchingSubmitNanos.put(pending.sequence(), System.nanoTime());
        try {
            List<CoreOrderState> preMatchingCancellations = pending.preMatchingCancellationOrderIds().stream()
                    .map(this::runtimeOrder)
                    .filter(java.util.Objects::nonNull)
                    .filter(order -> order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                    .toList();
            long userId = pending.command().header().userId();
            java.util.function.Supplier<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                    submission = switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payloadUnsafe());
                    var order = matchingOrder(command.orderId());
                    yield () -> matchingAdapter.placeAsync(userId, order);
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payloadUnsafe());
                    var order = runtimeOrder(command.orderId());
                    String symbol = order == null ? "" : order.symbol();
                    yield () -> matchingAdapter.cancelAsyncForContinuation(userId, command.orderId(), symbol);
                }
                case REPLACE, AMEND -> {
                    var originalId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payloadUnsafe()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payloadUnsafe()).originalOrderId();
                    var order = runtimeOrder(originalId);
                    var replacement = replacementFor(pending.command(), order);
                    var matchingReplacement = matchingOrder(userId, replacement);
                    yield () -> matchingAdapter.replaceOrderAsync(userId, originalId,
                            order.symbol(), matchingReplacement);
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payloadUnsafe());
                    var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                    if (trigger == null) {
                        yield () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        false, "TRIGGER_ORDER_NOT_FOUND"));
                    }
                    PlaceOrderCommand placement = triggerPlacement(trigger, execute[2]);
                    var order = matchingOrder(placement.orderId());
                    yield () -> matchingAdapter.placeAsync(trigger.userId(), order);
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe());
                    var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                    if (liquidation == null || !com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                            .isExecutable(runtimePlaceOrderState, runtimePlaceOrderIdentities, command)) {
                        yield () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS"));
                    }
                    var orders = lifecycleOrders(liquidation.userId(),
                            runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(), command.maxOrders()).orders();
                    yield () -> matchingAdapter.cancelBatchAsync(orders);
                }
                case LIQUIDATION_BATCH -> {
                    var orders = batchCancellationOrders(pending);
                    yield () -> matchingAdapter.cancelBatchAsync(orders);
                }
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payloadUnsafe());
                    var progress = runtimeLifecycleProgress(command.symbol());
                    if (progress != null && progress.ordersComplete()) {
                        yield () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS"));
                    }
                    var orders = lifecycleOrders(0, command.symbol(), command.cursorOrderId(),
                            command.maxOrders()).orders();
                    yield () -> matchingAdapter.cancelBatchAsync(orders);
                }
            };
            return withMatchingEvidence(pending,
                    () -> matchingAdapter.executeAfterCancellations(preMatchingCancellations, submission));
        } catch (RuntimeException exception) {
            return withMatchingEvidence(pending, () -> CompletableFuture.completedFuture(
                    new com.surprising.aeron.service.matching.CoreMatchingResult(
                            false, "EXCHANGE_CORE_FAILURE")));
        }
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> withMatchingEvidence(
            PendingMatching pending,
            java.util.function.Supplier<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                    submission) {
        long orderId = 0;
        long instrumentVersion = 0;
        switch (pending.operation()) {
            case PLACE -> {
                PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(pending.command().payloadUnsafe());
                orderId = command.orderId();
                instrumentVersion = command.instrumentVersion();
            }
            case CANCEL -> {
                CancelOrderCommand command = TradingCommandCodec.decodeCancelOrder(pending.command().payloadUnsafe());
                CoreOrderState order = runtimeOrder(command.orderId());
                orderId = command.orderId();
                instrumentVersion = order == null ? 0 : order.instrumentVersion();
            }
            case REPLACE, AMEND -> {
                long originalId = pending.operation() == PendingMatching.Operation.REPLACE
                        ? TradingCommandCodec.decodeReplaceOrder(pending.command().payloadUnsafe()).originalOrderId()
                        : TradingCommandCodec.decodeAmendOrder(pending.command().payloadUnsafe()).originalOrderId();
                PlaceOrderCommand replacement = replacementFor(pending.command(), runtimeOrder(originalId));
                orderId = replacement.orderId();
                instrumentVersion = replacement.instrumentVersion();
            }
            case TRIGGER -> {
                long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                        pending.command().payloadUnsafe());
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                if (trigger != null) {
                    PlaceOrderCommand placement = triggerPlacement(trigger, execute[2]);
                    orderId = placement.orderId();
                    instrumentVersion = placement.instrumentVersion();
                }
            }
            case LIQUIDATION -> {
                var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe());
                var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                orderId = command.liquidationId();
                instrumentVersion = liquidation == null ? 0 : liquidation.instrumentVersion();
            }
            case LIQUIDATION_BATCH -> {
            }
            case SETTLEMENT -> instrumentVersion = TradingCommandCodec.decodeSettleInstrument(
                    pending.command().payloadUnsafe()).instrumentVersion();
        }
        return withMatchingEvidence(pending, orderId, instrumentVersion, submission);
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> withMatchingEvidence(
            PendingMatching pending,
            long orderId,
            long instrumentVersion,
            java.util.function.Supplier<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                    submission) {
        return matchingAdapter.executeWithEvidence(pending.sequence(), pending.command().header().commandId(),
                orderId, instrumentVersion, pending.command().header().submittedAtEpochMillis(),
                submission);
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message,
                                                                            com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        var instrument = runtimePlaceOrderState.instrument(order.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        if (message.header().messageType() == CoreMessageType.REPLACE_ORDER) {
            return TradingCommandCodec.decodeReplaceOrder(message.payloadUnsafe()).replacement();
        }
        var command = TradingCommandCodec.decodeAmendOrder(message.payloadUnsafe());
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new com.surprising.aeron.protocol.PlaceOrderCommand(command.replacementOrderId(), order.symbol(),
                order.instrumentVersion(), order.side(), priceTicks, quantitySteps,
                order.reduceOnly(), order.marginMode(), order.positionSide(),
                order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    private PlaceOrderCommand replacementForAmend(AmendOrderCommand command, CoreOrderState order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        var instrument = runtimePlaceOrderState.instrument(order.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), order.symbol(), order.instrumentVersion(),
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(),
                order.positionSide(), order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long triggeredPriceTicks) {
        var instrument = runtimePlaceOrderState.instrument(trigger.symbol());
        Long clientKey = runtimePlaceOrderIdentities.findClientKey(
                trigger.userId(), "TRIGGER:" + trigger.triggerOrderId());
        Long orderId = clientKey == null ? null : runtimePlaceOrderState.orderIdByClient(trigger.userId(), clientKey);
        var order = orderId == null ? null : runtimePlaceOrderState.order(orderId);
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND",
                        "trigger child order not found");
        }
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long limitPriceTicks = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (order.priceTicks() > 0 ? order.priceTicks() : triggeredPriceTicks) : 0;
        return new com.surprising.aeron.protocol.PlaceOrderCommand(order.orderId(), trigger.symbol(),
                trigger.instrumentVersion(), trigger.side(), limitPriceTicks, order.quantitySteps(),
                order.reduceOnly(), trigger.marginMode(), trigger.positionSide(),
                trigger.orderType(), trigger.timeInForce(), false, order.clientOrderId());
    }

    private long currentMarkPriceTicks(String symbol, long referencePriceTicks) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        var markPrice = symbolId == null ? null : runtimePlaceOrderState.markPrice(symbolId);
        long value = markPrice == null ? referencePriceTicks : markPrice.markPriceTicks();
        if (value <= 0) throw new CoreStateRejectedException("MARK_PRICE_MISSING", "mark price is required");
        return value;
    }

    public com.surprising.aeron.service.matching.CoreMatchingResult takeMatchingResult(long sequence) {
        drainMatchingCompletions();
        if (pendingMatching.isEmpty() || pendingMatching.keySet().iterator().next() != sequence) return null;
        com.surprising.aeron.service.matching.CoreMatchingResult result = completedMatching.remove(sequence);
        if (result != null) {
            matchingFutures.remove(sequence);
            return result;
        }
        return null;
    }

    com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingResult(long sequence) {
        com.surprising.aeron.service.matching.CoreMatchingResult result = takeMatchingResult(sequence);
        if (result != null) return result;
        if (pendingMatching.isEmpty() || pendingMatching.keySet().iterator().next() != sequence) return null;
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future =
                matchingFutures.get(sequence);
        if (future == null) return null;
        future.join();
        while (activeMatchingSubmissions.contains(future)) {
            Thread.onSpinWait();
        }
        return takeMatchingResult(sequence);
    }

    boolean hasPendingMatchingRejection(long sequence) {
        return pendingMatchingRejections.containsKey(sequence);
    }

    CoreResponse completeRejectedMatching(long sequence) {
        runtime.assertOwner();
        PendingMatching pending = pendingMatching.get(sequence);
        CoreResultCode resultCode = pendingMatchingRejections.get(sequence);
        if (pending == null || resultCode == null || firstPendingMatchingSequence() != sequence) return null;
        commitMatchingSequence(sequence);
        long requiredExportSequence = appendRejectedCoreFact(pending.command(), resultCode, sequence);
        long stateHash = stateHash(cachedBusinessStateHash, pending.command().header().commandId(),
                ResponseStatus.REJECTED, resultCode, sequence);
        storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence, requiredExportSequence, stateHash,
                new byte[0], 0));
        laneCommandContexts.discard(sequence);
        removePendingMatching(sequence);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, requiredExportSequence, stateHash, new byte[0]);
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        runtime.assertOwner();
        return runtime.matcherReady().thenCompose(ignored -> matchingAdapter.orderBooksStateHashAsync());
    }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        currentClusterPosition = clusterPosition;
        ensureRuntimePlaceOrderState();
        assertHealthy();
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null || matchingResult == null) return null;
        matchingFutures.remove(sequence);
        Long submitNanos = matchingSubmitNanos.remove(sequence);
        if (submitNanos != null) {
            matchingPhaseMetrics.recordExchange(System.nanoTime() - submitNanos);
        }
        long applyStartNanos = System.nanoTime();
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            throw failMatching(pending, "matcher continuation returned " + matchingResult.resultCode(), null);
        }
        long matcherSequenceBefore = appliedMatcherSequence;
        long matcherPrefixBefore = appliedMatcherPrefixDigest;
        if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
            validateMatchingEvidence(pending, matchingResult);
            appliedMatcherSequence = matchingResult.nativeCommand().matcherSequence();
            appliedMatcherPrefixDigest = matchingResult.matcherPrefix().after();
        }
        if (pendingOrderBatches.containsKey(sequence)) {
            return completeOrderBatchMatching(sequence, matchingResult, clusterTimestamp, clusterPosition);
        }
        LaneCommandContextRing.Context laneContext = laneCommandContexts.required(sequence);
        laneContext.result(matchingResult, expectedLaneMask(pending, matchingResult), validAccountLaneMask());
        TradingCoreState before = pending.beforeState();
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandLiquidationProgress = null;
        commandLiquidationBatchResult = null;
        commandRiskScanControl = null;
        resetChangeAccumulators();
        commandBeforeBusinessStateHash = pending.beforeBusinessStateHash();
        commandBeforeFundsStateHash = pending.beforeFundsStateHash();
        beginSnapshotProjectionBatch();
        if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
            commandMatcherTransition = new com.surprising.aeron.protocol.CoreMatcherTransition(
                    matchingAdapter.topology().routeVersion(), matchingResult.nativeCommand().matcherShardId(),
                    matcherSequenceBefore, matchingResult.nativeCommand().matcherSequence(),
                    matcherPrefixBefore, matchingResult.matcherPrefix().after());
        }
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        com.surprising.aeron.service.state.RuntimeTreasuryDelta[] laneTreasuryDeltas = null;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payloadUnsafe());
                    commandChangedUserIds = matchingUserIds(pending.command().header().userId(),
                            matchingResult.matcherEvents());
                    commandChangedOrderIds = matchingOrderIds(List.of(command.orderId()),
                            matchingResult.matcherEvents());
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                command.orderId(), sequence, matchingResult, laneContext);
                    } else {
                        rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId());
                    }
                    commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                            matchingResult.matcherEvents());
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payloadUnsafe());
                    commandChangedUserIds = List.of(pending.command().header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    if (matchingResult.accepted()) {
                        cancelOrderRuntime(pending.command().header().userId(), command.orderId());
                    }
                }
                case REPLACE, AMEND -> {
                    var command = replacementFor(pending.command(), runtimeOrder(
                            pending.operation() == PendingMatching.Operation.REPLACE
                                    ? TradingCommandCodec.decodeReplaceOrder(pending.command().payloadUnsafe()).originalOrderId()
                                    : TradingCommandCodec.decodeAmendOrder(pending.command().payloadUnsafe()).originalOrderId()));
                    long originalOrderId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payloadUnsafe()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payloadUnsafe()).originalOrderId();
                    commandChangedUserIds = matchingUserIds(pending.command().header().userId(),
                            matchingResult.matcherEvents());
                    commandChangedOrderIds = matchingOrderIds(List.of(originalOrderId, command.orderId()),
                            matchingResult.matcherEvents());
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        cancelOrderRuntime(pending.command().header().userId(), originalOrderId);
                        requireOrderIdentityAvailable(pending.command().header().userId(), command);
                        reservePlaceOrderRuntime(pending.command().header().userId(), command,
                                pending.command().header().commandId(), pending.sequence());
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                command.orderId(), sequence, matchingResult, laneContext);
                        commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                                matchingResult.matcherEvents());
                    }
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payloadUnsafe());
                    var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    var command = triggerPlacement(trigger, execute[2]);
                    commandChangedUserIds = matchingUserIds(trigger.userId(), matchingResult.matcherEvents());
                    commandChangedOrderIds = matchingOrderIds(List.of(command.orderId()),
                            matchingResult.matcherEvents());
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                command.orderId(), sequence, matchingResult, laneContext);
                    } else {
                        rejectPlaceOrderRuntime(trigger.userId(), command.orderId());
                    }
                    completeTriggerOrderRuntime(trigger.triggerOrderId(), matchingResult.accepted(),
                            matchingResult.accepted() ? command.orderId() : 0,
                            matchingResult.accepted() ? "" : matchingResult.resultCode(), execute[3]);
                    commandExecutions = executionViews(command.orderId(), trigger.userId(),
                            matchingResult.matcherEvents());
                    commandOrderViews = commandChangedOrderIds.stream().map(this::runtimeOrder)
                            .filter(java.util.Objects::nonNull).map(CoreProbeState::orderView).toList();
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe());
                    var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                    commandChangedLiquidationIds = List.of(command.liquidationId());
                    LifecycleOrderChunk chunk = liquidation == null ? new LifecycleOrderChunk(List.of(), 0)
                            : lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(),
                            command.maxOrders());
                    commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
                    commandChangedUserIds = liquidation == null ? List.of() : List.of(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        if (!com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                                .isExecutable(runtimePlaceOrderState, runtimePlaceOrderIdentities, command)) {
                            executeLiquidationRuntime(command, List.of());
                            commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
                        } else if (chunk.more()) {
                            advanceLiquidationCancellationRuntime(
                                    command, chunk.orders(), chunk.nextCursorOrderId());
                            commandLiquidationProgress = new CoreLiquidationProgressView(false,
                                    chunk.nextCursorOrderId(), chunk.orders().size());
                        } else {
                            executeLiquidationRuntime(command, chunk.orders());
                            commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
                        }
                    }
                }
                case LIQUIDATION_BATCH -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe());
                    applyLiquidationBatch(command, matchingResult);
                }
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payloadUnsafe());
                    if (matchingResult.accepted()) {
                        applySettlementChangedIds(command);
                        settleInstrumentRuntime(command, pending.command().header().commandId());
                    }
                }
            }
            runtimePlaceOrderState.completePendingReservations(pending.sequence());
        } catch (CoreStateRejectedException exception) {
            if (pendingMatching.size() == 1) restoreCommandState(before);
            throw failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            if (pendingMatching.size() == 1) restoreCommandState(before);
            throw failMatching(pending, "Core and matcher state diverged", exception);
        }
        if (snapshotProjectionDirty || snapshotState != before) {
            stampOrderChangesRuntime(before, clusterTimestamp, clusterPosition, commandChangedOrderIds);
        }
        completeSnapshotProjectionBatch();
        materializeChangeAccumulators();
        long laneMask = runtimePlaceOrderState.applyLaneSequence(pending.sequence(), commandChangedUserIds,
                laneContext.matchingResult(), rollingBusinessStateHash.value(), rollingFundsStateHash.value());
        if (laneMask != laneContext.expectedLaneMask()) {
            throw failMatching(pending, "account lane mask differs from immutable matcher result", null);
        }
        if (laneTreasuryDeltas == null) {
            acknowledgeAccountLanes(laneContext, before, snapshotState);
        } else {
            AccountLaneAck[] acknowledgements = runtimePlaceOrderState.accountLaneAcks(
                    sequence, laneContext.expectedLaneMask(), matchingResult, laneTreasuryDeltas);
            for (AccountLaneAck acknowledgement : acknowledgements) {
                if (acknowledgement != null) laneContext.acknowledge(acknowledgement);
            }
            requireCompleteAccountLaneAcks(laneContext);
            laneContext.treasuryDelta().apply(runtimePlaceOrderState.treasury());
            runtimePlaceOrderState.setMetadata(productLine,
                    Math.incrementExact(runtimePlaceOrderState.revision()));
            refreshSnapshotProjection();
            materializeChangeAccumulators();
        }
        if (commandOrderViews.isEmpty() && !commandChangedOrderIds.isEmpty()) {
            commandOrderViews = commandChangedOrderIds.stream()
                    .map(this::runtimeOrder)
                    .filter(java.util.Objects::nonNull)
                    .map(CoreProbeState::orderView)
                    .toList();
        }
        commandDelta = commandDelta(before, snapshotState, true);
        validateFundsConservation(pending.command(), before, snapshotState, commandDelta);
        matchingPhaseMetrics.recordApply(System.nanoTime() - applyStartNanos);
        completedMatchingCount++;
        if (MATCHING_PHASE_LOG_INTERVAL > 0 && completedMatchingCount % MATCHING_PHASE_LOG_INTERVAL == 0) {
            LOG.log(System.Logger.Level.INFO, "matching phases count=" + completedMatchingCount + " "
                    + matchingPhaseMetrics.reportAndReset());
        }
        long businessStateHash = snapshotState == before ? cachedBusinessStateHash : currentBusinessStateHash();
        long applied = sequence;
        commitMatchingSequence(sequence);
        runtimePlaceOrderState.commitLaneSequence(sequence, laneContext.expectedLaneMask());
        long requiredExportSequence = appendCoreFact(pending.command(), status, resultCode, applied,
                businessStateHash, before, snapshotState, commandDelta, commandMatcherTransition);
        if (snapshotState != before) {
            terminalRetention.observe(before, snapshotState, requiredExportSequence,
                    commandDelta.orderIds(), commandDelta.liquidationIds(), commandDelta.triggerOrderIds());
        }
        cachedBusinessStateHash = businessStateHash;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(), status, resultCode, applied);
        byte[] responseData = commandResultData(pending, matchingResult);
        storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                status, resultCode, applied, requiredExportSequence, stateHash, responseData, 0));
        laneCommandContexts.release(sequence);
        removePendingMatching(sequence);
        if (!deferredMatching.isEmpty() || !pendingOrderBatches.isEmpty()) {
            submitDeferredMatchingAfterBatch();
        }
        return new CoreResponse(status, status, resultCode, applied, requiredExportSequence, stateHash, responseData);
    }

    private static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        if (message.header().kind() == WireMessageKind.COMMAND) {
            return isMatchingCommand(message.header().messageType());
        }
        return isCommittedExportQuery(message);
    }

    private static boolean accountLaneReadQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, ORDER_STATE_HASH_QUERY, USER_STATE_QUERY, ORDER_STATE_QUERY,
                    CLIENT_ORDER_STATE_QUERY, USER_OPEN_ORDERS_QUERY, TRIGGER_ORDER_QUERY,
                    USER_OPEN_TRIGGER_ORDERS_QUERY, FUNDING_PROGRESS_QUERY, SETTLEMENT_PROGRESS_QUERY,
                    ADL_CANDIDATE_QUERY, RISK_STATE_QUERY, ALGO_ORDER_QUERY, LIQUIDATION_WORK_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    private static boolean singleUserLaneQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, USER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY,
                    USER_OPEN_ORDERS_QUERY, USER_OPEN_TRIGGER_ORDERS_QUERY, RISK_STATE_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    private static boolean isCommittedExportQuery(CoreMessage message) {
        return message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY
                || message.header().messageType() == CoreMessageType.EXPORT_STATUS_QUERY);
    }

    private long expectedLaneMask(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        long mask = 0;
        long activeUserId = pending.command().header().userId();
        if (activeUserId > 0) mask |= matchingAdapter.topology().accountLaneMask(activeUserId);
        for (MatcherEvent match : result.matcherEvents()) {
            if (match.eventType() == MatcherEventType.TRADE) {
                mask |= matchingAdapter.topology().accountLaneMask(match.matchedOrderUid());
            }
        }
        for (CoreCancellationResult cancellation : result.cancellations()) {
            CoreOrderState order = runtimeOrder(cancellation.orderId());
            if (order != null) mask |= matchingAdapter.topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            for (var action : TradingCommandCodec.decodeExecuteLiquidationBatch(
                    pending.command().payloadUnsafe()).actions()) {
                mask |= matchingAdapter.topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == PendingMatching.Operation.LIQUIDATION) {
            var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe());
            var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
            if (liquidation != null) mask |= matchingAdapter.topology().accountLaneMask(liquidation.userId());
        } else if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            String symbol = TradingCommandCodec.decodeSettleInstrument(pending.command().payloadUnsafe()).symbol();
            for (Long userId : positionUserIndex.users(symbol)) {
                mask |= matchingAdapter.topology().accountLaneMask(userId);
            }
        }
        return mask;
    }

    private long validAccountLaneMask() {
        int count = matchingAdapter.topology().accountLaneCount();
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    private void acknowledgeAccountLanes(LaneCommandContextRing.Context context,
                                         TradingCoreState before, TradingCoreState after) {
        if (before == null || after == null) throw new IllegalArgumentException("treasury ACK states are required");
        var beforeTreasury = before.treasuryState();
        var afterTreasury = after.treasuryState();
        com.surprising.aeron.service.state.RuntimeTreasuryDelta delta = new com.surprising.aeron.service.state.RuntimeTreasuryDelta();
        addTreasuryDifference(delta, beforeTreasury.feeBalances(), afterTreasury.feeBalances(), TreasuryLedger.FEE);
        addTreasuryDifference(delta, beforeTreasury.insuranceBalances(), afterTreasury.insuranceBalances(),
                TreasuryLedger.INSURANCE);
        addTreasuryDifference(delta, beforeTreasury.liquidationFeeBalances(),
                afterTreasury.liquidationFeeBalances(), TreasuryLedger.INSURANCE);
        addTreasuryDifference(delta, beforeTreasury.insuranceDeficits(), afterTreasury.insuranceDeficits(),
                TreasuryLedger.DEFICIT);
        addTreasuryDifference(delta, beforeTreasury.fundingResidualBalances(),
                afterTreasury.fundingResidualBalances(), TreasuryLedger.FUNDING_RESIDUAL);
        addTreasuryDifference(delta, beforeTreasury.roundingResidualBalances(),
                afterTreasury.roundingResidualBalances(), TreasuryLedger.ROUNDING_RESIDUAL);
        addTreasuryDifference(delta, beforeTreasury.clearingPnlBalances(), afterTreasury.clearingPnlBalances(),
                TreasuryLedger.CLEARING);
        boolean treasuryAckAssigned = false;
        for (var lane : runtimePlaceOrderState.accountLaneViews(context.expectedLaneMask())) {
            boolean aggregate = !treasuryAckAssigned;
            context.acknowledge(new AccountLaneAck(context.coreSequence(), lane.laneId(), lane.revision(),
                    lane.localStateHash(), lane.localFundsHash(), context.matchingResult(),
                    aggregate ? delta : new com.surprising.aeron.service.state.RuntimeTreasuryDelta()));
            treasuryAckAssigned = true;
        }
        requireCompleteAccountLaneAcks(context);
        var aggregate = context.treasuryDelta();
        if (!sameTreasuryDelta(aggregate, delta)) {
            throw new IllegalStateException("account lane Treasury ACK aggregation mismatch");
        }
    }

    private void requireCompleteAccountLaneAcks(LaneCommandContextRing.Context context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
        for (var lane : runtimePlaceOrderState.accountLaneViews(context.expectedLaneMask())) {
            context.validate(lane);
        }
    }

    private void addTreasuryDifference(
            com.surprising.aeron.service.state.RuntimeTreasuryDelta delta,
            Map<String, Long> before,
            Map<String, Long> after,
            TreasuryLedger ledger) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>(before.keySet());
        assets.addAll(after.keySet());
        for (String asset : assets) {
            long units = Math.subtractExact(after.getOrDefault(asset, 0L), before.getOrDefault(asset, 0L));
            if (units == 0) continue;
            int assetId = runtimePlaceOrderIdentities.assetId(asset);
            switch (ledger) {
                case FEE -> delta.addFee(assetId, units);
                case INSURANCE -> delta.addInsurance(assetId, units);
                case DEFICIT -> delta.addDeficit(assetId, units);
                case FUNDING_RESIDUAL -> delta.addFundingResidual(assetId, units);
                case ROUNDING_RESIDUAL -> delta.addRoundingResidual(assetId, units);
                case CLEARING -> delta.addClearing(assetId, units);
            }
        }
    }

    private static boolean sameTreasuryDelta(
            com.surprising.aeron.service.state.RuntimeTreasuryDelta left,
            com.surprising.aeron.service.state.RuntimeTreasuryDelta right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.assetId(index) != right.assetId(index)
                    || left.feeUnits(index) != right.feeUnits(index)
                    || left.insuranceUnits(index) != right.insuranceUnits(index)
                    || left.deficitUnits(index) != right.deficitUnits(index)
                    || left.fundingResidualUnits(index) != right.fundingResidualUnits(index)
                    || left.roundingResidualUnits(index) != right.roundingResidualUnits(index)
                    || left.clearingUnits(index) != right.clearingUnits(index)) return false;
        }
        return true;
    }

    private enum TreasuryLedger {
        FEE,
        INSURANCE,
        DEFICIT,
        FUNDING_RESIDUAL,
        ROUNDING_RESIDUAL,
        CLEARING
    }

    private void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (!matchingResult.accepted()) {
            int obsolete = batch.actions().stream()
                    .map(action -> runtimePlaceOrderState.liquidation(action.liquidationId()))
                    .mapToInt(liquidation -> liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                            || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                            || liquidation.status() == CoreLiquidationState.Status.CANCELED ? 1 : 0)
                    .sum();
            commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), 0,
                    batch.actions().size() - obsolete, obsolete, 0, 0);
            return;
        }
        int remaining = batch.maxCancelOrders();
        int applied = 0;
        int pending = 0;
        int obsolete = 0;
        int processedOrders = 0;
        List<Long> changedOrders = new ArrayList<>(commandChangedOrderIds);
        List<Long> changedUsers = new ArrayList<>(commandChangedUserIds);
        for (var action : batch.actions()) {
            var liquidation = runtimePlaceOrderState.liquidation(action.liquidationId());
            if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                    || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.CANCELED) {
                obsolete++;
                continue;
            }
            if (remaining == 0) {
                pending++;
                continue;
            }
            ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                    action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                    action.cursorOrderId(), Math.min(remaining, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            changedOrders.addAll(chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList());
            changedUsers.add(liquidation.userId());
            if (!com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                    .isExecutable(runtimePlaceOrderState, runtimePlaceOrderIdentities, single)) {
                executeLiquidationRuntime(single, List.of());
                applied++;
                continue;
            }
            processedOrders += chunk.orders().size();
            remaining -= chunk.orders().size();
            if (chunk.more()) {
                advanceLiquidationCancellationRuntime(single, chunk.orders(), chunk.nextCursorOrderId());
                pending++;
                continue;
            }
            executeLiquidationRuntime(single, chunk.orders());
            applied++;
        }
        commandChangedOrderIds = changedOrders.stream().distinct().toList();
        commandChangedUserIds = changedUsers.stream().distinct().toList();
        commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied, pending,
                obsolete, processedOrders, 0);
        if (batch.riskScanContinuation() != null) {
            var scan = runtimePlaceOrderState.firstIncompleteRiskScan();
            var continuation = batch.riskScanContinuation();
            if (scan != null
                    && runtimePlaceOrderIdentities.symbol(scan.symbolId()).equals(continuation.symbol())
                    && scan.priceSequence() == continuation.priceSequence()
                    && scan.lastUserId() == continuation.lastUserId()) {
                long beforeRevision = runtimePlaceOrderState.revision();
                RuntimePerpetualRiskProcessor.applyContinuationRuntime(batch.maxRiskScanUsers(),
                        positionUserIndex.users(continuation.symbol()), runtimePlaceOrderState,
                        runtimePlaceOrderIdentities);
                if (runtimePlaceOrderState.revision() != beforeRevision) refreshSnapshotProjection();
                commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied,
                        pending, obsolete, processedOrders, batch.maxRiskScanUsers());
            }
        }
    }

    private PendingMatching applyBatchSuccessfulPrefix(PendingMatching pending,
                                                        com.surprising.aeron.service.matching.CoreMatchingResult result,
                                                        long clusterTimestamp, long clusterPosition) {
        var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payloadUnsafe());
        int successful = result.successfulPrefixCount();
        int remaining = batch.maxCancelOrders();
        int successLeft = successful;
        List<ExecuteLiquidationBatchAction> nextActions = new ArrayList<>(batch.actions());
        List<Long> changedOrders = new ArrayList<>();
        List<Long> changedUsers = new ArrayList<>();
        int actionIndex = 0;
        for (var action : batch.actions()) {
            if (remaining == 0 || successLeft == 0) break;
            var liquidation = runtimePlaceOrderState.liquidation(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) {
                actionIndex++;
                continue;
            }
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            int count = Math.min(successLeft, chunk.orders().size());
            if (count == 0) break;
            List<CoreOrderState> prefix = chunk.orders().subList(0, count);
            changedOrders.addAll(prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList());
            changedUsers.add(liquidation.userId());
            remaining -= count;
            successLeft -= count;
            ExecuteLiquidationCommand single = new ExecuteLiquidationCommand(action.liquidationId(),
                    action.triggerPriceSequence(), action.executionPriceTicks(), batch.liquidationFeeRatePpm(),
                    action.cursorOrderId(), Math.min(remaining + count, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS));
            if (chunk.more() || count < chunk.orders().size()) {
                long nextCursor = count < chunk.orders().size()
                        ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
                advanceLiquidationCancellationRuntime(single, prefix, nextCursor);
            } else {
                executeLiquidationRuntime(single, prefix);
            }
            var next = runtimePlaceOrderState.liquidation(action.liquidationId());
            long nextCursor = next != null && next.status() == CoreLiquidationState.Status.ORDERED
                    ? next.nextCancelOrderId() : action.cursorOrderId();
            nextActions.set(actionIndex, new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(),
                    action.symbol(), action.instrumentVersion(), action.triggerPriceSequence(),
                    action.executionPriceTicks(), nextCursor));
            actionIndex++;
        }
        commandChangedOrderIds = changedOrders.stream().distinct().toList();
        commandChangedUserIds = changedUsers.stream().distinct().toList();
        if (!nextActions.equals(batch.actions())) {
            var nextBatch = new ExecuteLiquidationBatchCommand(nextActions, batch.maxCancelOrders(),
                    batch.liquidationFeeRatePpm(), batch.riskScanContinuation(), batch.maxRiskScanUsers());
            pending = pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeExecuteLiquidationBatch(nextBatch)));
        }
        return pending;
    }

    private PendingMatching applySuccessfulCancellationPrefix(PendingMatching pending,
                                                               com.surprising.aeron.service.matching.CoreMatchingResult result) {
        return switch (pending.operation()) {
            case LIQUIDATION_BATCH -> applyBatchSuccessfulPrefix(pending, result, 0, 0);
            case LIQUIDATION -> applyLiquidationSuccessfulPrefix(pending, result);
            case SETTLEMENT -> applySettlementSuccessfulPrefix(pending, result);
            default -> pending;
        };
    }

    private PendingMatching applyLiquidationSuccessfulPrefix(PendingMatching pending,
                                                              com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payloadUnsafe());
        var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
        if (liquidation == null || !com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                .isExecutable(runtimePlaceOrderState, runtimePlaceOrderIdentities, command)) return pending;
        LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                command.cursorOrderId(), command.maxOrders());
        int count = Math.min(result.successfulPrefixCount(), chunk.orders().size());
        if (count == 0) return pending;
        List<CoreOrderState> prefix = chunk.orders().subList(0, count);
        commandChangedUserIds = List.of(liquidation.userId());
        commandChangedOrderIds = prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        if (chunk.more() || count < chunk.orders().size()) {
            long nextCursor = count < chunk.orders().size() ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
            advanceLiquidationCancellationRuntime(command, prefix, nextCursor);
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            command.liquidationId(), command.triggerPriceSequence(), command.executionPriceTicks(),
                            command.liquidationFeeRatePpm(), nextCursor, command.maxOrders()))));
        }
        executeLiquidationRuntime(command, prefix);
        return pending;
    }

    private PendingMatching applySettlementSuccessfulPrefix(PendingMatching pending,
                                                              com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payloadUnsafe());
        var progress = runtimeLifecycleProgress(command.symbol());
        if (progress != null && progress.ordersComplete()) {
            return pending;
        }
        LifecycleOrderChunk chunk = lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        int count = Math.min(result.successfulPrefixCount(), chunk.orders().size());
        if (count == 0) return pending;
        List<CoreOrderState> prefix = chunk.orders().subList(0, count);
        commandChangedUserIds = prefix.stream().map(CoreOrderState::userId).distinct().toList();
        commandChangedOrderIds = prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        if (chunk.more() || count < chunk.orders().size()) {
            long nextCursor = count < chunk.orders().size() ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
            advanceSettlementCancellationRuntime(
                    command, prefix, nextCursor, pending.command().header().commandId());
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeSettleInstrument(new com.surprising.aeron.protocol.SettleInstrumentCommand(
                            command.settlementId(), command.symbol(), command.instrumentVersion(),
                            command.settlementPriceTicks(), command.optionCashUnitsPerContract(), command.cursorUserId(),
                            command.maxUsers(), nextCursor, command.maxOrders()))));
        }
        settleInstrumentRuntime(command, pending.command().header().commandId());
        return pending;
    }

    private boolean matchingResultNeedsRecovery(PendingMatching pending,
                                                com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (result.accepted()) return false;
        if (result.matcherStateChanged()) {
            java.util.Set<Long> expected = java.util.Set.copyOf(pending.preMatchingCancellationOrderIds());
            boolean knownPrefix = !expected.isEmpty() && result.matcherEvents().stream()
                    .noneMatch(event -> event.eventType() == MatcherEventType.TRADE)
                    && result.cancellations().stream().filter(CoreCancellationResult::accepted)
                    .map(CoreCancellationResult::orderId).allMatch(expected::contains);
            if (!knownPrefix) return true;
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT) return true;
        return "EXCHANGE_CORE_FAILURE".equals(result.resultCode())
                || "MATCHING_TIMEOUT".equals(result.resultCode());
    }

    private void applyPreMatchingCancellations(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (pending.preMatchingCancellationOrderIds().isEmpty()) return;
        java.util.Set<Long> expected = java.util.Set.copyOf(pending.preMatchingCancellationOrderIds());
        LinkedHashSet<Long> changedOrders = new LinkedHashSet<>(commandChangedOrderIds);
        LinkedHashSet<Long> changedUsers = new LinkedHashSet<>(commandChangedUserIds);
        for (CoreCancellationResult cancellation : result.cancellations()) {
            if (!cancellation.accepted() || !expected.contains(cancellation.orderId())) continue;
            CoreOrderState order = runtimeOrder(cancellation.orderId());
            if (order == null || order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
                continue;
            }
            cancelOrderRuntime(order.userId(), order.orderId());
            changedOrders.add(order.orderId());
            changedUsers.add(order.userId());
        }
        commandChangedOrderIds = List.copyOf(changedOrders);
        commandChangedUserIds = List.copyOf(changedUsers);
    }

    private void validateMatchingEvidence(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var nativeCommand = result.nativeCommand();
        var prefix = result.matcherPrefix();
        UUID commandId;
        try {
            commandId = UUID.fromString(nativeCommand.commandId());
        } catch (IllegalArgumentException exception) {
            throw failMatching(pending, "matcher result command identity is malformed", exception);
        }
        if (nativeCommand.coreSequence() != pending.sequence()
                || !commandId.equals(pending.command().header().commandId())
                || nativeCommand.matcherSequence() <= appliedMatcherSequence
                || !prefix.bound()
                || prefix.before() != appliedMatcherPrefixDigest
                || prefix.after() == prefix.before()) {
            throw failMatching(pending, "matcher result prefix does not continue the applied prefix"
                    + " expectedSequenceAfter=" + appliedMatcherSequence
                    + " actualSequence=" + nativeCommand.matcherSequence()
                    + " expectedPrefix=" + appliedMatcherPrefixDigest
                    + " actualBefore=" + prefix.before()
                    + " actualAfter=" + prefix.after(), null);
        }
    }

    boolean isMatchingPending(UUID commandId) {
        return pendingMatchingByCommandId.containsKey(commandId);
    }

    long matchingSequence(UUID commandId) {
        return pendingMatchingByCommandId.getOrDefault(commandId, 0L);
    }

    void drainMatchingCompletions() {
        if (matchingCompletions.consumeOverflow()) {
            fatalFailure = new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                    "matching completion queue", firstPendingMatchingSequence(), 0,
                    "matching completion queue is full");
            throw fatalFailure;
        }
        com.surprising.aeron.service.matching.CoreMatchingResult completion;
        while ((completion = matchingCompletions.poll()) != null) {
            long sequence = completion.nativeCommand().coreSequence();
            if (pendingMatching.containsKey(sequence)) {
                completedMatching.putIfAbsent(sequence, completion);
            }
        }
    }

    public long querySequence(UUID queryId) {
        return queryIds.getOrDefault(queryId, 0L);
    }

    public CoreResponse takeQueryResult(long queryId) {
        if (failedQueries.remove(queryId) != null) {
            queryIds.values().removeIf(value -> value == queryId);
            return rejected(CoreResultCode.MATCHING_REJECTED);
        }
        CompletedBookQuery completed = completedBookQueries.remove(queryId);
        if (completed == null) return null;
        queryIds.values().removeIf(value -> value == queryId);
        long exportSequence = Math.decrementExact(exportState.nextSequence());
        if (completed.bootstrapSnapshot() == null) {
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    private CoreResponse bootstrapPageResponse(BookBootstrapSession session, CoreOrderBookBootstrapQuery query) {
        if (!query.symbolCursor().isEmpty() && !session.symbols().containsKey(query.symbolCursor())) {
            return rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
        }
        List<String> symbols = session.symbols().tailMap(query.symbolCursor(), false).keySet().stream()
                .limit(query.limit()).toList();
        int expectedLevels = 0;
        for (String symbol : symbols) {
            expectedLevels = Math.addExact(expectedLevels, session.symbols().get(symbol).size());
        }
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
        for (String symbol : symbols) levels.addAll(session.symbols().get(symbol));
        boolean complete = symbols.isEmpty()
                || session.symbols().higherKey(symbols.getLast()) == null;
        String nextCursor = complete ? "" : symbols.getLast();
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(session.snapshotId(),
                session.exportSequence(), nextCursor, complete, levels);
        byte[] encoded = CoreStateQueryCodec.encodeOrderBookBootstrapPage(page);
        return boundedBookResponse(levels.size(), encoded);
    }

    private CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > MAX_BOOK_RESPONSE_LEVELS || encoded.length > MAX_BOOK_RESPONSE_BYTES) {
            return rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash, encoded);
    }

    public int pendingMatchingCount() {
        return pendingMatching.size();
    }

    public CoreLaneMetrics laneMetrics() {
        runtime.assertOwner();
        int count = matchingAdapter.topology().accountLaneCount();
        long[] revisions = new long[count];
        long[] applied = new long[count];
        long[] committed = new long[count];
        int[] queueDepths = new int[count];
        int[] queueCapacities = new int[count];
        int[] queueHighWaterMarks = new int[count];
        long[] rejectedSubmissions = new long[count];
        long[] oldestPendingSequences = new long[count];
        long[] completedOperations = new long[count * CoreLaneMetrics.OPERATION_TYPE_COUNT];
        long[] latencySamples = new long[completedOperations.length];
        long[] totalLatencyNanos = new long[completedOperations.length];
        long[] maxLatencyNanos = new long[completedOperations.length];
        for (int laneId = 0; laneId < count; laneId++) {
            var lane = runtimePlaceOrderState.accountLaneById(laneId);
            var laneMetrics = runtimePlaceOrderState.accountLaneMetricsById(laneId);
            revisions[laneId] = lane.revision();
            applied[laneId] = lane.appliedSequence();
            committed[laneId] = lane.committedSequence();
            queueDepths[laneId] = laneMetrics.queueDepth();
            queueCapacities[laneId] = laneMetrics.queueCapacity();
            queueHighWaterMarks[laneId] = laneMetrics.queueHighWaterMark();
            rejectedSubmissions[laneId] = laneMetrics.rejectedSubmissions();
            oldestPendingSequences[laneId] = laneMetrics.oldestPendingSequence();
            System.arraycopy(laneMetrics.completedOperations(), 0, completedOperations,
                    laneId * CoreLaneMetrics.OPERATION_TYPE_COUNT, CoreLaneMetrics.OPERATION_TYPE_COUNT);
            System.arraycopy(laneMetrics.totalLatencyNanos(), 0, totalLatencyNanos,
                    laneId * CoreLaneMetrics.OPERATION_TYPE_COUNT, CoreLaneMetrics.OPERATION_TYPE_COUNT);
            System.arraycopy(laneMetrics.latencySamples(), 0, latencySamples,
                    laneId * CoreLaneMetrics.OPERATION_TYPE_COUNT, CoreLaneMetrics.OPERATION_TYPE_COUNT);
            System.arraycopy(laneMetrics.maxLatencyNanos(), 0, maxLatencyNanos,
                    laneId * CoreLaneMetrics.OPERATION_TYPE_COUNT, CoreLaneMetrics.OPERATION_TYPE_COUNT);
        }
        return new CoreLaneMetrics(matchingAdapter.topology().matchingEngineCount(), count,
                matchingAdapter.dispatchDepth(), matchingAdapter.dispatchCapacity(),
                matchingAdapter.dispatchHighWaterMark(), matchingCompletions.depth(),
                matchingCompletions.capacity(), matchingCompletions.highWaterMark(),
                laneCommandContexts.inFlight(), laneCommandContexts.capacity(),
                laneCommandContexts.highWaterMark(), committedCoreSequence,
                revisions, applied, committed, queueDepths, queueCapacities, queueHighWaterMarks,
                rejectedSubmissions, oldestPendingSequences,
                completedOperations, latencySamples, totalLatencyNanos, maxLatencyNanos);
    }

    private com.surprising.aeron.protocol.CoreLaneMetricsView laneMetricsView() {
        CoreLaneMetrics metrics = laneMetrics();
        return new com.surprising.aeron.protocol.CoreLaneMetricsView(
                metrics.matchingEngineCount(), metrics.accountLaneCount(),
                metrics.matcherDispatchDepth(), metrics.matcherDispatchCapacity(),
                metrics.matcherDispatchHighWaterMark(), metrics.matchingCompletionDepth(),
                metrics.matchingCompletionCapacity(), metrics.matchingCompletionHighWaterMark(),
                metrics.commandContextDepth(), metrics.commandContextCapacity(),
                metrics.commandContextHighWaterMark(), metrics.committedCoreSequence(),
                metrics.accountLaneRevisions(), metrics.accountLaneAppliedSequences(),
                metrics.accountLaneCommittedSequences(), metrics.accountLaneQueueDepths(),
                metrics.accountLaneQueueCapacities(), metrics.accountLaneQueueHighWaterMarks(),
                metrics.accountLaneRejectedSubmissions(), metrics.accountLaneOldestPendingSequences(),
                metrics.accountLaneCompletedOperations(), metrics.accountLaneLatencySamples(),
                metrics.accountLaneTotalLatencyNanos(),
                metrics.accountLaneMaxLatencyNanos());
    }

    public long firstPendingMatchingSequence() {
        return pendingMatching.isEmpty() ? 0 : pendingMatching.keySet().iterator().next();
    }

    boolean hasPendingMatchingForUser(long userId) {
        if (userId <= 0) return false;
        for (PendingMatching pending : pendingMatching.values()) {
            if (pending.command().header().userId() == userId) return true;
        }
        return false;
    }


    Map<Long, PendingMatching> pendingMatching() {
        return Collections.unmodifiableMap(pendingMatching);
    }

    com.surprising.aeron.service.state.LaneTopology laneTopology() {
        return matchingAdapter.topology();
    }

    List<com.surprising.aeron.service.state.AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        return runtimePlaceOrderState.accountLaneSnapshots(fenceSequence, globalState);
    }

    void restoreAccountLaneSnapshots(
            List<com.surprising.aeron.service.state.AccountLaneSnapshot> snapshots,
            long fenceSequence) {
        runtimePlaceOrderState.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    PendingMatching pendingMatching(long sequence) {
        return pendingMatching.get(sequence);
    }

    int terminalRetentionCandidateCount() {
        return terminalRetention.candidateCount();
    }

    int terminalRetentionTombstoneCount() {
        return terminalRetention.tombstoneCount();
    }

    TerminalStateRetention terminalRetention() {
        return terminalRetention;
    }

    public long stateHash() {
        return stateHash(cachedBusinessStateHash);
    }

    private long stateHash(long businessStateHash) {
        return stateHash(businessStateHash, null, null, null, 0);
    }

    private long stateHash(long businessStateHash, UUID commandId, ResponseStatus commandStatus,
                           CoreResultCode commandResultCode, long commandAppliedCommandCount) {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, productLine.ordinal());
        hash = mix(hash, appliedCommandCount);
        hash = mix(hash, probeValue);
        hash = mix(hash, businessStateHash);
        hash = mix(hash, exportState.acknowledgedSequence());
        hash = mix(hash, exportState.nextSequence());
        hash = mix(hash, exportState.pendingCount());
        hash = mix(hash, exportState.pendingDigest());
        hash = mix(hash, terminalRetention.digest());
        hash = mix(hash, lastSourceSequenceDigest);
        hash = mix(hash, commandResults.size());
        hash = mix(hash, commandResultBytes);
        hash = mix(hash, commandResultsDigest);
        if (commandId != null) {
            hash = mix(hash, commandId.getMostSignificantBits());
            hash = mix(hash, commandId.getLeastSignificantBits());
            hash = mix(hash, commandStatus.wireCode());
            hash = mix(hash, commandResultCode.wireCode());
            hash = mix(hash, commandAppliedCommandCount);
        }
        return hash;
    }

    public byte[] snapshot() {
        return snapshot(Math.max(1, Math.addExact(appliedCommandCount, 1)));
    }

    public byte[] snapshot(long snapshotId) {
        assertHealthy();
        long deadlineNanos = Math.addExact(System.nanoTime(), java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = pollSnapshotSections(0, 0, System.nanoTime());
            if (snapshot != null) return snapshot.toByteArray();
            Thread.yield();
        }
    }

    void beginSnapshot(long snapshotId, long deadlineNanos) {
        runtime.assertOwner();
        assertHealthy();
        if (snapshotId <= 0 || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid snapshot fence");
        }
        if (snapshotFence != null) {
            if (snapshotFence.snapshotId == snapshotId) {
                snapshotFence.deadlineNanos = Math.min(snapshotFence.deadlineNanos, deadlineNanos);
                return;
            }
            throw new SnapshotNotReadyException();
        }
        if (inFlightMatcherSnapshot.get() != null) throw new SnapshotNotReadyException();
        snapshotFence = new SnapshotFence(snapshotId, deadlineNanos);
    }

    SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        runtime.assertOwner();
        assertHealthy();
        SnapshotFence fence = snapshotFence;
        if (fence == null) throw new IllegalStateException("snapshot fence is not active");
        if (Thread.currentThread().isInterrupted()) {
            releaseSnapshotFence();
            throw new IllegalStateException("snapshot fence interrupted");
        }
        if (nowNanos >= fence.deadlineNanos) {
            releaseSnapshotFence();
            throw new SnapshotFenceTimeoutException();
        }
        try {
            drainMatchingCompletions();
            while (!pendingMatching.isEmpty()) {
                long sequence = firstPendingMatchingSequence();
                com.surprising.aeron.service.matching.CoreMatchingResult result = completedMatching.remove(sequence);
                if (result == null) return null;
                if (completeMatching(sequence, result, clusterTimestamp, clusterPosition) == null) return null;
                drainMatchingCompletions();
            }
            if (laneCommandContexts.inFlight() != 0 || matchingCompletions.depth() != 0) {
                throw new IllegalStateException("snapshot fence contains unfinished lane or matcher work");
            }
            if (fence.matcherSnapshot == null) {
                fence.coreSequence = appliedCommandCount;
                CompletableFuture<MatcherSnapshot> matcherSnapshot = matcherSnapshotCapture.capture(
                        fence.snapshotId, fence.coreSequence, snapshotState, activeOrderIndex.orders());
                if (!inFlightMatcherSnapshot.compareAndSet(null, matcherSnapshot)) {
                    throw new SnapshotNotReadyException();
                }
                fence.matcherSnapshot = matcherSnapshot;
                matcherSnapshot.whenComplete((ignored, failure) ->
                        inFlightMatcherSnapshot.compareAndSet(matcherSnapshot, null));
            }
            if (!fence.matcherSnapshot.isDone()) return null;
            MatcherSnapshot matcherSnapshot = fence.matcherSnapshot.getNow(null);
            if (matcherSnapshot == null || appliedCommandCount != fence.coreSequence || !pendingMatching.isEmpty()) {
                throw new IllegalStateException("snapshot fence state changed during capture");
            }
            SectionedCoreSnapshotCodec.SectionedSnapshot encoded =
                    SectionedCoreSnapshotCodec.encode(this, matcherSnapshot, fence.snapshotId,
                            fence.coreSequence, clusterTimestamp, clusterPosition);
            snapshotFence = null;
            return encoded;
        } catch (RuntimeException failure) {
            releaseSnapshotFence();
            throw failure;
        }
    }

    byte[] pollSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        return snapshot == null ? null : snapshot.toByteArray();
    }

    byte[] captureSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        byte[] snapshot = pollSnapshot(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    private void releaseSnapshotFence() {
        snapshotFence = null;
    }

    static final class SnapshotNotReadyException extends IllegalStateException {
        private SnapshotNotReadyException() {
            super("snapshot not ready");
        }
    }

    static final class SnapshotFenceTimeoutException extends IllegalStateException {
        private SnapshotFenceTimeoutException() {
            super("snapshot fence timed out");
        }
    }

    private static final class SnapshotFence {
        private final long snapshotId;
        private long deadlineNanos;
        private long coreSequence = -1;
        private CompletableFuture<MatcherSnapshot> matcherSnapshot;

        private SnapshotFence(long snapshotId, long deadlineNanos) {
            this.snapshotId = snapshotId;
            this.deadlineNanos = deadlineNanos;
        }
    }

    @FunctionalInterface
    interface MatcherSnapshotCapture {
        CompletableFuture<MatcherSnapshot> capture(
                long snapshotId,
                long coreSequence,
                TradingCoreState state,
                Iterable<CoreOrderState> activeOrders);
    }

    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException failMatching(
            PendingMatching pending,
            String detail,
            Throwable cause) {
        fatalFailure = cause == null
                ? new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                        pending.operation().name(), pending.sequence(), 0, detail)
                : new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                        pending.operation().name(), pending.sequence(), 0, detail, cause);
        return fatalFailure;
    }

    private void assertHealthy() {
        if (fatalFailure != null) throw fatalFailure;
    }

    public static CoreProbeState fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return CoreStateSnapshotCodec.decode(snapshot, productLine);
    }

    public static CoreSnapshotManifest inspectSnapshot(ProductLine productLine, byte[] snapshot) {
        return CoreStateSnapshotCodec.manifest(snapshot, productLine);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public long appliedCommandCount() {
        return appliedCommandCount;
    }

    public long committedCoreSequence() {
        return committedCoreSequence;
    }

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        return snapshotState;
    }

    TradingCoreState snapshotTradingState() {
        return com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(
                runtimePlaceOrderState, runtimePlaceOrderIdentities);
    }

    Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> feePolicies() {
        return runtimePlaceOrderState.feePoliciesSnapshot();
    }

    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> policies) {
        runtimePlaceOrderState.restoreFeePolicies(policies);
        cachedFeePolicyHash = computeFeePolicyHash(policies);
        cachedBusinessStateHash = currentBusinessStateHash();
    }

    Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers() {
        return runtimePlaceOrderState.pendingTransfersSnapshot();
    }

    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        runtimePlaceOrderState.restorePendingTransfers(transfers);
        cachedTransferHash = computeTransferHash(transfers);
        cachedBusinessStateHash = currentBusinessStateHash();
    }

    CoreExportState exportState() {
        return exportState;
    }

    long sourceSequenceDigest() {
        return lastSourceSequenceDigest;
    }

    Map<UUID, StoredResult> commandResults() {
        return Collections.unmodifiableMap(commandResults);
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences);
    }

    private ResponseStatus applyCommand(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payloadUnsafe()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> {
                commandChangedUserIds = List.of(message.header().userId());
                RuntimeCommandProcessor.adjustBalance(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
                refreshSnapshotProjection();
            }
            case TRANSFER_OUT -> {
                commandChangedUserIds = List.of(message.header().userId());
                RuntimeCommandProcessor.transferOut(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(), TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe()));
                cachedTransferHash = computeTransferHash(runtimePlaceOrderState.pendingTransfersSnapshot());
                refreshSnapshotProjection();
            }
            case TRANSFER_IN -> {
                commandChangedUserIds = List.of(message.header().userId());
                RuntimeCommandProcessor.transferIn(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(), TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe()));
                refreshSnapshotProjection();
            }
            case COMPLETE_TRANSFER -> {
                RuntimeCommandProcessor.completeTransfer(runtimePlaceOrderState, message.header().userId(),
                        TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId());
                cachedTransferHash = computeTransferHash(runtimePlaceOrderState.pendingTransfersSnapshot());
                refreshSnapshotProjection();
            }
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                    PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                    EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                    throw new IllegalStateException("matching command must use async continuation");
            case UPSERT_INSTRUMENT -> {
                var command = TradingCommandCodec.decodeUpsertInstrument(message.payloadUnsafe());
                RuntimeCommandProcessor.upsertInstrument(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, command);
                refreshSnapshotProjection();
            }
            case APPLY_MARK_PRICE -> {
                var command = TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe());
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                RuntimePerpetualRiskProcessor.applyMarkPriceRuntime(command,
                        positionUserIndex.users(command.symbol()), runtimePlaceOrderState,
                        runtimePlaceOrderIdentities);
                refreshSnapshotProjection();
                initializeTriggerScan(command);
                logRiskScan("mark-price", command.symbol(), runtimePlaceOrderState.riskScanControl().scanBatchSize(),
                        pendingBefore, startedAt);
                evaluateMarkPriceTriggers(command, message.header().commandId(), message.header().submittedAtEpochMillis());
            }
            case APPLY_FUNDING -> {
                var command = TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe());
                Iterable<Long> indexedUserIds = positionUserIndex.users(command.symbol());
                var result = RuntimePerpetualFundingProcessor.applyRuntime(command, indexedUserIds,
                        message.header().commandId(), runtimePlaceOrderState, runtimePlaceOrderIdentities);
                if (result.state() != runtimePlaceOrderState) {
                    throw new IllegalStateException("funding processor replaced authoritative runtime state");
                }
                refreshSnapshotProjection();
                commandFundingPayments = result.payments();
                commandFundingProgress = result.progress();
                commandChangedUserIds = commandFundingPayments.stream()
                        .map(com.surprising.aeron.protocol.CoreFundingPaymentView::userId).distinct().toList();
            }
            case EXECUTE_ADL -> {
                var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
                commandChangedUserIds = List.of(command.targetUserId());
                RuntimePerpetualLiquidationProcessor.applyAdlRuntime(
                        command, runtimePlaceOrderState, runtimePlaceOrderIdentities);
                refreshSnapshotProjection();
            }
            case RESOLVE_LIQUIDATION -> {
                var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
                RuntimePerpetualLiquidationProcessor.applyResolutionRuntime(
                        command, runtimePlaceOrderState, runtimePlaceOrderIdentities);
                refreshSnapshotProjection();
            }
            case CONTINUE_RISK_SCAN -> {
                var command = TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe());
                var control = runtimePlaceOrderState.riskScanControl();
                if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
                    throw new CoreStateRejectedException("INVALID_COMMAND",
                            "risk scan continuation exceeds current control");
                }
                var activeScan = runtimePlaceOrderState.firstIncompleteRiskScan();
                if (activeScan == null) break;
                String symbol = runtimePlaceOrderIdentities.symbol(activeScan.symbolId());
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                long beforeRevision = runtimePlaceOrderState.revision();
                RuntimePerpetualRiskProcessor.applyContinuationRuntime(command.maxUsers(),
                        positionUserIndex.users(symbol), runtimePlaceOrderState, runtimePlaceOrderIdentities);
                if (runtimePlaceOrderState.revision() != beforeRevision) {
                    refreshSnapshotProjection();
                }
                evaluatePendingTriggerScan(symbol);
                logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
            }
            case UPDATE_RISK_SCAN_CONTROL -> {
                var command = CoreRiskScanControlCodec.decodeCommand(message.payloadUnsafe());
                RuntimeCommandProcessor.updateRiskScanControl(runtimePlaceOrderState, command, clusterTimestamp);
                refreshSnapshotProjection();
                commandRiskScanControl = runtimePlaceOrderState.riskScanControl();
            }
            case UPSERT_FEE_POLICY -> {
                runtimePlaceOrderState.upsertFeePolicy(
                        TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe()));
                cachedFeePolicyHash = computeFeePolicyHash(runtimePlaceOrderState.feePoliciesSnapshot());
                refreshSnapshotProjection();
            }
            case ACK_EXPORT -> {
                exportState.acknowledge(CoreExportCodec.decodeAck(message.payloadUnsafe()));
                TerminalPruneBatch pruneBatch = terminalRetention.eligible(snapshotState,
                        exportState.acknowledgedSequence(), TerminalStateRetention.MAX_PRUNE_PER_ACK);
                commandChangedUserIds = pruneBatch.orderIds().stream()
                        .map(this::runtimeOrder)
                        .filter(java.util.Objects::nonNull)
                        .map(com.surprising.aeron.service.state.CoreOrderState::userId)
                        .distinct().toList();
                commandChangedOrderIds = pruneBatch.orderIds();
                commandChangedLiquidationIds = pruneBatch.liquidationIds();
                commandChangedTriggerOrderIds = pruneBatch.triggerOrderIds();
                if (!pruneBatch.isEmpty()) {
                    RuntimeCommandProcessor.pruneTerminalState(
                            runtimePlaceOrderState, runtimePlaceOrderIdentities, pruneBatch);
                    refreshSnapshotProjection();
                }
                terminalRetention.complete(pruneBatch, exportState.acknowledgedSequence());
            }
            case UPDATE_POSITION_MODE -> {
                commandChangedUserIds = List.of(message.header().userId());
                if (RuntimeCommandProcessor.updatePositionMode(runtimePlaceOrderState,
                        message.header().userId(),
                        TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe()))) {
                    refreshSnapshotProjection();
                }
            }
            case ADJUST_POSITION_MARGIN -> {
                commandChangedUserIds = List.of(message.header().userId());
                RuntimeCommandProcessor.adjustPositionMargin(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(),
                        TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe()));
                refreshSnapshotProjection();
            }
            case ADJUST_INSURANCE_FUND -> {
                RuntimeCommandProcessor.adjustInsuranceFund(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe()));
                refreshSnapshotProjection();
            }
            case UPDATE_LEVERAGE -> {
                commandChangedUserIds = List.of(message.header().userId());
                if (RuntimeCommandProcessor.updateLeverage(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(),
                        TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe()))) {
                    refreshSnapshotProjection();
                }
            }
            case UPSERT_ALGO_ORDER -> {
                var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payloadUnsafe())
                        .materializeCreation(clusterTimestamp);
                if (runtimePlaceOrderState.algoOrder(algo.algoOrderId()) == null
                        && terminalRetention.containsAlgo(algo.algoOrderId(), message.header().userId(),
                        algo.clientAlgoOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                            "terminal algo order identity is retained");
                }
                RuntimeCommandProcessor.upsertAlgoOrder(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                        message.header().userId(), algo);
                refreshSnapshotProjection();
            }
            case UPDATE_CANCEL_ALL_AFTER -> {
                RuntimeCommandProcessor.updateCancelAllAfter(runtimePlaceOrderState, message.header().userId(),
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payloadUnsafe()));
                refreshSnapshotProjection();
            }
            case PLACE_TRIGGER_ORDER -> {
                var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payloadUnsafe())
                        .materializeCreation(clusterTimestamp);
                if (runtimePlaceOrderState.triggerOrder(trigger.triggerOrderId()) == null
                        && terminalRetention.containsTrigger(trigger.triggerOrderId(), message.header().userId(),
                        trigger.clientTriggerOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                            "terminal trigger order identity is retained");
                }
                commandChangedTriggerOrderIds = List.of(trigger.triggerOrderId());
                int symbolId = runtimePlaceOrderIdentities.symbolId(trigger.symbol());
                long positionKey = preparedTriggerPositionKey(message.header().userId(), trigger);
                boolean instrumentSettled = runtimePlaceOrderState.treasury().lifecycleSettlement(symbolId) != 0;
                runtimePlaceOrderState.executeUserSettlement(message.header().userId(), () -> {
                    RuntimeCommandProcessor.upsertTriggerOrder(runtimePlaceOrderState,
                            message.header().userId(), trigger, symbolId, positionKey, instrumentSettled);
                    return null;
                });
                refreshSnapshotProjection();
                commandTriggerOrderView = runtimePlaceOrderState.triggerOrder(trigger.triggerOrderId()).view();
            }
            case CANCEL_TRIGGER_ORDER -> {
                long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payloadUnsafe());
                commandChangedTriggerOrderIds = List.of(triggerOrderId);
                cancelTriggerOrderRuntime(message.header().userId(), triggerOrderId);
            }
            case CLAIM_TRIGGER_ORDER -> {
                long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payloadUnsafe());
                claimTriggerOrderRuntime(claim[0], claim[1], claim[2], claim[3]);
            }
            case COMPLETE_TRIGGER_ORDER -> {
                long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payloadUnsafe());
                completeTriggerOrderRuntime(complete[0], complete[1] == 1, complete[2], "", complete[3]);
            }
            case UPDATE_TRIGGER_TRAILING -> {
                long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payloadUnsafe());
                updateTriggerTrailingRuntime(trailing[0], trailing[1], trailing[2], trailing[3]);
            }
            case EXPIRE_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
                expireTriggerOrderRuntime(lifecycle[0], lifecycle[1]);
            }
            case RETRY_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
                retryTriggerOrderRuntime(lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis());
            }
            case EXECUTE_TRIGGER_ORDER -> executeTriggerOrder(message);
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    private java.util.stream.Stream<com.surprising.aeron.service.state.CoreOrderState> openOrders(long userId) {
        return (userId == 0 ? activeOrderIndex.ids() : activeOrderIndex.ids(userId)).stream()
                .map(this::runtimeOrder)
                .filter(java.util.Objects::nonNull);
    }

    private void evaluateMarkPriceTriggers(com.surprising.aeron.protocol.ApplyMarkPriceCommand command,
                                           UUID commandId, long submittedAtEpochMillis) {
        evaluatePendingTriggerScan(command.symbol());
    }

    private void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(command.symbol());
        RiskScanRuntime scan = symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = triggerOrderIndex.maxPendingId(command.symbol());
        replaceRiskScan(scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    private void evaluatePendingTriggerScan(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return;
        long markPriceTicks = scan.triggerMarkPriceTicks();
        long triggeredAt = scan.triggerGeneratedAtEpochMillis();
        UUID commandId = UUID.nameUUIDFromBytes((productLine.name() + ":MARK_PRICE:" + symbol + ":"
                + scan.priceSequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int remaining = DEFAULT_TRIGGER_SCAN_BATCH_SIZE;
        if (scan.triggerOcoOrderId() != 0) {
            var pendingTrigger = runtimePlaceOrderState.triggerOrder(scan.triggerOcoOrderId());
            if (pendingTrigger == null
                    || pendingTrigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                scan = scan.withTriggerOcoProgress(0, 0);
                replaceRiskScan(scan);
            } else {
                OcoCancellationPage oco = cancelOcoSiblings(pendingTrigger,
                        scan.triggerOcoCursor() == 0 ? Long.MAX_VALUE : scan.triggerOcoCursor(), remaining);
                remaining -= oco.workUnits();
                if (!oco.complete()) {
                    replaceRiskScan(scan.withTriggerOcoProgress(pendingTrigger.triggerOrderId(), oco.nextCursor()));
                    return;
                }
                scan = scan.withTriggerOcoProgress(0, 0);
                replaceRiskScan(scan);
            }
        }
        if (remaining <= 0) return;
        var page = triggerOrderIndex.candidatesPage(symbol, markPriceTicks, scan.triggerPhase(),
                scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(), remaining);
        if (page.ids().isEmpty() && page.complete()) {
            replaceRiskScan(scan.withTriggerProgress(true, page.nextPhase(), page.nextPriceCursor(),
                    page.nextOrderCursor(), scan.triggerUpperId(), markPriceTicks, triggeredAt));
            return;
        }
        for (long triggerOrderId : page.ids()) {
            var trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                continue;
            }
            if (trigger.expiresAtEpochMillis() > 0 && triggeredAt > 0
                    && trigger.expiresAtEpochMillis() <= triggeredAt) {
                expireTriggerOrderRuntime(triggerOrderId, triggeredAt);
                markTriggerChanged(triggerOrderId);
                continue;
            }
            boolean triggered;
            if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
                if (trigger.activationPriceTicks() > 0
                        && ((sell && markPriceTicks < trigger.activationPriceTicks())
                        || (!sell && markPriceTicks > trigger.activationPriceTicks()))) {
                    continue;
                }
                long highest = sell
                        ? Math.max(trigger.highestPriceTicks(), markPriceTicks)
                        : trigger.highestPriceTicks();
                long lowest = sell
                        ? trigger.lowestPriceTicks()
                        : trigger.lowestPriceTicks() == 0
                        ? markPriceTicks
                        : Math.min(trigger.lowestPriceTicks(), markPriceTicks);
                long activatedAt = trigger.activatedAtEpochMillis() == 0 ? triggeredAt
                        : trigger.activatedAtEpochMillis();
                if (highest != trigger.highestPriceTicks() || lowest != trigger.lowestPriceTicks()
                        || activatedAt != trigger.activatedAtEpochMillis()) {
                    updateTriggerTrailingRuntime(triggerOrderId, highest, lowest, activatedAt);
                    markTriggerChanged(triggerOrderId);
                    trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
                }
                long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
                long delta = trailingDelta(base, trigger.callbackRatePpm());
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                triggered = trigger.activatedAtEpochMillis() > 0
                        && (sell ? markPriceTicks <= threshold : markPriceTicks >= threshold);
            } else {
                triggered = trigger.triggerCondition()
                        == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                        ? markPriceTicks >= trigger.triggerPriceTicks()
                        : markPriceTicks <= trigger.triggerPriceTicks();
            }
            if (!triggered) continue;
            if (remaining <= 0) {
                replaceRiskScan(scan.withTriggerOcoProgress(triggerOrderId, Long.MAX_VALUE));
                return;
            }
            OcoCancellationPage oco = cancelOcoSiblings(trigger, Long.MAX_VALUE, remaining);
            remaining -= oco.workUnits();
            if (!oco.complete()) {
                replaceRiskScan(scan.withTriggerOcoProgress(triggerOrderId, oco.nextCursor()));
                return;
            }
            executeTriggerOrder(triggerOrderId, scan.priceSequence(), markPriceTicks, triggeredAt, commandId, false);
        }
        replaceRiskScan(scan.withTriggerProgress(page.complete(), page.nextPhase(), page.nextPriceCursor(),
                page.nextOrderCursor(), scan.triggerUpperId(), markPriceTicks, triggeredAt)
                .withTriggerOcoProgress(0, 0));
    }

    private void replaceRiskScan(RiskScanRuntime scan) {
        RuntimeCommandProcessor.replaceRiskScan(runtimePlaceOrderState, scan);
        refreshSnapshotProjection();
    }

    private void cancelTriggersForClosedPositions(TradingCoreState before) {
        seedChangeAccumulators();
        if (changedUserIds.isEmpty()) return;
        for (long userId : changedUserIds) {
            var previousUser = before.user(userId);
            if (previousUser == null || runtimePlaceOrderState.user(userId) == null) continue;
            for (String positionKey : previousUser.positions().keySet()) {
                var previous = previousUser.positions().get(positionKey);
                var current = runtimePlaceOrderState.position(
                        runtimePlaceOrderIdentities.positionKey(userId, positionKey));
                if (previous == null || previous.signedQuantitySteps() == 0
                        || (current != null && current.signedQuantitySteps() != 0)) continue;
                for (long triggerOrderId : triggerOrderIndex.ids(userId, previous.symbol(), previous.marginMode(),
                        previous.positionSide())) {
                    var trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
                    if (trigger != null && trigger.status()
                            == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                        cancelTriggerOrderRuntime(userId, triggerOrderId);
                        markTriggerChanged(triggerOrderId);
                    }
                }
            }
        }
    }

    private static long trailingDelta(long base, long callbackRatePpm) {
        if (base <= 0 || callbackRatePpm <= 0) return 0;
        return Math.floorDiv(Math.multiplyExact(base, callbackRatePpm), 1_000_000L);
    }

    private static boolean isTriggerConditionSatisfied(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long priceTicks) {
        if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
            long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
            if (base <= 0 || trigger.activatedAtEpochMillis() <= 0) return false;
            long delta = trailingDelta(base, trigger.callbackRatePpm());
            long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
            return sell ? priceTicks <= threshold : priceTicks >= threshold;
        }
        return trigger.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? priceTicks >= trigger.triggerPriceTicks() : priceTicks <= trigger.triggerPriceTicks();
    }

    private OcoCancellationPage cancelOcoSiblings(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long cursor, int limit) {
        if (limit <= 0) return new OcoCancellationPage(false, cursor, 0);
        List<Long> page = new ArrayList<>(limit);
        boolean more = false;
        for (Long siblingId : triggerOrderIndex.ocoSiblings(trigger).descendingSet()) {
            if (siblingId == null || siblingId == trigger.triggerOrderId() || siblingId >= cursor) continue;
            if (page.size() >= limit) {
                more = true;
                break;
            }
            page.add(siblingId);
        }
        long nextCursor = cursor;
        int work = 0;
        for (long siblingId : page) {
            nextCursor = siblingId;
            work++;
            var sibling = runtimePlaceOrderState.triggerOrder(siblingId);
            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                cancelTriggerOrderRuntime(sibling.userId(), siblingId);
                markTriggerChanged(siblingId);
            }
        }
        return new OcoCancellationPage(!more, nextCursor, work);
    }

    private void cancelAllOcoSiblings(com.surprising.aeron.service.state.CoreTriggerOrderState trigger) {
        long cursor = Long.MAX_VALUE;
        while (true) {
            OcoCancellationPage page = cancelOcoSiblings(trigger, cursor, DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
            if (page.complete()) return;
            if (page.workUnits() == 0) throw new IllegalStateException("OCO cancellation made no progress");
            cursor = page.nextCursor();
        }
    }

    private record OcoCancellationPage(boolean complete, long nextCursor, int workUnits) {
    }

    private void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        executeTriggerOrder(execute[0], execute[1], execute[2], execute[3], message.header().commandId(), true);
    }

    private void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId) {
        executeTriggerOrder(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId, true);
    }

    private void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId, boolean cancelOco) {
        var trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
            return;
        }
        Integer triggerSymbolId = runtimePlaceOrderIdentities.findSymbolId(trigger.symbol());
        var mark = triggerSymbolId == null ? null : runtimePlaceOrderState.markPrice(triggerSymbolId);
        if (mark != null && (mark.priceSequence() != triggerSequence
                || mark.markPriceTicks() != triggeredPriceTicks
                || !isTriggerConditionSatisfied(trigger, triggeredPriceTicks))) {
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        }
        if (cancelOco) cancelAllOcoSiblings(trigger);
        if (RuntimeCommandProcessor.claimTriggerOrder(runtimePlaceOrderState, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis)) {
            refreshSnapshotProjection();
        }
        markTriggerChanged(triggerOrderId);
        var instrument = runtimePlaceOrderState.instrument(trigger.symbol());
        if (instrument == null || instrument.version() <= 0 || trigger.instrumentVersion() <= 0
                || instrument.version() != trigger.instrumentVersion()) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_VERSION",
                    triggeredAtEpochMillis);
            return;
        }
        long childOrderId = triggerChildOrderId(triggerOrderId, runtimePlaceOrderState);
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        long limitPriceTicks = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (trigger.priceTicks() > 0 ? trigger.priceTicks() : triggeredPriceTicks) : 0;
        var place = new com.surprising.aeron.protocol.PlaceOrderCommand(
                childOrderId, trigger.symbol(), trigger.instrumentVersion(), trigger.side(), limitPriceTicks,
                trigger.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                trigger.orderType(), trigger.timeInForce(), false, "TRIGGER:" + triggerOrderId);
        requireOrderIdentityAvailable(trigger.userId(), place);
        try {
            long childCoreSequence = Math.addExact(Math.addExact(appliedCommandCount, 2), queuedMatching.size());
            reservePlaceOrderRuntime(trigger.userId(), place, commandId, childCoreSequence);
        } catch (CoreStateRejectedException exception) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0, exception.code(), triggeredAtEpochMillis);
            return;
        }
        markUserChanged(trigger.userId());
        markOrderChanged(childOrderId);
        queueTriggerMatching(trigger, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId);
        commandOrderViews = appendDistinct(commandOrderViews, List.of(orderView(runtimeOrder(childOrderId))));
    }

    private void ensureRuntimePlaceOrderState() {
        if (runtimePlaceOrderState == null || runtimePlaceOrderIdentities == null) {
            throw new IllegalStateException("authoritative runtime state is unavailable");
        }
        if (runtimePlaceOrderState != runtime.runtimeStateForConstruction()
                || runtimePlaceOrderIdentities != runtime.identitiesForConstruction()) {
            throw new IllegalStateException("runtime state cursor does not match the owner runtime");
        }
    }

    private void refreshSnapshotProjection() {
        if (snapshotProjectionDeferred) {
            snapshotProjectionDirty = true;
            return;
        }
        projectSnapshotNow();
    }

    private void beginSnapshotProjectionBatch() {
        if (snapshotProjectionDeferred) {
            throw new IllegalStateException("snapshot projection batch is already active");
        }
        snapshotProjectionDeferred = true;
        snapshotProjectionDirty = false;
    }

    private void completeSnapshotProjectionBatch() {
        boolean dirty = snapshotProjectionDirty;
        snapshotProjectionDeferred = false;
        snapshotProjectionDirty = false;
        if (dirty) projectSnapshotNow();
    }

    private void abortSnapshotProjectionBatch() {
        snapshotProjectionDeferred = false;
        snapshotProjectionDirty = false;
    }

    private void projectSnapshotNow() {
        TradingCoreState previous = snapshotState;
        TradingCoreState materialized = RuntimeStateMaterializer.materializeTransition(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, previous);
        long previousBusinessStateHash = rollingBusinessStateHash.value();
        rollingBusinessStateHash.update(previous, materialized);
        rollingFundsStateHash.update(previous, materialized);
        runtime.commitRuntimeTransition(previous, materialized,
                previousBusinessStateHash, rollingBusinessStateHash.value());
        seedChangeAccumulators();
        for (Long userId : StateMapSupport.changedKeys(previous.users(), materialized.users())) {
            if (!java.util.Objects.equals(previous.user(userId), materialized.user(userId))) {
                changedUserIds.add(userId);
            }
        }
        changedTreasuryAssets.addAll(treasuryAssetChanges(previous.treasuryState(), materialized.treasuryState()));
        snapshotState = materialized;
    }

    private static java.util.Set<String> treasuryAssetChanges(
            com.surprising.aeron.service.state.CoreTreasuryState before,
            com.surprising.aeron.service.state.CoreTreasuryState after) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(StateMapSupport.changedKeys(before.feeBalances(), after.feeBalances()));
        assets.addAll(StateMapSupport.changedKeys(before.insuranceBalances(), after.insuranceBalances()));
        assets.addAll(StateMapSupport.changedKeys(before.insuranceDeficits(), after.insuranceDeficits()));
        assets.addAll(StateMapSupport.changedKeys(
                before.liquidationFeeBalances(), after.liquidationFeeBalances()));
        assets.addAll(StateMapSupport.changedKeys(
                before.fundingResidualBalances(), after.fundingResidualBalances()));
        assets.addAll(StateMapSupport.changedKeys(
                before.roundingResidualBalances(), after.roundingResidualBalances()));
        assets.addAll(StateMapSupport.changedKeys(before.clearingPnlBalances(), after.clearingPnlBalances()));
        return java.util.Collections.unmodifiableSet(assets);
    }

    private void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                          long pendingCoreSequence) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, userId, command, currentClusterTimestamp);
        long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, userId, resolved,
                openInterestIndex.openInterestSteps(command.symbol()), activeOrderIndex);
        RuntimeCommandProcessor.placeOrder(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                userId, resolved, commandId, requiredReservation);
        runtimePlaceOrderState.markPendingReservation(userId, resolved.orderId(), pendingCoreSequence);
        refreshSnapshotProjection();
    }

    private CoreMatchingOrder matchingOrder(long orderId) {
        com.surprising.aeron.service.state.OrderRuntime order = runtimePlaceOrderState.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "runtime order is missing");
        return new CoreMatchingOrder(order.orderId(), runtimePlaceOrderIdentities.symbol(order.symbolId()),
                order.side(), order.orderType(), order.timeInForce(), order.matchingPriceTicks(),
                order.remainingQuantitySteps());
    }

    private CoreOrderState runtimeOrder(long orderId) {
        var order = runtimePlaceOrderState.order(orderId);
        return order == null ? null : RuntimeStateMaterializer.orderSnapshot(order, runtimePlaceOrderIdentities);
    }

    private String runtimeLiquidationSymbol(
            com.surprising.aeron.service.state.LiquidationRuntime liquidation) {
        return runtimePlaceOrderIdentities.symbol(liquidation.symbolId());
    }

    private com.surprising.aeron.service.state.TreasuryRuntime.LifecycleProgressRuntime
            runtimeLifecycleProgress(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        return symbolId == null ? null : runtimePlaceOrderState.treasury().lifecycleProgress(symbolId);
    }

    private CoreMatchingOrder matchingOrder(long userId, PlaceOrderCommand intent) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, userId, intent, currentClusterTimestamp);
        return new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(), resolved.orderType(),
                resolved.timeInForce(), resolved.matchingPriceTicks(), resolved.quantitySteps());
    }

    private void cancelOrderRuntime(long userId, long orderId) {
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.cancelOrder(runtimePlaceOrderState, userId, orderId))) {
            refreshSnapshotProjection();
        }
    }

    private void rejectPlaceOrderRuntime(long userId, long orderId) {
        runtimePlaceOrderState.executeUserSettlement(userId, () -> {
            RuntimeCommandProcessor.rejectPlaceOrder(runtimePlaceOrderState, userId, orderId);
            return null;
        });
        refreshSnapshotProjection();
    }

    private void stampOrderChangesRuntime(TradingCoreState commandBefore, long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (RuntimeCommandProcessor.stampOrderChanges(runtimePlaceOrderState, runtimePlaceOrderIdentities,
                commandBefore, timestamp, clusterPosition, changedOrderIds)) {
            refreshSnapshotProjection();
        }
    }

    private void executeLiquidationRuntime(com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
                                           Collection<CoreOrderState> canceledOrders) {
        RuntimePerpetualLiquidationProcessor.applyExecutionRuntime(command, canceledOrders,
                runtimePlaceOrderState, runtimePlaceOrderIdentities);
        refreshSnapshotProjection();
    }

    private void advanceLiquidationCancellationRuntime(
            com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId) {
        RuntimePerpetualLiquidationProcessor.applyCancellationAdvanceRuntime(command, canceledOrders,
                nextCursorOrderId, runtimePlaceOrderState, runtimePlaceOrderIdentities);
        refreshSnapshotProjection();
    }

    private void settleInstrumentRuntime(com.surprising.aeron.protocol.SettleInstrumentCommand command,
                                         UUID commandId) {
        long beforeRevision = runtimePlaceOrderState.revision();
        commandSettlementProgress = RuntimeSettlementProcessor.applyRuntime(command,
                positionUserIndex.users(command.symbol()), commandId, activeOrderIndex,
                runtimePlaceOrderState, runtimePlaceOrderIdentities);
        if (runtimePlaceOrderState.revision() != beforeRevision) refreshSnapshotProjection();
    }

    private void advanceSettlementCancellationRuntime(
            com.surprising.aeron.protocol.SettleInstrumentCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId, UUID commandId) {
        RuntimeSettlementProcessor.advanceCancellationRuntime(command, canceledOrders, nextCursorOrderId,
                commandId, runtimePlaceOrderState, runtimePlaceOrderIdentities);
        refreshSnapshotProjection();
    }

    private void cancelTriggerOrderRuntime(long userId, long triggerOrderId) {
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.cancelTriggerOrder(
                        runtimePlaceOrderState, userId, triggerOrderId))) {
            refreshSnapshotProjection();
        }
    }

    private void claimTriggerOrderRuntime(long triggerOrderId, long triggerSequence,
                                          long triggeredPriceTicks, long triggeredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.claimTriggerOrder(runtimePlaceOrderState, triggerOrderId,
                        triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis))) {
            refreshSnapshotProjection();
        }
    }

    private void completeTriggerOrderRuntime(long triggerOrderId, boolean success, long placedOrderId,
                                             String rejectReason, long completedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.completeTriggerOrder(runtimePlaceOrderState, triggerOrderId, success,
                        placedOrderId, rejectReason, completedAtEpochMillis))) {
            refreshSnapshotProjection();
        }
    }

    private void expireTriggerOrderRuntime(long triggerOrderId, long expiredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.expireTriggerOrder(
                        runtimePlaceOrderState, triggerOrderId, expiredAtEpochMillis))) {
            refreshSnapshotProjection();
        }
    }

    private void updateTriggerTrailingRuntime(long triggerOrderId, long highestPriceTicks,
                                              long lowestPriceTicks, long activatedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.updateTriggerTrailing(runtimePlaceOrderState, triggerOrderId,
                        highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis))) {
            refreshSnapshotProjection();
        }
    }

    private void retryTriggerOrderRuntime(long triggerOrderId, long staleBeforeEpochMillis,
                                          long retryAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (runtimePlaceOrderState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.retryTriggerOrder(runtimePlaceOrderState, triggerOrderId,
                        staleBeforeEpochMillis, retryAtEpochMillis))) {
            refreshSnapshotProjection();
        }
    }

    private long requireTriggerOwner(long triggerOrderId) {
        var trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        return trigger.userId();
    }

    private long preparedTriggerPositionKey(
            long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        return RuntimeCommandProcessor.triggerPositionKey(
                runtimePlaceOrderIdentities, productLine, userId, trigger);
    }

    private com.surprising.aeron.service.state.RuntimeTreasuryDelta[] applyMatchesOnAccountLanes(
            long takerOrderId,
            long coreSequence,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        return runtimePlaceOrderState.applyMatcherSettlement(
                coreSequence, laneContext.expectedLaneMask(), takerOrderId,
                matchingResult, runtimePlaceOrderIdentities);
    }

    private void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    private void restoreCommandState(TradingCoreState state) {
        abortSnapshotProjectionBatch();
        runtime.restoreStateOnly(state);
        snapshotState = state;
        rollingBusinessStateHash.restore(state);
        rollingFundsStateHash.restore(state);
        runtimePlaceOrderIdentities = runtime.identitiesForConstruction();
        runtimePlaceOrderState = runtime.runtimeStateForConstruction();
    }

    private static long triggerChildOrderId(long triggerOrderId, TradingRuntimeState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.order(candidate) != null) {
            candidate = Math.addExact(candidate, 2);
        }
        return candidate;
    }

    private void queueTriggerMatching(com.surprising.aeron.service.state.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId) {
        UUID commandId = UUID.nameUUIDFromBytes((parentCommandId + ":trigger:" + trigger.triggerOrderId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandId,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        queuedMatching.add(new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)));
    }

    private void markTriggerChanged(long triggerOrderId) {
        seedChangeAccumulators();
        changedTriggerOrderIds.add(triggerOrderId);
    }

    private void markUserChanged(long userId) {
        seedChangeAccumulators();
        changedUserIds.add(userId);
    }

    private void markOrderChanged(long orderId) {
        seedChangeAccumulators();
        changedOrderIds.add(orderId);
    }

    private void resetChangeAccumulators() {
        commandBeforeBusinessStateHash = currentBusinessStateHash();
        commandBeforeFundsStateHash = rollingFundsStateHash.value();
        commandMatcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                appliedMatcherSequence, appliedMatcherPrefixDigest);
        changedUserIds.clear();
        changedOrderIds.clear();
        changedLiquidationIds.clear();
        changedTriggerOrderIds.clear();
        changedTreasuryAssets.clear();
    }

    private long appendCoreFact(CoreMessage command, ResponseStatus status, CoreResultCode resultCode,
                                long appliedCount, long businessStateHash, TradingCoreState before,
                                TradingCoreState after, CoreCommandDelta delta,
                                com.surprising.aeron.protocol.CoreMatcherTransition matcherTransition) {
        java.util.Set<Long> changedUsers = delta.userIds() == null
                ? StateMapSupport.changedKeys(before.users(), after.users())
                : new java.util.LinkedHashSet<>(delta.userIds());
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        com.surprising.aeron.service.state.FundsDelta fundsDelta =
                com.surprising.aeron.service.state.FundsDelta.between(
                        before, after, changedUsers,
                        commandChangedTreasuryAssets == null ? java.util.Set.of()
                                : new java.util.LinkedHashSet<>(commandChangedTreasuryAssets),
                        externalAdjustment);
        long fundsStateHash = after == snapshotState
                ? rollingFundsStateHash.value()
                : com.surprising.aeron.service.state.RollingFundsStateHash.compute(after);
        return exportState.append(command, status, resultCode, appliedCount, businessStateHash,
                commandBeforeBusinessStateHash, commandBeforeFundsStateHash, fundsStateHash,
                matchingAdapter.topology().topologyHash(), laneRevisionHash(),
                matcherTransition,
                currentClusterPosition, fundsDelta, delta.changedUsers(), delta.changedOrders(),
                delta.executions(), delta.fundingPayments(), delta.changedLiquidations(),
                delta.changedTreasuryAssets(), delta.changedTriggerOrders());
    }

    private void validateFundsConservation(CoreMessage command, TradingCoreState before,
                                           TradingCoreState after, CoreCommandDelta delta) {
        java.util.Set<Long> changedUsers = delta.userIds() == null
                ? StateMapSupport.changedKeys(before.users(), after.users())
                : new java.util.LinkedHashSet<>(delta.userIds());
        java.util.Set<String> changedTreasury = commandChangedTreasuryAssets == null
                ? java.util.Set.of()
                : commandChangedTreasuryAssets.stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        com.surprising.aeron.service.state.FundsDelta.between(
                before, after, changedUsers, changedTreasury, externalAdjustment);
    }

    private long laneRevisionHash() {
        long hash = 0xcbf29ce484222325L ^ matchingAdapter.topology().topologyHash();
        for (int laneId = 0; laneId < matchingAdapter.topology().accountLaneCount(); laneId++) {
            var lane = runtimePlaceOrderState.accountLaneById(laneId);
            hash ^= laneId;
            hash *= 0x100000001b3L;
            hash ^= lane.revision();
            hash *= 0x100000001b3L;
            hash ^= lane.committedSequence();
            hash *= 0x100000001b3L;
            hash ^= lane.localStateHash();
            hash *= 0x100000001b3L;
            hash ^= lane.localFundsHash();
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 1 : hash;
    }

    private long appendRejectedCoreFact(CoreMessage command, CoreResultCode resultCode, long appliedCount) {
        commandBeforeBusinessStateHash = currentBusinessStateHash();
        commandBeforeFundsStateHash = rollingFundsStateHash.value();
        commandMatcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                appliedMatcherSequence, appliedMatcherPrefixDigest);
        return appendCoreFact(command, ResponseStatus.REJECTED, resultCode, appliedCount,
                cachedBusinessStateHash, snapshotState, snapshotState, CoreCommandDelta.empty(),
                commandMatcherTransition);
    }

    private void seedChangeAccumulators() {
        if (commandChangedUserIds != null) changedUserIds.addAll(commandChangedUserIds);
        if (commandChangedOrderIds != null) changedOrderIds.addAll(commandChangedOrderIds);
        if (commandChangedLiquidationIds != null) changedLiquidationIds.addAll(commandChangedLiquidationIds);
        if (commandChangedTriggerOrderIds != null) changedTriggerOrderIds.addAll(commandChangedTriggerOrderIds);
        if (commandChangedTreasuryAssets != null) changedTreasuryAssets.addAll(commandChangedTreasuryAssets);
    }

    private void materializeChangeAccumulators() {
        seedChangeAccumulators();
        commandChangedUserIds = List.copyOf(changedUserIds);
        commandChangedOrderIds = List.copyOf(changedOrderIds);
        commandChangedLiquidationIds = List.copyOf(changedLiquidationIds);
        commandChangedTriggerOrderIds = List.copyOf(changedTriggerOrderIds);
        commandChangedTreasuryAssets = List.copyOf(changedTreasuryAssets);
    }

    private static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    private static List<Long> matchingUserIds(long takerUserId, List<MatcherEvent> matches) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.add(takerUserId);
        for (MatcherEvent match : matches) {
            if (match.eventType() == MatcherEventType.TRADE) ids.add(match.matchedOrderUid());
        }
        return List.copyOf(ids);
    }

    private static List<Long> matchingOrderIds(List<Long> initialOrderIds, List<MatcherEvent> matches) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(initialOrderIds);
        for (MatcherEvent match : matches) {
            if (match.eventType() == MatcherEventType.TRADE) ids.add(match.matchedOrderId());
        }
        return List.copyOf(ids);
    }

    private int pendingRiskScanCount() {
        return runtimePlaceOrderState.incompleteRiskScanCount();
    }

    private void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt) {
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        int pendingAfter = pendingRiskScanCount();
        System.Logger.Level level = pendingAfter > 0 ? System.Logger.Level.INFO : System.Logger.Level.DEBUG;
        if (!LOG.isLoggable(level)) return;
        LOG.log(level, "risk scan operation={0} symbol={1} batchSize={2} elapsedMicros={3} "
                        + "pendingSymbolsBefore={4} pendingSymbolsAfter={5}",
                new Object[]{operation, symbol, batchSize, elapsedMicros, pendingBefore, pendingAfter});
    }

    @Override
    public void close() {
        releaseSnapshotFence();
        inFlightMatcherSnapshot.set(null);
        CompletableFuture<?>[] inFlight = activeMatchingSubmissions.toArray(CompletableFuture[]::new);
        if (inFlight.length != 0) {
            try {
                CompletableFuture.allOf(inFlight).get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                inFlightMatcherSnapshot.set(null);
            }
        }
        activeMatchingSubmissions.clear();
        matchingFutures.clear();
        matchingCompletions.clear();
        completedMatching.clear();
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        pendingMatching.clear();
        pendingMatchingRejections.clear();
        pendingMatchingByCommandId.clear();
        pendingLifecycleScopes.clear();
        pendingOrderBatches.clear();
        deferredMatching.clear();
        runtime.close();
    }

    private CoreResponse userStateResponse(long userId) {
        var query = com.surprising.aeron.service.state.RuntimeStateQueryService.userState(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, userId);
        if (query.tooLarge()) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.QUERY_RESPONSE_TOO_LARGE, appliedCommandCount, cachedBusinessStateHash);
        }
        if (!query.found()) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, query.stateHash(),
                CoreStateQueryCodec.encodeUserState(query.view()));
    }

    private static List<com.surprising.aeron.protocol.CoreExecutionView> executionViews(
            long takerOrderId, long takerUserId, List<MatcherEvent> matches) {
        return matches.stream().filter(match -> match.eventType() == MatcherEventType.TRADE)
                .map(match -> new com.surprising.aeron.protocol.CoreExecutionView(
                        takerOrderId, match.matchedOrderId(), takerUserId, match.matchedOrderUid(),
                        match.price(), match.size())).toList();
    }

    private CoreCommandDelta commandDelta(TradingCoreState before, TradingCoreState after, boolean includeViews) {
        List<Long> userIds = List.copyOf(commandChangedUserIds);
        List<Long> orderIds = List.copyOf(commandChangedOrderIds);
        List<Long> liquidationIds = List.copyOf(commandChangedLiquidationIds);
        List<Long> triggerOrderIds = List.copyOf(commandChangedTriggerOrderIds);
        List<CoreUserStateView> changedUsers = includeViews
                ? changedUsers(before, after, userIds)
                : List.of();
        List<CoreOrderStateView> changedOrders = includeViews
                ? changedOrders(before, after, orderIds)
                : List.of();
        if (includeViews && !commandOrderViews.isEmpty()) {
            java.util.LinkedHashMap<Long, CoreOrderStateView> ordersById = new java.util.LinkedHashMap<>();
            changedOrders.forEach(order -> ordersById.put(order.orderId(), order));
            commandOrderViews.forEach(order -> ordersById.putIfAbsent(order.orderId(), order));
            changedOrders = List.copyOf(ordersById.values());
        }
        List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations = includeViews
                ? changedLiquidations(before, after, liquidationIds)
                : List.of();
        List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets = includeViews
                ? changedTreasuryAssets(before, after, commandChangedTreasuryAssets)
                : List.of();
        List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders = includeViews
                ? changedTriggerOrders(before, after, triggerOrderIds)
                : List.of();
        return new CoreCommandDelta(
                userIds, orderIds, liquidationIds, triggerOrderIds,
                commandExecutions, commandFundingPayments, commandFundingProgress, commandSettlementProgress,
                changedUsers, changedOrders, changedLiquidations, changedTreasuryAssets, changedTriggerOrders);
    }

    private static List<CoreUserStateView> changedUsers(
            TradingCoreState before, TradingCoreState after, List<Long> changedUserIds) {
        if (changedUserIds != null) {
            java.util.ArrayList<CoreUserStateView> result = new java.util.ArrayList<>(changedUserIds.size());
            for (Long userId : changedUserIds) {
                var user = after.user(userId);
                if (user != null && user != before.user(userId)) {
                    result.add(userDelta(before.user(userId), user));
                }
            }
            return List.copyOf(result);
        }
        throw new IllegalStateException("changed user ids are required for export");
    }

    private static List<CoreOrderStateView> changedOrders(
            TradingCoreState before, TradingCoreState after, List<Long> changedOrderIds) {
        if (changedOrderIds != null) {
            java.util.ArrayList<CoreOrderStateView> result = new java.util.ArrayList<>(changedOrderIds.size());
            for (Long orderId : changedOrderIds) {
                var order = after.order(orderId);
                if (order != null && !order.equals(before.order(orderId))) {
                    result.add(orderView(order));
                }
            }
            return List.copyOf(result);
        }
        throw new IllegalStateException("changed order ids are required for export");
    }

    private static List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations(
            TradingCoreState before, TradingCoreState after, List<Long> changedLiquidationIds) {
        if (changedLiquidationIds == null) {
            throw new IllegalStateException("changed liquidation ids are required for export");
        }
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreLiquidationState> values =
                changedLiquidationIds.stream()
                        .map(id -> after.riskState().liquidations().get(id))
                        .filter(java.util.Objects::nonNull);
        return values.filter(value -> !value.equals(before.riskState().liquidations().get(value.liquidationId())))
                .map(value -> {
                    var instrument = after.instruments().get(value.symbol());
                    if (instrument == null) throw new IllegalStateException("liquidation instrument is missing");
                    return new com.surprising.aeron.protocol.CoreLiquidationView(value.liquidationId(),
                            value.userId(), value.symbol(), instrument.settleAsset(), value.marginMode(),
                            value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                            value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                            value.executionPriceTicks(), value.liquidationFeeRatePpm(),
                            value.liquidationFeeUnits(), value.status().name());
                }).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders(
            TradingCoreState before, TradingCoreState after, List<Long> changedTriggerOrderIds) {
        if (changedTriggerOrderIds == null) {
            throw new IllegalStateException("changed trigger order ids are required for export");
        }
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> values =
                changedTriggerOrderIds.stream()
                        .map(id -> after.triggerOrders().get(id))
                        .filter(java.util.Objects::nonNull);
        return values.filter(value -> !value.equals(before.triggerOrders().get(value.triggerOrderId())))
                .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets(
            TradingCoreState before, TradingCoreState after, List<String> changedAssetNames) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(changedAssetNames);
        return assets.stream().filter(asset -> treasuryChanged(before, after, asset))
                .map(asset -> new com.surprising.aeron.protocol.CoreTreasuryAssetView(asset,
                        after.treasuryState().feeBalances().getOrDefault(asset, 0L),
                        after.treasuryState().insuranceBalances().getOrDefault(asset, 0L),
                        after.treasuryState().insuranceDeficits().getOrDefault(asset, 0L),
                        after.treasuryState().liquidationFeeBalances().getOrDefault(asset, 0L),
                        after.treasuryState().fundingResidualBalances().getOrDefault(asset, 0L),
                        after.treasuryState().roundingResidualBalances().getOrDefault(asset, 0L),
                        after.treasuryState().clearingPnlBalances().getOrDefault(asset, 0L))).toList();
    }

    private static boolean treasuryChanged(TradingCoreState before, TradingCoreState after, String asset) {
        return !java.util.Objects.equals(before.treasuryState().feeBalances().get(asset),
                after.treasuryState().feeBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceBalances().get(asset),
                after.treasuryState().insuranceBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceDeficits().get(asset),
                after.treasuryState().insuranceDeficits().get(asset))
                || !java.util.Objects.equals(before.treasuryState().liquidationFeeBalances().get(asset),
                after.treasuryState().liquidationFeeBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().fundingResidualBalances().get(asset),
                after.treasuryState().fundingResidualBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().roundingResidualBalances().get(asset),
                after.treasuryState().roundingResidualBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().clearingPnlBalances().get(asset),
                after.treasuryState().clearingPnlBalances().get(asset));
    }

    private static CoreUserStateView userDelta(com.surprising.aeron.service.state.CoreUserState before,
                                                com.surprising.aeron.service.state.CoreUserState after) {
        return new CoreUserStateView(after.productLine(), after.userId(), after.revision(), after.positionMode(),
                after.changedBalanceAssetsSince(before).stream()
                        .map(after.balances()::get)
                        .filter(java.util.Objects::nonNull)
                        .map(value -> new CoreBalanceView(value.asset(), value.availableUnits(), value.lockedUnits()))
                        .toList(),
                after.changedReservationIdsSince(before).stream()
                        .map(after.reservations()::get)
                        .filter(java.util.Objects::nonNull)
                        .map(value -> new CoreReservationView(value.orderId(), value.symbol(), value.instrumentVersion(),
                                value.kind(), value.asset(), value.reservedUnits(), value.releasedUnits(),
                                value.consumedUnits(), value.orderQuantitySteps())).toList(),
                after.changedPositionKeysSince(before).stream()
                        .map(after.positions()::get)
                        .filter(java.util.Objects::nonNull)
                        .map(value -> new CorePositionView(value.symbol(), value.marginAsset(), value.marginMode(),
                                value.positionSide(), value.instrumentVersion(), value.signedQuantitySteps(),
                                value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(),
                                value.positionMarginUnits())).toList());
    }

    private static CoreOrderStateView orderView(com.surprising.aeron.service.state.CoreOrderState order) {
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(), order.postOnly(),
                order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.cumulativeFeeUnits(), order.createdAtEpochMillis(), order.updatedAtEpochMillis(),
                order.clusterPosition(),
                order.status().name(), order.revision());
    }

    private byte[] commandResultData() {
        return commandResultData(null, null);
    }

    private byte[] commandResultData(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (commandRiskScanControl != null) {
            return CoreRiskScanControlCodec.encodeView(commandRiskScanControl);
        }
        if (commandFundingProgress != null) {
            return CoreFundingProgressCodec.encode(commandFundingProgress);
        }
        if (commandLiquidationProgress != null) {
            return CoreLiquidationProgressCodec.encode(commandLiquidationProgress);
        }
        if (commandLiquidationBatchResult != null) {
            return CoreLiquidationBatchResultCodec.encode(commandLiquidationBatchResult);
        }
        if (commandSettlementProgress != null) {
            return CoreSettlementProgressCodec.encode(commandSettlementProgress);
        }
        if (commandTriggerOrderView != null) {
            return com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of(commandTriggerOrderView));
        }
        if (commandOrderViews.isEmpty() && commandExecutions.isEmpty()) {
            return new byte[0];
        }
        if (pending == null || matchingResult == null) {
            return new byte[0];
        }
        var nativeCommand = matchingResult.nativeCommand();
        var matcherPrefix = matchingResult.matcherPrefix();
        UUID commandId;
        try {
            commandId = UUID.fromString(nativeCommand.commandId());
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
        if (nativeCommand.coreSequence() != pending.sequence()
                || !commandId.equals(pending.command().header().commandId())
                || nativeCommand.orderId() <= 0 || nativeCommand.instrumentVersion() <= 0
                || nativeCommand.matcherSequence() <= 0 || !matcherPrefix.bound()) {
            return new byte[0];
        }
        List<CoreOrderStateView> finalOrders = commandOrderViews.stream()
                .map(value -> {
                    var order = runtimeOrder(value.orderId());
                    return order == null ? value : orderView(order);
                })
                .toList();
        try {
            return CoreCommandResultCodec.encode(new CoreCommandResultView(pending.sequence(), commandId,
                    nativeCommand.orderId(), nativeCommand.instrumentVersion(), nativeCommand.matcherSequence(),
                    matcherPrefix.before(), matcherPrefix.after(), finalOrders, commandExecutions));
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    private CoreResponse orderStateResponse(long orderId) {
        return orderStateResponse(com.surprising.aeron.service.state.RuntimeStateQueryService.orderState(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, orderId));
    }

    private CoreResponse orderStateResponse(
            com.surprising.aeron.service.state.RuntimeStateQueryService.OrderQueryResult query) {
        if (!query.found()) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, query.stateHash(),
                CoreStateQueryCodec.encodeOrderState(query.view()));
    }

    private long currentBusinessStateHash() {
        long base = rollingBusinessStateHash.value();
        if (cachedFeePolicyHash != 0) base = mix(base, cachedFeePolicyHash);
        return cachedTransferHash == 0 ? base : mix(base, cachedTransferHash);
    }

    private static long computeTransferHash(
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        if (transfers.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var transfer : transfers.values()) {
            var command = transfer.command();
            digest = mix(digest, transfer.userId());
            digest = mix(digest, command.transferId());
            digest = mix(digest, command.sourceProductLine().ordinal());
            digest = mix(digest, command.targetProductLine().ordinal());
            digest = mixText(digest, command.sourceAccountType());
            digest = mixText(digest, command.targetAccountType());
            digest = mixText(digest, command.asset());
            digest = mix(digest, command.amountUnits());
            digest = mixText(digest, command.referenceId());
            digest = mixText(digest, command.reason());
        }
        return digest;
    }

    private static boolean isFundsIdempotencyCommand(CoreMessageType type) {
        return type == CoreMessageType.ADJUST_BALANCE || type == CoreMessageType.TRANSFER_OUT
                || type == CoreMessageType.TRANSFER_IN || type == CoreMessageType.COMPLETE_TRANSFER;
    }

    private static long computeFeePolicyHash(
            Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> policies) {
        if (policies.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var policy : policies.values()) {
            digest = mix(digest, policy.policyId());
            digest = mix(digest, policy.policyRevision());
            digest = mix(digest, policy.userId());
            digest = mixText(digest, policy.symbol());
            digest = mix(digest, policy.makerFeeRatePpm());
            digest = mix(digest, policy.takerFeeRatePpm());
            digest = mix(digest, policy.sourcePriority());
            digest = mix(digest, policy.active() ? 1 : 0);
            digest = mix(digest, policy.effectiveFromEpochMillis());
            digest = mix(digest, policy.expireAtEpochMillis());
        }
        return digest;
    }

    private static long mixText(long hash, String value) {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long result = mix(hash, encoded.length);
        for (byte item : encoded) {
            result ^= Byte.toUnsignedInt(item);
            result *= HASH_PRIME;
        }
        return result;
    }

    private static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
        long digest = 0;
        for (Map.Entry<SourceKey, Long> entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    private static long sourceSequenceDigest(SourceKey key, long sequence) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    private static long resultLedgerBytes(Map<UUID, StoredResult> results) {
        long bytes = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            bytes = Math.addExact(bytes, resultEntryBytes(entry.getValue()));
        }
        return bytes;
    }

    private static long resultEntryBytes(StoredResult result) {
        return Math.addExact(CoreStateSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataUnsafe().length);
    }

    private static LinkedHashMap<UUID, Long> resultLedgerContributions(Map<UUID, StoredResult> results) {
        LinkedHashMap<UUID, Long> contributions = new LinkedHashMap<>();
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            contributions.put(entry.getKey(), resultContribution(entry.getKey(), entry.getValue()));
        }
        return contributions;
    }

    private static long resultLedgerDigest(Map<UUID, Long> contributions) {
        long digest = 0;
        for (long contribution : contributions.values()) digest += contribution;
        return digest;
    }

    private static long resultEntryDigest(UUID commandId, StoredResult result) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, commandId.getMostSignificantBits());
        digest = mix(digest, commandId.getLeastSignificantBits());
        for (byte value : result.fingerprint().bytes()) {
            digest = mix(digest, Byte.toUnsignedInt(value));
        }
        digest = mix(digest, result.status().wireCode());
        digest = mix(digest, result.resultCode().wireCode());
        digest = mix(digest, result.appliedCommandCount());
        digest = mix(digest, result.requiredExportSequence());
        digest = mix(digest, result.retentionSequence());
        for (byte value : result.responseDataUnsafe()) {
            digest = mix(digest, Byte.toUnsignedInt(value));
        }
        return digest;
    }

    private static long resultContribution(UUID commandId, StoredResult result) {
        return resultEntryDigest(commandId, result) * retentionWeight(result.retentionSequence());
    }

    private static long retentionWeight(long sequence) {
        long weight = 1;
        long factor = RESULT_LEDGER_POSITION_BASE;
        long exponent = sequence;
        while (exponent > 0) {
            if ((exponent & 1) != 0) weight *= factor;
            factor *= factor;
            exponent >>>= 1;
        }
        return weight;
    }

    private static long nextRetentionSequence(Map<UUID, StoredResult> results) {
        long next = 1;
        for (StoredResult result : results.values()) {
            next = Math.max(next, Math.incrementExact(result.retentionSequence()));
        }
        return next;
    }

    private static void validateResultLedger(Map<UUID, StoredResult> results) {
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger count");
        }
        long bytes = resultLedgerBytes(results);
        long previousRetentionSequence = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().retentionSequence() <= previousRetentionSequence) {
                throw new IllegalArgumentException("invalid result ledger retention metadata");
            }
            previousRetentionSequence = entry.getValue().retentionSequence();
        }
        if (bytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger exceeds byte bound");
        }
    }

    private void storeResult(UUID commandId, StoredResult result) {
        long resultBytes = resultEntryBytes(result);
        if (resultBytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger entry exceeds byte bound");
        }
        StoredResult previous = commandResults.get(commandId);
        if (previous != null) {
            StoredResult retained = result.withRetentionSequence(previous.retentionSequence());
            commandResults.put(commandId, retained);
            commandResultBytes = Math.addExact(Math.subtractExact(commandResultBytes, resultEntryBytes(previous)),
                    resultBytes);
            long replacementContribution = resultContribution(commandId, retained);
            long previousContribution = commandResultContributions.put(commandId, replacementContribution);
            commandResultsDigest -= previousContribution;
            commandResultsDigest += replacementContribution;
        } else {
            long retentionSequence = nextResultRetentionSequence;
            StoredResult retained = result.withRetentionSequence(retentionSequence);
            long contribution = resultEntryDigest(commandId, retained) * nextResultRetentionWeight;
            nextResultRetentionSequence = Math.incrementExact(nextResultRetentionSequence);
            nextResultRetentionWeight *= RESULT_LEDGER_POSITION_BASE;
            commandResults.put(commandId, retained);
            commandResultBytes = Math.addExact(commandResultBytes, resultBytes);
            commandResultContributions.put(commandId, contribution);
            commandResultsDigest += contribution;
        }
        while (commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            Iterator<Map.Entry<UUID, StoredResult>> iterator = commandResults.entrySet().iterator();
            Map.Entry<UUID, StoredResult> oldest = null;
            while (iterator.hasNext()) {
                Map.Entry<UUID, StoredResult> candidate = iterator.next();
                if (!candidate.getKey().equals(commandId)) {
                    oldest = candidate;
                    iterator.remove();
                    break;
                }
            }
            if (oldest == null) {
                throw new IllegalStateException("result ledger protected entry exceeds bound");
            }
            commandResultBytes = Math.subtractExact(commandResultBytes, resultEntryBytes(oldest.getValue()));
            commandResultsDigest -= commandResultContributions.remove(oldest.getKey());
        }
    }

    private CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount, stateHash());
    }

    private static CoreResultCode matchingPendingCode() {
        return CoreResultCode.fromWireCode(MATCHING_PENDING_WIRE_CODE);
    }

    private record CompletedBookQuery(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            BookBootstrapSnapshot bootstrapSnapshot) {

        private static CompletedBookQuery single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new CompletedBookQuery(List.copyOf(levels), "", null, null);
        }

        private static CompletedBookQuery bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                BookBootstrapSnapshot snapshot) {
            return new CompletedBookQuery(List.of(), snapshotId, query, snapshot);
        }
    }

    private record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        private static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                BookBootstrapSnapshot snapshot) {
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> grouped = new TreeMap<>();
            for (String symbol : snapshot.symbols()) grouped.put(symbol, new ArrayList<>());
            for (com.surprising.aeron.protocol.CoreBookLevelView level : snapshot.levels()) {
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels = grouped.get(level.symbol());
                if (levels == null) throw new IllegalStateException("bootstrap level references unknown symbol");
                levels.add(level);
            }
            grouped.replaceAll((symbol, levels) -> List.copyOf(levels));
            return new BookBootstrapSession(snapshotId, exportSequence, depth,
                    Collections.unmodifiableNavigableMap(grouped));
        }
    }

    private enum OrderBatchKind {
        PLACE,
        CANCEL,
        AMEND
    }

    private record OrderBatchItem(
            long orderId,
            long originalOrderId,
            long replacementOrderId,
            Object command) {
    }

    private record DeferredMatching(long clusterTimestamp, long clusterPosition, SourceKey sourceKey) {
    }

    private static final class OrderBatchPending {
        private final OrderBatchKind kind;
        private final List<OrderBatchItem> items;
        private TradingCoreState beforeState;
        private final long clusterTimestamp;
        private final long clusterPosition;
        private final PendingMatching.Operation operation;
        private final List<CoreOrderBatchResult.Item> results = new ArrayList<>();
        private final java.util.LinkedHashSet<Long> changedUserIds = new java.util.LinkedHashSet<>();
        private final java.util.LinkedHashSet<Long> changedOrderIds = new java.util.LinkedHashSet<>();
        private final List<com.surprising.aeron.service.matching.CoreMatchingResult> matchingResults =
                new ArrayList<>();
        private com.surprising.aeron.service.state.RuntimeTreasuryDelta[] laneTreasuryDeltas;
        private int nextIndex;
        private long sequence;
        private TradingCoreState currentBefore;
        private List<Long> currentPreMatchingCancellationOrderIds = List.of();
        private boolean started;
        private com.surprising.aeron.protocol.CoreMatcherTransition matcherTransition;
        private com.surprising.aeron.service.matching.CoreMatchingResult lastMatchingResult;

        private OrderBatchPending(OrderBatchKind kind, List<OrderBatchItem> items,
                                  TradingCoreState beforeState, long clusterTimestamp,
                                  long clusterPosition, PendingMatching.Operation operation) {
            this.kind = kind;
            this.items = List.copyOf(items);
            this.beforeState = beforeState;
            this.clusterTimestamp = clusterTimestamp;
            this.clusterPosition = clusterPosition;
            this.operation = operation;
        }

        private void advanceMatcher(
                com.surprising.aeron.service.matching.CoreMatchingResult result) {
            long nextSequence = result.nativeCommand().matcherSequence();
            long nextPrefix = result.matcherPrefix().after();
            if (matcherTransition == null
                    || nextSequence != Math.incrementExact(matcherTransition.sequenceAfter())
                    || result.matcherPrefix().before() != matcherTransition.prefixAfter()) {
                throw new IllegalArgumentException("order batch matcher transition is not contiguous");
            }
            int previousShard = matcherTransition.matcherShardId();
            int nextShard = previousShard == -1 || previousShard == result.nativeCommand().matcherShardId()
                    ? result.nativeCommand().matcherShardId() : -1;
            matcherTransition = new com.surprising.aeron.protocol.CoreMatcherTransition(
                    matcherTransition.routeVersion(), nextShard,
                    matcherTransition.sequenceBefore(), nextSequence,
                    matcherTransition.prefixBefore(), nextPrefix);
        }

        private void retainMatchingResult(
                com.surprising.aeron.service.matching.CoreMatchingResult result) {
            if (result == null || result.nativeCommand().coreSequence() != sequence) {
                throw new IllegalArgumentException("invalid order batch matcher result");
            }
            matchingResults.add(result);
        }

        private void mergeTreasuryDeltas(
                com.surprising.aeron.service.state.RuntimeTreasuryDelta[] deltas) {
            if (deltas == null) throw new IllegalArgumentException("order batch Treasury deltas are required");
            if (laneTreasuryDeltas == null) {
                laneTreasuryDeltas = new com.surprising.aeron.service.state.RuntimeTreasuryDelta[deltas.length];
                for (int laneId = 0; laneId < deltas.length; laneId++) {
                    laneTreasuryDeltas[laneId] = new com.surprising.aeron.service.state.RuntimeTreasuryDelta(
                            com.surprising.aeron.service.state.RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
                }
            } else if (laneTreasuryDeltas.length != deltas.length) {
                throw new IllegalStateException("order batch Account Lane topology changed");
            }
            for (int laneId = 0; laneId < deltas.length; laneId++) {
                if (deltas[laneId] != null) laneTreasuryDeltas[laneId].merge(deltas[laneId]);
            }
        }

        private List<Long> changedOrderIdsFor(
                OrderBatchItem item,
                com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
            java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
            ids.add(item.orderId());
            if (item.originalOrderId() > 0) ids.add(item.originalOrderId());
            if (item.replacementOrderId() > 0) ids.add(item.replacementOrderId());
            matchingResult.matcherEvents().stream()
                    .filter(event -> event.eventType() == MatcherEventType.TRADE)
                    .map(MatcherEvent::matchedOrderId)
                    .forEach(ids::add);
            matchingResult.cancellations().stream().filter(CoreCancellationResult::accepted)
                    .map(CoreCancellationResult::orderId).forEach(ids::add);
            return List.copyOf(ids);
        }
    }

    record StoredResult(
            CommandFingerprint fingerprint,
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long requiredExportSequence,
            long stateHash,
            byte[] responseData,
            long retentionSequence) {

        StoredResult(ResponseStatus status, CoreResultCode resultCode, long appliedCommandCount, long stateHash) {
            this(CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]), status,
                    resultCode, appliedCommandCount, 0, stateHash,
                    new byte[0], Math.max(1, appliedCommandCount));
        }

        StoredResult(ResponseStatus status, CoreResultCode resultCode, long appliedCommandCount, long stateHash,
                     byte[] responseData) {
            this(CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]), status,
                    resultCode, appliedCommandCount, 0, stateHash,
                    responseData, Math.max(1, appliedCommandCount));
        }

        StoredResult {
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || requiredExportSequence < 0 || retentionSequence < 0) {
                throw new IllegalArgumentException("invalid stored result");
            }
            responseData = responseData == null ? new byte[0] : responseData.clone();
        }

        StoredResult withRetentionSequence(long sequence) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, responseData, sequence);
        }

        byte[] responseDataUnsafe() {
            return responseData;
        }

        @Override
        public byte[] responseData() {
            return responseData.clone();
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
