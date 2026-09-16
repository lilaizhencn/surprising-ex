package com.surprising.aeron.service.orchestration;


import com.surprising.aeron.service.orchestration.ClusterCommandWindow;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.command.DirectCommandDispatcher;
import com.surprising.aeron.service.command.balance.BalanceCommandContext;
import com.surprising.aeron.service.command.balance.BalanceTransferCommands;
import com.surprising.aeron.service.command.position.PositionCommands;
import com.surprising.aeron.service.command.leverage.LeverageCommands;
import com.surprising.aeron.service.command.risk.RiskCommandContext;
import com.surprising.aeron.service.command.risk.RiskCommands;
import com.surprising.aeron.service.command.liquidation.LiquidationCommandContext;
import com.surprising.aeron.service.command.liquidation.LiquidationCommands;
import com.surprising.aeron.service.command.adl.AdlCommands;
import com.surprising.aeron.service.command.insurance.InsuranceFundCommands;
import com.surprising.aeron.service.command.funding.FundingCommandContext;
import com.surprising.aeron.service.command.funding.PerpetualFundingCommands;
import com.surprising.aeron.service.command.settlement.InstrumentSettlementCommands;
import com.surprising.aeron.service.command.settlement.SettlementCommandContext;
import com.surprising.aeron.service.command.fee.FeePolicyCommandContext;
import com.surprising.aeron.service.command.fee.FeePolicyCommands;
import com.surprising.aeron.service.command.trigger.TriggerCommandContext;
import com.surprising.aeron.service.command.trigger.TriggerCommandDispatcher;
import com.surprising.aeron.service.command.trigger.TriggerOrderCommands;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;
import com.surprising.aeron.service.matcher.MatcherCommandPipeline;
import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;

import com.surprising.aeron.service.orchestration.CoreSnapshotLifecycle.SnapshotFence;
import com.surprising.aeron.service.orchestration.OrderBookQueryService.CompletedBookQuery;
import com.surprising.aeron.service.orchestration.OrderBookQueryService.BookBootstrapSession;
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
public final class TradingCoreRuntime implements AutoCloseable,
        CommandResultContext, BalanceCommandContext, FundingCommandContext,
        RiskCommandContext, LiquidationCommandContext, SettlementCommandContext,
        FeePolicyCommandContext, TriggerCommandContext {


    /** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
    final InstrumentSettlementCommands instrumentSettlement = new InstrumentSettlementCommands(this);


    /** 撮合命令准入：校验订单与生命周期依赖，登记延后命令，不提交未完成结算。 */
    final MatchingCommandAdmission admissions = new MatchingCommandAdmission(this);

    /** 有序提交阶段：验证撮合证据、收集 Lane 结算并发布已提交变化；不重新执行资金业务。 */
    final OrderedCommitCoordinator commits = new OrderedCommitCoordinator(this);

    /** 余额调整与资金转入转出命令；维护转账幂等状态。 */
    final BalanceTransferCommands balances = new BalanceTransferCommands(this);

    /** 币对配置与维护状态命令；六产品线按所属运行时隔离。 */
    final com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands instruments =
            new com.surprising.aeron.service.command.instrument.InstrumentConfigurationCommands(this);

    /** 永续合约资金费命令；沿用 U 本位和币本位各自结算规则。 */
    final PerpetualFundingCommands funding = new PerpetualFundingCommands(this);

    /** 衍生品标记价、风险续扫和风险扫描控制命令。 */
    final RiskCommands risk = new RiskCommands(this);

    /** 衍生品持仓模式和保证金命令；不处理杠杆变更。 */
    final PositionCommands positions = new PositionCommands(this);

    /** 衍生品杠杆命令；不处理持仓模式和保证金。 */
    final LeverageCommands leverage = new LeverageCommands(this);

    /** 衍生品强平命令；负责强平撤单推进和强平结果确认。 */
    final LiquidationCommands liquidations = new LiquidationCommands(this);

    /** 自动减仓命令。 */
    final AdlCommands adl = new AdlCommands(this);

    /** 保险基金调整命令。 */
    final InsuranceFundCommands insurance = new InsuranceFundCommands(this);

    /** 触发单/算法单命令；只负责触发生命周期和触发子单。 */
    final TriggerOrderCommands triggers = new TriggerOrderCommands(this);

    /** 直接业务命令路由；匹配和控制流水线由本编排器继续处理。 */
    final DirectCommandDispatcher directCommands = new DirectCommandDispatcher(
            balances, instruments, funding, risk, liquidations, adl, insurance,
            positions, leverage, instrumentSettlement, new FeePolicyCommands(this),
            new TriggerCommandDispatcher(triggers));

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
    final SourceSequenceIndex lastSourceSequences;
    /** 在途命令环；保存日志顺序及准入、撮合、结算的关联。 */
    final PendingMatchingRing pendingMatching;
    /** 待处理准入通知的撮合分片位图；仅调度提示，不是复制状态。 */
    long placeAdmissionReadyShardMask;
    /** 下一次空轮询健康检查时间；纳秒单调时钟。 */
    long nextMatchingHealthCheckNs = System.nanoTime();

    /** 每个在途序号的 Lane 提交上下文，与 pendingMatching 同生命周期。 */
    final CommandSlotRing laneCommandContexts;
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

    /** Owner 当前是否持有命令的变更缓冲；挂起时交还序号槽。 */
    boolean factContextActive;
    /** 已接收并应用的命令序号上界；可能高于已完成结算水位。 */
    long appliedCommandCount;
    /** 连续完成结算的全局命令水位；唯一 owner 推进。 */
    long committedCoreSequence;
    /** 确定性业务变更后发布失败；要求从快照和日志恢复。 */
    RuntimeException commitPublicationFailure;
    /** 协议探测命令的持久化值；不参与订单或资金计算。 */
    long probeValue;
    /** 当前业务状态摘要缓存，提交边界更新。 */
    long cachedBusinessStateHash;
    /**
     * Owner-only materialized read view.  The live runtime is authoritative; callers that ask
     * for a TradingCoreState read view must not rebuild the complete immutable graph repeatedly
     * while the committed state is unchanged (which used to recreate TreeMap entries, user
     * records and order snapshots on every query/verification call).
     */
    private TradingCoreState materializedStateCache;
    private long materializedStateCacheRevision = Long.MIN_VALUE;
    private long materializedStateCacheMarketRevision = Long.MIN_VALUE;
    private long materializedStateCacheSequence = Long.MIN_VALUE;
    private long materializedStateCacheBusinessHash = Long.MIN_VALUE;
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

    @Override public TradingRuntimeState runtimeState() { return runtimeState; }
    @Override public RuntimeIdentityRegistry identities() { return identities; }
    @Override public void requestCommitPublication() { commits.requestCommitPublication(); }
    @Override public boolean asynchronousCommands() { return runtimeState.asynchronousCommands(); }
    @Override public void setSingleChangedUser(long userId) { resultBuilder.setSingleChangedUser(userId); }
    @Override public void markUserChanged(long userId) { resultBuilder.markUserChanged(userId); }
    @Override public void markOrderChanged(long orderId) { resultBuilder.markOrderChanged(orderId); }
    @Override public void beginChangedUsers() { resultBuilder.beginChangedUsers(); }
    @Override public void addChangedUser(long userId) { resultBuilder.addChangedUser(userId); }
    @Override public void addChangedUsersFromOrders(Iterable<? extends CoreOrderState> orders) {
        resultBuilder.addChangedUsersFromOrders(orders);
    }
    @Override public void addChangedUsersFromFundingPayments(
            Iterable<? extends com.surprising.aeron.protocol.CoreFundingPaymentView> payments) {
        resultBuilder.addChangedUsersFromFundingPayments(payments);
    }
    @Override public void setCommandChangedOrderIds(List<Long> orderIds) {
        resultBuilder.commandChangedOrderIds = orderIds;
    }
    @Override public void setCommandFundingProgress(CoreFundingProgressView progress) {
        resultBuilder.commandFundingProgress = progress;
    }
    @Override public void setCommandSettlementProgress(CoreSettlementProgressView progress) {
        resultBuilder.commandSettlementProgress = progress;
    }
    @Override public void setCommandRiskScanControl(
            com.surprising.aeron.protocol.CoreRiskScanControlView control) {
        resultBuilder.commandRiskScanControl = control;
    }
    @Override public void setCommandTriggerOrderView(
            com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        resultBuilder.commandTriggerOrderView = trigger;
    }
    @Override public void refreshTransferHash() {
        cachedTransferHash = computeTransferHash(runtimeState.pendingTransfersSnapshot());
    }
    @Override public void refreshFeePolicyHash() {
        cachedFeePolicyHash = computeFeePolicyHash(runtimeState.feePoliciesSnapshot());
    }
    @Override public PositionUserIndex positionUserIndex() { return positionUserIndex; }
    @Override public OpenInterestIndex openInterestIndex() { return openInterestIndex; }
    @Override public TriggerOrderIndex triggerOrderIndex() { return triggerOrderIndex; }
    @Override public ActiveOrderIndex activeOrderIndex() { return activeOrderIndex; }
    @Override public Iterable<Long> activeLiquidationIds() { return liquidationIndex.activeIds(); }
    @Override public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        triggers.initializeTriggerScan(command);
    }
    @Override public java.util.function.BooleanSupplier pendingTriggerScan(String symbol, int maxWork) {
        return triggers.pendingTriggerScan(symbol, maxWork);
    }
    @Override public void evaluatePendingTriggerScan(String symbol, int maxWork) {
        triggers.evaluatePendingTriggerScan(symbol, maxWork);
    }
    @Override public int queuedMatchingCount() { return admissions.queuedMatching.size(); }
    @Override public int defaultTriggerScanBatchSize() { return DEFAULT_TRIGGER_SCAN_BATCH_SIZE; }
    @Override public long currentClusterTimestamp() { return currentClusterTimestamp; }
    @Override public boolean terminalAlgoRetained(long algoOrderId, long userId, String clientAlgoOrderId) {
        return terminalRetention.containsAlgo(algoOrderId, userId, clientAlgoOrderId);
    }
    @Override public boolean terminalTriggerRetained(long triggerOrderId, long userId, String clientTriggerOrderId) {
        return terminalRetention.containsTrigger(triggerOrderId, userId, clientTriggerOrderId);
    }
    @Override public com.surprising.aeron.service.state.TreasuryRuntime.LifecycleProgressRuntime
            lifecycleProgress(String symbol) {
        return runtimeLifecycleProgress(symbol);
    }
    @Override public SettlementCommandContext.LifecycleOrderPage settlementLifecycleOrders(
            long userId, String symbol, long cursorOrderId, int maxOrders) {
        LifecycleOrderChunk page = lifecycleOrders(userId, symbol, cursorOrderId, maxOrders);
        return new SettlementCommandContext.LifecycleOrderPage(page.orders(), page.nextCursorOrderId());
    }

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
        this.lastSourceSequences = new SourceSequenceIndex(lastSourceSequences);
        admissions.pendingLifecycleScopes = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
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
                        matchingAdapter.topology().matchingCompletionCapacity()), false, laneCommandContexts);
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
            // These commands only modify Owner metadata, never account state.
            case PROBE_INCREMENT, VERIFY_STATE_HASH, UPDATE_CANCEL_ALL_AFTER, ACK_EXPORT -> false;
            case UPSERT_ALGO_ORDER, EXECUTE_TRIGGER_ORDER -> false;
            case PLACE_TRIGGER_ORDER, CANCEL_TRIGGER_ORDER, CLAIM_TRIGGER_ORDER, COMPLETE_TRIGGER_ORDER,
                    UPDATE_TRIGGER_TRAILING, EXPIRE_TRIGGER_ORDER, RETRY_TRIGGER_ORDER,
                    ADJUST_BALANCE, TRANSFER_IN, TRANSFER_OUT, COMPLETE_TRANSFER, UPDATE_LEVERAGE, UPDATE_POSITION_MODE, ADJUST_POSITION_MARGIN, APPLY_FUNDING, APPLY_MARK_PRICE,
                    UPDATE_RISK_SCAN_CONTROL, ADJUST_INSURANCE_FUND, UPSERT_INSTRUMENT,
                    UPDATE_INSTRUMENT_MAINTENANCE, UPSERT_FEE_POLICY -> false;
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER, CANCEL_ORDER_BATCH -> false;
            case CONTINUE_RISK_SCAN, AMEND_ORDER_BATCH, PLACE_ORDER_BATCH, EXECUTE_ADL, RESOLVE_LIQUIDATION,
                    EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH, SETTLE_INSTRUMENT -> false;
            default -> true;
        };
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return applyClusterCommand(message, timestamp, position, null);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        // Keep this package-level adapter equivalent to the real clustered service callback:
        // cluster ingress owns Account Lane workers for the whole apply, even when tests invoke
        // the runtime directly instead of going through SurprisingClusteredService.
        boolean entered = !runtimeState.asynchronousCommands();
        if (entered) runtimeState.enterAsynchronousCommandScope();
        try {
            return applyDecodedCommand(message, timestamp, position, decoded, true);
        } finally {
            if (entered) runtimeState.exitAsynchronousCommandScope();
        }
    }

    /** 仅当前apply范围持有的预计算摘要，嵌套命令不能误用外层摘要。 */
    private CommandFingerprint preparedIngressFingerprint;
    /** Static ingress route captured before apply() creates the pending matcher. */
    private IngressRoute preparedIngressRoute;

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
        try { return apply(message, timestamp, position); }
        finally {
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

    /**
     * Capture only deterministic ingress routing.  Potential maker accounts are
     * discovered by the Matcher from its order book after the command is admitted;
     * the Owner must not scan counterparty or instrument indexes here.
     */
    boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        if (!activated) activate();
        assertOwner();
        long user = message.header().userId();
        if (message.header().productLine() != productLine || user <= 0) {
            preparedIngressRoute = null;
            return false;
        }
        window.resetCandidate(user);
        try {
            // Decode once for the whole command. The window keeps the same
            // cached object while this command remains at the ingress head.
            DecodedMatchingCommand decoded = window.decoded(message);
            switch (message.header().messageType()) {
                case PLACE_ORDER -> {
                    PlaceOrderCommand command = decoded.placeOrder();
                    int shard = decoded.matcherShard(matchingAdapter, command.symbol());
                    window.route(shard, runtimeState.topology().accountLaneMask(user));
                    window.candidateOrder(command.orderId());
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER -> {
                    long orderId = decoded.cancelOrder().orderId();
                    var route = activeOrderIndex.activeOrderRoute(orderId);
                    if (route == null || route.userId() != user || route.symbol() == null
                            || route.symbol().isBlank()) return rememberPipelineRoute(window, false);
                    window.candidateOrder(orderId);
                    window.route(matchingAdapter.matcherShardId(route.symbol()),
                            runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                case PLACE_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.placeOrderBatch().orders()) {
                        int current = decoded.matcherShard(matchingAdapter, order.symbol());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        if (shard < 0) {
                            shard = current;
                            window.route(shard, runtimeState.topology().accountLaneMask(user));
                        }
                        window.candidateOrder(order.orderId());
                    }
                    if (shard < 0) return rememberPipelineRoute(window, false);
                    return rememberPipelineRoute(window, true);
                }
                case CANCEL_ORDER_BATCH -> {
                    int shard = -1;
                    for (var order : decoded.cancelOrderBatch().orders()) {
                        var route = activeOrderIndex.activeOrderRoute(order.orderId());
                        if (route == null || route.userId() != user || route.symbol() == null
                                || route.symbol().isBlank()) return rememberPipelineRoute(window, false);
                        window.candidateOrder(order.orderId());
                        int current = matchingAdapter.matcherShardId(route.symbol());
                        if (shard >= 0 && current != shard) return rememberPipelineRoute(window, false);
                        shard = current;
                    }
                    window.route(shard, runtimeState.topology().accountLaneMask(user));
                    return rememberPipelineRoute(window, true);
                }
                default -> { return rememberPipelineRoute(window, false); }
            }
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) {
            return rememberPipelineRoute(window, false);
        }
    }

    private boolean rememberPipelineRoute(ClusterCommandWindow window, boolean eligible) {
        preparedIngressRoute = eligible ? window.route() : null;
        return eligible;
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        if (!activated) activate();
        assertOwner();
        if (directCommand.directActive) throw new IllegalStateException("asynchronous control command is still active");
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
            var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.userState(
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
                            0, cachedBusinessStateHash, EMPTY_RESPONSE_DATA);
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
                var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.orderState(
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
                var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.clientOrderState(
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
                        .map(orderId -> com.surprising.aeron.service.state.query.RuntimeStateQueryService.orderState(
                                runtimeState, identities, orderId))
                        .filter(com.surprising.aeron.service.state.query.RuntimeStateQueryService.OrderQueryResult::found)
                        .map(com.surprising.aeron.service.state.query.RuntimeStateQueryService.OrderQueryResult::view)
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
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.triggerOrders(
                        runtimeState, source, message.header().userId(), query.symbol(), query.status(),
                        query.triggerOrderId(), before,
                        message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,
                        query.limit());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
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
                var views = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.treasuryAssets(
                        runtimeState, identities);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeTreasuryState(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                CoreFundingProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
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
                CoreSettlementProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
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
                                com.surprising.aeron.service.state.query.RuntimeRiskQueryService.adlCandidates(
                                        runtimeState, identities, query.asset(),
                                        adlPositionIndex.positions(query.asset()), query.limit())));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.query.RuntimeRiskQueryService.snapshots(
                        runtimeState, identities, message.header().userId());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
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
                    > com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.MAX_QUERY_ENTITIES) {
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
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.algoOrders(
                        runtimeState, algoIds);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
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
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.cancelAllAfter(
                        runtimeState, keys);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
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
                var work = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService.work(
                        runtimeState, identities, productLine, query, candidates,
                        liquidationIndex.activeIds());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
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
        return applyCommandIngress(message, clusterTimestamp, clusterPosition);
    }

    /**
     * Command-only ingress path.  Query dispatch and lifecycle fences stay in
     * {@link #apply(CoreMessage, long, long)}; this method owns the command
     * admission boundary and hands accepted work to the existing matching or
     * direct command processors without creating a command context object.
     */
    private CoreResponse applyCommandIngress(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        CommandFingerprint fingerprint = message == decodedIngressMessage && preparedIngressFingerprint != null
                ? preparedIngressFingerprint : CommandFingerprint.of(message);
        StoredResult terminalDuplicate = resultLedger.get(message.header().commandId());
        if (terminalDuplicate != null) {
            return resultLedger.duplicateResponse(terminalDuplicate, fingerprint, appliedCommandCount, stateHash());
        }
        CommandSlot pendingDuplicate = pendingMatching.findByCommandId(message.header().commandId());
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
                            CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, 0, stateHash(), EMPTY_RESPONSE_DATA);
                }
                return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.APPLIED,
                        CoreResultCode.NONE, appliedCommandCount, 0, stateHash(), EMPTY_RESPONSE_DATA);
            }
            if (!terminalRetention.hasFundsCommandCapacity(message.header().commandId())) {
                return rejected(CoreResultCode.FUNDS_IDEMPOTENCY_RETENTION_FULL);
            }
        }
        long lastSourceSequence = lastSourceSequences.lookupOrDefault(
                message.header().source(), message.header().sourceId(), -1);
        SourceKey sourceKey = lastSourceSequences.lastLookupKey();
        if (sourceKey == null) sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        if (lastSourceSequence >= 0 && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, appliedCommandCount, stateHash());
        }
        if (lastSourceSequence < 0 && lastSourceSequences.size() >= MAX_SOURCE_SEQUENCES) {
            return rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        if (isMatchingCommand(message.header().messageType())) {
            if (pendingMatching.size() >= pendingMatching.capacity()) {
                throw new IllegalStateException("matcher dispatch window is exhausted after Cluster Log append");
            }
            if (isOrderBatchCommand(message.header().messageType())) {
                return batches.beginOrderBatchMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
            }
            return admissions.beginMatching(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint);
        }
        activateFactContext(message, fingerprint);
        RuntimeProjectionPoint beforeProjection = currentProjectionPoint;
        long beforeRuntimeRevision = runtimeState.revision();
        long runtimeCommandCheckpoint = runtimeState.commandRevisionCheckpoint();
        long positionIdentityCheckpoint = identities.positionCheckpoint();
        directCommand.initializeDirect(message, fingerprint, sourceKey, clusterTimestamp, clusterPosition,
                beforeProjection, beforeRuntimeRevision, runtimeCommandCheckpoint, positionIdentityCheckpoint);
        resultBuilder.beginCommand();
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
        if (directCommand.controlWork == null && runtimeState.asynchronousCommands()
                && !clusterPipelineAdmission) {
            // 控制命令统一通过续步提交；失败时账户回滚也在所属 Lane 执行。
            directCommand.status = status;
            directCommand.resultCode = resultCode;
        }
        if (directCommand.controlWork != null || directCommand.status != null) {
            return null;
        }
        return finishDirectCommand(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint, beforeProjection, beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }

    private CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint, RuntimeProjectionPoint beforeProjection,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        long nextAppliedCommandCount = Math.incrementExact(appliedCommandCount);
        if (!directCommand.finalizationPrepared) {
            if (status != ResponseStatus.APPLIED
                    && (commits.commitPublicationDirty || runtimeState.revision() != beforeRuntimeRevision
                        || runtimeState.hasUncommittedCommandChanges())) {
                rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                        Math.incrementExact(appliedCommandCount));
            }
            if (status == null) {
                commits.abortCommitPublicationBatch();
                return finishDirectContext(rejected(CoreResultCode.INVALID_MESSAGE));
            }
            if (status == ResponseStatus.APPLIED) {
                if (runtimeState.asynchronousCommands()) {
                    try {
                        if (!triggers.collectClosingTriggerIds().isEmpty()) commits.requestCommitPublication();
                    }
                    catch (ArithmeticException failure) {
                        deferFinalizationRejection(CoreResultCode.ARITHMETIC_OVERFLOW);
                        return null;
                    }
                } else triggers.cancelTriggersForClosedPositions();
                if (pendingMatching.size() + admissions.queuedMatching.size() > pendingMatching.capacity()) {
                    admissions.queuedMatching.clear();
                    if (deferFinalizationRejection(CoreResultCode.MATCHING_BACKPRESSURE)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectContext(rejected(CoreResultCode.MATCHING_BACKPRESSURE));
                }
            }
            if (status == ResponseStatus.APPLIED) {
                List<Long> changedOrderIds = resultBuilder.commandChangedOrderIds == null ? List.of() : resultBuilder.commandChangedOrderIds;
                try {
                    if (runtimeState.asynchronousCommands())
                        RuntimeCommandProcessor.validateOrderStampInputs(clusterTimestamp, clusterPosition, changedOrderIds);
                    else stampOrderChangesRuntime(clusterTimestamp, clusterPosition, changedOrderIds);
                } catch (IllegalStateException exception) {
                    if (deferFinalizationRejection(CoreResultCode.INVALID_COMMAND)) return null;
                    rollbackCommandState(runtimeCommandCheckpoint, positionIdentityCheckpoint,
                            Math.incrementExact(appliedCommandCount));
                    return finishDirectContext(rejected(CoreResultCode.INVALID_COMMAND));
                }
            }
            // Direct control commands finish on the Owner before another command can reuse
            // this workspace. Keep changed IDs as reusable primitive views; matching commands
            // still take immutable snapshots because their Lane continuation may suspend.
            resultBuilder.materializeDirectChangeAccumulators();
            if (status == ResponseStatus.APPLIED && !resultBuilder.commandChangedUserIds.isEmpty()) {
                // 业务任务已经结束。把发布序号交给实际参与 Lane，不接管全部账户。
                if (runtimeState.asynchronousCommands()) {
                    runtimeState.releaseCompletedSequentialLaneStage();
                    directCommand.commitEvent = runtimeState.dispatchLaneMutation(
                            nextAppliedCommandCount, resultBuilder.commandChangedUserIds,
                            resultBuilder.commandChangedOrderIds, triggers.closingTriggerIds(),
                            clusterTimestamp, clusterPosition);
                } else {
                    runtimeState.stageLaneMutation(nextAppliedCommandCount, resultBuilder.commandChangedUserIds);
                }
            }
            directCommand.finalizationPrepared = true;
        }
        if (directCommand.commitEvent != null) {
            if (!runtimeState.laneCommitComplete(directCommand.commitEvent)) {
                directCommand.status = status;
                directCommand.resultCode = resultCode;
                return null;
            }
            runtimeState.releaseLaneCommit(directCommand.commitEvent);
            directCommand.commitEvent = null;
        }
        directCommand.finalizationPrepared = false;
        commits.completeCommitPublicationBatch();
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
        admissions.appendQueuedMatching(clusterTimestamp, clusterPosition);
        lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (isFundsIdempotencyCommand(message.header().messageType())
                && status == ResponseStatus.APPLIED) {
            terminalRetention.retainFundsCommand(message.header().commandId(), fingerprint);
        }
        long stateHash = stateHash(businessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = resultBuilder.commandResultData();
        resultLedger.storeOwnedResult(message.header().commandId(), fingerprint, status, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, responseData);
        CoreResponse response = CoreResponse.owned(status, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData);
        return finishDirectContext(response);
    }

    private CoreResponse finishDirectContext(CoreResponse response) {
        directCommand.clearDirect();
        return finishFactContext(response);
    }

    /** Reuse the direct command continuation for failures discovered during terminal preparation. */
    private boolean deferFinalizationRejection(CoreResultCode code) {
        if (!runtimeState.asynchronousCommands()) return false;
        if (!directCommand.directActive)
            throw new IllegalStateException("asynchronous finalization has no command context");
        directCommand.status = ResponseStatus.REJECTED;
        directCommand.resultCode = code;
        return true;
    }

    /** 控制命令复用同一种命令槽；跨回调的执行、结果、回滚和提交状态均归属该命令。 */
    final CommandSlot directCommand = new CommandSlot();

    @Override public void deferControl(java.util.function.BooleanSupplier continuation) {
        directCommand.deferControl(continuation);
    }

    boolean hasPendingDirectCommand() { return directCommand.directActive; }

    CoreResponse pollDirectCommand() {
        assertOwner();
        var pending = directCommand;
        if (!pending.directActive) throw new IllegalStateException("no pending direct command");
        if (directCommand.status == null) {
            try {
                if (!directCommand.controlWork.getAsBoolean()) return null;
                directCommand.status = ResponseStatus.APPLIED;
                directCommand.resultCode = CoreResultCode.NONE;
            } catch (CoreStateRejectedException failure) {
                directCommand.status = ResponseStatus.REJECTED;
                directCommand.resultCode = CoreResultCode.fromRejectionCode(failure.code());
            } catch (ArithmeticException failure) {
                directCommand.status = ResponseStatus.REJECTED;
                directCommand.resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
            } catch (IllegalArgumentException failure) {
                directCommand.status = ResponseStatus.REJECTED;
                directCommand.resultCode = CoreResultCode.INVALID_COMMAND;
            }
            directCommand.controlWork = null;
        }
        if (directCommand.status != ResponseStatus.APPLIED
                && (commits.commitPublicationDirty || runtimeState.revision() != pending.beforeRevision
                    || runtimeState.hasUncommittedCommandChanges())) {
            if (directCommand.controlWork == null) {
                commits.abortCommitPublicationBatch();
                directCommand.controlWork = runtimeState.beginCommandRollback(pending.checkpoint,
                        Math.incrementExact(appliedCommandCount));
            }
            if (!directCommand.controlWork.getAsBoolean()) return null;
            identities.rollbackPositionKeys(pending.identityCheckpoint);
            directCommand.controlWork = null;
        }
        CoreResponse result = finishDirectCommand(pending.command(), pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), pending.sourceKey,
                pending.fingerprint(), pending.beforeProjection(),
                pending.beforeRevision, pending.checkpoint, pending.identityCheckpoint,
                directCommand.status, directCommand.resultCode);
        return result;
    }

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

    CoreResponse finishFactContext(CoreResponse response) {
        clearFactContext();
        return response;
    }

    void activateFactContext(CoreMessage command, CommandFingerprint fingerprint) {
        if (command == null || fingerprint == null) {
            throw new IllegalArgumentException("complete commit context is required before mutation");
        }
        runtimeProjectionJournal.assertHealthy();
        factContextActive = true;
    }

    void clearFactContext() {
        factContextActive = false;
    }

    CoreResponse admissionRejected(CoreResultCode resultCode) {
        currentClusterTimestamp = admissionPreviousClusterTimestamp;
        currentClusterPosition = admissionPreviousClusterPosition;
        return rejected(resultCode);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CommandFingerprint fingerprint, CoreResultCode resultCode,
                                                CommandSlot deferredPending) {
        return deferredPending == null ? recordRejectedMatching(message, sourceKey, fingerprint, resultCode)
                : admissions.recordRejectedDeferredMatching(deferredPending, resultCode);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CommandFingerprint fingerprint, CoreResultCode resultCode) {
        if (!pendingMatching.isEmpty()) {
            long sequence = Math.incrementExact(appliedCommandCount);
            CommandSlot pending = admissions.newPendingMatching(sequence,
                    MatchingCommandAdmission.matchingOperation(message.header().messageType()), message, fingerprint);
            putPendingMatching(pending);
            laneCommandContexts.required(sequence).rejectMatching(resultCode);
            pendingMatching.completeSubmission(sequence);
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
        resultLedger.storeOwnedResult(message.header().commandId(), fingerprint,
                ResponseStatus.REJECTED, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                TradingCoreRuntime.EMPTY_RESPONSE_DATA);
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, requiredExportSequence, stateHash, EMPTY_RESPONSE_DATA);
    }

    CommandSlot removePendingMatching(long sequence) {
        admissions.pendingLifecycleScopes.remove(sequence);
        int shard = pendingMatching.submissionShard(sequence);
        boolean releasesSubmissionHead = shard >= 0 && pendingMatching.isSubmissionHead(sequence, shard);
        CommandSlot removed = pendingMatching.remove(sequence);
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

    void putPendingMatching(CommandSlot pending) {
        pending.clusterIndependent = clusterPipelineAdmission;
        if (pendingMatching.size() >= pendingMatching.capacity()) {
            throw new IllegalStateException("matching pending capacity is exhausted");
        }
        try {
            if (clusterPipelineAdmission && preparedIngressRoute != null) {
                pending.partitionLaneMask = preparedIngressRoute.userLaneBit();
                // The route was already resolved at ingress. Reuse its shard instead
                // of probing the symbol registry again while registering the ring slot.
                pending.cachedMatcherShard(preparedIngressRoute.matcherShard());
            } else {
                pending.partitionLaneMask = 0;
            }
            pendingMatching.put(pending);
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
            throw failure;
        }
    }

    int matcherShard(CommandSlot pending) {
        int cached = pending.cachedMatcherShard();
        if (cached >= 0) return cached;
        String symbol = pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT
                ? admissions.pendingLifecycleSymbol(pending)
                : admissions.matchingSymbol(pending.command(), pending.operation(), pending.decodedCommand());
        int shard = symbol == null || symbol.isBlank() ? 0 : matchingAdapter.matcherShardId(symbol);
        pending.cachedMatcherShard(shard);
        return shard;
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

    List<CoreOrderState> batchCancellationOrders(CommandSlot pending) {
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

    void submitMatching(CommandSlot pending) {
        if (pending.crossShardCancellationStarted || matchingSubmissionDeferred(pending.sequence())) return;
        // Standalone callers execute the Account Lane admission inline.  Collect that result
        // before routing to the synchronous Matcher so a rejected PLACE never mutates the
        // order book after its Owner-side rejection has already been recorded.  Cluster ingress
        // remains fully asynchronous and takes the Matcher admission fence below instead.
        if (pending.placeAdmission() != null && !runtimeState.asynchronousCommands()) {
            if (!collectPlaceAdmissionIfReady(pending)
                    || hasPendingMatchingRejection(pending.sequence())) return;
        }
        if (pending.orderBatch != null) {
            batches.submitOrderBatchMatching(pending);
            if (pending.isMatchingSubmitted()) matchingSubmissionCompleted(pending);
            return;
        }
        if (pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
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
        var command = prepareMatchingCommand(pending);
        com.surprising.aeron.service.state.MatcherSettlementEvent direct = null;
        // The direct event is preconstructed with a detached taker when Lane admission is still
        // in flight.  The event is queued after the admission on that Lane and binds the
        // authoritative OrderRuntime just before applying the Matcher result.
        if (pending.operation() == CommandSlot.Operation.PLACE && runtimeState.asynchronousCommands()) {
            OrderRuntime directTaker = pending.placeAdmission() == null
                    ? runtimeOrder(pending.decodedCommand().placeOrder().orderId()) : null;
            if (pending.placeAdmission() != null) {
                direct = runtimeState.prepareDirectMatcherSettlement(
                        pending.sequence(), directSettlementLaneMask(), pending.placeAdmission(),
                        pending.command().header().commandId(), matcherShard(pending), identities,
                        pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                        pending.preMatchingCancellationOrderIds(), null);
            } else if (directTaker != null) {
                direct = runtimeState.prepareDirectMatcherSettlement(pending.sequence(),
                        directSettlementLaneMask(), directTaker, null, 1,
                        pending.command().header().commandId(), matcherShard(pending), identities,
                        pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                        pending.preMatchingCancellationOrderIds(), null);
            }
            pending.settlement(direct, direct.plan(), System.nanoTime());
            if (realtimeCapture != null)
                pending.realtimeTakerOrder = directTaker;
        }
        if (pending.operation() == CommandSlot.Operation.REPLACE || pending.operation() == CommandSlot.Operation.AMEND) {
            var admission = requireMatchingAdmission(pending);
            requireUnchangedAdmissionState(admission);
            direct = runtimeState.prepareDirectReplacement(pending.sequence(), pending.sequence(),
                    directSettlementLaneMask(),
                    admission, pending.command().header().commandId(), matcherShard(pending), identities,
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds());
            pending.settlement(direct, direct.plan(), System.nanoTime());
            if (realtimeCapture != null) pending.realtimeTakerOrder = direct.admittedOrder();
        }
        if (pending.operation() == CommandSlot.Operation.TRIGGER && runtimeState.asynchronousCommands()) {
            long[] execute = pending.decodedCommand().trigger();
            var trigger = java.util.Objects.requireNonNull(runtimeState.triggerOrder(execute[0]),
                    "admitted trigger is missing");
            OrderRuntime triggerOrder = runtimeOrder(pending.admittedMatchingOrder().orderId());
            direct = runtimeState.prepareDirectMatcherSettlement(pending.sequence(),
                    directSettlementLaneMask(),
                    triggerOrder, null, 1, pending.command().header().commandId(), matcherShard(pending),
                    identities, pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(),
                    pending.preMatchingCancellationOrderIds(), null);
            direct.triggerCompletion(trigger, execute[3]);
            pending.settlement(direct, direct.plan(), System.nanoTime());
            if (realtimeCapture != null) pending.realtimeTakerOrder = triggerOrder;
        }
        if (pending.operation() == CommandSlot.Operation.CANCEL && runtimeState.asynchronousCommands()) {
            OrderRuntime canceledOrder = runtimeOrder(pending.decodedCommand().cancelOrder().orderId());
            direct = runtimeState.prepareDirectCancellation(pending.sequence(), canceledOrder,
                    pending.command().header().commandId(), matcherShard(pending), identities,
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
            pending.settlement(direct, direct.plan(), System.nanoTime());
        }
        java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> matcherSubmission =
                command;
        if (direct != null && pending.placeAdmission() != null) {
            var directSettlement = direct;
            var originalSubmission = matcherSubmission;
            matcherSubmission = () -> {
                // Keep the optimistic command token on the Matcher, but fence the actual
                // exchange-core mutation behind the Account Lane's reservation decision.
                directSettlement.awaitAdmission();
                RuntimeException rejection = directSettlement.admissionRejection();
                if (rejection != null) {
                    var place = pending.decodedCommand().placeOrder();
                    return matchingAdapter.rejectedPlaceWithEvidence(
                            matcherShard(pending), pending.sequence(),
                            pending.command().header().commandId(), place.orderId(),
                            place.instrumentChangeId(), pending.command().header().submittedAtEpochMillis(),
                            directSettlement.admissionResultCode());
                }
                return originalSubmission.get();
            };
        }
        matcherPipeline.submit(matcherShard(pending), pending.sequence(), matcherSubmission, direct);
        pending.matchingSubmitted();
        // Keep the per-shard submission head occupied until the Account Lane admission is
        // resolved.  This preserves command order for a following cancel/control command while
        // allowing the Matcher itself to wait on the admission fence off the Owner thread.
        if (pending.placeAdmission() == null) matchingSubmissionCompleted(pending);
        // The event is already safe to queue: its commit fence is known and the Lane worker
        // will hold it until the matcher publishes the immutable result.  This removes the
        // owner completion drain from the matcher->Lane handoff; the owner still collects the
        // completed event later for ordered funds/index/result commit.
        if (direct != null) predispatchDirectSettlement(pending, direct);
    }

    /**
     * Until the Matcher-owned Lane mailbox is installed, direct events are
     * pre-enqueued to every Lane. The ingress dependency itself remains the
     * user's single Lane; the broad event route prevents a fill discovered by
     * Matcher from escaping the preallocated event. This compatibility bridge
     * is removed together with the Owner dispatch call in the next phase.
     */
    private long directSettlementLaneMask() {
        return commits.validAccountLaneMask();
    }

    /**
     * Enqueue a direct matcher settlement as soon as its partition dependency allows it.
     * The SPSC producer remains the owner; the matcher only publishes the payload readiness
     * bit.  If an earlier partition dependency is still outstanding, the ordered dispatcher
     * retries the same event without requiring another matcher result copy.
     */
    boolean predispatchDirectSettlement(CommandSlot pending,
                                     com.surprising.aeron.service.state.MatcherSettlementEvent event) {
        if (pending == null || event == null || !event.direct() || event.dispatched()) return false;
        // A direct event prepared outside a cluster callback must wait for the real
        // publication fence; never freeze a placeholder fence into the Lane command.
        if (!pending.commitFenceEstablished()) return false;
        int shard = pendingSubmissionShard(pending);
        if (pendingMatching.partitionDispatchHead(shard) != pending) {
            return false;
        }
        pending.establishCommitFence(pending.commitFenceTimestamp(), pending.commitFenceClusterPosition());
        runtimeState.dispatchDirectMatcherSettlement(event);
        pendingMatching.completePartitionDispatch(pending.sequence(), shard);
        pendingMatching.progressChanged();
        pending.countPipelinedSettlement();
        commits.dispatchedSettlementInFlight++;
        commits.dispatchedSettlementHighWaterMark = Math.max(
                commits.dispatchedSettlementHighWaterMark, commits.dispatchedSettlementInFlight);
        return true;
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

    /**
     * Advances only pipelined batch admissions.  A normal PLACE is submitted to the Matcher as
     * soon as its immutable admission input is prepared; its Lane completion is resolved by the
     * ordered commit head and never drives an Owner-side submission poll.
     */
    void progressPlaceBatchAdmissions() {
        drainPlaceAdmissionNotifications();
        if (placeAdmissionReadyShardMask == 0 && hasDeferredMatchingSubmission()) {
            CommandSlot head = pendingMatching.get(pendingMatching.firstSequence());
            placeAdmissionReadyShardMask |= 1L << pendingSubmissionShard(head);
        }
        long readyShards = placeAdmissionReadyShardMask;
        while (readyShards != 0) {
            int shard = Long.numberOfTrailingZeros(readyShards);
            long shardBit = 1L << shard;
            readyShards &= ~shardBit;
            while ((placeAdmissionReadyShardMask & shardBit) != 0) {
                CommandSlot pending = pendingMatching.submissionHead(shard);
                if (pending == null) {
                    placeAdmissionReadyShardMask &= ~shardBit;
                    break;
                }
                var admission = pending.placeAdmission();
                OrderBatchPending orderBatch = pending.orderBatch;
                PlaceBatchAdmissionEvent batchAdmission = orderBatch == null
                        ? null : orderBatch.placeBatchAdmissionEvent;
                if (ownerHasPendingMatchingRejection(pending)) {
                    // A synchronous Lane admission may have rejected the PLACE before a
                    // submission head was registered. Its ordered rejection is consumed by the
                    // commit coordinator; never try to rebuild a Matcher order for it.
                    placeAdmissionReadyShardMask &= ~shardBit;
                    break;
                }
                if (admission == null && batchAdmission == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchKind.CANCEL && orderBatch.activated()) {
                        submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    // A control or matching command may have been held behind an earlier
                    // submission head.  Sequential order batches own their own item cursor and
                    // are resumed by OrderBatchExecutor; submitting them here would route the
                    // same matcher token twice.
                    if (orderBatch == null && !pending.isMatchingSubmitted() && !pending.deferredMatching()) {
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
                    identities.recordLaneClientAllocations(batchAdmission.takeIdentityAllocations());
                    RuntimeException rejection = batchAdmission.rejection();
                    if (rejection != null) {
                        runtimeState.discardPlaceBatchAdmission(batchAdmission);
                        runtimeState.releasePlaceBatchAdmission(batchAdmission);
                        orderBatch.placeBatchAdmissionEvent = null;
                        batches.unregisterPipelinedBatchSymbols(orderBatch);
                        orderBatch.rollbackPreparedClientKeys(identities);
                        orderBatch.pipelined = false;
                        orderBatch.sequentialAdmission = true;
                        // The Lane already checked and rolled back the only item. There is no
                        // partial batch to re-evaluate, so retain its rejection at ordered commit.
                        if (orderBatch.items.size() == 1
                                && (rejection instanceof CoreStateRejectedException
                                || rejection instanceof ArithmeticException
                                || rejection instanceof IllegalArgumentException)) {
                            CoreResultCode code = rejection instanceof CoreStateRejectedException stateRejection
                                    ? CoreResultCode.fromRejectionCode(stateRejection.code())
                                    : rejection instanceof ArithmeticException
                                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
                            batches.appendOrderBatchResult(orderBatch, orderBatch.items.getFirst(),
                                    ResponseStatus.REJECTED, code);
                            orderBatch.nextIndex = 1;
                        }
                        if (orderBatch.admissionOrderIndex != null)
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
                            orderBatch.activated(false);
                            submitDeferredMatchingAfterBatch();
                        }
                        break;
                    }
                    if (!orderBatch.admissionCollected()) {
                        runtimeState.stagePlaceBatchAdmission(batchAdmission);
                        orderBatch.admissionCollected(true);
                    }
                    if (!pending.isMatchingSubmitted()) {
                        batches.submitPipelinedPlaceBatch(pending, orderBatch);
                        pending.matchingSubmitted();
                        matchingSubmissionCompleted(pending);
                    }
                    continue;
                }
                // A normal PLACE that was held behind an earlier submission can now enter the
                // Matcher even while its Account Lane admission is still running.  This is a
                // submission-head transition, not an admission poll.
                if (admission != null && !pending.isMatchingSubmitted()) {
                    submitMatching(pending);
                    if (pending.isMatchingSubmitted()) continue;
                }
                placeAdmissionReadyShardMask &= ~shardBit;
                break;
            }
        }
    }

    /** Drain completion cursors only to wake the batch dispatcher or ordered commit head. */
    void drainPlaceAdmissionNotifications() {
        long readyLaneMask = runtimeState.takePlaceAdmissionReadyLaneMask();
        while (readyLaneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLaneMask);
            readyLaneMask &= readyLaneMask - 1;
            long sequence;
            while ((sequence = runtimeState.pollPlaceAdmissionReady(laneId)) != 0) {
                pendingMatching.progressChanged();
                CommandSlot pending = pendingMatching.get(sequence);
                OrderBatchPending batch = pending == null ? null : batches.batch(sequence);
                if (pending == null) {
                    continue;
                }
                if (batch == null || batch.placeBatchAdmissionEvent == null) {
                    // Ordinary PLACE completion is consumed from this notification.  Matcher
                    // submission already happened; this only publishes the admission outcome
                    // to the ordered head and to its queued direct settlement.
                    collectPlaceAdmissionIfReady(pending);
                    continue;
                }
                if (batch == null || batch.placeBatchAdmissionEvent == null
                        || pending.isMatchingSubmitted()
                        || batch.finishing() || batch.nextIndex >= batch.items.size()) {
                    // Ordinary PLACE admissions are already submitted to Matcher.  The queue
                    // entry is only a wake-up cursor; ordered commit reads event.complete().
                    continue;
                }
                placeAdmissionReadyShardMask |= 1L << pendingSubmissionShard(pending);
            }
        }
    }

    boolean matchingSubmissionDeferred(long sequence) {
        CommandSlot pending = pendingMatching.get(sequence);
        if (pending != null) {
            int shardId = pendingSubmissionShard(pending);
            if (!pendingMatching.isSubmissionHead(sequence, shardId)) return true;
        }
        if (pending != null && pending.clusterIndependent || !batches.hasPendingBatches()) return false;
        return sequence > batches.firstBatch().sequence;
    }

    void matchingSubmissionCompleted(CommandSlot pending) {
        pendingMatching.progressChanged();
        int shard = pendingSubmissionShard(pending);
        pendingMatching.completeSubmission(pending.sequence());
        // Wake the next command on this shard as soon as the submission head is released.  A
        // normal PLACE may have been followed by a cancel/control command whose submission was
        // deferred while the PLACE admission was in flight; without this bit the next command
        // would remain parked until another Lane notification arrived.
        if (pending.orderBatch != null || pendingMatching.submissionHead(shard) != null
                || !admissions.deferredMatching.isEmpty()) {
            placeAdmissionReadyShardMask |= 1L << shard;
        }
    }

    int pendingSubmissionShard(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        return batch == null ? matcherShard(pending) : batches.orderBatchMatcherShard(batch);
    }

    void submitDeferredMatchingAfterBatch() {
        while (!pendingMatching.isEmpty()) {
            // Both orderings append increasing Core sequences. Only their heads can advance.
            OrderBatchPending batch = batches.firstBatch();
            Long deferredSequence = admissions.deferredMatching.isEmpty()
                    ? null : admissions.deferredMatching.keySet().iterator().next();
            CommandSlot pending;
            if (deferredSequence != null && (batch == null || deferredSequence < batch.sequence)) {
                pending = pendingMatching.get(deferredSequence);
                batch = null;
            } else if (batch != null && !batch.activated()) {
                pending = pendingMatching.get(batch.sequence);
            } else {
                return;
            }
            if (batch != null && !batch.activated()) {
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
        if (result == null || result.nativeCommand().coreSequence() != sequence) {
            throw new IllegalStateException("synchronous matcher returned an invalid result");
        }
        CommandSlot context = laneCommandContexts.required(sequence);
        context.publishMatchingCompletion(result);
        pendingMatching.progressChanged();
    }

    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult>
            prepareMatchingCommand(
            CommandSlot pending) {
        if (MATCHING_PHASE_METRICS_ENABLED) {
            matchingSubmitNanos.put(pending.sequence(), System.nanoTime());
        }
        try {
            List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellations =
                    preMatchingCancellationOrders(pending);
            long userId = pending.command().header().userId();
            if (pending.operation() == CommandSlot.Operation.PLACE && preMatchingCancellations.isEmpty()) {
                var command = pending.decodedCommand().placeOrder();
                var admittedOrder = pending.admittedPlaceOrder();
                int shard = matcherShard(pending);
                if (admittedOrder != null) {
                    return () -> matchingAdapter.placeWithEvidence(shard, pending.sequence(),
                            pending.command().header().commandId(), command.instrumentChangeId(),
                            pending.command().header().submittedAtEpochMillis(), userId, admittedOrder);
                }
                // Ordinary PLACE admissions are dispatched to the Account Lane and the Matcher
                // concurrently.  Until the Lane publishes its mutable runtime object, use the
                // immutable resolved admission input captured by the event.
                var order = pending.placeAdmission() == null
                        ? matchingOrder(command.orderId()) : pending.placeAdmission().matchingOrder();
                return () -> matchingAdapter.placeWithEvidence(shard, pending.sequence(),
                        pending.command().header().commandId(), command.instrumentChangeId(),
                        pending.command().header().submittedAtEpochMillis(), userId, order);
            }
            if (pending.operation() == CommandSlot.Operation.CANCEL && preMatchingCancellations.isEmpty()) {
                var command = pending.decodedCommand().cancelOrder();
                var order = runtimeState.order(command.orderId());
                if (order != null) {
                    String symbol = identities.symbol(order.symbolId());
                    long instrumentChangeId = order.instrumentChangeId();
                    int shard = matcherShard(pending);
                    return () -> matchingAdapter.cancelWithEvidence(shard, pending.sequence(),
                            pending.command().header().commandId(), command.orderId(), instrumentChangeId,
                            pending.command().header().submittedAtEpochMillis(), userId, symbol);
                }
            }
            MatchingSubmission matching = switch (pending.operation()) {
                case PLACE -> {
                    var command = pending.decodedCommand().placeOrder();
                    var admittedOrder = pending.admittedPlaceOrder();
                    if (admittedOrder != null) {
                        yield new MatchingSubmission(command.orderId(), command.instrumentChangeId(),
                                () -> matchingAdapter.place(userId, admittedOrder));
                    }
                    var order = pending.placeAdmission() == null
                            ? matchingOrder(command.orderId()) : pending.placeAdmission().matchingOrder();
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
                    if (liquidation == null || !com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService
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

    boolean matchingControlCommand(CommandSlot pending) {
        return pending.operation() == CommandSlot.Operation.LIQUIDATION
                || pending.operation() == CommandSlot.Operation.LIQUIDATION_BATCH
                || pending.operation() == CommandSlot.Operation.SETTLEMENT;
    }

    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult>
            matchingEvidenceCommand(
                    CommandSlot pending, long orderId, long instrumentChangeId, boolean control,
                    java.util.function.Supplier<com.surprising.aeron.service.matching.CoreMatchingResult> command) {
        long coreSequence = pending.sequence();
        UUID commandId = pending.command().header().commandId();
        long aeronTimestamp = pending.command().header().submittedAtEpochMillis();
        int shard = control ? -1 : matcherShard(pending);
        return control
                ? () -> matchingAdapter.executeControlWithEvidenceSync(
                        coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command)
                : () -> matchingAdapter.executeShardWithEvidenceSync(
                        shard, coreSequence, commandId, orderId, instrumentChangeId, aeronTimestamp, command);
    }

    List<DeterministicExchangeCoreAdapter.CancellationOrder> preMatchingCancellationOrders(
            CommandSlot pending) {
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
                                             CommandSlot.Operation operation,
                                             OrderRuntime order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (runtimeState.instrument(runtimeOrderSymbol(order)) == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        }
        return operation == CommandSlot.Operation.REPLACE
                ? decodedCommand.replaceOrder().replacement()
                : replacementForAmend(decodedCommand.amendOrder(), order);
    }

    PlaceOrderCommand replacementFor(CommandSlot pending, OrderRuntime order) {
        if (pending.operation() == CommandSlot.Operation.REPLACE) {
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
        if (!pendingMatching.contains(sequence)) return null;
        CommandSlot pending = pendingMatching.get(sequence);
        if (pending == null) return null;
        CommandSlot context = laneCommandContexts.required(sequence);
        if (context.matchingResult() != null) return context.matchingResult();
        var direct = pending.settlementEvent();
        if (direct == null && pending.orderBatch != null)
            direct = pending.orderBatch.itemSettlementEvent != null
                    ? pending.orderBatch.itemSettlementEvent : pending.orderBatch.settlementEvent;
        if (direct != null && direct.direct()) return direct.ready() ? direct.firstDirectResult() : null;
        return context.takeMatchingCompletion();
    }

    boolean establishMatchingCommitFence(long sequence, long clusterTimestamp, long clusterPosition) {
        CommandSlot pending = pendingMatching.get(sequence);
        if (pending == null) return false;
        pending.establishCommitFence(clusterTimestamp, clusterPosition);
        return true;
    }

    boolean placeAdmissionOutstanding(CommandSlot pending) {
        OrderBatchPending batch = pending.orderBatch;
        return batch != null && batch.placeBatchAdmissionEvent != null && !pending.isMatchingSubmitted();
    }

    /** Consume a completed ordinary admission notification without gating Matcher submission. */
    boolean collectPlaceAdmissionIfReady(CommandSlot pending) {
        if (pending == null || pending.placeAdmission() == null) return true;
        var admission = pending.placeAdmission();
        if (!admission.complete()) return false;
        identities.recordLaneClientAllocations(admission.takeIdentityAllocations());
        RuntimeException rejection = admission.rejection();
        var direct = pending.settlementEvent();
        if (direct != null && direct.direct()) direct.admissionResult(rejection);
        if (rejection != null) {
            CoreResultCode resultCode = rejection instanceof CoreStateRejectedException rejected
                    ? CoreResultCode.fromRejectionCode(rejected.code())
                    : rejection instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
            pending.rejectMatching(resultCode);
            completePlaceAdmissionSubmission(pending);
            runtimeState.releasePlaceAdmission(pending.takePlaceAdmission());
            return true;
        }
        ResolvedPlaceOrder admitted = runtimeState.collectPlaceAdmission(admission);
        pending.admissionCompleted(admitted);
        // Realtime publication still needs the authoritative mutable taker.  The direct event
        // was prepared before admission and therefore could not capture it at submission time.
        if (realtimeCapture != null && admitted != null)
            pending.realtimeTakerOrder = runtimeOrder(admitted.orderId());
        completePlaceAdmissionSubmission(pending);
        runtimeState.releasePlaceAdmission(pending.takePlaceAdmission());
        return true;
    }

    private void completePlaceAdmissionSubmission(CommandSlot pending) {
        if (pending != null && pending.isMatchingSubmitted()
                && pendingMatching.submissionShard(pending.sequence()) >= 0)
            matchingSubmissionCompleted(pending);
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
        CommandSlot pending = pendingMatching.findByCommandId(commandId);
        return pending == null ? 0 : pending.sequence();
    }

    /**
     * Fast owner-side gate for the completion pump.  The ordered commit loop is called for
     * every command prefix, including prefixes that are still waiting for an external matcher or
     * Lane notification.  In that state there is no queue to drain; checking the same producer
     * cursors once avoids entering the full drain routine and its shard/notification probes.
     *
     * <p>This is only a hint for the owner thread.  Producers publish to the SPSC queues before
     * setting their ready bits, so a false result can at worst defer work to the next pump; it
     * cannot make a completion invisible or bypass the ordered commit fence.</p>
     */
    boolean hasMatchingDrainWork() {
        return crossShardCancellations.hasPending()
                || placeAdmissionReadyShardMask != 0
                || runtimeState.hasMatchingNotifications()
                || matcherPipeline.hasMatchingCompletions()
                || hasLocalMatchingWork();
    }

    void drainMatchingCompletions() {
        if (crossShardCancellations.hasPending()) crossShardCancellations.poll();
        if (placeAdmissionReadyShardMask != 0 || runtimeState.hasPlaceAdmissionNotifications()
                || hasDeferredMatchingSubmission())
            progressPlaceBatchAdmissions();
        if (matcherPipeline.hasMatchingCompletions()) {
            matcherPipeline.drainMatchingCompletions();
            pendingMatching.progressChanged();
        }
        if (runtimeState.hasSettlementNotifications()) commits.drainMatcherSettlementCompletions();
    }

    long matchingProgressSequence() {
        return pendingMatching.dispatchRevision();
    }

    /** 尚未消费的本地准入/队首结果必须继续推进，不能只等待新的跨线程通知。 */
    boolean hasLocalMatchingWork() {
        if (placeAdmissionReadyShardMask != 0) return true;
        CommandSlot head = pendingMatching.get(pendingMatching.firstSequence());
        OrderBatchPending batch = head == null ? null : head.orderBatch;
        // Handoff and the final metadata commit have no matcher-settlement cursor.
        // Keep polling these bounded Owner continuations even after consuming the last notification.
        return batch != null && (!batch.activated() || batch.laneCommitEvent != null || batch.itemAdmission != null)
                || head != null && !head.isMatchingSubmitted() && head.placeAdmission() == null
                && !head.deferredMatching()
                || head != null && commits.matchingCommitReady(head);
    }

    /** A deferred command without a Lane admission can resume once the ordered head advances. */
    private boolean hasDeferredMatchingSubmission() {
        CommandSlot head = pendingMatching.get(pendingMatching.firstSequence());
        return head != null && !ownerHasPendingMatchingRejection(head)
                && !head.isMatchingSubmitted() && !head.deferredMatching()
                && head.orderBatch == null;
    }

    private boolean ownerHasPendingMatchingRejection(CommandSlot pending) {
        return pending != null && laneCommandContexts.claimed(pending.sequence())
                && laneCommandContexts.required(pending.sequence()).hasMatchingRejection();
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

    Map<Long, CommandSlot> pendingMatching() {
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

    CommandSlot pendingMatching(long sequence) {
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
            CommandSlot pending,
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
        if (directCommand.directActive || !pendingMatching.isEmpty() || !bookQueries.queryIds.isEmpty()) {
            throw new IllegalStateException("unfinished business work outside cluster log callback");
        }
    }

    void assertHealthy() {
        if (fatalFailure != null) throw fatalFailure;
        if (commitPublicationFailure != null) throw commitPublicationFailure;
        if (runtimeState != null) runtimeState.assertAccountLanesHealthy();
        runtimeProjectionJournal.assertHealthy();
        exportState.assertHealthy();
        RuntimeException auditFailure = snapshots.snapshotAuditFailure.get();
        if (auditFailure != null) throw auditFailure;
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
        return materializedTradingState();
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
        return materializedTradingState();
    }

    /**
     * Materialize the immutable read model once per committed runtime version.  This keeps the
     * expensive sorting and state-record construction at an actual read/snapshot boundary while
     * avoiding duplicate materialization when one boundary performs several reads.
     */
    private TradingCoreState materializedTradingState() {
        runtimeState.requireSnapshotFenceReady();
        long revision = runtimeState.revision();
        long marketRevision = runtimeState.marketRevision();
        long sequence = runtimeProjectionJournal.publishedSequence();
        if (materializedStateCache != null
                && materializedStateCacheRevision == revision
                && materializedStateCacheMarketRevision == marketRevision
                && materializedStateCacheSequence == sequence
                && materializedStateCacheBusinessHash == cachedBusinessStateHash) {
            return materializedStateCache;
        }
        TradingCoreState materialized = com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(
                runtimeState, identities);
        materializedStateCache = materialized;
        materializedStateCacheRevision = revision;
        materializedStateCacheMarketRevision = marketRevision;
        materializedStateCacheSequence = sequence;
        materializedStateCacheBusinessHash = cachedBusinessStateHash;
        return materialized;
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

    boolean snapshotHasPendingCommands() {
        return factContextActive || directCommand.directActive || laneCommandContexts.inFlight() != 0;
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
        return lastSourceSequences.digest();
    }

    Map<UUID, StoredResult> commandResults() {
        return resultLedger.entries();
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences.snapshot());
    }

    ResponseStatus applyCommand(CoreMessage message, long clusterTimestamp) {
        if (directCommands.dispatch(message, clusterTimestamp)) return ResponseStatus.APPLIED;
        switch (message.header().messageType()) {
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payloadUnsafe()));
            case VERIFY_STATE_HASH -> {
            }
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                    PLACE_ORDER_BATCH, CANCEL_ORDER_BATCH, AMEND_ORDER_BATCH,
                    EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                    throw new IllegalStateException("matching command must use async continuation");
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    @Override public void cancelAllOcoSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger) {
        long cursor = Long.MAX_VALUE;
        while (true) {
            TriggerCommandContext.OcoCancellationPage page = triggers.cancelOcoSiblings(
                    trigger, cursor, DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
            if (page.complete()) return;
            if (page.workUnits() == 0) throw new IllegalStateException("OCO cancellation made no progress");
            cursor = page.nextCursor();
        }
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
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence,
            long timestamp, long position) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState, identities, userId,
                command, currentClusterTimestamp);
        int assetId = identities.assetId(resolved.reservationAsset());
        return runtimeState.dispatchPlaceAdmission(coreSequence, userId, resolved, commandId,
                openInterestIndex.openInterestSteps(resolved.symbol()),
                runtimeState.treasury().lifecycleSettlement(resolved.symbolId()) != 0,
                runtimeState.treasury().fundingProgress(resolved.symbolId()) != null,
                resolved.symbolId(), assetId, identities, timestamp, position);
    }

    @Override public void reservePlaceOrderRuntime(long userId, PlaceOrderCommand command, UUID commandId,
                                  long pendingCoreSequence) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState,
                identities, userId, command, currentClusterTimestamp);
        var admissionIdentity = com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                runtimeState, identities, userId, resolved);
        long openInterestSteps = openInterestIndex.openInterestSteps(command.symbol());
        int symbolId = resolved.symbolId();
        int assetId = identities.assetId(resolved.reservationAsset());
        runtimeState.executeUserSettlement(userId, () -> {
            var preparedClientKey = identities.prepareClientKeyInCurrentLane(
                    userId, resolved.clientOrderId());
            try {
                long requiredReservation = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationPrepared(
                        runtimeState, userId, resolved, openInterestSteps, activeOrderIndex, admissionIdentity);
                RuntimeCommandProcessor.placeOrderPrepared(runtimeState, userId, resolved,
                        commandId, requiredReservation, preparedClientKey.key(), symbolId, assetId);
                runtimeState.markPendingReservation(userId, resolved.orderId(), pendingCoreSequence);
                return null;
            } catch (RuntimeException | Error failure) {
                if (preparedClientKey.allocated()) {
                    identities.rollbackClientKeyInCurrentLane(
                            userId, resolved.clientOrderId(), preparedClientKey);
                }
                throw failure;
            }
        });
        if (!batches.hasPendingBatches()) deferProvisionalSnapshotProjection();
        else commits.requestCommitPublication();
    }

    ResolvedMatchingAdmission requireMatchingAdmission(CommandSlot pending) {
        ResolvedMatchingAdmission admission = pending.matchingAdmission();
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

    void stampOrderChangesRuntime(long timestamp, long clusterPosition,
                                          Iterable<Long> changedOrderIds) {
        if (RuntimeCommandProcessor.stampChangedOrdersByLane(
                runtimeState, timestamp, clusterPosition,
                changedOrderIds, resultBuilder.commandChangedUserIds)) {
            commits.requestCommitPublication();
        }
    }

    com.surprising.aeron.service.state.RuntimeTreasuryDelta applyMatchesOnAccountLanes(
            CommandSlot pending,
            com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan,
            long coreSequence,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
            CommandSlot laneContext,
            long applyStartNanos) {
        com.surprising.aeron.service.state.MatcherSettlementEvent event = pending.settlementEvent();
        if (event == null) {
            if (realtimeCapture != null && realtimeCapture.active())
                pending.realtimeTakerOrder = runtimeOrder(settlementPlan.takerOrderId());
            event = runtimeState.dispatchMatcherSettlement(
                    coreSequence, laneContext.expectedLaneMask(), coreSequence,
                    pending.commitFenceTimestamp(), pending.commitFenceClusterPosition(), settlementPlan,
                    matchingResult, identities);
            pending.settlement(event, settlementPlan, applyStartNanos);
        }
        if (pending.isDispatchOnly()) return null;
        if (!event.complete()) return null;
        captureRealtimeTrades(pending);
        com.surprising.aeron.service.state.RuntimeTreasuryDelta delta =
                runtimeState.collectMatcherSettlement(
                        event, commandFundsAccumulator, terminalRetention);
        laneContext.completeLanes(event.requiredLaneMask());
        return delta;
    }

    void suspendMatchingCommitContext(CommandSlot pending) {
        if (!commits.commitPublicationDeferred || pending == null
                || pending.settlementEvent() == null && pending.cancelEvent() == null
                && pending.orderBatch == null
                && !commits.controlPending(pending.sequence())) {
            throw new IllegalStateException("matching commit context cannot be suspended");
        }
        // The continuation owns the primitive change buffers until it is restored.  Transfer
        // those buffers to the sequence slot instead of materializing boxed Lists and copying
        // them back when the ordered commit resumes.
        seedChangeAccumulators();
        runtimeState.acceptChangedUserIds(resultBuilder.changedUserIds::add);
        CommandSlot context = pending;
        context.suspendCommitContext(
                resultBuilder, commandFundsAccumulator,
                commits.commitPublicationDirty, commits.commitPublicationProvisionalOnly);
        commits.commitPublicationDeferred = false;
        commits.commitPublicationDirty = false;
        commits.commitPublicationProvisionalOnly = false;
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    void restoreMatchingCommitContext(CommandSlot pending) {
        if (commits.commitPublicationDeferred || factContextActive) {
            throw new IllegalStateException("another owner commit context is active");
        }
        CommandSlot context = pending;
        activateFactContext(pending.command(), pending.fingerprint());
        commits.commitPublicationDeferred = true;
        commits.commitPublicationDirty = context.commitSnapshotDirty();
        commits.commitPublicationProvisionalOnly = context.commitSnapshotProvisionalOnly();
        context.restoreCommitContext(resultBuilder);
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        context.takeCommitFundsTo(commandFundsAccumulator);
        context.clearCommitContext();
        resultBuilder.commandTradeCount = 0;
        resultBuilder.commandLiquidationProgress = null;
        resultBuilder.commandLiquidationBatchResult = null;
        resultBuilder.commandRiskScanControl = null;
    }

    @Override public void requireOrderIdentityAvailable(long userId, PlaceOrderCommand command) {
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

    @Override public void queueTriggerMatching(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
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

    @Override public void seedChangeAccumulators() {
        if (resultBuilder.commandChangedUserIds != null) resultBuilder.changedUserIds.addAll(resultBuilder.commandChangedUserIds);
        if (resultBuilder.commandChangedOrderIds != null) resultBuilder.changedOrderIds.addAll(resultBuilder.commandChangedOrderIds);
    }

    static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        if (existing == null || existing.isEmpty()) return additions == null ? List.of() : additions;
        if (additions == null || additions.isEmpty()) return existing;
        if (additions.size() == 1 && existing.contains(additions.get(0))) return existing;
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    static List<Long> boxedOrderIds(
            com.surprising.aeron.service.state.MatcherSettlementPlan plan) {
        if (plan == null || plan.orderCount() == 0) return List.of();
        return plan.orderIdList();
    }

    /** Collect lifecycle order identities without creating boxed Long elements. */
    static List<Long> boxedOrderIds(List<? extends CoreOrderState> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        long[] values = new long[orders.size()];
        for (int index = 0; index < values.length; index++) values[index] = orders.get(index).orderId();
        return ImmutableLongArrayList.takeOwnership(values);
    }

    @Override public int pendingRiskScanCount() {
        return runtimeState.incompleteRiskScanCount();
    }

    @Override public void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt) {
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
        clearFactContext();
        directCommand.clearDirect();
        batches.clearPendingBatches();
        pendingMatching.clear();
        crossShardCancellations.clear();
        admissions.pendingLifecycleScopes.clear();
        admissions.deferredMatching.clear();
        exportState.close();
        runtimeProjectionJournal.close();
        runtimeState.close();
    }

    CoreResponse userStateResponse(long userId) {
        var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.userState(
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

    void captureRealtimeTrades(CommandSlot pending) {
        var plan = pending.settlementPlan();
        OrderRuntime taker = pending.realtimeTakerOrder;
        pending.realtimeTakerOrder = null;
        if (realtimeCapture == null || !realtimeCapture.active()) return;
        try {
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
        return orderStateResponse(com.surprising.aeron.service.state.query.RuntimeStateQueryService.orderState(
                runtimeState, identities, orderId));
    }

    CoreResponse orderStateResponse(
            com.surprising.aeron.service.state.query.RuntimeStateQueryService.OrderQueryResult query) {
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
