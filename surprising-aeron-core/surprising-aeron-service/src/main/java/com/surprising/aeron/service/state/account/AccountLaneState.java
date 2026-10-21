package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.admission.AdmissionOrderIndex;
import com.surprising.aeron.service.state.admission.AdmissionSummary;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.order.ReservationRuntime;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import com.surprising.aeron.service.state.risk.RiskLaneProcessor;
import com.surprising.aeron.service.state.risk.RiskLiquidationBatch;
import com.surprising.aeron.service.state.settlement.MatcherSettlementChanges;
import com.surprising.aeron.service.state.settlement.RuntimeDerivativeFillCalculator;
import com.surprising.aeron.service.state.settlement.RuntimeDerivativeMatchProcessor;
import com.surprising.aeron.service.state.settlement.RuntimeSpotMatchProcessor;
import com.surprising.aeron.service.state.snapshot.AccountLaneSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class AccountLaneState {
    // 仅指标查询使用原子不透明读；不增加Lane热路径发布、锁或账户副本。
    private static final java.lang.invoke.VarHandle METRIC_REVISION = metricField("revision");
    private static final java.lang.invoke.VarHandle METRIC_APPLIED = metricField("appliedSequence");
    private static final java.lang.invoke.VarHandle METRIC_COMMITTED = metricField("committedSequence");
    private static final java.lang.invoke.VarHandle METRIC_COMMANDS = metricField("commandOperations");
    private static final java.lang.invoke.VarHandle METRIC_SETTLEMENTS = metricField("matcherSettlementOperations");

    private static java.lang.invoke.VarHandle metricField(String name) {
        try { return java.lang.invoke.MethodHandles.lookup().findVarHandle(AccountLaneState.class, name, long.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static final int INITIAL_ENTITY_CAPACITY = Math.max(16,
            Integer.getInteger("surprising.aeron.lane-initial-entities", 1_024));
    private final int laneId;
    private final int queueCapacity;
    /** 仅当前批量准入的 Lane 写入；新建用户订单索引一次按已知批量大小分配，离开作用域恢复。 */
    int admissionIndexCapacity = 2;
    private final LongHashSet userIds = new LongHashSet(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<UserRuntime> users = new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balances =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    // Back-shift deletion avoids tombstone-triggered whole-table allocation under order churn.
    final Long2ObjectHashMap<OrderRuntime> orders =
            new Long2ObjectHashMap<>(INITIAL_ENTITY_CAPACITY, 0.65f, false);
    // Lane-owned membership uses back-shift deletion; unique order IDs do not accumulate tombstones.
    final LongObjectHashMap<org.agrona.collections.LongHashSet> activeOrderIdsByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final Long2ObjectHashMap<ReservationRuntime> reservations =
            new Long2ObjectHashMap<>(INITIAL_ENTITY_CAPACITY, 0.65f, false);
    final LongObjectHashMap<org.agrona.collections.LongHashSet> reservationIdsByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<LongHashSet> positionKeysByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    /** Risk scan output is reused per logical Lane, including synchronous multi-Lane callers. */
    RiskLiquidationBatch riskLiquidationBatch;
    /** Open position keys stay sorted in primitive storage; scans no longer build a boxed TreeSet. */
    final IntObjectHashMap<LongObjectHashMap<LongArrayList>> positionKeysBySymbolAndUser
            = new IntObjectHashMap<>();
    final LongObjectHashMap<Long2LongHashMap> clientOrderIndex =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LaneClientIdentities clientIdentities = new LaneClientIdentities(this);
    final OrderClientKeyIndex clientKeysByOrderId = new OrderClientKeyIndex();
    /** 风险、清算、杠杆、算法单和触发单等低频结构与热状态分离。 */
    final LaneColdState cold = new LaneColdState();
    final TradingRuntimeState.LeverageLookup leverageLookup = new TradingRuntimeState.LeverageLookup();
    final RuntimeIdentityRegistry.LookupScratch identityLookupScratch = new RuntimeIdentityRegistry.LookupScratch();
    /** The worker borrows exactly one command receipt while advancing its users. */
    private LaneCommitDelta userRevisionChanges;
    private final org.eclipse.collections.api.block.procedure.primitive.LongLongProcedure advanceUserRevision =
            this::advanceUserRevision;
    /** Borrowed only while this Lane releases a terminal order's client aliases. */
    private RuntimeIdentityRegistry releasingClientIdentities;
    private long releasingClientUser;
    private final org.eclipse.collections.api.block.procedure.primitive.LongProcedure releaseClientIdentity =
            this::releaseClientIdentity;
    private Long2LongHashMap unlinkingClientOrders;
    private long unlinkingOrderId;
    private final org.eclipse.collections.api.block.procedure.primitive.LongProcedure unlinkClientOrder =
            this::unlinkClientOrder;
    final RuntimeDerivativeMatchProcessor.DerivativeSettlementAccumulator derivativeSettlementAccumulator =
            new RuntimeDerivativeMatchProcessor.DerivativeSettlementAccumulator();
    final RuntimeSpotMatchProcessor.SpotSettlementAccumulator spotSettlementAccumulator =
            new RuntimeSpotMatchProcessor.SpotSettlementAccumulator();
    final RiskLaneProcessor.PositionRiskScratch positionRiskScratch = new RiskLaneProcessor.PositionRiskScratch();
    final RuntimeDerivativeFillCalculator.FillCursor singleFillCursor = new RuntimeDerivativeFillCalculator.FillCursor();

    private volatile int queueHighWaterMark;

    public int updateQueueHighWaterMark(int depth) {
        int current = queueHighWaterMark;
        if (depth > current) {
            queueHighWaterMark = depth;
            return depth;
        }
        return current;
    }

    public int queueHighWaterMark() {
        return queueHighWaterMark;
    }

    public CoreTriggerOrderState putTrigger(CoreTriggerOrderState value) {
        CoreTriggerOrderState previous = cold.triggerOrders.put(value.triggerOrderId(), value);
        if (previous != null && previous.userId() != value.userId()) unindexTrigger(previous);
        cold.triggerIdsByUser.getIfAbsentPut(value.userId(), LongHashSet::new).add(value.triggerOrderId());
        return previous;
    }

    public CoreTriggerOrderState removeTrigger(long id) {
        CoreTriggerOrderState previous = cold.triggerOrders.remove(id);
        if (previous != null) unindexTrigger(previous);
        return previous;
    }

    private void unindexTrigger(CoreTriggerOrderState value) {
        LongHashSet ids = cold.triggerIdsByUser.get(value.userId());
        if (ids == null) throw new IllegalStateException("trigger user index is missing");
        ids.remove(value.triggerOrderId());
        if (ids.isEmpty()) cold.triggerIdsByUser.remove(value.userId());
    }
    // Positive sequences use zero as absent. Back-shift deletion retains the backing table
    // through repeated admission/settlement churn instead of rebuilding tombstones.
    final Long2LongHashMap pendingReservationSequences =
            new Long2LongHashMap(INITIAL_ENTITY_CAPACITY, 0.65f, 0, false);
    private final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(INITIAL_ENTITY_CAPACITY);
    /** One reusable table per asset; completed users are removed, capacity survives ordinary churn. */
    private final IntObjectHashMap<LongLongHashMap> pendingReservedUnitsByAsset = new IntObjectHashMap<>();
    private int totalPendingReservations;
    /** 该Lane新建客户订单身份的累计数量；完成收据由Owner汇入字典版本。 */
    long clientIdentityAllocations;
    private long revision;
    private long appliedSequence;
    private long committedSequence;
    private long commandOperations;
    private long matcherSettlementOperations;
    private long localStateHash = 0xcbf29ce484222325L;
    private long localFundsHash = 0xcbf29ce484222325L;
    /** Hashes are rebuilt on the lane owner; retain sort buffers across commits. */
    private long[] hashLongScratch = new long[16];
    private int[] hashIntScratch = new int[16];
    private final LaneAdmissionOrderIndex admissionOrderIndex = new LaneAdmissionOrderIndex();
    boolean commandScopeActive;
    boolean queryScopeActive;
    MatcherSettlementChanges matcherSettlementChanges;

    public AccountLaneState(int laneId, int queueCapacity) {
        if (laneId < 0 || laneId >= Long.SIZE || queueCapacity <= 0) {
            throw new IllegalArgumentException("invalid account lane");
        }
        this.laneId = laneId;
        this.queueCapacity = queueCapacity;
        this.localStateHash = computeStateHash();
        this.localFundsHash = computeFundsHash();
    }

    /** Owner returns detached orders; the Lane waits for the final command-retirement fence. */
    private final org.agrona.concurrent.OneToOneConcurrentArrayQueue<OrderRuntime> retiredOrders =
            new org.agrona.concurrent.OneToOneConcurrentArrayQueue<>(4_096);
    private final java.util.ArrayDeque<OrderRuntime> reusableOrders = new java.util.ArrayDeque<>();

    public void returnRetiredOrder(OrderRuntime order) {
        // Retention is bounded. A full recycling cache drops its detached value, never business work.
        retiredOrders.offer(order);
    }

    public OrderRuntime preparedOrder(com.surprising.product.api.ProductLine product, long userId, ResolvedPlaceOrder intent,
            java.util.UUID commandId, int symbolId, long timestamp, long position, long retiredOwnerSequence) {
        assertOwner();
        OrderRuntime ready;
        while ((ready = retiredOrders.peek()) != null && ready.retiredAfterSequence() <= retiredOwnerSequence) {
            retiredOrders.poll();
            ready.clearRetiredReferences();
            if (reusableOrders.size() < 4_096) reusableOrders.addLast(ready);
        }
        return OrderRuntime.preparePooled(reusableOrders.pollLast(), product, userId, intent, commandId, symbolId, timestamp, position);
    }

    private volatile Thread owner;
    Thread owner() { return owner; }
    private static final VarHandle OWNER;
    static {
        try { OWNER = MethodHandles.lookup().findVarHandle(AccountLaneState.class, "owner", Thread.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }

    public void bindOwner() { assertOwner(); }

    public void applyUserRevisions(LongLongHashMap revisions, LaneCommitDelta changes) {
        assertOwner();
        if (userRevisionChanges != null) throw new IllegalStateException("nested Lane user revision traversal");
        userRevisionChanges = java.util.Objects.requireNonNull(changes, "user revision receipt");
        try { revisions.forEachKeyValue(advanceUserRevision); }
        finally { userRevisionChanges = null; }
    }

    private void advanceUserRevision(long userId, long count) {
        UserRuntime current = users.get(userId);
        if (current == null)
            throw new IllegalStateException("runtime user disappeared during matcher settlement: " + userId);
        long revision = Math.addExact(current.revision(), count);
        UserRuntime advanced = current.laneValue().withState(revision, current.positionMode());
        users.put(userId, advanced);
        userRevisionChanges.putUser(userId, advanced);
    }

    public void releaseOrderClientIdentities(RuntimeIdentityRegistry identities, long orderId, long userId) {
        assertOwner();
        if (releasingClientIdentities != null)
            throw new IllegalStateException("nested terminal client identity release");
        releasingClientIdentities = java.util.Objects.requireNonNull(identities);
        releasingClientUser = userId;
        try { clientKeysByOrderId.forEach(orderId, releaseClientIdentity); }
        finally { releasingClientIdentities = null; releasingClientUser = 0; }
    }

    private void releaseClientIdentity(long clientKey) {
        releasingClientIdentities.releaseClientKeyInLane(this, releasingClientUser, clientKey);
    }

    public void removeClientOrdersForOrder(long userId, long orderId) {
        assertOwner();
        if (unlinkingClientOrders != null) throw new IllegalStateException("nested client order unlink");
        Long2LongHashMap ordersByClient = clientOrderIndex.get(userId);
        if (ordersByClient != null) {
            unlinkingClientOrders = ordersByClient;
            unlinkingOrderId = orderId;
            try { clientKeysByOrderId.forEach(orderId, unlinkClientOrder); }
            finally { unlinkingClientOrders = null; unlinkingOrderId = 0; }
        }
        clientKeysByOrderId.remove(orderId);
    }

    private void unlinkClientOrder(long clientKey) {
        if (unlinkingClientOrders.get(clientKey) == unlinkingOrderId) unlinkingClientOrders.remove(clientKey);
    }

    /** Bind the actual worker before it starts; no unowned startup window is exposed. */
    public void bindWorker(Thread worker) {
        assertOwner();
        if (worker == null || worker.getState() != Thread.State.NEW)
            throw new IllegalArgumentException("Account Lane worker must not have started");
        owner = worker;
    }

    public void releaseOwnerForHandoff() {
        assertOwner();
        owner = null;
    }

    public void assertOwner() {
        Thread current = Thread.currentThread();
        Thread bound = owner;
        if (bound == null && OWNER.compareAndSet(this, null, current)) return;
        if (owner != current)
            throw new IllegalStateException("account lane " + laneId + " is bound to another thread");
    }

    public void recordCommandOperation() {
        commandOperations++;
    }

    public void recordSettlementOperation() {
        matcherSettlementOperations++;
    }

    private static final long[] EMPTY_METRIC_OPERATIONS = new long[com.surprising.aeron.protocol.CoreLaneMetricsView.OPERATION_TYPE_COUNT];

    /** Owner可观察原子原语计数，各字段允许独立滞后；没有账户读取或Lane作用域。 */
    public void writeMetrics(com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder,
                      int depth, int capacity, int highWater) {
        long committed = (long) METRIC_COMMITTED.getOpaque(this);
        long applied = Math.max(committed, (long) METRIC_APPLIED.getOpaque(this));
        encoder.writeLane(laneId, (long) METRIC_REVISION.getOpaque(this), applied, committed,
                depth, capacity, highWater, 0, 0);
        encoder.writeOperations(laneId, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS, EMPTY_METRIC_OPERATIONS);
        long commands = (long) METRIC_COMMANDS.getOpaque(this);
        if (commands != 0) {
            encoder.addOperation(laneId, 0, commands, 0, 0, 0);
        }
        long settlements = (long) METRIC_SETTLEMENTS.getOpaque(this);
        if (settlements != 0) {
            encoder.addOperation(laneId, 1, settlements, 0, 0, 0);
        }
    }

    public int laneId() { return laneId; }
    public int queueCapacity() { return queueCapacity; }
    public long revision() { assertOwner(); return revision; }
    public long appliedSequence() { assertOwner(); return appliedSequence; }
    public long committedSequence() { assertOwner(); return committedSequence; }
    public long localStateHash() { assertOwner(); return localStateHash; }
    public long localFundsHash() { assertOwner(); return localFundsHash; }
    public boolean owns(long userId) { assertOwner(); return userIds.contains(userId); }
    public int userCount() { assertOwner(); return userIds.size(); }

    public void registerUser(long userId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        userIds.add(userId);
    }

    public void removeUser(long userId) {
        assertOwner();
        userIds.remove(userId);
        admissionOrderIndex.removeUnusedUser(userId);
    }

    public void markPendingReservation(long orderId, long coreSequence) {
        assertOwner();
        if (orderId <= 0 || coreSequence <= committedSequence) {
            throw new IllegalArgumentException("invalid pending reservation identity");
        }
        if (pendingReservationSequences.containsKey(orderId)) {
            throw new IllegalStateException("reservation is already pending");
        }
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int nextUserCount = Math.addExact(pendingReservationCountsByUser.get(reservation.userId()), 1);
        int nextTotal = Math.addExact(totalPendingReservations, 1);
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long nextUnits = Math.addExact(previousUnits, reservation.reservedUnits());
        pendingReservationSequences.put(orderId, coreSequence);
        pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    public void completePendingReservation(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, true);
    }

    public void requirePendingReservationCompletion(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, false);
    }

    /** Validate all counters before writing; validation and application share no temporary state object. */
    private void pendingReservationCompletion(long orderId, long coreSequence, boolean apply) {
        assertOwner();
        long pendingSequence = pendingReservationSequences.get(orderId);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int userCount = pendingReservationCountsByUser.get(reservation.userId());
        int nextUserCount = Math.max(0, userCount - 1);
        int nextTotal = Math.max(0, totalPendingReservations - 1);
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long nextUnits = Math.max(0L, previousUnits - reservation.reservedUnits());
        if (!apply) return;
        pendingReservationSequences.remove(orderId);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(reservation.userId());
        else pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    public void replacePendingReservation(ReservationRuntime previous, ReservationRuntime replacement) {
        assertOwner();
        if (!pendingReservationSequences.containsKey(previous.orderId())) return;
        if (previous.orderId() != replacement.orderId() || previous.userId() != replacement.userId()) {
            throw new IllegalStateException("pending reservation owner cannot change");
        }
        long currentUnits = pendingReservedUnits(previous.userId(), previous.assetId());
        long remainingUnits = Math.subtractExact(currentUnits, previous.reservedUnits());
        if (remainingUnits < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        if (previous.assetId() == replacement.assetId()) {
            long nextUnits = Math.addExact(remainingUnits, replacement.reservedUnits());
            setPendingReservedUnits(previous.userId(), previous.assetId(), nextUnits);
        } else {
            long replacementUnits = Math.addExact(
                    pendingReservedUnits(previous.userId(), replacement.assetId()), replacement.reservedUnits());
            // Validate both assets before changing either side, including rollback replacements.
            setPendingReservedUnits(previous.userId(), previous.assetId(), remainingUnits);
            setPendingReservedUnits(previous.userId(), replacement.assetId(), replacementUnits);
        }
    }

    public boolean pendingReservation(long orderId) {
        assertOwner();
        return pendingReservationSequences.containsKey(orderId);
    }

    public long pendingReservationSequence(long orderId) {
        assertOwner();
        return pendingReservationSequences.get(orderId);
    }

    public void updatePendingReservationInPlace(ReservationRuntime reservation, long previousReservedUnits) {
        assertOwner();
        if (reservation == null || previousReservedUnits < 0) throw new IllegalArgumentException("invalid reservation update");
        if (!pendingReservationSequences.containsKey(reservation.orderId())) return;
        long current = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long next = Math.addExact(Math.subtractExact(current, previousReservedUnits), reservation.reservedUnits());
        if (next < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), next);
    }

    public long pendingReservedUnits(long userId, int assetId) {
        LongLongHashMap unitsByUser = pendingReservedUnitsByAsset.get(assetId);
        return unitsByUser == null ? 0 : unitsByUser.get(userId);
    }

    private void setPendingReservedUnits(long userId, int assetId, long units) {
        LongLongHashMap users = pendingReservedUnitsByAsset.get(assetId);
        if (units == 0) {
            if (users != null) users.removeKey(userId);
        } else {
            if (users == null) {
                users = new LongLongHashMap();
                pendingReservedUnitsByAsset.put(assetId, users);
            }
            users.put(userId, units);
        }
    }

    public int pendingReservationCount(long userId) {
        assertOwner();
        return pendingReservationCountsByUser.get(userId);
    }

    public int pendingReservationCount() {
        assertOwner();
        return totalPendingReservations;
    }

    boolean hasPendingReservations() {
        assertOwner();
        return totalPendingReservations != 0;
    }

    /** The account owner selects self-trade cancellations from its own live orders. */
    public java.util.List<Long> preMatchingCancellations(long userId, int symbolId,
            com.surprising.aeron.protocol.CoreOrderSide side, long matchingPrice,
            long excludedOrderId, long newOrderId, java.util.List<Long> closeCapacity) {
        assertOwner();
        var active = activeOrderIdsByUser.get(userId);
        if (active == null || active.isEmpty()) return closeCapacity;
        LongHashSet cancellations = null;
        var iterator = active.iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextValue();
            if (id == excludedOrderId || id == newOrderId || pendingReservation(id)) continue;
            OrderRuntime order = orders.get(id);
            if (order == null || order.symbolId() != symbolId || order.side() == side) continue;
            boolean crosses = side == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? matchingPrice >= order.priceTicks() : matchingPrice <= order.priceTicks();
            if (crosses) {
                if (cancellations == null) {
                    cancellations = new LongHashSet();
                    for (long closeId : closeCapacity) cancellations.add(closeId);
                }
                cancellations.add(id);
            }
        }
        if (cancellations == null) return closeCapacity;
        long[] ids = cancellations.toArray();
        java.util.Arrays.sort(ids);
        return com.surprising.aeron.service.command.ImmutableLongArrayList.takeOwnership(ids);
    }

    public AdmissionOrderIndex admissionOrderIndex(int symbolId) {
        assertOwner();
        if (symbolId < 0) throw new IllegalArgumentException("invalid admission instrumentId");
        admissionOrderIndex.symbolId = symbolId;
        return admissionOrderIndex;
    }

    public void putOrder(OrderRuntime order) {
        assertOwner();
        if (order == null) throw new IllegalArgumentException("order is required");
        order = order.laneValue();
        OrderRuntime previous = orders.put(order.orderId(), order);
        replaceActiveOrder(previous, order);
        admissionOrderIndex.replace(previous, order);
    }

    public OrderRuntime removeOrder(long orderId) {
        assertOwner();
        OrderRuntime previous = orders.remove(orderId);
        replaceActiveOrder(previous, null);
        admissionOrderIndex.replace(previous, null);
        return previous;
    }

    /**
     * Applies execution fields without replacing the Lane-owned object.  The two compact
     * indexes are updated from primitive before-values, so a fill allocates no intermediate
     * OrderRuntime value.
     */
    public OrderRuntime updateOrderInPlace(long orderId, long executed, long remaining, long feeUnits,
                                    CoreOrderStatus nextStatus, long nextRevision,
                                    long commitTimestamp, long commitPosition) {
        assertOwner();
        OrderRuntime order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        boolean previousActive = active(order);
        long previousRemaining = order.remainingQuantitySteps();
        order.applyFillInPlace(executed, remaining, feeUnits, nextStatus, nextRevision,
                commitTimestamp, commitPosition);
        if (previousActive && !active(order)) removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && active(order)) addActiveOrder(order.userId(), order.orderId());
        admissionOrderIndex.updateInPlace(order, previousActive, previousRemaining);
        return order;
    }

    public OrderRuntime updateOrderStatusInPlace(long orderId, CoreOrderStatus nextStatus, long nextRevision,
                                          long commitTimestamp, long commitPosition) {
        OrderRuntime order = orders.get(orderId);
        if (order == null) throw new IllegalArgumentException("runtime order is not registered: " + orderId);
        boolean previousActive = active(order);
        order.applyStatusInPlace(nextStatus, nextRevision, commitTimestamp, commitPosition);
        if (previousActive && !active(order)) removeActiveOrder(order.userId(), order.orderId());
        else if (!previousActive && active(order)) addActiveOrder(order.userId(), order.orderId());
        admissionOrderIndex.updateInPlace(order, previousActive, order.remainingQuantitySteps());
        return order;
    }

    public long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) return 0;
        long total = 0;
        var iterator = orderIds.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            OrderRuntime order = orders.get(orderId);
            if (order != null && order.symbolId() == symbolId && order.reduceOnly()
                    && order.positionSide() == positionSide && order.side() == side
                    && order.marginMode() == marginMode) {
                total = Math.addExact(total, order.remainingQuantitySteps());
            }
        }
        return total;
    }

    private void replaceActiveOrder(OrderRuntime previous, OrderRuntime replacement) {
        // Quantity/status updates within the active set do not change its membership.
        if (active(previous) && active(replacement) && previous.userId() == replacement.userId()) return;
        if (active(previous)) removeActiveOrder(previous.userId(), previous.orderId());
        if (active(replacement)) addActiveOrder(replacement.userId(), replacement.orderId());
    }

    private void addActiveOrder(long userId, long orderId) {
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) {
            orderIds = new org.agrona.collections.LongHashSet(admissionIndexCapacity, 0.65f, true);
            activeOrderIdsByUser.put(userId, orderIds);
        }
        orderIds.add(orderId);
    }

    private void removeActiveOrder(long userId, long orderId) {
        var orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null || !orderIds.remove(orderId)) {
            throw new IllegalStateException("active order index is missing");
        }
        // Keep the primitive container while the user exists. Terminal-to-next-order churn is
        // the common path; removing it here only replaces a cleared table with a new allocation.
    }

    public void addReservationId(long userId, long orderId) {
        var ids = reservationIdsByUser.get(userId);
        if (ids == null) {
            ids = new org.agrona.collections.LongHashSet(admissionIndexCapacity, 0.65f, true);
            reservationIdsByUser.put(userId, ids);
        }
        ids.add(orderId);
    }

    public void removeReservationId(long userId, long orderId) {
        var ids = reservationIdsByUser.get(userId);
        if (ids != null) ids.remove(orderId);
        // Retain the user's existing container, just like active order membership.
    }

    private static boolean active(OrderRuntime order) {
        return order != null && !order.status().terminal();
    }

    private final class LaneAdmissionOrderIndex implements AdmissionOrderIndex {
        private final Long2ObjectHashMap<Int2ObjectHashMap<AdmissionAggregate>> summariesByUser =
                new Long2ObjectHashMap<>();
        /** Detached zero-valued cells contain no user or instrument identity; only this Lane reuses them. */
        private final java.util.ArrayDeque<AdmissionAggregate> emptyAggregates = new java.util.ArrayDeque<>();
        private final AdmissionSummary summary = new AdmissionSummary();
        private int symbolId;

        @Override
        public AdmissionSummary inspect(
                long userId, String instrumentId, CorePositionSide positionSide,
                CoreOrderSide side, CoreMarginMode conflictingMarginMode) {
            Int2ObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(userId);
            AdmissionAggregate aggregate = bySymbol == null ? null : bySymbol.get(symbolId);
            return aggregate == null ? summary.set(0, 0, 0)
                    : summary.set(aggregate.pending(positionSide, side), aggregate.reduceOnly(side),
                    aggregate.marginMode(positionSide, conflictingMarginMode));
        }

        private void updateInPlace(OrderRuntime order, boolean previousActive, long previousRemaining) {
            boolean nextActive = active(order);
            if (previousActive && nextActive) {
                var bySymbol = summariesByUser.get(order.userId());
                var aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
                if (aggregate == null) throw new IllegalStateException("admission order index is missing");
                aggregate.replaceQuantity(previousRemaining, order.remainingQuantitySteps(), order);
                return;
            }
            if (previousActive) update(order, -1, previousRemaining);
            if (nextActive) update(order, 1, order.remainingQuantitySteps());
        }

        private void replace(OrderRuntime previous, OrderRuntime replacement) {
            if (active(previous) && active(replacement)
                    && previous.userId() == replacement.userId() && previous.symbolId() == replacement.symbolId()
                    && previous.side() == replacement.side() && previous.positionSide() == replacement.positionSide()
                    && previous.marginMode() == replacement.marginMode() && previous.reduceOnly() == replacement.reduceOnly()) {
                var bySymbol = summariesByUser.get(previous.userId());
                var aggregate = bySymbol == null ? null : bySymbol.get(previous.symbolId());
                if (aggregate == null) throw new IllegalStateException("admission order index is missing");
                aggregate.replaceQuantity(previous, replacement);
                return;
            }
            if (active(previous)) update(previous, -1);
            if (active(replacement)) update(replacement, 1);
        }

        private void update(OrderRuntime order, int direction) {
            update(order, direction, order.remainingQuantitySteps());
        }

        private void update(OrderRuntime order, int direction, long remainingQuantity) {
            Int2ObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(order.userId());
            AdmissionAggregate aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
            if (aggregate == null) {
                if (direction < 0) throw new IllegalStateException("admission order index is missing");
                aggregate = emptyAggregates.pollLast();
                if (aggregate == null) aggregate = new AdmissionAggregate();
                aggregate.update(order, direction, remainingQuantity);
                bySymbol = bySymbol == null ? new Int2ObjectHashMap<>(8, 0.65f, false) : bySymbol;
                bySymbol.put(order.symbolId(), aggregate);
                summariesByUser.put(order.userId(), bySymbol);
                return;
            }
            aggregate.update(order, direction, remainingQuantity);
            if (aggregate.empty()) {
                bySymbol.remove(order.symbolId());
                if (emptyAggregates.size() < 256) emptyAggregates.addLast(aggregate);
                removeUnusedUser(order.userId());
            }
        }

        private void removeUnusedUser(long userId) {
            var bySymbol = summariesByUser.get(userId);
            // Keep only the empty table while its actual user exists, as for order membership.
            // If user removal precedes order cleanup, the last order releases the table instead.
            if (bySymbol != null && bySymbol.isEmpty() && !userIds.contains(userId)) summariesByUser.remove(userId);
        }

    }

    private static final class AdmissionAggregate {
        private long netBuy;
        private long netSell;
        private long longBuy;
        private long longSell;
        private long shortBuy;
        private long shortSell;
        private long reduceBuy;
        private long reduceSell;
        private int netCross;
        private int netIsolated;
        private int longCross;
        private int longIsolated;
        private int shortCross;
        private int shortIsolated;
        private int orders;

        void replaceQuantity(OrderRuntime previous, OrderRuntime replacement) {
            replaceQuantity(previous.remainingQuantitySteps(), replacement.remainingQuantitySteps(), previous);
        }

        private void replaceQuantity(long previousRemaining, long nextRemaining, OrderRuntime grouping) {
            // Grouping is unchanged for in-place execution updates. The caller supplies the
            // current order only when the old record is still available.
            CoreOrderSide side = grouping == null ? null : grouping.side();
            CorePositionSide positionSide = grouping == null ? null : grouping.positionSide();
            boolean reduce = grouping != null && grouping.reduceOnly();
            if (grouping == null) throw new IllegalStateException("in-place admission grouping is missing");
            long current = reduce ? reduceOnly(side) : pending(positionSide, side);
            long remaining = Math.subtractExact(current, previousRemaining);
            if (remaining < 0) throw new IllegalStateException("admission order index underflow");
            long next = Math.addExact(remaining, nextRemaining);
            if (reduce) assignReduceOnly(side, next);
            else assignPending(positionSide, side, next);
        }

        private void update(OrderRuntime order, int direction, long remainingQuantity) {
            long quantityDelta = direction > 0 ? remainingQuantity : Math.negateExact(remainingQuantity);
            long currentQuantity = order.reduceOnly()
                    ? reduceOnly(order.side())
                    : pending(order.positionSide(), order.side());
            long nextQuantity = Math.addExact(currentQuantity, quantityDelta);
            int nextMargin = Math.addExact(
                    marginMode(order.positionSide(), order.marginMode()), direction);
            int nextOrders = Math.addExact(orders, direction);
            if (nextQuantity < 0 || nextMargin < 0 || nextOrders < 0) {
                throw new IllegalStateException("admission order index underflow");
            }
            if (order.reduceOnly()) assignReduceOnly(order.side(), nextQuantity);
            else assignPending(order.positionSide(), order.side(), nextQuantity);
            assignMarginMode(order.positionSide(), order.marginMode(), nextMargin);
            orders = nextOrders;
        }

        long pending(CorePositionSide positionSide, CoreOrderSide side) {
            return switch (positionSide) {
                case NET -> side == CoreOrderSide.BUY ? netBuy : netSell;
                case LONG -> side == CoreOrderSide.BUY ? longBuy : longSell;
                case SHORT -> side == CoreOrderSide.BUY ? shortBuy : shortSell;
            };
        }

        long reduceOnly(CoreOrderSide side) {
            return side == CoreOrderSide.BUY ? reduceBuy : reduceSell;
        }

        int marginMode(CorePositionSide positionSide, CoreMarginMode marginMode) {
            return switch (positionSide) {
                case NET -> marginMode == CoreMarginMode.CROSS ? netCross : netIsolated;
                case LONG -> marginMode == CoreMarginMode.CROSS ? longCross : longIsolated;
                case SHORT -> marginMode == CoreMarginMode.CROSS ? shortCross : shortIsolated;
            };
        }

        boolean empty() { return orders == 0; }

        private void assignPending(CorePositionSide positionSide, CoreOrderSide side, long quantity) {
            switch (positionSide) {
                case NET -> { if (side == CoreOrderSide.BUY) netBuy = quantity; else netSell = quantity; }
                case LONG -> { if (side == CoreOrderSide.BUY) longBuy = quantity; else longSell = quantity; }
                case SHORT -> { if (side == CoreOrderSide.BUY) shortBuy = quantity; else shortSell = quantity; }
            }
        }

        private void assignReduceOnly(CoreOrderSide side, long quantity) {
            if (side == CoreOrderSide.BUY) reduceBuy = quantity;
            else reduceSell = quantity;
        }

        private void assignMarginMode(CorePositionSide positionSide, CoreMarginMode marginMode, int count) {
            switch (positionSide) {
                case NET -> { if (marginMode == CoreMarginMode.CROSS) netCross = count; else netIsolated = count; }
                case LONG -> { if (marginMode == CoreMarginMode.CROSS) longCross = count; else longIsolated = count; }
                case SHORT -> { if (marginMode == CoreMarginMode.CROSS) shortCross = count; else shortIsolated = count; }
            }
        }
    }

    public void applied(long coreSequence) {
        assertOwner();
        requireApplySequence(coreSequence);
        appliedSequence = coreSequence;
        revision = Math.incrementExact(revision);
    }

    void requireApplySequence(long coreSequence) {
        assertOwner();
        if (coreSequence <= appliedSequence) {
            throw new IllegalStateException("account lane apply is out of order: lane=" + laneId
                    + ", incoming=" + coreSequence + ", applied=" + appliedSequence
                    + ", committed=" + committedSequence);
        }
    }

    public void committed(long coreSequence) {
        assertOwner();
        requireCommit(coreSequence);
        committedSequence = coreSequence;
    }

    void requireCommit(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || coreSequence > appliedSequence) {
            throw new IllegalStateException("account lane commit is out of order");
        }
    }

    public void readFence(long coreSequence) {
        assertOwner();
        requireReadFence(coreSequence);
        advanceReadFence(coreSequence);
    }

    public void requireReadFence(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane read fence crossed uncommitted work");
        }
    }

    public void advanceReadFence(long coreSequence) {
        assertOwner();
        appliedSequence = coreSequence;
        committedSequence = coreSequence;
    }

    public void restore(long revision, long appliedSequence, long committedSequence,
                 long localStateHash, long localFundsHash, LongHashSet restoredUsers) {
        assertOwner();
        if (revision < 0 || appliedSequence < committedSequence || committedSequence < 0
                || localStateHash == 0 || localFundsHash == 0 || restoredUsers == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        this.revision = revision;
        this.appliedSequence = appliedSequence;
        this.committedSequence = committedSequence;
        this.localStateHash = localStateHash;
        this.localFundsHash = localFundsHash;
        userIds.clear();
        userIds.addAll(restoredUsers);
        pendingReservationSequences.clear();
        pendingReservationCountsByUser.clear();
        pendingReservedUnitsByAsset.clear();
        totalPendingReservations = 0;
    }

    LongHashSet userIdsSnapshot() {
        assertOwner();
        return new LongHashSet(userIds);
    }

    public AccountLaneSnapshot snapshot(long fenceSequence) {
        assertOwner();
        requireSnapshot(fenceSequence);
        appliedSequence = fenceSequence;
        committedSequence = fenceSequence;
        java.util.List<Long> users = new java.util.ArrayList<>(userIds.size());
        userIds.forEach(users::add);
        users.sort(Long::compare);
        return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                localStateHash, localFundsHash, users);
    }

    public void requireSnapshot(long fenceSequence) {
        assertOwner();
        if (!pendingReservationSequences.isEmpty()) {
            throw new IllegalStateException("pending reservation cannot cross snapshot fence");
        }
        if (fenceSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane snapshot crossed an incomplete commit");
        }
    }

    public void restore(AccountLaneSnapshot snapshot) {
        assertOwner();
        if (snapshot == null || snapshot.laneId() != laneId) {
            throw new IllegalArgumentException("account lane snapshot route mismatch");
        }
        LongHashSet restoredUsers = new LongHashSet();
        snapshot.userIds().forEach(restoredUsers::add);
        restore(snapshot.revision(), snapshot.appliedSequence(), snapshot.committedSequence(),
                snapshot.localStateHash(), snapshot.localFundsHash(), restoredUsers);
    }

    record MatcherSettlementMetrics(long operations, long totalLatencyNanos, long maxLatencyNanos) {
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    private long computeStateHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long userSum = 0;
        var userIterator = userIds.longIterator();
        while (userIterator.hasNext()) {
            long userId = userIterator.next();
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), mixValue(0, users.get(userId)));
        }
        hash = mix(hash, userSum);
        hash = mix(hash, mixMapUnordered(orders));
        hash = mix(hash, mixMapUnordered(reservations));
        hash = mix(hash, mixMapUnordered(positions));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.liquidations));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.riskSnapshots));
        long leverageSum = 0;
        for (Map.Entry<CoreLeverageKey, Long> entry : cold.leverages.entrySet()) {
            leverageSum += mix(mixValue(0, entry.getKey()), entry.getValue());
        }
        hash = mix(hash, leverageSum);
        hash = mix(hash, mixPrimitiveMapUnordered(cold.algoOrders));
        hash = mix(hash, mixPrimitiveMapUnordered(cold.triggerOrders));
        return hash == 0 ? 1 : hash;
    }

    private <T> long mixPrimitiveMapUnordered(LongObjectHashMap<T> values) {
        long sum = 0;
        var iterator = values.keySet().longIterator();
        while (iterator.hasNext()) {
            long key = iterator.next();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    private long computeFundsHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long userSum = 0;
        var userIterator = balances.keySet().longIterator();
        while (userIterator.hasNext()) {
            long userId = userIterator.next();
            long assetSum = 0;
            IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
            if (userBalances != null) {
                var assetIterator = userBalances.keySet().intIterator();
                while (assetIterator.hasNext()) {
                    int assetId = assetIterator.next();
                    BalanceRuntime balance = userBalances.get(assetId);
                    long itemHash = mix(mix(mix(0x9e3779b97f4a7c15L, assetId), balance.availableUnits()), balance.lockedUnits());
                    assetSum += itemHash;
                }
            }
            userSum += mix(mix(0x9e3779b97f4a7c15L, userId), assetSum);
        }
        hash = mix(hash, userSum);
        return hash == 0 ? 1 : hash;
    }

    public void rebuildLocalHashes() {
        assertOwner();
        localStateHash = computeStateHash();
        localFundsHash = computeFundsHash();
    }

    private long mixMapUnordered(Long2ObjectHashMap<?> values) {
        long sum = 0;
        var iterator = values.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            sum += mix(mix(0x9e3779b97f4a7c15L, key), mixValue(0, values.get(key)));
        }
        return sum;
    }

    private <T> long mixMapUnordered(LongObjectHashMap<T> values) {
        return mixPrimitiveMapUnordered(values);
    }

    private static long mixValue(long hash, Object value) {
        if (value == null) return mix(hash, 0);
        if (value instanceof CharSequence seq) {
            long mixed = mix(hash, seq.length());
            for (int index = 0; index < seq.length(); index++) {
                char character = seq.charAt(index);
                mixed ^= (character & 0xff);
                if (character > 0x7f) {
                    mixed ^= (character >>> 8);
                }
                mixed *= 0x100000001b3L;
            }
            return mixed;
        }
        return mix(hash, Integer.toUnsignedLong(value.hashCode()));
    }

    /** Core 内部业务协作入口；状态仍由原所属线程或有效读取屏障持有。 */
    public RuntimeIdentityRegistry.LookupScratch identityLookupScratch() { return identityLookupScratch; }
    public LaneClientIdentities clientIdentities() { return clientIdentities; }
    public void clientIdentityAllocations(long value) { this.clientIdentityAllocations = value; }
    public long clientIdentityAllocations() { return clientIdentityAllocations; }
    public RiskLiquidationBatch riskLiquidationBatch() { return riskLiquidationBatch; }
    public void riskLiquidationBatch(RiskLiquidationBatch value) { this.riskLiquidationBatch = value; }
    public boolean commandScopeActive() { return commandScopeActive; }
    public void queryScopeActive(boolean value) { this.queryScopeActive = value; }
    public MatcherSettlementChanges matcherSettlementChanges() { return matcherSettlementChanges; }
    public void commandScopeActive(boolean value) { this.commandScopeActive = value; }
    public void matcherSettlementChanges(MatcherSettlementChanges value) { this.matcherSettlementChanges = value; }
    public ReservationRuntime reservation(long key) { return reservations.get(key); }
    public ReservationRuntime removeReservationValue(long key) { return reservations.remove(key); }
    public UserRuntime user(long key) { return users.get(key); }
    public UserRuntime storeUser(long key, UserRuntime value) { return users.put(key, value); }
    public OrderClientKeyIndex clientKeysByOrderId() { return clientKeysByOrderId; }
    public OrderRuntime order(long key) { return orders.get(key); }
    public void forEachUserId(org.eclipse.collections.api.block.procedure.primitive.LongProcedure consumer) { users.forEachKey(consumer); }
    public com.surprising.aeron.service.state.position.PositionRuntime position(long key) { return positions.get(key); }
    public Long leverage(Object key) { return cold.leverages.get(key); }
    public boolean queryScopeActive() { return queryScopeActive; }
    public com.surprising.aeron.service.state.risk.LiquidationRuntime liquidation(long key) { return cold.liquidations.get(key); }
    public boolean anyLiquidation(org.eclipse.collections.api.block.predicate.Predicate<? super com.surprising.aeron.service.state.risk.LiquidationRuntime> predicate) { return cold.liquidations.anySatisfy(predicate); }
    public Iterable<com.surprising.aeron.service.state.risk.LiquidationRuntime> liquidationValuesForRead() { return cold.liquidations.values(); }
    public com.surprising.aeron.service.state.risk.RiskSnapshotRuntime riskSnapshot(long key) { return cold.riskSnapshots.get(key); }
    public int positionCount() { return positions.size(); }
    public Iterable<com.surprising.aeron.service.state.position.PositionRuntime> positionValuesForRead() { return positions.values(); }
    public TradingRuntimeState.LeverageLookup leverageLookup() { return leverageLookup; }
    public Long storeLeverage(CoreLeverageKey key, Long value) { return cold.leverages.put(key, value); }
    public com.surprising.aeron.service.state.model.CoreAlgoOrderState algoOrder(long key) { return cold.algoOrders.get(key); }
    public boolean anyAlgoOrder(org.eclipse.collections.api.block.predicate.Predicate<? super com.surprising.aeron.service.state.model.CoreAlgoOrderState> predicate) { return cold.algoOrders.anySatisfy(predicate); }
    public com.surprising.aeron.service.state.model.CoreAlgoOrderState storeAlgoOrder(long key, com.surprising.aeron.service.state.model.CoreAlgoOrderState value) { return cold.algoOrders.put(key, value); }
    public com.surprising.aeron.service.state.model.CoreAlgoOrderState removeAlgoOrderValue(long key) { return cold.algoOrders.remove(key); }
    public com.surprising.aeron.service.state.model.CoreTriggerOrderState triggerOrder(long key) { return cold.triggerOrders.get(key); }
    public void forEachAlgoOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.service.state.model.CoreAlgoOrderState> consumer) { cold.algoOrders.forEachKeyValue(consumer); }
    public void forEachTriggerOrder(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.service.state.model.CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachKeyValue(consumer); }
    public com.surprising.aeron.service.state.account.TransferRuntime pendingTransfer(long key) { return cold.pendingTransfers.get(key); }
    public void forEachPendingTransfer(org.eclipse.collections.api.block.procedure.primitive.LongObjectProcedure<? super com.surprising.aeron.service.state.account.TransferRuntime> consumer) { cold.pendingTransfers.forEachKeyValue(consumer); }
    public void clearPendingTransferValues() { cold.pendingTransfers.clear(); }
    public com.surprising.aeron.service.state.account.TransferRuntime storePendingTransfer(long key, com.surprising.aeron.service.state.account.TransferRuntime value) { return cold.pendingTransfers.put(key, value); }
    public boolean hasPendingTransfer(long key) { return cold.pendingTransfers.containsKey(key); }
    public com.surprising.aeron.service.state.account.TransferRuntime removePendingTransferValue(long key) { return cold.pendingTransfers.remove(key); }
    public void clearLeverageValues() { cold.leverages.clear(); }
    public void clearAlgoOrderValues() { cold.algoOrders.clear(); }
    public void clearTriggerOrderValues() { cold.triggerOrders.clear(); }
    public UserRuntime removeUserValue(long key) { return users.remove(key); }
    public ReservationRuntime storeReservation(long key, ReservationRuntime value) { return reservations.put(key, value); }
    public com.surprising.aeron.service.state.position.PositionRuntime storePosition(long key, com.surprising.aeron.service.state.position.PositionRuntime value) { return positions.put(key, value); }
    public com.surprising.aeron.service.state.risk.LiquidationRuntime storeLiquidation(long key, com.surprising.aeron.service.state.risk.LiquidationRuntime value) { return cold.liquidations.put(key, value); }
    public com.surprising.aeron.service.state.risk.RiskSnapshotRuntime storeRiskSnapshot(long key, com.surprising.aeron.service.state.risk.RiskSnapshotRuntime value) { return cold.riskSnapshots.put(key, value); }
    public com.surprising.aeron.service.state.risk.LiquidationRuntime removeLiquidationValue(long key) { return cold.liquidations.remove(key); }
    public com.surprising.aeron.service.state.position.PositionRuntime removePositionValue(long key) { return positions.remove(key); }
    public com.surprising.aeron.service.state.risk.RiskSnapshotRuntime removeRiskSnapshotValue(long key) { return cold.riskSnapshots.remove(key); }

    public void forEachOrder(org.agrona.collections.LongObjConsumer<OrderRuntime> consumer) { orders.longForEach(consumer); }
    public void forEachReservation(org.agrona.collections.LongObjConsumer<ReservationRuntime> consumer) { reservations.longForEach(consumer); }
    public boolean hasOrder(long key) { return orders.containsKey(key); }
    public int admissionIndexCapacity() { return admissionIndexCapacity; }
    public RuntimeDerivativeMatchProcessor.DerivativeSettlementAccumulator derivativeSettlementAccumulator() { return derivativeSettlementAccumulator; }
    public RuntimeSpotMatchProcessor.SpotSettlementAccumulator spotSettlementAccumulator() { return spotSettlementAccumulator; }
    public RiskLaneProcessor.PositionRiskScratch positionRiskScratch() { return positionRiskScratch; }
    public RuntimeDerivativeFillCalculator.FillCursor singleFillCursor() { return singleFillCursor; }
    public void admissionIndexCapacity(int value) { this.admissionIndexCapacity = value; }
    public void forEachOrderEntry(java.util.function.BiConsumer<? super Long, ? super OrderRuntime> consumer) { orders.forEach(consumer); }
    public void forEachPositionValue(org.eclipse.collections.api.block.procedure.Procedure<? super com.surprising.aeron.service.state.position.PositionRuntime> consumer) { positions.forEachValue(consumer); }
    public void forEachTriggerOrderValue(org.eclipse.collections.api.block.procedure.Procedure<? super com.surprising.aeron.service.state.model.CoreTriggerOrderState> consumer) { cold.triggerOrders.forEachValue(consumer); }
    public boolean leverageValuesEmpty() { return cold.leverages.isEmpty(); }

    public IntObjectHashMap<BalanceRuntime> storeUserBalances(long key, IntObjectHashMap<BalanceRuntime> value) { return balances.put(key, value); }
    public IntObjectHashMap<BalanceRuntime> userBalances(long key) { return balances.get(key); }
    public IntObjectHashMap<BalanceRuntime> removeUserBalances(long key) { return balances.remove(key); }
    public org.agrona.collections.LongHashSet reservationIds(long key) { return reservationIdsByUser.get(key); }
    public org.agrona.collections.LongHashSet removeReservationIds(long key) { return reservationIdsByUser.remove(key); }
    public LongHashSet positionKeys(long key) { return positionKeysByUser.get(key); }
    public LongHashSet removePositionKeys(long key) { return positionKeysByUser.remove(key); }
    public org.agrona.collections.LongHashSet activeOrderIds(long key) { return activeOrderIdsByUser.get(key); }
    public org.agrona.collections.LongHashSet removeActiveOrderIds(long key) { return activeOrderIdsByUser.remove(key); }
    public java.util.HashSet<CoreLeverageKey> storeLeverageKeys(long key, java.util.HashSet<CoreLeverageKey> value) { return cold.leverageKeysByUser.put(key, value); }
    public java.util.HashSet<CoreLeverageKey> leverageKeys(long key) { return cold.leverageKeysByUser.get(key); }
    public void clearLeverageKeys() { cold.leverageKeysByUser.clear(); }
    public java.util.HashSet<CoreLeverageKey> removeLeverageKeys(long key) { return cold.leverageKeysByUser.remove(key); }
    public LongObjectHashMap<LongArrayList> storeSymbolPositionKeys(int key, LongObjectHashMap<LongArrayList> value) { return positionKeysBySymbolAndUser.put(key, value); }
    public LongObjectHashMap<LongArrayList> symbolPositionKeys(int key) { return positionKeysBySymbolAndUser.get(key); }
    public LongObjectHashMap<LongArrayList> removeSymbolPositionKeys(int key) { return positionKeysBySymbolAndUser.remove(key); }
    public IntObjectHashMap<LongObjectHashMap<Long>> storeActiveLiquidations(long key, IntObjectHashMap<LongObjectHashMap<Long>> value) { return cold.activeLiquidationIndex.put(key, value); }
    public IntObjectHashMap<LongObjectHashMap<Long>> activeLiquidations(long key) { return cold.activeLiquidationIndex.get(key); }
    public IntObjectHashMap<LongObjectHashMap<Long>> removeActiveLiquidations(long key) { return cold.activeLiquidationIndex.remove(key); }
    public LongHashSet triggerIds(long key) { return cold.triggerIdsByUser.get(key); }
    public void clearTriggerIds() { cold.triggerIdsByUser.clear(); }
    public Long2LongHashMap storeUserClientOrders(long key, Long2LongHashMap value) { return clientOrderIndex.put(key, value); }
    public Long2LongHashMap userClientOrders(long key) { return clientOrderIndex.get(key); }
    public Long2LongHashMap removeUserClientOrders(long key) { return clientOrderIndex.remove(key); }
    public void indexPositionKey(long userId, long key) {
        LongHashSet keys = positionKeysByUser.get(userId);
        if (keys == null) { keys = new LongHashSet(16); positionKeysByUser.put(userId, keys); }
        keys.add(key);
    }
    public void unindexPositionKey(long userId, long key) {
        LongHashSet keys = positionKeysByUser.get(userId);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) positionKeysByUser.remove(userId);
    }

    /** 调用方持有 Lane 查询屏障；逐用户冻结发布值，不要求当前线程成为写入者。 */
    public LongObjectHashMap<UserRuntime> usersSnapshot() {
        LongObjectHashMap<UserRuntime> copy = new LongObjectHashMap<>(users.size());
        users.forEachKeyValue((userId, user) -> copy.put(userId, user.publicationValue()));
        return copy;
    }

    public LongObjectHashMap<IntObjectHashMap<BalanceRuntime>> balancesSnapshot() {
        return TradingRuntimeState.copyAllBalances(balances);
    }
    public LongObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap> clientOrdersSnapshot() {
        return TradingRuntimeState.copyClientOrderIndex(clientOrderIndex);
    }
    public LongObjectHashMap<PositionRuntime> positionsSnapshot() { return new LongObjectHashMap<>(positions); }
    public LongObjectHashMap<LiquidationRuntime> liquidationsSnapshot() { return new LongObjectHashMap<>(cold.liquidations); }
    public LongObjectHashMap<com.surprising.aeron.service.state.risk.RiskSnapshotRuntime> riskSnapshotsSnapshot() { return new LongObjectHashMap<>(cold.riskSnapshots); }
    public java.util.TreeMap<CoreLeverageKey, Long> leveragesSnapshot() { return new java.util.TreeMap<>(cold.leverages); }

}

/**
 * Lane 状态中不参与持续撮合的索引集合。
 *
 * <p>把风险、触发、清算和杠杆结构放在独立对象中，AccountLaneState 的热对象只保留
 * 订单、余额、预留和持仓引用。冷状态仍由同一个 Lane 线程拥有，因而不引入新的锁或
 * 可见性协议；快照、回放和查询继续使用原来的索引。</p>
 */
final class LaneColdState {
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<LiquidationRuntime> liquidations =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<Long>>> activeLiquidationIndex =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.state.risk.RiskSnapshotRuntime> riskSnapshots =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final java.util.Map<com.surprising.aeron.service.state.model.CoreLeverageKey, Long> leverages =
            new java.util.HashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<java.util.HashSet<com.surprising.aeron.service.state.model.CoreLeverageKey>> leverageKeysByUser =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.state.model.CoreAlgoOrderState> algoOrders =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.state.model.CoreTriggerOrderState> triggerOrders =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<org.eclipse.collections.impl.set.mutable.primitive.LongHashSet> triggerIdsByUser =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();

    void clear() {
        liquidations.clear();
        activeLiquidationIndex.clear();
        riskSnapshots.clear();
        leverages.clear();
        leverageKeysByUser.clear();
        algoOrders.clear();
        triggerOrders.clear();
        triggerIdsByUser.clear();
        pendingTransfers.clear();
    }
}

