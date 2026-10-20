package com.surprising.aeron.service.state.index;

import com.surprising.aeron.service.state.TradingCoreState;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;

import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import com.surprising.product.api.InstrumentIds;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import java.util.TreeSet;

public final class AlgoOrderIndex {

    private static final Comparator<AlgoDueKey> DUE_ORDER = Comparator
            .comparingLong(AlgoDueKey::nextSliceAtEpochMillis)
            .thenComparingLong(AlgoDueKey::algoOrderId);

    private final Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsByUser = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> idsBySymbol = new Int2ObjectOpenHashMap<>();
    private final NavigableSet<AlgoDueKey> allDue = new TreeSet<>(DUE_ORDER);
    private final Object2LongOpenHashMap<AlgoClientKey> idsByClient = new Object2LongOpenHashMap<>();
    private final Long2ObjectOpenHashMap<AlgoRoute> valuesById = new Long2ObjectOpenHashMap<>();

    /** Scheduling and identity keys only; quantities, status and child orders stay in Lane. */
    public record AlgoRoute(long algoOrderId, long userId, int instrumentId,
                            long nextSliceAtEpochMillis, String clientAlgoOrderId) { }

    public AlgoOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public boolean containsClient(long userId, String clientAlgoOrderId) {
        return clientAlgoOrderId != null && !clientAlgoOrderId.isEmpty()
                && idsByClient.containsKey(new AlgoClientKey(userId, clientAlgoOrderId));
    }

    /** 已有Owner索引的不可变路由事实；账户和子订单仍由实际Lane读取。 */
    public AlgoRoute value(long algoOrderId) { return valuesById.get(algoOrderId); }

    /** Export-worker bootstrap only; visits routing identities without copying Lane business state. */
    public void forEachRoute(java.util.function.Consumer<AlgoRoute> consumer) {
        valuesById.values().forEach(consumer);
    }

    public List<Long> query(long userId, String instrumentId, long dueAtEpochMillis, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        int normalizedSymbol = instrumentId == null || instrumentId.isEmpty()
                ? 0 : InstrumentIds.parse(instrumentId);
        NavigableSet<AlgoDueKey> candidates = candidateSet(userId, normalizedSymbol);
        if (candidates == null) candidates = allDue;
        NavigableSet<AlgoDueKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates
                : candidates.headSet(new AlgoDueKey(dueAtEpochMillis, Long.MAX_VALUE), true);
        var result = new ImmutableLongArrayList.Builder(Math.min(boundedLimit, candidates.size()));
        for (AlgoDueKey key : dueCandidates) {
            AlgoRoute value = valuesById.get(key.algoOrderId());
            if (value == null || (userId != 0 && value.userId() != userId)
                    || (normalizedSymbol != 0 && value.instrumentId() != normalizedSymbol)
                    || (dueAtEpochMillis != 0 && (value.nextSliceAtEpochMillis() == 0
                    || value.nextSliceAtEpochMillis() > dueAtEpochMillis))) {
                continue;
            }
            result.add(key.algoOrderId());
            if (result.size() == boundedLimit) break;
        }
        return result.freeze();
    }

    public void apply(long algoOrderId, CoreAlgoOrderState after) {
        AlgoRoute previous = valuesById.remove(algoOrderId);
        if (previous != null) remove(previous);
        if (after != null) add(after);
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        allDue.clear();
        idsByClient.clear();
        valuesById.clear();
        state.algoOrders().values().forEach(this::add);
    }

    private NavigableSet<AlgoDueKey> candidateSet(long userId, int instrumentId) {
        NavigableSet<AlgoDueKey> userSet = userId == 0 ? null : idsByUser.get(userId);
        NavigableSet<AlgoDueKey> symbolSet = instrumentId == 0 ? null : idsBySymbol.get(instrumentId);
        if (userSet == null) return symbolSet;
        if (symbolSet == null) return userSet;
        // Route filters in query apply the other criterion without materializing an intersection.
        return userSet.size() <= symbolSet.size() ? userSet : symbolSet;
    }

    private void add(CoreAlgoOrderState value) {
        AlgoRoute route = new AlgoRoute(value.algoOrderId(), value.userId(), InstrumentIds.parse(value.instrumentId()),
                value.nextSliceAtEpochMillis(), value.clientAlgoOrderId());
        valuesById.put(value.algoOrderId(), route);
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        idsBySymbol.computeIfAbsent(route.instrumentId(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        if (!value.clientAlgoOrderId().isEmpty()) {
            idsByClient.put(new AlgoClientKey(value.userId(), value.clientAlgoOrderId()), value.algoOrderId());
        }
    }

    private void remove(AlgoRoute value) {
        valuesById.remove(value.algoOrderId());
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.remove(key);
        remove(idsByUser, value.userId(), key);
        remove(idsBySymbol, value.instrumentId(), key);
        if (!value.clientAlgoOrderId().isEmpty()) {
            idsByClient.removeLong(new AlgoClientKey(value.userId(), value.clientAlgoOrderId()));
        }
    }

    private static void remove(Long2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, long key, AlgoDueKey dueKey) {
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    private static void remove(Int2ObjectOpenHashMap<NavigableSet<AlgoDueKey>> values, int key, AlgoDueKey dueKey) {
        NavigableSet<AlgoDueKey> ids = values.get(key);
        if (ids == null) return;
        ids.remove(dueKey);
        if (ids.isEmpty()) values.remove(key);
    }

    private record AlgoDueKey(long nextSliceAtEpochMillis, long algoOrderId) {
    }

    private record AlgoClientKey(long userId, String clientAlgoOrderId) {
    }
}
