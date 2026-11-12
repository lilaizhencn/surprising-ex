package com.surprising.aeron.service.core;

import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.core.command.DirectCommandDispatcher;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.LiquidationIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.matcher.OrderBookEngine;
import com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.matcher.OrderBookRouter;
import com.surprising.aeron.service.order.OrderCommandQueue.IngressWindow;
import com.surprising.aeron.service.order.OrderCommand;
import com.surprising.aeron.service.order.OrderMatchingFlow;
import com.surprising.aeron.service.order.OrderCommand.Direct;
import com.surprising.aeron.service.order.OrderCheckService;
import com.surprising.aeron.service.order.OrderMatchingFlow.Progress;
import com.surprising.aeron.service.order.OrderBatchProcessor;
import com.surprising.aeron.service.order.OrderBatchProcessor.PendingBatch;
import com.surprising.aeron.service.order.OrderCommandQueue;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderCommand.Decoded;
import com.surprising.aeron.service.order.OrderCommand.ReplaceOrderCheck;
import com.surprising.aeron.service.order.OrderCommitService;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.query.CoreQueryService;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.CommandResultLog;
import com.surprising.aeron.service.result.CommandResultLog.StoredResult;
import com.surprising.aeron.service.result.FundsCommandIndex;
import com.surprising.aeron.service.result.ResponseSlotPool;
import com.surprising.aeron.service.result.SourceSequenceIndex;
import com.surprising.aeron.service.retention.CompletedOrderArchive;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.snapshot.CoreSnapshotLifecycle;
import com.surprising.aeron.service.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/** 交易步骤 1：Owner 上的业务执行入口。接收复制命令后，依次处理查询边界、幂等与来源检查、业务准入、撮合派发和有序完成。本类保存当前命令的变化范围、路由索引、收据及恢复水位， 借出引用不会自动授予跨线程账户读写权限。 */
@Slf4j
public final class TradingCore implements AutoCloseable {

    public static final byte[] EMPTY_RESPONSE_DATA = new byte[0];

    @Accessors(fluent = true, chain = false)
    @Getter
    final ProductLine productLine;
    Thread owner;
    @Accessors(fluent = true, chain = false)
    @Getter
    final IdIndex identities;
    @Accessors(fluent = true, chain = false)
    @Getter
    final AccountBook accountBook;
    private final OrderBookRouter matcherPipeline;
    public final OrderCommitService commits = new OrderCommitService(this);
    public final OrderMatchingFlow matchingFlow = new OrderMatchingFlow(this);
    public final OrderMatchingFlow.Progress matchingProgress = new OrderMatchingFlow.Progress(this);
    public final CommandResultBuilder resultBuilder;
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderBatchProcessor batches = new OrderBatchProcessor(this);
    public final CoreSnapshotLifecycle snapshots = new CoreSnapshotLifecycle(this);
    public final CoreQueryService bookQueries = new CoreQueryService(this);
    public final OrderCommand.Direct directCommand = new OrderCommand.Direct(this);
    private java.util.concurrent.CompletableFuture<com.surprising.aeron.service.realtime.TradingRealtimeBoundary.UserSnapshot> pendingRealtimeSnapshot;
    final CoreRuntimeConfiguration configuration;
    final CommandFingerprint.Calculator fingerprints = new CommandFingerprint.Calculator();
    final OrderCheckService orderChecks;
    final TriggerOrderCommands triggers;
    final DirectCommandDispatcher directCommands;
    final ResponseSlotPool responseSlotPool;
    final CrossShardCancellationCoordinator crossShardCancellations;
    final com.surprising.aeron.service.core.index.BizIndex bizIndex;
    final CommandResultLog commandResultLog;
    final SourceSequenceIndex sourceSequenceIndex;
    final OrderCommandQueue pendingMatching;
    final OrderBookEngine matchingAdapter;
    final CompletedOrderArchive completedOrderArchive;
    final ExecutionState state = new ExecutionState();

    public static final class Limits {
        public static final int MAX_IDEMPOTENCY_RESULTS = 128;
        public static final long MAX_RESULT_LEDGER_BYTES = 32L * 1024 * 1024;
        public static final int MAX_SOURCE_SEQUENCES = 65_536;
        public static final int MAX_BOOK_RESPONSE_LEVELS = 10_000;
        public static final int MAX_BOOK_RESPONSE_BYTES = 1024 * 1024;
        public static final long MATCHING_AWAIT_TIMEOUT_NANOS =
                java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        public static final int MAX_BOOK_BOOTSTRAP_SNAPSHOTS = 4;

        private Limits() {
        }
    }

    static final class ExecutionState {
        static final int DEFAULT_TRIGGER_SCAN_BATCH_SIZE = 2;
        static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
        static final long HASH_PRIME = 0x100000001b3L;

        java.util.function.Consumer<com.surprising.aeron.service.funding.FundingSettlementService.FundingResult> replayFundingObserver;
        boolean clusterPipelineOrderCheck;
        CoreMessage decodedIngressMessage;
        OrderCommand.Decoded decodedIngressCommand;
        CommandFingerprint preparedIngressFingerprint;
        long preparedRouteUserLaneBit;
        int preparedRouteMatcherShard = -1;
        TradingCoreState cachedTradingState;
        long cachedRevision = Long.MIN_VALUE;
        long cachedMarketRevision = Long.MIN_VALUE;
        long cachedSequence = Long.MIN_VALUE;
        com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture realtimeCapture;
        long realtimeSnapshotUser, realtimeSnapshotId, realtimeSnapshotPosition, realtimeSnapshotTimestamp, realtimeSnapshotExportSequence;
        java.util.concurrent.CompletableFuture<java.util.List<com.surprising.aeron.protocol.CoreBookLevelView>> realtimeBook;
        String realtimeBookSymbol;
        long realtimeBookPosition, realtimeBookTimestamp;
        long terminalTradeCount;
        boolean factContextActive;
        long appliedCommandCount;
        long committedCoreSequence;
        RuntimeException commitPublicationFailure;
        FatalMatchingDivergenceException fatalFailure;
        long currentClusterPosition;
        long currentClusterTimestamp;
        long orderCheckPreviousClusterPosition;
        long orderCheckPreviousClusterTimestamp;
        boolean activated;
        boolean closed;
    }

    public CommandFingerprint.Calculator fingerprints() { return fingerprints; }
    public OrderCheckService orderChecks() { return orderChecks; }
    public TriggerOrderCommands triggers() { return triggers; }
    public ResponseSlotPool responseSlotPool() { return responseSlotPool; }
    public CrossShardCancellationCoordinator crossShardCancellations() { return crossShardCancellations; }
    public CoreRuntimeConfiguration configuration() { return configuration; }
    public com.surprising.aeron.service.core.index.BizIndex bizIndex() { return bizIndex; }
    public CommandResultLog commandResultLog() { return commandResultLog; }
    public OrderCommandQueue pendingMatching() { assertOwner(); return pendingMatching; }
    public OrderBookRouter matcherPipeline() { assertOwner(); return matcherPipeline; }
    public OrderBookEngine matchingAdapter() { return matchingAdapter; }
    public CompletedOrderArchive completedOrderArchive() { return completedOrderArchive; }
    public boolean clusterPipelineOrderCheck() { return state.clusterPipelineOrderCheck; }
    public long preparedRouteUserLaneBit() { return state.preparedRouteUserLaneBit; }
    public int preparedRouteMatcherShard() { return state.preparedRouteMatcherShard; }
    public com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture realtimeCapture() { return state.realtimeCapture; }
    public void terminalTradeCount(long terminalTradeCount) { state.terminalTradeCount = terminalTradeCount; }
    public boolean factContextActive() { return state.factContextActive; }
    public long appliedCommandCount() { return state.appliedCommandCount; }
    public long committedCoreSequence() { return state.committedCoreSequence; }
    public void commitPublicationFailure(RuntimeException commitPublicationFailure) { state.commitPublicationFailure = commitPublicationFailure; }
    public FatalMatchingDivergenceException fatalFailure() { return state.fatalFailure; }
    public long currentClusterPosition() { return state.currentClusterPosition; }
    public void currentClusterPosition(long currentClusterPosition) { state.currentClusterPosition = currentClusterPosition; }
    public long currentClusterTimestamp() { return state.currentClusterTimestamp; }
    public void currentClusterTimestamp(long currentClusterTimestamp) { state.currentClusterTimestamp = currentClusterTimestamp; }
    public boolean activated() { return state.activated; }
    public boolean closed() { return state.closed; }

    public void observeReplayFundingPayments(java.util.function.Consumer<com.surprising.aeron.service.funding.FundingSettlementService.FundingResult> observer) {
        state.replayFundingObserver = java.util.Objects.requireNonNull(observer);
    }

    public void notifyReplayFundingObserver(com.surprising.aeron.service.funding.FundingSettlementService.FundingResult result) {
        if (state.replayFundingObserver != null) state.replayFundingObserver.accept(result);
    }

    public PositionUserIndex positionUserIndex() { return bizIndex.positionUsers(); }
    public OpenInterestIndex openInterestIndex() { return bizIndex.openInterest(); }
    public TriggerOrderIndex triggerOrderIndex() { return bizIndex.triggers(); }
    public AlgoOrderIndex algoOrderIndex() { return bizIndex.algos(); }
    public LiquidationIndex liquidationIndex() { return bizIndex.liquidations(); }
    public ActiveOrderIndex activeOrderIndex() { return bizIndex.activeOrders(); }
    public AdlPositionIndex adlPositionIndex() { return bizIndex.adlPositions(); }

    public void requestCommitPublication() { commits.publication.request(); }
    public Iterable<Long> activeLiquidationIds() { return bizIndex.liquidations().activeIds(); }
    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        triggers.initializeTriggerScan(command);
    }
    public java.util.function.BooleanSupplier pendingTriggerScan(String instrumentId, int maxWork) {
        return triggers.pendingTriggerScan(instrumentId, maxWork);
    }
    public int queuedMatchingCount() { return orderChecks.queuedMatchingCount(); }
    public int defaultTriggerScanBatchSize() { return ExecutionState.DEFAULT_TRIGGER_SCAN_BATCH_SIZE; }

    public TradingCore(ProductLine productLine) {
        this(productLine, CoreConfiguration.standaloneLaneTopology());
    }

    public TradingCore(ProductLine productLine, LaneTopology startupTopology) {
        this(productLine, startupTopology, CoreRuntimeConfiguration.standalone());
    }

    public TradingCore(ProductLine productLine, LaneTopology startupTopology,
            CoreRuntimeConfiguration configuration) {
        this(productLine, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new CompletedOrderArchive(), null,
                0, Map.of(), Map.of(),
                java.util.Objects.requireNonNull(startupTopology, "startupTopology"), configuration);
    }

    public TradingCore(
            ProductLine productLine,
            long appliedCommandCount,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CompletedOrderArchive completedOrderArchive,
            OrderBookSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> restoredPendingTransfers) {
        this(productLine, appliedCommandCount, commandResults, lastSourceSequences, snapshotState,
                completedOrderArchive, matcherSnapshot, projectionSequence, restoredFeePolicies,
                restoredPendingTransfers,
                matcherSnapshot == null
                        ? CoreConfiguration.standaloneLaneTopology()
                        : matcherSnapshot.topology(), CoreRuntimeConfiguration.standalone());
    }

    private TradingCore(
            ProductLine productLine,
            long appliedCommandCount,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CompletedOrderArchive completedOrderArchive,
            OrderBookSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> restoredPendingTransfers,
            LaneTopology startupTopology, CoreRuntimeConfiguration configuration) {
        java.util.Objects.requireNonNull(configuration);
        this.productLine = productLine;
        this.state.appliedCommandCount = appliedCommandCount;
        this.state.committedCoreSequence = appliedCommandCount;
        snapshots.lastSnapshotId(matcherSnapshot == null ? 0 : matcherSnapshot.snapshotId());
        long restoredBusinessStateHash = canonicalBusinessStateHash(
                snapshotState.businessStateHash(), restoredFeePolicies, restoredPendingTransfers);
        var topology = matcherSnapshot == null
                ? startupTopology
                : matcherSnapshot.topology();
        this.identities = new IdIndex(topology);
        this.accountBook = com.surprising.aeron.service.snapshot.state.SnapshotLoader.loadSnapshot(
                snapshotState, identities, topology, configuration);
        var indexes = accountBook.bizIndex();
        var adapter = matcherSnapshot == null
                ? new OrderBookEngine(topology, false, configuration)
                : new OrderBookEngine(snapshotState, snapshotState.orders().values().stream().filter(order -> order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN).toList(),
                        appliedCommandCount, matcherSnapshot, false, restoredBusinessStateHash, configuration);
        var queue = new OrderCommandQueue(
                Math.min(configuration.maxPendingMatching(), adapter.topology().matcherWindowSize()),
                adapter.topology().matchingEngineCount());
        this.configuration = configuration;
        this.orderChecks = new OrderCheckService(this);
        this.triggers = new TriggerOrderCommands(this);
        this.directCommands = new DirectCommandDispatcher(this, this.triggers);
        this.responseSlotPool = new ResponseSlotPool(configuration);
        this.crossShardCancellations = new CrossShardCancellationCoordinator(this);
        this.commandResultLog = new CommandResultLog(commandResults, this.responseSlotPool);
        this.sourceSequenceIndex = new SourceSequenceIndex(lastSourceSequences);
        this.completedOrderArchive = completedOrderArchive;
        this.bizIndex = indexes;
        this.matchingAdapter = adapter;
        this.pendingMatching = queue;
        this.resultBuilder = new CommandResultBuilder(this);
        this.orderChecks.initializeLifecycleScopes();
        commits.initializeMatcherProgress(adapter.topology().matchingEngineCount() + 1);
        commits.initializeMatcherProgress(matcherSnapshot);
        this.matcherPipeline = new OrderBookRouter(
                adapter.topology().matchingEngineCount(),
                Math.min(adapter.topology().matcherWindowSize(),
                        adapter.topology().matchingCompletionCapacity()), queue.contexts());
        this.accountBook.restoreFeePolicies(restoredFeePolicies);
        this.accountBook.restorePendingTransfers(restoredPendingTransfers);
        commits.publication.initialize(snapshotState.revision(), projectionSequence);
        accountBook.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
    }

    public static TradingCore prepareRestore(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CompletedOrderArchive completedOrderArchive,
            OrderBookSnapshot matcherSnapshot,
            long projectionSequence,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers) {
        return prepareRestore(productLine, appliedCommandCount, commandResults, lastSourceSequences,
                snapshotState, completedOrderArchive, matcherSnapshot, projectionSequence, feePolicies,
                pendingTransfers, CoreRuntimeConfiguration.standalone());
    }

    public static TradingCore prepareRestore(ProductLine productLine, long appliedCommandCount,
            Map<UUID, StoredResult> commandResults, Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState, CompletedOrderArchive completedOrderArchive, OrderBookSnapshot matcherSnapshot,
            long projectionSequence, Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers,
            CoreRuntimeConfiguration configuration) {
        if (projectionSequence < 0 || appliedCommandCount < 0 || commandResults == null
                || commandResults.size() > Limits.MAX_IDEMPOTENCY_RESULTS || lastSourceSequences == null
                || lastSourceSequences.size() > Limits.MAX_SOURCE_SEQUENCES
                || matcherSnapshot == null || snapshotState == null
                || snapshotState.productLine() != productLine
                || completedOrderArchive == null || feePolicies == null || pendingTransfers == null) {
            throw new IllegalArgumentException("invalid passive restored trading state");
        }
        CommandResultLog.validateResultLedger(commandResults);
        return new TradingCore(productLine, appliedCommandCount,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, completedOrderArchive, matcherSnapshot, projectionSequence,
                feePolicies, pendingTransfers, matcherSnapshot.topology(), configuration);
    }

    /** 接入实时输出队列，创建本产品线的变更捕获器并交给运行时。这里只连接既有业务状态，不启动输出线程。 */
    public com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture attachRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        state.realtimeCapture = new com.surprising.aeron.service.realtime.TradingRealtimeBoundary.StateCapture(
                outbox, productLine, identities);
        accountBook.realtimeCapture(state.realtimeCapture);
        return state.realtimeCapture;
    }

    /** 返回已经发布的投影序号，用它标明实时快照可见的提交边界。 */
    public long realtimeExportSequence() { return commits.publication.publishedSequence(); }
    /** 判断是否还保存着一个待收集的实时账户快照请求。 */
    public boolean realtimeSnapshotPending() { return pendingRealtimeSnapshot != null; }
    /** 判断是否还保存着一个待收集的实时盘口请求。 */
    public boolean realtimeBookPending() { return state.realtimeBook != null; }

    /** 未来结果就绪后先取走等待槽，再按原请求位置、时间和导出序号发布账户快照。收集失败时记录捕获故障，并尝试发布快照不可用事件。 */
    public int pollRealtimeSnapshot() {
        if (pendingRealtimeSnapshot == null || !pendingRealtimeSnapshot.isDone()) return 0;
        var future = pendingRealtimeSnapshot;
        pendingRealtimeSnapshot = null;
        try {
            var snapshot = future.join();
            state.realtimeCapture.begin(state.realtimeSnapshotPosition, state.realtimeSnapshotTimestamp, state.realtimeSnapshotId);
            state.realtimeCapture.snapshot(state.realtimeSnapshotUser, snapshot, state.realtimeSnapshotExportSequence);
            state.realtimeCapture.commit();
        } catch (RuntimeException failure) {
            state.realtimeCapture.failed();
            state.realtimeCapture.begin(state.realtimeSnapshotPosition, state.realtimeSnapshotTimestamp, state.realtimeSnapshotId);
            state.realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE,
                    state.realtimeSnapshotUser, "", "", new byte[0]);
            state.realtimeCapture.commit();
        }
        return 1;
    }

    /** 收集已就绪的盘口读取并发布 BOOK 事件。 */
    public int pollRealtimeBook() {
        if (state.realtimeBook == null || !state.realtimeBook.isDone()) return 0;
        var future = state.realtimeBook;
        state.realtimeBook = null;
        try {
            var levels = future.join();
            state.realtimeCapture.begin(state.realtimeBookPosition, state.realtimeBookTimestamp, 0);
            state.realtimeCapture.emit(com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK, 0,
                    state.realtimeBookSymbol, state.realtimeBookSymbol,
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeOrderBookView(
                            new com.surprising.aeron.protocol.CoreOrderBookView(state.realtimeBookPosition, levels)));
            state.realtimeCapture.commit();
        } catch (RuntimeException failure) {
            state.realtimeCapture.failed();
        }
        return 1;
    }

    /** 实时功能启用且没有同类请求时，确认业务回调已完成，保存原请求元数据并发起账户快照读取。 */
    public void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (state.realtimeCapture == null || pendingRealtimeSnapshot != null) return;
        assertClusterCallbackComplete();
        state.realtimeSnapshotExportSequence = realtimeExportSequence();
        state.realtimeSnapshotUser = userId;
        state.realtimeSnapshotId = snapshotId;
        state.realtimeSnapshotPosition = position;
        state.realtimeSnapshotTimestamp = timestamp;
        try { pendingRealtimeSnapshot = accountBook.realtimeSnapshot(userId); }
        catch (RuntimeException failure) { state.realtimeCapture.failed(); }
    }

    /** 实时功能启用且没有同类请求时，在对应 Matcher 的提交栅栏后读取最多 50 档盘口。 */
    public void captureRealtimeBook(String instrumentId, long position, long timestamp) {
        if (state.realtimeCapture == null || state.realtimeBook != null) return;
        assertClusterCallbackComplete();
        state.realtimeBookSymbol = instrumentId;
        state.realtimeBookPosition = position;
        state.realtimeBookTimestamp = timestamp;
        try {
            state.realtimeBook = matcherPipeline.readAtSubmissionFence(matchingAdapter.matcherShardId(instrumentId),
                    () -> matchingAdapter.orderBookLevels(instrumentId, 50));
        } catch (RuntimeException failure) {
            state.realtimeCapture.failed();
        }
    }

    /** 独立调用的便捷入口：用请求提交时间作为业务时间，并计算下一个计数作为位置。 */
    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(state.appliedCommandCount, 1));
    }

    /** 交易入口：激活并绑定 Owner，检查健康、控制命令和快照边界，登记原日志元数据，按查询需要取得读栅栏。先尝试查询，再把合法命令交给幂等和业务准入入口。 */
    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!activated()) activate();
        assertOwner();
        if (directCommand.active()) {
            throw new IllegalStateException("asynchronous control command is still active");
        }
        state.orderCheckPreviousClusterTimestamp = state.currentClusterTimestamp;
        state.orderCheckPreviousClusterPosition = state.currentClusterPosition;
        state.currentClusterTimestamp = clusterTimestamp;
        state.currentClusterPosition = clusterPosition;
        assertHealthy();
        if (snapshots.inFlightMatcherSnapshot().get() != null
                || snapshots.snapshotFence() != null) {
            throw new IllegalStateException("snapshot fence is active");
        }
        if (!pendingMatching.isEmpty()
                && !isCommitCursorSafeWhileMatching(message)) {
            long userId = message.header().userId();
            if (userId <= 0 || pendingMatching.hasUser(userId)) {
                throw new IllegalStateException("command or query crossed its account-lane matching cursor");
            }
        }
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && requiresOwnerLaneAccessForPreparation(message)
                && accountLaneReadQuery(message.header().messageType())) {
            if (singleUserLaneQuery(message.header().messageType())
                    && message.header().userId() > 0) {
                accountBook.readFence(message.header().userId(), state.committedCoreSequence);
            } else {
                accountBook.readFenceAll(state.committedCoreSequence);
            }
        }
        CoreResponse queryResponse = bookQueries.routeQuery(message, clusterTimestamp);
        if (queryResponse != null) return queryResponse;
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        return applyCommandIngress(message, clusterTimestamp, clusterPosition);
    }

    /** 判断准备此请求是否需要 Owner 账户访问边界。 */
    public boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        if (message.header().kind() != WireMessageKind.QUERY) return false;
        var type = message.header().messageType();
        if (CoreQueryService.accountQuery(type) || CoreQueryService.riskAccountQuery(type)) return false;
        if (type == CoreMessageType.ORDER_PREFLIGHT_QUERY || type == CoreMessageType.LANE_METRICS_QUERY) return false;
        if (type == CoreMessageType.TRIGGER_ORDER_QUERY || type == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY) return false;
        if (type == CoreMessageType.TREASURY_STATE_QUERY || type == CoreMessageType.FUNDING_PROGRESS_QUERY
                || type == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) return false;
        if (type == CoreMessageType.LIQUIDATION_WORK_QUERY || type == CoreMessageType.PENDING_TRANSFER_QUERY) return false;
        if (type == CoreMessageType.OPEN_INTEREST_QUERY || type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) return false;
        return true;
    }

    /** 将 Cluster 命令按未提供预解码对象的形式交给统一异步入口。 */
    public CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    /** 必要时进入异步命令作用域，按独立窗口方式执行已准备的 Cluster 输入。 */
    public CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     OrderCommand.Decoded decoded) {
        return applyDecodedCommand(message, timestamp, position, decoded, true);
    }

    /** 为已准备输入补上空的预计算指纹参数，再进入统一执行路径。 */
    public CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     OrderCommand.Decoded decoded, boolean independent) {
        return applyDecodedCommand(message, timestamp, position, decoded, independent, null);
    }

    /** 临时接入本条输入的解码对象、指纹和窗口准入标记，调用正常交易入口。无论成功或抛错都恢复外层调用的准备状态。 */
    public CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     OrderCommand.Decoded decoded, boolean independent,
                                     CommandFingerprint fingerprint) {
        CommandFingerprint previousFingerprint = state.preparedIngressFingerprint;
        state.preparedIngressFingerprint = fingerprint;
        boolean previousOrderCheck = state.clusterPipelineOrderCheck;
        CoreMessage previousMessage = state.decodedIngressMessage;
        OrderCommand.Decoded previousCommand = state.decodedIngressCommand;
        state.clusterPipelineOrderCheck = independent;
        state.decodedIngressMessage = message;
        state.decodedIngressCommand = decoded;
        try {
            return apply(message, timestamp, position);
        } finally {
            state.preparedIngressFingerprint = previousFingerprint;
            state.clusterPipelineOrderCheck = previousOrderCheck;
            state.decodedIngressMessage = previousMessage;
            state.decodedIngressCommand = previousCommand;
        }
    }

    /** 取得当前输入的撮合解码结果。复用槽尚未解码时在此延迟解码，使串行输入先经过幂等和来源检查，再落入业务拒绝处理边界。 */
    public OrderCommand.Decoded decodeMatchingCommand(CoreMessage message) {
        if (message != state.decodedIngressMessage || state.decodedIngressCommand == null)
            return OrderCommand.Decoded.decode(message);
        // 解码留在幂等和来源检查之后，并处于准入的业务拒绝处理范围内。
        if (!state.decodedIngressCommand.isDecoded()) state.decodedIngressCommand.decodeInto(message);
        return state.decodedIngressCommand;
    }

    /** 只做独立窗口的路由预检查：支持同一 Matcher 分片的下单、撤单及对应批量命令，排除需要全局持仓量依赖等情况。 */
    public boolean prepareClusterPipelineScope(CoreMessage message, OrderCommandQueue.IngressWindow window) {
        if (!activated()) activate();
        assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != productLine || user <= 0) {
            return rememberPipelineRoute(window, false);
        }
        window.resetCandidate(user);
        try {
            OrderCommand.Decoded decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    var command = decoded.placeOrder();
                    var instrument = accountBook.instrument(command.instrumentId());
                    if (instrument != null && instrument.usesOpenInterestForUserLimit() && !command.reduceOnly())
                        return rememberPipelineRoute(window, false);
                    window.route(decoded.matcherShard(matchingAdapter, command.instrumentId()),
                            accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    long orderId = decoded.cancelOrder().orderId();
                    var route = bizIndex.activeOrders().activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.instrumentId() == null
                            || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                    window.route(matchingAdapter.matcherShardId(route.instrumentId()),
                            accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.placeOrderBatch().orders()) {
                        var instrument = accountBook.instrument(order.instrumentId());
                        if (instrument != null && instrument.usesOpenInterestForUserLimit() && !order.reduceOnly())
                            return rememberPipelineRoute(window, false);
                        int current = decoded.matcherShard(matchingAdapter, order.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        if (shard < 0) {
                            shard = current;
                            window.route(shard, accountBook.topology().accountLaneMask(user));
                        }
                    }
                    return rememberPipelineRoute(window, shard >= 0);
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.cancelOrderBatch().orders()) {
                        var route = bizIndex.activeOrders().activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.instrumentId() == null
                                || route.instrumentId().isBlank()) return rememberPipelineRoute(window, false);
                        int current = matchingAdapter.matcherShardId(route.instrumentId());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, accountBook.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    /** 不适用时清空路由提示，避免沿用上一条输入。 */
    private boolean rememberPipelineRoute(OrderCommandQueue.IngressWindow window, boolean eligible) {
        state.preparedRouteUserLaneBit = eligible ? window.routeUserLaneBit() : 0;
        state.preparedRouteMatcherShard = eligible ? window.routeMatcherShard() : -1;
        return eligible;
    }

    private CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CommandFingerprint fingerprint = message == state.decodedIngressMessage && state.preparedIngressFingerprint != null
                ? state.preparedIngressFingerprint : fingerprints.of(message);
        CoreResponse replayResponse = checkCommandReplay(message, fingerprint);
        if (replayResponse != null) return replayResponse;

        long lastSourceSequence = sourceSequenceIndex.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        SourceKey sourceKey = sourceSequenceIndex.lastLookupKey();
        if (sourceKey == null) {
            sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        }
        if (isFundsIdempotencyCommand(message.header().messageType())) {
            CoreResponse fundsReplay = fundsReplayResponse(completedOrderArchive.fundsCommandMatch(
                    message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint));
            if (fundsReplay != null) return fundsReplay;
        }
        CoreResponse sourceSequenceResponse = checkSourceSequence(message, lastSourceSequence);
        if (sourceSequenceResponse != null) return sourceSequenceResponse;

        if (isFundsIdempotencyCommand(message.header().messageType())) {
            completedOrderArchive.reserveFundsCommandIdentity();
        }

        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition,
                        sourceKey, fingerprint);
            }
            return orderChecks.beginMatching(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint);
        }
        return applyDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
    }

    /** 直接控制入口：建立命令事实、回滚检查点及发布作用域，执行业务处理并映射已列明的业务异常。有异步续步时返回空，否则继续完成终态。 */
    private CoreResponse applyDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                    SourceKey sourceKey, CommandFingerprint fingerprint) {
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        activateFactContext(message, fingerprint);
        long beforeRuntimeRevision = accountBook.revision();
        long positionIdentityCheckpoint = identities.positionCheckpoint();
        directCommand.initialize(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforeRuntimeRevision, beforeRuntimeRevision, positionIdentityCheckpoint);
        resultBuilder.beginCommand();
        orderChecks.clearQueuedMatching();
        commits.publication.begin();
        try {
            if (directCommands.dispatch(message, clusterTimestamp)) {
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
        if (directCommand.hasControlWork()) return null;
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforeRuntimeRevision, beforeRuntimeRevision, positionIdentityCheckpoint, status, resultCode);
    }

    /** 指纹冲突直接拒绝，同一在途命令只返回等待提示，避免重复准入。 */
    private CoreResponse checkCommandReplay(CoreMessage message, CommandFingerprint fingerprint) {
        if (commandResultLog.isEmpty() && pendingMatching.isEmpty()) return null;
        long msb = message.header().commandIdMsb();
        long lsb = message.header().commandIdLsb();
        if (!commandResultLog.isEmpty()) {
            CommandResultLog.StoredResult terminalDuplicate = commandResultLog.get(msb, lsb);
            if (terminalDuplicate != null) {
                return commandResultLog.duplicateResponse(terminalDuplicate, fingerprint, state.appliedCommandCount);
            }
        }
        if (!pendingMatching.isEmpty()) {
            OrderCommand pendingDuplicate = pendingMatching.findByCommandId(msb, lsb);
            if (pendingDuplicate != null) {
                if (!pendingDuplicate.fingerprint().equals(fingerprint)) {
                    return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            CoreResultCode.IDEMPOTENCY_CONFLICT, state.appliedCommandCount,
                            EMPTY_RESPONSE_DATA);
                }
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                        com.surprising.aeron.protocol.ResponseStatus.OK, CoreResultCode.MATCHING_PENDING,
                        pendingDuplicate.sequence(),
                        EMPTY_RESPONSE_DATA);
            }
        }
        return null;
    }

    private CoreResponse fundsReplayResponse(FundsCommandIndex.Match match) {
        if (match == FundsCommandIndex.Match.ABSENT) return null;
        if (match == FundsCommandIndex.Match.CONFLICT) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, state.appliedCommandCount,
                    EMPTY_RESPONSE_DATA);
        }
        return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.APPLIED, CoreResultCode.NONE,
                    state.appliedCommandCount, EMPTY_RESPONSE_DATA);
    }

    private CoreResponse checkSourceSequence(CoreMessage message, long lastSourceSequence) {
        if (lastSourceSequence >= 0 && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    com.surprising.aeron.protocol.ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, state.appliedCommandCount);
        }
        if (lastSourceSequence < 0
                && sourceSequenceIndex.size() >= Limits.MAX_SOURCE_SEQUENCES) {
            return rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        return null;
    }

    /** 直接控制完成入口：必要时回滚被拒绝的命令，收集关闭的触发单、订单变化和账户提交，等待资金校验及发布完成，最后进入资金长期身份保留与终态保存。 */
    public CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(state.appliedCommandCount);
        if (!directCommand.finalizationPrepared()) {
            if (status != ResponseStatus.APPLIED
                    && (commits.publication.dirty()
                    || accountBook.revision() != beforeRuntimeRevision
                    || accountBook.hasUncommittedCommandChanges())) {
                rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(state.appliedCommandCount));
            }
            if (status == null) {
                commits.publication.clearBatchState();
                return finishDirectCommandContext(rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                try {
                    if (!triggers.collectClosingTriggerIds().isEmpty()) {
                        commits.publication.request();
                    }
                } catch (ArithmeticException failure) {
                    if (deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW)) return null;
                    throw failure;
                }
                if (pendingMatching.size() + orderChecks.queuedMatchingCount()
                        > pendingMatching.capacity()) {
                    orderChecks.clearQueuedMatching();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(state.appliedCommandCount));
                    return finishDirectCommandContext(rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !resultBuilder.commandChangedUserIds.isEmpty()) {
                accountBook.releaseCompletedSequentialLaneStage();
                directCommand.commitEvent(accountBook.dispatchLaneMutation(
                        nextAppliedCommandCount, resultBuilder.commandChangedUserIds,
                        accountBook.metadataOrderUsers(resultBuilder.commandChangedOrderIds), triggers.closingTriggerIds(),
                        clusterTimestamp, clusterPosition));
            }
            directCommand.markFinalizationPrepared();
        }
        if (directCommand.commitEvent() != null) {
            if (!accountBook.laneCommitComplete(directCommand.commitEvent())) {
                directCommand.result(status, resultCode);
                return null;
            }
            accountBook.releaseLaneCommit(directCommand.commitEvent());
            directCommand.clearCommitEvent();
        }
        if (status == ResponseStatus.APPLIED) {
            accountBook.validateControlFunds(
                    message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE
                    || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRANSFER_OUT
                    || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRANSFER_IN
                    || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ADJUST_INSURANCE_FUND);
        }
        directCommand.clearFinalizationPrepared();
        commits.publication.complete();
        return finishResponseAfterFundsRetention(message, clusterTimestamp, clusterPosition,
                sourceKey, fingerprint, status, resultCode, nextAppliedCommandCount);
    }

    /** 成功资金命令同步保存长期身份，再推进计数、记来源、追加衍生撮合并保存近期收据，最后移交响应缓冲租约。长期保存失败可能发生在账户和发布已完成之后，不能据此声称业务已回滚。 */
    private CoreResponse finishResponseAfterFundsRetention(CoreMessage message, long clusterTimestamp,
            long clusterPosition, SourceKey sourceKey, CommandFingerprint fingerprint,
            ResponseStatus status, CoreResultCode resultCode, long nextAppliedCommandCount) {
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            completedOrderArchive.retainFundsCommand(message.header().commandIdMsb(),
                    message.header().commandIdLsb(), fingerprint);
        }
        recordAppliedSequence(nextAppliedCommandCount, sourceKey, message.header().sourceSequence());
        orderChecks.appendQueuedMatching(clusterTimestamp, clusterPosition);
        byte[] responseData = resultBuilder.commandResultData();
        int responseOffset = resultBuilder.responseDataOffset();
        int responseLength = resultBuilder.responseDataLength();
        commandResultLog.storeOwnedResult(message.header().commandIdMsb(), message.header().commandIdLsb(), fingerprint, status, resultCode,
                state.appliedCommandCount, responseData,
                responseOffset, responseLength);
        CoreResponse response = message.ownedResponse(status, status, resultCode, state.appliedCommandCount,
                responseData, responseOffset, responseLength);
        resultBuilder.transferResponseOwnership();
        return finishDirectCommandContext(response);
    }

    private CoreResponse finishDirectCommandContext(CoreResponse response) {
        directCommand.clear();
        return finishFactContext(response);
    }

    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!directCommand.active()) {
            throw new IllegalStateException("asynchronous finalization has no command context");
        }
        directCommand.result(ResponseStatus.REJECTED, code);
        return true;
    }
    /** 唯一直接控制命令续步槽，与普通撮合序号槽分开管理。 */

    /** 写入调用方已经判定的来源序号。 */
    void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        sourceSequenceIndex.put(sourceKey, sourceSequence);
    }

    /** 列举对外命令中需要撮合路径的类型。 */
    static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || isOrderBatchCommand(type)
                || type == CoreMessageType.EXECUTE_LIQUIDATION
                || type == CoreMessageType.EXECUTE_LIQUIDATION_BATCH
                || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    /** 判断是否为批量下单、批量撤单或批量改单。 */
    public static boolean isOrderBatchCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER_BATCH || type == CoreMessageType.CANCEL_ORDER_BATCH
                || type == CoreMessageType.AMEND_ORDER_BATCH;
    }

    /** 清除命令事实激活标记，并把原响应交还调用方。 */
    public CoreResponse finishFactContext(CoreResponse response) {
        clearFactContext();
        return response;
    }

    /** 归还响应占用的缓冲租约。 */
    void releaseResponse(CoreResponse response) {
        if (response != null) responseSlotPool.release(response.dataUnsafe());
    }

    /** 确认命令、指纹和提交发布边界有效，再标记当前命令事实已经激活。 */
    public void activateFactContext(CoreMessage command, CommandFingerprint fingerprint) {
        if (command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete commit context is required before mutation");
        }
        commits.publication.assertHealthy();
        state.factContextActive = true;
    }

    /** 结束当前命令事实作用域，不在此执行账户回滚。 */
    public void clearFactContext() {
        state.factContextActive = false;
    }

    /** 将原日志位置和业务时间恢复到准入前值，再生成本次准入拒绝响应。 */
    public CoreResponse orderCheckRejected(CoreResultCode resultCode) {
        state.currentClusterTimestamp = state.orderCheckPreviousClusterTimestamp;
        state.currentClusterPosition = state.orderCheckPreviousClusterPosition;
        return rejected(resultCode);
    }

    /** 判断在途撮合期间是否可继续处理该请求：允许撮合命令和不建立业务读栅栏的运行指标查询。 */
    static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        boolean nonFencingQuery = message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY;
        return nonFencingQuery || (message.header().kind() == WireMessageKind.COMMAND
                && isMatchingCommand(message.header().messageType()));
    }

    /** 列举需要账户读取边界的业务查询类型，供入口选择读栅栏。 */
    static boolean accountLaneReadQuery(CoreMessageType type) {
        return switch (type) {
            case STATE_HASH_QUERY, BUSINESS_STATE_HASH_QUERY,
                    USER_STATE_HASH_QUERY, ORDER_STATE_HASH_QUERY, USER_STATE_QUERY, ORDER_STATE_QUERY,
                    CLIENT_ORDER_STATE_QUERY, USER_OPEN_ORDERS_QUERY, TRIGGER_ORDER_QUERY,
                    USER_OPEN_TRIGGER_ORDERS_QUERY, FUNDING_PROGRESS_QUERY, SETTLEMENT_PROGRESS_QUERY,
                    ADL_CANDIDATE_QUERY, RISK_STATE_QUERY, ALGO_ORDER_QUERY, LIQUIDATION_WORK_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    /** 列举能够只等待指定用户 Lane 的查询类型，其余账户读取使用整体前缀边界。 */
    static boolean singleUserLaneQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, USER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY,
                    USER_OPEN_ORDERS_QUERY, USER_OPEN_TRIGGER_ORDERS_QUERY, RISK_STATE_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    /** 按 UUID 两个原始长整数查在途撮合序号。 */
    public long matchingSequence(long msb, long lsb) {
        OrderCommand pending = pendingMatching.findByCommandId(msb, lsb);
        return pending == null ? 0 : pending.sequence();
    }

    /** 按可空 UUID 查在途撮合序号。 */
    public long matchingSequence(UUID commandId) {
        return commandId == null ? 0 : matchingSequence(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
    }

    /** 探测控制槽、撮合队首及批次中是否有已完成的账户或账本回执可推进。 */
    boolean hasCompletedLaneCommit() {
        var directCommit = directCommand.commitEvent();
        if (directCommit != null && directCommit.complete()) return true;

        OrderCommand pending = pendingMatching.head();
        if (pending == null) return false;
        var settlement = pending.settlementEvent();
        if (settlement != null && settlement.complete()) return true;
        if (pending.commitEvent() != null && pending.commitEvent().complete()) return true;
        OrderBatchProcessor.PendingBatch batch = pending.orderBatch();
        if (batch == null) return false;
        if (batch.itemSettlementEvent() != null && batch.itemSettlementEvent().complete()) return true;
        if (batch.settlementEvent() != null && batch.settlementEvent().complete()) return true;
        return batch.laneCommitEvent() != null && batch.laneCommitEvent().complete();
    }

    /** 有序撮合提交完成后的终态交接回调，由 Owner 调用。 */
    @FunctionalInterface
    public interface MatchingCommitHandler {
        /** 接收已经完成有序提交的命令序号及响应，由外层继续交接终态。 */
        void onCommitted(long sequence, CoreResponse response);
    }

    /** 在 Owner 线程读取当前终态已累计的实际 TRADE 事件数。 */
    public long terminalTradeCount() {
        assertOwner();
        return state.terminalTradeCount;
    }

    /** 在 Owner 上编码 Matcher 队列、在途窗口、提交水位及账户 Lane 观测指标。 */
    public byte[] encodeLaneMetrics() {
        assertOwner();
        int count = matchingAdapter.topology().accountLaneCount();
        var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                matchingAdapter.topology().matchingEngineCount(), count,
                matcherPipeline.submissionDepth(), matcherPipeline.capacity(),
                matcherPipeline.submissionHighWaterMark(), matcherPipeline.completionDepth(),
                matcherPipeline.capacity(), matcherPipeline.completionHighWaterMark(),
                pendingMatching.inFlight(), pendingMatching.capacity(),
                pendingMatching.highWaterMark(), state.committedCoreSequence);
        for (int laneId = 0; laneId < count; laneId++) {
            accountBook.writeAccountLaneMetrics(laneId, encoder);
        }
        return encoder.finish();
    }

    /** 返回 Matcher 与账户路由共用的 Lane 拓扑。 */
    LaneTopology laneTopology() {
        return matchingAdapter.topology();
    }

    /** 按指定完成栅栏和全局状态请求各账户 Lane 的快照。 */
    public List<com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        return accountBook.accountLaneSnapshots(fenceSequence, globalState);
    }

    /** 只允许在激活前恢复账户 Lane 快照，并使用当前快照全局状态核对恢复边界。 */
    public void restoreAccountLaneSnapshots(
            List<com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot> snapshots,
            long fenceSequence) {
        if (state.activated) throw new IllegalStateException("account lanes must restore before activation");
        accountBook.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    /** 以禁用 CPU 亲和的配置激活运行时。 */
    public void activate() {
        activate(TradingCpuAffinity.disabled());
    }

    /** 启动步骤：先校验亲和配置，再启动 Matcher，绑定 Owner，启动账户 Lane 和账本 Lane，最后激活适配器和发布器。 */
    public void activate(TradingCpuAffinity affinity) {
        if (state.activated) return;
        if (state.closed) throw new IllegalStateException("cannot activate closed core state");
        affinity.validateConfiguration(laneTopology());
        try {
            matcherPipeline.start(matchingAdapter::activateShard, affinity);
            bindOwner();
            completedOrderArchive.activate();
            accountBook.startAccountLanes(affinity);
            accountBook.bindLedgerPositionOwner();
            matchingAdapter.activate();
            commits.publication.activate();
            state.activated = true;
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 返回近期终态墓碑当前条数，用于保留窗口观测。 */
    int completedOrderArchiveCompletedCount() {
        return completedOrderArchive.completedCount();
    }

    /** 返回运行时保存的客户身份数量。 */
    int runtimeClientIdentityCount() {
        return identities.clientIdentityCount();
    }

    /** 取得当前业务状态哈希，在完整快照与账户访问边界内物化状态，并合并费用政策和待完成划转哈希。 */
    public long stateHash() {
        accountBook.requireSnapshotFenceReady();
        boolean acquired = acquireOwnerLaneAccess("businessStateHash");
        try {
            TradingCoreState current = tradingState();
            return canonicalBusinessStateHash(current.businessStateHash(),
                    accountBook.feePoliciesSnapshot(), accountBook.pendingTransfersSnapshot());
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }

    /** 单次快照尝试尚未完成时的明确等待信号。 */
    public static final class SnapshotNotReadyException extends IllegalStateException {
        /** 构造快照尚未准备好的明确异常，交由外层决定是否重试。 */
        public SnapshotNotReadyException() {
            super("snapshot not ready");
        }
    }

    /** 快照读取栅栏超过等待预算时的故障。 */
    public static final class SnapshotFenceTimeoutException extends IllegalStateException {
        /** 构造快照栅栏等待超时的明确异常。 */
        public SnapshotFenceTimeoutException() {
            super("snapshot fence timed out");
        }
    }

    /** 尝试毒化 Matcher 并登记带命令序号的致命分歧。 */
    public FatalMatchingDivergenceException failMatching(
            OrderCommand pending,
            String detail,
            Throwable cause) {
        try {
            matchingAdapter.poisonFromOwner("deterministic matcher settlement failed sequence="
                    + pending.sequence() + " operation=" + pending.operation());
        } catch (RuntimeException poisonFailure) {
            if (cause != null) cause.addSuppressed(poisonFailure);
            else cause = poisonFailure;
        }
        state.fatalFailure = cause == null
                ? new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail)
                : new FatalMatchingDivergenceException(pending.operation().name(), pending.sequence(), 0, detail, cause);
        return state.fatalFailure;
    }

    /** 必要时激活并确认 Owner 与健康状态，再要求直接命令、在途撮合及盘口查询均已完成。 */
    public void assertClusterCallbackComplete() {
        if (!state.activated) activate();
        assertOwner();
        assertHealthy();
        if (directCommand.active() || !pendingMatching.isEmpty()
                || bookQueries.hasPendingQueries()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback"
                    + " direct=" + directCommand.active()
                    + " matching=" + !pendingMatching.isEmpty()
                    + " book=" + bookQueries.hasPendingQueries());
        }
    }

    /** 依次检查终态索引、撮合分歧、提交发布、账户 Lane 和发布器故障，发现已有故障立即传播。 */
    public void assertHealthy() {
        completedOrderArchive.assertHealthy();
        if (state.fatalFailure != null) throw state.fatalFailure;
        if (state.commitPublicationFailure != null) throw state.commitPublicationFailure;
        if (accountBook != null) accountBook.assertAccountLanesHealthy();
        commits.publication.assertHealthy();
    }

    /** 按指定产品线解码完整分块快照，恢复协议决定候选运行时的装配与激活。 */
    public static TradingCore fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.decode(snapshot, productLine);
    }

    /** 只检查快照清单和产品线边界，不在这里运行交易命令。 */
    public static CoreSnapshotManifest inspectSnapshot(ProductLine productLine, byte[] snapshot) {
        return SectionedCoreSnapshotCodec.manifest(snapshot, productLine);
    }

    /** 在 Owner 上计算下一个 Core 序号，溢出直接报错。 */
    public long nextAppliedSequence() {
        assertOwner();
        return Math.incrementExact(state.appliedCommandCount);
    }

    /** 写入调用方提供的已受理序号，刷新可提交前缀并按需记录来源。序号有效性和调用顺序由上层保证。 */
    public void recordAppliedSequence(long sequence, SourceKey sourceKey, long sourceSequence) {
        assertOwner();
        state.appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        if (sourceKey != null) {
            recordSourceSequence(sourceKey, sourceSequence);
        }
    }

    /** 把已提交水位推进到在途撮合队首之前，或无在途时推进到已受理计数。 */
    public void refreshCommittedCoreSequence() {
        assertOwner();
        long candidate = pendingMatching.isEmpty()
                ? state.appliedCommandCount
                : Math.subtractExact(pendingMatching.firstSequence(), 1);
        if (candidate > state.committedCoreSequence) {
            state.committedCoreSequence = candidate;
        }
        accountBook.releaseRetiredOrderFence(state.committedCoreSequence);
    }

    /** 活动账户已启动且当前未持有 Owner 访问边界时，最多自旋等待五秒取得访问权。 */
    private boolean acquireOwnerLaneAccess(String operation) {
        boolean acquired = accountBook.accountLanesStarted() && !accountBook.ownerLaneAccess();
        if (acquired) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!accountBook.tryAcquireOwnerLaneAccess()) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("timed out waiting for owner lane access in " + operation);
                }
                Thread.onSpinWait();
            }
        }
        return acquired;
    }

    /** 在快照及账户读取边界内取得物化业务视图。 */
    public TradingCoreState tradingState() {
        accountBook.requireSnapshotFenceReady();
        boolean acquired = acquireOwnerLaneAccess("current state materialization");
        try {
            long currentRevision = accountBook.revision();
            long currentMarketRevision = accountBook.marketRevision();
            long currentSequence = commits.publication.publishedSequence();
            if (state.cachedTradingState == null || state.cachedRevision != currentRevision || state.cachedMarketRevision != currentMarketRevision || state.cachedSequence != currentSequence) {
                state.cachedTradingState = com.surprising.aeron.service.snapshot.state.SnapshotWriter.writeSnapshot(accountBook, identities);
                state.cachedRevision = currentRevision;
                state.cachedMarketRevision = currentMarketRevision;
                state.cachedSequence = currentSequence;
            }
            return state.cachedTradingState;
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }

    /** 已有快照栅栏时使用其固定状态，否则通过正常物化读取取得业务状态。 */
    public TradingCoreState snapshotTradingState() {
        CoreSnapshotLifecycle.SnapshotFence fence = snapshots.snapshotFence();
        return fence != null && fence.snapshotState() != null ? fence.snapshotState() : tradingState();
    }
    /** 判断是否不存在尚未完成的风险扫描。 */
    boolean runtimeRiskScanComplete() { return accountBook.firstIncompleteRiskScan() == null; }
    /** 判断指定标的风险扫描是否不存在或已经完成。 */
    boolean runtimeRiskScanComplete(String instrumentId) {
        RiskScanner.State scan = runtimeRiskScan(instrumentId);
        return scan == null || scan.complete();
    }
    /** 把外部标的转成内部身份并查风险扫描状态，未知标的返回空。 */
    RiskScanner.State runtimeRiskScan(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : accountBook.riskScan(symbolId);
    }
    /** 把外部标的转成内部身份并查标记价格，未知标的返回空。 */
    MarkPrice runtimeMarkPrice(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : accountBook.markPrice(symbolId);
    }
    /** 查询指定标的已记录的资金费结算进度值，未知标的返回零。 */
    long runtimeFundingSettlement(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? 0 : accountBook.systemFunds().fundingSettlement(symbolId);
    }
    /** 在必要的账户读取边界内查强平状态，并在返回前释放本方法取得的访问权。 */
    RiskScanner.LiquidationOrder runtimeLiquidation(long liquidationId) {
        boolean acquired = acquireOwnerLaneAccess("runtimeLiquidation");
        try {
            return accountBook.liquidation(liquidationId);
        } finally {
            if (acquired) {
                accountBook.releaseOwnerLaneAccess();
            }
        }
    }
    /** 检查当前命令事实、直接控制槽和撮合队列是否有在途业务。 */
    public boolean snapshotHasPendingCommands() {
        return state.factContextActive || directCommand.active() || pendingMatching.inFlight() != 0;
    }
    /** 取得费用政策快照，供快照和哈希边界使用。 */
    public Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies() {
        return accountBook.feePoliciesSnapshot();
    }
    /** 在必要的账户访问边界内取得运行时提供的待完成划转快照。 */
    public Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers() {
        boolean acquired = acquireOwnerLaneAccess("pendingTransfers");
        try {
            return accountBook.pendingTransfersSnapshot();
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }

    /** 取得近期收据表的只读浅层视图。其中响应仍遵守缓冲池租约，不是可永久持有的深复制。 */
    public Map<UUID, StoredResult> commandResults() {
        return commandResultLog.entries();
    }

    /** 复制来源序号表并返回只读映射，避免调用方直接修改 Owner 表。 */
    public Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(sourceSequenceIndex.snapshot());
    }

    /** 把当前线程绑定为 Owner。已有其他 Owner 时拒绝，再绑定账户运行时并检查身份表所有权。 */
    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner != null && owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
        owner = current;
        accountBook.bindOwner();
        identities.assertOwner();
    }

    /** 首次访问时绑定当前线程，已经绑定时要求调用线程一致。 */
    public void assertOwner() {
        if (owner == null) bindOwner();
        else if (owner != Thread.currentThread()) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    /** 借用准入事件，按当前运行时业务时间解析下单规则并确定结算状态与资产，再派发账户准入。 */
    public com.surprising.aeron.service.order.OrderCommand.PlaceOrderCheckEvent dispatchPlaceOrderCheck(
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence,
            long timestamp, long position) {
        var event = accountBook.acquirePlaceOrderCheck();
        com.surprising.aeron.service.treasury.SystemFunds.SettlementStatus settlementStatus;
        int assetId;
        try {
            OrderCheckService.prepare(event, accountBook, identities, userId,
                    command, state.currentClusterTimestamp);
            settlementStatus = accountBook.systemFunds().settlementStatus(event.symbolId());
            var instrument = event.instrument();
            assetId = identities.assetId(productLine.isDerivative() ? instrument.settleAsset()
                    : command.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset());
        } catch (RuntimeException | Error failure) {
            accountBook.discardPlaceOrderCheck(event);
            throw failure;
        }
        return accountBook.dispatchPlaceOrderCheck(coreSequence, userId, event, commandId,
                settlementStatus,
                assetId, identities, timestamp, position);
    }

    /** 取得在途命令必须具备的准入结果，缺失视为流程错误。 */
    public OrderCommand.ReplaceOrderCheck requireMatchingOrderCheck(OrderCommand pending) {
        OrderCommand.ReplaceOrderCheck orderCheck = pending.matchingOrderCheck();
        if (orderCheck == null) throw new IllegalStateException("replace admission is missing");
        return orderCheck;
    }

    /** 先查本命令变化缓冲，包括已删除的空值，再查账户运行时中的订单。 */
    public Order runtimeOrder(long orderId) {
        return accountBook.hasChangedOrder(orderId) ? accountBook.changedOrderValue(orderId)
                : accountBook.order(orderId);
    }

    /** 查询标的生命周期进度，未知标的返回空。 */
    public com.surprising.aeron.service.treasury.SystemFunds.LifecycleProgressRuntime
            lifecycleProgress(String instrumentId) {
        Integer symbolId = identities.findSymbolId(instrumentId);
        return symbolId == null ? null : accountBook.systemFunds().lifecycleProgress(symbolId);
    }

    /** 仅挂起具有有效续步的撮合提交：把原始变化缓冲、资金累加器及发布标记交给序号槽，清空当前公开变化视图并结束事实作用域，等待按序恢复。 */
    public void suspendMatchingCommitContext(OrderCommand pending) {
        if (!commits.publication.deferred() || pending == null
                || pending.settlementEvent() == null
                && pending.orderBatch() == null
                && !commits.controlPending(pending.sequence())) {
            throw new IllegalStateException("matching commit context cannot be suspended");
        }
        // 续步恢复之前由序号槽持有原始变化缓冲。
        // 直接移交这些缓冲，避免挂起时装箱成列表、恢复时再复制回来。
        seedChangeAccumulators();
        accountBook.acceptChangedUserIds(resultBuilder.changedUserIds()::add);
        OrderCommand context = pending;
        context.suspendCommitContext(
                resultBuilder,
                commits.publication.dirty());
        commits.publication.clearBatchState();
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    /** 确认没有其他活动事实或发布作用域后，取回该序号槽保存的变化缓冲与发布上下文，并重置本轮成交数及相关结果字段。 */
    public void restoreMatchingCommitContext(OrderCommand pending) {
        if (commits.publication.deferred() || state.factContextActive) {
            throw new IllegalStateException("another owner commit context is active");
        }
        OrderCommand context = pending;
        activateFactContext(pending.command(), pending.fingerprint());
        commits.publication.restore(context.commitSnapshotDirty());
        context.restoreCommitContext(resultBuilder);
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        context.clearCommitContext();
        resultBuilder.commandTradeCount(0);
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandRiskScanControl = null;
    }

    /** 检查下单身份不为空，且订单号或同用户客户号未命中保留终态。 */
    public void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || completedOrderArchive.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    /** 通过 Owner 活动路由、本命令变化订单及终态保留判断订单号是否被占用，不在这里扫描全部账户。 */
    public boolean orderIdInUse(long orderId) {
        return bizIndex.activeOrders().activeOrderRoute(orderId) != null
                || accountBook.changedOrderValue(orderId) != null || completedOrderArchive.orderIdInUse(orderId);
    }

    /** 中止当前发布，按检查点回滚账户命令及持仓身份注册。此入口不回滚已经执行的 Matcher 状态。 */
    public void rollbackCommandState(
            long runtimeCheckpoint,
            long positionIdentityCheckpoint,
            long commandSequence) {
        commits.publication.clearBatchState();
        accountBook.rollbackActiveCommand(runtimeCheckpoint, commandSequence);
        identities.rollbackPositionKeys(positionIdentityCheckpoint);
    }

    /** 校验触发子单与原用户一致，派生稳定的内部命令身份，编码触发信息并排入衍生撮合队列。 */
    public void queueTriggerMatching(com.surprising.aeron.model.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId, Order childOrder) {
        if (childOrder == null || childOrder.userId() != trigger.userId())
            throw new IllegalStateException("trigger child admission receipt is missing");
        long parentMsb = parentCommandId == null ? 0L : parentCommandId.getMostSignificantBits();
        long parentLsb = parentCommandId == null ? 0L : parentCommandId.getLeastSignificantBits();
        long commandIdMsb = parentMsb ^ 0x545249474745524cL;
        long commandIdLsb = parentLsb ^ trigger.triggerOrderId();
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandIdMsb, commandIdLsb,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        orderChecks.borrowQueuedMatching(
                new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                        trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)),
                childOrder, trigger);
    }

    /** 把公开变化列表中的现有身份补入原始累加器，供挂起与恢复复用，不在此清空原列表。 */
    public void seedChangeAccumulators() {
        if (resultBuilder.commandChangedUserIds != null) resultBuilder.changedUserIds().addAll(resultBuilder.commandChangedUserIds);
        if (resultBuilder.commandChangedOrderIds != null) resultBuilder.changedOrderIds().addAll(resultBuilder.commandChangedOrderIds);
    }

    /** 返回运行时记录的待处理风险扫描数量。 */
    public int pendingRiskScanCount() {
        return accountBook.incompleteRiskScanCount();
    }

    /** 计算本轮耗时及剩余风险扫描数，在 debug 开启时输出诊断。 */
    public void logRiskScan(String operation, String instrumentId, int batchSize, int pendingBefore, long startedAt) {
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        int pendingAfter = pendingRiskScanCount();
        if (!log.isDebugEnabled()) return;
        log.debug("risk scan operation={} instrumentId={} batchSize={} elapsedMicros={} "
                        + "pendingSymbolsBefore={} pendingSymbolsAfter={}",
                operation, instrumentId, batchSize, elapsedMicros, pendingBefore, pendingAfter);
    }

    /** 逐项停止活动资源，首异常继续传播。清理失败不能跳过账户和账本线程。 */
    @Override
    public void close() {
        if (state.closed) return;
        if (state.activated) assertOwner();
        state.closed = true;
        Throwable failure = null;
        try { snapshots.releaseSnapshotFence(); }
        catch (RuntimeException | Error problem) { failure = problem; }
        snapshots.inFlightMatcherSnapshot().set(null);
        try { matcherPipeline.closeShards(matchingAdapter::closeShard); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { accountBook.endOrderBatchMutationScope(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { commits.publication.close(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { accountBook.close(); }
        catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        try { completedOrderArchive.assertHealthy(); }
        catch (RuntimeException | Error problem) {
            failure = shutdownFailure(failure, new IllegalStateException("Core workers stopped with a failure", problem));
        }
        // 未确认全部线程退出时保留其输入引用。
        if (matcherPipeline.workersStopped() && accountBook.workersStopped()) {
            try {
                bookQueries.clearPendingQueries();
                clearFactContext();
                directCommand.clear();
                batches.clearPendingBatches();
                pendingMatching.clear();
                crossShardCancellations.clear();
                orderChecks.clearLifecycleScopes();
            } catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
            try { completedOrderArchive.close(); }
            catch (RuntimeException | Error problem) { failure = shutdownFailure(failure, problem); }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private static Throwable shutdownFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    /** 先保存成交计划与 taker 引用，再清在途实时引用。仅捕获 TRADE 事件，实时捕获异常只计故障，不回滚交易。 */
    public void captureRealtimeTrades(OrderCommand pending) {
        var plan = pending.settlementPlan();
        Order taker = pending.realtimeTakerOrder();
        OrderCommand.ResolvedPlaceOrder resolvedTaker = pending.realtimeResolvedTakerOrder();
        pending.realtimeTakerOrder(null);
        pending.realtimeResolvedTakerOrder(null);
        if (state.realtimeCapture == null || !state.realtimeCapture.active()) return;
        try {
            for (int index = 0; index < plan.matcherEventCount(); index++) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() != MatcherEventType.TRADE) continue;
                if (resolvedTaker != null) {
                    state.realtimeCapture.trade(pending.command().header().userId(), resolvedTaker.orderId(),
                            resolvedTaker.symbolId(), resolvedTaker.side(), plan.coreSequence(), index,
                            match.price(), match.size(), match.matchedOrderId(), match.matchedOrderUid());
                } else {
                    state.realtimeCapture.trade(taker, plan.coreSequence(), index, match.price(), match.size(),
                            match.matchedOrderId(), match.matchedOrderUid());
                }
            }
        } catch (RuntimeException failure) { state.realtimeCapture.failed(); }
    }

    /** 统计结算计划中的 TRADE 事件条数，不以订单数或成交数量代替。 */
    public static long tradeCount(com.surprising.aeron.service.settlement.TradeSettlement.Plan plan) {
        long trades = 0;
        for (int index = 0; index < plan.matcherEventCount(); index++)
            if (plan.matcherEvent(index).eventType() == MatcherEventType.TRADE) trades++;
        return trades;
    }

    /** 以基础状态哈希为起点，依次混入非空费用政策和待完成划转摘要。调用方仍须提供稳定的映射遍历顺序。 */
    public static long canonicalBusinessStateHash(
            long base,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    /** 按提供映射的值遍历顺序混入待完成划转的业务字段。 */
    static long computeTransferHash(
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> transfers) {
        if (transfers.isEmpty()) return 0;
        long digest = ExecutionState.HASH_OFFSET_BASIS;
        for (var command : transfers.values()) {
            digest = mix(digest, command.transferId());
            digest = mix(digest, command.sourceUserId());
            digest = mix(digest, command.targetUserId());
            digest = mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = mixText(digest, command.sourceAccountType());
            digest = mixText(digest, command.targetAccountType());
            digest = mixText(digest, command.asset());
            digest = mix(digest, command.amountUnits());
            digest = mixText(digest, command.referenceId());
            digest = mixText(digest, command.reason());
        }
        return digest;
    }

    /** 列举需要长期资金身份去重的余额调整、转出、转入和完成划转命令。 */
    static boolean isFundsIdempotencyCommand(CoreMessageType type) {
        return type == CoreMessageType.ADJUST_BALANCE || type == CoreMessageType.TRANSFER_OUT
                || type == CoreMessageType.TRANSFER_IN || type == CoreMessageType.COMPLETE_TRANSFER;
    }

    /** 按提供映射的值遍历顺序混入费用政策字段。 */
    static long computeFeePolicyHash(
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> policies) {
        if (policies.isEmpty()) return 0;
        long digest = ExecutionState.HASH_OFFSET_BASIS;
        for (var policy : policies.values()) {
            digest = mix(digest, policy.policyId());
            digest = mix(digest, policy.policyRevision());
            digest = mix(digest, policy.userId());
            digest = mixText(digest, policy.instrumentId());
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
        if (value == null || value.isEmpty()) return mix(hash, 0);
        int length = value.length();
        boolean isAscii = true;
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) >= 0x80) {
                isAscii = false;
                break;
            }
        }
        if (isAscii) {
            long result = mix(hash, length);
            for (int i = 0; i < length; i++) {
                result ^= value.charAt(i);
                result *= ExecutionState.HASH_PRIME;
            }
            return result;
        }
        int utf8Len = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) utf8Len++;
            else if (c < 0x800) utf8Len += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                utf8Len += 4;
                i++;
            } else if (!Character.isSurrogate(c)) utf8Len += 3;
            else utf8Len += 1;
        }
        long result = mix(hash, utf8Len);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                result ^= c;
                result *= ExecutionState.HASH_PRIME;
            } else if (c < 0x800) {
                result ^= (0xc0 | (c >>> 6));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= ExecutionState.HASH_PRIME;
            } else if (!Character.isSurrogate(c)) {
                result ^= (0xe0 | (c >>> 12));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | ((c >>> 6) & 0x3f));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= ExecutionState.HASH_PRIME;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                int point = Character.toCodePoint(c, value.charAt(++i));
                result ^= (0xf0 | (point >>> 18));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | ((point >>> 12) & 0x3f));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | ((point >>> 6) & 0x3f));
                result *= ExecutionState.HASH_PRIME;
                result ^= (0x80 | (point & 0x3f));
                result *= ExecutionState.HASH_PRIME;
            } else {
                result ^= '?';
                result *= ExecutionState.HASH_PRIME;
            }
        }
        return result;
    }

    /** 按从低到高的八个字节混入长整数。自然溢出属于哈希算法，与资金算术无关。 */
    public static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= ExecutionState.HASH_PRIME;
        }
        return result;
    }

    /** 将每个来源键及其序号的摘要异或合并，因此不依赖来源表遍历顺序。 */
    public static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
        long digest = 0;
        for (Map.Entry<SourceKey, Long> entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    /** 对来源类型、来源身份及该来源序号计算单项摘要。 */
    public static long sourceSequenceDigest(SourceKey key, long sequence) {
        long digest = ExecutionState.HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    /** 使用当前已受理计数生成业务拒绝响应，不在此推进命令水位。 */
    public CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, state.appliedCommandCount);
    }

    /** 当前批量命令不适用独立窗口的内部信号，避免为正常分流创建栈轨迹。 */
    public static final class PipelinedBatchNotApplicable extends RuntimeException {
        /** 构造轻量窗口不适用信号，不生成栈轨迹或启用 suppressed 异常。 */
        public PipelinedBatchNotApplicable() {
            super(null, null, false, false);
        }
    }

    /** 来源类型与来源身份组成的序号跟踪键。 */
    public record SourceKey(CommandSource source, long sourceId) {
    }

    /** 使用默认快照身份取得完整快照字节，由快照生命周期负责等待和编码。 */
    public byte[] snapshot() { return snapshots.snapshot(); }
    /** 使用指定快照身份取得完整快照字节。 */
    public byte[] snapshot(long snapshotId) { return snapshots.snapshot(snapshotId); }

    /** Owner 上的跨 Matcher 分片撤单推进器：逐项派发、等待证据并保留原顺序，完整结果再交给已有结算路径。后续队列仅由其 Owner 推进。参数 owner：拥有本跨分片撤单推进器的运行时。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class CrossShardCancellationCoordinator {
        /** 拥有此撤单推进器的运行时，所有队列推进遵守其 Owner 约束。 */
        private final TradingCore owner;
        /** 按登记顺序等待派发与回执的跨分片撤单工作。 */
        private final java.util.ArrayDeque<Progress> pending = new java.util.ArrayDeque<>();

        /** 同一命令只登记一次跨分片撤单，保存其稳定撤单描述并立即尝试推进。 */
        public void start(OrderCommand command, java.util.List<com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder> orders) {
            if (command.crossShardCancellationStarted()) return;
            command.crossShardCancellationStarted(true);
            pending.addLast(new Progress(owner, command, orders));
            poll();
        }

        /** 每轮最多推进 64 次队首操作，逐项向对应 Matcher 提交撤单并收集证据。 */
        public void poll() {
            for (int work = 0; work < 64 && !pending.isEmpty(); work++) {
                Progress progress = pending.peekFirst();
                if (progress.token != 0) {
                    var result = (com.surprising.aeron.service.matcher.OrderMatchingResult.Control) owner.matcherPipeline().pollControl(progress.shard, progress.token);
                    if (result == null) return;
                    progress.results.add(result);
                    progress.failed = !result.accepted();
                    progress.token = 0;
                    progress.index++;
                }
                if (progress.index < progress.orders.size()) {
                    if (progress.failed) {
                        progress.results.add(new com.surprising.aeron.service.matcher.OrderMatchingResult.Control(false, "NOT_SUBMITTED"));
                        progress.index++;
                        continue;
                    }
                    var order = progress.orders.get(progress.index);
                    progress.shard = owner.matchingAdapter().topology().matcherShardId(order.symbolId());
                    progress.currentOrder = order;
                    progress.token = owner.matcherPipeline().submitControl(progress.shard, progress);
                    continue;
                }
                var command = progress.command;
                var evidenced = owner.matchingAdapter().completeControlCancellations(
                        command.sequence(), command.command().header().commandId(),
                        command.command().header().submittedAtEpochMillis(), progress.orders, progress.results);
                pending.removeFirst();
                command.crossShardCancellationStarted(false);
                owner.matchingFlow.publishMatchingCompletion(command.sequence(), evidenced);
                command.matchingSubmitted();
                owner.matchingProgress.submissionCompleted(command);
            }
        }

        /** 只清本地等待队列，依赖关闭顺序保证工作线程已结束。 */
        void clear() { pending.clear(); }

        /** 判断是否还有跨分片撤单编排等待推进。 */
        public boolean hasPending() { return !pending.isEmpty(); }

        /** 一条跨分片撤单的推进状态，保存稳定订单描述、对应结果及当前控制任务身份。只由 Owner 修改。 */
        private static final class Progress implements java.util.function.Supplier<com.surprising.aeron.service.matcher.OrderMatchingResult.Control> {
            final TradingCore owner;
            final OrderCommand command;
            /** 本轮按顺序借用的稳定撤单描述，列表生命周期由命令保证。 */
            final java.util.List<com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder> orders;
            final java.util.ArrayList<com.surprising.aeron.service.matcher.OrderMatchingResult.Control> results;
            int index, shard;
            long token;
            com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder currentOrder;
            boolean failed;
            /** 保留本命令及稳定撤单描述，建立同顺序的结果列表，供逐项串行推进。 */
            Progress(TradingCore owner, OrderCommand command, java.util.List<com.surprising.aeron.service.matcher.OrderBookEngine.CancellationOrder> orders) {
                this.owner = owner; this.command = command; this.orders = orders;
                results = new java.util.ArrayList<>(orders.size());
            }

            @Override
            public com.surprising.aeron.service.matcher.OrderMatchingResult.Control get() {
                var order = currentOrder;
                return owner.matchingAdapter().cancelControlOrder(command.command().header().submittedAtEpochMillis(),
                        order.userId(), order.orderId(), order.symbolId());
            }
        }
    }

    /** 按撮合命令类型准备普通下单、触发子单、撤单或改单结算事件。 */
    public com.surprising.aeron.service.settlement.TradeSettlement prepareForMatching(OrderCommand pending) {
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

    /** 要求下单准入收据已挂上，用初始用户 Lane 和原提交栅栏准备结算事件。单笔先撤稍后由准入事件并入，并保留 Matcher 发布租约。 */
    private com.surprising.aeron.service.settlement.TradeSettlement preparePlace(OrderCommand pending) {
        var orderCheck = java.util.Objects.requireNonNull(pending.placeOrderCheck(), "place admission receipt");
        com.surprising.aeron.service.settlement.TradeSettlement direct = accountBook.prepareDirectMatcherSettlement(
                pending.sequence(), directInitialLaneMask(orderCheck.userId()), orderCheck,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending),
                identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                java.util.List.of(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    /** 要求改单准入已完成，按原提交栅栏准备替换结算及发布租约。 */
    private com.surprising.aeron.service.settlement.TradeSettlement prepareReplacement(OrderCommand pending) {
        var orderCheck = requireMatchingOrderCheck(pending);
        com.surprising.aeron.service.settlement.TradeSettlement direct = accountBook.prepareDirectReplacement(
                pending.sequence(), pending.sequence(), directInitialLaneMask(orderCheck.userId()), orderCheck,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        if (state.realtimeCapture != null) pending.realtimeTakerOrder(direct.admittedOrder());
        return direct;
    }

    /** 解码触发执行字段，要求原触发单及子单收据存在，准备子单结算并登记触发完成信息，再保留发布租约及可选实时引用。 */
    private com.surprising.aeron.service.settlement.TradeSettlement prepareTrigger(OrderCommand pending) {
        long[] execute = pending.decodedCommand().trigger();
        var trigger = java.util.Objects.requireNonNull(pending.sourceTrigger(),
                "admitted trigger is missing");
        com.surprising.aeron.service.order.Order triggerOrder = java.util.Objects.requireNonNull(pending.triggerOrderCheckOrder(),
                "trigger admission receipt is missing");
        com.surprising.aeron.service.settlement.TradeSettlement direct = accountBook.prepareDirectMatcherSettlement(pending.sequence(),
                directInitialLaneMask(triggerOrder.userId()), triggerOrder, null, 1,
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                pending.preMatchingCancellationOrderIds(), pending.laneResultTarget());
        direct.triggerCompletion(trigger, execute[3]);
        reserveMatcherPublication(pending, direct);
        if (state.realtimeCapture != null) pending.realtimeTakerOrder(triggerOrder);
        return direct;
    }

    /** 按用户、订单身份、原提交栅栏及结果槽准备直接撤单结算事件，并保留 Matcher 发布租约。 */
    private com.surprising.aeron.service.settlement.TradeSettlement prepareCancellation(OrderCommand pending) {
        com.surprising.aeron.service.settlement.TradeSettlement direct = accountBook.prepareDirectCancellation(pending.sequence(),
                pending.command().header().userId(), pending.decodedCommand().cancelOrder().orderId(),
                pending.command().header().commandId(), matchingFlow.matcherShard(pending), identities,
                pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.laneResultTarget());
        reserveMatcherPublication(pending, direct);
        return direct;
    }

    /** 把结算事件与计划登记到在途命令，并取得 Matcher 发布所需的生产者租约。 */
    private void reserveMatcherPublication(OrderCommand pending, com.surprising.aeron.service.settlement.TradeSettlement direct) {
        pending.settlement(direct, direct.plan());
        direct.reserveMatcherPublication();
    }

    private long directInitialLaneMask(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("direct settlement user is required");
        return accountBook.topology().accountLaneMask(userId);
    }

    /** 借出来源序号索引，写入仍由 Owner 的有序命令完成路径负责。 */
    public SourceSequenceIndex sourceSequenceIndex() { return sourceSequenceIndex; }
}
