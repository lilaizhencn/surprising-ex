package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ActiveOrderIndex {

    public static final int MAX_PAGE_SIZE = 1_024;

    private final Map<Long, NavigableSet<Long>> idsByUser = new TreeMap<>();
    private final Map<String, NavigableSet<Long>> idsBySymbol = new TreeMap<>();
    private final NavigableMap<Long, CoreOrderState> ordersById = new TreeMap<>();
    private final Map<PendingKey, Long> pendingQuantity = new HashMap<>();
    private final Map<ReduceKey, Long> reduceOnlyQuantity = new HashMap<>();
    private final Map<MarginKey, Integer> marginModeCounts = new HashMap<>();

    public ActiveOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public NavigableSet<Long> ids() {
        return ordersById.descendingKeySet();
    }

    public Collection<CoreOrderState> orders() {
        return Collections.unmodifiableCollection(ordersById.values());
    }

    public NavigableSet<Long> ids(long userId) {
        NavigableSet<Long> ids = idsByUser.get(userId);
        return ids == null ? new TreeSet<>() : ids.descendingSet();
    }

    public NavigableSet<Long> ids(String symbol) {
        NavigableSet<Long> ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? new TreeSet<>() : ids.descendingSet();
    }

    public NavigableSet<Long> ids(long userId, String symbol) {
        NavigableSet<Long> userIds = idsByUser.get(userId);
        NavigableSet<Long> symbolIds = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (userIds == null || symbolIds == null) return new TreeSet<>();
        TreeSet<Long> result = new TreeSet<>(userIds);
        result.retainAll(symbolIds);
        return result.descendingSet();
    }

    public long pendingQuantity(long userId, String symbol,
                                com.surprising.aeron.protocol.CorePositionSide positionSide,
                                com.surprising.aeron.protocol.CoreOrderSide side) {
        return pendingQuantity.getOrDefault(new PendingKey(userId, OrderReservation.normalizeSymbol(symbol),
                positionSide, side), 0L);
    }

    public long reduceOnlyQuantity(long userId, String symbol,
                                   com.surprising.aeron.protocol.CoreOrderSide side) {
        return reduceOnlyQuantity.getOrDefault(
                new ReduceKey(userId, OrderReservation.normalizeSymbol(symbol), side), 0L);
    }

    public boolean hasDifferentMarginMode(long userId, String symbol,
                                          com.surprising.aeron.protocol.CorePositionSide positionSide,
                                          com.surprising.aeron.protocol.CoreMarginMode marginMode) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        for (com.surprising.aeron.protocol.CoreMarginMode candidate
                : com.surprising.aeron.protocol.CoreMarginMode.values()) {
            if (candidate != marginMode && marginModeCounts.getOrDefault(
                    new MarginKey(userId, normalized, positionSide, candidate), 0) > 0) {
                return true;
            }
        }
        return false;
    }

    public Page page(long userId, String symbol, long beforeOrderId, int limit) {
        if (beforeOrderId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid active-order page");
        }
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        NavigableSet<Long> source;
        NavigableSet<Long> filter = null;
        if (userId == 0 && (normalizedSymbol == null || normalizedSymbol.isBlank())) {
            source = ordersById.navigableKeySet();
        } else if (userId == 0) {
            source = idsBySymbol.get(normalizedSymbol);
        } else if (normalizedSymbol == null || normalizedSymbol.isBlank()) {
            source = idsByUser.get(userId);
        } else {
            NavigableSet<Long> userIds = idsByUser.get(userId);
            NavigableSet<Long> symbolIds = idsBySymbol.get(normalizedSymbol);
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
        NavigableSet<Long> descending = beforeOrderId == 0
                ? source.descendingSet()
                : source.headSet(beforeOrderId, false).descendingSet();
        Iterator<Long> iterator = descending.iterator();
        List<Long> result = new ArrayList<>(limit);
        while (iterator.hasNext() && result.size() < limit) {
            long orderId = iterator.next();
            if (filter == null || filter.contains(orderId)) result.add(orderId);
        }
        long nextCursor = 0;
        while (iterator.hasNext()) {
            long orderId = iterator.next();
            if (filter == null || filter.contains(orderId)) {
                nextCursor = result.getLast();
                break;
            }
        }
        return new Page(List.copyOf(result), nextCursor);
    }

    public record Page(List<Long> orderIds, long nextCursorOrderId) {
        public Page {
            if (orderIds == null || nextCursorOrderId < 0) throw new IllegalArgumentException("invalid page");
            orderIds = List.copyOf(orderIds);
        }
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.orders() == after.orders()) return;
        StateMapSupport.requireDeltaLineage(before.orders(), after.orders(), "active order index orders");
        Set<Long> changed = after.changedOrderIds();
        for (Long id : changed) {
            if (id == null) continue;
            CoreOrderState previous = before.order(id);
            CoreOrderState current = after.order(id);
            if (isActive(previous)) remove(previous);
            if (isActive(current)) add(current);
        }
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        ordersById.clear();
        pendingQuantity.clear();
        reduceOnlyQuantity.clear();
        marginModeCounts.clear();
        state.orders().values().stream()
                .filter(ActiveOrderIndex::isActive)
                .forEach(this::add);
    }

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(CoreOrderState order) {
        ordersById.put(order.orderId(), order);
        idsByUser.computeIfAbsent(order.userId(), ignored -> new TreeSet<>()).add(order.orderId());
        idsBySymbol.computeIfAbsent(order.symbol(), ignored -> new TreeSet<>()).add(order.orderId());
        if (order.reduceOnly()) {
            add(reduceOnlyQuantity, new ReduceKey(order.userId(), order.symbol(), order.side()),
                    order.remainingQuantitySteps());
        } else {
            add(pendingQuantity, new PendingKey(order.userId(), order.symbol(), order.positionSide(), order.side()),
                    order.remainingQuantitySteps());
        }
        marginModeCounts.merge(new MarginKey(order.userId(), order.symbol(), order.positionSide(), order.marginMode()),
                1, Math::addExact);
    }

    private void remove(CoreOrderState order) {
        ordersById.remove(order.orderId());
        remove(idsByUser, order.userId(), order.orderId());
        remove(idsBySymbol, order.symbol(), order.orderId());
        if (order.reduceOnly()) {
            subtract(reduceOnlyQuantity, new ReduceKey(order.userId(), order.symbol(), order.side()),
                    order.remainingQuantitySteps());
        } else {
            subtract(pendingQuantity,
                    new PendingKey(order.userId(), order.symbol(), order.positionSide(), order.side()),
                    order.remainingQuantitySteps());
        }
        MarginKey marginKey = new MarginKey(order.userId(), order.symbol(), order.positionSide(), order.marginMode());
        int nextCount = Math.subtractExact(marginModeCounts.getOrDefault(marginKey, 0), 1);
        if (nextCount == 0) marginModeCounts.remove(marginKey); else marginModeCounts.put(marginKey, nextCount);
    }

    private static <K> void add(Map<K, Long> values, K key, long quantity) {
        values.put(key, Math.addExact(values.getOrDefault(key, 0L), quantity));
    }

    private static <K> void subtract(Map<K, Long> values, K key, long quantity) {
        long next = Math.subtractExact(values.getOrDefault(key, 0L), quantity);
        if (next < 0) throw new IllegalStateException("negative active order aggregate");
        if (next == 0) values.remove(key); else values.put(key, next);
    }

    private static <K> void remove(Map<K, NavigableSet<Long>> values, K key, long id) {
        NavigableSet<Long> ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.remove(key);
    }

    private record PendingKey(long userId, String symbol,
                              com.surprising.aeron.protocol.CorePositionSide positionSide,
                              com.surprising.aeron.protocol.CoreOrderSide side) {
    }

    private record ReduceKey(long userId, String symbol,
                             com.surprising.aeron.protocol.CoreOrderSide side) {
    }

    private record MarginKey(long userId, String symbol,
                             com.surprising.aeron.protocol.CorePositionSide positionSide,
                             com.surprising.aeron.protocol.CoreMarginMode marginMode) {
    }
}
