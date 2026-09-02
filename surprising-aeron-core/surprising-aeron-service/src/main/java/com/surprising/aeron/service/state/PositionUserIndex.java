package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class PositionUserIndex {

    private final Map<String, LongHashSet> usersBySymbol = new HashMap<>();
    private final LongObjectHashMap<RuntimePositionIndexValue> positions = new LongObjectHashMap<>();
    private final Map<String, LongIntHashMap> positionCountsBySymbol = new HashMap<>();

    public PositionUserIndex(TradingCoreState state) {
        rebuild(state);
    }

    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableSet<Long> users(String symbol) {
        LongHashSet users = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (users == null) return Collections.emptyNavigableSet();
        TreeSet<Long> sorted = new TreeSet<>();
        users.forEach(sorted::add);
        return Collections.unmodifiableNavigableSet(sorted);
    }

    public Long higherUser(String symbol, long cursorUserId) {
        LongHashSet users = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        if (users == null) return null;
        long higher = Long.MAX_VALUE;
        LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (userId > cursorUserId && userId < higher) higher = userId;
        }
        return higher == Long.MAX_VALUE ? null : higher;
    }

    void apply(java.util.List<RuntimeFactFrame.PositionChange> changes, RuntimeFactFrame.IdentityView identities) {
        for (RuntimeFactFrame.PositionChange change : changes) {
            apply(change.positionKey(), change.after(), identities);
        }
    }

    void apply(long positionKey, PositionRuntime after, RuntimeFactFrame.IdentityView identities) {
        RuntimePositionIndexValue previous = positions.removeKey(positionKey);
        if (previous != null) removePosition(previous);
        if (after != null) {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(after, identities);
            positions.put(positionKey, indexed);
            addPosition(indexed);
        }
    }

    public void rebuild(TradingCoreState state) {
        usersBySymbol.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> add(position.symbol(), user.userId())));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        usersBySymbol.clear();
        positions.clear();
        positionCountsBySymbol.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            positions.put(positionKey, indexed);
            addPosition(indexed);
        }));
    }

    private void addPosition(RuntimePositionIndexValue position) {
        LongIntHashMap counts = positionCountsBySymbol.computeIfAbsent(
                position.symbol(), ignored -> new LongIntHashMap());
        int count = counts.addToValue(position.userId(), 1);
        if (count == 1) add(position.symbol(), position.userId());
    }

    private void removePosition(RuntimePositionIndexValue position) {
        LongIntHashMap counts = positionCountsBySymbol.get(position.symbol());
        if (counts == null) throw new IllegalStateException("position user count is missing");
        int count = counts.addToValue(position.userId(), -1);
        if (count == 0) {
            counts.removeKey(position.userId());
            if (counts.isEmpty()) positionCountsBySymbol.remove(position.symbol());
            remove(position.symbol(), position.userId());
        } else if (count < 0) throw new IllegalStateException("negative position user count");
    }

    private void add(String symbol, long userId) {
        usersBySymbol.computeIfAbsent(symbol, ignored -> new LongHashSet()).add(userId);
    }

    private void remove(String symbol, long userId) {
        LongHashSet users = usersBySymbol.get(symbol);
        if (users == null) return;
        users.remove(userId);
        if (users.isEmpty()) usersBySymbol.remove(symbol);
    }
}
