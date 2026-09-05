package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
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
    private final Map<Long, CoreTriggerOrderState> valuesById = new TreeMap<>();

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

    public long maxPendingId(String symbol) {
        Map<CoreTriggerOrderStatus, NavigableSet<Long>> byStatus = idsBySymbolStatus.get(
                OrderReservation.normalizeSymbol(symbol));
        NavigableSet<Long> pending = byStatus == null ? null : byStatus.get(CoreTriggerOrderStatus.PENDING);
        return pending == null || pending.isEmpty() ? 0 : pending.last();
    }

    public TriggerCandidatePage candidatesPage(String symbol, long markPriceTicks,
                                                int phase, long priceCursor, long orderCursor,
                                                long upperTriggerId, int limit) {
        if (limit <= 0 || limit > 4_096) {
            throw new IllegalArgumentException("candidate page limit must be in [1,4096]");
        }
        String normalized = OrderReservation.normalizeSymbol(symbol);
        NavigableMapByPrice price = idsByPrice.get(normalized);
        if (price == null || upperTriggerId <= 0 || phase >= PHASE_COMPLETE) {
            return TriggerCandidatePage.emptyPage();
        }
        int nextPhase = Math.max(phase, PHASE_GREATER_OR_EQUAL);
        long nextPrice = priceCursor;
        long nextOrder = orderCursor;
        ArrayList<Long> result = new ArrayList<>(limit);
        while (result.size() < limit && nextPhase < PHASE_COMPLETE) {
            if (nextPhase == PHASE_GREATER_OR_EQUAL) {
                Map.Entry<Long, NavigableSet<Long>> bucket = nextGreaterOrEqualBucket(
                        price.greaterOrEqual, markPriceTicks, nextPrice, nextOrder);
                if (bucket == null) {
                    nextPhase = PHASE_LESS_OR_EQUAL;
                    nextPrice = 0;
                    nextOrder = Long.MAX_VALUE;
                    continue;
                }
                long last = appendBucket(result, bucket.getValue(), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) {
                    return new TriggerCandidatePage(result, nextPhase, bucket.getKey(), last, false);
                }
                nextPrice = bucket.getKey();
                nextOrder = Long.MAX_VALUE;
                continue;
            }
            if (nextPhase == PHASE_LESS_OR_EQUAL) {
                Map.Entry<Long, NavigableSet<Long>> bucket = nextLessOrEqualBucket(
                        price.lessOrEqual, markPriceTicks, nextPrice, nextOrder);
                if (bucket == null) {
                    nextPhase = PHASE_TRAILING_GREATER_OR_EQUAL;
                    nextPrice = 0;
                    nextOrder = Long.MAX_VALUE;
                    continue;
                }
                long last = appendBucket(result, bucket.getValue(), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) {
                    return new TriggerCandidatePage(result, nextPhase, bucket.getKey(), last, false);
                }
                nextPrice = bucket.getKey();
                nextOrder = Long.MAX_VALUE;
                continue;
            }
            if (nextPhase == PHASE_TRAILING_GREATER_OR_EQUAL) {
                Map.Entry<Long, NavigableSet<Long>> bucket = nextTrailingGreaterOrEqualBucket(
                        price.trailingGreaterOrEqual, markPriceTicks, nextPrice, nextOrder);
                if (bucket == null) {
                    nextPhase = PHASE_TRAILING_LESS_OR_EQUAL;
                    nextPrice = 0;
                    nextOrder = Long.MAX_VALUE;
                    continue;
                }
                long last = appendBucket(result, bucket.getValue(), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) {
                    return new TriggerCandidatePage(result, nextPhase, bucket.getKey(), last, false);
                }
                nextPrice = bucket.getKey();
                nextOrder = Long.MAX_VALUE;
                continue;
            }
            if (nextPhase == PHASE_TRAILING_LESS_OR_EQUAL) {
                Map.Entry<Long, NavigableSet<Long>> bucket = nextTrailingLessOrEqualBucket(
                        price.trailingLessOrEqual, markPriceTicks, nextPrice, nextOrder);
                if (bucket == null) {
                    nextPhase = PHASE_TRAILING_ALWAYS;
                    nextPrice = 0;
                    nextOrder = Long.MAX_VALUE;
                    continue;
                }
                long last = appendBucket(result, bucket.getValue(), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) {
                    return new TriggerCandidatePage(result, nextPhase, bucket.getKey(), last, false);
                }
                nextPrice = bucket.getKey();
                nextOrder = Long.MAX_VALUE;
                continue;
            }
            long last = appendBucket(result, price.trailingAlways, upperTriggerId, nextOrder, limit);
            if (result.size() >= limit) {
                return new TriggerCandidatePage(result, PHASE_TRAILING_ALWAYS, 0, last, false);
            }
            nextPhase = PHASE_COMPLETE;
        }
        return new TriggerCandidatePage(result, PHASE_COMPLETE, 0, 0, true);
    }

    public static final int PHASE_GREATER_OR_EQUAL = 0;
    public static final int PHASE_LESS_OR_EQUAL = 1;
    public static final int PHASE_TRAILING_GREATER_OR_EQUAL = 2;
    public static final int PHASE_TRAILING_LESS_OR_EQUAL = 3;
    public static final int PHASE_TRAILING_ALWAYS = 4;
    public static final int PHASE_COMPLETE = 5;

    private static Map.Entry<Long, NavigableSet<Long>> nextGreaterOrEqualBucket(
            NavigableMap<Long, NavigableSet<Long>> buckets, long markPriceTicks, long cursor,
            long orderCursor) {
        NavigableMap<Long, NavigableSet<Long>> eligible = buckets.headMap(markPriceTicks, true);
        if (eligible.isEmpty()) return null;
        if (orderCursor != Long.MAX_VALUE && eligible.containsKey(cursor)) {
            return Map.entry(cursor, eligible.get(cursor));
        }
        return cursor == Long.MAX_VALUE ? eligible.lastEntry() : eligible.lowerEntry(cursor);
    }

    private static Map.Entry<Long, NavigableSet<Long>> nextLessOrEqualBucket(
            NavigableMap<Long, NavigableSet<Long>> buckets, long markPriceTicks, long cursor,
            long orderCursor) {
        NavigableMap<Long, NavigableSet<Long>> eligible = buckets.tailMap(markPriceTicks, true);
        if (eligible.isEmpty()) return null;
        if (orderCursor != Long.MAX_VALUE && eligible.containsKey(cursor)) {
            return Map.entry(cursor, eligible.get(cursor));
        }
        return cursor == 0 ? eligible.firstEntry() : eligible.higherEntry(cursor);
    }

    private static Map.Entry<Long, NavigableSet<Long>> nextTrailingGreaterOrEqualBucket(
            NavigableMap<Long, NavigableSet<Long>> buckets, long markPriceTicks, long cursor,
            long orderCursor) {
        NavigableMap<Long, NavigableSet<Long>> eligible = buckets.tailMap(markPriceTicks, true);
        if (eligible.isEmpty()) return null;
        if (orderCursor != Long.MAX_VALUE && eligible.containsKey(cursor)) {
            return Map.entry(cursor, eligible.get(cursor));
        }
        return cursor == Long.MAX_VALUE ? eligible.firstEntry() : eligible.higherEntry(cursor);
    }

    private static Map.Entry<Long, NavigableSet<Long>> nextTrailingLessOrEqualBucket(
            NavigableMap<Long, NavigableSet<Long>> buckets, long markPriceTicks, long cursor,
            long orderCursor) {
        NavigableMap<Long, NavigableSet<Long>> eligible = buckets.headMap(markPriceTicks, true);
        if (eligible.isEmpty()) return null;
        if (orderCursor != Long.MAX_VALUE && eligible.containsKey(cursor)) {
            return Map.entry(cursor, eligible.get(cursor));
        }
        return cursor == 0 ? eligible.lastEntry() : eligible.lowerEntry(cursor);
    }

    private static long appendBucket(List<Long> result, NavigableSet<Long> ids,
                                     long upperTriggerId, long orderCursor, int limit) {
        long last = 0;
        for (Long id : ids.descendingSet()) {
            if (id == null || id > upperTriggerId || id >= orderCursor) continue;
            result.add(id);
            last = id;
            if (result.size() >= limit) break;
        }
        return last;
    }

    public record TriggerCandidatePage(List<Long> ids, int nextPhase, long nextPriceCursor,
                                       long nextOrderCursor, boolean complete) {
        public TriggerCandidatePage {
            ids = ids == null ? List.of() : List.copyOf(ids);
            if (nextPhase < PHASE_GREATER_OR_EQUAL || nextPhase > PHASE_COMPLETE) {
                throw new IllegalArgumentException("invalid trigger candidate cursor");
            }
        }

        public static TriggerCandidatePage emptyPage() {
            return new TriggerCandidatePage(List.of(), PHASE_COMPLETE, 0, 0, true);
        }
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
        return ids == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(ids);
    }

    public NavigableSet<Long> ocoSiblings(CoreTriggerOrderState order) {
        if (order.ocoGroupId().isEmpty()) return Collections.emptyNavigableSet();
        NavigableSet<Long> ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                OrderReservation.normalizeSymbol(order.symbol()), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(ids);
    }

    public boolean containsClient(long userId, String clientTriggerOrderId) {
        return clientTriggerOrderId != null && !clientTriggerOrderId.isEmpty()
                && idsByClient.containsKey(new ClientTriggerKey(userId, clientTriggerOrderId));
    }

    void apply(java.util.List<RuntimeFactFrame.TriggerOrderChange> changes) {
        for (RuntimeFactFrame.TriggerOrderChange change : changes) {
            apply(change.triggerOrderId(), change.after());
        }
    }

    void apply(long id, CoreTriggerOrderState current) {
        CoreTriggerOrderState previous = valuesById.remove(id);
        if (previous != null) {
            remove(previous, id);
            if (!previous.clientTriggerOrderId().isEmpty()) {
                idsByClient.remove(new ClientTriggerKey(previous.userId(), previous.clientTriggerOrderId()));
            }
        }
        if (current != null) {
            add(current);
            if (!current.clientTriggerOrderId().isEmpty()) {
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
        valuesById.clear();
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
        valuesById.put(id, order);
        allIds.add(id);
        idsBySymbol.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> new TreeSet<>()).add(id);
        idsBySymbolStatus.computeIfAbsent(OrderReservation.normalizeSymbol(symbol), ignored -> new java.util.EnumMap<>(CoreTriggerOrderStatus.class))
                .computeIfAbsent(order.status(), ignored -> new TreeSet<>()).add(id);
        idsByStatus.computeIfAbsent(order.status(), ignored -> new TreeSet<>()).add(id);
        idsByUser.computeIfAbsent(userId, ignored -> new TreeSet<>()).add(id);
        idsByPosition.computeIfAbsent(new TriggerPositionKey(userId,
                OrderReservation.normalizeSymbol(symbol), order.marginMode(), order.positionSide()),
                ignored -> new TreeSet<>()).add(id);
        if (order.status() == CoreTriggerOrderStatus.PENDING && !order.ocoGroupId().isEmpty()) {
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
                indexTrailing(price, order);
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
        valuesById.remove(id);
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
        removeTrailing(price, order, id);
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
        if (price.greaterOrEqual.isEmpty() && price.lessOrEqual.isEmpty()
                && price.trailingGreaterOrEqual.isEmpty() && price.trailingLessOrEqual.isEmpty()
                && price.trailingAlways.isEmpty()) {
            idsByPrice.remove(OrderReservation.normalizeSymbol(symbol));
        }
    }

    private static final class NavigableMapByPrice {
        private final NavigableMap<Long, NavigableSet<Long>> greaterOrEqual = new TreeMap<>();
        private final NavigableMap<Long, NavigableSet<Long>> lessOrEqual = new TreeMap<>();
        private final NavigableMap<Long, NavigableSet<Long>> trailingGreaterOrEqual = new TreeMap<>();
        private final NavigableMap<Long, NavigableSet<Long>> trailingLessOrEqual = new TreeMap<>();
        private final NavigableSet<Long> trailingAlways = new TreeSet<>();
    }

    private static void indexTrailing(NavigableMapByPrice price, CoreTriggerOrderState order) {
        boolean sell = order.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
        if (order.activatedAtEpochMillis() > 0) {
            long base = sell ? order.highestPriceTicks() : order.lowestPriceTicks();
            if (base > 0 && order.callbackRatePpm() > 0) {
                long delta = Math.floorDiv(Math.multiplyExact(base, order.callbackRatePpm()), 1_000_000L);
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                addPriceId(sell ? price.trailingGreaterOrEqual : price.trailingLessOrEqual, threshold,
                        order.triggerOrderId());
                return;
            }
        }
        if (order.activationPriceTicks() > 0) {
            addPriceId(sell ? price.trailingLessOrEqual : price.trailingGreaterOrEqual,
                    order.activationPriceTicks(), order.triggerOrderId());
            return;
        }
        price.trailingAlways.add(order.triggerOrderId());
    }

    private static void removeTrailing(NavigableMapByPrice price, CoreTriggerOrderState order, long id) {
        boolean sell = order.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
        if (order.activatedAtEpochMillis() > 0) {
            long base = sell ? order.highestPriceTicks() : order.lowestPriceTicks();
            if (base > 0 && order.callbackRatePpm() > 0) {
                long delta = Math.floorDiv(Math.multiplyExact(base, order.callbackRatePpm()), 1_000_000L);
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                removePriceId(sell ? price.trailingGreaterOrEqual : price.trailingLessOrEqual, threshold, id);
                return;
            }
        }
        if (order.activationPriceTicks() > 0) {
            removePriceId(sell ? price.trailingLessOrEqual : price.trailingGreaterOrEqual,
                    order.activationPriceTicks(), id);
            return;
        }
        price.trailingAlways.remove(id);
    }

    private static void addPriceId(NavigableMap<Long, NavigableSet<Long>> map, long price, long id) {
        map.computeIfAbsent(price, ignored -> new TreeSet<>()).add(id);
    }

    private static void removePriceId(NavigableMap<Long, NavigableSet<Long>> map, long price, long id) {
        NavigableSet<Long> ids = map.get(price);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) map.remove(price);
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
