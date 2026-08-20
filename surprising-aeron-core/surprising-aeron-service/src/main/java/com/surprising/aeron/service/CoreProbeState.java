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
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreReducer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeStateProjector;
import com.surprising.aeron.service.state.RuntimeStateParityChecker;
import com.surprising.aeron.service.state.RuntimeStateDeltaApplier;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.CoreLiquidationState;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.StateMapSupport;
import com.surprising.aeron.service.state.TerminalPruneBatch;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.BookBootstrapSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MATCHING_PENDING_WIRE_CODE = 66;
    private final ProductLine productLine;
    private final TradingCoreRuntime runtime;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private long commandResultBytes;
    private long nextResultRetentionSequence;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final LinkedHashMap<Long, PendingMatching> pendingMatching;
    private final LinkedHashMap<Long, List<LifecycleScope>> pendingLifecycleScopes;
    private final LinkedHashMap<Long, OrderBatchPending> pendingOrderBatches;
    private final List<CoreMessage> queuedMatching = new ArrayList<>();
    private final Map<Long, com.surprising.aeron.service.matching.CoreMatchingResult> completedMatching
            = new ConcurrentHashMap<>();
    private final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();
    private final TradingCoreReducer tradingReducer;
    private final DeterministicExchangeCoreAdapter matchingAdapter;
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
    private long appliedCommandCount;
    private long probeValue;
    private long cachedBusinessStateHash;
    private long lastSourceSequenceDigest;
    private long nextAsyncQueryId = Long.MIN_VALUE;
    private RuntimeIdentityRegistry runtimePlaceOrderIdentities;
    private TradingRuntimeState runtimePlaceOrderState;
    private TradingCoreState runtimePlaceOrderCoreState;
    private com.surprising.aeron.service.matching.FatalMatchingDivergenceException fatalFailure;
    private TradingCoreState tradingState;
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

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new CoreExportState(), new TerminalStateRetention(), null);
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.commandResultBytes = resultLedgerBytes(commandResults);
        this.nextResultRetentionSequence = nextRetentionSequence(commandResults);
        this.lastSourceSequences = lastSourceSequences;
        this.pendingMatching = new LinkedHashMap<>();
        this.pendingLifecycleScopes = new LinkedHashMap<>();
        this.pendingOrderBatches = new LinkedHashMap<>();
        this.tradingState = tradingState;
        this.rollingBusinessStateHash = com.surprising.aeron.service.state.RollingBusinessStateHash.create(tradingState);
        this.cachedBusinessStateHash = rollingBusinessStateHash.value();
        this.lastSourceSequenceDigest = sourceSequenceDigest(lastSourceSequences);
        this.exportState = exportState;
        this.terminalRetention = terminalRetention;
        this.runtime = matcherSnapshot == null
                ? new TradingCoreRuntime(productLine, tradingState)
                : new TradingCoreRuntime(productLine, tradingState, appliedCommandCount, matcherSnapshot);
        this.tradingReducer = runtime.reducerForConstruction();
        this.matchingAdapter = runtime.matcherForConstruction();
        this.positionUserIndex = runtime.positionUsersForConstruction();
        this.openInterestIndex = runtime.openInterestForConstruction();
        this.triggerOrderIndex = runtime.triggersForConstruction();
        this.algoOrderIndex = runtime.algosForConstruction();
        this.liquidationIndex = runtime.liquidationsForConstruction();
        this.cancelAllAfterIndex = runtime.timersForConstruction();
        this.activeOrderIndex = runtime.activeOrdersForConstruction();
        this.adlPositionIndex = runtime.adlPositionsForConstruction();
        this.riskSnapshotIndex = runtime.riskSnapshotsForConstruction();
        this.runtimePlaceOrderIdentities = new RuntimeIdentityRegistry();
        this.runtimePlaceOrderState = RuntimeStateProjector.project(tradingState, runtimePlaceOrderIdentities);
        this.runtimePlaceOrderCoreState = tradingState;
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                tradingState, exportState, new TerminalStateRetention(), null);
    }

    private static CoreProbeState restoreInternal(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot) {
        if (appliedCommandCount < 0 || commandResults == null || commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || lastSourceSequences == null || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || tradingState == null || tradingState.productLine() != productLine || exportState == null
                || terminalRetention == null) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        validateResultLedger(commandResults);
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                tradingState, exportState, terminalRetention, matcherSnapshot);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot) {
        if (matcherSnapshot == null || tradingState == null || tradingState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid paired matcher snapshot");
        }
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                tradingState, exportState, new TerminalStateRetention(), matcherSnapshot);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState,
            TerminalStateRetention terminalRetention,
            MatcherSnapshot matcherSnapshot) {
        if (matcherSnapshot == null || tradingState == null || tradingState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid paired matcher snapshot");
        }
        return restoreInternal(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                tradingState, exportState, terminalRetention, matcherSnapshot);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        assertHealthy();
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
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
                && message.header().messageType() == CoreMessageType.USER_STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                    tradingState.userStateHash(message.header().userId()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payload());
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
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                        tradingState.orderStateHash(TradingCommandCodec.decodeOrderStateQuery(message.payload())));
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
                return orderStateResponse(TradingCommandCodec.decodeOrderStateQuery(message.payload()));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CLIENT_ORDER_STATE_QUERY) {
            try {
                return orderStateResponse(tradingState.order(message.header().userId(),
                        CoreStateQueryCodec.decodeClientOrderStateQuery(message.payload())));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            try {
                var query = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payload());
                long beforeOrderId = query.beforeOrderId() == 0 ? Long.MAX_VALUE : query.beforeOrderId();
                long requestedUserId = message.header().userId();
                var orders = openOrders(requestedUserId)
                        .filter(order -> order.status().name().equals("OPEN"))
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> order.orderId() < beforeOrderId)
                        .limit(query.limit())
                        .map(CoreProbeState::orderView)
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
                var query = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payload());
                long before = query.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : query.beforeTriggerOrderId();
                java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> source = query.expiresBeforeEpochMillis() > 0
                        ? triggerOrderIndex.expired(query.expiresBeforeEpochMillis(), query.limit()).stream()
                        .map(tradingState.triggerOrders()::get)
                        .filter(java.util.Objects::nonNull)
                        : query.symbol().isEmpty()
                        ? (query.status() != null
                        ? triggerOrderIndex.ids(query.status())
                        : message.header().userId() == 0 ? triggerOrderIndex.ids() : triggerOrderIndex.ids(message.header().userId())).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull)
                        : (query.status() == null ? triggerOrderIndex.ids(query.symbol())
                        : triggerOrderIndex.ids(query.symbol(), query.status())).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull);
                var values = source
                        .filter(order -> message.header().userId() == 0 || order.userId() == message.header().userId())
                        .filter(order -> query.triggerOrderId() == 0 || order.triggerOrderId() == query.triggerOrderId())
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> query.status() == null || order.status() == query.status())
                        .filter(order -> query.triggerOrderId() != 0 || order.triggerOrderId() < before)
                        .filter(order -> message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                                || order.status().open())
                        .limit(query.limit())
                        .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
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
                int maxEvents = CoreExportCodec.decodeBatchQuery(message.payload());
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
            java.util.TreeSet<String> assets = new java.util.TreeSet<>();
            assets.addAll(tradingState.treasuryState().feeBalances().keySet());
            assets.addAll(tradingState.treasuryState().insuranceBalances().keySet());
            assets.addAll(tradingState.treasuryState().insuranceDeficits().keySet());
            var views = assets.stream().map(asset -> new com.surprising.aeron.protocol.CoreTreasuryAssetView(asset,
                    tradingState.treasuryState().feeBalances().getOrDefault(asset, 0L),
                    tradingState.treasuryState().insuranceBalances().getOrDefault(asset, 0L),
                    tradingState.treasuryState().insuranceDeficits().getOrDefault(asset, 0L))).toList();
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    CoreStateQueryCodec.encodeTreasuryState(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeFundingProgressQuery(message.payload());
                var treasury = tradingState.treasuryState();
                var progress = treasury.fundingProgress(symbol);
                long settledSettlementId = treasury.fundingSettlement(symbol);
                CoreFundingProgressView view = progress == null
                        ? new CoreFundingProgressView(settledSettlementId, true, 0, 0)
                        : new CoreFundingProgressView(progress.settlementId(), false,
                                progress.nextCursorUserId(), 0);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeSettlementProgressQuery(message.payload());
                var treasury = tradingState.treasuryState();
                var progress = treasury.lifecycleProgress(symbol);
                long settledSettlementId = treasury.lifecycleSettlement(symbol);
                CoreSettlementProgressView view = progress == null
                        ? new CoreSettlementProgressView(settledSettlementId, true, true, 0, 0, 0, 0)
                        : new CoreSettlementProgressView(progress.settlementId(), false, progress.ordersComplete(),
                                progress.nextCursorOrderId(), progress.nextCursorUserId(), 0, 0);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ADL_CANDIDATE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAdlQueryCodec.decodeQuery(message.payload());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAdlQueryCodec.encodeCandidates(
                                tradingReducer.adlCandidates(tradingState, query.asset(), query.limit(), adlPositionIndex)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            long requestedUserId = message.header().userId();
            var views = tradingReducer.riskSnapshots(tradingState, requestedUserId,
                    requestedUserId == 0 ? riskSnapshotIndex.keys() : riskSnapshotIndex.keys(requestedUserId));
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payload().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    CoreRiskScanControlCodec.encodeView(tradingState.riskState().scanControl()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.OPEN_INTEREST_QUERY) {
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
                var query = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payload());
                var algoIds = query.algoOrderId() != 0
                        ? List.of(query.algoOrderId())
                        : algoOrderIndex.query(query.userId(), query.symbol(), query.dueAtEpochMillis(),
                                query.limit(), tradingState.algoOrders());
                var values = algoIds.stream()
                        .map(tradingState.algoOrders()::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(value -> query.userId() == 0 || value.userId() == query.userId())
                        .filter(value -> query.symbol().isEmpty() || value.symbol().equalsIgnoreCase(query.symbol()))
                        .filter(value -> query.dueAtEpochMillis() == 0 || value.nextSliceAtEpochMillis() > 0
                                && value.nextSliceAtEpochMillis() <= query.dueAtEpochMillis())
                        .filter(value -> query.algoOrderId() == 0 || value.algoOrderId() == query.algoOrderId())
                        .map(this::algoView).toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payload());
                var values = cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                                query.limit(), tradingState.cancelAllAfterTimers()).stream()
                        .map(tradingState.cancelAllAfterTimers()::get)
                        .filter(java.util.Objects::nonNull)
                        .map(com.surprising.aeron.service.state.CoreCancelAllAfterState::view)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payload());
                if (query.productLine() != productLine) {
                    return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                var eligible = liquidationIndex.activeIds().tailSet(query.afterLiquidationId(), false).stream()
                        .map(tradingState.riskState().liquidations()::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(value -> switch (query.purpose()) {
                            case EXECUTION -> value.status()
                                    == com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED
                                    || value.status()
                                    == com.surprising.aeron.service.state.CoreLiquidationState.Status.ORDERED;
                            case INSURANCE -> value.status()
                                    == com.surprising.aeron.service.state.CoreLiquidationState.Status.INSURANCE_REQUIRED;
                            case ADL -> value.status()
                                    == com.surprising.aeron.service.state.CoreLiquidationState.Status.ADL_REQUIRED;
                        })
                        .filter(value -> {
                            if (query.purpose()
                                    == com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION) {
                                var mark = tradingState.riskState().markPrices().get(value.symbol());
                                return mark != null && mark.priceSequence() == value.triggerPriceSequence();
                            }
                            var instrument = tradingState.instruments().get(value.symbol());
                            return instrument != null && instrument.version() == value.instrumentVersion()
                                    && instrument.contractType().productLine() == productLine;
                        })
                        .toList();
                var scan = tradingState.riskState().scan();
                var continuation = query.purpose() == com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION
                        && !scan.riskComplete()
                        ? new com.surprising.aeron.protocol.CoreRiskScanContinuation(scan.symbol(),
                        scan.priceSequence(), scan.lastUserId()) : null;
                java.util.ArrayList<com.surprising.aeron.protocol.CoreLiquidationActionView> actions =
                        new java.util.ArrayList<>();
                java.util.ArrayList<com.surprising.aeron.protocol.CoreLiquidationWorkView.Resolution> resolutions =
                        new java.util.ArrayList<>();
                long nextCursor = query.afterLiquidationId();
                for (var value : eligible) {
                    if (actions.size() + resolutions.size() >= query.maxItems()) break;
                    com.surprising.aeron.protocol.CoreLiquidationActionView action = null;
                    com.surprising.aeron.protocol.CoreLiquidationWorkView.Resolution resolution = null;
                    if (query.purpose()
                            == com.surprising.aeron.protocol.CoreLiquidationWorkView.Purpose.EXECUTION) {
                            var mark = tradingState.riskState().markPrices().get(value.symbol());
                            action = new com.surprising.aeron.protocol.CoreLiquidationActionView(
                                    value.liquidationId(), value.userId(), value.symbol(), value.marginMode(),
                                    value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                                    value.signedQuantitySteps(), value.closeQuantitySteps(), mark.markPriceTicks(),
                                    value.status().name(), value.status()
                                            == com.surprising.aeron.service.state.CoreLiquidationState.Status.ORDERED
                                            ? value.nextCancelOrderId() : 0);
                    } else {
                        var instrument = tradingState.instruments().get(value.symbol());
                        resolution = new com.surprising.aeron.protocol.CoreLiquidationWorkView.Resolution(
                                value.liquidationId(), value.userId(), value.symbol(), instrument.settleAsset(),
                                value.marginMode(), value.positionSide(), value.instrumentVersion(),
                                value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(),
                                query.purpose());
                    }
                    if (action != null) actions.add(action);
                    if (resolution != null) resolutions.add(resolution);
                    long candidateCursor = value.liquidationId();
                    var candidate = new com.surprising.aeron.protocol.CoreLiquidationWorkView(productLine,
                            candidateCursor, false, continuation, actions, resolutions);
                    if (com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(candidate).length
                            > query.maxBytes()) {
                        if (action != null) actions.removeLast();
                        if (resolution != null) resolutions.removeLast();
                        break;
                    }
                    nextCursor = candidateCursor;
                }
                boolean complete = actions.size() + resolutions.size() == eligible.size();
                var work = new com.surprising.aeron.protocol.CoreLiquidationWorkView(productLine, nextCursor,
                        complete, continuation, actions, resolutions);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                var command = TradingCommandCodec.decodePlaceOrder(message.payload());
                requireOrderIdentityAvailable(message.header().userId(), command);
                TradingCoreState preview = tradingReducer.placeOrder(tradingState, message.header().userId(), command,
                        message.header().commandId(), openInterestIndex.openInterestSteps(command.symbol()),
                        activeOrderIndex);
                var reservation = preview.user(message.header().userId()).reservations().get(command.orderId());
                if (reservation == null) throw new IllegalStateException("preflight reservation is missing");
                var view = new com.surprising.aeron.protocol.CoreOrderPreflightView(
                        reservation.asset(), reservation.reservedUnits());
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
        if (exportCommand && message.payload().length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        commandTriggerOrderView = null;
        if (isMatchingCommand(message.header().messageType())) {
            if (isOrderBatchCommand(message.header().messageType())) {
                return beginOrderBatchMatching(message, clusterTimestamp, clusterPosition, sourceKey);
            }
            return beginMatching(message, clusterTimestamp, clusterPosition, sourceKey);
        }
        TradingCoreState beforeTradingState = tradingState;
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
        if (status != ResponseStatus.APPLIED && tradingState != beforeTradingState) {
            restoreCommandState(beforeTradingState);
            runtime.restoreStateOnly(beforeTradingState);
        }
        if (status == null) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (status == ResponseStatus.APPLIED) {
            cancelTriggersForClosedPositions(beforeTradingState);
            if (exportCommand && !exportState.hasCapacityFor(1 + queuedMatching.size())) {
                restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                queuedMatching.clear();
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        boolean tradingStateChanged = status == ResponseStatus.APPLIED && tradingState != beforeTradingState;
        if (status == ResponseStatus.APPLIED) {
            List<Long> changedOrderIds = commandChangedOrderIds == null ? List.of() : commandChangedOrderIds;
            try {
                adoptState(tradingState.stampOrderChanges(beforeTradingState, clusterTimestamp, clusterPosition,
                        changedOrderIds));
            } catch (IllegalStateException exception) {
                restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        try {
            commandDelta = commandDelta(beforeTradingState, tradingState, exportCommand);
        } catch (IllegalStateException exception) {
            if (tradingStateChanged) restoreCommandState(beforeTradingState);
            runtime.restoreStateOnly(beforeTradingState);
            return rejected(CoreResultCode.INVALID_COMMAND);
        }
        long businessStateHash = tradingStateChanged
                ? rollingBusinessStateHash.value() : cachedBusinessStateHash;
        long requiredExportSequence = 0;
        if (exportCommand) {
            try {
                requiredExportSequence = exportState.append(message, status, resultCode,
                        Math.incrementExact(appliedCommandCount),
                        businessStateHash, commandDelta.changedUsers(), commandDelta.changedOrders(), commandDelta.executions(),
                        commandDelta.fundingPayments(),
                        commandDelta.changedLiquidations(), commandDelta.changedTreasuryAssets(),
                        commandDelta.changedTriggerOrders());
                if (tradingStateChanged) {
                    terminalRetention.observe(beforeTradingState, tradingState, requiredExportSequence,
                            commandDelta.orderIds(), commandDelta.liquidationIds(), commandDelta.triggerOrderIds());
                }
            } catch (CoreStateRejectedException exception) {
                if (!"EXPORT_BACKLOG_FULL".equals(exception.code())) throw exception;
                if (tradingStateChanged) restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        appendQueuedMatching(businessStateHash);
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(businessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = message.header().messageType() == CoreMessageType.ACK_EXPORT
                && status == ResponseStatus.APPLIED
                ? CoreExportCodec.encodeStatus(exportState.status()) : commandResultData();
        storeResult(message.header().commandId(), new StoredResult(fingerprint, status, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, responseData, 0));
        return new CoreResponse(status, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData);
    }

    private void appendQueuedMatching(long businessStateHash) {
        if (queuedMatching.isEmpty()) return;
        for (CoreMessage command : queuedMatching) {
            long sequence = Math.incrementExact(appliedCommandCount);
            exportState.append(command, ResponseStatus.APPLIED, matchingPendingCode(), sequence,
                    businessStateHash, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            PendingMatching pending = newPendingMatching(sequence, PendingMatching.Operation.TRIGGER, command,
                    command.header().submittedAtEpochMillis());
            pendingMatching.put(sequence, pending);
            registerPendingLifecycle(pending);
            appliedCommandCount = sequence;
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
        long sequence = Math.incrementExact(appliedCommandCount);
        PendingMatching pending = newPendingMatching(sequence, batch.operation, message, clusterTimestamp);
        batch.sequence = sequence;
        pendingMatching.put(sequence, pending);
        registerPendingLifecycle(pending);
        pendingOrderBatches.put(sequence, batch);
        appliedCommandCount = sequence;
        recordSourceSequence(sourceKey, message.header().sourceSequence());
        long pendingStateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.OK, matchingPendingCode(), appliedCommandCount);
        storeResult(message.header().commandId(), new StoredResult(CommandFingerprint.of(message),
                ResponseStatus.OK, matchingPendingCode(), appliedCommandCount, 0, pendingStateHash,
                new byte[0], 0));
        CoreResponse completed = startOrderBatchItem(batch, pending, clusterTimestamp, clusterPosition);
        if (completed != null) return completed;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, 0, pendingStateHash, new byte[0]);
    }

    private OrderBatchPending decodeOrderBatch(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CoreMessageType type = message.header().messageType();
        if (type == CoreMessageType.PLACE_ORDER_BATCH) {
            PlaceOrderBatchCommand command = TradingOrderBatchCodec.decodePlaceOrderBatch(message.payload());
            return new OrderBatchPending(OrderBatchKind.PLACE, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    tradingState, clusterTimestamp, clusterPosition, PendingMatching.Operation.PLACE);
        }
        if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
            CancelOrderBatchCommand command = TradingOrderBatchCodec.decodeCancelOrderBatch(message.payload());
            return new OrderBatchPending(OrderBatchKind.CANCEL, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.orderId(), 0, 0, value)).toList(),
                    tradingState, clusterTimestamp, clusterPosition, PendingMatching.Operation.CANCEL);
        }
        if (type == CoreMessageType.AMEND_ORDER_BATCH) {
            AmendOrderBatchCommand command = TradingOrderBatchCodec.decodeAmendOrderBatch(message.payload());
            return new OrderBatchPending(OrderBatchKind.AMEND, command.orders().stream()
                    .map(value -> new OrderBatchItem(value.replacementOrderId(), value.originalOrderId(),
                            value.replacementOrderId(), value)).toList(),
                    tradingState, clusterTimestamp, clusterPosition, PendingMatching.Operation.AMEND);
        }
        throw new IllegalArgumentException("unsupported order batch type");
    }

    private void validateOrderBatchIdentity(OrderBatchPending batch, long userId) {
        if (batch.kind == OrderBatchKind.PLACE) return;
        for (OrderBatchItem item : batch.items) {
            long orderId = batch.kind == OrderBatchKind.CANCEL ? item.orderId : item.originalOrderId;
            CoreOrderState order = tradingState.order(orderId);
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
            TradingCoreState before = tradingState;
            try {
                prepareOrderBatchItem(batch, item, pending.command().header().userId(), pending.command().header().commandId());
                batch.currentBefore = before;
                submitMatching(pending);
                return null;
            } catch (CoreStateRejectedException exception) {
                if (tradingState != before) {
                    restoreCommandState(before);
                    runtime.restoreStateOnly(before);
                }
                appendOrderBatchResult(batch, item, ResponseStatus.REJECTED,
                        CoreResultCode.fromRejectionCode(exception.code()), List.of());
                batch.nextIndex++;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                if (tradingState != before) {
                    restoreCommandState(before);
                    runtime.restoreStateOnly(before);
                }
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
                adoptState(tradingReducer.placeOrder(tradingState, userId, command, commandId,
                        openInterestIndex.openInterestSteps(command.symbol()), activeOrderIndex));
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                CoreOrderState order = tradingState.order(command.orderId());
                if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                if (order.userId() != userId) {
                    throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                }
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                CoreOrderState order = tradingState.order(command.originalOrderId());
                if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
                if (order.userId() != userId) {
                    throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
                }
                if (order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN
                        || order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
                }
                if (tradingState.order(command.replacementOrderId()) != null) {
                    throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "replacement order already exists");
                }
            }
        }
    }

    private void submitOrderBatchMatching(PendingMatching pending) {
        OrderBatchPending batch = pendingOrderBatches.get(pending.sequence());
        if (batch == null) return;
        runtime.matcherReady().thenCompose(ignored -> submitOrderBatchMatchingNow(pending, batch))
                .whenComplete((result, failure) -> {
                    OrderBatchPending current = pendingOrderBatches.get(pending.sequence());
                    if (current != batch) return;
                    completedMatching.put(pending.sequence(), failure == null && result != null ? result
                            : new com.surprising.aeron.service.matching.CoreMatchingResult(
                                    false, "EXCHANGE_CORE_FAILURE", List.of()));
                });
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> submitOrderBatchMatchingNow(
            PendingMatching pending, OrderBatchPending batch) {
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        try {
            return switch (batch.kind) {
                case PLACE -> matchingAdapter.placeAsync(pending.command().header().userId(),
                        (PlaceOrderCommand) item.command);
                case CANCEL -> {
                    CancelOrderCommand command = (CancelOrderCommand) item.command;
                    CoreOrderState order = tradingState.order(command.orderId());
                    yield matchingAdapter.cancelAsyncForContinuation(pending.command().header().userId(),
                            command.orderId(), order == null ? "" : order.symbol());
                }
                case AMEND -> {
                    AmendOrderCommand command = (AmendOrderCommand) item.command;
                    CoreOrderState order = tradingState.order(command.originalOrderId());
                    yield matchingAdapter.replaceOrderAsync(pending.command().header().userId(),
                            command.originalOrderId(), order.symbol(), replacementForAmend(command, order));
                }
            };
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                    false, "EXCHANGE_CORE_FAILURE", List.of()));
        }
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
        OrderBatchItem item = batch.items.get(batch.nextIndex);
        TradingCoreState before = batch.currentBefore;
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            List<CoreExecutionView> executions = applyOrderBatchMatcherResult(batch, item, pending, matchingResult);
            if (tradingState != before) {
                adoptState(tradingState.stampOrderChanges(before, clusterTimestamp, clusterPosition,
                        batch.changedOrderIds(item, matchingResult)));
            }
            appendOrderBatchResult(batch, item, status, resultCode, executions);
            batch.nextIndex++;
        } catch (CoreStateRejectedException | ArithmeticException | IllegalArgumentException exception) {
            if (tradingState != before) restoreCommandState(before);
            runtime.restoreStateOnly(before);
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
                if (matchingResult.accepted()) {
                    adoptState(tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                            command.quoteAsset(), matchingResult.matches()));
                } else {
                    adoptState(tradingReducer.rejectPlaceOrder(tradingState,
                            pending.command().header().userId(), command.orderId()));
                }
                return executionViews(command.orderId(), pending.command().header().userId(), matchingResult.matches());
            }
            case CANCEL -> {
                CancelOrderCommand command = (CancelOrderCommand) item.command;
                if (matchingResult.accepted()) {
                    adoptState(tradingReducer.cancelOrder(tradingState, pending.command().header().userId(), command));
                }
                return List.of();
            }
            case AMEND -> {
                AmendOrderCommand command = (AmendOrderCommand) item.command;
                PlaceOrderCommand replacement = replacementForAmend(command,
                        tradingState.order(command.originalOrderId()));
                if (matchingResult.accepted()) {
                    adoptState(tradingReducer.cancelOrder(tradingState, pending.command().header().userId(),
                            new CancelOrderCommand(command.originalOrderId())));
                    requireOrderIdentityAvailable(pending.command().header().userId(), replacement);
                    adoptState(tradingReducer.placeOrder(tradingState, pending.command().header().userId(), replacement,
                            pending.command().header().commandId(), openInterestIndex.openInterestSteps(replacement.symbol()),
                            activeOrderIndex));
                    adoptState(tradingReducer.applyMatches(tradingState, replacement.orderId(), replacement.baseAsset(),
                            replacement.quoteAsset(), matchingResult.matches()));
                }
                return executionViews(replacement.orderId(), pending.command().header().userId(), matchingResult.matches());
            }
            default -> throw new IllegalStateException("unsupported order batch kind");
        }
    }

    private void appendOrderBatchResult(OrderBatchPending batch, OrderBatchItem item,
                                        ResponseStatus status, CoreResultCode resultCode,
                                        List<CoreExecutionView> executions) {
        CoreOrderState order = tradingState.order(item.orderId);
        if (order == null && item.originalOrderId > 0) order = tradingState.order(item.originalOrderId);
        batch.results.add(new CoreOrderBatchResult.Item(batch.results.size(), item.orderId,
                item.originalOrderId, item.replacementOrderId, status, resultCode,
                order == null ? null : orderView(order), executions));
    }

    private CoreResponse finishOrderBatch(OrderBatchPending batch, PendingMatching pending,
                                          long clusterTimestamp, long clusterPosition) {
        CoreOrderBatchResult result = new CoreOrderBatchResult(batch.results);
        byte[] responseData = TradingOrderBatchCodec.encodeResult(result);
        commandExecutions = batch.results.stream()
                .flatMap(item -> item.executions().stream())
                .toList();
        materializeChangeAccumulators();
        CoreCommandDelta delta = commandDelta(batch.beforeState, tradingState, true);
        long businessStateHash = tradingState == batch.beforeState
                ? cachedBusinessStateHash : rollingBusinessStateHash.value();
        long requiredExportSequence = exportState.append(pending.command(), ResponseStatus.APPLIED,
                CoreResultCode.NONE, appliedCommandCount, businessStateHash, delta.changedUsers(),
                delta.changedOrders(), delta.executions(), delta.fundingPayments(), delta.changedLiquidations(),
                delta.changedTreasuryAssets(), delta.changedTriggerOrders());
        if (tradingState != batch.beforeState) {
            terminalRetention.observe(batch.beforeState, tradingState, requiredExportSequence,
                    delta.orderIds(), delta.liquidationIds(), delta.triggerOrderIds());
        }
        cachedBusinessStateHash = businessStateHash;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(),
                ResponseStatus.APPLIED, CoreResultCode.NONE, appliedCommandCount);
        storeResult(pending.command().header().commandId(), new StoredResult(
                CommandFingerprint.of(pending.command()), ResponseStatus.APPLIED, CoreResultCode.NONE,
                appliedCommandCount, requiredExportSequence, stateHash, responseData, 0));
        removePendingMatching(batch.sequence);
        pendingOrderBatches.remove(batch.sequence);
        return new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                appliedCommandCount, requiredExportSequence, stateHash, responseData);
    }

    private void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, sourceSequence);
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, sourceSequence);
    }

    private static boolean isMatchingCommand(CoreMessageType type) {
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
                                               CoreMessage command, long timestamp) {
        return new PendingMatching(sequence, operation, command, attemptDeadline(timestamp));
    }

    private static long attemptDeadline(long timestamp) {
        long base = Math.max(0, timestamp);
        return base > Long.MAX_VALUE - PendingMatching.ATTEMPT_TIMEOUT_MILLIS
                ? Long.MAX_VALUE : base + PendingMatching.ATTEMPT_TIMEOUT_MILLIS;
    }

    private CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payload().length == 0) {
            throw new IllegalArgumentException("single-symbol book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payload());
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
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payload());
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
        long matchingStartNanos = System.nanoTime();
        int requiredExportCapacity = switch (message.header().messageType()) {
            case EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH, SETTLE_INSTRUMENT -> 3;
            default -> 2;
        };
        if (!exportState.hasCapacityFor(requiredExportCapacity)) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        PendingMatching.Operation operation = switch (message.header().messageType()) {
            case PLACE_ORDER -> PendingMatching.Operation.PLACE;
            case CANCEL_ORDER -> PendingMatching.Operation.CANCEL;
            case REPLACE_ORDER -> PendingMatching.Operation.REPLACE;
            case AMEND_ORDER -> PendingMatching.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> PendingMatching.Operation.LIQUIDATION;
            case EXECUTE_LIQUIDATION_BATCH -> PendingMatching.Operation.LIQUIDATION_BATCH;
            case SETTLE_INSTRUMENT -> PendingMatching.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
        try {
            rejectLifecycleOverlap(message, operation);
        } catch (CoreStateRejectedException exception) {
            return recordRejectedMatching(message, sourceKey, CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey, exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        TradingCoreState before = tradingState;
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
        try {
            switch (operation) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(message.payload());
                    requireOrderIdentityAvailable(message.header().userId(), command);
                    adoptState(tradingReducer.placeOrder(tradingState, message.header().userId(), command,
                            message.header().commandId(), openInterestIndex.openInterestSteps(command.symbol()),
                            activeOrderIndex));
                    commandChangedUserIds = List.of(message.header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    commandOrderViews = List.of(orderView(tradingState.order(command.orderId())));
                }
                case CANCEL -> validatePendingCancel(message);
                case REPLACE -> validatePendingReplace(message, false);
                case AMEND -> validatePendingReplace(message, true);
                case LIQUIDATION -> validatePendingLiquidation(message);
                case LIQUIDATION_BATCH -> validatePendingLiquidationBatch(message);
                case SETTLEMENT -> validatePendingSettlement(message);
            }
        } catch (CoreStateRejectedException exception) {
            return recordRejectedMatching(message, sourceKey, CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey, exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        boolean tradingStateChanged = tradingState != before;
        if (tradingStateChanged) {
            try {
                adoptState(tradingState.stampOrderChanges(before, clusterTimestamp, clusterPosition,
                        commandChangedOrderIds));
            } catch (RuntimeException exception) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
                throw exception;
            }
        }
        try {
            commandDelta = commandDelta(before, tradingState, true);
        } catch (RuntimeException exception) {
            if (tradingStateChanged) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
            }
            return rejected(CoreResultCode.INVALID_COMMAND);
        }
        long businessStateHash = tradingStateChanged ? rollingBusinessStateHash.value() : cachedBusinessStateHash;
        long sequence = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence;
        try {
            requiredExportSequence = exportState.append(message, ResponseStatus.APPLIED, matchingPendingCode(), sequence,
                    businessStateHash, commandDelta.changedUsers(), commandDelta.changedOrders(),
                    commandDelta.executions(), commandDelta.fundingPayments(), commandDelta.changedLiquidations(),
                    commandDelta.changedTreasuryAssets(), commandDelta.changedTriggerOrders());
            if (tradingStateChanged) {
                terminalRetention.observe(before, tradingState, requiredExportSequence,
                        commandDelta.orderIds(), commandDelta.liquidationIds(), commandDelta.triggerOrderIds());
            }
        } catch (CoreStateRejectedException exception) {
            if (tradingStateChanged) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
            }
            return rejected(CoreResultCode.fromRejectionCode(exception.code()));
        }
        PendingMatching pending = newPendingMatching(sequence, operation, message, clusterTimestamp);
        pendingMatching.put(sequence, pending);
        registerPendingLifecycle(pending);
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = sequence;
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(businessStateHash, message.header().commandId(), ResponseStatus.OK,
                matchingPendingCode(), appliedCommandCount);
        byte[] responseData = commandResultData();
        storeResult(message.header().commandId(), new StoredResult(CommandFingerprint.of(message), ResponseStatus.OK,
                matchingPendingCode(), appliedCommandCount, requiredExportSequence, stateHash, responseData, 0));
        matchingPhaseMetrics.recordPrepare(System.nanoTime() - matchingStartNanos);
        submitMatching(pending);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, requiredExportSequence, stateHash, responseData);
    }

    private CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CoreResultCode resultCode) {
        long sequence = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence = exportState.append(message, ResponseStatus.REJECTED, resultCode, sequence,
                cachedBusinessStateHash, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        appliedCommandCount = sequence;
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
        var command = TradingCommandCodec.decodeCancelOrder(message.payload());
        var order = tradingState.order(command.orderId());
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
            originalOrderId = TradingCommandCodec.decodeAmendOrder(message.payload()).originalOrderId();
        } else {
            originalOrderId = TradingCommandCodec.decodeReplaceOrder(message.payload()).originalOrderId();
        }
        var order = tradingState.order(originalOrderId);
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
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(originalOrderId);
    }

    private void validatePendingLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidation(message.payload());
        var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
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
        LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
                command.cursorOrderId(), command.maxOrders());
        commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        commandChangedLiquidationIds = List.of(liquidation.liquidationId());
    }

    private void validatePendingLiquidationBatch(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidationBatch(message.payload());
        if (command.riskScanContinuation() != null) {
            var control = tradingState.riskState().scanControl();
            if (!control.enabled() || command.maxRiskScanUsers() > control.scanBatchSize()) {
                throw new CoreStateRejectedException("INVALID_COMMAND",
                        "risk scan continuation exceeds current control");
            }
            var scan = tradingState.riskState().scan();
            var continuation = command.riskScanContinuation();
            if (scan.riskComplete() || !scan.symbol().equals(continuation.symbol())
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
            var liquidation = tradingState.riskState().liquidations().get(action.liquidationId());
            changedLiquidations.add(action.liquidationId());
            if (liquidation == null || liquidation.status() == CoreLiquidationState.Status.COMPLETED
                    || liquidation.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.ADL_REQUIRED
                    || liquidation.status() == CoreLiquidationState.Status.CANCELED) continue;
            if (liquidation.userId() != action.userId() || !liquidation.symbol().equals(action.symbol())
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
            if (!scopes.add(liquidation.userId() + "\u0000" + liquidation.symbol())) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "liquidation batch contains overlapping scopes");
            }
            ensureLifecycleScopeAvailable(new LifecycleScope(false, liquidation.userId(), liquidation.symbol(),
                    liquidation.liquidationId(), true, false));
            changedUsers.add(liquidation.userId());
            if (remaining > 0) {
                LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
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
        var command = TradingCommandCodec.decodeSettleInstrument(message.payload());
        var progress = tradingState.treasuryState().lifecycleProgress(command.symbol());
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
        if (tradingState.treasuryState().lifecycleProgress(candidate.symbol()) != null
                && candidate.orderChanging()) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                    "settlement lifecycle is in progress");
        }
        if (candidate.lifecycle()) {
            boolean settlementActive = tradingState.treasuryState().lifecycleProgress(candidate.symbol()) != null;
            boolean liquidationActive = tradingState.riskState().liquidations().values().stream()
                    .filter(value -> value.status() == CoreLiquidationState.Status.PLANNED
                            || value.status() == CoreLiquidationState.Status.ORDERED)
                    .filter(value -> candidate.lifecycleId() == 0
                            || value.liquidationId() != candidate.lifecycleId())
                    .anyMatch(value -> conflicts(candidate, new LifecycleScope(false, value.userId(), value.symbol(),
                            value.liquidationId(), true, false)));
            if (settlementActive || liquidationActive) {
                throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS",
                        "matching lifecycle scope is in progress");
            }
        }
    }

    private boolean pendingLifecycleConflicts(LifecycleScope candidate, PendingMatching pending) {
        if (pending.operation() != PendingMatching.Operation.LIQUIDATION_BATCH) {
            return conflicts(candidate, lifecycleScope(pending));
        }
        var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload());
        return batch.actions().stream().map(action -> new LifecycleScope(false, action.userId(), action.symbol(),
                        action.liquidationId(), true, false)).anyMatch(scope -> conflicts(candidate, scope));
    }

    private void registerPendingLifecycle(PendingMatching pending) {
        List<LifecycleScope> scopes = switch (pending.operation()) {
            case LIQUIDATION, SETTLEMENT -> List.of(lifecycleScope(pending));
            case LIQUIDATION_BATCH -> TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload())
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
        return pendingMatching.remove(sequence);
    }

    private LifecycleScope lifecycleScope(CoreMessage message, PendingMatching.Operation operation) {
        return switch (operation) {
            case LIQUIDATION -> {
                var command = TradingCommandCodec.decodeExecuteLiquidation(message.payload());
                var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
                yield liquidation == null ? new LifecycleScope(false, 0, "", 0, true, false)
                        : new LifecycleScope(false, liquidation.userId(), liquidation.symbol(),
                                liquidation.liquidationId(), true, false);
            }
            case SETTLEMENT -> new LifecycleScope(true, 0,
                    TradingCommandCodec.decodeSettleInstrument(message.payload()).symbol(), 0, true, false);
            default -> new LifecycleScope(false, message.header().userId(), matchingSymbol(message, operation),
                    0, false, true);
        };
    }

    private LifecycleScope lifecycleScope(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var action = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload()).actions().getFirst();
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
            case PLACE -> TradingCommandCodec.decodePlaceOrder(message.payload()).symbol();
            case CANCEL -> {
                var command = TradingCommandCodec.decodeCancelOrder(message.payload());
                var order = tradingState.order(command.orderId());
                yield order == null ? "" : order.symbol();
            }
            case REPLACE, AMEND -> {
                long orderId = operation == PendingMatching.Operation.REPLACE
                        ? TradingCommandCodec.decodeReplaceOrder(message.payload()).originalOrderId()
                        : TradingCommandCodec.decodeAmendOrder(message.payload()).originalOrderId();
                var order = tradingState.order(orderId);
                yield order == null ? "" : order.symbol();
            }
            case TRIGGER -> {
                long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payload());
                var trigger = tradingState.triggerOrders().get(execute[0]);
                yield trigger == null ? "" : trigger.symbol();
            }
            case LIQUIDATION, SETTLEMENT -> "";
            case LIQUIDATION_BATCH -> {
                var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(message.payload());
                yield batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
            }
        };
    }

    private String pendingLifecycleSymbol(PendingMatching pending) {
        if (pending.operation() == PendingMatching.Operation.SETTLEMENT) {
            return TradingCommandCodec.decodeSettleInstrument(pending.command().payload()).symbol();
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH) {
            var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload());
            return batch.actions().isEmpty() ? "" : batch.actions().getFirst().symbol();
        }
        var liquidation = tradingState.riskState().liquidations().get(
                TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload()).liquidationId());
        return liquidation == null ? "" : liquidation.symbol();
    }

    private void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = tradingState.treasuryState().lifecycleProgress(command.symbol());
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
                .map(tradingState::order)
                .filter(order -> order != null
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .toList();
        return new LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    private List<CoreOrderState> batchCancellationOrders(PendingMatching pending) {
        var command = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload());
        List<CoreOrderState> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            if (remaining == 0) break;
            var liquidation = tradingState.riskState().liquidations().get(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
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

    private TradingCoreState executeLiquidationWithShadow(TradingCoreState before,
                                                           com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
                                                           boolean afterCancellation) {
        return afterCancellation
                ? tradingReducer.executeLiquidationAfterCancellation(before, command)
                : tradingReducer.executeLiquidation(before, command);
    }

    private TradingCoreState executeLiquidationAfterCancellationsWithShadow(
            TradingCoreState before, TradingCoreState canceled,
            com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders) {
        return tradingReducer.executeLiquidationAfterCancellation(canceled, command);
    }

    private TradingCoreState advanceLiquidationCancellationWithShadow(
            TradingCoreState before, com.surprising.aeron.protocol.ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId) {
        return tradingReducer.advanceLiquidationCancellation(
                before, command, canceledOrders, nextCursorOrderId);
    }

    private TradingCoreState resolveLiquidationWithShadow(TradingCoreState before,
                                                           com.surprising.aeron.protocol.ResolveLiquidationCommand command) {
        return tradingReducer.resolveLiquidation(before, command);
    }

    private TradingCoreState executeAdlWithShadow(TradingCoreState before,
                                                  com.surprising.aeron.protocol.ExecuteAdlCommand command) {
        return tradingReducer.executeAdl(before, command);
    }

    private void submitMatching(PendingMatching pending) {
        if (BENCHMARK_SKIP_MATCHING_SUBMIT) {
            completedMatching.put(pending.sequence(),
                    new com.surprising.aeron.service.matching.CoreMatchingResult(true, "BENCHMARK_SKIPPED", List.of()));
            return;
        }
        if (pendingOrderBatches.containsKey(pending.sequence())) {
            submitOrderBatchMatching(pending);
            return;
        }
        runtime.matcherReady().thenCompose(ignored -> submitMatchingNow(pending))
                .whenComplete((result, failure) -> {
                    PendingMatching current = pendingMatching.get(pending.sequence());
                    if (current != pending) return;
                    completedMatching.put(pending.sequence(),
                            failure == null && result != null ? result
                                    : new com.surprising.aeron.service.matching.CoreMatchingResult(false,
                                    "EXCHANGE_CORE_FAILURE", List.of()));
                });
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> submitMatchingNow(
            PendingMatching pending) {
        matchingSubmitNanos.put(pending.sequence(), System.nanoTime());
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future;
        try {
            future = switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payload());
                    yield matchingAdapter.placeAsync(pending.command().header().userId(), command);
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payload());
                    var order = tradingState.order(command.orderId());
                    yield matchingAdapter.cancelAsyncForContinuation(pending.command().header().userId(),
                            command.orderId(), order == null ? "" : order.symbol());
                }
                case REPLACE, AMEND -> {
                    var originalId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId();
                    var order = tradingState.order(originalId);
                    var replacement = replacementFor(pending.command(), order);
                    yield matchingAdapter.replaceOrderAsync(pending.command().header().userId(), originalId,
                            order.symbol(), replacement);
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payload());
                    var trigger = tradingState.triggerOrders().get(execute[0]);
                    if (trigger == null) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                false, "TRIGGER_ORDER_NOT_FOUND", List.of()));
                    }
                    yield matchingAdapter.placeAsync(trigger.userId(), triggerPlacement(trigger, execute[2]));
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload());
                    var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
                    if (liquidation == null || !tradingReducer.isLiquidationExecutable(tradingState, command)) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                true, "SUCCESS", List.of()));
                    }
                    yield matchingAdapter.cancelBatchAsync(lifecycleOrders(liquidation.userId(), liquidation.symbol(),
                            command.cursorOrderId(), command.maxOrders()).orders());
                }
                case LIQUIDATION_BATCH -> matchingAdapter.cancelBatchAsync(batchCancellationOrders(pending));
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payload());
                    var progress = tradingState.treasuryState().lifecycleProgress(command.symbol());
                    if (progress != null && progress.ordersComplete()) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                true, "SUCCESS", List.of()));
                    }
                    yield matchingAdapter.cancelBatchAsync(lifecycleOrders(0, command.symbol(), command.cursorOrderId(),
                            command.maxOrders()).orders());
                }
            };
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(false,
                    "EXCHANGE_CORE_FAILURE", List.of()));
        }
        return future;
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message,
                                                                            com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        var instrument = tradingState.instruments().get(order.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        if (message.header().messageType() == CoreMessageType.REPLACE_ORDER) {
            return TradingCommandCodec.decodeReplaceOrder(message.payload()).replacement();
        }
        var command = TradingCommandCodec.decodeAmendOrder(message.payload());
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new com.surprising.aeron.protocol.PlaceOrderCommand(command.replacementOrderId(), order.symbol(),
                order.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(),
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(), order.positionSide(),
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                                ? instrument.quoteAsset() : instrument.baseAsset()
                        : instrument.settleAsset(), 0, order.orderType(), timeInForce, priceTicks, postOnly,
                clientOrderId, order.makerFeeRatePpm(), order.takerFeeRatePpm());
    }

    private PlaceOrderCommand replacementForAmend(AmendOrderCommand command, CoreOrderState order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        var instrument = tradingState.instruments().get(order.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), order.symbol(), order.instrumentVersion(),
                instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(), order.side(), priceTicks,
                quantitySteps, order.reduceOnly(), order.marginMode(), order.positionSide(),
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                                ? instrument.quoteAsset() : instrument.baseAsset()
                        : instrument.settleAsset(), 0, order.orderType(), timeInForce, priceTicks, postOnly,
                clientOrderId, order.makerFeeRatePpm(), order.takerFeeRatePpm());
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long triggeredPriceTicks) {
        var instrument = tradingState.instruments().get(trigger.symbol());
        var order = tradingState.order(trigger.userId(), "TRIGGER:" + trigger.triggerOrderId());
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND",
                        "trigger child order not found");
        }
        boolean spot = instrument != null
                && instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        return new com.surprising.aeron.protocol.PlaceOrderCommand(order.orderId(), trigger.symbol(),
                trigger.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(),
                trigger.side(), order.priceTicks(), order.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                spot ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                spot && trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                        ? instrument.quoteAsset() : spot ? instrument.baseAsset() : instrument.settleAsset(), 0,
                trigger.orderType(), trigger.timeInForce(), order.priceTicks() > 0 ? order.priceTicks() : triggeredPriceTicks,
                false, order.clientOrderId(), trigger.makerFeeRatePpm(), trigger.takerFeeRatePpm());
    }

    public com.surprising.aeron.service.matching.CoreMatchingResult takeMatchingResult(long sequence) {
        if (pendingMatching.isEmpty() || pendingMatching.keySet().iterator().next() != sequence) return null;
        return completedMatching.remove(sequence);
    }

    public boolean markMatchingTimeout(long sequence, long clusterTimestamp) {
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null || pending.attemptDeadline() == Long.MAX_VALUE
                || clusterTimestamp < pending.attemptDeadline()) return false;
        completedMatching.putIfAbsent(sequence,
                new com.surprising.aeron.service.matching.CoreMatchingResult(false,
                        "MATCHING_TIMEOUT", List.of()));
        return true;
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        runtime.assertOwner();
        return runtime.matcherReady().thenCompose(ignored -> matchingAdapter.orderBooksStateHashAsync());
    }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        assertHealthy();
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null || matchingResult == null) return null;
        Long submitNanos = matchingSubmitNanos.remove(sequence);
        if (submitNanos != null) {
            matchingPhaseMetrics.recordExchange(System.nanoTime() - submitNanos);
        }
        long applyStartNanos = System.nanoTime();
        if (pendingOrderBatches.containsKey(sequence)) {
            return completeOrderBatchMatching(sequence, matchingResult, clusterTimestamp, clusterPosition);
        }
        if (matchingResultNeedsRecovery(pending, matchingResult)) {
            throw failMatching(pending, "matcher continuation returned " + matchingResult.resultCode(), null);
        }
        TradingCoreState before = tradingState;
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
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payload());
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(pending.command().header().userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    adoptState(matchingResult.accepted()
                            ? tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                            command.quoteAsset(), matchingResult.matches())
                            : tradingReducer.rejectPlaceOrder(tradingState, pending.command().header().userId(),
                            command.orderId()));
                    commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                            matchingResult.matches());
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payload());
                    commandChangedUserIds = List.of(pending.command().header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.cancelOrder(tradingState,
                                pending.command().header().userId(), command));
                    }
                }
                case REPLACE, AMEND -> {
                    var command = replacementFor(pending.command(), tradingState.order(
                            pending.operation() == PendingMatching.Operation.REPLACE
                                    ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                                    : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId()));
                    long originalOrderId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId();
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(pending.command().header().userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(originalOrderId, command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.cancelOrder(tradingState,
                                pending.command().header().userId(), new com.surprising.aeron.protocol.CancelOrderCommand(originalOrderId)));
                        requireOrderIdentityAvailable(pending.command().header().userId(), command);
                        adoptState(tradingReducer.placeOrder(tradingState, pending.command().header().userId(), command,
                                pending.command().header().commandId(), openInterestIndex.openInterestSteps(command.symbol()),
                                activeOrderIndex));
                        adoptState(tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                                command.quoteAsset(), matchingResult.matches()));
                        commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                                matchingResult.matches());
                    }
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payload());
                    var trigger = tradingState.triggerOrders().get(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    var command = triggerPlacement(trigger, execute[2]);
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(trigger.userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                                command.quoteAsset(), matchingResult.matches()));
                    } else {
                        adoptState(tradingReducer.rejectPlaceOrder(tradingState, trigger.userId(), command.orderId()));
                    }
                    adoptState(tradingReducer.completeTriggerOrder(tradingState, trigger.triggerOrderId(),
                            matchingResult.accepted(), matchingResult.accepted() ? command.orderId() : 0,
                            matchingResult.accepted() ? "" : matchingResult.resultCode(), execute[3]));
                    commandExecutions = executionViews(command.orderId(), trigger.userId(), matchingResult.matches());
                    commandOrderViews = commandChangedOrderIds.stream().map(tradingState::order)
                            .filter(java.util.Objects::nonNull).map(CoreProbeState::orderView).toList();
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload());
                    var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
                    commandChangedLiquidationIds = List.of(command.liquidationId());
                    LifecycleOrderChunk chunk = liquidation == null ? new LifecycleOrderChunk(List.of(), 0)
                            : lifecycleOrders(liquidation.userId(), liquidation.symbol(), command.cursorOrderId(),
                            command.maxOrders());
                    commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
                    commandChangedUserIds = liquidation == null ? List.of() : List.of(liquidation.userId());
                    if (matchingResult.accepted() && liquidation != null) {
                        if (!tradingReducer.isLiquidationExecutable(tradingState, command)) {
                            adoptState(executeLiquidationWithShadow(tradingState, command, false));
                            commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
                        } else if (chunk.more()) {
                            adoptState(advanceLiquidationCancellationWithShadow(tradingState, command,
                                    chunk.orders(), chunk.nextCursorOrderId()));
                            commandLiquidationProgress = new CoreLiquidationProgressView(false,
                                    chunk.nextCursorOrderId(), chunk.orders().size());
                        } else {
                            TradingCoreState canceled = tradingReducer.cancelLifecycleOrders(tradingState,
                                    chunk.orders());
                            adoptState(executeLiquidationAfterCancellationsWithShadow(
                                    tradingState, canceled, command, chunk.orders()));
                            commandLiquidationProgress = new CoreLiquidationProgressView(true, 0,
                                    chunk.orders().size());
                        }
                    }
                }
                case LIQUIDATION_BATCH -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload());
                    applyLiquidationBatch(command, matchingResult);
                }
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payload());
                    if (matchingResult.accepted()) {
                        applySettlementChangedIds(command);
                        var settlement = tradingReducer.settleInstrumentWithProgress(tradingState, command,
                                positionUserIndex.users(command.symbol()), pending.command().header().commandId(), activeOrderIndex);
                        adoptState(settlement.state());
                        commandSettlementProgress = settlement.progress();
                    }
                }
            }
        } catch (CoreStateRejectedException exception) {
            restoreCommandState(before);
            runtime.restoreStateOnly(before);
            throw failMatching(pending, "Core rejected an accepted matcher result", exception);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            restoreCommandState(before);
            runtime.restoreStateOnly(before);
            throw failMatching(pending, "Core and matcher state diverged", exception);
        }
        materializeChangeAccumulators();
        if (commandOrderViews.isEmpty() && !commandChangedOrderIds.isEmpty()) {
            commandOrderViews = commandChangedOrderIds.stream()
                    .map(tradingState::order)
                    .filter(java.util.Objects::nonNull)
                    .map(CoreProbeState::orderView)
                    .toList();
        }
        if (tradingState != before) {
            adoptState(tradingState.stampOrderChanges(before, clusterTimestamp, clusterPosition,
                    commandChangedOrderIds));
        }
        commandDelta = commandDelta(before, tradingState, true);
        matchingPhaseMetrics.recordApply(System.nanoTime() - applyStartNanos);
        completedMatchingCount++;
        if (MATCHING_PHASE_LOG_INTERVAL > 0 && completedMatchingCount % MATCHING_PHASE_LOG_INTERVAL == 0) {
            LOG.log(System.Logger.Level.INFO, "matching phases count=" + completedMatchingCount + " "
                    + matchingPhaseMetrics.reportAndReset());
        }
        long businessStateHash = tradingState == before ? cachedBusinessStateHash : rollingBusinessStateHash.value();
        long applied = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence = exportState.append(pending.command(), status, resultCode, applied, businessStateHash,
                commandDelta.changedUsers(), commandDelta.changedOrders(), commandDelta.executions(),
                commandDelta.fundingPayments(), commandDelta.changedLiquidations(), commandDelta.changedTreasuryAssets(),
                commandDelta.changedTriggerOrders());
        if (tradingState != before) {
            terminalRetention.observe(before, tradingState, requiredExportSequence,
                    commandDelta.orderIds(), commandDelta.liquidationIds(), commandDelta.triggerOrderIds());
        }
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = applied;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(), status, resultCode, applied);
        byte[] responseData = commandResultData();
        storeResult(pending.command().header().commandId(), new StoredResult(CommandFingerprint.of(pending.command()),
                status, resultCode, applied, requiredExportSequence, stateHash, responseData, 0));
        removePendingMatching(sequence);
        return new CoreResponse(status, status, resultCode, applied, requiredExportSequence, stateHash, responseData);
    }

    private void applyLiquidationBatch(
            com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand batch,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (!matchingResult.accepted()) {
            int obsolete = batch.actions().stream()
                    .map(action -> tradingState.riskState().liquidations().get(action.liquidationId()))
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
            var liquidation = tradingState.riskState().liquidations().get(action.liquidationId());
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
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
                    action.cursorOrderId(), remaining);
            changedOrders.addAll(chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList());
            changedUsers.add(liquidation.userId());
            if (!tradingReducer.isLiquidationExecutable(tradingState, single)) {
                adoptState(executeLiquidationWithShadow(tradingState, single, false));
                applied++;
                continue;
            }
            processedOrders += chunk.orders().size();
            remaining -= chunk.orders().size();
            if (chunk.more()) {
                adoptState(advanceLiquidationCancellationWithShadow(tradingState, single,
                        chunk.orders(), chunk.nextCursorOrderId()));
                pending++;
                continue;
            }
            TradingCoreState canceled = tradingReducer.cancelLifecycleOrders(tradingState, chunk.orders());
            adoptState(executeLiquidationAfterCancellationsWithShadow(
                    tradingState, canceled, single, chunk.orders()));
            applied++;
        }
        commandChangedOrderIds = changedOrders.stream().distinct().toList();
        commandChangedUserIds = changedUsers.stream().distinct().toList();
        commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied, pending,
                obsolete, processedOrders, 0);
        if (batch.riskScanContinuation() != null) {
            var scan = tradingState.riskState().scan();
            if (!scan.symbol().equals(batch.riskScanContinuation().symbol())
                    || scan.priceSequence() != batch.riskScanContinuation().priceSequence()
                    || scan.lastUserId() != batch.riskScanContinuation().lastUserId()) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "risk scan cursor does not match state");
            }
            adoptState(tradingReducer.continueRiskScan(tradingState, batch.maxRiskScanUsers(), positionUserIndex,
                    liquidationIndex));
            commandLiquidationBatchResult = new CoreLiquidationBatchResultView(batch.actions().size(), applied,
                    pending, obsolete, processedOrders, batch.maxRiskScanUsers());
        }
    }

    private PendingMatching applyBatchSuccessfulPrefix(PendingMatching pending,
                                                        com.surprising.aeron.service.matching.CoreMatchingResult result,
                                                        long clusterTimestamp, long clusterPosition) {
        var batch = TradingCommandCodec.decodeExecuteLiquidationBatch(pending.command().payload());
        int successful = result.successfulPrefixCount();
        int remaining = batch.maxCancelOrders();
        int successLeft = successful;
        List<ExecuteLiquidationBatchAction> nextActions = new ArrayList<>(batch.actions());
        List<Long> changedOrders = new ArrayList<>();
        List<Long> changedUsers = new ArrayList<>();
        int actionIndex = 0;
        for (var action : batch.actions()) {
            if (remaining == 0 || successLeft == 0) break;
            var liquidation = tradingState.riskState().liquidations().get(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) {
                actionIndex++;
                continue;
            }
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
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
                adoptState(advanceLiquidationCancellationWithShadow(tradingState, single, prefix, nextCursor));
            } else {
                TradingCoreState canceled = tradingReducer.cancelLifecycleOrders(tradingState, prefix);
                adoptState(executeLiquidationAfterCancellationsWithShadow(
                        tradingState, canceled, single, prefix));
            }
            var next = tradingState.riskState().liquidations().get(action.liquidationId());
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
        var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload());
        var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null || !tradingReducer.isLiquidationExecutable(tradingState, command)) return pending;
        LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), liquidation.symbol(),
                command.cursorOrderId(), command.maxOrders());
        int count = Math.min(result.successfulPrefixCount(), chunk.orders().size());
        if (count == 0) return pending;
        List<CoreOrderState> prefix = chunk.orders().subList(0, count);
        commandChangedUserIds = List.of(liquidation.userId());
        commandChangedOrderIds = prefix.stream().mapToLong(CoreOrderState::orderId).boxed().toList();
        if (chunk.more() || count < chunk.orders().size()) {
            long nextCursor = count < chunk.orders().size() ? prefix.getLast().orderId() : chunk.nextCursorOrderId();
            adoptState(advanceLiquidationCancellationWithShadow(tradingState, command, prefix, nextCursor));
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                            command.liquidationId(), command.triggerPriceSequence(), command.executionPriceTicks(),
                            command.liquidationFeeRatePpm(), nextCursor, command.maxOrders()))));
        }
        TradingCoreState canceled = tradingReducer.cancelLifecycleOrders(tradingState, prefix);
        adoptState(executeLiquidationAfterCancellationsWithShadow(
                tradingState, canceled, command, prefix));
        return pending;
    }

    private PendingMatching applySettlementSuccessfulPrefix(PendingMatching pending,
                                                              com.surprising.aeron.service.matching.CoreMatchingResult result) {
        var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payload());
        if (tradingState.treasuryState().lifecycleProgress(command.symbol()) != null
                && tradingState.treasuryState().lifecycleProgress(command.symbol()).ordersComplete()) {
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
            adoptState(tradingReducer.advanceSettlementOrderCancellation(tradingState, command, prefix, nextCursor,
                    pending.command().header().commandId()));
            return pending.withCommand(new CoreMessage(pending.command().header(),
                    TradingCommandCodec.encodeSettleInstrument(new com.surprising.aeron.protocol.SettleInstrumentCommand(
                            command.settlementId(), command.symbol(), command.instrumentVersion(),
                            command.settlementPriceTicks(), command.optionCashUnitsPerContract(), command.cursorUserId(),
                            command.maxUsers(), nextCursor, command.maxOrders()))));
        }
        TradingCoreState canceled = tradingReducer.cancelLifecycleOrders(tradingState, prefix);
        var settlement = tradingReducer.settleInstrumentWithProgress(canceled, command,
                positionUserIndex.users(command.symbol()), pending.command().header().commandId(), activeOrderIndex);
        adoptState(settlement.state());
        commandSettlementProgress = settlement.progress();
        return pending;
    }

    private boolean matchingResultNeedsRecovery(PendingMatching pending,
                                                com.surprising.aeron.service.matching.CoreMatchingResult result) {
        if (result.accepted()) return false;
        if (result.matcherStateChanged()) return true;
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT) return true;
        return "EXCHANGE_CORE_FAILURE".equals(result.resultCode())
                || "MATCHING_TIMEOUT".equals(result.resultCode());
    }

    boolean isMatchingPending(UUID commandId) {
        return pendingMatching.values().stream().anyMatch(value -> value.command().header().commandId().equals(commandId));
    }

    long matchingSequence(UUID commandId) {
        return pendingMatching.values().stream()
                .filter(value -> value.command().header().commandId().equals(commandId))
                .mapToLong(PendingMatching::sequence).findFirst().orElse(0);
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

    public long firstPendingMatchingSequence() {
        return pendingMatching.isEmpty() ? 0 : pendingMatching.keySet().iterator().next();
    }


    Map<Long, PendingMatching> pendingMatching() {
        return Collections.unmodifiableMap(pendingMatching);
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
        for (Map.Entry<UUID, StoredResult> entry : commandResults.entrySet()) {
            hash = mix(hash, entry.getKey().getMostSignificantBits());
            hash = mix(hash, entry.getKey().getLeastSignificantBits());
            for (byte value : entry.getValue().fingerprint().bytes()) {
                hash = mix(hash, Byte.toUnsignedInt(value));
            }
            hash = mix(hash, entry.getValue().status().wireCode());
            hash = mix(hash, entry.getValue().resultCode().wireCode());
            hash = mix(hash, entry.getValue().appliedCommandCount());
            hash = mix(hash, entry.getValue().requiredExportSequence());
            hash = mix(hash, entry.getValue().retentionSequence());
            for (byte value : entry.getValue().responseData()) {
                hash = mix(hash, Byte.toUnsignedInt(value));
            }
        }
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
        if (!pendingMatching.isEmpty()) {
            throw new IllegalStateException("cannot snapshot while matching commands are pending");
        }
        MatcherSnapshot matcherSnapshot = matchingAdapter
                .snapshotAsync(snapshotId, appliedCommandCount, tradingState, activeOrderIndex.orders())
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .join();
        return CoreStateSnapshotCodec.encode(this, matcherSnapshot);
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

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        return tradingState;
    }

    CoreExportState exportState() {
        return exportState;
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
                    probeValue, CoreProtocol.decodeProbeDelta(message.payload()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.adjustBalance(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeBalanceAdjustment(message.payload())));
            }
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                    PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                    EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                    throw new IllegalStateException("matching command must use async continuation");
            case UPSERT_INSTRUMENT -> {
                var command = TradingCommandCodec.decodeUpsertInstrument(message.payload());
                TradingCoreState next = tradingReducer.upsertInstrument(tradingState, command);
                adoptState(next);
            }
            case APPLY_MARK_PRICE -> {
                var command = TradingCommandCodec.decodeApplyMarkPrice(message.payload());
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                TradingCoreState after = tradingReducer.applyMarkPrice(tradingState, command, positionUserIndex,
                        liquidationIndex);
                adoptState(after);
                initializeTriggerScan(command);
                logRiskScan("mark-price", command.symbol(), tradingState.riskState().scanControl().scanBatchSize(),
                        pendingBefore, startedAt);
                evaluateMarkPriceTriggers(command, message.header().commandId(), message.header().submittedAtEpochMillis());
            }
            case APPLY_FUNDING -> {
                var command = TradingCommandCodec.decodeApplyFunding(message.payload());
                Iterable<Long> indexedUserIds = positionUserIndex.users(command.symbol());
                var result = tradingReducer.applyFundingWithFacts(tradingState,
                        command, indexedUserIds, message.header().commandId());
                adoptState(result.state());
                commandFundingPayments = result.payments();
                commandFundingProgress = result.progress();
                commandChangedUserIds = commandFundingPayments.stream()
                        .map(com.surprising.aeron.protocol.CoreFundingPaymentView::userId).distinct().toList();
            }
            case EXECUTE_ADL -> {
                var command = TradingCommandCodec.decodeExecuteAdl(message.payload());
                commandChangedUserIds = List.of(command.targetUserId());
                adoptState(executeAdlWithShadow(tradingState, command));
            }
            case RESOLVE_LIQUIDATION -> adoptState(resolveLiquidationWithShadow(tradingState,
                    TradingCommandCodec.decodeResolveLiquidation(message.payload())));
            case CONTINUE_RISK_SCAN -> {
                var command = TradingCommandCodec.decodeContinueRiskScan(message.payload());
                var control = tradingState.riskState().scanControl();
                if (!control.enabled() || command.maxUsers() > control.scanBatchSize()) {
                    throw new CoreStateRejectedException("INVALID_COMMAND",
                            "risk scan continuation exceeds current control");
                }
                String symbol = tradingState.riskState().scan().symbol();
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                TradingCoreState after = tradingReducer.continueRiskScan(tradingState, command.maxUsers(), positionUserIndex,
                        liquidationIndex);
                adoptState(after);
                evaluatePendingTriggerScan(symbol);
                logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
            }
            case UPDATE_RISK_SCAN_CONTROL -> {
                var command = CoreRiskScanControlCodec.decodeCommand(message.payload());
                adoptState(tradingReducer.updateRiskScanControl(tradingState, command, clusterTimestamp));
                commandRiskScanControl = tradingState.riskState().scanControl();
            }
            case ACK_EXPORT -> {
                exportState.acknowledge(CoreExportCodec.decodeAck(message.payload()));
                TerminalPruneBatch pruneBatch = terminalRetention.eligible(tradingState,
                        exportState.acknowledgedSequence(), TerminalStateRetention.MAX_PRUNE_PER_ACK);
                commandChangedUserIds = pruneBatch.orderIds().stream()
                        .map(tradingState::order)
                        .filter(java.util.Objects::nonNull)
                        .map(com.surprising.aeron.service.state.CoreOrderState::userId)
                        .distinct().toList();
                commandChangedOrderIds = pruneBatch.orderIds();
                commandChangedLiquidationIds = pruneBatch.liquidationIds();
                commandChangedTriggerOrderIds = pruneBatch.triggerOrderIds();
                adoptState(tradingReducer.pruneTerminalState(tradingState, pruneBatch));
                terminalRetention.complete(pruneBatch, exportState.acknowledgedSequence());
            }
            case UPDATE_POSITION_MODE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.updatePositionMode(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdatePositionMode(message.payload())));
            }
            case ADJUST_POSITION_MARGIN -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.adjustPositionMargin(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeAdjustPositionMargin(message.payload())));
            }
            case ADJUST_INSURANCE_FUND -> adoptState(tradingReducer.adjustInsuranceFund(
                    tradingState, TradingCommandCodec.decodeAdjustInsuranceFund(message.payload())));
            case UPDATE_LEVERAGE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.updateLeverage(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdateLeverage(message.payload())));
            }
            case UPSERT_ALGO_ORDER -> {
                var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payload())
                        .materializeCreation(clusterTimestamp);
                if (tradingState.algoOrders().get(algo.algoOrderId()) == null
                        && terminalRetention.containsAlgo(algo.algoOrderId(), message.header().userId(),
                        algo.clientAlgoOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                            "terminal algo order identity is retained");
                }
                adoptState(tradingReducer.upsertAlgoOrder(tradingState, message.header().userId(), algo, algoOrderIndex));
            }
            case UPDATE_CANCEL_ALL_AFTER -> adoptState(tradingReducer.updateCancelAllAfter(tradingState,
                    message.header().userId(),
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payload())));
            case PLACE_TRIGGER_ORDER -> {
                var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payload())
                        .materializeCreation(clusterTimestamp);
                if (tradingState.triggerOrders().get(trigger.triggerOrderId()) == null
                        && terminalRetention.containsTrigger(trigger.triggerOrderId(), message.header().userId(),
                        trigger.clientTriggerOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                            "terminal trigger order identity is retained");
                }
                commandChangedTriggerOrderIds = List.of(trigger.triggerOrderId());
                adoptState(tradingReducer.upsertTriggerOrder(tradingState, message.header().userId(), trigger,
                        triggerOrderIndex));
                commandTriggerOrderView = tradingState.triggerOrders().get(trigger.triggerOrderId()).view();
            }
            case CANCEL_TRIGGER_ORDER -> {
                long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payload());
                commandChangedTriggerOrderIds = List.of(triggerOrderId);
                adoptState(tradingReducer.cancelTriggerOrder(tradingState, message.header().userId(), triggerOrderId));
            }
            case CLAIM_TRIGGER_ORDER -> {
                long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payload());
                adoptState(tradingReducer.claimTriggerOrder(tradingState, claim[0], claim[1], claim[2], claim[3]));
            }
            case COMPLETE_TRIGGER_ORDER -> {
                long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payload());
                adoptState(tradingReducer.completeTriggerOrder(tradingState, complete[0], complete[1] == 1,
                        complete[2], "", complete[3]));
            }
            case UPDATE_TRIGGER_TRAILING -> {
                long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payload());
                adoptState(tradingReducer.updateTriggerTrailing(tradingState, trailing[0], trailing[1], trailing[2], trailing[3]));
            }
            case EXPIRE_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                adoptState(tradingReducer.expireTriggerOrder(tradingState, lifecycle[0], lifecycle[1]));
            }
            case RETRY_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                adoptState(tradingReducer.retryTriggerOrder(tradingState, lifecycle[0], lifecycle[1],
                        message.header().submittedAtEpochMillis()));
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
                .map(tradingState::order)
                .filter(java.util.Objects::nonNull);
    }

    private void evaluateMarkPriceTriggers(com.surprising.aeron.protocol.ApplyMarkPriceCommand command,
                                           UUID commandId, long submittedAtEpochMillis) {
        evaluatePendingTriggerScan(command.symbol());
    }

    private void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        CoreRiskState.RiskScan scan = tradingState.riskState().scans().get(command.symbol());
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = triggerOrderIndex.maxPendingId(command.symbol());
        replaceRiskScan(scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    private void evaluatePendingTriggerScan(String symbol) {
        CoreRiskState.RiskScan scan = tradingState.riskState().scans().get(symbol);
        if (scan == null || scan.triggerComplete()) return;
        long markPriceTicks = scan.triggerMarkPriceTicks();
        long triggeredAt = scan.triggerGeneratedAtEpochMillis();
        UUID commandId = UUID.nameUUIDFromBytes((productLine.name() + ":MARK_PRICE:" + symbol + ":"
                + scan.priceSequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int remaining = DEFAULT_TRIGGER_SCAN_BATCH_SIZE;
        if (scan.triggerOcoOrderId() != 0) {
            var pendingTrigger = tradingState.triggerOrders().get(scan.triggerOcoOrderId());
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
            var trigger = tradingState.triggerOrders().get(triggerOrderId);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                continue;
            }
            if (trigger.expiresAtEpochMillis() > 0 && triggeredAt > 0
                    && trigger.expiresAtEpochMillis() <= triggeredAt) {
                adoptState(tradingReducer.expireTriggerOrder(tradingState, triggerOrderId, triggeredAt));
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
                    adoptState(tradingReducer.updateTriggerTrailing(tradingState, triggerOrderId,
                            highest, lowest, activatedAt));
                    markTriggerChanged(triggerOrderId);
                    trigger = tradingState.triggerOrders().get(triggerOrderId);
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

    private void replaceRiskScan(CoreRiskState.RiskScan scan) {
        Map<String, CoreRiskState.RiskScan> scans = StateMapSupport.delta(tradingState.riskState().scans());
        scans.put(scan.symbol(), scan);
        CoreRiskState risk = new CoreRiskState(tradingState.riskState().markPrices(),
                tradingState.riskState().snapshots(), tradingState.riskState().liquidations(), scans,
                tradingState.riskState().nextLiquidationId(), tradingState.riskState().scanControl());
        adoptState(new TradingCoreState(tradingState.productLine(), Math.incrementExact(tradingState.revision()),
                tradingState.users(), tradingState.orders(), tradingState.instruments(), risk,
                tradingState.treasuryState(), tradingState.leverages(), tradingState.algoOrders(),
                tradingState.cancelAllAfterTimers(), tradingState.clientOrderIndex(), tradingState.triggerOrders()));
    }

    private void cancelTriggersForClosedPositions(TradingCoreState before) {
        seedChangeAccumulators();
        if (changedUserIds.isEmpty()) return;
        for (long userId : changedUserIds) {
            var previousUser = before.user(userId);
            var currentUser = tradingState.user(userId);
            if (previousUser == null || currentUser == null) continue;
            java.util.LinkedHashSet<String> positionKeys = new java.util.LinkedHashSet<>();
            positionKeys.addAll(previousUser.positions().keySet());
            positionKeys.addAll(currentUser.positions().keySet());
            for (String positionKey : positionKeys) {
                var previous = previousUser.positions().get(positionKey);
                var current = currentUser.positions().get(positionKey);
                if (previous == null || previous.signedQuantitySteps() == 0
                        || (current != null && current.signedQuantitySteps() != 0)) continue;
                var position = current == null ? previous : current;
                for (long triggerOrderId : triggerOrderIndex.ids(userId, position.symbol(), position.marginMode(),
                        position.positionSide())) {
                    var trigger = tradingState.triggerOrders().get(triggerOrderId);
                    if (trigger != null && trigger.status()
                            == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                        adoptState(tradingReducer.cancelTriggerOrder(tradingState, userId, triggerOrderId));
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
            var sibling = tradingState.triggerOrders().get(siblingId);
            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                adoptState(tradingReducer.cancelTriggerOrder(tradingState, sibling.userId(), siblingId));
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
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payload());
        executeTriggerOrder(execute[0], execute[1], execute[2], execute[3], message.header().commandId(), true);
    }

    private void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId) {
        executeTriggerOrder(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId, true);
    }

    private void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId, boolean cancelOco) {
        var trigger = tradingState.triggerOrders().get(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
            return;
        }
        var mark = tradingState.riskState().markPrices().get(trigger.symbol());
        if (mark != null && (mark.priceSequence() != triggerSequence
                || mark.markPriceTicks() != triggeredPriceTicks
                || !isTriggerConditionSatisfied(trigger, triggeredPriceTicks))) {
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        }
        if (cancelOco) cancelAllOcoSiblings(trigger);
        TradingCoreState before = tradingState;
        TradingCoreState claimed = tradingReducer.claimTriggerOrder(before, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis);
        adoptState(claimed);
        markTriggerChanged(triggerOrderId);
        var instrument = claimed.instruments().get(trigger.symbol());
        if (instrument == null || instrument.version() <= 0 || trigger.instrumentVersion() <= 0
                || instrument.version() != trigger.instrumentVersion()) {
            adoptState(tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_VERSION", triggeredAtEpochMillis));
            return;
        }
        long childOrderId = triggerChildOrderId(triggerOrderId, claimed);
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        var place = new com.surprising.aeron.protocol.PlaceOrderCommand(
                childOrderId, trigger.symbol(), trigger.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(),
                instrument.settleAsset(), trigger.side(), trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET
                        ? 0 : trigger.priceTicks(), trigger.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                spot ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                spot && trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                        ? instrument.quoteAsset() : spot ? instrument.baseAsset() : instrument.settleAsset(), 0,
                trigger.orderType(), trigger.timeInForce(), trigger.priceTicks() > 0 ? trigger.priceTicks() : triggeredPriceTicks, false,
                "TRIGGER:" + triggerOrderId, trigger.makerFeeRatePpm(), trigger.takerFeeRatePpm());
        requireOrderIdentityAvailable(trigger.userId(), place);
        TradingCoreState reserved;
        try {
            reserved = tradingReducer.placeOrder(claimed, trigger.userId(), place, commandId,
                    openInterestIndex.openInterestSteps(trigger.symbol()), activeOrderIndex);
        } catch (CoreStateRejectedException exception) {
            adoptState(tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    exception.code(), triggeredAtEpochMillis));
            return;
        }
        adoptState(reserved);
        markUserChanged(trigger.userId());
        markOrderChanged(childOrderId);
        queueTriggerMatching(trigger, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId);
        commandOrderViews = appendDistinct(commandOrderViews, List.of(orderView(tradingState.order(childOrderId))));
    }

    private void adoptState(TradingCoreState next) {
        if (next == null || next.productLine() != productLine) {
            throw new IllegalArgumentException("invalid trading state");
        }
        if (next == tradingState) return;
        TradingCoreState previous = tradingState;
        if (runtimePlaceOrderCoreState != previous) {
            throw new IllegalStateException("runtime state cursor does not match core state");
        }
        RuntimeStateDeltaApplier.apply(previous, next, runtimePlaceOrderState, runtimePlaceOrderIdentities);
        RuntimeStateParityChecker.assertMatches(next, runtimePlaceOrderIdentities, runtimePlaceOrderState);
        TradingCoreState authoritativeNext = RuntimeStateMaterializer.materialize(runtimePlaceOrderState,
                runtimePlaceOrderIdentities);
        runtimePlaceOrderCoreState = authoritativeNext;
        runtime.transition(previous, next, authoritativeNext);
        rollingBusinessStateHash.update(previous, authoritativeNext);
        seedChangeAccumulators();
        if (previous.users() != next.users()) {
            changedUserIds.addAll(next.changedUserIds());
        }
        if (previous.orders() != next.orders()) {
            changedOrderIds.addAll(next.changedOrderIds());
        }
        if (previous.riskState().liquidations() != next.riskState().liquidations()) {
            changedLiquidationIds.addAll(next.changedLiquidationIds());
        }
        if (previous.triggerOrders() != next.triggerOrders()) {
            changedTriggerOrderIds.addAll(next.changedTriggerOrderIds());
        }
        if (previous.treasuryState() != next.treasuryState()) {
            changedTreasuryAssets.addAll(next.changedTreasuryAssets());
        }
        tradingState = authoritativeNext;
    }

    private void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    private void restoreCommandState(TradingCoreState state) {
        tradingState = state;
        rollingBusinessStateHash.restore(state);
        runtimePlaceOrderIdentities = new RuntimeIdentityRegistry();
        runtimePlaceOrderState = RuntimeStateProjector.project(state, runtimePlaceOrderIdentities);
        runtimePlaceOrderCoreState = state;
    }

    private static long triggerChildOrderId(long triggerOrderId, TradingCoreState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.orders().containsKey(candidate)) {
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
        changedUserIds.clear();
        changedOrderIds.clear();
        changedLiquidationIds.clear();
        changedTriggerOrderIds.clear();
        changedTreasuryAssets.clear();
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

    private int pendingRiskScanCount() {
        return (int) tradingState.riskState().scans().values().stream()
                .filter(scan -> !scan.complete())
                .count();
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
        completedMatching.clear();
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        pendingMatching.clear();
        pendingLifecycleScopes.clear();
        pendingOrderBatches.clear();
        runtime.close();
    }

    private CoreResponse userStateResponse(long userId) {
        var user = tradingState.user(userId);
        if (user == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        var view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(), user.positionMode(),
                user.balances().values().stream().map(value -> new CoreBalanceView(
                        value.asset(), value.availableUnits(), value.lockedUnits())).toList(),
                user.reservations().values().stream().map(value -> new CoreReservationView(
                        value.orderId(), value.symbol(), value.instrumentVersion(), value.kind(), value.asset(),
                        value.reservedUnits(),
                        value.releasedUnits(), value.consumedUnits(), value.orderQuantitySteps())).toList(),
                user.positions().values().stream().map(value -> new CorePositionView(
                        value.symbol(), value.marginAsset(), value.marginMode(), value.positionSide(),
                        value.instrumentVersion(), value.signedQuantitySteps(),
                        value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(),
                        value.positionMarginUnits())).toList(),
                tradingState.leverages().entrySet().stream()
                        .filter(entry -> entry.getKey().userId() == userId)
                        .map(entry -> new com.surprising.aeron.protocol.CoreLeverageView(entry.getKey().symbol(),
                                entry.getKey().marginMode(), entry.getValue())).toList());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.userStateHash(userId),
                CoreStateQueryCodec.encodeUserState(view));
    }

    private com.surprising.aeron.protocol.CoreAlgoOrderView algoView(
            com.surprising.aeron.service.state.CoreAlgoOrderState state) {
        long executed = 0, active = 0; int activeCount = 0;
        for (long childOrderId : state.childOrderIds()) {
            var child = tradingState.order(childOrderId);
            if (child == null) throw new IllegalStateException("algo child order missing");
            executed = Math.addExact(executed, child.executedQuantitySteps());
            if (child.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
                active = Math.addExact(active, child.remainingQuantitySteps()); activeCount++;
            }
        }
        return new com.surprising.aeron.protocol.CoreAlgoOrderView(state.algoOrderId(), state.userId(),
                state.clientAlgoOrderId(), state.symbol(), state.algoTypeCode(), state.side(), state.priceTicks(),
                state.quantitySteps(), state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(),
                state.marginMode(), state.positionSide(), state.reduceOnly(), state.postOnly(), state.timeInForce(),
                state.statusCode(), state.currentOrderId(), state.rejectReason(), state.traceId(), state.startAtEpochMillis(),
                state.nextSliceAtEpochMillis(), state.completedAtEpochMillis(), state.createdAtEpochMillis(),
                state.updatedAtEpochMillis(), state.revision(), state.childOrderIds(), executed, active, activeCount);
    }

    private static List<com.surprising.aeron.protocol.CoreExecutionView> executionViews(
            long takerOrderId, long takerUserId, List<com.surprising.aeron.service.matching.CoreMatch> matches) {
        return matches.stream().map(match -> new com.surprising.aeron.protocol.CoreExecutionView(
                takerOrderId, match.makerOrderId(), takerUserId, match.makerUserId(),
                match.priceTicks(), match.quantitySteps())).toList();
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
                        after.treasuryState().insuranceDeficits().getOrDefault(asset, 0L))).toList();
    }

    private static boolean treasuryChanged(TradingCoreState before, TradingCoreState after, String asset) {
        return !java.util.Objects.equals(before.treasuryState().feeBalances().get(asset),
                after.treasuryState().feeBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceBalances().get(asset),
                after.treasuryState().insuranceBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceDeficits().get(asset),
                after.treasuryState().insuranceDeficits().get(asset));
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
                order.createdAtEpochMillis(), order.updatedAtEpochMillis(), order.clusterPosition(),
                order.status().name(), order.revision());
    }

    private byte[] commandResultData() {
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
        List<CoreOrderStateView> finalOrders = commandOrderViews.stream()
                .map(value -> {
                    var order = tradingState.order(value.orderId());
                    return order == null ? value : orderView(order);
                })
                .toList();
        try {
            return CoreCommandResultCodec.encode(new CoreCommandResultView(finalOrders, commandExecutions));
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    private CoreResponse orderStateResponse(long orderId) {
        return orderStateResponse(tradingState.order(orderId));
    }

    private CoreResponse orderStateResponse(com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        var view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(), order.postOnly(),
                order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.createdAtEpochMillis(), order.updatedAtEpochMillis(), order.clusterPosition(),
                order.status().name(), order.revision());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.orderStateHash(order.orderId()),
                CoreStateQueryCodec.encodeOrderState(view));
    }

    private static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    private static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
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
        return Math.addExact(CoreStateSnapshotCodec.RESULT_FIXED_LENGTH, result.responseData().length);
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
            commandResults.put(commandId, result.withRetentionSequence(previous.retentionSequence()));
        } else {
            StoredResult retained = result.withRetentionSequence(nextResultRetentionSequence);
            nextResultRetentionSequence = Math.incrementExact(nextResultRetentionSequence);
            commandResults.put(commandId, retained);
        }
        commandResultBytes = resultLedgerBytes(commandResults);
        while (commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            UUID oldest = commandResults.keySet().stream()
                    .filter(key -> !key.equals(commandId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("result ledger protected entry exceeds bound"));
            commandResults.remove(oldest);
            commandResultBytes = resultLedgerBytes(commandResults);
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

    private static final class OrderBatchPending {
        private final OrderBatchKind kind;
        private final List<OrderBatchItem> items;
        private final TradingCoreState beforeState;
        private final long clusterTimestamp;
        private final long clusterPosition;
        private final PendingMatching.Operation operation;
        private final List<CoreOrderBatchResult.Item> results = new ArrayList<>();
        private int nextIndex;
        private long sequence;
        private TradingCoreState currentBefore;

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

        private List<Long> changedOrderIds(
                OrderBatchItem item,
                com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
            java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
            ids.add(item.orderId());
            if (item.originalOrderId() > 0) ids.add(item.originalOrderId());
            if (item.replacementOrderId() > 0) ids.add(item.replacementOrderId());
            matchingResult.matches().stream()
                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId)
                    .forEach(ids::add);
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

        @Override
        public byte[] responseData() {
            return responseData.clone();
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
