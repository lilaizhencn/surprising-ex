package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ActiveOrderIndex {

    private final Map<Long, NavigableSet<Long>> idsByUser = new TreeMap<>();
    private final Map<String, NavigableSet<Long>> idsBySymbol = new TreeMap<>();
    private final NavigableSet<Long> allIds = new TreeSet<>();

    public ActiveOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public NavigableSet<Long> ids() {
        return allIds.descendingSet();
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

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.orders() == after.orders()) return;
        if (!StateMapSupport.isDelta(after.orders())) {
            rebuild(after);
            return;
        }
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
        allIds.clear();
        state.orders().values().stream()
                .filter(ActiveOrderIndex::isActive)
                .forEach(this::add);
    }

    private static boolean isActive(CoreOrderState order) {
        return order != null && order.status() == CoreOrderStatus.OPEN;
    }

    private void add(CoreOrderState order) {
        allIds.add(order.orderId());
        idsByUser.computeIfAbsent(order.userId(), ignored -> new TreeSet<>()).add(order.orderId());
        idsBySymbol.computeIfAbsent(order.symbol(), ignored -> new TreeSet<>()).add(order.orderId());
    }

    private void remove(CoreOrderState order) {
        allIds.remove(order.orderId());
        remove(idsByUser, order.userId(), order.orderId());
        remove(idsBySymbol, order.symbol(), order.orderId());
    }

    private static <K> void remove(Map<K, NavigableSet<Long>> values, K key, long id) {
        NavigableSet<Long> ids = values.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) values.remove(key);
    }
}
