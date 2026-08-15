package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class TriggerOrderIndex {

    private final Map<String, NavigableSet<Long>> idsBySymbol = new TreeMap<>();
    private final Map<ClientTriggerKey, Long> idsByClient = new java.util.HashMap<>();

    public TriggerOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public Set<Long> ids(String symbol) {
        NavigableSet<Long> ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids.descendingSet());
    }

    public boolean containsClient(long userId, String clientTriggerOrderId) {
        return clientTriggerOrderId != null && !clientTriggerOrderId.isEmpty()
                && idsByClient.containsKey(new ClientTriggerKey(userId, clientTriggerOrderId));
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        Set<Long> changed = after.changedTriggerOrderIdsSince(before);
        if (changed == null) {
            rebuild(after);
            return;
        }
        for (Long id : changed) {
            if (id == null) continue;
            CoreTriggerOrderState previous = before.triggerOrders().get(id);
            CoreTriggerOrderState current = after.triggerOrders().get(id);
            if (previous != null) remove(previous.symbol(), id);
            if (previous != null && !previous.clientTriggerOrderId().isEmpty()) {
                idsByClient.remove(new ClientTriggerKey(previous.userId(), previous.clientTriggerOrderId()));
            }
            if (current != null) add(current.symbol(), id);
            if (current != null && !current.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(current.userId(), current.clientTriggerOrderId()), id);
            }
        }
    }

    public void rebuild(TradingCoreState state) {
        idsBySymbol.clear();
        idsByClient.clear();
        state.triggerOrders().values().forEach(order -> {
            add(order.symbol(), order.triggerOrderId());
            if (!order.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(order.userId(), order.clientTriggerOrderId()), order.triggerOrderId());
            }
        });
    }

    private void add(String symbol, long id) {
        idsBySymbol.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> new TreeSet<>()).add(id);
    }

    private void remove(String symbol, long id) {
        NavigableSet<Long> ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) idsBySymbol.remove(OrderReservation.normalizeSymbol(symbol));
    }

    private record ClientTriggerKey(long userId, String clientTriggerOrderId) {
    }
}
