package com.surprising.aeron.service.core;

import com.surprising.aeron.service.settlement.TradeSettlementChanges;
import com.surprising.aeron.service.settlement.TradeSettlementPlan;
import com.surprising.aeron.service.settlement.TradeSettlementPosting;
import com.surprising.aeron.service.order.ReplaceOrderCheck;
import com.surprising.aeron.service.position.PositionIndexOps;
import com.surprising.aeron.service.order.ClientOrderIndexOps;
import com.surprising.aeron.service.liquidation.LiquidationIndexOps;
import com.surprising.aeron.service.trigger.TriggerOrderOperations;
import com.surprising.aeron.service.order.*;

import com.surprising.aeron.bootstrap.account.LaneThread;
import com.surprising.aeron.bootstrap.account.AccountLaneLifecycle;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.change.StateChangeBuffer;
import com.surprising.aeron.service.core.command.DirectCommandDispatcher;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.core.index.BizIndex;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.OrderFundsReservation.PendingTracker;
import com.surprising.aeron.service.order.PlaceOrderCheckEvent;
import com.surprising.aeron.service.order.PlaceBatchCheckEvent;
import com.surprising.aeron.service.order.PendingOrderBatch;
import com.surprising.aeron.service.order.ReplaceOrderCheckEvent;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderResultTarget;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.SnapshotWriter;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.concurrent.LaneSequenceQueue;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 交易账户与账本的运行时：按产品线和 Lane 保存可变业务状态，Owner 负责命令交接、变化收集和完成水位。 */
public final class AccountBook implements AutoCloseable {
    @Accessors(fluent = true, chain = false)
    @Getter
    final OrderFundsReservation.PendingTracker pendingReservations = new OrderFundsReservation.PendingTracker(this);
    @Accessors(fluent = true, chain = false)
    @Getter
    ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    long revision;
    @Accessors(fluent = true, chain = false)
    @Getter
    final LaneTopology topology;
    final AccountPartition[] accountLanes;
    final AccountLaneLifecycle laneLifecycle;
    final IntObjectHashMap<MarkPrice> markPrices = new IntObjectHashMap<>();
    final SystemFunds systemFunds = new SystemFunds();
    final IntObjectHashMap<CoreInstrument> instruments = new IntObjectHashMap<>();
    final LongHashSet changedUsers = new LongHashSet();
    final StateChangeBuffer.OwnerIndexed<Order, Void> changedOrders =
            new StateChangeBuffer.OwnerIndexed<>(new StateChangeBuffer.Orders());
    final LongHashSet changedReservations = new LongHashSet();
    @Accessors(fluent = true, chain = false)
    @Getter
    final StateChangeBuffer.Indexed<RiskMarginCalculator.Snapshot, Boolean> changedRiskSnapshots =
            new StateChangeBuffer.Indexed<>();
    @Accessors(fluent = true, chain = false)
    @Getter
    final AccountTransactionRollback accountRollback;
    volatile Thread owner;
    private static final java.lang.invoke.VarHandle OWNER_THREAD;
    static final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList EMPTY_POSITION_KEYS =
            new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(0);
    final IntObjectHashMap<RiskScanner.State> riskScans = new IntObjectHashMap<>();
    SystemLedgerCommand ledgerControl;
    final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
    volatile FeePolicyBook feePolicies = FeePolicyBook.EMPTY;
    BizIndex bizIndex;
    IdIndex indexIdentities;
    final LongHashSet riskPositionIdentities = new LongHashSet();
    long nextLiquidationId = 1;
    long marketRevision;
    CoreRiskScanControlView riskScanControl = CoreRiskState.defaultScanControl();
    boolean balancesChanged;
    final org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap metadataOrderUsers =
            new org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap();
    final org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<Order>
            captureMetadataUser = (id, order) -> {
                if (order != null && metadataOrderUsers.containsKey(id)) metadataOrderUsers.put(id, order.userId());
            };
    final org.eclipse.collections.api.block.procedure.primitive.LongLongProcedure
            captureActiveMetadataUser = (id, userId) -> {
                if (userId != 0) return;
                var route = bizIndex.activeOrders().activeOrderRoute(id);
                if (route != null) metadataOrderUsers.put(id, route.userId());
            };
    final StateChangeBuffer.OwnerIndexed<Position, Position.IndexValue> changedPositions =
            new StateChangeBuffer.OwnerIndexed<>(new StateChangeBuffer.Positions());
    final StateChangeBuffer<RiskScanner.LiquidationOrder> changedLiquidations =
            new StateChangeBuffer<>();
    final IntHashSet changedMarkPrices = new IntHashSet();
    final IntHashSet changedRiskScans = new IntHashSet();
    final HashSet<CoreLeverageKey> changedLeverages = new HashSet<>();
    final StateChangeBuffer<CoreAlgoOrderState> changedAlgoOrders = new StateChangeBuffer<>();
    final HashSet<CoreCancelAllAfterKey> changedCancelAllAfterTimers = new HashSet<>();
    final StateChangeBuffer<CoreTriggerOrderState> changedTriggerOrders =
            new StateChangeBuffer<>();
    final LongHashSet changedFeePolicies = new LongHashSet();
    TradingRealtimeBoundary.StateCapture realtimeCapture;

    static final int CHANGE_KEY_COMPACTION_THRESHOLD = 512;
    static final int COMMITTED_SEQUENCE_STRIDE = 16;
    static final VarHandle COMMITTED_SEQUENCES = MethodHandles.arrayElementVarHandle(long[].class);
    static final int EVENT_POOL_INITIAL_CAPACITY = 1024;
    static final int EVENT_POOL_PREALLOCATE_COUNT = 512;

    final CoreRuntimeConfiguration configuration;
    final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
    final PaddedReadyMask accountOrderCheckReadyShards;
    final LaneSequenceQueue[] placeOrderCheckReadyQueues;
    final int[] accountLaneQueueHighWaterMarks;
    final long[] publishedLaneCommittedSlots;
    final long[] dispatchedLaneCommitSequences;
    final java.util.ArrayDeque<AccountPartitionCommit> laneCommitEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    final java.util.ArrayDeque<PlaceOrderCheckEvent> placeOrderCheckEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    final java.util.ArrayDeque<ReplaceOrderCheckEvent> replacementOrderCheckEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    final java.util.ArrayDeque<PlaceBatchCheckEvent> placeBatchOrderCheckEventPool =
            new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
    final java.util.ArrayDeque<TradeSettlement> matcherSettlementEventPool =
            new java.util.ArrayDeque<>();
    final AccountPartitionCommit.Delta[] laneDeltas;
    boolean orderBatchMutationScope;
    AccountPartition ownerLaneScope;
    TradeSettlementChanges ownerMatcherSettlementChangesScope;
    final TradeSettlementChanges[] matcherSettlementChangesRing;
    final int[] freeMatcherSettlementChangeSlots;
    int freeMatcherSettlementChangeCount;
    volatile long retiredOwnerSequence;
    final AccountCommandService.Dispatcher controlLanes;
    final PaddedReadyMask directSettlementReadyLanes;
    final PaddedReadyMask placeOrderCheckReadyLanes;
    final FundsTotal controlValidationFunds = new FundsTotal();
    final BizIndex.PositionUndo controlPositionUndo = new BizIndex.PositionUndo();
    boolean controlFundsValidated;
    private final TriggerOrderOperations triggerOps = new TriggerOrderOperations(this);
    private final AlgoOrderOperations algoOps = new AlgoOrderOperations(this);
    private final AccountMarketCatalog marketCatalog = new AccountMarketCatalog(this);
    private final AccountSnapshotViews snapshotViews = new AccountSnapshotViews(this);
    private final AccountOrderOperations orderOps = new AccountOrderOperations(this);
    private final AccountStateChangeTracker stateChanges = new AccountStateChangeTracker(this);
    private final AccountLaneCoordinator laneCoordinator = new AccountLaneCoordinator(this);
    private final AccountEntityQueries entityQueries = new AccountEntityQueries(this);
    private final AccountEntityMutations entityMutations = new AccountEntityMutations(this);

    static {
        try { OWNER_THREAD = java.lang.invoke.MethodHandles.lookup().findVarHandle(AccountBook.class, "owner", Thread.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public boolean accountLanesStarted() { return laneLifecycle.started(); }
    public long marketRevision() { return marketRevision; }
    public StateChangeBuffer<RiskScanner.LiquidationOrder> changedLiquidations() { return changedLiquidations; }
    public StateChangeBuffer<CoreAlgoOrderState> changedAlgoOrders() { return changedAlgoOrders; }
    public StateChangeBuffer<CoreTriggerOrderState> changedTriggerOrders() { return changedTriggerOrders; }
    public boolean orderBatchMutationScope() { return orderBatchMutationScope; }
    public TradingRealtimeBoundary.StateCapture realtimeCapture() { return realtimeCapture; }

    public void bindFactIndexes(BizIndex indexes, IdIndex identities) {
        assertOwner();
        bizIndex = java.util.Objects.requireNonNull(indexes);
        indexIdentities = java.util.Objects.requireNonNull(identities);
    }

    BizIndex bizIndex() {
        assertOwner();
        if (bizIndex == null) throw new IllegalStateException("position indexes are not bound");
        return bizIndex;
    }

    public com.surprising.aeron.service.core.index.LiquidationIndex.LiquidationRoute liquidationRoute(long id) {
        assertOwner(); return bizIndex == null ? null : bizIndex.liquidations().route(id);
    }

    public AccountBook() {
        this(CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone());
    }

    public AccountBook(LaneTopology topology) {
        this(topology, CoreRuntimeConfiguration.standalone());
    }

    public AccountBook(LaneTopology topology, CoreRuntimeConfiguration config) {
        java.util.Objects.requireNonNull(config);
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        this.accountLanes = new AccountPartition[topology.accountLaneCount()];
        this.accountRollback = new AccountTransactionRollback(this, topology.accountLaneCount());
        this.laneLifecycle = new AccountLaneLifecycle(accountLanes, topology, this::assertOwner);
        this.configuration = config;
        this.accountOrderCheckReadyShards = new PaddedReadyMask(topology.matchingEngineCount());
        this.directSettlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        this.placeOrderCheckReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
        int deltaRingCapacity = Math.max(64, config.maxPendingMatching());
        this.matcherSettlementChangesRing = new TradeSettlementChanges[deltaRingCapacity];
        this.freeMatcherSettlementChangeSlots = new int[deltaRingCapacity];
        this.freeMatcherSettlementChangeCount = deltaRingCapacity;
        for (int index = 0; index < deltaRingCapacity; index++) {
            matcherSettlementChangesRing[index] = new TradeSettlementChanges(topology.accountLaneCount(), index);
            freeMatcherSettlementChangeSlots[index] = index;
        }
        this.laneDeltas = new AccountPartitionCommit.Delta[topology.accountLaneCount()];
        this.laneUserScratch = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
        this.placeOrderCheckReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
        this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
        this.publishedLaneCommittedSlots = new long[Math.multiplyExact(topology.accountLaneCount(), COMMITTED_SEQUENCE_STRIDE)];
        this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
        this.controlLanes = new AccountCommandService.Dispatcher(this);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountPartition(laneId, topology.accountLaneQueueCapacity(), config.laneInitialEntities());
            placeOrderCheckReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneCommittedSequence(accountLanes[laneId]);
            laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            laneDeltas[laneId] = new AccountPartitionCommit.Delta();
        }
        for (int i = 0; i < EVENT_POOL_PREALLOCATE_COUNT; i++) {
            laneCommitEventPool.addFirst(new AccountPartitionCommit(accountLanes.length));
            placeOrderCheckEventPool.addFirst(new PlaceOrderCheckEvent());
            replacementOrderCheckEventPool.addFirst(new ReplaceOrderCheckEvent());
            placeBatchOrderCheckEventPool.addFirst(new PlaceBatchCheckEvent());
        }
    }

    /** 按固定拓扑把用户身份路由到所属账户 Lane。 */
    public int accountLaneId(long userId) {
        return topology.accountLaneId(userId);
    }

    /** 按用户路由取得权威账户 Lane，并校验该 Lane 引用及当前调用者所有权。不通过搜索线程身份决定用户归属。 */
    public AccountPartition accountLaneForUser(long userId) {
        AccountPartition lane = accountLanes[topology.accountLaneId(userId)];
        requireLaneAccess(lane, userId);
        return lane;
    }

    /** 仅按用户路由借出 Lane 引用，不做所有权检查或取得读取栅栏。调用方必须已经处于允许的读取边界。 */
    public AccountPartition accountLaneForUserReadOnly(long userId) {
        return accountLanes[topology.accountLaneId(userId)];
    }

    /** 要求 Lane 属于当前运行时、与该用户路由一致，且当前线程满足 Lane 所有权检查。 */
    public void requireLaneAccess(AccountPartition lane, long userId) {
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    /** 要求 Lane 引用属于当前运行时，并检查当前线程是否拥有它。 */
    void requireLaneAccess(AccountPartition lane) {
        if (lane == null || lane.laneId() < 0 || lane.laneId() >= accountLanes.length
                || accountLanes[lane.laneId()] != lane)
            throw new IllegalStateException("account access crossed its owning Lane");
        lane.assertOwner();
    }

    /** 交给所属 Matcher 分片的结算入口。不借出资源数组或允许替换工作线程。 */
    public void publishMatcherSettlement(int laneId, int shard,
            com.surprising.aeron.service.settlement.TradeSettlement event) {
        laneLifecycle.worker(laneId).publishMatcherSettlement(shard, event);
    }

    /** 测试与控制任务按原 Owner 单生产者协议投递，不暴露线程资源。 */
    public void submitAccountCommand(int laneId, AccountLaneCommand command) {
        assertOwner();
        laneLifecycle.worker(laneId).submit(command);
    }

    /** 在 Owner 上采样指定工作队列深度。 */
    public int laneWorkerDepth(int laneId) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length) throw new IllegalArgumentException("invalid Lane id");
        SettlementLaneWorker worker = laneLifecycle.worker(laneId);
        return worker == null ? 0 : Math.max(0, worker.depth());
    }

    /** 在 Owner 上采样队列深度、容量和高水位并写入编码器。 */
    public void writeAccountLaneMetrics(int laneId, com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder) {
        assertOwner();
        if (laneId < 0 || laneId >= accountLanes.length || encoder == null) {
            throw new IllegalArgumentException("invalid Lane metrics request");
        }
        int depth = laneWorkerDepth(laneId);
        accountLanes[laneId].writeMetrics(encoder, depth, laneWorkerCapacity(laneId),
                observeLaneQueueHighWaterMark(laneId, depth));
    }

    public int laneWorkerCapacity(int laneId) {
        assertOwner();
        SettlementLaneWorker worker = laneLifecycle.worker(laneId);
        return worker == null ? accountLanes[laneId].queueCapacity() : worker.metricsQueueCapacity();
    }

    public int observeLaneQueueHighWaterMark(int laneId, int depth) {
        assertOwner();
        int highWater = accountLanes[laneId].updateQueueHighWaterMark(
                Math.max(accountLaneQueueHighWaterMarks[laneId], depth));
        accountLaneQueueHighWaterMarks[laneId] = highWater;
        return highWater;
    }

    /** 在 Owner 上单调发布已退休命令的序号边界，供 Lane 回收订单身份元数据。 */
    public void releaseRetiredOrderFence(long sequence) {
        assertOwner();
        if (sequence < retiredOwnerSequence) throw new IllegalStateException("order retirement fence moved backwards");
        retiredOwnerSequence = sequence;
    }

    /** 使用禁用 CPU 亲和的配置创建账户工作资源。 */
    public void startAccountLanes() {
        startAccountLanes(TradingCpuAffinity.disabled());
    }

    /** 为每条账户 Lane 创建工作对象并接入统一故障通知，全部创建后标记已启动并交出 Owner 访问权，再记录是否存在普通线程实现。 */
    public void startAccountLanes(TradingCpuAffinity affinity) {
        laneLifecycle.start(affinity);
    }

    public void setParkOnIdle(boolean parkOnIdle) {
        laneLifecycle.setParkOnIdle(parkOnIdle);
    }

    public boolean ownerLaneAccess() { return laneLifecycle.ownerHasAccess(); }
    /** Owner 持有账户访问权且没有批量修改范围时归还 Lane，避免过早释放仍被批次使用的访问权。 */
    public void releaseCompletedSequentialLaneStage() {
        if (ownerLaneAccess() && !orderBatchMutationScope) releaseOwnerLaneAccess();
    }

    /** 在 Owner 上请求全部账户工作线程交还所有权：首次请求递增轮次并设置 30 秒预算，尚有工作线程未就绪时返回假，超时或中断则抛错。 */
    public boolean tryAcquireOwnerLaneAccess() {
        return laneLifecycle.tryAcquireOwnerAccess();
    }

    /** 循环尝试取得全部账户 Lane 访问权，未就绪时自旋。 */
    public void acquireOwnerLaneAccess() {
        while (!tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
    }

    /** 在 Owner 上释放各活动 Lane 的绑定并清访问标记。若正在交接，通知工作线程恢复该轮工作并清请求标记。 */
    public void releaseOwnerLaneAccess() { laneLifecycle.releaseOwnerAccess(); }

    public void assertAccountLanesHealthy() { laneLifecycle.assertHealthy(); }

    public void recordFailure(Throwable problem) { laneLifecycle.recordFailure(problem); }

    /** 激活时将业务持仓索引绑定至当前 Owner 线程，保留冷恢复后的线程交接边界。 */
    public void bindLedgerPositionOwner() {
        assertOwner();
        if (bizIndex != null) bizIndex.bindBusinessOwner(Thread.currentThread());
    }

    /** 从账本拥有的事实索引读取准入所需持仓量。 */
    public long ledgerOrderCheckOpenInterest(String instrumentId) {
        if (bizIndex == null) throw new IllegalStateException("position indexes are not bound");
        return bizIndex.openInterest().openInterestSteps(instrumentId);
    }

    /** 索引已绑定时，把指定结算 Lane 的持仓变化交给账本业务索引应用。 */
    public void applyLedgerSettlementPositions(TradeSettlementChanges changes, long mask, IdIndex identities) {
        if (this.bizIndex != null) this.bizIndex.applySettlementPositions(changes, mask, identities);
    }

    /** 索引已绑定时，把当前控制命令持仓变化应用到账本索引，并记录可回滚内容。 */
    public void applyLedgerControlPositions(BizIndex.PositionUndo undo) {
        if (bizIndex != null) bizIndex.applyControlPositions(changedPositions, indexIdentities, undo);
    }

    /** 索引已绑定时，按既有撤销记录恢复账本持仓索引。 */
    public void rollbackLedgerPositions(BizIndex.PositionUndo undo) {
        if (bizIndex != null) undo.rollback(bizIndex);
    }

    /** 在 Owner 上派发财务控制命令：内联应用并保证同一控制命令独占财务范围。 */
    public void dispatchLedgerCommand(SystemLedgerCommand command) {
        assertOwner();
        if (command == null) throw new IllegalArgumentException("Ledger command is required");
        if (ledgerControl != null && ledgerControl != command)
            throw new IllegalStateException("another control command owns the Ledger financial scope");
        ledgerControl = command;
        command.apply(systemFunds);
    }

    void rollbackLedgerControl() {
        if (ledgerControl == null) {
            if (controlFundsValidated) {
                rollbackLedgerPositions(controlPositionUndo);
                controlFundsValidated = false;
            }
            return;
        }
        ledgerControl.rollbackScope();
    }

    /** 按目标掩码派发普通控制结算任务，交由唯一控制派发器管理回执。 */
    public void dispatchControlLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, DirectCommandDispatcher.OperationType.SETTLEMENT, operation);
    }

    /** 按目标掩码派发风险任务，仍使用同一有界控制派发器。 */
    public void dispatchRiskLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        controlLanes.dispatch(laneMask, DirectCommandDispatcher.OperationType.RISK, operation);
    }

    /** 校验强平批次容量在 1 至 4096 之间，从当前 Lane 或冷路径默认 Lane 0 取得可复用批次，容量不足时替换并重置。 */
    public RiskScanner.LiquidationBatch riskLiquidationBatch(int capacity) {
        if (capacity <= 0 || capacity > 4_096) {
            throw new IllegalArgumentException("invalid risk liquidation batch capacity");
        }
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null && accountLanes != null && accountLanes.length > 0) {
            lane = accountLanes[0];
        }
        if (lane == null) {
            throw new IllegalStateException("risk liquidation batch requires an owning Lane scope");
        }
        RiskScanner.LiquidationBatch batch = lane.riskLiquidationBatch();
        if (batch == null || batch.capacity() < capacity) {
            batch = new RiskScanner.LiquidationBatch(capacity);
            lane.riskLiquidationBatch(batch);
        }
        batch.reset();
        return batch;
    }

    /** 让控制派发器收集本轮完成状态，返回其是否已经完成。 */
    public boolean pollControlLanes() { return controlLanes.poll(); }

    /** 读取在途控制交接的诊断信息，供推进故障定位使用。 */
    public String controlLaneDiagnostics() { return controlLanes.diagnostics(); }

    /** 取得指定 Lane 的控制结果，完成与读取条件由控制派发器检查。 */
    public Object controlLaneResult(int laneId) { return controlLanes.result(laneId); }

    /** 必要时先取得全部账户访问权，再只对目标用户 Lane 建立已提交序号的读栅栏。 */
    public void readFence(long userId, long committedCoreSequence) { laneCoordinator.readFence(userId, committedCoreSequence); }

    /** 必要时取得全部账户访问权，先逐 Lane 检查读栅栏，再逐 Lane 推进读水位，避免检查尚未通过时提前推进其他 Lane。 */
    public void readFenceAll(long committedCoreSequence) { laneCoordinator.readFenceAll(committedCoreSequence); }

    /** 按用户所属 Lane 执行给定操作，沿用统一的 Lane 范围检查。 */
    public <T> T onLane(long userId, java.util.function.Function<AccountPartition, T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    /** 已有命令范围时只允许访问同一 Lane，否则进入受检查的 Lane 命令范围执行。 */
    public <T> T onLane(int laneId, java.util.function.Function<AccountPartition, T> operation) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        return inLaneCommandScope(accountLanes[laneId], operation);
    }

    /** 直接借出指定 Lane 执行读取，不自动建立线程权限或读取栅栏。 */
    public <T> T onLaneReadOnly(int laneId, java.util.function.Function<AccountPartition, T> operation) {
        return operation.apply(accountLanes[laneId]);
    }

    /** 进入给定 Lane 的命令范围执行回调，无论正常返回或抛错都退出该范围。 */
    public <T> T inLaneCommandScope(AccountPartition lane, java.util.function.Function<AccountPartition, T> operation) {
        enterLaneCommandScope(lane);
        try {
            return operation.apply(lane);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    /** 查当前线程正在执行的 Lane 范围：Owner 使用本地引用，专用 LaneThread 直接取其 Lane，存在普通工作线程时才逐项匹配。 */
    public AccountPartition currentLaneCommandScope() {
        Thread current = Thread.currentThread();
        if (current == owner) {
            return ownerLaneScope;
        }
        if (current instanceof LaneThread laneThread) {
            AccountPartition lane = laneThread.lane();
            return lane.commandScopeActive() ? lane : null;
        }
        return null;
    }

    /** 在查询范围内读权威账户表，冷阶段允许当前拥有者执行，活动阶段要求实际所属工作线程。 */
    public <T> T readAccountLane(AccountPartition lane, java.util.function.Function<AccountPartition, T> query) {
        return laneCoordinator.readAccountLane(lane, query);
    }

    /** 由 Owner 先检查全部目标 Lane 的容量，全部可接纳才派发同一查询，避免邮箱满造成部分查询。冷阶段逐 Lane 直接执行，活动阶段先归还 Owner 访问权。 */
    public boolean dispatchAccountQuery(long laneMask, AccountLaneCommand query) {
        return laneCoordinator.dispatchAccountQuery(laneMask, query);
    }

    /** 取得当前线程已接入的撮合变化容器，Owner 使用本地范围，其余线程从当前 Lane 范围读取。 */
    public TradeSettlementChanges currentMatcherSettlementChangesScope() {
        if (Thread.currentThread() == owner) return ownerMatcherSettlementChangesScope;
        AccountPartition lane = currentLaneCommandScope();
        return lane != null ? lane.matcherSettlementChanges() : null;
    }

    /** 校验 Lane 引用、当前线程和既有范围，禁止交叉或嵌套账户访问。活动 Owner 还须持有交接后的访问权，再登记当前范围。 */
    public void enterLaneCommandScope(AccountPartition lane) { laneCoordinator.enterLaneCommandScope(lane); }

    /** 要求撮合变化容器覆盖该 Lane 且没有其他撮合范围，先进入账户命令范围，再登记本次结算变化容器。 */
    public void enterMatcherSettlementScope(AccountPartition lane, TradeSettlementChanges changes) {
        laneCoordinator.enterMatcherSettlementScope(lane, changes);
    }

    /** 按默认规则退出账户命令范围，需要时保留持仓退休证明。 */
    public void exitLaneCommandScope(AccountPartition lane) { laneCoordinator.exitLaneCommandScope(lane); }

    /** 确认正在退出同一撮合变化容器，清 Owner 与 Lane 的撮合引用，再退出账户范围。 */
    public void exitMatcherSettlementScope(AccountPartition lane, TradeSettlementChanges changes) {
        laneCoordinator.exitMatcherSettlementScope(lane, changes);
    }

    /** 在修改任务状态前检查全部目标账户邮箱。 */
    public void ensureLaneWorkerCapacity(long laneMask) { laneCoordinator.ensureLaneWorkerCapacity(laneMask); }

    /** 要求当前已有账户命令范围，再判断给定用户是否路由到该范围的 Lane。 */
    public boolean currentLaneOwns(long userId) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null) throw new IllegalStateException("account lane command scope is required");
        return topology.accountLaneId(userId) == scoped.laneId();
    }

    /** 在所属 Lane 注册尚未登记的用户，推进已应用与已提交序号，并发布提交序号观测槽。 */
    public void applyLaneUsers(AccountPartition lane,
                        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users,
                        long coreSequence) {
        laneCoordinator.applyLaneUsers(lane, users, coreSequence);
    }

    /** 把该 Lane 的已提交序号写入独立 volatile 槽，供 Owner 观察完成前缀。 */
    public void publishLaneCommittedSequence(AccountPartition lane) { laneCoordinator.publishLaneCommittedSequence(lane); }

    /** 复制指定资产的余额数值。 */
    static AccountBalance copyBalance(IntObjectHashMap<AccountBalance> balances, int assetId) {
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        return balance == null ? null : new AccountBalance(balance.userId(), balance.assetId(),
                balance.availableUnits(), balance.lockedUnits());
    }

    /** 复制一名用户的资产映射及每项可变余额对象。 */
    static IntObjectHashMap<AccountBalance> copyBalances(IntObjectHashMap<AccountBalance> balances) {
        if (balances == null) return null;
        IntObjectHashMap<AccountBalance> copy = new IntObjectHashMap<>(balances.size());
        balances.forEachKeyValue((assetId, balance) -> copy.put(assetId,
                new AccountBalance(balance.userId(), balance.assetId(),
                        balance.availableUnits(), balance.lockedUnits())));
        return copy;
    }

    /** 复制所有用户的资产映射和余额对象，供明确的冷查询或快照边界使用。 */
    public static LongObjectHashMap<IntObjectHashMap<AccountBalance>> copyAllBalances(
            LongObjectHashMap<IntObjectHashMap<AccountBalance>> balances) {
        LongObjectHashMap<IntObjectHashMap<AccountBalance>> copy = new LongObjectHashMap<>(balances.size());
        balances.forEachKeyValue((userId, userBalances) -> copy.put(userId, copyBalances(userBalances)));
        return copy;
    }

    /** 复制客户订单号索引，跳过仅作为内部容量缓存的空用户映射。 */
    public static LongObjectHashMap<LongLongHashMap> copyClientOrderIndex(
            LongObjectHashMap<Long2LongHashMap> index) {
        LongObjectHashMap<LongLongHashMap> copy = new LongObjectHashMap<>(index.size());
        index.forEachKeyValue((userId, values) -> {
            if (!values.isEmpty()) {
                LongLongHashMap entries = new LongLongHashMap(values.size());
                values.forEachLong(entries::put);
                copy.put(userId, entries);
            }
        });
        return copy;
    }

    /** 当前已有 Lane 范围时把用户变化写入该范围的变化容器。 */
    public void publishUser(long userId, UserAccount value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putUser(userId, value);
        }
    }

    /** 当前已有 Lane 范围时登记订单变化，空值保留删除含义。 */
    public void publishOrder(long orderId, Order value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putOrder(orderId, value);
        }
    }

    /** 当前已有 Lane 范围时登记订单预留变化，无范围时不写入。 */
    public void publishReservation(long orderId, OrderFundsReservation value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putReservation(orderId, value);
        }
    }

    /** 当前已有 Lane 范围时登记持仓变化，无范围时不写入。 */
    public void publishPosition(long positionKey, Position value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            laneDelta(scoped.laneId()).putPosition(positionKey, value);
        }
    }

    /** Lane 范围内把强平变化交给该 Lane 容器，否则写入当前 Owner 变化缓冲。 */
    void publishLiquidation(long liquidationId, RiskScanner.LiquidationOrder value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null) changedLiquidations.put(liquidationId, value);
        else laneDelta(scoped.laneId()).putLiquidation(liquidationId, value);
    }

    /** Lane 范围内登记风险快照变化，否则写入当前 Owner 风险变化缓冲。 */
    public void publishRiskSnapshot(long positionKey, RiskMarginCalculator.Snapshot value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null) changedRiskSnapshots.put(positionKey, value);
        else laneDelta(scoped.laneId()).putRiskSnapshot(positionKey, value);
    }

    /** 按当前是否处于 Lane 范围，把触发单变化交给 Lane 容器或 Owner 缓冲。 */
    public void publishTriggerOrder(long id, CoreTriggerOrderState value) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null) {
            changedTriggerOrders.put(id, value);
        } else {
            laneDelta(scoped.laneId()).putTrigger(id, value);
        }
    }

    /** 优先取得当前撮合范围的指定 Lane 变化容器，没有撮合范围则使用普通控制变化容器。 */
    public AccountPartitionCommit.Delta laneDelta(int laneId) {
        TradeSettlementChanges changes = currentMatcherSettlementChangesScope();
        return changes == null ? laneDeltas[laneId] : changes.laneDelta(laneId);
    }

    /** 按明确的 Lane 引用选择它的撮合变化容器或普通控制变化容器。 */
    public AccountPartitionCommit.Delta laneDelta(AccountPartition lane) {
        TradeSettlementChanges changes = lane.matcherSettlementChanges();
        return changes == null ? laneDeltas[lane.laneId()] : changes.laneDelta(lane.laneId());
    }

    /** 向明确指定 Lane 的当前变化容器登记用户变化。 */
    public void publishUser(AccountPartition lane, long userId, UserAccount value) {
        laneDelta(lane).putUser(userId, value);
    }

    /** 向明确指定 Lane 的当前变化容器登记订单变化。 */
    public void publishOrder(AccountPartition lane, long orderId, Order value) {
        laneDelta(lane).putOrder(orderId, value);
    }

    /** 向明确指定 Lane 的当前变化容器登记订单预留变化。 */
    public void publishReservation(AccountPartition lane, long orderId, OrderFundsReservation value) {
        laneDelta(lane).putReservation(orderId, value);
    }

    /** 向明确指定 Lane 的当前变化容器登记持仓变化。 */
    public void publishPosition(AccountPartition lane, long positionKey, Position value) {
        laneDelta(lane).putPosition(positionKey, value);
    }

    /** 按默认的空附加身份集合，收集指定 Lane 的控制变化。 */
    public void flushPublishedChanges(int laneId) { stateChanges.flushPublishedChanges(laneId, null, null); }

    /** 收集普通控制变化、余额变化标记及触发单变化，再排入 Owner 变化缓冲和可选身份集合。 */
    public void flushPublishedChanges(int laneId,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        stateChanges.flushPublishedChanges(laneId, changedUsers, changedOrders);
    }

    /** 在 Owner 上从固定环借出空闲变化槽，标记占用并准备目标 Lane 容器。 */
    public TradeSettlementChanges acquireMatcherSettlementChanges(long laneMask) {
        return laneCoordinator.acquireMatcherSettlementChanges(laneMask);
    }

    /** 要求变化槽仍被占用，归还退休订单引用、清空容器并把槽号放回空闲栈。 */
    public void releaseMatcherSettlementChanges(TradeSettlementChanges changes) {
        laneCoordinator.releaseMatcherSettlementChanges(changes);
    }

    /** 先归还 Owner 账户访问权并清账户启动标记，再逐一关闭账户工作对象与账本，汇总已捕获的关闭异常。账本线程确认退出后解除索引业务所有者，最后保留 Error 或包装其他故障抛出。 */
    @Override
    public void close() {
        laneLifecycle.close();
    }

    public boolean workersStopped() { return laneLifecycle.workersStopped(); }

    /** 从当前命令的冻结后值捕获候选归属，活动索引只补未产生后值的仍活动订单。返回 Owner 复用草稿，调用方必须立即交给 dispatch，不能跨命令保存。 */
    public org.eclipse.collections.api.map.primitive.LongLongMap metadataOrderUsers(Iterable<Long> orderIds) {
        return stateChanges.metadataOrderUsers(orderIds);
    }

    /** 校验提交序号和原日志元数据，按变化用户派发账户提交任务，同时携带订单盖章和可选触发取消信息。 */
    public AccountPartitionCommit dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            org.eclipse.collections.api.map.primitive.LongLongMap orderUsers, Iterable<Long> triggerIds, long timestamp, long position) {
        return laneCoordinator.dispatchLaneMutation(coreSequence, userIds, orderUsers, triggerIds, timestamp, position);
    }

    /** 最终批次响应与账户提交使用同一任务。 */
    public AccountPartitionCommit dispatchBatchCommit(long coreSequence, Iterable<Long> userIds,
            org.eclipse.collections.api.map.primitive.LongLongMap orderUsers, long timestamp, long position, long responseUserId,
            OrderResultTarget target, IdIndex identities) {
        return laneCoordinator.dispatchBatchCommit(coreSequence, userIds, orderUsers, timestamp, position,
                responseUserId, target, identities);
    }

    /** 清空每条 Lane 的临时用户列表，准备下一次派发。 */
    void clearLaneUserScratch() { laneCoordinator.clearLaneUserScratch(); }

    /** 按拓扑把用户身份追加到对应临时列表，不在这里去重。 */
    void addLaneUser(long userId) { laneCoordinator.addLaneUser(userId); }

    /** 在 Owner 上检查健康并观察给定提交事件是否全部完成。 */
    public boolean laneCommitComplete(AccountPartitionCommit event) { return laneCoordinator.laneCommitComplete(event); }

    /** 在调用方完成等待后发布并清空提交事件，再放回复用池。 */
    public void releaseLaneCommit(AccountPartitionCommit event) { laneCoordinator.releaseLaneCommit(event); }

    /** 从下单准入池借出事件。 */
    public PlaceOrderCheckEvent acquirePlaceOrderCheck() { return laneCoordinator.acquirePlaceOrderCheck(); }

    /** 未派发的准入准备失败时丢弃事件内容并归还池，空事件无需处理。 */
    public void discardPlaceOrderCheck(PlaceOrderCheckEvent event) { laneCoordinator.discardPlaceOrderCheck(event); }

    /** 准备单笔下单事件，先检查账户准入容量。失败时账本仍持有输入则抛出明确故障而不回收该事件，避免复用仍被引用的槽。 */
    public PlaceOrderCheckEvent dispatchPlaceOrderCheck(
            long coreSequence, long userId, PlaceOrderCheckEvent event, java.util.UUID commandId,
            SystemFunds.SettlementStatus settlementStatus,
            int assetId, IdIndex identities,
            long timestamp, long position) {
        return laneCoordinator.dispatchPlaceOrderCheck(coreSequence, userId, event, commandId, settlementStatus,
                assetId, identities, timestamp, position);
    }

    /** 调用方完成准入收集后清空事件并归还池。 */
    public void releasePlaceOrderCheck(PlaceOrderCheckEvent event) { laneCoordinator.releasePlaceOrderCheck(event); }

    /** 准备改单准入，必要时先派发账本输入读取，再交给所属账户的普通工作邮箱。失败时仍由账本持有的输入不能回收，否则丢弃事件并归还池。 */
    public ReplaceOrderCheckEvent dispatchReplaceOrderCheck(long sequence, long userId,
            long originalOrderId, com.surprising.aeron.protocol.AmendOrderCommand amend,
            com.surprising.aeron.protocol.ReplaceOrderCommand replace,
            com.surprising.aeron.service.order.OrderCheckService.Context decision,
            int matcherShard, IdIndex identities) {
        return laneCoordinator.dispatchReplaceOrderCheck(sequence, userId, originalOrderId, amend, replace,
                decision, matcherShard, identities);
    }

    /** 在 Owner 上把已完成收集的改单准入事件清空并归还池。 */
    public void releaseReplaceOrderCheck(ReplaceOrderCheckEvent event) { laneCoordinator.releaseReplaceOrderCheck(event); }

    /** 为该用户借出专用变化槽及批次准入事件，准备订单输入后在冷阶段执行或投递账户准入邮箱。 */
    public PlaceBatchCheckEvent dispatchPlaceBatchOrderCheck(
            long coreSequence, long userId, java.util.UUID commandId,
            ResolvedPlaceOrder[] orders, long[] clientKeys,
            Order[] admittedOrders, int itemCount,
            int matcherShard, IdIndex identities, PendingOrderBatch source,
            long timestamp, long position) {
        return laneCoordinator.dispatchPlaceBatchOrderCheck(coreSequence, userId, commandId, orders, clientKeys,
                admittedOrders, itemCount, matcherShard, identities, source, timestamp, position);
    }

    /** 只登记已经完成且未拒绝的批次准入，把各订单加入待完成预留索引，并按批次项数递增修订。 */
    public void registerPlaceBatchOrderCheck(PlaceBatchCheckEvent event) { laneCoordinator.registerPlaceBatchOrderCheck(event); }

    /** 在该批次有序提交时才接入账户回滚前值，移交准入标记及可用余额变化。账户实体仍归 Lane，不重放用户、订单和预留发布缓冲。 */
    public void collectPlaceBatchOrderCheck(PlaceBatchCheckEvent event) { laneCoordinator.collectPlaceBatchOrderCheck(event); }

    /** 在 Owner 上取走批次专用变化槽并归还。 */
    public void discardPlaceBatchOrderCheck(PlaceBatchCheckEvent event) { laneCoordinator.discardPlaceBatchOrderCheck(event); }

    /** 在 Owner 上清空已完成使用的批次准入事件并归还池，调用方先处理其变化槽。 */
    public void releasePlaceBatchOrderCheck(PlaceBatchCheckEvent event) { laneCoordinator.releasePlaceBatchOrderCheck(event); }

    /** 在所属 Lane 按相反顺序撤销本批已准入订单的待预留、客户号、订单与冻结，最后恢复原用户修订及持仓模式。 */
    public void rollbackPlaceBatchOrderCheckInLane(
            AccountPartition lane, long userId, long coreSequence, ResolvedPlaceOrder[] orders,
            long[] clientKeys, int admittedCount, UserAccount userBefore) {
        laneCoordinator.rollbackPlaceBatchOrderCheckInLane(lane, userId, coreSequence, orders, clientKeys,
                admittedCount, userBefore);
    }

    /** 先发布准入完成的 Core 序号，再设置 Lane 唤醒提示。新观察到置位时通知 Owner。 */
    public void publishPlaceOrderCheckReady(int laneId, long coreSequence) {
        placeOrderCheckReadyQueues[laneId].publish(coreSequence);
        placeOrderCheckReadyLanes.setBit(laneId);
    }

    /** 在 Owner 上采样并清除账户准入提示，实际完成序号仍需从队列取出。 */
    public long takePlaceOrderCheckReadyLaneMask() {
        assertOwner();
        return placeOrderCheckReadyLanes.getAndClear();
    }

    /** 在 Owner 上从指定 Lane 的准入完成队列取一个 Core 序号，空队列返回零。 */
    public long pollPlaceOrderCheckReady(int laneId) {
        assertOwner();
        return placeOrderCheckReadyQueues[laneId].poll();
    }

    /** 校验 Matcher 分片后发布账户准入就绪提示，唤醒对应撮合提交头。 */
    public void publishAccountOrderCheckReadyShard(int matcherShard) {
        if (matcherShard < 0 || matcherShard >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        accountOrderCheckReadyShards.setBit(matcherShard);
    }

    /** 在 Owner 上采样并清除 Matcher 分片的账户准入提示。 */
    public long takeAccountOrderCheckReadyShardMask() {
        assertOwner();
        return accountOrderCheckReadyShards.getAndClear();
    }

    /** 在 Owner 上检查工作健康并只探测准入或结算就绪提示，不消费完成序号，也不代替事件完成判断。 */
    public boolean hasMatchingNotifications() {
        assertOwner();
        assertAccountLanesHealthy();
        return placeOrderCheckReadyLanes.get() != 0
                || hasPlaceOrderCheckNotifications() || hasSettlementNotifications();
    }

    /** 只探测是否有账户准入对应的 Matcher 分片就绪提示，不消费业务结果。 */
    public boolean hasPlaceOrderCheckNotifications() {
        return accountOrderCheckReadyShards.get() != 0;
    }

    /** 探测直接结算提示，真实完成事实仍从原事件读取。 */
    public boolean hasSettlementNotifications() {
        return directSettlementReadyLanes.get() != 0;
    }

    /** 直接结算只发布 Lane 唤醒提示。原结算事件持有权威完成标记，不额外建立 Owner 序号队列或预期计数。 */
    public void publishDirectMatcherSettlementReady(int laneId) {
        directSettlementReadyLanes.setBit(laneId);
    }

    /** 采样并清除直接结算提示，实际完成状态仍查原结算事件。 */
    public long takeDirectMatcherSettlementReadyLaneMask() {
        return directSettlementReadyLanes.getAndClear();
    }

    /** 在有效执行范围内收集已经完成且未拒绝的单笔准入，禁止重复登记，建立 Owner 的待预留索引并递增修订，返回原解析结果。 */
    public ResolvedPlaceOrder collectPlaceOrderCheck(PlaceOrderCheckEvent event) {
        return laneCoordinator.collectPlaceOrderCheck(event);
    }

    /** 要求直接财务控制范围已经结束，再登记原撮合事件的财务结算。 */
    public void dispatchMatcherFinancialSettlement(TradeSettlement event) {
        laneCoordinator.dispatchMatcherFinancialSettlement(event);
    }

    /** 先尝试派发财务部分，推进内联财务结算并检查原事件整体完成状态。 */
    public boolean pollMatcherFinancialSettlement(TradeSettlement event) {
        return laneCoordinator.pollMatcherFinancialSettlement(event);
    }

    /** 等待账户及财务结算完成，标记事件单次收集。 */
    public boolean collectMatcherSettlement(
            TradeSettlement event,
            TerminalOrderSink terminalOrderSink,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        return laneCoordinator.collectMatcherSettlement(event, terminalOrderSink, changedUsers, changedOrders);
    }

    /** 判断当前命令是否已经收集该订单键，删除所用的空值也算有效变化。 */
    public boolean hasChangedOrder(long orderId) {
        assertOwner();
        return changedOrders.containsKey(orderId);
    }

    /** 取得当前命令已收集的订单变化。 */
    public Order changedOrderValue(long orderId) {
        assertOwner();
        return changedOrders.get(orderId);
    }

    /** 取得当前命令已收集的持仓变化，返回借用值而不是再次查询账户。 */
    public Position changedPositionValue(long positionKey) {
        assertOwner();
        return changedPositions.get(positionKey);
    }

    /** Owner 收集终态订单身份的接收器，只传订单、用户和客户号。 */
    @FunctionalInterface
    public interface TerminalOrderSink {
        /** 接收一个终态订单的原始身份，避免 Owner 再遍历订单对象取得这些字段。 */
        void accept(long orderId, long userId, String clientOrderId);

        /** 按已有 Lane 终态缓冲的顺序逐项调用接收器，不另建订单列表。 */
        default void acceptBatch(AccountPartitionCommit.Delta delta) {
            for (int index = 0; index < delta.terminalOrderCount(); index++) {
                accept(delta.terminalOrderId(index), delta.terminalOrderUser(index),
                        delta.terminalOrderClient(index));
            }
        }

        /** 本序号终态身份已经交接后的结束钩子，默认不做额外处理，由需要批量收尾的实现覆盖。 */
        default void completeSequence() {
        }
    }

    /** 在所属账户 Lane 完成与本计划序号匹配的待预留。按当前是否有撮合变化槽保存回滚前值及完成计数，补记订单变化和余额前后值。全字段未变化时复用准入版本，避免仅凭修订号判断提交元数据是否相同。 */
    public void completeMatcherPendingReservations(AccountPartition lane, TradeSettlementPlan plan,
                                            Order orderCheckVersion) {
        laneCoordinator.completeMatcherPendingReservations(lane, plan, orderCheckVersion);
    }

    /** 按计划逐个检查订单，只有原提交时间或日志位置变化时更新元数据。 */
    public void stampMatcherOrders(AccountPartition lane, TradeSettlementPlan plan,
                            long timestamp, long clusterPosition) {
        laneCoordinator.stampMatcherOrders(lane, plan, timestamp, clusterPosition);
    }

    /** 校验用户、资产及持仓身份后，标记用户、余额和持仓发生变化。 */
    public void recordUserSettlementChanges(long userId, int assetId, long positionKey) {
        assertOwner();
        if (userId <= 0 || assetId < 0 || positionKey <= 0) {
            throw new IllegalArgumentException("invalid user settlement changes");
        }
        markUserChanged(userId);
        markBalancesChanged();
        changedPosition(positionKey);
    }

    /** 在调用方已经拥有各账户状态的边界内，逐 Lane 重建本地哈希并重新发布提交序号。 */
    public void rebuildAccountLaneHashes() {
        assertOwner();
        for (AccountPartition lane : accountLanes) {
            lane.rebuildLocalHashes();
            publishLaneCommittedSequence(lane);
        }
    }

    /** 校验全局状态产品线及待预留计数，必要时取得账户访问权，先核对全部快照栅栏再捕获各 Lane。 */
    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(long fenceSequence, TradingCoreState globalState) {
        return snapshotViews.accountLaneSnapshots(fenceSequence, globalState);
    }

    /** 判断指定用户是否允许同账户自成交（跳过前置交叉撤单扫描）。 */
    public boolean selfTradeAllowed(long userId) {
        return configuration != null && configuration.selfTradeAllowedUsers().contains(userId);
    }

    /** 判断控制任务或账户结算环是否仍有尚未排空的异步项，供快照屏障轮询等待。 */
    public boolean hasUnfinishedSettlementRing() { return snapshotViews.hasUnfinishedSettlementRing(); }

    /** 要求控制任务、账户工作队列、待预留索引和本命令变化缓冲均已收尾。 */
    public void requireSnapshotFenceReady() { snapshotViews.requireSnapshotFenceReady(); }

    /** 判断变化缓冲或回滚前值是否仍未提交，并检查冷财库变化或活动财务控制槽。 */
    public boolean hasUncommittedCommandChanges() {
        assertOwner();
        return snapshotProjectionStateDirty() || systemFunds.hasChangedValues() || ledgerControl != null;
    }

    /** 只检查当前命令各类变化缓冲、余额标记及回滚前值是否非空，不代替全部异步任务完成判断。 */
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

    /** 在调用方已建立的恢复访问边界内，先核对清单及用户路由，再逐 Lane 恢复并发布提交序号，最后清恢复用待预留信息。激活前限制由外层恢复入口保证。 */
    public void restoreAccountLaneSnapshots(java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
                                            TradingCoreState globalState) {
        snapshotViews.restoreAccountLaneSnapshots(snapshots, fenceSequence, globalState);
    }

    /** 只校验快照清单：数量与 Lane 唯一性、应用及提交序号、用户无重复且路由正确，并与全局用户集合完全对应。 */
    public static void validateAccountLaneSnapshotManifest(
            java.util.List<AccountLaneSnapshot> snapshots, long fenceSequence,
            TradingCoreState globalState, LaneTopology topology) {
        AccountSnapshotViews.validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, topology);
    }

    /** 用 CAS 为尚未绑定的运行时登记当前线程。已经绑定时严格要求同一线程，不接纳其他 Lane 线程代替 Owner。 */
    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null && OWNER_THREAD.compareAndSet(this, null, current)) return;
        if (owner != current) {
            throw new IllegalStateException("trading runtime state is bound to another thread");
        }
    }

    /** 检查当前执行权限：Owner 直接通过，尚未绑定则尝试绑定，已经处于合法账户 Lane 命令范围的工作线程也允许通过。其他线程交给严格绑定检查拒绝。 */
    public void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == current) return;
        if (owner == null) { bindOwner(); return; }
        if (currentLaneCommandScope() != null) return;
        bindOwner();
    }

    /** 要求账户与账本资源均已关闭，逐个释放 Lane 和财库的所有者，再清运行时 Owner 引用，供被动状态交给另一线程。 */
    public void releaseOwnerForHandoff() {
        if (accountLanesStarted()) throw new IllegalStateException("all lanes must be closed before handoff");
        for (AccountPartition lane : accountLanes) lane.releaseOwnerForHandoff();
        systemFunds.releaseOwnerForHandoff();
        owner = null;
    }

    /** 在有效执行范围内读取当前业务修订。 */
    public long revision() {
        assertOwner();
        return revision;
    }

    /** 要求当前没有批量范围且变化与财务范围都干净，登记批量修改标记。 */
    public void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("order batch mutation scope is already active");
        if (snapshotProjectionStateDirty() || systemFunds.hasChangedValues() || ledgerControl != null) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        orderBatchMutationScope = true;
        systemFunds.beginOrderBatchMutationScope();
    }

    /** 存在批量范围时先结束冷财库对应范围，再清本地批量标记。 */
    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!orderBatchMutationScope) return;
        systemFunds.endOrderBatchMutationScope();
        orderBatchMutationScope = false;
    }

    /** 当前处于订单批量范围时拒绝指定业务域的交叉修改，范围外不额外拦截。 */
    void rejectUnsupportedOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    /** 为异步命令建立失败续步：中止私有实时批次，向各账户 Lane 派发待预留完成与前值恢复，返回可轮询回调。账户恢复收集完成后才恢复 Owner，再等待财务范围逆向收尾。 */
    public java.util.function.BooleanSupplier beginCommandRollback(long revisionCheckpoint, long coreSequence) {
        return snapshotViews.beginCommandRollback(revisionCheckpoint, coreSequence);
    }

    /** 同步恢复失败命令的预留、各 Lane 和 Owner 前值。该检查发生在账户恢复之后，因此不能把一次抛错理解为此前没有状态变化。 */
    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        snapshotViews.rollbackActiveCommand(revisionCheckpoint, coreSequence);
    }

    /** 当前全局修订已经达到 long 上限时抛出算术溢出异常，供修改前检查。 */
    public void requireRevisionCapacity() {
        assertOwner();
        if (revision == Long.MAX_VALUE) {
            throw new ArithmeticException("runtime command revision overflow");
        }
    }

    /** 账户控制任务活动时只累计本任务修订增量，其他情况通过元数据入口精确递增全局修订，溢出直接报错。 */
    public void incrementCommandRevision() {
        assertOwner();
        if (controlLanes.active()) {
            controlLanes.recordRevisionDelta();
            return;
        }
        setMetadata(productLine, Math.incrementExact(revision));
    }

    /** 校验产品线非空及修订非负后保存元数据，不在这里重新划分既有账户或执行产品线迁移。 */
    public void setMetadata(ProductLine productLine, long revision) {
        assertOwner();
        if (productLine == null || revision < 0) throw new IllegalArgumentException("invalid runtime metadata");
        this.productLine = productLine;
        this.revision = revision;
    }

    /** 取得本产品线当前风险扫描开关和预算配置。 */
    public CoreRiskScanControlView riskScanControl() {
        assertOwner();
        return riskScanControl;
    }

    /** 要求不在不支持的订单批量修改范围，且配置非空，再替换当前风险扫描配置。 */
    public void setRiskScanControl(CoreRiskScanControlView value) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan control");
        if (value == null) throw new IllegalArgumentException("risk scan control is required");
        riskScanControl = value;
    }

    /** 账户已启动时，要求当前已有账户命令范围或 Owner 已取得交接访问权。 */
    void requireAccountReadOwnership() {
        if (accountLanesStarted() && currentLaneCommandScope() == null && !ownerLaneAccess())
            throw new IllegalStateException("account read requires its owning Lane thread");
    }

    /** 在当前 Lane 范围内读取该用户，跨 Lane 返回空。 */
    public UserAccount user(long userId) { return entityQueries.user(userId); }

    /** 读取用户并在未注册时抛出带用户身份的参数异常。 */
    public UserAccount requireUser(long userId) {
        UserAccount user = user(userId);
        if (user == null) {
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        }
        return user;
    }

    /** 校验明确 Lane 与用户路由及所有权后，借出该用户的运行时值。 */
    public UserAccount user(AccountPartition lane, long userId) {
        requireLaneAccess(lane, userId);
        return lane.user(userId);
    }

    /** 当前账户范围内返回原余额，跨 Lane 直接拒绝。 */
    public AccountBalance balance(long userId, int assetId) { return entityQueries.balance(userId, assetId); }

    /** 核对明确 Lane 与用户的归属后，借出该资产原余额对象，缺少资产时返回空。 */
    public AccountBalance balance(AccountPartition lane, long userId, int assetId) {
        requireLaneAccess(lane, userId);
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        return balances == null ? null : balances.get(assetId);
    }

    /** 核对账户所有权后复制该用户的资产映射和可变余额对象。 */
    public IntObjectHashMap<AccountBalance> balancesForUser(AccountPartition lane, long userId) {
        requireLaneAccess(lane, userId);
        return copyBalances(lane.userBalances(userId));
    }

    /** 在该用户所属 Lane 扫描预留身份，取严格大于游标的最小订单号，没有后续项返回零。 */
    public long nextRiskReservationId(long userId, long cursor) { return entityQueries.nextRiskReservationId(userId, cursor); }

    /** 按持仓对外身份文本的字典序取下一项，返回它的内部长整数键。 */
    public long nextRiskPositionKey(long userId, String cursor, IdIndex identities) {
        return entityQueries.nextRiskPositionKey(userId, cursor, identities);
    }

    /** 核对读取权限并定位当前用户 Lane。已有范围必须路由一致，最后检查实际 Lane 所有权，不自行建立新的命令范围。 */
    AccountPartition riskCursorLane(long userId) { return entityQueries.riskCursorLane(userId); }

    /** 只查该用户已有索引，判断是否存在活动订单、非零持仓或仍有冻结数量的预留，供持仓模式修改前校验。 */
    public boolean hasOpenAccountExposure(long userId) { return entityQueries.hasOpenAccountExposure(userId); }

    /** 只查用户所属 Lane，判断同标的、同保证金模式是否有 OPEN 订单或非零持仓，供杠杆变更前校验。 */
    public boolean hasOpenLeverageExposure(CoreLeverageKey key, int symbolId) {
        return entityQueries.hasOpenLeverageExposure(key, symbolId);
    }

    /** 已有 Lane 范围时登记该 Lane 当前用户值，否则把用户身份加入 Owner 的本命令变化集合。 */
    public void markUserChanged(long userId) {
        assertOwner();
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) changedUsers.add(userId);
        else laneDelta(lane.laneId()).putUser(userId, lane.user(userId));
    }

    /** 在用户所属 Lane 的有效读取范围内复制预留身份集合。 */
    public long[] reservationIdsForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> reservationIdsForUser(lane, userId));
    }

    /** 在用户所属 Lane 的有效范围内读取预留数量，不为计数复制集合。 */
    public int reservationCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> reservationCountForUser(lane, userId));
    }

    /** 校验账户归属后复制当前预留身份，缺少索引时返回独立空集合。 */
    public long[] reservationIdsForUser(AccountPartition lane, long userId) {
        return entityQueries.reservationIdsForUser(lane, userId);
    }

    /** 校验账户归属后读取预留索引大小，缺少索引返回零。 */
    public int reservationCountForUser(AccountPartition lane, long userId) {
        requireLaneAccess(lane, userId);
        var ids = lane.reservationIds(userId);
        return ids == null ? 0 : ids.size();
    }

    /** 在所属账户范围内复制用户持仓内部键集合。 */
    public long[] positionKeysForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> positionKeysForUser(lane, userId));
    }

    /** 在所属账户范围内读取用户持仓键数量。 */
    public int positionCountForUser(long userId) {
        assertOwner();
        return onLane(userId, lane -> positionCountForUser(lane, userId));
    }

    /** 校验账户归属后复制持仓键，缺少索引时返回独立空集合。 */
    public long[] positionKeysForUser(AccountPartition lane, long userId) {
        requireLaneAccess(lane, userId);
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? new long[0] : keys.toArray();
    }

    /** 校验账户归属后直接读取持仓键数量，缺少索引返回零。 */
    public int positionCountForUser(AccountPartition lane, long userId) {
        requireLaneAccess(lane, userId);
        LongHashSet keys = lane.positionKeys(userId);
        return keys == null ? 0 : keys.size();
    }

    /** 校验账户归属后复制并排序杠杆业务键，返回只读集合。 */
    public NavigableSet<CoreLeverageKey> leverageKeysForUser(AccountPartition lane, long userId) {
        return entityQueries.leverageKeysForUser(lane, userId);
    }

    /** 核对明确 Lane 与杠杆键用户的路由及所有权后读取配置值，缺失可返回空。 */
    public Long leverage(AccountPartition lane, CoreLeverageKey key) {
        requireLaneAccess(lane, key.userId());
        return lane.leverage(key);
    }

    /** 校验明确 Lane 的所有权后直接借出该订单引用，沿用调用方已经建立的可见性边界。 */
    public Order order(AccountPartition lane, long orderId) {
        requireLaneAccess(lane);
        return lane.order(orderId);
    }

    /** 校验明确 Lane 的所有权后直接借出该订单预留引用。 */
    public OrderFundsReservation reservation(AccountPartition lane, long orderId) {
        requireLaneAccess(lane);
        return lane.reservation(orderId);
    }

    /** 校验明确 Lane 的所有权后直接借出该持仓引用。 */
    public Position position(AccountPartition lane, long positionKey) {
        requireLaneAccess(lane);
        return lane.position(positionKey);
    }

    /** 按当前 Lane、活动路由和必要的全 Lane 检索读取订单。Owner 准备范围仅按已变化、批量或触发子单规则暴露在途订单，返回引用仍遵守读取权限。 */
    public Order order(long orderId) { return entityQueries.order(orderId); }

    /** 按当前 Lane、活动路由和必要的全 Lane 检索读取预留。Owner 仅在该预留已变化或批量范围内取得在途值，没有订单读取的触发子单特例。 */
    public OrderFundsReservation reservation(long orderId) { return entityQueries.reservation(orderId); }

    /** 在有效账户权限内读取持仓，优先使用当前范围或持仓身份路由，必要时检查各 Lane。 */
    public Position position(long positionKey) { return entityQueries.position(positionKey); }

    /** 要求当前已经进入该用户 Lane 范围，借出原始持仓键列表。只能在范围退出前使用，缺失时返回共享空列表。 */
    public org.eclipse.collections.impl.list.mutable.primitive.LongArrayList positionKeysForUserAndSymbolPrimitive(
            long userId, int symbolId) {
        return entityQueries.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
    }

    /** 当前有账户范围时只查该 Lane，否则要求有效账户读取权限后逐 Lane 查强平状态，返回借用引用。 */
    public RiskScanner.LiquidationOrder liquidation(long liquidationId) { return entityQueries.liquidation(liquidationId); }

    /** 在用户所属 Lane 按标的及持仓方向索引定位活动强平，再取得其运行时引用。 */
    public RiskScanner.LiquidationOrder activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        return entityQueries.activeLiquidation(userId, symbolId, positionSide);
    }

    /** 供 Core 配置边界检查尚未结束的强平：禁止在账户命令范围内调用，优先查询已绑定事实索引。 */
    public boolean hasUnresolvedLiquidation(String instrumentId, int symbolId) {
        return entityQueries.hasUnresolvedLiquidation(instrumentId, symbolId);
    }

    /** 在有效执行范围内按内部标的身份读取本产品线标记价。 */
    public MarkPrice markPrice(int symbolId) {
        assertOwner();
        return markPrices.get(symbolId);
    }

    /** 要求当前线程拥有传入 Lane，再读取本运行时的共享标记价元数据。 */
    public MarkPrice markPrice(AccountPartition lane, int symbolId) {
        lane.assertOwner();
        return markPrices.get(symbolId);
    }

    /** 当前有账户范围时只查该 Lane，否则在有效账户权限下逐 Lane 查风险快照，返回借用引用。 */
    public RiskMarginCalculator.Snapshot riskSnapshot(long positionKey) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.riskSnapshot(positionKey);
        requireAccountReadOwnership();
        for (AccountPartition lane : accountLanes) {
            lane.assertOwner();
            RiskMarginCalculator.Snapshot value = lane.riskSnapshot(positionKey);
            if (value != null) return value;
        }
        return null;
    }

    /** 按内部标的身份取得当前风险扫描续步状态。 */
    public RiskScanner.State riskScan(int symbolId) {
        assertOwner();
        return riskScans.get(symbolId);
    }

    /** 在整体 complete 标记尚未结束的扫描中，选择标的内部身份最小的一条。 */
    public RiskScanner.State firstIncompleteRiskScan() { return marketCatalog.firstIncompleteRiskScan(); }

    /** 只在 riskComplete 尚未结束的扫描中，按最近调度修订、再按标的身份选择最先需要推进的一条。 */
    public RiskScanner.State firstRiskIncompleteScan() { return marketCatalog.firstRiskIncompleteScan(); }

    /** 先比较最近调度修订，相同再比较内部标的身份，形成确定的风险推进顺序。 */
    static int compareRiskProgress(RiskScanner.State left, RiskScanner.State right) {
        return AccountMarketCatalog.compareRiskProgress(left, right);
    }

    /** 统计整体 complete 标记尚未完成的扫描数量。 */
    public int incompleteRiskScanCount() { return marketCatalog.incompleteRiskScanCount(); }

    /** 读取下一个强平身份数值。 */
    public long nextLiquidationId() {
        assertOwner();
        return nextLiquidationId;
    }

    /** 在实际财库拥有者线程上物化不可变财库导出值，不把财库所有权交给 Owner。 */
    public CoreTreasuryState systemFundsForQuery(IdIndex identities) {
        return queryLedgerAtRest(ledger -> SnapshotWriter.writeSystemFunds(ledger, identities));
    }

    /** 在 Owner 的既有财库读取边界同步执行导出。 */
    public <T> T queryLedgerAtRest(java.util.function.Function<SystemFunds, T> read) {
        assertOwner();
        java.util.Objects.requireNonNull(read, "Ledger read is required");
        if (ledgerControl != null) throw new IllegalStateException("Treasury query requires committed control work");
        return read.apply(systemFunds);
    }

    /** 确认已有财务控制已完成财务阶段，再就地发布该范围内的持仓索引，供风险读取。 */
    public void publishRiskPositionInputs() {
        assertOwner();
        if (ledgerControl != null && !ledgerControl.allowsRiskInput())
            throw new IllegalStateException("risk input requires a completed financial phase before scope close");
        if (ledgerControl != null) ledgerControl.publishRiskPositionInputs();
    }

    /** 借出唯一财库引用，账户命令范围内禁止取得。账本激活后仍须遵守财库自己的线程检查，这个引用不转移所有权。 */
    public SystemFunds systemFunds() {
        assertOwner();
        if (currentLaneCommandScope() != null) {
            throw new IllegalStateException("Account Lane cannot access SystemLedger-owned Treasury");
        }
        return systemFunds;
    }

    /** 借出当前配置名称的迭代入口，供 Owner 在明确的查询输入边界内消费。沿用原配置迭代顺序，不创建独立快照。 */
    public Iterable<String> instrumentIdsForQuery() {
        assertOwner();
        return () -> new java.util.Iterator<>() {
            /** 当前配置集合的借用迭代器，消费期间沿用 Owner 查询输入边界，不复制配置。 */
            private final java.util.Iterator<CoreInstrument> values = instruments.values().iterator();
            /** 沿用原配置迭代器判断是否还有标的。 */
            public boolean hasNext() { return values.hasNext(); }
            /** 从原配置迭代器取得下一条标的配置并返回其外部身份。 */
            public String next() { return values.next().instrumentId(); }
        };
    }

    /** 把外部标的 ID 解析为内部身份，再读取已有标的配置。 */
    public CoreInstrument instrument(String instrumentId) {
        return instrument(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    public CoreInstrument requireInstrument(String instrumentId) {
        CoreInstrument instrument = instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        return instrument;
    }

    /** 在有效执行范围内按内部身份读取标的配置，缺失返回空。 */
    public CoreInstrument instrument(int instrumentId) {
        assertOwner();
        return instruments.get(instrumentId);
    }

    /** 要求当前线程拥有传入 Lane，再解析外部标的 ID 并读取共享配置。 */
    public CoreInstrument instrument(AccountPartition lane, String instrumentId) {
        lane.assertOwner();
        return instruments.get(com.surprising.product.api.utils.InstrumentIds.parse(instrumentId));
    }

    /** 禁止订单批量范围内注册，校验配置非空且身份未注册后保存原配置对象。 */
    public void registerInstrument(CoreInstrument instrument) { marketCatalog.registerInstrument(instrument); }

    /** 要求原对象正是注册表中的对象，且新旧标的身份一致，再原地更新配置，保持已有引用身份。 */
    public void updateInstrumentConfiguration(CoreInstrument instrument, CoreInstrument updated) {
        marketCatalog.updateInstrumentConfiguration(instrument, updated);
    }

    /** 校验已注册对象身份和维护参数，在允许的控制边界内原地更新维护状态。 */
    public void updateInstrumentMaintenance(
            CoreInstrument instrument,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        marketCatalog.updateInstrumentMaintenance(instrument, maintenance);
    }

    /** 在用户所属账户范围内用正式业务键读取杠杆值。 */
    public Long leverage(CoreLeverageKey key) {
        assertOwner();
        return onLane(key.userId(), lane -> lane.leverage(key));
    }

    /** 核对明确 Lane 与用户归属后，复用该 Lane 探针读取杠杆，finally 清理探针引用。 */
    public Long leverage(AccountPartition lane, long userId, String canonicalSymbol, CoreMarginMode marginMode) {
        requireLaneAccess(lane, userId);
        LeverageLookup lookup = lane.leverageLookup();
        lookup.set(userId, canonicalSymbol, marginMode);
        try { return lane.leverage(lookup); }
        finally { lookup.clear(); }
    }

    /** 校验杠杆至少为一倍且不在订单批量范围内，写入用户所属 Lane 并登记用户杠杆键。范围外调用再记录 Owner 变化。 */
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

    /** 收集已经完成的杠杆变化并按需捕获实时值，账户任务不直接操作共享捕获器。 */
    public void recordLeverageChange(CoreLeverageKey key, long leveragePpm) {
        assertOwner();
        changedLeverages.add(key);
        if (realtimeCapture != null) realtimeCapture.leverage(key, leveragePpm);
    }

    /** 取得事实索引中的算法单路由，尚未绑定索引时返回空。 */
    public com.surprising.aeron.service.core.index.AlgoOrderIndex.AlgoRoute algoRoute(long id) {
        assertOwner(); return bizIndex == null ? null : bizIndex.algos().value(id);
    }

    /** 优先取已有触发单路由。 */
    public com.surprising.aeron.service.core.index.TriggerOrderIndex.TriggerRoute triggerRoute(long id) {
        assertOwner();
        if (bizIndex != null) return bizIndex.triggers().route(id);
        requireAccountReadOwnership();
        var value = triggerOrder(id);
        return value == null ? null : com.surprising.aeron.service.core.index.TriggerOrderIndex.TriggerRoute.from(value);
    }

    /** 当前有账户范围时只查该 Lane，否则在有效读取权限下逐 Lane 查询算法单。 */
    public CoreAlgoOrderState algoOrder(long algoOrderId) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.algoOrder(algoOrderId);
        requireAccountReadOwnership();
        for (AccountPartition lane : accountLanes) {
            lane.assertOwner();
            CoreAlgoOrderState value = lane.algoOrder(algoOrderId);
            if (value != null) return value;
        }
        return null;
    }

    /** 在用户所属 Lane 检查同用户客户算法单号是否已经存在。 */
    public boolean hasAlgoClient(long userId, String clientId) {
        assertOwner();
        return onLane(userId, lane -> lane.anyAlgoOrder(
 value -> value.userId() == userId && value.clientAlgoOrderId().equals(clientId)));
    }

    /** 把算法单变化登记到当前 Lane 容器或 Owner 本命令变化缓冲。 */
    public void publishAlgoOrder(CoreAlgoOrderState order) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null) changedAlgoOrders.put(order.algoOrderId(), order);
        else laneDelta(scoped.laneId()).putAlgo(order.algoOrderId(), order);
    }

    /** 校验算法单非空且允许修改后，在所属 Lane 保存并登记变化。 */
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

    /** 读取用户及标的范围对应的定时全撤状态。 */
    public CoreCancelAllAfterState cancelAllAfterTimer(CoreCancelAllAfterKey key) {
        assertOwner();
        return cancelAllAfterTimers.get(key);
    }

    /** 校验业务键与定时器非空，写入定时全撤表并记录变化键。 */
    public void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        cancelAllAfterTimers.put(key, timer);
        changedCancelAllAfterTimers.add(key);
    }

    /** 核对用户后按用户及币对范围读旧计时器。 */
    public void updateCancelAllAfter(long userId, CoreCancelAllAfterCommand command) {
        algoOps.updateCancelAllAfter(userId, command);
    }

    /** 读取本命令已经收集的触发单变化，保留到有序发布清理结束。 */
    public CoreTriggerOrderState changedTriggerOrder(long id) { assertOwner(); return changedTriggerOrders.get(id); }

    /** 当前有账户范围时只查该 Lane，否则在有效账户权限下逐 Lane 读取触发单。 */
    public CoreTriggerOrderState triggerOrder(long triggerOrderId) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.triggerOrder(triggerOrderId);
        requireAccountReadOwnership();
        for (AccountPartition lane : accountLanes) {
            lane.assertOwner();
            CoreTriggerOrderState value = lane.triggerOrder(triggerOrderId);
            if (value != null) return value;
        }
        return null;
    }

    /** 保存触发单修改前值，在用户所属 Lane 写入新值并登记变化。禁止不支持的订单批量交叉修改。 */
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

    /** 要求明确 Lane 归属且已经接入撮合变化容器，直接写入触发单并交接本次变化。 */
    public void putTriggerOrder(AccountPartition lane, CoreTriggerOrderState triggerOrder) {
        requireLaneAccess(lane, triggerOrder.userId());
        rejectUnsupportedOrderBatchMutation("trigger-order state");
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("trigger completion requires its settlement Lane");
        lane.putTrigger(triggerOrder);
        laneDelta(lane).putTrigger(triggerOrder.triggerOrderId(), triggerOrder);
    }

    /** 按外部标的名称建立有序映射副本，配置对象本身仍与运行时共享。 */
    public Map<String, CoreInstrument> instrumentsForRuntime() { return marketCatalog.instrumentsForRuntime(); }

    /** 在调用方建立的读取边界内，收集各 Lane 杠杆快照到新有序映射。 */
    public Map<CoreLeverageKey, Long> leveragesForRuntime() { return snapshotViews.leveragesForRuntime(); }

    /** 在已有读取边界内，把各 Lane 算法单引用收集到新有序映射，不深复制业务值。 */
    public Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() { return snapshotViews.algoOrdersForRuntime(); }

    /** 借出定时全撤原映射，沿用 Owner 与恢复边界，不返回独立副本。 */
    public Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return cancelAllAfterTimers;
    }

    /** 在所属账户触发单索引中检查客户触发单号是否存在。 */
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

    /** 汇总同标的、模式、方向及买卖侧的活动触发数量，再用当前同 OCO 组最大值与新数量的较大者替换该最大项。 */
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

    /** 有当前账户范围时只复制该 Lane 的触发单映射，否则在已有读取边界内收集各 Lane。 */
    public Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() { return snapshotViews.triggerOrdersForRuntime(); }

    /** 在 Owner 路由边界读取财库保存的划转用户身份，账户命令范围禁止调用。 */
    public long pendingTransferUser(long transferId) { return marketCatalog.pendingTransferUser(transferId); }

    /** 当前账户范围内读取本 Lane 划转。 */
    public TransferFundsCommand pendingTransfer(long transferId) { return marketCatalog.pendingTransfer(transferId); }

    /** 要求活动 Owner 已取得账户访问权，复制各 Lane 待完成划转引用并返回只读有序映射。 */
    public Map<Long, TransferFundsCommand> pendingTransfersSnapshot() { return marketCatalog.pendingTransfersSnapshot(); }

    /** 向财库取得有限页的划转 ID、用户 ID 和 Lane 掩码，仅交接路由，不复制账户划转正文。 */
    public SystemFunds.PendingTransferRoutes pendingTransferRoutes(int limit) { return marketCatalog.pendingTransferRoutes(limit); }

    /** 用该页最后一个划转 ID 推进财库分页边界，空页使用零。 */
    public void completePendingTransferPage(SystemFunds.PendingTransferRoutes page) { marketCatalog.completePendingTransferPage(page); }

    /** 要求账户已停止，校验映射容量、键值非空及划转身份一致，再清旧路由与账户值并逐项恢复。 */
    public void restorePendingTransfers(Map<Long, TransferFundsCommand> restored) { marketCatalog.restorePendingTransfers(restored); }

    /** 查询财库路由索引是否还有待完成划转容量。 */
    public boolean hasPendingTransferCapacity() { return marketCatalog.hasPendingTransferCapacity(); }

    /** 把划转 ID 与所属用户登记到唯一财库路由索引。 */
    public void registerPendingTransferRoute(long id, long userId) { marketCatalog.registerPendingTransferRoute(id, userId); }

    /** 让财库按划转 ID 和用户身份完成对应路由，不在此重复删除账户正文。 */
    public void completePendingTransferRoute(long id, long userId) { marketCatalog.completePendingTransferRoute(id, userId); }

    /** 禁止订单批量交叉修改，在所属账户范围保存新划转。 */
    public void putPendingTransfer(TransferFundsCommand transfer) { marketCatalog.putPendingTransfer(transfer); }

    /** 核对划转所属用户与 Lane，存在时检查修订容量并删除账户值。未找到返回假，用户冲突按幂等冲突拒绝。 */
    public boolean removePendingTransfer(long transferId, long userId) { return marketCatalog.removePendingTransfer(transferId, userId); }

    /** 取得当前不可变费用政策版本，沿用其已有读写边界。 */
    public FeePolicyBook feePolicyBook() { return marketCatalog.feePolicyBook(); }

    /** 取得当前费用政策书提供的快照映射。 */
    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() { return marketCatalog.feePoliciesSnapshot(); }

    /** 要求账户已停止，从恢复映射重建费用政策版本，并清空政策变化身份。 */
    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) { marketCatalog.restoreFeePolicies(restored); }

    /** 活动阶段只允许配置 Lane 0 修改，构造并合并不可变政策版本。内容未变直接返回，否则登记变化并推进命令修订。 */
    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) { marketCatalog.upsertFeePolicy(command); }

    /** 按给定状态替换产品元数据、风险配置、标的、杠杆、算法单、定时器和触发单。依次调用现有 Lane 入口，不替换资金、订单或持仓，也不自行建立回滚事务。 */
    public void replaceAuxiliaryState(TradingCoreState source) { marketCatalog.replaceAuxiliaryState(source); }

    /** 查客户号对应订单，把原始索引的缺失值零转换为空引用。 */
    public Long orderIdByClient(long userId, long clientKey) {
        assertOwner();
        long orderId = orderIdByClientValue(userId, clientKey);
        return orderId == 0 ? null : orderId;
    }

    /** 在当前所属账户范围或临时用户 Lane 范围中查客户号，跨 Lane 拒绝，缺失保留零。 */
    public long orderIdByClientValue(long userId, long clientKey) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
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

    /** 核对明确 Lane 与用户归属后读取客户号原始索引，缺少用户映射返回零。 */
    public long orderIdByClientValue(AccountPartition lane, long userId, long clientKey) {
        requireLaneAccess(lane, userId);
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        return userClientOrders == null ? 0L : userClientOrders.get(clientKey);
    }

    /** 保存用户前值，在所属 Lane 写入用户并登记用户身份。 */
    public void putUser(UserAccount user) { entityMutations.putUser(user); }

    /** 把用户修订按默认一步推进。 */
    public void advanceUserRevision(long userId) { advanceUserRevision(userId, 1); }

    /** 要求正增量。 */
    public void advanceUserRevision(long userId, long count) { entityMutations.advanceUserRevision(userId, count); }

    /** 使用明确 Lane，把用户修订按默认一步推进。 */
    public void advanceUserRevision(AccountPartition lane, long userId) { advanceUserRevision(lane, userId, 1); }

    /** 核对 Lane 后忽略非正增量。有撮合变化槽时累计用户差额，否则交给普通修订入口。 */
    public void advanceUserRevision(AccountPartition lane, long userId, long count) {
        entityMutations.advanceUserRevision(lane, userId, count);
    }

    /** 校验用户、修订及持仓模式，保存前值，在所属 Lane 创建或更新用户元数据，再交接独立变化收据。 */
    public void setUserState(long userId, long revision, com.surprising.aeron.protocol.CorePositionMode mode) {
        entityMutations.setUserState(userId, revision, mode);
    }

    /** 读取输入余额数值，保存前值后在所属 Lane 建立独立余额对象并捕获后值。没有外层账户范围时再标记 Owner 变化。 */
    public void putBalance(AccountBalance balance) { entityMutations.putBalance(balance); }

    /** 明确撮合 Lane 内保存给定余额对象及前后补丁。 */
    public void putBalance(AccountPartition lane, AccountBalance balance) { entityMutations.putBalance(lane, balance); }

    /** 保存用户及订单前值，在所属 Lane 写入订单，再登记订单与用户变化。 */
    public void putOrder(Order order) { entityMutations.putOrder(order); }

    /** 保存前值，旧预留若仍待撮合则拒绝。 */
    public void putReservation(OrderFundsReservation reservation) { entityMutations.putReservation(reservation); }

    /** 保存订单前值并禁止更换用户，在所属 Lane 替换订单。没有撮合变化范围时登记 Owner 订单和用户变化。 */
    public void replaceOrder(Order order) { entityMutations.replaceOrder(order); }

    /** 把余额对象的用户、资产及数值交给原地更新入口。 */
    public void replaceBalance(AccountBalance balance) {
        assertOwner();
        replaceBalance(balance.userId(), balance.assetId(), balance.availableUnits(), balance.lockedUnits());
    }

    /** 在有效账户范围内保存前值并原地替换余额，跨 Lane 拒绝。没有范围时临时进入用户 Lane，退出后补记 Owner 变化。 */
    public void replaceBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        entityMutations.replaceBalance(userId, assetId, availableUnits, lockedUnits);
    }

    /** 核对明确 Lane 与用户，撮合范围内只捕获本 Lane 补丁并原地修改，否则沿用普通更新。 */
    public void replaceBalance(AccountPartition lane, long userId, int assetId, long available, long locked) {
        entityMutations.replaceBalance(lane, userId, assetId, available, locked);
    }

    /** 在所属 Lane 创建或原地更新持仓。 */
    public Position updatePositionInLane(AccountPartition lane, long positionKey, long userId, int symbolId, int assetId,
                                         CoreInstrument instrument, long signedQuantitySteps,
                                         long entryPriceTicks, long entryValueTicks,
                                         long realizedPnlUnits, long positionMarginUnits,
                                         CoreMarginMode marginMode, CorePositionSide positionSide) {
        return entityMutations.updatePositionInLane(lane, positionKey, userId, symbolId, assetId, instrument,
                signedQuantitySteps, entryPriceTicks, entryValueTicks, realizedPnlUnits, positionMarginUnits,
                marginMode, positionSide);
    }

    /** 在账户线程原地更新订单执行数量、费用、状态及提交元数据，写入成交价值高低位，再发布本次变化。 */
    public Order updateOrderInLane(AccountPartition lane, long orderId, long executed, long remaining, long feeUnits,
                                   long executedValueHigh, long executedValueLow,
                                   CoreOrderStatus status, long revision,
                                   long commitTimestamp, long commitPosition) {
        return entityMutations.updateOrderInLane(lane, orderId, executed, remaining, feeUnits, executedValueHigh,
                executedValueLow, status, revision, commitTimestamp, commitPosition);
    }

    /** 在账户线程原地更新订单状态、修订及提交元数据，再交出订单变化。 */
    public Order updateOrderStatusInLane(AccountPartition lane, long orderId, CoreOrderStatus status, long revision,
                                         long commitTimestamp, long commitPosition) {
        return entityMutations.updateOrderStatusInLane(lane, orderId, status, revision, commitTimestamp, commitPosition);
    }

    /** 要求预留存在且释放累计量不回退，原地更新消耗与释放，同步待预留数量差，按开关交出变化。 */
    public OrderFundsReservation updateReservationInLane(AccountPartition lane, long orderId, long nextConsumed, long nextReleased,
                                               boolean publish) {
        return entityMutations.updateReservationInLane(lane, orderId, nextConsumed, nextReleased, publish);
    }

    /** 保存持仓前值并禁止更换用户，在所属 Lane 替换实体及索引，随后按当前范围登记持仓与用户变化。 */
    public void replacePosition(long positionKey, Position position) { entityMutations.replacePosition(positionKey, position); }

    /** 要求强平身份尚未存在，在所属 Lane 保存强平及活动索引并交出变化，随后登记用户变化。 */
    public void putLiquidation(RiskScanner.LiquidationOrder liquidation) { entityMutations.putLiquidation(liquidation); }

    /** 在允许的非批量范围内写入标记价，并记录标的变化身份。 */
    public void putMarkPrice(MarkPrice markPrice) { marketCatalog.putMarkPrice(markPrice); }

    /** 保存用户及风险快照前值，在所属 Lane 写入并交出变化，登记相关用户。 */
    public void putRiskSnapshot(long positionKey, RiskMarginCalculator.Snapshot snapshot) {
        entityMutations.putRiskSnapshot(positionKey, snapshot);
    }

    /** 在允许的非批量范围内保存标的风险扫描续步，并登记标的变化。 */
    public void putRiskScan(RiskScanner.State scan) { marketCatalog.putRiskScan(scan); }

    /** Owner 校验期望控制版本，生成下一版本的启用、间隔和工作量配置。 */
    public void updateRiskScanControl(UpdateRiskScanControlCommand command, long updatedAtEpochMillis) {
        marketCatalog.updateRiskScanControl(command, updatedAtEpochMillis);
    }

    /** 直接写入已使用整数币对编号的运行时扫描记录，再增加命令修订号。 */
    public void replaceRiskScan(RiskScanner.State scan) { marketCatalog.replaceRiskScan(scan); }

    /** 校验非负后保存调用方提供的行情修订。 */
    public void setMarketRevision(long value) {
        assertOwner();
        if (value < 0) throw new IllegalArgumentException("invalid market revision");
        marketRevision = value;
    }

    /** 禁止订单批量交叉修改，校验正值后保存下一个强平身份。 */
    public void setNextLiquidationId(long value) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (value <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        nextLiquidationId = value;
    }

    /** 要求原强平存在，先移除旧活动索引，按新用户保存并重建索引。 */
    public void replaceLiquidation(RiskScanner.LiquidationOrder liquidation) {
        assertOwner();
        accountRollback.captureUserBefore(liquidation.userId());
        RiskScanner.LiquidationOrder previous = liquidation(liquidation.liquidationId());
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

    /** 保存用户、订单与预留前值，在所属账户完成精确冻结释放及终态更新，再交出变化并推进用户修订。 */
    public void cancelOrder(long orderId, long userId, long releaseUnits) { orderOps.cancelOrder(orderId, userId, releaseUnits); }

    /** 使用原负值元数据哨兵，在明确撮合 Lane 中取消订单。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, long orderId) { orderOps.cancelOrderInLane(lane, userId, orderId); }

    /** 在明确撮合 Lane 中释放剩余冻结并把已解析订单改为 CANCELED，避免重复 HashMap 查表。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, Order order, long timestamp, long position) {
        orderOps.cancelOrderInLane(lane, userId, order, timestamp, position);
    }

    /** 在明确撮合 Lane 中释放剩余冻结并把订单改为 CANCELED。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, long orderId, long timestamp, long position) {
        orderOps.cancelOrderInLane(lane, userId, orderId, timestamp, position);
    }

    /** 在明确撮合 Lane 中释放剩余冻结并把订单改为 REJECTED。 */
    public void rejectOrderInLane(AccountPartition lane, long userId, long orderId, long timestamp, long position) {
        orderOps.rejectOrderInLane(lane, userId, orderId, timestamp, position);
    }

    /** 在明确撮合 Lane 内释放已经终态订单仍保留的冻结，原地更新预留及余额补丁。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, long orderId) {
        return orderOps.releaseTerminalReservationInLane(lane, orderId);
    }

    /** 在明确撮合 Lane 内释放已持有的终态订单引用，省去 Lane 内部二次查表。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, Order preparedOrder,
            OrderFundsReservation preparedReservation) {
        return orderOps.releaseTerminalReservationInLane(lane, preparedOrder, preparedReservation);
    }

    /** 在明确撮合 Lane 内释放已经终态订单仍保留的冻结，原地更新预留及余额补丁。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, long orderId,
            Order preparedOrder, OrderFundsReservation preparedReservation) {
        return orderOps.releaseTerminalReservationInLane(lane, orderId, preparedOrder, preparedReservation);
    }

    /** 保存用户及客户号索引前值，在所属 Lane 更新客户号与订单的双向索引，并标记用户变化。 */
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

    /** 冷阶段内联捕获并返回完成或失败的 future。 */
    public java.util.concurrent.CompletableFuture<TradingRealtimeBoundary.UserSnapshot> realtimeSnapshot(long userId) {
        return stateChanges.realtimeSnapshot(userId);
    }

    /** 接入可空实时捕获器，不在此启动输出线程。 */
    public void realtimeCapture(TradingRealtimeBoundary.StateCapture capture) { assertOwner(); realtimeCapture = capture; }

    /** 实时捕获器存在时记录用户元数据，捕获运行时异常只记失败。 */
    public void captureUserMetadata(com.surprising.aeron.service.account.UserAccount value) { stateChanges.captureUserMetadata(value); }

    /** 实时捕获器存在时记录预留变化，捕获运行时异常只记失败。 */
    public void captureReservation(OrderFundsReservation value) { stateChanges.captureReservation(value); }

    /** 启用私有捕获时导出本命令风险、订单和持仓变化。 */
    public void captureRealtimeChanges(TradingRealtimeBoundary.StateCapture capture) { stateChanges.captureRealtimeChanges(capture); }

    /** 校验接收函数后直接遍历原始变化用户身份，不先创建集合副本。 */
    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) { stateChanges.acceptChangedUserIds(consumer); }

    /** 标记本命令涉及余额及用户变化。 */
    public void markBalanceChanged(long userId) { stateChanges.markBalanceChanged(userId); }

    /** 按当前范围把预留身份交给 Owner 集合或所属 Lane 的变化容器。 */
    void markReservationChanged(long orderId) { stateChanges.markReservationChanged(orderId); }

    /** 活动账户没有当前范围时要求已有收集收据，避免退回账户读取。 */
    public void changedOrder(long orderId) { stateChanges.changedOrder(orderId); }

    /** 按当前执行范围登记订单值或删除标记，不在此再次修改账户。 */
    public void changedOrder(long orderId, Order order) { stateChanges.changedOrder(orderId, order); }

    /** 活动账户没有当前范围时要求已有持仓收据。 */
    void changedPosition(long positionKey) { stateChanges.changedPosition(positionKey); }

    /** 把持仓变化或空删除值交给当前 Lane 容器或 Owner 缓冲。 */
    void changedPosition(long positionKey, Position position) { stateChanges.changedPosition(positionKey, position); }

    /** 借出当前持仓变化键的原始迭代器，消费期间保留变化缓冲有效。 */
    public org.eclipse.collections.api.iterator.LongIterator changedPositionIterator() {
        assertOwner();
        return changedPositions.longIterator();
    }

    /** 判断本命令持仓变化缓冲是否非空。 */
    public boolean hasChangedPositions() {
        assertOwner();
        return !changedPositions.isEmpty();
    }

    /** 遍历直接及保留 Lane 变化缓冲，直接更新运行时绑定的事实索引。 */
    public void applyChangedIndexes() { stateChanges.applyChangedIndexes(); }

    /** 日志发布后先处理风险与持仓身份退休，再清变化容器并执行普通提交清理。 */
    public void clearCommittedChanges(IdIndex identities) { stateChanges.clearCommittedChanges(identities); }

    /** 只有风险记录不再持有该键时才请求身份表释放持仓身份。 */
    void releaseRetiredPositionIdentity(IdIndex identities, long positionKey) {
        stateChanges.releaseRetiredPositionIdentity(identities, positionKey);
    }

    /** 衍生品直接结算在当前 Lane 输出中只保留一次持仓身份，核对保留结果未换键，再登记当前持仓值以便发布后归还。 */
    public void retainDirectPositionIdentity(AccountPartition lane, IdIndex identities,
                                      CoreInstrument instrument, Order order) {
        stateChanges.retainDirectPositionIdentity(lane, identities, instrument, order);
    }

    /** 用当前修订精确减去待完成预留数，得到当前可提交修订。 */
    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, pendingReservations.totalPendingReservations());
    }

    /** 活动财务控制已有专用槽时沿用其校验。否则仅在账户余额或持仓有变化时同步校验。校验失败直接抛出，财务范围关闭仍需后续提交或回滚。 */
    public void validateControlFunds(boolean externalAdjustment) { stateChanges.validateControlFunds(externalAdjustment); }

    /** 遍历现有各 Lane 余额前后补丁，将本控制命令资金差额追加到目标累加器，不清空目标。 */
    public void appendControlAccountFunds(FundsTotal target) { stateChanges.appendControlAccountFunds(target); }

    /** 清空目标累加器后，按一份 Lane 余额补丁重建资金差额。 */
    public static void prepareBalanceFundsDelta(AccountTransactionRollback.BalancePatches patches, FundsTotal accumulator) {
        AccountStateChangeTracker.prepareBalanceFundsDelta(patches, accumulator);
    }

    /** 要求每项余额已发布后值，将缺失前后值按零处理，分别精确计算可用与冻结差额并追加用户分账项。 */
    static void appendBalanceFundsDelta(AccountTransactionRollback.BalancePatches balances, FundsTotal accumulator) {
        AccountStateChangeTracker.appendBalanceFundsDelta(balances, accumulator);
    }

    /** 借出当前命令回滚补丁保存的持仓前值。 */
    public Position currentPatchPositionBefore(long positionKey) {
        return accountRollback.currentPositionBefore(positionKey);
    }

    /** 提交或回滚收尾时清当前命令变化，按冷财库或活动控制槽结束财务范围，再发布清余额补丁并请求独立资金校验关闭。 */
    public void clearChangedKeys() { stateChanges.clearChangedKeys(); }

    /** 清空非空长整数变化集合，原数量达到压缩阈值时再压缩内部容量。 */
    static void clearChanged(LongHashSet values) { AccountStateChangeTracker.clearChanged(values); }

    /** 清空非空整数变化集合，原数量达到阈值时压缩内部容量。 */
    static void clearChanged(IntHashSet values) { AccountStateChangeTracker.clearChanged(values); }

    /** 在调用方已建立的读取边界内，合并各 Lane 用户快照到新映射，不自行取得账户权限。 */
    public LongObjectHashMap<UserAccount> usersForSnapshot() { return snapshotViews.usersForSnapshot(); }

    /** 在已有读取边界内合并各 Lane 余额快照，返回新顶层映射。 */
    public LongObjectHashMap<IntObjectHashMap<AccountBalance>> balancesForSnapshot() { return snapshotViews.balancesForSnapshot(); }

    /** 在已有读取边界内把各 Lane 订单引用收集到新映射，不深复制订单。 */
    public LongObjectHashMap<Order> ordersForSnapshot() { return snapshotViews.ordersForSnapshot(); }

    /** 在所属账户范围内，按用户、标的、持仓方向、买卖侧和保证金模式读取现有减仓单数量。 */
    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        return onLane(userId, lane -> lane.openReduceOnlyQuantity(
                userId, symbolId, positionSide, side, marginMode));
    }

    /** 在已有读取边界内收集各 Lane 预留引用到新映射。 */
    public LongObjectHashMap<OrderFundsReservation> reservationsForSnapshot() { return snapshotViews.reservationsForSnapshot(); }

    /** 在已有读取边界内合并各 Lane 客户订单号索引快照。 */
    public LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() { return snapshotViews.clientOrderIndexForSnapshot(); }

    /** 在已有读取边界内合并各 Lane 提供的持仓快照。 */
    public LongObjectHashMap<Position> positionsForSnapshot() { return snapshotViews.positionsForSnapshot(); }

    /** 在已有读取边界内合并各 Lane 提供的强平快照。 */
    public LongObjectHashMap<RiskScanner.LiquidationOrder> liquidationsForSnapshot() { return snapshotViews.liquidationsForSnapshot(); }

    /** 借出原标记价映射，由外层快照边界保护读取，不创建独立映射。 */
    public IntObjectHashMap<MarkPrice> markPricesForSnapshot() { return snapshotViews.markPricesForSnapshot(); }

    /** 在已有读取边界内合并各 Lane 提供的风险快照映射。 */
    public LongObjectHashMap<RiskMarginCalculator.Snapshot> riskSnapshotsForSnapshot() { return snapshotViews.riskSnapshotsForSnapshot(); }

    /** 借出原风险扫描映射，由外层快照边界保护读取。 */
    public IntObjectHashMap<RiskScanner.State> riskScansForSnapshot() { return snapshotViews.riskScansForSnapshot(); }

    public int riskScanCount() {
        assertOwner();
        return riskScans.size();
    }

    /** 校验已准备订单与预留的身份、用户、标的和数量一致，再以预留剩余量调用冻结安装入口。 */
    public void reserveOrder(Order order, OrderFundsReservation reservation, long clientKey) {
        orderOps.reserveOrder(order, reservation, clientKey);
    }

    /** 保存命令回滚前值，在所属 Lane 冻结指定资金并安装已有订单、预留及客户号。范围内通过 Lane 变化交接，范围外登记 Owner 变化。 */
    public void reserveOrder(Order order, OrderFundsReservation reservation, long clientKey, long reservedUnits) {
        orderOps.reserveOrder(order, reservation, clientKey, reservedUnits);
    }

    /** 用已解析参数构造独立 OPEN 订单，初始修订为一。 */
    static Order preparedOrder(com.surprising.product.api.ProductLine productLine,
            long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId,
            long timestamp, long position) {
        return AccountOrderOperations.preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position);
    }

    /** 纯冷阶段构造独立订单，活动执行要求当前所属 Lane 并使用共享退休栅栏的订单池。 */
    public Order prepareControlOrder(long userId, ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId) {
        return orderOps.prepareControlOrder(userId, command, commandId, symbolId);
    }

    /** 校验所属范围、身份、客户号及可用余额，选择已准备值或 Lane 池订单，冻结资金并写入预留、索引及用户修订。仅未传 prepared 时自行登记待预留，普通临时下单不保存控制命令回滚前值。 */
    public void placeOrderInLane(AccountPartition lane, long userId, ResolvedPlaceOrder command,
            java.util.UUID commandId, long requiredReservation, long clientKey,
            int symbolId, int assetId, long coreSequence, Order prepared, long timestamp, long position) {
        orderOps.placeOrderInLane(lane, userId, command, commandId, requiredReservation, clientKey,
                symbolId, assetId, coreSequence, prepared, timestamp, position);
    }

    /** 在所属 Lane 保存前值并禁止换用户，写入持仓及索引。没有撮合变化范围时再登记 Owner 变化。 */
    public void putPosition(long positionKey, Position position) {
        assertOwner();
        Position previous = onLane(position.userId(), lane -> {
            accountRollback.capturePositionBefore(positionKey, position.userId());
            Position prev = lane.position(positionKey);
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

    private static void replacePositionIndexes(AccountPartition lane, long positionKey,
                                               Position previous, Position replacement) {
        PositionIndexOps.replacePositionIndexes(lane, positionKey, previous, replacement);
    }

    public static void indexPosition(AccountPartition lane, long positionKey, Position position) {
        PositionIndexOps.indexPosition(lane, positionKey, position);
    }

    public static void unindexPosition(AccountPartition lane, long positionKey, Position position) {
        PositionIndexOps.unindexPosition(lane, positionKey, position);
    }

    /** 没有当前账户范围时设置 Owner 余额变化标记。 */
    public void markBalancesChanged() {
        if (currentLaneCommandScope() == null) balancesChanged = true;
    }

    public static void putClientOrderIndex(AccountPartition lane, long userId, long clientKey, long orderId) {
        ClientOrderIndexOps.putClientOrderIndex(lane, userId, clientKey, orderId);
    }

    public static Long removeClientOrderIndex(AccountPartition lane, long userId, long clientKey) {
        return ClientOrderIndexOps.removeClientOrderIndex(lane, userId, clientKey);
    }

    static void indexActiveLiquidation(AccountPartition lane, RiskScanner.LiquidationOrder liquidation) {
        LiquidationIndexOps.indexActiveLiquidation(lane, liquidation);
    }

    static void removeActiveLiquidation(AccountPartition lane, RiskScanner.LiquidationOrder liquidation) {
        LiquidationIndexOps.removeActiveLiquidation(lane, liquidation);
    }

    static boolean active(RiskScanner.LiquidationOrder liquidation) {
        return LiquidationIndexOps.active(liquidation);
    }

    /** 以同一业务序号和提交序号，把已准备单笔或批量订单交给直接结算准备入口。 */
    public TradeSettlement prepareDirectMatcherSettlement(long sequence, long laneMask,
            Order single, Order[] orders, int count, java.util.UUID commandId, int shard,
            IdIndex identities, long timestamp, long position, List<Long> cancellations,
            OrderResultTarget target) {
        return laneCoordinator.prepareDirectMatcherSettlement(sequence, laneMask, single, orders, count,
                commandId, shard, identities, timestamp, position, cancellations, target);
    }

    /** 普通下单准入尚未完成时先准备直接结算槽，固定命令证明字段。 */
    public TradeSettlement prepareDirectMatcherSettlement(
            long sequence, long laneMask, PlaceOrderCheckEvent orderCheck,
            java.util.UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, List<Long> cancellations, OrderResultTarget target) {
        return laneCoordinator.prepareDirectMatcherSettlement(sequence, laneMask, orderCheck, commandId, shard,
                identities, timestamp, position, cancellations, target);
    }

    /** 显式区分原业务序号与提交序号，准备直接撮合结算事件，不在此应用账户成交。 */
    public TradeSettlement prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, Order single, Order[] orders, int count, java.util.UUID commandId,
            int shard, IdIndex identities, long timestamp, long position,
            List<Long> cancellations, OrderResultTarget target) {
        return laneCoordinator.prepareDirectMatcherSettlement(coreSequence, commitSequence, laneMask, single, orders,
                count, commandId, shard, identities, timestamp, position, cancellations, target);
    }

    /** 把已解决的改单准入及原提交边界交给直接替换结算准备入口。 */
    public TradeSettlement prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.order.ReplaceOrderCheck orderCheck,
            java.util.UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, List<Long> cancellations, OrderResultTarget resultTarget) {
        return laneCoordinator.prepareDirectReplacement(sequence, commitSequence, laneMask, orderCheck, commandId,
                shard, identities, timestamp, position, cancellations, resultTarget);
    }

    /** 为直接撤单准备补上空的最终结果槽。 */
    public TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, IdIndex identities, long timestamp, long position) {
        return laneCoordinator.prepareDirectCancellation(sequence, userId, orderId, commandId, shard,
                identities, timestamp, position);
    }

    /** 按用户、订单和原提交边界准备直接撤单结算事件。 */
    public TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, OrderResultTarget resultTarget) {
        return laneCoordinator.prepareDirectCancellation(sequence, userId, orderId, commandId, shard,
                identities, timestamp, position, resultTarget);
    }

    /** 把批量撤单身份、有效项数及是否最后一段交给直接结算准备入口。 */
    public TradeSettlement prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders,
            int count, java.util.UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, OrderResultTarget resultTarget) {
        return laneCoordinator.prepareDirectCancelBatch(sequence, finalChunk, userId, orders, count,
                commandId, shard, identities, timestamp, position, resultTarget);
    }

    /** 下单步骤 4.3：单 Matcher 发布方校验事件与实际路由，向每个 Lane 的分片 SPSC 队列投递一次。 */
    public void publishMatcherOwned(TradeSettlement event) { laneCoordinator.publishMatcherOwned(event); }

    /** Owner 清理已完成且已交出 changes 的事件并归还对象池。具体完成与租约校验由 event.clear 执行，空事件不处理。 */
    public void releaseMatcherSettlement(TradeSettlement event) { laneCoordinator.releaseMatcherSettlement(event); }

    /** Owner 丢弃已完成的拒绝等直达事件。 */
    public void discardMatcherSettlement(TradeSettlement event) { laneCoordinator.discardMatcherSettlement(event); }

    /** 在批量账户作用域中等待本项结算完成，先把余额前后值按顺序接入批次回滚边界，再交既有收集入口处理变化。 */
    public void collectOrderBatchMatcherSettlement(TradeSettlement event, TerminalOrderSink terminalOrderSink) {
        laneCoordinator.collectOrderBatchMatcherSettlement(event, terminalOrderSink);
    }

    /** 借出包含直接与保留 Lane 输出的订单变化容器。 */
    public StateChangeBuffer.OwnerIndexed<Order, Void> orderChanges() { return changedOrders; }
    /** 借出包含持仓值及已准备索引的变化容器。 */
    public StateChangeBuffer.OwnerIndexed<Position, Position.IndexValue> positionChanges() { return changedPositions; }

    /** 按数组下标借出原账户 Lane，调用方保证下标及访问边界有效。 */
    public AccountPartition accountLane(int index) { return accountLanes[index]; }
    /** 返回账户 Lane 数量。 */
    public int accountLaneCount() { return accountLanes.length; }

    /** 直接向 Owner 变化集合加入用户身份，调用方保证当前收集范围正确。 */
    public void changedUser(long userId) { changedUsers.add(userId); }
    /** 直接向 Owner 变化集合加入预留订单身份。 */
    public void changedReservation(long orderId) { changedReservations.add(orderId); }
    /** 登记仍由风险记录持有的持仓身份，延后其退休释放。 */
    public void addRiskPositionIdentity(long key) { riskPositionIdentities.add(key); }
    /** 取消风险记录对持仓身份的保留标记。 */
    public void removeRiskPositionIdentity(long key) { riskPositionIdentities.remove(key); }
    /** 直接恢复调用方提供的修订值，不在此校验或推进业务。 */
    public void restoreRevision(long value) { revision = value; }

    /** 再次核对订单和客户端标识未占用、用户及资产余额存在且可用金额足够，准备订单和冻结，写入并推进用户和命令版本。 */
    public void placePreparedOrder(long userId, com.surprising.aeron.service.order.ResolvedPlaceOrder command, java.util.UUID commandId,
                                   long requiredReservation, long clientKey, int symbolId, int assetId) {
        orderOps.placePreparedOrder(userId, command, commandId, requiredReservation, clientKey, symbolId, assetId);
    }

    /** 在触发子单所属 Lane 计算预留并写订单，标记待完成冻结。 */
    public com.surprising.aeron.service.order.Order placeTriggerChildInLane(long userId,
            com.surprising.aeron.service.order.ResolvedPlaceOrder command, java.util.UUID commandId,
            long coreSequence, long openInterestSteps,
            com.surprising.aeron.service.core.identity.IdIndex identities,
            long clientKey, int assetId, boolean lifecycleSettled, boolean fundingInProgress) {
        return orderOps.placeTriggerChildInLane(userId, command, commandId, coreSequence, openInterestSteps,
                identities, clientKey, assetId, lifecycleSettled, fundingInProgress);
    }

    /** 在当前账户范围读取权威父单并应用算法父单命令更新。 */
    public void upsertAlgoOrder(com.surprising.aeron.service.core.identity.IdIndex identities,
                               long userId, com.surprising.aeron.protocol.CoreAlgoOrderCommand command, long clusterTimestamp) {
        algoOps.upsertAlgoOrder(identities, userId, command, clusterTimestamp);
    }

    /** 新建只接收意图。 */
    public void upsertAlgoOrder(long userId, com.surprising.aeron.protocol.CoreAlgoOrderCommand command,
                               int symbolId, long clusterTimestamp) {
        algoOps.upsertAlgoOrder(userId, command, symbolId, clusterTimestamp);
    }

    /** 条件单状态操作入口，与本运行时共用 Lane 所有权。 */
    public TriggerOrderOperations triggerOrders() {
        return triggerOps;
    }
}
