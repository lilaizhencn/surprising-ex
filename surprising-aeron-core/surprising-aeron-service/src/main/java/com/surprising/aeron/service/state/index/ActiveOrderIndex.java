package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimeStateProjector;
import com.surprising.aeron.service.state.TradingCoreState;

import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.api.iterator.LongIterator;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;

public final class ActiveOrderIndex implements RuntimeOrderAdmission.AdmissionOrderIndex {

    public static final int MAX_PAGE_SIZE = 1_024;
    /**
     * The production window keeps at most a few hundred active orders per process
     * in the hot turnover path.  Starting the primitive maps at this bound avoids
     * repeated rehash/copy cycles when a fixed 128-symbol workload warms up.
     * Maps still grow normally for larger deployments.
     */
    private static final int INITIAL_INDEX_CAPACITY = 256;
    private static final int INITIAL_USER_INDEX_CAPACITY = 64;
    private static final NavigableSet<Long> EMPTY_IDS = Collections.emptyNavigableSet();
    private static final LongIterator EMPTY_ITERATOR = new LongIterator() {
        public boolean hasNext() { return false; }
        public long next() { throw new java.util.NoSuchElementException(); }
    };

    // Back-shift deletion avoids tombstone rehashes during bounded order turnover.
    // Independent iterators preserve nested admission/query cursors.
    private final Long2ObjectHashMap<LongHashSet> idsByUser =
            new Long2ObjectHashMap<>(INITIAL_USER_INDEX_CAPACITY, 0.65f, false);
    private final Map<String, LongHashSet> idsBySymbol = new HashMap<>(INITIAL_INDEX_CAPACITY);
    // Owner-maintained participant counts: admission must include potential maker accounts
    // without scanning the order book on every incoming command. Removed with the last order.
    private final Map<String, OrderParticipantIndex> participantsBySymbol = new HashMap<>(INITIAL_INDEX_CAPACITY);
    private final Long2ObjectHashMap<IndexedOrder> ordersById =
            new Long2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    /** Query-only holder reused by the Owner thread; it is never part of index state. */
    private static final ThreadLocal<CounterpartyMasks> COUNTERPARTY_SCRATCH =
            ThreadLocal.withInitial(CounterpartyMasks::new);
    private RuntimeIdentityRegistry recoveryIdentities;

    /** One Owner-owned index entry per active order; updates reuse the Lane's immutable value. */
    private static final class IndexedOrder {
        OrderRuntime order;
        String symbol;
        IndexedOrder(OrderRuntime order, String symbol) { this.order = order; this.symbol = symbol; }
        long orderId() { return order.orderId(); }
        long userId() { return order.userId(); }
        String symbol() { return symbol; }
        com.surprising.aeron.protocol.CoreOrderSide side() { return order.side(); }
        long matchingPriceTicks() { return order.matchingPriceTicks(); }
        long remainingQuantitySteps() { return order.remainingQuantitySteps(); }
        boolean reduceOnly() { return order.reduceOnly(); }
        com.surprising.aeron.protocol.CorePositionSide positionSide() { return order.positionSide(); }
        com.surprising.aeron.protocol.CoreMarginMode marginMode() { return order.marginMode(); }
        CoreOrderState snapshot() { return RuntimeStateMaterializer.orderSnapshot(order, symbol); }
    }
    // Owner-only bounded query scratch; never sized to total book depth.
    private long[] pageScratch;
    /** 账户路由由持久化拓扑决定，不能使用与恢复状态不同的启动参数。 */
    private final com.surprising.aeron.service.state.LaneTopology topology;
    private final RuntimeOrderAdmission.AdmissionSummary admissionSummary =
            new RuntimeOrderAdmission.AdmissionSummary();

    public ActiveOrderIndex(TradingCoreState state) {
        this(state, null, com.surprising.aeron.service.state.LaneTopology.productionDefault());
    }

    public ActiveOrderIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        this(state, identities, com.surprising.aeron.service.state.LaneTopology.productionDefault());
    }

    public ActiveOrderIndex(TradingCoreState state, RuntimeIdentityRegistry identities,
                            com.surprising.aeron.service.state.LaneTopology topology) {
        this.topology = java.util.Objects.requireNonNull(topology);
        recoveryIdentities = identities == null ? new RuntimeIdentityRegistry() : identities;
        rebuild(state);
    }

    public NavigableSet<Long> ids() {
        return descending(orderIds());
    }

    public Collection<CoreOrderState> orders() {
        ArrayList<CoreOrderState> result = new ArrayList<>(ordersById.size());
        long[] orderIds = orderIds();
        java.util.Arrays.sort(orderIds);
        for (long orderId : orderIds) {
            result.add(ordersById.get(orderId).snapshot());
        }
        return Collections.unmodifiableCollection(result);
    }

    public NavigableSet<Long> ids(long userId) {
        LongHashSet ids = idsByUser.get(userId);
        return ids == null ? EMPTY_IDS : descending(idsArray(ids));
    }

    public NavigableSet<Long> ids(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? EMPTY_IDS : descending(idsArray(ids));
    }

    public int count(String symbol) {
        LongHashSet ids = symbol == null ? null : idsBySymbol.get(symbol);
        if (ids == null) ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? 0 : ids.size();
    }

    public int count() {
        return ordersById.size();
    }

    public long counterpartyMask(String symbol, com.surprising.aeron.protocol.CoreOrderSide side, long limitPrice) {
        OrderParticipantIndex participants = participantsBySymbol.get(symbol);
        return participants == null ? 0 : participants.counterparties(side, limitPrice);
    }

    public long counterpartyLaneMask(String symbol, com.surprising.aeron.protocol.CoreOrderSide side, long limitPrice) {
        OrderParticipantIndex participants = participantsBySymbol.get(symbol);
        return participants == null ? 0 : participants.counterpartyLanes(side, limitPrice);
    }

    /** Computes both admission masks in one crossing-price tree walk. */
    CounterpartyMasks counterpartyMasks(String symbol,
                                        com.surprising.aeron.protocol.CoreOrderSide side,
                                        long limitPrice) {
        CounterpartyMasks result = COUNTERPARTY_SCRATCH.get();
        OrderParticipantIndex participants = participantsBySymbol.get(symbol);
        if (participants == null) {
            result.accountMask = 0;
            result.laneMask = 0;
        } else {
            participants.computeCounterpartyMasks(side, limitPrice);
            result.accountMask = participants.computedCounterpartyMask();
            result.laneMask = participants.computedCounterpartyLaneMask();
        }
        return result;
    }

    static final class CounterpartyMasks {
        long accountMask;
        long laneMask;
    }

    public boolean hasCounterparty(String symbol, com.surprising.aeron.protocol.CoreOrderSide side,
                                   long price, long userId) {
        OrderParticipantIndex index = participantsBySymbol.get(symbol);
        return index != null && index.contains(side, price, userId);
    }

    public boolean counterpartiesOverlap(String firstSymbol, com.surprising.aeron.protocol.CoreOrderSide firstSide,
                                         long firstPrice, String secondSymbol,
                                         com.surprising.aeron.protocol.CoreOrderSide secondSide, long secondPrice) {
        OrderParticipantIndex first = participantsBySymbol.get(firstSymbol);
        OrderParticipantIndex second = participantsBySymbol.get(secondSymbol);
        return first != null && second != null && first.overlaps(firstSide, firstPrice, second, secondSide, secondPrice);
    }

    /** Full immutable query view; admission/routing uses the existing runtime reference below. */
    public CoreOrderState activeOrder(long orderId) {
        IndexedOrder entry = ordersById.get(orderId);
        return entry == null ? null : entry.snapshot();
    }

    public OrderRuntime activeOrderRuntime(long orderId) {
        IndexedOrder entry = ordersById.get(orderId);
        return entry == null ? null : entry.order;
    }

    public String activeOrderSymbol(long orderId) {
        IndexedOrder entry = ordersById.get(orderId);
        return entry == null ? null : entry.symbol;
    }

    public NavigableSet<Long> ids(long userId, String symbol) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return EMPTY_IDS;
        LongHashSet source = userIds.size() <= symbolIds.size() ? userIds : symbolIds;
        LongHashSet filter = source == userIds ? symbolIds : userIds;
        TreeSet<Long> result = new TreeSet<>();
        source.forEachLong(orderId -> { if (filter.contains(orderId)) result.add(orderId); });
        return result.descendingSet();
    }

    /**
     * Owner-only, unordered primitive intersection. Consume before mutating this index.
     * Each caller owns its cursor; nested inspections do not overwrite shared scratch.
     */
    public LongIterator matchingIds(long userId, String symbol) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return EMPTY_ITERATOR;
        LongHashSet source = userIds.size() <= symbolIds.size() ? userIds : symbolIds;
        LongHashSet filter = source == userIds ? symbolIds : userIds;
        var sourceIterator = source.iterator();
        return new LongIterator() {
            private boolean ready;
            private long next;
            public boolean hasNext() {
                while (!ready && sourceIterator.hasNext()) {
                    long candidate = sourceIterator.nextValue();
                    if (filter.contains(candidate)) {
                        next = candidate;
                        ready = true;
                    }
                }
                return ready;
            }
            public long next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                ready = false;
                return next;
            }
        };
    }

    /** Primitive deterministic intersection for callers requiring a materialized sorted result. */
    public long[] sortedIds(long userId, String symbol) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return new long[0];
        LongHashSet source = userIds.size() <= symbolIds.size() ? userIds : symbolIds;
        LongHashSet filter = source == userIds ? symbolIds : userIds;
        long[] values = new long[source.size()];
        int size = 0;
        var iterator = source.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            if (filter.contains(orderId)) values[size++] = orderId;
        }
        if (size != values.length) values = java.util.Arrays.copyOf(values, size);
        java.util.Arrays.sort(values);
        return values;
    }

    /**
     * Primitive descending symbol index for settlement/query cursors.  The public {@link #ids(String)}
     * method is retained for compatibility, but callers on a runtime path should not create a
     * boxed {@code TreeSet<Long>} just to iterate the same index.
     */
    public long[] sortedIdsDescending(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return sortedDescending(ids);
    }

    /** Primitive descending user index for settlement/query cursors. */
    public long[] sortedIdsDescending(long userId) {
        return sortedDescending(idsByUser.get(userId));
    }

    public long pendingQuantity(long userId, String symbol,
                                com.surprising.aeron.protocol.CorePositionSide positionSide,
                                com.surprising.aeron.protocol.CoreOrderSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        LongHashSet ids = idsByUser.get(userId);
        if (ids == null) return 0;
        long total = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            IndexedOrder order = ordersById.get(orderId);
            if (order != null && !order.reduceOnly() && order.symbol().equals(normalized)
                    && order.positionSide() == positionSide && order.side() == side) {
                total = Math.addExact(total, order.remainingQuantitySteps());
            }
        }
        return total;
    }

    @Override
    public RuntimeOrderAdmission.AdmissionSummary inspect(
            long userId, String symbol,
            com.surprising.aeron.protocol.CorePositionSide positionSide,
            com.surprising.aeron.protocol.CoreOrderSide side,
            com.surprising.aeron.protocol.CoreMarginMode conflictingMarginMode) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        LongHashSet ids = idsByUser.get(userId);
        if (ids == null) return admissionSummary.set(0, 0, 0);
        long pendingQuantity = 0;
        long reduceOnlyQuantity = 0;
        int marginModeCount = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            IndexedOrder order = ordersById.get(iterator.nextValue());
            if (order == null || !order.symbol().equals(normalized)) continue;
            if (order.reduceOnly() && order.side() == side) {
                reduceOnlyQuantity = Math.addExact(
                        reduceOnlyQuantity, order.remainingQuantitySteps());
            } else if (!order.reduceOnly() && order.positionSide() == positionSide
                    && order.side() == side) {
                pendingQuantity = Math.addExact(pendingQuantity, order.remainingQuantitySteps());
            }
            if (order.positionSide() == positionSide
                    && order.marginMode() == conflictingMarginMode) {
                marginModeCount = Math.incrementExact(marginModeCount);
            }
        }
        return admissionSummary.set(pendingQuantity, reduceOnlyQuantity, marginModeCount);
    }

    public long reduceOnlyQuantity(long userId, String symbol,
                                   com.surprising.aeron.protocol.CoreOrderSide side) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        LongHashSet ids = idsByUser.get(userId);
        if (ids == null) return 0;
        long total = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            IndexedOrder order = ordersById.get(orderId);
            if (order != null && order.reduceOnly() && order.symbol().equals(normalized)
                    && order.side() == side) {
                total = Math.addExact(total, order.remainingQuantitySteps());
            }
        }
        return total;
    }

    public boolean hasDifferentMarginMode(long userId, String symbol,
                                          com.surprising.aeron.protocol.CorePositionSide positionSide,
                                          com.surprising.aeron.protocol.CoreMarginMode marginMode) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        LongHashSet ids = idsByUser.get(userId);
        if (ids == null) return false;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            IndexedOrder order = ordersById.get(orderId);
            if (order != null && order.symbol().equals(normalized)
                    && order.positionSide() == positionSide && order.marginMode() != marginMode) {
                return true;
            }
        }
        return false;
    }

    public int marginModeCount(long userId, String symbol,
                               com.surprising.aeron.protocol.CorePositionSide positionSide,
                               com.surprising.aeron.protocol.CoreMarginMode marginMode) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        LongHashSet ids = idsByUser.get(userId);
        if (ids == null) return 0;
        int count = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long orderId = iterator.nextValue();
            IndexedOrder order = ordersById.get(orderId);
            if (order != null && order.symbol().equals(normalized)
                    && order.positionSide() == positionSide && order.marginMode() == marginMode) {
                count = Math.incrementExact(count);
            }
        }
        return count;
    }

    public Page page(long userId, String symbol, long beforeOrderId, int limit) {
        if (beforeOrderId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid active-order page");
        }
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : OrderReservation.normalizeSymbol(symbol);
        LongHashSet source;
        Long2ObjectHashMap<IndexedOrder>.KeyIterator allOrders = null;
        LongHashSet filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = null;
            allOrders = ordersById.keySet().iterator();
        } else if (userId == 0) {
            source = idsBySymbol.get(normalizedSymbol);
        } else if (normalizedSymbol == null || normalizedSymbol.isBlank()) {
            source = idsByUser.get(userId);
        } else {
            LongHashSet userIds = idsByUser.get(userId);
            LongHashSet symbolIds = idsBySymbol.get(normalizedSymbol);
            if (userIds == null || symbolIds == null) return new Page(List.of(), 0);
            if (userIds.size() <= symbolIds.size()) {
                source = userIds;
                filter = symbolIds;
            } else {
                source = symbolIds;
                filter = userIds;
            }
        }
        if (allOrders == null && (source == null || source.isEmpty())) return new Page(List.of(), 0);
        if (pageScratch == null) pageScratch = new long[MAX_PAGE_SIZE + 1];
        long[] descending = pageScratch;
        int size = 0;
        var iterator = allOrders == null ? source.iterator() : null;
        while (allOrders != null ? allOrders.hasNext() : iterator.hasNext()) {
            long orderId = allOrders != null ? allOrders.nextLong() : iterator.nextValue();
            if (beforeOrderId != 0 && orderId >= beforeOrderId
                    || filter != null && !filter.contains(orderId)) continue;
            if (size < limit + 1) {
                int child = size++;
                while (child > 0) {
                    int parent = (child - 1) >>> 1;
                    if (descending[parent] <= orderId) break;
                    descending[child] = descending[parent];
                    child = parent;
                }
                descending[child] = orderId;
            } else if (orderId > descending[0]) {
                int parent = 0;
                while (parent * 2 + 1 < size) {
                    int child = parent * 2 + 1;
                    if (child + 1 < size && descending[child + 1] < descending[child]) child++;
                    if (descending[child] >= orderId) break;
                    descending[parent] = descending[child];
                    parent = child;
                }
                descending[parent] = orderId;
            }
        }
        java.util.Arrays.sort(descending, 0, size);
        List<Long> result = new ArrayList<>(limit);
        long nextCursor = 0;
        for (int index = size - 1; index >= 0; index--) {
            long orderId = descending[index];
            if (result.size() < limit) result.add(orderId);
            else { nextCursor = result.getLast(); break; }
        }
        return new Page(List.copyOf(result), nextCursor);
    }

    public record Page(List<Long> orderIds, long nextCursorOrderId) {
        public Page {
            if (orderIds == null || nextCursorOrderId < 0) throw new IllegalArgumentException("invalid page");
            orderIds = List.copyOf(orderIds);
        }
    }

    void apply(java.util.List<RuntimeFactFrame.OrderChange> changes, RuntimeFactFrame.IdentityView identities) {
        for (RuntimeFactFrame.OrderChange change : changes) {
            apply(change.orderId(), change.after(), identities);
        }
    }

    public void apply(long orderId, OrderRuntime after, RuntimeFactFrame.IdentityView identities) {
        OrderRuntime current = after != null && after.status() == CoreOrderStatus.OPEN ? after : null;
        applyRuntime(orderId, current, current == null ? null : identities.symbol(current.symbolId()));
    }

    /** Snapshot ingestion belongs to recovery and detached snapshot callers, never Lane completion. */
    public void applySnapshot(long orderId, CoreOrderState current) {
        applyRuntime(orderId, current == null ? null : RuntimeStateProjector.toRuntimeOrder(current, recoveryIdentities),
                current == null ? null : current.symbol());
    }

    private void applyRuntime(long orderId, OrderRuntime current, String symbol) {
        IndexedOrder previous = ordersById.get(orderId);
        if (previous == null) {
            if (current != null) add(new IndexedOrder(current, symbol));
            return;
        }
        if (current == null) {
            remove(previous);
            return;
        }
        boolean changedParticipant = previous.userId() != current.userId() || !previous.symbol.equals(symbol)
                || previous.side() != current.side() || previous.matchingPriceTicks() != current.matchingPriceTicks();
        if (changedParticipant) removeParticipant(previous);
        if (previous.userId() != current.userId()) {
            remove(idsByUser, previous.userId(), orderId);
            add(idsByUser, current.userId(), orderId);
        }
        if (!previous.symbol.equals(symbol)) {
            remove(idsBySymbol, previous.symbol, orderId);
            add(idsBySymbol, symbol, orderId);
        }
        previous.order = current;
        previous.symbol = symbol;
        if (changedParticipant) addParticipant(previous);
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        participantsBySymbol.clear();
        ordersById.clear();
        state.orders().values().stream()
                .filter(ActiveOrderIndex::isActive)
                .forEach(order -> applySnapshot(order.orderId(), order));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        recoveryIdentities = java.util.Objects.requireNonNull(identities, "runtime identities");
        rebuild(state);
    }

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(IndexedOrder order) {
        addParticipant(order);
        ordersById.put(order.orderId(), order);
        LongHashSet userIds = idsByUser.get(order.userId());
        if (userIds == null) {
            // User buckets are removed when the last order leaves.  Keep their
            // per-user allocation small so short-lived users do not pay the
            // process-wide active-order capacity.
            userIds = new LongHashSet(8, 0.65f, false);
            idsByUser.put(order.userId(), userIds);
        }
        userIds.add(order.orderId());
        idsBySymbol.computeIfAbsent(order.symbol(), ignored ->
                new LongHashSet(INITIAL_INDEX_CAPACITY, 0.65f, false)).add(order.orderId());
    }

    private void remove(IndexedOrder order) {
        removeParticipant(order);
        ordersById.remove(order.orderId());
        remove(idsByUser, order.userId(), order.orderId());
        remove(idsBySymbol, order.symbol(), order.orderId());
    }

    private void addParticipant(IndexedOrder order) {
        participantsBySymbol.computeIfAbsent(order.symbol(), ignored -> new OrderParticipantIndex(topology)).add(order.side(), order.matchingPriceTicks(), order.userId());
    }

    private void removeParticipant(IndexedOrder order) {
        OrderParticipantIndex participants = participantsBySymbol.get(order.symbol());
        if (participants == null)
            throw new IllegalStateException("active order participant count underflow");
        participants.remove(order.side(), order.matchingPriceTicks(), order.userId());
        if (participants.mask() == 0) participantsBySymbol.remove(order.symbol());
    }

    private static void remove(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.remove(key);
    }

    private static void add(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = new LongHashSet(8, 0.65f, false);
            values.put(key, ids);
        }
        ids.add(id);
    }

    private static <K> void remove(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.remove(key);
    }

    private static <K> void add(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = new LongHashSet(8, 0.65f, false);
            values.put(key, ids);
        }
        ids.add(id);
    }

    private long[] orderIds() {
        long[] ids = new long[ordersById.size()];
        var iterator = ordersById.keySet().iterator();
        for (int i = 0; iterator.hasNext(); i++) ids[i] = iterator.nextLong();
        return ids;
    }

    private static long[] idsArray(LongHashSet values) {
        long[] ids = new long[values.size()];
        var iterator = values.iterator();
        for (int i = 0; iterator.hasNext(); i++) ids[i] = iterator.nextValue();
        return ids;
    }

    private static NavigableSet<Long> descending(long[] values) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (long value : values) sorted.add(value);
        return sorted.descendingSet();
    }

    private static long[] sortedDescending(LongHashSet values) {
        if (values == null || values.isEmpty()) return new long[0];
        long[] sorted = idsArray(values);
        java.util.Arrays.sort(sorted);
        for (int left = 0, right = sorted.length - 1; left < right; left++, right--) {
            long value = sorted[left];
            sorted[left] = sorted[right];
            sorted[right] = value;
        }
        return sorted;
    }

}
