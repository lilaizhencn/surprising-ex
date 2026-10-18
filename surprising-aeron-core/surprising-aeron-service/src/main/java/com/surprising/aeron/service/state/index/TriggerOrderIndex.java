package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.command.ImmutableLongArrayList;

import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

import com.surprising.product.api.InstrumentIds;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import it.unimi.dsi.fastutil.longs.LongSortedSets;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreTriggerPriceSource;

public final class TriggerOrderIndex {

    // Owner-owned derived indices; apply/remove/rebuild follow the committed Lane state.
    private final Int2ObjectOpenHashMap<LongSortedSet> idsBySymbol = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Map<CoreTriggerOrderStatus, LongSortedSet>> idsBySymbolStatus = new Int2ObjectOpenHashMap<>();
    private final Map<CoreTriggerOrderStatus, LongSortedSet> idsByStatus = new java.util.EnumMap<>(CoreTriggerOrderStatus.class);
    private final Long2ObjectOpenHashMap<LongSortedSet> idsByUser = new Long2ObjectOpenHashMap<>();
    private final LongSortedSet allIds = descendingIds();
    private final Object2LongOpenHashMap<ClientTriggerKey> idsByClient = new Object2LongOpenHashMap<>();
    private final Map<TriggerPositionKey, LongSortedSet> idsByPosition = new java.util.HashMap<>();
    private final Map<TriggerOcoKey, LongSortedSet> idsByOco = new java.util.HashMap<>();
    private final Map<PriceKey, TriggerPriceBuckets> idsByPrice = new java.util.HashMap<>();
    private final Long2ObjectAVLTreeMap<LongSortedSet> idsByExpiry = new Long2ObjectAVLTreeMap<>();
    private final Long2ObjectOpenHashMap<TriggerRoute> valuesById = new Long2ObjectOpenHashMap<>();

    private static LongSortedSet descendingIds() {
        return new LongAVLTreeSet(LongComparators.OPPOSITE_COMPARATOR);
    }

    /** Identity and candidate-index keys; order size, fees and execution state remain in Lane. */
    public record TriggerRoute(long triggerOrderId, long userId, int instrumentId,
            String clientTriggerOrderId, String ocoGroupId, CoreMarginMode marginMode,
            CorePositionSide positionSide, CoreTriggerOrderStatus status, long expiresAtEpochMillis,
            CoreTriggerPriceSource priceSource, CoreTriggerOrderType triggerType,
            com.surprising.aeron.protocol.CoreTriggerCondition triggerCondition, long triggerPriceTicks,
            com.surprising.aeron.protocol.CoreOrderSide side, long activationPriceTicks,
            long callbackRatePpm, long highestPriceTicks, long lowestPriceTicks, long activatedAtEpochMillis,
            boolean cancelRevisionAvailable) {
        public static TriggerRoute from(CoreTriggerOrderState value) {
            return new TriggerRoute(value.triggerOrderId(), value.userId(), value.instrument().instrumentIdValue(),
                    value.clientTriggerOrderId(), value.ocoGroupId(), value.marginMode(), value.positionSide(),
                    value.status(), value.expiresAtEpochMillis(), value.priceSource(), value.triggerType(),
                    value.triggerCondition(), value.triggerPriceTicks(), value.side(), value.activationPriceTicks(),
                    value.callbackRatePpm(), value.highestPriceTicks(), value.lowestPriceTicks(), value.activatedAtEpochMillis(),
                    value.revision() < Long.MAX_VALUE);
        }
    }

    public TriggerRoute route(long id) { return valuesById.get(id); }

    public TriggerOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public Set<Long> ids(String instrumentId) {
        LongSortedSet ids = idsBySymbol.get(InstrumentIds.parse(instrumentId));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public Set<Long> ids() {
        return LongSortedSets.unmodifiable(allIds);
    }

    public Set<Long> ids(CoreTriggerOrderStatus status) {
        LongSortedSet ids = idsByStatus.get(status);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public Set<Long> ids(String instrumentId, CoreTriggerOrderStatus status) {
        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                InstrumentIds.parse(instrumentId));
        LongSortedSet ids = byStatus == null ? null : byStatus.get(status);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public LongSortedSet ids(long userId) {
        LongSortedSet ids = idsByUser.get(userId);
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public long maxPendingId(String instrumentId) {
        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                InstrumentIds.parse(instrumentId));
        LongSortedSet pending = byStatus == null ? null : byStatus.get(CoreTriggerOrderStatus.PENDING);
        return pending == null || pending.isEmpty() ? 0 : pending.firstLong();
    }

    public TriggerCandidatePage candidatesPage(String instrumentId, long markPriceTicks,
                                                int phase, long priceCursor, long orderCursor,
                                                long upperTriggerId, int limit) {
        return candidatesPage(instrumentId, CoreTriggerPriceSource.MARK, markPriceTicks, phase,
                priceCursor, orderCursor, upperTriggerId, limit);
    }

    public TriggerCandidatePage candidatesPage(String instrumentId, CoreTriggerPriceSource source,
                                                long markPriceTicks, int phase, long priceCursor,
                                                long orderCursor, long upperTriggerId, int limit) {
        return candidatesPage(InstrumentIds.parse(instrumentId), source, markPriceTicks, phase,
                priceCursor, orderCursor, upperTriggerId, limit);
    }

    public TriggerCandidatePage candidatesPage(int instrumentId, CoreTriggerPriceSource source,
                                                long markPriceTicks, int phase, long priceCursor,
                                                long orderCursor, long upperTriggerId, int limit) {
        if (limit <= 0 || limit > 4_096) {
            throw new IllegalArgumentException("candidate page limit must be in [1,4096]");
        }
        TriggerPriceBuckets price = idsByPrice.get(new PriceKey(instrumentId, source));
        if (price == null || markPriceTicks <= 0 || upperTriggerId <= 0 || phase >= PHASE_COMPLETE) {
            return TriggerCandidatePage.emptyPage();
        }
        int nextPhase = Math.max(phase, PHASE_GREATER_OR_EQUAL);
        long nextPrice = priceCursor;
        long nextOrder = orderCursor;
        // Candidate pages are on the mark-price hot path. Keep IDs primitive so
        // scanning a large trigger index does not build a boxed Long list and a
        // second defensive copy for every page.
        ImmutableLongArrayList.Builder result = new ImmutableLongArrayList.Builder(limit);
        for (; nextPhase < PHASE_TRAILING_ALWAYS; nextPhase++) {
            Long2ObjectAVLTreeMap<LongSortedSet> buckets = switch (nextPhase) {
                case PHASE_GREATER_OR_EQUAL -> price.greaterOrEqual;
                case PHASE_LESS_OR_EQUAL -> price.lessOrEqual;
                case PHASE_TRAILING_GREATER_OR_EQUAL -> price.trailingGreaterOrEqual;
                default -> price.trailingLessOrEqual;
            };
            boolean ascending = nextPhase == PHASE_LESS_OR_EQUAL || nextPhase == PHASE_TRAILING_GREATER_OR_EQUAL;
            LongBidirectionalIterator prices = priceIterator(buckets, markPriceTicks, nextPhase, nextPrice, nextOrder);
            while (ascending ? prices.hasNext() : prices.hasPrevious()) {
                long bucketPrice = ascending ? prices.nextLong() : prices.previousLong();
                long last = appendBucket(result, buckets.get(bucketPrice), upperTriggerId, nextOrder, limit);
                if (result.size() >= limit) return page(result, nextPhase, bucketPrice, last, false);
                nextOrder = Long.MAX_VALUE;
            }
            nextPrice = 0;
            nextOrder = Long.MAX_VALUE;
        }
        if (nextPhase == PHASE_TRAILING_ALWAYS) {
            long last = appendBucket(result, price.trailingAlways, upperTriggerId, nextOrder, limit);
            if (result.size() >= limit) return page(result, PHASE_TRAILING_ALWAYS, 0, last, false);
        }
        return page(result, PHASE_COMPLETE, 0, 0, true);
    }

    public static final int PHASE_GREATER_OR_EQUAL = 0;
    public static final int PHASE_LESS_OR_EQUAL = 1;
    public static final int PHASE_TRAILING_GREATER_OR_EQUAL = 2;
    public static final int PHASE_TRAILING_LESS_OR_EQUAL = 3;
    public static final int PHASE_TRAILING_ALWAYS = 4;
    public static final int PHASE_COMPLETE = 5;

    /** Seek once per phase, then walk only eligible price buckets, including a partially consumed bucket. */
    private static LongBidirectionalIterator priceIterator(Long2ObjectAVLTreeMap<LongSortedSet> buckets,
            long mark, int phase, long cursor, long orderCursor) {
        boolean ascending = phase == PHASE_LESS_OR_EQUAL || phase == PHASE_TRAILING_GREATER_OR_EQUAL;
        long initialCursor = phase == PHASE_GREATER_OR_EQUAL || phase == PHASE_TRAILING_GREATER_OR_EQUAL
                ? Long.MAX_VALUE : 0;
        boolean initial = cursor == initialCursor && orderCursor == Long.MAX_VALUE;
        if (ascending) {
            if (initial) return buckets.tailMap(mark).keySet().iterator();
            if (orderCursor != Long.MAX_VALUE) return buckets.tailMap(Math.max(mark, cursor)).keySet().iterator();
            return buckets.tailMap(mark).keySet().iterator(cursor);
        }
        long upper = initial ? mark : Math.min(mark, cursor);
        LongBidirectionalIterator iterator = buckets.keySet().iterator(upper);
        if (!initial && orderCursor == Long.MAX_VALUE && upper == cursor && buckets.containsKey(cursor))
            iterator.previousLong();
        return iterator;
    }

    private static long appendBucket(ImmutableLongArrayList.Builder result, LongSortedSet ids,
                                     long upperTriggerId, long orderCursor, int limit) {
        if (orderCursor <= 0) return 0;
        long start = Math.min(upperTriggerId, orderCursor - 1);
        var iterator = ids.tailSet(start).iterator();
        long last = 0;
        while (iterator.hasNext() && result.size() < limit) {
            last = iterator.nextLong();
            result.add(last);
        }
        return last;
    }

    private static TriggerCandidatePage page(ImmutableLongArrayList.Builder result, int nextPhase,
                                              long nextPriceCursor, long nextOrderCursor,
                                              boolean complete) {
        return new TriggerCandidatePage(result.freeze(), nextPhase, nextPriceCursor, nextOrderCursor, complete);
    }

    public record TriggerCandidatePage(List<Long> ids, int nextPhase, long nextPriceCursor,
                                       long nextOrderCursor, boolean complete) {
        public TriggerCandidatePage {
            ids = ids == null || ids.isEmpty() ? List.of()
                    : ids instanceof ImmutableLongArrayList ? ids : List.copyOf(ids);
            if (nextPhase < PHASE_GREATER_OR_EQUAL || nextPhase > PHASE_COMPLETE) {
                throw new IllegalArgumentException("invalid trigger candidate cursor");
            }
        }

        public long idAt(int index) {
            if (ids instanceof ImmutableLongArrayList primitive) return primitive.valueAt(index);
            return ids.get(index);
        }

        public static TriggerCandidatePage emptyPage() {
            return new TriggerCandidatePage(List.of(), PHASE_COMPLETE, 0, 0, true);
        }
    }

    public Set<Long> expired(long epochMillis, int limit) {
        if (epochMillis <= 0 || limit <= 0) return Set.of();
        LongSortedSet result = new LongAVLTreeSet();
        var buckets = epochMillis == Long.MAX_VALUE ? idsByExpiry : idsByExpiry.headMap(epochMillis + 1);
        for (LongSortedSet ids : buckets.values()) {
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                result.add(iterator.nextLong());
                if (result.size() >= limit) return LongSortedSets.unmodifiable(result);
            }
        }
        return LongSortedSets.unmodifiable(result);
    }

    public Set<Long> ids(long userId, String instrumentId, CoreMarginMode marginMode, CorePositionSide positionSide) {
        LongSortedSet ids = idsByPosition.get(new TriggerPositionKey(userId,
                InstrumentIds.parse(instrumentId), marginMode, positionSide));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public LongSortedSet ocoSiblingsDescending(CoreTriggerOrderState order) {
        if (order.ocoGroupId().isEmpty()) return LongSortedSets.EMPTY_SET;
        LongSortedSet ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                order.instrument().instrumentIdValue(), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public LongSortedSet ocoSiblingsDescending(TriggerRoute order) {
        if (order.ocoGroupId().isEmpty()) return LongSortedSets.EMPTY_SET;
        LongSortedSet ids = idsByOco.get(new TriggerOcoKey(order.userId(),
                order.instrumentId(), order.marginMode(), order.positionSide(),
                order.ocoGroupId()));
        return ids == null ? LongSortedSets.EMPTY_SET : LongSortedSets.unmodifiable(ids);
    }

    public boolean containsClient(long userId, String clientTriggerOrderId) {
        return clientTriggerOrderId != null && !clientTriggerOrderId.isEmpty()
                && idsByClient.containsKey(new ClientTriggerKey(userId, clientTriggerOrderId));
    }

    public void apply(long id, CoreTriggerOrderState current) {
        TriggerRoute previous = valuesById.remove(id);
        if (previous != null) {
            remove(previous, id);
            if (!previous.clientTriggerOrderId().isEmpty()) {
                idsByClient.removeLong(new ClientTriggerKey(previous.userId(), previous.clientTriggerOrderId()));
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

    private void add(CoreTriggerOrderState order) { add(TriggerRoute.from(order)); }

    private void add(TriggerRoute order) {
        long userId = order.userId();
        int instrumentId = order.instrumentId();
        long id = order.triggerOrderId();
        valuesById.put(id, order);
        allIds.add(id);
        idsBySymbol.computeIfAbsent(instrumentId, ignored -> descendingIds()).add(id);
        idsBySymbolStatus.computeIfAbsent(instrumentId, ignored -> new java.util.EnumMap<>(CoreTriggerOrderStatus.class))
                .computeIfAbsent(order.status(), ignored -> descendingIds()).add(id);
        idsByStatus.computeIfAbsent(order.status(), ignored -> descendingIds()).add(id);
        idsByUser.computeIfAbsent(userId, ignored -> descendingIds()).add(id);
        idsByPosition.computeIfAbsent(new TriggerPositionKey(userId,
                instrumentId, order.marginMode(), order.positionSide()),
                ignored -> new LongAVLTreeSet()).add(id);
        if (order.status() == CoreTriggerOrderStatus.PENDING && !order.ocoGroupId().isEmpty()) {
            idsByOco.computeIfAbsent(new TriggerOcoKey(userId, instrumentId,
                    order.marginMode(), order.positionSide(), order.ocoGroupId()), ignored -> descendingIds()).add(id);
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING) {
            if (order.expiresAtEpochMillis() > 0) {
                idsByExpiry.computeIfAbsent(order.expiresAtEpochMillis(), ignored -> new LongAVLTreeSet()).add(id);
            }
            TriggerPriceBuckets price = idsByPrice.computeIfAbsent(new PriceKey(instrumentId, order.priceSource()),
                    ignored -> new TriggerPriceBuckets());
            if (order.triggerType() == CoreTriggerOrderType.TRAILING_STOP) {
                indexTrailing(price, order);
            } else if (order.triggerPriceTicks() > 0) {
                if (order.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL) {
                    price.greaterOrEqual.computeIfAbsent(order.triggerPriceTicks(), ignored -> descendingIds()).add(id);
                } else {
                    price.lessOrEqual.computeIfAbsent(order.triggerPriceTicks(), ignored -> descendingIds()).add(id);
                }
            }
        }
    }

    private void remove(TriggerRoute order, long id) {
        valuesById.remove(id);
        long userId = order.userId();
        int instrumentId = order.instrumentId();
        allIds.remove(id);
        LongSortedSet userIds = idsByUser.get(userId);
        if (userIds != null) {
            userIds.remove(id);
            if (userIds.isEmpty()) idsByUser.remove(userId);
        }
        LongSortedSet ids = idsBySymbol.get(instrumentId);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) idsBySymbol.remove(instrumentId);
        }

        Map<CoreTriggerOrderStatus, LongSortedSet> byStatus = idsBySymbolStatus.get(
                instrumentId);
        if (byStatus != null) {
            LongSortedSet statusIds = byStatus.get(order.status());
            if (statusIds != null) {
                statusIds.remove(id);
                if (statusIds.isEmpty()) byStatus.remove(order.status());
            }
            if (byStatus.isEmpty()) idsBySymbolStatus.remove(instrumentId);
        }
        LongSortedSet statusIds = idsByStatus.get(order.status());
        if (statusIds != null) {
            statusIds.remove(id);
            if (statusIds.isEmpty()) idsByStatus.remove(order.status());
        }

        TriggerPositionKey positionKey = new TriggerPositionKey(userId,
                instrumentId, order.marginMode(), order.positionSide());
        LongSortedSet positionIds = idsByPosition.get(positionKey);
        if (positionIds != null) {
            positionIds.remove(id);
            if (positionIds.isEmpty()) idsByPosition.remove(positionKey);
        }
        if (!order.ocoGroupId().isEmpty()) {
            TriggerOcoKey ocoKey = new TriggerOcoKey(userId, instrumentId,
                    order.marginMode(), order.positionSide(), order.ocoGroupId());
            LongSortedSet ocoIds = idsByOco.get(ocoKey);
            if (ocoIds != null) {
                ocoIds.remove(id);
                if (ocoIds.isEmpty()) idsByOco.remove(ocoKey);
            }
        }
        if (order.status() == CoreTriggerOrderStatus.PENDING && order.expiresAtEpochMillis() > 0) {
            LongSortedSet expiryIds = idsByExpiry.get(order.expiresAtEpochMillis());
            if (expiryIds != null) {
                expiryIds.remove(id);
                if (expiryIds.isEmpty()) idsByExpiry.remove(order.expiresAtEpochMillis());
            }
        }
        PriceKey priceKey = new PriceKey(instrumentId, order.priceSource());
        TriggerPriceBuckets price = idsByPrice.get(priceKey);
        if (price == null) return;
        removeTrailing(price, order, id);
        if (order.triggerPriceTicks() > 0) {
            Long2ObjectAVLTreeMap<LongSortedSet> map = order.triggerCondition()
                    == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                    ? price.greaterOrEqual : price.lessOrEqual;
            LongSortedSet priceIds = map.get(order.triggerPriceTicks());
            if (priceIds != null) {
                priceIds.remove(id);
                if (priceIds.isEmpty()) map.remove(order.triggerPriceTicks());
            }
        }
        if (price.greaterOrEqual.isEmpty() && price.lessOrEqual.isEmpty()
                && price.trailingGreaterOrEqual.isEmpty() && price.trailingLessOrEqual.isEmpty()
                && price.trailingAlways.isEmpty()) {
            idsByPrice.remove(priceKey);
        }
    }

    private static final class TriggerPriceBuckets {
        private final Long2ObjectAVLTreeMap<LongSortedSet> greaterOrEqual = new Long2ObjectAVLTreeMap<>();
        private final Long2ObjectAVLTreeMap<LongSortedSet> lessOrEqual = new Long2ObjectAVLTreeMap<>();
        private final Long2ObjectAVLTreeMap<LongSortedSet> trailingGreaterOrEqual = new Long2ObjectAVLTreeMap<>();
        private final Long2ObjectAVLTreeMap<LongSortedSet> trailingLessOrEqual = new Long2ObjectAVLTreeMap<>();
        private final LongSortedSet trailingAlways = descendingIds();
    }

    private static void indexTrailing(TriggerPriceBuckets price, TriggerRoute order) {
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

    private static void removeTrailing(TriggerPriceBuckets price, TriggerRoute order, long id) {
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

    private static void addPriceId(Long2ObjectAVLTreeMap<LongSortedSet> map, long price, long id) {
        map.computeIfAbsent(price, ignored -> descendingIds()).add(id);
    }

    private static void removePriceId(Long2ObjectAVLTreeMap<LongSortedSet> map, long price, long id) {
        LongSortedSet ids = map.get(price);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) map.remove(price);
    }

    private record TriggerPositionKey(long userId, int instrumentId, CoreMarginMode marginMode,
                                      CorePositionSide positionSide) {
    }

    private record TriggerOcoKey(long userId, int instrumentId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, String groupId) {
    }

    private record ClientTriggerKey(long userId, String clientTriggerOrderId) {
    }

    private record PriceKey(int instrumentId, CoreTriggerPriceSource source) {
    }
}
