package com.surprising.aeron.service.core;

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
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.RuntimeFactIndexes;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.OrderFundsReservation.PendingTracker;
import com.surprising.aeron.service.order.OrderCommand.PlaceAdmissionEvent;
import com.surprising.aeron.service.order.OrderBatchProcessor.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.order.OrderBatchProcessor.PendingBatch;
import com.surprising.aeron.service.order.OrderCommand.ReplacementAdmissionEvent;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.OrderCommand.ResultTarget;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.risk.*;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.aeron.service.treasury.RuntimeFundsAccumulator;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
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
public final class TradingAccountRuntime implements AutoCloseable {
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
    final TreasuryRuntime treasury = new TreasuryRuntime();
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
    final UncommittedChanges changes = new UncommittedChanges();
    final PartitionCoordinator coordinator;
    private final TriggerOrderOperations triggerOps = new TriggerOrderOperations(this);

    static {
        try { OWNER_THREAD = java.lang.invoke.MethodHandles.lookup().findVarHandle(TradingAccountRuntime.class, "owner", Thread.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    static final class UncommittedChanges {
        static final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList EMPTY_POSITION_KEYS =
                new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(0);
        final IntObjectHashMap<RiskScanner.State> riskScans = new IntObjectHashMap<>();
        SystemLedgerCommand ledgerControl;
        final Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers = new HashMap<>();
        volatile FeePolicyBook feePolicies = FeePolicyBook.EMPTY;
        RuntimeFactIndexes factIndexes;
        RuntimeIdentityRegistry indexIdentities;
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
                    var route = factIndexes.activeOrders().activeOrderRoute(id);
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
    }

    static final class PartitionCoordinator {
        static final int CHANGE_KEY_COMPACTION_THRESHOLD = 512;
        static final int COMMITTED_SEQUENCE_STRIDE = 16;
        static final VarHandle COMMITTED_SEQUENCES = MethodHandles.arrayElementVarHandle(long[].class);
        static final int EVENT_POOL_INITIAL_CAPACITY = 1024;
        static final int EVENT_POOL_PREALLOCATE_COUNT = 512;

        final CoreRuntimeConfiguration configuration;
        final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] laneUserScratch;
        final PaddedReadyMask accountAdmissionReadyShards;
        final LaneSequenceQueue[] placeAdmissionReadyQueues;
        final int[] accountLaneQueueHighWaterMarks;
        final long[] publishedLaneCommittedSlots;
        final long[] dispatchedLaneCommitSequences;
        final java.util.ArrayDeque<AccountPartitionCommit> laneCommitEventPool =
                new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
        final java.util.ArrayDeque<OrderCommand.PlaceAdmissionEvent> placeAdmissionEventPool =
                new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
        final java.util.ArrayDeque<OrderCommand.ReplacementAdmissionEvent> replacementAdmissionEventPool =
                new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
        final java.util.ArrayDeque<OrderBatchProcessor.PlaceBatchAdmissionEvent> placeBatchAdmissionEventPool =
                new java.util.ArrayDeque<>(EVENT_POOL_INITIAL_CAPACITY);
        final java.util.ArrayDeque<TradeSettlement> matcherSettlementEventPool =
                new java.util.ArrayDeque<>();
        final AccountPartitionCommit.Delta[] laneDeltas;
        boolean orderBatchMutationScope;
        AccountPartition ownerLaneScope;
        TradeSettlement.Changes ownerMatcherSettlementChangesScope;
        final TradeSettlement.Changes[] matcherSettlementChangesRing;
        final int[] freeMatcherSettlementChangeSlots;
        int freeMatcherSettlementChangeCount;
        volatile long retiredOwnerSequence;
        final AccountCommandService.Dispatcher controlLanes;
        final PaddedReadyMask directSettlementReadyLanes;
        final PaddedReadyMask placeAdmissionReadyLanes;
        final RuntimeFundsAccumulator controlValidationFunds = new RuntimeFundsAccumulator();
        final RuntimeFactIndexes.PositionUndo controlPositionUndo = new RuntimeFactIndexes.PositionUndo();
        boolean controlFundsValidated;

        PartitionCoordinator(TradingAccountRuntime runtime, LaneTopology topology, CoreRuntimeConfiguration configuration) {
            this.configuration = configuration;
            this.accountAdmissionReadyShards = new PaddedReadyMask(topology.matchingEngineCount());
            this.directSettlementReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
            this.placeAdmissionReadyLanes = new PaddedReadyMask(topology.accountLaneCount());
            int deltaRingCapacity = Math.max(64, configuration.maxPendingMatching());
            this.matcherSettlementChangesRing = new TradeSettlement.Changes[deltaRingCapacity];
            this.freeMatcherSettlementChangeSlots = new int[deltaRingCapacity];
            this.freeMatcherSettlementChangeCount = deltaRingCapacity;
            for (int index = 0; index < deltaRingCapacity; index++) {
                matcherSettlementChangesRing[index] = new TradeSettlement.Changes(topology.accountLaneCount(), index);
                freeMatcherSettlementChangeSlots[index] = index;
            }
            this.laneDeltas = new AccountPartitionCommit.Delta[topology.accountLaneCount()];
            this.laneUserScratch = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[topology.accountLaneCount()];
            this.placeAdmissionReadyQueues = new LaneSequenceQueue[topology.accountLaneCount()];
            this.accountLaneQueueHighWaterMarks = new int[topology.accountLaneCount()];
            this.publishedLaneCommittedSlots = new long[Math.multiplyExact(topology.accountLaneCount(), COMMITTED_SEQUENCE_STRIDE)];
            this.dispatchedLaneCommitSequences = new long[topology.accountLaneCount()];
            this.controlLanes = new AccountCommandService.Dispatcher(runtime);
        }
    }

    public boolean accountLanesStarted() { return laneLifecycle.started(); }
    public long marketRevision() { return changes.marketRevision; }
    public StateChangeBuffer<RiskScanner.LiquidationOrder> changedLiquidations() { return changes.changedLiquidations; }
    public StateChangeBuffer<CoreAlgoOrderState> changedAlgoOrders() { return changes.changedAlgoOrders; }
    public StateChangeBuffer<CoreTriggerOrderState> changedTriggerOrders() { return changes.changedTriggerOrders; }
    public boolean orderBatchMutationScope() { return coordinator.orderBatchMutationScope; }
    public TradingRealtimeBoundary.StateCapture realtimeCapture() { return changes.realtimeCapture; }

    public void bindFactIndexes(RuntimeFactIndexes indexes, RuntimeIdentityRegistry identities) {
        assertOwner();
        changes.factIndexes = java.util.Objects.requireNonNull(indexes);
        changes.indexIdentities = java.util.Objects.requireNonNull(identities);
    }

    RuntimeFactIndexes factIndexes() {
        assertOwner();
        if (changes.factIndexes == null) throw new IllegalStateException("position indexes are not bound");
        return changes.factIndexes;
    }

    public com.surprising.aeron.service.core.index.LiquidationIndex.LiquidationRoute liquidationRoute(long id) {
        assertOwner(); return changes.factIndexes == null ? null : changes.factIndexes.liquidations().route(id);
    }

    public TradingAccountRuntime() {
        this(CoreConfiguration.standaloneLaneTopology(), CoreRuntimeConfiguration.standalone());
    }

    public TradingAccountRuntime(LaneTopology topology) {
        this(topology, CoreRuntimeConfiguration.standalone());
    }

    public TradingAccountRuntime(LaneTopology topology, CoreRuntimeConfiguration config) {
        java.util.Objects.requireNonNull(config);
        if (topology == null) throw new IllegalArgumentException("lane topology is required");
        this.topology = topology;
        this.accountLanes = new AccountPartition[topology.accountLaneCount()];
        this.accountRollback = new AccountTransactionRollback(this, topology.accountLaneCount());
        this.laneLifecycle = new AccountLaneLifecycle(accountLanes, topology, this::assertOwner);
        this.coordinator = new PartitionCoordinator(this, topology, config);
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            accountLanes[laneId] = new AccountPartition(laneId, topology.accountLaneQueueCapacity(), config.laneInitialEntities());
            coordinator.placeAdmissionReadyQueues[laneId] = new LaneSequenceQueue(topology.accountLaneQueueCapacity());
            publishLaneCommittedSequence(accountLanes[laneId]);
            coordinator.laneUserScratch[laneId] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(4);
            coordinator.laneDeltas[laneId] = new AccountPartitionCommit.Delta();
        }
        for (int i = 0; i < PartitionCoordinator.EVENT_POOL_PREALLOCATE_COUNT; i++) {
            coordinator.laneCommitEventPool.addFirst(new AccountPartitionCommit(accountLanes.length));
            coordinator.placeAdmissionEventPool.addFirst(new OrderCommand.PlaceAdmissionEvent());
            coordinator.replacementAdmissionEventPool.addFirst(new OrderCommand.ReplacementAdmissionEvent());
            coordinator.placeBatchAdmissionEventPool.addFirst(new OrderBatchProcessor.PlaceBatchAdmissionEvent());
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
    private void requireLaneAccess(AccountPartition lane) {
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
                Math.max(coordinator.accountLaneQueueHighWaterMarks[laneId], depth));
        coordinator.accountLaneQueueHighWaterMarks[laneId] = highWater;
        return highWater;
    }

    /** 在 Owner 上单调发布已退休命令的序号边界，供 Lane 回收订单身份元数据。 */
    public void releaseRetiredOrderFence(long sequence) {
        assertOwner();
        if (sequence < coordinator.retiredOwnerSequence) throw new IllegalStateException("order retirement fence moved backwards");
        coordinator.retiredOwnerSequence = sequence;
    }

    /** 使用禁用 CPU 亲和的配置创建账户工作资源。 */
    public void startAccountLanes() {
        startAccountLanes(TradingCpuAffinity.disabled());
    }

    /** 为每条账户 Lane 创建工作对象并接入统一故障通知，全部创建后标记已启动并交出 Owner 访问权，再记录是否存在普通线程实现。 */
    public void startAccountLanes(TradingCpuAffinity affinity) {
        laneLifecycle.start(affinity);
    }

    public boolean ownerLaneAccess() { return laneLifecycle.ownerHasAccess(); }
    /** Owner 持有账户访问权且没有批量修改范围时归还 Lane，避免过早释放仍被批次使用的访问权。 */
    public void releaseCompletedSequentialLaneStage() {
        if (ownerLaneAccess() && !coordinator.orderBatchMutationScope) releaseOwnerLaneAccess();
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
        if (changes.factIndexes != null) changes.factIndexes.bindBusinessOwner(Thread.currentThread());
    }

    /** 从账本拥有的事实索引读取准入所需持仓量。 */
    public long ledgerAdmissionOpenInterest(String instrumentId) {
        if (changes.factIndexes == null) throw new IllegalStateException("position indexes are not bound");
        return changes.factIndexes.openInterest().openInterestSteps(instrumentId);
    }

    /** 索引已绑定时，把指定结算 Lane 的持仓变化交给账本业务索引应用。 */
    public void applyLedgerSettlementPositions(TradeSettlement.Changes changes, long mask, RuntimeIdentityRegistry identities) {
        if (this.changes.factIndexes != null) this.changes.factIndexes.applySettlementPositions(changes, mask, identities);
    }

    /** 索引已绑定时，把当前控制命令持仓变化应用到账本索引，并记录可回滚内容。 */
    public void applyLedgerControlPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (changes.factIndexes != null) changes.factIndexes.applyControlPositions(changes.changedPositions, changes.indexIdentities, undo);
    }

    /** 索引已绑定时，按既有撤销记录恢复账本持仓索引。 */
    public void rollbackLedgerPositions(RuntimeFactIndexes.PositionUndo undo) {
        if (changes.factIndexes != null) undo.rollback(changes.factIndexes);
    }

    /** 在 Owner 上派发财务控制命令：内联应用并保证同一控制命令独占财务范围。 */
    public void dispatchLedgerCommand(SystemLedgerCommand command) {
        assertOwner();
        if (command == null) throw new IllegalArgumentException("Ledger command is required");
        if (changes.ledgerControl != null && changes.ledgerControl != command)
            throw new IllegalStateException("another control command owns the Ledger financial scope");
        changes.ledgerControl = command;
        command.apply(treasury);
    }

    private void rollbackLedgerControl() {
        if (changes.ledgerControl == null) {
            if (coordinator.controlFundsValidated) {
                rollbackLedgerPositions(coordinator.controlPositionUndo);
                coordinator.controlFundsValidated = false;
            }
            return;
        }
        changes.ledgerControl.rollbackScope();
    }

    /** 按目标掩码派发普通控制结算任务，交由唯一控制派发器管理回执。 */
    public void dispatchControlLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        coordinator.controlLanes.dispatch(laneMask, DirectCommandDispatcher.OperationType.SETTLEMENT, operation);
    }

    /** 按目标掩码派发风险任务，仍使用同一有界控制派发器。 */
    public void dispatchRiskLanes(long laneMask, java.util.function.IntFunction<Object> operation) {
        coordinator.controlLanes.dispatch(laneMask, DirectCommandDispatcher.OperationType.RISK, operation);
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
    public boolean pollControlLanes() { return coordinator.controlLanes.poll(); }

    /** 读取在途控制交接的诊断信息，供推进故障定位使用。 */
    public String controlLaneDiagnostics() { return coordinator.controlLanes.diagnostics(); }

    /** 取得指定 Lane 的控制结果，完成与读取条件由控制派发器检查。 */
    public Object controlLaneResult(int laneId) { return coordinator.controlLanes.result(laneId); }

    /** 必要时先取得全部账户访问权，再只对目标用户 Lane 建立已提交序号的读栅栏。 */
    public void readFence(long userId, long committedCoreSequence) {
        assertOwner();
        boolean acquired = accountLanesStarted() && !ownerLaneAccess();
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

    /** 必要时取得全部账户访问权，先逐 Lane 检查读栅栏，再逐 Lane 推进读水位，避免检查尚未通过时提前推进其他 Lane。 */
    public void readFenceAll(long committedCoreSequence) {
        assertOwner();
        boolean acquired = accountLanesStarted() && !ownerLaneAccess();
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

    /** 按用户所属 Lane 执行给定操作，沿用统一的 Lane 范围检查。 */
    public <T> T onLane(long userId, LaneOperation<T> operation) {
        return onLane(topology.accountLaneId(userId), operation);
    }

    /** 已有命令范围时只允许访问同一 Lane。否则拒绝没有账户访问权的活动 Owner，再进入受检查的 Lane 命令范围执行。 */
    public <T> T onLane(int laneId, LaneOperation<T> operation) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("account lane command crossed its owner boundary");
            }
            return operation.apply(scoped);
        }
        if (Thread.currentThread() == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        return inLaneCommandScope(accountLanes[laneId], operation);
    }

    /** 检查回调不为空后直接借出指定 Lane 执行读取。本方法不自动建立线程权限或读取栅栏。 */
    public <T> T onLaneReadOnly(int laneId, LaneOperation<T> operation) {
        if (operation == null) {
            throw new IllegalStateException("invalid account lane read operation");
        }
        return operation.apply(accountLanes[laneId]);
    }

    /** 进入给定 Lane 的命令范围执行回调，无论正常返回或抛错都退出该范围。 */
    public <T> T inLaneCommandScope(AccountPartition lane, LaneOperation<T> operation) {
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

    /** 查当前线程正在执行的 Lane 范围：Owner 使用本地引用，专用 LaneThread 直接取其 Lane，存在普通工作线程时才逐项匹配。 */
    public AccountPartition currentLaneCommandScope() {
        Thread current = Thread.currentThread();
        if (current == owner) {
            return coordinator.ownerLaneScope;
        }
        if (current instanceof LaneThread laneThread) {
            AccountPartition lane = laneThread.lane();
            return lane.commandScopeActive() ? lane : null;
        }
        return null;
    }

    /** 在查询范围内读权威账户表，冷阶段允许当前拥有者执行，活动阶段要求实际所属工作线程。 */
    public <T> T readAccountLane(AccountPartition lane, java.util.function.Function<AccountPartition, T> query) {
        if (!accountLanesStarted()) {
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
        if (lane == null || query == null || lane.laneId() >= accountLanes.length
                || accountLanes[lane.laneId()] != lane || laneLifecycle.worker(lane.laneId()) == null) {
            throw new IllegalArgumentException("invalid account lane read scope");
        }
        Thread current = Thread.currentThread();
        if (current instanceof LaneThread laneThread) {
            if (laneThread.lane() != lane) {
                throw new IllegalStateException("account query must run on its owning Lane thread");
            }
        } else if (current != laneLifecycle.worker(lane.laneId()).thread()) {
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

    /** 由 Owner 先检查全部目标 Lane 的容量，全部可接纳才派发同一查询，避免邮箱满造成部分查询。冷阶段逐 Lane 直接执行，活动阶段先归还 Owner 访问权。 */
    public boolean dispatchAccountQuery(long laneMask, AccountLaneCommand query) {
        assertOwner();
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("account query producer must be Owner");
        long validMask = accountLanes.length == Long.SIZE ? -1L : (1L << accountLanes.length) - 1;
        if (query == null || laneMask == 0 || (laneMask & ~validMask) != 0)
            throw new IllegalArgumentException("invalid account query dispatch");
        if (!accountLanesStarted()) {
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
            if (!laneLifecycle.worker(laneId).hasCapacity()) return false;
        }
        releaseOwnerLaneAccess();
        lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            laneLifecycle.worker(laneId).submit(query);
        }
        return true;
    }

    /** 取得当前线程已接入的撮合变化容器，Owner 使用本地范围，其余线程从当前 Lane 范围读取。 */
    public TradeSettlement.Changes currentMatcherSettlementChangesScope() {
        if (Thread.currentThread() == owner) return coordinator.ownerMatcherSettlementChangesScope;
        AccountPartition lane = currentLaneCommandScope();
        return lane != null ? lane.matcherSettlementChanges() : null;
    }

    /** 校验 Lane 引用、当前线程和既有范围，禁止交叉或嵌套账户访问。活动 Owner 还须持有交接后的访问权，再登记当前范围。 */
    public void enterLaneCommandScope(AccountPartition lane) {
        if (lane == null || lane.laneId() >= accountLanes.length || accountLanes[lane.laneId()] != lane) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        Thread current = Thread.currentThread();
        if (owner == null) bindOwner();
        if (current == owner ? coordinator.ownerLaneScope != null : lane.commandScopeActive())
            throw new IllegalStateException("invalid account lane command scope");
        SettlementLaneWorker worker = laneLifecycle.worker(lane.laneId());
        if (current != owner && (worker == null || worker.thread() != current)) {
            throw new IllegalStateException("account command must run on its owning Lane thread");
        }
        if (current == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        if (current != owner || !accountLanesStarted()) {
            lane.assertOwner();
        }
        if (current == owner) {
            coordinator.ownerLaneScope = lane;
            if (!accountLanesStarted()) lane.commandScopeActive(true);
        } else {
            lane.commandScopeActive(true);
        }
    }

    /** 要求撮合变化容器覆盖该 Lane 且没有其他撮合范围，先进入账户命令范围，再登记本次结算变化容器。 */
    public void enterMatcherSettlementScope(AccountPartition lane, TradeSettlement.Changes changes) {
        if (changes == null || lane == null || lane.matcherSettlementChanges() != null
                || Thread.currentThread() == owner && coordinator.ownerMatcherSettlementChangesScope != null
                || (changes.activeLaneMask() & (1L << lane.laneId())) == 0) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        if (Thread.currentThread() == owner) coordinator.ownerMatcherSettlementChangesScope = changes;
        lane.matcherSettlementChanges(changes);
    }

    /** 按默认规则退出账户命令范围，需要时保留持仓退休证明。 */
    public void exitLaneCommandScope(AccountPartition lane) {
        exitLaneCommandScope(lane, true);
    }

    /** 确认当前命令范围匹配，按线程及冷阶段分支选择是否准备持仓退休证明，然后清范围。Owner 退出时收集该 Lane 已发布变化。 */
    private void exitLaneCommandScope(AccountPartition lane, boolean captureRetirementProof) {
        Thread current = Thread.currentThread();
        if (lane == null || (current == owner
                ? coordinator.ownerLaneScope != lane : !lane.commandScopeActive())) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        if (current != owner || !accountLanesStarted()) {
            lane.assertOwner();
            if (captureRetirementProof) coordinator.laneDeltas[lane.laneId()].preparePositionRetirement(lane);
        }
        if (current == owner) {
            coordinator.ownerLaneScope = null;
            if (!accountLanesStarted()) lane.commandScopeActive(false);
            flushPublishedChanges(lane.laneId());
        } else {
            lane.commandScopeActive(false);
        }
    }

    /** 确认正在退出同一撮合变化容器，清 Owner 与 Lane 的撮合引用，再退出账户范围。 */
    public void exitMatcherSettlementScope(AccountPartition lane, TradeSettlement.Changes changes) {
        if (changes == null || lane == null || (Thread.currentThread() == owner
                ? coordinator.ownerMatcherSettlementChangesScope != changes : lane.matcherSettlementChanges() != changes)) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        if (Thread.currentThread() == owner) coordinator.ownerMatcherSettlementChangesScope = null;
        lane.matcherSettlementChanges(null);
        exitLaneCommandScope(lane, false);
    }

    /** 在修改任务状态前检查全部目标账户邮箱。 */
    public void ensureLaneWorkerCapacity(long laneMask) {
        assertAccountLanesHealthy();
        if (!accountLanesStarted()) return;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!laneLifecycle.worker(laneId).hasCapacity()) {
                throw new java.util.concurrent.RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    /** 已经确定账户 Lane 后执行一次业务的回调。线程与访问范围由调用入口建立。 */
    public @FunctionalInterface
    interface LaneOperation<T> {
        /** 在调用方已经建立的账户 Lane 范围内执行业务，返回本次操作结果。 */
        T apply(AccountPartition lane);
    }

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
        org.eclipse.collections.api.iterator.LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneCommittedSequence(lane);
    }

    /** 把该 Lane 的已提交序号写入独立 volatile 槽，供 Owner 观察完成前缀。 */
    public void publishLaneCommittedSequence(AccountPartition lane) {
        int laneId = lane.laneId();
        long committed = lane.committedSequence();
        coordinator.COMMITTED_SEQUENCES.setVolatile(coordinator.publishedLaneCommittedSlots, laneId * coordinator.COMMITTED_SEQUENCE_STRIDE, committed);
    }

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
        if (scoped == null) changes.changedLiquidations.put(liquidationId, value);
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
            changes.changedTriggerOrders.put(id, value);
        } else {
            laneDelta(scoped.laneId()).putTrigger(id, value);
        }
    }

    /** 优先取得当前撮合范围的指定 Lane 变化容器，没有撮合范围则使用普通控制变化容器。 */
    public AccountPartitionCommit.Delta laneDelta(int laneId) {
        TradeSettlement.Changes changes = currentMatcherSettlementChangesScope();
        return changes == null ? coordinator.laneDeltas[laneId] : changes.laneDelta(laneId);
    }

    /** 按明确的 Lane 引用选择它的撮合变化容器或普通控制变化容器。 */
    public AccountPartitionCommit.Delta laneDelta(AccountPartition lane) {
        TradeSettlement.Changes changes = lane.matcherSettlementChanges();
        return changes == null ? coordinator.laneDeltas[lane.laneId()] : changes.laneDelta(lane.laneId());
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
    public void flushPublishedChanges(int laneId) {
        flushPublishedChanges(laneId, null, null);
    }

    /** 收集普通控制变化、余额变化标记及触发单变化，再排入 Owner 变化缓冲和可选身份集合。 */
    public void flushPublishedChanges(int laneId,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        AccountPartitionCommit.Delta changes = coordinator.laneDeltas[laneId];
        changes.recordControlChanges(this, laneId);
        if (accountRollback.balancePatch(laneId).size() != 0) markBalancesChanged();
        changes.publishTriggersToOwner(this);
        changes.drainTo(this, changedUsers, changedOrders);
    }

    /** 在 Owner 上从固定环借出空闲变化槽，标记占用并准备目标 Lane 容器。 */
    public TradeSettlement.Changes acquireMatcherSettlementChanges(long laneMask) {
        assertOwner();
        if (coordinator.freeMatcherSettlementChangeCount == 0) {
            throw new IllegalStateException("AccountPartitionCommit.Delta ring is full");
        }
        TradeSettlement.Changes changes = coordinator.matcherSettlementChangesRing[
                coordinator.freeMatcherSettlementChangeSlots[--coordinator.freeMatcherSettlementChangeCount]];
        if (changes.inUse()) throw new IllegalStateException("AccountPartitionCommit.Delta ring slot is already active");
        changes.inUse(true);
        changes.activeLaneMask(laneMask);
        changes.ensureActiveLanes(laneMask);
        return changes;
    }

    /** 要求变化槽仍被占用，归还退休订单引用、清空容器并把槽号放回空闲栈。 */
    public void releaseMatcherSettlementChanges(TradeSettlement.Changes changes) {
        if (changes == null || !changes.inUse()) throw new IllegalStateException("AccountPartitionCommit.Delta slot is not active");
        changes.returnRetiredOrders(this);
        changes.clear();
        changes.inUse(false);
        coordinator.freeMatcherSettlementChangeSlots[coordinator.freeMatcherSettlementChangeCount++] = changes.ringIndex();
    }

    /** 先归还 Owner 账户访问权并清账户启动标记，再逐一关闭账户工作对象与账本，汇总已捕获的关闭异常。账本线程确认退出后解除索引业务所有者，最后保留 Error 或包装其他故障抛出。 */
    @Override
    public void close() {
        laneLifecycle.close();
    }

    public boolean workersStopped() { return laneLifecycle.workersStopped(); }

    /** 从当前命令的冻结后值捕获候选归属，活动索引只补未产生后值的仍活动订单。返回 Owner 复用草稿，调用方必须立即交给 dispatch，不能跨命令保存。 */
    public org.eclipse.collections.api.map.primitive.LongLongMap metadataOrderUsers(Iterable<Long> orderIds) {
        assertOwner();
        changes.metadataOrderUsers.clear();
        if (orderIds instanceof PrimitiveLongView ids) {
            for (int i = 0; i < ids.primitiveSize(); i++) changes.metadataOrderUsers.put(ids.primitiveValueAt(i), 0);
        } else {
            for (Long id : orderIds) if (id != null) changes.metadataOrderUsers.put(id, 0);
        }
        if (changes.metadataOrderUsers.isEmpty()) return changes.metadataOrderUsers;
        changedOrders.forEach(changes.captureMetadataUser);
        if (changes.factIndexes != null) changes.metadataOrderUsers.forEachKeyValue(changes.captureActiveMetadataUser);
        return changes.metadataOrderUsers;
    }

    /** 校验提交序号和原日志元数据，按变化用户派发账户提交任务，同时携带订单盖章和可选触发取消信息。 */
    public AccountPartitionCommit dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            org.eclipse.collections.api.map.primitive.LongLongMap orderUsers, Iterable<Long> triggerIds, long timestamp, long position) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid account metadata commit");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderUsers, triggerIds, timestamp, position,
                null, null, -1);
    }

    /** 最终批次响应与账户提交使用同一任务。 */
    public AccountPartitionCommit dispatchBatchCommit(long coreSequence, Iterable<Long> userIds,
            org.eclipse.collections.api.map.primitive.LongLongMap orderUsers, long timestamp, long position, long responseUserId,
            OrderCommand.ResultTarget target, RuntimeIdentityRegistry identities) {
        assertOwner();
        if (coreSequence <= 0 || userIds == null || timestamp < 0 || position < 0
                || responseUserId <= 0 || target == null || identities == null)
            throw new IllegalArgumentException("invalid final batch account task");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderUsers, null, timestamp, position,
                target, identities, topology.accountLaneId(responseUserId));
    }

    private void addLaneUsers(Iterable<Long> userIds) {
        if (userIds instanceof ImmutableLongArrayList primitive) {
            primitive.forEachLong(userId -> { if (userId > 0) addLaneUser(userId); });
            return;
        }
        if (userIds instanceof PrimitiveLongView view) {
            for (int i = 0; i < view.primitiveSize(); i++) {
                long userId = view.primitiveValueAt(i);
                if (userId > 0) addLaneUser(userId);
            }
            return;
        }
        for (Long userId : userIds) if (userId != null && userId > 0) addLaneUser(userId);
    }

    /** 清空每条 Lane 的临时用户列表，准备下一次派发。 */
    void clearLaneUserScratch() {
        for (org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users : coordinator.laneUserScratch) {
            users.clear();
        }
    }

    /** 按拓扑把用户身份追加到对应临时列表，不在这里去重。 */
    void addLaneUser(long userId) {
        int laneId = topology.accountLaneId(userId);
        coordinator.laneUserScratch[laneId].add(userId);
    }

    /** 按临时用户列表校验每条写入 Lane 的提交顺序，必要时加入只读响应 Lane，检查容量并借用事件。 */
    private AccountPartitionCommit dispatchLaneMutationFromScratch(long coreSequence,
            org.eclipse.collections.api.map.primitive.LongLongMap orderUsers, Iterable<Long> triggerIds, long timestamp, long position,
            OrderCommand.ResultTarget target, RuntimeIdentityRegistry identities, int responseLaneId) {
        long laneMask = 0;
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            org.eclipse.collections.impl.list.mutable.primitive.LongArrayList users = coordinator.laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    (long) coordinator.COMMITTED_SEQUENCES.getVolatile(coordinator.publishedLaneCommittedSlots,
                            laneId * coordinator.COMMITTED_SEQUENCE_STRIDE), coordinator.dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        long commitMask = laneMask;
        if (target != null) laneMask |= 1L << responseLaneId;
        if (laneMask == 0) return null;
        if (accountLanesStarted()) releaseOwnerLaneAccess();
        ensureLaneWorkerCapacity(laneMask);
        AccountPartitionCommit event = coordinator.laneCommitEventPool.pollFirst();
        if (event == null) event = new AccountPartitionCommit(accountLanes.length);
        event.prepare(coreSequence, laneMask, coordinator.laneUserScratch, this);
        try {
            if (target != null) event.prepareResponse(target, identities, responseLaneId, commitMask);
            event.prepareMetadata(orderUsers, timestamp, position);
            event.prepareTriggerCancellations(triggerIds);
        }
        catch (RuntimeException failure) {
            event.discard();
            coordinator.laneCommitEventPool.addFirst(event);
            throw failure;
        }
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                laneLifecycle.worker(laneId).submit(event);
            }
            if ((commitMask & (1L << laneId)) != 0)
                coordinator.dispatchedLaneCommitSequences[laneId] = coreSequence;
        }
        return event;
    }

    /** 在 Owner 上检查健康并观察给定提交事件是否全部完成。 */
    public boolean laneCommitComplete(AccountPartitionCommit event) {
        assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        assertAccountLanesHealthy();
        return event.complete();
    }

    /** 在调用方完成等待后发布并清空提交事件，再放回复用池。 */
    public void releaseLaneCommit(AccountPartitionCommit event) {
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.publishAndClear();
        coordinator.laneCommitEventPool.addFirst(event);
    }

    /** 从下单准入池借出事件。 */
    public OrderCommand.PlaceAdmissionEvent acquirePlaceAdmission() {
        OrderCommand.PlaceAdmissionEvent event = coordinator.placeAdmissionEventPool.pollFirst();
        return event == null ? new OrderCommand.PlaceAdmissionEvent() : event;
    }

    /** 未派发的准入准备失败时丢弃事件内容并归还池，空事件无需处理。 */
    public void discardPlaceAdmission(OrderCommand.PlaceAdmissionEvent event) {
        if (event == null) return;
        event.discard();
        coordinator.placeAdmissionEventPool.addFirst(event);
    }

    /** 准备单笔下单事件，先检查账户准入容量。失败时账本仍持有输入则抛出明确故障而不回收该事件，避免复用仍被引用的槽。 */
    public OrderCommand.PlaceAdmissionEvent dispatchPlaceAdmission(
            long coreSequence, long userId, OrderCommand.PlaceAdmissionEvent event, java.util.UUID commandId,
            TreasuryRuntime.SettlementStatus settlementStatus,
            int assetId, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        if (event == null) throw new IllegalArgumentException("place admission event is required");
        event.prepare(
                coreSequence, userId, commandId, settlementStatus,
                assetId, laneId, this, identities, timestamp, position);
        if (event.instrument().usesOpenInterestForUserLimit() && !event.reduceOnly()) {
            event.bindOpenInterest(ledgerAdmissionOpenInterest(event.instrumentId()));
        }
        try {
            if (accountLanesStarted() && !laneLifecycle.worker(laneId).hasAdmissionCapacity())
                throw new java.util.concurrent.RejectedExecutionException("account admission mailbox is full");
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submitAdmission(event);
            }
        } catch (RuntimeException | Error failure) {
            if (!event.complete()) event.discard();
            coordinator.placeAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /** 调用方完成准入收集后清空事件并归还池。 */
    public void releasePlaceAdmission(OrderCommand.PlaceAdmissionEvent event) {
        if (event == null) return;
        event.clear();
        coordinator.placeAdmissionEventPool.addFirst(event);
    }

    /** 准备改单准入，必要时先派发账本输入读取，再交给所属账户的普通工作邮箱。失败时仍由账本持有的输入不能回收，否则丢弃事件并归还池。 */
    public OrderCommand.ReplacementAdmissionEvent dispatchReplacementAdmission(long sequence, long userId,
            long originalOrderId, com.surprising.aeron.protocol.AmendOrderCommand amend,
            com.surprising.aeron.protocol.ReplaceOrderCommand replace,
            com.surprising.aeron.service.order.OrderAdmissionService.Context decision,
            int matcherShard, RuntimeIdentityRegistry identities) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        ensureLaneWorkerCapacity(1L << laneId);
        OrderCommand.ReplacementAdmissionEvent event = coordinator.replacementAdmissionEventPool.pollFirst();
        if (event == null) event = new OrderCommand.ReplacementAdmissionEvent();
        event.prepare(sequence, userId, originalOrderId, amend, replace, decision,
                matcherShard, this, identities);
        if (decision.instrument().usesOpenInterestForUserLimit()
                && (replace == null || replace.replacement() == null || !replace.replacement().reduceOnly())) {
            event.bindOpenInterest(ledgerAdmissionOpenInterest(decision.instrument().instrumentId()));
        }
        try {
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submit(event);
            }
        } catch (RuntimeException | Error failure) {
            event.discard();
            coordinator.replacementAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /** 在 Owner 上把已完成收集的改单准入事件清空并归还池。 */
    public void releaseReplacementAdmission(OrderCommand.ReplacementAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        coordinator.replacementAdmissionEventPool.addFirst(event);
    }

    /** 为该用户借出专用变化槽及批次准入事件，准备订单输入后在冷阶段执行或投递账户准入邮箱。 */
    public OrderBatchProcessor.PlaceBatchAdmissionEvent dispatchPlaceBatchAdmission(
            long coreSequence, long userId, java.util.UUID commandId,
            OrderCommand.ResolvedPlaceOrder[] orders, long[] clientKeys,
            Order[] admittedOrders, int itemCount,
            int matcherShard, RuntimeIdentityRegistry identities, OrderBatchProcessor.PendingBatch source,
            long timestamp, long position) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        TradeSettlement.Changes changes = acquireMatcherSettlementChanges(1L << laneId);
        changes.ensureAdmissionCapacity(laneId, itemCount);
        OrderBatchProcessor.PlaceBatchAdmissionEvent event = coordinator.placeBatchAdmissionEventPool.pollFirst();
        if (event == null) event = new OrderBatchProcessor.PlaceBatchAdmissionEvent();
        event.prepare(coreSequence, userId, commandId, orders, clientKeys, admittedOrders,
                itemCount, laneId, matcherShard, this, changes, identities, source,
                timestamp, position);
        try {
            if (!accountLanesStarted()) {
                event.execute(accountLanes[laneId]);
            } else {
                releaseOwnerLaneAccess();
                laneLifecycle.worker(laneId).submitAdmission(event);
            }
        } catch (RuntimeException | Error failure) {
            releaseMatcherSettlementChanges(event.discardChanges());
            event.clear();
            coordinator.placeBatchAdmissionEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    /** 只登记已经完成且未拒绝的批次准入，把各订单加入待完成预留索引，并按批次项数递增修订。 */
    public void registerPlaceBatchAdmission(OrderBatchProcessor.PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        long userId = event.userId();
        pendingReservations.registerBatch(event.coreSequence(), userId, event.admittedOrders(), event.itemCount());
        revision = Math.addExact(revision, event.itemCount());
    }

    /** 在该批次有序提交时才接入账户回滚前值，移交准入标记及可用余额变化。账户实体仍归 Lane，不重放用户、订单和预留发布缓冲。 */
    public void collectPlaceBatchAdmission(
            OrderBatchProcessor.PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        int laneId = topology.accountLaneId(event.userId());
        // 只在本批进入有序提交时接入共享回滚前值。
        // 前面的命令不能提前发布或清空未来批次的修改前状态。
        accountRollback.captureBatchAdmissionBefore(event.userId(), event.userBefore(), event.admittedOrders(), event.itemCount(), laneId);
        event.copyBalanceBeforeTo(accountRollback.balancePatch(laneId));
        TradeSettlement.Changes changes = event.takeChanges();
        try {
            // 不重放用户、订单或预留的发布缓冲。
            changes.laneDelta(laneId).commitBatchAdmissionToOwner(this, laneId);
            AccountTransactionRollback.BalancePatches balances = changes.balancePatch(laneId);
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(this, index);
                if (changedUsers != null) changedUsers.add(balances.userId(index));
                markBalancesChanged();
            }
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    /** 在 Owner 上取走批次专用变化槽并归还。 */
    public void discardPlaceBatchAdmission(OrderBatchProcessor.PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        releaseMatcherSettlementChanges(event.takeChanges());
    }

    /** 在 Owner 上清空已完成使用的批次准入事件并归还池，调用方先处理其变化槽。 */
    public void releasePlaceBatchAdmission(OrderBatchProcessor.PlaceBatchAdmissionEvent event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        coordinator.placeBatchAdmissionEventPool.addFirst(event);
    }

    /** 在所属 Lane 按相反顺序撤销本批已准入订单的待预留、客户号、订单与冻结，最后恢复原用户修订及持仓模式。 */
    public void rollbackPlaceBatchAdmissionInLane(
            AccountPartition lane, long userId, long coreSequence, OrderCommand.ResolvedPlaceOrder[] orders,
            long[] clientKeys,
            int admittedCount, UserAccount userBefore) {
        lane.assertOwner();
        for (int index = admittedCount - 1; index >= 0; index--) {
            long orderId = orders[index].orderId();
            OrderFundsReservation reservation = lane.reservation(orderId);
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
            IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
            AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("batch admission rollback balance is missing");
            balance.release(reservation.reservedUnits());
        }
        if (userBefore != null) {
            UserAccount current = lane.user(userId);
            lane.storeUser(userId, (accountLanesStarted() ? current.laneValue() : current)
                    .withState(userBefore.revision(), userBefore.positionMode()));
        }
    }

    /** 先发布准入完成的 Core 序号，再设置 Lane 唤醒提示。新观察到置位时通知 Owner。 */
    public void publishPlaceAdmissionReady(int laneId, long coreSequence) {
        coordinator.placeAdmissionReadyQueues[laneId].publish(coreSequence);
        coordinator.placeAdmissionReadyLanes.setBit(laneId);
    }

    /** 在 Owner 上采样并清除账户准入提示，实际完成序号仍需从队列取出。 */
    public long takePlaceAdmissionReadyLaneMask() {
        assertOwner();
        return coordinator.placeAdmissionReadyLanes.getAndClear();
    }

    /** 在 Owner 上从指定 Lane 的准入完成队列取一个 Core 序号，空队列返回零。 */
    public long pollPlaceAdmissionReady(int laneId) {
        assertOwner();
        return coordinator.placeAdmissionReadyQueues[laneId].poll();
    }

    /** 校验 Matcher 分片后发布账户准入就绪提示，唤醒对应撮合提交头。 */
    public void publishAccountAdmissionReadyShard(int matcherShard) {
        if (matcherShard < 0 || matcherShard >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("invalid matcher shard");
        }
        coordinator.accountAdmissionReadyShards.setBit(matcherShard);
    }

    /** 在 Owner 上采样并清除 Matcher 分片的账户准入提示。 */
    public long takeAccountAdmissionReadyShardMask() {
        assertOwner();
        return coordinator.accountAdmissionReadyShards.getAndClear();
    }

    /** 在 Owner 上检查工作健康并只探测准入或结算就绪提示，不消费完成序号，也不代替事件完成判断。 */
    public boolean hasMatchingNotifications() {
        assertOwner();
        assertAccountLanesHealthy();
        return coordinator.placeAdmissionReadyLanes.get() != 0
                || hasPlaceAdmissionNotifications() || hasSettlementNotifications();
    }

    /** 只探测是否有账户准入对应的 Matcher 分片就绪提示，不消费业务结果。 */
    public boolean hasPlaceAdmissionNotifications() {
        return coordinator.accountAdmissionReadyShards.get() != 0;
    }

    /** 探测直接结算提示，真实完成事实仍从原事件读取。 */
    public boolean hasSettlementNotifications() {
        return coordinator.directSettlementReadyLanes.get() != 0;
    }

    /** 直接结算只发布 Lane 唤醒提示。原结算事件持有权威完成标记，不额外建立 Owner 序号队列或预期计数。 */
    public void publishDirectMatcherSettlementReady(int laneId) {
        coordinator.directSettlementReadyLanes.setBit(laneId);
    }

    /** 采样并清除直接结算提示，实际完成状态仍查原结算事件。 */
    public long takeDirectMatcherSettlementReadyLaneMask() {
        return coordinator.directSettlementReadyLanes.getAndClear();
    }

    /** 在有效执行范围内收集已经完成且未拒绝的单笔准入，禁止重复登记，建立 Owner 的待预留索引并递增修订，返回原解析结果。 */
    public OrderCommand.ResolvedPlaceOrder collectPlaceAdmission(OrderCommand.PlaceAdmissionEvent event) {
        assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        long orderId = event.orderId();
        long userId = event.userId();
        if (pendingReservations.hasPendingUser(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(pendingReservations.totalPendingReservations()));
        revision = Math.incrementExact(revision);
        return event.resolvedOrder();
    }

    /** 要求直接财务控制范围已经结束，再登记原撮合事件的财务结算。 */
    public void dispatchMatcherFinancialSettlement(TradeSettlement event) {
        assertOwner();
        if (event == null) return;
        if (changes.ledgerControl != null) throw new IllegalStateException("Matcher money cannot enter an unfinished control scope");
        if (!event.hasLedgerSettlement()) event.submitTreasurySettlement();
    }

    /** 先尝试派发财务部分，推进内联财务结算并检查原事件整体完成状态。 */
    public boolean pollMatcherFinancialSettlement(TradeSettlement event) {
        if (event == null) return true;
        dispatchMatcherFinancialSettlement(event);
        return event.complete();
    }

    /** 等待账户及财务结算完成，标记事件单次收集。 */
    public boolean collectMatcherSettlement(
            TradeSettlement event,
            TerminalOrderSink terminalOrderSink,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.support.collection.PrimitiveLongChangeSet changedOrders) {
        assertOwner();
        if (event == null || !event.complete()) return false;
        event.markCollected();
        boolean ownerPending = pendingReservations.containsSequence(event.plan().coreSequence());
        for (int index = 0; index < event.planCount(); index++) {
            if (ownerPending) pendingReservations.unindexMatcherPendingReservations(event.plan(index));
            // 全部结算成功后由 owner 合并一次触发终态变更。
            if (event.plan(index).completedTrigger() != null) revision = Math.incrementExact(revision);
        }
        long laneMask = event.requiredLaneMask();
        TradeSettlement.Changes changes = event.takeChanges();
        try {
            long remainingLanes = laneMask;
            int completed = 0;
            while (remainingLanes != 0) {
                int laneId = Long.numberOfTrailingZeros(remainingLanes);
                remainingLanes &= remainingLanes - 1;
                completed += changes.laneDelta(laneId).completedPending();
                if (changes.directPositionIdentities()) {
                    var positions = changes.laneDelta(laneId).positions();
                    for (int index = 0; index < positions.size(); index++)
                        event.identities().releasePublishedPosition(positions.keyAt(index));
                }
                changes.laneDelta(laneId).commitTerminalToOwner(this, laneId, terminalOrderSink, changedUsers,
                        changedOrders);
                AccountTransactionRollback.BalancePatches balances = changes.balancePatch(laneId);
                for (int index = 0; index < balances.size(); index++) {
                    balances.publishAvailableAt(this, index);
                    if (changedUsers != null) changedUsers.add(balances.userId(index));
                    markBalancesChanged();
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            pendingReservations.completedBatchItems(event.plan().coreSequence(), completed);
            return true;
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
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
        return changes.changedPositions.get(positionKey);
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
    public void completeMatcherPendingReservations(AccountPartition lane, TradeSettlement.Plan plan,
                                            Order admissionVersion) {
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequence(orderId) != plan.coreSequence()) continue;
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation == null || topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            TradeSettlement.Changes changes = lane.matcherSettlementChanges();
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
            // 比较全部字段，不能只看修订，因为提交时间和位置也可能变化。
            if (changes == null || !changes.laneDelta(lane.laneId()).orders().containsKey(orderId)) {
                Order admitted = lane.order(orderId);
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

    /** 按计划逐个检查订单，只有原提交时间或日志位置变化时更新元数据。 */
    public void stampMatcherOrders(AccountPartition lane, TradeSettlement.Plan plan,
                            long timestamp, long clusterPosition) {
        for (int index = 0; index < plan.orderCount(); index++) {
            Order order = lane.order(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            if (lane.matcherSettlementChanges() == null) replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
            else updateOrderStatusInLane(lane, order.orderId(), order.status(), order.revision(), timestamp, clusterPosition);
        }
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
    public java.util.List<AccountLaneSnapshot> accountLaneSnapshots(
            long fenceSequence, TradingCoreState globalState) {
        assertOwner();
        if (globalState == null || globalState.productLine() != productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        pendingReservations.assertPendingReservationCounts();
        boolean acquired = accountLanesStarted() && !ownerLaneAccess();
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

    /** 要求控制任务、账户工作队列、待预留索引和本命令变化缓冲均已收尾。 */
    public void requireSnapshotFenceReady() {
        assertOwner();
        if (coordinator.controlLanes.pending()) throw new IllegalStateException("control Lane work is unfinished");
        if (accountLanesStarted()) {
            for (int laneId = 0; laneId < accountLanes.length; laneId++) {
                SettlementLaneWorker worker = laneLifecycle.worker(laneId);
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

    /** 判断变化缓冲或回滚前值是否仍未提交，并检查冷财库变化或活动财务控制槽。 */
    public boolean hasUncommittedCommandChanges() {
        assertOwner();
        return snapshotProjectionStateDirty() || treasury.hasChangedValues() || changes.ledgerControl != null;
    }

    /** 只检查当前命令各类变化缓冲、余额标记及回滚前值是否非空，不代替全部异步任务完成判断。 */
    boolean snapshotProjectionStateDirty() {
        return !changedUsers.isEmpty() || changes.balancesChanged || !changedOrders.isEmpty()
                || !changedReservations.isEmpty() || !changes.changedPositions.isEmpty()
                || !changes.changedLiquidations.isEmpty() || !changes.changedMarkPrices.isEmpty()
                || !changedRiskSnapshots.isEmpty() || !changes.changedRiskScans.isEmpty()
                || !changes.changedLeverages.isEmpty()
                || !changes.changedAlgoOrders.isEmpty() || !changes.changedCancelAllAfterTimers.isEmpty()
                || !changes.changedTriggerOrders.isEmpty() || !changes.changedFeePolicies.isEmpty()
                || accountRollback.hasCaptured();
    }

    /** 在调用方已建立的恢复访问边界内，先核对清单及用户路由，再逐 Lane 恢复并发布提交序号，最后清恢复用待预留信息。激活前限制由外层恢复入口保证。 */
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

    /** 只校验快照清单：数量与 Lane 唯一性、应用及提交序号、用户无重复且路由正确，并与全局用户集合完全对应。 */
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
        treasury.releaseOwnerForHandoff();
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
        if (coordinator.orderBatchMutationScope) throw new IllegalStateException("order batch mutation scope is already active");
        if (snapshotProjectionStateDirty() || treasury.hasChangedValues() || changes.ledgerControl != null) {
            throw new IllegalStateException("order batch requires a clean command mutation set");
        }
        coordinator.orderBatchMutationScope = true;
        treasury.beginOrderBatchMutationScope();
    }

    /** 存在批量范围时先结束冷财库对应范围，再清本地批量标记。 */
    public void endOrderBatchMutationScope() {
        assertOwner();
        if (!coordinator.orderBatchMutationScope) return;
        treasury.endOrderBatchMutationScope();
        coordinator.orderBatchMutationScope = false;
    }

    /** 当前处于订单批量范围时拒绝指定业务域的交叉修改，范围外不额外拦截。 */
    void rejectUnsupportedOrderBatchMutation(String domain) {
        if (coordinator.orderBatchMutationScope) {
            throw new IllegalStateException("order batch cannot mutate " + domain);
        }
    }

    /** 为异步命令建立失败续步：中止私有实时批次，向各账户 Lane 派发待预留完成与前值恢复，返回可轮询回调。账户恢复收集完成后才恢复 Owner，再等待财务范围逆向收尾。 */
    public java.util.function.BooleanSupplier beginCommandRollback(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        if (revisionCheckpoint < 0 || coreSequence <= 0)
            throw new IllegalArgumentException("invalid asynchronous rollback");
        if (changes.realtimeCapture != null && changes.realtimeCapture.active()) changes.realtimeCapture.abort();
        long[] pendingOrders = pendingReservations.pendingOrderIds(coreSequence);
        OrderFundsReservation.PendingTracker.PendingBatch batch = pendingReservations.batch(coreSequence);
        int expectedPending = pendingOrders.length + (batch == null ? 0 : batch.remaining());
        long laneMask = accountLanes.length == 64 ? -1L : (1L << accountLanes.length) - 1;
        dispatchControlLanes(laneMask, id -> {
            AccountPartition lane = currentLaneCommandScope();
            int completed = 0;
            for (long orderId : pendingOrders) {
                long userId = pendingReservations.pendingUser(orderId);
                if (topology.accountLaneId(userId) != id) continue;
                lane.completePendingReservation(orderId, coreSequence);
                completed++;
            }
            if (batch != null && topology.accountLaneId(batch.userId()) == id) {
                for (int index = 0; index < batch.count(); index++) {
                    Order order = batch.order(index);
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
            /** 收齐账户恢复后核对预留完成总数、移除 Owner 索引，同步恢复 Owner、账本与修订并清变化。 */
            @Override public boolean getAsBoolean() {
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
                rollbackLedgerControl();
                changes.ledgerControl = null;
                revision = revisionCheckpoint;
                clearChangedKeys();
                return true;
            }
        };
    }

    /** 同步恢复失败命令的预留、各 Lane 和 Owner 前值。该检查发生在账户恢复之后，因此不能把一次抛错理解为此前没有状态变化。 */
    public void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        assertOwner();
        // 失败补丁可能已经暂存私有实时值，回滚时先丢弃当前实时批次。
        // 后续由权威快照恢复视图。
        if (changes.realtimeCapture != null && changes.realtimeCapture.active()) changes.realtimeCapture.abort();
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
        if (changes.ledgerControl != null)
            throw new IllegalStateException("active Ledger rollback requires its asynchronous continuation");
        treasury.rollbackChangedValues();
        revision = revisionCheckpoint;
        clearChangedKeys();
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
        if (coordinator.controlLanes.active()) {
            coordinator.controlLanes.recordRevisionDelta();
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
        return changes.riskScanControl;
    }

    /** 要求不在不支持的订单批量修改范围，且配置非空，再替换当前风险扫描配置。 */
    public void setRiskScanControl(CoreRiskScanControlView value) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan control");
        if (value == null) throw new IllegalArgumentException("risk scan control is required");
        changes.riskScanControl = value;
    }

    /** 账户已启动时，要求当前已有账户命令范围或 Owner 已取得交接访问权。 */
    private void requireAccountReadOwnership() {
        if (accountLanesStarted() && currentLaneCommandScope() == null && !ownerLaneAccess())
            throw new IllegalStateException("account read requires its owning Lane thread");
    }

    /** 在当前 Lane 范围内读取该用户，跨 Lane 返回空。 */
    public UserAccount user(long userId) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.laneId() == laneId ? scoped.user(userId) : null;
        }
        checkLaneAccess();
        AccountPartition lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return lane.user(userId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

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
    public AccountBalance balance(long userId, int assetId) {
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            IntObjectHashMap<AccountBalance> balances = scoped.userBalances(userId);
            return balances == null ? null : balances.get(assetId);
        }
        checkLaneAccess();
        AccountPartition lane = accountLanes[laneId];
        enterLaneCommandScope(lane);
        try {
            return copyBalance(lane.userBalances(userId), assetId);
        } finally {
            exitLaneCommandScope(lane);
        }
    }

    /** 活动 Owner 未持有账户访问权时拒绝直接读取，不在此自动等待账户交接。 */
    private void checkLaneAccess() {
        if (Thread.currentThread() == owner && accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
    }

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
    public long nextRiskReservationId(long userId, long cursor) {
        AccountPartition lane = riskCursorLane(userId);
        var ids = lane.reservationIds(userId);
        if (ids == null || ids.isEmpty()) return 0;
        if (ids.size() == 1) {
            long single = ids.iterator().nextValue();
            return single > cursor ? single : 0;
        }
        long selected = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextValue();
            if (id > cursor && (selected == 0 || id < selected)) selected = id;
        }
        return selected;
    }

    /** 按持仓对外身份文本的字典序取下一项，返回它的内部长整数键。 */
    public long nextRiskPositionKey(long userId, String cursor, RuntimeIdentityRegistry identities) {
        AccountPartition lane = riskCursorLane(userId);
        LongHashSet ids = lane.positionKeys(userId);
        if (ids == null || ids.isEmpty()) return 0;
        if (ids.size() == 1) {
            long single = ids.longIterator().next();
            if ("-".equals(cursor)) return single;
            return identities.positionKey(userId, single).compareTo(cursor) > 0 ? single : 0;
        }
        long selected = 0;
        String selectedIdentity = null;
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
        return selected;
    }

    /** 核对读取权限并定位当前用户 Lane。已有范围必须路由一致，最后检查实际 Lane 所有权，不自行建立新的命令范围。 */
    AccountPartition riskCursorLane(long userId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) {
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("risk cursor must run in the user's Lane");
        }
        lane.assertOwner();
        return lane;
    }

    /** 只查该用户已有索引，判断是否存在活动订单、非零持仓或仍有冻结数量的预留，供持仓模式修改前校验。 */
    public boolean hasOpenAccountExposure(long userId) {
        assertOwner();
        return onLane(userId, lane -> {
            var orders = lane.activeOrderIds(userId);
            if (orders != null && !orders.isEmpty()) return true;
            LongHashSet positions = lane.positionKeys(userId);
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    Position position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0) return true;
                }
            }
            var reservations = lane.reservationIds(userId);
            if (reservations != null) {
                var it = reservations.iterator();
                while (it.hasNext()) {
                    OrderFundsReservation reservation = lane.reservation(it.nextValue());
                    if (reservation != null && reservation.reservedUnits() != 0) return true;
                }
            }
            return false;
        });
    }

    /** 只查用户所属 Lane，判断同标的、同保证金模式是否有 OPEN 订单或非零持仓，供杠杆变更前校验。 */
    public boolean hasOpenLeverageExposure(CoreLeverageKey key, int symbolId) {
        assertOwner();
        return onLane(key.userId(), lane -> {
            var orders = lane.activeOrderIds(key.userId());
            if (orders != null) {
                var it = orders.iterator();
                while (it.hasNext()) {
                    Order order = lane.order(it.nextValue());
                    if (order != null && order.status() == CoreOrderStatus.OPEN
                            && order.symbolId() == symbolId && order.marginMode() == key.marginMode()) return true;
                }
            }
            LongHashSet positions = lane.positionKeys(key.userId());
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    Position position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0
                            && position.symbolId() == symbolId && position.marginMode() == key.marginMode()) return true;
                }
            }
            return false;
        });
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
        requireLaneAccess(lane, userId);
        var ids = lane.reservationIds(userId);
        if (ids == null) return new long[0];
        long[] result = new long[ids.size()];
        var iterator = ids.iterator();
        for (int index = 0; index < result.length; index++) result[index] = iterator.nextValue();
        return result;
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
        requireLaneAccess(lane, userId);
        Set<CoreLeverageKey> keys = lane.leverageKeys(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
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
    public Order order(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            Order order = scoped.order(orderId);
            if (order != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || coordinator.orderBatchMutationScope) {
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
        if (changes.factIndexes != null) {
            var route = changes.factIndexes.activeOrders().activeOrderRoute(orderId);
            if (route != null) {
                AccountPartition lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                Order order = lane.order(orderId);
                if (order != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedOrders.get(orderId) != null || coordinator.orderBatchMutationScope) {
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
            Order order = accountLanes[i].order(orderId);
            if (order != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedOrders.get(orderId) != null || coordinator.orderBatchMutationScope) {
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

    /** 按当前 Lane、活动路由和必要的全 Lane 检索读取预留。Owner 仅在该预留已变化或批量范围内取得在途值，没有订单读取的触发子单特例。 */
    public OrderFundsReservation reservation(long orderId) {
        assertOwner();
        requireAccountReadOwnership();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            OrderFundsReservation reservation = scoped.reservation(orderId);
            if (reservation != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == owner && scoped.pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || coordinator.orderBatchMutationScope) {
                        return reservation;
                    }
                    return null;
                }
                return reservation;
            }
            return null;
        }
        if (changes.factIndexes != null) {
            var route = changes.factIndexes.activeOrders().activeOrderRoute(orderId);
            if (route != null) {
                AccountPartition lane = accountLanes[topology.accountLaneId(route.userId())];
                lane.assertOwner();
                OrderFundsReservation reservation = lane.reservation(orderId);
                if (reservation != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (changedReservations.contains(orderId) || coordinator.orderBatchMutationScope) {
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
            OrderFundsReservation reservation = accountLanes[i].reservation(orderId);
            if (reservation != null) {
                if (accountLanes[i].pendingReservation(orderId)) {
                    if (changedReservations.contains(orderId) || coordinator.orderBatchMutationScope) {
                        return reservation;
                    }
                    continue;
                }
                return reservation;
            }
        }
        return null;
    }

    /** 在有效账户权限内读取持仓，优先使用当前范围或持仓身份路由，必要时检查各 Lane。 */
    public Position position(long positionKey) {
        assertOwner();
        requireAccountReadOwnership();
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) {
            return scoped.position(positionKey);
        }
        if (changes.indexIdentities != null) {
            var identity = changes.indexIdentities.positionIdentity(positionKey);
            if (identity != null) {
                AccountPartition lane = accountLanes[topology.accountLaneId(identity.userId())];
                lane.assertOwner();
                Position position = lane.position(positionKey);
                if (position != null) return position;
            }
        }
        for (int i = 0; i < accountLanes.length; i++) {
            accountLanes[i].assertOwner();
            Position position = accountLanes[i].position(positionKey);
            if (position != null) return position;
        }
        return null;
    }

    /** 要求当前已经进入该用户 Lane 范围，借出原始持仓键列表。只能在范围退出前使用，缺失时返回共享空列表。 */
    public org.eclipse.collections.impl.list.mutable.primitive.LongArrayList positionKeysForUserAndSymbolPrimitive(
            long userId, int symbolId) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped == null || scoped.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("primitive position index access requires owning Lane scope");
        }
        LongObjectHashMap<org.eclipse.collections.impl.list.mutable.primitive.LongArrayList> byUser =
                scoped.symbolPositionKeys(symbolId);
        org.eclipse.collections.impl.list.mutable.primitive.LongArrayList keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? changes.EMPTY_POSITION_KEYS : keys;
    }

    /** 当前有账户范围时只查该 Lane，否则要求有效账户读取权限后逐 Lane 查强平状态，返回借用引用。 */
    public RiskScanner.LiquidationOrder liquidation(long liquidationId) {
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) return scoped.liquidation(liquidationId);
        assertOwner();
        requireAccountReadOwnership();
        for (AccountPartition lane : accountLanes) {
            lane.assertOwner();
            RiskScanner.LiquidationOrder value = lane.liquidation(liquidationId);
            if (value != null) return value;
        }
        return null;
    }

    /** 在用户所属 Lane 按标的及持仓方向索引定位活动强平，再取得其运行时引用。 */
    public RiskScanner.LiquidationOrder activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        assertOwner();
        return onLane(userId, lane -> {
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(userId);
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidation(liquidationId);
        });
    }

    /** 供 Core 配置边界检查尚未结束的强平：禁止在账户命令范围内调用，优先查询已绑定事实索引。 */
    public boolean hasUnresolvedLiquidation(String instrumentId, int symbolId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("instrument check belongs to Core owner");
        if (changes.factIndexes != null) return changes.factIndexes.liquidations().hasUnresolved(
                symbolId < 0 ? null : instrumentId);
        requireAccountReadOwnership();
        for (AccountPartition lane : accountLanes) {
            lane.assertOwner();
            if (lane.anyLiquidation(value ->
                    (symbolId < 0 || value.symbolId() == symbolId)
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED)) return true;
        }
        return false;
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
        return changes.riskScans.get(symbolId);
    }

    /** 在整体 complete 标记尚未结束的扫描中，选择标的内部身份最小的一条。 */
    public RiskScanner.State firstIncompleteRiskScan() {
        assertOwner();
        RiskScanner.State selected = null;
        for (RiskScanner.State scan : changes.riskScans.values()) {
            if (!scan.complete()
                    && (selected == null || scan.symbolId() < selected.symbolId())) {
                selected = scan;
            }
        }
        return selected;
    }

    /** 只在 riskComplete 尚未结束的扫描中，按最近调度修订、再按标的身份选择最先需要推进的一条。 */
    public RiskScanner.State firstRiskIncompleteScan() {
        assertOwner();
        RiskScanner.State selected = null;
        for (RiskScanner.State scan : changes.riskScans.values()) {
            if (!scan.riskComplete() && (selected == null
                    || compareRiskProgress(scan, selected) < 0)) {
                selected = scan;
            }
        }
        return selected;
    }

    /** 先比较最近调度修订，相同再比较内部标的身份，形成确定的风险推进顺序。 */
    static int compareRiskProgress(RiskScanner.State left, RiskScanner.State right) {
        int scheduled = Long.compare(left.lastScheduledRevision(), right.lastScheduledRevision());
        return scheduled != 0 ? scheduled : Integer.compare(left.symbolId(), right.symbolId());
    }

    /** 统计整体 complete 标记尚未完成的扫描数量。 */
    public int incompleteRiskScanCount() {
        assertOwner();
        int count = 0;
        for (RiskScanner.State scan : changes.riskScans.values()) {
            if (!scan.complete()) {
                count++;
            }
        }
        return count;
    }

    /** 读取下一个强平身份数值。 */
    public long nextLiquidationId() {
        assertOwner();
        return changes.nextLiquidationId;
    }

    /** 在实际财库拥有者线程上物化不可变财库导出值，不把财库所有权交给 Owner。 */
    public CoreTreasuryState treasuryForQuery(RuntimeIdentityRegistry identities) {
        return queryLedgerAtRest(ledger -> RuntimeStateMaterializer.materializeTreasury(ledger, identities));
    }

    /** 在 Owner 的既有财库读取边界同步执行导出。 */
    public <T> T queryLedgerAtRest(java.util.function.Function<TreasuryRuntime, T> read) {
        assertOwner();
        java.util.Objects.requireNonNull(read, "Ledger read is required");
        if (changes.ledgerControl != null) throw new IllegalStateException("Treasury query requires committed control work");
        return read.apply(treasury);
    }

    /** 确认已有财务控制已完成财务阶段，再就地发布该范围内的持仓索引，供风险读取。 */
    public void publishRiskPositionInputs() {
        assertOwner();
        if (changes.ledgerControl != null && !changes.ledgerControl.allowsRiskInput())
            throw new IllegalStateException("risk input requires a completed financial phase before scope close");
        if (changes.ledgerControl != null) changes.ledgerControl.publishRiskPositionInputs();
    }

    /** 借出唯一财库引用，账户命令范围内禁止取得。账本激活后仍须遵守财库自己的线程检查，这个引用不转移所有权。 */
    public TreasuryRuntime treasury() {
        assertOwner();
        if (currentLaneCommandScope() != null) {
            throw new IllegalStateException("Account Lane cannot access SystemLedger-owned Treasury");
        }
        return treasury;
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
    public void registerInstrument(CoreInstrument instrument) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument registry");
        if (instrument == null || instruments.containsKey(instrument.instrumentIdValue())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is already registered");
        }
        instruments.put(instrument.instrumentIdValue(), instrument);
    }

    /** 要求原对象正是注册表中的对象，且新旧标的身份一致，再原地更新配置，保持已有引用身份。 */
    public void updateInstrumentConfiguration(CoreInstrument instrument, CoreInstrument updated) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("instrument configuration");
        if (instrument == null || updated == null || instruments.get(instrument.instrumentIdValue()) != instrument
                || instrument.instrumentIdValue() != updated.instrumentIdValue()) {
            throw new IllegalArgumentException("invalid canonical instrument configuration update");
        }
        instrument.updateConfiguration(updated);
    }

    /** 校验已注册对象身份和维护参数，在允许的控制边界内原地更新维护状态。 */
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
        changes.changedLeverages.add(key);
        if (changes.realtimeCapture != null) changes.realtimeCapture.leverage(key, leveragePpm);
    }

    /** 取得事实索引中的算法单路由，尚未绑定索引时返回空。 */
    public com.surprising.aeron.service.core.index.AlgoOrderIndex.AlgoRoute algoRoute(long id) {
        assertOwner(); return changes.factIndexes == null ? null : changes.factIndexes.algos().value(id);
    }

    /** 优先取已有触发单路由。 */
    public com.surprising.aeron.service.core.index.TriggerOrderIndex.TriggerRoute triggerRoute(long id) {
        assertOwner();
        if (changes.factIndexes != null) return changes.factIndexes.triggers().route(id);
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
        if (scoped == null) changes.changedAlgoOrders.put(order.algoOrderId(), order);
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
        return changes.cancelAllAfterTimers.get(key);
    }

    /** 校验业务键与定时器非空，写入定时全撤表并记录变化键。 */
    public void putCancelAllAfterTimer(CoreCancelAllAfterKey key, CoreCancelAllAfterState timer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("timer state");
        if (key == null || timer == null) throw new IllegalArgumentException("invalid runtime cancel-all-after timer");
        changes.cancelAllAfterTimers.put(key, timer);
        changes.changedCancelAllAfterTimers.add(key);
    }

    /** 核对用户后按用户及币对范围读旧计时器。 */
    public void updateCancelAllAfter(long userId, CoreCancelAllAfterCommand command) {
        if (command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel-all-after update");
        }
        assertOwner();
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        CoreCancelAllAfterState current = cancelAllAfterTimer(key);
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? CoreCancelAllAfterStatus.DISABLED : CoreCancelAllAfterStatus.ACTIVE;
                long triggerAt;
                try {
                    triggerAt = command.countdownMillis() == 0 ? 0
                            : Math.addExact(command.updatedAtEpochMillis(), command.countdownMillis());
                } catch (ArithmeticException overflow) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER", "timer deadline overflow");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), status,
                        triggerAt,
                        command.updatedAtEpochMillis(),
                        current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireCancelAllAfterRevision(current, command);
                if (!current.claimableAt(command.updatedAtEpochMillis())) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.TRIGGERING, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireCancelAllAfterRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.TRIGGERED, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireCancelAllAfterRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.ACTIVE, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        requireRevisionCapacity();
        putCancelAllAfterTimer(key, next);
        incrementCommandRevision();
    }

    private static void requireCancelAllAfterRevision(CoreCancelAllAfterState current, CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }

    /** 读取本命令已经收集的触发单变化，保留到有序发布清理结束。 */
    public CoreTriggerOrderState changedTriggerOrder(long id) { assertOwner(); return changes.changedTriggerOrders.get(id); }

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
    public Map<String, CoreInstrument> instrumentsForRuntime() {
        assertOwner();
        Map<String, CoreInstrument> exported = new TreeMap<>();
        instruments.forEachValue(instrument -> exported.put(instrument.instrumentId(), instrument));
        return exported;
    }

    /** 在调用方建立的读取边界内，收集各 Lane 杠杆快照到新有序映射。 */
    public Map<CoreLeverageKey, Long> leveragesForRuntime() {
        assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.leveragesSnapshot()));
        }
        return values;
    }

    /** 在已有读取边界内，把各 Lane 算法单引用收集到新有序映射，不深复制业务值。 */
    public Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++)
            onLaneReadOnly(laneId, lane -> { lane.forEachAlgoOrder(values::put); return null; });
        return values;
    }

    /** 借出定时全撤原映射，沿用 Owner 与恢复边界，不返回独立副本。 */
    public Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimersForRuntime() {
        assertOwner();
        return changes.cancelAllAfterTimers;
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
    public Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
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

    /** 在 Owner 路由边界读取财库保存的划转用户身份，账户命令范围禁止调用。 */
    public long pendingTransferUser(long transferId) {
        assertOwner();
        if (currentLaneCommandScope() != null) throw new IllegalStateException("transfer routing belongs to Owner");
        return treasury.pendingTransferUser(transferId);
    }

    /** 当前账户范围内读取本 Lane 划转。 */
    public TransferFundsCommand pendingTransfer(long transferId) {
        assertOwner();
        AccountPartition lane = currentLaneCommandScope();
        if (lane != null) return lane.pendingTransfer(transferId);
        if (accountLanesStarted()) throw new IllegalStateException("active transfer identity must be read on its Account Lane");
        long userId = pendingTransferUser(transferId);
        return userId == 0 ? null : accountLanes[topology.accountLaneId(userId)].pendingTransfer(transferId);
    }

    /** 要求活动 Owner 已取得账户访问权，复制各 Lane 待完成划转引用并返回只读有序映射。 */
    public Map<Long, TransferFundsCommand> pendingTransfersSnapshot() {
        assertOwner();
        if (accountLanesStarted() && !ownerLaneAccess()) {
            throw new IllegalStateException("pending transfers snapshot requires owner lane access");
        }
        TreeMap<Long, TransferFundsCommand> values = new TreeMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> { lane.forEachPendingTransfer(values::put); return null; });
        }
        return Collections.unmodifiableMap(values);
    }

    /** 向财库取得有限页的划转 ID、用户 ID 和 Lane 掩码，仅交接路由，不复制账户划转正文。 */
    public TreasuryRuntime.PendingTransferRoutes pendingTransferRoutes(int limit) {
        assertOwner();
        return treasury.pendingTransferRoutes(limit, topology::accountLaneMask);
    }

    /** 用该页最后一个划转 ID 推进财库分页边界，空页使用零。 */
    public void completePendingTransferPage(TreasuryRuntime.PendingTransferRoutes page) {
        assertOwner();
        treasury.completePendingTransferPage(page.ids().length == 0 ? 0 : page.ids()[page.ids().length - 1]);
    }

    /** 要求账户已停止，校验映射容量、键值非空及划转身份一致，再清旧路由与账户值并逐项恢复。 */
    public void restorePendingTransfers(Map<Long, TransferFundsCommand> restored) {
        assertOwner();
        if (accountLanesStarted()) throw new IllegalStateException("transfer restore requires stopped Account Lanes");
        if (restored == null || restored.size() > TreasuryRuntime.MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId()))
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        treasury.clearPendingTransfers();
        for (AccountPartition lane : accountLanes) lane.clearPendingTransferValues();
        restored.forEach((id, transfer) -> {
            treasury.restorePendingTransferRoute(id, transfer.sourceUserId());
            accountLanes[topology.accountLaneId(transfer.sourceUserId())].storePendingTransfer(id, transfer);
        });
    }

    /** 查询财库路由索引是否还有待完成划转容量。 */
    public boolean hasPendingTransferCapacity() {
        assertOwner();
        return treasury.hasPendingTransferCapacity();
    }

    /** 把划转 ID 与所属用户登记到唯一财库路由索引。 */
    public void registerPendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.registerPendingTransferRoute(id, userId);
    }

    /** 让财库按划转 ID 和用户身份完成对应路由，不在此重复删除账户正文。 */
    public void completePendingTransferRoute(long id, long userId) {
        assertOwner();
        treasury.completePendingTransferRoute(id, userId);
    }

    /** 禁止订单批量交叉修改，在所属账户范围保存新划转。 */
    public void putPendingTransfer(TransferFundsCommand transfer) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted()) throw new IllegalStateException("active transfer writes require their Account Lane");
            if (!hasPendingTransferCapacity())
                throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
            lane = accountLanes[topology.accountLaneId(transfer.sourceUserId())];
        }
        if (lane.laneId() != topology.accountLaneId(transfer.sourceUserId()) || lane.hasPendingTransfer(transfer.transferId()))
            throw new IllegalArgumentException("pending transfer already exists or reached the wrong Lane");
        lane.storePendingTransfer(transfer.transferId(), transfer);
        if (!accountLanesStarted()) registerPendingTransferRoute(transfer.transferId(), transfer.sourceUserId());
    }

    /** 核对划转所属用户与 Lane，存在时检查修订容量并删除账户值。未找到返回假，用户冲突按幂等冲突拒绝。 */
    public boolean removePendingTransfer(long transferId, long userId) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("pending-transfer state");
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) {
            if (accountLanesStarted()) throw new IllegalStateException("active transfer completion requires its Account Lane");
            long routed = pendingTransferUser(transferId);
            if (routed == 0) return false;
            if (routed != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
            lane = accountLanes[topology.accountLaneId(userId)];
        }
        if (lane.laneId() != topology.accountLaneId(userId)) throw new IllegalStateException("transfer completion crossed Account Lane");
        TransferFundsCommand current = lane.pendingTransfer(transferId);
        if (current == null) return false;
        if (current.sourceUserId() != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        requireRevisionCapacity();
        lane.removePendingTransferValue(transferId);
        if (!accountLanesStarted()) completePendingTransferRoute(transferId, userId);
        return true;
    }

    /** 取得当前不可变费用政策版本，沿用其已有读写边界。 */
    public FeePolicyBook feePolicyBook() {
        assertOwner();
        return changes.feePolicies;
    }

    /** 取得当前费用政策书提供的快照映射。 */
    public Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        assertOwner();
        return changes.feePolicies.snapshot();
    }

    /** 要求账户已停止，从恢复映射重建费用政策版本，并清空政策变化身份。 */
    public void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        assertOwner();
        if (accountLanesStarted()) throw new IllegalStateException("fee policy restore requires stopped Lanes");
        changes.feePolicies = FeePolicyBook.from(restored);
        changes.changedFeePolicies.clear();
    }

    /** 活动阶段只允许配置 Lane 0 修改，构造并合并不可变政策版本。内容未变直接返回，否则登记变化并推进命令修订。 */
    public void upsertFeePolicy(com.surprising.aeron.protocol.UpsertFeePolicyCommand command) {
        assertOwner();
        if (accountLanesStarted() && (currentLaneCommandScope() == null || currentLaneCommandScope().laneId() != 0))
            throw new IllegalStateException("active fee policy updates must run on configuration Lane 0");
        rejectUnsupportedOrderBatchMutation("fee-policy state");
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        FeePolicyBook updated = changes.feePolicies.upsert(next);
        if (updated == changes.feePolicies) return;
        requireRevisionCapacity();
        changes.feePolicies = updated;
        changes.changedFeePolicies.add(next.policyId());
        incrementCommandRevision();
    }

    /** 按给定状态替换产品元数据、风险配置、标的、杠杆、算法单、定时器和触发单。依次调用现有 Lane 入口，不替换资金、订单或持仓，也不自行建立回滚事务。 */
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
        changes.cancelAllAfterTimers.clear();
        changes.cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(this::putTriggerOrder);
    }

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
    public void putUser(UserAccount user) {
        assertOwner();
        accountRollback.captureUserBefore(user.userId());
        onLane(user.userId(), lane -> {
            UserAccount current = lane.user(user.userId());
            UserAccount value = user;
            if (accountLanesStarted()) {
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

    /** 把用户修订按默认一步推进。 */
    public void advanceUserRevision(long userId) {
        advanceUserRevision(userId, 1);
    }

    /** 要求正增量。 */
    public void advanceUserRevision(long userId, long count) {
        if (count <= 0) throw new IllegalArgumentException("revision advance must be positive");
        assertOwner();
        TradeSettlement.Changes settlementChanges = currentMatcherSettlementChangesScope();
        if (settlementChanges != null) {
            AccountPartition lane = currentLaneCommandScope();
            if (lane == null || topology.accountLaneId(userId) != lane.laneId()) {
                throw new IllegalStateException("user revision crossed its owner lane");
            }
            if (lane.user(userId) == null) {
                throw new IllegalArgumentException("runtime user is not registered: " + userId);
            }
            settlementChanges.addUserRevision(lane.laneId(), userId, count);
            return;
        }
        UserAccount current = requireUser(userId);
        setUserState(userId, Math.addExact(current.revision(), count), current.positionMode());
    }

    /** 使用明确 Lane，把用户修订按默认一步推进。 */
    public void advanceUserRevision(AccountPartition lane, long userId) {
        advanceUserRevision(lane, userId, 1);
    }

    /** 核对 Lane 后忽略非正增量。有撮合变化槽时累计用户差额，否则交给普通修订入口。 */
    public void advanceUserRevision(AccountPartition lane, long userId, long count) {
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

    /** 校验用户、修订及持仓模式，保存前值，在所属 Lane 创建或更新用户元数据，再交接独立变化收据。 */
    public void setUserState(long userId, long revision, com.surprising.aeron.protocol.CorePositionMode mode) {
        assertOwner();
        if (userId <= 0 || revision < 0 || mode == null) throw new IllegalArgumentException("invalid runtime user");
        accountRollback.captureUserBefore(userId);
        UserAccount updated = onLane(userId, lane -> {
            UserAccount current = lane.user(userId);
            UserAccount value = current == null ? new UserAccount(productLine, userId, revision, mode)
                    : (accountLanesStarted() ? current.laneValue() : current).withState(revision, mode);
            if (accountLanesStarted()) value = value.laneValue();
            lane.storeUser(userId, value);
            lane.registerUser(userId);
            return value;
        });
        publishUser(userId, updated);
        if (currentLaneCommandScope() == null) markUserChanged(userId);
    }

    /** 读取输入余额数值，保存前值后在所属 Lane 建立独立余额对象并捕获后值。没有外层账户范围时再标记 Owner 变化。 */
    public void putBalance(AccountBalance balance) {
        assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        onLane(userId, lane -> {
            accountRollback.captureUserBefore(userId);
            accountRollback.captureBalanceBefore(userId, assetId);
            IntObjectHashMap<AccountBalance> userBalances = lane.userBalances(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.storeUserBalances(userId, userBalances);
            }
            userBalances.put(assetId, new AccountBalance(userId, assetId, availableUnits, lockedUnits));
            accountRollback.captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (currentLaneCommandScope() == null) {
            markBalancesChanged();
            markUserChanged(userId);
        }
    }

    /** 明确撮合 Lane 内保存给定余额对象及前后补丁。 */
    public void putBalance(AccountPartition lane, AccountBalance balance) {
        requireLaneAccess(lane, balance.userId());
        if (lane.matcherSettlementChanges() == null) {
            putBalance(balance);
            return;
        }
        accountRollback.captureBalanceBefore(lane, balance.userId(), balance.assetId());
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(balance.userId());
        if (balances == null) {
            balances = new IntObjectHashMap<>();
            lane.storeUserBalances(balance.userId(), balances);
        }
        balances.put(balance.assetId(), balance);
        accountRollback.captureBalanceAfter(lane, balance.userId(), balance.assetId());
    }

    /** 保存用户及订单前值，在所属 Lane 写入订单，再登记订单与用户变化。 */
    public void putOrder(Order order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        changedOrder(order.orderId(), order);
        markUserChanged(order.userId());
    }

    /** 保存前值，旧预留若仍待撮合则拒绝。 */
    public void putReservation(OrderFundsReservation reservation) {
        assertOwner();
        accountRollback.captureUserBefore(reservation.userId());
        accountRollback.captureReservationBefore(reservation.orderId());
        OrderFundsReservation previous = reservation(reservation.orderId());
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
        markReservationChanged(reservation.orderId());
        markUserChanged(reservation.userId());
        if (previous != null) markUserChanged(previous.userId());
    }

    /** 保存订单前值并禁止更换用户，在所属 Lane 替换订单。没有撮合变化范围时登记 Owner 订单和用户变化。 */
    public void replaceOrder(Order order) {
        assertOwner();
        accountRollback.captureUserBefore(order.userId());
        accountRollback.captureOrderBefore(order.orderId());
        Order previous = order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        publishOrder(order.orderId(), order);
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(order.orderId(), order);
            markUserChanged(order.userId());
        }
    }

    /** 把余额对象的用户、资产及数值交给原地更新入口。 */
    public void replaceBalance(AccountBalance balance) {
        assertOwner();
        replaceBalance(balance.userId(), balance.assetId(), balance.availableUnits(), balance.lockedUnits());
    }

    /** 在有效账户范围内保存前值并原地替换余额，跨 Lane 拒绝。没有范围时临时进入用户 Lane，退出后补记 Owner 变化。 */
    public void replaceBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        assertOwner();
        AccountPartition scoped = currentLaneCommandScope();
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

    private void replaceBalanceInLane(AccountPartition lane, long userId, int assetId,
                                      long availableUnits, long lockedUnits) {
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance current = balances == null ? null : balances.get(assetId);
        if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
        current.replace(availableUnits, lockedUnits);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    /** 核对明确 Lane 与用户，撮合范围内只捕获本 Lane 补丁并原地修改，否则沿用普通更新。 */
    public void replaceBalance(AccountPartition lane, long userId, int assetId, long available, long locked) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null) {
            replaceBalance(userId, assetId, available, locked);
            return;
        }
        accountRollback.captureBalanceBefore(lane, userId, assetId);
        replaceBalanceInLane(lane, userId, assetId, available, locked);
    }

    /** 在所属 Lane 创建或原地更新持仓。 */
    public Position updatePositionInLane(AccountPartition lane, long positionKey, long userId, int symbolId, int assetId,
                                         CoreInstrument instrument, long signedQuantitySteps,
                                         long entryPriceTicks, long entryValueTicks,
                                         long realizedPnlUnits, long positionMarginUnits,
                                         CoreMarginMode marginMode, CorePositionSide positionSide) {
        lane.assertOwner();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("position update must execute in its owning Account Lane");
        }
        Position current = lane.position(positionKey);
        if (current == null) {
            Position created = new Position(userId, symbolId, assetId, marginMode, positionSide,
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
            PositionIndexOps.unindexOpenPosition(lane, positionKey, current.userId(), oldSymbol);
        if ((!wasOpen && isOpen) || oldSymbol != current.symbolId()) PositionIndexOps.indexOpenPosition(lane, positionKey, current);
        publishPosition(lane, positionKey, current);
        return current;
    }

    /** 在账户线程原地更新订单执行数量、费用、状态及提交元数据，写入成交价值高低位，再发布本次变化。 */
    public Order updateOrderInLane(AccountPartition lane, long orderId, long executed, long remaining, long feeUnits,
                                   long executedValueHigh, long executedValueLow,
                                   CoreOrderStatus status, long revision,
                                   long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order update must execute in its owning Account Lane");
        }
        Order order = lane.updateOrderInPlace(orderId, executed, remaining, feeUnits,
                status, revision, commitTimestamp, commitPosition);
        order.executionValue(executedValueHigh, executedValueLow);
        publishOrder(lane, orderId, order);
        return order;
    }

    /** 在账户线程原地更新订单状态、修订及提交元数据，再交出订单变化。 */
    public Order updateOrderStatusInLane(AccountPartition lane, long orderId, CoreOrderStatus status, long revision,
                                         long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order status update must execute in its owning Account Lane");
        }
        Order order = lane.updateOrderStatusInPlace(orderId, status, revision,
                commitTimestamp, commitPosition);
        publishOrder(lane, orderId, order);
        return order;
    }

    /** 要求预留存在且释放累计量不回退，原地更新消耗与释放，同步待预留数量差，按开关交出变化。 */
    public OrderFundsReservation updateReservationInLane(AccountPartition lane, long orderId, long nextConsumed, long nextReleased,
                                               boolean publish) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("reservation update must execute in its owning Account Lane");
        }
        OrderFundsReservation reservation = lane.reservation(orderId);
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

    /** 保存持仓前值并禁止更换用户，在所属 Lane 替换实体及索引，随后按当前范围登记持仓与用户变化。 */
    public void replacePosition(long positionKey, Position position) {
        assertOwner();
        accountRollback.captureUserBefore(position.userId());
        accountRollback.capturePositionBefore(positionKey, position.userId());
        Position previous = position(positionKey);
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

    /** 要求强平身份尚未存在，在所属 Lane 保存强平及活动索引并交出变化，随后登记用户变化。 */
    public void putLiquidation(RiskScanner.LiquidationOrder liquidation) {
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
            changes.changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
    }

    /** 在允许的非批量范围内写入标记价，并记录标的变化身份。 */
    public void putMarkPrice(MarkPrice markPrice) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("mark-price state");
        markPrices.put(markPrice.symbolId(), markPrice);
        changes.changedMarkPrices.add(markPrice.symbolId());
    }

    /** 保存用户及风险快照前值，在所属 Lane 写入并交出变化，登记相关用户。 */
    public void putRiskSnapshot(long positionKey, RiskMarginCalculator.Snapshot snapshot) {
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

    /** 在允许的非批量范围内保存标的风险扫描续步，并登记标的变化。 */
    public void putRiskScan(RiskScanner.State scan) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("risk-scan state");
        changes.riskScans.put(scan.symbolId(), scan);
        changes.changedRiskScans.add(scan.symbolId());
    }

    /** Owner 校验期望控制版本，生成下一版本的启用、间隔和工作量配置。 */
    public void updateRiskScanControl(UpdateRiskScanControlCommand command, long updatedAtEpochMillis) {
        if (command == null) {
            throw new IllegalArgumentException("invalid runtime risk scan control update");
        }
        assertOwner();
        CoreRiskScanControlView current = riskScanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        requireRevisionCapacity();
        setRiskScanControl(new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis)));
        incrementCommandRevision();
    }

    /** 直接写入已使用整数币对编号的运行时扫描记录，再增加命令修订号。 */
    public void replaceRiskScan(RiskScanner.State scan) {
        if (scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        putRiskScan(scan);
        incrementCommandRevision();
    }

    /** 校验非负后保存调用方提供的行情修订。 */
    public void setMarketRevision(long value) {
        assertOwner();
        if (value < 0) throw new IllegalArgumentException("invalid market revision");
        changes.marketRevision = value;
    }

    /** 禁止订单批量交叉修改，校验正值后保存下一个强平身份。 */
    public void setNextLiquidationId(long value) {
        assertOwner();
        rejectUnsupportedOrderBatchMutation("liquidation sequence");
        if (value <= 0) throw new IllegalArgumentException("invalid next liquidation id");
        changes.nextLiquidationId = value;
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
            changes.changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        markUserChanged(liquidation.userId());
        markUserChanged(previous.userId());
    }

    /** 保存用户、订单与预留前值，在所属账户完成精确冻结释放及终态更新，再交出变化并推进用户修订。 */
    public void cancelOrder(long orderId, long userId, long releaseUnits) {
        assertOwner();
        accountRollback.captureUserBefore(userId);
        accountRollback.captureOrderBefore(orderId);
        accountRollback.captureReservationBefore(orderId);
        AccountPartition scoped = currentLaneCommandScope();
        Order terminalOrder = scoped == null
                ? onLane(userId, lane -> cancelOrderInOwnerLane(lane, orderId, userId, releaseUnits))
                : cancelOrderInOwnerLane(scoped, orderId, userId, releaseUnits);
        if (currentMatcherSettlementChangesScope() == null) {
            changedOrder(orderId, terminalOrder);
            markReservationChanged(orderId);
            markUserChanged(userId);
            markBalancesChanged();
        }
        advanceUserRevision(userId);
    }

    private Order cancelOrderInOwnerLane(AccountPartition lane, long orderId, long userId, long releaseUnits) {
        if (lane.laneId() != topology.accountLaneId(userId)) {
            throw new IllegalStateException("terminal order release crossed its owner Lane");
        }
        Order order = lane.order(orderId);
        OrderFundsReservation reservation = lane.reservation(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        if (releaseUnits != reservation.reservedUnits()) {
            throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
        }
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) {
            throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
        }
        accountRollback.captureBalanceBefore(userId, reservation.assetId());
        balance.release(releaseUnits);
        if (currentMatcherSettlementChangesScope() != null && currentLaneCommandScope() == lane) {
            long previousReserved = reservation.reservedUnits();
            reservation.releaseInPlace(releaseUnits);
            lane.updatePendingReservationInPlace(reservation, previousReserved);
            Order terminalOrder = lane.updateOrderStatusInPlace(orderId, CoreOrderStatus.CANCELED,
                    Math.incrementExact(order.revision()), -1, -1);
            accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
            publishOrder(orderId, terminalOrder);
            publishReservation(orderId, reservation);
            return terminalOrder;
        }
        Order terminalOrder = order.withStatus(CoreOrderStatus.CANCELED,
                Math.incrementExact(order.revision()), -1, -1);
        OrderFundsReservation released = reservation.release(releaseUnits);
        lane.replacePendingReservation(reservation, released);
        lane.putOrder(terminalOrder);
        lane.storeReservation(orderId, released.laneValue());
        accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        publishOrder(orderId, terminalOrder);
        publishReservation(orderId, released);
        return terminalOrder;
    }

    /** 使用原负值元数据哨兵，在明确撮合 Lane 中取消订单。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, long orderId) {
        cancelOrderInLane(lane, userId, orderId, -1, -1);
    }

    /** 在明确撮合 Lane 中释放剩余冻结并把已解析订单改为 CANCELED，避免重复 HashMap 查表。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, Order order,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, order, null, timestamp, position, CoreOrderStatus.CANCELED);
    }

    /** 在明确撮合 Lane 中释放剩余冻结并把订单改为 CANCELED。 */
    public void cancelOrderInLane(AccountPartition lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.CANCELED);
    }

    /** 在明确撮合 Lane 中释放剩余冻结并把订单改为 REJECTED。 */
    public void rejectOrderInLane(AccountPartition lane, long userId, long orderId,
                           long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.REJECTED);
    }

    /** 要求有效撮合范围及尚未终态的订单，释放全部剩余冻结，原地更新预留与订单并发布前后变化，最后累计用户修订。 */
    private void releaseOrderToTerminalInLane(AccountPartition lane, long userId, long orderId,
            long timestamp, long position, CoreOrderStatus status) {
        Order order = lane.order(orderId);
        releaseOrderToTerminalInLane(lane, userId, order, null, timestamp, position, status);
    }

    private void releaseOrderToTerminalInLane(AccountPartition lane, long userId, Order order,
            OrderFundsReservation preparedReservation, long timestamp, long position, CoreOrderStatus status) {
        requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("terminal order requires its settlement Lane");
        if (order == null || order.userId() != userId || order.status().terminal())
            throw new IllegalStateException("terminal order reservation is missing: " + (order == null ? 0 : order.orderId()));
        long orderId = order.orderId();
        OrderFundsReservation reservation = preparedReservation == null ? lane.reservation(orderId) : preparedReservation;
        if (reservation == null)
            throw new IllegalStateException("terminal order reservation is missing: " + orderId);
        AccountBalance balance = balance(lane, userId, reservation.assetId());
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

    /** 在明确撮合 Lane 内释放已经终态订单仍保留的冻结，原地更新预留及余额补丁。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, long orderId) {
        return releaseTerminalReservationInLane(lane, orderId, null, null);
    }

    /** 在明确撮合 Lane 内释放已持有的终态订单引用，省去 Lane 内部二次查表。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, Order preparedOrder,
            OrderFundsReservation preparedReservation) {
        return releaseTerminalReservationInLane(lane, preparedOrder == null ? 0 : preparedOrder.orderId(),
                preparedOrder, preparedReservation);
    }

    /** 在明确撮合 Lane 内释放已经终态订单仍保留的冻结，原地更新预留及余额补丁。 */
    public long releaseTerminalReservationInLane(AccountPartition lane, long orderId,
            Order preparedOrder, OrderFundsReservation preparedReservation) {
        lane.assertOwner();
        if (lane == null || lane.matcherSettlementChanges() == null) {
            throw new IllegalStateException("terminal release must execute in its owning Account Lane");
        }
        Order order = preparedOrder == null ? lane.order(orderId) : preparedOrder;
        long targetOrderId = order != null ? order.orderId() : orderId;
        OrderFundsReservation reservation = preparedReservation == null ? lane.reservation(targetOrderId) : preparedReservation;
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + targetOrderId);
        }
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return 0;
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(order.userId());
        AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + targetOrderId);
        accountRollback.captureBalanceBefore(lane, order.userId(), reservation.assetId());
        balance.release(releaseUnits);
        long previousReserved = reservation.reservedUnits();
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        publishReservation(lane, targetOrderId, reservation);
        accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
        return releaseUnits;
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
        assertOwner();
        int laneId = topology.accountLaneId(userId);
        if (!accountLanesStarted()) {
            try { return java.util.concurrent.CompletableFuture.completedFuture(onLane(userId, lane -> TradingRealtimeBoundary.UserSnapshot.capture(lane, userId))); }
            catch (RuntimeException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
        }
        var result = new java.util.concurrent.CompletableFuture<TradingRealtimeBoundary.UserSnapshot>();
        boolean accepted = dispatchAccountQuery(1L << laneId, lane -> {
            try { result.complete(readAccountLane(lane, owned -> TradingRealtimeBoundary.UserSnapshot.capture(owned, userId))); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        });
        if (!accepted) result.completeExceptionally(new IllegalStateException("realtime snapshot Lane mailbox is full"));
        return result;
    }

    /** 接入可空实时捕获器，不在此启动输出线程。 */
    public void realtimeCapture(TradingRealtimeBoundary.StateCapture capture) { assertOwner(); changes.realtimeCapture = capture; }

    /** 实时捕获器存在时记录用户元数据，捕获运行时异常只记失败。 */
    public void captureUserMetadata(com.surprising.aeron.service.account.UserAccount value) {
        if (changes.realtimeCapture == null) return;
        try { changes.realtimeCapture.metadata(value); }
        catch (RuntimeException failure) { changes.realtimeCapture.failed(); }
    }
    /** 实时捕获器存在时记录预留变化，捕获运行时异常只记失败。 */
    public void captureReservation(OrderFundsReservation value) {
        if (changes.realtimeCapture == null) return;
        try { changes.realtimeCapture.reservation(value); }
        catch (RuntimeException failure) { changes.realtimeCapture.failed(); }
    }

    /** 启用私有捕获时导出本命令风险、订单和持仓变化。 */
    public void captureRealtimeChanges(TradingRealtimeBoundary.StateCapture capture) {
        assertOwner();
        if (capture == null || !capture.active()) return;
        try {
            changedRiskSnapshots.forEach((id, value) -> capture.risk(value));
            if (!accountLanesStarted()) {
                changedUsers.forEach(id -> capture.metadata(user(id)));
                changedReservations.forEach(id -> capture.reservation(reservation(id)));
            }
            changedOrders.forEach((orderId, value) -> capture.order(value));
            changes.changedPositions.forEach((positionKey, value) -> {
                if(value==null)capture.removedPosition(currentPatchPositionBefore(positionKey));else capture.position(value);
            });
        } catch (RuntimeException failure) { capture.failed(); }
    }

    /** 校验接收函数后直接遍历原始变化用户身份，不先创建集合副本。 */
    public void acceptChangedUserIds(java.util.function.LongConsumer consumer) {
        assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed user consumer is required");
        changedUsers.forEach(consumer::accept);
    }

    /** 标记本命令涉及余额及用户变化。 */
    public void markBalanceChanged(long userId) {
        assertOwner();
        markBalancesChanged();
        markUserChanged(userId);
    }

    /** 按当前范围把预留身份交给 Owner 集合或所属 Lane 的变化容器。 */
    private void markReservationChanged(long orderId) {
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) changedReservations.add(orderId);
        else laneDelta(lane.laneId()).putReservation(orderId, lane.reservation(orderId));
    }

    /** 活动账户没有当前范围时要求已有收集收据，避免退回账户读取。 */
    public void changedOrder(long orderId) {
        if (currentLaneCommandScope() == null && accountLanesStarted()) {
            if (!changedOrders.containsKey(orderId))
                throw new IllegalStateException("order change requires a collected Lane receipt: " + orderId);
            return;
        }
        changedOrder(orderId, order(orderId));
    }

    /** 按当前执行范围登记订单值或删除标记，不在此再次修改账户。 */
    public void changedOrder(long orderId, Order order) {
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) changedOrders.put(orderId, order);
        else laneDelta(lane.laneId()).putOrder(orderId, order);
    }

    /** 活动账户没有当前范围时要求已有持仓收据。 */
    void changedPosition(long positionKey) {
        if (currentLaneCommandScope() == null && accountLanesStarted()) {
            if (!changes.changedPositions.containsKey(positionKey))
                throw new IllegalStateException("position change requires a collected Lane receipt: " + positionKey);
            return;
        }
        changedPosition(positionKey, position(positionKey));
    }

    /** 把持仓变化或空删除值交给当前 Lane 容器或 Owner 缓冲。 */
    void changedPosition(long positionKey, Position position) {
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null) changes.changedPositions.put(positionKey, position);
        else laneDelta(lane.laneId()).putPosition(positionKey, position);
    }

    /** 借出当前持仓变化键的原始迭代器，消费期间保留变化缓冲有效。 */
    public org.eclipse.collections.api.iterator.LongIterator changedPositionIterator() {
        assertOwner();
        return changes.changedPositions.longIterator();
    }

    /** 判断本命令持仓变化缓冲是否非空。 */
    public boolean hasChangedPositions() {
        assertOwner();
        return !changes.changedPositions.isEmpty();
    }

    /** 遍历直接及保留 Lane 变化缓冲，直接更新运行时绑定的事实索引。 */
    public void applyChangedIndexes() {
        assertOwner();
        if (changes.factIndexes == null) throw new IllegalStateException("fact indexes are not bound");
        applyChangedOrderIndexes(changedOrders.directBuffer());
        long remaining = changedOrders.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            applyChangedOrderIndexes(changedOrders.laneBuffer(laneId));
        }
        for (int index = 0; index < changes.changedLiquidations.size(); index++) {
            RiskScanner.LiquidationOrder value = changes.changedLiquidations.valueAt(index);
            if (changes.realtimeCapture != null && changes.realtimeCapture.active()) {
                try { changes.realtimeCapture.liquidation(value); }
                catch (RuntimeException failure) { changes.realtimeCapture.failed(); }
            }
            changes.factIndexes.liquidations().apply(changes.changedLiquidations.keyAt(index), value, changes.indexIdentities);
        }
        for (int index = 0; index < changes.changedAlgoOrders.size(); index++)
            changes.factIndexes.algos().apply(changes.changedAlgoOrders.keyAt(index), changes.changedAlgoOrders.valueAt(index));
        for (int index = 0; index < changes.changedTriggerOrders.size(); index++) {
            long triggerOrderId = changes.changedTriggerOrders.keyAt(index);
            CoreTriggerOrderState value = changes.changedTriggerOrders.valueAt(index);
            if (changes.realtimeCapture != null && changes.realtimeCapture.active()) {
                try { changes.realtimeCapture.trigger(value); } catch (RuntimeException failure) { changes.realtimeCapture.failed(); }
            }
            changes.factIndexes.triggers().apply(triggerOrderId, value);
        }
        if (!changes.changedCancelAllAfterTimers.isEmpty())
            for (var key : changes.changedCancelAllAfterTimers) changes.factIndexes.timers().apply(key, cancelAllAfterTimer(key));
    }

    private void applyChangedOrderIndexes(StateChangeBuffer<Order> orders) {
        for (int index = 0; index < orders.size(); index++)
            changes.factIndexes.activeOrders().apply(orders.keyAt(index), orders.valueAt(index), changes.indexIdentities);
    }

    /** 日志发布后先处理风险与持仓身份退休，再清变化容器并执行普通提交清理。 */
    public void clearCommittedChanges(RuntimeIdentityRegistry identities) {
        assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        for (int index = 0; index < changedRiskSnapshots.size(); index++) {
            long positionKey = changedRiskSnapshots.keyAt(index);
            if (changedRiskSnapshots.valueAt(index) == null && changedRiskSnapshots.hasPreparedAt(index)
                    && Boolean.TRUE.equals(changedRiskSnapshots.preparedAt(index))
                    && changes.changedPositions.get(positionKey) == null)
                releaseRetiredPositionIdentity(identities, positionKey);
        }
        changedRiskSnapshots.clear();
        releaseRetiredPositionIdentities(identities, changes.changedPositions.directBuffer());
        long remaining = changes.changedPositions.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            releaseRetiredPositionIdentities(identities, changes.changedPositions.laneBuffer(laneId));
        }
        changes.changedPositions.clear();
        clearChangedKeys();
    }

    private void releaseRetiredPositionIdentities(RuntimeIdentityRegistry identities,
            StateChangeBuffer<Position> positions) {
        for (int index = 0; index < positions.size(); index++)
            if (positions.valueAt(index) == null) releaseRetiredPositionIdentity(identities, positions.keyAt(index));
    }

    /** 只有风险记录不再持有该键时才请求身份表释放持仓身份。 */
    void releaseRetiredPositionIdentity(RuntimeIdentityRegistry identities, long positionKey) {
        if (!changes.riskPositionIdentities.contains(positionKey)) {
            identities.releasePositionKey(positionKey);
        }
    }

    /** 衍生品直接结算在当前 Lane 输出中只保留一次持仓身份，核对保留结果未换键，再登记当前持仓值以便发布后归还。 */
    public void retainDirectPositionIdentity(AccountPartition lane, RuntimeIdentityRegistry identities,
                                      CoreInstrument instrument, Order order) {
        if (!productLine.isDerivative()) return;
        TradeSettlement.Changes changes = lane.matcherSettlementChanges();
        if (changes == null || !changes.directPositionIdentities() || (!lane.commandScopeActive() && coordinator.ownerLaneScope != lane))
            throw new IllegalStateException("direct position identity requires its settlement Lane");
        long key = order.positionIdentityKey();
        var output = changes.laneDelta(lane.laneId()).positions();
        if (output.indexOf(key) >= 0) return;
        long retained = identities.retainPositionInLane(
                lane, key, order.userId(), instrument.instrumentId(), order.positionSide());
        if (retained != key) throw new IllegalStateException("position identity changed during retention");
        output.put(key, lane.position(key));
    }

    /** 用当前修订精确减去待完成预留数，得到当前可提交修订。 */
    public long committedRevision() {
        assertOwner();
        return Math.subtractExact(revision, pendingReservations.totalPendingReservations());
    }

    /** 活动财务控制已有专用槽时沿用其校验。否则仅在账户余额或持仓有变化时同步校验。校验失败直接抛出，财务范围关闭仍需后续提交或回滚。 */
    public void validateControlFunds(boolean externalAdjustment) {
        assertOwner();
        if (changes.ledgerControl != null) {
            changes.ledgerControl.validateFunds(externalAdjustment);
            return;
        }
        boolean accountFundsChanged = false;
        for (int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            AccountTransactionRollback.BalancePatches patches = accountRollback.balancePatch(laneIndex);
            accountFundsChanged |= patches.size() != 0;
        }
        if (!accountFundsChanged && changes.changedPositions.isEmpty()) return;
        if (!coordinator.controlFundsValidated) {
            appendControlAccountFunds(coordinator.controlValidationFunds);
            coordinator.controlValidationFunds.requireConserved(externalAdjustment);
            coordinator.controlValidationFunds.clear();
            applyLedgerControlPositions(coordinator.controlPositionUndo);
            coordinator.controlFundsValidated = true;
        }
    }

    /** 遍历现有各 Lane 余额前后补丁，将本控制命令资金差额追加到目标累加器，不清空目标。 */
    public void appendControlAccountFunds(RuntimeFundsAccumulator target) {
        for (int laneIndex = 0; laneIndex < accountRollback.balancePatchCount(); laneIndex++) {
            AccountTransactionRollback.BalancePatches patches = accountRollback.balancePatch(laneIndex);
            appendBalanceFundsDelta(patches, target);
        }
    }

    /** 清空目标累加器后，按一份 Lane 余额补丁重建资金差额。 */
    public static void prepareBalanceFundsDelta(AccountTransactionRollback.BalancePatches patches,
                                                 RuntimeFundsAccumulator accumulator) {
        accumulator.clear();
        appendBalanceFundsDelta(patches, accumulator);
    }

    /** 要求每项余额已发布后值，将缺失前后值按零处理，分别精确计算可用与冻结差额并追加用户分账项。 */
    static void appendBalanceFundsDelta(AccountTransactionRollback.BalancePatches balances,
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
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.USER, userId,
                    TradeSettlement.Posting.Subledger.AVAILABLE,
                    Math.subtractExact(afterAvailable, beforeAvailable));
            accumulator.add(assetId, TradeSettlement.Posting.OwnerKind.USER, userId,
                    TradeSettlement.Posting.Subledger.LOCKED,
                    Math.subtractExact(afterLocked, beforeLocked));
        }
    }

    /** 借出当前命令回滚补丁保存的持仓前值。 */
    public Position currentPatchPositionBefore(long positionKey) {
        return accountRollback.currentPositionBefore(positionKey);
    }

    /** 提交或回滚收尾时清当前命令变化，按冷财库或活动控制槽结束财务范围，再发布清余额补丁并请求独立资金校验关闭。 */
    public void clearChangedKeys() {
        assertOwner();
        changes.balancesChanged = false;
        accountRollback.clearNonBalanceImages();
        clearChanged(changedUsers);
        changedOrders.clear();
        clearChanged(changedReservations);
        changes.changedPositions.clear();
        changes.changedLiquidations.clear();
        clearChanged(changes.changedMarkPrices);
        changedRiskSnapshots.clear();
        clearChanged(changes.changedRiskScans);
        changes.changedLeverages.clear();
        changes.changedAlgoOrders.clear();
        changes.changedCancelAllAfterTimers.clear();
        changes.changedTriggerOrders.clear();
        clearChanged(changes.changedFeePolicies);
        if (changes.ledgerControl != null) {
            changes.ledgerControl.commitScope();
            changes.ledgerControl = null;
        } else {
            treasury.clearChangedKeys();
        }
        accountRollback.publishAndClearBalances();
        if (coordinator.controlFundsValidated) {
            coordinator.controlPositionUndo.clear();
            coordinator.controlFundsValidated = false;
        }
    }

    /** 清空非空长整数变化集合，原数量达到压缩阈值时再压缩内部容量。 */
    static void clearChanged(LongHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= PartitionCoordinator.CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    /** 清空非空整数变化集合，原数量达到阈值时压缩内部容量。 */
    static void clearChanged(IntHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= PartitionCoordinator.CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    /** 在调用方已建立的读取边界内，合并各 Lane 用户快照到新映射，不自行取得账户权限。 */
    public LongObjectHashMap<UserAccount> usersForSnapshot() {
        LongObjectHashMap<UserAccount> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, AccountPartition::usersSnapshot));
        }
        return values;
    }

    /** 在已有读取边界内合并各 Lane 余额快照，返回新顶层映射。 */
    public LongObjectHashMap<IntObjectHashMap<AccountBalance>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<AccountBalance>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.balancesSnapshot()));
        }
        return values;
    }

    /** 在已有读取边界内把各 Lane 订单引用收集到新映射，不深复制订单。 */
    public LongObjectHashMap<Order> ordersForSnapshot() {
        LongObjectHashMap<Order> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> {
                lane.forEachOrder(values::put);
                return null;
            });
        }
        return values;
    }

    /** 在所属账户范围内，按用户、标的、持仓方向、买卖侧和保证金模式读取现有减仓单数量。 */
    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        return onLane(userId, lane -> lane.openReduceOnlyQuantity(
                userId, symbolId, positionSide, side, marginMode));
    }

    /** 在已有读取边界内收集各 Lane 预留引用到新映射。 */
    public LongObjectHashMap<OrderFundsReservation> reservationsForSnapshot() {
        LongObjectHashMap<OrderFundsReservation> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            onLaneReadOnly(laneId, lane -> {
                lane.forEachReservation(values::put);
                return null;
            });
        }
        return values;
    }

    /** 在已有读取边界内合并各 Lane 客户订单号索引快照。 */
    public LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.clientOrdersSnapshot()));
        }
        return values;
    }

    /** 在已有读取边界内合并各 Lane 提供的持仓快照。 */
    public LongObjectHashMap<Position> positionsForSnapshot() {
        LongObjectHashMap<Position> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.positionsSnapshot()));
        }
        return values;
    }

    /** 在已有读取边界内合并各 Lane 提供的强平快照。 */
    public LongObjectHashMap<RiskScanner.LiquidationOrder> liquidationsForSnapshot() {
        LongObjectHashMap<RiskScanner.LiquidationOrder> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.liquidationsSnapshot()));
        }
        return values;
    }

    /** 借出原标记价映射，由外层快照边界保护读取，不创建独立映射。 */
    public IntObjectHashMap<MarkPrice> markPricesForSnapshot() {
        return markPrices;
    }

    /** 在已有读取边界内合并各 Lane 提供的风险快照映射。 */
    public LongObjectHashMap<RiskMarginCalculator.Snapshot> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskMarginCalculator.Snapshot> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < accountLanes.length; laneId++) {
            values.putAll(onLaneReadOnly(laneId, lane -> lane.riskSnapshotsSnapshot()));
        }
        return values;
    }

    /** 借出原风险扫描映射，由外层快照边界保护读取。 */
    public IntObjectHashMap<RiskScanner.State> riskScansForSnapshot() {
        return changes.riskScans;
    }

    public int riskScanCount() {
        assertOwner();
        return changes.riskScans.size();
    }

    /** 校验已准备订单与预留的身份、用户、标的和数量一致，再以预留剩余量调用冻结安装入口。 */
    public void reserveOrder(Order order, OrderFundsReservation reservation, long clientKey) {
        if (order == null || reservation == null || order.orderId() != reservation.orderId()
                || order.userId() != reservation.userId() || order.symbolId() != reservation.symbolId()
                || order.quantitySteps() != reservation.orderQuantitySteps() || clientKey < 0) {
            throw new IllegalArgumentException("invalid prepared reservation");
        }
        reserveOrder(order, reservation, clientKey, reservation.reservedUnits());
    }

    /** 保存命令回滚前值，在所属 Lane 冻结指定资金并安装已有订单、预留及客户号。范围内通过 Lane 变化交接，范围外登记 Owner 变化。 */
    public void reserveOrder(Order order, OrderFundsReservation reservation,
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
        AccountPartition scoped = currentLaneCommandScope();
        if (scoped != null) reserveOrderInLane(scoped, order, reservation, clientKey, reservedUnits);
        else onLane(userId, lane -> {
            reserveOrderInLane(lane, order, reservation, clientKey, reservedUnits);
            return null;
        });
        // 账户控制任务运行时 Owner 可能挂起或收集当前提交。
        // 通过 Lane 变化交接，等 Owner 观察完成后再由 collectControlReservation 登记变化。
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

    private void reserveOrderInLane(AccountPartition lane, Order order, OrderFundsReservation reservation,
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
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        if (balance == null)
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
        balance.reserve(reservedUnits);
        lane.putOrder(order);
        lane.storeReservation(orderId, reservation);
        lane.addReservationId(userId, orderId);
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, orderId);
        accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    /** 用已解析参数构造独立 OPEN 订单，初始修订为一。 */
    static Order preparedOrder(com.surprising.product.api.ProductLine productLine,
            long userId, OrderCommand.ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId,
            long timestamp, long position) {
        return new Order(command.orderId(), productLine, userId, symbolId,
                command.instrument(), command.side(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, timestamp, timestamp, position, CoreOrderStatus.OPEN, 1);
    }

    /** 纯冷阶段构造独立订单，活动执行要求当前所属 Lane 并使用共享退休栅栏的订单池。 */
    public Order prepareControlOrder(long userId, OrderCommand.ResolvedPlaceOrder command, java.util.UUID commandId, int symbolId) {
        if (!accountLanesStarted())
            return preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0);
        AccountPartition lane = currentLaneCommandScope();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("active order creation requires its Account Lane");
        return lane.preparedOrder(productLine, userId, command, commandId, symbolId, 0, 0, coordinator.retiredOwnerSequence);
    }

    /** 校验所属范围、身份、客户号及可用余额，选择已准备值或 Lane 池订单，冻结资金并写入预留、索引及用户修订。仅未传 prepared 时自行登记待预留，普通临时下单不保存控制命令回滚前值。 */
    public void placeOrderInLane(AccountPartition lane, long userId, OrderCommand.ResolvedPlaceOrder command,
            java.util.UUID commandId, long requiredReservation, long clientKey,
            int symbolId, int assetId, long coreSequence, Order prepared, long timestamp, long position) {
        if (lane == null || !(lane.commandScopeActive() || Thread.currentThread() == owner && coordinator.ownerLaneScope == lane)
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
        UserAccount user = lane.user(userId);
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        Order order = prepared != null && !prepared.commandSlotValue() ? prepared
                : accountLanesStarted()
                ? lane.preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position, coordinator.retiredOwnerSequence)
                : preparedOrder(productLine, userId, command, commandId, symbolId, timestamp, position);
        OrderFundsReservation reservation = order.prepareReservation(
                command.reservationKind(), assetId, requiredReservation);
        long nextUserRevision = Math.incrementExact(user.revision());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.storeReservation(reservation.orderId(), reservation.laneValue());
        lane.addReservationId(userId, order.orderId());
        if (clientKey != 0) putClientOrderIndex(lane, userId, clientKey, order.orderId());
        lane.storeUser(userId, (accountLanesStarted() ? user.laneValue() : user)
                .withState(nextUserRevision, user.positionMode()));
        if (prepared == null) lane.markPendingReservation(order.orderId(), coreSequence);
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
        if (currentLaneCommandScope() == null) changes.balancesChanged = true;
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
            RuntimeIdentityRegistry identities, long timestamp, long position, List<Long> cancellations,
            OrderCommand.ResultTarget target) {
        return prepareDirectMatcherSettlement(sequence, sequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target, null);
    }

    /** 普通下单准入尚未完成时先准备直接结算槽，固定命令证明字段。 */
    public TradeSettlement prepareDirectMatcherSettlement(
            long sequence, long laneMask, OrderCommand.PlaceAdmissionEvent admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, OrderCommand.ResultTarget target) {
        assertOwner();
        if (admission == null) throw new IllegalArgumentException("place admission is missing");
        OrderCommand.ResolvedPlaceOrder resolved = admission.matchingOrder();
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = coordinator.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        TradeSettlement.BatchStorage storage = event.batchStorage(1);
        CoreInstrument instrument = instrument(identities.instrumentId(resolved.symbolId()));
        if (instrument == null || instrument != resolved.instrument()) {
            throw new IllegalStateException("direct settlement instrument changed");
        }
        storage.instruments[0] = instrument;
        storage.baseAssetIds[0] = identities.assetId(instrument.baseAsset());
        storage.quoteAssetIds[0] = identities.assetId(instrument.quoteAsset());
        storage.settleAssetIds[0] = identities.assetId(instrument.settleAsset());
        storage.metadataSlots.put(resolved.symbolId(), 0);
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                this, identities, 1, cancellations, target);
        event.admissionRoute(admission.laneId());
        event.admissionOrder(resolved, admission.userId());
        return event;
    }

    /** 显式区分原业务序号与提交序号，准备直接撮合结算事件，不在此应用账户成交。 */
    public TradeSettlement prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, Order single, Order[] orders, int count, java.util.UUID commandId,
            int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, OrderCommand.ResultTarget target) {
        return prepareDirectMatcherSettlement(coreSequence, commitSequence, laneMask, single, orders, count,
                commandId, shard, identities, timestamp, position, cancellations, target, null);
    }

    private TradeSettlement prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, Order single, Order[] orders, int count, java.util.UUID commandId,
            int shard, RuntimeIdentityRegistry identities, long timestamp, long position,
            List<Long> cancellations, OrderCommand.ResultTarget target,
            com.surprising.aeron.service.order.OrderCommand.ReplacementAdmission replacement) {
        assertOwner();
        if (count <= 0 || replacement == null && single == null && (orders == null || count > orders.length))
            throw new IllegalArgumentException("direct settlement admission is missing");
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = coordinator.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        TradeSettlement.BatchStorage storage = event.batchStorage(count);
        if (replacement != null) {
            if (count != 1) throw new IllegalStateException("replacement must have one admission slot");
            var resolved = replacement.resolved();
            single = storage.replacementOrder = Order.prepareCommandOrder(storage.replacementOrder,
                    productLine, replacement.userId(), resolved, commandId, resolved.symbolId(), timestamp, position);
        }
        for (int index = 0; index < count; index++) {
            Order order = single == null ? orders[index] : single;
            if (order == null) throw new IllegalStateException("direct settlement order was not admitted");
            storage.admittedOrders[index] = order;
            int prior = storage.metadataSlots.get(order.symbolId());
            if (prior < 0) {
                CoreInstrument instrument = instrument(identities.instrumentId(order.symbolId()));
                if (instrument == null || instrument != order.instrument())
                    throw new IllegalStateException("direct settlement instrument changed");
                storage.instruments[index] = instrument;
                storage.baseAssetIds[index] = identities.assetId(instrument.baseAsset());
                storage.quoteAssetIds[index] = identities.assetId(instrument.quoteAsset());
                storage.settleAssetIds[index] = identities.assetId(instrument.settleAsset());
                storage.metadataSlots.put(order.symbolId(), index);
            } else {
                storage.instruments[index] = storage.instruments[prior];
                storage.baseAssetIds[index] = storage.baseAssetIds[prior];
                storage.quoteAssetIds[index] = storage.quoteAssetIds[prior];
                storage.settleAssetIds[index] = storage.settleAssetIds[prior];
            }
        }
        event.prepareDirect(coreSequence, commitSequence, laneMask, timestamp, position, commandId, shard,
                this, identities, count, cancellations, target);
        return event;
    }

    /** 把已解决的改单准入及原提交边界交给直接替换结算准备入口。 */
    public TradeSettlement prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            com.surprising.aeron.service.order.OrderCommand.ReplacementAdmission admission,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, List<Long> cancellations, OrderCommand.ResultTarget resultTarget) {
        assertOwner();
        var resolved = admission.resolved();
        TradeSettlement event = prepareDirectMatcherSettlement(sequence, commitSequence, laneMask, null, null, 1,
                commandId, shard, identities, timestamp, position, cancellations, resultTarget, admission);
        event.replacement(admission, identities.assetId(resolved.reservationAsset()));
        return event;
    }

    /** 为直接撤单准备补上空的最终结果槽。 */
    public TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position) {
        return prepareDirectCancellation(sequence, userId, orderId, commandId, shard, identities,
                timestamp, position, null);
    }

    /** 按用户、订单和原提交边界准备直接撤单结算事件。 */
    public TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, OrderCommand.ResultTarget resultTarget) {
        assertOwner();
        if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("cancel order is missing");
        long laneMask = topology.accountLaneMask(userId);
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = coordinator.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        event.batchStorage(1).cancellationOrderIds[0] = orderId;
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                this, identities, 1, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /** 把批量撤单身份、有效项数及是否最后一段交给直接结算准备入口。 */
    public TradeSettlement prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders,
            int count, java.util.UUID commandId, int shard, RuntimeIdentityRegistry identities,
            long timestamp, long position, OrderCommand.ResultTarget resultTarget) {
        assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        long mask = topology.accountLaneMask(userId);
        ensureLaneWorkerCapacity(mask);
        TradeSettlement event = coordinator.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        System.arraycopy(orders, 0, event.batchStorage(count).cancellationOrderIds, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                this, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    /** 下单步骤 4.3：单 Matcher 发布方校验事件与实际路由，向每个 Lane 的分片 SPSC 队列投递一次。 */
    public void publishMatcherOwned(TradeSettlement event) {
        if (event == null || event.runtime() != this || !event.direct()
                || !event.dispatched() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.dispatched() && event.queuedLaneMask() != 0)
            throw new IllegalStateException("Matcher settlement was already dispatched");
        long lanes = event.routedLaneMask();
        if (lanes == 0) throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!accountLanesStarted()) return;
        int shard = event.directShard();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            event.markLaneQueued(laneId);
            publishMatcherSettlement(laneId, shard, event);
        }
    }

    /** Owner 清理已完成且已交出 changes 的事件并归还对象池。具体完成与租约校验由 event.clear 执行，空事件不处理。 */
    public void releaseMatcherSettlement(TradeSettlement event) {
        assertOwner();
        if (event == null) return;
        event.clear();
        coordinator.matcherSettlementEventPool.addFirst(event);
    }

    /** Owner 丢弃已完成的拒绝等直达事件。 */
    public void discardMatcherSettlement(TradeSettlement event) {
        assertOwner();
        if (event == null) return;
        if (!event.complete()) throw new IllegalStateException("rejected direct settlement is incomplete");
        if (event.hasChanges()) {
            releaseMatcherSettlementChanges(event.takeChanges());
        }
        event.clear();
        coordinator.matcherSettlementEventPool.addFirst(event);
    }

    /** 在批量账户作用域中等待本项结算完成，先把余额前后值按顺序接入批次回滚边界，再交既有收集入口处理变化。 */
    public void collectOrderBatchMatcherSettlement(
            TradeSettlement event, TerminalOrderSink terminalOrderSink) {
        assertOwner();
        if (!orderBatchMutationScope() || !event.complete())
            throw new IllegalStateException("batch item settlement is not ready to collect");
        assertAccountLanesHealthy();
        long lanes = event.requiredLaneMask();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            accountRollback.balancePatch(laneId).mergeSequential(event.changes().balancePatches[laneId]);
        }
        collectMatcherSettlement(event, terminalOrderSink, null, null);
    }

    /** 借出包含直接与保留 Lane 输出的订单变化容器。 */
    public StateChangeBuffer.OwnerIndexed<Order, Void> orderChanges() { return changedOrders; }
    /** 借出包含持仓值及已准备索引的变化容器。 */
    public StateChangeBuffer.OwnerIndexed<Position, Position.IndexValue> positionChanges() { return changes.changedPositions; }

    /** 按数组下标借出原账户 Lane，调用方保证下标及访问边界有效。 */
    public AccountPartition accountLane(int index) { return accountLanes[index]; }
    /** 返回账户 Lane 数量。 */
    public int accountLaneCount() { return accountLanes.length; }

    /** 直接向 Owner 变化集合加入用户身份，调用方保证当前收集范围正确。 */
    public void changedUser(long userId) { changedUsers.add(userId); }
    /** 直接向 Owner 变化集合加入预留订单身份。 */
    public void changedReservation(long orderId) { changedReservations.add(orderId); }
    /** 登记仍由风险记录持有的持仓身份，延后其退休释放。 */
    public void addRiskPositionIdentity(long key) { changes.riskPositionIdentities.add(key); }
    /** 取消风险记录对持仓身份的保留标记。 */
    public void removeRiskPositionIdentity(long key) { changes.riskPositionIdentities.remove(key); }
    /** 直接恢复调用方提供的修订值，不在此校验或推进业务。 */
    public void restoreRevision(long value) { revision = value; }

    /** 再次核对订单和客户端标识未占用、用户及资产余额存在且可用金额足够，准备订单和冻结，写入并推进用户和命令版本。 */
    public void placePreparedOrder(long userId, com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder command, java.util.UUID commandId,
                                   long requiredReservation, long clientKey, int symbolId, int assetId) {
        if (command == null || commandId == null || userId <= 0
                || requiredReservation <= 0 || clientKey < 0 || symbolId < 0 || assetId < 0) {
            throw new IllegalArgumentException("invalid prepared runtime place order");
        }
        assertOwner();
        if (order(command.orderId()) != null) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (clientKey != 0 && orderIdByClient(userId, clientKey) != null) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        com.surprising.aeron.service.account.UserAccount user = user(userId);
        com.surprising.aeron.service.account.AccountBalance balance = balance(userId, assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        com.surprising.aeron.service.order.Order order = prepareControlOrder(userId, command, commandId, symbolId);
        com.surprising.aeron.service.order.OrderFundsReservation reservation = order.prepareReservation(command.reservationKind(), assetId, requiredReservation);
        long userRevision = Math.incrementExact(user.revision());
        reserveOrder(order, reservation, clientKey);
        setUserState(userId, userRevision, user.positionMode());
        incrementCommandRevision();
    }

    /** 在触发子单所属 Lane 计算预留并写订单，标记待完成冻结。 */
    public com.surprising.aeron.service.order.Order placeTriggerChildInLane(long userId,
            com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder command, java.util.UUID commandId,
            long coreSequence, long openInterestSteps,
            com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities,
            long clientKey, int assetId, boolean lifecycleSettled, boolean fundingInProgress) {
        com.surprising.aeron.service.account.AccountPartition lane = currentLaneCommandScope();
        if (lane == null || lane.laneId() != topology.accountLaneId(userId))
            throw new IllegalStateException("trigger child requires its Account Lane");
        long positionKey = identities.findPositionKeyValueInLane(lane, userId,
                command.instrument(), command.positionSide());
        long required = com.surprising.aeron.service.order.OrderAdmissionService.requiredReservationPrepared(this, lane, userId, command,
                openInterestSteps, clientKey, positionKey, lifecycleSettled, fundingInProgress);
        placePreparedOrder(userId, command, commandId, required, clientKey, command.symbolId(), assetId);
        lane.markPendingReservation(command.orderId(), coreSequence);
        com.surprising.aeron.service.order.Order receipt = laneDelta(lane.laneId()).orders().get(command.orderId());
        if (receipt == null) throw new IllegalStateException("trigger admission receipt is missing");
        return receipt.copyIntoCommandSlot(null);
    }

    /** 在当前账户范围读取权威父单并应用算法父单命令更新。 */
    public void upsertAlgoOrder(com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry identities,
                               long userId, com.surprising.aeron.protocol.CoreAlgoOrderCommand command, long clusterTimestamp) {
        if (identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        var current = algoOrder(command.algoOrderId());
        int symbolId = -1;
        if (command instanceof com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create create) symbolId = identities.symbolId(create.instrumentId());
        else if (current != null) symbolId = identities.symbolId(current.instrumentId());
        upsertAlgoOrder(userId, command, symbolId, clusterTimestamp);
    }

    /** 新建只接收意图。 */
    public void upsertAlgoOrder(long userId, com.surprising.aeron.protocol.CoreAlgoOrderCommand command,
                               int symbolId, long clusterTimestamp) {
        if (command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid prepared algo update");
        }
        assertOwner();
        com.surprising.aeron.model.CoreAlgoOrderState current = algoOrder(command.algoOrderId());
        if (current != null && current.userId() != userId) {
            throw new com.surprising.aeron.exception.CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        com.surprising.aeron.model.CoreAlgoOrderState next;
        switch (command) {
            case com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create create -> {
                if (current != null) throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE",
                        "algo order already exists");
                if (symbolId < 0 || clusterTimestamp <= 0) throw new IllegalArgumentException("invalid algo creation context");
                if (create.clientAlgoOrderId().isBlank()) {
                    throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
                }
                if (hasAlgoClient(userId, create.clientAlgoOrderId())) {
                    throw new com.surprising.aeron.exception.CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                            "clientAlgoOrderId already exists");
                }
                long start = Math.max(create.startAtEpochMillis(), clusterTimestamp);
                next = new com.surprising.aeron.model.CoreAlgoOrderState(create.algoOrderId(), userId, create.clientAlgoOrderId(),
                        create.instrumentId(), create.algoTypeCode(), create.side(), create.priceTicks(),
                        create.quantitySteps(), create.childQuantitySteps(), create.intervalSeconds(),
                        create.durationSeconds(),
                        create.marginMode(), create.positionSide(), create.reduceOnly(),
                        create.timeInForce(),
                        0, 0, "", start, start, clusterTimestamp, clusterTimestamp, 1, java.util.List.of());
            }
            case com.surprising.aeron.protocol.CoreAlgoOrderCommand.Update update -> {
                if (current == null) throw new com.surprising.aeron.exception.CoreStateRejectedException("ENTITY_NOT_FOUND", "algo order is missing");
                if (symbolId < 0) throw new IllegalArgumentException("invalid algo update symbol");
                if (current.terminal() || current.statusCode() == 2 && update.statusCode() < 2) {
                    throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "terminal or cancel-requested algo cannot resume execution");
                }
                if (update.expectedRevision() < current.revision()) {
                    throw new com.surprising.aeron.exception.CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
                }
                if (update.expectedRevision() != current.revision()) {
                    throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "algo order revision is not monotonic");
                }
                java.util.List<Long> children = current.childOrderIds();
                long pending = current.pendingClosedChildId();
                long appended = update.appendedChildOrderId();
                if (appended != 0) {
                    var child = order(appended);
                    if ((child == null && pending != appended)
                            || (child != null && (child.userId() != userId || child.symbolId() != symbolId
                            || !("algo-" + current.algoOrderId() + "-" + children.size()).equals(child.clientOrderId())))) {
                        throw new com.surprising.aeron.exception.CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child order is not authoritative");
                    }
                    var appendedChildren = new java.util.ArrayList<>(children);
                    appendedChildren.add(appended);
                    children = appendedChildren;
                    if (pending == appended) pending = 0;
                }
                next = new com.surprising.aeron.model.CoreAlgoOrderState(current.algoOrderId(), current.userId(), current.clientAlgoOrderId(),
                        current.instrumentId(), current.algoTypeCode(), current.side(), current.priceTicks(),
                        current.quantitySteps(), current.childQuantitySteps(), current.intervalSeconds(),
                        current.durationSeconds(),
                        current.marginMode(), current.positionSide(), current.reduceOnly(),
                        current.timeInForce(),
                        update.statusCode(), update.currentOrderId(), update.rejectReason(), current.startAtEpochMillis(),
                        update.nextSliceAtEpochMillis(), current.createdAtEpochMillis(),
                        update.updatedAtEpochMillis(), Math.incrementExact(current.revision()), children,
                        current.retiredExecutedQuantitySteps(), pending);
            }
        }
        requireRevisionCapacity();
        putAlgoOrder(next);
        incrementCommandRevision();
    }

    public void upsertTriggerOrder(long userId,
                                   CoreTriggerOrderStateView view, int symbolId,
                                   long positionKey, boolean instrumentSettled) {
        triggerOps.upsertTriggerOrder(userId, view, symbolId, positionKey, instrumentSettled);
    }

    public void placeOcoPair(long userId,
                             CoreTriggerOrderStateView takeProfit, CoreTriggerOrderStateView stopLoss,
                             int symbolId, long positionKey, boolean instrumentSettled) {
        triggerOps.placeOcoPair(userId, takeProfit, stopLoss, symbolId, positionKey, instrumentSettled);
    }

    public boolean cancelTriggerOrder(long userId, long triggerOrderId) {
        return triggerOps.cancelTriggerOrder(userId, triggerOrderId);
    }

    public boolean cancelPendingTriggerOrderForCommit(long triggerId) {
        return triggerOps.cancelPendingTriggerOrderForCommit(triggerId);
    }

    public boolean claimTriggerOrder(long triggerOrderId, long triggerSequence,
                                    long triggeredPriceTicks, long triggeredAtEpochMillis) {
        return triggerOps.claimTriggerOrder(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis);
    }

    public boolean completeTriggerOrder(long triggerOrderId, boolean success,
                                       long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        return triggerOps.completeTriggerOrder(triggerOrderId, success, placedOrderId, rejectReason, completedAtEpochMillis);
    }

    public boolean updateTrailingTriggerOrder(long triggerOrderId,
                                             long highestPriceTicks, long lowestPriceTicks,
                                             long activatedAtEpochMillis) {
        return triggerOps.updateTrailingTriggerOrder(triggerOrderId, highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis);
    }

    public boolean expireTriggerOrder(long triggerOrderId, long expiredAtEpochMillis) {
        return triggerOps.expireTriggerOrder(triggerOrderId, expiredAtEpochMillis);
    }

    public boolean retryTriggerOrder(long triggerOrderId,
                                    long staleBeforeEpochMillis, long retryAtEpochMillis) {
        return triggerOps.retryTriggerOrder(triggerOrderId, staleBeforeEpochMillis, retryAtEpochMillis);
    }
}
