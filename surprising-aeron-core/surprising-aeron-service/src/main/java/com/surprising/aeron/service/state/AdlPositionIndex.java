package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AdlPositionIndex {

    private final Map<String, HashSet<PositionKey>> keysByAsset = new HashMap<>();
    private final Map<Long, RuntimePositionIndexValue> positions = new HashMap<>();
    private final Map<AssetPositionKey, Integer> positionCounts = new HashMap<>();

    public AdlPositionIndex(TradingCoreState state) {
        rebuild(state);
    }

    public AdlPositionIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public Set<PositionKey> positions(String asset) {
        Set<PositionKey> values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        if (values == null || values.isEmpty()) return Set.of();
        PositionKey[] ordered = values.toArray(PositionKey[]::new);
        java.util.Arrays.sort(ordered);
        LinkedHashSet<PositionKey> result = new LinkedHashSet<>(ordered.length);
        Collections.addAll(result, ordered);
        return Collections.unmodifiableSet(result);
    }

    void apply(java.util.List<RuntimeFactFrame.PositionChange> changes, RuntimeFactFrame.IdentityView identities) {
        for (RuntimeFactFrame.PositionChange change : changes) {
            apply(change.positionKey(), change.after(), identities);
        }
    }

    void apply(long positionKey, PositionRuntime after, RuntimeFactFrame.IdentityView identities) {
        RuntimePositionIndexValue previous = positions.remove(positionKey);
        if (previous != null) remove(previous);
        if (after != null) {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(after, identities);
            positions.put(positionKey, indexed);
            add(indexed);
        }
    }

    public void rebuild(TradingCoreState state) {
        keysByAsset.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> add(user.userId(), position)));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        keysByAsset.clear();
        positions.clear();
        positionCounts.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            positions.put(positionKey, indexed);
            add(indexed);
        }));
    }

    private void add(RuntimePositionIndexValue position) {
        if (position.signedQuantitySteps() == 0) return;
        PositionKey key = new PositionKey(position.userId(), position.symbol(), position.positionSide());
        AssetPositionKey counted = new AssetPositionKey(position.asset(), key);
        if (positionCounts.merge(counted, 1, Math::addExact) == 1) {
            keysByAsset.computeIfAbsent(position.asset(), ignored -> new HashSet<>()).add(key);
        }
    }

    private void remove(RuntimePositionIndexValue position) {
        if (position.signedQuantitySteps() == 0) return;
        PositionKey key = new PositionKey(position.userId(), position.symbol(), position.positionSide());
        AssetPositionKey counted = new AssetPositionKey(position.asset(), key);
        int count = Math.subtractExact(positionCounts.getOrDefault(counted, 0), 1);
        if (count != 0) {
            positionCounts.put(counted, count);
            return;
        }
        positionCounts.remove(counted);
        Set<PositionKey> values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) keysByAsset.remove(position.asset());
    }

    private record AssetPositionKey(String asset, PositionKey position) {}

    private void add(long userId, CorePositionState position) {
        if (position.signedQuantitySteps() == 0) return;
        keysByAsset.computeIfAbsent(position.marginAsset(), ignored -> new HashSet<>())
                .add(new PositionKey(userId, position.symbol(), position.positionSide()));
    }

    private void remove(long userId, CorePositionState position) {
        Set<PositionKey> values = keysByAsset.get(position.marginAsset());
        if (values == null) return;
        values.remove(new PositionKey(userId, position.symbol(), position.positionSide()));
        if (values.isEmpty()) keysByAsset.remove(position.marginAsset());
    }

    public record PositionKey(long userId, String symbol, com.surprising.aeron.protocol.CorePositionSide positionSide)
            implements Comparable<PositionKey> {
        @Override
        public int compareTo(PositionKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = symbol.compareTo(other.symbol);
            return symbolCompare != 0 ? symbolCompare
                    : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
