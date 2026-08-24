package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PositionUserIndex {

    private final Map<String, NavigableSet<Long>> usersBySymbol = new TreeMap<>();

    public PositionUserIndex(TradingCoreState state) {
        rebuild(state);
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

    public void rebuild(TradingCoreState state) {
        usersBySymbol.clear();
        state.users().values().forEach(user -> user.positions().values()
                .forEach(position -> add(position.symbol(), user.userId())));
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
