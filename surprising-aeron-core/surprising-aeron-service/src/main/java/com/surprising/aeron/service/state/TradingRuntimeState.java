package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.aeron.service.state.model.CoreFeeRate;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权威交易状态：可变账户归属各 Lane，owner 仅消费已发布状态。
 * 结算派发和待完成预留由专门组件维护；资金、订单和持仓不会被复制为第二套可写模型。
 */
public final class TradingRuntimeState implements AutoCloseable {
    /** 撮合后结算派发与完成收集；沿用现货和衍生品结算处理器，保持 Lane 序号。 */
    final MatcherSettlementDispatcher settlements = new MatcherSettlementDispatcher(this);

    /** 尚未完成的订单预留索引；owner 维护序号和用户计数，在 Lane 完成后释放。 */
    final PendingReservationTracker pendingReservations = new PendingReservationTracker(this);

    /** 尚未完成的资金转账容量上限。 */
    public static final int MAX_PENDING_TRANSFERS = 131_072;
    /** 批量结算完成等待的超时时间，单位为纳秒。 */
    static final long ORDER_BATCH_SETTLEMENT_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 控制命令等同步提交边界等待 Lane 的最长时间，单位为纳秒。 */
    static final long LANE_COMMIT_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    /** 变更键缓冲触发压缩的数量阈值。 */
    static final int CHANGE_KEY_COMPACTION_THRESHOLD = 512;
    /** 启用并行结算派发所需的最少 Lane 操作数。 */
    static final int PARALLEL_SETTLEMENT_MIN_LANE_OPERATIONS = Math.max(2,
            Integer.getInteger("surprising.aeron.parallel-settlement-min-lane-operations", 2));
    /** 等待 Lane 完成时的自旋次数配置。 */
    static final int LANE_COMPLETION_SPINS = Math.max(0,
            Integer.getInteger("surprising.aeron.lane-completion-spins", 1_024));

    /** 所属产品线；所有命令、账户与币对均在此边界隔离。 */
    ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    /** 运行时变更版本，仅用于内部提交与回滚边界。 */
    long revision;
    /** 固定的 matcher 与账户 Lane 拓扑，快照恢复时必须一致。 */
    final LaneTopology topology;
    /** 各账户 Lane 的权威可变状态；启动后只能由所属 Lane 修改。 */
    final AccountLaneState[] accountLanes;
    /** 当前执行范围复用的 laneUser 临时缓冲，不保存第二份业务状态。 */
    final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
    /** 账户 Lane 工作线程与队列；owner 只负责派发和收集。 */
    final SettlementLaneWorker[] laneWorkers;
    /** 各 Lane 到 owner 的准入完成通知队列。 */
    final LaneSequenceQueue[] placeAdmissionReadyQueues;
    /** 各 Lane 到 owner 的结算完成通知队列。 */
    final LaneSequenceQueue[] matcherSettlementReadyQueues;
    /** 按 Lane 固定复用的变更任务，完成收集后才能重新派发。 */
    final LaneMutationTask[] laneMutationTasks;
    /** 当前执行范围复用的 laneMutationStartedNanos 临时缓冲，不保存第二份业务状态。 */
    final long[] laneMutationStartedNanosScratch;
    /** 当前执行范围复用的 laneMutationResults 临时缓冲，不保存第二份业务状态。 */
    final Object[] laneMutationResultsScratch;
    /** 本次已写入结果的 Lane 位图。 */
    long laneMutationResultsMask;

    /** 各 Lane 派发队列长度峰值，仅用于运行观测。 */
    final int[] accountLaneQueueHighWaterMarks;
    /** 各 Lane 按业务类型累计的完成数量。 */
    final long[][] accountLaneCompletedOperations;
    /** 各 Lane 按业务类型累计的延迟样本数。 */
    final long[][] accountLaneLatencySamples;
    /** 各 Lane 按业务类型累计的延迟纳秒数。 */
    final long[][] accountLaneTotalLatencyNanos;
    /** 各 Lane 按业务类型观察到的最大延迟。 */
    final long[][] accountLaneMaxLatencyNanos;
    /** owner 已收集的各 Lane 业务状态摘要。 */
    final long[] publishedLaneStateHashes;
    /** owner 已收集的各 Lane 资金状态摘要。 */
    final long[] publishedLaneFundsHashes;
    /** owner 可见的各 Lane 已提交序号。 */
    final long[] publishedLaneCommittedSequences;
    /** 已派发到各 Lane 的提交序号，防止重复派发。 */
    final long[] dispatchedLaneCommitSequences;
    /** 可复用的 laneCommitEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<LaneCommitEvent> laneCommitEventPool = new java.util.ArrayDeque<>();

    /** 可复用的 placeAdmissionEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<PlaceAdmissionEvent> placeAdmissionEventPool =
            new java.util.ArrayDeque<>();
    /** 可复用的 placeBatchAdmissionEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<PlaceBatchAdmissionEvent> placeBatchAdmissionEventPool =
            new java.util.ArrayDeque<>();
    /** 账户 Lane 是否已启动，区分恢复阶段和运行阶段。 */
    boolean accountLanesStarted;
    /** 控制阶段已通过异步交接取得全部 Lane 的唯一写入权。 */
    boolean ownerLaneAccess;
    /** 当前是否存在尚未完成的所有权交接。 */
    private boolean laneHandoffRequested;
    /** 交接代次，防止上一轮释放被误认为新一轮完成。 */
    private long laneHandoffEpoch;
    /** 交接无进展只触发节点故障，不转换成与线程速度相关的业务拒绝。 */
    private long laneHandoffDeadline;

    /** 各币对当前标记价，由所属产品运行时独立维护。 */
    final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    /** 各币对的风险扫描续作状态。 */
    final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    /** 手续费、保险、清算等全局资金状态，按提交边界合并。 */
    final TreasuryRuntime treasury = new TreasuryRuntime();
    /** 当前产品线的币对配置，以运行时生效状态为准。 */
    final Map<String, CoreInstrumentState> instruments = new HashMap<>();
    /** 用户自动撤单定时器状态。 */
    final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
    /** 用户手续费策略状态，参与持久化与恢复。 */
    final Map<Long, CoreFeePolicyState> feePolicies = new HashMap<>();
    /** 尚未结束的跨账户资金转账，完成后移除。 */
    final Map<Long, TransferRuntime> pendingTransfers = new HashMap<>();

    /** 订单 ID 到所属 Lane 的路由索引；Lane ID 加一保存。 */
    final Long2LongHashMap orderLaneIds = new Long2LongHashMap(4_096, 0.65f, 0);
    /** 预留 ID 到所属 Lane 的路由索引；Lane ID 加一保存。 */
    final Long2LongHashMap reservationLaneIds = new Long2LongHashMap(4_096, 0.65f, 0);
    /** 持仓 ID 到所属 Lane 的路由索引；Lane ID 加一保存。 */
    final Long2LongHashMap positionLaneIds = new Long2LongHashMap(4_096, 0.65f, 0);
    /** 当前执行范围复用的 matcherSettlementRemaining 临时缓冲，不保存第二份业务状态。 */
    final LongLongHashMap matcherSettlementRemainingScratch = new LongLongHashMap();
    /** 当前执行范围复用的 matcherSettlementOrder 临时缓冲，不保存第二份业务状态。 */
    final LongHashSet matcherSettlementOrderScratch = new LongHashSet();
    /** owner 可见的已发布用户；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<UserRuntime> publishedUsers = new LongObjectHashMap<>(4_096);
    /** owner 可见的已发布订单；与提交/回滚边界同步维护。 */
    final Long2ObjectHashMap<OrderRuntime> publishedOrders = new Long2ObjectHashMap<>(4_096, 0.65f);
    /** owner 可见的已发布预留；与提交/回滚边界同步维护。 */
    final Long2ObjectHashMap<ReservationRuntime> publishedReservations = new Long2ObjectHashMap<>(4_096, 0.65f);
    /** owner 可见的已发布持仓；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<PositionRuntime> publishedPositions = new LongObjectHashMap<>(4_096);
    /** owner 可见的已发布清算；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<LiquidationRuntime> publishedLiquidations = new LongObjectHashMap<>(4_096);
    // Owner view of immutable Lane results; no mutable Lane map is read by the owner.
    final LongObjectHashMap<CoreTriggerOrderState> publishedTriggerOrders = new LongObjectHashMap<>();
    /** owner 可见的已发布风险快照；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<RiskSnapshotRuntime> publishedRiskSnapshots = new LongObjectHashMap<>(4_096);
    /** owner 可见的已发布可用余额；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<IntLongHashMap> publishedAvailableBalances = new LongObjectHashMap<>(4_096);
    /** 各 Lane 输出给 owner 的变化缓冲，在完成交接后消费。 */
    final PublishedLaneChanges[] publishedLaneChanges;

    /** 下一条清算状态的 ID，需随快照保存。 */
    long nextLiquidationId = 1;
    /** owner 写入，Lane 在控制任务发布后读取；只随成功的行情输入推进。 */
    long marketRevision;
    /** 命令回滚所需的行情输入序号原值。 */
    long patchMarketRevisionBefore;
    boolean patchMarketRevisionChanged;
    /** 风险扫描开关和预算；各产品线独立保存。 */
    CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    /** 当前提交范围内变化的用户；与提交/回滚边界同步维护。 */
    final LongHashSet changedUsers = new LongHashSet();
    /** 当前提交范围内变化的余额；与提交/回滚边界同步维护。 */
    final LongObjectHashMap<IntHashSet> changedBalances = new LongObjectHashMap<>();
    /** 当前提交范围内变化的订单；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<OrderRuntime> changedOrders =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的活跃订单索引值；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<CoreOrderState> changedActiveOrderValues =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的预留；与提交/回滚边界同步维护。 */
    final LongHashSet changedReservations = new LongHashSet();
    /** 当前提交范围内变化的持仓；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<PositionRuntime> changedPositions =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的持仓索引值；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<RuntimePositionIndexValue> changedPositionIndexValues =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的清算；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<LiquidationRuntime> changedLiquidations =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的标记价；与提交/回滚边界同步维护。 */
    final IntHashSet changedMarkPrices = new IntHashSet();
    /** 当前提交范围内变化的风险快照；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<RiskSnapshotRuntime> changedRiskSnapshots =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的风险扫描；与提交/回滚边界同步维护。 */
    final IntHashSet changedRiskScans = new IntHashSet();
    /** 当前提交范围内变化的币对；与提交/回滚边界同步维护。 */
    final HashSet<String> changedInstruments = new HashSet<>();
    /** 当前提交范围内变化的杠杆；与提交/回滚边界同步维护。 */
    final HashSet<CoreLeverageKey> changedLeverages = new HashSet<>();
    /** 当前提交范围内变化的算法单；与提交/回滚边界同步维护。 */
    final LongHashSet changedAlgoOrders = new LongHashSet();
    /** 当前提交范围内变化的自动撤单定时器；与提交/回滚边界同步维护。 */
    final HashSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new HashSet<>();
    /** 当前提交范围内变化的触发单；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<CoreTriggerOrderState> changedTriggerOrders =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的手续费策略；与提交/回滚边界同步维护。 */
    final LongHashSet changedFeePolicies = new LongHashSet();
    /** 回滚需要的变更前用户；按 Lane 隔离保存，完成后清理。 */
    final LaneLongCaptures<UserRuntime>[] patchUsersBeforeByLane;
    /** 回滚需要的变更前余额；按 Lane 隔离保存，完成后清理。 */
    final LaneBalancePatches[] patchBalancesBeforeByLane;
    /** 回滚需要的变更前预留；按 Lane 隔离保存，完成后清理。 */
    final LaneLongCaptures<PatchReservationBefore>[] patchReservationsBeforeByLane;
    /** 回滚需要的变更前订单；按 Lane 隔离保存，完成后清理。 */
    final LaneLongCaptures<PatchOrderBefore>[] patchOrdersBeforeByLane;
    /** 回滚需要的变更前持仓；按 Lane 隔离保存，完成后清理。 */
    final LaneLongCaptures<PositionRuntime>[] patchPositionsBeforeByLane;
    /** 回滚需要的变更前客户单号；按 Lane 隔离保存，完成后清理。 */
    final LaneClientOrderCaptures[] patchClientOrdersBeforeByLane;
    /** 是否处于批量变更范围，限制不支持的交叉全局修改。 */
    boolean orderBatchMutationScope;
    /** 回滚需要的变更前清算；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<LiquidationRuntime>> patchLiquidationsBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前风险快照；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<RiskSnapshotRuntime>> patchRiskSnapshotsBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前杠杆；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<CoreLeverageKey, PatchBefore<Long>> patchLeveragesBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前算法单；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<CoreAlgoOrderState>> patchAlgoOrdersBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前触发单；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<CoreTriggerOrderState>> patchTriggerOrdersBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前定时器；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<CoreCancelAllAfterKey, PatchBefore<CoreCancelAllAfterState>> patchTimersBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前标记价；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Integer, PatchBefore<MarkPriceRuntime>> patchMarkPricesBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前风险扫描；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Integer, PatchBefore<RiskScanRuntime>> patchRiskScansBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前币对；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<String, PatchBefore<CoreInstrumentState>> patchInstrumentsBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前待完成转账；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<TransferRuntime>> patchPendingTransfersBefore =
            new ConcurrentHashMap<>();
    /** 回滚需要的变更前手续费策略；与提交/回滚边界同步维护。 */
    ConcurrentHashMap<Long, PatchBefore<CoreFeePolicyState>> patchFeePoliciesBefore =
            new ConcurrentHashMap<>();
    /** 本次修改前的清算 ID 分配器值，用于回滚。 */
    long patchNextLiquidationIdBefore;
    /** 清算 ID 分配器是否已记录修改前值。 */
    boolean patchNextLiquidationIdChanged;
    /** 本次修改前的风险扫描控制状态。 */
    CoreRiskScanControlView patchRiskScanControlBefore;
    /** 风险扫描控制是否已记录修改前值。 */
    boolean patchRiskScanControlChanged;
    /** 当前 Lane 的线程局部上下文，防止越界读取可变状态。 */
    final ThreadLocal<AccountLaneState> laneCommandScope = new ThreadLocal<>();
    /** 当前结算事件的线程局部变更收集器。 */
    final ThreadLocal<MatcherSettlementChanges> matcherSettlementChangesScope = new ThreadLocal<>();
    /** 可复用的 matcherSettlementChanges 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<MatcherSettlementChanges> matcherSettlementChangesPool =
            new java.util.ArrayDeque<>();
    /** 可复用的 laneCancelEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<LaneCancelEvent> laneCancelEventPool = new java.util.ArrayDeque<>();
    /** 可复用的 laneReplaceEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<LaneReplaceEvent> laneReplaceEventPool = new java.util.ArrayDeque<>();

    /** 拥有当前执行上下文的唯一 owner；不得在其他线程直接修改其状态。 */
    Thread owner;

    public TradingRuntimeState() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public TradingRuntimeState(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        this.accountLanes = new AccountLaneState[topology.accountLaneCount()];
        @SuppressWarnings("unchecked")
        LaneLongCaptures<UserRuntime>[] userPatches =
                (LaneLongCaptures<UserRuntime>[]) new LaneLongCaptures<?>[
                        topology.accountLaneCount()];
        this.patchUsersBeforeByLane = userPatches;
        this.patchBalancesBeforeByLane = new LaneBalancePatches[topology.accountLaneCount()];
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PatchReservationBefore>[] reservationPatches =
                (LaneLongCaptures<PatchReservationBefore>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchReservationsBeforeByLane = reservationPatches;
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PatchOrderBefore>[] orderPatches =
                (LaneLongCaptures<PatchOrderBefore>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchOrdersBeforeByLane = orderPatches;
        @SuppressWarnings("unchecked")
        LaneLongCaptures<PositionRuntime>[] positionPatches =
                (LaneLongCaptures<PositionRuntime>[]) new LaneLongCaptures<?>[topology.accountLaneCount()];
        this.patchPositionsBeforeByLane = positionPatches;
        this.patchClientOrdersBeforeByLane = new LaneClientOrderCaptures[topology.accountLaneCount()];
        this.publishedLaneChanges = new PublishedLaneChanges[topology.accountLaneCount()];
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] routedUsers =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.laneWorkers = new SettlementLaneWorker[topology.accountLaneCount()];
        this.placeAdmissionReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.matcherSettlementReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.laneMutationTasks = new LaneMutationTask[topology.accountLaneCount()];
        this.laneMutationStartedNanosScratch = new long[topology.accountLaneCount()];
        this.laneMutationResultsScratch = new Object[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.accountLaneCompletedOperations = laneMetricValues(topology.accountLaneCount());
        this.accountLaneLatencySamples = laneMetricValues(topology.accountLaneCount());
        this.accountLaneTotalLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.accountLaneMaxLatencyNanos = laneMetricValues(topology.accountLaneCount());
        this.publishedLaneStateHashes = new long[topology.accountLaneCount()];
        this.publishedLaneFundsHashes = new long[topology.accountLaneCount()];
        this.publishedLaneCommittedSequences = new long[topology.accountLaneCount()];
        this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            placeAdmissionReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            matcherSettlementReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneHashes(accountLanes[laneId]);
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            patchUsersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchBalancesBeforeByLane[laneId] = new LaneBalancePatches();
            patchReservationsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchOrdersBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchPositionsBeforeByLane[laneId] = new LaneLongCaptures<>();
            patchClientOrdersBeforeByLane[laneId] = new LaneClientOrderCaptures();
            publishedLaneChanges[laneId] = new PublishedLaneChanges();
        }
    }

    public LaneTopology topology() {
        return topology;
    }

    public AccountLaneView accountLane(long userId) {
        assertOwner();
        return accountLaneById(topology.accountLaneId(userId));
    }

    public AccountLaneView[] accountLanes() {
        assertOwner();
        AccountLaneView[] views = new AccountLaneView[accountLanes.length];
        for (int laneId = 0; laneId < views.length; laneId++) views[laneId] = accountLaneById(laneId);
        return views;
    }

    public AccountLaneView accountLaneById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        return onLane(laneId, lane -> laneView(laneId, lane));
    }

    public long accountLaneLocalStateHashById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) {
            throw new IllegalArgumentException("invalid laneId");
        }
        return publishedLaneStateHashes[laneId];
    }

    public long accountLaneLocalFundsHashById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) {
            throw new IllegalArgumentException("invalid laneId");
        }
        return publishedLaneFundsHashes[laneId];
    }

    public AccountLaneMetricsSnapshot accountLaneMetricsById(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid laneId");
        int queueDepth = accountLanesStarted ? laneWorkers[laneId].depth() : 0;
        AccountLaneState.MatcherSettlementMetrics matcherMetrics =
                onLane(laneId, AccountLaneState::matcherSettlementMetrics);
        int settlementIndex = AccountLaneOperationType.SETTLEMENT.ordinal();
        long[] completed = accountLaneCompletedOperations[laneId].clone();
        long[] samples = accountLaneLatencySamples[laneId].clone();
        long[] totalLatency = accountLaneTotalLatencyNanos[laneId].clone();
        long[] maxLatency = accountLaneMaxLatencyNanos[laneId].clone();
        completed[settlementIndex] = Math.addExact(completed[settlementIndex], matcherMetrics.operations());
        samples[settlementIndex] = Math.addExact(samples[settlementIndex], matcherMetrics.operations());
        totalLatency[settlementIndex] = Math.addExact(
                totalLatency[settlementIndex], matcherMetrics.totalLatencyNanos());
        maxLatency[settlementIndex] = Math.max(
                maxLatency[settlementIndex], matcherMetrics.maxLatencyNanos());
        return new AccountLaneMetricsSnapshot(queueDepth, accountLanes[laneId].queueCapacity(),
                accountLaneQueueHighWaterMarks[laneId], 0, 0,
                completed, samples, totalLatency, maxLatency);
    }

    /** Writes a single Lane snapshot; the encoder is exclusively handed off until onLane completes. */
    public void writeAccountLaneMetrics(int laneId, com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || encoder == null) {
            throw new IllegalArgumentException("invalid Lane metrics request");
        }
        int depth = accountLanesStarted ? laneWorkers[laneId].depth() : 0;
        int highWater = Math.max(depth, accountLaneQueueHighWaterMarks[laneId]);
        onLane(laneId, lane -> {
            // Admission/commit also update these counters on the Lane. Read them in this task,
            // after preceding Lane events; the Core owner is waiting and cannot update them.
            encoder.writeOperations(laneId, accountLaneCompletedOperations[laneId],
                    accountLaneLatencySamples[laneId], accountLaneTotalLatencyNanos[laneId],
                    accountLaneMaxLatencyNanos[laneId]);
            lane.writeMetrics(encoder, depth, highWater);
            return null;
        });
    }

    public void startAccountLanes() {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("account lanes are already started");
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            AccountLaneState lane = accountLanes[laneId];
            lane.releaseOwnerForHandoff();
            laneWorkers[laneId] = new SettlementLaneWorker(
                    "account", lane, topology.accountLaneQueueCapacity());
        }
        accountLanesStarted = true;
    }

    /** 集群命令推进作用域内禁止退回跨线程同步调用；独立恢复/快照 API 不在此作用域。 */
    private boolean asynchronousCommandScope;

    public void enterAsynchronousCommandScope() {
        assertOwner();
        if (asynchronousCommandScope) throw new IllegalStateException("nested asynchronous command scope");
        asynchronousCommandScope = true;
    }

    public void exitAsynchronousCommandScope() {
        assertOwner();
        asynchronousCommandScope = false;
    }

    /** 逐项批量业务只有取得唯一写入权后才能进入；失败返回表示下次继续轮询。 */
    public boolean tryEnterSequentialLaneStage() {
        return !asynchronousCommandScope || tryAcquireOwnerLaneAccess();
    }

    /** 批量阶段结束后归还 Lane；仍在交接或批量上下文中的所有权不能提前释放。 */
    public void releaseCompletedSequentialLaneStage() {
        if (ownerLaneAccess && !orderBatchMutationScope) releaseOwnerLaneAccess();
    }

    public boolean tryAcquireOwnerLaneAccess() {
        assertOwner();
        assertAccountLanesHealthy();
        if (ownerLaneAccess) return true;
        if (!accountLanesStarted) { ownerLaneAccess = true; return true; }
        if (!laneHandoffRequested) {
            laneHandoffEpoch = Math.incrementExact(laneHandoffEpoch);
            laneHandoffRequested = true;
            laneHandoffDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            for (SettlementLaneWorker worker : laneWorkers) worker.requestHandoff(laneHandoffEpoch);
        }
        for (SettlementLaneWorker worker : laneWorkers) {
            if (!worker.handoffReady(laneHandoffEpoch)) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - laneHandoffDeadline >= 0)
                    throw new IllegalStateException("Account Lane ownership handoff interrupted or timed out");
                return false;
            }
        }
        for (AccountLaneState lane : accountLanes) lane.bindOwner();
        ownerLaneAccess = true;
        return true;
    }

    public void releaseOwnerLaneAccess() {
        assertOwner();
        if (ownerLaneAccess && accountLanesStarted)
            for (AccountLaneState lane : accountLanes) lane.releaseOwnerForHandoff();
        ownerLaneAccess = false;
        if (laneHandoffRequested) {
            for (SettlementLaneWorker worker : laneWorkers) worker.resumeHandoff(laneHandoffEpoch);
            laneHandoffRequested = false;
        }
    }

    public void assertAccountLanesHealthy() {
        assertOwner();
        if (!accountLanesStarted) return;
        for (SettlementLaneWorker worker : laneWorkers) {
            Throwable failure = worker.failure();
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("account lane failed", failure);
        }
    }

    /** 集群控制阶段采用非阻塞派发；独立调用沿用调用方的同步完成契约。 */
    public boolean asynchronousCommands() { assertOwner(); return asynchronousCommandScope; }

    /** 控制账户任务的唯一派发/收集入口，与账户业务处理器分离。 */
    private final ControlLaneDispatcher controlLanes = new ControlLaneDispatcher(this);

    public void dispatchControlLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.SETTLEMENT, operation);
    }

    public void dispatchRiskLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.RISK, operation);
    }

    public boolean pollControlLanes() { return controlLanes.poll(); }

    public Object controlLaneResult(int laneId) { return controlLanes.result(laneId); }

    public void readFence(long userId, long committedCoreSequence) {
        assertOwner();
        onLane(topology.accountLaneId(userId), lane -> {
            lane.readFence(committedCoreSequence);
            return null;
        });
    }

    public void readFenceAll(long committedCoreSequence) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            final int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.requireReadFence(committedCoreSequence);
                return null;
            });
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            final int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.advanceReadFence(committedCoreSequence);
                return null;
            });
        }
    }

    static AccountLaneView laneView(int laneId, AccountLaneState lane) {
        String ownerThreadName = Thread.currentThread().getName();
        if (ownerThreadName.isBlank()) ownerThreadName = "product-core-owner";
        return new AccountLaneView(laneId, lane.revision(), lane.appliedSequence(), lane.committedSequence(),
                lane.localStateHash(), lane.localFundsHash(), lane.userCount(),
                0, lane.queueCapacity(), 0, ownerThreadName);
    }

    <T> T onLane(long userId, LaneOperation<T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    <T> T onLane(int laneId, LaneOperation<T> operation) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        if (ownerLaneAccess) return inLaneCommandScope(accountLanes[laneId], operation);
        if (!accountLanesStarted) return operation.apply(accountLanes[laneId]);
        if (asynchronousCommandScope)
            throw new org.agrona.concurrent.AgentTerminationException(
                    new IllegalStateException("asynchronous command requires Lane execution ownership"));
        LaneMutationTask task = laneMutationTasks[laneId];
        if (task == null) {
            task = new LaneMutationTask(laneId);
            laneMutationTasks[laneId] = task;
        }
        task.prepare(operation);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        laneWorkers[laneId].submit(task);
        @SuppressWarnings("unchecked") T result = (T) task.await();
        flushPublishedChanges(laneId);
        return result;
    }

    <T> T inLaneCommandScope(AccountLaneState lane, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        enterLaneCommandScope(lane);
        try {
            return operation.apply(lane);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    void enterLaneCommandScope(AccountLaneState lane) {
        if (lane == null || laneCommandScope.get() != null) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        lane.assertOwner();
        laneCommandScope.set(lane);
    }

    void enterMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || matcherSettlementChangesScope.get() != null) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        matcherSettlementChangesScope.set(changes);
    }

    void exitLaneCommandScope(AccountLaneState lane) {
        if (lane == null || laneCommandScope.get() != lane) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        // Lane workers are long lived. Retaining the empty ThreadLocalMap slot avoids allocating
        // a new Entry on every command while still making an inactive scope read as null.
        laneCommandScope.set(null);
        if (Thread.currentThread() == owner) flushPublishedChanges(lane.laneId());
    }

    void exitMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || matcherSettlementChangesScope.get() != changes) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        matcherSettlementChangesScope.set(null);
        exitLaneCommandScope(lane);
    }

    public <T> T executeUserSettlement(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user settlement command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, false, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public <T> T executeUserRisk(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user risk command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, false, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public <T> T executeRiskLane(int laneId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || operation == null) {
            throw new IllegalArgumentException("invalid risk lane command");
        }
        long startedNanos = System.nanoTime();
        T result = onLane(laneId, ignored -> operation.get());
        recordLaneOperation(laneId, AccountLaneOperationType.RISK, System.nanoTime() - startedNanos);
        return result;
    }

    public Object[] executeOwnerSettlements(Iterable<Long> userIds,
                                            java.util.function.IntFunction<Object> operation) {
        return executeOwnerSettlements(userIds, Long::longValue, operation);
    }

    public <E> Object[] executeOwnerSettlements(Iterable<E> values,
                                                java.util.function.ToLongFunction<E> ownerUserId,
                                                java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid owner settlement command");
        }
        long selectedLaneMask = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId > 0) selectedLaneMask |= topology.accountLaneMask(userId);
        }
        return executeLaneMutations(selectedLaneMask, Long.bitCount(selectedLaneMask), false, operation);
    }

    public <E> Object[] executeLifecycleSettlements(Iterable<E> values,
                                                    java.util.function.ToLongFunction<E> ownerUserId,
                                                    java.util.function.IntFunction<Object> operation) {
        assertOwner();
        if (values == null || ownerUserId == null || operation == null) {
            throw new IllegalArgumentException("invalid lifecycle settlement command");
        }
        long selectedLaneMask = 0;
        int workItems = 0;
        for (E value : values) {
            if (value == null) continue;
            long userId = ownerUserId.applyAsLong(value);
            if (userId <= 0) continue;
            workItems++;
            selectedLaneMask |= topology.accountLaneMask(userId);
        }
        return executeLaneMutations(selectedLaneMask, workItems, true, operation);
    }

    Object[] executeLaneMutations(long selectedLaneMask, int workItems, boolean allowParallel,
                                          java.util.function.IntFunction<Object> operation) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((selectedLaneMask & ~validMask) != 0 || workItems < 0 || operation == null) {
            throw new IllegalArgumentException("invalid account lane mutation");
        }
        Object[] results = laneMutationResultsScratch;
        long staleResults = laneMutationResultsMask;
        while (staleResults != 0) {
            int laneId = Long.numberOfTrailingZeros(staleResults);
            results[laneId] = null;
            staleResults &= staleResults - 1;
        }
        laneMutationResultsMask = selectedLaneMask;
        if (selectedLaneMask == 0) {
            return results;
        }
        if (workItems == 0) throw new IllegalArgumentException("account lane mutation has no work");
        int selectedCount = Long.bitCount(selectedLaneMask);
        int laneOperations = Math.max(workItems, selectedCount);
        if (!allowParallel || selectedCount < 2
                || laneOperations < PARALLEL_SETTLEMENT_MIN_LANE_OPERATIONS || !accountLanesStarted || ownerLaneAccess) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((selectedLaneMask & 1L << laneId) == 0) continue;
                int currentLaneId = laneId;
                long startedNanos = System.nanoTime();
                results[laneId] = accountLanesStarted
                        ? onLane(currentLaneId, ignored -> operation.apply(currentLaneId))
                        : inLaneCommandScope(accountLanes[currentLaneId],
                        ignored -> operation.apply(currentLaneId));
                recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                        System.nanoTime() - startedNanos);
            }
            return results;
        }
        long[] startedNanos = laneMutationStartedNanosScratch;
        long submittedLaneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & 1L << laneId) == 0) continue;
            accountLaneQueueHighWaterMarks[laneId] = Math.max(
                    accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
            startedNanos[laneId] = System.nanoTime();
            LaneMutationTask task = laneMutationTasks[laneId];
            if (task == null) {
                task = new LaneMutationTask(laneId);
                laneMutationTasks[laneId] = task;
            }
            SettlementLaneWorker worker = laneWorkers[laneId];
            task.prepareIndexed(operation);
            worker.submit(task);
            submittedLaneMask |= 1L << laneId;
        }
        completeLaneMutations(submittedLaneMask, results, startedNanos);
        return results;
    }

    void completeLaneMutations(long laneMask, Object[] results, long[] startedNanos) {
        RuntimeException failure = null;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) == 0) continue;
            try {
                results[laneId] = laneMutationTasks[laneId].await();
                laneWorkers[laneId].assertHealthy();
                recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT,
                        System.nanoTime() - startedNanos[laneId]);
            } catch (RuntimeException laneFailure) {
                if (failure == null) failure = laneFailure;
                else failure.addSuppressed(laneFailure);
            }
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0) flushPublishedChanges(laneId);
        }
        if (failure != null) throw failure;
    }

    @FunctionalInterface
    interface LaneOperation<T> {
        T apply(AccountLaneState lane);
    }

    public boolean currentLaneOwns(long userId) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) throw new IllegalStateException("account lane command scope is required");
        return topology.accountLaneId(userId) == scoped.laneId();
    }

    void applyLaneUsers(AccountLaneState lane,
                        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                        long coreSequence) {
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneHashes(lane);
    }

    void publishLaneHashes(AccountLaneState lane) {
        int laneId = lane.laneId();
        publishedLaneStateHashes[laneId] = lane.localStateHash();
        publishedLaneFundsHashes[laneId] = lane.localFundsHash();
        publishedLaneCommittedSequences[laneId] = lane.committedSequence();
    }

    static BalanceRuntime copyBalance(IntObjectHashMap<BalanceRuntime> balances, int assetId) {
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        return balance == null ? null : new BalanceRuntime(balance.userId(), balance.assetId(),
                balance.availableUnits(), balance.lockedUnits());
    }

    static IntObjectHashMap<BalanceRuntime> copyBalances(IntObjectHashMap<BalanceRuntime> balances) {
        if (balances == null) return null;
        IntObjectHashMap<BalanceRuntime> copy = new IntObjectHashMap<>(balances.size());
        balances.forEachKeyValue((assetId, balance) -> copy.put(assetId,
                new BalanceRuntime(balance.userId(), balance.assetId(),
                        balance.availableUnits(), balance.lockedUnits())));
        return copy;
    }

    static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copyAllBalances(
            LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances) {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copy = new LongObjectHashMap<>(balances.size());
        balances.forEachKeyValue((userId, userBalances) -> copy.put(userId, copyBalances(userBalances)));
        return copy;
    }

    static LongObjectHashMap<LongLongHashMap> copyClientOrderIndex(
            LongObjectHashMap<LongLongHashMap> index) {
        LongObjectHashMap<LongLongHashMap> copy = new LongObjectHashMap<>(index.size());
        index.forEachKeyValue((userId, values) -> copy.put(userId, new LongLongHashMap(values)));
        return copy;
    }

    void publishUser(long userId, UserRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedUsers, userId, value);
        else lanePublishedChanges(scoped.laneId()).putUser(userId, value);
    }

    void publishOrder(long orderId, OrderRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedOrders, orderId, value);
        else lanePublishedChanges(scoped.laneId()).putOrder(orderId, value);
    }

    void publishReservation(long orderId, ReservationRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedReservations, orderId, value);
        else lanePublishedChanges(scoped.laneId()).putReservation(orderId, value);
    }

    void publishPosition(long positionKey, PositionRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedPositions, positionKey, value);
        else lanePublishedChanges(scoped.laneId()).putPosition(positionKey, value);
    }

    void publishLiquidation(long liquidationId, LiquidationRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedLiquidations, liquidationId, value);
        else lanePublishedChanges(scoped.laneId()).putLiquidation(liquidationId, value);
    }

    void publishRiskSnapshot(long positionKey, RiskSnapshotRuntime value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) putOrRemove(publishedRiskSnapshots, positionKey, value);
        else lanePublishedChanges(scoped.laneId()).putRiskSnapshot(positionKey, value);
    }

    void publishTriggerOrder(long id, CoreTriggerOrderState value) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped == null) {
            putOrRemove(publishedTriggerOrders, id, value);
            changedTriggerOrders.put(id, value);
        } else {
            lanePublishedChanges(scoped.laneId()).putTrigger(id, value);
        }
    }

    PublishedLaneChanges lanePublishedChanges(int laneId) {
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        return changes == null ? publishedLaneChanges[laneId] : changes.publishedLaneChanges[laneId];
    }

    void flushPublishedChanges(int laneId) {
        PublishedLaneChanges changes = publishedLaneChanges[laneId];
        changes.recordRiskChanges(this);
        changes.publishTriggersToOwner(this);
        changes.drainTo(
                laneId, publishedUsers, publishedOrders, publishedReservations, publishedPositions,
                publishedLiquidations, publishedRiskSnapshots,
                orderLaneIds, reservationLaneIds, positionLaneIds);
    }

    MatcherSettlementChanges acquireMatcherSettlementChanges() {
        assertOwner();
        MatcherSettlementChanges changes = matcherSettlementChangesPool.pollFirst();
        return changes == null ? new MatcherSettlementChanges(accountLanes.length) : changes;
    }

    void releaseMatcherSettlementChanges(MatcherSettlementChanges changes) {
        changes.clear();
        matcherSettlementChangesPool.addFirst(changes);
    }

    static final class MatcherSettlementChanges {
        /** 各 Lane 输出给 owner 的变化缓冲，在完成交接后消费。 */
        final PublishedLaneChanges[] publishedLaneChanges;
        /** 按 Lane 保存的余额前后值，用于资金增量核对。 */
        final LaneBalancePatches[] balancePatches;
        /** 各 Lane 收集的资金增量，完成后由 owner 合并。 */
        final RuntimeFundsAccumulator[] laneFundsDeltas;
        /** 汇总各 Lane 资金增量的复用累加器。 */
        final RuntimeFundsAccumulator aggregateFundsDelta = new RuntimeFundsAccumulator(32);

        MatcherSettlementChanges(int laneCount) {
            publishedLaneChanges = new PublishedLaneChanges[laneCount];
            balancePatches = new LaneBalancePatches[laneCount];
            laneFundsDeltas = new RuntimeFundsAccumulator[laneCount];
            for (int laneId = 0; laneId < laneCount; laneId++) {
                publishedLaneChanges[laneId] = new PublishedLaneChanges();
                balancePatches[laneId] = new LaneBalancePatches();
                laneFundsDeltas[laneId] = new RuntimeFundsAccumulator();
            }
        }

        void ensureAdmissionCapacity(int laneId, int expectedOrders) {
            if (expectedOrders <= 0) return;
            publishedLaneChanges[laneId].ensureAdmissionCapacity(expectedOrders);
        }

        void ensureOrderCapacity(int expectedOrders, long laneMask) {
            if (expectedOrders <= 0) return;
            while (laneMask != 0) {
                int laneId = Long.numberOfTrailingZeros(laneMask);
                laneMask &= laneMask - 1;
                publishedLaneChanges[laneId].ensureOrderCapacity(expectedOrders);
            }
        }

        void prepareLaneTerminal(int laneId, RuntimeIdentityRegistry identities, AccountLaneState lane) {
            PublishedLaneChanges changes = publishedLaneChanges[laneId];
            changes.orders.forEach((orderId, order) -> {
                changes.activeOrderValues.put(orderId,
                        order != null && order.status() == CoreOrderStatus.OPEN
                                ? RuntimeStateMaterializer.orderSnapshot(order, identities) : null);
                if (order == null || !order.status().terminal()) return;
                ReservationRuntime reservation = lane.reservations.get(orderId);
                if (reservation != null && reservation.reservedUnits() != 0) return;
                lane.removeOrder(orderId);
                changes.removeOrderRoute(orderId);
                if (reservation != null) {
                    lane.reservations.remove(orderId);
                    removeUserEntity(lane.reservationIdsByUser, order.userId(), orderId);
                    changes.removeReservationRoute(orderId);
                }
                lane.clientKeysByOrderId.forEach(orderId,
                        clientKey -> changes.retireClientIdentity(order.userId(), clientKey));
                removeClientOrdersForOrder(lane, order.userId(), orderId);
            });
            changes.positions.forEach((positionKey, position) -> changes.positionIndexValues.put(positionKey,
                    position == null ? null : RuntimePositionIndexValue.from(position, identities)));
            prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
        }

        void prepareAdmissionLane(int laneId) {
            prepareBalanceFundsDelta(balancePatches[laneId], laneFundsDeltas[laneId]);
        }

        RuntimeFundsDelta collectFundsDelta(long laneMask) {
            aggregateFundsDelta.clear();
            for (int laneId = 0; laneId < laneFundsDeltas.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) aggregateFundsDelta.add(laneFundsDeltas[laneId]);
            }
            return aggregateFundsDelta.toDelta();
        }

        void appendFundsDelta(long laneMask, RuntimeFundsAccumulator target) {
            if (target == null) throw new IllegalArgumentException("funds accumulator is required");
            for (int laneId = 0; laneId < laneFundsDeltas.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) target.add(laneFundsDeltas[laneId]);
            }
        }

        void clear() {
            for (PublishedLaneChanges changes : publishedLaneChanges) changes.clear();
            for (LaneBalancePatches patches : balancePatches) patches.clear();
            for (RuntimeFundsAccumulator delta : laneFundsDeltas) delta.clear();
            aggregateFundsDelta.clear();
        }
    }

    static <V> void putOrRemove(Long2ObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    static <V> void putOrRemove(LongObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    static final class PublishedLaneChanges {
        /** 本次需要发布的触发单变化。 */
        RuntimeChangeBuffer<CoreTriggerOrderState> triggers;
        /** 本次需要发布的用户变化。 */
        final RuntimeChangeBuffer<UserRuntime> users = new RuntimeChangeBuffer<>();
        /** 本次需要发布的订单变化。 */
        final RuntimeChangeBuffer<OrderRuntime> orders = new RuntimeChangeBuffer<>();
        /** 本次需要发布的预留变化。 */
        final RuntimeChangeBuffer<ReservationRuntime> reservations = new RuntimeChangeBuffer<>();
        /** 本次需要发布的持仓变化。 */
        final RuntimeChangeBuffer<PositionRuntime> positions = new RuntimeChangeBuffer<>();
        /** 本次需要发布的清算变化。 */
        final RuntimeChangeBuffer<LiquidationRuntime> liquidations = new RuntimeChangeBuffer<>();
        /** 本次需要发布的风险快照变化。 */
        final RuntimeChangeBuffer<RiskSnapshotRuntime> riskSnapshots = new RuntimeChangeBuffer<>();
        /** 当前变更对应的活跃订单索引值。 */
        final RuntimeChangeBuffer<CoreOrderState> activeOrderValues = new RuntimeChangeBuffer<>();
        /** 当前变更对应的持仓索引值。 */
        final RuntimeChangeBuffer<RuntimePositionIndexValue> positionIndexValues = new RuntimeChangeBuffer<>();
        /** 待移除的订单路由 ID。 */
        final LongHashSet removedOrderRoutes = new LongHashSet();
        /** 待移除的预留路由 ID。 */
        final LongHashSet removedReservationRoutes = new LongHashSet();
        /** 终态后可释放的客户单号，完成交接后由 owner 回收。 */
        final ClientIdentityReleaseBuffer retiredClientIdentities = new ClientIdentityReleaseBuffer();

        void ensureAdmissionCapacity(int expectedOrders) {
            users.ensureCapacity(1);
            orders.ensureCapacity(expectedOrders);
            reservations.ensureCapacity(expectedOrders);
        }

        void ensureOrderCapacity(int expectedOrders) {
            users.ensureCapacity(expectedOrders * 2);
            orders.ensureCapacity(expectedOrders);
            reservations.ensureCapacity(expectedOrders);
            positions.ensureCapacity(expectedOrders * 2);
            activeOrderValues.ensureCapacity(expectedOrders);
            positionIndexValues.ensureCapacity(expectedOrders * 2);
        }

        void putUser(long key, UserRuntime value) {
            users.put(key, value);
        }

        void putOrder(long key, OrderRuntime value) {
            orders.put(key, value);
        }

        void putReservation(long key, ReservationRuntime value) {
            reservations.put(key, value);
        }

        void putPosition(long key, PositionRuntime value) {
            positions.put(key, value);
        }

        void putLiquidation(long key, LiquidationRuntime value) {
            liquidations.put(key, value);
        }

        void putRiskSnapshot(long key, RiskSnapshotRuntime value) {
            riskSnapshots.put(key, value);
        }

        void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
        void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }
        void retireClientIdentity(long userId, long clientKey) {
            retiredClientIdentities.add(userId, clientKey);
        }

        void releaseRetiredClientIdentities(RuntimeIdentityRegistry identities) {
            retiredClientIdentities.release(identities);
        }

        void drainTo(int laneId,
                             LongObjectHashMap<UserRuntime> targetUsers,
                             Long2ObjectHashMap<OrderRuntime> targetOrders,
                             Long2ObjectHashMap<ReservationRuntime> targetReservations,
                             LongObjectHashMap<PositionRuntime> targetPositions,
                             LongObjectHashMap<LiquidationRuntime> targetLiquidations,
                             LongObjectHashMap<RiskSnapshotRuntime> targetRiskSnapshots,
                             Long2LongHashMap targetOrderLanes,
                             Long2LongHashMap targetReservationLanes,
                             Long2LongHashMap targetPositionLanes) {
            users.drain(targetUsers, null, laneId);
            orders.drain(targetOrders, targetOrderLanes, laneId);
            reservations.drain(targetReservations, targetReservationLanes, laneId);
            positions.drain(targetPositions, targetPositionLanes, laneId);
            liquidations.drain(targetLiquidations, null, laneId);
            riskSnapshots.drain(targetRiskSnapshots, null, laneId);
            removedOrderRoutes.forEach(orderId -> {
                targetOrders.remove(orderId);
                targetOrderLanes.remove(orderId);
            });
            removedReservationRoutes.forEach(orderId -> {
                targetReservations.remove(orderId);
                targetReservationLanes.remove(orderId);
            });
            removedOrderRoutes.clear();
            removedReservationRoutes.clear();
        }

        void commitTerminalToOwner(TradingRuntimeState state, int laneId,
                                           TerminalOrderSink terminalOrderSink, long coreSequence) {
            publishTriggersToOwner(state);
            users.drainTo((userId, user) -> {
                state.changedUsers.add(userId);
                putOrRemove(state.publishedUsers, userId, user);
            });
            orders.drainTo((orderId, order) -> {
                state.changedOrders.put(orderId, order);
                if (terminalOrderSink != null && order != null && order.status().terminal()) {
                    terminalOrderSink.accept(order, coreSequence);
                }
                putOrRemove(state.publishedOrders, orderId, order);
                if (order == null) state.orderLaneIds.remove(orderId);
                else state.orderLaneIds.put(orderId, laneId + 1L);
            });
            reservations.drainTo((orderId, reservation) -> {
                state.changedReservations.add(orderId);
                putOrRemove(state.publishedReservations, orderId, reservation);
                if (reservation == null) state.reservationLaneIds.remove(orderId);
                else state.reservationLaneIds.put(orderId, laneId + 1L);
            });
            positions.drainTo((positionKey, position) -> {
                state.changedPositions.put(positionKey, position);
                if (position == null && state.realtimeCapture != null) {
                    try { state.realtimeCapture.removedPosition(state.publishedPositions.get(positionKey)); }
                    catch (RuntimeException failure) { state.realtimeCapture.failed(); }
                }
                putOrRemove(state.publishedPositions, positionKey, position);
                if (position == null) state.positionLaneIds.remove(positionKey);
                else state.positionLaneIds.put(positionKey, laneId + 1L);
            });
            liquidations.drainTo((id, value) -> {
                state.changedLiquidations.put(id, value);
                putOrRemove(state.publishedLiquidations, id, value);
            });
            riskSnapshots.drainTo((key, value) -> {
                state.changedRiskSnapshots.put(key, value);
                putOrRemove(state.publishedRiskSnapshots, key, value);
            });
            activeOrderValues.drainTo(state.changedActiveOrderValues::put);
            positionIndexValues.drainTo(state.changedPositionIndexValues::put);
            removedOrderRoutes.forEach(orderId -> {
                state.publishedOrders.remove(orderId);
                state.orderLaneIds.remove(orderId);
            });
            removedReservationRoutes.forEach(orderId -> {
                state.publishedReservations.remove(orderId);
                state.reservationLaneIds.remove(orderId);
            });
            removedOrderRoutes.clear();
            removedReservationRoutes.clear();
        }

        void putTrigger(long id, CoreTriggerOrderState value) {
            if (triggers == null) triggers = new RuntimeChangeBuffer<>();
            triggers.put(id, value);
        }

        void publishTriggersToOwner(TradingRuntimeState state) {
            if (triggers == null || triggers.isEmpty()) return;
            triggers.forEach((id, value) -> {
                putOrRemove(state.publishedTriggerOrders, id, value);
                state.changedTriggerOrders.put(id, value);
            });
            triggers.clear();
        }

        void recordRiskChanges(TradingRuntimeState state) {
            liquidations.forEach(state.changedLiquidations::put);
            riskSnapshots.forEach(state.changedRiskSnapshots::put);
        }

        void clear() {
            if (triggers != null) triggers.clear();
            users.clear();
            orders.clear();
            reservations.clear();
            positions.clear();
            liquidations.clear();
            riskSnapshots.clear();
            activeOrderValues.clear();
            positionIndexValues.clear();
            removedOrderRoutes.clear();
            removedReservationRoutes.clear();
            retiredClientIdentities.clear();
        }

        static final class ClientIdentityReleaseBuffer {
            /** 当前缓冲各项对应的用户 ID。 */
            long[] userIds = new long[4];
            /** 当前缓冲各项对应的内部客户单号键。 */
            long[] clientKeys = new long[4];
            /** 当前有效元素数量。 */
            int size;

            void add(long userId, long clientKey) {
                if (userId <= 0 || clientKey <= 0) {
                    throw new IllegalArgumentException("invalid retired client identity");
                }
                if (size == userIds.length) {
                    int capacity = Math.multiplyExact(size, 2);
                    userIds = java.util.Arrays.copyOf(userIds, capacity);
                    clientKeys = java.util.Arrays.copyOf(clientKeys, capacity);
                }
                userIds[size] = userId;
                clientKeys[size] = clientKey;
                size++;
            }

            void release(RuntimeIdentityRegistry identities) {
                if (identities == null) return;
                for (int index = 0; index < size; index++) {
                    identities.releaseClientKey(userIds[index], clientKeys[index]);
                }
                clear();
            }

            void clear() {
                size = 0;
            }
        }

    }

    @Override
    public void close() {
        releaseOwnerLaneAccess();
        accountLanesStarted = false;
        for (SettlementLaneWorker worker : laneWorkers) {
            if (worker != null) worker.close();
        }
    }

    final class LaneMutationTask implements SettlementLaneWorker.Command {
        /** 该任务唯一归属的账户 Lane ID。 */
        final int laneId;
        /** 固定的 Lane 作用域调用，避免每次派发新建闭包。 */
        final LaneOperation<Object> indexedScopedOperation;
        /** 本次派发的业务操作，在对应执行完成之前保留。 */
        LaneOperation<Object> operation;
        /** 本次按 Lane ID 执行的业务操作。 */
        java.util.function.IntFunction<Object> indexedOperation;
        /** 当前派发任务的完成结果，在完成通知之后读取。 */
        Object result;
        /** 当前派发任务报告的失败，收集阶段传播给 owner。 */
        Throwable failure;
        /** 该任务是否已发布完成通知，建立结果可见性边界。 */
        volatile boolean completed = true;
        /** 等待任务完成的线程，只用于唤醒而不执行业务。 */
        volatile Thread waiter;

        LaneMutationTask(int laneId) {
            this.laneId = laneId;
            indexedScopedOperation = ignored -> indexedOperation.apply(this.laneId);
        }

        @SuppressWarnings("unchecked")
        void prepare(LaneOperation<?> operation) {
            if (!completed) throw new IllegalStateException("account lane task is still active");
            this.operation = (LaneOperation<Object>) operation;
            indexedOperation = null;
            result = null;
            failure = null;
            completed = false;
        }

        void prepareIndexed(java.util.function.IntFunction<Object> operation) {
            if (!completed) throw new IllegalStateException("account lane task is still active");
            this.operation = null;
            indexedOperation = operation;
            result = null;
            failure = null;
            completed = false;
        }

        @Override
        public void execute(AccountLaneState lane) {
            try {
                result = operation == null
                        ? inLaneCommandScope(lane, indexedScopedOperation)
                        : inLaneCommandScope(lane, operation);
            } catch (Throwable taskFailure) {
                failure = taskFailure;
            } finally {
                completed = true;
                Thread blocked = waiter;
                if (blocked != null) java.util.concurrent.locks.LockSupport.unpark(blocked);
            }
        }

        Object await() {
            boolean interrupted = false;
            Thread current = Thread.currentThread();
            waiter = current;
            try {
                int spins = LANE_COMPLETION_SPINS;
                while (!completed && spins-- > 0) Thread.onSpinWait();
                while (!completed) {
                    java.util.concurrent.locks.LockSupport.park(this);
                    if (Thread.interrupted()) interrupted = true;
                }
            } finally {
                if (waiter == current) waiter = null;
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (interrupted) throw new IllegalStateException("account lane mutation was interrupted");
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("account lane mutation failed", failure);
            return result;
        }
    }

    static long[][] laneMetricValues(int laneCount) {
        long[][] values = new long[laneCount][];
        for (int laneId = 0; laneId < laneCount; laneId++) {
            values[laneId] = new long[AccountLaneOperationType.values().length];
        }
        return values;
    }

    void recordLaneOperation(int laneId, AccountLaneOperationType operation, long latencyNanos) {
        int operationIndex = operation.ordinal();
        accountLaneCompletedOperations[laneId][operationIndex]++;
        accountLaneLatencySamples[laneId][operationIndex]++;
        accountLaneTotalLatencyNanos[laneId][operationIndex] = Math.addExact(
                accountLaneTotalLatencyNanos[laneId][operationIndex], latencyNanos);
        accountLaneMaxLatencyNanos[laneId][operationIndex] = Math.max(
                accountLaneMaxLatencyNanos[laneId][operationIndex], latencyNanos);
    }

    void recordMatcherLaneOperation(AccountLaneState lane, long latencyNanos) {
        lane.recordMatcherSettlement(latencyNanos);
    }

    void recordAdmissionLaneOperation(AccountLaneState lane, long latencyNanos) {
        recordLaneOperation(lane.laneId(), AccountLaneOperationType.COMMAND, latencyNanos);
    }

    void recordSequenceCommitLaneOperation(int laneId, long latencyNanos) {
        recordLaneOperation(laneId, AccountLaneOperationType.SETTLEMENT, latencyNanos);
    }

    record TerminalRelease(long units, int assetId) {
    }

    record CanceledOrder(OrderRuntime order, ReservationRuntime reservation) {
    }

    record TerminalOrderPrune(long orderId, long userId, long clientKey) {
    }

    record TerminalOrderPruned(
            long orderId, long userId, int reservationAssetId, boolean reservationRemoved) {
    }

    public long stageLaneMutation(long coreSequence, Iterable<Long> userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) continue;
            addLaneUser(userId.longValue());
        }
        return stageLaneMutationFromScratch(coreSequence);
    }

    public long stageLaneMutation(long coreSequence, long[] userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return stageLaneMutationFromScratch(coreSequence);
    }

    public LaneCommitEvent dispatchLaneMutation(long coreSequence, long[] userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) {
            throw new IllegalArgumentException("invalid lane apply");
        }
        clearLaneUserScratch();
        for (long userId : userIds) {
            if (userId > 0) addLaneUser(userId);
        }
        return dispatchLaneMutationFromScratch(coreSequence);
    }

    /** 直接控制命令使用已有结果集合派发，只触及发生变化的账户 Lane。 */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null) throw new IllegalArgumentException("invalid lane apply");
        clearLaneUserScratch();
        for (Long userId : userIds) if (userId != null && userId > 0) addLaneUser(userId);
        return dispatchLaneMutationFromScratch(coreSequence);
    }

    void clearLaneUserScratch() {
        for (org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users : laneUserScratch) {
            users.clear();
        }
    }

    void addLaneUser(long userId) {
        int laneId = topology.accountLaneId(userId);
        laneUserScratch[laneId].add(userId);
    }

    long stageLaneMutationFromScratch(long coreSequence) {
        LaneCommitEvent event = dispatchLaneMutationFromScratch(coreSequence);
        if (event == null) return 0;
        awaitLaneCommit(event, System.nanoTime() + LANE_COMMIT_TIMEOUT_NANOS);
        long laneMask = event.requiredLaneMask();
        releaseLaneCommit(event);
        return laneMask;
    }

    void awaitLaneCommit(LaneCommitEvent event, long deadlineNanos) {
        if (asynchronousCommandScope && !event.complete())
            throw new org.agrona.concurrent.AgentTerminationException(
                    new IllegalStateException("asynchronous command cannot await Lane commit"));
        int idle = 0;
        while (!event.complete()) {
            if ((idle++ & 1_023) == 0) {
                assertAccountLanesHealthy();
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
                    // Operational failure: never recycle the event or publish an unfinished commit.
                    throw new IllegalStateException("Account Lane commit interrupted or timed out");
                }
            }
            if (idle <= LANE_COMPLETION_SPINS) Thread.onSpinWait();
            else Thread.yield();
        }
        assertAccountLanesHealthy();
    }

    LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence) {
        long laneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    publishedLaneCommittedSequences[laneId], dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        if (laneMask == 0) return null;
        LaneCommitEvent event = laneCommitEventPool.pollFirst();
        if (event == null) event = new LaneCommitEvent(accountLanes.length);
        event.prepare(coreSequence, laneMask, laneUserScratch, this);
        if (!accountLanesStarted || ownerLaneAccess) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((laneMask & 1L << laneId) == 0) continue;
                event.execute(accountLanes[laneId]);
                dispatchedLaneCommitSequences[laneId] = coreSequence;
            }
            return event;
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & 1L << laneId) != 0 && !laneWorkers[laneId].hasCapacity()) {
                event.discard();
                laneCommitEventPool.addFirst(event);
                throw new java.util.concurrent.RejectedExecutionException("Account Lane commit queue is full");
            }
        }
        long submittedMask = 0;
        try {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                long laneBit = 1L << laneId;
                if ((laneMask & laneBit) == 0) continue;
                accountLaneQueueHighWaterMarks[laneId] = Math.max(
                        accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
                if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
                dispatchedLaneCommitSequences[laneId] = coreSequence;
                submittedMask |= laneBit;
            }
        } catch (RuntimeException failure) {
            if (submittedMask != 0) {
                throw new IllegalStateException("partial Account Lane commit dispatch", failure);
            }
            event.discard();
            laneCommitEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public boolean laneCommitComplete(LaneCommitEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        assertAccountLanesHealthy();
        return event.complete();
    }

    public void releaseLaneCommit(LaneCommitEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.clear();
        laneCommitEventPool.addFirst(event);
    }

    public PlaceAdmissionEvent dispatchPlaceAdmission(
            long coreSequence, long userId, ResolvedPlaceOrder order, java.util.UUID commandId,
            long openInterestSteps, RuntimeOrderAdmission.AdmissionIdentity identity,
            RuntimeIdentityRegistry.PreparedClientKey preparedClientKey, int symbolId, int assetId) {
        assertOwner();
        if (!accountLanesStarted) {
            throw new IllegalStateException("asynchronous place admission requires Account Lane workers");
        }
        int laneId = topology.accountLaneId(userId);
        PlaceAdmissionEvent event = placeAdmissionEventPool.pollFirst();
        if (event == null) event = new PlaceAdmissionEvent();
        event.prepare(
                coreSequence, userId, order, commandId, openInterestSteps, identity,
                preparedClientKey, symbolId, assetId, laneId, this);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
        return event;
    }

    public void releasePlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        placeAdmissionEventPool.addFirst(event);
    }

    public PlaceBatchAdmissionEvent dispatchPlaceBatchAdmission(
            long coreSequence, long userId, java.util.UUID commandId,
            ResolvedPlaceOrder[] orders, long[] openInterestSteps,
            RuntimeOrderAdmission.AdmissionIdentity[] admissionIdentities,
            RuntimeIdentityRegistry.PreparedClientKey[] clientKeys, int[] symbolIds, int[] assetIds,
            CoreMatchingOrder[] matchingOrders,
            OrderRuntime[] admittedOrders, ReservationRuntime[] admittedReservations, int itemCount) {
        assertOwner();
        if (!accountLanesStarted) {
            throw new IllegalStateException("asynchronous place batch admission requires Account Lane workers");
        }
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        changes.ensureAdmissionCapacity(laneId, itemCount);
        PlaceBatchAdmissionEvent event = placeBatchAdmissionEventPool.pollFirst();
        if (event == null) event = new PlaceBatchAdmissionEvent();
        event.prepare(coreSequence, userId, commandId, orders, openInterestSteps, admissionIdentities,
                clientKeys, symbolIds, assetIds, matchingOrders, admittedOrders,
                admittedReservations, itemCount, laneId, this, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        try {
            if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
        } catch (RuntimeException | Error failure) {
            releaseMatcherSettlementChanges(event.discardChanges());
            event.clear();
            placeBatchAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void stagePlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        long userId = event.userId();
        int laneId = topology.accountLaneId(userId);
        publishedUsers.put(userId, event.admittedUser());
        int nextTotal = pendingReservations.totalPendingReservations;
        for (int index = 0; index < event.itemCount(); index++) {
            OrderRuntime order = event.admittedOrder(index);
            ReservationRuntime reservation = event.admittedReservation(index);
            long orderId = order.orderId();
            if (pendingReservations.pendingReservationUsers.containsKey(orderId)) {
                throw new IllegalStateException("place batch admission was collected twice");
            }
            publishedOrders.put(orderId, order);
            publishedReservations.put(orderId, reservation);
            orderLaneIds.put(orderId, laneId + 1L);
            reservationLaneIds.put(orderId, laneId + 1L);
            nextTotal = Math.incrementExact(nextTotal);
            pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(), nextTotal);
        }
        revision = Math.addExact(revision, event.itemCount());
    }

    public void collectPlaceBatchAdmission(
            PlaceBatchAdmissionEvent event, RuntimeFundsAccumulator fundsAccumulator) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        int laneId = topology.accountLaneId(event.userId());
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.publishedLaneChanges[laneId].commitTerminalToOwner(
                    this, laneId, null, event.coreSequence());
            LaneBalancePatches balances = changes.balancePatches[laneId];
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                changedUsers.add(balances.userId(index));
                changedBalance(balances.userId(index), balances.assetId(index));
            }
            if (fundsAccumulator != null) changes.appendFundsDelta(1L << laneId, fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    public void discardPlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        releaseMatcherSettlementChanges(event.takeChanges());
    }

    public void releasePlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        placeBatchAdmissionEventPool.addFirst(event);
    }

    void rollbackPlaceBatchAdmissionInLane(
            AccountLaneState lane, long userId, long coreSequence, ResolvedPlaceOrder[] orders,
            RuntimeIdentityRegistry.PreparedClientKey[] clientKeys,
            ReservationRuntime[] reservations, int admittedCount, UserRuntime userBefore) {
        lane.assertOwner();
        for (int index = admittedCount - 1; index >= 0; index--) {
            long orderId = orders[index].orderId();
            ReservationRuntime reservation = reservations[index];
            lane.completePendingReservation(orderId, coreSequence);
            if (clientKeys[index].key() != 0) {
                removeClientOrderIndex(lane, userId, clientKeys[index].key());
            }
            lane.reservations.remove(orderId);
            removeUserEntity(lane.reservationIdsByUser, userId, orderId);
            lane.removeOrder(orderId);
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("batch admission rollback balance is missing");
            balance.release(reservation.reservedUnits());
        }
        if (userBefore != null) lane.users.put(userId, userBefore);
    }

    void publishPlaceAdmissionReady(int laneId, long coreSequence) {
        placeAdmissionReadyQueues[laneId].publish(coreSequence);
    }

    public long takePlaceAdmissionReadyLaneMask() {
        assertOwner();
        return readyLaneMask(placeAdmissionReadyQueues);
    }

    /** Read-only SPSC cursor probe. A later publication is observed on the next owner poll. */
    public boolean hasMatchingNotifications() {
        assertOwner();
        for (int lane = 0; lane < accountLanes.length; lane++) {
            if (placeAdmissionReadyQueues[lane].hasPending()
                    || matcherSettlementReadyQueues[lane].hasPending()) return true;
        }
        return false;
    }

    public long pollPlaceAdmissionReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= placeAdmissionReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        return placeAdmissionReadyQueues[laneId].poll();
    }

    void publishMatcherSettlementReady(int laneId, long coreSequence) {
        matcherSettlementReadyQueues[laneId].publish(coreSequence);
    }

    public long takeMatcherSettlementReadyLaneMask() {
        assertOwner();
        return readyLaneMask(matcherSettlementReadyQueues);
    }

    public long pollMatcherSettlementReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= matcherSettlementReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        return matcherSettlementReadyQueues[laneId].poll();
    }

    static long readyLaneMask(LaneSequenceQueue[] queues) {
        long mask = 0;
        for (int laneId = 0; laneId < queues.length; laneId++) {
            if (queues[laneId].hasPending()) mask |= 1L << laneId;
        }
        return mask;
    }

    public CoreMatchingOrder collectPlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        long orderId = event.orderId();
        long userId = event.userId();
        if (pendingReservations.pendingReservationUsers.containsKey(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        int laneId = topology.accountLaneId(userId);
        publishedUsers.put(userId, event.admittedUser());
        publishedOrders.put(orderId, event.admittedOrder());
        publishedReservations.put(orderId, event.admittedReservation());
        orderLaneIds.put(orderId, laneId + 1L);
        reservationLaneIds.put(orderId, laneId + 1L);
        pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(pendingReservations.totalPendingReservations));
        revision = Math.incrementExact(revision);
        return event.matchingOrder();
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(MatcherSettlementEvent event) {
        return collectMatcherSettlement(event, null, null);
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        RuntimeTreasuryDelta aggregate = event.collectTreasuryDelta();
        for (int index = 0; index < event.planCount(); index++) {
            unindexMatcherPendingReservations(event.plan(index));
            // Lane 不修改全局版本；全部结算成功后由 owner 合并一次触发终态变更。
            if (event.plan(index).completedTrigger() != null) revision = Math.incrementExact(revision);
        }
        long laneMask = event.requiredLaneMask();
        MatcherSettlementChanges changes = event.commitSequence() != 0 || event.hasIsolatedChanges()
                ? event.takeChanges() : null;
        try {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                if ((laneMask & 1L << laneId) != 0) {
                    if (changes == null) flushPublishedChanges(laneId);
                    else {
                        changes.publishedLaneChanges[laneId].commitTerminalToOwner(
                                this, laneId, terminalOrderSink, event.plan().coreSequence());
                        changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
                        LaneBalancePatches balances = changes.balancePatches[laneId];
                        for (int index = 0; index < balances.size(); index++) {
                            balances.publishAvailableAt(this, index);
                            changedUsers.add(balances.userId(index));
                            changedBalance(balances.userId(index), balances.assetId(index));
                        }
                    }
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            if (changes != null) {
                if (fundsAccumulator == null) event.collectedFundsDelta(changes.collectFundsDelta(laneMask));
                else changes.appendFundsDelta(laneMask, fundsAccumulator);
            }
            return aggregate;
        } finally {
            if (changes != null) releaseMatcherSettlementChanges(changes);
        }
    }

    public LaneCancelEvent dispatchCancel(
            long coreSequence, long userId, long orderId, long commitTimestamp, long commitClusterPosition) {
        return dispatchCancel(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition, null);
    }

    public LaneCancelEvent dispatchCancel(
            long coreSequence, long userId, long orderId, long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!accountLanesStarted) throw new IllegalStateException("asynchronous cancel requires Account Lanes");
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        try {
            if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
        } catch (RuntimeException | Error failure) {
            event.discard();
            releaseMatcherSettlementChanges(changes);
            laneCancelEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities) {
        return dispatchCancelBatch(coreSequence, userId, orderIds, commitTimestamp,
                commitClusterPosition, identities, false);
    }

    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities, boolean commitLane) {
        assertOwner();
        if (!accountLanesStarted || orderIds == null || orderIds.length == 0) {
            throw new IllegalStateException("asynchronous cancel batch requires Account Lanes and orders");
        }
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderIds, orderIds.length,
                commitTimestamp, commitClusterPosition, laneId, commitLane, this, identities, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        try {
            if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
        } catch (RuntimeException | Error failure) {
            event.discard();
            releaseMatcherSettlementChanges(changes);
            laneCancelEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void collectCancel(LaneCancelEvent event, RuntimeFundsAccumulator fundsAccumulator,
                              TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("cancel event is incomplete");
        int laneId = event.laneId();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.publishedLaneChanges[laneId].commitTerminalToOwner(
                    this, laneId, terminalOrderSink, event.coreSequence());
            changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
            LaneBalancePatches balances = changes.balancePatches[laneId];
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                changedUsers.add(balances.userId(index));
                changedBalance(balances.userId(index), balances.assetId(index));
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            if (fundsAccumulator != null) changes.appendFundsDelta(event.requiredLaneMask(), fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    public void releaseCancel(LaneCancelEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("cancel event is required");
        event.clear();
        laneCancelEventPool.addFirst(event);
    }

    public LaneReplaceEvent dispatchReplace(
            long coreSequence, long userId, long originalOrderId, long[] preCancelOrderIds,
            ResolvedPlaceOrder replacement,
            java.util.UUID commandId, long requiredReservation, long clientKey, int symbolId, int assetId,
            long commitTimestamp, long commitClusterPosition, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (!accountLanesStarted) throw new IllegalStateException("asynchronous replace requires Account Lanes");
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges();
        LaneReplaceEvent event = laneReplaceEventPool.pollFirst();
        if (event == null) event = new LaneReplaceEvent();
        event.prepare(coreSequence, userId, originalOrderId, preCancelOrderIds, replacement, commandId,
                requiredReservation, clientKey, symbolId, assetId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        accountLaneQueueHighWaterMarks[laneId] = Math.max(
                accountLaneQueueHighWaterMarks[laneId], laneWorkers[laneId].depth() + 1);
        if (ownerLaneAccess) event.execute(accountLanes[laneId]);
            else laneWorkers[laneId].submit(event);
        return event;
    }

    public void collectReplace(LaneReplaceEvent event, RuntimeFundsAccumulator fundsAccumulator,
                               TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("replace event is incomplete");
        int laneId = event.laneId();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.publishedLaneChanges[laneId].commitTerminalToOwner(
                    this, laneId, terminalOrderSink, event.coreSequence());
            changes.publishedLaneChanges[laneId].releaseRetiredClientIdentities(event.identities());
            LaneBalancePatches balances = changes.balancePatches[laneId];
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                changedUsers.add(balances.userId(index));
                changedBalance(balances.userId(index), balances.assetId(index));
            }
            pendingReservations.indexPendingReservation(event.userId(), event.replacementOrderId(), event.coreSequence(),
                    Math.incrementExact(pendingReservations.totalPendingReservations));
            if (fundsAccumulator != null) changes.appendFundsDelta(event.requiredLaneMask(), fundsAccumulator);
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    public void releaseReplace(LaneReplaceEvent event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("replace event is required");
        event.clear();
        laneReplaceEventPool.addFirst(event);
    }

    public OrderRuntime changedOrderValue(long orderId) {
        assertOwner();
        return changedOrders.get(orderId);
    }

    @FunctionalInterface
    public interface TerminalOrderSink {
        void accept(OrderRuntime order, long coreSequence);

        default void completeSequence() {
        }
    }

    LongLongHashMap matcherSettlementRemainingScratch() {
        assertOwner();
        return matcherSettlementRemainingScratch;
    }

    LongHashSet matcherSettlementOrderScratch() {
        assertOwner();
        matcherSettlementOrderScratch.clear();
        return matcherSettlementOrderScratch;
    }

    void completeMatcherPendingReservations(AccountLaneState lane, MatcherSettlementPlan plan) {
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequences.getIfAbsent(orderId, 0) != plan.coreSequence()) continue;
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            captureUserBefore(reservation.userId());
            captureOrderBefore(orderId);
            captureReservationBefore(orderId);
            captureBalanceBefore(reservation.userId(), reservation.assetId());
            lane.clientKeysByOrderId.forEach(orderId,
                    clientKey -> captureClientOrderBefore(reservation.userId(), clientKey));
            lane.completePendingReservation(orderId, plan.coreSequence());
            captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
        }
    }

    void stampMatcherOrders(AccountLaneState lane, MatcherSettlementPlan plan,
                            long timestamp, long clusterPosition) {
        for (int index = 0; index < plan.orderCount(); index++) {
            OrderRuntime order = lane.orders.get(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
        }
    }

    void stampOrderInLane(AccountLaneState lane, long orderId, long timestamp, long clusterPosition) {
        if (lane == null || laneCommandScope.get() != lane || matcherSettlementChangesScope.get() == null) {
            throw new IllegalStateException("order stamp must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.orders.get(orderId);
        if (order != null && (order.updatedAtEpochMillis() != timestamp
                || order.clusterPosition() != clusterPosition)) {
            replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
        }
    }

    void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        if (!pendingReservations.pendingReservationsBySequence.containsKey(plan.coreSequence())) return;
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (!pendingReservations.pendingReservationsBySequence.contains(plan.coreSequence(), orderId)) continue;
            long userId = pendingReservations.pendingReservationUsers.getOrDefault(orderId, 0);
            if (userId == 0) throw new IllegalStateException("matcher pending reservation owner is missing");
            pendingReservations.unindexPendingReservation(orderId, plan.coreSequence(), userId,
                    Math.subtractExact(pendingReservations.totalPendingReservations, 1));
        }
    }

    void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        changedUsers.add(userId);
        changedBalance(userId, assetId);
        changedPosition(positionKey);
    }

    public java.util.List<AccountLaneView> accountLaneViews(long laneMask) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((laneMask & ~validMask) != 0) throw new IllegalArgumentException("invalid account lane mask");
        java.util.List<AccountLaneView> selected = new java.util.ArrayList<>(Long.bitCount(laneMask));
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((laneMask & (1L << laneId)) == 0) continue;
            selected.add(accountLaneById(laneId));
        }
        return java.util.List.copyOf(selected);
    }

    void rebuildAccountLaneHashes() {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            lane.rebuildLocalHashes();
            publishLaneHashes(lane);
        }
    }

    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        pendingReservations.assertPendingReservationCounts();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.requireSnapshot(fenceSequence);
                return null;
            });
        }
        java.util.List<AccountLaneSnapshot> snapshots = new java.util.ArrayList<>(accountLanes.length);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            snapshots.add(onLane(currentLaneId, lane -> lane.snapshot(fenceSequence)));
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            AccountLaneView view = accountLaneById(snapshot.laneId());
            if (view.revision() != snapshot.revision()
                    || view.appliedSequence() != snapshot.appliedSequence()
                    || view.committedSequence() != snapshot.committedSequence()
                    || view.localStateHash() != snapshot.localStateHash()
                    || view.localFundsHash() != snapshot.localFundsHash()) {
                throw new IllegalStateException("account lane changed during snapshot capture");
            }
        }
        return java.util.List.copyOf(snapshots);
    }

    public void requireSnapshotFenceReady() {
        assertOwner();
        if (controlLanes.pending()) throw new IllegalStateException("control Lane work is unfinished");
        pendingReservations.assertPendingReservationCounts();
        if (pendingReservations.totalPendingReservations != 0 || !pendingReservations.pendingReservationsBySequence.isEmpty()
                || !pendingReservations.pendingReservationUsers.isEmpty() || snapshotProjectionStateDirty()) {
            throw new IllegalStateException("runtime contains unfinished reservation or patch work");
        }
    }

    /** 包含尚未提升 revision 的 Lane 修改，失败时也必须回滚。 */
    public boolean hasUncommittedCommandChanges() {
        assertOwner();
        return snapshotProjectionStateDirty() || !treasury.changedAssets().isEmpty()
                || !treasury.changedFundingSymbols().isEmpty() || !treasury.changedLifecycleSymbols().isEmpty();
    }

    boolean snapshotProjectionStateDirty() {
        return !changedUsers.isEmpty() || !changedBalances.isEmpty() || !changedOrders.isEmpty()
                || !changedReservations.isEmpty() || !changedPositions.isEmpty()
                || !changedLiquidations.isEmpty() || !changedMarkPrices.isEmpty()
                || !changedRiskSnapshots.isEmpty() || !changedRiskScans.isEmpty()
                || !changedInstruments.isEmpty() || !changedLeverages.isEmpty()
                || !changedAlgoOrders.isEmpty() || !changedCancelAllAfterTimers.isEmpty()
                || !changedTriggerOrders.isEmpty() || !changedFeePolicies.isEmpty()
                || hasCapturedUsers() || hasCapturedBalances()
                || hasCaptured(patchReservationsBeforeByLane) || hasCaptured(patchOrdersBeforeByLane)
                || !patchLiquidationsBefore.isEmpty() || !patchRiskSnapshotsBefore.isEmpty()
                || !patchLeveragesBefore.isEmpty() || !patchAlgoOrdersBefore.isEmpty()
                || !patchTriggerOrdersBefore.isEmpty() || hasCapturedClientOrders()
                || !patchTimersBefore.isEmpty() || !patchMarkPricesBefore.isEmpty()
                || !patchRiskScansBefore.isEmpty() || !patchInstrumentsBefore.isEmpty()
                || !patchPendingTransfersBefore.isEmpty() || !patchFeePoliciesBefore.isEmpty()
                || patchMarketRevisionChanged || patchNextLiquidationIdChanged || patchRiskScanControlChanged;
    }

    boolean hasCapturedUsers() {
        for (LaneLongCaptures<UserRuntime> captured : patchUsersBeforeByLane) {
            if (!captured.isEmpty()) return true;
        }
        return false;
    }

    static boolean hasCaptured(LaneLongCaptures<?>[] capturedByLane) {
        for (LaneLongCaptures<?> captured : capturedByLane) if (!captured.isEmpty()) return true;
        return false;
    }

    boolean hasCapturedBalances() {
        for (LaneBalancePatches captured : patchBalancesBeforeByLane) {
            if (captured.size() != 0) return true;
        }
        return false;
    }

    boolean hasCapturedClientOrders() {
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) {
            if (captured.size() != 0) return true;
        }
        return false;
    }

    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, topology);
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            for (Long userId : snapshot.userIds()) {
                if (!onLane(laneId, lane -> lane.users.get(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.users.forEachKey(userId -> {
                    if (!lane.owns(userId)) {
                        throw new IllegalArgumentException("runtime user is absent from its account lane");
                    }
                });
                return null;
            });
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            onLane(snapshot.laneId(), lane -> {
                lane.restore(snapshot);
                publishLaneHashes(lane);
                return null;
            });
        }
        pendingReservations.pendingReservationsBySequence.clear();
        pendingReservations.pendingReservationUsers.clear();
        pendingReservations.pendingReservationCountsByUser.clear();
        pendingReservations.totalPendingReservations = 0;
        pendingReservations.assertPendingReservationCounts();
    }

    public static void validateAccountLaneSnapshotManifest(
            java.util.List<AccountLaneSnapshot> snapshots,
            long fenceSequence,
            TradingCoreState globalState,
            LaneTopology topology) {
        if (snapshots == null || topology == null || snapshots.size() != topology.accountLaneCount()
                || globalState == null || fenceSequence < 0) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        @SuppressWarnings("unchecked")
        java.util.TreeSet<Long>[] expectedUsers = new java.util.TreeSet[topology.accountLaneCount()];
        for (int laneId = 0; laneId < expectedUsers.length; laneId++) {
            expectedUsers[laneId] = new java.util.TreeSet<>();
        }
        globalState.users().keySet().forEach(userId ->
                expectedUsers[topology.accountLaneId(userId)].add(userId));
        boolean[] present = new boolean[topology.accountLaneCount()];
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= present.length || present[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            java.util.TreeSet<Long> actualUsers = new java.util.TreeSet<>(snapshot.userIds());
            if (actualUsers.size() != snapshot.userIds().size()
                    || actualUsers.stream().anyMatch(userId -> topology.accountLaneId(userId) != laneId)) {
                throw new IllegalArgumentException("account lane contains an incorrectly routed user");
            }
            if (!expectedUsers[laneId].equals(actualUsers)) {
                throw new IllegalArgumentException("account lane user manifest differs from global state");
            }
            present[laneId] = true;
        }
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == current) return;
        if (owner == null) {
            owner = current;
            return;
        }
        if (laneCommandScope.get() != null) return;
        bindOwner();
    }

    public void releaseOwnerForHandoff() {
        if (accountLanesStarted) throw new IllegalStateException("account lanes must be closed before handoff");
        for (AccountLaneState lane : accountLanes) lane.releaseOwnerForHandoff();
        treasury.releaseOwnerForHandoff();
        owner = null;
    }

    public ProductLine productLine() {
        assertOwner();
        return productLine;
    }

    public long revision() {
        assertOwner();
        return revision;
    }

    public long commandRevisionCheckpoint() {
        assertOwner();
        return revision;
    }

    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("order batch mutation scope is already active");
        if (snapshotProjectionStateDirty() || !treasury.changedAssets().isEmpty()
                || !treasury.changedFundingSymbols().isEmpty()
                || !treasury.changedLifecycleSymbols().isEmpty()) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        orderBatchMutationScope = true;
        treasury.beginOrderBatchMutationScope();
    }

    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!orderBatchMutationScope) return;
        treasury.endOrderBatchMutationScope();
        orderBatchMutationScope = false;
    }

    void rejectUnsupportedOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        // An aborted business patch may have staged provisional private values. Drop the outer
        // realtime batch; authoritative snapshots heal it. Reliable replay captures committed fills only.
        if (realtimeCapture != null && realtimeCapture.privateStateEnabled()) realtimeCapture.abort();
        if (revisionCheckpoint < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (pendingReservations.pendingReservationsBySequence.containsKey(coreSequence)) {
            pendingReservations.completePendingReservations(coreSequence);
        }
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) {
            for (int index = 0; index < captured.size(); index++) {
                long userId = captured.userId(index);
                long clientKey = captured.clientKey(index);
                Long beforeOrderId = captured.beforeOrderId(index);
                onLane(userId, lane -> {
                    removeClientOrderIndex(lane, userId, clientKey);
                    if (beforeOrderId != null) {
                        putClientOrderIndex(lane, userId, clientKey, beforeOrderId);
                    }
                    return null;
                });
            }
        }
        for (LaneLongCaptures<PatchOrderBefore> capturedOrders : patchOrdersBeforeByLane) {
            for (int index = 0; index < capturedOrders.size(); index++) {
                rollbackOrder(capturedOrders.key(index), capturedOrders.value(index).value());
            }
        }
        for (LaneLongCaptures<PatchReservationBefore> capturedReservations : patchReservationsBeforeByLane) {
            for (int index = 0; index < capturedReservations.size(); index++) {
                long orderId = capturedReservations.key(index);
                PatchReservationBefore captured = capturedReservations.value(index);
                if (captured.pending()) {
                    throw new IllegalStateException("order batch overlapped an existing pending reservation");
                }
                rollbackReservation(orderId, captured.value());
            }
        }
        for (long positionKey : changedPositions.toArray()) rollbackPosition(positionKey);
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) {
            for (int index = 0; index < capturedBalances.size(); index++) {
                rollbackBalance(capturedBalances.userId(index), capturedBalances.assetId(index),
                        capturedBalances.before(index));
            }
        }
        for (LaneLongCaptures<UserRuntime> capturedUsers : patchUsersBeforeByLane) {
            for (int index = 0; index < capturedUsers.size(); index++) {
                rollbackUser(capturedUsers.key(index), capturedUsers.value(index));
            }
        }
        rollbackCommandGlobals();
        treasury.rollbackChangedValues();
        revision = revisionCheckpoint;
        clearChangedKeys();
    }

    void rollbackCommandGlobals() {
        patchLiquidationsBefore.forEach((id, before) -> {
            LiquidationRuntime current = liquidation(id);
            if (current != null) onLane(current.userId(), lane -> {
                lane.liquidations.remove(id);
                removeActiveLiquidation(lane, current);
                return null;
            });
            LiquidationRuntime restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.liquidations.put(id, restored);
                indexActiveLiquidation(lane, restored);
                return null;
            });
            putOrRemove(publishedLiquidations, id, restored);
        });
        patchRiskSnapshotsBefore.forEach((key, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int id = laneId;
                onLane(id, lane -> { lane.riskSnapshots.remove(key); return null; });
            }
            RiskSnapshotRuntime restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.riskSnapshots.put(key, restored);
                return null;
            });
            putOrRemove(publishedRiskSnapshots, key, restored);
        });
        patchLeveragesBefore.forEach((key, before) -> onLane(key.userId(), lane -> {
            if (before.value() == null) {
                lane.leverages.remove(key);
                Set<CoreLeverageKey> keys = lane.leverageKeysByUser.get(key.userId());
                if (keys != null) {
                    keys.remove(key);
                    if (keys.isEmpty()) lane.leverageKeysByUser.remove(key.userId());
                }
            } else {
                lane.leverages.put(key, before.value());
                lane.leverageKeysByUser.getIfAbsentPut(key.userId(), HashSet::new).add(key);
            }
            return null;
        }));
        patchAlgoOrdersBefore.forEach((id, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int laneIndex = laneId;
                onLane(laneIndex, lane -> { lane.algoOrders.remove(id); return null; });
            }
            CoreAlgoOrderState restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.algoOrders.put(id, restored);
                return null;
            });
        });
        patchTriggerOrdersBefore.forEach((id, before) -> {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                int laneIndex = laneId;
                onLane(laneIndex, lane -> { lane.removeTrigger(id); return null; });
            }
            CoreTriggerOrderState restored = before.value();
            if (restored != null) onLane(restored.userId(), lane -> {
                lane.putTrigger(restored);
                return null;
            });
            putOrRemove(publishedTriggerOrders, id, restored);
        });
        patchTimersBefore.forEach((key, before) -> putOrRemove(cancelAllAfterTimers, key, before.value()));
        patchMarkPricesBefore.forEach((id, before) -> putOrRemove(markPrices, id, before.value()));
        patchRiskScansBefore.forEach((id, before) -> putOrRemove(riskScans, id, before.value()));
        patchInstrumentsBefore.forEach((symbol, before) -> putOrRemove(instruments, symbol, before.value()));
        patchPendingTransfersBefore.forEach((id, before) -> putOrRemove(pendingTransfers, id, before.value()));
        patchFeePoliciesBefore.forEach((id, before) -> putOrRemove(feePolicies, id, before.value()));
        if (patchNextLiquidationIdChanged) nextLiquidationId = patchNextLiquidationIdBefore;
        if (patchMarketRevisionChanged) marketRevision = patchMarketRevisionBefore;
        if (patchRiskScanControlChanged) riskScanControl = patchRiskScanControlBefore;
    }

    static <K, V> void putOrRemove(Map<K, V> values, K key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    static <V> void putOrRemove(IntObjectHashMap<V> values, int key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    public void setMetadata(ProductLine productLine, long revision) {
        assertOwner();
        if (productLine == null || revision < 0) throw new IllegalArgumentException("invalid runtime metadata");
        this.productLine = productLine;
        this.revision = revision;
    }

    public CoreRiskScanControlView riskScanControl() {
        assertOwner();
        return riskScanControl;
    }

    public void setRiskScanControl(CoreRiskScanControlView riskScanControl) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan control");
        if (riskScanControl == null) throw new IllegalArgumentException("risk scan control is required");
        if (!patchRiskScanControlChanged) {
            patchRiskScanControlBefore = this.riskScanControl;
            patchRiskScanControlChanged = true;
        }
        this.riskScanControl = riskScanControl;
    }

    public UserRuntime user(long userId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        return scoped == null ? publishedUsers.get(userId) : scoped.users.get(userId);
    }

    public UserRuntime requireUser(long userId) {
        UserRuntime user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    public BalanceRuntime balance(long userId, int assetId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            IntObjectHashMap<BalanceRuntime> balances = scoped.balances.get(userId);
            return balances == null ? null : balances.get(assetId);
        }
        return onLane(userId, lane -> copyBalance(lane.balances.get(userId), assetId));
    }

    public long publishedAvailableBalance(long userId, int assetId) {
        assertOwner();
        IntLongHashMap balances = publishedAvailableBalances.get(userId);
        return balances == null ? Long.MIN_VALUE : balances.getIfAbsent(assetId, Long.MIN_VALUE);
    }

    IntObjectHashMap<BalanceRuntime> balancesForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> copyBalances(lane.balances.get(userId)));
    }

    long nextRiskReservationId(long userId, long cursor) {
        AccountLaneState lane = riskCursorLane(userId);
        LongHashSet ids = lane.reservationIdsByUser.get(userId);
        long selected = 0;
        if (ids != null) {
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                long id = iterator.next();
                if (id > cursor && (selected == 0 || id < selected)) selected = id;
            }
        }
        return selected;
    }

    long nextRiskPositionKey(long userId, String cursor, RuntimeIdentityRegistry identities) {
        AccountLaneState lane = riskCursorLane(userId);
        LongHashSet ids = lane.positionKeysByUser.get(userId);
        long selected = 0;
        String selectedIdentity = null;
        if (ids != null) {
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                long id = iterator.next();
                String identity = identities.positionKey(userId, id);
                if (!"-".equals(cursor) && identity.compareTo(cursor) <= 0) continue;
                if (selectedIdentity == null || identity.compareTo(selectedIdentity) < 0) {
                    selected = id;
                    selectedIdentity = identity;
                }
            }
        }
        return selected;
    }

    AccountLaneState riskCursorLane(long userId) {
        assertOwner();
        AccountLaneState lane = laneCommandScope.get();
        if (lane == null && !accountLanesStarted) {
            // Recovery/projector execution is owner-confined until worker ownership is handed off.
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("risk cursor must run in the user's Lane");
        }
        return lane;
    }

    LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet orderIds = lane.reservationIdsByUser.get(userId);
            return orderIds == null ? new LongHashSet() : new LongHashSet(orderIds);
        });
    }

    int reservationCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet orderIds = lane.reservationIdsByUser.get(userId);
            return orderIds == null ? 0 : orderIds.size();
        });
    }

    LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet positionKeys = lane.positionKeysByUser.get(userId);
            return positionKeys == null ? new LongHashSet() : new LongHashSet(positionKeys);
        });
    }

    int positionCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongHashSet positionKeys = lane.positionKeysByUser.get(userId);
            return positionKeys == null ? 0 : positionKeys.size();
        });
    }

    NavigableSet<CoreLeverageKey> leverageKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            Set<CoreLeverageKey> keys = lane.leverageKeysByUser.get(userId);
            return keys == null ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
        });
    }

    public OrderRuntime order(long orderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.orders.get(orderId);
        return publishedOrders.get(orderId);
    }

    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.reservations.get(orderId);
        return publishedReservations.get(orderId);
    }

    static int indexedLane(Long2LongHashMap laneIds, long entityId) {
        long encodedLaneId = laneIds.getOrDefault(entityId, 0);
        return encodedLaneId == 0 ? -1 : Math.toIntExact(encodedLaneId - 1);
    }

    public PositionRuntime position(long positionKey) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.positions.get(positionKey);
        return publishedPositions.get(positionKey);
    }

    public NavigableSet<Long> positionKeysForUserAndSymbol(long userId, int symbolId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(symbolId);
            LongHashSet keys = byUser == null ? null : byUser.get(userId);
            return keys == null ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(toSortedSet(keys));
        });
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.liquidations.get(liquidationId);
        return publishedLiquidations.get(liquidationId);
    }

    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        return onLane(userId, lane -> {
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(userId);
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidations.get(liquidationId);
        });
    }

    public boolean hasActiveLiquidationConflict(long userId, int symbolId, long excludedLiquidationId) {
        return hasLiquidationConflict(userId,symbolId,excludedLiquidationId,false);
    }

    public boolean hasUnresolvedLiquidation(int symbolId) {
        return hasLiquidationConflict(0,symbolId,0,true);
    }

    boolean hasLiquidationConflict(long userId, int symbolId, long excludedLiquidationId, boolean includeDebt) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (userId != 0 && laneId != topology.accountLaneId(userId)) continue;
            boolean conflict = onLane(laneId, lane -> {
                boolean[] found = new boolean[1];
                lane.liquidations.forEachValue(liquidation -> {
                if (!found[0] && liquidation.liquidationId() != excludedLiquidationId
                        && (liquidation.status() == CoreLiquidationState.Status.PLANNED
                        || liquidation.status() == CoreLiquidationState.Status.ORDERED
                        || includeDebt && liquidation.status() != CoreLiquidationState.Status.COMPLETED
                            && liquidation.status() != CoreLiquidationState.Status.CANCELED)
                        && (userId == 0 || liquidation.userId() == userId)
                        && (symbolId < 0 || liquidation.symbolId() == symbolId)) {
                    found[0] = true;
                }
            });
                return found[0];
            });
            if (conflict) return true;
        }
        return false;
    }

    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.riskSnapshots.get(positionKey);
        return publishedRiskSnapshots.get(positionKey);
    }

    public RiskScanRuntime riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    public int publishedPositionCount() {
        assertOwner();
        return publishedPositions.size();
    }

    public RiskScanRuntime firstIncompleteRiskScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachKeyValue((symbolId, scan) -> {
            if (!scan.complete()
                    && (selected[0] == null || symbolId < selected[0].symbolId())) {
                selected[0] = scan;
            }
        });
        return selected[0];
    }

    public RiskScanRuntime firstRiskIncompleteScan() {
        assertOwner();
        RiskScanRuntime[] selected = new RiskScanRuntime[1];
        riskScans.forEachValue(scan -> {
            if (!scan.riskComplete() && (selected[0] == null
                    || compareRiskProgress(scan, selected[0]) < 0)) selected[0] = scan;
        });
        return selected[0];
    }

    static int compareRiskProgress(RiskScanRuntime left, RiskScanRuntime right) {
        int scheduled = Long.compare(left.lastScheduledRevision(), right.lastScheduledRevision());
        return scheduled != 0 ? scheduled : Integer.compare(left.symbolId(), right.symbolId());
    }

    public int incompleteRiskScanCount() {
        assertOwner();
        int[] count = new int[1];
        riskScans.forEachValue(scan -> {
            if (!scan.complete()) count[0]++;
        });
        return count[0];
    }

    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    public TreasuryRuntime treasury() {
        assertOwner();
        if (laneCommandScope.get() != null) {
            throw new IllegalStateException("Account Lane cannot mutate Sequencer-owned Treasury");
        }
        return treasury;
    }

    public CoreInstrumentState instrument(String symbol) {
        assertOwner();
        CoreInstrumentState known = symbol == null ? null : instruments.get(symbol);
        if (known != null) return known;
        return instruments.get(OrderReservation.normalizeSymbol(symbol));
    }

    void putInstrument(CoreInstrumentState instrument) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument state");
        if (instrument == null) {
            throw new IllegalArgumentException("invalid runtime instrument");
        }
        patchInstrumentsBefore.computeIfAbsent(instrument.symbol(),
                symbol -> new PatchBefore<>(instruments.get(symbol)));
        instruments.put(instrument.symbol(), instrument);
        changedInstruments.add(instrument.symbol());
    }

    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), lane -> lane.leverages.get(key));
    }

    void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("leverage state");
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        patchLeveragesBefore.computeIfAbsent(key, value -> new PatchBefore<>(leverage(value)));
        onLane(key.userId(), lane -> {
            lane.leverages.put(key, leveragePpm);
            HashSet<CoreLeverageKey> userKeys = lane.leverageKeysByUser.get(key.userId());
            if (userKeys == null) {
                userKeys = new HashSet<>();
                lane.leverageKeysByUser.put(key.userId(), userKeys);
            }
            userKeys.add(key);
            return null;
        });
        changedLeverages.add(key);
        if (realtimeCapture != null) realtimeCapture.leverage(key,leveragePpm);
    }

    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            CoreAlgoOrderState order = onLane(laneId, lane -> lane.algoOrders.get(algoOrderId));
            if (order != null) return order;
        }
        return null;
    }

    void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        patchAlgoOrdersBefore.computeIfAbsent(algoOrder.algoOrderId(),
                id -> new PatchBefore<>(algoOrder(id)));
        onLane(algoOrder.userId(), lane -> lane.algoOrders.put(algoOrder.algoOrderId(), algoOrder));
        changedAlgoOrders.add(algoOrder.algoOrderId());
    }

    void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        patchAlgoOrdersBefore.computeIfAbsent(algoOrderId, id -> new PatchBefore<>(algoOrder(id)));
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> lane.algoOrders.remove(algoOrderId));
        }
        changedAlgoOrders.add(algoOrderId);
    }

    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        patchTimersBefore.computeIfAbsent(key, value -> new PatchBefore<>(cancelAllAfterTimers.get(value)));
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.triggerOrders.get(triggerOrderId);
        return publishedTriggerOrders.get(triggerOrderId);
    }

    void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        patchTriggerOrdersBefore.computeIfAbsent(triggerOrder.triggerOrderId(),
                id -> new PatchBefore<>(triggerOrder(id)));
        onLane(triggerOrder.userId(), lane -> {
            lane.putTrigger(triggerOrder);
            publishTriggerOrder(triggerOrder.triggerOrderId(), triggerOrder);
            return null;
        });
    }

    void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        CoreTriggerOrderState current = triggerOrder(triggerOrderId);
        patchTriggerOrdersBefore.computeIfAbsent(triggerOrderId, id -> new PatchBefore<>(current));
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            scoped.removeTrigger(triggerOrderId);
            publishTriggerOrder(triggerOrderId, null);
        } else if (current != null) {
            onLane(current.userId(), lane -> {
                lane.removeTrigger(triggerOrderId);
                publishTriggerOrder(triggerOrderId, null);
                return null;
            });
        } else {
            changedTriggerOrders.put(triggerOrderId, null);
        }
    }

    Map<String, CoreInstrumentState> instrumentsForRuntime() {
        assertOwner();
        return instruments;
    }

    Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new TreeMap<>(lane.leverages)));
        }
        return values;
    }

    Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.algoOrders.forEachKeyValue(values::put);
                return null;
            });
        }
        return values;
    }

    Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    boolean hasTriggerClient(long userId, String clientId) {
        return onLane(userId, lane -> {
            LongHashSet ids = lane.triggerIdsByUser.get(userId);
            if (ids == null) return false;
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                if (lane.triggerOrders.get(iterator.next()).clientTriggerOrderId().equals(clientId)) return true;
            }
            return false;
        });
    }

    long projectedTriggerCapacity(long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView view) {
        return onLane(userId, lane -> {
            long capacity = 0;
            long sameOcoMax = 0;
            LongHashSet ids = lane.triggerIdsByUser.get(userId);
            if (ids != null) {
                var iterator = ids.longIterator();
                while (iterator.hasNext()) {
                    CoreTriggerOrderState trigger = lane.triggerOrders.get(iterator.next());
                    if (!trigger.status().open() || !trigger.symbol().equals(view.symbol())
                            || trigger.marginMode() != view.marginMode()
                            || trigger.positionSide() != view.positionSide() || trigger.side() != view.side()) continue;
                    capacity = Math.addExact(capacity, trigger.quantitySteps());
                    if (!view.ocoGroupId().isEmpty() && view.ocoGroupId().equals(trigger.ocoGroupId())) {
                        sameOcoMax = Math.max(sameOcoMax, trigger.quantitySteps());
                    }
                }
            }
            return Math.addExact(Math.subtractExact(capacity, sameOcoMax),
                    Math.max(sameOcoMax, view.quantitySteps()));
        });
    }

    Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
            scoped.triggerOrders.forEachKeyValue(values::put);
            return values;
        }
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.triggerOrders.forEachKeyValue(values::put);
                return null;
            });
        }
        return values;
    }

    Map<Long, CoreFeePolicyState> feePoliciesForRuntime() {
        assertOwner();
        return feePolicies;
    }

    public TransferRuntime pendingTransfer(long transferId) {
        assertOwner();
        return pendingTransfers.get(transferId);
    }

    public Map<Long, TransferRuntime> pendingTransfersSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(pendingTransfers));
    }

    public List<TransferRuntime> pendingTransfers(int limit) {
        assertOwner();
        if (limit <= 0 || limit > com.surprising.aeron.protocol.CorePendingTransferCodec.MAX_RESULTS) {
            throw new IllegalArgumentException("invalid pending transfer limit");
        }
        return pendingTransfers.values().stream().limit(limit).toList();
    }

    public void restorePendingTransfers(Map<Long, TransferRuntime> restored) {
        assertOwner();
        if (restored == null || restored.size() > MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId())) {
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        }
        pendingTransfers.clear();
        pendingTransfers.putAll(restored);
    }

    boolean hasPendingTransferCapacity() {
        assertOwner();
        return pendingTransfers.size() < MAX_PENDING_TRANSFERS;
    }

    void putPendingTransfer(TransferRuntime transfer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (!hasPendingTransferCapacity()) {
            throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL",
                    "pending transfer runtime capacity is full");
        }
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        patchPendingTransfersBefore.computeIfAbsent(transfer.transferId(),
                id -> new PatchBefore<>(pendingTransfers.get(id)));
        if (pendingTransfers.putIfAbsent(transfer.transferId(), transfer) != null) {
            throw new IllegalArgumentException("pending transfer already exists");
        }
    }

    boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        TransferRuntime current = pendingTransfers.get(transferId);
        if (current == null) return false;
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        }
        patchPendingTransfersBefore.computeIfAbsent(transferId, id -> new PatchBefore<>(current));
        pendingTransfers.remove(transferId);
        return true;
    }

    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return Collections.unmodifiableMap(new TreeMap<>(feePolicies));
    }

    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (restored == null) throw new IllegalArgumentException("fee policy snapshot is required");
        feePolicies.clear();
        feePolicies.putAll(restored);
        changedFeePolicies.clear();
    }

    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("fee-policy state");
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        CoreFeePolicyState current = feePolicies.get(next.policyId());
        if (current != null && next.policyRevision() < current.policyRevision()) {
            throw new CoreStateRejectedException("STALE_FEE_POLICY_VERSION", "fee policy version must increase");
        }
        if (current != null && next.policyRevision() == current.policyRevision()) {
            if (!current.equals(next)) {
                throw new CoreStateRejectedException("FEE_POLICY_VERSION_CONFLICT",
                        "fee policy version contains different data");
            }
            return;
        }
        patchFeePoliciesBefore.computeIfAbsent(next.policyId(), id -> new PatchBefore<>(current));
        feePolicies.put(next.policyId(), next);
        changedFeePolicies.add(next.policyId());
        setMetadata(productLine, Math.incrementExact(revision));
    }

    public CoreFeeRate resolveFee(long userId, String symbol, long clusterTimestamp,
                                  CoreInstrumentState instrument) {
        assertOwner();
        String normalizedSymbol = instrument.symbol().equals(symbol)
                ? instrument.symbol() : OrderReservation.normalizeSymbol(symbol);
        CoreFeePolicyState selected = null;
        for (CoreFeePolicyState policy : feePolicies.values()) {
            if (policy.effective(userId, normalizedSymbol, clusterTimestamp)
                    && (selected == null || policy.compareTo(selected) < 0)) {
                selected = policy;
            }
        }
        return selected == null
                ? new CoreFeeRate(instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm(), 0)
                : new CoreFeeRate(selected.makerFeeRatePpm(), selected.takerFeeRatePpm(),
                selected.policyRevision());
    }

    public void replaceAuxiliaryState(TradingCoreState source) {
        assertOwner();
        setMetadata(source.productLine(), source.revision());
        setRiskScanControl(source.riskState().scanControl());
        instruments.clear();
        instruments.putAll(source.instruments());
        publishedTriggerOrders.clear();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.leverages.clear();
                lane.leverageKeysByUser.clear();
                lane.algoOrders.clear();
                lane.triggerOrders.clear();
                lane.triggerIdsByUser.clear();
                return null;
            });
        }
        source.leverages().forEach(this::putLeverage);
        source.algoOrders().values().forEach(this::putAlgoOrder);
        cancelAllAfterTimers.clear();
        cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(this::putTriggerOrder);
    }

    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        return onLane(userId, lane -> {
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
            return userClientOrders == null || !userClientOrders.containsKey(clientKey)
                    ? null : userClientOrders.get(clientKey);
        });
    }

    public void putUser(UserRuntime user) {
        assertOwner();
        captureUserBefore(user.userId());
        onLane(user.userId(), lane -> {
            lane.users.put(user.userId(), user);
            lane.registerUser(user.userId());
            return null;
        });
        publishUser(user.userId(), user);
        changedUsers.add(user.userId());
    }

    public void advanceUserRevision(long userId) {
        advanceUserRevision(userId, 1);
    }

    void advanceUserRevision(long userId, long count) {
        if (count <= 0) throw new IllegalArgumentException("revision advance must be positive");
        assertOwner();
        captureUserBefore(userId);
        UserRuntime current = requireUser(userId);
        UserRuntime advanced = new UserRuntime(current.productLine(), userId,
                Math.addExact(current.revision(), count), current.positionMode());
        onLane(userId, lane -> lane.users.put(userId, advanced));
        publishUser(userId, advanced);
        if (laneCommandScope.get() == null) changedUsers.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        captureUserBefore(userId);
        onLane(userId, lane -> {
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
            if (userClientOrders != null) userClientOrders.forEachKeyValue((clientKey, orderId) -> {
                removeClientOrderReverse(lane, orderId, clientKey);
            });
            lane.users.remove(userId);
            lane.removeUser(userId);
            lane.balances.remove(userId);
            lane.clientOrderIndex.remove(userId);
            lane.reservationIdsByUser.remove(userId);
            lane.positionKeysByUser.remove(userId);
            lane.leverageKeysByUser.remove(userId);
            return null;
        });
        publishedAvailableBalances.remove(userId);
        publishUser(userId, null);
        changedUsers.add(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.balances.put(userId, userBalances);
            }
            userBalances.put(assetId, new BalanceRuntime(userId, assetId, availableUnits, lockedUnits));
            captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedBalance(userId, assetId);
            changedUsers.add(userId);
        }
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        captureUserBefore(order.userId());
        captureOrderBefore(order.orderId());
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
        changedOrder(order.orderId(), order);
        changedUsers.add(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        captureUserBefore(reservation.userId());
        captureReservationBefore(reservation.orderId());
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && onLane(previous.userId(), lane -> lane.pendingReservation(previous.orderId()))) {
            throw new IllegalStateException("pending reservation must be replaced through its owner lane");
        }
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.reservations.remove(previous.orderId());
                removeUserEntity(lane.reservationIdsByUser, previous.userId(), previous.orderId());
                return null;
            });
        }
        onLane(reservation.userId(), lane -> {
            lane.reservations.put(reservation.orderId(), reservation);
            addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
            reservationLaneIds.put(reservation.orderId(), topology.accountLaneId(reservation.userId()) + 1L);
        }
        changedReservations.add(reservation.orderId());
        changedUsers.add(reservation.userId());
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        captureUserBefore(order.userId());
        captureOrderBefore(order.orderId());
        OrderRuntime previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        if (previous == null) {
            if (matcherSettlementChangesScope.get() == null) {
                orderLaneIds.put(order.orderId(), topology.accountLaneId(order.userId()) + 1L);
            }
        }
        if (matcherSettlementChangesScope.get() == null) {
            changedOrder(order.orderId(), order);
            changedUsers.add(order.userId());
        }
    }

    public void removeOrder(long orderId) {
        assertOwner();
        captureOrderBefore(orderId);
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> { lane.removeOrder(orderId); return null; });
            publishOrder(orderId, null);
            orderLaneIds.remove(orderId);
            changedOrder(orderId, null);
            changedUsers.add(previous.userId());
        }
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        captureUserBefore(reservation.userId());
        captureReservationBefore(reservation.orderId());
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && previous.userId() != reservation.userId()) {
            throw new IllegalArgumentException("runtime reservation owner cannot change");
        }
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                boolean pending = lane.pendingReservation(previous.orderId());
                if (pending) {
                    captureBalanceBefore(previous.userId(), previous.assetId());
                    captureBalanceBefore(reservation.userId(), reservation.assetId());
                }
                lane.replacePendingReservation(previous, reservation);
                lane.reservations.remove(previous.orderId());
                removeUserEntity(lane.reservationIdsByUser, previous.userId(), previous.orderId());
                if (pending) {
                    captureBalanceAfter(lane, previous.userId(), previous.assetId());
                    captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
                }
                return null;
            });
        }
        onLane(reservation.userId(), lane -> {
            lane.reservations.put(reservation.orderId(), reservation);
            addUserEntity(lane.reservationIdsByUser, reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
            if (matcherSettlementChangesScope.get() == null) {
                reservationLaneIds.put(reservation.orderId(), topology.accountLaneId(reservation.userId()) + 1L);
            }
        }
        if (matcherSettlementChangesScope.get() == null) {
            changedReservations.add(reservation.orderId());
            changedUsers.add(reservation.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        captureUserBefore(userId);
        captureReservationBefore(orderId);
        onLane(userId, lane -> {
            ReservationRuntime current = lane.reservations.get(orderId);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
            }
            if (lane.pendingReservation(orderId)) {
                throw new IllegalStateException("pending reservation must complete before removal");
            }
            lane.reservations.remove(orderId);
            removeUserEntity(lane.reservationIdsByUser, userId, orderId);
            return null;
        });
        publishReservation(orderId, null);
        reservationLaneIds.remove(orderId);
        changedReservations.add(orderId);
        changedUsers.add(userId);
    }

    public void replaceBalance(BalanceRuntime balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime current = balances == null ? null : balances.get(assetId);
            if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
            current.replace(availableUnits, lockedUnits);
            captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedBalance(userId, assetId);
            changedUsers.add(userId);
        }
    }

    public void removeBalance(long userId, int assetId) {
        assertOwner();
        captureUserBefore(userId);
        captureBalanceBefore(userId, assetId);
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> userBalances = lane.balances.get(userId);
            if (userBalances == null || userBalances.remove(assetId) == null) {
                throw new IllegalArgumentException("runtime balance is not registered: " + userId + '/' + assetId);
            }
            captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        captureUserBefore(position.userId());
        capturePositionBefore(positionKey, position.userId());
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), lane -> {
            if (previous != null) unindexPosition(lane, positionKey, previous);
            lane.positions.put(positionKey, position);
            indexPosition(lane, positionKey, position);
            return null;
        });
        publishPosition(positionKey, position);
        if (laneCommandScope.get() == null) {
            positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
            changedPosition(positionKey, position);
            changedUsers.add(position.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        captureUserBefore(liquidation.userId());
        captureLiquidationBefore(liquidation.liquidationId());
        if (liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        onLane(liquidation.userId(), lane -> {
            lane.liquidations.put(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        changedUsers.add(liquidation.userId());
    }

    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        captureMarkPriceBefore(markPrice.symbolId());
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        captureUserBefore(snapshot.userId());
        captureRiskSnapshotBefore(positionKey);
        onLane(snapshot.userId(), lane -> {
            lane.riskSnapshots.put(positionKey, snapshot);
            publishRiskSnapshot(positionKey, snapshot);
            return null;
        });
        if (laneCommandScope.get() == null) changedRiskSnapshots.put(positionKey, snapshot);
        changedUsers.add(snapshot.userId());
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        captureRiskScanBefore(scan.symbolId());
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    public long marketRevision() { return marketRevision; }

    public void setMarketRevision(long value) {
        assertOwner();
        if (value < 0) throw new IllegalArgumentException("invalid market revision");
        if (!patchMarketRevisionChanged) {
            patchMarketRevisionBefore = marketRevision;
            patchMarketRevisionChanged = true;
        }
        marketRevision = value;
    }

    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        if (!patchNextLiquidationIdChanged) {
            patchNextLiquidationIdBefore = this.nextLiquidationId;
            patchNextLiquidationIdChanged = true;
        }
        this.nextLiquidationId = nextLiquidationId;
    }

    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        captureUserBefore(liquidation.userId());
        captureLiquidationBefore(liquidation.liquidationId());
        LiquidationRuntime previous = liquidation(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        if (previous.userId() != liquidation.userId()) {
            onLane(previous.userId(), lane -> {
                removeActiveLiquidation(lane, previous);
                lane.liquidations.remove(previous.liquidationId());
                publishLiquidation(previous.liquidationId(), null);
                return null;
            });
        }
        onLane(liquidation.userId(), lane -> {
            if (previous.userId() == liquidation.userId()) removeActiveLiquidation(lane, previous);
            lane.liquidations.put(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (laneCommandScope.get() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        changedUsers.add(liquidation.userId());
        changedUsers.add(previous.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        captureUserBefore(userId);
        capturePositionBefore(positionKey);
        onLane(userId, lane -> {
            PositionRuntime current = lane.positions.get(positionKey);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
            }
            lane.positions.remove(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        publishPosition(positionKey, null);
        positionLaneIds.remove(positionKey);
        changedPosition(positionKey, null);
        changedUsers.add(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        captureLiquidationBefore(liquidationId);
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
            captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> {
                lane.liquidations.remove(liquidationId);
                removeActiveLiquidation(lane, previous);
                publishLiquidation(liquidationId, null);
                return null;
            });
            if (laneCommandScope.get() == null) changedLiquidations.put(liquidationId, null);
            changedUsers.add(previous.userId());
        }
    }

    public void removeMarkPrice(int symbolId) {
        assertOwner();
        captureMarkPriceBefore(symbolId);
        markPrices.remove(symbolId);
        changedMarkPrices.add(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        captureRiskSnapshotBefore(positionKey);
        RiskSnapshotRuntime previous = riskSnapshot(positionKey);
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.riskSnapshots.remove(positionKey);
                publishRiskSnapshot(positionKey, null);
                return null;
            });
        } else {
            publishedRiskSnapshots.removeKey(positionKey);
        }
        if (laneCommandScope.get() == null) changedRiskSnapshots.put(positionKey, null);
        if (previous != null) changedUsers.add(previous.userId());
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        captureRiskScanBefore(symbolId);
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        cancelOrder(orderId, userId, releaseUnits, -1, -1);
    }

    void cancelOrder(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition) {
        assertOwner();
        captureUserBefore(userId);
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        CanceledOrder canceled = onLane(userId, lane -> {
            OrderRuntime order = lane.orders.get(orderId);
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
                throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
            }
            if (releaseUnits != reservation.reservedUnits()) {
                throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
            }
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) {
                throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
            }
            captureBalanceBefore(userId, reservation.assetId());
            balance.release(releaseUnits);
            OrderRuntime terminalOrder = order.withStatus(CoreOrderStatus.CANCELED,
                    Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.putOrder(terminalOrder);
            lane.reservations.put(orderId, released);
            captureBalanceAfter(lane, userId, reservation.assetId());
            return new CanceledOrder(terminalOrder, released);
        });
        publishOrder(orderId, canceled.order());
        publishReservation(orderId, canceled.reservation());
        if (matcherSettlementChangesScope.get() == null) {
            changedOrder(orderId, canceled.order());
            changedReservations.add(orderId);
            changedUsers.add(userId);
            changedBalance(userId, canceled.reservation().assetId());
        }
        advanceUserRevision(userId);
    }

    void cancelOrderInLane(long userId, long orderId) {
        cancelOrderInLane(userId, orderId, -1, -1);
    }

    void cancelOrderInLane(long userId, long orderId, long commitTimestamp, long commitPosition) {
        AccountLaneState lane = laneCommandScope.get();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)
                || matcherSettlementChangesScope.get() == null) {
            throw new IllegalStateException("cancel must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.orders.get(orderId);
        ReservationRuntime reservation = lane.reservations.get(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.status().terminal()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        cancelOrder(orderId, userId, reservation.reservedUnits(), commitTimestamp, commitPosition);
    }

    void replaceOrderInLane(AccountLaneState lane, long userId, long originalOrderId,
                            ResolvedPlaceOrder replacement, java.util.UUID commandId,
                            long requiredReservation, long clientKey, int symbolId, int assetId,
                            long coreSequence) {
        if (lane == null || laneCommandScope.get() != lane || matcherSettlementChangesScope.get() == null
                || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("replace must execute in its owning Account Lane");
        }
        cancelOrderInLane(userId, originalOrderId);
        captureBalanceBefore(userId, assetId);
        placeOrderProvisionalInLane(lane, userId, replacement, commandId, requiredReservation,
                clientKey, symbolId, assetId, coreSequence);
        publishUser(userId, lane.users.get(userId));
        publishOrder(replacement.orderId(), lane.orders.get(replacement.orderId()));
        publishReservation(replacement.orderId(), lane.reservations.get(replacement.orderId()));
        captureBalanceAfter(lane, userId, assetId);
    }

    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        captureReservationBefore(orderId);
        OrderRuntime order = order(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        captureUserBefore(order.userId());
        TerminalRelease release = onLane(order.userId(), lane -> {
            ReservationRuntime reservation = lane.reservations.get(orderId);
            if (reservation == null || !order.canceled()) {
                throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
            }
            long releaseUnits = reservation.reservedUnits();
            if (releaseUnits == 0) return new TerminalRelease(0, reservation.assetId());
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(order.userId());
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
            captureBalanceBefore(order.userId(), reservation.assetId());
            balance.release(releaseUnits);
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.reservations.put(orderId, released);
            publishReservation(orderId, released);
            captureBalanceAfter(lane, order.userId(), reservation.assetId());
            return new TerminalRelease(releaseUnits, reservation.assetId());
        });
        if (release.units() == 0) return;
        if (laneCommandScope.get() == null) {
            changedReservations.add(orderId);
            changedUsers.add(order.userId());
            changedBalance(order.userId(), release.assetId());
        }
    }

    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        captureUserBefore(userId);
        captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            putClientOrderIndex(lane, userId, clientKey, orderId);
            return null;
        });
        changedUsers.add(userId);
    }

    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        captureUserBefore(userId);
        captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            removeClientOrderIndex(lane, userId, clientKey);
            return null;
        });
        changedUsers.add(userId);
    }

    public void pruneTerminalOrders(RuntimeIdentityRegistry identities, List<Long> orderIds) {
        assertOwner();
        if (identities == null || orderIds == null) {
            throw new IllegalArgumentException("terminal order prune input is required");
        }
        List<TerminalOrderPrune> prunes = new ArrayList<>(orderIds.size());
        for (Long orderId : orderIds) {
            if (orderId == null) throw new IllegalArgumentException("terminal order id is required");
            OrderRuntime order = order(orderId);
            if (order == null || !order.status().terminal()) {
                throw new IllegalStateException("order is not terminal: " + orderId);
            }
            ReservationRuntime reservation = reservation(orderId);
            if (reservation != null && reservation.reservedUnits() != 0) {
                throw new IllegalStateException("terminal order retains funded reservation: " + orderId);
            }
            captureOrderBefore(orderId);
            captureReservationBefore(orderId);
            prunes.add(new TerminalOrderPrune(orderId, order.userId(),
                    identities.clientKey(order.userId(), order.clientOrderId())));
            long clientKey = identities.clientKey(order.userId(), order.clientOrderId());
            if (clientKey != 0) captureClientOrderBefore(order.userId(), clientKey);
        }
        Object[] results = executeOwnerSettlements(prunes, TerminalOrderPrune::userId, laneId -> {
            AccountLaneState lane = laneCommandScope.get();
            List<TerminalOrderPruned> pruned = new ArrayList<>();
            for (TerminalOrderPrune prune : prunes) {
                if (topology.accountLaneId(prune.userId()) != laneId) continue;
                OrderRuntime order = lane.orders.get(prune.orderId());
                if (order == null || !order.status().terminal() || order.userId() != prune.userId()) {
                    throw new IllegalStateException("terminal order changed during prune: " + prune.orderId());
                }
                ReservationRuntime reservation = lane.reservations.get(prune.orderId());
                int reservationAssetId = -1;
                if (reservation != null) {
                    if (lane.pendingReservation(prune.orderId())) {
                        throw new IllegalStateException(
                                "pending reservation must complete before terminal pruning: " + prune.orderId());
                    }
                    if (reservation.reservedUnits() != 0) {
                        throw new IllegalStateException(
                                "terminal order retains funded reservation: " + prune.orderId());
                    }
                    reservationAssetId = reservation.assetId();
                    lane.reservations.remove(prune.orderId());
                    removeUserEntity(lane.reservationIdsByUser, prune.userId(), prune.orderId());
                }
                lane.removeOrder(prune.orderId());
                if (prune.clientKey() != 0) {
                    LongLongHashMap clients = lane.clientOrderIndex.get(prune.userId());
                    if (clients != null && clients.containsKey(prune.clientKey())
                            && clients.get(prune.clientKey()) == prune.orderId()) {
                        removeClientOrderIndex(lane, prune.userId(), prune.clientKey());
                    }
                }
                pruned.add(new TerminalOrderPruned(prune.orderId(), prune.userId(), reservationAssetId,
                        reservation != null));
            }
            return List.copyOf(pruned);
        });
        for (Object result : results) {
            if (result == null) continue;
            @SuppressWarnings("unchecked")
            List<TerminalOrderPruned> pruned = (List<TerminalOrderPruned>) result;
            for (TerminalOrderPruned terminal : pruned) {
                publishOrder(terminal.orderId(), null);
                orderLaneIds.remove(terminal.orderId());
                changedOrder(terminal.orderId(), null);
                changedUsers.add(terminal.userId());
                if (terminal.reservationRemoved()) {
                    publishReservation(terminal.orderId(), null);
                    reservationLaneIds.remove(terminal.orderId());
                    changedReservations.add(terminal.orderId());
                    changedBalance(terminal.userId(), terminal.reservationAssetId());
                }
            }
        }
    }

    /** Captures only touched entities at the published-owner boundary; does not enumerate account state. */
    public java.util.concurrent.CompletableFuture<RealtimeUserSnapshot> realtimeSnapshot(long userId) {
        assertOwner();
        int laneId=topology.accountLaneId(userId);
        var future=new java.util.concurrent.CompletableFuture<RealtimeUserSnapshot>();
        SettlementLaneWorker.Command task=lane->{
            try { future.complete(RealtimeUserSnapshot.capture(lane,userId)); }
            catch (RuntimeException failure) { future.completeExceptionally(failure); }
        };
        if (!accountLanesStarted) task.execute(accountLanes[laneId]);
        else {
            // This boundary uses one existing FIFO slot and never waits on the owner.
            if (laneWorkers[laneId].depth()!=0 || !laneWorkers[laneId].hasCapacity())
                return java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException("snapshot lane busy"));
            laneWorkers[laneId].submit(task);
        }
        return future;
    }

    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    RealtimeStateCapture realtimeCapture;

    public void realtimeCapture(RealtimeStateCapture capture) { assertOwner(); realtimeCapture = capture; }

    public void captureRealtimeChanges(RealtimeStateCapture capture) {
        assertOwner();
        if (capture == null || !capture.privateStateEnabled()) return;
        try {
            changedRiskSnapshots.forEach((id,value) -> capture.risk(value));
            changedUsers.forEach(id -> capture.metadata(user(id)));
            changedReservations.forEach(id -> capture.reservation(reservation(id)));
            changedOrders.forEach((orderId, value) -> capture.order(value));
            changedPositions.forEach((positionKey, value) -> {
                if(value==null)capture.removedPosition(currentPatchPositionBefore(positionKey));else capture.position(value);
            });
        } catch (RuntimeException failure) { capture.failed(); }
    }

    public LongHashSet changedUsers() {
        assertOwner();
        return new LongHashSet(changedUsers);
    }

    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed user consumer is required");
        changedUsers.forEach(consumer::accept);
    }

    public IntHashSet changedBalances(long userId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets == null ? new IntHashSet() : new IntHashSet(assets);
    }

    public boolean hasChangedBalance(long userId, int assetId) {
        assertOwner();
        IntHashSet assets = changedBalances.get(userId);
        return assets != null && assets.contains(assetId);
    }

    void markBalanceChanged(long userId, int assetId) {
        assertOwner();
        changedBalance(userId, assetId);
        changedUsers.add(userId);
    }

    void changedOrder(long orderId) {
        changedOrders.put(orderId, order(orderId));
    }

    void changedOrder(long orderId, OrderRuntime order) {
        changedOrders.put(orderId, order);
    }

    void changedPosition(long positionKey) {
        changedPositions.put(positionKey, position(positionKey));
    }

    void changedPosition(long positionKey, PositionRuntime position) {
        changedPositions.put(positionKey, position);
    }

    public LongHashSet changedOrders() {
        assertOwner();
        LongHashSet keys = new LongHashSet();
        changedOrders.forEach((orderId, ignored) -> keys.add(orderId));
        return keys;
    }

    public LongHashSet changedReservations() {
        assertOwner();
        return new LongHashSet(changedReservations);
    }

    public LongHashSet changedPositions() {
        assertOwner();
        LongHashSet keys = new LongHashSet();
        changedPositions.forEach((positionKey, ignored) -> keys.add(positionKey));
        return keys;
    }

    public org.eclipse.collections.api.iterator.LongIterator changedPositionIterator() {
        assertOwner();
        return changedPositions.longIterator();
    }

    public boolean hasChangedPositions() {
        assertOwner();
        return !changedPositions.isEmpty();
    }

    void visitChangedIndexes(RuntimeFactFrame.ChangeConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed-index consumer is required");
        changedOrders.forEach((orderId, value) -> {
            if (!changedActiveOrderValues.containsKey(orderId)) consumer.order(orderId, null, value);
        });
        changedPositions.forEach((positionKey, value) -> {
            if (!changedPositionIndexValues.containsKey(positionKey)) consumer.position(positionKey, null, value);
        });
        changedLiquidations.forEach((liquidationId, value) ->
                consumer.liquidation(liquidationId, null, value));
        changedAlgoOrders.forEach(algoOrderId -> consumer.algoOrder(algoOrderId, null, algoOrder(algoOrderId)));
        changedTriggerOrders.forEach((triggerOrderId, value) -> {
            if (realtimeCapture != null && realtimeCapture.active()) {
                try { realtimeCapture.trigger(value); } catch (RuntimeException failure) { realtimeCapture.failed(); }
            }
            consumer.triggerOrder(triggerOrderId, null, value);
        });
        changedCancelAllAfterTimers.forEach(key -> consumer.timer(key, null, cancelAllAfterTimer(key)));
    }

    void visitPreparedMatcherIndexes(RuntimeFactIndexes indexes) {
        assertOwner();
        changedActiveOrderValues.forEach((orderId, prepared) -> {
            int slot = changedOrders.indexOf(orderId);
            OrderRuntime current = slot < 0 ? null : changedOrders.valueAt(slot);
            indexes.preparedOrder(orderId,
                    slot >= 0 && (current == null || current.status() != CoreOrderStatus.OPEN)
                            ? null : prepared);
        });
        changedPositionIndexValues.forEach(indexes::preparedPosition);
    }

    public void releaseRetiredPositionIdentities(RuntimeIdentityRegistry identities) {
        assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        changedPositions.forEach((positionKey, ignored) ->
                releaseRetiredPositionIdentity(identities, positionKey));
        changedRiskSnapshots.forEach((positionKey, ignored) ->
                releaseRetiredPositionIdentity(identities, positionKey));
    }

    void releaseRetiredPositionIdentity(RuntimeIdentityRegistry identities, long positionKey) {
        if (publishedPositions.get(positionKey) == null && publishedRiskSnapshots.get(positionKey) == null) {
            identities.releasePositionKey(positionKey);
        }
    }

    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, pendingReservations.totalPendingReservations);
    }

    public void appendFundsDelta(RuntimeFundsAccumulator accumulator) {
        assertOwner();
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (LaneBalancePatches balances : patchBalancesBeforeByLane) {
            appendBalanceFundsDelta(balances, accumulator);
        }
        treasury.changedAssets().forEach(assetId -> {
            RuntimeFactFrame.TreasuryAssetValue before = treasury.patchAssetBefore(assetId);
            RuntimeFactFrame.TreasuryAssetValue after = treasuryAssetValue(assetId);
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.FEE, Math.subtractExact(fee(after), fee(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.INSURANCE,
                    Math.subtractExact(insurance(after), insurance(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.DEFICIT,
                    Math.negateExact(Math.subtractExact(deficit(after), deficit(before))));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.LIQUIDATION_FEE,
                    Math.subtractExact(liquidationFee(after), liquidationFee(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.FUNDING_RESIDUAL,
                    Math.subtractExact(fundingResidual(after), fundingResidual(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.ROUNDING_RESIDUAL,
                    Math.subtractExact(roundingResidual(after), roundingResidual(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.TREASURY, 0,
                    FundsPosting.Subledger.CLEARING_PNL,
                    Math.subtractExact(clearingPnl(after), clearingPnl(before)));
        });
    }

    static void prepareBalanceFundsDelta(LaneBalancePatches patches,
                                                 RuntimeFundsAccumulator accumulator) {
        accumulator.clear();
        for (int index = 0; index < patches.size(); index++) {
            long userId = patches.userId(index);
            int assetId = patches.assetId(index);
            RuntimeFactFrame.UserBalance before = patches.before(index);
            RuntimeFactFrame.UserBalance after = patches.after(index);
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(available(after), available(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(locked(after), locked(before)));
        }
    }

    static void appendBalanceFundsDelta(LaneBalancePatches balances,
                                                RuntimeFundsAccumulator accumulator) {
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            RuntimeFactFrame.UserBalance before = balances.before(index);
            RuntimeFactFrame.UserBalance after = balances.after(index);
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(available(after), available(before)));
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(locked(after), locked(before)));
        }
    }

    static long available(RuntimeFactFrame.UserBalance value) {
        return value == null ? 0 : value.availableUnits();
    }

    static long locked(RuntimeFactFrame.UserBalance value) {
        return value == null ? 0 : value.lockedUnits();
    }

    static long fee(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.fee();
    }

    static long insurance(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.insurance();
    }

    static long deficit(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.deficit();
    }

    static long liquidationFee(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.liquidationFee();
    }

    static long fundingResidual(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.fundingResidual();
    }

    static long roundingResidual(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.roundingResidual();
    }

    static long clearingPnl(RuntimeFactFrame.TreasuryAssetValue value) {
        return value == null ? 0 : value.clearingPnl();
    }

    public PositionRuntime currentPatchPositionBefore(long positionKey) {
        assertOwner();
        PositionRuntime current = position(positionKey);
        if (current != null) {
            int laneId = topology.accountLaneId(current.userId());
            LaneLongCaptures<PositionRuntime> captured = patchPositionsBeforeByLane[laneId];
            return captured.containsKey(positionKey) ? captured.get(positionKey) : current;
        }
        for (LaneLongCaptures<PositionRuntime> captured : patchPositionsBeforeByLane) {
            if (captured.containsKey(positionKey)) {
                return captured.get(positionKey);
            }
        }
        return null;
    }

    public OrderRuntime currentPatchOrderBefore(long orderId) {
        assertOwner();
        PatchOrderBefore captured = capturedOrderBefore(orderId);
        return captured == null ? order(orderId) : captured.value();
    }

    RuntimeFactFrame.TreasuryAssetValue treasuryAssetValue(int assetId) {
        long fee = treasury.fee(assetId);
        long insurance = treasury.insurance(assetId);
        long deficit = treasury.insuranceDeficit(assetId);
        long liquidationFee = treasury.liquidationFee(assetId);
        long fundingResidual = treasury.fundingResidual(assetId);
        long roundingResidual = treasury.roundingResidual(assetId);
        long clearingPnl = treasury.clearingPnl(assetId);
        if ((fee | insurance | deficit | liquidationFee | fundingResidual | roundingResidual | clearingPnl) == 0) {
            return null;
        }
        return new RuntimeFactFrame.TreasuryAssetValue(fee, insurance, deficit, liquidationFee,
                fundingResidual, roundingResidual, clearingPnl);
    }

    public void clearChangedKeys() {
        assertOwner();
        removeChangedMapEntries(changedBalances, changedUsers);
        for (LaneLongCaptures<?> captured : patchUsersBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchReservationsBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchOrdersBeforeByLane) captured.clear();
        for (LaneLongCaptures<?> captured : patchPositionsBeforeByLane) captured.clear();
        for (LaneClientOrderCaptures captured : patchClientOrdersBeforeByLane) captured.clear();
        clearChanged(changedUsers);
        changedOrders.clear();
        changedActiveOrderValues.clear();
        clearChanged(changedReservations);
        changedPositions.clear();
        changedPositionIndexValues.clear();
        changedLiquidations.clear();
        clearChanged(changedMarkPrices);
        changedRiskSnapshots.clear();
        clearChanged(changedRiskScans);
        changedInstruments.clear();
        changedLeverages.clear();
        clearChanged(changedAlgoOrders);
        changedCancelAllAfterTimers.clear();
        changedTriggerOrders.clear();
        clearChanged(changedFeePolicies);
        treasury.clearChangedKeys();
        for (LaneBalancePatches capturedBalances : patchBalancesBeforeByLane) {
            for (int index = 0; index < capturedBalances.size(); index++) {
                capturedBalances.publishAvailableAt(this, index);
            }
            capturedBalances.clear();
        }
        patchLiquidationsBefore = clearCapturedChanges(patchLiquidationsBefore);
        patchRiskSnapshotsBefore = clearCapturedChanges(patchRiskSnapshotsBefore);
        patchLeveragesBefore = clearCapturedChanges(patchLeveragesBefore);
        patchAlgoOrdersBefore = clearCapturedChanges(patchAlgoOrdersBefore);
        patchTriggerOrdersBefore = clearCapturedChanges(patchTriggerOrdersBefore);
        patchTimersBefore = clearCapturedChanges(patchTimersBefore);
        patchMarkPricesBefore = clearCapturedChanges(patchMarkPricesBefore);
        patchRiskScansBefore = clearCapturedChanges(patchRiskScansBefore);
        patchInstrumentsBefore = clearCapturedChanges(patchInstrumentsBefore);
        patchPendingTransfersBefore = clearCapturedChanges(patchPendingTransfersBefore);
        patchFeePoliciesBefore = clearCapturedChanges(patchFeePoliciesBefore);
        patchNextLiquidationIdChanged = false;
        patchMarketRevisionChanged = false;
        patchRiskScanControlChanged = false;
    }

    static void clearChanged(LongHashSet values) {
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    static void clearChanged(IntHashSet values) {
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    static <T> void removeChangedMapEntries(LongObjectHashMap<T> values, LongHashSet changedKeys) {
        changedKeys.forEach(values::removeKey);
        if (!values.isEmpty()) {
            throw new IllegalStateException("changed map contains an untracked key");
        }
    }

    static <K, V> ConcurrentHashMap<K, V> clearCapturedChanges(ConcurrentHashMap<K, V> values) {
        if (values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD) return new ConcurrentHashMap<>();
        values.clear();
        return values;
    }

    TradingRuntimeSnapshot snapshot(long revision) {
        assertOwner();
        return RuntimeSnapshotBuilder.capture(this, revision);
    }

    LongObjectHashMap<UserRuntime> usersForSnapshot() {
        LongObjectHashMap<UserRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.users)));
        }
        return values;
    }

    LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> copyAllBalances(lane.balances)));
        }
        return values;
    }

    LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        LongObjectHashMap<OrderRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.orders)));
        }
        return values;
    }

    long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        return onLane(userId, lane -> lane.openReduceOnlyQuantity(
                userId, symbolId, positionSide, side, marginMode));
    }

    LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        LongObjectHashMap<ReservationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.reservations)));
        }
        return values;
    }

    LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> copyClientOrderIndex(lane.clientOrderIndex)));
        }
        return values;
    }

    LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        LongObjectHashMap<PositionRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.positions)));
        }
        return values;
    }

    LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        LongObjectHashMap<LiquidationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.liquidations)));
        }
        return values;
    }

    IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskSnapshotRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLane(laneId, lane -> new LongObjectHashMap<>(lane.riskSnapshots)));
        }
        return values;
    }

    IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    public void reserveOrder(long orderId, long userId, long clientKey, int symbolId,
                             long quantitySteps, int assetId, long reservedUnits) {
        reserveOrder(new OrderRuntime(orderId, userId, symbolId, quantitySteps),
                new ReservationRuntime(orderId, userId, assetId, reservedUnits), clientKey, reservedUnits);
    }

    /** Installs the prepared immutable values once, within the same reservation/capture boundary. */
    void reserveOrder(OrderRuntime order, ReservationRuntime reservation, long clientKey) {
        if (order == null || reservation == null || order.orderId() != reservation.orderId()
                || order.userId() != reservation.userId() || order.symbolId() != reservation.symbolId()
                || order.instrumentChangeId() != reservation.instrumentChangeId()
                || order.quantitySteps() != reservation.orderQuantitySteps() || clientKey < 0) {
            throw new IllegalArgumentException("invalid prepared reservation");
        }
        reserveOrder(order, reservation, clientKey, reservation.reservedUnits());
    }

    void reserveOrder(OrderRuntime order, ReservationRuntime reservation,
                              long clientKey, long reservedUnits) {
        assertOwner();
        long orderId = order.orderId();
        long userId = order.userId();
        int assetId = reservation.assetId();
        if (order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        captureUserBefore(userId);
        captureOrderBefore(orderId);
        captureReservationBefore(orderId);
        captureBalanceBefore(userId, assetId);
        if (clientKey != 0) captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
            if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
                throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
            }
            UserRuntime user = lane.users.get(userId);
            if (user == null) throw new IllegalArgumentException("runtime user is not registered: " + userId);
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(assetId);
            if (balance == null) {
                throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
            }
            balance.reserve(reservedUnits);
            lane.putOrder(order);
            lane.reservations.put(orderId, reservation);
            addUserEntity(lane.reservationIdsByUser, userId, orderId);
            if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
            captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        publishOrder(orderId, order);
        publishReservation(orderId, reservation);
        orderLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        reservationLaneIds.put(orderId, topology.accountLaneId(userId) + 1L);
        changedOrder(orderId, order);
        changedReservations.add(orderId);
        changedUsers.add(userId);
        changedBalance(userId, assetId);
    }

    /**
     * Lane-owned provisional PLACE mutation. Admission is not externally visible until the owner
     * collects the event, so it deliberately creates no checkpoint, changed-key or commit-patch state.
     */
    void placeOrderProvisionalInLane(
            AccountLaneState lane, long userId, ResolvedPlaceOrder command, java.util.UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId, long coreSequence) {
        if (lane == null || laneCommandScope.get() != lane
                || lane.laneId() != topology.accountLaneId(userId)
                || command == null || commandId == null || userId <= 0 || requiredReservation <= 0
                || clientKey < 0 || symbolId < 0 || assetId < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid Lane-owned provisional place order");
        }
        lane.assertOwner();
        if (lane.orders.containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserRuntime user = lane.users.get(userId);
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        OrderRuntime order = new OrderRuntime(command.orderId(), productLine, userId, symbolId,
                command.instrumentChangeId(), command.side(), command.limitPriceTicks(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, 0, 0, CoreOrderStatus.OPEN, 1);
        ReservationRuntime reservation = new ReservationRuntime(command.orderId(), userId, symbolId,
                command.instrumentChangeId(), command.reservationKind(), assetId, requiredReservation,
                0, 0, command.quantitySteps());
        UserRuntime advanced = new UserRuntime(productLine, userId,
                Math.incrementExact(user.revision()), user.positionMode());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.reservations.put(reservation.orderId(), reservation);
        addUserEntity(lane.reservationIdsByUser, userId, order.orderId());
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, order.orderId());
        lane.users.put(userId, advanced);
        lane.markPendingReservation(order.orderId(), coreSequence);
    }

    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        capturePositionBefore(positionKey, position.userId());
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), lane -> {
            if (previous != null) unindexPosition(lane, positionKey, previous);
            lane.positions.put(positionKey, position);
            indexPosition(lane, positionKey, position);
            return null;
        });
        publishPosition(positionKey, position);
        if (matcherSettlementChangesScope.get() == null) {
            positionLaneIds.put(positionKey, topology.accountLaneId(position.userId()) + 1L);
            changedPosition(positionKey, position);
            changedUsers.add(position.userId());
            if (previous != null) changedUsers.add(previous.userId());
        }
    }

    static void indexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        addUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.positionKeysBySymbolAndUser.put(position.symbolId(), byUser);
        }
        LongHashSet keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new LongHashSet(2);
            byUser.put(position.userId(), keys);
        }
        keys.add(positionKey);
    }

    static void unindexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        removeUserEntity(lane.positionKeysByUser, position.userId(), positionKey);
        LongObjectHashMap<LongHashSet> byUser = lane.positionKeysBySymbolAndUser.get(position.symbolId());
        LongHashSet keys = byUser == null ? null : byUser.get(position.userId());
        if (keys == null || !keys.remove(positionKey)) return;
        if (keys.isEmpty()) byUser.remove(position.userId());
        if (byUser.isEmpty()) lane.positionKeysBySymbolAndUser.remove(position.symbolId());
    }

    void changedBalance(long userId, int assetId) {
        IntHashSet assets = changedBalances.get(userId);
        if (assets == null) {
            assets = new IntHashSet();
            changedBalances.put(userId, assets);
        }
        assets.add(assetId);
    }

    static TreeSet<Long> toSortedSet(LongHashSet values) {
        TreeSet<Long> sorted = new TreeSet<>();
        values.forEach(sorted::add);
        return sorted;
    }

    void captureUserBefore(long userId) {
        if (matcherSettlementChangesScope.get() != null) return;
        LaneLongCaptures<UserRuntime> captured =
                patchUsersBeforeByLane[topology.accountLaneId(userId)];
        if (!captured.containsKey(userId)) captured.put(userId, user(userId));
    }

    void rollbackUser(long userId, UserRuntime before) {
        onLane(userId, lane -> {
            if (before == null) {
                lane.users.remove(userId);
                lane.removeUser(userId);
            } else {
                lane.users.put(userId, before);
                lane.registerUser(userId);
            }
            return null;
        });
        publishUser(userId, before);
    }

    void rollbackBalance(long userId, int assetId, RuntimeFactFrame.UserBalance before) {
        onLane(userId, lane -> {
            IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
            if (before == null) {
                if (balances != null) {
                    balances.remove(assetId);
                    if (balances.isEmpty()) lane.balances.remove(userId);
                }
            } else {
                if (balances == null) {
                    balances = new IntObjectHashMap<>();
                    lane.balances.put(userId, balances);
                }
                balances.put(assetId, new BalanceRuntime(userId, assetId,
                        before.availableUnits(), before.lockedUnits()));
            }
            return null;
        });
    }

    void rollbackOrder(long orderId, OrderRuntime before) {
        OrderRuntime current = order(orderId);
        if (current != null) onLane(current.userId(), lane -> { lane.removeOrder(orderId); return null; });
        if (before == null) {
            publishOrder(orderId, null);
            orderLaneIds.remove(orderId);
        } else {
            onLane(before.userId(), lane -> { lane.putOrder(before); return null; });
            publishOrder(orderId, before);
            orderLaneIds.put(orderId, topology.accountLaneId(before.userId()) + 1L);
        }
    }

    void rollbackReservation(long orderId, ReservationRuntime before) {
        ReservationRuntime current = reservation(orderId);
        if (current != null) onLane(current.userId(), lane -> {
            lane.reservations.remove(orderId);
            removeUserEntity(lane.reservationIdsByUser, current.userId(), orderId);
            return null;
        });
        if (before == null) {
            publishReservation(orderId, null);
            reservationLaneIds.remove(orderId);
        } else {
            onLane(before.userId(), lane -> {
                lane.reservations.put(orderId, before);
                addUserEntity(lane.reservationIdsByUser, before.userId(), orderId);
                return null;
            });
            publishReservation(orderId, before);
            reservationLaneIds.put(orderId, topology.accountLaneId(before.userId()) + 1L);
        }
    }

    void rollbackPosition(long positionKey) {
        PositionRuntime current = position(positionKey);
        int laneId = current == null ? -1 : topology.accountLaneId(current.userId());
        PositionRuntime before = laneId >= 0 && patchPositionsBeforeByLane[laneId].containsKey(positionKey)
                ? patchPositionsBeforeByLane[laneId].get(positionKey) : null;
        if (current == null) {
            for (int candidateLaneId = 0; candidateLaneId < accountLanes.length; candidateLaneId++) {
                if (patchPositionsBeforeByLane[candidateLaneId].containsKey(positionKey)) {
                    before = patchPositionsBeforeByLane[candidateLaneId].get(positionKey);
                    break;
                }
            }
        }
        PositionRuntime rollbackValue = before;
        if (current != null) onLane(current.userId(), lane -> {
            lane.positions.remove(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        if (rollbackValue == null) {
            publishPosition(positionKey, null);
            positionLaneIds.remove(positionKey);
        } else {
            onLane(rollbackValue.userId(), lane -> {
                lane.positions.put(positionKey, rollbackValue);
                indexPosition(lane, positionKey, rollbackValue);
                return null;
            });
            publishPosition(positionKey, rollbackValue);
            positionLaneIds.put(positionKey, topology.accountLaneId(rollbackValue.userId()) + 1L);
        }
    }

    void captureBalanceBefore(long userId, int assetId) {
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[topology.accountLaneId(userId)]
                : changes.balancePatches[topology.accountLaneId(userId)];
        if (captured.contains(userId, assetId)) return;
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) {
            captureBalanceBefore(scoped, captured, userId, assetId);
            return;
        }
        onLane(userId, lane -> {
            captureBalanceBefore(lane, captured, userId, assetId);
            return null;
        });
    }

    static void captureBalanceBefore(AccountLaneState lane, LaneBalancePatches captured,
                                             long userId, int assetId) {
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.add(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    void captureBalanceAfter(AccountLaneState lane, long userId, int assetId) {
        MatcherSettlementChanges changes = matcherSettlementChangesScope.get();
        LaneBalancePatches captured = changes == null
                ? patchBalancesBeforeByLane[lane.laneId()]
                : changes.balancePatches[lane.laneId()];
        IntObjectHashMap<BalanceRuntime> balances = lane.balances.get(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        captured.after(userId, assetId, balance, lane.pendingReservedUnits(userId, assetId));
    }

    void captureOrderBefore(long orderId) {
        if (matcherSettlementChangesScope.get() != null) return;
        if (capturedOrderBefore(orderId) != null) return;
        OrderRuntime value = order(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), orderLaneIds);
        LaneLongCaptures<PatchOrderBefore> captured = patchOrdersBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, new PatchOrderBefore(value,
                    value != null && pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    void captureReservationBefore(long orderId) {
        if (matcherSettlementChangesScope.get() != null) return;
        if (capturedReservationBefore(orderId) != null) return;
        ReservationRuntime value = reservation(orderId);
        int laneId = captureLane(orderId, value == null ? 0 : value.userId(), reservationLaneIds);
        LaneLongCaptures<PatchReservationBefore> captured = patchReservationsBeforeByLane[laneId];
        if (!captured.containsKey(orderId)) {
            captured.put(orderId, new PatchReservationBefore(value,
                    value != null && pendingReservations.pendingReservation(orderId, value.userId())));
        }
    }

    boolean reservationPendingBefore(long orderId) {
        PatchReservationBefore before = capturedReservationBefore(orderId);
        return before != null && before.pending();
    }

    int captureLane(long entityId, long userId, Long2LongHashMap laneIds) {
        AccountLaneState scoped = laneCommandScope.get();
        if (scoped != null) return scoped.laneId();
        if (userId > 0) return topology.accountLaneId(userId);
        int indexed = indexedLane(laneIds, entityId);
        return indexed >= 0 ? indexed : Math.floorMod(Long.hashCode(entityId), accountLanes.length);
    }

    PatchOrderBefore capturedOrderBefore(long orderId) {
        for (LaneLongCaptures<PatchOrderBefore> captured : patchOrdersBeforeByLane) {
            PatchOrderBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    PatchReservationBefore capturedReservationBefore(long orderId) {
        for (LaneLongCaptures<PatchReservationBefore> captured : patchReservationsBeforeByLane) {
            PatchReservationBefore value = captured.get(orderId);
            if (value != null || captured.containsKey(orderId)) return value;
        }
        return null;
    }

    boolean reservationPendingAfter(long orderId) {
        ReservationRuntime current = reservation(orderId);
        return current != null && pendingReservations.pendingReservation(orderId, current.userId());
    }

    Long visibleClientOrder(Long orderId, boolean before) {
        if (orderId == null) return null;
        boolean pending = before ? reservationPendingBefore(orderId) : reservationPendingAfter(orderId);
        return pending ? null : orderId;
    }

    void capturePositionBefore(long positionKey) {
        capturePositionBefore(positionKey, 0);
    }

    void capturePositionBefore(long positionKey, long fallbackUserId) {
        if (matcherSettlementChangesScope.get() != null) return;
        PositionRuntime before = position(positionKey);
        long userId = before == null ? fallbackUserId : before.userId();
        if (userId > 0) {
            LaneLongCaptures<PositionRuntime> captured =
                    patchPositionsBeforeByLane[topology.accountLaneId(userId)];
            if (!captured.containsKey(positionKey)) captured.put(positionKey, before);
        }
    }

    void captureLiquidationBefore(long liquidationId) {
        rejectUnsupportedOrderBatchMutation("liquidation state");
        patchLiquidationsBefore.computeIfAbsent(liquidationId, id -> new PatchBefore<>(liquidation(id)));
    }

    void captureRiskSnapshotBefore(long positionKey) {
        rejectUnsupportedOrderBatchMutation("risk snapshot state");
        patchRiskSnapshotsBefore.computeIfAbsent(positionKey, key -> new PatchBefore<>(riskSnapshot(key)));
    }

    void captureMarkPriceBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("mark-price state");
        patchMarkPricesBefore.computeIfAbsent(symbolId, id -> new PatchBefore<>(markPrices.get(id)));
    }

    void captureRiskScanBefore(int symbolId) {
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        patchRiskScansBefore.computeIfAbsent(symbolId, id -> new PatchBefore<>(riskScans.get(id)));
    }

    void captureClientOrderBefore(long userId, long clientKey) {
        if (matcherSettlementChangesScope.get() != null) return;
        LaneClientOrderCaptures captured = patchClientOrdersBeforeByLane[topology.accountLaneId(userId)];
        if (!captured.contains(userId, clientKey)) {
            captured.add(userId, clientKey, orderIdByClient(userId, clientKey));
        }
    }

    static void addUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) {
            entities = new LongHashSet(2);
            index.put(userId, entities);
        }
        entities.add(entityId);
    }

    static void removeUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) return;
        entities.remove(entityId);
        if (entities.isEmpty()) index.remove(userId);
    }

    static void putClientOrderIndex(AccountLaneState lane, long userId, long clientKey, long orderId) {
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null) {
            userClientOrders = new LongLongHashMap(2);
            lane.clientOrderIndex.put(userId, userClientOrders);
        }
        boolean hadPrevious = userClientOrders.containsKey(clientKey);
        long previousOrderId = hadPrevious ? userClientOrders.get(clientKey) : 0;
        userClientOrders.put(clientKey, orderId);
        if (hadPrevious && previousOrderId != orderId) {
            removeClientOrderReverse(lane, previousOrderId, clientKey);
        }
        lane.clientKeysByOrderId.add(orderId, clientKey);
    }

    static Long removeClientOrderIndex(AccountLaneState lane, long userId, long clientKey) {
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders == null || !userClientOrders.containsKey(clientKey)) return null;
        long orderId = userClientOrders.get(clientKey);
        userClientOrders.remove(clientKey);
        if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
        removeClientOrderReverse(lane, orderId, clientKey);
        return orderId;
    }

    static void removeClientOrderReverse(AccountLaneState lane, long orderId, long clientKey) {
        lane.clientKeysByOrderId.remove(orderId, clientKey);
    }

    static void removeClientOrdersForOrder(AccountLaneState lane, long userId, long orderId) {
        LongLongHashMap userClientOrders = lane.clientOrderIndex.get(userId);
        if (userClientOrders != null) {
            lane.clientKeysByOrderId.forEach(orderId, userClientOrders::removeKey);
            if (userClientOrders.isEmpty()) lane.clientOrderIndex.remove(userId);
        }
        lane.clientKeysByOrderId.remove(orderId);
    }

    static void indexActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.activeLiquidationIndex.put(liquidation.userId(), bySymbol);
        }
        LongObjectHashMap<Long> bySide = bySymbol.get(liquidation.symbolId());
        if (bySide == null) {
            bySide = new LongObjectHashMap<>();
            bySymbol.put(liquidation.symbolId(), bySide);
        }
        Long current = bySide.get(liquidation.positionSide().ordinal());
        if (current != null && current != liquidation.liquidationId()) {
            throw new IllegalStateException("multiple active runtime liquidations for one position");
        }
        bySide.put(liquidation.positionSide().ordinal(), liquidation.liquidationId());
    }

    static void removeActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidationIndex.get(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.activeLiquidationIndex.remove(liquidation.userId());
    }

    static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }

    static final class LaneClientOrderCaptures {
        /** 当前缓冲各项对应的用户 ID。 */
        long[] userIds = new long[4];
        /** 当前缓冲各项对应的内部客户单号键。 */
        long[] clientKeys = new long[4];
        /** 变更前客户单号指向的订单 ID。 */
        long[] beforeOrderIds = new long[4];
        /** 该实体在变更前是否存在，区分不存在和零值。 */
        boolean[] presentBefore = new boolean[4];
        /** 当前有效元素数量。 */
        int size;

        int size() { return size; }
        long userId(int index) { return userIds[index]; }
        long clientKey(int index) { return clientKeys[index]; }
        Long beforeOrderId(int index) {
            return presentBefore[index] ? beforeOrderIds[index] : null;
        }

        boolean contains(long userId, long clientKey) {
            for (int index = 0; index < size; index++) {
                if (userIds[index] == userId && clientKeys[index] == clientKey) return true;
            }
            return false;
        }

        void add(long userId, long clientKey, Long beforeOrderId) {
            if (userId <= 0 || clientKey <= 0) {
                throw new IllegalArgumentException("invalid client-order capture");
            }
            if (size == userIds.length) {
                int capacity = Math.multiplyExact(size, 2);
                userIds = java.util.Arrays.copyOf(userIds, capacity);
                clientKeys = java.util.Arrays.copyOf(clientKeys, capacity);
                beforeOrderIds = java.util.Arrays.copyOf(beforeOrderIds, capacity);
                presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
            }
            userIds[size] = userId;
            clientKeys[size] = clientKey;
            presentBefore[size] = beforeOrderId != null;
            beforeOrderIds[size] = beforeOrderId == null ? 0 : beforeOrderId;
            size++;
        }

        void clear() {
            for (int index = 0; index < size; index++) presentBefore[index] = false;
            size = 0;
        }

    }

    static final class LaneBalancePatches {
        void mergeSequential(LaneBalancePatches source) {
            for (int sourceIndex = 0; sourceIndex < source.size; sourceIndex++) {
                long userId = source.userIds[sourceIndex];
                int assetId = source.assetIds[sourceIndex];
                int target = indexOf(userId, assetId);
                if (target < 0) {
                    target = size;
                    add(userId, assetId, null, 0);
                    presentBefore[target] = source.presentBefore[sourceIndex];
                    availableBefore[target] = source.availableBefore[sourceIndex];
                    lockedBefore[target] = source.lockedBefore[sourceIndex];
                    pendingBefore[target] = source.pendingBefore[sourceIndex];
                }
                presentAfter[target] = source.presentAfter[sourceIndex];
                capturedAfter[target] = source.capturedAfter[sourceIndex];
                availableAfter[target] = source.availableAfter[sourceIndex];
                lockedAfter[target] = source.lockedAfter[sourceIndex];
                pendingAfter[target] = source.pendingAfter[sourceIndex];
            }
        }

        /** 当前缓冲各项对应的用户 ID。 */
        long[] userIds = new long[8];
        /** 余额缓冲各项对应的资产内部 ID。 */
        int[] assetIds = new int[8];
        /** 变更前可用余额。 */
        long[] availableBefore = new long[8];
        /** 变更前冻结余额。 */
        long[] lockedBefore = new long[8];
        /** 变更前尚未完成的预留金额。 */
        long[] pendingBefore = new long[8];
        /** 该实体在变更前是否存在，区分不存在和零值。 */
        boolean[] presentBefore = new boolean[8];
        /** 变更后可用余额。 */
        long[] availableAfter = new long[8];
        /** 变更后冻结余额。 */
        long[] lockedAfter = new long[8];
        /** 变更后尚未完成的预留金额。 */
        long[] pendingAfter = new long[8];
        /** 该实体在变更后是否存在。 */
        boolean[] presentAfter = new boolean[8];
        /** 该实体是否已捕获变更后值。 */
        boolean[] capturedAfter = new boolean[8];
        /** 余额索引槽对应的用户 ID。 */
        long[] indexUserIds = new long[16];
        /** 余额索引槽对应的资产 ID。 */
        int[] indexAssetIds = new int[16];
        /** 索引槽对应的连续存储下标。 */
        int[] indexSlots = new int[16];
        /** 每个索引槽所属的清空代次，避免每次清零整张索引。 */
        int[] indexGenerations = new int[16];
        /** 当前有效代次；溢出时清空代次数组。 */
        int indexGeneration = 1;
        /** 当前有效元素数量。 */
        int size;

        int size() { return size; }
        long userId(int index) { return userIds[index]; }
        int assetId(int index) { return assetIds[index]; }
        RuntimeFactFrame.UserBalance before(int index) {
            return !presentBefore[index] ? null : new RuntimeFactFrame.UserBalance(
                    availableBefore[index], lockedBefore[index], pendingBefore[index]);
        }

        RuntimeFactFrame.UserBalance after(int index) {
            if (!capturedAfter[index]) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            return !presentAfter[index] ? null : new RuntimeFactFrame.UserBalance(
                    availableAfter[index], lockedAfter[index], pendingAfter[index]);
        }

        boolean contains(long userId, int assetId) {
            return indexOf(userId, assetId) >= 0;
        }

        void add(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
            if (indexOf(userId, assetId) >= 0) {
                throw new IllegalStateException("balance capture key already exists");
            }
            ensureIndexCapacity(size + 1);
            if (size == userIds.length) {
                int capacity = Math.multiplyExact(size, 2);
                userIds = java.util.Arrays.copyOf(userIds, capacity);
                assetIds = java.util.Arrays.copyOf(assetIds, capacity);
                availableBefore = java.util.Arrays.copyOf(availableBefore, capacity);
                lockedBefore = java.util.Arrays.copyOf(lockedBefore, capacity);
                pendingBefore = java.util.Arrays.copyOf(pendingBefore, capacity);
                presentBefore = java.util.Arrays.copyOf(presentBefore, capacity);
                availableAfter = java.util.Arrays.copyOf(availableAfter, capacity);
                lockedAfter = java.util.Arrays.copyOf(lockedAfter, capacity);
                pendingAfter = java.util.Arrays.copyOf(pendingAfter, capacity);
                presentAfter = java.util.Arrays.copyOf(presentAfter, capacity);
                capturedAfter = java.util.Arrays.copyOf(capturedAfter, capacity);
            }
            int indexPosition = emptyIndexPosition(userId, assetId);
            userIds[size] = userId;
            assetIds[size] = assetId;
            capturedAfter[size] = false;
            presentAfter[size] = false;
            presentBefore[size] = value != null;
            if (value != null) {
                availableBefore[size] = value.availableUnits();
                lockedBefore[size] = value.lockedUnits();
                pendingBefore[size] = pendingReservedUnits;
            }
            indexUserIds[indexPosition] = userId;
            indexAssetIds[indexPosition] = assetId;
            indexSlots[indexPosition] = size;
            indexGenerations[indexPosition] = indexGeneration;
            size++;
        }

        void after(long userId, int assetId, BalanceRuntime value, long pendingReservedUnits) {
            int index = indexOf(userId, assetId);
            if (index < 0) {
                throw new IllegalStateException("balance after-state is missing its before-state");
            }
            capturedAfter[index] = true;
            presentAfter[index] = value != null;
            if (value != null) {
                availableAfter[index] = value.availableUnits();
                lockedAfter[index] = value.lockedUnits();
                pendingAfter[index] = pendingReservedUnits;
            }
        }

        void clear() {
            size = 0;
            if (++indexGeneration == 0) {
                java.util.Arrays.fill(indexGenerations, 0);
                indexGeneration = 1;
            }
        }

        void publishAvailableAt(TradingRuntimeState state, int index) {
            if (index < 0 || index >= size || !capturedAfter[index]) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            long userId = userIds[index];
            int assetId = assetIds[index];
            if (state.realtimeCapture != null) state.realtimeCapture.balance(userId, assetId,
                    presentAfter[index] ? availableAfter[index] : 0,
                    presentAfter[index] ? lockedAfter[index] : 0);
            IntLongHashMap balances = state.publishedAvailableBalances.get(userId);
            if (!presentAfter[index]) {
                if (balances != null) {
                    balances.removeKey(assetId);
                    if (balances.isEmpty()) state.publishedAvailableBalances.remove(userId);
                }
                return;
            }
            if (balances == null) {
                balances = new IntLongHashMap();
                state.publishedAvailableBalances.put(userId, balances);
            }
            balances.put(assetId, availableAfter[index]);
        }

        int indexOf(long userId, int assetId) {
            int mask = indexSlots.length - 1;
            int position = pairHash(userId, assetId) & mask;
            while (indexGenerations[position] == indexGeneration) {
                if (indexUserIds[position] == userId && indexAssetIds[position] == assetId) {
                    return indexSlots[position];
                }
                position = (position + 1) & mask;
            }
            return -1;
        }

        int emptyIndexPosition(long userId, int assetId) {
            int mask = indexSlots.length - 1;
            int position = pairHash(userId, assetId) & mask;
            while (indexGenerations[position] == indexGeneration) position = (position + 1) & mask;
            return position;
        }

        void ensureIndexCapacity(int requiredSize) {
            if (requiredSize <= indexSlots.length / 2) return;
            int capacity = Math.multiplyExact(indexSlots.length, 2);
            indexUserIds = new long[capacity];
            indexAssetIds = new int[capacity];
            indexSlots = new int[capacity];
            indexGenerations = new int[capacity];
            indexGeneration = 1;
            for (int index = 0; index < size; index++) {
                int position = emptyIndexPosition(userIds[index], assetIds[index]);
                indexUserIds[position] = userIds[index];
                indexAssetIds[position] = assetIds[index];
                indexSlots[position] = index;
                indexGenerations[position] = indexGeneration;
            }
        }

        static int pairHash(long userId, int assetId) {
            long value = userId ^ Integer.toUnsignedLong(assetId) * 0x9e3779b97f4a7c15L;
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    /**
     * Per-command capture buffer. Keys stay primitive and only the slots touched by the current
     * command are visited; unlike an open-addressed map, historical capacity never becomes scan
     * work on the owner hot path.
     */

    record PatchBefore<T>(T value) {}
    record PatchOrderBefore(OrderRuntime value, boolean pending) {}
    record PatchReservationBefore(ReservationRuntime value, boolean pending) {}

    public void markPendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.markPendingReservation(userId, orderId, coreSequence); }
    public void completePendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.completePendingReservation(userId, orderId, coreSequence); }
    public void completePendingReservations(long coreSequence) { pendingReservations.completePendingReservations(coreSequence); }
    public boolean hasPendingReservations() { return pendingReservations.hasPendingReservations(); }
    public MatcherSettlementEvent dispatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long commitSequence,
            long commitTimestamp, long commitClusterPosition,
            MatcherSettlementPlan plan, CoreMatchingResult matchingResult,
            RuntimeIdentityRegistry identities) { return settlements.dispatchMatcherSettlement(coreSequence, expectedLaneMask, commitSequence, commitTimestamp, commitClusterPosition, plan, matchingResult, identities); }
    public void releaseMatcherSettlement(MatcherSettlementEvent event) { settlements.releaseMatcherSettlement(event); }
    public RuntimeTreasuryDelta applyOrderBatchMatcherSettlement(
            long coreSequence, long expectedLaneMask, long takerOrderId,
            CoreMatchingResult matchingResult, RuntimeIdentityRegistry identities,
            TerminalOrderSink terminalOrderSink) { return settlements.applyOrderBatchMatcherSettlement(coreSequence, expectedLaneMask, takerOrderId, matchingResult, identities, terminalOrderSink); }

    public MatcherSettlementEvent[] dispatchMatcherSettlementBatch(
            long coreSequence, long[] takerOrderIds, long[] expectedLaneMasks,
            List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities,
            long commitTimestamp, long commitClusterPosition) { return settlements.dispatchMatcherSettlementBatch(coreSequence, takerOrderIds, expectedLaneMasks, matchingResults, identities, commitTimestamp, commitClusterPosition); }
    public MatcherSettlementEvent[] dispatchSpotMatcherSettlements(
            long coreSequence, List<Long> takerOrderIds, List<Long> expectedLaneMasks,
            List<CoreMatchingResult> matchingResults, RuntimeIdentityRegistry identities) { return settlements.dispatchSpotMatcherSettlements(coreSequence, takerOrderIds, expectedLaneMasks, matchingResults, identities); }
    public RuntimeTreasuryDelta collectMatcherSettlements(MatcherSettlementEvent[] events) { return settlements.collectMatcherSettlements(events); }
    public RuntimeTreasuryDelta collectMatcherSettlements(
            MatcherSettlementEvent[] events, RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink) { return settlements.collectMatcherSettlements(events, fundsAccumulator, terminalOrderSink); }
    boolean pendingReservation(long orderId, long userId) { return pendingReservations.pendingReservation(orderId, userId); }
    long pendingReservedUnits(long userId, int assetId) { return pendingReservations.pendingReservedUnits(userId, assetId); }
    int pendingReservationCount(long userId) { return pendingReservations.pendingReservationCount(userId); }
    int pendingReservationCount() { return pendingReservations.pendingReservationCount(); }
}
