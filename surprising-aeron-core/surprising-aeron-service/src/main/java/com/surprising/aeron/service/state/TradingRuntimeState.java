package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.command.AccountLaneOperationType;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.service.config.TradingCpuAffinity;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.lane.LaneSequenceQueue;
import com.surprising.aeron.service.lane.LaneThread;
import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.BalanceState;
import com.surprising.aeron.service.state.account.ControlLaneDispatcher;
import com.surprising.aeron.service.state.account.LaneBalancePatches;
import com.surprising.aeron.service.state.account.RuntimeAccountRollback;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.admission.PendingReservationTracker;
import com.surprising.aeron.service.state.admission.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.admission.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.admission.PlaceBatchIntentSource;
import com.surprising.aeron.service.state.admission.ReplacementAdmissionEvent;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.change.LaneCommitEvent;
import com.surprising.aeron.service.state.change.OwnerIndexedChanges;
import com.surprising.aeron.service.state.change.RuntimeChangeBuffer;
import com.surprising.aeron.service.state.change.RuntimeIndexedChangeBuffer;
import com.surprising.aeron.service.state.change.RuntimeOrderChangeBuffer;
import com.surprising.aeron.service.state.change.RuntimePositionChangeBuffer;
import com.surprising.aeron.service.state.fee.FeePolicyBook;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.index.RuntimeFactIndexes;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.ledger.RuntimeFundsAccumulator;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.ledger.SystemLedgerCommand;
import com.surprising.aeron.service.state.ledger.SystemLedgerLane;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.LaneCancelEvent;
import com.surprising.aeron.service.state.order.LaneOrderResultTarget;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.position.RuntimePositionIndexValue;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.aeron.service.state.realtime.RealtimeUserSnapshot;
import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import com.surprising.aeron.service.state.risk.RiskLiquidationBatch;
import com.surprising.aeron.service.state.risk.RiskScanCoordinator;
import com.surprising.aeron.service.state.risk.RiskScanRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.state.settlement.FundsPosting;
import com.surprising.aeron.service.state.settlement.MatcherSettlementChanges;
import com.surprising.aeron.service.state.settlement.MatcherSettlementDispatcher;
import com.surprising.aeron.service.state.settlement.MatcherSettlementEvent;
import com.surprising.aeron.service.state.settlement.MatcherSettlementPlan;
import com.surprising.aeron.service.state.snapshot.AccountLaneSnapshot;
import com.surprising.aeron.service.state.snapshot.RuntimeStateMaterializer;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 权威交易状态：可变账户归属各 Lane，owner 仅消费已发布状态。
 * 结算派发和待完成预留由专门组件维护；资金、订单和持仓不会被复制为第二套可写模型。
 */
public final class TradingRuntimeState implements AutoCloseable {
    /**
     * Probe used by the settlement hot path.  Leverage state is retained in the
     * durable {@code CoreLeverageKey} map, but constructing that record for
     * every derivative fill is pure lookup overhead.  HashMap/CHM lookup calls
     * the probe's equals method, so the mutable key never enters the map and is
     * safe to reuse on the owning thread.
     */
    private final LeverageLookup ownerLeverageLookup = new LeverageLookup();

    public static final class LeverageLookup {
        long userId;
        String instrumentId;
        CoreMarginMode marginMode;

        public void set(long userId, String instrumentId, CoreMarginMode marginMode) {
            this.userId = userId;
            this.instrumentId = instrumentId;
            this.marginMode = marginMode;
        }

        public void clear() {
            instrumentId = null;
            marginMode = null;
        }

        @Override
        public int hashCode() {
            // Matches the record hash generated for CoreLeverageKey, without
            // allocating the varargs array used by Objects.hash.
            int hash = 31 * Long.hashCode(userId) + instrumentId.hashCode();
            return 31 * hash + marginMode.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CoreLeverageKey(long id, String symbol1, CoreMarginMode mode)
                    && userId == id
                    && instrumentId.equals(symbol1)
                    && marginMode == mode;
        }
    }

    /** 撮合后结算派发与完成收集；沿用现货和衍生品结算处理器，保持 Lane 序号。 */
    final MatcherSettlementDispatcher settlements = new MatcherSettlementDispatcher(this);

    /** 尚未完成的订单预留索引；owner 维护序号和用户计数，在 Lane 完成后释放。 */
    final PendingReservationTracker pendingReservations = new PendingReservationTracker(this);

    /** 尚未完成的资金转账容量上限。 */
    public static final int MAX_PENDING_TRANSFERS = TreasuryRuntime.MAX_PENDING_TRANSFERS;
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
    /** 永久运行的各账户 Lane 工作线程；承担所有多核真并发结算。 */
    public final SettlementLaneWorker[] laneWorkers;
    /** 批量准入完成的 shard 唤醒位；批上下文本身保存唯一完成事实。 */
    private final PaddedReadyMask accountAdmissionReadyShards;
    /** Admission payload stays in its command event; this queue only wakes the exact Owner slot. */
    final LaneSequenceQueue[] placeAdmissionReadyQueues;
    /** 各 Lane 到 owner 的结算完成通知队列。 */
    final LaneSequenceQueue[] matcherSettlementReadyQueues;
    /** 当前执行范围复用的 laneMutationStartedNanos 临时缓冲，不保存第二份业务状态。 */
    final long[] laneMutationStartedNanosScratch;
    /** 当前执行范围复用的 laneMutationResults 临时缓冲，不保存第二份业务状态。 */
    final Object[] laneMutationResultsScratch;
    /** 本次已写入结果的 Lane 位图。 */
    long laneMutationResultsMask;

    /** 各 Lane 派发队列长度峰值，仅用于运行观测。 */
    final int[] accountLaneQueueHighWaterMarks;
    /** 缓存行隔离的已提交序号槽位，防止多 Lane worker 并发写触发 CPU 伪共享。 */
    final PaddedCommittedSequenceSlot[] publishedLaneCommittedSlots;
    /** Owner-only committed metadata, updated from existing Lane receipts; no position copies. */
    private final int[] publishedLanePositionCounts;
    private int publishedPositions;
    /** 已派发到各 Lane 的提交序号，防止重复派发。 */
    final long[] dispatchedLaneCommitSequences;
    private static final int EVENT_POOL_INITIAL_CAPACITY = 1024;
    private static final int EVENT_POOL_PREALLOCATE_COUNT = 512;
    /** 可复用的 laneCommitEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<LaneCommitEvent> laneCommitEventPool = new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);

    /**
     * Coalesced wake-up hints, not business completion state. Each bit has its own
     * cache-line-separated cell; publishing one Lane never rewrites another Lane's word.
     * Multiple producers of the same bit coalesce through a single exchange, without CAS retries.
     * Queue cursors and event release markers remain the authoritative completion fences.
     */
    public static final class PaddedReadyMask {
        private static final int STRIDE = 16;
        private static final java.lang.invoke.VarHandle CELLS =
                java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
        private final long[] cells;
        private final int bitCount;

        public PaddedReadyMask() { this(Long.SIZE); }

        public PaddedReadyMask(int bitCount) {
            if (bitCount <= 0 || bitCount > Long.SIZE)
                throw new IllegalArgumentException("ready bit count must be in [1,64]");
            this.bitCount = bitCount;
            cells = new long[Math.multiplyExact(bitCount, STRIDE)];
        }

        private int offset(int bitIndex) {
            if (bitIndex < 0 || bitIndex >= bitCount)
                throw new IllegalArgumentException("ready bit is outside its topology");
            return bitIndex * STRIDE;
        }

        public boolean setBit(int bitIndex) {
            int offset = offset(bitIndex);
            if ((long) CELLS.getAcquire(cells, offset) != 0) return false;
            CELLS.setRelease(cells, offset, 1L);
            return true;
        }

        public void clearBit(int bitIndex) {
            CELLS.setRelease(cells, offset(bitIndex), 0L);
        }

        public void clearBits(long bitsToClear) {
            while (bitsToClear != 0) {
                int bit = Long.numberOfTrailingZeros(bitsToClear);
                bitsToClear &= bitsToClear - 1;
                CELLS.setRelease(cells, bit * STRIDE, 0L);
            }
        }

        public long get() {
            long ready = 0;
            for (int bit = 0; bit < bitCount; bit++)
                if ((long) CELLS.getAcquire(cells, bit * STRIDE) != 0) ready |= 1L << bit;
            return ready;
        }

        public long getAndClear() {
            long ready = 0;
            for (int bit = 0; bit < bitCount; bit++) {
                int offset = bit * STRIDE;
                // Empty polling reads a shared line; only an actual receipt takes write ownership.
                if ((long) CELLS.getAcquire(cells, offset) != 0) {
                    CELLS.setRelease(cells, offset, 0L);
                    ready |= 1L << bit;
                }
            }
            return ready;
        }
    }

    private static class PaddedCommittedSequenceLhsPadding {
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14;
    }

    private static class PaddedCommittedSequenceValue extends PaddedCommittedSequenceLhsPadding {
        protected volatile long sequence;
    }

    private static final class PaddedCommittedSequenceSlot extends PaddedCommittedSequenceValue {
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24;
    }

    /** 可复用的 placeAdmissionEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<PlaceAdmissionEvent> placeAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    private final java.util.ArrayDeque<ReplacementAdmissionEvent> replacementAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    /** 可复用的 placeBatchAdmissionEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<PlaceBatchAdmissionEvent> placeBatchAdmissionEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    /** 账户 Lane 是否已初始化。 */
    boolean accountLanesStarted;
    private boolean fusedExecutionActive;

    public void enableFusedExecution() {
        assertOwner();
        this.fusedExecutionActive = true;
    }

    public boolean isFusedExecution() {
        return fusedExecutionActive;
    }

    /** 各币对当前标记价，由所属产品运行时独立维护。 */
    final IntObjectHashMap<MarkPriceRuntime> markPrices = new IntObjectHashMap<>();
    /** 各币对的风险扫描续作状态。 */
    final IntObjectHashMap<RiskScanRuntime> riskScans = new IntObjectHashMap<>();
    /** 唯一系统财库；启动后只允许SystemLedgerLane写入，不建立Owner资金镜像。 */
    final TreasuryRuntime treasury = new TreasuryRuntime();
    /** 交易 Owner 执行衍生品撮合状态应用时的复用财库变化对象，避免高频分配。 */
    final RuntimeTreasuryDelta scratchTreasuryDelta = new RuntimeTreasuryDelta();
    private SystemLedgerLane systemLedger;
    /** 当前唯一控制命令的财务交接槽；匹配事件各用自身已有财务槽。 */
    private SystemLedgerCommand ledgerControl;
    /** 配置命令在控制栅栏内热注册；同一 instrumentId 的对象身份保持稳定。 */
    final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>();
    /** 用户自动撤单定时器状态。 */
    final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
    /** 用户手续费策略状态，参与持久化与恢复。 */
    /** Lane 0 writes active fee configuration; readers retain immutable versions. */
    private volatile FeePolicyBook feePolicies = FeePolicyBook.EMPTY;
    final Object[] laneMutationTasks;

    /** 当前执行范围复用的 matcherSettlementRemaining 临时缓冲，不保存第二份业务状态。 */
    final LongLongHashMap matcherSettlementRemainingScratch = new LongLongHashMap();
    /** 当前执行范围复用的 matcherSettlementOrder 临时缓冲，不保存第二份业务状态。 */
    final LongHashSet matcherSettlementOrderScratch = new LongHashSet();
    /** Owner 引用已有路由索引；完整业务状态只属于 Account Lane。 */
    private RuntimeFactIndexes factIndexes;
    private RuntimeIdentityRegistry indexIdentities;
    public void bindFactIndexes(RuntimeFactIndexes indexes, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (systemLedger != null) throw new IllegalStateException("position indexes must bind before Ledger activation");
        factIndexes = java.util.Objects.requireNonNull(indexes);
        indexIdentities = java.util.Objects.requireNonNull(identities);
    }
    public com.surprising.aeron.service.state.index.LiquidationIndex.LiquidationRoute liquidationRoute(long id) {
        assertOwner(); return factIndexes == null ? null : factIndexes.liquidationRoute(id);
    }
    /** Owner identity-lifetime index only: keys retained by Lane risk records, no business values. */
    final LongHashSet riskPositionIdentities = new LongHashSet();
    /** 各 Lane 输出给 owner 的变化缓冲，在完成交接后消费。 */
    final LaneCommitDelta[] laneDeltas;

    /** 下一条清算状态的 ID，需随快照保存。 */
    long nextLiquidationId = 1;
    /** owner 写入，Lane 在控制任务发布后读取；只随成功的行情输入推进。 */
    long marketRevision;
    /** 风险扫描开关和预算；各产品线独立保存。 */
    CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    /** 当前提交范围内变化的用户；与提交/回滚边界同步维护。 */
    final LongHashSet changedUsers = new LongHashSet();
    /** Owner 本次提交是否包含余额变化；明细由 LaneBalancePatches 保持。 */
    private boolean balancesChanged;
    /** 当前提交范围内变化的订单；与提交/回滚边界同步维护。 */
    final OwnerIndexedChanges<OrderRuntime, Void> changedOrders =
            new OwnerIndexedChanges<>(new RuntimeOrderChangeBuffer());
    /** 当前提交范围内变化的预留；与提交/回滚边界同步维护。 */
    final LongHashSet changedReservations = new LongHashSet();
    /** 当前提交范围内变化的持仓；与提交/回滚边界同步维护。 */
    final OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> changedPositions =
            new OwnerIndexedChanges<>(new RuntimePositionChangeBuffer());
    /** 当前提交范围内变化的清算；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<LiquidationRuntime> changedLiquidations =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的标记价；与提交/回滚边界同步维护。 */
    final IntHashSet changedMarkPrices = new IntHashSet();
    /** 当前提交范围内变化的风险快照；与提交/回滚边界同步维护。 */
    final RuntimeIndexedChangeBuffer<RiskSnapshotRuntime, Boolean> changedRiskSnapshots =
            new RuntimeIndexedChangeBuffer<>();
    /** 当前提交范围内变化的风险扫描；与提交/回滚边界同步维护。 */
    final IntHashSet changedRiskScans = new IntHashSet();
    /** 当前提交范围内变化的杠杆；与提交/回滚边界同步维护。 */
    final HashSet<CoreLeverageKey> changedLeverages = new HashSet<>();
    /** 当前提交范围内变化的算法单；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<CoreAlgoOrderState> changedAlgoOrders = new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的自动撤单定时器；与提交/回滚边界同步维护。 */
    final HashSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new HashSet<>();
    /** 当前提交范围内变化的触发单；与提交/回滚边界同步维护。 */
    final RuntimeChangeBuffer<CoreTriggerOrderState> changedTriggerOrders =
            new RuntimeChangeBuffer<>();
    /** 当前提交范围内变化的手续费策略；与提交/回滚边界同步维护。 */
    final LongHashSet changedFeePolicies = new LongHashSet();
    /** 账户 before-image 归属各 Lane，失败时先恢复 Lane 再恢复 Owner 视图。 */
    final RuntimeAccountRollback accountRollback;
    /** 是否处于批量变更范围，限制不支持的交叉全局修改。 */
    boolean orderBatchMutationScope;
    private AccountLaneState ownerLaneScope;
    private MatcherSettlementChanges ownerMatcherSettlementChangesScope;

    /**
     * 固定 LaneCommitDelta ring。每个活跃撮合结算占用一个预建槽位，Owner 收集完成后归还；
     * 不再在提交压力下创建 MatcherSettlementChanges/LaneCommitDelta 对象。
     */
    final MatcherSettlementChanges[] matcherSettlementChangesRing;
    private final int[] freeMatcherSettlementChangeSlots;
    private int freeMatcherSettlementChangeCount;
    /** 可复用的 laneCancelEvent 对象池；仅在消费者完成后回收。 */
    final java.util.ArrayDeque<LaneCancelEvent> laneCancelEventPool = new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    /** 可复用的 laneReplaceEvent 对象池；仅在消费者完成后回收。 */

    /** 拥有当前执行上下文的唯一 owner；不得在其他线程直接修改其状态。 */
    volatile Thread owner;
    private static final java.lang.invoke.VarHandle OWNER_THREAD;
    static {
        try { OWNER_THREAD = java.lang.invoke.MethodHandles.lookup().findVarHandle(TradingRuntimeState.class, "owner", Thread.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public TradingRuntimeState() {
        this(LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization")));
    }

    public TradingRuntimeState(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        accountAdmissionReadyShards = new PaddedReadyMask(topology.matchingEngineCount());
        settlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        directSettlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        placeAdmissionReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        this.accountLanes = new AccountLaneState[topology.accountLaneCount()];
        int deltaRingCapacity = Math.max(64,
                Integer.getInteger("surprising.aeron.max-pending-matching", 4_096));
        this.matcherSettlementChangesRing = new MatcherSettlementChanges[deltaRingCapacity];
        this.freeMatcherSettlementChangeSlots = new int[deltaRingCapacity];
        this.freeMatcherSettlementChangeCount = deltaRingCapacity;
        for (int index = 0; index < deltaRingCapacity; index++) {
            matcherSettlementChangesRing[index] = new MatcherSettlementChanges(accountLanes.length, index);
            freeMatcherSettlementChangeSlots[index] = index;
        }
        this.accountRollback = new RuntimeAccountRollback(this, topology.accountLaneCount());
        this.laneDeltas = new LaneCommitDelta[topology.accountLaneCount()];
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] routedUsers =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.laneUserScratch = routedUsers;
        this.laneWorkers = new SettlementLaneWorker[topology.accountLaneCount()];
        this.laneMutationTasks = new Object[topology.accountLaneCount()];
        this.placeAdmissionReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.matcherSettlementReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.laneMutationStartedNanosScratch = new long[topology.accountLaneCount()];
        this.laneMutationResultsScratch = new Object[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.publishedLaneCommittedSlots = new PaddedCommittedSequenceSlot[topology.accountLaneCount()];
        for (int i = 0; i < topology.accountLaneCount(); i++) {
            this.publishedLaneCommittedSlots[i] = new PaddedCommittedSequenceSlot();
        }
        this.publishedLanePositionCounts = new int[topology.accountLaneCount()];
        this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
            placeAdmissionReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            matcherSettlementReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneCommittedSequence(accountLanes[laneId]);
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            laneDeltas[laneId] = new LaneCommitDelta();
        }
        for (int i = 0; i < EVENT_POOL_PREALLOCATE_COUNT; i++) {
            laneCommitEventPool.addFirst(new LaneCommitEvent(accountLanes.length));
            placeAdmissionEventPool.addFirst(new PlaceAdmissionEvent());
            replacementAdmissionEventPool.addFirst(new ReplacementAdmissionEvent());
            placeBatchAdmissionEventPool.addFirst(new PlaceBatchAdmissionEvent());
            laneCancelEventPool.addFirst(new LaneCancelEvent());
        }
    }

    public LaneTopology topology() {
        return topology;
    }

    public int laneCount() {
        return accountLanes.length;
    }

    public int accountLaneId(long userId) {
        return topology.accountLaneId(userId);
    }

    /** Select a known user's authority at a cold or control entry, never search worker identities. */
    public AccountLaneState accountLaneForUser(long userId) {
        AccountLaneState lane = accountLanes[topology.accountLaneId(userId)];
        requireLaneAccess(lane, userId);
        return lane;
    }

    public AccountLaneState accountLaneForUserReadOnly(long userId) {
        return accountLanes[topology.accountLaneId(userId)];
    }

    public void requireLaneAccess(AccountLaneState lane, long userId) {
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    private void requireLaneAccess(AccountLaneState lane) {
        if (lane == null || lane.laneId() < 0 || lane.laneId() >= accountLanes.length
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    public int laneWorkerDepth(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= laneWorkers.length) throw new IllegalArgumentException("invalid Lane id");
        SettlementLaneWorker worker = laneWorkers[laneId];
        // 非fence观测可能跨过Matcher发布/消费；只约束瞬时负差，不影响业务队列。
        return worker == null ? 0 : Math.max(0, worker.depth());
    }

    /** 指标是非fence观测；Owner只读原子计数，不进入账户作用域或收集业务回执。 */
    public void writeAccountLaneMetrics(int laneId, com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || encoder == null) {
            throw new IllegalArgumentException("invalid Lane metrics request");
        }
        int depth = laneWorkerDepth(laneId);
        int capacity = laneWorkers[laneId] == null ? accountLanes[laneId].queueCapacity() : laneWorkers[laneId].metricsQueueCapacity();
        int highWater = Math.max(accountLaneQueueHighWaterMarks[laneId], depth);
        highWater = accountLanes[laneId].updateQueueHighWaterMark(highWater);
        accountLaneQueueHighWaterMarks[laneId] = highWater; // 指标采样所见的高水位，不声称捕获每次入队。
        accountLanes[laneId].writeMetrics(encoder, depth, capacity, highWater);
    }

    private volatile long retiredOwnerSequence;

    /** Only routing/lifetime metadata crosses this Owner-to-Lane release boundary. */
    public void releaseRetiredOrderFence(long sequence) {
        assertOwner();
        if (sequence < retiredOwnerSequence) throw new IllegalStateException("order retirement fence moved backwards");
        retiredOwnerSequence = sequence;
    }

    public void startAccountLanes() {
        startAccountLanes(TradingCpuAffinity.disabled());
    }

    public void startAccountLanes(TradingCpuAffinity affinity) {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("account lanes are already started");
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            AccountLaneState lane = accountLanes[laneId];
            laneWorkers[laneId] = new SettlementLaneWorker(
                    "account", lane, topology.accountLaneQueueCapacity(),
                    failure -> {
                        accountLaneFailure.compareAndSet(null, failure);
                        signalOwnerCompletion();
                    },
                    topology.matchingEngineCount(), affinity);
        }
        accountLanesStarted = true;
        ownerLaneAccess = false;
        boolean nonLane = false;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            SettlementLaneWorker worker = laneWorkers[laneId];
            if (worker != null && !(worker.thread() instanceof LaneThread)) {
                nonLane = true;
                break;
            }
        }
        this.hasNonLaneThreadWorkers = nonLane;
    }

    private volatile boolean hasNonLaneThreadWorkers;

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

    public boolean ownerLaneAccess = true;
    public boolean ownerLaneAccess() { return ownerLaneAccess; }
    public volatile long laneHandoffEpoch = 0L;
    public long laneHandoffEpoch() { return laneHandoffEpoch; }
    private boolean laneHandoffRequested;
    private long laneHandoffDeadline;

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

    public void acquireOwnerLaneAccess() {
        while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
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

    /** 各 Lane 只在失败时发布一次；Owner 常态只读一个故障引用。 */
    private final java.util.concurrent.atomic.AtomicReference<Throwable> accountLaneFailure = new java.util.concurrent.atomic.AtomicReference<>();

    public void assertAccountLanesHealthy() {
        Throwable failure = accountLaneFailure.get();
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("account lane failed", failure);
    }

    public void startSystemLedgerLane() {
        assertOwner();
        if (systemLedger != null) return;
        if (treasury.hasChangedValues()) throw new IllegalStateException("Ledger activation requires committed Treasury");
        systemLedger = new SystemLedgerLane(treasury,
                Math.max(4_096, Math.multiplyExact(matcherSettlementChangesRing.length, 2)), failure -> {
                    accountLaneFailure.compareAndSet(null, failure);
                    signalOwnerCompletion();
                }, businessOwner -> { if (factIndexes != null) factIndexes.bindBusinessOwner(businessOwner); });
        if (factIndexes != null) factIndexes.bindBusinessOwner(systemLedger.thread());
    }

    public boolean systemLedgerStarted() { return systemLedger != null; }

    /** Ledger-owned business input; admission slots retain their own primitive result. */
    public long ledgerAdmissionOpenInterest(String instrumentId) {
        if (factIndexes == null) throw new IllegalStateException("position indexes are not bound");
        return factIndexes.admissionOpenInterest(instrumentId);
    }

    public void applyLedgerSettlementPositions(MatcherSettlementChanges changes, long mask, RuntimeIdentityRegistry identities) {
        if (factIndexes != null) factIndexes.applySettlementPositions(changes, mask, identities);
    }

    public void applyLedgerControlPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (factIndexes != null) factIndexes.applyControlPositions(changedPositions, indexIdentities, undo);
    }

    public void rollbackLedgerPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (factIndexes != null) undo.rollback(factIndexes);
    }

    public boolean dispatchLedgerCommand(SystemLedgerCommand command) {
        assertOwner();
        if (command == null) throw new IllegalArgumentException("Ledger command is required");
        if (systemLedger == null) {
            command.applyBeforeActivation(treasury);
            return true;
        }
        if (ledgerControl != null && ledgerControl != command)
            throw new IllegalStateException("another control command owns the Ledger financial scope");
        command.submitted();
        if (!systemLedger.offer(command)) {
            command.notAdmitted();
            return false;
        }
        ledgerControl = command;
        return true;
    }

    private boolean rollbackLedgerControl() {
        if (systemLedger == null) {
            treasury.rollbackChangedValues();
            return true;
        }
        if (ledgerControl == null) return controlFundsValidation.requestRollback();
        // Queue the inverse scope only after the previously dispatched phase released it.
        return ledgerControl.requestRollback();
    }

    public boolean enqueueLedgerScopeClose(SystemLedgerLane.Entry close) {
        assertOwner();
        return systemLedger.offer(close);
    }

    /** 启动worker后的所有入口都派发真实Lane；显式作用域只用于尚未启动的命令边界。 */
    public boolean asynchronousCommands() { assertOwner(); return accountLanesStarted || systemLedger != null || asynchronousCommandScope; }
    public boolean accountLanesStarted() { return accountLanesStarted; }

    /** 控制账户任务的唯一派发/收集入口，与账户业务处理器分离。 */
    private final ControlLaneDispatcher controlLanes = new ControlLaneDispatcher(this);

    public void dispatchControlLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.SETTLEMENT, operation);
    }

    public void dispatchRiskLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, AccountLaneOperationType.RISK, operation);
    }

    public RiskLiquidationBatch riskLiquidationBatch(int capacity) {
        if (capacity <= 0 || capacity > 4_096) {
            throw new IllegalArgumentException("invalid risk liquidation batch capacity");
        }
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null && accountLanes != null && accountLanes.length > 0) {
            lane = accountLanes[0];
        }
        if (lane == null) {
            throw new IllegalStateException("risk liquidation batch requires an owning Lane scope");
        }
        RiskLiquidationBatch batch = lane.riskLiquidationBatch();
        if (batch == null || batch.capacity() < capacity) {
            batch = new RiskLiquidationBatch(capacity);
            lane.riskLiquidationBatch(batch);
        }
        batch.reset();
        return batch;
    }

    public boolean pollControlLanes() { return controlLanes.poll(); }

    /** Read the in-flight control Lane handoff only after a Core progress failure. */
    public String controlLaneDiagnostics() { return controlLanes.diagnostics(); }

    public Object controlLaneResult(int laneId) { return controlLanes.result(laneId); }

    public void readFence(long userId, long committedCoreSequence) {
        assertOwner();
        boolean acquired = accountLanesStarted && !ownerLaneAccess;
        try {
            if (acquired) while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            onLane(topology.accountLaneId(userId), lane -> {
                lane.readFence(committedCoreSequence);
                return null;
            });
        } finally {
            if (acquired) releaseOwnerLaneAccess();
        }
    }

    public void readFenceAll(long committedCoreSequence) {
        assertOwner();
        boolean acquired = accountLanesStarted && !ownerLaneAccess;
        try {
            if (acquired) while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
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
        } finally {
            if (acquired) releaseOwnerLaneAccess();
        }
    }

    public <T> T onLane(long userId, LaneOperation<T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    public <T> T onLane(int laneId, LaneOperation<T> operation) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        if (Thread.currentThread() == owner && accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        return inLaneCommandScope(accountLanes[laneId], operation);
    }

    public <T> T onLaneReadOnly(int laneId, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane read operation");
        }
        return operation.apply(accountLanes[laneId]);
    }

    public <T> T inLaneCommandScope(AccountLaneState lane, LaneOperation<T> operation) {
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

    public AccountLaneState currentLaneCommandScope() {
        Thread current = Thread.currentThread();
        if (current == owner) {
            return ownerLaneScope;
        }
        if (current instanceof LaneThread laneThread) {
            AccountLaneState lane = laneThread.lane();
            return lane.commandScopeActive() ? lane : null;
        }
        if (hasNonLaneThreadWorkers && laneWorkers != null) {
            for (int i = 0; i < laneWorkers.length; i++) {
                SettlementLaneWorker worker = laneWorkers[i];
                if (worker != null && worker.thread() == current) {
                    AccountLaneState lane = accountLanes[i];
                    return lane.commandScopeActive() ? lane : null;
                }
            }
        }
        return null;
    }

    /** 查询只在实际Lane线程读原表；不能由Owner借用作用域执行。 */
    public <T> T readAccountLane(AccountLaneState lane, java.util.function.Function<AccountLaneState, T> query) {
        if (!accountLanesStarted) {
            if (lane == null || query == null || lane.laneId() >= accountLanes.length
                    || accountLanes[lane.laneId()] != lane) {
                throw new IllegalArgumentException("invalid account lane read scope");
            }
            enterLaneCommandScope(lane);
            lane.queryScopeActive(true);
            try { return query.apply(lane); }
            finally {
                lane.queryScopeActive(false);
                exitLaneCommandScope(lane, false);
            }
        }
        if (lane == null || query == null || lane.laneId() >= laneWorkers.length
                || accountLanes[lane.laneId()] != lane || laneWorkers[lane.laneId()] == null) {
            throw new IllegalArgumentException("invalid account lane read scope");
        }
        Thread current = Thread.currentThread();
        if (current instanceof LaneThread laneThread) {
            if (laneThread.lane() != lane) {
                throw new IllegalStateException("account query must run on its owning Lane thread");
            }
        } else if (current != laneWorkers[lane.laneId()].thread()) {
            throw new IllegalStateException("account query must run on its owning Lane thread");
        }
        enterLaneCommandScope(lane);
        lane.queryScopeActive(true);
        try { return query.apply(lane); }
        finally {
            lane.queryScopeActive(false);
            exitLaneCommandScope(lane, false);
        }
    }

    /** 单Owner向既有Lane邮箱派发读请求；预检全部容量后才发布，满时不产生部分查询。 */
    public boolean dispatchAccountQuery(long laneMask, SettlementLaneWorker.Command query) {
        assertOwner();
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("account query producer must be Owner");
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1;
        if (query == null || laneMask == 0 || (laneMask & ~validMask) != 0)
            throw new IllegalArgumentException("invalid account query dispatch");
        if (!accountLanesStarted) {
            long lanes = laneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
                query.execute(accountLanes[laneId]);
            }
            return true;
        }
        assertAccountLanesHealthy();
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            if (!laneWorkers[laneId].hasCapacity()) return false;
        }
        releaseOwnerLaneAccess();
        lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneWorkers[laneId].submit(query);
        }
        return true;
    }

    public MatcherSettlementChanges currentMatcherSettlementChangesScope() {
        if (Thread.currentThread() == owner) return ownerMatcherSettlementChangesScope;
        AccountLaneState lane = currentLaneCommandScope();
        return lane != null ? lane.matcherSettlementChanges() : null;
    }

    public void enterLaneCommandScope(AccountLaneState lane) {
        if (lane == null || lane.laneId() >= accountLanes.length || accountLanes[lane.laneId()] != lane) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        Thread current = Thread.currentThread();
        if (owner == null) bindOwner();
        if (current == owner ? ownerLaneScope != null : lane.commandScopeActive())
            throw new IllegalStateException("invalid account lane command scope");
        SettlementLaneWorker worker = laneWorkers[lane.laneId()];
        if (current != owner && (worker == null || worker.thread() != current)) {
            throw new IllegalStateException("account command must run on its owning Lane thread");
        }
        if (current == owner && accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        if (current != owner || !accountLanesStarted) {
            lane.assertOwner();
        }
        if (current == owner) {
            ownerLaneScope = lane;
            if (!accountLanesStarted) lane.commandScopeActive(true);
        } else {
            lane.commandScopeActive(true);
        }
    }

    public void enterMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || lane == null || lane.matcherSettlementChanges() != null
                || Thread.currentThread() == owner && ownerMatcherSettlementChangesScope != null
                || (changes.activeLaneMask() & (1L << lane.laneId())) == 0) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        if (Thread.currentThread() == owner) ownerMatcherSettlementChangesScope = changes;
        lane.matcherSettlementChanges(changes);
    }

    public void exitLaneCommandScope(AccountLaneState lane) {
        exitLaneCommandScope(lane, true);
    }

    private void exitLaneCommandScope(AccountLaneState lane, boolean captureRetirementProof) {
        Thread current = Thread.currentThread();
        if (lane == null || (current == owner
                ? ownerLaneScope != lane : !lane.commandScopeActive())) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        if (current != owner || !accountLanesStarted) {
            lane.assertOwner();
            if (captureRetirementProof) laneDeltas[lane.laneId()].preparePositionRetirement(lane);
        }
        if (current == owner) {
            ownerLaneScope = null;
            if (!accountLanesStarted) lane.commandScopeActive(false);
            flushPublishedChanges(lane.laneId());
        } else {
            lane.commandScopeActive(false);
        }
    }

    public void exitMatcherSettlementScope(AccountLaneState lane, MatcherSettlementChanges changes) {
        if (changes == null || lane == null || (Thread.currentThread() == owner
                ? ownerMatcherSettlementChangesScope != changes : lane.matcherSettlementChanges() != changes)) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        if (Thread.currentThread() == owner) ownerMatcherSettlementChangesScope = null;
        lane.matcherSettlementChanges(null);
        exitLaneCommandScope(lane, false);
    }

    public <T> T executeUserSettlement(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user settlement command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public <T> T executeUserRisk(long userId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (userId <= 0 || operation == null) {
            throw new IllegalArgumentException("invalid user risk command");
        }
        int laneId = topology.accountLaneId(userId);
        executeLaneMutations(1L << laneId, 1, ignored -> operation.get());
        @SuppressWarnings("unchecked") T result = (T) laneMutationResultsScratch[laneId];
        return result;
    }

    public <T> T executeRiskLane(int laneId, java.util.function.Supplier<T> operation) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || operation == null) {
            throw new IllegalArgumentException("invalid risk lane command");
        }
        if (accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("active Account Lane risk must be dispatched to its worker");
        }
        return onLane(laneId, ignored -> operation.get());
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
        return executeLaneMutations(selectedLaneMask, Long.bitCount(selectedLaneMask), operation);
    }

    /** Execute an already-partitioned owner settlement without rescanning its values. */
    public Object[] executeOwnerSettlements(long selectedLaneMask, int workItems,
                                            java.util.function.IntFunction<Object> operation) {
        assertOwner();
        return executeLaneMutations(selectedLaneMask, workItems, operation);
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
        return executeLaneMutations(selectedLaneMask, workItems, operation);
    }

    /** Execute an already-partitioned lifecycle batch without rescanning its values. */
    public Object[] executeLifecycleSettlements(long selectedLaneMask, int workItems,
                                                java.util.function.IntFunction<Object> operation) {
        assertOwner();
        return executeLaneMutations(selectedLaneMask, workItems, operation);
    }

    public Object[] executeLaneMutations(long selectedLaneMask, int workItems,
                                          java.util.function.IntFunction<Object> operation) {
        assertOwner();
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1L;
        if ((selectedLaneMask & ~validMask) != 0 || workItems < 0 || operation == null) {
            throw new IllegalArgumentException("invalid account lane mutation");
        }
        if (accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("active Account Lane mutations must be dispatched to their workers");
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
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if ((selectedLaneMask & 1L << laneId) == 0) continue;
            int currentLaneId = laneId;
            results[laneId] = inLaneCommandScope(accountLanes[currentLaneId],
                    ignored -> operation.apply(currentLaneId));
            accountLanes[laneId].recordSettlementOperation();
        }
        return results;
    }

    /** Preflight all owner-to-Lane submissions before changing task or control state. */
    public void ensureLaneWorkerCapacity(long laneMask) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted) return;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!laneWorkers[laneId].hasCapacity()) {
                throw new java.util.concurrent.RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    int ensurePlaceAdmissionDispatchCapacity(int laneId) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted) return 0;
        int depth = laneWorkers[laneId].admissionDepthIfAvailable();
        if (depth < 0) {
            throw new java.util.concurrent.RejectedExecutionException("Admission queue for lane " + laneId + " is full");
        }
        return depth;
    }

    public void ensureMatcherSettlementDispatchCapacity(long laneMask) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted) return;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!laneWorkers[laneId].hasCapacity()) {
                throw new java.util.concurrent.RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    public @FunctionalInterface
    interface LaneOperation<T> {
        T apply(AccountLaneState lane);
    }

    public boolean currentLaneOwns(long userId) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) throw new IllegalStateException("account lane command scope is required");
        return topology.accountLaneId(userId) == scoped.laneId();
    }

    public void applyLaneUsers(AccountLaneState lane,
                        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                        long coreSequence) {
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneCommittedSequence(lane);
    }

    public void publishLaneCommittedSequence(AccountLaneState lane) {
        int laneId = lane.laneId();
        long committed = lane.committedSequence();
        publishedLaneCommittedSlots[laneId].sequence = committed;
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

    public static LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copyAllBalances(
            LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances) {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> copy = new LongObjectHashMap<>(balances.size());
        balances.forEachKeyValue((userId, userBalances) -> copy.put(userId, copyBalances(userBalances)));
        return copy;
    }

    public static LongObjectHashMap<LongLongHashMap> copyClientOrderIndex(
            LongObjectHashMap<Long2LongHashMap> index) {
        LongObjectHashMap<LongLongHashMap> copy = new LongObjectHashMap<>(index.size());
        // Empty per-user maps are an internal allocation cache, not published state.
        index.forEachKeyValue((userId, values) -> {
            if (!values.isEmpty()) {
                LongLongHashMap entries = new LongLongHashMap(values.size());
                values.forEachLong(entries::put);
                copy.put(userId, entries);
            }
        });
        return copy;
    }

    public void publishUser(long userId, UserRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putUser(userId, value);
        }
    }

    public void publishOrder(long orderId, OrderRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putOrder(orderId, value);
        }
    }

    public void publishReservation(long orderId, ReservationRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putReservation(orderId, value);
        }
    }

    public void publishPosition(long positionKey, PositionRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putPosition(positionKey, value);
        }
    }

    void publishLiquidation(long liquidationId, LiquidationRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedLiquidations.put(liquidationId, value);
        else laneDelta(scoped.laneId()).putLiquidation(liquidationId, value);
    }

    public void publishRiskSnapshot(long positionKey, RiskSnapshotRuntime value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedRiskSnapshots.put(positionKey, value);
        else laneDelta(scoped.laneId()).putRiskSnapshot(positionKey, value);
    }

    public void publishTriggerOrder(long id, CoreTriggerOrderState value) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) {
            changedTriggerOrders.put(id, value);
        } else {
            laneDelta(scoped.laneId()).putTrigger(id, value);
        }
    }

    public LaneCommitDelta laneDelta(int laneId) {
        MatcherSettlementChanges changes = currentMatcherSettlementChangesScope();
        return changes == null ? laneDeltas[laneId] : changes.laneDelta(laneId);
    }

    public LaneCommitDelta laneDelta(AccountLaneState lane) {
        MatcherSettlementChanges changes = lane.matcherSettlementChanges();
        return changes == null ? laneDeltas[lane.laneId()] : changes.laneDelta(lane.laneId());
    }

    public void publishUser(AccountLaneState lane, long userId, UserRuntime value) {
        laneDelta(lane).putUser(userId, value);
    }

    public void publishOrder(AccountLaneState lane, long orderId, OrderRuntime value) {
        laneDelta(lane).putOrder(orderId, value);
    }

    public void publishReservation(AccountLaneState lane, long orderId, ReservationRuntime value) {
        laneDelta(lane).putReservation(orderId, value);
    }

    public void publishPosition(AccountLaneState lane, long positionKey, PositionRuntime value) {
        laneDelta(lane).putPosition(positionKey, value);
    }

    public void flushPublishedChanges(int laneId) {
        flushPublishedChanges(laneId, null, null);
    }

    public void flushPublishedChanges(int laneId,
                               com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        LaneCommitDelta changes = laneDeltas[laneId];
        changes.recordControlChanges(this, laneId);
        if (accountRollback.balancePatch(laneId).size() != 0) markBalancesChanged();
        changes.publishTriggersToOwner(this);
        changes.drainTo(laneId, this, changedUsers, changedOrders);
    }

    public MatcherSettlementChanges acquireMatcherSettlementChanges(long laneMask) {
        assertOwner();
        if (freeMatcherSettlementChangeCount == 0) {
            throw new IllegalStateException("LaneCommitDelta ring is full");
        }
        MatcherSettlementChanges changes = matcherSettlementChangesRing[
                freeMatcherSettlementChangeSlots[--freeMatcherSettlementChangeCount]];
        if (changes.inUse()) throw new IllegalStateException("LaneCommitDelta ring slot is already active");
        changes.inUse(true);
        changes.activeLaneMask(laneMask);
        changes.ensureActiveLanes(laneMask);
        return changes;
    }

    public void releaseMatcherSettlementChanges(MatcherSettlementChanges changes) {
        if (changes == null || !changes.inUse()) throw new IllegalStateException("LaneCommitDelta slot is not active");
        changes.returnRetiredOrders(this);
        changes.clear();
        changes.inUse(false);
        freeMatcherSettlementChangeSlots[freeMatcherSettlementChangeCount++] = changes.ringIndex();
    }

    static <V> void putOrRemove(Long2ObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    static <V> void putOrRemove(LongObjectHashMap<V> values, long key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    @Override
    public void close() {
        releaseOwnerLaneAccess();
        accountLanesStarted = false;
        Throwable failure = null;
        for (SettlementLaneWorker worker : laneWorkers) {
            try { if (worker != null) worker.close(); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem; else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        try { if (systemLedger != null) systemLedger.close(); }
        catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem; else if (failure != problem) failure.addSuppressed(problem);
        } finally {
            if (systemLedger != null && !systemLedger.thread().isAlive() && factIndexes != null)
                factIndexes.bindBusinessOwner(null);
            systemLedger = null;
        }
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Core workers stopped with a failure", failure);
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
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence);
    }

    /** Order metadata is applied in the same Lane task that advances its commit sequence. */
    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, long timestamp, long position) {
        return dispatchLaneMutation(coreSequence, userIds, orderIds, null, timestamp, position);
    }

    public LaneCommitEvent dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || orderIds == null || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid account metadata commit");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, triggerIds, timestamp, position);
    }

    /** Final batch response uses the same account commit task; rejected-only batches use a read task. */
    public LaneCommitEvent dispatchBatchCommit(long coreSequence, Iterable<Long> userIds,
            Iterable<Long> orderIds, long timestamp, long position, long responseUserId,
            LaneOrderResultTarget target, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || orderIds == null || timestamp < 0 || position < 0
                || responseUserId <= 0 || target == null || identities == null)
            throw new IllegalArgumentException("invalid final batch account task");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, null, timestamp, position,
                target, identities, topology.accountLaneId(responseUserId));
    }

    private void addLaneUsers(Iterable<Long> userIds) {
        if (userIds instanceof ImmutableLongArrayList primitive) {
            primitive.forEachLong(userId -> { if (userId > 0) addLaneUser(userId); });
            return;
        }
        for (Long userId : userIds) if (userId != null && userId > 0) addLaneUser(userId);
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

    public void awaitLaneCommit(LaneCommitEvent event, long deadlineNanos) {
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
        return dispatchLaneMutationFromScratch(coreSequence, null, null, 0, 0);
    }

    private LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position) {
        return dispatchLaneMutationFromScratch(coreSequence, orderIds, triggerIds, timestamp, position,
                null, null, -1);
    }

    private LaneCommitEvent dispatchLaneMutationFromScratch(long coreSequence,
            Iterable<Long> orderIds, Iterable<Long> triggerIds, long timestamp, long position,
            LaneOrderResultTarget target, RuntimeIdentityRegistry identities, int responseLaneId) {
        long laneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    publishedLaneCommittedSlots[laneId].sequence, dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        long commitMask = laneMask;
        if (target != null) laneMask |= 1L << responseLaneId;
        if (laneMask == 0) return null;
        if (accountLanesStarted) releaseOwnerLaneAccess();
        ensureLaneWorkerCapacity(laneMask);
        LaneCommitEvent event = laneCommitEventPool.pollFirst();
        if (event == null) event = new LaneCommitEvent(accountLanes.length);
        event.prepare(coreSequence, laneMask, laneUserScratch, this);
        try {
            if (target != null) event.prepareResponse(target, identities, responseLaneId, commitMask);
            event.prepareMetadata(orderIds, timestamp, position);
            event.prepareTriggerCancellations(triggerIds);
        }
        catch (RuntimeException failure) {
            event.discard();
            laneCommitEventPool.addFirst(event);
            throw failure;
        }
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!accountLanesStarted) {
                event.execute(accountLanes[laneId]);
            } else {
                laneWorkers[laneId].submit(event);
            }
            if ((commitMask & (1L << laneId)) != 0)
                dispatchedLaneCommitSequences[laneId] = coreSequence;
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
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.publishAndClear();
        laneCommitEventPool.addFirst(event);
    }

    public PlaceAdmissionEvent acquirePlaceAdmission() {
        PlaceAdmissionEvent event = placeAdmissionEventPool.pollFirst();
        return event == null ? new PlaceAdmissionEvent() : event;
    }

    public void discardPlaceAdmission(PlaceAdmissionEvent event) {
        if (event == null) return;
        event.discard();
        placeAdmissionEventPool.addFirst(event);
    }

    public PlaceAdmissionEvent dispatchPlaceAdmission(
            long coreSequence, long userId, PlaceAdmissionEvent event, java.util.UUID commandId,
            TreasuryRuntime.SettlementStatus settlementStatus,
            int assetId, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        if (event == null) throw new IllegalArgumentException("place admission event is required");
        event.prepare(
                coreSequence, userId, commandId, settlementStatus,
                assetId, laneId, asynchronousCommands(), this, identities, timestamp, position);
        try {
            if (accountLanesStarted && !laneWorkers[laneId].hasAdmissionCapacity())
                throw new java.util.concurrent.RejectedExecutionException("account admission mailbox is full");
            if (!event.accountReady()) {
                if (!dispatchLedgerQuery(event))
                    throw new java.util.concurrent.RejectedExecutionException("Ledger admission mailbox is full");
                event.inputQueued();
            }
            if (!accountLanesStarted) {
                if (event.accountReady()) event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneWorkers[laneId].submitAdmission(event);
            }
        } catch (RuntimeException | Error failure) {
            if (event.inputPending()) throw new IllegalStateException("Ledger still owns failed admission input", failure);
            if (!event.complete()) event.discard();
            placeAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void releasePlaceAdmission(PlaceAdmissionEvent event) {
        if (event == null) return;
        event.clear();
        placeAdmissionEventPool.addFirst(event);
    }

    public ReplacementAdmissionEvent dispatchReplacementAdmission(long sequence, long userId,
            long originalOrderId, com.surprising.aeron.protocol.AmendOrderCommand amend,
            com.surprising.aeron.protocol.ReplaceOrderCommand replace,
            com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.Context decision,
            int matcherShard, RuntimeIdentityRegistry identities) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        ensureLaneWorkerCapacity(1L << laneId);
        ReplacementAdmissionEvent event = replacementAdmissionEventPool.pollFirst();
        if (event == null) event = new ReplacementAdmissionEvent();
        event.prepare(sequence, userId, originalOrderId, amend, replace, decision,
                matcherShard, this, identities);
        try {
            if (!event.accountReady()) {
                if (!dispatchLedgerQuery(event))
                    throw new java.util.concurrent.RejectedExecutionException("Ledger replacement mailbox is full");
                event.inputQueued();
            }
            if (!accountLanesStarted) {
                if (event.accountReady()) event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneWorkers[laneId].submit(event);
            }
        } catch (RuntimeException | Error failure) {
            if (event.inputPending()) throw new IllegalStateException("Ledger still owns failed replacement input", failure);
            event.discard();
            replacementAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void releaseReplacementAdmission(ReplacementAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        replacementAdmissionEventPool.addFirst(event);
    }

    public PlaceBatchAdmissionEvent dispatchPlaceBatchAdmission(
            long coreSequence, long userId, java.util.UUID commandId,
            ResolvedPlaceOrder[] orders, long[] clientKeys,
            OrderRuntime[] admittedOrders, int itemCount,
            int matcherShard, RuntimeIdentityRegistry identities, PlaceBatchIntentSource source,
            long timestamp, long position) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        changes.ensureAdmissionCapacity(laneId, itemCount);
        PlaceBatchAdmissionEvent event = placeBatchAdmissionEventPool.pollFirst();
        if (event == null) event = new PlaceBatchAdmissionEvent();
        event.prepare(coreSequence, userId, commandId, orders, clientKeys, admittedOrders,
                itemCount, laneId, matcherShard, this, changes, identities, source,
                timestamp, position);
        try {
            if (!accountLanesStarted) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneWorkers[laneId].submitAdmission(event);
            }
        } catch (RuntimeException | Error failure) {
            releaseMatcherSettlementChanges(event.discardChanges());
            event.clear();
            placeBatchAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    public void registerPlaceBatchAdmission(PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        long userId = event.userId();
        pendingReservations.registerBatch(event.coreSequence(), userId, event.admittedOrders(), event.itemCount());
        revision = Math.addExact(revision, event.itemCount());
    }

    public void collectPlaceBatchAdmission(
            PlaceBatchAdmissionEvent event, RuntimeFundsAccumulator fundsAccumulator) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        int laneId = topology.accountLaneId(event.userId());
        // Only this batch's ordered commit may populate the shared rollback buffers.
        // Earlier commits must never publish or clear a future admission's before-images.
        accountRollback.captureBatchAdmissionBefore(event.userId(), event.userBefore(), event.admittedOrders(), event.itemCount(), laneId);
        event.copyBalanceBeforeTo(accountRollback.balancePatch(laneId));
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            // Admission values remain Lane-owned until terminal settlement.  This boundary only
            // commits primitive admission markers and the funds/available-balance handoff; it
            // must not replay user/order/reservation publication buffers.
            changes.laneDelta(laneId).commitBatchAdmissionToOwner(this, laneId);
            LaneBalancePatches balances = changes.balancePatch(laneId);
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                if (changedUsers != null) changedUsers.add(balances.userId(index));
                markBalancesChanged();
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

    public void rollbackPlaceBatchAdmissionInLane(
            AccountLaneState lane, long userId, long coreSequence, ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            int admittedCount, UserRuntime userBefore) {
        lane.assertOwner();
        for (int index = admittedCount - 1; index >= 0; index--) {
            long orderId = orders[index].orderId();
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null) {
                throw new IllegalStateException("batch admission rollback reservation is missing");
            }
            lane.completePendingReservation(orderId, coreSequence);
            if (clientKeys[index] != 0) {
                removeClientOrderIndex(lane, userId, clientKeys[index]);
            }
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            lane.removeOrder(orderId);
            IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("batch admission rollback balance is missing");
            balance.release(reservation.reservedUnits());
        }
        if (userBefore != null) {
            UserRuntime current = lane.user(userId);
            lane.storeUser(userId, (accountLanesStarted ? current.laneValue() : current)
                    .withState(userBefore.revision(), userBefore.positionMode()));
        }
    }

    /** Owner 登记的 matcher 结算通知范围；其序号仍由各 Lane 的 SPSC 队列承载。 */
    private long expectedSettlementLanes;
    private final PaddedReadyMask settlementReadyLanes;
    /**
     * Direct Matcher->Lane settlements do not need an Owner-consumed sequence queue.  The
     * settlement event already owns the per-Lane completion bits; this mask is only a wake-up
     * hint so the Owner retries its ordered head.  Keeping it separate avoids allocating and
     * draining one notification record for every Lane touched by every matching result.
     */
    private final PaddedReadyMask directSettlementReadyLanes;
    private final PaddedReadyMask placeAdmissionReadyLanes;
    private final int[] expectedSettlements = new int[64];
    public void releaseSettlementExpectation(int laneId) {
        assertOwner();
        if (expectedSettlements[laneId] <= 0) throw new IllegalStateException("unexpected settlement completion");
        if (--expectedSettlements[laneId] == 0) {
            long bit = 1L << laneId;
            expectedSettlementLanes &= ~bit;
            settlementReadyLanes.clearBit(laneId);
        }
    }
    public void expectMatcherSettlement(long lanes) {
        assertOwner();
        expectedSettlementLanes |= lanes;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            expectedSettlements[lane]++;
        }
    }

    public void publishPlaceAdmissionReady(int laneId, long coreSequence) {
        placeAdmissionReadyQueues[laneId].publish(coreSequence);
        if (placeAdmissionReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    public long takePlaceAdmissionReadyLaneMask() {
        assertOwner();
        return placeAdmissionReadyLanes.getAndClear();
    }

    public long pollPlaceAdmissionReady(int laneId) {
        assertOwner();
        return placeAdmissionReadyQueues[laneId].poll();
    }

    /** Account admission wakes the matching submission head without transferring business data. */
    public void publishAccountAdmissionReadyShard(int matcherShard) {
        if (matcherShard < 0 || matcherShard >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        if (accountAdmissionReadyShards.setBit(matcherShard)) signalOwnerCompletion();
    }

    public long takeAccountAdmissionReadyShardMask() {
        assertOwner();
        return accountAdmissionReadyShards.getAndClear();
    }

    /** 仅读两个就绪字，不消费序号；滞后的置位最多造成一次无效收集，不会丢通知。 */
    public boolean hasMatchingNotifications() {
        assertOwner();
        assertAccountLanesHealthy();
        return placeAdmissionReadyLanes.get() != 0
                || hasPlaceAdmissionNotifications() || hasSettlementNotifications();
    }

    /** 仅唤醒已经声明休眠的 Owner；在发布队列/就绪位后调用，不替代完成队列。 */
    private volatile Runnable ownerCompletionSignal;
    public void ownerCompletionSignal(Runnable signal) { assertOwner(); ownerCompletionSignal = signal; }
    public void signalOwnerCompletion() {
        Runnable signal = ownerCompletionSignal;
        if (signal != null) signal.run();
    }

    /** Owner 只探测本阶段就绪标记；队列仍是实际结果的唯一来源。 */
    public boolean hasPlaceAdmissionNotifications() {
        return accountAdmissionReadyShards.get() != 0;
    }

    public boolean hasSettlementNotifications() {
        return (settlementReadyLanes.get() & expectedSettlementLanes) != 0
                || directSettlementReadyLanes.get() != 0;
    }

    public void publishMatcherSettlementReady(int laneId, long coreSequence) {
        matcherSettlementReadyQueues[laneId].publish(coreSequence);
        if (settlementReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    /**
     * Publish only a wake-up hint for a direct settlement.  The event's padded completion array
     * is the authoritative source, so no Owner-side queue node or expectation counter is needed.
     */
    public void publishDirectMatcherSettlementReady(int laneId) {
        if (directSettlementReadyLanes.setBit(laneId)) signalOwnerCompletion();
    }

    public long takeDirectMatcherSettlementReadyLaneMask() {
        return directSettlementReadyLanes.getAndClear();
    }

    public long takeMatcherSettlementReadyLaneMask() {
        assertOwner();
        return takeReadyLaneMask(settlementReadyLanes, matcherSettlementReadyQueues, expectedSettlementLanes);
    }

    public long pollMatcherSettlementReady(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= matcherSettlementReadyQueues.length) {
            throw new IllegalArgumentException("invalid Account Lane id");
        }
        long sequence = matcherSettlementReadyQueues[laneId].poll();
        if (sequence != 0) releaseSettlementExpectation(laneId);
        return sequence;
    }

    private static long takeReadyLaneMask(PaddedReadyMask ready,
                                         LaneSequenceQueue[] queues, long expected) {
        long signalled = ready.get() & expected;
        if (signalled == 0) return 0;
        // 先清位再消费。清位之后的发布留下新位；之前的发布已经可从队列读取。
        ready.clearBits(signalled);
        return readyLaneMask(queues, signalled);
    }

    static long readyLaneMask(LaneSequenceQueue[] queues, long expected) {
        long mask = 0;
        while (expected != 0) {
            int laneId = Long.numberOfTrailingZeros(expected); expected &= expected - 1;
            if (queues[laneId].hasPending()) mask |= 1L << laneId;
        }
        return mask;
    }

    public ResolvedPlaceOrder collectPlaceAdmission(PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        long orderId = event.orderId();
        long userId = event.userId();
        if (pendingReservations.hasPendingUser(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        int laneId = topology.accountLaneId(userId);
        pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(pendingReservations.totalPendingReservations()));
        revision = Math.incrementExact(revision);
        return event.resolvedOrder();
    }

    /**
     * A batch matcher fact is irreversible before its deferred Lane settlement is collected.
     * Expose the already immutable admission receipt for recovery inspection without publishing
     * provisional user/reservation state or allocating another publication batch.
     */
    public void publishObservedAdmission(OrderRuntime order) {
        assertOwner();
        if (order == null) throw new IllegalArgumentException("observed admission order is required");
    }

    /** Owner按日志顺序投递；队列满时保留同一事实，不能跳过或回收。 */
    public void applyCollectedTreasuryBeforeActivation(RuntimeTreasuryDelta delta) {
        assertOwner();
        if (delta == null || delta.size() == 0) return;
        if (systemLedger != null) throw new IllegalStateException("live money must be applied by SystemLedgerLane");
        delta.apply(treasury);
    }

    public boolean dispatchMatcherFinancialSettlement(MatcherSettlementEvent event) {
        assertOwner();
        if (event == null) return true;
        if (systemLedger == null) return true;
        if (ledgerControl != null) throw new IllegalStateException("Matcher money cannot enter an unfinished control scope");
        return event.hasLedgerSettlement() || event.submitTreasurySettlement(systemLedger);
    }

    /** 有序响应等待已有财务回执；顺序批次仍在队首逐项投递。 */
    public boolean pollMatcherFinancialSettlement(MatcherSettlementEvent event) {
        return dispatchMatcherFinancialSettlement(event) && (event == null || event.complete());
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(MatcherSettlementEvent event) {
        return collectMatcherSettlement(event, null, null);
    }

    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink) {
        return collectMatcherSettlement(event, fundsAccumulator, terminalOrderSink, null, null);
    }

    /** Collect settlement facts and build response changed IDs during the same Lane traversal. */
    public RuntimeTreasuryDelta collectMatcherSettlement(
            MatcherSettlementEvent event,
            RuntimeFundsAccumulator fundsAccumulator,
            TerminalOrderSink terminalOrderSink,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (systemLedger != null && !event.hasLedgerSettlement())
            throw new IllegalStateException("Matcher money must be dispatched from the ordered commit head");
        RuntimeTreasuryDelta aggregate = event.collectTreasuryDelta(fundsAccumulator);
        boolean ledgerFunds = event.hasLedgerSettlement();
        if (event.replacementIdentityAllocations() != 0)
            event.identities().recordLaneClientAllocations(event.replacementIdentityAllocations());
        boolean ownerPending = pendingReservations.containsSequence(event.plan().coreSequence());
        for (int index = 0; index < event.planCount(); index++) {
            if (ownerPending) unindexMatcherPendingReservations(event.plan(index));
            // Lane 不修改全局版本；全部结算成功后由 owner 合并一次触发终态变更。
            if (event.plan(index).completedTrigger() != null) revision = Math.incrementExact(revision);
        }
        long laneMask = event.requiredLaneMask();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            long remainingLanes = laneMask;
            int completed = 0;
            while (remainingLanes != 0) {
                int laneId = Long.numberOfTrailingZeros(remainingLanes);
                remainingLanes &= remainingLanes - 1;
                completed += changes.laneDelta(laneId).completedPending();
                if (fundsAccumulator != null && !ledgerFunds) fundsAccumulator.add(changes.laneFundsDelta(laneId));
                if (changes.directPositionIdentities()) {
                    var positions = changes.laneDelta(laneId).positions();
                    for (int index = 0; index < positions.size(); index++)
                        event.identities().releasePublishedPosition(positions.keyAt(index));
                }
                changes.laneDelta(laneId).commitTerminalToOwner(
                        this, laneId, terminalOrderSink, event.plan().coreSequence(),
                        changedUsers, changedOrders);
                LaneBalancePatches balances = changes.balancePatch(laneId);
                for (int index = 0; index < balances.size(); index++) {
                    balances.publishAvailableAt(this, index);
                    if (changedUsers != null) changedUsers.add(balances.userId(index));
                    markBalancesChanged();
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            pendingReservations.completedBatchItems(event.plan().coreSequence(), completed);
            return aggregate;
        } finally {
            releaseMatcherSettlementChanges(changes);
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
        int laneId = topology.accountLaneId(userId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderId, commitTimestamp, commitClusterPosition,
                laneId, this, identities, changes);
        try {
            if (!accountLanesStarted) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneWorkers[laneId].submit(event);
            }
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
        return dispatchCancelBatch(coreSequence, userId, orderIds, commitTimestamp, commitClusterPosition,
                identities, commitLane, null);
    }

    public LaneCancelEvent dispatchCancelBatch(
            long coreSequence, long userId, long[] orderIds,
            long commitTimestamp, long commitClusterPosition,
            RuntimeIdentityRegistry identities, boolean commitLane, LaneOrderResultTarget resultTarget) {
        assertOwner();
        if (orderIds == null || orderIds.length == 0) {
            throw new IllegalStateException("cancel batch requires orders");
        }
        int laneId = topology.accountLaneId(userId);
        ensureMatcherSettlementDispatchCapacity(1L << laneId);
        MatcherSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        LaneCancelEvent event = laneCancelEventPool.pollFirst();
        if (event == null) event = new LaneCancelEvent();
        event.prepare(coreSequence, userId, orderIds, orderIds.length,
                commitTimestamp, commitClusterPosition, laneId, commitLane, this, identities, changes);
        event.resultTarget(resultTarget);
        try {
            if (!accountLanesStarted) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneWorkers[laneId].submit(event);
            }
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
        collectCancel(event, fundsAccumulator, terminalOrderSink, null, null);
    }

    /** Collect cancel facts and changed IDs without a second buffer traversal. */
    public void collectCancel(LaneCancelEvent event, RuntimeFundsAccumulator fundsAccumulator,
                              TerminalOrderSink terminalOrderSink,
                              com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
                              com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        assertOwner();
        if (event == null || !event.complete()) throw new IllegalStateException("cancel event is incomplete");
        int laneId = event.laneId();
        MatcherSettlementChanges changes = event.takeChanges();
        try {
            changes.laneDelta(laneId).commitTerminalToOwner(
                    this, laneId, terminalOrderSink, event.coreSequence(), changedUsers, changedOrders);
            LaneBalancePatches balances = changes.balancePatch(laneId);
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                if (changedUsers != null) changedUsers.add(balances.userId(index));
                markBalancesChanged();
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

    /** Owner已收集的本次回执，null删除标记也是有效回执，不得退回旧路由引用。 */
    public boolean hasChangedOrder(long orderId) {
        assertOwner();
        return changedOrders.containsKey(orderId);
    }

    public OrderRuntime changedOrderValue(long orderId) {
        assertOwner();
        return changedOrders.get(orderId);
    }

    public PositionRuntime changedPositionValue(long positionKey) {
        assertOwner();
        return changedPositions.get(positionKey);
    }

    @FunctionalInterface
    public interface TerminalOrderSink {
        /** Compact Lane handoff; the Owner receives identity primitives, never a second order walk. */
        void accept(long orderId, long userId, String clientOrderId, long coreSequence);

        default void acceptBatch(LaneCommitDelta delta, long coreSequence) {
            for (int index = 0; index < delta.terminalOrderCount(); index++) {
                accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                        delta.terminalOrderClient(index), coreSequence);
            }
        }

        default void completeSequence() {
        }
    }

    public LongLongHashMap matcherSettlementRemainingScratch() {
        assertOwner();
        return matcherSettlementRemainingScratch;
    }

    public LongHashSet matcherSettlementOrderScratch() {
        assertOwner();
        matcherSettlementOrderScratch.clear();
        return matcherSettlementOrderScratch;
    }

    public void completeMatcherPendingReservations(AccountLaneState lane, MatcherSettlementPlan plan,
                                            OrderRuntime admissionVersion) {
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequence(orderId) != plan.coreSequence()) continue;
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            MatcherSettlementChanges changes = lane.matcherSettlementChanges();
            if (changes == null) {
                accountRollback.captureUserBefore(reservation.userId());
                accountRollback.captureOrderBefore(orderId);
                accountRollback.captureReservationBefore(orderId);
                lane.clientKeysByOrderId().forEach(orderId,
                        clientKey -> accountRollback.captureClientOrderBefore(reservation.userId(), clientKey));
            }
            accountRollback.captureBalanceBefore(lane, reservation.userId(), reservation.assetId());
            lane.completePendingReservation(orderId, plan.coreSequence());
            if (changes != null) changes.laneDelta(lane.laneId()).completedPending(changes.laneDelta(lane.laneId()).completedPending() + 1);
            // Completion still contributes a changed ID, but an unchanged admission already
            // has an immutable version. Reuse it instead of freezing the Lane value again.
            // Compare all fields: commit metadata may change without incrementing revision.
            if (changes == null || !changes.laneDelta(lane.laneId()).orders().containsKey(orderId)) {
                OrderRuntime admitted = lane.order(orderId);
                publishOrder(lane, orderId, admissionVersion != null && admissionVersion.equals(admitted)
                        ? admissionVersion : admitted);
                if (changes == null) {
                    changedOrder(orderId, admitted);
                    markUserChanged(reservation.userId());
                }
            }
            accountRollback.captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
        }
    }

    public void stampMatcherOrders(AccountLaneState lane, MatcherSettlementPlan plan,
                            long timestamp, long clusterPosition) {
        for (int index = 0; index < plan.orderCount(); index++) {
            OrderRuntime order = lane.order(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            if (lane.matcherSettlementChanges() == null) replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
            else updateOrderStatusInLane(lane, order.orderId(), order.status(), order.revision(), timestamp, clusterPosition);
        }
    }

    void stampOrderInLane(AccountLaneState lane, long orderId, long timestamp, long clusterPosition) {
        if (lane == null || !lane.commandScopeActive() || lane.matcherSettlementChanges() == null) {
            throw new IllegalStateException("order stamp must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.order(orderId);
        if (order != null && (order.updatedAtEpochMillis() != timestamp
                || order.clusterPosition() != clusterPosition)) {
            if (lane.matcherSettlementChanges() == null) replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
            else updateOrderStatusInLane(lane, order.orderId(), order.status(), order.revision(), timestamp, clusterPosition);
        }
    }

    void unindexMatcherPendingReservations(MatcherSettlementPlan plan) {
        pendingReservations.unindexMatcherPendingReservations(plan);
    }

    public void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        markUserChanged(userId);
        markBalancesChanged();
        changedPosition(positionKey);
    }

    public void rebuildAccountLaneHashes() {
        assertOwner();
        for (AccountLaneState lane : accountLanes) {
            lane.rebuildLocalHashes();
            publishLaneCommittedSequence(lane);
        }
    }

    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        pendingReservations.assertPendingReservationCounts();
        boolean acquired = (accountLanesStarted || systemLedgerStarted()) && !ownerLaneAccess;
        try {
            if (acquired) {
                while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            }
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
                boolean unchanged = onLane(snapshot.laneId(), lane ->
                        lane.revision() == snapshot.revision()
                                && lane.appliedSequence() == snapshot.appliedSequence()
                                && lane.committedSequence() == snapshot.committedSequence()
                                && lane.localStateHash() == snapshot.localStateHash()
                                && lane.localFundsHash() == snapshot.localFundsHash());
                if (!unchanged) {
                    throw new IllegalStateException("account lane changed during snapshot capture");
                }
            }
            return java.util.List.copyOf(snapshots);
        } finally {
            if (acquired) {
                releaseOwnerLaneAccess();
            }
        }
    }

    public void requireSnapshotFenceReady() {
        assertOwner();
        if (controlLanes.pending()) throw new IllegalStateException("control Lane work is unfinished");
        if (accountLanesStarted) {
            for (SettlementLaneWorker worker : laneWorkers) {
                if (worker != null && worker.depth() != 0)
                    throw new IllegalStateException("Matcher settlement ring is unfinished");
            }
        }
        pendingReservations.assertPendingReservationCounts();
        if (pendingReservations.totalPendingReservations() != 0 || !pendingReservations.sequenceIndexEmpty()
                || !pendingReservations.pendingUserIndexEmpty() || snapshotProjectionStateDirty()) {
            throw new IllegalStateException("runtime contains unfinished reservation or patch work");
        }
    }

    /** 包含尚未提升 revision 的 Lane 修改，失败时也必须回滚。 */
    public boolean hasUncommittedCommandChanges() {
        assertOwner();
        return snapshotProjectionStateDirty() || (systemLedger == null ? treasury.hasChangedValues() : ledgerControl != null);
    }

    boolean snapshotProjectionStateDirty() {
        return !changedUsers.isEmpty() || balancesChanged || !changedOrders.isEmpty()
                || !changedReservations.isEmpty() || !changedPositions.isEmpty()
                || !changedLiquidations.isEmpty() || !changedMarkPrices.isEmpty()
                || !changedRiskSnapshots.isEmpty() || !changedRiskScans.isEmpty()
                || !changedLeverages.isEmpty()
                || !changedAlgoOrders.isEmpty() || !changedCancelAllAfterTimers.isEmpty()
                || !changedTriggerOrders.isEmpty() || !changedFeePolicies.isEmpty()
                || accountRollback.hasCaptured();
    }

    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        assertOwner();
        validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, topology);
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            for (Long userId : snapshot.userIds()) {
                if (!onLane(laneId, lane -> lane.user(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            onLane(currentLaneId, lane -> {
                lane.forEachUserId(userId -> {
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
                publishLaneCommittedSequence(lane);
                return null;
            });
        }
        pendingReservations.clearRestoredPendingReservations();
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
        if (owner == null && OWNER_THREAD.compareAndSet(this, null, current)) return;
        if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == current) return;
        if (owner == null) { bindOwner(); return; }
        if (currentLaneCommandScope() != null) return;
        bindOwner();
    }

    public boolean inOwnerThread() {
        Thread current = Thread.currentThread();
        return owner == current || owner == null;
    }

    public void releaseOwnerForHandoff() {
        if (accountLanesStarted || systemLedger != null) throw new IllegalStateException("all lanes must be closed before handoff");
        for (AccountLaneState lane : accountLanes) lane.releaseOwnerForHandoff();
        treasury.releaseOwnerForHandoff();
        owner = null;
    }

    public ProductLine productLine() {
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
        if (snapshotProjectionStateDirty() || (systemLedger == null ? treasury.hasChangedValues() : ledgerControl != null)) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        orderBatchMutationScope = true;
        if (systemLedger == null) treasury.beginOrderBatchMutationScope();
    }

    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!orderBatchMutationScope) return;
        if (systemLedger == null) treasury.endOrderBatchMutationScope();
        orderBatchMutationScope = false;
    }

    void rejectUnsupportedOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    /** Failure-only continuation; before images remain live until all Lane restoration completes. */
    public java.util.function.BooleanSupplier beginCommandRollback(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        if (!asynchronousCommands() || revisionCheckpoint < 0 || coreSequence <= 0)
            throw new IllegalArgumentException("invalid asynchronous rollback");
        if (realtimeCapture != null && realtimeCapture.privateStateEnabled()) realtimeCapture.abort();
        long[] pendingOrders = pendingReservations.pendingOrderIds(coreSequence);
        PendingReservationTracker.PendingBatch batch = pendingReservations.batch(coreSequence);
        int expectedPending = pendingOrders.length + (batch == null ? 0 : batch.remaining());
        long laneMask = accountLanes.length == 64 ? -1L : (1L << accountLanes.length) - 1;
        dispatchControlLanes(laneMask, id -> {
            AccountLaneState lane = currentLaneCommandScope();
            int completed = 0;
            for (long orderId : pendingOrders) {
                long userId = pendingReservations.pendingUser(orderId);
                if (topology.accountLaneId(userId) != id) continue;
                lane.completePendingReservation(orderId, coreSequence);
                completed++;
            }
            if (batch != null && topology.accountLaneId(batch.userId()) == id) {
                for (int index = 0; index < batch.count(); index++) {
                    OrderRuntime order = batch.order(index);
                    if (order == null || lane.pendingReservationSequence(order.orderId()) != coreSequence)
                        continue;
                    lane.completePendingReservation(order.orderId(), coreSequence);
                    completed++;
                }
            }
            accountRollback.restoreLane(lane);
            return completed;
        });
        return new java.util.function.BooleanSupplier() {
            private boolean ownerRestored;
            @Override public boolean getAsBoolean() {
                if (!ownerRestored) {
                    if (!pollControlLanes()) return false;
                    int completed = 0;
                    for (int id = 0; id < accountLanes.length; id++)
                        completed = Math.addExact(completed, (Integer) controlLaneResult(id));
                    if (completed != expectedPending) throw new IllegalStateException("rollback reservation count mismatch");
                    for (long orderId : pendingOrders) {
                        long userId = pendingReservations.pendingUser(orderId);
                        pendingReservations.unindexPendingReservation(orderId, coreSequence, userId,
                                Math.subtractExact(pendingReservations.totalPendingReservations(), 1));
                    }
                    if (batch != null) pendingReservations.completedBatchItems(coreSequence, batch.remaining());
                    accountRollback.restoreOwner();
                    ownerRestored = true;
                }
                if (!rollbackLedgerControl()) return false;
                ledgerControl = null;
                revision = revisionCheckpoint;
                clearChangedKeys();
                return true;
            }
        };
    }

    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        // An aborted business patch may have staged provisional private values. Drop the outer
        // realtime batch; authoritative snapshots heal it. Reliable replay captures committed fills only.
        if (realtimeCapture != null && realtimeCapture.privateStateEnabled()) realtimeCapture.abort();
        if (revisionCheckpoint < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (pendingReservations.containsSequence(coreSequence)
                || pendingReservations.hasBatch(coreSequence)) {
            pendingReservations.completePendingReservations(coreSequence);
        }
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                accountRollback.restoreLane(lane);
                return null;
            });
        }
        accountRollback.restoreOwner();
        if (systemLedger != null && ledgerControl != null)
            throw new IllegalStateException("active Ledger rollback requires its asynchronous continuation");
        if (systemLedger == null) treasury.rollbackChangedValues();
        revision = revisionCheckpoint;
        clearChangedKeys();
    }

    static <K, V> void putOrRemove(Map<K, V> values, K key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    static <V> void putOrRemove(IntObjectHashMap<V> values, int key, V value) {
        if (value == null) values.remove(key); else values.put(key, value);
    }

    public void requireRevisionCapacity() {
        assertOwner();
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("runtime command revision overflow");
        }
    }

    /** Lane 控制任务只产生版本增量，Owner 收集成功结果后统一发布全局版本。 */
    public void incrementCommandRevision() {
        assertOwner();
        if (controlLanes.active()) {
            controlLanes.recordRevisionDelta();
            return;
        }
        setMetadata(productLine, Math.incrementExact(revision));
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
        this.riskScanControl = riskScanControl;
    }

    public com.surprising.aeron.service.state.admission.AdmissionOrderIndex admissionOrders(long userId, int symbolId) {
        return onLane(userId, lane -> lane.admissionOrderIndex(symbolId));
    }

    private void requireAccountReadOwnership() {
        if (accountLanesStarted && currentLaneCommandScope() == null && !ownerLaneAccess)
            throw new IllegalStateException("account read requires its owning Lane thread");
    }

    public UserRuntime user(long userId) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.laneId() == laneId ? scoped.user(userId) : null;
        }
        checkLaneAccess();
        AccountLaneState lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return lane.user(userId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    public UserRuntime requireUser(long userId) {
        UserRuntime user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    public UserRuntime user(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        return lane.user(userId);
    }

    public BalanceRuntime balance(long userId, int assetId) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            IntObjectHashMap<BalanceRuntime> balances = scoped.userBalances(userId);
            return balances == null ? null : balances.get(assetId);
        }
        checkLaneAccess();
        AccountLaneState lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return copyBalance(lane.userBalances(userId), assetId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    private void checkLaneAccess() {
        if (Thread.currentThread() == owner && accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
    }

    public BalanceRuntime balance(AccountLaneState lane, long userId, int assetId) {
        requireLaneAccess(lane, userId);
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        return balances == null ? null : balances.get(assetId);
    }

    public long publishedAvailableBalance(long userId, int assetId) {
        assertOwner();
        BalanceRuntime balance = balance(userId, assetId);
        return balance == null ? Long.MIN_VALUE : balance.availableUnits();
    }

    /** Read-only query access to the owning lane's balance table. */
    public IntObjectHashMap<BalanceRuntime> balancesForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> balancesForUser(lane, userId));
    }

    public IntObjectHashMap<BalanceRuntime> balancesForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        return copyBalances(lane.userBalances(userId));
    }

    public long nextRiskReservationId(long userId, long cursor) {
        AccountLaneState lane = riskCursorLane(userId);
        var ids = lane.reservationIds(userId);
        long selected = 0;
        if (ids != null) {
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                long id = iterator.nextValue();
                if (id > cursor && (selected == 0 || id < selected)) selected = id;
            }
        }
        return selected;
    }

    public long nextRiskPositionKey(long userId, String cursor, RuntimeIdentityRegistry identities) {
        AccountLaneState lane = riskCursorLane(userId);
        LongHashSet ids = lane.positionKeys(userId);
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
        requireAccountReadOwnership();
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("risk cursor must run in the user's Lane");
        }
        lane.assertOwner();
        return lane;
    }

    /** 使用账户已有索引检查全部币对，避免持仓模式修改扫描其他账户或借用其 Lane。 */
    public boolean hasOpenAccountExposure(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            var orders = lane.activeOrderIds(userId);
            if (orders != null && !orders.isEmpty()) return true;
            LongHashSet positions = lane.positionKeys(userId);
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    PositionRuntime position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0) return true;
                }
            }
            var reservations = lane.reservationIds(userId);
            if (reservations != null) {
                var it = reservations.iterator();
                while (it.hasNext()) {
                    ReservationRuntime reservation = lane.reservation(it.nextValue());
                    if (reservation != null && reservation.reservedUnits() != 0) return true;
                }
            }
            return false;
        });
    }

    /** 杠杆修改只遍历所属账户现有索引，不复制整个产品的订单和持仓。 */
    public boolean hasOpenLeverageExposure(CoreLeverageKey key, int symbolId) {
        assertOwner();
        return onLane(key.userId(), lane -> {
            var orders = lane.activeOrderIds(key.userId());
            if (orders != null) {
                var it = orders.iterator();
                while (it.hasNext()) {
                    OrderRuntime order = lane.order(it.nextValue());
                    if (order != null && order.status() == CoreOrderStatus.OPEN
                            && order.symbolId() == symbolId && order.marginMode() == key.marginMode()) return true;
                }
            }
            LongHashSet positions = lane.positionKeys(key.userId());
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    PositionRuntime position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0
                            && position.symbolId() == symbolId && position.marginMode() == key.marginMode()) return true;
                }
            }
            return false;
        });
    }

    public void markUserChanged(long userId) {
        assertOwner();
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedUsers.add(userId);
        else laneDelta(lane.laneId()).putUser(userId, lane.user(userId));
    }

    /** Read-only query access to the owning lane's reservation ids. */
    public LongHashSet reservationIdsForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> reservationIdsForUser(lane, userId));
    }

    public int reservationCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> reservationCountForUser(lane, userId));
    }

    public LongHashSet reservationIdsForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        var ids = lane.reservationIds(userId);
        LongHashSet result = new LongHashSet(ids == null ? 0 : ids.size());
        if (ids != null) ids.forEachLong(result::add);
        return result;
    }

    public int reservationCountForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        var ids = lane.reservationIds(userId);
        return ids == null ? 0 : ids.size();
    }

    /** Read-only query access to the owning lane's position keys. */
    public LongHashSet positionKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> positionKeysForUser(lane, userId));
    }

    public int positionCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> positionCountForUser(lane, userId));
    }

    public LongHashSet positionKeysForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? new LongHashSet() : new LongHashSet(keys);
    }

    public int positionCountForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? 0 : keys.size();
    }

    public NavigableSet<CoreLeverageKey> leverageKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> leverageKeysForUser(lane, userId));
    }

    public NavigableSet<CoreLeverageKey> leverageKeysForUser(AccountLaneState lane, long userId) {
        requireLaneAccess(lane, userId);
        Set<CoreLeverageKey> keys = lane.leverageKeys(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
    }

    public Long leverage(AccountLaneState lane, CoreLeverageKey key) {
        requireLaneAccess(lane, key.userId());
        return lane.leverage(key);
    }

    public OrderRuntime order(AccountLaneState lane, long orderId) {
        requireLaneAccess(lane);
        return lane.order(orderId);
    }

    public ReservationRuntime reservation(AccountLaneState lane, long orderId) {
        requireLaneAccess(lane);
        return lane.reservation(orderId);
    }

    public PositionRuntime position(AccountLaneState lane, long positionKey) {
        requireLaneAccess(lane);
        return lane.position(positionKey);
    }

    public OrderRuntime order(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            OrderRuntime order = scoped.order(orderId);
            if (order != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    return null;
                }
                return order;
            }
            return null;
        }
        if (factIndexes != null) {
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                AccountLaneState lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                OrderRuntime order = lane.order(orderId);
                if (order != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                            return order;
                        }
                        if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                            return order;
                        }
                        return null;
                    }
                    return order;
                }
            }
        }
        for (int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            OrderRuntime order = accountLanes[i].order(orderId);
            if (order != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    continue;
                }
                return order;
            }
        }
        return null;
    }

    public ReservationRuntime reservation(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            ReservationRuntime reservation = scoped.reservation(orderId);
            if (reservation != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                        return reservation;
                    }
                    return null;
                }
                return reservation;
            }
            return null;
        }
        if (factIndexes != null) {
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                AccountLaneState lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                ReservationRuntime reservation = lane.reservation(orderId);
                if (reservation != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                            return reservation;
                        }
                        return null;
                    }
                    return reservation;
                }
            }
        }
        for (int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            ReservationRuntime reservation = accountLanes[i].reservation(orderId);
            if (reservation != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || orderBatchMutationScope) {
                        return reservation;
                    }
                    continue;
                }
                return reservation;
            }
        }
        return null;
    }

    public OrderRuntime provisionalOrder(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.order(orderId);
        }
        if (factIndexes != null) {
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                AccountLaneState lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                OrderRuntime order = lane.order(orderId);
                if (order != null) return order;
            }
        }
        for (int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            OrderRuntime order = accountLanes[i].order(orderId);
            if (order != null) return order;
        }
        return null;
    }

    public PositionRuntime position(long positionKey) {
        assertOwner();
        requireAccountReadOwnership();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.position(positionKey);
        }
        if (indexIdentities != null) {
            var identity = indexIdentities.positionIdentity(positionKey);
            if (identity != null) {
                AccountLaneState lane = accountLanes[topology.accountLaneId(identity.userId())];
                lane.assertOwner();
                PositionRuntime position = lane.position(positionKey);
                if (position != null) return position;
            }
        }
        for (int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            PositionRuntime position = accountLanes[i].position(positionKey);
            if (position != null) return position;
        }
        return null;
    }

    public NavigableSet<Long> positionKeysForUserAndSymbol(long userId, int symbolId) {
        assertOwner();
        return onLane(userId, lane -> {
            LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                    lane.symbolPositionKeys(symbolId);
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser == null ? null : byUser.get(userId);
            return (keys == null || keys.isEmpty()) ? Collections.emptyNavigableSet()
                    : Collections.unmodifiableNavigableSet(toSortedSet(keys));
        });
    }

    /**
     * Lane-scoped primitive access for lifecycle scans. The returned list belongs to the current
     * Lane and must only be consumed before the command scope exits.
     */
    public org.eclipse.collections.impl.list.mutable.primitive.LongArrayList positionKeysForUserAndSymbolPrimitive(
            long userId, int symbolId) {
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null || scoped.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("primitive position index access requires owning Lane scope");
        }
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                scoped.symbolPositionKeys(symbolId);
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? EMPTY_POSITION_KEYS : keys;
    }

    public LiquidationRuntime liquidation(long liquidationId) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.liquidation(liquidationId);
        requireAccountReadOwnership();
        for (AccountLaneState lane : accountLanes) {
            lane.assertOwner();
            LiquidationRuntime value = lane.liquidation(liquidationId);
            if (value != null) return value;
        }
        return null;
    }

    public LiquidationRuntime activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        return onLane(userId, lane -> {
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(userId);
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidation(liquidationId);
        });
    }

    public boolean hasActiveLiquidationConflict(long userId, int symbolId, long excludedLiquidationId) {
        return hasLiquidationConflict(userId,symbolId,excludedLiquidationId,false);
    }

    /** 全局配置屏障之后读取已提交索引；不复制账户快照或请求账户所有权。 */
    public boolean hasUnresolvedLiquidation(String instrumentId, int symbolId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("instrument check belongs to Core owner");
        if (factIndexes != null) return factIndexes.hasUnresolvedLiquidation(
                symbolId < 0 ? null : instrumentId);
        requireAccountReadOwnership();
        for (AccountLaneState lane : accountLanes) {
            lane.assertOwner();
            if (lane.anyLiquidation(value ->
                    (symbolId < 0 || value.symbolId() == symbolId)
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED)) return true;
        }
        return false;
    }

    boolean hasLiquidationConflict(long userId, int symbolId, long excludedLiquidationId, boolean includeDebt) {
        assertOwner();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            if (userId != 0 && laneId != topology.accountLaneId(userId)) continue;
            boolean conflict = onLane(laneId, lane -> {
                for (LiquidationRuntime liquidation : lane.liquidationValuesForRead()) {
                    if (liquidation.liquidationId() != excludedLiquidationId
                            && (liquidation.status() == CoreLiquidationState.Status.PLANNED
                            || liquidation.status() == CoreLiquidationState.Status.ORDERED
                            || includeDebt && liquidation.status() != CoreLiquidationState.Status.COMPLETED
                                && liquidation.status() != CoreLiquidationState.Status.CANCELED)
                            && (userId == 0 || liquidation.userId() == userId)
                            && (symbolId < 0 || liquidation.symbolId() == symbolId)) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            });
            if (conflict) return true;
        }
        return false;
    }

    public MarkPriceRuntime markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    public MarkPriceRuntime markPrice(AccountLaneState lane, int symbolId) {
        lane.assertOwner();
        return markPrices.get(symbolId);
    }

    public RiskSnapshotRuntime riskSnapshot(long positionKey) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.riskSnapshot(positionKey);
        requireAccountReadOwnership();
        for (AccountLaneState lane : accountLanes) {
            lane.assertOwner();
            RiskSnapshotRuntime value = lane.riskSnapshot(positionKey);
            if (value != null) return value;
        }
        return null;
    }

    public RiskScanRuntime riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    /** Bounded query over committed positions, including those not yet visited by the risk scan. */
    public LongHashSet usersWithPublishedPositions(int maximumPositions) {
        assertOwner();
        requireAccountReadOwnership();
        LongHashSet users = new LongHashSet();
        int totalPositions = 0;
        AccountLaneState scoped = currentLaneCommandScope();
        for (AccountLaneState lane : accountLanes) {
            if (scoped != null && lane != scoped) continue;
            lane.assertOwner();
            totalPositions = Math.addExact(totalPositions, lane.positionCount());
            for (PositionRuntime position : lane.positionValuesForRead())
                if (position.signedQuantitySteps() != 0) users.add(position.userId());
        }
        if (totalPositions > maximumPositions)
            throw new com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException();
        return users;
    }

    public int publishedPositionCount() {
        bindOwner();
        return publishedPositions;
    }

    public void publishLanePositionCount(int laneId, int count) {
        bindOwner();
        if (laneId < 0 || laneId >= publishedLanePositionCounts.length || count < 0)
            throw new IllegalArgumentException("invalid Lane position count receipt");
        int total = Math.addExact(publishedPositions,
                Math.subtractExact(count, publishedLanePositionCounts[laneId]));
        publishedLanePositionCounts[laneId] = count;
        publishedPositions = total;
    }

    public RiskScanRuntime firstIncompleteRiskScan() {
        assertOwner();
        RiskScanRuntime selected = null;
        for (RiskScanRuntime scan : riskScans.values()) {
            if (!scan.complete()
                    && (selected == null || scan.symbolId() < selected.symbolId())) {
                selected = scan;
            }
        }
        return selected;
    }

    public RiskScanRuntime firstRiskIncompleteScan() {
        assertOwner();
        RiskScanRuntime selected = null;
        for (RiskScanRuntime scan : riskScans.values()) {
            if (!scan.riskComplete() && (selected == null
                    || compareRiskProgress(scan, selected) < 0)) {
                selected = scan;
            }
        }
        return selected;
    }

    static int compareRiskProgress(RiskScanRuntime left, RiskScanRuntime right) {
        int scheduled = Long.compare(left.lastScheduledRevision(), right.lastScheduledRevision());
        return scheduled != 0 ? scheduled : Integer.compare(left.symbolId(), right.symbolId());
    }

    public int incompleteRiskScanCount() {
        assertOwner();
        int count = 0;
        for (RiskScanRuntime scan : riskScans.values()) {
            if (!scan.complete()) {
                count++;
            }
        }
        return count;
    }

    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    /** 冷查询只取得不可变导出值，不向Owner交接Treasury所有权。 */
    public CoreTreasuryState treasuryForQuery(RuntimeIdentityRegistry identities) {
        return queryLedgerAtRest(ledger -> RuntimeStateMaterializer.materializeTreasury(ledger, identities));
    }

    /** 冷导出边界的同步契约；生产查询使用非阻塞dispatchLedgerQuery。 */
    public <T> T queryLedgerAtRest(java.util.function.Function<TreasuryRuntime, T> read) {
        assertOwner();
        java.util.Objects.requireNonNull(read, "Ledger read is required");
        if (ledgerControl != null) throw new IllegalStateException("Treasury query requires committed control work");
        if (systemLedger == null) return read.apply(treasury);
        final class Query implements SystemLedgerLane.Entry {
            private T value;
            private RuntimeException failure;
            private volatile boolean complete;
            @Override public void apply(TreasuryRuntime ledger) {
                try { value = read.apply(ledger); }
                catch (RuntimeException invalidQuery) { failure = invalidQuery; }
                finally { complete = true; }
            }
        }
        Query query = new Query();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!systemLedger.offer(query)) {
            systemLedger.assertHealthy();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Treasury query mailbox timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(this, 100_000L);
        }
        while (!query.complete) {
            systemLedger.assertHealthy();
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Treasury query completion timeout");
            java.util.concurrent.locks.LockSupport.parkNanos(this, 100_000L);
        }
        if (query.failure != null) throw query.failure;
        return query.value;
    }

    public boolean dispatchLedgerQuery(SystemLedgerLane.Entry query) {
        assertOwner();
        if (query == null) throw new IllegalArgumentException("Ledger query is required");
        if (ledgerControl != null) throw new IllegalStateException("Ledger query requires committed control work");
        if (systemLedger == null) { query.apply(treasury); return true; }
        return systemLedger.offer(query);
    }

    /** Risk belongs to the current command: retain its index before-values in the same Ledger scope. */
    public boolean dispatchLedgerRiskInput(RiskScanCoordinator risk) {
        assertOwner();
        if (ledgerControl != null && !ledgerControl.allowsRiskInput())
            throw new IllegalStateException("risk input requires a completed financial phase before scope close");
        risk.bindLedgerScope(ledgerControl);
        if (systemLedger == null) { risk.apply(treasury); return true; }
        return systemLedger.offer(risk);
    }

    public TreasuryRuntime treasury() {
        assertOwner();
        if (currentLaneCommandScope() != null) {
            throw new IllegalStateException("Account Lane cannot access SystemLedger-owned Treasury");
        }
        return treasury;
    }

    public RuntimeTreasuryDelta scratchTreasuryDelta() {
        assertOwner();
        scratchTreasuryDelta.clear();
        return scratchTreasuryDelta;
    }

    /** Borrowed configuration names, consumed on Owner at the explicit query-input boundary. */
    public Iterable<String> instrumentIdsForQuery() {
        assertOwner();
        return () -> new java.util.Iterator<>() {
            private final java.util.Iterator<CoreInstrument> values = instruments.values().iterator();
            public boolean hasNext() { return values.hasNext(); }
            public String next() { return values.next().instrumentId(); }
        };
    }

    public CoreInstrument instrument(String instrumentId) {
        return instrument(com.surprising.product.api.InstrumentIds.parse(instrumentId));
    }

    public CoreInstrument instrument(int instrumentId) {
        assertOwner();
        return instruments.get(instrumentId);
    }

    public CoreInstrument instrument(AccountLaneState lane, String instrumentId) {
        lane.assertOwner();
        return instruments.get(com.surprising.product.api.InstrumentIds.parse(instrumentId));
    }

    public void registerInstrument(CoreInstrument instrument) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument registry");
        if (instrument == null || instruments.containsKey(instrument.instrumentIdValue())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is already registered");
        }
        instruments.put(instrument.instrumentIdValue(), instrument);
    }

    public void updateInstrumentConfiguration(CoreInstrument instrument, CoreInstrument updated) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument configuration");
        if (instrument == null || updated == null || instruments.get(instrument.instrumentIdValue()) != instrument
                || instrument.instrumentIdValue() != updated.instrumentIdValue()) {
            throw new IllegalArgumentException("invalid canonical instrument configuration update");
        }
        instrument.updateConfiguration(updated);
    }

    public void updateInstrumentMaintenance(
            CoreInstrument instrument,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument operation");
        if (instrument == null || maintenance == null || instruments.get(instrument.instrumentIdValue()) != instrument) {
            throw new IllegalArgumentException("invalid canonical instrument operation");
        }
        instrument.updateMaintenance(maintenance);
    }

    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), lane -> lane.leverage(key));
    }

    /**
     * Lookup the configured leverage without allocating a CoreLeverageKey.
     * Callers must pass the already canonical instrument instrumentId held by the
     * runtime identity dictionary; normalization belongs at the command edge.
     */
    public Long leverage(long userId, String canonicalSymbol, CoreMarginMode marginMode) {
        assertOwner();
        if (userId <= 0 || canonicalSymbol == null || marginMode == null) {
            throw new IllegalArgumentException("invalid runtime leverage lookup");
        }
        requireAccountReadOwnership();
        AccountLaneState scopedLane = currentLaneCommandScope();
        if (scopedLane != null && scopedLane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("leverage read crossed its owning Lane");
        LeverageLookup lookup = scopedLane == null ? ownerLeverageLookup : scopedLane.leverageLookup();
        lookup.set(userId, canonicalSymbol, marginMode);
        try {
            return accountLanes[topology.accountLaneId(userId)].leverage(lookup);
        } finally {
            lookup.clear();
        }
    }

    public Long leverage(AccountLaneState lane, long userId, String canonicalSymbol, CoreMarginMode marginMode) {
        requireLaneAccess(lane, userId);
        LeverageLookup lookup = lane.leverageLookup();
        lookup.set(userId, canonicalSymbol, marginMode);
        try { return lane.leverage(lookup); }
        finally { lookup.clear(); }
    }

    public void putLeverage(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("leverage state");
        if (key == null || leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("invalid runtime leverage");
        }
        onLane(key.userId(), lane -> {
            lane.storeLeverage(key, leveragePpm);
            HashSet<CoreLeverageKey> userKeys = lane.leverageKeys(key.userId());
            if (userKeys == null) {
                userKeys = new HashSet<>();
                lane.storeLeverageKeys(key.userId(), userKeys);
            }
            userKeys.add(key);
            return null;
        });
        if (currentLaneCommandScope() == null) recordLeverageChange(key, leveragePpm);
    }

    /** Lane 完成后由 Owner 收集，避免账户线程修改共享推送捕获器。 */
    public void recordLeverageChange(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        changedLeverages.add(key);
        if (realtimeCapture != null) realtimeCapture.leverage(key, leveragePpm);
    }

    public com.surprising.aeron.service.state.index.AlgoOrderIndex.AlgoRoute algoRoute(long id) {
        assertOwner(); return factIndexes == null ? null : factIndexes.algoRoute(id);
    }

    public com.surprising.aeron.service.state.index.TriggerOrderIndex.TriggerRoute triggerRoute(long id) {
        assertOwner();
        if (factIndexes != null) return factIndexes.triggerRoute(id);
        requireAccountReadOwnership();
        var value = triggerOrder(id);
        return value == null ? null : com.surprising.aeron.service.state.index.TriggerOrderIndex.TriggerRoute.from(value);
    }

    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.algoOrder(algoOrderId);
        requireAccountReadOwnership();
        for (AccountLaneState lane : accountLanes) {
            lane.assertOwner();
            CoreAlgoOrderState value = lane.algoOrder(algoOrderId);
            if (value != null) return value;
        }
        return null;
    }

    public boolean hasAlgoClient(long userId, String clientId) {
        assertOwner();
        return onLane(userId, lane -> lane.anyAlgoOrder(
                value -> value.userId() == userId && value.clientAlgoOrderId().equals(clientId)));
    }

    public void publishAlgoOrder(CoreAlgoOrderState order) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped == null) changedAlgoOrders.put(order.algoOrderId(), order);
        else laneDelta(scoped.laneId()).putAlgo(order.algoOrderId(), order);
    }

    public void putAlgoOrder(CoreAlgoOrderState algoOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        if (algoOrder == null) throw new IllegalArgumentException("invalid runtime algo order");
        onLane(algoOrder.userId(), lane -> {
            lane.storeAlgoOrder(algoOrder.algoOrderId(), algoOrder);
            publishAlgoOrder(algoOrder);
            return null;
        });
    }

    public void removeAlgoOrder(long algoOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("algo-order state");
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            scoped.removeAlgoOrderValue(algoOrderId);
            laneDelta(scoped.laneId()).putAlgo(algoOrderId, null);
        } else {
            for (int laneId = 0; laneId < accountLanes.length; laneId++)
                onLane(laneId, lane -> { lane.removeAlgoOrderValue(algoOrderId); return null; });
            changedAlgoOrders.put(algoOrderId, null);
        }
    }

    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    public void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    /** Command-local trigger receipt, retained only until ordered publication clears changes. */
    public CoreTriggerOrderState changedTriggerOrder(long id) { assertOwner(); return changedTriggerOrders.get(id); }

    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.triggerOrder(triggerOrderId);
        requireAccountReadOwnership();
        for (AccountLaneState lane : accountLanes) {
            lane.assertOwner();
            CoreTriggerOrderState value = lane.triggerOrder(triggerOrderId);
            if (value != null) return value;
        }
        return null;
    }

    public void putTriggerOrder(CoreTriggerOrderState triggerOrder) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (triggerOrder == null) throw new IllegalArgumentException("invalid runtime trigger order");
        accountRollback.captureTriggerOrderBefore(triggerOrder.triggerOrderId(), triggerOrder.userId());
        onLane(triggerOrder.userId(), lane -> {
            lane.putTrigger(triggerOrder);
            publishTriggerOrder(triggerOrder.triggerOrderId(), triggerOrder);
            return null;
        });
    }

    public void putTriggerOrder(AccountLaneState lane, CoreTriggerOrderState triggerOrder) {
        requireLaneAccess(lane, triggerOrder.userId());
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("trigger completion requires its settlement Lane");
        lane.putTrigger(triggerOrder);
        laneDelta(lane).putTrigger(triggerOrder.triggerOrderId(), triggerOrder);
    }

    public void removeTriggerOrder(long triggerOrderId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        CoreTriggerOrderState current = triggerOrder(triggerOrderId);
        if (current != null) {
            accountRollback.captureTriggerOrderBefore(triggerOrderId, current.userId());
        }
        AccountLaneState scoped = currentLaneCommandScope();
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

    public Map<String, CoreInstrument> instrumentsForRuntime() {
        assertOwner();
        Map<String, CoreInstrument> exported = new TreeMap<>();
        instruments.forEachValue(instrument -> exported.put(instrument.instrumentId(), instrument));
        return exported;
    }

    public Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.leveragesSnapshot()));
        }
        return values;
    }

    public Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++)
            onLaneReadOnly(laneId, lane -> { lane.forEachAlgoOrder(values::put); return null; });
        return values;
    }

    public Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    public boolean hasTriggerClient(long userId, String clientId) {
        return onLane(userId, lane -> {
            LongHashSet ids = lane.triggerIds(userId);
            if (ids == null) return false;
            var iterator = ids.longIterator();
            while (iterator.hasNext()) {
                if (lane.triggerOrder(iterator.next()).clientTriggerOrderId().equals(clientId)) return true;
            }
            return false;
        });
    }

    public long projectedTriggerCapacity(long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView view) {
        return onLane(userId, lane -> {
            long capacity = 0;
            long sameOcoMax = 0;
            LongHashSet ids = lane.triggerIds(userId);
            if (ids != null) {
                var iterator = ids.longIterator();
                while (iterator.hasNext()) {
                    CoreTriggerOrderState trigger = lane.triggerOrder(iterator.next());
                    if (!trigger.status().open() || !trigger.instrumentId().equals(view.instrumentId())
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

    public Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
            scoped.forEachTriggerOrder(values::put);
            return values;
        }
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> {
                lane.forEachTriggerOrder(values::put);
                return null;
            });
        }
        return values;
    }

    public long pendingTransferUser(long transferId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("transfer routing belongs to Owner");
        return treasury.pendingTransferUser(transferId);
    }

    public TransferRuntime pendingTransfer(long transferId) {
        assertOwner();
        AccountLaneState lane = currentLaneCommandScope();
        if (lane != null) return lane.pendingTransfer(transferId);
        if (accountLanesStarted) throw new IllegalStateException("active transfer identity must be read on its Account Lane");
        long userId = pendingTransferUser(transferId);
        return userId == 0 ? null : accountLanes[topology.accountLaneId(userId)].pendingTransfer(transferId);
    }

    public Map<Long, TransferRuntime> pendingTransfersSnapshot() {
        assertOwner();
        if (accountLanesStarted && !ownerLaneAccess) {
            throw new IllegalStateException("pending transfers snapshot requires owner lane access");
        }
        TreeMap<Long, TransferRuntime> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> { lane.forEachPendingTransfer(values::put); return null; });
        }
        return Collections.unmodifiableMap(values);
    }

    /** Per-query routing only, bounded by the protocol page size; no account payload is captured here. */
    public record PendingTransferRoutes(long[] ids, long[] users, long laneMask) { }

    public PendingTransferRoutes pendingTransferRoutes(int limit) {
        assertOwner();
        TreasuryRuntime.PendingTransferRoutes routes = treasury.pendingTransferRoutes(limit, topology::accountLaneMask);
        return new PendingTransferRoutes(routes.ids(), routes.users(), routes.laneMask());
    }

    public void completePendingTransferPage(PendingTransferRoutes page) {
        assertOwner();
        treasury.completePendingTransferPage(page.ids().length == 0 ? 0 : page.ids()[page.ids().length - 1]);
    }

    public List<TransferRuntime> pendingTransfers(int limit) {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("active transfer queries must run on Account Lanes");
        PendingTransferRoutes page = pendingTransferRoutes(limit);
        var values = new java.util.ArrayList<TransferRuntime>(page.ids().length);
        for (long id : page.ids()) values.add(pendingTransfer(id));
        completePendingTransferPage(page);
        return List.copyOf(values);
    }

    public void restorePendingTransfers(Map<Long, TransferRuntime> restored) {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("transfer restore requires stopped Account Lanes");
        if (restored == null || restored.size() > MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId()))
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        treasury.clearPendingTransfers();
        for (AccountLaneState lane : accountLanes) lane.clearPendingTransferValues();
        restored.forEach((id, transfer) -> {
            treasury.restorePendingTransferRoute(id, transfer.userId());
            accountLanes[topology.accountLaneId(transfer.userId())].storePendingTransfer(id, transfer);
        });
    }

    public boolean hasPendingTransferCapacity() {
        assertOwner();
        return treasury.hasPendingTransferCapacity();
    }

    public void registerPendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.registerPendingTransferRoute(id, userId);
    }

    public void completePendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.completePendingTransferRoute(id, userId);
    }

    public void putPendingTransfer(TransferRuntime transfer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted) throw new IllegalStateException("active transfer writes require their Account Lane");
            if (!hasPendingTransferCapacity())
                throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
            lane = accountLanes[topology.accountLaneId(transfer.userId())];
        }
        if (lane.laneId() != topology.accountLaneId(transfer.userId()) || lane.hasPendingTransfer(transfer.transferId()))
            throw new IllegalArgumentException("pending transfer already exists or reached the wrong Lane");
        lane.storePendingTransfer(transfer.transferId(), transfer);
        if (!accountLanesStarted) registerPendingTransferRoute(transfer.transferId(), transfer.userId());
    }

    public boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted) throw new IllegalStateException("active transfer completion requires its Account Lane");
            long routed = pendingTransferUser(transferId);
            if (routed == 0) return false;
            if (routed != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane.laneId() != topology.accountLaneId(userId)) throw new IllegalStateException("transfer completion crossed Account Lane");
        TransferRuntime current = lane.pendingTransfer(transferId);
        if (current == null) return false;
        if (current.userId() != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        requireRevisionCapacity();
        lane.removePendingTransferValue(transferId);
        if (!accountLanesStarted) completePendingTransferRoute(transferId, userId);
        return true;
    }

    public FeePolicyBook feePolicyBook() {
        assertOwner();
        return feePolicies;
    }

    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return feePolicies.snapshot();
    }

    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (accountLanesStarted) throw new IllegalStateException("fee policy restore requires stopped Lanes");
        feePolicies = FeePolicyBook.from(restored);
        changedFeePolicies.clear();
    }

    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        if (accountLanesStarted && (currentLaneCommandScope() == null || currentLaneCommandScope().laneId() != 0))
            throw new IllegalStateException("active fee policy updates must run on configuration Lane 0");
        rejectUnsupportedOrderBatchMutation("fee-policy state");
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        FeePolicyBook updated = feePolicies.upsert(next);
        if (updated == feePolicies) return;
        requireRevisionCapacity();
        feePolicies = updated;
        changedFeePolicies.add(next.policyId());
        incrementCommandRevision();
    }

    public void replaceAuxiliaryState(TradingCoreState source) {
        assertOwner();
        setMetadata(source.productLine(), source.revision());
        setRiskScanControl(source.riskState().scanControl());
        instruments.clear();
        source.instruments().values().forEach(instrument -> instruments.put(instrument.instrumentIdValue(), instrument));
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLane(laneId, lane -> {
                lane.clearLeverageValues();
                lane.clearLeverageKeys();
                lane.clearAlgoOrderValues();
                lane.clearTriggerOrderValues();
                lane.clearTriggerIds();
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
        long orderId = orderIdByClientValue(userId, clientKey);
        return orderId == 0 ? null : orderId;
    }

    public long orderIdByClientValue(long userId, long clientKey) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != topology.accountLaneId(userId)) {
                throw new IllegalStateException("client-order lookup crossed its owner Lane");
            }
            return orderIdByClientValue(scoped, userId, clientKey);
        }
        return onLane(userId, lane -> {
            return orderIdByClientValue(lane, userId, clientKey);
        });
    }

    public long orderIdByClientValue(AccountLaneState lane, long userId, long clientKey) {
        requireLaneAccess(lane, userId);
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        return userClientOrders == null ? 0L : userClientOrders.get(clientKey);
    }

    public void putUser(UserRuntime user) {
        assertOwner();
        accountRollback.captureUserBefore(user.userId());
        onLane(user.userId(), lane -> {
            UserRuntime current = lane.user(user.userId());
            UserRuntime value = user;
            if (accountLanesStarted) {
                value = current == null || current.productLine() != user.productLine() ? user.laneValue()
                        : current.laneValue().withState(user.revision(), user.positionMode());
            }
            lane.storeUser(user.userId(), value);
            lane.registerUser(user.userId());
            return null;
        });
        publishUser(user.userId(), user);
        if (currentLaneCommandScope() == null) markUserChanged(user.userId());
    }

    public void advanceUserRevision(long userId) {
        advanceUserRevision(userId, 1);
    }

    public void advanceUserRevision(long userId, long count) {
        if (count <= 0) throw new IllegalArgumentException("revision advance must be positive");
        assertOwner();
        MatcherSettlementChanges settlementChanges = currentMatcherSettlementChangesScope();
        if (settlementChanges != null) {
            AccountLaneState lane = currentLaneCommandScope();
            if (lane == null || topology.accountLaneId(userId) != lane.laneId()) {
                throw new IllegalStateException("user revision crossed its owner lane");
            }
            if (lane.user(userId) == null) {
                throw new IllegalArgumentException("runtime user is not registered: " + userId);
            }
            // Matching can advance one user several times in a settlement. Coalesce the delta
            // and freeze its final value when the Lane finalizes the settlement.
            settlementChanges.addUserRevision(lane.laneId(), userId, count);
            return;
        }
        UserRuntime current = requireUser(userId);
        setUserState(userId, Math.addExact(current.revision(), count), current.positionMode());
    }

    public void advanceUserRevision(AccountLaneState lane, long userId) {
        advanceUserRevision(lane, userId, 1);
    }

    public void advanceUserRevision(AccountLaneState lane, long userId, long count) {
        requireLaneAccess(lane, userId);
        if (count <= 0) return;
        if (lane.matcherSettlementChanges() == null) {
            advanceUserRevision(userId, count);
            return;
        }
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        lane.matcherSettlementChanges().addUserRevision(lane.laneId(), userId, count);
    }

    /** Update user metadata on its Lane; the command buffer owns a separate frozen receipt. */
    public void setUserState(long userId, long revision, com.surprising.aeron.protocol.CorePositionMode mode) {
        assertOwner();
        if (userId <= 0 || revision < 0 || mode == null) throw new IllegalArgumentException("invalid runtime user");
        accountRollback.captureUserBefore(userId);
        UserRuntime updated = onLane(userId, lane -> {
            UserRuntime current = lane.user(userId);
            UserRuntime value = current == null ? new UserRuntime(productLine, userId, revision, mode)
                    : (accountLanesStarted ? current.laneValue() : current).withState(revision, mode);
            if (accountLanesStarted) value = value.laneValue();
            lane.storeUser(userId, value);
            lane.registerUser(userId);
            return value;
        });
        publishUser(userId, updated);
        if (currentLaneCommandScope() == null) markUserChanged(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        onLane(userId, lane -> {
            Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
            if (userClientOrders != null) userClientOrders.forEachLong((clientKey, orderId) -> {
                removeClientOrderReverse(lane, orderId, clientKey);
            });
            lane.removeUserValue(userId);
            lane.removeUser(userId);
            lane.removeUserBalances(userId);
            lane.removeUserClientOrders(userId);
            lane.removeReservationIds(userId);
            lane.removeActiveOrderIds(userId);
            lane.removePositionKeys(userId);
            lane.removeLeverageKeys(userId);
            return null;
        });
        publishUser(userId, null);
        markUserChanged(userId);
    }

    public void putBalance(BalanceRuntime balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            IntObjectHashMap<BalanceRuntime> userBalances = lane.userBalances(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.storeUserBalances(userId, userBalances);
            }
            userBalances.put(assetId, new BalanceRuntime(userId, assetId, availableUnits, lockedUnits));
            accountRollback.captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            markBalancesChanged();
            markUserChanged(userId);
        }
    }

    public void putBalance(AccountLaneState lane, BalanceRuntime balance) {
        requireLaneAccess(lane, balance.userId());
        if (lane.matcherSettlementChanges() == null) {
            putBalance(balance);
            return;
        }
        accountRollback.captureBalanceBefore(lane, balance.userId(), balance.assetId());
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(balance.userId());
        if (balances == null) {
            balances = new IntObjectHashMap<>();
            lane.storeUserBalances(balance.userId(), balances);
        }
        balances.put(balance.assetId(), balance);
        accountRollback.captureBalanceAfter(lane, balance.userId(), balance.assetId());
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        changedOrder(order.orderId(), order);
        markUserChanged(order.userId());
    }

    public void putReservation(ReservationRuntime reservation) {
        assertOwner();
        accountRollback.captureUserBefore(reservation.userId());
        accountRollback.captureReservationBefore(reservation.orderId());
        ReservationRuntime previous = reservation(reservation.orderId());
        if (previous != null && onLane(previous.userId(), lane -> lane.pendingReservation(previous.orderId()))) {
            throw new IllegalStateException("pending reservation must be replaced through its owner lane");
        }
        if (previous != null) {
            onLane(previous.userId(), lane -> {
                lane.removeReservationValue(previous.orderId());
                lane.removeReservationId(previous.userId(), previous.orderId());
                return null;
            });
        }
        onLane(reservation.userId(), lane -> {
            lane.storeReservation(reservation.orderId(), reservation.laneValue());
            lane.addReservationId(reservation.userId(), reservation.orderId());
            return null;
        });
        publishReservation(reservation.orderId(), reservation);
        if (previous == null) {
        }
        markReservationChanged(reservation.orderId());
        markUserChanged(reservation.userId());
        if (previous != null) markUserChanged(previous.userId());
    }

    public void replaceOrder(OrderRuntime order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        OrderRuntime previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        if (previous == null) {
            if (currentMatcherSettlementChangesScope() == null) {
            }
        }
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(order.orderId(), order);
            markUserChanged(order.userId());
        }
    }

    public void removeOrder(long orderId) {
        assertOwner();
        accountRollback.captureOrderBefore(orderId);
        OrderRuntime previous = order(orderId);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> { lane.removeOrder(orderId); return null; });
            publishOrder(orderId, null);
            changedOrder(orderId, null);
            markUserChanged(previous.userId());
        }
    }

    private ReservationRuntime findReservation(long orderId) {
        if (factIndexes != null) {
            var route = factIndexes.activeOrderRoute(orderId);
            if (route != null) {
                return onLane(route.userId(), lane -> lane.reservation(orderId));
            }
        }
        for (AccountLaneState lane : accountLanes) {
            ReservationRuntime res = onLane(lane.laneId(), l -> l.reservation(orderId));
            if (res != null) return res;
        }
        return null;
    }

    public void replaceReservation(ReservationRuntime reservation) {
        assertOwner();
        ReservationRuntime existing = findReservation(reservation.orderId());
        if (existing != null && existing.userId() != reservation.userId()) {
            throw new IllegalArgumentException("runtime reservation owner cannot change");
        }
        ReservationRuntime previous = onLane(reservation.userId(), lane -> {
            accountRollback.captureUserBefore(reservation.userId());
            accountRollback.captureReservationBefore(reservation.orderId(), reservation.userId());
            ReservationRuntime prev = lane.reservation(reservation.orderId());
            boolean pending = prev != null && lane.pendingReservation(prev.orderId());
            if (pending) {
                accountRollback.captureBalanceBefore(prev.userId(), prev.assetId());
                accountRollback.captureBalanceBefore(reservation.userId(), reservation.assetId());
            }
            if (prev != null) lane.replacePendingReservation(prev, reservation);
            lane.storeReservation(reservation.orderId(), reservation.laneValue());
            if (prev == null)
                lane.addReservationId(reservation.userId(), reservation.orderId());
            if (pending) {
                accountRollback.captureBalanceAfter(lane, prev.userId(), prev.assetId());
                accountRollback.captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
            }
            return prev;
        });
        publishReservation(reservation.orderId(), reservation);
        if (currentMatcherSettlementChangesScope() == null) {
            markReservationChanged(reservation.orderId());
            markUserChanged(reservation.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    public void removeReservation(long orderId, long userId) {
        assertOwner();
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureReservationBefore(orderId, userId);
            ReservationRuntime current = lane.reservation(orderId);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
            }
            if (lane.pendingReservation(orderId)) {
                throw new IllegalStateException("pending reservation must complete before removal");
            }
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            return null;
        });
        publishReservation(orderId, null);
        markReservationChanged(orderId);
        markUserChanged(userId);
    }

    public void replaceBalance(BalanceRuntime balance) {
        assertOwner();
        replaceBalance(balance.userId(), balance.assetId(), balance.availableUnits(), balance.lockedUnits());
    }

    /** Matching settlement fast path: update a Lane-owned balance without creating a replacement object. */
    public void replaceBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        assertOwner();
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null && scoped.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("balance write crossed its owner Lane");
        if (scoped != null) {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(scoped, userId, assetId, availableUnits, lockedUnits);
            return;
        }
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(lane, userId, assetId, availableUnits, lockedUnits);
            return null;
        });
        markBalancesChanged();
        markUserChanged(userId);
    }

    private void replaceBalanceInLane(AccountLaneState lane, long userId, int assetId,
                                      long availableUnits, long lockedUnits) {
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        BalanceRuntime current = balances == null ? null : balances.get(assetId);
        if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
        current.replace(availableUnits, lockedUnits);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    public void replaceBalance(AccountLaneState lane, long userId, int assetId, long available, long locked) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null) {
            replaceBalance(userId, assetId, available, locked);
            return;
        }
        accountRollback.captureBalanceBefore(lane, userId, assetId);
        replaceBalanceInLane(lane, userId, assetId, available, locked);
    }

    public void removeBalance(long userId, int assetId) {
        assertOwner();
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            IntObjectHashMap<BalanceRuntime> userBalances = lane.userBalances(userId);
            if (userBalances == null || userBalances.remove(assetId) == null) {
                throw new IllegalArgumentException("runtime balance is not registered: " + userId + '/' + assetId);
            }
            accountRollback.captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        markBalancesChanged();
        markUserChanged(userId);
    }

    /** Same operation with the instrumentId identity supplied explicitly for a newly opened position. */
    public PositionRuntime updatePositionInLane(AccountLaneState lane, long positionKey, long userId, int symbolId, int assetId,
                                         CoreInstrument instrument, long signedQuantitySteps,
                                         long entryPriceTicks, long entryValueTicks,
                                         long realizedPnlUnits, long positionMarginUnits,
                                         CoreMarginMode marginMode, CorePositionSide positionSide) {
        lane.assertOwner();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("position update must execute in its owning Account Lane");
        }
        PositionRuntime current = lane.position(positionKey);
        if (current == null) {
            PositionRuntime created = new PositionRuntime(userId, symbolId, assetId, marginMode, positionSide,
                    instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                    realizedPnlUnits, positionMarginUnits);
            lane.storePosition(positionKey, created.laneValue());
            indexPosition(lane, positionKey, created);
            publishPosition(lane, positionKey, created);
            return created;
        }
        boolean wasOpen = current.signedQuantitySteps() != 0;
        int oldSymbol = current.symbolId();
        current.applyInPlace(instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        boolean isOpen = current.signedQuantitySteps() != 0;
        if (wasOpen && (!isOpen || oldSymbol != current.symbolId()))
            unindexOpenPosition(lane, positionKey, current.userId(), oldSymbol);
        if ((!wasOpen && isOpen) || oldSymbol != current.symbolId()) indexOpenPosition(lane, positionKey, current);
        publishPosition(lane, positionKey, current);
        return current;
    }

    /** Matcher/Lane hot path: mutate execution counters and publish one after-image. */
    public OrderRuntime updateOrderInLane(AccountLaneState lane, long orderId, long executed, long remaining, long feeUnits,
                                   long executedValueHigh, long executedValueLow,
                                   CoreOrderStatus status, long revision,
                                   long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order update must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.updateOrderInPlace(orderId, executed, remaining, feeUnits,
                status, revision, commitTimestamp, commitPosition);
        order.executionValue(executedValueHigh, executedValueLow);
        publishOrder(lane, orderId, order);
        return order;
    }

    /** Matcher/Lane hot path: mutate terminal status and publish one after-image. */
    public OrderRuntime updateOrderStatusInLane(AccountLaneState lane, long orderId, CoreOrderStatus status, long revision,
                                         long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order status update must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.updateOrderStatusInPlace(orderId, status, revision,
                commitTimestamp, commitPosition);
        publishOrder(lane, orderId, order);
        return order;
    }

    /** Matcher/Lane hot path: mutate reservation counters and publish one after-image. */
    public ReservationRuntime updateReservationInLane(AccountLaneState lane, long orderId, long nextConsumed, long nextReleased) {
        lane.assertOwner();
        return updateReservationInLane(lane, orderId, nextConsumed, nextReleased, true);
    }

    public ReservationRuntime updateReservationInLane(AccountLaneState lane, long orderId, long nextConsumed, long nextReleased,
                                               boolean publish) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("reservation update must execute in its owning Account Lane");
        }
        ReservationRuntime reservation = lane.reservation(orderId);
        if (reservation == null) throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        long previousReserved = reservation.reservedUnits();
        if (nextReleased < reservation.releasedUnits())
            throw new IllegalArgumentException("reservation release moved backwards");
        reservation.setConsumedInPlace(nextConsumed);
        if (nextReleased != reservation.releasedUnits()) reservation.releaseInPlace(nextReleased - reservation.releasedUnits());
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        if (publish) publishReservation(lane, orderId, reservation);
        return reservation;
    }

    public void replacePosition(long positionKey, PositionRuntime position) {
        assertOwner();
        accountRollback.captureUserBefore(position.userId());
        accountRollback.capturePositionBefore(positionKey, position.userId());
        PositionRuntime previous = position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        onLane(position.userId(), lane -> {
            lane.storePosition(positionKey, position.laneValue());
            replacePositionIndexes(lane, positionKey, previous, position);
            return null;
        });
        publishPosition(positionKey, position);
        if (currentLaneCommandScope() == null) {
            changedPosition(positionKey, position);
            markUserChanged(position.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    public void putLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        accountRollback.captureUserBefore(liquidation.userId());
        if (liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        onLane(liquidation.userId(), lane -> {
            lane.storeLiquidation(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
    }

    public void putMarkPrice(MarkPriceRuntime markPrice) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("mark-price state");
        markPrices.put(markPrice.symbolId(), markPrice);
        changedMarkPrices.add(markPrice.symbolId());
    }

    public void putRiskSnapshot(long positionKey, RiskSnapshotRuntime snapshot) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk snapshot");
        accountRollback.captureUserBefore(snapshot.userId());
        accountRollback.captureRiskSnapshotBefore(positionKey, snapshot.userId());
        onLane(snapshot.userId(), lane -> {
            lane.storeRiskSnapshot(positionKey, snapshot);
            publishRiskSnapshot(positionKey, snapshot);
            return null;
        });
        if (currentLaneCommandScope() == null) changedRiskSnapshots.put(positionKey, snapshot);
        markUserChanged(snapshot.userId());
    }

    public void putRiskScan(RiskScanRuntime scan) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        riskScans.put(scan.symbolId(), scan);
        changedRiskScans.add(scan.symbolId());
    }

    public long marketRevision() { return marketRevision; }

    public void setMarketRevision(long value) {
        assertOwner();
        if (value < 0) throw new IllegalArgumentException("invalid market revision");
        marketRevision = value;
    }

    public void setNextLiquidationId(long nextLiquidationId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (nextLiquidationId <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        this.nextLiquidationId = nextLiquidationId;
    }

    public void replaceLiquidation(LiquidationRuntime liquidation) {
        assertOwner();
        accountRollback.captureUserBefore(liquidation.userId());
        LiquidationRuntime previous = liquidation(liquidation.liquidationId());
        if (previous == null) {
            throw new IllegalArgumentException("runtime liquidation is not registered: "
                    + liquidation.liquidationId());
        }
        if (previous.userId() != liquidation.userId()) {
            onLane(previous.userId(), lane -> {
                removeActiveLiquidation(lane, previous);
                lane.removeLiquidationValue(previous.liquidationId());
                publishLiquidation(previous.liquidationId(), null);
                return null;
            });
        }
        onLane(liquidation.userId(), lane -> {
            if (previous.userId() == liquidation.userId()) removeActiveLiquidation(lane, previous);
            lane.storeLiquidation(liquidation.liquidationId(), liquidation);
            indexActiveLiquidation(lane, liquidation);
            publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
        markUserChanged(previous.userId());
    }

    public void removePosition(long positionKey, long userId) {
        assertOwner();
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.capturePositionBefore(positionKey, userId);
            PositionRuntime current = lane.position(positionKey);
            if (current == null || current.userId() != userId) {
                throw new IllegalArgumentException("runtime position is not registered: " + positionKey);
            }
            lane.removePositionValue(positionKey);
            unindexPosition(lane, positionKey, current);
            return null;
        });
        publishPosition(positionKey, null);
        changedPosition(positionKey, null);
        markUserChanged(userId);
    }

    public void removeLiquidation(long liquidationId) {
        assertOwner();
        LiquidationRuntime previous = liquidation(liquidationId);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            onLane(previous.userId(), lane -> {
                lane.removeLiquidationValue(liquidationId);
                removeActiveLiquidation(lane, previous);
                publishLiquidation(liquidationId, null);
                return null;
            });
            if (currentLaneCommandScope() == null) changedLiquidations.put(liquidationId, null);
            markUserChanged(previous.userId());
        }
    }

    public void removeMarkPrice(int symbolId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("mark-price state");
        markPrices.remove(symbolId);
        changedMarkPrices.add(symbolId);
    }

    public void removeRiskSnapshot(long positionKey) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk snapshot");
        RiskSnapshotRuntime previous = riskSnapshot(positionKey);
        if (previous != null) {
            accountRollback.captureUserBefore(previous.userId());
            accountRollback.captureRiskSnapshotBefore(positionKey, previous.userId());
            boolean positionAbsent = onLane(previous.userId(), lane -> {
                lane.removeRiskSnapshotValue(positionKey);
                publishRiskSnapshot(positionKey, null);
                return lane.position(positionKey) == null;
            });
            if (currentLaneCommandScope() == null)
                changedRiskSnapshots.putIndexed(positionKey, null, true, positionAbsent);
        } else if (currentLaneCommandScope() != null) {
            laneDelta(currentLaneCommandScope().laneId()).putRiskSnapshot(positionKey, null);
        } else {
            riskPositionIdentities.remove(positionKey);
        }
        if (currentLaneCommandScope() == null && previous == null) changedRiskSnapshots.put(positionKey, null);
        if (previous != null) markUserChanged(previous.userId());
    }

    public void removeRiskScan(int symbolId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        riskScans.remove(symbolId);
        changedRiskScans.add(symbolId);
    }

    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        cancelOrder(orderId, userId, releaseUnits, -1, -1);
    }

    void cancelOrder(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition) {
        releaseOrderToTerminal(orderId, userId, releaseUnits, commitTimestamp, commitPosition, CoreOrderStatus.CANCELED);
    }

    /** 在账户所有者线程完成冻结释放和终态修改，不由全局 Owner 借用账户。 */
    private void releaseOrderToTerminal(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus) {
        releaseOrderToTerminal(orderId, userId, releaseUnits, commitTimestamp, commitPosition, terminalStatus,
                null, null);
    }

    /** 已在同一 Lane 校验的实体直接沿用；Owner 控制入口仍在取得 Lane 后查询。 */
    private void releaseOrderToTerminal(long orderId, long userId, long releaseUnits,
                             long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus,
                             OrderRuntime preparedOrder, ReservationRuntime preparedReservation) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureOrderBefore(orderId);
        accountRollback.captureReservationBefore(orderId);
        AccountLaneState scoped = currentLaneCommandScope();
        CanceledOrder canceled = scoped == null
                ? onLane(userId, lane -> releaseOrderToTerminalInLane(lane, orderId, userId, releaseUnits,
                        commitTimestamp, commitPosition, terminalStatus, preparedOrder, preparedReservation))
                : releaseOrderToTerminalInLane(scoped, orderId, userId, releaseUnits,
                        commitTimestamp, commitPosition, terminalStatus, preparedOrder, preparedReservation);
        publishOrder(orderId, canceled.order());
        publishReservation(orderId, canceled.reservation());
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(orderId, canceled.order());
            markReservationChanged(orderId);
            markUserChanged(userId);
            markBalancesChanged();
        }
        advanceUserRevision(userId);
    }

    private CanceledOrder releaseOrderToTerminalInLane(
            AccountLaneState lane, long orderId, long userId, long releaseUnits,
            long commitTimestamp, long commitPosition, CoreOrderStatus terminalStatus,
            OrderRuntime preparedOrder, ReservationRuntime preparedReservation) {
        if (lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("terminal order release crossed its owner Lane");
        }
        OrderRuntime order = preparedOrder == null ? lane.order(orderId) : preparedOrder;
        ReservationRuntime reservation = preparedReservation == null
                ? lane.reservation(orderId) : preparedReservation;
        if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        if (releaseUnits != reservation.reservedUnits()) {
            throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
        }
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) {
            throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
        }
        accountRollback.captureBalanceBefore(userId, reservation.assetId());
        balance.release(releaseUnits);
        if (currentMatcherSettlementChangesScope() != null && currentLaneCommandScope() == lane) {
            long previousReserved = reservation.reservedUnits();
            reservation.releaseInPlace(releaseUnits);
            lane.updatePendingReservationInPlace(reservation, previousReserved);
            OrderRuntime terminalOrder = lane.updateOrderStatusInPlace(orderId, terminalStatus,
                    Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
            accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
            return new CanceledOrder(terminalOrder, reservation);
        }
        OrderRuntime terminalOrder = order.withStatus(terminalStatus,
                Math.incrementExact(order.revision()), commitTimestamp, commitPosition);
        ReservationRuntime released = reservation.release(releaseUnits);
        lane.replacePendingReservation(reservation, released);
        lane.putOrder(terminalOrder);
        lane.storeReservation(orderId, released.laneValue());
        accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        return new CanceledOrder(terminalOrder, released);
    }

    public void cancelOrderInLane(AccountLaneState lane, long userId, long orderId) {
        cancelOrderInLane(lane, userId, orderId, -1, -1);
    }

    public void cancelOrderInLane(AccountLaneState lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.CANCELED);
    }

    public void rejectOrderInLane(AccountLaneState lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.REJECTED);
    }

    private void releaseOrderToTerminalInLane(AccountLaneState lane, long userId, long orderId,
            long timestamp, long position, CoreOrderStatus status) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("terminal order requires its settlement Lane");
        OrderRuntime order = lane.order(orderId);
        ReservationRuntime reservation = lane.reservation(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.status().terminal())
            throw new IllegalStateException("terminal order reservation is missing: " + orderId);
        BalanceRuntime balance = balance(lane, userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("terminal order balance is missing: " + orderId);
        long releaseUnits = reservation.reservedUnits();
        accountRollback.captureBalanceBefore(lane, userId, reservation.assetId());
        balance.release(releaseUnits);
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, releaseUnits);
        lane.updateOrderStatusInPlace(orderId, status, Math.incrementExact(order.revision()), timestamp, position);
        publishOrder(lane, orderId, order);
        publishReservation(lane, orderId, reservation);
        accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        advanceUserRevision(lane, userId);
    }

    /** Matcher/Lane hot path: release a terminal order's remaining reservation in place. */
    public long releaseTerminalReservationInLane(AccountLaneState lane, long orderId) {
        lane.assertOwner();
        if (lane == null || lane.matcherSettlementChanges() == null) {
            throw new IllegalStateException("terminal release must execute in its owning Account Lane");
        }
        OrderRuntime order = lane.order(orderId);
        ReservationRuntime reservation = lane.reservation(orderId);
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        }
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return 0;
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(order.userId());
        BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
        accountRollback.captureBalanceBefore(lane, order.userId(), reservation.assetId());
        balance.release(releaseUnits);
        long previousReserved = reservation.reservedUnits();
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        publishReservation(lane, orderId, reservation);
        accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
        return releaseUnits;
    }

    public void releaseTerminalReservation(long orderId) {
        assertOwner();
        accountRollback.captureReservationBefore(orderId);
        OrderRuntime order = order(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
        accountRollback.captureUserBefore(order.userId());
        TerminalRelease release = onLane(order.userId(), lane -> {
            ReservationRuntime reservation = lane.reservation(orderId);
            if (reservation == null || !order.canceled()) {
                throw new IllegalArgumentException("runtime order is not terminal: " + orderId);
            }
            long releaseUnits = reservation.reservedUnits();
            if (releaseUnits == 0) return new TerminalRelease(0, reservation.assetId());
            IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(order.userId());
            BalanceRuntime balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + orderId);
            accountRollback.captureBalanceBefore(order.userId(), reservation.assetId());
            balance.release(releaseUnits);
            ReservationRuntime released = reservation.release(releaseUnits);
            lane.replacePendingReservation(reservation, released);
            lane.storeReservation(orderId, released.laneValue());
            publishReservation(orderId, released);
            accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
            return new TerminalRelease(releaseUnits, reservation.assetId());
        });
        if (release.units() == 0) return;
        if (currentLaneCommandScope() == null) {
            markReservationChanged(orderId);
            markUserChanged(order.userId());
            markBalancesChanged();
        }
    }

    public void putClientOrder(long userId, long clientKey, long orderId) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            putClientOrderIndex(lane, userId, clientKey, orderId);
            return null;
        });
        markUserChanged(userId);
    }

    public void removeClientOrder(long userId, long clientKey) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureClientOrderBefore(userId, clientKey);
        onLane(userId, lane -> {
            removeClientOrderIndex(lane, userId, clientKey);
            return null;
        });
        markUserChanged(userId);
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
            accountRollback.captureOrderBefore(orderId);
            accountRollback.captureReservationBefore(orderId);
            prunes.add(new TerminalOrderPrune(orderId, order.userId(),
                    identities.clientKey(order.userId(), order.clientOrderId())));
            long clientKey = identities.clientKey(order.userId(), order.clientOrderId());
            if (clientKey != 0) accountRollback.captureClientOrderBefore(order.userId(), clientKey);
        }
        Object[] results = executeOwnerSettlements(prunes, TerminalOrderPrune::userId, laneId -> {
            AccountLaneState lane = currentLaneCommandScope();
            List<TerminalOrderPruned> pruned = new ArrayList<>();
            for (TerminalOrderPrune prune : prunes) {
                if (topology.accountLaneId(prune.userId()) != laneId) continue;
                OrderRuntime order = lane.order(prune.orderId());
                if (order == null || !order.status().terminal() || order.userId() != prune.userId()) {
                    throw new IllegalStateException("terminal order changed during prune: " + prune.orderId());
                }
                ReservationRuntime reservation = lane.reservation(prune.orderId());
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
                    lane.removeReservationValue(prune.orderId());
                    lane.removeReservationId(prune.userId(), prune.orderId());
                }
                lane.removeOrder(prune.orderId());
                if (prune.clientKey() != 0) {
                    Long2LongHashMap clients = lane.userClientOrders(prune.userId());
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
                changedOrder(terminal.orderId(), null);
                markUserChanged(terminal.userId());
                if (terminal.reservationRemoved()) {
                    publishReservation(terminal.orderId(), null);
                    markReservationChanged(terminal.orderId());
                    markBalancesChanged();
                }
            }
        }
    }

    public java.util.concurrent.CompletableFuture<RealtimeUserSnapshot> realtimeSnapshot(long userId) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        if (!accountLanesStarted) {
            try { return java.util.concurrent.CompletableFuture.completedFuture(onLane(userId, lane -> RealtimeUserSnapshot.capture(lane, userId))); }
            catch (RuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
        }
        var result = new java.util.concurrent.CompletableFuture<RealtimeUserSnapshot>();
        boolean accepted = dispatchAccountQuery(1L << laneId, lane -> {
            try { result.complete(readAccountLane(lane, owned -> RealtimeUserSnapshot.capture(owned, userId))); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        });
        if (!accepted) result.completeExceptionally(new IllegalStateException("realtime snapshot Lane mailbox is full"));
        return result;
    }

    /** 实时事件编码出口；只能读取已提交或有快照屏障保护的值。 */
    RealtimeStateCapture realtimeCapture;

    public void realtimeCapture(RealtimeStateCapture capture) { assertOwner(); realtimeCapture = capture; }

    public void captureUserMetadata(com.surprising.aeron.service.state.account.UserRuntime value) {
        if (realtimeCapture == null) return;
        try { realtimeCapture.metadata(value); }
        catch (RuntimeException failure) { realtimeCapture.failed(); }
    }
    public void captureReservation(ReservationRuntime value) {
        if (realtimeCapture == null) return;
        try { realtimeCapture.reservation(value); }
        catch (RuntimeException failure) { realtimeCapture.failed(); }
    }

    public void captureRealtimeChanges(RealtimeStateCapture capture) {
        assertOwner();
        if (capture == null || !capture.privateStateEnabled()) return;
        try {
            changedRiskSnapshots.forEach((id,value) -> capture.risk(value));
            // Live execution exports metadata and reservations from the committed Lane delta,
            // including when the Owner executes the Lane inline. Cold direct application
            // has no delta handoff and still exports its change sets here.
            if (!accountLanesStarted && !fusedExecutionActive) {
                changedUsers.forEach(id -> capture.metadata(user(id)));
                changedReservations.forEach(id -> capture.reservation(reservation(id)));
            }
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

    public void markBalanceChanged(long userId, int assetId) {
        assertOwner();
        markBalancesChanged();
        markUserChanged(userId);
    }

    private void markReservationChanged(long orderId) {
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedReservations.add(orderId);
        else laneDelta(lane.laneId()).putReservation(orderId, lane.reservation(orderId));
    }

    public void changedOrder(long orderId) {
        if (currentLaneCommandScope() == null && accountLanesStarted) {
            if (!changedOrders.containsKey(orderId))
                throw new IllegalStateException("order change requires a collected Lane receipt: " + orderId);
            return;
        }
        changedOrder(orderId, order(orderId));
    }

    public void changedOrder(long orderId, OrderRuntime order) {
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedOrders.put(orderId, order);
        else laneDelta(lane.laneId()).putOrder(orderId, order);
    }

    void changedPosition(long positionKey) {
        if (currentLaneCommandScope() == null && accountLanesStarted) {
            if (!changedPositions.containsKey(positionKey))
                throw new IllegalStateException("position change requires a collected Lane receipt: " + positionKey);
            return;
        }
        changedPosition(positionKey, position(positionKey));
    }

    void changedPosition(long positionKey, PositionRuntime position) {
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null) changedPositions.put(positionKey, position);
        else laneDelta(lane.laneId()).putPosition(positionKey, position);
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

    public interface ChangedIndexConsumer {
        default void order(long orderId, OrderRuntime before, OrderRuntime after) {}
        default void position(long positionKey, PositionRuntime before, PositionRuntime after) {}
        default void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {}
        default void algoOrder(long algoOrderId,
                               com.surprising.aeron.service.state.model.CoreAlgoOrderState before,
                               com.surprising.aeron.service.state.model.CoreAlgoOrderState after) {}
        default void triggerOrder(long triggerOrderId,
                                  com.surprising.aeron.service.state.model.CoreTriggerOrderState before,
                                  com.surprising.aeron.service.state.model.CoreTriggerOrderState after) {}
        default void timer(com.surprising.aeron.service.state.model.CoreCancelAllAfterKey key,
                           com.surprising.aeron.service.state.model.CoreCancelAllAfterState before,
                           com.surprising.aeron.service.state.model.CoreCancelAllAfterState after) {}
    }

    public void visitChangedIndexes(ChangedIndexConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed-index consumer is required");
        RuntimeFactIndexes preparedConsumer = consumer instanceof RuntimeFactIndexes indexes ? indexes : null;
        visitChangedOrderIndexes(consumer, changedOrders.directBuffer());
        long remaining = changedOrders.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            visitChangedOrderIndexes(consumer, changedOrders.laneBuffer(laneId));
        }
        if (systemLedger == null || preparedConsumer == null) {
            visitChangedPositionIndexes(consumer, preparedConsumer, changedPositions.directBuffer());
            remaining = changedPositions.retainedLaneMask();
            while (remaining != 0) {
                int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
                visitChangedPositionIndexes(consumer, preparedConsumer, changedPositions.laneBuffer(laneId));
            }
        }
        for (int index = 0; index < changedLiquidations.size(); index++)
            consumer.liquidation(changedLiquidations.keyAt(index), null, changedLiquidations.valueAt(index));
        for (int index = 0; index < changedAlgoOrders.size(); index++)
            consumer.algoOrder(changedAlgoOrders.keyAt(index), null, changedAlgoOrders.valueAt(index));
        for (int index = 0; index < changedTriggerOrders.size(); index++) {
            long triggerOrderId = changedTriggerOrders.keyAt(index);
            CoreTriggerOrderState value = changedTriggerOrders.valueAt(index);
            if (realtimeCapture != null && realtimeCapture.active()) {
                try { realtimeCapture.trigger(value); } catch (RuntimeException failure) { realtimeCapture.failed(); }
            }
            consumer.triggerOrder(triggerOrderId, null, value);
        }
        if (!changedCancelAllAfterTimers.isEmpty())
            for (var key : changedCancelAllAfterTimers) consumer.timer(key, null, cancelAllAfterTimer(key));
    }

    private void visitChangedOrderIndexes(ChangedIndexConsumer consumer, RuntimeChangeBuffer<OrderRuntime> orders) {
        for (int index = 0; index < orders.size(); index++)
            consumer.order(orders.keyAt(index), null, orders.valueAt(index));
    }

    private void visitChangedPositionIndexes(ChangedIndexConsumer consumer, RuntimeFactIndexes preparedConsumer,
            RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> positions) {
        for (int index = 0; index < positions.size(); index++) {
            long key = positions.keyAt(index);
            if (positions.hasPreparedAt(index) && preparedConsumer != null)
                preparedConsumer.preparedPosition(key, positions.preparedAt(index));
            else consumer.position(key, null, positions.valueAt(index));
        }
    }

    /**
     * Release identities only after journal publication and clear the two traversed buffers at
     * the same time. Other changed keys still follow the normal commit cleanup below.
     */
    public void clearCommittedChanges(RuntimeIdentityRegistry identities) {
        assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        // 风险先清理，以已有持仓变更排除本次随后重开的持仓；不回读Lane表。
        for (int index = 0; index < changedRiskSnapshots.size(); index++) {
            long positionKey = changedRiskSnapshots.keyAt(index);
            if (changedRiskSnapshots.valueAt(index) == null && changedRiskSnapshots.hasPreparedAt(index)
                    && Boolean.TRUE.equals(changedRiskSnapshots.preparedAt(index))
                    && changedPositions.get(positionKey) == null)
                releaseRetiredPositionIdentity(identities, positionKey);
        }
        changedRiskSnapshots.clear();
        releaseRetiredPositionIdentities(identities, changedPositions.directBuffer());
        long remaining = changedPositions.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            releaseRetiredPositionIdentities(identities, changedPositions.laneBuffer(laneId));
        }
        changedPositions.clear();
        clearChangedKeys();
    }

    private void releaseRetiredPositionIdentities(RuntimeIdentityRegistry identities,
            RuntimeChangeBuffer<PositionRuntime> positions) {
        for (int index = 0; index < positions.size(); index++)
            if (positions.valueAt(index) == null) releaseRetiredPositionIdentity(identities, positions.keyAt(index));
    }

    void releaseRetiredPositionIdentity(RuntimeIdentityRegistry identities, long positionKey) {
        if (!riskPositionIdentities.contains(positionKey)) {
            identities.releasePositionKey(positionKey);
        }
    }

    /** Reuse the position delta's existing key index to retain each identity once per Lane output. */
    public void retainDirectPositionIdentity(AccountLaneState lane, RuntimeIdentityRegistry identities,
                                      CoreInstrument instrument, OrderRuntime order) {
        if (!productLine.isDerivative()) return;
        MatcherSettlementChanges changes = lane.matcherSettlementChanges();
        if (changes == null || !changes.directPositionIdentities() || (!lane.commandScopeActive() && ownerLaneScope != lane))
            throw new IllegalStateException("direct position identity requires its settlement Lane");
        long key = order.positionIdentityKey();
        var output = changes.laneDelta(lane.laneId()).positions();
        if (output.indexOf(key) >= 0) return;
        long retained = identities.retainPositionInLane(
                lane, key, order.userId(), instrument.instrumentId(), order.positionSide());
        if (retained != key) throw new IllegalStateException("position identity changed during retention");
        output.put(key, lane.position(key));
    }

    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, pendingReservations.totalPendingReservations());
    }

    /** Only cold execution owns a local funds accumulator; live validation belongs to Ledger. */
    private final RuntimeFundsAccumulator coldFunds = new RuntimeFundsAccumulator();
    public RuntimeFundsAccumulator coldCommandFunds() { return systemLedger == null ? coldFunds : null; }

    private final ControlFundsValidation controlFundsValidation = new ControlFundsValidation();

    /** Account patches are complete and retained until this Ledger receipt is observed. */
    public boolean pollControlFundsValidation(boolean externalAdjustment) {
        assertOwner();
        if (systemLedger == null) return true;
        if (ledgerControl != null) return ledgerControl.validateFunds(externalAdjustment);
        boolean accountFundsChanged = false;
        for (int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            LaneBalancePatches patches = accountRollback.balancePatch(laneIndex);
accountFundsChanged |= patches.size() != 0;
        }
        if (!accountFundsChanged && changedPositions.isEmpty()) return true;
        return controlFundsValidation.poll(externalAdjustment);
    }

    public void appendControlAccountFunds(RuntimeFundsAccumulator target) {
        for (int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            LaneBalancePatches patches = accountRollback.balancePatch(laneIndex);
appendBalanceFundsDelta(patches, target);
        }
    }

    private final class ControlFundsValidation implements SystemLedgerLane.Entry {
        private final RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        private final RuntimeFactIndexes.PositionUndo positionUndo = new RuntimeFactIndexes.PositionUndo();
        private boolean queued, externalAdjustment, rollback;
        private volatile boolean validated, requested, completed = true;

        boolean poll(boolean externalAdjustment) {
            if (queued && requested) {
                if (!completed) return false;
                queued = false;
            }
            if (!queued) {
                this.externalAdjustment = externalAdjustment;
                rollback = requested = validated = false;
                completed = false;
                if (!systemLedger.offer(this)) { completed = true; return false; }
                queued = true;
            }
            return validated;
        }

        @Override public boolean ready() {
            if (!validated && !requested) {
                appendControlAccountFunds(funds);
                funds.requireConserved(externalAdjustment);
                funds.clear();
                applyLedgerControlPositions(positionUndo);
                validated = true;
                signalOwnerCompletion();
            }
            return requested;
        }

        @Override public void apply(TreasuryRuntime ledger) {
            if (rollback) rollbackLedgerPositions(positionUndo);
            else positionUndo.clear();
            completed = true;
            signalOwnerCompletion();
        }

        boolean requestRollback() {
            if (!queued) return true;
            rollback = true;
            requested = true;
            return completed;
        }

        void clear() {
            if (!queued || completed) return;
            if (!validated) throw new IllegalStateException("Ledger funds validation is incomplete");
            requested = true;
        }
    }

    public void appendFundsDelta(RuntimeFundsAccumulator accumulator) {
        assertOwner();
        if (systemLedger != null) return;
        if (accumulator == null) throw new IllegalArgumentException("funds accumulator is required");
        for (int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            LaneBalancePatches balances = accountRollback.balancePatch(laneIndex);

            appendBalanceFundsDelta(balances, accumulator);

        }
        treasury.appendFundsDelta(accumulator);
    }

    public static void prepareBalanceFundsDelta(LaneBalancePatches patches,
                                                 RuntimeFundsAccumulator accumulator) {
        accumulator.clear();
        appendBalanceFundsDelta(patches, accumulator);
    }

    static void appendBalanceFundsDelta(LaneBalancePatches balances,
                                                RuntimeFundsAccumulator accumulator) {
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            if (!balances.capturedAfter(index)) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            long beforeAvailable = balances.presentBefore(index) ? balances.availableBefore(index) : 0;
            long beforeLocked = balances.presentBefore(index) ? balances.lockedBefore(index) : 0;
            long afterAvailable = balances.presentAfter(index) ? balances.availableAfter(index) : 0;
            long afterLocked = balances.presentAfter(index) ? balances.lockedAfter(index) : 0;
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.AVAILABLE,
                    Math.subtractExact(afterAvailable, beforeAvailable));
            accumulator.add(assetId, FundsPosting.OwnerKind.USER, userId,
                    FundsPosting.Subledger.LOCKED,
                    Math.subtractExact(afterLocked, beforeLocked));
        }
    }

    static long available(BalanceState value) {
        return value == null ? 0 : value.availableUnits();
    }

    static long locked(BalanceState value) {
        return value == null ? 0 : value.lockedUnits();
    }

    public PositionRuntime currentPatchPositionBefore(long positionKey) {
        return accountRollback.currentPositionBefore(positionKey);
    }

    public OrderRuntime currentPatchOrderBefore(long orderId) {
        return accountRollback.currentOrderBefore(orderId);
    }

    public void clearChangedKeys() {
        assertOwner();
        balancesChanged = false;
        accountRollback.clearNonBalanceImages();
        clearChanged(changedUsers);
        changedOrders.clear();
        clearChanged(changedReservations);
        changedPositions.clear();
        changedLiquidations.clear();
        clearChanged(changedMarkPrices);
        changedRiskSnapshots.clear();
        clearChanged(changedRiskScans);
        changedLeverages.clear();
        changedAlgoOrders.clear();
        changedCancelAllAfterTimers.clear();
        changedTriggerOrders.clear();
        clearChanged(changedFeePolicies);
        if (systemLedger == null) treasury.clearChangedKeys();
        else if (ledgerControl != null) {
            ledgerControl.commitScope();
            ledgerControl = null;
        }
        accountRollback.publishAndClearBalances();
        controlFundsValidation.clear();
    }

    static void clearChanged(LongHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    static void clearChanged(IntHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    public LongObjectHashMap<UserRuntime> usersForSnapshot() {
        LongObjectHashMap<UserRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, AccountLaneState::usersSnapshot));
        }
        return values;
    }

    public LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.balancesSnapshot()));
        }
        return values;
    }

    public LongObjectHashMap<OrderRuntime> ordersForSnapshot() {
        LongObjectHashMap<OrderRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> {
                lane.forEachOrder(values::put);
                return null;
            });
        }
        return values;
    }

    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        return onLane(userId, lane -> lane.openReduceOnlyQuantity(
                userId, symbolId, positionSide, side, marginMode));
    }

    public LongObjectHashMap<ReservationRuntime> reservationsForSnapshot() {
        LongObjectHashMap<ReservationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> {
                lane.forEachReservation(values::put);
                return null;
            });
        }
        return values;
    }

    public LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.clientOrdersSnapshot()));
        }
        return values;
    }

    public LongObjectHashMap<PositionRuntime> positionsForSnapshot() {
        LongObjectHashMap<PositionRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.positionsSnapshot()));
        }
        return values;
    }

    public LongObjectHashMap<LiquidationRuntime> liquidationsForSnapshot() {
        LongObjectHashMap<LiquidationRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.liquidationsSnapshot()));
        }
        return values;
    }

    public IntObjectHashMap<MarkPriceRuntime> markPricesForSnapshot() {
        return markPrices;
    }

    public LongObjectHashMap<RiskSnapshotRuntime> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskSnapshotRuntime> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.riskSnapshotsSnapshot()));
        }
        return values;
    }

    public IntObjectHashMap<RiskScanRuntime> riskScansForSnapshot() {
        return riskScans;
    }

    /** Installs the prepared immutable values once, within the same reservation/capture boundary. */
    public void reserveOrder(OrderRuntime order, ReservationRuntime reservation, long clientKey) {
        if (order == null || reservation == null || order.orderId() != reservation.orderId()
                || order.userId() != reservation.userId() || order.symbolId() != reservation.symbolId()
                || order.quantitySteps() != reservation.orderQuantitySteps() || clientKey < 0) {
            throw new IllegalArgumentException("invalid prepared reservation");
        }
        reserveOrder(order, reservation, clientKey, reservation.reservedUnits());
    }

    public void reserveOrder(OrderRuntime order, ReservationRuntime reservation,
                              long clientKey, long reservedUnits) {
        assertOwner();
        long orderId = order.orderId();
        long userId = order.userId();
        int assetId = reservation.assetId();
        if (order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        accountRollback.captureUserBefore(userId);
        accountRollback.captureOrderBefore(orderId, userId);
        accountRollback.captureReservationBefore(orderId, userId);
        accountRollback.captureBalanceBefore(userId, assetId);
        if (clientKey != 0) accountRollback.captureClientOrderBefore(userId, clientKey);
        AccountLaneState scoped = currentLaneCommandScope();
        if (scoped != null) reserveOrderInLane(scoped, order, reservation, clientKey, reservedUnits);
        else onLane(userId, lane -> {
            reserveOrderInLane(lane, order, reservation, clientKey, reservedUnits);
            return null;
        });
        // Control tasks run while Owner may suspend/drain its current commit context.
        // Hand over through the Lane delta; collectControlReservation records changed keys
        // after the task's completion has been observed by Owner.
        if (currentLaneCommandScope() == null) {
            changedOrder(orderId, order);
            markReservationChanged(orderId);
            markUserChanged(userId);
            markBalancesChanged();
        } else {
            publishOrder(orderId, order);
            publishReservation(orderId, reservation);
        }
    }

    private void reserveOrderInLane(AccountLaneState lane, OrderRuntime order, ReservationRuntime reservation,
                                    long clientKey, long reservedUnits) {
        lane.assertOwner();
        long userId = order.userId(), orderId = order.orderId();
        int assetId = reservation.assetId();
        if (lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("reservation crossed Account Lane");
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey))
            throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (balance == null)
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
        balance.reserve(reservedUnits);
        lane.putOrder(order);
        lane.storeReservation(orderId, reservation);
        lane.addReservationId(userId, orderId);
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    /** Cold construction also supplies detached benchmark values; live admission uses the Lane pool. */
    static OrderRuntime preparedOrder(com.surprising.product.api.ProductLine productLine,
            long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId,
            long timestamp, long position) {
        return new OrderRuntime(command.orderId(), productLine, userId, symbolId,
                command.instrument(), command.side(), command.limitPriceTicks(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, timestamp, timestamp, position, CoreOrderStatus.OPEN, 1);
    }

    /** Control admission shares the same retirement fence as ordinary Lane admission. */
    public OrderRuntime prepareControlOrder(long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId) {
        if (!accountLanesStarted && !fusedExecutionActive)
            return preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0);
        AccountLaneState lane = currentLaneCommandScope();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("active order creation requires its Account Lane");
        return lane.preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0, retiredOwnerSequence);
    }

    /** Provisional ordinary PLACE does not capture control rollback images. */
    public void placeOrderInLane(AccountLaneState lane, long userId, ResolvedPlaceOrder command,
            java.util.UUID commandId, long requiredReservation, long clientKey,
            int symbolId, int assetId, long coreSequence, OrderRuntime prepared, long timestamp, long position) {
        if (lane == null || !(lane.commandScopeActive() || Thread.currentThread() == owner && ownerLaneScope == lane)
                || lane.laneId() != topology.accountLaneId(userId)
                || command == null || commandId == null || userId <= 0 || requiredReservation <= 0
                || clientKey < 0 || symbolId < 0 || assetId < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid Lane-owned provisional place order");
        }
        lane.assertOwner();
        if (lane.hasOrder(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserRuntime user = lane.user(userId);
        IntObjectHashMap<BalanceRuntime> balances = lane.userBalances(userId);
        BalanceRuntime balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        OrderRuntime order = prepared != null && !prepared.commandSlotValue() ? prepared
                : accountLanesStarted || fusedExecutionActive
                ? lane.preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position, retiredOwnerSequence)
                : preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position);
        ReservationRuntime reservation = order.prepareReservation(
                command.reservationKind(), assetId, requiredReservation);
        long nextUserRevision = Math.incrementExact(user.revision());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.storeReservation(reservation.orderId(), reservation.laneValue());
        lane.addReservationId(userId, order.orderId());
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, order.orderId());
        lane.storeUser(userId, (accountLanesStarted ? user.laneValue() : user)
                .withState(nextUserRevision, user.positionMode()));
        if (prepared == null) lane.markPendingReservation(order.orderId(), coreSequence);
    }

    public void putPosition(long positionKey, PositionRuntime position) {
        assertOwner();
        PositionRuntime previous = onLane(position.userId(), lane -> {
            accountRollback.capturePositionBefore(positionKey, position.userId());
            PositionRuntime prev = lane.position(positionKey);
            if (prev != null && prev.userId() != position.userId()) {
                throw new IllegalArgumentException("runtime position owner cannot change");
            }
            lane.storePosition(positionKey, position.laneValue());
            replacePositionIndexes(lane, positionKey, prev, position);
            return prev;
        });
        publishPosition(positionKey, position);
        if (currentMatcherSettlementChangesScope() == null) {
            changedPosition(positionKey, position);
            markUserChanged(position.userId());
            if (previous != null) markUserChanged(previous.userId());
        }
    }

    private static void replacePositionIndexes(AccountLaneState lane, long positionKey,
                                               PositionRuntime previous, PositionRuntime replacement) {
        if (previous == null) {
            indexPosition(lane, positionKey, replacement);
            return;
        }
        // Both callers validate the same user. Metadata, quantity and sign changes do
        // not change user membership, or instrumentId membership while exposure stays open.
        if (previous.symbolId() == replacement.symbolId()
                && (previous.signedQuantitySteps() != 0) == (replacement.signedQuantitySteps() != 0)) return;
        unindexOpenPosition(lane, positionKey, previous);
        indexOpenPosition(lane, positionKey, replacement);
    }

    public static void indexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        lane.indexPositionKey(position.userId(), positionKey);
        indexOpenPosition(lane, positionKey, position);
    }

    private static void indexOpenPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                lane.symbolPositionKeys(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.storeSymbolPositionKeys(position.symbolId(), byUser);
        }
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(2);
            byUser.put(position.userId(), keys);
        }
        int index = keys.binarySearch(positionKey);
        if (index < 0) keys.addAtIndex(-index - 1, positionKey);
    }

    public static void unindexPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        lane.unindexPositionKey(position.userId(), positionKey);
        unindexOpenPosition(lane, positionKey, position);
    }

    private static void unindexOpenPosition(AccountLaneState lane, long positionKey,
                                             long userId, int symbolId) {
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                lane.symbolPositionKeys(symbolId);
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys =
                byUser == null ? null : byUser.get(userId);
        if (keys == null) return;
        int index = keys.binarySearch(positionKey);
        if (index < 0) return;
        keys.removeAtIndex(index);
        if (keys.isEmpty()) byUser.remove(userId);
        if (byUser.isEmpty()) lane.removeSymbolPositionKeys(symbolId);
    }

    private static void unindexOpenPosition(AccountLaneState lane, long positionKey, PositionRuntime position) {
        if (position.signedQuantitySteps() == 0) return;
        unindexOpenPosition(lane, positionKey, position.userId(), position.symbolId());
    }

    public void markBalancesChanged() {
        if (currentLaneCommandScope() == null) balancesChanged = true;
    }

    private static final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList EMPTY_POSITION_KEYS =
            new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(0);

    static TreeSet<Long> toSortedSet(org.eclipse.collections.impl.list.mutable.primitive.LongArrayList values) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (int index = 0; index < values.size(); index++) sorted.add(values.get(index));
        return sorted;
    }

    static void addUserEntity(LongObjectHashMap<LongHashSet> index, long userId, long entityId) {
        LongHashSet entities = index.get(userId);
        if (entities == null) {
            entities = new LongHashSet(16);
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

    public static void putClientOrderIndex(AccountLaneState lane, long userId, long clientKey, long orderId) {
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) {
            userClientOrders = new Long2LongHashMap(lane.admissionIndexCapacity(), 0.65f, 0, false);
            lane.storeUserClientOrders(userId, userClientOrders);
        }
        // Order IDs are allocated from the positive core sequence space; zero is
        // the primitive map's absent value, so avoid a contains+get double probe.
        long previousOrderId = userClientOrders.get(clientKey);
        boolean hadPrevious = previousOrderId != 0;
        userClientOrders.put(clientKey, orderId);
        if (hadPrevious && previousOrderId != orderId) {
            removeClientOrderReverse(lane, previousOrderId, clientKey);
        }
        lane.clientKeysByOrderId().add(orderId, clientKey);
    }

    public static Long removeClientOrderIndex(AccountLaneState lane, long userId, long clientKey) {
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (userClientOrders == null) return null;
        long orderId = userClientOrders.get(clientKey);
        if (orderId == 0) return null;
        userClientOrders.remove(clientKey);
        removeClientOrderReverse(lane, orderId, clientKey);
        return orderId;
    }

    static void removeClientOrderReverse(AccountLaneState lane, long orderId, long clientKey) {
        lane.clientKeysByOrderId().remove(orderId, clientKey);
    }

    static void indexActiveLiquidation(AccountLaneState lane, LiquidationRuntime liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.storeActiveLiquidations(liquidation.userId(), bySymbol);
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
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.removeActiveLiquidations(liquidation.userId());
    }

    static boolean active(LiquidationRuntime liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }

    /**
     * Per-command capture buffer. Keys stay primitive and only the slots touched by the current
     * command are visited; unlike an open-addressed map, historical capacity never becomes scan
     * work on the owner hot path.
     */

    public void collectControlReservation(OrderRuntime order, long coreSequence) {
        pendingReservations.collectControlReservation(order, coreSequence);
    }

    public void markPendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.markPendingReservation(userId, orderId, coreSequence); }
    public void completePendingReservation(long userId, long orderId, long coreSequence) { pendingReservations.completePendingReservation(userId, orderId, coreSequence); }
    public void completePendingReservations(long coreSequence) { pendingReservations.completePendingReservations(coreSequence); }
    public boolean hasPendingReservations() { return pendingReservations.hasPendingReservations(); }
    public MatcherSettlementEvent prepareDirectMatcherSettlement(long sequence, long laneMask,
            OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId, int shard,
            RuntimeIdentityRegistry identities, long timestamp, long position, List<Long> cancellations,
            LaneOrderResultTarget target) {
        return settlements.prepareDirect(sequence, sequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target);
    }

    /**
     * Prepares the direct settlement slot before a normal PLACE admission has completed.  The
     * dispatcher carries immutable command fields for Matcher proof; the Account Lane binds its
     * authoritative order from the admission receipt before applying the result.
     */
    public MatcherSettlementEvent prepareDirectMatcherSettlement(
            long sequence, long laneMask, PlaceAdmissionEvent admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget target) {
        return settlements.prepareDirectAdmission(sequence, laneMask, admission, commandId, shard,
                identities, timestamp, position, cancellations, target);
    }

    public MatcherSettlementEvent prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, OrderRuntime single, OrderRuntime[] orders, int count, java.util.UUID commandId,
            int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, LaneOrderResultTarget target) {
        return settlements.prepareDirect(coreSequence, commitSequence, laneMask, single, orders, count,
                commandId, shard, identities, timestamp, position, cancellations, target);
    }

    public MatcherSettlementEvent prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.command.order.ResolvedMatchingAdmission admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectReplacement(sequence, commitSequence, laneMask, admission, commandId, shard,
                identities, timestamp, position, cancellations, resultTarget);
    }

    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        return prepareDirectCancellation(sequence, userId, orderId, commandId, shard, identities,
                timestamp, position, null);
    }

    public MatcherSettlementEvent prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectCancellation(sequence, userId, orderId, commandId, shard,
                identities, timestamp, position, resultTarget);
    }

    public MatcherSettlementEvent prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders,
            int count, java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, LaneOrderResultTarget resultTarget) {
        return settlements.prepareDirectCancelBatch(sequence, finalChunk, userId, orders, count, commandId, shard,
                identities, timestamp, position, resultTarget);
    }

    public void releaseMatcherSettlement(MatcherSettlementEvent event) { settlements.releaseMatcherSettlement(event); }
    public void discardMatcherSettlement(MatcherSettlementEvent event) { settlements.discardMatcherSettlement(event); }
    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, TerminalOrderSink terminalOrderSink) {
        return collectOrderBatchMatcherSettlement(event, null, terminalOrderSink);
    }

    public RuntimeTreasuryDelta collectOrderBatchMatcherSettlement(
            MatcherSettlementEvent event, RuntimeFundsAccumulator funds, TerminalOrderSink terminalOrderSink) {
        return settlements.collectOrderBatchMatcherSettlement(event, funds, terminalOrderSink);
    }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public RuntimeChangeBuffer<LiquidationRuntime> changedLiquidations() { return changedLiquidations; }
    public RuntimeIndexedChangeBuffer<RiskSnapshotRuntime, Boolean> changedRiskSnapshots() { return changedRiskSnapshots; }
    public OwnerIndexedChanges<OrderRuntime, Void> orderChanges() { return changedOrders; }
    public OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> positionChanges() { return changedPositions; }
    public RuntimeChangeBuffer<CoreAlgoOrderState> changedAlgoOrders() { return changedAlgoOrders; }
    public RuntimeChangeBuffer<CoreTriggerOrderState> changedTriggerOrders() { return changedTriggerOrders; }
    public RealtimeStateCapture realtimeCapture() { return realtimeCapture; }
    public boolean orderBatchMutationScope() { return orderBatchMutationScope; }
    public RuntimeAccountRollback accountRollback() { return accountRollback; }
    public PendingReservationTracker pendingReservations() { return pendingReservations; }
    public MatcherSettlementDispatcher settlements() { return settlements; }

    public AccountLaneState accountLane(int index) { return accountLanes[index]; }
    public int accountLaneCount() { return accountLanes.length; }

    public void changedUser(long userId) { changedUsers.add(userId); }
    public void changedReservation(long orderId) { changedReservations.add(orderId); }
    public void addRiskPositionIdentity(long key) { riskPositionIdentities.add(key); }
    public void removeRiskPositionIdentity(long key) { riskPositionIdentities.remove(key); }
    public void restoreRevision(long value) { revision = value; }
}
