package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public final class OpenInterestIndex {

    private final NavigableMap<String, Totals> totals = new TreeMap<>();

    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    public NavigableMap<String, Totals> totals() {
        return Collections.unmodifiableNavigableMap(totals);
    }

    public long openInterestSteps(String symbol) {
        Totals value = totals.get(OrderReservation.normalizeSymbol(symbol));
        return value == null ? 0 : Math.max(value.longQuantity(), value.shortQuantity());
    }

    public void update(TradingCoreState before, TradingCoreState after) {
        if (before.users() == after.users()) return;
        StateMapSupport.requireDeltaLineage(before.users(), after.users(), "open interest users");
        Set<Long> changedUsers = StateMapSupport.changedKeys(after.users());
        for (Long userId : changedUsers) {
            if (userId == null) continue;
            CoreUserState previous = before.user(userId);
            CoreUserState current = after.user(userId);
            if (previous != null) previous.positions().values().forEach(this::remove);
            if (current != null) current.positions().values().forEach(this::add);
        }
    }

    public void rebuild(TradingCoreState state) {
        totals.clear();
        state.users().values().forEach(user -> user.positions().values().forEach(this::add));
    }

    private void add(CorePositionState position) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        Totals current = totals.getOrDefault(position.symbol(), Totals.EMPTY);
        totals.put(position.symbol(), quantity > 0
                ? new Totals(Math.addExact(current.longQuantity(), quantity), current.shortQuantity())
                : new Totals(current.longQuantity(), Math.addExact(current.shortQuantity(), Math.negateExact(quantity))));
    }

    private void remove(CorePositionState position) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        Totals current = totals.get(position.symbol());
        if (current == null) {
            throw new IllegalStateException("open interest index is missing symbol=" + position.symbol());
        }
        Totals next = quantity > 0
                ? new Totals(Math.subtractExact(current.longQuantity(), quantity), current.shortQuantity())
                : new Totals(current.longQuantity(), Math.subtractExact(current.shortQuantity(), Math.negateExact(quantity)));
        if (next.longQuantity() == 0 && next.shortQuantity() == 0) totals.remove(position.symbol());
        else totals.put(position.symbol(), next);
    }

    public record Totals(long longQuantity, long shortQuantity) {
        private static final Totals EMPTY = new Totals(0, 0);
    }
}
