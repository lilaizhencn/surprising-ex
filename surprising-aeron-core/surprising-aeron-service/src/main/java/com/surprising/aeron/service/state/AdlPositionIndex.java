package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public final class AdlPositionIndex {

    private final Map<String, NavigableSet<PositionKey>> keysByAsset = new HashMap<>();
    private final Map<Long, RuntimePositionIndexValue> positions = new HashMap<>();
    private final Map<AssetPositionKey, Integer> positionCounts = new HashMap<>();

    public AdlPositionIndex(TradingCoreState state) {
        rebuild(state);
    }

    public AdlPositionIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public Set<PositionKey> positions(String asset) {
        NavigableSet<PositionKey> values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        return values == null ? Set.of() : Collections.unmodifiableNavigableSet(values);
    }

    void apply(java.util.List<RuntimeCommitPatch.PositionChange> changes, RuntimeCommitPatch.IdentityView identities) {
        for (RuntimeCommitPatch.PositionChange change : changes) {
            RuntimePositionIndexValue previous = positions.remove(change.positionKey());
            if (previous != null) remove(previous);
            if (change.after() != null) {
                RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(change.after(), identities);
                positions.put(change.positionKey(), indexed);
                add(indexed);
            }
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
            keysByAsset.computeIfAbsent(position.asset(), ignored -> new TreeSet<>()).add(key);
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
        NavigableSet<PositionKey> values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) keysByAsset.remove(position.asset());
    }

    private record AssetPositionKey(String asset, PositionKey position) {}

    private void add(long userId, CorePositionState position) {
        if (position.signedQuantitySteps() == 0) return;
        keysByAsset.computeIfAbsent(position.marginAsset(), ignored -> new TreeSet<>())
                .add(new PositionKey(userId, position.symbol(), position.positionSide()));
    }

    private void remove(long userId, CorePositionState position) {
        NavigableSet<PositionKey> values = keysByAsset.get(position.marginAsset());
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
