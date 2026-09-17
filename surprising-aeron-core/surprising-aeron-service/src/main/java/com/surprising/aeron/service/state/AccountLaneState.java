package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.aeron.service.state.admission.AdmissionOrderIndex;
import com.surprising.aeron.service.state.admission.AdmissionSummary;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import java.util.Map;

public final class AccountLaneState {
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
    final LongObjectHashMap<LongHashSet> activeOrderIdsByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final Long2ObjectHashMap<ReservationRuntime> reservations =
            new Long2ObjectHashMap<>(INITIAL_ENTITY_CAPACITY, 0.65f, false);
    final LongObjectHashMap<LongHashSet> reservationIdsByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<PositionRuntime> positions = new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final LongObjectHashMap<LongHashSet> positionKeysByUser =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    /** Risk scan output is reused per logical Lane, including synchronous multi-Lane callers. */
    RiskLiquidationBatch riskLiquidationBatch;
    /** Open position keys stay sorted in primitive storage; scans no longer build a boxed TreeSet. */
    final IntObjectHashMap<LongObjectHashMap<LongArrayList>> positionKeysBySymbolAndUser
            = new IntObjectHashMap<>();
    final LongObjectHashMap<LongLongHashMap> clientOrderIndex =
            new LongObjectHashMap<>(INITIAL_ENTITY_CAPACITY);
    final OrderClientKeyIndex clientKeysByOrderId = new OrderClientKeyIndex();
    /** 风险、清算、杠杆、算法单和触发单等低频结构与热状态分离。 */
    final LaneColdState cold = new LaneColdState();

    CoreTriggerOrderState putTrigger(CoreTriggerOrderState value) {
        CoreTriggerOrderState previous = cold.triggerOrders.put(value.triggerOrderId(), value);
        if (previous != null && previous.userId() != value.userId()) unindexTrigger(previous);
        cold.triggerIdsByUser.getIfAbsentPut(value.userId(), LongHashSet::new).add(value.triggerOrderId());
        return previous;
    }

    CoreTriggerOrderState removeTrigger(long id) {
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
    /**
     * Pending reservations are short lived but can reach the same in-flight
     * cardinality as the lane's active entities.  The default primitive-map
     * capacity is too small for the closed-loop benchmark and repeatedly
     * reallocates while orders churn.  Reuse the lane baseline so normal
     * admission/settlement cycles stay within one backing table.
     */
    final LongLongHashMap pendingReservationSequences = new LongLongHashMap(INITIAL_ENTITY_CAPACITY);
    private final LongIntHashMap pendingReservationCountsByUser = new LongIntHashMap(INITIAL_ENTITY_CAPACITY);
    /** One reusable table per asset; completed users are removed, capacity survives ordinary churn. */
    private final IntObjectHashMap<LongLongHashMap> pendingReservedUnitsByAsset = new IntObjectHashMap<>();
    private int totalPendingReservations;
    /** 该Lane新建客户订单身份的累计数量；完成收据由Owner汇入字典版本。 */
    long clientIdentityAllocations;
    private long revision;
    private long appliedSequence;
    private long committedSequence;
    private long localStateHash = 0xcbf29ce484222325L;
    private long localFundsHash = 0xcbf29ce484222325L;
    /** Hashes are rebuilt on the lane owner; retain sort buffers across commits. */
    private long[] hashLongScratch = new long[16];
    private int[] hashIntScratch = new int[16];
    private long matcherSettlementOperations;
    private long matcherSettlementLatencyNanos;
    private long matcherSettlementMaxLatencyNanos;
    private Thread owner;
    private final LaneAdmissionOrderIndex admissionOrderIndex = new LaneAdmissionOrderIndex();

    AccountLaneState(int laneId, int queueCapacity) {
        if (laneId < 0 || laneId >= Long.SIZE || queueCapacity <= 0) {
            throw new IllegalArgumentException("invalid account lane");
        }
        this.laneId = laneId;
        this.queueCapacity = queueCapacity;
        this.localStateHash = computeStateHash();
        this.localFundsHash = computeFundsHash();
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("account lane is bound to another thread");
    }

    public void releaseOwnerForHandoff() {
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException("account lane owner mismatch");
        }
        owner = null;
    }

    void assertOwner() {
        bindOwner();
    }

    void writeMetrics(com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder encoder,
                      int depth, int highWater) {
        assertOwner();
        encoder.writeLane(laneId, revision, appliedSequence, committedSequence,
                depth, queueCapacity, highWater, 0, 0);
        encoder.addOperation(laneId, AccountLaneOperationType.SETTLEMENT.ordinal(),
                matcherSettlementOperations, matcherSettlementOperations,
                matcherSettlementLatencyNanos, matcherSettlementMaxLatencyNanos);
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

    void registerUser(long userId) {
        assertOwner();
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        userIds.add(userId);
    }

    void removeUser(long userId) {
        assertOwner();
        userIds.remove(userId);
    }

    void markPendingReservation(long orderId, long coreSequence) {
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

    void completePendingReservation(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, true);
    }

    void requirePendingReservationCompletion(long orderId, long coreSequence) {
        pendingReservationCompletion(orderId, coreSequence, false);
    }

    /** Validate all counters before writing; validation and application share no temporary state object. */
    private void pendingReservationCompletion(long orderId, long coreSequence, boolean apply) {
        assertOwner();
        long pendingSequence = pendingReservationSequences.getIfAbsent(orderId, 0);
        if (pendingSequence != coreSequence) {
            throw new IllegalStateException("pending reservation sequence mismatch");
        }
        ReservationRuntime reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalStateException("pending reservation is missing");
        int userCount = pendingReservationCountsByUser.get(reservation.userId());
        int nextUserCount = Math.subtractExact(userCount, 1);
        int nextTotal = Math.subtractExact(totalPendingReservations, 1);
        long previousUnits = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long nextUnits = Math.subtractExact(previousUnits, reservation.reservedUnits());
        if (nextUserCount < 0 || nextTotal < 0 || nextUnits < 0) {
            throw new IllegalStateException("pending reservation counters are inconsistent");
        }
        if (!apply) return;
        pendingReservationSequences.removeKey(orderId);
        if (nextUserCount == 0) pendingReservationCountsByUser.removeKey(reservation.userId());
        else pendingReservationCountsByUser.put(reservation.userId(), nextUserCount);
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), nextUnits);
        totalPendingReservations = nextTotal;
    }

    void replacePendingReservation(ReservationRuntime previous, ReservationRuntime replacement) {
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

    boolean pendingReservation(long orderId) {
        assertOwner();
        return pendingReservationSequences.containsKey(orderId);
    }

    void updatePendingReservationInPlace(ReservationRuntime reservation, long previousReservedUnits) {
        assertOwner();
        if (reservation == null || previousReservedUnits < 0) throw new IllegalArgumentException("invalid reservation update");
        if (!pendingReservationSequences.containsKey(reservation.orderId())) return;
        long current = pendingReservedUnits(reservation.userId(), reservation.assetId());
        long next = Math.addExact(Math.subtractExact(current, previousReservedUnits), reservation.reservedUnits());
        if (next < 0) throw new IllegalStateException("pending reservation counters are inconsistent");
        setPendingReservedUnits(reservation.userId(), reservation.assetId(), next);
    }

    long pendingReservedUnits(long userId, int assetId) {
        assertOwner();
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

    int pendingReservationCount(long userId) {
        assertOwner();
        return pendingReservationCountsByUser.get(userId);
    }

    int pendingReservationCount() {
        assertOwner();
        return totalPendingReservations;
    }

    void recordMatcherSettlement(long latencyNanos) {
        assertOwner();
        if (latencyNanos < 0) throw new IllegalArgumentException("invalid matcher settlement latency");
        matcherSettlementOperations++;
        matcherSettlementLatencyNanos = Math.addExact(matcherSettlementLatencyNanos, latencyNanos);
        matcherSettlementMaxLatencyNanos = Math.max(matcherSettlementMaxLatencyNanos, latencyNanos);
    }

    MatcherSettlementMetrics matcherSettlementMetrics() {
        assertOwner();
        return new MatcherSettlementMetrics(matcherSettlementOperations,
                matcherSettlementLatencyNanos, matcherSettlementMaxLatencyNanos);
    }

    boolean hasPendingReservations() {
        assertOwner();
        return totalPendingReservations != 0;
    }

    AdmissionOrderIndex admissionOrderIndex(int symbolId) {
        assertOwner();
        if (symbolId < 0) throw new IllegalArgumentException("invalid admission symbol");
        admissionOrderIndex.symbolId = symbolId;
        return admissionOrderIndex;
    }

    void putOrder(OrderRuntime order) {
        assertOwner();
        if (order == null) throw new IllegalArgumentException("order is required");
        order = order.laneValue();
        OrderRuntime previous = orders.put(order.orderId(), order);
        replaceActiveOrder(previous, order);
        admissionOrderIndex.replace(previous, order);
    }

    void removeOrder(long orderId) {
        assertOwner();
        OrderRuntime previous = orders.remove(orderId);
        replaceActiveOrder(previous, null);
        admissionOrderIndex.replace(previous, null);
    }

    /**
     * Applies execution fields without replacing the Lane-owned object.  The two compact
     * indexes are updated from primitive before-values, so a fill allocates no intermediate
     * OrderRuntime value.
     */
    OrderRuntime updateOrderInPlace(long orderId, long executed, long remaining, long feeUnits,
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

    OrderRuntime updateOrderStatusInPlace(long orderId, CoreOrderStatus nextStatus, long nextRevision,
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

    long openReduceOnlyQuantity(long userId, int symbolId, CorePositionSide positionSide,
                                CoreOrderSide side, CoreMarginMode marginMode) {
        assertOwner();
        LongHashSet orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) return 0;
        long total = 0;
        var iterator = orderIds.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
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
        LongHashSet orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null) {
            orderIds = new LongHashSet(admissionIndexCapacity);
            activeOrderIdsByUser.put(userId, orderIds);
        }
        orderIds.add(orderId);
    }

    private void removeActiveOrder(long userId, long orderId) {
        LongHashSet orderIds = activeOrderIdsByUser.get(userId);
        if (orderIds == null || !orderIds.remove(orderId)) {
            throw new IllegalStateException("active order index is missing");
        }
        if (orderIds.isEmpty()) activeOrderIdsByUser.remove(userId);
    }

    private static boolean active(OrderRuntime order) {
        return order != null && !order.status().terminal();
    }

    private final class LaneAdmissionOrderIndex implements AdmissionOrderIndex {
        private final Long2ObjectHashMap<IntObjectHashMap<AdmissionAggregate>> summariesByUser =
                new Long2ObjectHashMap<>();
        private final AdmissionSummary summary = new AdmissionSummary();
        private int symbolId;

        @Override
        public AdmissionSummary inspect(
                long userId, String symbol, CorePositionSide positionSide,
                CoreOrderSide side, CoreMarginMode conflictingMarginMode) {
            IntObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(userId);
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
            IntObjectHashMap<AdmissionAggregate> bySymbol = summariesByUser.get(order.userId());
            AdmissionAggregate aggregate = bySymbol == null ? null : bySymbol.get(order.symbolId());
            if (aggregate == null) {
                if (direction < 0) throw new IllegalStateException("admission order index is missing");
                bySymbol = bySymbol == null ? new IntObjectHashMap<>() : bySymbol;
                aggregate = new AdmissionAggregate();
                aggregate.update(order, direction);
                bySymbol.put(order.symbolId(), aggregate);
                summariesByUser.put(order.userId(), bySymbol);
                return;
            }
            aggregate.update(order, direction, remainingQuantity);
            if (aggregate.empty()) {
                bySymbol.remove(order.symbolId());
                if (bySymbol.isEmpty()) summariesByUser.remove(order.userId());
            }
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

        void update(OrderRuntime order, int direction) {
            update(order, direction, order.remainingQuantitySteps());
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

    void applied(long coreSequence) {
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

    void committed(long coreSequence) {
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

    void readFence(long coreSequence) {
        assertOwner();
        requireReadFence(coreSequence);
        advanceReadFence(coreSequence);
    }

    void requireReadFence(long coreSequence) {
        assertOwner();
        if (coreSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane read fence crossed uncommitted work");
        }
    }

    void advanceReadFence(long coreSequence) {
        assertOwner();
        appliedSequence = coreSequence;
        committedSequence = coreSequence;
    }

    void restore(long revision, long appliedSequence, long committedSequence,
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

    AccountLaneSnapshot snapshot(long fenceSequence) {
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

    void requireSnapshot(long fenceSequence) {
        assertOwner();
        if (!pendingReservationSequences.isEmpty()) {
            throw new IllegalStateException("pending reservation cannot cross snapshot fence");
        }
        if (fenceSequence < committedSequence || appliedSequence != committedSequence) {
            throw new IllegalStateException("account lane snapshot crossed an incomplete commit");
        }
    }

    void restore(AccountLaneSnapshot snapshot) {
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
        long[] userKeys = copyLongKeys(userIds.size());
        int userIndex = 0;
        var userIterator = userIds.longIterator();
        while (userIterator.hasNext()) userKeys[userIndex++] = userIterator.next();
        java.util.Arrays.sort(userKeys, 0, userIndex);
        for (int index = 0; index < userIndex; index++) {
            long userId = userKeys[index];
            hash = mix(hash, userId);
            hash = mixText(hash, users.get(userId));
        }
        hash = mixMap(hash, orders);
        hash = mixMap(hash, reservations);
        hash = mixMap(hash, positions);
        hash = mixMap(hash, cold.liquidations);
        hash = mixMap(hash, cold.riskSnapshots);
        for (Map.Entry<CoreLeverageKey, Long> entry : cold.leverages.entrySet()) {
            hash = mixText(hash, entry.getKey());
            hash = mix(hash, entry.getValue());
        }
        hash = mixPrimitiveMap(hash, cold.algoOrders);
        hash = mixPrimitiveMap(hash, cold.triggerOrders);
        return hash == 0 ? 1 : hash;
    }

    private long mixPrimitiveMap(long hash, LongObjectHashMap<?> values) {
        long[] keys = copyLongKeys(values.size());
        int keyIndex = 0;
        var iterator = values.keySet().longIterator();
        while (iterator.hasNext()) keys[keyIndex++] = iterator.next();
        java.util.Arrays.sort(keys, 0, keyIndex);
        long mixed = hash;
        for (int index = 0; index < keyIndex; index++) {
            long key = keys[index];
            mixed = mix(mixed, key);
            Object value = values.get(key);
            mixed = mixText(mixed, value);
        }
        return mixed;
    }

    private long computeFundsHash() {
        long hash = mix(0xcbf29ce484222325L, laneId);
        long[] userKeys = copyLongKeys(balances.size());
        int userIndex = 0;
        var userIterator = balances.keySet().longIterator();
        while (userIterator.hasNext()) userKeys[userIndex++] = userIterator.next();
        java.util.Arrays.sort(userKeys, 0, userIndex);
        for (int index = 0; index < userIndex; index++) {
            long userId = userKeys[index];
            hash = mix(hash, userId);
            IntObjectHashMap<BalanceRuntime> userBalances = balances.get(userId);
            int[] assetIds = copyIntKeys(userBalances.size());
            int assetIndex = 0;
            var assetIterator = userBalances.keySet().intIterator();
            while (assetIterator.hasNext()) assetIds[assetIndex++] = assetIterator.next();
            java.util.Arrays.sort(assetIds, 0, assetIndex);
            for (int asset = 0; asset < assetIndex; asset++) {
                int assetId = assetIds[asset];
                BalanceRuntime balance = userBalances.get(assetId);
                hash = mix(hash, assetId);
                hash = mix(hash, balance.availableUnits());
                hash = mix(hash, balance.lockedUnits());
            }
        }
        return hash == 0 ? 1 : hash;
    }

    void rebuildLocalHashes() {
        assertOwner();
        localStateHash = computeStateHash();
        localFundsHash = computeFundsHash();
    }

    private long mixMap(long hash, Long2ObjectHashMap<?> values) {
        long[] keys = copyLongKeys(values.size());
        var iterator = values.keySet().iterator();
        int keyIndex = 0;
        while (iterator.hasNext()) keys[keyIndex++] = iterator.nextLong();
        java.util.Arrays.sort(keys, 0, keyIndex);
        long mixed = hash;
        for (int index = 0; index < keyIndex; index++) {
            long key = keys[index];
            mixed = mix(mixed, key);
            mixed = mixText(mixed, values.get(key));
        }
        return mixed;
    }

    private <T> long mixMap(long hash, LongObjectHashMap<T> values) {
        long[] keys = copyLongKeys(values.size());
        int keyIndex = 0;
        var iterator = values.keySet().longIterator();
        while (iterator.hasNext()) keys[keyIndex++] = iterator.next();
        java.util.Arrays.sort(keys, 0, keyIndex);
        long mixed = hash;
        for (int index = 0; index < keyIndex; index++) {
            long key = keys[index];
            mixed = mix(mixed, key);
            mixed = mixText(mixed, values.get(key));
        }
        return mixed;
    }

    private long[] copyLongKeys(int size) {
        if (hashLongScratch.length < size) {
            int capacity = hashLongScratch.length;
            while (capacity < size) capacity = Math.multiplyExact(capacity, 2);
            hashLongScratch = java.util.Arrays.copyOf(hashLongScratch, capacity);
        }
        return hashLongScratch;
    }

    private int[] copyIntKeys(int size) {
        if (hashIntScratch.length < size) {
            int capacity = hashIntScratch.length;
            while (capacity < size) capacity = Math.multiplyExact(capacity, 2);
            hashIntScratch = java.util.Arrays.copyOf(hashIntScratch, capacity);
        }
        return hashIntScratch;
    }

    private static long mixText(long hash, Object value) {
        String text = String.valueOf(value);
        long mixed = mix(hash, text.length());
        boolean ascii = true;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character > 0x7f) {
                ascii = false;
                break;
            }
            mixed ^= character;
            mixed *= 0x100000001b3L;
        }
        if (ascii) return mixed;
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mixed = mix(hash, bytes.length);
        for (byte item : bytes) {
            mixed ^= Byte.toUnsignedInt(item);
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }
}
