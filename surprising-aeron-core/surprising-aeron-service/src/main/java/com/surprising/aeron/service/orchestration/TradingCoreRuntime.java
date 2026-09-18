package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.state.account.TransferRuntime;

import com.surprising.aeron.service.orchestration.metrics.CoreLaneMetrics;
import com.surprising.aeron.service.orchestration.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.orchestration.snapshot.CoreStateSnapshotCodec;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.orchestration.realtime.RealtimeReadCoordinator;

import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
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
import com.surprising.aeron.service.exception.FatalMatchingDivergenceException;
import com.surprising.aeron.service.matcher.MatcherPipelineGroup;
import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.risk.RiskScanRuntime;
import com.surprising.aeron.service.state.LiquidationRuntime;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** 直接控制命令的异步终结边界。 */
    final CoreDirectCommandFlow directCommandFlow = new CoreDirectCommandFlow(this);
    /** 集群日志入口的幂等、来源序号与命令分流边界。 */
    final CoreCommandIngress commandIngress = new CoreCommandIngress(this, directCommandFlow);
    /** 撮合在途命令、Matcher 提交及 Lane 结算事件的完整流程。 */
    final CoreMatchingFlow matchingFlow = new CoreMatchingFlow(this);
    /** 已提交状态的查询与快照读视图。 */
    final CoreRuntimeStateView stateView = new CoreRuntimeStateView(this);
    /** Owner 绑定、启动、健康检查和释放顺序。 */
    final CoreRuntimeLifecycle lifecycle = new CoreRuntimeLifecycle(this);

    /** Common matching responses use bounded stable slabs; ledger eviction returns their slots. */
    final ResponseArena responseArena = new ResponseArena();

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
    /** 查询协议路由；只读权威状态，不参与命令准入或资金变更。 */
    final TradingCoreQueryRouter queryRouter = new TradingCoreQueryRouter(this);
    /** 撮合在途推进状态；拥有 Lane/Matcher 通知排空和队首唤醒规则。 */
    final MatchingPipelineProgress matchingProgress = new MatchingPipelineProgress(this);
    /** 已准入命令到确定性 Matcher 输入的协议转换与证据包装。 */
    final MatcherCommandSubmission matcherCommands = new MatcherCommandSubmission(this);
    /** 普通命令的 Matcher→Account Lane 直接结算事件准备。 */
    final DirectMatcherSettlementPreparation directMatcherSettlements =
            new DirectMatcherSettlementPreparation(this);

    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    com.surprising.aeron.service.state.realtime.RealtimeStateCapture realtimeCapture;
    /** 绑定实时事件出口，并创建实时快照/盘口读取协调器。 */
    public com.surprising.aeron.service.state.realtime.RealtimeStateCapture attachRealtime(
            com.surprising.aeron.client.RealtimeOutbox outbox) {
        realtimeCapture = new com.surprising.aeron.service.state.realtime.RealtimeStateCapture(
                outbox, productLine, identities);
        runtimeState.realtimeCapture(realtimeCapture);
        realtimeReads = new RealtimeReadCoordinator(runtimeState, matcherPipeline, matchingAdapter, realtimeCapture);
        return realtimeCapture;
    }

    /** 异步实时快照和盘口读取的生命周期管理。 */
    RealtimeReadCoordinator realtimeReads;

    /** 返回已经提交并可对外发布的实时事件水位。 */
    public long realtimeExportSequence() { return runtimeProjectionJournal.publishedSequence(); }
    /** 返回是否存在尚未完成的用户实时快照读取。 */
    public boolean realtimeSnapshotPending() { return realtimeReads != null && realtimeReads.realtimeSnapshotPending(); }
    /** 返回是否存在尚未完成的盘口读取。 */
    public boolean realtimeBookPending() { return realtimeReads != null && realtimeReads.realtimeBookPending(); }
    /** 推进用户实时快照读取，并返回本轮完成的工作数。 */
    public int pollRealtimeSnapshot() { return realtimeReads == null ? 0 : realtimeReads.pollRealtimeSnapshot(); }
    /** 推进盘口读取，并返回本轮完成的工作数。 */
    public int pollRealtimeBook() { return realtimeReads == null ? 0 : realtimeReads.pollRealtimeBook(); }

    /** 在已提交位置上登记一个用户实时快照请求。 */
    public void captureRealtimeSnapshot(long userId, long snapshotId, long position, long timestamp) {
        if (realtimeReads == null || realtimeReads.realtimeSnapshotPending()) return;
        assertClusterCallbackComplete();
        realtimeReads.captureRealtimeSnapshot(userId, snapshotId, position, timestamp, realtimeExportSequence());
    }

    /** 在已提交位置上登记一个盘口读取请求。 */
    public void captureRealtimeBook(String symbol, long position, long timestamp) {
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
    /** 手续费策略摘要缓存，策略变化时更新。 */
    long cachedFeePolicyHash;
    /** 待完成转账摘要缓存，转账状态变化时更新。 */
    long cachedTransferHash;

    /** 字符串与 primitive 标识的唯一字典；生命周期覆盖该运行时。 */
    final RuntimeIdentityRegistry identities;
    /** 唯一权威运行时状态；账户可变数据由所属 Lane 维护。 */
    final TradingRuntimeState runtimeState;

    /** 撮合或确定性执行的不可恢复错误，阻止继续交易。 */
    FatalMatchingDivergenceException fatalFailure;

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
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> restoredPendingTransfers,
            long restoredAuditBusinessStateHash,
            long restoredAuditFundsStateHash,
            MatcherSnapshotCapture matcherSnapshotCapture,
            SnapshotEncoder snapshotEncoder) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.committedCoreSequence = appliedCommandCount;
        this.probeValue = probeValue;
        this.resultLedger = new CommandResultLedger(commandResults, responseArena);
        this.lastSourceSequences = new SourceSequenceIndex(lastSourceSequences);
        admissions.pendingLifecycleScopes = new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
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
                    SectionedCoreSnapshotWriter.encode(image));
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
        commits.initializeCommitPublication(snapshotState.revision());
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
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers,
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

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        return commandIngress.apply(message, clusterTimestamp, clusterPosition);
    }

    boolean requiresOwnerLaneAccessForPreparation(CoreMessage message) {
        return commandIngress.requiresOwnerLaneAccessForPreparation(message);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position) {
        return commandIngress.applyClusterCommand(message, timestamp, position);
    }

    CoreResponse applyClusterCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded) {
        return commandIngress.applyClusterCommand(message, timestamp, position, decoded);
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent) {
        return commandIngress.applyDecodedCommand(message, timestamp, position, decoded, independent);
    }

    CoreResponse applyDecodedCommand(CoreMessage message, long timestamp, long position,
                                     DecodedMatchingCommand decoded, boolean independent,
                                     CommandFingerprint fingerprint) {
        return commandIngress.applyDecodedCommand(message, timestamp, position, decoded, independent, fingerprint);
    }

    DecodedMatchingCommand decodeMatchingCommand(CoreMessage message) {
        return commandIngress.decodeMatchingCommand(message);
    }

    boolean prepareClusterPipelineScope(CoreMessage message, ClusterCommandWindow window) {
        return commandIngress.prepareClusterPipelineScope(message, window);
    }
    CoreResponse finishDirectCommand(CoreMessage message, long clusterTimestamp, long clusterPosition,
            SourceKey sourceKey, CommandFingerprint fingerprint, RuntimeProjectionPoint beforeProjection,
            long beforeRuntimeRevision, long runtimeCommandCheckpoint, long positionIdentityCheckpoint,
            ResponseStatus status, CoreResultCode resultCode) {
        return directCommandFlow.finish(message, clusterTimestamp, clusterPosition, sourceKey, fingerprint,
                beforeProjection, beforeRuntimeRevision, runtimeCommandCheckpoint,
                positionIdentityCheckpoint, status, resultCode);
    }
    /** 唯一直接控制命令的续步槽；与订单撮合序号槽分离。 */
    final DirectCommandSlot directCommand = new DirectCommandSlot();

    @Override public void deferControl(java.util.function.BooleanSupplier continuation) {
        directCommand.deferControl(continuation);
    }

    @Override public void deferFundingControl(
            com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor.FundingWork work) {
        directCommand.deferFundingControl(this, work);
    }

    @Override public com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor.FundingWork
            reusableFundingWork() {
        return directCommand.reusableFundingWork();
    }

    @Override public void deferRiskScanControl(
            com.surprising.aeron.service.command.risk.RiskCommandContext owner,
            com.surprising.aeron.service.state.RiskScanCoordinator risk, int symbolId, String symbol,
            int maxUsers, int pendingBefore, long startedAt, long beforeRevision) {
        directCommand.deferRiskScanControl(owner, risk, symbolId, symbol, maxUsers, pendingBefore,
                startedAt, beforeRevision);
    }

    @Override public void deferAdlControl(
            com.surprising.aeron.service.state.RuntimeAdlExecution.AdlWork work) {
        directCommand.deferAdlControl(this, work);
    }

    @Override public com.surprising.aeron.service.state.RuntimeAdlExecution.AdlWork
            reusableAdlWork() {
        return directCommand.reusableAdlWork();
    }

    @Override public void deferLiquidationResolutionControl(
            com.surprising.aeron.service.state.RuntimeLiquidationResolution.ResolutionWork work) {
        directCommand.deferLiquidationResolutionControl(this, work);
    }

    @Override public com.surprising.aeron.service.state.RuntimeLiquidationResolution.ResolutionWork
            reusableLiquidationResolutionWork() {
        return directCommand.reusableLiquidationResolutionWork();
    }

    @Override public com.surprising.aeron.service.state.RiskScanCoordinator
            reusableRiskScanCoordinator(int maxUsers) {
        return directCommand.reusableRiskScanCoordinator(maxUsers, positionUserIndex, runtimeState,
                identities);
    }

    @Override public com.surprising.aeron.service.state.AccountBalanceAdjustment
            reusableBalanceAdjustment() {
        return directCommand.reusableBalanceAdjustment();
    }

    @Override public com.surprising.aeron.service.state.AccountTransferOut reusableTransferOut() {
        return directCommand.reusableTransferOut();
    }

    @Override public com.surprising.aeron.service.state.AccountLeverageChange reusableLeverageChange() {
        return directCommand.reusableLeverageChange();
    }

    @Override public com.surprising.aeron.service.state.AccountPositionModeChange reusablePositionModeChange() {
        return directCommand.reusablePositionModeChange();
    }

    @Override public com.surprising.aeron.service.state.AccountPositionMarginAdjustment
            reusablePositionMarginAdjustment() {
        return directCommand.reusablePositionMarginAdjustment();
    }

    @Override public void deferBalanceAdjustmentControl(
            com.surprising.aeron.service.state.AccountBalanceAdjustment work) {
        directCommand.deferBalanceAdjustmentControl(this, work);
    }

    @Override public void deferTransferOutControl(
            com.surprising.aeron.service.state.AccountTransferOut work) {
        directCommand.deferTransferOutControl(this, work);
    }

    @Override public void deferLeverageChangeControl(
            com.surprising.aeron.service.state.AccountLeverageChange work, long beforeRevision) {
        directCommand.deferLeverageChangeControl(this, work, beforeRevision);
    }

    @Override public void deferPositionModeChangeControl(
            com.surprising.aeron.service.state.AccountPositionModeChange work, long beforeRevision) {
        directCommand.deferPositionModeChangeControl(this, work, beforeRevision);
    }

    @Override public void deferPositionMarginAdjustmentControl(
            com.surprising.aeron.service.state.AccountPositionMarginAdjustment work) {
        directCommand.deferPositionMarginAdjustmentControl(this, work);
    }

    @Override public void deferTriggerMutation(long userId,
            com.surprising.aeron.service.command.trigger.TriggerCommandContext.Mutation mutation,
            long triggerOrderId, long arg1, long arg2, long arg3, boolean flag, String text) {
        directCommand.deferTriggerMutationControl(this, userId, mutation, triggerOrderId, arg1, arg2,
                arg3, flag, text);
    }

    @Override public void deferTriggerUpsert(long userId,
            com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger, int symbolId,
            long positionKey, boolean instrumentSettled) {
        directCommand.deferTriggerUpsertControl(this, userId, trigger, symbolId, positionKey,
                instrumentSettled);
    }

    @Override public void deferAlgoUpsert(long userId,
            com.surprising.aeron.protocol.CoreAlgoOrderView algo, int symbolId) {
        directCommand.deferAlgoUpsertControl(this, userId, algo, symbolId);
    }

    boolean hasPendingDirectCommand() { return directCommand.active(); }

    CoreResponse pollDirectCommand() { return directCommand.poll(this); }

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

    /** Releases the transport-side reference after the response has been encoded or copied. */
    void releaseResponse(CoreResponse response) {
        if (response != null) responseArena.release(response.dataUnsafe());
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
        return matchingFlow.recordRejectedMatching(message, sourceKey, fingerprint, resultCode, deferredPending);
    }

    CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                        CommandFingerprint fingerprint, CoreResultCode resultCode) {
        return matchingFlow.recordRejectedMatching(message, sourceKey, fingerprint, resultCode);
    }

    CommandSlot removePendingMatching(long sequence) { return matchingFlow.removePendingMatching(sequence); }
    void refreshCommittedCoreSequence() { matchingFlow.refreshCommittedCoreSequence(); }
    void commitMatchingSequence(long sequence) { matchingFlow.commitMatchingSequence(sequence); }
    void putPendingMatching(CommandSlot pending) { matchingFlow.putPendingMatching(pending); }
    int matcherShard(CommandSlot pending) { return matchingFlow.matcherShard(pending); }

    LifecycleOrderChunk lifecycleOrders(long userId, String symbol, long cursorOrderId, int maxOrders) {
        return matchingFlow.lifecycleOrders(userId, symbol, cursorOrderId, maxOrders);
    }

    List<CoreOrderState> batchCancellationOrders(CommandSlot pending) {
        return matchingFlow.batchCancellationOrders(pending);
    }

    record LifecycleOrderChunk(List<CoreOrderState> orders, long nextCursorOrderId) {
        boolean more() { return nextCursorOrderId != 0; }
    }

    void submitMatching(CommandSlot pending) { matchingFlow.submitMatching(pending); }
    boolean predispatchDirectSettlement(CommandSlot pending,
            com.surprising.aeron.service.state.MatcherSettlementEvent event) {
        return matchingFlow.predispatchDirectSettlement(pending, event);
    }
    int singleMatcherShard(List<CoreOrderState> orders) { return matchingFlow.singleMatcherShard(orders); }
    void progressPlaceBatchAdmissions() { matchingFlow.progressPlaceBatchAdmissions(); }
    void drainPlaceAdmissionNotifications() { matchingFlow.drainPlaceAdmissionNotifications(); }
    boolean matchingSubmissionDeferred(long sequence) { return matchingFlow.matchingSubmissionDeferred(sequence); }
    void matchingSubmissionCompleted(CommandSlot pending) { matchingFlow.matchingSubmissionCompleted(pending); }
    int pendingSubmissionShard(CommandSlot pending) { return matchingFlow.pendingSubmissionShard(pending); }
    void submitDeferredMatchingAfterBatch() { matchingFlow.submitDeferredMatchingAfterBatch(); }
    void publishMatchingCompletion(long sequence, com.surprising.aeron.service.matching.CoreMatchingResult result) {
        matchingFlow.publishMatchingCompletion(sequence, result);
    }

    com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message, OrderRuntime order) {
        return matchingFlow.replacementFor(message, order);
    }
    PlaceOrderCommand replacementFor(DecodedMatchingCommand decodedCommand,
            CommandSlot.Operation operation, OrderRuntime order) {
        return matchingFlow.replacementFor(decodedCommand, operation, order);
    }
    PlaceOrderCommand replacementFor(CommandSlot pending, OrderRuntime order) {
        return matchingFlow.replacementFor(pending, order);
    }
    PlaceOrderCommand replacementForAmend(AmendOrderCommand command, OrderRuntime order) {
        return matchingFlow.replacementForAmend(command, order);
    }
    com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long triggeredPriceTicks) {
        return matchingFlow.triggerPlacement(trigger, triggeredPriceTicks);
    }
    com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long triggeredPriceTicks,
            OrderRuntime order) {
        return matchingFlow.triggerPlacement(trigger, triggeredPriceTicks, order);
    }
    long currentMarkPriceTicks(String symbol, long referencePriceTicks) {
        return matchingFlow.currentMarkPriceTicks(symbol, referencePriceTicks);
    }
    public com.surprising.aeron.service.matching.CoreMatchingResult takeMatchingResult(long sequence) {
        return matchingFlow.takeMatchingResult(sequence);
    }
    boolean establishMatchingCommitFence(long sequence, long clusterTimestamp, long clusterPosition) {
        return matchingFlow.establishMatchingCommitFence(sequence, clusterTimestamp, clusterPosition);
    }
    boolean placeAdmissionOutstanding(CommandSlot pending) { return matchingFlow.placeAdmissionOutstanding(pending); }
    boolean collectPlaceAdmissionIfReady(CommandSlot pending) { return matchingFlow.collectPlaceAdmissionIfReady(pending); }
    boolean hasPendingMatchingRejection(long sequence) { return matchingFlow.hasPendingMatchingRejection(sequence); }
    CompletableFuture<Integer> matchingStateHashAsync() { return matchingFlow.matchingStateHashAsync(); }
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

    /** Fast Owner-side gate for the matching completion pump. */
    boolean hasMatchingDrainWork() { return matchingProgress.hasDrainWork(); }

    /** Consumes Lane/Matcher notifications and advances the ordered matching pipeline. */
    void drainMatchingCompletions() { matchingProgress.drainMatchingCompletions(); }

    long matchingProgressSequence() {
        return pendingMatching.dispatchRevision();
    }

    boolean hasLocalMatchingWork() { return matchingProgress.hasLocalMatchingWork(); }

    /** Only external completion cursors; the caller must first exhaust owner-local progress. */
    boolean hasMatchingNotifications() { return matchingProgress.hasNotifications(); }

    boolean hasMatchingNotifications(long now) { return matchingProgress.hasNotifications(now); }

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
        lifecycle.activate();
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

    FatalMatchingDivergenceException failMatching(
            CommandSlot pending,
            String detail,
            Throwable cause) {
        return lifecycle.failMatching(pending, detail, cause);
    }

    void assertClusterCallbackComplete() {
        lifecycle.assertClusterCallbackComplete();
    }

    void assertHealthy() {
        lifecycle.assertHealthy();
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

    public TradingCoreState tradingState() { return stateView.tradingState(); }

    int incompleteRiskScanCount() { return stateView.incompleteRiskScanCount(); }
    int incompleteFundingCount() { return stateView.incompleteFundingCount(); }
    int activeOrderCount() { return stateView.activeOrderCount(); }
    int positionCount() { return stateView.positionCount(); }
    int triggerOrderCount() { return stateView.triggerOrderCount(); }

    TradingCoreState snapshotTradingState() { return stateView.snapshotTradingState(); }
    long snapshotBusinessStateHash() { return stateView.snapshotBusinessStateHash(); }
    long snapshotBusinessAuditBaseHash() { return stateView.snapshotBusinessAuditBaseHash(); }
    long snapshotFundsStateHash() { return stateView.snapshotFundsStateHash(); }
    long snapshotProjectionSequence() { return stateView.snapshotProjectionSequence(); }
    long snapshotProjectionFreezeCount() { return stateView.snapshotProjectionFreezeCount(); }
    boolean runtimeRiskScanComplete() { return stateView.runtimeRiskScanComplete(); }
    boolean runtimeRiskScanComplete(String symbol) { return stateView.runtimeRiskScanComplete(symbol); }
    RiskScanRuntime runtimeRiskScan(String symbol) { return stateView.runtimeRiskScan(symbol); }
    MarkPriceRuntime runtimeMarkPrice(String symbol) { return stateView.runtimeMarkPrice(symbol); }
    long runtimeFundingSettlement(String symbol) { return stateView.runtimeFundingSettlement(symbol); }
    long runtimeInsurance(String asset) { return stateView.runtimeInsurance(asset); }
    LiquidationRuntime runtimeLiquidation(long liquidationId) { return stateView.runtimeLiquidation(liquidationId); }
    PositionRuntime runtimePosition(long userId, String positionKey) {
        return stateView.runtimePosition(userId, positionKey);
    }
    boolean snapshotHasPendingCommands() { return stateView.snapshotHasPendingCommands(); }
    Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies() {
        return stateView.feePolicies();
    }
    void restoreFeePolicies(Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies) {
        stateView.restoreFeePolicies(policies);
    }
    Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers() {
        return stateView.pendingTransfers();
    }
    void restorePendingTransfers(Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        stateView.restorePendingTransfers(transfers);
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
        lifecycle.bindOwner();
    }

    void assertOwner() {
        lifecycle.assertOwner();
    }

    void deferProvisionalSnapshotProjection() {
        commits.deferProvisionalCommitPublication();
    }

    com.surprising.aeron.service.state.PlaceAdmissionEvent dispatchPlaceAdmission(
            long userId, PlaceOrderCommand command, UUID commandId, long coreSequence,
            long timestamp, long position) {
        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtimeState, identities, userId,
                command, currentClusterTimestamp);
        int assetId = identities.assetId(resolved.reservationAsset());
        int matcherShard = matchingAdapter.matcherShardId(resolved.symbol());
        return runtimeState.dispatchPlaceAdmission(coreSequence, userId, resolved, commandId,
                openInterestIndex.openInterestSteps(resolved.symbol()),
                runtimeState.treasury().lifecycleSettlement(resolved.symbolId()) != 0,
                runtimeState.treasury().fundingProgress(resolved.symbolId()) != null,
                resolved.symbolId(), assetId, matcherShard, identities, timestamp, position);
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
                        event, commandFundsAccumulator, terminalRetention,
                        resultBuilder.changedUserIds, resultBuilder.changedOrderIds);
        resultBuilder.markLaneDeltaIdsSeeded();
        laneContext.completeLanes(event.requiredLaneMask());
        return delta;
    }

    void suspendMatchingCommitContext(CommandSlot pending) {
        if (!commits.commitPublicationDeferred() || pending == null
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
                commits.commitPublicationDirty(), commits.commitPublicationProvisionalOnly());
        commits.suspendCommitPublication();
        resultBuilder.commandChangedUserIds = List.of();
        resultBuilder.commandChangedOrderIds = List.of();
        resultBuilder.resetChangeAccumulators();
        clearFactContext();
    }

    void restoreMatchingCommitContext(CommandSlot pending) {
        if (commits.commitPublicationDeferred() || factContextActive) {
            throw new IllegalStateException("another owner commit context is active");
        }
        CommandSlot context = pending;
        activateFactContext(pending.command(), pending.fingerprint());
        commits.restoreCommitPublication(context.commitSnapshotDirty(), context.commitSnapshotProvisionalOnly());
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
        lifecycle.close();
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
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    static long computeTransferHash(
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
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
