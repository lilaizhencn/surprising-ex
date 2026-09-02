package com.surprising.aeron.service.state;

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

    private final LongObjectHashMap<LongHashSet> idsByUser = new LongObjectHashMap<>();
    private final Map<String, LongHashSet> idsBySymbol = new HashMap<>();
    private final LongObjectHashMap<CoreOrderState> ordersById = new LongObjectHashMap<>();

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
        return ids == null ? new TreeSet<>() : descending(ids.toArray());
    }

    public NavigableSet<Long> ids(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? new TreeSet<>() : descending(ids.toArray());
    }

    public int count(String symbol) {
        LongHashSet ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? 0 : ids.size();
    }

    public NavigableSet<Long> ids(long userId, String symbol) {
        LongHashSet userIds = idsByUser.get(userId);
        LongHashSet symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return new TreeSet<>();
        LongHashSet source = userIds.size() <= symbolIds.size() ? userIds : symbolIds;
        LongHashSet filter = source == userIds ? symbolIds : userIds;
        TreeSet<Long> result = new TreeSet<>();
        source.forEach(orderId -> { if (filter.contains(orderId)) result.add(orderId); });
        return result.descendingSet();
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
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        LongHashSet source;
        LongHashSet filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = new LongHashSet(ordersById.keySet().toArray());
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
        if (source == null || source.isEmpty()) return new Page(List.of(), 0);
        long[] descending = source.toArray();
        java.util.Arrays.sort(descending);
        List<Long> result = new ArrayList<>(limit);
        long nextCursor = 0;
        for (int index = descending.length - 1; index >= 0; index--) {
            long orderId = descending[index];
            if (beforeOrderId != 0 && orderId >= beforeOrderId
                    || filter != null && !filter.contains(orderId)) continue;
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

    void apply(java.util.List<RuntimeCommitPatch.OrderChange> changes, RuntimeCommitPatch.IdentityView identities) {
        for (RuntimeCommitPatch.OrderChange change : changes) {
            CoreOrderState previous = ordersById.get(change.orderId());
            if (previous != null) remove(previous);
            if (change.businessAfter() != null && change.businessAfter().status() == CoreOrderStatus.OPEN) {
                add(change.businessAfter());
            }
        }
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
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
        ordersById.remove(order.orderId());
        remove(idsByUser, order.userId(), order.orderId());
        remove(idsBySymbol, order.symbol(), order.orderId());
    }

    private static void remove(LongObjectHashMap<LongHashSet> values, long key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.removeKey(key);
    }

    private static <K> void remove(Map<K, LongHashSet> values, K key, long id) {
        LongHashSet ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.remove(key);
    }

    private static NavigableSet<Long> descending(long[] values) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (long value : values) sorted.add(value);
        return sorted.descendingSet();
    }

}
