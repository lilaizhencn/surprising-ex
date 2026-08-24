package com.surprising.aeron.service.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class AlgoOrderIndex {

    private static final Comparator<AlgoDueKey> DUE_ORDER = Comparator
            .comparingLong(AlgoDueKey::nextSliceAtEpochMillis)
            .thenComparingLong(AlgoDueKey::algoOrderId);

    private final Map<Long, NavigableSet<AlgoDueKey>> idsByUser = new TreeMap<>();
    private final Map<String, NavigableSet<AlgoDueKey>> idsBySymbol = new TreeMap<>();
    private final NavigableSet<AlgoDueKey> allDue = new TreeSet<>(DUE_ORDER);
    private final Map<AlgoClientKey, Long> idsByClient = new java.util.HashMap<>();

    public AlgoOrderIndex(TradingCoreState state) {
        rebuild(state);
    }

    public boolean containsClient(long userId, String clientAlgoOrderId) {
        return clientAlgoOrderId != null && !clientAlgoOrderId.isEmpty()
                && idsByClient.containsKey(new AlgoClientKey(userId, clientAlgoOrderId));
    }

    public List<Long> query(long userId, String symbol, long dueAtEpochMillis, int limit,
                            Map<Long, CoreAlgoOrderState> values) {
        int boundedLimit = Math.max(1, Math.min(limit, 10_000));
        String normalizedSymbol = symbol == null || symbol.isEmpty()
                ? "" : OrderReservation.normalizeSymbol(symbol);
        NavigableSet<AlgoDueKey> candidates = candidateSet(userId, normalizedSymbol);
        if (candidates == null) candidates = allDue;
        NavigableSet<AlgoDueKey> dueCandidates = dueAtEpochMillis == 0
                ? candidates
                : candidates.headSet(new AlgoDueKey(dueAtEpochMillis, Long.MAX_VALUE), true);
        List<Long> result = new ArrayList<>(Math.min(boundedLimit, dueCandidates.size()));
        for (AlgoDueKey key : dueCandidates) {
            CoreAlgoOrderState value = values.get(key.algoOrderId());
            if (value == null || (userId != 0 && value.userId() != userId)
                    || (!normalizedSymbol.isEmpty() && !value.symbol().equals(normalizedSymbol))
                    || (dueAtEpochMillis != 0 && (value.nextSliceAtEpochMillis() == 0
                    || value.nextSliceAtEpochMillis() > dueAtEpochMillis))) {
                continue;
            }
            result.add(key.algoOrderId());
            if (result.size() == boundedLimit) break;
        }
        return List.copyOf(result);
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.algoOrders() == after.algoOrders()) return;
        StateMapSupport.requireDeltaLineage(before.algoOrders(), after.algoOrders(), "algo orders");
        Set<Long> changed = StateMapSupport.changedKeys(after.algoOrders());
        for (Long id : changed) {
            if (id == null) continue;
            CoreAlgoOrderState previous = before.algoOrders().get(id);
            CoreAlgoOrderState current = after.algoOrders().get(id);
            if (previous != null) remove(previous);
            if (current != null) add(current);
        }
    }

    public void rebuild(TradingCoreState state) {
        idsByUser.clear();
        idsBySymbol.clear();
        allDue.clear();
        idsByClient.clear();
        state.algoOrders().values().forEach(this::add);
    }

    private NavigableSet<AlgoDueKey> candidateSet(long userId, String symbol) {
        NavigableSet<AlgoDueKey> userSet = userId == 0 ? null : idsByUser.get(userId);
        NavigableSet<AlgoDueKey> symbolSet = symbol.isEmpty() ? null : idsBySymbol.get(symbol);
        if (userSet == null) return symbolSet;
        if (symbolSet == null) return userSet;
        NavigableSet<AlgoDueKey> smaller = userSet.size() <= symbolSet.size() ? userSet : symbolSet;
        NavigableSet<AlgoDueKey> other = smaller == userSet ? symbolSet : userSet;
        TreeSet<AlgoDueKey> intersection = new TreeSet<>(DUE_ORDER);
        for (AlgoDueKey key : smaller) {
            if (other.contains(key)) intersection.add(key);
        }
        return intersection;
    }

    private void add(CoreAlgoOrderState value) {
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.add(key);
        idsByUser.computeIfAbsent(value.userId(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        idsBySymbol.computeIfAbsent(value.symbol(), ignored -> new TreeSet<>(DUE_ORDER)).add(key);
        if (!value.clientAlgoOrderId().isEmpty()) {
            idsByClient.put(new AlgoClientKey(value.userId(), value.clientAlgoOrderId()), value.algoOrderId());
        }
    }

    private void remove(CoreAlgoOrderState value) {
        AlgoDueKey key = new AlgoDueKey(value.nextSliceAtEpochMillis(), value.algoOrderId());
        allDue.remove(key);
        remove(idsByUser, value.userId(), key);
        remove(idsBySymbol, value.symbol(), key);
        if (!value.clientAlgoOrderId().isEmpty()) {
            idsByClient.remove(new AlgoClientKey(value.userId(), value.clientAlgoOrderId()));
        }
    }

    private static <K> void remove(Map<K, NavigableSet<AlgoDueKey>> values, K key, AlgoDueKey dueKey) {
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
