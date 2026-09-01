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
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.MarkPriceRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor;
import com.surprising.aeron.service.state.RuntimePerpetualLiquidationProcessor;
import com.surprising.aeron.service.state.RuntimePerpetualRiskProcessor;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.RuntimeSettlementProcessor;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderState;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CoreProbeState implements AutoCloseable {
    @FunctionalInterface
    interface CommitFaultInjector {
        void inject(String phase);
    }

    private static volatile CommitFaultInjector commitFaultInjector = ignored -> { };

    static void setCommitFaultInjectorForTest(CommitFaultInjector injector) {
        commitFaultInjector = injector == null ? ignored -> { } : injector;
    }

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
            "surprising.aeron.matching-phase-log-interval", 0);
    private static final int MATCHING_COMPLETION_SPINS = Math.max(0, Integer.getInteger(
            "surprising.aeron.matching-completion-spins", 1_024));
    private static final int PARALLEL_SETTLEMENT_MIN_TRADES = Math.max(2, Integer.getInteger(
            "surprising.aeron.parallel-settlement-min-trades", 8));
    private static final long MATCHING_AWAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final boolean MATCHING_PHASE_METRICS_ENABLED = MATCHING_PHASE_LOG_INTERVAL > 0;
    private static final int STANDALONE_SNAPSHOT_TIMEOUT_SECONDS = Integer.getInteger(
            "surprising.aeron.standalone-snapshot-timeout-seconds", 300);
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final byte[] EMPTY_RESPONSE_DATA = new byte[0];
    private static final long RESULT_LEDGER_POSITION_BASE = 0x9e3779b97f4a7c15L;
    private static final int MATCHING_PENDING_WIRE_CODE = 66;
    static final int MAX_PENDING_MATCHING = Integer.getInteger(
            "surprising.aeron.max-pending-matching", 4_096);
    private static final int MAX_MATCHING_COMPLETIONS = MAX_PENDING_MATCHING;
    private final ProductLine productLine;
    private final TradingCoreRuntime runtime;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private long commandResultBytes;
    private long commandResultsDigest;
    private long nextResultRetentionSequence;
    private long nextResultRetentionWeight;
    private final long[] appliedMatcherSequences;
    private final long[] appliedMatcherPrefixDigests;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final PendingMatchingRing pendingMatching;
    private final LinkedHashMap<Long, List<LifecycleScope>> pendingLifecycleScopes;
    private final LinkedHashMap<Long, OrderBatchPending> pendingOrderBatches;
    private final LinkedHashMap<Long, DeferredMatching> deferredMatching;
    private final LinkedHashMap<Long, CoreResultCode> pendingMatchingRejections = new LinkedHashMap<>();
    private final List<CoreMessage> queuedMatching = new ArrayList<>();
    private final MatchingCompletionQueue matchingCompletions;
    private final LaneCommandContextRing laneCommandContexts;
    private final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    private final MatcherSnapshotCapture matcherSnapshotCapture;
    private final SnapshotEncoder snapshotEncoder;
    private final ExecutorService snapshotEncoderExecutor;
    private final ExecutorService snapshotAuditExecutor;
    private final AtomicReference<RuntimeException> snapshotAuditFailure = new AtomicReference<>();
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
    private final Map<Long, Long> matchingSubmitNanos = MATCHING_PHASE_METRICS_ENABLED
            ? new HashMap<>() : null;
    private long completedMatchingCount;
    private final TerminalStateRetention terminalRetention;
    private final com.surprising.aeron.service.state.RollingBusinessStateHash rollingBusinessStateHash;
    private final com.surprising.aeron.service.state.RollingFundsStateHash rollingFundsStateHash;
    private long runtimePatchRevision;
    private final com.surprising.aeron.service.state.RuntimeCommitJournal runtimeProjectionJournal;
    private final Map<UUID, CoreExportState.PatchChain> factPatchChains = new HashMap<>();
    private RuntimeProjectionPoint currentProjectionPoint;
    private List<com.surprising.aeron.service.state.RuntimeCommitPatch> capturedCommitPatches;
    private CoreAdmissionReservation currentAdmission;
    private com.surprising.aeron.service.state.RuntimeCommitJournal.AdmissionReservation currentRetentionAdmission;
    private CoreMessage activeFactCommand;
    private CommandFingerprint activeFactFingerprint;
    private long activeFactTopologyHash;
    private long activeFactLaneRevisionHash;
    private long appliedCommandCount;
    private long committedCoreSequence;
    private boolean commandExternalAdjustment;
    private RuntimeException commitPublicationFailure;
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
    private boolean snapshotProjectionProvisionalOnly;
    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException fatalFailure;
    private Long orderBatchExpectedLaneMaskForTest;
    private Runnable orderBatchAfterItemFaultForTest;
    private SnapshotFence snapshotFence;
    private long lastSnapshotId;
    private TradingCoreState snapshotState;
    private List<CoreOrderStateView> commandOrderViews = List.of();
    private long[] commandTerminalOrderIds;
    private List<Long> commandChangedUserIds;
    private List<Long> commandChangedOrderIds;
    private List<Long> commandChangedLiquidationIds;
    private List<Long> commandChangedTriggerOrderIds;
    private List<String> commandChangedTreasuryAssets;
    private final PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();
    private final PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();
    private final PrimitiveLongChangeSet changedLiquidationIds = new PrimitiveLongChangeSet();
    private final PrimitiveLongChangeSet changedTriggerOrderIds = new PrimitiveLongChangeSet();
    private final LinkedHashSet<String> changedTreasuryAssets = new LinkedHashSet<>();
    private final RuntimeTreasuryDelta mergedLaneTreasuryDelta =
            new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
    private com.surprising.aeron.service.state.RuntimeFundsDelta commandFundsDelta =
            com.surprising.aeron.service.state.RuntimeFundsDelta.empty();
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
    private long admissionPreviousClusterPosition;
    private long admissionPreviousClusterTimestamp;
    private boolean activated;
    private boolean closed;

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), null, null);
    }

    CoreProbeState(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, null);
    }

    CoreProbeState(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture,
                   SnapshotEncoder snapshotEncoder) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), matcherSnapshotCapture, snapshotEncoder);
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
            long projectionSequence,
            Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> restoredPendingTransfers,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.commandResultBytes = resultLedgerBytes(commandResults);
        this.commandResultsDigest = resultLedgerDigest(commandResults);
        this.nextResultRetentionSequence = nextRetentionSequence(commandResults);
        this.nextResultRetentionWeight = retentionWeight(nextResultRetentionSequence);
        this.lastSourceSequences = lastSourceSequences;
        this.pendingMatching = new PendingMatchingRing(MAX_PENDING_MATCHING);
        this.pendingLifecycleScopes = new LinkedHashMap<>();
        this.pendingOrderBatches = new LinkedHashMap<>();
        this.deferredMatching = new LinkedHashMap<>();
        this.snapshotState = snapshotState;
        this.lastSnapshotId = matcherSnapshot == null ? 0 : matcherSnapshot.snapshotId();
        this.lastSourceSequenceDigest = sourceSequenceDigest(lastSourceSequences);
        this.exportState = exportState;
        this.terminalRetention = terminalRetention;
        long restoredBusinessStateHash = canonicalBusinessStateHash(
                snapshotState.businessStateHash(), restoredFeePolicies, restoredPendingTransfers);
        this.runtime = TradingCoreRuntime.passive(
                productLine, snapshotState, appliedCommandCount, matcherSnapshot, restoredBusinessStateHash);
        this.matchingAdapter = runtime.matcherForConstruction();
        this.appliedMatcherSequences = new long[matchingAdapter.topology().matchingEngineCount() + 1];
        this.appliedMatcherPrefixDigests = new long[appliedMatcherSequences.length];
        initializeMatcherProgress(matcherSnapshot);
        this.matchingCompletions = new MatchingCompletionQueue(
                matchingAdapter.topology().matchingCompletionCapacity());
        this.laneCommandContexts = new LaneCommandContextRing(
                matchingAdapter.topology().matcherWindowSize(),
                matchingAdapter.topology().accountLaneCount());
        this.matcherSnapshotCapture = matcherSnapshotCapture == null
                ? matchingAdapter::snapshotAsync
                : matcherSnapshotCapture;
        if (snapshotEncoder == null) {
            this.snapshotEncoderExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task,
                        "core-snapshot-encoder-" + productLine.name().toLowerCase(java.util.Locale.ROOT));
                thread.setDaemon(true);
                return thread;
            });
            this.snapshotEncoder = image -> CompletableFuture.supplyAsync(
                    () -> SectionedCoreSnapshotCodec.encode(image), snapshotEncoderExecutor);
        } else {
            this.snapshotEncoderExecutor = null;
            this.snapshotEncoder = snapshotEncoder;
        }
        this.snapshotAuditExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task,
                            "core-snapshot-audit-" + productLine.name().toLowerCase(java.util.Locale.ROOT));
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
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
        this.runtimePlaceOrderState.restoreFeePolicies(restoredFeePolicies);
        this.runtimePlaceOrderState.restorePendingTransfers(restoredPendingTransfers);
        this.rollingBusinessStateHash =
                com.surprising.aeron.service.state.RollingBusinessStateHash.create(
                        snapshotState, runtimePlaceOrderIdentities);
        this.rollingFundsStateHash = com.surprising.aeron.service.state.RollingFundsStateHash.create(
                snapshotState, runtimePlaceOrderIdentities);
        this.cachedFeePolicyHash = computeFeePolicyHash(restoredFeePolicies);
        this.cachedTransferHash = computeTransferHash(restoredPendingTransfers);
        this.cachedBusinessStateHash = currentBusinessStateHash();
        if (cachedBusinessStateHash != restoredBusinessStateHash) {
            throw new IllegalStateException("restored canonical business hash mismatch");
        }
        this.runtimePatchRevision = snapshotState.revision();
        this.runtime.restoreCommittedConsumers(
                snapshotState, runtimePatchRevision, cachedBusinessStateHash);
        this.runtimeProjectionJournal = com.surprising.aeron.service.state.RuntimeCommitJournal.passive(
                productLine, snapshotState, cachedBusinessStateHash, rollingFundsStateHash.value(),
                projectionSequence);
        this.currentProjectionPoint = runtimeProjectionJournal.initialPoint();
        this.runtime.releaseOwnerForHandoff();
    }

    static CoreProbeState prepareRestore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers) {
        if (projectionSequence < 0 || appliedCommandCount < 0 || commandResults == null
                || commandResults.size() > MAX_IDEMPOTENCY_RESULTS || lastSourceSequences == null
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || matcherSnapshot == null || snapshotState == null
                || snapshotState.productLine() != productLine || exportState == null
                || terminalRetention == null || feePolicies == null || pendingTransfers == null) {
            throw new IllegalArgumentException("invalid passive restored probe state");
        }
        validateResultLedger(commandResults);
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, exportState, terminalRetention, matcherSnapshot, projectionSequence,
                feePolicies, pendingTransfers, null, null);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!activated) activate();
        runtime.assertOwner();
        admissionPreviousClusterTimestamp = currentClusterTimestamp;
        admissionPreviousClusterPosition = currentClusterPosition;
        currentClusterTimestamp = clusterTimestamp;
        currentClusterPosition = clusterPosition;
        assertHealthy();
        if (snapshotFence != null && snapshotFence.encodedSnapshot == null) {
            throw new IllegalStateException("snapshot fence is active");
        }
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
                        result.responseDataUnsafe());
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
                    duplicate.responseDataUnsafe());
        }
        PendingMatching pendingDuplicate = pendingMatching.findByCommandId(message.header().commandId());
        if (pendingDuplicate != null) {
            if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, 0, stateHash(),
                        EMPTY_RESPONSE_DATA);
            }
            return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.OK, matchingPendingCode(),
                    pendingDuplicate.sequence(), 0, pendingDuplicate.pendingStateHash(), EMPTY_RESPONSE_DATA);
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
        if (message.header().messageType() == CoreMessageType.ACK_EXPORT && !pendingMatching.isEmpty()) {
            return applyExportAckControl(message, fingerprint, sourceKey);
        }
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        boolean exportCommand = message.header().messageType() != CoreMessageType.ACK_EXPORT;
        if (exportCommand && message.payloadUnsafe().length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        commandTriggerOrderView = null;
        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= MAX_PENDING_MATCHING) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return beginOrderBatchMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
            }
            return beginMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
        }
        CoreAdmissionReservation commandAdmission = null;
        CoreAdmissionReservation.AdmissionDemand commandDemand = null;
        if (exportCommand) {
            try {
                commandDemand = CoreAdmissionReservation.AdmissionDemand.direct(message,
                        runtimePlaceOrderState.riskScanControl().scanBatchSize());
                commandAdmission = CoreAdmissionReservation.reserve(
                        runtimeProjectionJournal, exportState, commandDemand);
                activateFactContext(commandAdmission, message, fingerprint);
            } catch (CoreStateRejectedException rejection) {
                return admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
            } catch (ArithmeticException exception) {
                return admissionRejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return admissionRejected(CoreResultCode.INVALID_COMMAND);
            }
        } else {
            try {
                currentRetentionAdmission = runtimeProjectionJournal.reserveAdmission(1);
                activateRetentionContext(message, fingerprint);
            } catch (CoreStateRejectedException rejection) {
                return admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
            }
        }
        RuntimeProjectionPoint beforeProjection = currentProjectionPoint;
        long beforeRuntimeRevision = runtimePlaceOrderState.revision();
        var runtimeCommandCheckpoint = runtimePlaceOrderState.commandCheckpoint();
        long positionIdentityCheckpoint = runtimePlaceOrderIdentities.positionCheckpoint();
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
        commandExternalAdjustment = message.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || message.header().messageType() == CoreMessageType.TRANSFER_OUT
                || message.header().messageType() == CoreMessageType.TRANSFER_IN
                || message.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        queuedMatching.clear();
        beginSnapshotProjectionBatch();
        try {
            status = applyCommand(message, clusterTimestamp);
        } catch (CoreAdmissionReservation.FactEstimateInvariantException exception) {
            return rejectDirectFactEstimateInvariant(exception, runtimeCommandCheckpoint,
                    positionIdentityCheckpoint, Math.incrementExact(appliedCommandCount), commandAdmission);
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
        if (status != ResponseStatus.APPLIED
                && (snapshotProjectionDirty || runtimePlaceOrderState.revision() != beforeRuntimeRevision)) {
            restoreCommandState(beforeProjection);
        }
        if (status == null) {
            abortSnapshotProjectionBatch();
            return releaseAdmission(commandAdmission, rejected(CoreResultCode.INVALID_MESSAGE));
        }
        if (status == ResponseStatus.APPLIED) {
            cancelTriggersForClosedPositions();
            int reservedChildPatches = commandDemand == null ? 0 : commandDemand.patchCount() - 1;
            if (queuedMatching.size() > reservedChildPatches
                    || pendingMatching.size() + queuedMatching.size() > MAX_PENDING_MATCHING) {
                restoreCommandState(beforeProjection);
                queuedMatching.clear();
                return releaseAdmission(commandAdmission, rejected(CoreResultCode.MATCHING_BACKPRESSURE));
            }
        }
        if (status == ResponseStatus.APPLIED) {
            List<Long> changedOrderIds = commandChangedOrderIds == null ? List.of() : commandChangedOrderIds;
            try {
                stampOrderChangesRuntime(clusterTimestamp, clusterPosition, changedOrderIds);
            } catch (IllegalStateException exception) {
                restoreCommandState(beforeProjection);
                return releaseAdmission(commandAdmission, rejected(CoreResultCode.INVALID_COMMAND));
            }
        }
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        LaneCommandContextRing.Context commandLaneContext = null;
        List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneApply = List.of();
        try {
            materializeChangeAccumulators();
            try {
                commandDelta = commandDelta();
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("command delta failed after typed state commit", exception);
            }
            if (status == ResponseStatus.APPLIED && currentRetentionAdmission == null
                    && !commandDelta.userIds().isEmpty()) {
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
                laneApply = applyAndCommitLaneSequence(nextAppliedCommandCount,
                        commandDelta.userIds(), commandLaneContext.matchingResult(),
                        rollingBusinessStateHash.value(), rollingFundsStateHash.value(), commandLaneContext);
                if (commandLaneContext.completedLaneMask() != expectedLaneMask) {
                    throw new IllegalStateException("single command account lane mask mismatch");
                }
                requireCompleteAccountLanes(commandLaneContext);
            }
            completeSnapshotProjectionBatch(laneApply);
            if (status == ResponseStatus.APPLIED) {
                validateFundsConservation(message);
            }
        } catch (CoreAdmissionReservation.FactEstimateInvariantException exception) {
            return rejectDirectFactEstimateInvariant(exception, runtimeCommandCheckpoint,
                    positionIdentityCheckpoint, nextAppliedCommandCount, commandAdmission);
        }
        boolean tradingStateChanged = status == ResponseStatus.APPLIED
                && currentProjectionPoint != beforeProjection;
        long businessStateHash = tradingStateChanged ? currentBusinessStateHash() : cachedBusinessStateHash;
        appliedCommandCount = nextAppliedCommandCount;
        refreshCommittedCoreSequence();
        if (commandLaneContext != null) {
            laneCommandContexts.release(nextAppliedCommandCount);
        }
        long requiredExportSequence = 0;
        if (exportCommand) {
            try {
                requiredExportSequence = appendCoreFact(message, fingerprint, status, resultCode,
                        nextAppliedCommandCount, businessStateHash,
                        beforeProjection, currentProjectionPoint, commandDelta, commandMatcherTransition);
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
        storeResult(message.header().commandId(), StoredResult.owned(fingerprint, status, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, responseData));
        if (message.header().messageType() == CoreMessageType.ACK_EXPORT && status == ResponseStatus.APPLIED
                && (!deferredMatching.isEmpty() || !pendingOrderBatches.isEmpty())) {
            submitDeferredMatchingAfterBatch();
        }
        CoreResponse response = new CoreResponse(status, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData);
        if (commandAdmission != null) return releaseAdmission(commandAdmission, response);
        releaseRetentionAdmission();
        return response;
    }

    private void appendQueuedMatching() {
        if (queuedMatching.isEmpty()) return;
        if (currentAdmission == null) {
            throw new IllegalStateException("queued matching admission reservation is missing");
        }
        currentAdmission.retainHolders(queuedMatching.size());
        for (CoreMessage command : queuedMatching) {
            long sequence = Math.incrementExact(appliedCommandCount);
            PendingMatching pending = newPendingMatching(sequence, PendingMatching.Operation.TRIGGER, command)
                    .withCapacityReservation(currentAdmission);
            putPendingMatching(pending);
            registerPendingLifecycle(pending);
            appliedCommandCount = sequence;
            refreshCommittedCoreSequence();
            submitMatching(pending);
        }
        queuedMatching.clear();
    }

    private CoreResponse beginOrderBatchMatching(CoreMessage message, long clusterTimestamp,
                                                  long clusterPosition, SourceKey sourceKey,
                                                  CommandFingerprint fingerprint) {
        OrderBatchPending batch;
        try {
            batch = decodeOrderBatch(message, clusterTimestamp, clusterPosition);
            validateOrderBatchIdentity(batch, message.header().userId());
        } catch (CoreStateRejectedException exception) {
            return rejected(CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey, fingerprint,
                    exception instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        CoreAdmissionReservation capacityReservation;
        try {
            capacityReservation = CoreAdmissionReservation.reserve(runtimeProjectionJournal, exportState,
                    CoreAdmissionReservation.AdmissionDemand.matching(message, matchingOrderBound(message)));
        } catch (CoreStateRejectedException rejection) {
            return admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        }
        boolean activateNow = pendingOrderBatches.isEmpty();
        long sequence = Math.incrementExact(appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, batch.operation, message, fingerprint)
                .withCapacityReservation(capacityReservation);
        batch.sequence = sequence;
        putPendingMatching(pending);
        registerPendingLifecycle(pending);
        pendingOrderBatches.put(sequence, batch);
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long pendingStateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.OK, matchingPendingCode(), appliedCommandCount);
        pending.withPendingStateHash(pendingStateHash);
        CoreResponse completed = activateNow ? activateOrderBatch(batch, pending) : null;
        if (completed != null) return completed;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, 0, pendingStateHash, EMPTY_RESPONSE_DATA);
    }

    private CoreResponse activateOrderBatch(OrderBatchPending batch, PendingMatching pending) {
        CoreAdmissionReservation reservation = pending.capacityReservation();
        if (reservation == null) throw new IllegalStateException("order batch admission reservation is missing");
        activateFactContext(reservation, pending.command(), pending.fingerprint());
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
        runtimePlaceOrderState.beginOrderBatchMutationScope();
        batch.beforeProjection = currentProjectionPoint;
        batch.runtimeCheckpoint = runtimePlaceOrderState.commandCheckpoint();
        batch.positionIdentityCheckpoint = runtimePlaceOrderIdentities.positionCheckpoint();
        batch.matcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                matcherSequence(-1), matcherPrefixDigest(-1));
        batch.admissionOrderIndex = new BatchAdmissionOrderIndex(activeOrderIndex);
        batch.started = true;
        beginSnapshotProjectionBatch();
        if (preparePipelinedPerpetualPlaceBatch(batch, pending)) {
            submitPipelinedPerpetualPlaceBatch(pending, batch);
            clearFactContext();
            return null;
        }
        CoreResponse response = startOrderBatchItem(batch, pending, batch.clusterTimestamp, batch.clusterPosition);
        if (response == null) clearFactContext();
        return response;
    }

    private boolean preparePipelinedPerpetualPlaceBatch(OrderBatchPending batch, PendingMatching pending) {
        if (!productLine.isDerivative() || batch.kind != OrderBatchKind.PLACE || batch.items.size() < 2
                || BENCHMARK_SKIP_MATCHING_SUBMIT) {
            return false;
        }
        long userId = pending.command().header().userId();
        try {
            List<PreparedPlaceReservation> reservations = new ArrayList<>(batch.items.size());
            for (OrderBatchItem item : batch.items) {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                if (command.reduceOnly()) throw new PipelinedBatchNotApplicable();
                requireOrderIdentityAvailable(userId, command);
                if (!preMatchingCloseCapacityCancellations(userId, command, command.orderId()).isEmpty()) {
                    throw new PipelinedBatchNotApplicable();
                }
                ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                        runtimePlaceOrderIdentities, userId, command, currentClusterTimestamp);
                var admissionIdentity = com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, userId, resolved);
                var preparedClientKey = runtimePlaceOrderIdentities.prepareClientKey(
                        userId, resolved.clientOrderId());
                Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(resolved.symbol());
                if (symbolId == null) {
                    throw new IllegalStateException("order symbol identity was not prepared with the instrument");
                }
                reservations.add(new PreparedPlaceReservation(command, resolved, admissionIdentity,
                        preparedClientKey, symbolId,
                        runtimePlaceOrderIdentities.assetId(resolved.reservationAsset()),
                        batchOpenInterestSteps(batch, command.symbol())));
            }
            reservePipelinedPerpetualPlaceBatch(batch, reservations, userId,
                    pending.command().header().commandId());
            batch.pipelined = true;
            return true;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException
                 | PipelinedBatchNotApplicable exception) {
            rollbackOrderBatchMutations(batch, false);
            beginSnapshotProjectionBatch();
            batch.admissionOrderIndex = new BatchAdmissionOrderIndex(activeOrderIndex);
            batch.currentPreMatchingCancellationOrderIds = List.of();
            return false;
        }
    }

    private void reservePipelinedPerpetualPlaceBatch(
            OrderBatchPending batch, List<PreparedPlaceReservation> reservations,
            long userId, UUID commandId) {
        for (PreparedPlaceReservation reservation : reservations) {
            batch.retainPreparedClientKey(userId, reservation.resolved().clientOrderId(),
                    reservation.preparedClientKey());
        }
        runtimePlaceOrderState.executeUserSettlement(userId, () -> {
            for (PreparedPlaceReservation reservation : reservations) {
                long requiredReservation =
                        com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationPrepared(
                                runtimePlaceOrderState, userId, reservation.resolved(),
                                reservation.openInterestSteps(), batch.admissionOrderIndex,
                                reservation.admissionIdentity());
                RuntimeCommandProcessor.placeOrderPrepared(runtimePlaceOrderState, userId,
                        reservation.resolved(), commandId, requiredReservation,
                        reservation.preparedClientKey().key(), reservation.symbolId(), reservation.assetId());
                runtimePlaceOrderState.markPendingReservation(
                        userId, reservation.command().orderId(), batch.sequence);
                batch.admissionOrderIndex.addPending(userId, reservation.resolved());
            }
            return null;
        });
        refreshSnapshotProjection();
    }

    private void submitPipelinedPerpetualPlaceBatch(PendingMatching pending, OrderBatchPending batch) {
        CompletableFuture<Void> matcherReady = runtime.matcherReady();
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future =
                matcherReady.isDone() && !matcherReady.isCompletedExceptionally()
                        ? submitPipelinedPerpetualPlaceBatchNow(pending, batch)
                        : matcherReady.thenCompose(ignored -> submitPipelinedPerpetualPlaceBatchNow(pending, batch));
        trackMatchingFuture(pending.sequence(), future);
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>
            submitPipelinedPerpetualPlaceBatchNow(PendingMatching pending, OrderBatchPending batch) {
        long userId = pending.command().header().userId();
        List<String> symbols = new ArrayList<>(batch.items.size());
        List<com.surprising.aeron.service.matching.CoreMatchingOrder> matchingOrders =
                new ArrayList<>(batch.items.size());
        for (OrderBatchItem item : batch.items) {
            String symbol = ((PlaceOrderCommand) item.command).symbol();
            if (!symbols.contains(symbol)) symbols.add(symbol);
            matchingOrders.add(matchingOrder(item.orderId()));
        }
        return matchingAdapter.prepareOrderRoutesAsync(userId, symbols)
                .thenCompose(ignored -> submitPreparedPipelinedPerpetualPlaceBatch(
                        pending, batch, userId, matchingOrders));
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>
            submitPreparedPipelinedPerpetualPlaceBatch(
                    PendingMatching pending, OrderBatchPending batch, long userId,
                    List<com.surprising.aeron.service.matching.CoreMatchingOrder> matchingOrders) {
        List<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>> futures =
                new ArrayList<>(batch.items.size());
        for (int index = 0; index < batch.items.size(); index++) {
            OrderBatchItem item = batch.items.get(index);
            PlaceOrderCommand command = (PlaceOrderCommand) item.command;
            com.surprising.aeron.service.matching.CoreMatchingOrder matchingOrder = matchingOrders.get(index);
            futures.add(withMatchingEvidence(pending, command.orderId(), command.instrumentVersion(),
                    () -> matchingAdapter.placeAsync(userId, matchingOrder)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
            if (failure != null) {
                batch.pipelinedMatchingFailure = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure;
                throw new java.util.concurrent.CompletionException(batch.pipelinedMatchingFailure);
            }
            List<com.surprising.aeron.service.matching.CoreMatchingResult> results =
                    new ArrayList<>(futures.size());
            for (CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future : futures) {
                com.surprising.aeron.service.matching.CoreMatchingResult result = future.getNow(null);
                if (result == null) {
                    throw new IllegalStateException("pipelined matcher batch completed without every result");
                }
                results.add(result);
            }
            batch.pipelinedMatchingResults = results;
            return results.getFirst();
        });
    }

    private OrderBatchPending decodeOrderBatch(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.PLACE, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.PLACE);
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.CANCEL, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.CANCEL);
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe());
            return new OrderBatchPending(OrderBatchKind.AMEND, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.replacementOrderId(), value.originalOrderId(),
                            value.replacementOrderId(), value)).toList(),
                    clusterTimestamp, clusterPosition, PendingMatching.Operation.AMEND);
        }
        throw new IllegalArgumentException("unsupported order batch type");
    }

    private void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            OrderRuntime order = runtimeOrder(orderId);
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
            long runtimeRevisionBefore = runtimePlaceOrderState.revision();
            try {
                prepareOrderBatchItem(batch, item, pending.command().header().userId(),
                        pending.command().header().commandId());
                pending = pending.withPreMatchingCancellations(batch.currentPreMatchingCancellationOrderIds);
                pendingMatching.put(pending);
                submitMatching(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()), List.of());
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                requireUnchangedRejectedBatchItem(batch, pending, runtimeRevisionBefore, exception);
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        exception instanceof ArithmeticException
                                ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND,
                        List.of());
                batch.nextIndex++;
            }
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    private void requireUnchangedRejectedBatchItem(OrderBatchPending batch, PendingMatching pending,
                                                   long runtimeRevisionBefore,
                                                   RuntimeException exception) {
        if (runtimePlaceOrderState.revision() != runtimeRevisionBefore) {
            rollbackOrderBatchMutations(batch, true);
            throw failMatching(pending, "order batch item mutated before rejection", exception);
        }
    }

    private void prepareOrderBatchItem(OrderBatchPending batch, OrderBatchItem item, long userId,
                                       UUID commandId) {
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                requireOrderIdentityAvailable(userId, command);
                reservePlaceOrderRuntime(userId, command, commandId, batch.sequence,
                        batchOpenInterestSteps(batch, command.symbol()), batch.admissionOrderIndex, batch);
                batch.currentPreMatchingCancellationOrderIds = preMatchingCloseCapacityCancellations(
                        userId, command, command.orderId());
            }
            case CANCEL -> {
                batch.currentPreMatchingCancellationOrderIds = List.of();
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                OrderRuntime order = runtimeOrder(command.orderId());
                if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                if (order.userId() != userId) {
                    throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                }
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                OrderRuntime order = runtimeOrder(command.originalOrderId());
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
                        batchOpenInterestSteps(batch, replacement.symbol()), batch.admissionOrderIndex,
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
                var order = runtimePlaceOrderState.order(command.orderId());
                orderId = command.orderId();
                instrumentVersion = order == null ? 0 : order.instrumentVersion();
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                OrderRuntime order = runtimeOrder(command.originalOrderId());
                PlaceOrderCommand replacement = replacementForAmend(command, order);
                orderId = replacement.orderId();
                instrumentVersion = replacement.instrumentVersion();
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
        return withMatchingEvidence(pending, orderId, instrumentVersion, () -> {
            try {
                List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellations =
                        preMatchingCancellationOrders(pending);
                return matchingAdapter.executeAfterCancellations(preMatchingCancellations, () -> switch (batch.kind) {
                case PLACE -> matchingAdapter.placeAsync(pending.command().header().userId(),
                        matchingOrder(item.orderId()));
                case CANCEL -> {
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    var order = runtimePlaceOrderState.order(command.orderId());
                    yield matchingAdapter.cancelAsyncForContinuation(pending.command().header().userId(),
                            command.orderId(), order == null
                                    ? "" : runtimePlaceOrderIdentities.symbol(order.symbolId()));
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    OrderRuntime order = runtimeOrder(command.originalOrderId());
                    yield matchingAdapter.replaceOrderAsync(pending.command().header().userId(),
                            command.originalOrderId(), runtimeOrderSymbol(order), matchingOrder(
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
            OrderBatchPending failedBatch = pendingOrderBatches.get(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            throw failOrderBatch(batch, pending, detail, failure);
        }
        if (batch.pipelined) {
            return completePipelinedPerpetualPlaceBatch(
                    batch, pending, matchingResult, clusterTimestamp, clusterPosition);
        }
        batch.lastMatchingResult = matchingResult;
        batch.retainMatchingResult(matchingResult);
        if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
            try {
                batch.advanceMatcher(matchingResult);
            } catch (IllegalArgumentException exception) {
                throw failOrderBatch(batch, pending, "order batch matcher transition is not contiguous", exception);
            }
        }
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            List<CoreExecutionView> executions = applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            List<Long> itemChangedUserIds = matchingUserIds(pending.command().header().userId(),
                    matchingResult.matcherEvents());
            batch.changedUserIds.addAll(itemChangedUserIds);
            List<Long> changedOrderIds = batch.changedOrderIdsFor(item, matchingResult);
            batch.changedOrderIds.addAll(changedOrderIds);
            batch.runtimeChangedOrderIds.addAll(batch.runtimeChangedOrderIdsFor(item, matchingResult));
            for (Long orderId : changedOrderIds) {
                batch.admissionOrderIndex.update(runtimePlaceOrderState.currentPatchOrderBefore(orderId),
                        runtimeOrder(orderId));
            }
            appendOrderBatchResult(batch, item, status, resultCode, executions);
            batch.nextIndex++;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
            throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
        }
        return startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition);
    }

    private CoreResponse completePipelinedPerpetualPlaceBatch(
            OrderBatchPending batch, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult firstMatchingResult,
            long clusterTimestamp, long clusterPosition) {
        List<com.surprising.aeron.service.matching.CoreMatchingResult> matchingResults =
                batch.pipelinedMatchingResults;
        if (matchingResults == null || matchingResults.size() != batch.items.size()
                || matchingResults.getFirst().nativeCommand().matcherSequence()
                != firstMatchingResult.nativeCommand().matcherSequence()) {
            throw failOrderBatch(batch, pending, "pipelined matcher batch result is incomplete", null);
        }
        for (int index = 0; index < matchingResults.size(); index++) {
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult = matchingResults.get(index);
            if (matchingResultNeedsRecovery(pending, matchingResult)) {
                throw failOrderBatch(batch, pending,
                        "matcher continuation returned " + matchingResult.resultCode(), null);
            }
            if (index > 0 && !BENCHMARK_SKIP_MATCHING_SUBMIT) {
                validateMatchingEvidence(pending, matchingResult);
                applyMatcherProgress(matchingResult);
            }
            batch.lastMatchingResult = matchingResult;
            batch.retainMatchingResult(matchingResult);
            if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
                try {
                    batch.advanceMatcher(matchingResult);
                } catch (IllegalArgumentException exception) {
                    throw failOrderBatch(batch, pending,
                            "order batch matcher transition is not contiguous", exception);
                }
            }
            OrderBatchItem item = batch.items.get(batch.nextIndex);
            ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
            CoreResultCode resultCode = matchingResult.accepted()
                    ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
            try {
                List<CoreExecutionView> executions =
                        applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
                batch.changedUserIds.addAll(matchingUserIds(
                        pending.command().header().userId(), matchingResult.matcherEvents()));
                List<Long> changedOrderIds = batch.changedOrderIdsFor(item, matchingResult);
                batch.changedOrderIds.addAll(changedOrderIds);
                batch.runtimeChangedOrderIds.addAll(batch.runtimeChangedOrderIdsFor(item, matchingResult));
                for (Long orderId : changedOrderIds) {
                    batch.admissionOrderIndex.update(runtimePlaceOrderState.currentPatchOrderBefore(orderId),
                            runtimeOrder(orderId));
                }
                appendOrderBatchResult(batch, item, status, resultCode, executions);
                batch.nextIndex++;
            } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
                throw failOrderBatch(batch, pending, "Core and matcher state diverged", exception);
            }
        }
        return finishOrderBatch(batch, pending, clusterTimestamp, clusterPosition);
    }

    private long batchOpenInterestSteps(OrderBatchPending batch, String symbol) {
        String normalizedSymbol = symbol.strip().toUpperCase(java.util.Locale.ROOT);
        com.surprising.aeron.service.state.OpenInterestIndex.Totals totals =
                openInterestIndex.totals().get(normalizedSymbol);
        long longQuantity = totals == null ? 0 : totals.longQuantity();
        long shortQuantity = totals == null ? 0 : totals.shortQuantity();
        for (Long userId : batch.changedUserIds) {
            long before = batchPositionQuantityBefore(userId, normalizedSymbol);
            long current = runtimePositionQuantity(userId, normalizedSymbol);
            if (before > 0) longQuantity = Math.subtractExact(longQuantity, before);
            else if (before < 0) shortQuantity = Math.subtractExact(shortQuantity, Math.negateExact(before));
            if (current > 0) longQuantity = Math.addExact(longQuantity, current);
            else if (current < 0) shortQuantity = Math.addExact(shortQuantity, Math.negateExact(current));
        }
        return Math.max(longQuantity, shortQuantity);
    }

    private long batchPositionQuantityBefore(long userId, String symbol) {
        long quantity = 0;
        for (com.surprising.aeron.protocol.CorePositionSide side
                : com.surprising.aeron.protocol.CorePositionSide.values()) {
            String key = side == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + side.name();
            Long positionKey = runtimePlaceOrderIdentities.findPositionKey(userId, key);
            if (positionKey == null) continue;
            com.surprising.aeron.service.state.PositionRuntime position =
                    runtimePlaceOrderState.currentPatchPositionBefore(positionKey);
            if (position != null) quantity = Math.addExact(quantity, position.signedQuantitySteps());
        }
        return quantity;
    }

    private void rollbackOrderBatchMutations(OrderBatchPending batch, boolean endScope) {
        abortSnapshotProjectionBatch();
        runtimePlaceOrderState.rollbackActiveCommand(batch.runtimeCheckpoint, batch.sequence);
        batch.rollbackPreparedClientKeys(runtimePlaceOrderIdentities);
        runtimePlaceOrderIdentities.rollbackPositionKeys(batch.positionIdentityCheckpoint);
        if (endScope) runtimePlaceOrderState.endOrderBatchMutationScope();
    }

    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException failOrderBatch(
            OrderBatchPending batch, PendingMatching pending, String detail, Throwable cause) {
        // Exchange-core facts are irreversible here. Preserve their observed sequence/prefix for replay evidence,
        // stop every continuation, and roll back only the unpublished Product Core runtime command.
        matchingAdapter.poisonFromOwner("fatal order batch divergence sequence=" + batch.sequence
                + " detail=" + detail);
        queuedMatching.clear();
        deferredMatching.clear();
        pendingMatching.forEach(value -> {
            if (laneCommandContexts.claimed(value.sequence())) {
                laneCommandContexts.required(value.sequence()).resetMatchingContinuation();
            }
        });
        matchingCompletions.clear();
        Throwable failure = cause;
        try {
            rollbackOrderBatchMutations(batch, true);
        } catch (RuntimeException rollbackFailure) {
            if (failure == null) failure = rollbackFailure;
            else failure.addSuppressed(rollbackFailure);
        }
        return failMatching(pending, detail, failure);
    }

    private long runtimePositionQuantity(long userId, String symbol) {
        long quantity = 0;
        for (com.surprising.aeron.protocol.CorePositionSide side
                : com.surprising.aeron.protocol.CorePositionSide.values()) {
            String key = side == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + side.name();
            Long positionKey = runtimePlaceOrderIdentities.findPositionKey(userId, key);
            if (positionKey == null) continue;
            com.surprising.aeron.service.state.PositionRuntime position =
                    runtimePlaceOrderState.position(positionKey);
            if (position != null) quantity = Math.addExact(quantity, position.signedQuantitySteps());
        }
        return quantity;
    }

    private List<CoreExecutionView> applyOrderBatchMatcherResult(
            OrderBatchPending batch, OrderBatchItem item, PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        switch (batch.kind) {
            case PLACE -> {
                PlaceOrderCommand command = (PlaceOrderCommand) item.command;
                applyPreMatchingCancellations(pending, matchingResult);
                if (matchingResult.accepted()) {
                    if (productLine.isDerivative() && pending.preMatchingCancellationOrderIds().isEmpty()) {
                        batch.deferredPerpetualOrderIds.add(command.orderId());
                        batch.deferredPerpetualExpectedLaneMasks.add(expectedLaneMask(pending, matchingResult));
                        batch.deferredPerpetualMatchingResults.add(matchingResult);
                    } else if (matchingResult.matcherEvents().stream()
                            .noneMatch(event -> event.eventType() == MatcherEventType.TRADE)
                            && pending.preMatchingCancellationOrderIds().isEmpty()) {
                        batch.deferredNoTradeOrderIds.add(command.orderId());
                        batch.deferredNoTradeMatchingResults.add(matchingResult);
                    } else {
                        batch.mergeTreasuryDeltas(runtimePlaceOrderState.applyMatcherSettlement(
                                pending.sequence(), expectedLaneMask(pending, matchingResult), command.orderId(),
                                matchingResult, runtimePlaceOrderIdentities));
                    }
                } else {
                    rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId(), pending.sequence());
                }
                return executionViews(command.orderId(), pending.command().header().userId(),
                        matchingResult.matcherEvents());
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                if (matchingResult.accepted()) {
                    batch.acceptedCancellationOrderIds.add(command.orderId());
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
                            pending.command().header().commandId(), pending.sequence(),
                            batchOpenInterestSteps(batch, replacement.symbol()), batch.admissionOrderIndex, batch);
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
        batch.results.add(new CoreOrderBatchResult.Item(batch.results.size(), item.orderId,
                item.originalOrderId, item.replacementOrderId, status, resultCode,
                null, executions));
    }

    private CoreResponse finishOrderBatch(OrderBatchPending batch, PendingMatching pending,
                                          long clusterTimestamp, long clusterPosition) {
        CoreAdmissionReservation capacityReservation = pending.capacityReservation();
        if (capacityReservation == null) {
            throw new IllegalStateException("order batch admission reservation is missing");
        }
        activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
        commandFundsDelta = pending.fundsDelta();
        if (!batch.deferredPerpetualOrderIds.isEmpty()) {
            batch.mergeTreasuryDeltas(runtimePlaceOrderState.applyPerpetualMatcherSettlements(
                    batch.sequence, batch.deferredPerpetualOrderIds,
                    batch.deferredPerpetualExpectedLaneMasks, batch.deferredPerpetualMatchingResults,
                    runtimePlaceOrderIdentities));
        }
        if (!batch.deferredNoTradeOrderIds.isEmpty()) {
            batch.mergeTreasuryDeltas(runtimePlaceOrderState.applyNoTradeMatcherSettlements(
                    batch.sequence, pending.command().header().userId(), batch.deferredNoTradeOrderIds,
                    batch.deferredNoTradeMatchingResults, runtimePlaceOrderIdentities));
        }
        if (!batch.acceptedCancellationOrderIds.isEmpty()) {
            cancelOrderBatchRuntime(pending.command().header().userId(), batch.acceptedCancellationOrderIds);
        }
        try {
            runOrderBatchAfterItemFaultForTest();
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
            throw failOrderBatch(batch, pending, "order batch pre-commit validation failed", exception);
        }
        runtimePlaceOrderState.completePendingReservations(batch.sequence);
        if (!batch.runtimeChangedOrderIds.isEmpty()) {
            if (RuntimeCommandProcessor.stampChangedOrdersByLane(runtimePlaceOrderState,
                    clusterTimestamp, clusterPosition, batch.runtimeChangedOrderIds, batch.changedUserIds)) {
                refreshSnapshotProjection();
            }
        }
        commandChangedUserIds = List.copyOf(batch.changedUserIds);
        commandChangedOrderIds = List.copyOf(batch.changedOrderIds);
        LaneCommandContextRing.Context laneContext = laneCommandContexts.required(batch.sequence);
        long expectedLaneMask = 0;
        for (Long userId : commandChangedUserIds) {
            expectedLaneMask |= matchingAdapter.topology().accountLaneMask(userId);
        }
        long actualLaneMask = expectedLaneMask;
        if (orderBatchExpectedLaneMaskForTest != null) {
            expectedLaneMask = orderBatchExpectedLaneMaskForTest;
            orderBatchExpectedLaneMaskForTest = null;
        }
        var finalMatchingResult = batch.lastMatchingResult == null
                ? new com.surprising.aeron.service.matching.CoreMatchingResult(true, "NO_NATIVE_COMMAND")
                .withCoreSequence(batch.sequence)
                : batch.lastMatchingResult;
        laneContext.result(finalMatchingResult, expectedLaneMask, validAccountLaneMask());
        if (laneContext.expectedLaneMask() != actualLaneMask) {
            throw failOrderBatch(batch, pending, "order batch account lane mask mismatch", null);
        }
        RuntimeTreasuryDelta[] laneTreasuryDeltas = batch.laneTreasuryDeltas == null
                ? new RuntimeTreasuryDelta[matchingAdapter.topology().accountLaneCount()]
                : batch.laneTreasuryDeltas;
        List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneApply;
        try {
            RuntimeTreasuryDelta expectedTreasuryDelta = mergeTreasuryDeltas(laneTreasuryDeltas);
            expectedTreasuryDelta.apply(runtimePlaceOrderState.treasury());
            runtimePlaceOrderState.setMetadata(productLine,
                    Math.incrementExact(runtimePlaceOrderState.revision()));
            laneApply = applyAndCommitLaneSequence(batch.sequence, commandChangedUserIds,
                    laneContext.matchingResult(), rollingBusinessStateHash.value(), rollingFundsStateHash.value(),
                    laneContext);
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw new IllegalStateException("order batch account lane mask mismatch");
            }
            requireCompleteAccountLanes(laneContext);
        } catch (RuntimeException validationFailure) {
            throw failOrderBatch(batch, pending, "order batch final validation failed", validationFailure);
        }
        completeSnapshotProjectionBatch(laneApply);
        materializeChangeAccumulators();
        java.util.ArrayList<CoreOrderBatchResult.Item> resultItems = new java.util.ArrayList<>(batch.results.size());
        java.util.ArrayList<CoreExecutionView> executions = new java.util.ArrayList<>();
        for (CoreOrderBatchResult.Item item : batch.results) {
            OrderRuntime order = runtimeOrder(item.orderId());
            if (order == null && item.originalOrderId() > 0) order = runtimeOrder(item.originalOrderId());
            resultItems.add(new CoreOrderBatchResult.Item(item.index(), item.orderId(), item.originalOrderId(),
                    item.replacementOrderId(), item.status(), item.resultCode(),
                    order == null ? null : orderView(order), item.executions()));
            executions.addAll(item.executions());
        }
        CoreOrderBatchResult result = new CoreOrderBatchResult(resultItems);
        byte[] responseData = TradingOrderBatchCodec.encodeResult(result);
        commandExecutions = List.copyOf(executions);
        CoreCommandDelta delta = commandDelta();
        validateFundsConservation(pending.command());
        commitMatchingSequence(batch.sequence);
        long businessStateHash = currentProjectionPoint == batch.beforeProjection
                ? cachedBusinessStateHash : currentBusinessStateHash();
        long requiredExportSequence = appendCoreFact(pending.command(), pending.fingerprint(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence, businessStateHash, batch.beforeProjection,
                currentProjectionPoint, delta,
                batch.matcherTransition);
        cachedBusinessStateHash = businessStateHash;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, batch.sequence);
        storeResult(pending.command().header().commandId(), StoredResult.owned(
                pending.fingerprint(), ResponseStatus.APPLIED, CoreResultCode.NONE,
                batch.sequence, requiredExportSequence, stateHash, responseData));
        laneCommandContexts.release(batch.sequence);
        runtimePlaceOrderState.endOrderBatchMutationScope();
        removePendingMatching(batch.sequence);
        pendingOrderBatches.remove(batch.sequence);
        submitDeferredMatchingAfterBatch();
        CoreResponse response = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, batch.sequence, requiredExportSequence, stateHash, responseData);
        return releaseAdmission(capacityReservation, response);
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
        return newPendingMatching(sequence, operation, command, CommandFingerprint.of(command));
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint) {
        return new PendingMatching(sequence, operation, command, fingerprint, currentProjectionPoint,
                currentBusinessStateHash(), rollingFundsStateHash.value(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty());
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations) {
        return new PendingMatching(sequence, operation, command, preMatchingCancellations, currentProjectionPoint,
                currentBusinessStateHash(), rollingFundsStateHash.value(),
                com.surprising.aeron.service.state.RuntimeFundsDelta.empty());
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, List<Long> preMatchingCancellations,
                                               RuntimeProjectionPoint beforeProjection, long beforeBusinessStateHash,
                                               long beforeFundsStateHash,
                                               DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return new PendingMatching(sequence, operation, command, preMatchingCancellations, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, commandFundsDelta, decodedCommand, admission);
    }

    private PendingMatching newPendingMatching(long sequence, PendingMatching.Operation operation,
                                               CoreMessage command, CommandFingerprint fingerprint,
                                               List<Long> preMatchingCancellations,
                                               RuntimeProjectionPoint beforeProjection, long beforeBusinessStateHash,
                                               long beforeFundsStateHash, DecodedMatchingCommand decodedCommand,
                                               ResolvedMatchingAdmission admission) {
        return new PendingMatching(sequence, operation, command, fingerprint, preMatchingCancellations,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, commandFundsDelta,
                decodedCommand, admission);
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
                                       SourceKey sourceKey, CommandFingerprint fingerprint) {
        PendingMatching.Operation operation = matchingOperation(message.header().messageType());
        if (!pendingOrderBatches.isEmpty()) {
            return deferMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint);
        }
        return prepareMatching(message, clusterTimestamp, clusterPosition, sourceKey, operation, fingerprint, null);
    }

    private int matchingOrderBound(CoreMessage message) {
        int inFlightOrders = pendingMatching.size();
        if (!pendingOrderBatches.isEmpty()) {
            inFlightOrders = Math.addExact(inFlightOrders, PlaceOrderBatchCommand.MAX_ORDERS);
        }
        return switch (message.header().messageType()) {
            case PLACE_ORDER -> {
                PlaceOrderCommand command = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
                yield Math.addExact(activeOrderIndex.count(command.symbol()), inFlightOrders);
            }
            case PLACE_ORDER_BATCH -> {
                PlaceOrderBatchCommand command =
                        TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe());
                int activeOrders = 0;
                for (PlaceOrderCommand order : command.orders()) {
                    activeOrders = Math.max(activeOrders, activeOrderIndex.count(order.symbol()));
                }
                yield Math.addExact(Math.addExact(activeOrders, inFlightOrders), command.orders().size());
            }
            default -> 0;
        };
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
                                         CommandFingerprint fingerprint, PendingMatching deferredPending) {
        long matchingStartNanos = System.nanoTime();
        CoreAdmissionReservation capacityReservation;
        try {
            capacityReservation = deferredPending == null
                    ? CoreAdmissionReservation.reserve(runtimeProjectionJournal, exportState,
                            CoreAdmissionReservation.AdmissionDemand.matching(
                                    message, matchingOrderBound(message)))
                    : deferredPending.capacityReservation();
            if (capacityReservation == null) {
                throw new IllegalStateException("deferred matching admission reservation is missing");
            }
        } catch (CoreStateRejectedException rejection) {
            return deferredPending == null
                    ? admissionRejected(CoreResultCode.fromRejectionCode(rejection.code())) : null;
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return deferredPending == null
                    ? admissionRejected(rejection instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND) : null;
        }
        CommandFingerprint effectiveFingerprint = deferredPending == null
                ? fingerprint : deferredPending.fingerprint();
        activateFactContext(capacityReservation, message, effectiveFingerprint);
        DecodedMatchingCommand decodedCommand = deferredPending == null
                ? DecodedMatchingCommand.decode(message) : deferredPending.decodedCommand();
        try {
            rejectLifecycleOverlap(message, operation, decodedCommand);
        } catch (CoreStateRejectedException exception) {
            CoreResponse response = recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return releaseAdmission(capacityReservation, response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            CoreResponse response = recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return releaseAdmission(capacityReservation, response);
        }
        RuntimeProjectionPoint beforeProjection = deferredPending == null
                ? currentProjectionPoint : deferredPending.beforeProjection();
        long beforeBusinessStateHash = deferredPending == null
                ? currentBusinessStateHash() : deferredPending.beforeBusinessStateHash();
        long beforeFundsStateHash = deferredPending == null
                ? rollingFundsStateHash.value() : deferredPending.beforeFundsStateHash();
        long sequence = deferredPending == null
                ? Math.incrementExact(appliedCommandCount) : deferredPending.sequence();
        List<Long> preMatchingCancellations = List.of();
        ResolvedMatchingAdmission admission = null;
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
                    var command = decodedCommand.placeOrder();
                    requireOrderIdentityAvailable(message.header().userId(), command);
                    reservePlaceOrderRuntime(message.header().userId(), command,
                            message.header().commandId(), sequence);
                    commandChangedUserIds = List.of(message.header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    commandOrderViews = List.of(runtimeOrderView(command.orderId()));
                }
                case CANCEL -> validatePendingCancel(message, decodedCommand);
                case REPLACE -> admission = validatePendingReplace(message, decodedCommand, false);
                case AMEND -> admission = validatePendingReplace(message, decodedCommand, true);
                case TRIGGER -> validatePendingTrigger(decodedCommand);
                case LIQUIDATION -> validatePendingLiquidation(decodedCommand);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(decodedCommand);
                case SETTLEMENT -> validatePendingSettlement(decodedCommand);
            }
            preMatchingCancellations = preMatchingCloseCapacityCancellations(
                    operation, message, decodedCommand, admission);
        } catch (CoreStateRejectedException exception) {
            if (snapshotProjectionDirty) {
                if (!pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                restoreCommandState(beforeProjection);
            }
            else abortSnapshotProjectionBatch();
            CoreResponse response = recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    CoreResultCode.fromRejectionCode(exception.code()), deferredPending);
            return releaseAdmission(capacityReservation, response);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            if (snapshotProjectionDirty) {
                if (!pendingMatching.isEmpty()) {
                    throw new IllegalStateException("cannot roll back across an in-flight lane command", exception);
                }
                restoreCommandState(beforeProjection);
            }
            else abortSnapshotProjectionBatch();
            CoreResponse response = recordRejectedMatching(message, sourceKey, effectiveFingerprint,
                    exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND, deferredPending);
            return releaseAdmission(capacityReservation, response);
        }
        boolean tradingStateChanged = snapshotProjectionDirty && !snapshotProjectionProvisionalOnly;
        if (tradingStateChanged) {
            try {
                stampOrderChangesRuntime(clusterTimestamp, clusterPosition, commandChangedOrderIds);
            } catch (RuntimeException exception) {
                restoreCommandState(beforeProjection);
                throw exception;
            }
        }
        completeSnapshotProjectionBatch();
        try {
            commandDelta = commandDelta();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("matching delta failed after typed state commit", exception);
        }
        long businessStateHash = tradingStateChanged ? currentBusinessStateHash() : cachedBusinessStateHash;
        long requiredExportSequence = 0;
        PendingMatching pending = deferredPending == null
                ? newPendingMatching(sequence, operation, message, effectiveFingerprint,
                        preMatchingCancellations, beforeProjection,
                        beforeBusinessStateHash, beforeFundsStateHash, decodedCommand, admission)
                : deferredPending.withPreMatchingCancellations(preMatchingCancellations)
                        .withAdmission(admission);
        pending = pending.withCapacityReservation(capacityReservation);
        if (deferredPending == null) {
            putPendingMatching(pending);
        } else {
            pendingMatching.put(pending);
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
        byte[] responseData = EMPTY_RESPONSE_DATA;
        pending.withPendingStateHash(stateHash);
        if (MATCHING_PHASE_METRICS_ENABLED) {
            matchingPhaseMetrics.recordPrepare(System.nanoTime() - matchingStartNanos);
        }
        submitMatching(pending);
        clearFactContext();
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                sequence, requiredExportSequence, stateHash, responseData);
    }

    private CoreResponse deferMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       SourceKey sourceKey, PendingMatching.Operation operation,
                                       CommandFingerprint fingerprint) {
        CoreAdmissionReservation reservation;
        try {
            reservation = CoreAdmissionReservation.reserve(runtimeProjectionJournal, exportState,
                    CoreAdmissionReservation.AdmissionDemand.matching(message, matchingOrderBound(message)));
        } catch (CoreStateRejectedException rejection) {
            return admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (ArithmeticException | IllegalArgumentException rejection) {
            return admissionRejected(rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        long sequence = Math.incrementExact(appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, operation, message, fingerprint)
                .withCapacityReservation(reservation);
        putPendingMatching(pending);
        deferredMatching.put(sequence, new DeferredMatching(clusterTimestamp, clusterPosition, sourceKey));
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(), ResponseStatus.OK,
                matchingPendingCode(), sequence);
        pending.withPendingStateHash(stateHash);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                sequence, 0, stateHash, EMPTY_RESPONSE_DATA);
    }

    private CoreResponse releaseAdmission(CoreAdmissionReservation reservation, CoreResponse response) {
        clearFactContext();
        if (reservation != null) reservation.releaseUnused();
        return response;
    }

    private void activateFactContext(CoreAdmissionReservation reservation, CoreMessage command,
                                     CommandFingerprint fingerprint) {
        if (reservation == null || command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete Core Fact context is required before mutation");
        }
        currentAdmission = reservation;
        activeFactCommand = command;
        activeFactFingerprint = fingerprint;
        activeFactTopologyHash = matchingAdapter.topology().topologyHash();
        activeFactLaneRevisionHash = laneRevisionHash();
    }

    private void activateRetentionContext(CoreMessage command, CommandFingerprint fingerprint) {
        if (currentRetentionAdmission == null || command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete retention commit context is required before mutation");
        }
        activeFactCommand = command;
        activeFactFingerprint = fingerprint;
        activeFactTopologyHash = matchingAdapter.topology().topologyHash();
        activeFactLaneRevisionHash = laneRevisionHash();
    }

    private void releaseRetentionAdmission() {
        if (currentRetentionAdmission != null && currentRetentionAdmission.remaining() > 0) {
            runtimeProjectionJournal.release(currentRetentionAdmission);
        }
        currentRetentionAdmission = null;
        clearFactContext();
    }

    private void clearFactContext() {
        currentAdmission = null;
        activeFactCommand = null;
        activeFactFingerprint = null;
        activeFactTopologyHash = 0;
        activeFactLaneRevisionHash = 0;
    }

    private CoreResponse admissionRejected(CoreResultCode resultCode) {
        currentClusterTimestamp = admissionPreviousClusterTimestamp;
        currentClusterPosition = admissionPreviousClusterPosition;
        return rejected(resultCode);
    }

    private CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CommandFingerprint fingerprint, CoreResultCode resultCode,
                                                PendingMatching deferredPending) {
        return deferredPending == null ? recordRejectedMatching(message, sourceKey, fingerprint, resultCode)
                : recordRejectedDeferredMatching(deferredPending, resultCode);
    }

    private CoreResponse recordRejectedDeferredMatching(PendingMatching pending, CoreResultCode resultCode) {
        commitMatchingSequence(pending.sequence());
        long requiredExportSequence = appendRejectedCoreFact(
                pending.command(), pending.fingerprint(), resultCode, pending.sequence());
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
                                                CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!pendingMatching.isEmpty()) {
            long sequence = Math.incrementExact(appliedCommandCount);
            currentAdmission.retainHolders(1);
            PendingMatching pending = newPendingMatching(sequence,
                    matchingOperation(message.header().messageType()), message, fingerprint)
                    .withCapacityReservation(currentAdmission);
            putPendingMatching(pending);
            pendingMatchingRejections.put(sequence, resultCode);
            appliedCommandCount = sequence;
            recordSourceSequence(sourceKey, message.header().sourceSequence());
            long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                    ResponseStatus.OK, matchingPendingCode(), sequence);
            pending.withPendingStateHash(stateHash);
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                    sequence, 0, stateHash, EMPTY_RESPONSE_DATA);
        }
        long sequence = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence = appendRejectedCoreFact(
                message, fingerprint, resultCode, sequence);
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.REJECTED, resultCode, appliedCommandCount);
        storeResult(message.header().commandId(), new StoredResult(fingerprint,
                ResponseStatus.REJECTED, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                new byte[0], 0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, new byte[0]);
    }

    private void validatePendingCancel(CoreMessage message, DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.cancelOrder();
        var order = runtimePlaceOrderState.order(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(command.orderId());
    }

    private ResolvedMatchingAdmission validatePendingReplace(
            CoreMessage message, DecodedMatchingCommand decodedCommand, boolean amend) {
        long originalOrderId = amend ? decodedCommand.amendOrder().originalOrderId()
                : decodedCommand.replaceOrder().originalOrderId();
        var order = runtimePlaceOrderState.order(originalOrderId);
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
        PlaceOrderCommand replacement = replacementFor(decodedCommand,
                amend ? PendingMatching.Operation.AMEND : PendingMatching.Operation.REPLACE, order);
        if (replacement.orderId() != originalOrderId) {
            requireOrderIdentityAvailable(message.header().userId(), replacement);
        }
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, message.header().userId(), replacement, currentClusterTimestamp);
        long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                runtimePlaceOrderState, runtimePlaceOrderIdentities, message.header().userId(), resolved,
                openInterestIndex.openInterestSteps(replacement.symbol()), activeOrderIndex, originalOrderId);
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(originalOrderId);
        var user = runtimePlaceOrderState.user(message.header().userId());
        var matchingOrder = new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(),
                resolved.orderType(), resolved.timeInForce(), resolved.matchingPriceTicks(),
                resolved.quantitySteps());
        return new ResolvedMatchingAdmission(message.header().userId(), originalOrderId, order.revision(),
                user == null ? 0 : user.revision(), replacement, resolved, matchingOrder, requiredReservation);
    }

    private void validatePendingTrigger(DecodedMatchingCommand decodedCommand) {
        long[] execute = decodedCommand.trigger();
        var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        PlaceOrderCommand child = triggerPlacement(trigger, execute[2]);
        var order = runtimePlaceOrderState.order(child.orderId());
        if (order == null || order.userId() != trigger.userId()) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "trigger child reservation is missing");
        }
        commandChangedUserIds = List.of(trigger.userId());
        commandChangedOrderIds = List.of(child.orderId());
        commandChangedTriggerOrderIds = List.of(trigger.triggerOrderId());
    }

    private List<Long> preMatchingCloseCapacityCancellations(
            PendingMatching.Operation operation,
            CoreMessage message,
            DecodedMatchingCommand decodedCommand,
            ResolvedMatchingAdmission admission) {
        PlaceOrderCommand placement;
        long excludedOrderId = 0;
        switch (operation) {
            case PLACE -> {
                placement = decodedCommand.placeOrder();
                excludedOrderId = placement.orderId();
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                if (trigger == null) return List.of();
                placement = triggerPlacement(trigger, execute[2]);
                excludedOrderId = placement.orderId();
            }
            case REPLACE, AMEND -> {
                if (admission == null) throw new IllegalStateException("replace admission is missing");
                excludedOrderId = admission.originalOrderId();
                placement = admission.command();
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
                .map(OrderRuntime::orderId)
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

    private void validatePendingLiquidation(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.liquidation();
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

    private void validatePendingLiquidationBatch(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.liquidationBatch();
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

    private void validatePendingSettlement(DecodedMatchingCommand decodedCommand) {
        var command = decodedCommand.settlement();
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

    private void rejectLifecycleOverlap(CoreMessage message, PendingMatching.Operation operation,
                                        DecodedMatchingCommand decodedCommand) {
        LifecycleScope candidate = lifecycleScope(message, operation, decodedCommand);
        if (candidate.symbol().isBlank()) return;
        ensureLifecycleScopeAvailable(candidate);
    }

    private void ensureLifecycleScopeAvailable(LifecycleScope candidate) {
        if (candidate.symbol().isBlank()) return;
        if (candidate.lifecycle()) {
            pendingMatching.forEach(pending -> {
                if (pendingLifecycleConflicts(candidate, pending)) {
                    throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                            "matching lifecycle scope is in progress");
                }
            });
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
        var batch = pending.decodedCommand().liquidationBatch();
        return batch.actions().stream().map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                        action.liquidationId(), true, false)).anyMatch(scope -> conflicts(candidate, scope));
    }

    private void registerPendingLifecycle(PendingMatching pending) {
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> pending.decodedCommand().liquidationBatch()
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
        if (laneCommandContexts.claimed(sequence)) laneCommandContexts.discard(sequence);
        matchingCompletions.poll(sequence);
        refreshCommittedCoreSequence();
        return removed;
    }

    private void refreshCommittedCoreSequence() {
        long candidate = pendingMatching.isEmpty()
                ? appliedCommandCount : Math.subtractExact(pendingMatching.firstSequence(), 1);
        while (committedCoreSequence < candidate) {
            long next = Math.incrementExact(committedCoreSequence);
            runtime.commitCoreSequence(next);
            committedCoreSequence = next;
        }
    }

    private void commitMatchingSequence(long sequence) {
        if (pendingMatching.isEmpty() || pendingMatching.firstSequence() != sequence
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
        pendingMatching.put(pending);
        laneCommandContexts.claim(pending.sequence());
    }

    private LifecycleScope lifecycleScope(CoreMessage message, PendingMatching.Operation operation,
                                          DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case LIQUIDATION -> {
                var command = decodedCommand.liquidation();
                var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                                liquidation.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0, decodedCommand.settlement().symbol(),
                    0, true, false);
            default -> new LifecycleScope(false, message.header().userId(),
                    matchingSymbol(message, operation, decodedCommand), 0, false, true);
        };
    }

    private LifecycleScope lifecycleScope(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var action = pending.decodedCommand().liquidationBatch().actions().getFirst();
            return new LifecycleScope(false, action.userId(), action.symbol(), action.liquidationId(), true, false);
        }
        return lifecycleScope(pending.command(), pending.operation(), pending.decodedCommand());
    }

    private static boolean conflicts(LifecycleScope left, LifecycleScope right) {
        if (left.symbol().isBlank() || !left.symbol().equals(right.symbol())
                || (!left.lifecycle() && !right.lifecycle())) return false;
        return left.settlement() || right.settlement() || left.userId() == right.userId();
    }

    private record LifecycleScope(boolean settlement, long userId, String symbol, long lifecycleId,
                                  boolean lifecycle, boolean orderChanging) {
    }

    private String matchingSymbol(CoreMessage message, PendingMatching.Operation operation,
                                  DecodedMatchingCommand decodedCommand) {
        return switch (operation) {
            case PLACE -> decodedCommand.placeOrder().symbol();
            case CANCEL -> {
                var command = decodedCommand.cancelOrder();
                var order = runtimePlaceOrderState.order(command.orderId());
                yield order == null ? "" : runtimePlaceOrderIdentities.symbol(order.symbolId());
            }
            case REPLACE, AMEND -> {
                long orderId = operation == PendingMatching.Operation.REPLACE
                        ? decodedCommand.replaceOrder().originalOrderId()
                        : decodedCommand.amendOrder().originalOrderId();
                var order = runtimePlaceOrderState.order(orderId);
                yield order == null ? "" : runtimePlaceOrderIdentities.symbol(order.symbolId());
            }
            case TRIGGER -> {
                long[] execute = decodedCommand.trigger();
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                yield trigger == null ? "" : trigger.symbol();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = decodedCommand.liquidationBatch();
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
            }
        };
    }

    private String pendingLifecycleSymbol(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            return pending.decodedCommand().settlement().symbol();
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var batch = pending.decodedCommand().liquidationBatch();
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
        }
        var liquidation = runtimePlaceOrderState.liquidation(
                pending.decodedCommand().liquidation().liquidationId());
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
        var command = pending.decodedCommand().liquidationBatch();
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
            laneCommandContexts.required(pending.sequence()).publishMatchingCompletion(
                    new com.surprising.aeron.service.matching.CoreMatchingResult(
                            true, "BENCHMARK_SKIPPED").withCoreSequence(pending.sequence()));
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
            PendingMatching pending = pendingMatching.findFirst(value -> {
                        if (value.sequence() > throughSequence) return false;
                        OrderBatchPending batch = pendingOrderBatches.get(value.sequence());
                        return batch == null ? deferredMatching.containsKey(value.sequence()) : !batch.started;
                    });
            if (pending == null) return;
            OrderBatchPending batch = pendingOrderBatches.get(pending.sequence());
            if (batch != null && !batch.started) {
                activateOrderBatch(batch, pending);
                return;
            }
            DeferredMatching deferred = deferredMatching.get(pending.sequence());
            if (deferred != null) {
                CoreResponse response = prepareMatching(pending.command(), deferred.clusterTimestamp,
                        deferred.clusterPosition, deferred.sourceKey, pending.operation(), pending.fingerprint(),
                        pending);
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
        LaneCommandContextRing.Context context = laneCommandContexts.required(sequence);
        LaneCommandContextRing.SubmissionToken token =
                laneCommandContexts.beginMatchingSubmission(sequence);
        registerMatchingCallback(context, token, matchingCompletions, future);
    }

    static void registerMatchingCallback(
            LaneCommandContextRing.Context context,
            LaneCommandContextRing.SubmissionToken token,
            MatchingCompletionQueue completions,
            CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future) {
        if (context == null || token == null || completions == null || future == null) {
            throw new IllegalArgumentException("matching callback registration is incomplete");
        }
        completions.submissionStarted();
        future.whenComplete((result, failure) -> {
            try {
                context.enqueueMatchingCompletion(token, completions,
                        matchingResult(token.coreSequence(), result, failure));
            } finally {
                completions.submissionCompleted();
            }
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
        if (MATCHING_PHASE_METRICS_ENABLED) {
            matchingSubmitNanos.put(pending.sequence(), System.nanoTime());
        }
        try {
            List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellations =
                    preMatchingCancellationOrders(pending);
            long userId = pending.command().header().userId();
            MatchingSubmission matching = switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    var order = matchingOrder(command.orderId());
                    yield new MatchingSubmission(command.orderId(), command.instrumentVersion(),
                            () -> matchingAdapter.placeAsync(userId, order));
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    var order = runtimePlaceOrderState.order(command.orderId());
                    String symbol = order == null ? "" : runtimePlaceOrderIdentities.symbol(order.symbolId());
                    long instrumentVersion = order == null ? 0 : order.instrumentVersion();
                    yield new MatchingSubmission(command.orderId(), instrumentVersion,
                            () -> matchingAdapter.cancelAsyncForContinuation(userId, command.orderId(), symbol));
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = requireMatchingAdmission(pending);
                    var order = runtimeOrder(admission.originalOrderId());
                    yield new MatchingSubmission(admission.resolved().orderId(),
                            admission.resolved().instrumentVersion(),
                            () -> matchingAdapter.replaceOrderAsync(userId, admission.originalOrderId(),
                                    runtimeOrderSymbol(order), admission.matchingOrder()));
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                    if (trigger == null) {
                        yield new MatchingSubmission(0, 0, () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        false, "TRIGGER_ORDER_NOT_FOUND")));
                    }
                    PlaceOrderCommand placement = triggerPlacement(trigger, execute[2]);
                    var order = matchingOrder(placement.orderId());
                    yield new MatchingSubmission(placement.orderId(), placement.instrumentVersion(),
                            () -> matchingAdapter.placeAsync(trigger.userId(), order));
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
                    if (liquidation == null || !com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                            .isExecutable(runtimePlaceOrderState, runtimePlaceOrderIdentities, command)) {
                        yield new MatchingSubmission(command.liquidationId(),
                                liquidation == null ? 0 : liquidation.instrumentVersion(),
                                () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS")));
                    }
                    var orders = lifecycleOrders(liquidation.userId(),
                            runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(), command.maxOrders()).orders();
                    yield new MatchingSubmission(command.liquidationId(), liquidation.instrumentVersion(),
                            () -> matchingAdapter.cancelBatchAsync(orders));
                }
                case LIQUIDATION_BATCH -> {
                    var orders = batchCancellationOrders(pending);
                    yield new MatchingSubmission(0, 0, () -> matchingAdapter.cancelBatchAsync(orders));
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    var progress = runtimeLifecycleProgress(command.symbol());
                    if (progress != null && progress.ordersComplete()) {
                        yield new MatchingSubmission(0, command.instrumentVersion(),
                                () -> CompletableFuture.completedFuture(
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS")));
                    }
                    var orders = lifecycleOrders(0, command.symbol(), command.cursorOrderId(),
                            command.maxOrders()).orders();
                    yield new MatchingSubmission(0, command.instrumentVersion(),
                            () -> matchingAdapter.cancelBatchAsync(orders));
                }
            };
            return withMatchingEvidence(pending, matching.orderId(), matching.instrumentVersion(),
                    () -> matchingAdapter.executeAfterCancellations(
                            preMatchingCancellations, matching.submission()));
        } catch (RuntimeException exception) {
            return withMatchingEvidence(pending, () -> CompletableFuture.completedFuture(
                    new com.surprising.aeron.service.matching.CoreMatchingResult(
                            false, "EXCHANGE_CORE_FAILURE")));
        }
    }

    private List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellationOrders(
            PendingMatching pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<DeterministicExchangeCoreAdapter.CancellationOrder> orders = new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            OrderRuntime order = runtimeOrder(orderId);
            if (order != null && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
                orders.add(new DeterministicExchangeCoreAdapter.CancellationOrder(
                        order.orderId(), order.userId(), runtimeOrderSymbol(order)));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }

    private record MatchingSubmission(
            long orderId,
            long instrumentVersion,
            java.util.function.Supplier<CompletableFuture<
                    com.surprising.aeron.service.matching.CoreMatchingResult>> submission) {
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> withMatchingEvidence(
            PendingMatching pending,
            java.util.function.Supplier<CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult>>
                    submission) {
        long orderId = 0;
        long instrumentVersion = 0;
        switch (pending.operation()) {
            case PLACE -> {
                PlaceOrderCommand command = pending.decodedCommand().placeOrder();
                orderId = command.orderId();
                instrumentVersion = command.instrumentVersion();
            }
            case CANCEL -> {
                CancelOrderCommand command = pending.decodedCommand().cancelOrder();
                var order = runtimePlaceOrderState.order(command.orderId());
                orderId = command.orderId();
                instrumentVersion = order == null ? 0 : order.instrumentVersion();
            }
            case REPLACE, AMEND -> {
                long originalId = pending.operation() == PendingMatching.Operation.REPLACE
                        ? pending.decodedCommand().replaceOrder().originalOrderId()
                        : pending.decodedCommand().amendOrder().originalOrderId();
                PlaceOrderCommand replacement = replacementFor(pending, runtimeOrder(originalId));
                orderId = replacement.orderId();
                instrumentVersion = replacement.instrumentVersion();
            }
            case TRIGGER -> {
                long[] execute = pending.decodedCommand().trigger();
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                if (trigger != null) {
                    PlaceOrderCommand placement = triggerPlacement(trigger, execute[2]);
                    orderId = placement.orderId();
                    instrumentVersion = placement.instrumentVersion();
                }
            }
            case LIQUIDATION -> {
                var command = pending.decodedCommand().liquidation();
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
        boolean controlShard = pendingOrderBatches.containsKey(pending.sequence())
                || !pending.preMatchingCancellationOrderIds().isEmpty()
                || pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT;
        return controlShard
                ? matchingAdapter.executeControlWithEvidence(
                        pending.sequence(), pending.command().header().commandId(), orderId, instrumentVersion,
                        pending.command().header().submittedAtEpochMillis(), submission)
                : matchingAdapter.executeWithEvidence(
                        pending.sequence(), pending.command().header().commandId(), orderId, instrumentVersion,
                        pending.command().header().submittedAtEpochMillis(), submission);
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message,
                                                                            OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String symbol = runtimeOrderSymbol(order);
        var instrument = runtimePlaceOrderState.instrument(symbol);
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
        return new com.surprising.aeron.protocol.PlaceOrderCommand(command.replacementOrderId(), symbol,
                order.instrumentVersion(), order.side(), priceTicks, quantitySteps,
                order.reduceOnly(), order.marginMode(), order.positionSide(),
                order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    private PlaceOrderCommand replacementFor(DecodedMatchingCommand decodedCommand,
                                             PendingMatching.Operation operation,
                                             OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (runtimePlaceOrderState.instrument(runtimeOrderSymbol(order)) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        return operation == PendingMatching.Operation.REPLACE
                ? decodedCommand.replaceOrder().replacement()
                : replacementForAmend(decodedCommand.amendOrder(), order);
    }

    private PlaceOrderCommand replacementFor(PendingMatching pending, OrderRuntime order) {
        if (pending.operation() == PendingMatching.Operation.REPLACE) {
            if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
            if (runtimePlaceOrderState.instrument(runtimeOrderSymbol(order)) == null) {
                throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
            }
            return pending.decodedCommand().replaceOrder().replacement();
        }
        return replacementForAmend(pending.decodedCommand().amendOrder(), order);
    }

    private PlaceOrderCommand replacementForAmend(AmendOrderCommand command, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String symbol = runtimeOrderSymbol(order);
        var instrument = runtimePlaceOrderState.instrument(symbol);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), symbol, order.instrumentVersion(),
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
        if (fatalFailure != null) return null;
        transferMatchingCompletion(sequence);
        if (pendingMatching.isEmpty() || pendingMatching.firstSequence() != sequence) return null;
        LaneCommandContextRing.Context context = laneCommandContexts.required(sequence);
        return context.settlementDispatched()
                ? context.matchingResult() : context.takeMatchingCompletion();
    }

    boolean establishMatchingCommitFence(long sequence, long clusterTimestamp, long clusterPosition) {
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null) return false;
        if (firstPendingMatchingSequence() != sequence) return false;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        return true;
    }

    com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingResult(long sequence) {
        return awaitMatchingResult(sequence, MATCHING_AWAIT_TIMEOUT_NANOS);
    }

    com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingResult(
            long sequence, long timeoutNanos) {
        if (fatalFailure != null) return null;
        if (timeoutNanos <= 0) return null;
        long deadline = System.nanoTime() + timeoutNanos;
        com.surprising.aeron.service.matching.CoreMatchingResult result = takeMatchingResult(sequence);
        if (result != null) return result;
        if (pendingMatching.isEmpty() || pendingMatching.firstSequence() != sequence) return null;
        for (int spin = 0; spin < MATCHING_COMPLETION_SPINS; spin++) {
            result = takeMatchingResult(sequence);
            if (result != null) return result;
            Thread.onSpinWait();
        }
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) return null;
            matchingCompletions.awaitSequence(sequence, remainingNanos);
            result = takeMatchingResult(sequence);
            if (result != null) return result;
            if (pendingMatching.isEmpty() || pendingMatching.firstSequence() != sequence) return null;
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("matching wait was interrupted");
            }
        }
    }

    boolean hasPendingMatchingRejection(long sequence) {
        return pendingMatchingRejections.containsKey(sequence);
    }

    CoreResponse completeRejectedMatching(long sequence) {
        runtime.assertOwner();
        PendingMatching pending = pendingMatching.get(sequence);
        CoreResultCode resultCode = pendingMatchingRejections.get(sequence);
        if (pending == null || resultCode == null || firstPendingMatchingSequence() != sequence) return null;
        CoreAdmissionReservation capacityReservation = pending.capacityReservation();
        if (capacityReservation == null) {
            throw new IllegalStateException("rejected matching admission reservation is missing");
        }
        activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
        commitMatchingSequence(sequence);
        long requiredExportSequence = appendRejectedCoreFact(
                pending.command(), pending.fingerprint(), resultCode, sequence);
        long stateHash = stateHash(cachedBusinessStateHash, pending.command().header().commandId(),
                ResponseStatus.REJECTED, resultCode, sequence);
        storeResult(pending.command().header().commandId(), new StoredResult(pending.fingerprint(),
                ResponseStatus.REJECTED, resultCode, sequence, requiredExportSequence, stateHash,
                new byte[0], 0));
        laneCommandContexts.discard(sequence);
        removePendingMatching(sequence);
        CoreResponse response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                sequence, requiredExportSequence, stateHash, new byte[0]);
        return releaseAdmission(capacityReservation, response);
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        runtime.assertOwner();
        return runtime.matcherReady().thenCompose(ignored -> matchingAdapter.orderBooksStateHashAsync());
    }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        ensureRuntimePlaceOrderState();
        assertHealthy();
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null || matchingResult == null) return null;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        clusterTimestamp = pending.commitFenceTimestamp();
        clusterPosition = pending.commitFenceClusterPosition();
        currentClusterPosition = clusterPosition;
        LaneCommandContextRing.Context laneContext = laneCommandContexts.required(sequence);
        boolean resumingSettlement = laneContext.settlementDispatched();
        if (resumingSettlement) {
            matchingResult = laneContext.matchingResult();
            if (!laneContext.settlementReady()) return null;
            Throwable failure = laneContext.settlementFailure();
            if (failure != null) {
                matchingAdapter.poisonFromOwner("account lane calculation failed sequence=" + sequence);
                throw failMatching(pending, "account lane calculation failed", failure);
            }
        }
        if (!resumingSettlement && MATCHING_PHASE_METRICS_ENABLED) {
            Long submitNanos = matchingSubmitNanos.remove(sequence);
            if (submitNanos != null) matchingPhaseMetrics.recordExchange(System.nanoTime() - submitNanos);
        }
        long applyStartNanos = System.nanoTime();
        if (!resumingSettlement && matchingResultNeedsRecovery(pending, matchingResult)) {
            OrderBatchPending failedBatch = pendingOrderBatches.get(sequence);
            Throwable failure = failedBatch == null ? null : failedBatch.pipelinedMatchingFailure;
            String detail = "matcher continuation returned " + matchingResult.resultCode()
                    + (failure == null || failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (failedBatch != null) throw failOrderBatch(failedBatch, pending, detail, failure);
            matchingAdapter.poisonFromOwner("unreconciled matcher outcome sequence=" + sequence
                    + " result=" + matchingResult.resultCode());
            throw failMatching(pending, detail, failure);
        }
        int matcherShardId = matchingResult.nativeCommand().matcherShardId();
        long matcherSequenceBefore = resumingSettlement
                ? matchingResult.nativeCommand().matcherSequence() - 1 : matcherSequence(matcherShardId);
        long matcherPrefixBefore = resumingSettlement
                ? matchingResult.matcherPrefix().before() : matcherPrefixDigest(matcherShardId);
        var matchingRuntimeCheckpoint = runtimePlaceOrderState.commandCheckpoint();
        long matchingPositionIdentityCheckpoint = runtimePlaceOrderIdentities.positionCheckpoint();
        if (!resumingSettlement && !BENCHMARK_SKIP_MATCHING_SUBMIT) {
            validateMatchingEvidence(pending, matchingResult);
            applyMatcherProgress(matchingResult);
        }
        if (pendingOrderBatches.containsKey(sequence)) {
            return completeOrderBatchMatching(sequence, matchingResult, clusterTimestamp, clusterPosition);
        }
        com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan =
                resumingSettlement ? laneContext.settlementPlan()
                        : initialMatcherSettlementPlan(pending, matchingResult);
        if (!resumingSettlement && settlementPlan != null) {
            laneContext.result(matchingResult, settlementPlan, settlementPlan.requiredLaneMask(),
                    validAccountLaneMask());
        } else if (!resumingSettlement && !(matchingResult.accepted()
                && (pending.operation() == PendingMatching.Operation.REPLACE
                || pending.operation() == PendingMatching.Operation.AMEND))) {
            laneContext.result(matchingResult, expectedLaneMask(pending, matchingResult), validAccountLaneMask());
        }
        if (!resumingSettlement && matchingResult.accepted() && productLine.isDerivative()
                && settlementPlan != null
                && settlementPlan.tradeEvents().size() >= PARALLEL_SETTLEMENT_MIN_TRADES
                && Long.bitCount(settlementPlan.requiredLaneMask()) > 1
                && (pending.operation() == PendingMatching.Operation.PLACE
                || pending.operation() == PendingMatching.Operation.TRIGGER)) {
            laneContext.dispatch(runtimePlaceOrderState.dispatchPerpetualSettlement(
                    settlementPlan, runtimePlaceOrderIdentities));
            return null;
        }
        RuntimeProjectionPoint beforeProjection = pending.beforeProjection();
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
        commandFundsDelta = pending.fundsDelta();
        CoreAdmissionReservation capacityReservation = pending.capacityReservation();
        if (capacityReservation == null) {
            throw new IllegalStateException("matching admission reservation is missing");
        }
        activateFactContext(capacityReservation, pending.command(), pending.fingerprint());
        beginSnapshotProjectionBatch();
        if (!BENCHMARK_SKIP_MATCHING_SUBMIT) {
            commandMatcherTransition = new com.surprising.aeron.protocol.CoreMatcherTransition(
                    matchingAdapter.topology().routeVersion(), matcherShardId,
                    matcherSequenceBefore, matchingResult.nativeCommand().matcherSequence(),
                    matcherPrefixBefore, matchingResult.matcherPrefix().after());
        }
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        com.surprising.aeron.service.state.RuntimeTreasuryDelta[] laneTreasuryDeltas = null;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    commandChangedUserIds = boxedLongs(settlementPlan.userIds());
                    commandChangedOrderIds = boxedLongsIncluding(settlementPlan.orderIds(), command.orderId());
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                settlementPlan, sequence, matchingResult, laneContext);
                    } else {
                        rejectPlaceOrderRuntime(pending.command().header().userId(), command.orderId(), sequence);
                    }
                    commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                            settlementPlan.tradeEvents());
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    commandChangedUserIds = List.of(pending.command().header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    if (matchingResult.accepted()) {
                        cancelOrderRuntime(pending.command().header().userId(), command.orderId());
                    }
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = requireMatchingAdmission(pending);
                    requireUnchangedAdmissionState(admission);
                    var command = admission.command();
                    long originalOrderId = admission.originalOrderId();
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        cancelOrderRuntime(pending.command().header().userId(), originalOrderId);
                        reservePlaceOrderRuntime(admission, pending.command().header().commandId(),
                                pending.sequence());
                        settlementPlan = com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                                sequence, command.orderId(), admission.userId(),
                                new long[]{originalOrderId, command.orderId()}, matchingResult,
                                runtimePlaceOrderState, runtimePlaceOrderIdentities);
                        laneContext.result(matchingResult, settlementPlan, settlementPlan.requiredLaneMask(),
                                validAccountLaneMask());
                        commandChangedUserIds = boxedLongs(settlementPlan.userIds());
                        commandChangedOrderIds = boxedLongs(settlementPlan.orderIds());
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                settlementPlan, sequence, matchingResult, laneContext);
                        commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                                settlementPlan.tradeEvents());
                    } else {
                        commandChangedUserIds = boxedLongs(settlementPlan.userIds());
                        commandChangedOrderIds = boxedLongs(settlementPlan.orderIds());
                    }
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    var command = triggerPlacement(trigger, execute[2]);
                    commandChangedUserIds = boxedLongs(settlementPlan.userIds());
                    commandChangedOrderIds = boxedLongs(settlementPlan.orderIds());
                    applyPreMatchingCancellations(pending, matchingResult);
                    if (matchingResult.accepted()) {
                        laneTreasuryDeltas = applyMatchesOnAccountLanes(
                                settlementPlan, sequence, matchingResult, laneContext);
                    } else {
                        rejectPlaceOrderRuntime(trigger.userId(), command.orderId(), sequence);
                    }
                    completeTriggerOrderRuntime(trigger.triggerOrderId(), matchingResult.accepted(),
                            matchingResult.accepted() ? command.orderId() : 0,
                            matchingResult.accepted() ? "" : matchingResult.resultCode(), execute[3]);
                    commandExecutions = executionViews(command.orderId(), trigger.userId(),
                            settlementPlan.tradeEvents());
                    commandOrderViews = commandChangedOrderIds.stream().map(this::runtimeOrder)
                            .filter(java.util.Objects::nonNull).map(this::orderView).toList();
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
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
                    var command = pending.decodedCommand().liquidationBatch();
                    applyLiquidationBatch(command, matchingResult);
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    if (matchingResult.accepted()) {
                        applySettlementChangedIds(command);
                        settleInstrumentRuntime(command, pending.command().header().commandId());
                    }
                }
            }
            runtimePlaceOrderState.completePendingReservations(pending.sequence());
            if (pending.operation() == PendingMatching.Operation.PLACE
                    || pending.operation() == PendingMatching.Operation.REPLACE
                    || pending.operation() == PendingMatching.Operation.AMEND
                    || pending.operation() == PendingMatching.Operation.TRIGGER) {
                refreshSnapshotProjection();
            }
        } catch (CoreAdmissionReservation.FactEstimateInvariantException exception) {
            throw failObservedMatchingEstimateInvariant(pending, exception, matchingRuntimeCheckpoint,
                    matchingPositionIdentityCheckpoint);
        } catch (CoreStateRejectedException exception) {
            throw failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw failMatching(pending, "Core and matcher state diverged", exception);
        }
        try {
            RuntimeTreasuryDelta settledTreasuryDelta = laneTreasuryDeltas == null
                    ? null : mergeTreasuryDeltas(laneTreasuryDeltas);
            if (settledTreasuryDelta != null) {
                settledTreasuryDelta.apply(runtimePlaceOrderState.treasury());
                runtimePlaceOrderState.setMetadata(productLine,
                        Math.incrementExact(runtimePlaceOrderState.revision()));
                refreshSnapshotProjection();
            }
            if (snapshotProjectionDirty) {
                stampOrderChangesRuntime(clusterTimestamp, clusterPosition, commandChangedOrderIds);
            }
        } catch (CoreAdmissionReservation.FactEstimateInvariantException exception) {
            throw failObservedMatchingEstimateInvariant(pending, exception, matchingRuntimeCheckpoint,
                    matchingPositionIdentityCheckpoint);
        }
        try {
            materializeChangeAccumulators();
            var laneApply = applyAndCommitLaneSequence(pending.sequence(), changedUserIds.toPrimitiveArray(),
                    laneContext.matchingResult(), rollingBusinessStateHash.value(), rollingFundsStateHash.value(),
                    laneContext);
            if (laneContext.completedLaneMask() != laneContext.expectedLaneMask()) {
                throw failMatching(pending, "account lane mask differs from immutable matcher result", null);
            }
            requireCompleteAccountLanes(laneContext);
            completeSnapshotProjectionBatch(laneApply);
            if (!commandChangedOrderIds.isEmpty()) {
                materializeCommandOrderViews();
            }
            commandDelta = commandDelta();
            validateFundsConservation(pending.command());
        } catch (CoreAdmissionReservation.FactEstimateInvariantException exception) {
            throw failObservedMatchingEstimateInvariant(pending, exception, matchingRuntimeCheckpoint,
                    matchingPositionIdentityCheckpoint);
        }
        if (MATCHING_PHASE_METRICS_ENABLED) {
            matchingPhaseMetrics.recordApply(System.nanoTime() - applyStartNanos);
            completedMatchingCount++;
            if (completedMatchingCount % MATCHING_PHASE_LOG_INTERVAL == 0) {
                LOG.log(System.Logger.Level.DEBUG, "matching phases count=" + completedMatchingCount + " "
                        + matchingPhaseMetrics.reportAndReset());
            }
        }
        long businessStateHash = currentProjectionPoint == beforeProjection
                ? cachedBusinessStateHash : currentBusinessStateHash();
        long applied = sequence;
        commitMatchingSequence(sequence);
        long requiredExportSequence = appendCoreFact(pending.command(), pending.fingerprint(), status, resultCode,
                applied,
                businessStateHash, pending.beforeProjection(), currentProjectionPoint,
                commandDelta, commandMatcherTransition);
        cachedBusinessStateHash = businessStateHash;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(), status, resultCode, applied);
        byte[] responseData = commandResultData(pending, matchingResult);
        storeResult(pending.command().header().commandId(), StoredResult.owned(pending.fingerprint(),
                status, resultCode, applied, requiredExportSequence, stateHash, responseData));
        laneCommandContexts.release(sequence);
        removePendingMatching(sequence);
        if (!deferredMatching.isEmpty() || !pendingOrderBatches.isEmpty()) {
            submitDeferredMatchingAfterBatch();
        }
        CoreResponse response = new CoreResponse(
                status, status, resultCode, applied, requiredExportSequence, stateHash, responseData);
        return releaseAdmission(capacityReservation, response);
    }

    private static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        if (message.header().kind() == WireMessageKind.COMMAND) {
            return isMatchingCommand(message.header().messageType())
                    || message.header().messageType() == CoreMessageType.ACK_EXPORT;
        }
        return isCommittedExportQuery(message);
    }

    private CoreResponse applyExportAckControl(CoreMessage message, CommandFingerprint fingerprint,
                                               SourceKey sourceKey) {
        ResponseStatus status = ResponseStatus.APPLIED;
        CoreResultCode resultCode = CoreResultCode.NONE;
        try {
            exportState.acknowledge(CoreExportCodec.decodeAck(message.payloadUnsafe()));
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        refreshCommittedCoreSequence();
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = status == ResponseStatus.APPLIED
                ? CoreExportCodec.encodeStatus(exportState.status()) : new byte[0];
        storeResult(message.header().commandId(), new StoredResult(fingerprint, status, resultCode,
                appliedCommandCount, 0, stateHash, responseData, 0));
        return new CoreResponse(status, status, resultCode, appliedCommandCount, 0, stateHash, responseData);
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
            OrderRuntime order = runtimeOrder(cancellation.orderId());
            if (order != null) mask |= matchingAdapter.topology().accountLaneMask(order.userId());
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            for (var action : TradingCommandCodec.decodeExecuteLiquidationBatch(
                    pending.command().payloadUnsafe()).actions()) {
                mask |= matchingAdapter.topology().accountLaneMask(action.userId());
            }
        } else if (pending.operation() == PendingMatching.Operation.LIQUIDATION) {
            var command = pending.decodedCommand().liquidation();
            var liquidation = runtimePlaceOrderState.liquidation(command.liquidationId());
            if (liquidation != null) mask |= matchingAdapter.topology().accountLaneMask(liquidation.userId());
        } else if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            String symbol = pending.decodedCommand().settlement().symbol();
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

    private com.surprising.aeron.service.state.MatcherSettlementPlan initialMatcherSettlementPlan(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        long userId = pending.command().header().userId();
        return switch (pending.operation()) {
            case PLACE -> {
                long orderId = pending.decodedCommand().placeOrder().orderId();
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                        pending.sequence(), orderId, userId, new long[]{orderId}, result,
                        runtimePlaceOrderState, runtimePlaceOrderIdentities);
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = requireMatchingAdmission(pending);
                if (result.accepted()) yield null;
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.empty(
                        pending.sequence(), userId,
                        new long[]{admission.originalOrderId(), admission.command().orderId()},
                        runtimePlaceOrderState);
            }
            case TRIGGER -> {
                long[] execute = pending.decodedCommand().trigger();
                var trigger = runtimePlaceOrderState.triggerOrder(execute[0]);
                if (trigger == null) yield null;
                long orderId = triggerPlacement(trigger, execute[2]).orderId();
                yield com.surprising.aeron.service.state.MatcherSettlementPlan.build(
                        pending.sequence(), orderId, trigger.userId(), new long[]{orderId}, result,
                        runtimePlaceOrderState, runtimePlaceOrderIdentities);
            }
            default -> null;
        };
    }

    private RuntimeTreasuryDelta mergeTreasuryDeltas(RuntimeTreasuryDelta[] laneDeltas) {
        if (laneDeltas == null) throw new IllegalArgumentException("account lane Treasury deltas are required");
        RuntimeTreasuryDelta aggregate = mergedLaneTreasuryDelta;
        aggregate.clear();
        for (RuntimeTreasuryDelta delta : laneDeltas) {
            if (delta != null) aggregate.merge(delta);
        }
        return aggregate;
    }

    private void requireCompleteAccountLanes(LaneCommandContextRing.Context context) {
        if (!context.complete()) throw new IllegalStateException("account lane ACK barrier is incomplete");
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
        var batch = pending.decodedCommand().liquidationBatch();
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
        var command = pending.decodedCommand().liquidation();
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
        var command = pending.decodedCommand().settlement();
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
        if (result.outcome() == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.APPLIED
                || result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.REJECTED_UNCHANGED) {
            return false;
        }
        if (result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.KNOWN_PREFIX_APPLIED) {
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
        return result.outcome()
                == com.surprising.aeron.service.matching.CoreMatchingResult.Outcome.FATAL_DIVERGENCE;
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
            OrderRuntime order = runtimeOrder(cancellation.orderId());
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
        int matcherShardId = nativeCommand.matcherShardId();
        long appliedMatcherSequence = matcherSequence(matcherShardId);
        long appliedMatcherPrefixDigest = matcherPrefixDigest(matcherShardId);
        if (nativeCommand.coreSequence() != pending.sequence()
                || !nativeCommand.matches(pending.command().header().commandId())
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

    private void initializeMatcherProgress(MatcherSnapshot snapshot) {
        long initialDigest = com.surprising.aeron.service.matching.CoreMatchingResult.MatcherPrefix.initialDigest();
        java.util.Arrays.fill(appliedMatcherPrefixDigests, initialDigest);
        if (snapshot == null) return;
        for (com.surprising.aeron.service.matching.MatcherShardProgress progress
                : snapshot.matcherShardProgress()) {
            int index = matcherProgressIndex(progress.matcherShardId());
            appliedMatcherSequences[index] = progress.matcherSequence();
            appliedMatcherPrefixDigests[index] = progress.prefixDigest();
        }
    }

    private void applyMatcherProgress(
            com.surprising.aeron.service.matching.CoreMatchingResult result) {
        int index = matcherProgressIndex(result.nativeCommand().matcherShardId());
        appliedMatcherSequences[index] = result.nativeCommand().matcherSequence();
        appliedMatcherPrefixDigests[index] = result.matcherPrefix().after();
    }

    private long matcherSequence(int matcherShardId) {
        return appliedMatcherSequences[matcherProgressIndex(matcherShardId)];
    }

    private long matcherPrefixDigest(int matcherShardId) {
        return appliedMatcherPrefixDigests[matcherProgressIndex(matcherShardId)];
    }

    private int matcherProgressIndex(int matcherShardId) {
        int index = matcherShardId + 1;
        if (index < 0 || index >= appliedMatcherSequences.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return index;
    }

    boolean isMatchingPending(UUID commandId) {
        return pendingMatching.findByCommandId(commandId) != null;
    }

    long matchingSequence(UUID commandId) {
        PendingMatching pending = pendingMatching.findByCommandId(commandId);
        return pending == null ? 0 : pending.sequence();
    }

    void drainMatchingCompletions() {
        checkMatchingCompletionOverflow();
        long sequence = firstPendingMatchingSequence();
        if (sequence != 0) transferMatchingCompletion(sequence);
    }

    private void transferMatchingCompletion(long sequence) {
        checkMatchingCompletionOverflow();
        if (sequence <= 0 || !pendingMatching.contains(sequence) || !laneCommandContexts.claimed(sequence)) {
            return;
        }
        com.surprising.aeron.service.matching.CoreMatchingResult completion = matchingCompletions.poll(sequence);
        if (completion != null) laneCommandContexts.required(sequence).publishMatchingCompletion(completion);
    }

    private void checkMatchingCompletionOverflow() {
        if (matchingCompletions.consumeOverflow()) {
            fatalFailure = new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                    "matching completion queue", firstPendingMatchingSequence(), 0,
                    "matching completion queue is full");
            throw fatalFailure;
        }
    }

    int commitReadyMatching(int maxCompletions, long clusterTimestamp, long clusterPosition,
                            boolean awaitFirst, MatchingCommitHandler handler) {
        runtime.assertOwner();
        if (maxCompletions <= 0 || handler == null) {
            throw new IllegalArgumentException("matching commit batch requires a positive limit and handler");
        }
        beginDownstreamPublicationBatch();
        try {
            drainMatchingCompletions();
            int completed = 0;
            int attempts = 0;
            while (attempts < maxCompletions) {
                long sequence = firstPendingMatchingSequence();
                if (sequence == 0) break;
                PendingMatching pending = pendingMatching.get(sequence);
                if (pending == null) throw new IllegalStateException("matching commit cursor is missing");
                pending.establishCommitFence(clusterTimestamp, clusterPosition);
                CoreResponse response;
                if (hasPendingMatchingRejection(sequence)) {
                    response = completeRejectedMatching(sequence);
                } else {
                    com.surprising.aeron.service.matching.CoreMatchingResult matching = attempts == 0 && awaitFirst
                            ? awaitMatchingResult(sequence) : takeMatchingResult(sequence);
                    if (matching == null) break;
                    response = completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
                }
                attempts++;
                if (response == null) break;
                handler.onCommitted(sequence, response);
                completed++;
            }
            return completed;
        } finally {
            endDownstreamPublicationBatch();
        }
    }

    private void beginDownstreamPublicationBatch() {
        runtimeProjectionJournal.beginPublicationBatch();
        try {
            exportState.beginMaterializationBatch();
        } catch (RuntimeException failure) {
            runtimeProjectionJournal.endPublicationBatch();
            throw failure;
        }
    }

    private void endDownstreamPublicationBatch() {
        try {
            exportState.endMaterializationBatch();
        } finally {
            runtimeProjectionJournal.endPublicationBatch();
        }
    }

    int matchingCompletionHighWaterMark() {
        return matchingCompletions.highWaterMark();
    }

    int matchingCompletionCapacity() {
        return matchingCompletions.capacity();
    }

    @FunctionalInterface
    interface MatchingCommitHandler {
        void onCommitted(long sequence, CoreResponse response);
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
        return pendingMatching.firstSequence();
    }

    boolean hasPendingMatchingForUser(long userId) {
        return pendingMatching.hasUser(userId);
    }


    Map<Long, PendingMatching> pendingMatching() {
        return pendingMatching.snapshot();
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
        if (activated) throw new IllegalStateException("account lanes must restore before activation");
        runtimePlaceOrderState.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed core state");
        try {
            runtime.activate();
            runtimeProjectionJournal.activate();
            exportState.activate();
            activated = true;
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    boolean activated() { return activated; }

    RestoreActivationState restoreActivationState() {
        return new RestoreActivationState(activated, runtime.activated(), matchingAdapter.activated(),
                runtimeProjectionJournal.activated(), exportState.activated());
    }

    record RestoreActivationState(boolean core, boolean runtime, boolean matcher,
                                  boolean projector, boolean exportMaterializer) {
        boolean allPassive() {
            return !core && !runtime && !matcher && !projector && !exportMaterializer;
        }

        boolean allActivated() {
            return core && runtime && matcher && projector && exportMaterializer;
        }
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
        return snapshot(Math.max(Math.addExact(lastSnapshotId, 1),
                Math.max(1, Math.addExact(appliedCommandCount, 1))));
    }

    public byte[] snapshot(long snapshotId) {
        if (!activated) activate();
        assertHealthy();
        long deadlineNanos = Math.addExact(System.nanoTime(), java.util.concurrent.TimeUnit.SECONDS.toNanos(
                STANDALONE_SNAPSHOT_TIMEOUT_SECONDS));
        beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = pollSnapshotSections(0, 0, System.nanoTime());
            if (snapshot != null) return snapshot.toByteArray();
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    void beginSnapshot(long snapshotId, long deadlineNanos) {
        if (!activated) activate();
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
            if (fence.encodedSnapshot != null) {
                if (!fence.encodedSnapshot.isDone()) return null;
                SectionedCoreSnapshotCodec.SectionedSnapshot encoded = fence.encodedSnapshot.join();
                lastSnapshotId = Math.max(lastSnapshotId, fence.snapshotId);
                snapshotFence = null;
                return encoded;
            }
            drainMatchingCompletions();
            while (!pendingMatching.isEmpty()) {
                long sequence = firstPendingMatchingSequence();
                com.surprising.aeron.service.matching.CoreMatchingResult result =
                        laneCommandContexts.required(sequence).takeMatchingCompletion();
                if (result == null) return null;
                if (completeMatching(sequence, result, clusterTimestamp, clusterPosition) == null) return null;
                drainMatchingCompletions();
            }
            if (laneCommandContexts.inFlight() != 0 || matchingCompletions.depth() != 0) {
                throw new IllegalStateException("snapshot fence contains unfinished lane or matcher work");
            }
            if (currentAdmission != null || !factPatchChains.isEmpty()
                    || runtimeProjectionJournal.hasOutstandingReservation()
                    || snapshotProjectionDeferred || snapshotProjectionDirty) {
                throw new IllegalStateException("snapshot fence contains outstanding admission or patch work");
            }
            runtimePlaceOrderState.requireSnapshotFenceReady();
            if (fence.projectionSequence < 0) {
                fence.projectionSequence = runtimeProjectionJournal.publishedSequence();
                runtimeProjectionJournal.requestProjection(fence.projectionSequence);
            }
            if (runtimeProjectionJournal.projectedSequence() < fence.projectionSequence) return null;
            if (fence.projection == null) {
                var projection = runtimeProjectionJournal.await(
                        fence.projectionSequence, fence.deadlineNanos, false);
                if (projection.businessStateHash() != currentBusinessStateHash()
                        || projection.fundsStateHash() != rollingFundsStateHash.value()) {
                    throw new IllegalStateException("snapshot projection fence hash mismatch");
                }
                snapshotState = projection.state();
                fence.projection = projection;
            }
            var projection = fence.projection;
            if (fence.matcherSnapshot == null) {
                fence.coreSequence = appliedCommandCount;
                CompletableFuture<MatcherSnapshot> matcherSnapshot = matcherSnapshotCapture.capture(
                        fence.snapshotId, fence.coreSequence, projection.businessStateHash(),
                        snapshotState, activeOrderIndex.orders());
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
            if (exportState.materializedThroughSequence() < Math.subtractExact(exportState.nextSequence(), 1)) {
                return null;
            }
            CoreSnapshotImage image = SectionedCoreSnapshotCodec.capture(this, matcherSnapshot, fence.snapshotId,
                    fence.coreSequence, clusterTimestamp, clusterPosition);
            fence.encodedSnapshot = CompletableFuture.runAsync(image::verifyFullState, snapshotAuditExecutor)
                    .thenCompose(ignored -> {
                        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encoded =
                                snapshotEncoder.encode(image);
                        if (encoded == null) {
                            throw new IllegalStateException("snapshot encoder returned no completion");
                        }
                        return encoded;
                    });
            if (fence.encodedSnapshot == null) {
                throw new IllegalStateException("snapshot encoder returned no completion");
            }
            fence.encodedSnapshot.whenComplete((ignored, failure) -> {
                if (failure == null) return;
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        ? failure.getCause() : failure;
                RuntimeException auditFailure = cause instanceof RuntimeException runtimeFailure
                        ? runtimeFailure : new IllegalStateException("snapshot audit failed", cause);
                snapshotAuditFailure.compareAndSet(null, auditFailure);
            });
            return null;
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
        if (snapshotFence != null && snapshotFence.encodedSnapshot == null) releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        if (snapshotFence != null && snapshotFence.encodedSnapshot == null) releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    private void releaseSnapshotFence() {
        if (snapshotFence != null && snapshotFence.encodedSnapshot != null) {
            snapshotFence.encodedSnapshot.cancel(true);
        }
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
        private long projectionSequence = -1;
        private com.surprising.aeron.service.state.RuntimeCommitJournal.ProjectionVersion projection;
        private CompletableFuture<MatcherSnapshot> matcherSnapshot;
        private CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encodedSnapshot;

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
                long businessStateHash,
                TradingCoreState state,
                Iterable<CoreOrderState> activeOrders);
    }

    @FunctionalInterface
    interface SnapshotEncoder {
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encode(CoreSnapshotImage image);
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

    private CoreResponse rejectDirectFactEstimateInvariant(
            CoreAdmissionReservation.FactEstimateInvariantException failure,
            com.surprising.aeron.service.state.TradingRuntimeState.CommandCheckpoint runtimeCheckpoint,
            long positionIdentityCheckpoint,
            long commandSequence,
            CoreAdmissionReservation admission) {
        abortSnapshotProjectionBatch();
        try {
            runtimePlaceOrderState.rollbackActiveCommand(runtimeCheckpoint, commandSequence);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            runtimePlaceOrderIdentities.rollbackPositionKeys(positionIdentityCheckpoint);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            if (laneCommandContexts.claimed(commandSequence)) {
                laneCommandContexts.discard(commandSequence);
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        queuedMatching.clear();
        if (failure.getSuppressed().length != 0) {
            commitPublicationFailure = new IllegalStateException(
                    "Core Fact estimate invariant rollback failed", failure);
            throw commitPublicationFailure;
        }
        return releaseAdmission(admission, admissionRejected(CoreResultCode.INVALID_COMMAND));
    }

    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException
            failObservedMatchingEstimateInvariant(
                    PendingMatching pending,
                    CoreAdmissionReservation.FactEstimateInvariantException failure,
                    com.surprising.aeron.service.state.TradingRuntimeState.CommandCheckpoint runtimeCheckpoint,
                    long positionIdentityCheckpoint) {
        abortSnapshotProjectionBatch();
        try {
            runtimePlaceOrderState.rollbackActiveCommand(runtimeCheckpoint, pending.sequence());
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            runtimePlaceOrderIdentities.rollbackPositionKeys(positionIdentityCheckpoint);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            matchingAdapter.poisonFromOwner("Core Fact estimate invariant after observed matcher fact sequence="
                    + pending.sequence());
            if (laneCommandContexts.claimed(pending.sequence())) {
                laneCommandContexts.required(pending.sequence()).resetMatchingContinuation();
            }
        } catch (RuntimeException poisonFailure) {
            failure.addSuppressed(poisonFailure);
        }
        return failMatching(pending, "Core Fact estimate invariant failed after matcher fact", failure);
    }

    private void assertHealthy() {
        if (commitPublicationFailure != null) throw commitPublicationFailure;
        runtimeProjectionJournal.current();
        exportState.assertHealthy();
        RuntimeException auditFailure = snapshotAuditFailure.get();
        if (auditFailure != null) throw auditFailure;
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

    long committedBusinessHashCoreSequence() {
        return rollingBusinessStateHash.coreSequence();
    }

    long committedFundsHashCoreSequence() {
        return rollingFundsStateHash.coreSequence();
    }

    long committedProjectionSequence() {
        return runtimeProjectionJournal.publishedSequence();
    }

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        snapshotState = runtimeProjectionJournal.await(currentProjectionPoint);
        return snapshotState;
    }

    TradingCoreState snapshotTradingState() {
        if (snapshotState == null) throw new IllegalStateException("snapshot projection is unavailable");
        return snapshotState;
    }

    long snapshotBusinessStateHash() {
        return currentBusinessStateHash();
    }

    long snapshotFundsStateHash() {
        return rollingFundsStateHash.value();
    }

    long snapshotProjectionSequence() {
        return runtimeProjectionJournal.publishedSequence();
    }

    long snapshotProjectionFreezeCount() {
        return runtimeProjectionJournal.projectionFreezeCount();
    }

    boolean hasProjectionAdmissionCapacity(int additionalEntries) {
        return !runtimeProjectionJournal.activated()
                || runtimeProjectionJournal.hasCapacityFor(additionalEntries);
    }

    boolean runtimeRiskScanComplete() {
        return runtimePlaceOrderState.firstIncompleteRiskScan() == null;
    }

    boolean runtimeRiskScanComplete(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
        return scan == null || scan.complete();
    }

    RiskScanRuntime runtimeRiskScan(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        return symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
    }

    MarkPriceRuntime runtimeMarkPrice(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        return symbolId == null ? null : runtimePlaceOrderState.markPrice(symbolId);
    }

    long runtimeFundingSettlement(String symbol) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        return symbolId == null ? 0 : runtimePlaceOrderState.treasury().fundingSettlement(symbolId);
    }

    long runtimeInsurance(String asset) {
        Integer assetId = runtimePlaceOrderIdentities.findAssetId(asset);
        return assetId == null ? 0 : runtimePlaceOrderState.treasury().insurance(assetId);
    }

    LiquidationRuntime runtimeLiquidation(long liquidationId) {
        return runtimePlaceOrderState.liquidation(liquidationId);
    }

    PositionRuntime runtimePosition(long userId, String positionKey) {
        Long key = runtimePlaceOrderIdentities.findPositionKey(userId, positionKey);
        return key == null ? null : runtimePlaceOrderState.position(key);
    }

    boolean snapshotHasOutstandingReservation() {
        return currentAdmission != null || !factPatchChains.isEmpty()
                || runtimeProjectionJournal.hasOutstandingReservation();
    }

    void captureCommittedPatchesForTest() {
        if (capturedCommitPatches == null) capturedCommitPatches = new ArrayList<>();
        else capturedCommitPatches.clear();
    }

    void failOrderBatchLaneMaskPreflightForTest(long expectedLaneMask) {
        if ((expectedLaneMask & ~validAccountLaneMask()) != 0) {
            throw new IllegalArgumentException("test lane mask is outside configured topology");
        }
        orderBatchExpectedLaneMaskForTest = expectedLaneMask;
    }

    void failOrderBatchAfterItemForTest(Runnable fault) {
        orderBatchAfterItemFaultForTest = fault;
    }

    private void runOrderBatchAfterItemFaultForTest() {
        Runnable fault = orderBatchAfterItemFaultForTest;
        if (fault == null) return;
        orderBatchAfterItemFaultForTest = null;
        fault.run();
    }

    List<com.surprising.aeron.service.state.RuntimeCommitPatch> capturedCommitPatchesForTest() {
        return capturedCommitPatches == null ? List.of() : List.copyOf(capturedCommitPatches);
    }

    List<com.surprising.aeron.service.state.RuntimeCommitPatch> drainCapturedCommitPatchesForTest() {
        List<com.surprising.aeron.service.state.RuntimeCommitPatch> captured = capturedCommitPatchesForTest();
        if (capturedCommitPatches != null) capturedCommitPatches.clear();
        return captured;
    }

    Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> feePolicies() {
        return runtimePlaceOrderState.feePoliciesSnapshot();
    }

    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> policies) {
        long beforeBusinessStateHash = cachedBusinessStateHash;
        runtimePlaceOrderState.restoreFeePolicies(policies);
        cachedFeePolicyHash = computeFeePolicyHash(policies);
        cachedBusinessStateHash = currentBusinessStateHash();
        runtime.rebaseInitialBusinessStateHash(beforeBusinessStateHash, cachedBusinessStateHash);
        runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, cachedBusinessStateHash);
    }

    Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers() {
        return runtimePlaceOrderState.pendingTransfersSnapshot();
    }

    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        long beforeBusinessStateHash = cachedBusinessStateHash;
        runtimePlaceOrderState.restorePendingTransfers(transfers);
        cachedTransferHash = computeTransferHash(transfers);
        cachedBusinessStateHash = currentBusinessStateHash();
        runtime.rebaseInitialBusinessStateHash(beforeBusinessStateHash, cachedBusinessStateHash);
        runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, cachedBusinessStateHash);
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
                initializeTriggerScan(command);
                refreshSnapshotProjection();
                logRiskScan("mark-price", command.symbol(), runtimePlaceOrderState.riskScanControl().scanBatchSize(),
                        pendingBefore, startedAt);
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
                var activeRiskScan = runtimePlaceOrderState.firstRiskIncompleteScan();
                var activeScan = activeRiskScan == null
                        ? runtimePlaceOrderState.firstIncompleteRiskScan() : activeRiskScan;
                if (activeScan == null) break;
                String symbol = runtimePlaceOrderIdentities.symbol(activeScan.symbolId());
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                long beforeRevision = runtimePlaceOrderState.revision();
                int completedRiskWork = 0;
                if (!activeScan.riskComplete()) {
                    completedRiskWork = RuntimePerpetualRiskProcessor.applyContinuationRuntime(command.maxUsers(),
                            activeScan.symbolId(), positionUserIndex.users(symbol),
                            runtimePlaceOrderState, runtimePlaceOrderIdentities);
                }
                if (runtimePlaceOrderState.revision() != beforeRevision) {
                    refreshSnapshotProjection();
                }
                int remainingWork = command.maxUsers() - completedRiskWork;
                if (remainingWork > 0 && runtimePlaceOrderState.riskScan(activeScan.symbolId()).riskComplete()) {
                    evaluatePendingTriggerScan(symbol, remainingWork);
                }
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
                TerminalPruneBatch pruneBatch = terminalRetention.eligible(runtimePlaceOrderState,
                        exportState.acknowledgedSequence(), TerminalStateRetention.MAX_PRUNE_PER_ACK);
                commandChangedUserIds = pruneBatch.orderIds().stream()
                        .map(this::runtimeOrder)
                        .filter(java.util.Objects::nonNull)
                        .map(OrderRuntime::userId)
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

    private void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(command.symbol());
        RiskScanRuntime scan = symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = triggerOrderIndex.maxPendingId(command.symbol());
        RuntimeCommandProcessor.replaceRiskScan(runtimePlaceOrderState,
                scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    private void evaluatePendingTriggerScan(String symbol, int maxWork) {
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : runtimePlaceOrderState.riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return;
        long markPriceTicks = scan.triggerMarkPriceTicks();
        long triggeredAt = scan.triggerGeneratedAtEpochMillis();
        UUID commandId = UUID.nameUUIDFromBytes((productLine.name() + ":MARK_PRICE:" + symbol + ":"
                + scan.priceSequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int remaining = Math.min(maxWork, DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
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

    private void cancelTriggersForClosedPositions() {
        seedChangeAccumulators();
        if (!runtimePlaceOrderState.hasChangedPositions()) return;
        for (long positionKey : runtimePlaceOrderState.changedPositions().toArray()) {
            var previous = runtimePlaceOrderState.currentPatchPositionBefore(positionKey);
            if (previous == null || previous.signedQuantitySteps() == 0) continue;
            var current = runtimePlaceOrderState.position(positionKey);
            if (current != null && current.signedQuantitySteps() != 0) continue;
            var identity = runtimePlaceOrderIdentities.positionIdentity(positionKey);
            var previousMarginMode = previous.marginMode();
            for (long triggerOrderId : triggerOrderIndex.ids(identity.userId())) {
                var trigger = runtimePlaceOrderState.triggerOrder(triggerOrderId);
                if (trigger == null || trigger.status()
                        != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                String triggerPositionIdentity = trigger.positionSide()
                        == com.surprising.aeron.protocol.CorePositionSide.NET
                        ? trigger.symbol() : trigger.symbol() + ':' + trigger.positionSide().name();
                if (!identity.positionKey().equals(triggerPositionIdentity)
                        || trigger.marginMode() != previousMarginMode) continue;
                cancelTriggerOrderRuntime(identity.userId(), triggerOrderId);
                markTriggerChanged(triggerOrderId);
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
        commandOrderViews = appendDistinct(commandOrderViews, List.of(runtimeOrderView(childOrderId)));
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
            snapshotProjectionProvisionalOnly = false;
            return;
        }
        projectSnapshotNow();
    }

    private void deferProvisionalSnapshotProjection() {
        if (!snapshotProjectionDeferred) {
            throw new IllegalStateException("provisional projection requires a command batch");
        }
        if (!snapshotProjectionDirty) snapshotProjectionProvisionalOnly = true;
        snapshotProjectionDirty = true;
    }

    private void beginSnapshotProjectionBatch() {
        if (snapshotProjectionDeferred) {
            throw new IllegalStateException("snapshot projection batch is already active");
        }
        snapshotProjectionDeferred = true;
        snapshotProjectionDirty = false;
        snapshotProjectionProvisionalOnly = false;
    }

    private void completeSnapshotProjectionBatch() {
        completeSnapshotProjectionBatch(null);
    }

    private void completeSnapshotProjectionBatch(
            List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneCommits) {
        boolean dirty = snapshotProjectionDirty;
        boolean provisionalOnly = snapshotProjectionProvisionalOnly;
        snapshotProjectionDeferred = false;
        snapshotProjectionDirty = false;
        snapshotProjectionProvisionalOnly = false;
        if (dirty && provisionalOnly) runtimePlaceOrderState.clearChangedKeys();
        else if (dirty) projectSnapshotNow(laneCommits);
    }

    private void abortSnapshotProjectionBatch() {
        snapshotProjectionDeferred = false;
        snapshotProjectionDirty = false;
        snapshotProjectionProvisionalOnly = false;
    }

    private void projectSnapshotNow() {
        projectSnapshotNow(null);
    }

    private void projectSnapshotNow(
            List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneCommits) {
        long projectionSequence = Math.incrementExact(runtimeProjectionJournal.publishedSequence());
        long previousBusinessStateHash = cachedBusinessStateHash;
        long previousFundsStateHash = rollingFundsStateHash.value();
        com.surprising.aeron.service.state.TradingRuntimeState.PreparedCommit preparedCommit;
        com.surprising.aeron.service.state.RuntimeCommitPatch.PreparedChanges preparedChanges = null;
        com.surprising.aeron.service.state.RollingBusinessStateHash.HashTransition businessTransition = null;
        com.surprising.aeron.service.state.RollingFundsStateHash.HashTransition fundsTransition = null;
        com.surprising.aeron.service.state.RuntimeCommitPatch commit = null;
        CoreAdmissionReservation.FactPermit factPermit = null;
        CoreExportState.PatchChain nextFactPatchChain = null;
        UUID factCommandId = null;
        boolean indexesApplied = false;
        try {
            commitFaultInjector.inject("preflight");
            if (currentAdmission == null && currentRetentionAdmission == null
                    || activeFactCommand == null || activeFactFingerprint == null) {
                throw new IllegalStateException("Core Fact metadata must be admitted before runtime mutation");
            }
            if (currentAdmission != null) factPermit = currentAdmission.reserveFactPatch();
            boolean retentionOnly = currentAdmission == null;
            long coreSequence = retentionOnly ? committedCoreSequence
                    : laneCommits == null || laneCommits.isEmpty()
                    ? Math.incrementExact(appliedCommandCount) : laneCommits.getFirst().coreSequence();
            long previousCoreSequence = retentionOnly
                    ? coreSequence : Math.subtractExact(coreSequence, 1);
            preparedCommit = runtimePlaceOrderState.prepareCommitPatch(
                    projectionSequence, previousCoreSequence, coreSequence,
                    runtimePlaceOrderIdentities,
                    runtimePatchRevision, commandMatcherTransition, laneCommits,
                    previousBusinessStateHash, previousBusinessStateHash,
                    previousFundsStateHash, previousFundsStateHash, commandExternalAdjustment);
            preparedCommit.builder().coreFactValues(
                    new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactValues(
                            commandExecutions, commandFundingPayments,
                            commandFundingProgress, commandSettlementProgress));
            activeFactTopologyHash = matchingAdapter.topology().topologyHash();
            activeFactLaneRevisionHash = laneRevisionHash();
            var factMetadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                    activeFactCommand.header().commandId(), activeFactFingerprint,
                    activeFactCommand.header().messageType().wireCode(), activeFactCommand.header().userId(),
                    ResponseStatus.APPLIED, CoreResultCode.NONE, coreSequence, currentClusterPosition,
                    activeFactTopologyHash, activeFactLaneRevisionHash, commandExternalAdjustment);
            var baseMetadata = preparedCommit.metadata();
            preparedChanges = preparedCommit.builder().prepare(
                            new com.surprising.aeron.service.state.RuntimeCommitPatch.PrepareMetadata(
                                    baseMetadata.beforeRevision(), baseMetadata.afterRevision(),
                                    baseMetadata.beforeBusinessStateHash(), baseMetadata.beforeFundsStateHash(),
                                    baseMetadata.laneMask(), factMetadata, baseMetadata.externalAdjustment()),
                            preparedCommit.identities());
            long nextBusinessStateHash;
            long nextFundsStateHash;
            if (retentionOnly) {
                nextBusinessStateHash = previousBusinessStateHash;
                nextFundsStateHash = previousFundsStateHash;
            } else {
                businessTransition = rollingBusinessStateHash.prepareApplied(preparedChanges);
                fundsTransition = rollingFundsStateHash.prepareApplied(preparedChanges);
                nextBusinessStateHash = canonicalBusinessStateHash(businessTransition.afterHash());
                nextFundsStateHash = fundsTransition.afterHash();
            }
            commit = preparedCommit.seal(preparedChanges, nextBusinessStateHash, nextFundsStateHash);
            if (factPermit != null) {
                factPermit.consume(commit);
                factCommandId = activeFactCommand.header().commandId();
                nextFactPatchChain = new CoreExportState.PatchChain(
                        commit, factPatchChains.get(factCommandId), factPermit);
            }
            runtime.commitRuntimeTransition(commit, previousBusinessStateHash, nextBusinessStateHash);
            indexesApplied = true;
            commitFaultInjector.inject("indexes");
            if (!retentionOnly) {
                businessTransition.commit();
                commitFaultInjector.inject("business-hash");
                fundsTransition.commit();
                commitFaultInjector.inject("funds-hash");
            }
            if (currentAdmission != null) {
                publishSealedCommit(commit, nextBusinessStateHash, nextFundsStateHash);
            } else {
                runtimeProjectionJournal.publish(currentRetentionAdmission, commit,
                        nextBusinessStateHash, nextFundsStateHash);
            }
        } catch (RuntimeException failure) {
            if (laneCommits != null && !laneCommits.isEmpty()) {
                try {
                    runtimePlaceOrderState.rollbackLaneSequence(laneCommits);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (factPermit != null) {
                try {
                    currentAdmission.abortFactPatch(factPermit);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (fundsTransition != null) {
                try {
                    fundsTransition.rollback();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (businessTransition != null) {
                try {
                    businessTransition.rollback();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (indexesApplied) {
                try {
                    runtime.rollbackRuntimeTransition(commit, runtimePatchRevision, previousBusinessStateHash);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                if (commit != null) runtimePlaceOrderState.abortPreparedCommit(commit);
                else runtimePlaceOrderState.abortPreparedCommit(preparedChanges);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (!(failure instanceof CoreAdmissionReservation.FactEstimateInvariantException)) {
                commitPublicationFailure = new IllegalStateException("owner commit publication failed", failure);
            }
            throw failure;
        }
        seedChangeAccumulators();
        commit.acceptChangedUserIds(changedUserIds::add);
        if (laneCommits != null) runtimePlaceOrderState.commitLaneSequence(laneCommits);
        commandFundsDelta = commandFundsDelta.plus(commit.fundsDelta());
        currentProjectionPoint = commit.projectionPoint();
        if (factCommandId != null) factPatchChains.put(factCommandId, nextFactPatchChain);
        if (capturedCommitPatches != null) capturedCommitPatches.add(commit);
        runtimePatchRevision = commit.revision();
        releaseRetiredIdentities(commit);
        runtimePlaceOrderState.clearChangedKeys();
    }

    private void releaseRetiredIdentities(
            com.surprising.aeron.service.state.RuntimeCommitPatch commit) {
        for (var group : commit.accountLaneGroups()) {
            for (var change : group.clientOrders()) {
                if (change.afterOrderId() == null
                        && runtimePlaceOrderState.orderIdByClient(
                        change.key().userId(), change.key().clientKey()) == null) {
                    runtimePlaceOrderIdentities.releaseClientKey(
                            change.key().userId(), change.key().clientKey());
                }
            }
            for (var change : group.positions()) {
                releaseRetiredPositionIdentity(change.positionKey(), change.after());
            }
            for (var change : group.riskSnapshots()) {
                releaseRetiredPositionIdentity(change.riskKey(), change.after());
            }
        }
    }

    private void releaseRetiredPositionIdentity(long positionKey, Object after) {
        if (after == null && runtimePlaceOrderState.position(positionKey) == null
                && runtimePlaceOrderState.riskSnapshot(positionKey) == null) {
            runtimePlaceOrderIdentities.releasePositionKey(positionKey);
        }
    }

    private void publishSealedCommit(com.surprising.aeron.service.state.RuntimeCommitPatch commit,
            long businessStateHash,
            long fundsStateHash) {
        if (currentAdmission == null) throw new IllegalStateException("commit admission reservation is missing");
        currentAdmission.publish(commit, businessStateHash, fundsStateHash);
    }

    private void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                          long pendingCoreSequence) {
        reservePlaceOrderRuntime(userId, command, commandId, pendingCoreSequence,
                openInterestIndex.openInterestSteps(command.symbol()), activeOrderIndex);
    }

    private void reservePlaceOrderRuntime(
            long userId, PlaceOrderCommand command, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex) {
        reservePlaceOrderRuntime(userId, command, commandId, pendingCoreSequence, openInterestSteps,
                admissionOrderIndex, null);
    }

    private void reservePlaceOrderRuntime(
            long userId, PlaceOrderCommand command, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex,
            OrderBatchPending batch) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimePlaceOrderState,
                runtimePlaceOrderIdentities, userId, command, currentClusterTimestamp);
        var admissionIdentity =
                com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                        runtimePlaceOrderState, runtimePlaceOrderIdentities, userId, resolved);
        long requiredReservation =
                com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationPrepared(
                        runtimePlaceOrderState, userId, resolved, openInterestSteps,
                        admissionOrderIndex, admissionIdentity);
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence, requiredReservation, batch);
    }

    private void reservePlaceOrderRuntime(ResolvedMatchingAdmission admission, UUID commandId,
                                          long pendingCoreSequence) {
        reservePlaceOrderRuntime(admission.userId(), admission.resolved(), commandId, pendingCoreSequence,
                admission.requiredReservationUnits());
    }

    private void reservePlaceOrderRuntime(long userId, ResolvedPlaceOrder resolved, UUID commandId,
                                          long pendingCoreSequence, long requiredReservation) {
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence, requiredReservation, null);
    }

    private void reservePlaceOrderRuntime(long userId, ResolvedPlaceOrder resolved, UUID commandId,
                                          long pendingCoreSequence, long requiredReservation,
                                          OrderBatchPending batch) {
        var preparedClientKey = runtimePlaceOrderIdentities.prepareClientKey(
                userId, resolved.clientOrderId());
        Integer symbolId = runtimePlaceOrderIdentities.findSymbolId(resolved.symbol());
        if (symbolId == null) {
            if (preparedClientKey.allocated()) {
                runtimePlaceOrderIdentities.rollbackPreparedClientKey(
                        userId, resolved.clientOrderId(), preparedClientKey);
            }
            throw new IllegalStateException("order symbol identity was not prepared with the instrument");
        }
        int assetId = runtimePlaceOrderIdentities.assetId(resolved.reservationAsset());
        try {
            runtimePlaceOrderState.executeUserSettlement(userId, () -> {
                RuntimeCommandProcessor.placeOrderPrepared(runtimePlaceOrderState, userId, resolved,
                        commandId, requiredReservation, preparedClientKey.key(), symbolId, assetId);
                runtimePlaceOrderState.markPendingReservation(userId, resolved.orderId(), pendingCoreSequence);
                return null;
            });
        } catch (RuntimeException | Error failure) {
            if (preparedClientKey.allocated()) {
                runtimePlaceOrderIdentities.rollbackPreparedClientKey(
                        userId, resolved.clientOrderId(), preparedClientKey);
            }
            throw failure;
        }
        if (batch != null) {
            batch.retainPreparedClientKey(userId, resolved.clientOrderId(), preparedClientKey);
        }
        if (pendingOrderBatches.isEmpty()) deferProvisionalSnapshotProjection();
        else refreshSnapshotProjection();
    }

    private ResolvedMatchingAdmission requireMatchingAdmission(PendingMatching pending) {
        ResolvedMatchingAdmission admission = pending.admission();
        if (admission == null) throw new IllegalStateException("replace admission is missing");
        return admission;
    }

    private void requireUnchangedAdmissionState(ResolvedMatchingAdmission admission) {
        OrderRuntime original = runtimeOrder(admission.originalOrderId());
        var user = runtimePlaceOrderState.user(admission.userId());
        long userRevision = user == null ? 0 : user.revision();
        if (original == null || original.revision() != admission.originalOrderRevision()
                || userRevision != admission.userRevision()) {
            throw new IllegalStateException("replace admission state changed before matcher completion");
        }
    }

    private CoreMatchingOrder matchingOrder(long orderId) {
        com.surprising.aeron.service.state.OrderRuntime order = runtimePlaceOrderState.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "runtime order is missing");
        return new CoreMatchingOrder(order.orderId(), runtimePlaceOrderIdentities.symbol(order.symbolId()),
                order.side(), order.orderType(), order.timeInForce(), order.matchingPriceTicks(),
                order.remainingQuantitySteps());
    }

    private OrderRuntime runtimeOrder(long orderId) {
        return runtimePlaceOrderState.order(orderId);
    }

    private String runtimeOrderSymbol(OrderRuntime order) {
        return runtimePlaceOrderIdentities.symbol(order.symbolId());
    }

    private CoreOrderStateView runtimeOrderView(long orderId) {
        OrderRuntime order = runtimeOrder(orderId);
        return order == null ? null : orderView(order);
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

    private void cancelOrderBatchRuntime(long userId, List<Long> orderIds) {
        boolean changed = runtimePlaceOrderState.executeUserSettlement(userId, () -> {
            boolean laneChanged = false;
            for (Long orderId : orderIds) {
                laneChanged |= RuntimeCommandProcessor.cancelOrder(runtimePlaceOrderState, userId, orderId);
            }
            return laneChanged;
        });
        if (changed) refreshSnapshotProjection();
    }

    private void rejectPlaceOrderRuntime(long userId, long orderId, long coreSequence) {
        runtimePlaceOrderState.executeUserSettlement(userId, () -> {
            RuntimeCommandProcessor.rejectPlaceOrder(runtimePlaceOrderState, userId, orderId, coreSequence);
            return null;
        });
        refreshSnapshotProjection();
    }

    private void stampOrderChangesRuntime(long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (RuntimeCommandProcessor.stampChangedOrdersByLane(
                runtimePlaceOrderState, timestamp, clusterPosition,
                changedOrderIds, commandChangedUserIds)) {
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
            com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan,
            long coreSequence,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext) {
        if (laneContext.settlementDispatched()) {
            return runtimePlaceOrderState.applyPreparedPerpetualSettlement(
                    settlementPlan, laneContext.settlementJournals(), runtimePlaceOrderIdentities);
        }
        return runtimePlaceOrderState.applyMatcherSettlement(
                coreSequence, laneContext.expectedLaneMask(), settlementPlan,
                matchingResult, runtimePlaceOrderIdentities);
    }

    private void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    private void restoreCommandState(TradingCoreState state) {
        if (activeFactCommand != null) factPatchChains.remove(activeFactCommand.header().commandId());
        abortSnapshotProjectionBatch();
        runtime.restoreStateOnly(state);
        snapshotState = state;
        runtimePlaceOrderIdentities = runtime.identitiesForConstruction();
        runtimePlaceOrderState = runtime.runtimeStateForConstruction();
        rollingBusinessStateHash.restore(state, runtimePlaceOrderIdentities);
        rollingFundsStateHash.restore(state, runtimePlaceOrderIdentities);
        runtimePatchRevision = state.revision();
        runtime.restoreCommittedConsumers(
                state, runtimePatchRevision, canonicalBusinessStateHash(state.businessStateHash()));
    }

    private void restoreCommandState(RuntimeProjectionPoint projectionPoint) {
        restoreCommandState(runtimeProjectionJournal.await(projectionPoint));
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
                matcherSequence(-1), matcherPrefixDigest(-1));
        commandFundsDelta = com.surprising.aeron.service.state.RuntimeFundsDelta.empty();
        commandExternalAdjustment = false;
        commandTerminalOrderIds = null;
        changedUserIds.clear();
        changedOrderIds.clear();
        changedLiquidationIds.clear();
        changedTriggerOrderIds.clear();
        changedTreasuryAssets.clear();
    }

    private long appendCoreFact(CoreMessage command, CommandFingerprint fingerprint,
                                ResponseStatus status, CoreResultCode resultCode,
                                long appliedCount, long businessStateHash, RuntimeProjectionPoint beforeProjection,
                                RuntimeProjectionPoint afterProjection, CoreCommandDelta delta,
                                com.surprising.aeron.protocol.CoreMatcherTransition matcherTransition) {
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        long fundsStateHash = rollingFundsStateHash.value();
        boolean activeCommand = activeFactCommand != null
                && activeFactCommand.header().commandId().equals(command.header().commandId());
        long topologyHash = activeCommand && activeFactTopologyHash != 0
                ? activeFactTopologyHash : matchingAdapter.topology().topologyHash();
        long revisionHash = activeCommand && activeFactLaneRevisionHash != 0
                ? activeFactLaneRevisionHash : laneRevisionHash();
        long clusterPosition = currentClusterPosition;
        long beforeBusinessStateHash = commandBeforeBusinessStateHash;
        long beforeFundsStateHash = commandBeforeFundsStateHash;
        CoreExportState.PatchChain commandFactPatches = factPatchChains.remove(command.header().commandId());
        long[] terminalOrderIds = terminalOrderIds(delta);
        int itemCount = Math.addExact(delta.executions().size(), delta.fundingPayments().size());
        itemCount = Math.addExact(itemCount, terminalOrderIds.length);
        if (commandFactPatches != null) itemCount = Math.addExact(itemCount, commandFactPatches.itemCount());
        com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata metadata =
                commandFactPatches == null ? null : commandFactPatches.patch().coreFactMetadata();
        if (metadata == null
                || !metadata.commandId().equals(command.header().commandId())
                || !metadata.commandFingerprint().equals(fingerprint)
                || metadata.messageTypeWireCode() != command.header().messageType().wireCode()
                || metadata.userId() != command.header().userId()
                || metadata.status() != status || metadata.resultCode() != resultCode
                || metadata.appliedCommandCount() != appliedCount
                || metadata.clusterPosition() != clusterPosition
                || metadata.topologyHash() != topologyHash
                || metadata.laneRevisionHash() != revisionHash
                || metadata.externalAdjustment() != externalAdjustment) {
            metadata = new com.surprising.aeron.service.state.RuntimeCommitPatch.CoreFactMetadata(
                    command.header().commandId(), fingerprint,
                    command.header().messageType().wireCode(), command.header().userId(), status, resultCode,
                    appliedCount, clusterPosition, topologyHash, revisionHash, externalAdjustment);
        }
        CoreExportState.Draft draft = new CoreExportState.Draft(command, status, resultCode, appliedCount,
                businessStateHash, beforeBusinessStateHash, beforeFundsStateHash, fundsStateHash,
                topologyHash, revisionHash, matcherTransition, clusterPosition, afterProjection.sequence(),
                itemCount, terminalOrderIds, commandFactPatches, delta, commandFundsDelta,
                runtimePlaceOrderIdentities,
                metadata);
        long sequence = currentAdmission == null
                ? exportState.append(draft) : currentAdmission.append(draft);
        if (commandFactPatches != null) {
            commandFactPatches.acceptOldestFirst(patch -> terminalRetention.observe(patch, sequence));
        }
        return sequence;
    }

    private long[] terminalOrderIds(CoreCommandDelta delta) {
        if (commandTerminalOrderIds != null) return commandTerminalOrderIds;
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList terminal =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        List<Long> orderIds = delta.orderIds();
        for (int index = 0; index < orderIds.size(); index++) {
            long orderId = orderIds instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : orderIds.get(index);
            var order = runtimePlaceOrderState.order(orderId);
            if (order != null && order.status().terminal()) terminal.add(orderId);
        }
        return terminal.toArray();
    }

    private void validateFundsConservation(CoreMessage command) {
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        commandFundsDelta.requireConserved(externalAdjustment);
    }

    private long laneRevisionHash() {
        long hash = 0xcbf29ce484222325L ^ matchingAdapter.topology().topologyHash();
        for (int laneId = 0; laneId < matchingAdapter.topology().accountLaneCount(); laneId++) {
            var lane = runtimePlaceOrderState.accountLaneById(laneId);
            hash ^= laneId;
            hash *= 0x100000001b3L;
            hash ^= lane.localStateHash();
            hash *= 0x100000001b3L;
            hash ^= lane.localFundsHash();
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 1 : hash;
    }

    private List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit>
    applyAndCommitLaneSequence(
            long sequence, Iterable<Long> userIds,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            long stateContribution, long fundsContribution, LaneCommandContextRing.Context context) {
        if (context == null || sequence <= 0) {
            throw new IllegalStateException("account lane commit context is missing");
        }
        List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneCommits =
                runtimePlaceOrderState.applyAndCommitLaneSequence(sequence, userIds, matchingResult,
                        stateContribution, fundsContribution);
        for (com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit laneCommit : laneCommits) {
            context.completeLane(laneCommit.laneId(), laneCommit.afterRevision(),
                    laneCommit.afterHash(), laneCommit.afterFundsHash());
        }
        return laneCommits;
    }

    private List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit>
    applyAndCommitLaneSequence(
            long sequence, long[] userIds,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            long stateContribution, long fundsContribution, LaneCommandContextRing.Context context) {
        if (context == null || sequence <= 0) {
            throw new IllegalStateException("account lane commit context is missing");
        }
        List<com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit> laneCommits =
                runtimePlaceOrderState.applyAndCommitLaneSequence(sequence, userIds, matchingResult,
                        stateContribution, fundsContribution);
        for (com.surprising.aeron.service.state.RuntimeCommitPatch.LaneCommit laneCommit : laneCommits) {
            context.completeLane(laneCommit.laneId(), laneCommit.afterRevision(),
                    laneCommit.afterHash(), laneCommit.afterFundsHash());
        }
        return laneCommits;
    }

    private long appendRejectedCoreFact(CoreMessage command, CommandFingerprint fingerprint,
                                        CoreResultCode resultCode, long appliedCount) {
        commandBeforeBusinessStateHash = currentBusinessStateHash();
        commandBeforeFundsStateHash = rollingFundsStateHash.value();
        commandMatcherTransition = com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(
                matcherSequence(-1), matcherPrefixDigest(-1));
        return appendCoreFact(command, fingerprint, ResponseStatus.REJECTED, resultCode, appliedCount,
                cachedBusinessStateHash, currentProjectionPoint, currentProjectionPoint, CoreCommandDelta.empty(),
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
        for (long userId : runtimePlaceOrderState.changedUsers().toArray()) {
            changedUserIds.add(userId);
        }
        commandChangedUserIds = ImmutableLongArrayList.copyOf(changedUserIds.toPrimitiveArray());
        commandChangedOrderIds = ImmutableLongArrayList.copyOf(changedOrderIds.toPrimitiveArray());
        commandChangedLiquidationIds = ImmutableLongArrayList.copyOf(changedLiquidationIds.toPrimitiveArray());
        commandChangedTriggerOrderIds = ImmutableLongArrayList.copyOf(changedTriggerOrderIds.toPrimitiveArray());
        commandChangedTreasuryAssets = List.copyOf(changedTreasuryAssets);
    }

    private static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    private static List<Long> matchingUserIds(long takerUserId, List<MatcherEvent> matches) {
        PrimitiveLongChangeSet ids = new PrimitiveLongChangeSet();
        ids.add(takerUserId);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            ids.add(match.matchedOrderUid());
        }
        return immutableLongList(ids);
    }

    private static List<Long> matchingOrderIds(List<Long> initialOrderIds, List<MatcherEvent> matches) {
        PrimitiveLongChangeSet ids = new PrimitiveLongChangeSet();
        ids.addAll(initialOrderIds);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            ids.add(match.matchedOrderId());
        }
        return immutableLongList(ids);
    }

    private static List<Long> immutableLongList(PrimitiveLongChangeSet ids) {
        return ImmutableLongArrayList.copyOf(ids.toPrimitiveArray());
    }

    private static List<Long> boxedLongs(long[] values) {
        return ImmutableLongArrayList.copyOf(values);
    }

    private static List<Long> boxedLongsIncluding(long[] values, long requiredValue) {
        return ImmutableLongArrayList.sortedDistinct(values, requiredValue);
    }

    private int pendingRiskScanCount() {
        return runtimePlaceOrderState.incompleteRiskScanCount();
    }

    private void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt) {
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        int pendingAfter = pendingRiskScanCount();
        System.Logger.Level level = System.Logger.Level.DEBUG;
        if (!LOG.isLoggable(level)) return;
        LOG.log(level, "risk scan operation={0} symbol={1} batchSize={2} elapsedMicros={3} "
                        + "pendingSymbolsBefore={4} pendingSymbolsAfter={5}",
                new Object[]{operation, symbol, batchSize, elapsedMicros, pendingBefore, pendingAfter});
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (activated) runtime.assertOwner();
        releaseSnapshotFence();
        if (snapshotEncoderExecutor != null) snapshotEncoderExecutor.shutdownNow();
        snapshotAuditExecutor.shutdownNow();
        inFlightMatcherSnapshot.set(null);
        if (!matchingCompletions.awaitQuiescence(java.util.concurrent.TimeUnit.SECONDS.toNanos(30))) {
            inFlightMatcherSnapshot.set(null);
        }
        matchingCompletions.clear();
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        rollbackPendingOrderBatchesForTeardown();
        if (currentAdmission != null) {
            currentAdmission.releaseUnused();
            clearFactContext();
        }
        pendingMatching.forEach(pending -> {
            if (pending.capacityReservation() != null) pending.capacityReservation().releaseUnused();
        });
        pendingMatching.clear();
        factPatchChains.clear();
        pendingMatchingRejections.clear();
        pendingLifecycleScopes.clear();
        pendingOrderBatches.clear();
        deferredMatching.clear();
        exportState.close();
        runtimeProjectionJournal.close();
        runtime.close();
    }

    private void rollbackPendingOrderBatchesForTeardown() {
        for (OrderBatchPending batch : pendingOrderBatches.values()) {
            if (batch.started && batch.runtimeCheckpoint != null) {
                rollbackOrderBatchMutations(batch, true);
            }
        }
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
        java.util.ArrayList<com.surprising.aeron.protocol.CoreExecutionView> executions = null;
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            if (executions == null) executions = new java.util.ArrayList<>();
            executions.add(new com.surprising.aeron.protocol.CoreExecutionView(
                    takerOrderId, match.matchedOrderId(), takerUserId, match.matchedOrderUid(),
                    match.price(), match.size()));
        }
        return executions == null ? List.of() : List.copyOf(executions);
    }

    private CoreCommandDelta commandDelta() {
        List<CoreUserStateView> changedUsers = List.of();
        List<CoreOrderStateView> changedOrders = List.of();
        List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations = List.of();
        List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets = List.of();
        List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders = List.of();
        return new CoreCommandDelta(
                commandChangedUserIds, commandChangedOrderIds,
                commandChangedLiquidationIds, commandChangedTriggerOrderIds,
                commandExecutions, commandFundingPayments, commandFundingProgress, commandSettlementProgress,
                changedUsers, changedOrders, changedLiquidations, changedTreasuryAssets, changedTriggerOrders);
    }

    private void materializeCommandOrderViews() {
        PrimitiveLongChangeSet orderIds = new PrimitiveLongChangeSet();
        java.util.ArrayList<CoreOrderStateView> views = new java.util.ArrayList<>(
                commandOrderViews.size() + commandChangedOrderIds.size());
        for (CoreOrderStateView view : commandOrderViews) {
            if (orderIds.add(view.orderId())) views.add(view);
        }
        for (int orderIndex = 0; orderIndex < commandChangedOrderIds.size(); orderIndex++) {
            long orderId = commandChangedOrderIds instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(orderIndex) : commandChangedOrderIds.get(orderIndex);
            var order = runtimeOrder(orderId);
            if (order == null) continue;
            CoreOrderStateView view = orderView(order);
            if (orderIds.add(orderId)) {
                views.add(view);
                continue;
            }
            for (int index = 0; index < views.size(); index++) {
                if (views.get(index).orderId() == orderId) {
                    views.set(index, view);
                    break;
                }
            }
        }
        commandOrderViews = List.copyOf(views);
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList terminalIds =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        for (CoreOrderStateView view : commandOrderViews) {
            if (com.surprising.aeron.service.state.CoreOrderStatus.valueOf(view.status()).terminal()) {
                terminalIds.add(view.orderId());
            }
        }
        commandTerminalOrderIds = terminalIds.toArray();
    }

    private CoreOrderStateView orderView(OrderRuntime order) {
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                runtimeOrderSymbol(order), order.instrumentVersion(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
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
        if (nativeCommand.coreSequence() != pending.sequence()
                || !nativeCommand.matches(pending.command().header().commandId())
                || nativeCommand.orderId() <= 0 || nativeCommand.instrumentVersion() <= 0
                || nativeCommand.matcherSequence() <= 0 || !matcherPrefix.bound()) {
            return new byte[0];
        }
        try {
            return CoreCommandResultCodec.encode(new CoreCommandResultView(
                    pending.sequence(), pending.command().header().commandId(),
                    nativeCommand.orderId(), nativeCommand.instrumentVersion(), nativeCommand.matcherSequence(),
                    matcherPrefix.before(), matcherPrefix.after(), commandOrderViews, commandExecutions));
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
        return canonicalBusinessStateHash(rollingBusinessStateHash.value());
    }

    private long canonicalBusinessStateHash(long base) {
        if (cachedFeePolicyHash != 0) base = mix(base, cachedFeePolicyHash);
        return cachedTransferHash == 0 ? base : mix(base, cachedTransferHash);
    }

    static long canonicalBusinessStateHash(
            long base,
            Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
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

    private static long resultLedgerDigest(Map<UUID, StoredResult> results) {
        long digest = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            digest += resultContribution(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    private static long resultEntryDigest(UUID commandId, StoredResult result) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, commandId.getMostSignificantBits());
        digest = mix(digest, commandId.getLeastSignificantBits());
        for (int index = 0; index < CommandFingerprint.LENGTH; index++) {
            digest = mix(digest, Byte.toUnsignedInt(result.fingerprint().byteAt(index)));
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
            commandResultsDigest -= resultContribution(commandId, previous);
            commandResultsDigest += replacementContribution;
        } else {
            long retentionSequence = nextResultRetentionSequence;
            StoredResult retained = result.withRetentionSequence(retentionSequence);
            long contribution = resultEntryDigest(commandId, retained) * nextResultRetentionWeight;
            nextResultRetentionSequence = Math.incrementExact(nextResultRetentionSequence);
            nextResultRetentionWeight *= RESULT_LEDGER_POSITION_BASE;
            commandResults.put(commandId, retained);
            commandResultBytes = Math.addExact(commandResultBytes, resultBytes);
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
            commandResultsDigest -= resultContribution(oldest.getKey(), oldest.getValue());
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

    private record PreparedPlaceReservation(
            PlaceOrderCommand command,
            ResolvedPlaceOrder resolved,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionIdentity admissionIdentity,
            com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey preparedClientKey,
            int symbolId,
            int assetId,
            long openInterestSteps) {
    }

    private record DeferredMatching(long clusterTimestamp, long clusterPosition, SourceKey sourceKey) {
    }

    private final class BatchAdmissionOrderIndex
            implements com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex {
        private final ActiveOrderIndex baseline;
        private final java.util.Map<Long, OrderChange> changes = new java.util.HashMap<>();
        private final java.util.Map<PendingQuantityKey, Long> pendingQuantities = new java.util.HashMap<>();
        private final java.util.Map<ReduceQuantityKey, Long> reduceOnlyQuantities = new java.util.HashMap<>();
        private final java.util.Map<MarginCountKey, Integer> marginModeCounts = new java.util.HashMap<>();

        private BatchAdmissionOrderIndex(ActiveOrderIndex baseline) {
            this.baseline = java.util.Objects.requireNonNull(baseline, "baseline");
        }

        private void update(OrderRuntime previous, OrderRuntime current) {
            long orderId = current == null ? previous == null ? 0 : previous.orderId() : current.orderId();
            if (orderId == 0) return;
            changes.compute(orderId, (ignored, existing) -> new OrderChange(
                    existing == null ? previous : existing.previous(), current));
        }

        private void addPending(long userId, ResolvedPlaceOrder order) {
            if (order.reduceOnly()) {
                reduceOnlyQuantities.merge(new ReduceQuantityKey(userId, order.symbol(), order.side()),
                        order.quantitySteps(), Math::addExact);
            } else {
                pendingQuantities.merge(new PendingQuantityKey(
                                userId, order.symbol(), order.positionSide(), order.side()),
                        order.quantitySteps(), Math::addExact);
            }
            marginModeCounts.merge(new MarginCountKey(
                    userId, order.symbol(), order.positionSide(), order.marginMode()), 1, Math::addExact);
        }

        @Override
        public long pendingQuantity(long userId, String symbol,
                                    com.surprising.aeron.protocol.CorePositionSide positionSide,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
            long quantity = baseline.pendingQuantity(userId, symbol, positionSide, side);
            for (OrderChange change : changes.values()) {
                quantity = Math.subtractExact(quantity,
                        previousPendingContribution(change.previous(), userId, symbol, positionSide, side));
                quantity = Math.addExact(quantity,
                        currentPendingContribution(change.current(), userId, symbol, positionSide, side));
            }
            return Math.addExact(quantity, pendingQuantities.getOrDefault(
                    new PendingQuantityKey(userId, symbol, positionSide, side), 0L));
        }

        @Override
        public long reduceOnlyQuantity(long userId, String symbol,
                                       com.surprising.aeron.protocol.CoreOrderSide side) {
            long quantity = baseline.reduceOnlyQuantity(userId, symbol, side);
            for (OrderChange change : changes.values()) {
                quantity = Math.subtractExact(quantity,
                        previousReduceOnlyContribution(change.previous(), userId, symbol, side));
                quantity = Math.addExact(quantity,
                        currentReduceOnlyContribution(change.current(), userId, symbol, side));
            }
            return Math.addExact(quantity, reduceOnlyQuantities.getOrDefault(
                    new ReduceQuantityKey(userId, symbol, side), 0L));
        }

        @Override
        public int marginModeCount(long userId, String symbol,
                                   com.surprising.aeron.protocol.CorePositionSide positionSide,
                                   com.surprising.aeron.protocol.CoreMarginMode marginMode) {
            int count = baseline.marginModeCount(userId, symbol, positionSide, marginMode);
            for (OrderChange change : changes.values()) {
                count = Math.subtractExact(count,
                        previousMarginContribution(change.previous(), userId, symbol, positionSide, marginMode));
                count = Math.addExact(count,
                        currentMarginContribution(change.current(), userId, symbol, positionSide, marginMode));
            }
            return Math.addExact(count, marginModeCounts.getOrDefault(
                    new MarginCountKey(userId, symbol, positionSide, marginMode), 0));
        }

        private long previousPendingContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreOrderSide side) {
            return active(order) && !order.reduceOnly() && order.userId() == userId
                    && runtimeOrderSymbol(order).equals(symbol) && order.positionSide() == positionSide
                    && order.side() == side ? order.remainingQuantitySteps() : 0;
        }

        private long currentPendingContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreOrderSide side) {
            return active(order) && !order.reduceOnly() && order.userId() == userId
                    && runtimeOrderSymbol(order).equals(symbol) && order.positionSide() == positionSide
                    && order.side() == side ? order.remainingQuantitySteps() : 0;
        }

        private long previousReduceOnlyContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CoreOrderSide side) {
            return active(order) && order.reduceOnly() && order.userId() == userId
                    && runtimeOrderSymbol(order).equals(symbol) && order.side() == side
                    ? order.remainingQuantitySteps() : 0;
        }

        private long currentReduceOnlyContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CoreOrderSide side) {
            return active(order) && order.reduceOnly() && order.userId() == userId
                    && runtimeOrderSymbol(order).equals(symbol) && order.side() == side
                    ? order.remainingQuantitySteps() : 0;
        }

        private int previousMarginContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreMarginMode marginMode) {
            return active(order) && order.userId() == userId && runtimeOrderSymbol(order).equals(symbol)
                    && order.positionSide() == positionSide && order.marginMode() == marginMode ? 1 : 0;
        }

        private int currentMarginContribution(
                OrderRuntime order, long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreMarginMode marginMode) {
            return active(order) && order.userId() == userId && runtimeOrderSymbol(order).equals(symbol)
                    && order.positionSide() == positionSide && order.marginMode() == marginMode ? 1 : 0;
        }

        private static boolean active(OrderRuntime order) {
            return order != null
                    && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN;
        }

        private record OrderChange(OrderRuntime previous, OrderRuntime current) {
        }

        private record PendingQuantityKey(
                long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreOrderSide side) {
        }

        private record ReduceQuantityKey(
                long userId, String symbol, com.surprising.aeron.protocol.CoreOrderSide side) {
        }

        private record MarginCountKey(
                long userId, String symbol,
                com.surprising.aeron.protocol.CorePositionSide positionSide,
                com.surprising.aeron.protocol.CoreMarginMode marginMode) {
        }
    }

    private static final class OrderBatchPending {
        private final OrderBatchKind kind;
        private final List<OrderBatchItem> items;
        private RuntimeProjectionPoint beforeProjection;
        private com.surprising.aeron.service.state.TradingRuntimeState.CommandCheckpoint runtimeCheckpoint;
        private long positionIdentityCheckpoint;
        private final long clusterTimestamp;
        private final long clusterPosition;
        private final PendingMatching.Operation operation;
        private final List<CoreOrderBatchResult.Item> results = new ArrayList<>();
        private final java.util.LinkedHashSet<Long> changedUserIds = new java.util.LinkedHashSet<>();
        private final java.util.LinkedHashSet<Long> changedOrderIds = new java.util.LinkedHashSet<>();
        private final java.util.LinkedHashSet<Long> runtimeChangedOrderIds = new java.util.LinkedHashSet<>();
        private final List<Long> acceptedCancellationOrderIds = new ArrayList<>();
        private final List<com.surprising.aeron.service.matching.CoreMatchingResult> matchingResults =
                new ArrayList<>();
        private final List<Long> deferredNoTradeOrderIds = new ArrayList<>();
        private final List<com.surprising.aeron.service.matching.CoreMatchingResult>
                deferredNoTradeMatchingResults = new ArrayList<>();
        private final List<Long> deferredPerpetualOrderIds = new ArrayList<>();
        private final List<Long> deferredPerpetualExpectedLaneMasks = new ArrayList<>();
        private final List<com.surprising.aeron.service.matching.CoreMatchingResult>
                deferredPerpetualMatchingResults = new ArrayList<>();
        private final List<PreparedClientAllocation> preparedClientKeys = new ArrayList<>();
        private com.surprising.aeron.service.state.RuntimeTreasuryDelta[] laneTreasuryDeltas;
        private int nextIndex;
        private long sequence;
        private List<Long> currentPreMatchingCancellationOrderIds = List.of();
        private boolean started;
        private boolean pipelined;
        private List<com.surprising.aeron.service.matching.CoreMatchingResult> pipelinedMatchingResults;
        private Throwable pipelinedMatchingFailure;
        private com.surprising.aeron.protocol.CoreMatcherTransition matcherTransition;
        private com.surprising.aeron.service.matching.CoreMatchingResult lastMatchingResult;
        private BatchAdmissionOrderIndex admissionOrderIndex;

        private OrderBatchPending(OrderBatchKind kind, List<OrderBatchItem> items,
                                  long clusterTimestamp,
                                  long clusterPosition, PendingMatching.Operation operation) {
            this.kind = kind;
            this.items = List.copyOf(items);
            this.clusterTimestamp = clusterTimestamp;
            this.clusterPosition = clusterPosition;
            this.operation = operation;
        }

        private void advanceMatcher(
                com.surprising.aeron.service.matching.CoreMatchingResult result) {
            long nextSequence = result.nativeCommand().matcherSequence();
            long nextPrefix = result.matcherPrefix().after();
            if (matcherTransition == null
                    || result.nativeCommand().matcherShardId() != matcherTransition.matcherShardId()
                    || nextSequence <= matcherTransition.sequenceAfter()
                    || result.matcherPrefix().before() != matcherTransition.prefixAfter()) {
                throw new IllegalArgumentException("order batch matcher transition is not contiguous");
            }
            matcherTransition = new com.surprising.aeron.protocol.CoreMatcherTransition(
                    matcherTransition.routeVersion(), matcherTransition.matcherShardId(),
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

        private void retainPreparedClientKey(
                long userId, String clientOrderId,
                com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey prepared) {
            if (prepared != null && prepared.allocated()) {
                preparedClientKeys.add(new PreparedClientAllocation(userId, clientOrderId, prepared));
            }
        }

        private void rollbackPreparedClientKeys(
                com.surprising.aeron.service.state.RuntimeIdentityRegistry identities) {
            for (int index = preparedClientKeys.size() - 1; index >= 0; index--) {
                PreparedClientAllocation allocation = preparedClientKeys.get(index);
                identities.rollbackPreparedClientKey(allocation.userId(), allocation.clientOrderId(),
                        allocation.prepared());
            }
            preparedClientKeys.clear();
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

        private List<Long> runtimeChangedOrderIdsFor(
                OrderBatchItem item,
                com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
            java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
            matchingResult.cancellations().stream().filter(CoreCancellationResult::accepted)
                    .map(CoreCancellationResult::orderId).forEach(ids::add);
            if (kind == OrderBatchKind.PLACE || matchingResult.accepted()) {
                ids.add(item.orderId());
                if (item.originalOrderId() > 0) ids.add(item.originalOrderId());
                if (item.replacementOrderId() > 0) ids.add(item.replacementOrderId());
                matchingResult.matcherEvents().stream()
                        .filter(event -> event.eventType() == MatcherEventType.TRADE)
                        .map(MatcherEvent::matchedOrderId)
                        .forEach(ids::add);
            }
            return List.copyOf(ids);
        }
    }

    private record PreparedClientAllocation(
            long userId, String clientOrderId,
            com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey prepared) {}

    private static final class PipelinedBatchNotApplicable extends RuntimeException {
        private PipelinedBatchNotApplicable() {
            super(null, null, false, false);
        }
    }


    static final class StoredResult {
        private final CommandFingerprint fingerprint;
        private final ResponseStatus status;
        private final CoreResultCode resultCode;
        private final long appliedCommandCount;
        private final long requiredExportSequence;
        private final long stateHash;
        private final byte[] responseData;
        private final long retentionSequence;

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount, long requiredExportSequence, long stateHash,
                     byte[] responseData, long retentionSequence) {
            this(fingerprint, status, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                    responseData, retentionSequence, false);
        }

        private StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                             long appliedCommandCount, long requiredExportSequence, long stateHash,
                             byte[] responseData, long retentionSequence, boolean ownedResponseData) {
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || requiredExportSequence < 0 || retentionSequence < 0) {
                throw new IllegalArgumentException("invalid stored result");
            }
            this.fingerprint = fingerprint;
            this.status = status;
            this.resultCode = resultCode;
            this.appliedCommandCount = appliedCommandCount;
            this.requiredExportSequence = requiredExportSequence;
            this.stateHash = stateHash;
            byte[] normalized = responseData == null ? new byte[0] : responseData;
            this.responseData = ownedResponseData ? normalized : normalized.clone();
            this.retentionSequence = retentionSequence;
        }

        static StoredResult owned(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                                  long appliedCommandCount, long requiredExportSequence, long stateHash,
                                  byte[] responseData) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, responseData, 0, true);
        }

        StoredResult withRetentionSequence(long sequence) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, responseData, sequence, true);
        }

        CommandFingerprint fingerprint() { return fingerprint; }
        ResponseStatus status() { return status; }
        CoreResultCode resultCode() { return resultCode; }
        long appliedCommandCount() { return appliedCommandCount; }
        long requiredExportSequence() { return requiredExportSequence; }
        long stateHash() { return stateHash; }
        long retentionSequence() { return retentionSequence; }

        byte[] responseDataUnsafe() {
            return responseData;
        }

        public byte[] responseData() {
            return responseData.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StoredResult result)) return false;
            return appliedCommandCount == result.appliedCommandCount
                    && requiredExportSequence == result.requiredExportSequence
                    && stateHash == result.stateHash
                    && retentionSequence == result.retentionSequence
                    && fingerprint.equals(result.fingerprint)
                    && status == result.status
                    && resultCode == result.resultCode
                    && java.util.Arrays.equals(responseData, result.responseData);
        }

        @Override
        public int hashCode() {
            int hash = java.util.Objects.hash(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, retentionSequence);
            return 31 * hash + java.util.Arrays.hashCode(responseData);
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
