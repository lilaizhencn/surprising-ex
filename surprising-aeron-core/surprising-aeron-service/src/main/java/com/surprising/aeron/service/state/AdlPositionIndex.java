package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class AdlPositionIndex {

    private final Map<String, LongHashSet> keysByAsset = new HashMap<>();
    private final LongObjectHashMap<RuntimePositionIndexValue> positions = new LongObjectHashMap<>();

    public AdlPositionIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public Set<PositionKey> positions(String asset) {
        LongHashSet values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        if (values == null || values.isEmpty()) return Set.of();
        PositionKey[] captured = new PositionKey[values.size()];
        int[] cursor = {0};
        values.forEach(positionKey -> {
            RuntimePositionIndexValue value = positions.get(positionKey);
            if (value != null) captured[cursor[0]++] = new PositionKey(
                    value.userId(), value.symbol(), value.positionSide());
        });
        PositionKey[] ordered = cursor[0] == captured.length
                ? captured : java.util.Arrays.copyOf(captured, cursor[0]);
        java.util.Arrays.sort(ordered);
        LinkedHashSet<PositionKey> result = new LinkedHashSet<>(ordered.length);
        Collections.addAll(result, ordered);
        return Collections.unmodifiableSet(result);
    }

    RuntimePositionIndexValue value(long positionKey) {
        return positions.get(positionKey);
    }

    void apply(long positionKey, RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        RuntimePositionIndexValue indexedPrevious = positions.remove(positionKey);
        if (indexedPrevious != previous) {
            throw new IllegalStateException("ADL position index differs from shared position index");
        }
        if (previous != null) remove(previous, positionKey);
        if (current != null) {
            positions.put(positionKey, current);
            add(current, positionKey);
        }
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        keysByAsset.clear();
        positions.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            positions.put(positionKey, indexed);
            add(indexed, positionKey);
        }));
    }

    private void add(RuntimePositionIndexValue position, long positionKey) {
        if (position.signedQuantitySteps() == 0) return;
        keysByAsset.computeIfAbsent(position.asset(), ignored -> new LongHashSet()).add(positionKey);
    }

    private void remove(RuntimePositionIndexValue position, long positionKey) {
        if (position.signedQuantitySteps() == 0) return;
        LongHashSet values = keysByAsset.get(position.asset());
        if (values == null) return;
        values.remove(positionKey);
        if (values.isEmpty()) keysByAsset.remove(position.asset());
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
