package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.OrderReservation;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingDependencyMask;

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
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class ActiveOrderIndex implements RuntimeOrderAdmission.AdmissionOrderIndex {

    public static final int MAX_PAGE_SIZE = 1_024;
    private static final NavigableSet<Long> EMPTY_IDS = Collections.emptyNavigableSet();
    private static final LongIterator EMPTY_ITERATOR = new LongIterator() {
        public boolean hasNext() { return false; }
        public long next() { throw new java.util.NoSuchElementException(); }
    };

    private final LongObjectHashMap<LongHashSet> idsByUser = new LongObjectHashMap<>();
    private final Map<String, LongHashSet> idsBySymbol = new HashMap<>();
    // Owner-maintained participant counts: admission must include potential maker accounts
    // without scanning the order book on every incoming command. Removed with the last order.
    private final Map<String, ParticipantMask> participantsBySymbol = new HashMap<>();
    private final LongObjectHashMap<CoreOrderState> ordersById = new LongObjectHashMap<>();
    // Owner-only bounded query scratch; never sized to total book depth.
    private long[] pageScratch;
    private final RuntimeOrderAdmission.AdmissionSummary admissionSummary =
            new RuntimeOrderAdmission.AdmissionSummary();

    public ActiveOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public ActiveOrderIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state);
    }

    public NavigableSet<Long> ids() {
        return descending(ordersById.keySet().toArray());
    }

    public Collection<CoreOrderState> orders() {
        ArrayList<CoreOrderState> result = new ArrayList<>(ordersById.size());
        long[] orderIds = ordersById.keySet().toArray();
        java.util.Arrays.sort(orderIds);
        for (long orderId : orderIds) {
            result.add(ordersById.get(orderId));
        }
        return Collections.unmodifiableCollection(result);
    }

    public NavigableSet<Long> ids(long userId) {
        LongHashSet ids = idsByUser.get(userId);
        return ids == null ? EMPTY_IDS : descending(ids.toArray());
    }

    public NavigableSet<Long> ids(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? EMPTY_IDS : descending(ids.toArray());
    }

    public int count(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? 0 : ids.size();
    }

    public int count() {
        return ordersById.size();
    }

    public long participantMask(String symbol) {
        ParticipantMask participants = participantsBySymbol.get(symbol);
        return participants == null ? 0 : participants.mask;
    }

    public CoreOrderState activeOrder(long orderId) {
        return ordersById.get(orderId);
    }

    public NavigableSet<Long> ids(long userId, String symbol) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return EMPTY_IDS;
        LongHashSet source = userIds.size() <= symbolIds.size() ? userIds : symbolIds;
        LongHashSet filter = source == userIds ? symbolIds : userIds;
        TreeSet<Long> result = new TreeSet<>();
        source.forEach(orderId -> { if (filter.contains(orderId)) result.add(orderId); });
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
        LongIterator sourceIterator = source.longIterator();
        return new LongIterator() {
            private boolean ready;
            private long next;
            public boolean hasNext() {
                while (!ready && sourceIterator.hasNext()) {
                    long candidate = sourceIterator.next();
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
        LongIterator iterator = source.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
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
        LongIterator iterator = ids.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
            CoreOrderState order = ordersById.get(orderId);
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
        LongIterator iterator = ids.longIterator();
        while (iterator.hasNext()) {
            CoreOrderState order = ordersById.get(iterator.next());
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
        LongIterator iterator = ids.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
            CoreOrderState order = ordersById.get(orderId);
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
        LongIterator iterator = ids.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
            CoreOrderState order = ordersById.get(orderId);
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
        LongIterator iterator = ids.longIterator();
        while (iterator.hasNext()) {
            long orderId = iterator.next();
            CoreOrderState order = ordersById.get(orderId);
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
        LongIterator allOrders = null;
        LongHashSet filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = null;
            allOrders = ordersById.keySet().longIterator();
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
        LongIterator iterator = allOrders == null ? source.longIterator() : allOrders;
        while (iterator.hasNext()) {
            long orderId = iterator.next();
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
        CoreOrderState current = after != null && after.status() == CoreOrderStatus.OPEN
                ? RuntimeStateMaterializer.orderSnapshot(after, identities) : null;
        applySnapshot(orderId, current);
    }

    public void applySnapshot(long orderId, CoreOrderState current) {
        CoreOrderState previous = ordersById.get(orderId);
        if (previous == null) {
            if (current != null) add(current);
            return;
        }
        if (current == null) {
            remove(previous);
            return;
        }
        ordersById.put(orderId, current);
        if (previous.userId() != current.userId() || !previous.symbol().equals(current.symbol())) {
            removeParticipant(previous);
            addParticipant(current);
        }
        if (previous.userId() != current.userId()) {
            remove(idsByUser, previous.userId(), orderId);
            add(idsByUser, current.userId(), orderId);
        }
        if (!previous.symbol().equals(current.symbol())) {
            remove(idsBySymbol, previous.symbol(), orderId);
            add(idsBySymbol, current.symbol(), orderId);
        }
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        participantsBySymbol.clear();
        ordersById.clear();
        state.orders().values().stream()
                .filter(ActiveOrderIndex::isActive)
                .forEach(this::add);
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state);
    }

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(CoreOrderState order) {
        addParticipant(order);
        ordersById.put(order.orderId(), order);
        LongHashSet userIds = idsByUser.get(order.userId());
        if (userIds == null) {
            userIds = new LongHashSet();
            idsByUser.put(order.userId(), userIds);
        }
        userIds.add(order.orderId());
        idsBySymbol.computeIfAbsent(order.symbol(), ignored -> new LongHashSet()).add(order.orderId());
    }

    private void remove(CoreOrderState order) {
        removeParticipant(order);
        ordersById.remove(order.orderId());
        remove(idsByUser, order.userId(), order.orderId());
        remove(idsBySymbol, order.symbol(), order.orderId());
    }

    private void addParticipant(CoreOrderState order) {
        ParticipantMask participants = participantsBySymbol.computeIfAbsent(order.symbol(), ignored -> new ParticipantMask());
        int partition = TradingDependencyMask.partition(order.userId());
        participants.counts[partition] = Math.incrementExact(participants.counts[partition]);
        participants.mask |= 1L << partition;
    }

    private void removeParticipant(CoreOrderState order) {
        ParticipantMask participants = participantsBySymbol.get(order.symbol());
        int partition = TradingDependencyMask.partition(order.userId());
        if (participants == null || participants.counts[partition] <= 0)
            throw new IllegalStateException("active order participant count underflow");
        if (--participants.counts[partition] == 0) participants.mask &= ~(1L << partition);
        if (participants.mask == 0) participantsBySymbol.remove(order.symbol());
    }

    private static final class ParticipantMask {
        private final int[] counts = new int[64];
        private long mask;
    }

    private static void remove(LongObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.removeKey(key);
    }

    private static void add(LongObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) {
            ids = new LongHashSet();
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
            ids = new LongHashSet();
            values.put(key, ids);
        }
        ids.add(id);
    }

    private static NavigableSet<Long> descending(long[] values) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (long value : values) sorted.add(value);
        return sorted.descendingSet();
    }

    private static long[] sortedDescending(LongHashSet values) {
        if (values == null || values.isEmpty()) return new long[0];
        long[] sorted = values.toArray();
        java.util.Arrays.sort(sorted);
        for (int left = 0, right = sorted.length - 1; left < right; left++, right--) {
            long value = sorted[left];
            sorted[left] = sorted[right];
            sorted[right] = value;
        }
        return sorted;
    }

}
