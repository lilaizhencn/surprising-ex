package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.execution.CommandResultLedger.StoredResult;

import com.surprising.aeron.service.state.DerivativeAccountCommandProcessor;
import com.surprising.aeron.service.execution.CoreSnapshotLifecycle.SnapshotFence;
import com.surprising.aeron.service.execution.OrderBookQueryService.CompletedBookQuery;
import com.surprising.aeron.service.execution.OrderBookQueryService.BookBootstrapSession;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
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
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskScanRuntime;
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.MarkPriceRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.RuntimeSettlementProcessor;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 唯一交易执行入口，按集群日志顺序组织准入、撮合和有序提交。
 * 产品命令、批量执行、结果账本、查询及快照由所属组件管理；不持有旧探测运行时副本。
 */
public final class TradingCoreRuntime implements AutoCloseable {


    /** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
    final InstrumentSettlementCommands instrumentSettlement = new InstrumentSettlementCommands(this);


    /** 撮合命令准入：校验订单与生命周期依赖，登记延后命令，不提交未完成结算。 */
    final MatchingCommandAdmission admissions = new MatchingCommandAdmission(this);

    /** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
    final OrderedCommitCoordinator commits = new OrderedCommitCoordinator(this);

    /** 余额调整与资金转入转出命令；维护转账幂等状态。 */
    final BalanceTransferCommands balances = new BalanceTransferCommands(this);

    /** 币对配置与维护状态命令；六产品线按所属运行时隔离。 */
    final InstrumentConfigurationCommands instruments = new InstrumentConfigurationCommands(this);

    /** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
    final PerpetualFundingCommands funding = new PerpetualFundingCommands(this);

    /** 衍生品标记价、风险续扫、保险及 ADL 命令。 */
    final DerivativeRiskCommands derivativeRisk = new DerivativeRiskCommands(this);

    /** 衍生品保证金和杠杆命令；不处理现货资金冻结。 */
    final DerivativeAccountCommands derivativeAccounts = new DerivativeAccountCommands(this);

    /** 触发单和算法单生命周期命令；各产品线使用所属运行时和订单规则。 */
    final TriggerOrderCommands triggers = new TriggerOrderCommands(this);

    /** 当前命令的结果与变更 ID；owner 串行复用，在命令完成边界编码。 */
    final CommandResultBuilder resultBuilder = new CommandResultBuilder(this);

    /** 批量命令准备、结算派发与终态收集；仅交易 owner 推进。 */
    final OrderBatchExecutor batches = new OrderBatchExecutor(this);
    /** 跨 matcher 清算撤单的非阻塞推进器。 */
    final CrossShardCancellationCoordinator crossShardCancellations = new CrossShardCancellationCoordinator(this);

    /** 快照屏障与异步编码生命周期；不持有第二份交易状态。 */
    final CoreSnapshotLifecycle snapshots = new CoreSnapshotLifecycle(this);

    /** 盘口查询与分页会话；仅在查询边界物化视图。 */
    final OrderBookQueryService bookQueries = new OrderBookQueryService(this);

    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    com.surprising.aeron.service.state.realtime.RealtimeStateCapture realtimeCapture;
    com.surprising.aeron.service.state.realtime.RealtimeStateCapture attachRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        realtimeCapture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                outbox, productLine, identities);
        runtimeState.realtimeCapture(realtimeCapture);
        realtimeReads = new RealtimeReadCoordinator(runtimeState, matcherPipeline, matchingAdapter, realtimeCapture);
        return realtimeCapture;
    }

    /** 异步实时快照和盘口读取的生命周期管理。 */
    RealtimeReadCoordinator realtimeReads;

    long realtimeExportSequence() { return runtimeProjectionJournal.publishedSequence(); }
    boolean realtimeSnapshotPending() { return realtimeReads != null && realtimeReads.realtimeSnapshotPending(); }
    boolean realtimeBookPending() { return realtimeReads != null && realtimeReads.realtimeBookPending(); }
    int pollRealtimeSnapshot() { return realtimeReads == null ? 0 : realtimeReads.pollRealtimeSnapshot(); }
    int pollRealtimeBook() { return realtimeReads == null ? 0 : realtimeReads.pollRealtimeBook(); }

    void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (realtimeReads == null || realtimeReads.realtimeSnapshotPending()) return;
        assertClusterCallbackComplete();
        realtimeReads.captureRealtimeSnapshot(userId, snapshotId, position, timestamp, realtimeExportSequence());
    }

    void captureRealtimeBook(String symbol, long position, long timestamp) {
        if (realtimeReads == null || realtimeReads.realtimeBookPending()) return;
        assertClusterCallbackComplete();
        realtimeReads.captureRealtimeBook(symbol, position, timestamp);
    }

    /** 幂等结果账本允许保留的最大命令数。 */
    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    /** 幂等响应账本的总字节预算。 */
    static final long MAX_RESULT_LEDGER_BYTES = 32L * 1024 * 1024;
    /** 单条可保留响应的字节上限，不得超过账本总预算。 */
    static final int MAX_STORED_RESPONSE_BYTES = Math.toIntExact(MAX_RESULT_LEDGER_BYTES);
    /** 来源序号去重表的容量上限。 */
    static final int MAX_SOURCE_SEQUENCES = 65_536;
    /** 单次盘口响应允许返回的最大档位数。 */
    static final int MAX_BOOK_RESPONSE_LEVELS = 10_000;
    /** 单次盘口响应的编码字节上限。 */
    static final int MAX_BOOK_RESPONSE_BYTES = 1024 * 1024;
    /** 同步完成边界等待撮合的超时时间，单位为纳秒。 */
    static final long MATCHING_AWAIT_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 同时保留的盘口分页快照会话上限。 */
    static final int MAX_BOOK_BOOTSTRAP_SNAPSHOTS = 4;
    /** 未显式指定时，每轮触发扫描处理的条数。 */
    static final int DEFAULT_TRIGGER_SCAN_BATCH_SIZE = 2;
    /** 交易运行时日志输出器。 */
    static final System.Logger LOG = System.getLogger(TradingCoreRuntime.class.getName());
    /** 撮合阶段指标日志的输出间隔配置；零表示关闭。 */
    static final int MATCHING_PHASE_LOG_INTERVAL = Integer.getInteger(
            "surprising.aeron.matching-phase-log-interval", 0);
    /** 是否收集撮合阶段耗时；关闭时不创建时间戳登记表。 */
    static final boolean MATCHING_PHASE_METRICS_ENABLED = MATCHING_PHASE_LOG_INTERVAL > 0;
    /** 无订单结果共用的空数组，不得写入。 */
    static final long[] EMPTY_ORDER_IDS = new long[0];
    /** 独立快照调用等待完成的超时时间，单位为秒。 */
    static final int STANDALONE_SNAPSHOT_TIMEOUT_SECONDS = Integer.getInteger(
            "surprising.aeron.standalone-snapshot-timeout-seconds", 300);
    /** 确定性状态摘要的 FNV 初始值。 */
    static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    /** 确定性状态摘要的 FNV 混合乘数。 */
    static final long HASH_PRIME = 0x100000001b3L;
    /** 无需响应正文时共用的空字节数组。 */
    static final byte[] EMPTY_RESPONSE_DATA = new byte[0];
    /** 协议中的撮合处理中结果码，保留现有线协议取值。 */
    static final int MATCHING_PENDING_WIRE_CODE = 66;
    /** 撮合在途命令上限，用于入口背压和有界容量。 */
    static final int MAX_PENDING_MATCHING = Integer.getInteger(
            "surprising.aeron.max-pending-matching", 4_096);
    /** 所属产品线；所有命令、账户与币对均在此边界隔离。 */
    final ProductLine productLine;
    /** 拥有当前执行上下文的唯一 owner；不得在其他线程直接修改其状态。 */
    Thread owner;
    /** 已提交业务变化的索引更新器；不复制完整交易状态。 */
    final com.surprising.aeron.service.state.RuntimeFactIndexes factIndexes;
    /** 有界命令结果账本；保存幂等响应而非业务状态副本。 */
    final CommandResultLedger resultLedger;

    /** 各命令来源已应用序号，供来源去重及恢复使用。 */
    final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    /** 在途命令环；保存日志顺序及准入、撮合、结算的关联。 */
    final PendingMatchingRing pendingMatching;
    /** 待处理准入通知的撮合分片位图；仅调度提示，不是复制状态。 */
    long placeAdmissionReadyShardMask;
    // Owner-only scheduling evidence, never replicated state or a terminal-operation count.
    long matchingProgressSequence;
    /** 下一次空轮询健康检查时间；纳秒单调时钟。 */
    long nextMatchingHealthCheckNs = System.nanoTime();

    /** 每个在途序号的 Lane 提交上下文，与 pendingMatching 同生命周期。 */
    final LaneCommandContextRing laneCommandContexts;
    /** 撮合派发和完成通知通道；只在完成边界交接数据。 */
    final MatcherPipelineGroup matcherPipeline;

    /** 真实撮合器适配器；命令经 matcher 队列推进。 */
    final DeterministicExchangeCoreAdapter matchingAdapter;

    /** positionUserIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final PositionUserIndex positionUserIndex;
    /** openInterestIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final OpenInterestIndex openInterestIndex;
    /** triggerOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final TriggerOrderIndex triggerOrderIndex;
    /** algoOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final AlgoOrderIndex algoOrderIndex;
    /** liquidationIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final LiquidationIndex liquidationIndex;
    /** cancelAllAfterIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final CancelAllAfterIndex cancelAllAfterIndex;
    /** activeOrderIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final ActiveOrderIndex activeOrderIndex;
    /** adlPositionIndex 业务查询索引；由提交变化维护，不作为独立权威状态。 */
    final AdlPositionIndex adlPositionIndex;
    /** 对外导出与恢复的状态边界，和权威运行时分离。 */
    final CoreExportState exportState;
    /** 撮合各阶段的运行观测指标，不参与确定性业务判断。 */
    final CoreMatchingPhaseMetrics matchingPhaseMetrics = new CoreMatchingPhaseMetrics();
    /** 采样开启时记录的提交时间，完成后移除；关闭时不分配此表。 */
    final Map<Long, Long> matchingSubmitNanos = MATCHING_PHASE_METRICS_ENABLED
            ? new HashMap<>() : null;
    /** 已完成撮合处理计数；用于运行观测，不作为资金依据。 */
    long completedMatchingCount;

    /** 已提交成交数量；不能与订单业务操作数混用。 */
    long terminalTradeCount;
    /** 已终结订单及触发身份的有界保留区，防止短期重复执行。 */
    final TerminalStateRetention terminalRetention;
    /** 审计用业务状态摘要，用于快照和重放核对。 */
    long auditBusinessStateHash;
    /** 审计用资金状态摘要，用于守恒和恢复核对。 */
    long auditFundsStateHash;
    /** 缓存审计摘要对应的命令序号。 */
    long auditHashCoreSequence;

    /** 提交日志与快照版本边界；与交易日志共同约束恢复。 */
    final com.surprising.aeron.service.state.RuntimeCommitJournal runtimeProjectionJournal;
    /** 当前完整提交点；只描述提交边界，不逐命令复制全量状态。 */
    RuntimeProjectionPoint currentProjectionPoint;

    /** 当前命令的提交资源预留；挂起时交回对应序号上下文。 */
    CoreAdmissionReservation currentAdmission;
    /** 已接收并应用的命令序号上界；可能高于已完成结算水位。 */
    long appliedCommandCount;
    /** 连续完成结算的全局命令水位；唯一 owner 推进。 */
    long committedCoreSequence;
    /** 当前命令是否包含合法外部资金调整，用于守恒核对。 */
    boolean commandExternalAdjustment;
    /** 确定性业务变更后发布失败；要求从快照和日志恢复。 */
    RuntimeException commitPublicationFailure;
    /** 协议探测命令的持久化值；不参与订单或资金计算。 */
    long probeValue;
    /** 当前业务状态摘要缓存，提交边界更新。 */
    long cachedBusinessStateHash;
    /** 手续费策略摘要缓存，策略变化时更新。 */
    long cachedFeePolicyHash;
    /** 待完成转账摘要缓存，转账状态变化时更新。 */
    long cachedTransferHash;

    /** 字符串与 primitive 标识的唯一字典；生命周期覆盖该运行时。 */
    final RuntimeIdentityRegistry identities;
    /** 唯一权威运行时状态；账户可变数据由所属 Lane 维护。 */
    final TradingRuntimeState runtimeState;

    /** 撮合或确定性执行的不可恢复错误，阻止继续交易。 */
    com.surprising.aeron.service.matching.FatalMatchingDivergenceException fatalFailure;

    /** 收集各 Lane 国库变化的复用缓冲；提交完清空。 */
    final RuntimeTreasuryDelta mergedLaneTreasuryDelta =
            new RuntimeTreasuryDelta(RuntimeTreasuryDelta.ORDER_BATCH_CAPACITY);
    /** 当前命令资金变化累加器；挂起/恢复时保持命令隔离。 */
    final com.surprising.aeron.service.state.RuntimeFundsAccumulator commandFundsAccumulator =
            new com.surprising.aeron.service.state.RuntimeFundsAccumulator(64);
    /** 已释放的提交预留对象池；复用前必须清空原命令引用。 */
    final java.util.ArrayDeque<CoreAdmissionReservation> admissionReservationPool =
            new java.util.ArrayDeque<>();

    /** 当前激活命令在复制日志中的位置。 */
    long currentClusterPosition;
    /** 当前激活命令的集群时间，保证确定性业务时间。 */
    long currentClusterTimestamp;
    /** 准入临时切换前的日志位置，退出准入时恢复。 */
    long admissionPreviousClusterPosition;
    /** 准入临时切换前的集群时间，退出准入时恢复。 */
    long admissionPreviousClusterTimestamp;
    /** 运行时是否已绑定 owner 并启动消费者。 */
    boolean activated;
    /** 资源是否已释放，避免重复关闭。 */
    boolean closed;

    public TradingCoreRuntime(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), 0, 0, null, null);
    }

    TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), 0, 0, matcherSnapshotCapture, null);
    }

    TradingCoreRuntime(ProductLine productLine, MatcherSnapshotCapture matcherSnapshotCapture,
                   SnapshotEncoder snapshotEncoder) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), CoreExportState.passive(), new TerminalStateRetention(), null,
                0, Map.of(), Map.of(), 0, 0, matcherSnapshotCapture, snapshotEncoder);
    }

    TradingCoreRuntime(
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
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> restoredFeePolicies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> restoredPendingTransfers,
            long restoredAuditBusinessStateHash,
            long restoredAuditFundsStateHash,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.probeValue = probeValue;
        this.resultLedger = new CommandResultLedger(commandResults);
        this.lastSourceSequences = lastSourceSequences;
        admissions.pendingLifecycleScopes = new LinkedHashMap<>();
        batches.pendingOrderBatches = new LinkedHashMap<>();
        admissions.deferredMatching = new LinkedHashMap<>();
        snapshots.lastSnapshotId = matcherSnapshot == null ? 0 : matcherSnapshot.snapshotId();
        this.exportState = exportState;
        this.terminalRetention = terminalRetention;
        long restoredBusinessStateHash = canonicalBusinessStateHash(
                snapshotState.businessStateHash(), restoredFeePolicies, restoredPendingTransfers);
        var topology = matcherSnapshot == null
                ? com.surprising.aeron.service.state.LaneTopology.configured(
                        Boolean.getBoolean("surprising.aeron.p10-characterization"))
                : matcherSnapshot.topology();
        this.identities = new RuntimeIdentityRegistry();
        this.runtimeState = com.surprising.aeron.service.state.RuntimeStateProjector.project(
                snapshotState, identities, topology);
        this.activeOrderIndex = new ActiveOrderIndex(snapshotState, identities);
        this.matchingAdapter = matcherSnapshot == null
                ? new DeterministicExchangeCoreAdapter(false)
                : new DeterministicExchangeCoreAdapter(snapshotState, activeOrderIndex.orders(),
                        appliedCommandCount, matcherSnapshot, false, restoredBusinessStateHash);
        this.pendingMatching = new PendingMatchingRing(
                Math.min(MAX_PENDING_MATCHING, matchingAdapter.topology().matcherWindowSize()),
                matchingAdapter.topology().matchingEngineCount(),
                matchingAdapter.topology().accountLaneCount());
        commits.appliedMatcherSequences = new long[matchingAdapter.topology().matchingEngineCount() + 1];
        commits.appliedMatcherPrefixDigests = new long[commits.appliedMatcherSequences.length];
        commits.initializeMatcherProgress(matcherSnapshot);
        this.laneCommandContexts = pendingMatching.contexts();
        this.matcherPipeline = new MatcherPipelineGroup(
                matchingAdapter.topology().matchingEngineCount(),
                Math.min(matchingAdapter.topology().matcherWindowSize(),
                        matchingAdapter.topology().matchingCompletionCapacity()), false);
        snapshots.matcherSnapshotCapture = matcherSnapshotCapture == null
                ? snapshots::captureMatcherSnapshot : matcherSnapshotCapture;
        if (snapshotEncoder == null) {
            snapshots.snapshotEncoder = image -> CompletableFuture.completedFuture(
                    SectionedCoreSnapshotCodec.encode(image));
        } else {
            snapshots.snapshotEncoder = snapshotEncoder;
        }
        this.positionUserIndex = new PositionUserIndex(snapshotState, identities, topology);
        this.openInterestIndex = new OpenInterestIndex(snapshotState, identities);
        this.triggerOrderIndex = new TriggerOrderIndex(snapshotState);
        this.algoOrderIndex = new AlgoOrderIndex(snapshotState);
        this.liquidationIndex = new LiquidationIndex(snapshotState);
        this.cancelAllAfterIndex = new CancelAllAfterIndex(snapshotState);
        this.adlPositionIndex = new AdlPositionIndex(snapshotState, identities);
        this.factIndexes = new com.surprising.aeron.service.state.RuntimeFactIndexes(
                positionUserIndex, openInterestIndex, triggerOrderIndex, algoOrderIndex,
                liquidationIndex, cancelAllAfterIndex, activeOrderIndex, adlPositionIndex);
        this.runtimeState.restoreFeePolicies(restoredFeePolicies);
        this.runtimeState.restorePendingTransfers(restoredPendingTransfers);
        this.cachedFeePolicyHash = computeFeePolicyHash(restoredFeePolicies);
        this.cachedTransferHash = computeTransferHash(restoredPendingTransfers);
        this.auditBusinessStateHash = restoredAuditBusinessStateHash == 0
                ? com.surprising.aeron.service.state.RollingBusinessStateHash.compute(snapshotState)
                : restoredAuditBusinessStateHash;
        this.auditFundsStateHash = restoredAuditFundsStateHash == 0
                ? com.surprising.aeron.service.state.RollingFundsStateHash.compute(snapshotState)
                : restoredAuditFundsStateHash;
        this.auditHashCoreSequence = Long.MIN_VALUE;
        this.cachedBusinessStateHash = currentBusinessStateHash();
        commits.runtimePatchRevision = snapshotState.revision();
        this.factIndexes.rebuild(snapshotState, identities);
        this.runtimeProjectionJournal = com.surprising.aeron.service.state.RuntimeCommitJournal.passive(
                productLine, snapshotState, cachedBusinessStateHash, auditFundsStateHash,
                projectionSequence);
        this.currentProjectionPoint = runtimeProjectionJournal.initialPoint();
        runtimeState.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
    }

    static TradingCoreRuntime prepareRestore(
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
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers,
            long auditBusinessStateHash,
            long auditFundsStateHash) {
        if (projectionSequence < 0 || appliedCommandCount < 0 || commandResults == null
                || commandResults.size() > MAX_IDEMPOTENCY_RESULTS || lastSourceSequences == null
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || matcherSnapshot == null || snapshotState == null
                || snapshotState.productLine() != productLine || exportState == null
                || terminalRetention == null || feePolicies == null || pendingTransfers == null) {
            throw new IllegalArgumentException("invalid passive restored probe state");
        }
        CommandResultLedger.validateResultLedger(commandResults);
        return new TradingCoreRuntime(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                snapshotState, exportState, terminalRetention, matcherSnapshot, projectionSequence,
                feePolicies, pendingTransfers, auditBusinessStateHash, auditFundsStateHash, null, null);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    /** 当前入口是否允许独立命令流水准入。 */
    boolean clusterPipelineAdmission;
    /** 当前解码缓存对应的信封身份；入口返回时恢复上一上下文。 */
    CoreMessage decodedIngressMessage;
    /** 当前信封不可变解码结果；作用域重算不会再次解码。 */
    DecodedMatchingCommand decodedIngressCommand;

    /** 全局元数据更新及 Lane 控制任务派发，不要求事先停驻所有账户。 */
    boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        return switch (message.header().messageType()) {
            case APPLY_FUNDING, APPLY_MARK_PRICE, UPDATE_RISK_SCAN_CONTROL, ADJUST_INSURANCE_FUND -> false;
            case CONTINUE_RISK_SCAN -> runtimeState.firstRiskIncompleteScan() == null;
            default -> true;
        };
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        return applyDecodedCommand(message, timestamp, position, decoded, true);
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent) {
        boolean previousAdmission = clusterPipelineAdmission;
        CoreMessage previousMessage = decodedIngressMessage;
        DecodedMatchingCommand previousCommand = decodedIngressCommand;
        clusterPipelineAdmission = independent;
        decodedIngressMessage = message;
        decodedIngressCommand = decoded;
        try { return apply(message, timestamp, position); }
        finally {
            clusterPipelineAdmission = previousAdmission;
            decodedIngressMessage = previousMessage;
            decodedIngressCommand = previousCommand;
        }
    }

    DecodedMatchingCommand decodeMatchingCommand(CoreMessage message) {
        return message == decodedIngressMessage && decodedIngressCommand != null
                ? decodedIngressCommand : DecodedMatchingCommand.decode(message);
    }

    /** Scopes include all batch items and resting counterparties, using owner indexes only. */
    boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        if (!activated) activate();
        assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != productLine || user <= 0) return false;
        window.resetCandidate(user);
        window.participants(activeOrderIndex);
        try {
            switch (message.header().messageType()) {
                case PLACE_ORDER -> { return addPlaceScope(
                        window.decoded(message).placeOrder(), window); }
                case CANCEL_ORDER -> { return addCancelScope(user,
                        window.decoded(message).cancelOrder().orderId(), window); }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : window.decoded(message).placeOrderBatch().orders()) {
                        if (!addPlaceScope(order, window)) return false;
                        int current = matchingAdapter.matcherShardId(order.symbol());
                        if (shard >= 0 && current != shard) return false;
                        shard = current;
                    }
                    return true;
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : window.decoded(message).cancelOrderBatch().orders()) {
                        if (!addCancelScope(user, order.orderId(), window)) return false;
                        int current = matchingAdapter.matcherShardId(activeOrderIndex.activeOrder(order.orderId()).symbol());
                        if (shard >= 0 && current != shard) return false;
                        shard = current;
                    }
                    return true;
                }
                default -> { return false; }
            }
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return false;
        }
    }

    boolean addPlaceScope(PlaceOrderCommand command, ClusterCommandWindow window) {
        var instrument = runtimeState.instrument(command.symbol());
        if (instrument == null || command.reduceOnly()) return false;
        long counterparties = activeOrderIndex.counterpartyMask(
                instrument.symbol(), command.side(), command.limitPriceTicks());
        window.candidateAccounts |= counterparties;
        window.candidateOrder(command.orderId(), instrument.symbol(), command.side(), command.limitPriceTicks(),
                productLine.isDerivative() && counterparties != 0);
        return true;
    }

    boolean addCancelScope(long user, long orderId, ClusterCommandWindow window) {
        var order = activeOrderIndex.activeOrder(orderId);
        if (order == null || order.userId() != user) return false;
        window.candidateOrder(orderId, order.symbol(), null, 0);
        return true;
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!activated) activate();
        assertOwner();
        if (pendingDirectCommand != null) throw new IllegalStateException("asynchronous control command is still active");
        admissionPreviousClusterTimestamp = currentClusterTimestamp;
        admissionPreviousClusterPosition = currentClusterPosition;
        currentClusterTimestamp = clusterTimestamp;
        currentClusterPosition = clusterPosition;
        assertHealthy();
        if (snapshots.snapshotFence != null && snapshots.snapshotFence.encodedSnapshot == null) {
            throw new IllegalStateException("snapshot fence is active");
        }
        if (!pendingMatching.isEmpty() && !isCommitCursorSafeWhileMatching(message)) {
            long userId = message.header().userId();
            if (userId <= 0 || pendingMatching.hasUser(userId)) {
                throw new IllegalStateException("command or query crossed its account-lane matching cursor");
            }
        }
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().messageType() == CoreMessageType.ACK_EXPORT
                || message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY
                || message.header().messageType() == CoreMessageType.EXPORT_STATUS_QUERY) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && accountLaneReadQuery(message.header().messageType())) {
            if (singleUserLaneQuery(message.header().messageType()) && message.header().userId() > 0) {
                runtimeState.readFence(
                        message.header().userId(), committedCoreSequence);
            } else {
                runtimeState.readFenceAll(committedCoreSequence);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            // Full hashes are explicit audit queries. The hot-path cached value is a snapshot
            // audit anchor and does not track mutable account/order changes.
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                    canonicalBusinessStateHash(tradingState().businessStateHash()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadUnsafe().length != 0) return rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    encodeLaneMetrics());
        }
        if (message.header().messageType() == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                var instrument = runtimeState.instrument(query.symbol());
                if (instrument == null) return rejected(CoreResultCode.ENTITY_NOT_FOUND);
                var users = new java.util.ArrayList<Long>(query.limit());
                boolean more = false;
                for (long userId : positionUserIndex.usersAfter(query.symbol(), query.afterUserId())) {
                    if (users.size() == query.limit()) { more = true; break; }
                    users.add(userId);
                }
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                                new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(
                                        instrument.maintenance(), instrument.changeId(), users, more)));
            } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_HASH_QUERY) {
            var query = com.surprising.aeron.service.state.RuntimeStateQueryService.userState(
                    runtimeState, identities, message.header().userId());
            if (query.tooLarge()) return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                    query.found() ? query.stateHash() : 0);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                StoredResult result = resultLedger.get(commandId);
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
                        runtimeState, identities,
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
                        runtimeState, identities, message.header().userId(),
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
                                runtimeState, identities, orderId))
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
                        runtimeState, source, message.header().userId(), query.symbol(), query.status(),
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
                return bookQueries.beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return bookQueries.beginBookBootstrapQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.RuntimeOperationalQueryService.treasuryAssets(
                        runtimeState, identities);
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
                        .fundingProgress(runtimeState, identities, symbol);
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
                        .settlementProgress(runtimeState, identities, symbol);
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
                                        runtimeState, identities, query.asset(),
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
                        runtimeState, identities, message.header().userId());
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
                    CoreRiskScanControlCodec.encodeView(runtimeState.riskScanControl()));
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
                                query.limit(), runtimeState::algoOrder);
                var values = com.surprising.aeron.service.state.RuntimeOperationalQueryService.algoOrders(
                        runtimeState, algoIds);
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
                        query.limit(), runtimeState::cancelAllAfterTimer);
                var values = com.surprising.aeron.service.state.RuntimeOperationalQueryService.cancelAllAfter(
                        runtimeState, keys);
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
                        runtimeState, identities, productLine, query, candidates,
                        liquidationIndex.activeIds());
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
                ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                        identities, message.header().userId(), command, clusterTimestamp);
                long reservedUnits = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                        runtimeState, identities, message.header().userId(), resolved,
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
                var transfers = runtimeState.pendingTransfers(limit).stream()
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
        StoredResult duplicate = resultLedger.get(message.header().commandId());
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
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        resultBuilder.commandTriggerOrderView = null;
        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
            }
            return admissions.beginMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
        }
        CoreAdmissionReservation commandAdmission = null;
        CoreAdmissionReservation.AdmissionDemand commandDemand = null;
        try {
            commandDemand = CoreAdmissionReservation.AdmissionDemand.direct(message,
                    runtimeState.riskScanControl().scanBatchSize());
            commandAdmission = reserveAdmission(commandDemand);
            activateFactContext(commandAdmission, message, fingerprint);
        } catch (CoreStateRejectedException rejection) {
            return admissionRejected(CoreResultCode.fromRejectionCode(rejection.code()));
        } catch (ArithmeticException exception) {
            return admissionRejected(CoreResultCode.ARITHMETIC_OVERFLOW);
        } catch (IllegalArgumentException exception) {
            return admissionRejected(CoreResultCode.INVALID_COMMAND);
        }
        RuntimeProjectionPoint beforeProjection = currentProjectionPoint;
        long beforeRuntimeRevision = runtimeState.revision();
        long runtimeCommandCheckpoint = runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = identities.positionCheckpoint();
        resultBuilder.commandOrderViews = List.of();
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.commandTradeCount = 0;
        resultBuilder.commandFundingProgress = null;
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandSettlementProgress = null;
        resultBuilder.commandRiskScanControl = null;
        resultBuilder.resetChangeAccumulators();
        commandExternalAdjustment = message.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || message.header().messageType() == CoreMessageType.TRANSFER_OUT
                || message.header().messageType() == CoreMessageType.TRANSFER_IN
                || message.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        admissions.queuedMatching.clear();
        commits.beginCommitPublicationBatch();
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
        if (controlContinuation == null && runtimeState.asynchronousCommands()
                && (message.header().messageType() == CoreMessageType.APPLY_FUNDING
                    || message.header().messageType() == CoreMessageType.CONTINUE_RISK_SCAN)) {
            // 校验失败或空任务也要在账户交接完成后进入统一提交/回滚路径。
            controlCompletionStatus = status;
            controlCompletionCode = resultCode;
        }
        if (controlContinuation != null || controlCompletionStatus != null) {
            pendingDirectCommand = new PendingDirectCommand(message, clusterTimestamp, clusterPosition,
                    sourceKey, fingerprint, commandAdmission, commandDemand, beforeProjection,
                    beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
            return null;
        }
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint, commandAdmission,
                commandDemand, beforeProjection, beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }

    private CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint, CoreAdmissionReservation commandAdmission,
            CoreAdmissionReservation.AdmissionDemand commandDemand, RuntimeProjectionPoint beforeProjection,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        long committedLaneMask = 0;
        if (!directFinalizationPrepared) {
            if (status != ResponseStatus.APPLIED
                    && (commits.commitPublicationDirty || runtimeState.revision() != beforeRuntimeRevision
                        || runtimeState.hasUncommittedCommandChanges())) {
                rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(appliedCommandCount));
            }
            if (status == null) {
                commits.abortCommitPublicationBatch();
                return releaseAdmission(commandAdmission, rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                triggers.cancelTriggersForClosedPositions();
                int reservedChildPatches = commandDemand == null ? 0 : commandDemand.patchCount() - 1;
                if (admissions.queuedMatching.size() > reservedChildPatches
                        || pendingMatching.size() + admissions.queuedMatching.size() > pendingMatching.capacity()) {
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    admissions.queuedMatching.clear();
                    return releaseAdmission(commandAdmission, rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            if (status == ResponseStatus.APPLIED) {
                List<Long> changedOrderIds = resultBuilder.commandChangedOrderIds == null ? List.of() : resultBuilder.commandChangedOrderIds;
                try {
                    stampOrderChangesRuntime(clusterTimestamp, clusterPosition, changedOrderIds);
                } catch (IllegalStateException exception) {
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return releaseAdmission(commandAdmission, rejected(CoreResultCode.INVALID_COMMAND));
                }
            }
            resultBuilder.materializeChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !resultBuilder.commandChangedUserIds.isEmpty()) {
                // 业务任务已经结束。把发布序号交给实际参与 Lane，不接管全部账户。
                if (runtimeState.asynchronousCommands()) {
                    runtimeState.releaseCompletedSequentialLaneStage();
                    directCommitEvent = runtimeState.dispatchLaneMutation(
                            nextAppliedCommandCount, resultBuilder.commandChangedUserIds);
                } else {
                    committedLaneMask = runtimeState.stageLaneMutation(
                            nextAppliedCommandCount, resultBuilder.commandChangedUserIds);
                }
            }
            directFinalizationPrepared = true;
        }
        if (directCommitEvent != null) {
            if (!runtimeState.laneCommitComplete(directCommitEvent)) {
                if (pendingDirectCommand == null) {
                    pendingDirectCommand = new PendingDirectCommand(message, clusterTimestamp, clusterPosition,
                            sourceKey, fingerprint, commandAdmission, commandDemand, beforeProjection,
                            beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
                    controlCompletionStatus = status;
                    controlCompletionCode = resultCode;
                }
                return null;
            }
            committedLaneMask = directCommitEvent.requiredLaneMask();
            runtimeState.releaseLaneCommit(directCommitEvent);
            directCommitEvent = null;
        }
        directFinalizationPrepared = false;
        commits.completeCommitPublicationBatch(committedLaneMask);
        if (status == ResponseStatus.APPLIED) {
            validateFundsConservation(message);
        }
        boolean tradingStateChanged = status == ResponseStatus.APPLIED
                && currentProjectionPoint != beforeProjection;
        long businessStateHash = tradingStateChanged ? currentBusinessStateHash() : cachedBusinessStateHash;
        appliedCommandCount = nextAppliedCommandCount;
        refreshCommittedCoreSequence();
        long requiredExportSequence = 0;
        cachedBusinessStateHash = businessStateHash;
        admissions.appendQueuedMatching();
        lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            terminalRetention.retainFundsCommand(message.header().commandId(), fingerprint);
        }
        long stateHash = stateHash(businessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = resultBuilder.commandResultData();
        resultLedger.storeResult(message.header().commandId(), StoredResult.owned(fingerprint, status, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, responseData));
        CoreResponse response = CoreResponse.owned(status, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData);
        return releaseAdmission(commandAdmission, response);
    }

    /** 当前直接命令的业务续步，只由日志回调中的 owner 推进。 */
    private java.util.function.BooleanSupplier controlContinuation;
    /** 保留原始命令、资金检查点及准入额度，异步完成前禁止提前提交。 */
    private PendingDirectCommand pendingDirectCommand;
    /** 直接命令唯一的完成事件；全部参与 Lane 完成后才能回收和发布。 */
    private com.surprising.aeron.service.state.LaneCommitEvent directCommitEvent;
    /** 跨回调只执行一次收尾准备，防止重复撤触发单、盖订单时间戳或派发。 */
    private boolean directFinalizationPrepared;
    /** 业务阶段完成后保留结果，等待提交/回滚交接时禁止再次调用业务续步。 */
    private ResponseStatus controlCompletionStatus;
    private CoreResultCode controlCompletionCode;

    void deferControl(java.util.function.BooleanSupplier continuation) {
        if (controlContinuation != null || continuation == null) throw new IllegalStateException("nested control continuation");
        controlContinuation = continuation;
    }

    boolean hasPendingDirectCommand() { return pendingDirectCommand != null; }

    CoreResponse pollDirectCommand() {
        assertOwner();
        var pending = pendingDirectCommand;
        if (pending == null) throw new IllegalStateException("no pending direct command");
        if (controlCompletionStatus == null) {
            try {
                if (!controlContinuation.getAsBoolean()) return null;
                controlCompletionStatus = ResponseStatus.APPLIED;
                controlCompletionCode = CoreResultCode.NONE;
            } catch (CoreStateRejectedException failure) {
                controlCompletionStatus = ResponseStatus.REJECTED;
                controlCompletionCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (ArithmeticException failure) {
                controlCompletionStatus = ResponseStatus.REJECTED;
                controlCompletionCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (IllegalArgumentException failure) {
                controlCompletionStatus = ResponseStatus.REJECTED;
                controlCompletionCode = CoreResultCode.INVALID_COMMAND;
            }
        }
        // 回滚仍必须独占受影响状态；成功的资金费/风险任务已经在 Lane 完成业务修改。
        if (controlCompletionStatus != ResponseStatus.APPLIED
                && runtimeState.hasUncommittedCommandChanges()
                && !runtimeState.tryAcquireOwnerLaneAccess()) return null;
        CoreResponse result = finishDirectCommand(pending.message, pending.timestamp, pending.position, pending.sourceKey,
                pending.fingerprint, pending.admission, pending.demand, pending.beforeProjection,
                pending.beforeRevision, pending.checkpoint, pending.identityCheckpoint,
                controlCompletionStatus, controlCompletionCode);
        if (result == null) return null;
        controlCompletionStatus = null;
        controlCompletionCode = null;
        controlContinuation = null;
        pendingDirectCommand = null;
        return result;
    }

    /** 仅异步控制命令需要跨日志回调保留的提交上下文。 */
    private record PendingDirectCommand(CoreMessage message, long timestamp, long position,
            SourceKey sourceKey, CommandFingerprint fingerprint, CoreAdmissionReservation admission,
            CoreAdmissionReservation.AdmissionDemand demand, RuntimeProjectionPoint beforeProjection,
            long beforeRevision, long checkpoint, long identityCheckpoint) { }

    long runtimePositionQuantity(long userId, String symbol) {
        long quantity = 0;
        for (com.surprising.aeron.protocol.CorePositionSide side
                : com.surprising.aeron.protocol.CorePositionSide.values()) {
            String key = side == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? symbol : symbol + ':' + side.name();
            Long positionKey = identities.findPositionKey(userId, key);
            if (positionKey == null) continue;
            com.surprising.aeron.service.state.PositionRuntime position =
                    runtimeState.position(positionKey);
            if (position != null) quantity = Math.addExact(quantity, position.signedQuantitySteps());
        }
        return quantity;
    }

    /** Dispatch once from either preparation or ordered commit; collection stays at commit. */

    void recordSourceSequence(SourceKey sourceKey, long sourceSequence) {
        lastSourceSequences.put(sourceKey, sourceSequence);
    }

    static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || isOrderBatchCommand(type)
                || type == CoreMessageType.EXECUTE_LIQUIDATION
                || type == CoreMessageType.EXECUTE_LIQUIDATION_BATCH
                || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    static boolean isOrderBatchCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER_BATCH || type == CoreMessageType.CANCEL_ORDER_BATCH
                || type == CoreMessageType.AMEND_ORDER_BATCH;
    }

    CoreResponse releaseAdmission(CoreAdmissionReservation reservation, CoreResponse response) {
        clearFactContext();
        if (reservation != null) reservation.releaseUnused();
        return response;
    }

    CoreAdmissionReservation reserveAdmission(
            CoreAdmissionReservation.AdmissionDemand demand) {
        return CoreAdmissionReservation.reserve(
                runtimeProjectionJournal, exportState, demand, admissionReservationPool);
    }

    void activateFactContext(CoreAdmissionReservation reservation, CoreMessage command,
                                     CommandFingerprint fingerprint) {
        if (reservation == null || command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete commit context is required before mutation");
        }
        currentAdmission = reservation;
    }

    void clearFactContext() {
        currentAdmission = null;
    }

    CoreResponse admissionRejected(CoreResultCode resultCode) {
        currentClusterTimestamp = admissionPreviousClusterTimestamp;
        currentClusterPosition = admissionPreviousClusterPosition;
        return rejected(resultCode);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CommandFingerprint fingerprint, CoreResultCode resultCode,
                                                PendingMatching deferredPending) {
        return deferredPending == null ? recordRejectedMatching(message, sourceKey, fingerprint, resultCode)
                : admissions.recordRejectedDeferredMatching(deferredPending, resultCode);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!pendingMatching.isEmpty()) {
            long sequence = Math.incrementExact(appliedCommandCount);
            currentAdmission.retainHolders(1);
            PendingMatching pending = admissions.newPendingMatching(sequence,
                    MatchingCommandAdmission.matchingOperation(message.header().messageType()), message, fingerprint)
                    .withCapacityReservation(currentAdmission);
            putPendingMatching(pending);
            laneCommandContexts.required(sequence).rejectMatching(resultCode);
            pendingMatching.completeSubmission(sequence);
            commits.signalPendingMatchingReady(sequence);
            appliedCommandCount = sequence;
            recordSourceSequence(sourceKey, message.header().sourceSequence());
            long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                    ResponseStatus.OK, matchingPendingCode(), sequence);
            pending.withPendingStateHash(stateHash);
            return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                    sequence, 0, stateHash, EMPTY_RESPONSE_DATA);
        }
        long sequence = Math.incrementExact(appliedCommandCount);
        long requiredExportSequence = 0;
        appliedCommandCount = sequence;
        refreshCommittedCoreSequence();
        lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.REJECTED, resultCode, appliedCommandCount);
        resultLedger.storeResult(message.header().commandId(), new StoredResult(fingerprint,
                ResponseStatus.REJECTED, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                new byte[0], 0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, new byte[0]);
    }

    PendingMatching removePendingMatching(long sequence) {
        admissions.pendingLifecycleScopes.remove(sequence);
        int shard = pendingMatching.submissionShard(sequence);
        boolean releasesSubmissionHead = shard >= 0 && pendingMatching.isSubmissionHead(sequence, shard);
        PendingMatching removed = pendingMatching.remove(sequence);
        // A wholly rejected batch can finish without submitting anything to the matcher.
        // Its successor's Lane notification may already have been consumed while blocked.
        if (releasesSubmissionHead) placeAdmissionReadyShardMask |= 1L << shard;
        refreshCommittedCoreSequence();
        return removed;
    }

    void refreshCommittedCoreSequence() {
        long candidate = pendingMatching.isEmpty()
                ? appliedCommandCount : Math.subtractExact(pendingMatching.firstSequence(), 1);
        // No per-sequence side effect remains: advance only the continuous completed prefix.
        if (candidate > committedCoreSequence) committedCoreSequence = candidate;
    }

    void commitMatchingSequence(long sequence) {
        if (!pendingMatching.contains(sequence) || sequence <= committedCoreSequence) {
            throw new IllegalStateException("matching sequence is not pending above the committed watermark");
        }
    }

    void putPendingMatching(PendingMatching pending) {
        pending.clusterIndependent = clusterPipelineAdmission;
        if (pendingMatching.size() >= pendingMatching.capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        CoreAdmissionReservation admission = pending.takeCapacityReservation();
        if (admission == null) throw new IllegalStateException("matching sequence admission is missing");
        try {
            pendingMatching.put(pending);
            laneCommandContexts.required(pending.sequence()).admission(admission);
            CoreMessageType type = pending.command().header().messageType();
            if (type != CoreMessageType.PLACE_ORDER_BATCH
                    && type != CoreMessageType.CANCEL_ORDER_BATCH
                    && type != CoreMessageType.AMEND_ORDER_BATCH) {
                pendingMatching.registerSubmission(pending.sequence(), matcherShard(pending));
            }
        } catch (RuntimeException failure) {
            if (pendingMatching.remove(pending.sequence()) == null) {
                pendingMatching.discardPrepared(pending.sequence());
            }
            admission.releaseUnused();
            throw failure;
        }
    }

    CoreAdmissionReservation sequenceAdmission(long sequence) {
        CoreAdmissionReservation admission = laneCommandContexts.required(sequence).admission();
        if (admission == null) throw new IllegalStateException("sequence admission is missing");
        return admission;
    }

    int matcherShard(PendingMatching pending) {
        String symbol = pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT
                ? admissions.pendingLifecycleSymbol(pending)
                : admissions.matchingSymbol(pending.command(), pending.operation(), pending.decodedCommand());
        return symbol == null || symbol.isBlank() ? 0 : matchingAdapter.matcherShardId(symbol);
    }

    LifecycleOrderChunk lifecycleOrders(long userId, String symbol, long cursorOrderId, int maxOrders) {
        var page = activeOrderIndex.page(userId, symbol, cursorOrderId, maxOrders);
        List<CoreOrderState> selected = page.orderIds().stream()
                .map(runtimeState::order)
                .filter(order -> order != null
                        && order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN)
                .map(order -> RuntimeStateMaterializer.orderSnapshot(order, identities))
                .toList();
        return new LifecycleOrderChunk(selected, page.nextCursorOrderId());
    }

    List<CoreOrderState> batchCancellationOrders(PendingMatching pending) {
        var command = pending.decodedCommand().liquidationBatch();
        List<CoreOrderState> orders = new ArrayList<>();
        int remaining = command.maxCancelOrders();
        for (var action : command.actions()) {
            if (remaining == 0) break;
            var liquidation = runtimeState.liquidation(action.liquidationId());
            if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                    && liquidation.status() != CoreLiquidationState.Status.ORDERED)) continue;
            LifecycleOrderChunk chunk = lifecycleOrders(liquidation.userId(), runtimeLiquidationSymbol(liquidation),
                    action.cursorOrderId(), remaining);
            orders.addAll(chunk.orders());
            remaining -= chunk.orders().size();
        }
        return List.copyOf(orders);
    }

    record LifecycleOrderChunk(List<CoreOrderState> orders, long nextCursorOrderId) {
        boolean more() {
            return nextCursorOrderId != 0;
        }
    }

    void submitMatching(PendingMatching pending) {
        if (pending.crossShardCancellationStarted || matchingSubmissionDeferred(pending.sequence())) return;
        if (batches.pendingOrderBatches.containsKey(pending.sequenceKey())) {
            batches.submitOrderBatchMatching(pending);
            if (pending.isMatchingSubmitted()) matchingSubmissionCompleted(pending);
            return;
        }
        if (pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                && matchingAdapter.topology().matchingEngineCount() > 1) {
            List<CoreOrderState> orders = batchCancellationOrders(pending);
            int shardId = singleMatcherShard(orders);
            if (shardId == -2) {
                crossShardCancellations.start(pending, orders);
                return;
            }
            matcherPipeline.submit(shardId < 0 ? matcherShard(pending) : shardId,
                    pending.sequence(), prepareMatchingCommand(pending));
            pending.matchingSubmitted();
            matchingSubmissionCompleted(pending);
            return;
        }
        matcherPipeline.submit(matcherShard(pending), pending.sequence(), prepareMatchingCommand(pending));
        pending.matchingSubmitted();
        matchingSubmissionCompleted(pending);
    }

    int singleMatcherShard(List<CoreOrderState> orders) {
        int shardId = -1;
        for (CoreOrderState order : orders) {
            int orderShard = matchingAdapter.matcherShardId(order.symbol());
            if (shardId == -1) shardId = orderShard;
            else if (shardId != orderShard) return -2;
        }
        return shardId;
    }

    void progressPlaceAdmissions() {
        drainLaneReadyNotifications();
        long readyShards = placeAdmissionReadyShardMask;
        while (readyShards != 0) {
            int shard = Long.numberOfTrailingZeros(readyShards);
            long shardBit = 1L << shard;
            readyShards &= ~shardBit;
            while ((placeAdmissionReadyShardMask & shardBit) != 0) {
                PendingMatching pending = pendingMatching.submissionHead(shard);
                if (pending == null) {
                    placeAdmissionReadyShardMask &= ~shardBit;
                    break;
                }
                var admission = pending.placeAdmission();
                OrderBatchPending orderBatch = batches.pendingOrderBatches.get(pending.sequenceKey());
                PlaceBatchAdmissionEvent batchAdmission = orderBatch == null
                        ? null : orderBatch.placeBatchAdmissionEvent;
                if (admission == null && batchAdmission == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchKind.CANCEL && orderBatch.started) {
                        submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    // A prepared cancel/control command may have waited behind a place's
                    // asynchronous Lane admission. Resume it at the same shard submission
                    // head; it has no admission notification of its own to wake us later.
                    if (orderBatch == null && !admissions.deferredMatching.containsKey(pending.sequence())) {
                        submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    placeAdmissionReadyShardMask &= ~shardBit;
                    break;
                }
                if (batchAdmission != null) {
                    if (!batchAdmission.complete()) {
                        placeAdmissionReadyShardMask &= ~shardBit;
                        break;
                    }
                    RuntimeException rejection = batchAdmission.rejection();
                    if (rejection != null) {
                        runtimeState.discardPlaceBatchAdmission(batchAdmission);
                        runtimeState.releasePlaceBatchAdmission(batchAdmission);
                        orderBatch.placeBatchAdmissionEvent = null;
                        batches.unregisterPipelinedBatchSymbols(orderBatch);
                        orderBatch.rollbackPreparedClientKeys(identities);
                        orderBatch.pipelined = false;
                        orderBatch.sequentialAdmission = true;
                        orderBatch.admissionOrderIndex.reset(pending.command().header().userId());
                        placeAdmissionReadyShardMask &= ~shardBit;
                        if (orderBatch.commitStarted()) {
                            restoreMatchingCommitContext(pending);
                            try {
                                batches.startOrderBatchItem(orderBatch, pending,
                                        orderBatch.clusterTimestamp, orderBatch.clusterPosition, true);
                            } finally {
                                clearFactContext();
                            }
                        } else {
                            orderBatch.started = false;
                            submitDeferredMatchingAfterBatch();
                        }
                        break;
                    }
                    if (!orderBatch.admissionCollected) {
                        runtimeState.stagePlaceBatchAdmission(batchAdmission);
                        orderBatch.admissionCollected = true;
                    }
                    if (!pending.isMatchingSubmitted()) {
                        batches.submitPipelinedPlaceBatch(pending, orderBatch);
                        pending.matchingSubmitted();
                        matchingSubmissionCompleted(pending);
                    }
                    continue;
                }
                if (!admission.complete()) {
                    placeAdmissionReadyShardMask &= ~shardBit;
                    break;
                }
                RuntimeException rejection = admission.rejection();
                if (rejection != null) {
                    if (admission.allocatedClientKey()) {
                        identities.rollbackPreparedClientKey(
                                admission.userId(), admission.clientOrderId(),
                                new RuntimeIdentityRegistry.PreparedClientKey(admission.clientKey(), true));
                    }
                    CoreResultCode resultCode = rejection instanceof CoreStateRejectedException rejected
                            ? CoreResultCode.fromRejectionCode(rejected.code())
                            : rejection instanceof ArithmeticException
                            ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
                    laneCommandContexts.required(pending.sequence()).rejectMatching(resultCode);
                    runtimeState.releasePlaceAdmission(pending.takePlaceAdmission());
                    pendingMatching.completeSubmission(pending.sequence());
                    commits.signalPendingMatchingReady(pending.sequence());
                    continue;
                }
                if (pending.admittedMatchingOrder() == null) {
                    pending.admissionCompleted(runtimeState.collectPlaceAdmission(admission));
                    runtimeState.releasePlaceAdmission(pending.takePlaceAdmission());
                }
                submitMatching(pending);
                if (!pending.isMatchingSubmitted()) break;
            }
        }
    }

    /** Drain completed place admissions and mark their matcher shard once. */
    void drainLaneReadyNotifications() {
        long readyLaneMask = runtimeState.takePlaceAdmissionReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = runtimeState.pollPlaceAdmissionReady(laneId)) != 0) {
                matchingProgressSequence++;
                PendingMatching pending = pendingMatching.get(sequence);
                OrderBatchPending batch = pending == null ? null : batches.pendingOrderBatches.get(sequence);
                if (pending == null || pending.isMatchingSubmitted()
                        || batch != null && (batch.finishing || batch.nextIndex >= batch.items.size())
                        || pending.placeAdmission() == null
                        && (batch == null || batch.placeBatchAdmissionEvent == null)) {
                    continue;
                }
                placeAdmissionReadyShardMask |= 1L << pendingSubmissionShard(pending);
            }
        }
    }

    boolean matchingSubmissionDeferred(long sequence) {
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending != null) {
            int shardId = pendingSubmissionShard(pending);
            if (!pendingMatching.isSubmissionHead(sequence, shardId)) return true;
        }
        if (pending != null && pending.clusterIndependent || batches.pendingOrderBatches.isEmpty()) return false;
        return sequence > batches.pendingOrderBatches.keySet().iterator().next();
    }

    void matchingSubmissionCompleted(PendingMatching pending) {
        matchingProgressSequence++;
        int shard = pendingSubmissionShard(pending);
        pendingMatching.completeSubmission(pending.sequence());
        placeAdmissionReadyShardMask |= 1L << shard;
    }

    int pendingSubmissionShard(PendingMatching pending) {
        OrderBatchPending batch = batches.pendingOrderBatches.get(pending.sequenceKey());
        return batch == null ? matcherShard(pending) : batches.orderBatchMatcherShard(batch);
    }

    void submitDeferredMatchingAfterBatch() {
        while (!pendingMatching.isEmpty()) {
            long throughSequence = batches.pendingOrderBatches.isEmpty()
                    ? Long.MAX_VALUE : batches.pendingOrderBatches.keySet().iterator().next();
            PendingMatching pending = pendingMatching.findFirst(value -> {
                        if (value.sequence() > throughSequence) return false;
                        OrderBatchPending batch = batches.pendingOrderBatches.get(value.sequenceKey());
                        return batch == null ? admissions.deferredMatching.containsKey(value.sequence()) : !batch.started;
                    });
            if (pending == null) return;
            OrderBatchPending batch = batches.pendingOrderBatches.get(pending.sequenceKey());
            if (batch != null && !batch.started) {
                if (batches.tryActivatePipelinedOrderBatch(batch, pending)) continue;
                batches.activateOrderBatch(batch, pending, true);
                return;
            }
            MatchingCommandAdmission.DeferredMatching deferred = admissions.deferredMatching.get(pending.sequence());
            if (deferred != null) {
                CoreResponse response = admissions.prepareMatching(pending.command(), deferred.clusterTimestamp(),
                        deferred.clusterPosition(), deferred.sourceKey(), pending.operation(), pending.fingerprint(),
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
        if (result == null) throw new IllegalStateException("synchronous matcher returned no result");
        laneCommandContexts.required(sequence).publishMatchingCompletion(result.withCoreSequence(sequence));
        matchingProgressSequence++;
        commits.signalPendingMatchingReady(sequence);
    }

    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult>
            prepareMatchingCommand(
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
                    var admittedOrder = pending.admittedMatchingOrder();
                    var order = admittedOrder == null ? matchingOrder(command.orderId()) : admittedOrder;
                    yield new MatchingSubmission(command.orderId(), command.instrumentChangeId(),
                            () -> matchingAdapter.place(userId, order));
                }
                case CANCEL -> {
                    var command = pending.decodedCommand().cancelOrder();
                    var order = runtimeState.order(command.orderId());
                    String symbol = order == null ? "" : identities.symbol(order.symbolId());
                    long instrumentChangeId = order == null ? 0 : order.instrumentChangeId();
                    yield new MatchingSubmission(command.orderId(), instrumentChangeId,
                            () -> matchingAdapter.cancelForContinuation(userId, command.orderId(), symbol));
                }
                case REPLACE, AMEND -> {
                    ResolvedMatchingAdmission admission = requireMatchingAdmission(pending);
                    var order = runtimeOrder(admission.originalOrderId());
                    String symbol = runtimeOrderSymbol(order);
                    yield new MatchingSubmission(admission.resolved().orderId(),
                            admission.resolved().instrumentChangeId(),
                            () -> matchingAdapter.replaceOrder(userId, admission.originalOrderId(),
                                    symbol, admission.matchingOrder()));
                }
                case TRIGGER -> {
                    long[] execute = pending.decodedCommand().trigger();
                    var trigger = runtimeState.triggerOrder(execute[0]);
                    if (trigger == null) {
                        yield new MatchingSubmission(0, 0, () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        false, "TRIGGER_ORDER_NOT_FOUND"));
                    }
                    var order = java.util.Objects.requireNonNull(pending.admittedMatchingOrder(), "trigger admission is missing");
                    yield new MatchingSubmission(order.orderId(), trigger.instrumentChangeId(),
                            () -> matchingAdapter.place(trigger.userId(), order));
                }
                case LIQUIDATION -> {
                    var command = pending.decodedCommand().liquidation();
                    var liquidation = runtimeState.liquidation(command.liquidationId());
                    if (liquidation == null || !com.surprising.aeron.service.state.RuntimeLiquidationQueryService
                            .isExecutable(runtimeState, identities, command)) {
                        yield new MatchingSubmission(command.liquidationId(),
                                liquidation == null ? 0 : liquidation.instrumentChangeId(),
                                () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS"));
                    }
                    var orders = lifecycleOrders(liquidation.userId(),
                            runtimeLiquidationSymbol(liquidation),
                            command.cursorOrderId(), command.maxOrders()).orders();
                    yield new MatchingSubmission(command.liquidationId(), liquidation.instrumentChangeId(),
                            () -> matchingAdapter.cancelBatch(orders));
                }
                case LIQUIDATION_BATCH -> {
                    var orders = batchCancellationOrders(pending);
                    yield new MatchingSubmission(0, 0, () -> matchingAdapter.cancelBatch(orders));
                }
                case SETTLEMENT -> {
                    var command = pending.decodedCommand().settlement();
                    var progress = runtimeLifecycleProgress(command.symbol());
                    if (progress != null && progress.ordersComplete()) {
                        yield new MatchingSubmission(0, command.instrumentChangeId(),
                                () ->
                                new com.surprising.aeron.service.matching.CoreMatchingResult(
                                        true, "SUCCESS"));
                    }
                    var orders = lifecycleOrders(0, command.symbol(), command.cursorOrderId(),
                            command.maxOrders()).orders();
                    yield new MatchingSubmission(0, command.instrumentChangeId(),
                            () -> matchingAdapter.cancelBatch(orders));
                }
            };
            java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> guarded =
                    () -> matchingAdapter.executeAfterCancellationsSync(
                            preMatchingCancellations, matching.submission());
            return matchingEvidenceCommand(pending, matching.orderId(), matching.instrumentChangeId(),
                    matchingControlCommand(pending), guarded);
        } catch (RuntimeException exception) {
            return matchingEvidenceCommand(pending, 0, 0, matchingControlCommand(pending), () ->
                    new com.surprising.aeron.service.matching.CoreMatchingResult(false, "EXCHANGE_CORE_FAILURE"));
        }
    }

    boolean matchingControlCommand(PendingMatching pending) {
        return batches.pendingOrderBatches.containsKey(pending.sequenceKey())
                || !pending.preMatchingCancellationOrderIds().isEmpty()
                || pending.operation() == PendingMatching.Operation.LIQUIDATION
                || pending.operation() == PendingMatching.Operation.LIQUIDATION_BATCH
                || pending.operation() == PendingMatching.Operation.SETTLEMENT;
    }

    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult>
            matchingEvidenceCommand(
                    PendingMatching pending, long orderId, long instrumentChangeId, boolean control,
                    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> command) {
        long coreSequence = pending.sequence();
        UUID commandId = pending.command().header().commandId();
        long aeronTimestamp = pending.command().header().submittedAtEpochMillis();
        return control
                ? () -> matchingAdapter.executeControlWithEvidenceSync(
                        coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command)
                : () -> matchingAdapter.executeWithEvidenceSync(
                        coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command);
    }

    List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellationOrders(
            PendingMatching pending) {
        List<Long> orderIds = pending.preMatchingCancellationOrderIds();
        if (orderIds.isEmpty()) return List.of();
        ArrayList<DeterministicExchangeCoreAdapter.CancellationOrder> orders = new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            OrderRuntime order = runtimeOrder(orderId);
            if (order != null && order.status() == com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN) {
                orders.add(new DeterministicExchangeCoreAdapter.CancellationOrder(
                        order.orderId(), order.userId(), runtimeOrderSymbol(order)));
            }
        }
        return orders.isEmpty() ? List.of() : orders;
    }

    record MatchingSubmission(
            long orderId,
            long instrumentChangeId,
            java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> submission) {
    }

    com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message,
                                                                            OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String symbol = runtimeOrderSymbol(order);
        var instrument = runtimeState.instrument(symbol);
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
                order.instrumentChangeId(), order.side(), priceTicks, quantitySteps,
                order.reduceOnly(), order.marginMode(), order.positionSide(),
                order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    PlaceOrderCommand replacementFor(DecodedMatchingCommand decodedCommand,
                                             PendingMatching.Operation operation,
                                             OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (runtimeState.instrument(runtimeOrderSymbol(order)) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        return operation == PendingMatching.Operation.REPLACE
                ? decodedCommand.replaceOrder().replacement()
                : replacementForAmend(decodedCommand.amendOrder(), order);
    }

    PlaceOrderCommand replacementFor(PendingMatching pending, OrderRuntime order) {
        if (pending.operation() == PendingMatching.Operation.REPLACE) {
            if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
            if (runtimeState.instrument(runtimeOrderSymbol(order)) == null) {
                throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
            }
            return pending.decodedCommand().replaceOrder().replacement();
        }
        return replacementForAmend(pending.decodedCommand().amendOrder(), order);
    }

    PlaceOrderCommand replacementForAmend(AmendOrderCommand command, OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        String symbol = runtimeOrderSymbol(order);
        var instrument = runtimeState.instrument(symbol);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new PlaceOrderCommand(command.replacementOrderId(), symbol, order.instrumentChangeId(),
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(),
                order.positionSide(), order.orderType(), timeInForce, postOnly, clientOrderId);
    }

    com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long triggeredPriceTicks) {
        var instrument = runtimeState.instrument(trigger.symbol());
        Long clientKey = identities.findClientKey(
                trigger.userId(), "TRIGGER:" + trigger.triggerOrderId());
        Long orderId = clientKey == null ? null : runtimeState.orderIdByClient(trigger.userId(), clientKey);
        var order = orderId == null ? null : runtimeState.order(orderId);
        return triggerPlacement(trigger, triggeredPriceTicks, order);
    }

    com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long triggeredPriceTicks,
            OrderRuntime order) {
        var instrument = runtimeState.instrument(trigger.symbol());
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND",
                        "trigger child order not found");
        }
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        long limitPriceTicks = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (order.priceTicks() > 0 ? order.priceTicks() : triggeredPriceTicks) : 0;
        return new com.surprising.aeron.protocol.PlaceOrderCommand(order.orderId(), trigger.symbol(),
                trigger.instrumentChangeId(), trigger.side(), limitPriceTicks, order.quantitySteps(),
                order.reduceOnly(), trigger.marginMode(), trigger.positionSide(),
                trigger.orderType(), trigger.timeInForce(), false, order.clientOrderId());
    }

    long currentMarkPriceTicks(String symbol, long referencePriceTicks) {
        Integer symbolId = identities.findSymbolId(symbol);
        var markPrice = symbolId == null ? null : runtimeState.markPrice(symbolId);
        long value = markPrice == null ? referencePriceTicks : markPrice.markPriceTicks();
        if (value <= 0) throw new CoreStateRejectedException("MARK_PRICE_MISSING", "mark price is required");
        return value;
    }

    public com.surprising.aeron.service.matching.CoreMatchingResult takeMatchingResult(long sequence) {
        if (fatalFailure != null) return null;
        progressPlaceAdmissions();
        if (!pendingMatching.contains(sequence)) return null;
        LaneCommandContextRing.Context context = laneCommandContexts.required(sequence);
        if (context.matchingResult() != null) return context.matchingResult();
        transferMatchingCompletion(sequence);
        return context.takeMatchingCompletion();
    }

    void transferMatchingCompletion(long sequence) {
        if (!pendingMatching.contains(sequence)) return;
        LaneCommandContextRing.Context context = laneCommandContexts.required(sequence);
        if (context.hasMatchingCompletion() || context.matchingResult() != null) return;
        com.surprising.aeron.service.matching.CoreMatchingResult result = matcherPipeline.poll(sequence);
        if (result != null) context.publishMatchingCompletion(result.withCoreSequence(sequence));
    }

    boolean establishMatchingCommitFence(long sequence, long clusterTimestamp, long clusterPosition) {
        PendingMatching pending = pendingMatching.get(sequence);
        if (pending == null) return false;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        return true;
    }

    boolean placeAdmissionOutstanding(PendingMatching pending) {
        if (pending.placeAdmission() != null && !pending.isMatchingSubmitted()) return true;
        OrderBatchPending batch = batches.pendingOrderBatches.get(pending.sequenceKey());
        return batch != null && batch.placeBatchAdmissionEvent != null && !pending.isMatchingSubmitted();
    }

    boolean hasPendingMatchingRejection(long sequence) {
        return laneCommandContexts.claimed(sequence)
                && laneCommandContexts.required(sequence).hasMatchingRejection();
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        assertOwner();
        try {
            return matchingAdapter.topology().matchingEngineCount() == 1
                    ? matcherPipeline.readAtSubmissionFence(0, () -> matchingAdapter.orderBooksStateHashAsync().join())
                    : matcherPipeline.readEachAsync(matchingAdapter::stateHashShard)
                    .thenApply(matchingAdapter::aggregateBookHash);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static boolean isCommitCursorSafeWhileMatching(CoreMessage message) {
        return isNonFencingQuery(message) || (message.header().kind() == WireMessageKind.COMMAND
                && isMatchingCommand(message.header().messageType()));
    }

    // Operational counters, not a coherent account/order snapshot. Business queries retain
    // the ingress fence, including command-result queries which must not overtake writes.
    static boolean isNonFencingQuery(CoreMessage message) {
        return message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY;
    }

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

    static boolean singleUserLaneQuery(CoreMessageType type) {
        return switch (type) {
            case USER_STATE_HASH_QUERY, USER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY,
                    USER_OPEN_ORDERS_QUERY, USER_OPEN_TRIGGER_ORDERS_QUERY, RISK_STATE_QUERY,
                    ORDER_PREFLIGHT_QUERY -> true;
            default -> false;
        };
    }

    boolean isMatchingPending(UUID commandId) {
        return pendingMatching.findByCommandId(commandId) != null;
    }

    long matchingSequence(UUID commandId) {
        PendingMatching pending = pendingMatching.findByCommandId(commandId);
        return pending == null ? 0 : pending.sequence();
    }

    void drainMatchingCompletions() {
        crossShardCancellations.poll();
        progressPlaceAdmissions();
        matcherPipeline.drainMatchingCompletions(this::publishMatchingCompletion);
        commits.drainMatcherSettlementCompletions();
    }

    long matchingProgressSequence() {
        return matchingProgressSequence;
    }

    /** Only external completion cursors; the caller must first exhaust owner-local progress. */
    boolean hasMatchingNotifications() {
        return hasMatchingNotifications(System.nanoTime());
    }

    boolean hasMatchingNotifications(long now) {
        // A failed Lane need not publish a completion. Keep a bounded empty-path health
        // check; apply/commit still check on every invocation, including ready notifications.
        if (now - nextMatchingHealthCheckNs >= 0) {
            assertHealthy();
            nextMatchingHealthCheckNs = now + 1_000_000L;
        }
        return runtimeState.hasMatchingNotifications() || matcherPipeline.hasMatchingCompletions();
    }

    void beginDownstreamPublicationBatch() {
        runtimeProjectionJournal.beginPublicationBatch();
    }

    void endDownstreamPublicationBatch() {
        runtimeProjectionJournal.endPublicationBatch();
    }

    int matchingCompletionHighWaterMark() {
        return matcherPipeline.completionHighWaterMark();
    }

    int dispatchedSettlementHighWaterMark() {
        return commits.dispatchedSettlementHighWaterMark;
    }

    int matchingCompletionCapacity() {
        return matcherPipeline.capacity();
    }

    @FunctionalInterface
    interface MatchingCommitHandler {
        void onCommitted(long sequence, CoreResponse response);
    }

    public int pendingMatchingCount() {
        return pendingMatching.size();
    }

    public long terminalTradeCount() {
        assertOwner();
        return terminalTradeCount;
    }

    public CoreLaneMetrics laneMetrics() {
        var metrics = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(encodeLaneMetrics());
        return new CoreLaneMetrics(
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

    byte[] encodeLaneMetrics() {
        assertOwner();
        int count = matchingAdapter.topology().accountLaneCount();
        var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                matchingAdapter.topology().matchingEngineCount(), count,
                matcherPipeline.submissionDepth(), matcherPipeline.capacity(),
                matcherPipeline.submissionHighWaterMark(), matcherPipeline.completionDepth(),
                matcherPipeline.capacity(), matcherPipeline.completionHighWaterMark(),
                laneCommandContexts.inFlight(), laneCommandContexts.capacity(),
                laneCommandContexts.highWaterMark(), committedCoreSequence);
        for (int laneId = 0; laneId < count; laneId++) {
            runtimeState.writeAccountLaneMetrics(laneId, encoder);
        }
        return encoder.finish();
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
        return runtimeState.accountLaneSnapshots(fenceSequence, globalState);
    }

    void restoreAccountLaneSnapshots(
            List<com.surprising.aeron.service.state.AccountLaneSnapshot> snapshots,
            long fenceSequence) {
        if (activated) throw new IllegalStateException("account lanes must restore before activation");
        runtimeState.restoreAccountLaneSnapshots(snapshots, fenceSequence, snapshotTradingState());
    }

    void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed core state");
        try {
            matcherPipeline.start(matchingAdapter::activateShard);
            bindOwner();
            runtimeState.startAccountLanes();
            matchingAdapter.activate();
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

    PendingMatching pendingMatching(long sequence) {
        return pendingMatching.get(sequence);
    }

    int terminalRetentionCandidateCount() {
        return terminalRetention.candidateCount();
    }

    int terminalRetentionTombstoneCount() {
        return terminalRetention.tombstoneCount();
    }

    int runtimeClientIdentityCount() {
        return identities.clientIdentityCount();
    }

    TerminalStateRetention terminalRetention() {
        return terminalRetention;
    }

    public long stateHash() {
        return stateHash(cachedBusinessStateHash);
    }

    long stateHash(long businessStateHash) {
        return stateHash(businessStateHash, null, null, null, 0);
    }

    long stateHash(long businessStateHash, UUID commandId, ResponseStatus commandStatus,
                           CoreResultCode commandResultCode, long commandAppliedCommandCount) {
        return businessStateHash;
    }

    byte[] captureSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        byte[] snapshot = snapshots.pollSnapshot(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        if (snapshots.snapshotFence != null && snapshots.snapshotFence.encodedSnapshot == null) snapshots.releaseSnapshotFence();
        throw new SnapshotNotReadyException();
    }

    static final class SnapshotNotReadyException extends IllegalStateException {
        SnapshotNotReadyException() {
            super("snapshot not ready");
        }
    }

    static final class SnapshotFenceTimeoutException extends IllegalStateException {
        SnapshotFenceTimeoutException() {
            super("snapshot fence timed out");
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

    com.surprising.aeron.service.matching.FatalMatchingDivergenceException failMatching(
            PendingMatching pending,
            String detail,
            Throwable cause) {
        try {
            matchingAdapter.poisonFromOwner("deterministic matcher settlement failed sequence="
                    + pending.sequence() + " operation=" + pending.operation());
        } catch (RuntimeException poisonFailure) {
            if (cause != null) cause.addSuppressed(poisonFailure);
            else cause = poisonFailure;
        }
        fatalFailure = cause == null
                ? new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                        pending.operation().name(), pending.sequence(), 0, detail)
                : new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                        pending.operation().name(), pending.sequence(), 0, detail, cause);
        return fatalFailure;
    }

    void assertClusterCallbackComplete() {
        if (!activated) activate();
        assertOwner();
        assertHealthy();
        if (pendingDirectCommand != null || !pendingMatching.isEmpty() || !bookQueries.queryIds.isEmpty()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback");
        }
    }

    void assertHealthy() {
        if (commitPublicationFailure != null) throw commitPublicationFailure;
        if (runtimeState != null) runtimeState.assertAccountLanesHealthy();
        runtimeProjectionJournal.assertHealthy();
        exportState.assertHealthy();
        RuntimeException auditFailure = snapshots.snapshotAuditFailure.get();
        if (auditFailure != null) throw auditFailure;
        if (fatalFailure != null) throw fatalFailure;
    }

    public static TradingCoreRuntime fromSnapshot(ProductLine productLine, byte[] snapshot) {
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
        return auditHashCoreSequence;
    }

    long committedFundsHashCoreSequence() {
        return auditHashCoreSequence;
    }

    long committedProjectionSequence() {
        return runtimeProjectionJournal.publishedSequence();
    }

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        runtimeState.requireSnapshotFenceReady();
        return com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(
                runtimeState, identities);
    }

    // Scalar observations of the committed owner view; no Lane reads or snapshot materialization.
    int incompleteRiskScanCount() { return runtimeState.incompleteRiskScanCount(); }
    int incompleteFundingCount() { return runtimeState.treasury().incompleteFundingCount(); }
    int activeOrderCount() { return activeOrderIndex.count(); }
    int positionCount() { return runtimeState.publishedPositionCount(); }
    int triggerOrderCount() { return triggerOrderIndex.ids().size(); }

    TradingCoreState snapshotTradingState() {
        CoreSnapshotLifecycle.SnapshotFence fence = snapshots.snapshotFence;
        if (fence != null && fence.snapshotState != null) return fence.snapshotState;
        runtimeState.requireSnapshotFenceReady();
        return com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(
                runtimeState, identities);
    }

    long snapshotBusinessStateHash() {
        return currentBusinessStateHash();
    }

    long snapshotBusinessAuditBaseHash() {
        return auditBusinessStateHash;
    }

    long snapshotFundsStateHash() {
        return auditFundsStateHash;
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
        return runtimeState.firstIncompleteRiskScan() == null;
    }

    boolean runtimeRiskScanComplete(String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : runtimeState.riskScan(symbolId);
        return scan == null || scan.complete();
    }

    RiskScanRuntime runtimeRiskScan(String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        return symbolId == null ? null : runtimeState.riskScan(symbolId);
    }

    MarkPriceRuntime runtimeMarkPrice(String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        return symbolId == null ? null : runtimeState.markPrice(symbolId);
    }

    long runtimeFundingSettlement(String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        return symbolId == null ? 0 : runtimeState.treasury().fundingSettlement(symbolId);
    }

    long runtimeInsurance(String asset) {
        Integer assetId = identities.findAssetId(asset);
        return assetId == null ? 0 : runtimeState.treasury().insurance(assetId);
    }

    LiquidationRuntime runtimeLiquidation(long liquidationId) {
        return runtimeState.liquidation(liquidationId);
    }

    PositionRuntime runtimePosition(long userId, String positionKey) {
        Long key = identities.findPositionKey(userId, positionKey);
        return key == null ? null : runtimeState.position(key);
    }

    boolean snapshotHasOutstandingReservation() {
        return currentAdmission != null
                || runtimeProjectionJournal.hasOutstandingReservation();
    }

    Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies() {
        return runtimeState.feePoliciesSnapshot();
    }

    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
        long beforeBusinessStateHash = cachedBusinessStateHash;
        runtimeState.restoreFeePolicies(policies);
        cachedFeePolicyHash = computeFeePolicyHash(policies);
        cachedBusinessStateHash = currentBusinessStateHash();

        runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, cachedBusinessStateHash);
    }

    Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers() {
        return runtimeState.pendingTransfersSnapshot();
    }

    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        long beforeBusinessStateHash = cachedBusinessStateHash;
        runtimeState.restorePendingTransfers(transfers);
        cachedTransferHash = computeTransferHash(transfers);
        cachedBusinessStateHash = currentBusinessStateHash();

        runtimeProjectionJournal.rebaseInitialBusinessStateHash(
                beforeBusinessStateHash, cachedBusinessStateHash);
    }

    CoreExportState exportState() {
        return exportState;
    }

    long sourceSequenceDigest() {
        return sourceSequenceDigest(lastSourceSequences);
    }

    Map<UUID, StoredResult> commandResults() {
        return resultLedger.entries();
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences);
    }

    ResponseStatus applyCommand(CoreMessage message, long clusterTimestamp) {
        switch (message.header().messageType()) {
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payloadUnsafe()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> balances.executeAdjustBalance(message, clusterTimestamp);
            case TRANSFER_OUT -> balances.executeTransferOut(message, clusterTimestamp);
            case TRANSFER_IN -> balances.executeTransferIn(message, clusterTimestamp);
            case COMPLETE_TRANSFER -> balances.executeCompleteTransfer(message, clusterTimestamp);
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                    PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                    EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                    throw new IllegalStateException("matching command must use async continuation");
            case UPSERT_INSTRUMENT -> instruments.executeUpsertInstrument(message, clusterTimestamp);
            case APPLY_MARK_PRICE -> derivativeRisk.executeApplyMarkPrice(message, clusterTimestamp);
            case APPLY_FUNDING -> funding.executeApplyFunding(message, clusterTimestamp);
            case EXECUTE_ADL -> derivativeRisk.executeExecuteAdl(message, clusterTimestamp);
            case RESOLVE_LIQUIDATION -> derivativeRisk.executeResolveLiquidation(message, clusterTimestamp);
            case CONTINUE_RISK_SCAN -> derivativeRisk.executeContinueRiskScan(message, clusterTimestamp);
            case UPDATE_RISK_SCAN_CONTROL -> derivativeRisk.executeUpdateRiskScanControl(message, clusterTimestamp);
            case UPDATE_INSTRUMENT_MAINTENANCE -> instruments.executeUpdateInstrumentMaintenance(message, clusterTimestamp);
            case UPSERT_FEE_POLICY -> {
                runtimeState.upsertFeePolicy(
                        TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe()));
                cachedFeePolicyHash = computeFeePolicyHash(runtimeState.feePoliciesSnapshot());
                commits.requestCommitPublication();
            }
            case UPDATE_POSITION_MODE -> {
                resultBuilder.commandChangedUserIds = List.of(message.header().userId());
                if (DerivativeAccountCommandProcessor.updatePositionMode(runtimeState,
                        message.header().userId(),
                        TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe()))) {
                    commits.requestCommitPublication();
                }
            }
            case ADJUST_POSITION_MARGIN -> derivativeAccounts.executeAdjustPositionMargin(message, clusterTimestamp);
            case ADJUST_INSURANCE_FUND -> derivativeRisk.executeAdjustInsuranceFund(message, clusterTimestamp);
            case UPDATE_LEVERAGE -> derivativeAccounts.executeUpdateLeverage(message, clusterTimestamp);
            case UPSERT_ALGO_ORDER -> triggers.executeUpsertAlgoOrder(message, clusterTimestamp);
            case UPDATE_CANCEL_ALL_AFTER -> triggers.executeUpdateCancelAllAfter(message, clusterTimestamp);
            case PLACE_TRIGGER_ORDER -> triggers.executePlaceTriggerOrder(message, clusterTimestamp);
            case CANCEL_TRIGGER_ORDER -> triggers.executeCancelTriggerOrder(message, clusterTimestamp);
            case CLAIM_TRIGGER_ORDER -> triggers.executeClaimTriggerOrder(message, clusterTimestamp);
            case COMPLETE_TRIGGER_ORDER -> triggers.executeCompleteTriggerOrder(message, clusterTimestamp);
            case UPDATE_TRIGGER_TRAILING -> triggers.executeUpdateTriggerTrailing(message, clusterTimestamp);
            case EXPIRE_TRIGGER_ORDER -> triggers.executeExpireTriggerOrder(message, clusterTimestamp);
            case RETRY_TRIGGER_ORDER -> triggers.executeRetryTriggerOrder(message, clusterTimestamp);
            case EXECUTE_TRIGGER_ORDER -> triggers.executeExecuteTriggerOrder(message, clusterTimestamp);
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    void cancelAllOcoSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger) {
        long cursor = Long.MAX_VALUE;
        while (true) {
            OcoCancellationPage page = triggers.cancelOcoSiblings(trigger, cursor, DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
            if (page.complete()) return;
            if (page.workUnits() == 0) throw new IllegalStateException("OCO cancellation made no progress");
            cursor = page.nextCursor();
        }
    }

    record OcoCancellationPage(boolean complete, long nextCursor, int workUnits) {
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner != null && owner != current)
            throw new IllegalStateException("trading runtime is bound to another thread");
        owner = current;
        runtimeState.bindOwner();
        identities.assertOwner();
    }

    void assertOwner() {
        if (owner == null) bindOwner();
        else if (owner != Thread.currentThread())
            throw new IllegalStateException("trading runtime is bound to another thread");
    }

    void deferProvisionalSnapshotProjection() {
        if (!commits.commitPublicationDeferred) {
            throw new IllegalStateException("provisional projection requires a command batch");
        }
        if (!commits.commitPublicationDirty) commits.commitPublicationProvisionalOnly = true;
        commits.commitPublicationDirty = true;
    }

    com.surprising.aeron.service.state.PlaceAdmissionEvent dispatchPlaceAdmission(
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                identities, userId, command, currentClusterTimestamp);
        var identity = com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                runtimeState, identities, userId, resolved);
        var preparedClientKey = identities.prepareClientKey(
                userId, resolved.clientOrderId());
        int assetId = identities.assetId(resolved.reservationAsset());
        return runtimeState.dispatchPlaceAdmission(coreSequence, userId, resolved, commandId,
                openInterestIndex.openInterestSteps(resolved.symbol()), identity,
                preparedClientKey, resolved.symbolId(), assetId);
    }

    void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                          long pendingCoreSequence) {
        reservePlaceOrderRuntime(userId, command, commandId, pendingCoreSequence,
                openInterestIndex.openInterestSteps(command.symbol()), activeOrderIndex);
    }

    void reservePlaceOrderRuntime(
            long userId, PlaceOrderCommand command, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex) {
        reservePlaceOrderRuntime(userId, command, commandId, pendingCoreSequence, openInterestSteps,
                admissionOrderIndex, null);
    }

    void reservePlaceOrderRuntime(
            long userId, PlaceOrderCommand command, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex,
            OrderBatchPending batch) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                identities, userId, command, currentClusterTimestamp);
        var admissionIdentity =
                com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                        runtimeState, identities, userId, resolved);
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence,
                openInterestSteps, admissionOrderIndex, admissionIdentity, batch);
    }

    void reservePlaceOrderRuntime(ResolvedMatchingAdmission admission, UUID commandId,
                                          long pendingCoreSequence) {
        reservePlaceOrderRuntime(admission.userId(), admission.resolved(), commandId, pendingCoreSequence,
                admission.requiredReservationUnits());
    }

    void reservePlaceOrderRuntime(long userId, ResolvedPlaceOrder resolved, UUID commandId,
                                          long pendingCoreSequence, long requiredReservation) {
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence, requiredReservation, null);
    }

    void reservePlaceOrderRuntime(long userId, ResolvedPlaceOrder resolved, UUID commandId,
                                          long pendingCoreSequence, long requiredReservation,
                                          OrderBatchPending batch) {
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence,
                0, null, null, requiredReservation, batch);
    }

    void reservePlaceOrderRuntime(
            long userId, ResolvedPlaceOrder resolved, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionIdentity admissionIdentity,
            OrderBatchPending batch) {
        reservePlaceOrderRuntime(userId, resolved, commandId, pendingCoreSequence,
                openInterestSteps, admissionOrderIndex, admissionIdentity, 0, batch);
    }

    void reservePlaceOrderRuntime(
            long userId, ResolvedPlaceOrder resolved, UUID commandId, long pendingCoreSequence,
            long openInterestSteps,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionOrderIndex admissionOrderIndex,
            com.surprising.aeron.service.state.RuntimeOrderAdmission.AdmissionIdentity admissionIdentity,
            long preparedRequiredReservation, OrderBatchPending batch) {
        var preparedClientKey = identities.prepareClientKey(
                userId, resolved.clientOrderId());
        int symbolId = resolved.symbolId();
        int assetId = identities.assetId(resolved.reservationAsset());
        long spotAmendOriginal = batch != null && batch.kind == OrderBatchKind.AMEND
                && productLine == ProductLine.SPOT
                ? ((AmendOrderCommand) batch.items.get(batch.nextIndex).command).originalOrderId() : 0;
        try {
            runtimeState.executeUserSettlement(userId, () -> {
                // Reuse the existing reservation task and batch funds boundary. The matcher has
                // replaced the old order, so its locked funds must be available to the replacement.
                if (spotAmendOriginal != 0) {
                    RuntimeCommandProcessor.cancelOrder(runtimeState, userId, spotAmendOriginal);
                }
                long requiredReservation = admissionIdentity == null
                        ? preparedRequiredReservation
                        : com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationPrepared(
                                runtimeState, userId, resolved, openInterestSteps,
                                admissionOrderIndex, admissionIdentity);
                RuntimeCommandProcessor.placeOrderPrepared(runtimeState, userId, resolved,
                        commandId, requiredReservation, preparedClientKey.key(), symbolId, assetId);
                runtimeState.markPendingReservation(userId, resolved.orderId(), pendingCoreSequence);
                return null;
            });
        } catch (RuntimeException | Error failure) {
            if (preparedClientKey.allocated()) {
                identities.rollbackPreparedClientKey(
                        userId, resolved.clientOrderId(), preparedClientKey);
            }
            throw failure;
        }
        if (batch != null) {
            batch.retainPreparedClientKey(userId, resolved.clientOrderId(), preparedClientKey);
        }
        if (batches.pendingOrderBatches.isEmpty()) deferProvisionalSnapshotProjection();
        else commits.requestCommitPublication();
    }

    ResolvedMatchingAdmission requireMatchingAdmission(PendingMatching pending) {
        ResolvedMatchingAdmission admission = pending.admission();
        if (admission == null) throw new IllegalStateException("replace admission is missing");
        return admission;
    }

    void requireUnchangedAdmissionState(ResolvedMatchingAdmission admission) {
        OrderRuntime original = runtimeOrder(admission.originalOrderId());
        var user = runtimeState.user(admission.userId());
        long userRevision = user == null ? 0 : user.revision();
        if (original == null || original.revision() != admission.originalOrderRevision()
                || userRevision != admission.userRevision()) {
            throw new IllegalStateException("replace admission state changed before matcher completion");
        }
    }

    CoreMatchingOrder matchingOrder(long orderId) {
        com.surprising.aeron.service.state.OrderRuntime order = runtimeState.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "runtime order is missing");
        return new CoreMatchingOrder(order.orderId(), identities.symbol(order.symbolId()),
                order.side(), order.orderType(), order.timeInForce(), order.matchingPriceTicks(),
                order.remainingQuantitySteps());
    }

    OrderRuntime runtimeOrder(long orderId) {
        return runtimeState.order(orderId);
    }

    String runtimeOrderSymbol(OrderRuntime order) {
        return identities.symbol(order.symbolId());
    }

    CoreOrderStateView runtimeOrderView(long orderId) {
        OrderRuntime order = runtimeOrder(orderId);
        return order == null ? null : orderView(order);
    }

    String runtimeLiquidationSymbol(
            com.surprising.aeron.service.state.LiquidationRuntime liquidation) {
        return identities.symbol(liquidation.symbolId());
    }

    com.surprising.aeron.service.state.TreasuryRuntime.LifecycleProgressRuntime
            runtimeLifecycleProgress(String symbol) {
        Integer symbolId = identities.findSymbolId(symbol);
        return symbolId == null ? null : runtimeState.treasury().lifecycleProgress(symbolId);
    }

    CoreMatchingOrder matchingOrder(long userId, PlaceOrderCommand intent) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                identities, userId, intent, currentClusterTimestamp);
        return new CoreMatchingOrder(resolved.orderId(), resolved.symbol(), resolved.side(), resolved.orderType(),
                resolved.timeInForce(), resolved.matchingPriceTicks(), resolved.quantitySteps());
    }

    void cancelOrderRuntime(long userId, long orderId) {
        if (runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.cancelOrder(runtimeState, userId, orderId))) {
            commits.requestCommitPublication();
        }
    }

    void rejectPlaceOrderRuntime(long userId, long orderId, long coreSequence) {
        runtimeState.executeUserSettlement(userId, () -> {
            RuntimeCommandProcessor.rejectPlaceOrder(runtimeState, userId, orderId, coreSequence);
            return null;
        });
        commits.requestCommitPublication();
    }

    void stampOrderChangesRuntime(long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (RuntimeCommandProcessor.stampChangedOrdersByLane(
                runtimeState, timestamp, clusterPosition,
                changedOrderIds, resultBuilder.commandChangedUserIds)) {
            commits.requestCommitPublication();
        }
    }

    com.surprising.aeron.service.state.RuntimeTreasuryDelta applyMatchesOnAccountLanes(
            PendingMatching pending,
            com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan,
            long coreSequence,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            LaneCommandContextRing.Context laneContext,
            long applyStartNanos) {
        com.surprising.aeron.service.state.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null) {
            captureRealtimeTrades(settlementPlan);
            event = runtimeState.dispatchMatcherSettlement(
                    coreSequence, laneContext.expectedLaneMask(), coreSequence,
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), settlementPlan,
                    matchingResult, identities);
            pending.settlement(event, settlementPlan, applyStartNanos);
        }
        if (pending.isDispatchOnly()) return null;
        if (!event.complete()) return null;
        com.surprising.aeron.service.state.RuntimeTreasuryDelta delta =
                runtimeState.collectMatcherSettlement(
                        event, commandFundsAccumulator, terminalRetention);
        laneContext.completeLanes(event.requiredLaneMask());
        return delta;
    }

    void suspendMatchingCommitContext(PendingMatching pending) {
        if (!commits.commitPublicationDeferred || pending == null
                || pending.settlementEvent() == null && pending.cancelEvent() == null
                && pending.replaceEvent() == null
                && !batches.pendingOrderBatches.containsKey(pending.sequence())
                && !commits.controlPending(pending.sequence())) {
            throw new IllegalStateException("matching commit context cannot be suspended");
        }
        resultBuilder.materializeChangeAccumulators();
        laneCommandContexts.required(pending.sequence()).suspendCommitContext(
                resultBuilder.commandChangedUserIds, resultBuilder.commandChangedOrderIds, commandFundsAccumulator,
                commits.commitPublicationDirty, commits.commitPublicationProvisionalOnly);
        commits.commitPublicationDeferred = false;
        commits.commitPublicationDirty = false;
        commits.commitPublicationProvisionalOnly = false;
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    void restoreMatchingCommitContext(PendingMatching pending) {
        if (commits.commitPublicationDeferred || currentAdmission != null) {
            throw new IllegalStateException("another owner commit context is active");
        }
        LaneCommandContextRing.Context context = laneCommandContexts.required(pending.sequence());
        activateFactContext(sequenceAdmission(pending.sequence()), pending.command(), pending.fingerprint());
        commits.commitPublicationDeferred = true;
        commits.commitPublicationDirty = context.commitSnapshotDirty();
        commits.commitPublicationProvisionalOnly = context.commitSnapshotProvisionalOnly();
        resultBuilder.commandChangedUserIds = context.commitChangedUserIds();
        resultBuilder.commandChangedOrderIds = context.commitChangedOrderIds();
        resultBuilder.changedUserIds.clear();
        resultBuilder.changedUserIds.addAll(resultBuilder.commandChangedUserIds);
        resultBuilder.changedOrderIds.clear();
        resultBuilder.changedOrderIds.addAll(resultBuilder.commandChangedOrderIds);
        context.copyCommitFundsTo(commandFundsAccumulator);
        context.clearCommitContext();
        commandExternalAdjustment = false;
        resultBuilder.commandTradeCount = 0;
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandRiskScanControl = null;
    }

    void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
        if (command == null || terminalRetention.containsOrder(command.orderId(), userId, command.clientOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "terminal order identity is retained");
        }
    }

    void rollbackCommandState(
            long runtimeCheckpoint,
            long positionIdentityCheckpoint,
            long commandSequence) {
        commits.abortCommitPublicationBatch();
        runtimeState.rollbackActiveCommand(runtimeCheckpoint, commandSequence);
        identities.rollbackPositionKeys(positionIdentityCheckpoint);
    }

    void queueTriggerMatching(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId, long childOrderId) {
        UUID commandId = UUID.nameUUIDFromBytes((parentCommandId + ":trigger:" + trigger.triggerOrderId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandId,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        admissions.queuedMatching.add(new MatchingCommandAdmission.QueuedTriggerMatching(
                new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                        trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)),
                matchingOrder(childOrderId)));
    }

    void addChangedUsers(com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        resultBuilder.changedUserIds.add(plan.activeUserId());
        for (int index = 0; index < plan.orderCount(); index++) {
            var order = runtimeState.order(plan.orderId(index));
            if (order != null) resultBuilder.changedUserIds.add(order.userId());
        }
        resultBuilder.commandChangedUserIds = List.of();
    }

    void setCommandFundsDelta(
            com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta) {
        commandFundsAccumulator.clear();
        commandFundsAccumulator.add(fundsDelta);
    }

    void validateFundsConservation(CoreMessage command) {
        boolean externalAdjustment = command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                || command.header().messageType() == CoreMessageType.TRANSFER_OUT
                || command.header().messageType() == CoreMessageType.TRANSFER_IN
                || command.header().messageType() == CoreMessageType.ADJUST_INSURANCE_FUND;
        commandFundsAccumulator.requireConserved(externalAdjustment);
    }

    long stageLaneMutation(
            long sequence, Iterable<Long> userIds, LaneCommandContextRing.Context context) {
        if (context == null || sequence <= 0) {
            throw new IllegalStateException("account lane commit context is missing");
        }
        long laneMask = runtimeState.stageLaneMutation(sequence, userIds);
        context.completeLanes(laneMask);
        return laneMask;
    }

    long stageLaneMutation(
            long sequence, long[] userIds, LaneCommandContextRing.Context context) {
        if (context == null || sequence <= 0) {
            throw new IllegalStateException("account lane commit context is missing");
        }
        long laneMask = runtimeState.stageLaneMutation(sequence, userIds);
        context.completeLanes(laneMask);
        return laneMask;
    }

    void seedChangeAccumulators() {
        if (resultBuilder.commandChangedUserIds != null) resultBuilder.changedUserIds.addAll(resultBuilder.commandChangedUserIds);
        if (resultBuilder.commandChangedOrderIds != null) resultBuilder.changedOrderIds.addAll(resultBuilder.commandChangedOrderIds);
    }

    static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    static void addMatchingUserIds(
            PrimitiveLongChangeSet ids, long takerUserId, List<MatcherEvent> matches) {
        ids.add(takerUserId);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            ids.add(match.matchedOrderUid());
        }
    }

    static List<Long> matchingOrderIds(List<Long> initialOrderIds, List<MatcherEvent> matches) {
        PrimitiveLongChangeSet ids = new PrimitiveLongChangeSet();
        ids.addAll(initialOrderIds);
        for (MatcherEvent match : matches) {
            if (match.eventType() != MatcherEventType.TRADE) continue;
            ids.add(match.matchedOrderId());
        }
        return immutableLongList(ids);
    }

    static List<Long> immutableLongList(PrimitiveLongChangeSet ids) {
        return ids.toImmutableList();
    }

    static List<Long> boxedOrderIds(
            com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        long[] values = new long[plan.orderCount()];
        for (int index = 0; index < values.length; index++) values[index] = plan.orderId(index);
        return ImmutableLongArrayList.takeOwnership(values);
    }

    int pendingRiskScanCount() {
        return runtimeState.incompleteRiskScanCount();
    }

    void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt) {
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
        if (activated) assertOwner();
        closed = true;
        snapshots.releaseSnapshotFence();
        snapshots.inFlightMatcherSnapshot.set(null);
        matcherPipeline.closeShards(matchingAdapter::closeShard);
        bookQueries.completedBookQueries.clear();
        bookQueries.failedQueries.clear();
        bookQueries.queryIds.clear();
        runtimeState.endOrderBatchMutationScope();
        if (currentAdmission != null) {
            currentAdmission.releaseUnused();
            clearFactContext();
        }
        pendingMatching.forEach(pending -> {
            sequenceAdmission(pending.sequence()).releaseUnused();
        });
        pendingMatching.clear();
        crossShardCancellations.clear();
        admissions.pendingLifecycleScopes.clear();
        batches.pendingOrderBatches.clear();
        admissions.deferredMatching.clear();
        exportState.close();
        runtimeProjectionJournal.close();
        runtimeState.close();
    }

    CoreResponse userStateResponse(long userId) {
        var query = com.surprising.aeron.service.state.RuntimeStateQueryService.userState(
                runtimeState, identities, userId);
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

    void captureRealtimeTrades(com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        if (realtimeCapture == null || !realtimeCapture.active()) return;
        try {
            OrderRuntime taker = runtimeOrder(plan.takerOrderId());
            for (int index = 0; index < plan.matcherEventCount(); index++) {
                MatcherEvent match = plan.matcherEvent(index);
                if (match.eventType() == MatcherEventType.TRADE)
                    realtimeCapture.trade(taker, plan.coreSequence(), index, match.price(), match.size(),match.matchedOrderId(),match.matchedOrderUid());
            }
        } catch (RuntimeException failure) { realtimeCapture.failed(); }
    }

    static long tradeCount(com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        long trades = 0;
        for (int index = 0; index < plan.matcherEventCount(); index++)
            if (plan.matcherEvent(index).eventType() == MatcherEventType.TRADE) trades++;
        return trades;
    }

    OrderRuntime responseOrder(long orderId) {
        OrderRuntime changed = runtimeState.changedOrderValue(orderId);
        return changed == null ? runtimeOrder(orderId) : changed;
    }

    CoreOrderStateView orderView(OrderRuntime order) {
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(),
                runtimeOrderSymbol(order), order.instrumentChangeId(), order.side(), order.priceTicks(),
                order.quantitySteps(), order.executedQuantitySteps(), order.remainingQuantitySteps(),
                order.reduceOnly(), order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(),
                order.postOnly(), order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(),
                order.takerFeeRatePpm(), order.cumulativeFeeUnits(), order.createdAtEpochMillis(),
                order.updatedAtEpochMillis(), order.clusterPosition(), order.status().name(), order.revision());
    }

    CoreResponse orderStateResponse(long orderId) {
        return orderStateResponse(com.surprising.aeron.service.state.RuntimeStateQueryService.orderState(
                runtimeState, identities, orderId));
    }

    CoreResponse orderStateResponse(
            com.surprising.aeron.service.state.RuntimeStateQueryService.OrderQueryResult query) {
        if (!query.found()) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, query.stateHash(),
                CoreStateQueryCodec.encodeOrderState(query.view()));
    }

    long currentBusinessStateHash() {
        return canonicalBusinessStateHash(auditBusinessStateHash);
    }

    long canonicalBusinessStateHash(long base) {
        if (cachedFeePolicyHash != 0) base = mix(base, cachedFeePolicyHash);
        return cachedTransferHash == 0 ? base : mix(base, cachedTransferHash);
    }

    static long canonicalBusinessStateHash(
            long base,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.service.state.TransferRuntime> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    static long computeTransferHash(
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

    static boolean isFundsIdempotencyCommand(CoreMessageType type) {
        return type == CoreMessageType.ADJUST_BALANCE || type == CoreMessageType.TRANSFER_OUT
                || type == CoreMessageType.TRANSFER_IN || type == CoreMessageType.COMPLETE_TRANSFER;
    }

    static long computeFeePolicyHash(
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
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

    static long mixText(long hash, String value) {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long result = mix(hash, encoded.length);
        for (byte item : encoded) {
            result ^= Byte.toUnsignedInt(item);
            result *= HASH_PRIME;
        }
        return result;
    }

    static long mix(long hash, long value) {
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

    static long sourceSequenceDigest(SourceKey key, long sequence) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount, stateHash());
    }

    static CoreResultCode matchingPendingCode() {
        return CoreResultCode.fromWireCode(MATCHING_PENDING_WIRE_CODE);
    }

    static final class PipelinedBatchNotApplicable extends RuntimeException {
        PipelinedBatchNotApplicable() {
            super(null, null, false, false);
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }

    public long querySequence(UUID queryId) { return bookQueries.querySequence(queryId); }
    public CoreResponse takeQueryResult(long queryId) { return bookQueries.takeQueryResult(queryId); }
    public byte[] snapshot() { return snapshots.snapshot(); }
    public byte[] snapshot(long snapshotId) { return snapshots.snapshot(snapshotId); }
    void beginSnapshot(long snapshotId, long deadlineNanos) { snapshots.beginSnapshot(snapshotId, deadlineNanos); }
    SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) { return snapshots.pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos); }
    byte[] pollSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) { return snapshots.pollSnapshot(clusterTimestamp, clusterPosition, nowNanos); }
    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) { return snapshots.captureSnapshotSections(clusterTimestamp, clusterPosition, nowNanos); }

    public CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) { return commits.completeMatching(sequence, matchingResult, clusterTimestamp, clusterPosition); }

}
