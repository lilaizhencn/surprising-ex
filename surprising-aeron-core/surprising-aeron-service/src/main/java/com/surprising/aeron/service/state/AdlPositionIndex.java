package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class AdlPositionIndex {

    private final Map<String, NavigableSet<PositionKey>> keysByAsset = new TreeMap<>();

    public AdlPositionIndex(TradingCoreState state) {
        rebuild(state);
    }

    public Set<PositionKey> positions(String asset) {
        NavigableSet<PositionKey> values = keysByAsset.get(AssetBalance.normalizeAsset(asset));
        return values == null ? Set.of() : Collections.unmodifiableNavigableSet(values);
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.users() == after.users()) return;
        if (!StateMapSupport.isDelta(after.users())) {
            rebuild(after);
            return;
        }
        Set<Long> changed = after.changedUserIds();
        for (Long userId : changed) {
            if (userId == null) continue;
            CoreUserState previous = before.user(userId);
            CoreUserState current = after.user(userId);
            if (previous != null) previous.positions().values().forEach(position -> remove(userId, position));
            if (current != null) current.positions().values().forEach(position -> add(userId, position));
        }
    }

    public void rebuild(TradingCoreState state) {
        keysByAsset.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> add(user.userId(), position)));
    }

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
