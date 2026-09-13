package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.CoreCancellationResult;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

public final class MatcherSettlementPlan {
    /** 撮合明确拒绝时，由账户 Lane 释放准入冻结并产生拒单终态。 */
    private boolean rejectedTaker;
    public MatcherSettlementPlan rejectTaker(boolean rejected) {
        if (rejected && tradeCount != 0) throw new IllegalArgumentException("rejected taker cannot contain trades");
        rejectedTaker = rejected;
        return this;
    }
    boolean rejectedTaker() { return rejectedTaker; }

    private long coreSequence;
    private long takerOrderId;
    private long activeUserId;
    private long requiredLaneMask;
    private long[] orderIds;
    private int orderCount;
    private List<MatcherEvent> matcherEvents;
    private int tradeCount;
    private long[] preCancellationOrderIds;
    /** Number of valid entries in the reusable cancellation storage. */
    private int preCancellationSize;
    /**
     * Reusable storage for event-owned cancellation identities.  The public
     * setter still copies caller-owned arrays, while direct matcher events can
     * fill this plan-owned buffer without creating a temporary array and then
     * copying it a second time.
     */
    private long[] preCancellationStorage;
    private int[] makerLaneHeads;
    private int[] makerLaneNext;
    private int takerLaneId;
    /** 索引数组归事件所有并复用；只有本代深度成交启用时读取。 */
    private boolean laneEventsIndexed;
    /** 单命令初始身份，仅所属在途槽复用，构建完成后不对外发布。 */
    private long[] initialOrderScratch;
    private static final long[] NO_ORDERS = new long[0];
    private OrderRuntime directTaker;
    private static final ThreadLocal<LongHashSet> DIRECT_ORDER_KEYS = ThreadLocal.withInitial(LongHashSet::new);
    /** Read-only view owned by this plan; it remains valid until the enclosing context is recycled. */
    private final List<Long> orderIdView = new OrderIdView(this);

    /** 事件池或在途序号槽独占并复用；Owner准备后只读，所有Lane完成后才能清理。 */
    public MatcherSettlementPlan() { orderIds = new long[8]; preCancellationOrderIds = NO_ORDERS; matcherEvents = List.of(); }

    public void clearReferences() {
        matcherEvents = List.of(); completedTrigger = null; preCancellationOrderIds = NO_ORDERS;
        preCancellationSize = 0;
        rejectedTaker = false; laneEventsIndexed = false; orderCount = tradeCount = 0;
        directTaker = null;
    }

    /** Matcher builds routing from its immutable fact; it never reads another thread's account tables. */
    void buildDirect(long sequence, OrderRuntime taker, CoreInstrumentState instrument,
                     CoreMatchingResult result, TradingRuntimeState runtime) {
        if (taker == null || instrument == null || result == null
                || result.nativeCommand().coreSequence() != sequence
                || result.nativeCommand().orderId() != taker.orderId()
                || instrument.changeId() != taker.instrumentChangeId())
            throw new IllegalArgumentException("invalid direct matcher fact");
        clearReferences();
        coreSequence = sequence; directTaker = taker;
        takerOrderId = taker.orderId(); activeUserId = taker.userId();
        requiredLaneMask = runtime.topology().accountLaneMask(activeUserId);
        matcherEvents = result.matcherEvents();
        int capacity = Math.addExact(matcherEvents.size(), Math.addExact(result.cancellations().size(), 1));
        if (orderIds.length < capacity) orderIds = new long[Math.max(capacity, orderIds.length * 2)];
        LongHashSet keys = DIRECT_ORDER_KEYS.get();
        keys.clear();
        try {
            orderCount = addUnique(keys, orderIds, 0, takerOrderId);
            long remaining = taker.remainingQuantitySteps();
            for (MatcherEvent event : matcherEvents) {
                if (event == null) throw new IllegalArgumentException("missing matcher event");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (event.price() <= 0 || event.size() <= 0 || event.matchedOrderId() <= 0
                        || event.matchedOrderUid() <= 0 || event.matchedOrderUid() == activeUserId)
                    throw new IllegalStateException("invalid direct fill identity or quantity");
                remaining = Math.subtractExact(remaining, event.size());
                if (remaining < 0) throw new IllegalStateException("fill exceeds admitted taker quantity");
                orderCount = addUnique(keys, orderIds, orderCount, event.matchedOrderId());
                requiredLaneMask |= runtime.topology().accountLaneMask(event.matchedOrderUid());
                tradeCount++;
            }
            // Pre-matching cancellations belong to the submitting account and were authorized at admission.
            for (var cancellation : result.cancellations()) if (cancellation.accepted())
                orderCount = addUnique(keys, orderIds, orderCount, cancellation.orderId());
            rejectTaker(!result.accepted());
            indexLaneEvents(runtime);
        } finally { keys.clear(); }
    }

    /** The single account writer checks the current order, including earlier fills in this batch. */
    void validateDirectLane(AccountLaneState lane, TradingRuntimeState runtime,
                            RuntimeIdentityRegistry identities, CoreInstrumentState instrument) {
        if (directTaker == null) return;
        if (runtime.topology().accountLaneId(activeUserId) == lane.laneId()) {
            OrderRuntime taker = lane.orders.get(takerOrderId);
            requireDirectOrder(taker, activeUserId, directTaker.side());
            if (tradeCount != 0) runtime.retainDirectPositionIdentity(lane, identities, instrument, taker);
        }
        for (MatcherEvent event : matcherEvents) {
            if (event.eventType() != MatcherEventType.TRADE
                    || runtime.topology().accountLaneId(event.matchedOrderUid()) != lane.laneId()) continue;
            OrderRuntime maker = lane.orders.get(event.matchedOrderId());
            requireDirectOrder(maker, event.matchedOrderUid(), directTaker.side()
                    == com.surprising.aeron.protocol.CoreOrderSide.BUY
                    ? com.surprising.aeron.protocol.CoreOrderSide.SELL
                    : com.surprising.aeron.protocol.CoreOrderSide.BUY);
            if (maker.remainingQuantitySteps() < event.size())
                throw new IllegalStateException("fill exceeds Lane maker remaining quantity");
            runtime.retainDirectPositionIdentity(lane, identities, instrument, maker);
        }
    }

    private void requireDirectOrder(OrderRuntime order, long userId,
                                    com.surprising.aeron.protocol.CoreOrderSide side) {
        if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                || order.symbolId() != directTaker.symbolId() || order.side() != side
                || order.instrumentChangeId() != directTaker.instrumentChangeId())
            throw new IllegalStateException("matcher fact does not match Lane-owned order");
    }
    /** 仅触发子订单携带；owner 派发前构造，taker Lane 在同一结算事件中写入。 */
    private com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger;

    public MatcherSettlementPlan completeTrigger(com.surprising.aeron.service.state.model.CoreTriggerOrderState value) {
        if (value == null || value.userId() != activeUserId || (rejectedTaker
                ? value.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGER_FAILED
                        || value.placedOrderId() != 0
                : value.placedOrderId() != takerOrderId) || completedTrigger != null)
            throw new IllegalArgumentException("invalid settlement trigger completion");
        completedTrigger = value;
        return this;
    }
    com.surprising.aeron.service.state.model.CoreTriggerOrderState completedTrigger() { return completedTrigger; }

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount) {
        this(coreSequence, takerOrderId, activeUserId, requiredLaneMask, orderIds, orderCount,
                matcherEvents, tradeCount, NO_ORDERS);
    }

    private MatcherSettlementPlan(long coreSequence, long takerOrderId, long activeUserId,
                                  long requiredLaneMask, long[] orderIds, int orderCount,
                                  List<MatcherEvent> matcherEvents, int tradeCount,
                                  long[] preCancellationOrderIds) {
        this.coreSequence = coreSequence;
        this.takerOrderId = takerOrderId;
        this.activeUserId = activeUserId;
        this.requiredLaneMask = requiredLaneMask;
        this.orderIds = orderIds;
        this.orderCount = orderCount;
        this.matcherEvents = matcherEvents;
        this.tradeCount = tradeCount;
        this.preCancellationOrderIds = preCancellationOrderIds;
        this.preCancellationSize = preCancellationOrderIds.length;
    }

    static final class BatchValidationScratch {
        private final LongLongHashMap remainingByOrderId = new LongLongHashMap();
        private final LongHashSet terminalOrderIds = new LongHashSet();
        /** 本批相邻成交重复maker的只读引用；不额外建立逐订单缓存表。 */
        private OrderRuntime lastMaker;

        OrderRuntime maker(TradingRuntimeState runtime, long id) {
            if (lastMaker == null || lastMaker.orderId() != id) lastMaker = requireOpen(runtime, id);
            return lastMaker;
        }

        void clear() {
            remainingByOrderId.clear();
            terminalOrderIds.clear();
            lastMaker = null;
        }
    }

    public static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, CoreMatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (initialOrderIds == null) throw new IllegalArgumentException("initial orders are required");
        return build(coreSequence, takerOrderId, activeUserId, initialOrderIds, result, runtime, identities,
                null, null, null, null);
    }

    /** 调用方独占 target，全部 Lane 完成并消费结果之前不得再次构建或清理。 */
    public static MatcherSettlementPlan buildInto(MatcherSettlementPlan target,
            long sequence, long takerOrderId, long userId, long firstOrderId, long secondOrderId,
            CoreMatchingResult result, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null) throw new IllegalArgumentException("settlement target is required");
        if (target.initialOrderScratch == null) target.initialOrderScratch = new long[2];
        target.initialOrderScratch[0] = firstOrderId;
        target.initialOrderScratch[1] = secondOrderId;
        return build(sequence, takerOrderId, userId, target.initialOrderScratch, result, runtime, identities,
                null, null, null, target);
    }

    /**
     * Single-item batch variant.  The previous call site created a one-element
     * {@code long[]} solely to pass the taker identity through the generic
     * builder.  Keep that scratch storage on the pooled target instead.
     */
    public static MatcherSettlementPlan buildSingleInto(MatcherSettlementPlan target,
            long sequence, long takerOrderId, long userId,
            CoreMatchingResult result, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (target == null) throw new IllegalArgumentException("settlement target is required");
        if (target.initialOrderScratch == null || target.initialOrderScratch.length < 1)
            target.initialOrderScratch = new long[1];
        target.initialOrderScratch[0] = takerOrderId;
        return build(sequence, takerOrderId, userId, target.initialOrderScratch, result, runtime, identities,
                null, null, null, target);
    }

    public static MatcherSettlementPlan emptyInto(MatcherSettlementPlan target, long sequence,
            long userId, long firstOrderId, long secondOrderId, TradingRuntimeState runtime) {
        if (target == null || sequence <= 0 || userId <= 0 || firstOrderId <= 0 || secondOrderId <= 0
                || runtime == null) throw new IllegalArgumentException("invalid empty settlement target");
        target.clearReferences();
        target.coreSequence = sequence;
        target.activeUserId = userId;
        target.takerOrderId = secondOrderId;
        target.requiredLaneMask = runtime.topology().accountLaneMask(userId);
        target.orderIds[0] = firstOrderId;
        target.orderIds[1] = secondOrderId;
        target.orderCount = 2;
        return target;
    }

    /** 批量构建与累计数量校验共用一次成交遍历；scratch 只在本批 Owner 调用期间使用。 */
    static MatcherSettlementPlan buildBatchItem(long sequence, OrderRuntime taker, CoreInstrumentState instrument,
                                                CoreMatchingResult result, TradingRuntimeState runtime,
                                                RuntimeIdentityRegistry identities, BatchValidationScratch scratch, MatcherSettlementPlan target) {
        return build(sequence, taker.orderId(), taker.userId(), null, result, runtime, identities,
                taker, instrument, scratch, target);
    }

    private static MatcherSettlementPlan build(long coreSequence, long takerOrderId, long activeUserId,
                                               long[] initialOrderIds, CoreMatchingResult result,
                                               TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                               OrderRuntime preparedTaker, CoreInstrumentState preparedInstrument,
                                               BatchValidationScratch batch, MatcherSettlementPlan target) {
        if (coreSequence <= 0 || takerOrderId <= 0 || activeUserId <= 0 || (initialOrderIds == null && batch == null)
                || result == null || runtime == null || identities == null
                || result.nativeCommand().coreSequence() != coreSequence) {
            throw new IllegalArgumentException("invalid matcher settlement plan input");
        }
        OrderRuntime taker = preparedTaker == null ? requireOpen(runtime, takerOrderId) : preparedTaker;
        if (taker.status() != CoreOrderStatus.OPEN || batch != null && batch.terminalOrderIds.contains(takerOrderId))
            throw new IllegalStateException("runtime matched order is not open: " + takerOrderId);
        CoreInstrumentState instrument = preparedInstrument == null
                ? runtime.instrument(identities.symbol(taker.symbolId())) : preparedInstrument;
        if (instrument == null || instrument.changeId() != taker.instrumentChangeId()) {
            throw new IllegalStateException("runtime match instrument is missing");
        }
        int expectedChanges = Math.max(2, result.matcherEvents().size() + result.cancellations().size()
                + (initialOrderIds == null ? 1 : initialOrderIds.length) + 1);
        long[] orders;
        if (target == null) orders = new long[expectedChanges];
        else {
            if (target.orderIds.length < expectedChanges)
                target.orderIds = new long[Math.max(expectedChanges, target.orderIds.length * 2)];
            orders = target.orderIds;
        }
        int orderCount = 0;
        LongHashSet uniqueOrders = runtime.matcherSettlementOrderScratch();
        if (initialOrderIds == null) orderCount = addUnique(uniqueOrders, orders, orderCount, takerOrderId);
        else for (long orderId : initialOrderIds) {
            if (orderId > 0) orderCount = addUnique(uniqueOrders, orders, orderCount, orderId);
        }
        long laneMask = runtime.topology().accountLaneMask(activeUserId);
        // A single native event cannot revisit a maker; no remaining-quantity table is needed.
        boolean cumulativeValidation = batch != null || result.matcherEvents().size() > 1;
        LongLongHashMap remainingByOrderId = !cumulativeValidation ? null : batch == null
                ? runtime.matcherSettlementRemainingScratch() : batch.remainingByOrderId;
        if (batch == null && remainingByOrderId != null) remainingByOrderId.clear();
        if (remainingByOrderId == null || !remainingByOrderId.containsKey(takerOrderId)) {
            if (remainingByOrderId != null) remainingByOrderId.put(takerOrderId, taker.remainingQuantitySteps());
        }
        int tradeCount = 0;
        try {
            for (MatcherEvent event : result.matcherEvents()) {
                if (event == null) throw new IllegalArgumentException("runtime match is required");
                if (event.eventType() != MatcherEventType.TRADE) continue;
                if (tradeCount == 0) preparePositionIdentity(runtime, identities, instrument, taker);
                if (event.price() <= 0 || event.size() <= 0) {
                    throw new IllegalArgumentException("invalid runtime match price or quantity");
                }
                OrderRuntime maker = batch == null ? requireOpen(runtime, event.matchedOrderId())
                        : batch.maker(runtime, event.matchedOrderId());
                if (batch != null && batch.terminalOrderIds.contains(maker.orderId())
                        || maker.userId() != event.matchedOrderUid() || maker.symbolId() != taker.symbolId()
                        || maker.side() == taker.side() || maker.userId() == taker.userId()) {
                    throw new IllegalStateException("runtime match does not match authoritative orders");
                }
                long takerRemaining = Math.subtractExact(remainingByOrderId == null
                        ? taker.remainingQuantitySteps() : remainingByOrderId.get(takerOrderId), event.size());
                boolean knownMaker = remainingByOrderId != null && remainingByOrderId.containsKey(maker.orderId());
                long makerBefore = knownMaker ? remainingByOrderId.get(maker.orderId()) : maker.remainingQuantitySteps();
                long makerRemaining = Math.subtractExact(makerBefore, event.size());
                if (takerRemaining < 0 || makerRemaining < 0) {
                    throw new IllegalStateException("fill exceeds runtime order remaining quantity");
                }
                if (remainingByOrderId != null) {
                    remainingByOrderId.put(takerOrderId, takerRemaining);
                    remainingByOrderId.put(maker.orderId(), makerRemaining);
                }
                if (batch != null && makerRemaining == 0) batch.terminalOrderIds.add(maker.orderId());
                if (!knownMaker)
                    preparePositionIdentity(runtime, identities, instrument, maker);
                orderCount = addUnique(uniqueOrders, orders, orderCount, maker.orderId());
                int makerLane = runtime.topology().accountLaneId(maker.userId());
                tradeCount++;
                laneMask |= 1L << makerLane;
            }
            if (batch != null && (remainingByOrderId.get(takerOrderId) == 0 || taker.timeInForce().immediate()
                    || taker.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET))
                batch.terminalOrderIds.add(takerOrderId);
        } finally {
            if (batch == null && remainingByOrderId != null) remainingByOrderId.clear();
        }
        for (var cancellation : result.cancellations()) {
            OrderRuntime order = runtime.order(cancellation.orderId());
            if (order != null) {
                orderCount = addUnique(uniqueOrders, orders, orderCount, order.orderId());
                laneMask |= runtime.topology().accountLaneMask(order.userId());
            }
        }
        MatcherSettlementPlan plan = target == null
                ? new MatcherSettlementPlan(coreSequence, takerOrderId, activeUserId, laneMask, orders, orderCount, result.matcherEvents(), tradeCount)
                : target;
        plan.coreSequence = coreSequence; plan.takerOrderId = takerOrderId; plan.activeUserId = activeUserId;
        plan.requiredLaneMask = laneMask; plan.orderIds = orders; plan.orderCount = orderCount;
        plan.matcherEvents = result.matcherEvents(); plan.tradeCount = tradeCount;
        plan.preCancellationOrderIds = NO_ORDERS; plan.preCancellationSize = 0;
        plan.completedTrigger = null; plan.rejectedTaker = false;
        plan.laneEventsIndexed = false;
        return plan.indexLaneEvents(runtime);
    }

    public static MatcherSettlementPlan empty(long coreSequence, long activeUserId, long[] orderIds,
                                               TradingRuntimeState runtime) {
        if (coreSequence <= 0 || activeUserId <= 0 || orderIds == null || runtime == null) {
            throw new IllegalArgumentException("invalid empty matcher settlement plan input");
        }
        return new MatcherSettlementPlan(coreSequence,
                orderIds.length == 0 ? 0 : orderIds[orderIds.length - 1], activeUserId,
                runtime.topology().accountLaneMask(activeUserId), orderIds.clone(), orderIds.length,
                List.of(), 0);
    }

    private static int addUnique(LongHashSet unique, long[] values, int size, long value) {
        if (!unique.add(value)) return size;
        values[size] = value;
        return size + 1;
    }

    private static final class OrderIdView extends AbstractList<Long> implements RandomAccess {
        private final MatcherSettlementPlan plan;

        private OrderIdView(MatcherSettlementPlan plan) { this.plan = plan; }

        @Override
        public Long get(int index) {
            if (index < 0 || index >= plan.orderCount) throw new IndexOutOfBoundsException(index);
            return plan.orderIds[index];
        }

        @Override
        public int size() { return plan.orderCount; }
    }

    private static void preparePositionIdentity(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                CoreInstrumentState instrument, OrderRuntime order) {
        if (!runtime.productLine().isDerivative()) return;
        String positionIdentity = order.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                ? instrument.symbol() : instrument.symbol() + ':' + order.positionSide().name();
        identities.positionKey(order.userId(), positionIdentity);
    }

    private static OrderRuntime requireOpen(TradingRuntimeState runtime, long orderId) {
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("runtime matched order is not open: " + orderId
                    + (order == null ? " missing" : " status=" + order.status()
                    + " executed=" + order.executedQuantitySteps()
                    + " remaining=" + order.remainingQuantitySteps()
                    + " revision=" + order.revision()));
        }
        return order;
    }

    public long coreSequence() { return coreSequence; }
    public long takerOrderId() { return takerOrderId; }
    public long activeUserId() { return activeUserId; }
    public long requiredLaneMask() { return requiredLaneMask; }
    public int orderCount() { return orderCount; }

    /**
     * Returns the plan-owned order ID view without copying a primitive array. The view is only
     * valid while this plan remains attached to its pending sequence/context.
     */
    public List<Long> orderIdList() { return orderIdView; }
    public long orderId(int index) { return orderIds[index]; }
    public int matcherEventCount() { return matcherEvents.size(); }
    public MatcherEvent matcherEvent(int index) { return matcherEvents.get(index); }
    public int tradeCount() { return tradeCount; }
    public MatcherSettlementPlan preCancellations(long[] orderIds) {
        if (orderIds == null) throw new IllegalArgumentException("pre-cancellation ids are required");
        if (orderIds.length == 0) {
            preCancellationOrderIds = NO_ORDERS;
            preCancellationSize = 0;
            return this;
        }
        ensurePreCancellationCapacity(orderIds.length);
        System.arraycopy(orderIds, 0, preCancellationStorage, 0, orderIds.length);
        preCancellationOrderIds = preCancellationStorage;
        preCancellationSize = orderIds.length;
        return this;
    }

    /**
     * Populate accepted pre-matching cancellations directly from a matcher
     * result.  The plan owns the resulting storage until the pooled event is
     * collected, so no intermediate array or defensive second copy is needed.
     */
    void preCancellationsFromResult(List<CoreCancellationResult> cancellations,
                                     List<Long> authorizedOrderIds) {
        if (cancellations == null || authorizedOrderIds == null) {
            throw new IllegalArgumentException("cancellation inputs are required");
        }
        int count = 0;
        for (CoreCancellationResult cancellation : cancellations) {
            if (!cancellation.accepted()) continue;
            long orderId = cancellation.orderId();
            if (!authorizedOrderIds.contains(orderId)) {
                throw new IllegalStateException("direct matcher cancelled an unauthorized order");
            }
            ensurePreCancellationCapacity(count + 1);
            preCancellationStorage[count++] = orderId;
        }
        preCancellationOrderIds = count == 0 ? NO_ORDERS : preCancellationStorage;
        preCancellationSize = count;
    }

    /**
     * Populate pre-matching cancellations in the admission order.  PLACE and
     * TRIGGER carry the expected IDs separately from the matcher result; write
     * the accepted subset straight into this plan-owned buffer instead of
     * allocating an intermediate array for the coordinator to copy.
     */
    public void preCancellationsFromExpected(List<Long> expectedOrderIds,
                                             List<CoreCancellationResult> cancellations) {
        if (expectedOrderIds == null || cancellations == null) {
            throw new IllegalArgumentException("cancellation inputs are required");
        }
        if (expectedOrderIds.isEmpty()) {
            preCancellationOrderIds = NO_ORDERS;
            preCancellationSize = 0;
            return;
        }
        int count = 0;
        for (Long expectedOrderId : expectedOrderIds) {
            if (expectedOrderId == null) continue;
            long orderId = expectedOrderId;
            boolean accepted = false;
            for (CoreCancellationResult cancellation : cancellations) {
                if (cancellation.accepted() && cancellation.orderId() == orderId) {
                    accepted = true;
                    break;
                }
            }
            if (accepted) {
                ensurePreCancellationCapacity(count + 1);
                preCancellationStorage[count++] = orderId;
            }
        }
        preCancellationOrderIds = count == 0 ? NO_ORDERS : preCancellationStorage;
        preCancellationSize = count;
    }

    private void ensurePreCancellationCapacity(int required) {
        if (required <= 0) return;
        if (preCancellationStorage == null) {
            preCancellationStorage = new long[Math.max(4, required)];
        } else if (preCancellationStorage.length < required) {
            preCancellationStorage = java.util.Arrays.copyOf(preCancellationStorage,
                    Math.max(required, preCancellationStorage.length * 2));
        }
    }
    public int preCancellationCount() { return preCancellationSize; }
    long preCancellationOrderId(int index) { return preCancellationOrderIds[index]; }
    private MatcherSettlementPlan indexLaneEvents(TradingRuntimeState runtime) {
        // Small fills need no index. For deep fills, each maker lane follows only its events;
        // the taker lane still consumes the original order, without copying any fill.
        if (matcherEvents.size() < 8 || Long.bitCount(requiredLaneMask) < 2) return this;
        takerLaneId = runtime.topology().accountLaneId(activeUserId);
        laneEventsIndexed = true;
        if (makerLaneHeads == null || makerLaneHeads.length != runtime.topology().accountLaneCount())
            makerLaneHeads = new int[runtime.topology().accountLaneCount()];
        if (makerLaneNext == null || makerLaneNext.length < matcherEvents.size())
            makerLaneNext = new int[matcherEvents.size()];
        java.util.Arrays.fill(makerLaneHeads, -1);
        for (int index = matcherEvents.size() - 1; index >= 0; index--) {
            MatcherEvent event = matcherEvents.get(index);
            if (event.eventType() != MatcherEventType.TRADE) continue;
            int lane = runtime.topology().accountLaneId(event.matchedOrderUid());
            makerLaneNext[index] = makerLaneHeads[lane];
            makerLaneHeads[lane] = index;
        }
        return this;
    }

    int firstMatcherEvent(int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneHeads[laneId];
        return matcherEvents.isEmpty() ? -1 : 0;
    }

    int nextMatcherEvent(int index, int laneId) {
        if (laneEventsIndexed && laneId != takerLaneId) return makerLaneNext[index];
        return index + 1 < matcherEvents.size() ? index + 1 : -1;
    }

    public boolean matcherEventTouchesLane(int index, int laneId, TradingRuntimeState runtime) {
        // For an indexed deep-fill plan, non-taker Lane traversal is already built from the
        // maker Lane chain. Recomputing both topology lookups here only burns CPU in the hot
        // settlement loop and cannot change the answer.
        if (laneEventsIndexed && laneId != takerLaneId) return true;
        MatcherEvent event = matcherEvents.get(index);
        return runtime.topology().accountLaneId(activeUserId) == laneId
                || runtime.topology().accountLaneId(event.matchedOrderUid()) == laneId;
    }
}
