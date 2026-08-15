package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;

public final class TriggerOrderIndex {

    private final Map<String, NavigableSet<Long>> idsBySymbol = new TreeMap<>();
    private final Map<String, Map<CoreTriggerOrderStatus, NavigableSet<Long>>> idsBySymbolStatus = new TreeMap<>();
    private final Map<CoreTriggerOrderStatus, NavigableSet<Long>> idsByStatus = new java.util.EnumMap<>(CoreTriggerOrderStatus.class);
    private final Map<Long, NavigableSet<Long>> idsByUser = new TreeMap<>();
    private final NavigableSet<Long> allIds = new TreeSet<>();
    private final Map<ClientTriggerKey, Long> idsByClient = new java.util.HashMap<>();
    private final Map<TriggerPositionKey, NavigableSet<Long>> idsByPosition = new java.util.HashMap<>();
    private final Map<TriggerOcoKey, NavigableSet<Long>> idsByOco = new java.util.HashMap<>();
    private final Map<String, NavigableMapByPrice> idsByPrice = new TreeMap<>();
    private final NavigableMap<Long, NavigableSet<Long>> idsByExpiry = new TreeMap<>();

    public TriggerOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public Set<Long> ids(String symbol) {
        NavigableSet<Long> ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids.descendingSet());
    }

    public Set<Long> ids() {
        return Collections.unmodifiableNavigableSet(allIds.descendingSet());
    }

    public Set<Long> ids(CoreTriggerOrderStatus status) {
        NavigableSet<Long> ids = idsByStatus.get(status);
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids.descendingSet());
    }

    public Set<Long> ids(String symbol, CoreTriggerOrderStatus status) {
        Map<CoreTriggerOrderStatus, NavigableSet<Long>> byStatus = idsBySymbolStatus.get(
                OrderReservation.normalizeSymbol(symbol));
        NavigableSet<Long> ids = byStatus == null ? null : byStatus.get(status);
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids.descendingSet());
    }

    public Set<Long> ids(long userId) {
        NavigableSet<Long> ids = idsByUser.get(userId);
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids.descendingSet());
    }

    public Set<Long> candidates(String symbol, long markPriceTicks) {
        NavigableMapByPrice price = idsByPrice.get(OrderReservation.normalizeSymbol(symbol));
        if (price == null) return Set.of();
        NavigableSet<Long> result = new TreeSet<>();
        price.greaterOrEqual.headMap(markPriceTicks, true).values().forEach(result::addAll);
        price.lessOrEqual.tailMap(markPriceTicks, true).values().forEach(result::addAll);
        result.addAll(price.trailing);
        return Collections.unmodifiableNavigableSet(result);
    }

    public Set<Long> expired(long epochMillis, int limit) {
        if (epochMillis <= 0 || limit <= 0) return Set.of();
        TreeSet<Long> result = new TreeSet<>();
        for (NavigableSet<Long> ids : idsByExpiry.headMap(epochMillis, true).values()) {
            for (Long id : ids) {
                result.add(id);
                if (result.size() >= limit) {
                    return Collections.unmodifiableNavigableSet(result);
                }
            }
        }
        return Collections.unmodifiableNavigableSet(result);
    }

    public Set<Long> ids(long userId, String symbol, CoreMarginMode marginMode, CorePositionSide positionSide) {
        NavigableSet<Long> ids = idsByPosition.get(new TriggerPositionKey(userId,
                OrderReservation.normalizeSymbol(symbol), marginMode, positionSide));
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids);
    }

    public Set<Long> ocoSiblings(CoreTriggerOrderState order) {
        if (order.ocoGroupId().isEmpty()) return Set.of();
        NavigableSet<Long> ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                OrderReservation.normalizeSymbol(order.symbol()), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? Set.of() : Collections.unmodifiableNavigableSet(ids);
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
            if (previous != null) remove(previous, id);
            if (previous != null && !previous.clientTriggerOrderId().isEmpty()) {
                idsByClient.remove(new ClientTriggerKey(previous.userId(), previous.clientTriggerOrderId()));
            }
            if (current != null) add(current);
            if (current != null && !current.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(current.userId(), current.clientTriggerOrderId()), id);
            }
        }
    }

    public void rebuild(TradingCoreState state) {
        idsBySymbol.clear();
        idsBySymbolStatus.clear();
        idsByStatus.clear();
        idsByUser.clear();
        allIds.clear();
        idsByClient.clear();
        idsByPosition.clear();
        idsByOco.clear();
        idsByPrice.clear();
        idsByExpiry.clear();
        state.triggerOrders().values().forEach(order -> {
            add(order);
            if (!order.clientTriggerOrderId().isEmpty()) {
                idsByClient.put(new ClientTriggerKey(order.userId(), order.clientTriggerOrderId()), order.triggerOrderId());
            }
        });
    }

    private void add(CoreTriggerOrderState order) {
        long userId = order.userId();
        String symbol = order.symbol();
        long id = order.triggerOrderId();
        allIds.add(id);
        idsBySymbol.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> new TreeSet<>()).add(id);
        idsBySymbolStatus.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> new java.util.EnumMap<>(CoreTriggerOrderStatus.class))
                .computeIfAbsent(order.status(), ignored -> new TreeSet<>()).add(id);
        idsByStatus.computeIfAbsent(order.status(), ignored -> new TreeSet<>()).add(id);
        idsByUser.computeIfAbsent(userId, ignored -> new TreeSet<>()).add(id);
        idsByPosition.computeIfAbsent(new TriggerPositionKey(userId,
                OrderReservation.normalizeSymbol(symbol), order.marginMode(), order.positionSide()),
                ignored -> new TreeSet<>()).add(id);
        if (!order.ocoGroupId().isEmpty()) {
            idsByOco.computeIfAbsent(new TriggerOcoKey(userId, OrderReservation.normalizeSymbol(symbol),
                    order.marginMode(), order.positionSide(), order.ocoGroupId()), ignored -> new TreeSet<>()).add(id);
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING) {
            if (order.expiresAtEpochMillis() > 0) {
                idsByExpiry.computeIfAbsent(order.expiresAtEpochMillis(), ignored -> new TreeSet<>()).add(id);
            }
            NavigableMapByPrice price = idsByPrice.computeIfAbsent(OrderReservation.normalizeSymbol(symbol),
                    ignored -> new NavigableMapByPrice());
            if (order.triggerType() == CoreTriggerOrderType.TRAILING_STOP) {
                price.trailing.add(id);
            } else if (order.triggerPriceTicks() > 0) {
                if (order.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL) {
                    price.greaterOrEqual.computeIfAbsent(order.triggerPriceTicks(), ignored -> new TreeSet<>()).add(id);
                } else {
                    price.lessOrEqual.computeIfAbsent(order.triggerPriceTicks(), ignored -> new TreeSet<>()).add(id);
                }
            }
        }
    }

    private void remove(CoreTriggerOrderState order, long id) {
        long userId = order.userId();
        String symbol = order.symbol();
        allIds.remove(id);
        NavigableSet<Long> userIds = idsByUser.get(userId);
        if (userIds != null) {
            userIds.remove(id);
            if (userIds.isEmpty()) idsByUser.remove(userId);
        }
        NavigableSet<Long> ids = idsBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) idsBySymbol.remove(OrderReservation.normalizeSymbol(symbol));
        }

        Map<CoreTriggerOrderStatus, NavigableSet<Long>> byStatus = idsBySymbolStatus.get(
                OrderReservation.normalizeSymbol(symbol));
        if (byStatus != null) {
            NavigableSet<Long> statusIds = byStatus.get(order.status());
            if (statusIds != null) {
                statusIds.remove(id);
                if (statusIds.isEmpty()) byStatus.remove(order.status());
            }
            if (byStatus.isEmpty()) idsBySymbolStatus.remove(OrderReservation.normalizeSymbol(symbol));
        }
        NavigableSet<Long> statusIds = idsByStatus.get(order.status());
        if (statusIds != null) {
            statusIds.remove(id);
            if (statusIds.isEmpty()) idsByStatus.remove(order.status());
        }

        TriggerPositionKey positionKey = new TriggerPositionKey(userId,
                OrderReservation.normalizeSymbol(symbol), order.marginMode(), order.positionSide());
        NavigableSet<Long> positionIds = idsByPosition.get(positionKey);
        if (positionIds != null) {
            positionIds.remove(id);
            if (positionIds.isEmpty()) idsByPosition.remove(positionKey);
        }
        if (!order.ocoGroupId().isEmpty()) {
            TriggerOcoKey ocoKey = new TriggerOcoKey(userId, OrderReservation.normalizeSymbol(symbol),
                    order.marginMode(), order.positionSide(), order.ocoGroupId());
            NavigableSet<Long> ocoIds = idsByOco.get(ocoKey);
            if (ocoIds != null) {
                ocoIds.remove(id);
                if (ocoIds.isEmpty()) idsByOco.remove(ocoKey);
            }
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING && order.expiresAtEpochMillis() > 0) {
            NavigableSet<Long> expiryIds = idsByExpiry.get(order.expiresAtEpochMillis());
            if (expiryIds != null) {
                expiryIds.remove(id);
                if (expiryIds.isEmpty()) idsByExpiry.remove(order.expiresAtEpochMillis());
            }
        }
        NavigableMapByPrice price = idsByPrice.get(OrderReservation.normalizeSymbol(symbol));
        if (price == null) return;
        price.trailing.remove(id);
        if (order.triggerPriceTicks() > 0) {
            NavigableMap<Long, NavigableSet<Long>> map = order.triggerCondition()
                    == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                    ? price.greaterOrEqual : price.lessOrEqual;
            NavigableSet<Long> priceIds = map.get(order.triggerPriceTicks());
            if (priceIds != null) {
                priceIds.remove(id);
                if (priceIds.isEmpty()) map.remove(order.triggerPriceTicks());
            }
        }
        if (price.greaterOrEqual.isEmpty() && price.lessOrEqual.isEmpty() && price.trailing.isEmpty()) {
            idsByPrice.remove(OrderReservation.normalizeSymbol(symbol));
        }
    }

    private static final class NavigableMapByPrice {
        private final NavigableMap<Long, NavigableSet<Long>> greaterOrEqual = new TreeMap<>();
        private final NavigableMap<Long, NavigableSet<Long>> lessOrEqual = new TreeMap<>();
        private final NavigableSet<Long> trailing = new TreeSet<>();
    }

    private record TriggerPositionKey(long userId, String symbol, CoreMarginMode marginMode,
                                      CorePositionSide positionSide) {
    }

    private record TriggerOcoKey(long userId, String symbol, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, String groupId) {
    }

    private record ClientTriggerKey(long userId, String clientTriggerOrderId) {
    }
}
