package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PositionUserIndex {

    private final Map<String, NavigableSet<Long>> usersBySymbol = new TreeMap<>();
    private final Map<Long, RuntimePositionIndexValue> positions = new TreeMap<>();
    private final Map<UserSymbol, Integer> positionCounts = new TreeMap<>();

    public PositionUserIndex(TradingCoreState state) {
        rebuild(state);
    }

    public PositionUserIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableSet<Long> users(String symbol) {
        NavigableSet<Long> users = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return users == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(users);
    }

    public Long higherUser(String symbol, long cursorUserId) {
        NavigableSet<Long> users = usersBySymbol.get(OrderReservation.normalizeSymbol(symbol));
        return users == null ? null : users.higher(cursorUserId);
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.users() == after.users()) return;
        StateMapSupport.requireDeltaLineage(before.users(), after.users(), "position user index users");
        Set<Long> changedUsers = StateMapSupport.changedKeys(after.users());
        for (Long userId : changedUsers) {
            if (userId == null) continue;
            CoreUserState previous = before.user(userId);
            CoreUserState current = after.user(userId);
            if (previous != null) {
                previous.positions().values().forEach(position -> remove(position.symbol(), userId));
            }
            if (current != null) {
                current.positions().values().forEach(position -> add(position.symbol(), userId));
            }
        }
    }

    public void update(RuntimeCommitEntry entry) {
        RuntimeMutationDelta.ValueChanges<Long, PositionRuntime> changes = entry.mutation().positions();
        for (Long positionKey : changes.changedKeys()) {
            RuntimePositionIndexValue previous = positions.remove(positionKey);
            if (previous != null) removePosition(previous);
            PositionRuntime current = changes.currentValues().get(positionKey);
            if (current != null) {
                RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(current, entry.identities());
                positions.put(positionKey, indexed);
                addPosition(indexed);
            }
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
        positionCounts.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            positions.put(positionKey, indexed);
            addPosition(indexed);
        }));
    }

    private void addPosition(RuntimePositionIndexValue position) {
        UserSymbol key = new UserSymbol(position.userId(), position.symbol());
        int count = positionCounts.merge(key, 1, Math::addExact);
        if (count == 1) add(position.symbol(), position.userId());
    }

    private void removePosition(RuntimePositionIndexValue position) {
        UserSymbol key = new UserSymbol(position.userId(), position.symbol());
        int count = Math.subtractExact(positionCounts.getOrDefault(key, 0), 1);
        if (count == 0) {
            positionCounts.remove(key);
            remove(position.symbol(), position.userId());
        } else {
            positionCounts.put(key, count);
        }
    }

    private record UserSymbol(long userId, String symbol) implements Comparable<UserSymbol> {
        @Override
        public int compareTo(UserSymbol other) {
            int comparison = Long.compare(userId, other.userId);
            return comparison != 0 ? comparison : symbol.compareTo(other.symbol);
        }
    }

    private void add(String symbol, long userId) {
        usersBySymbol.computeIfAbsent(symbol, ignored -> new TreeSet<>()).add(userId);
    }

    private void remove(String symbol, long userId) {
        NavigableSet<Long> users = usersBySymbol.get(symbol);
        if (users == null) return;
        users.remove(userId);
        if (users.isEmpty()) usersBySymbol.remove(symbol);
    }
}
