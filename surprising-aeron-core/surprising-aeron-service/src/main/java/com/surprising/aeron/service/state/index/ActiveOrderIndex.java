package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.TradingCoreState;

import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.api.iterator.LongIterator;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;

public final class ActiveOrderIndex {

    public static final int MAX_PAGE_SIZE = 1_024;
    /**
     * The production window keeps at most a few hundred active orders per process
     * in the hot turnover path.  Starting the primitive maps at this bound avoids
     * repeated rehash/copy cycles when a fixed 128-instrumentId workload warms up.
     * Maps still grow normally for larger deployments.
     */
    private static final int INITIAL_INDEX_CAPACITY = 256;
    private static final int INITIAL_USER_INDEX_CAPACITY = 64;
    /** Symbol buckets are usually short-lived in the 128-instrumentId turnover workload. */
    private static final int INITIAL_SYMBOL_INDEX_CAPACITY = 8;
    private static final NavigableSet<Long> EMPTY_IDS = Collections.emptyNavigableSet();
    private static final LongIterator EMPTY_ITERATOR = new LongIterator() {
        public boolean hasNext() { return false; }
        public long next() { throw new java.util.NoSuchElementException(); }
    };

    // Back-shift deletion avoids tombstone rehashes during bounded order turnover.
    // Independent iterators preserve nested admission/query cursors.
    private final Long2ObjectHashMap<LongHashSet> idsByUser =
            new Long2ObjectHashMap<>(INITIAL_USER_INDEX_CAPACITY, 0.65f, false);
    private final Map<String, LongHashSet> idsBySymbol =
            new org.agrona.collections.Object2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    private final Long2ObjectHashMap<IndexedOrder> ordersById =
            new Long2ObjectHashMap<>(INITIAL_INDEX_CAPACITY, 0.65f, false);
    /** Owner-only wrapper pool; its lifetime follows this index and retains no terminal values. */
    private final ArrayDeque<IndexedOrder> recycledOrders = new ArrayDeque<>(INITIAL_INDEX_CAPACITY);
    /** Empty primitive membership tables carry no route keys; bound both count and retained table size. */
    private final ArrayDeque<LongHashSet> recycledMemberships = new ArrayDeque<>();
    private static final int MAX_RECYCLED_ORDERS = 4_096;
    private static final int MAX_RECYCLED_MEMBERSHIPS = 64, MAX_RECYCLED_MEMBERSHIP_CAPACITY = 4_096;

    /** Owner routing metadata only; authoritative orders remain in Account Lane. */
    public static final class IndexedOrder {
        private long orderId, userId;
        private String instrumentId;
        IndexedOrder(long orderId, long userId, String instrumentId) { reset(orderId, userId, instrumentId); }
        void reset(long orderId, long userId, String instrumentId) {
            this.orderId = orderId; this.userId = userId; this.instrumentId = instrumentId;
        }
        void clear() { orderId = userId = 0; instrumentId = null; }
        public long orderId() { return orderId; }
        public long userId() { return userId; }
        public String instrumentId() { return instrumentId; }
    }
    // Owner-only bounded query scratch; never sized to total book depth.
    private long[] pageScratch;

    public ActiveOrderIndex(TradingCoreState state) {
        this(state, null);
    }

    public ActiveOrderIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state);
    }

    public NavigableSet<Long> ids() {
        return descending(orderIds());
    }

    public NavigableSet<Long> ids(long userId) {
        LongHashSet ids = idsByUser.get(userId);
        return ids == null ? EMPTY_IDS : descending(idsArray(ids));
    }

    public NavigableSet<Long> ids(String instrumentId) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        return ids == null ? EMPTY_IDS : descending(idsArray(ids));
    }

    public int count(String instrumentId) {
        LongHashSet ids = instrumentId == null ? null : idsBySymbol.get(instrumentId);
        if (ids == null) ids = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        return ids == null ? 0 : ids.size();
    }

    public int count() {
        return ordersById.size();
    }

    /**
     * Return the existing active-order holder for routing a cancel command.
     * The holder is owned by this Owner index and must not be retained by a
     * caller after the next index mutation.
     */
    public IndexedOrder activeOrderRoute(long orderId) {
        return ordersById.get(orderId);
    }

    public String activeOrderSymbol(long orderId) {
        IndexedOrder entry = ordersById.get(orderId);
        return entry == null ? null : entry.instrumentId;
    }

    public NavigableSet<Long> ids(long userId, String instrumentId) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
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
    public LongIterator matchingIds(long userId, String instrumentId) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
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
    public long[] sortedIds(long userId, String instrumentId) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
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
     * Primitive descending instrumentId index for settlement/query cursors.  The public {@link #ids(String)}
     * method is retained for compatibility, but callers on a runtime path should not create a
     * boxed {@code TreeSet<Long>} just to iterate the same index.
     */
    public long[] sortedIdsDescending(String instrumentId) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.requireInstrumentId(instrumentId));
        return sortedDescending(ids);
    }

    /** Primitive descending user index for settlement/query cursors. */
    public long[] sortedIdsDescending(long userId) {
        return sortedDescending(idsByUser.get(userId));
    }

    public Page page(long userId, String instrumentId, long beforeOrderId, int limit) {
        if (beforeOrderId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid active-order page");
        }
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : OrderReservation.requireInstrumentId(instrumentId);
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

    public void apply(long orderId, OrderRuntime after, RuntimeIdentityRegistry identities) {
        OrderRuntime current = after != null && after.status() == CoreOrderStatus.OPEN ? after : null;
        applyRoute(orderId, current == null ? 0 : current.userId(),
                current == null ? null : identities.instrumentId(current.symbolId()));
    }

    private void applyRoute(long orderId, long userId, String instrumentId) {
        if (instrumentId == null) {
            // Removal already returns the old entry; do not probe the same order twice.
            IndexedOrder previous = ordersById.remove(orderId);
            if (previous != null) {
                remove(idsByUser, previous.userId(), orderId);
                remove(idsBySymbol, previous.instrumentId(), orderId);
                previous.clear();
                if (recycledOrders.size() < MAX_RECYCLED_ORDERS) recycledOrders.addFirst(previous);
            }
            return;
        }
        IndexedOrder previous = ordersById.get(orderId);
        if (previous == null) {
            IndexedOrder entry = recycledOrders.pollFirst();
            if (entry == null) entry = new IndexedOrder(orderId, userId, instrumentId);
            else entry.reset(orderId, userId, instrumentId);
            add(entry);
            return;
        }
        if (previous.userId() != userId) {
            remove(idsByUser, previous.userId(), orderId);
            add(idsByUser, userId, orderId);
        }
        if (!previous.instrumentId.equals(instrumentId)) {
            remove(idsBySymbol, previous.instrumentId, orderId);
            add(idsBySymbol, instrumentId, orderId);
        }
        previous.reset(orderId, userId, instrumentId);
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        ordersById.clear();
        state.orders().values().stream()
                .filter(ActiveOrderIndex::isActive)
                .forEach(order -> {
                    CoreInstrument instrument = state.instruments().get(order.instrumentId());
                    if (instrument == null) {
                        throw new IllegalStateException("instrument is not registered: " + order.instrumentId());
                    }
                    applyRoute(order.orderId(), order.userId(), order.instrumentId());
                });
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        java.util.Objects.requireNonNull(identities, "runtime identities");
        rebuild(state);
    }

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(IndexedOrder order) {
        ordersById.put(order.orderId(), order);
        add(idsByUser, order.userId(), order.orderId());
        add(idsBySymbol, order.instrumentId(), order.orderId());
    }

    private void remove(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    private <K> void remove(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) { values.remove(key); recycleMembership(ids); }
    }

    private <K> void add(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    private void add(Long2ObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = membership();
            values.put(key, ids);
        }
        ids.add(id);
    }

    private LongHashSet membership() {
        LongHashSet ids = recycledMemberships.pollFirst();
        return ids == null ? new LongHashSet(INITIAL_SYMBOL_INDEX_CAPACITY, 0.65f, false) : ids;
    }

    private void recycleMembership(LongHashSet ids) {
        if (ids.capacity() <= MAX_RECYCLED_MEMBERSHIP_CAPACITY
                && recycledMemberships.size() < MAX_RECYCLED_MEMBERSHIPS) recycledMemberships.addFirst(ids);
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
