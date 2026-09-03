package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class OpenInterestIndex {

    private final NavigableMap<String, Totals> totals = new TreeMap<>();
    private final LongObjectHashMap<RuntimePositionIndexValue> positions = new LongObjectHashMap<>();
    private RuntimeIdentityRegistry identities;

    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    public OpenInterestIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableMap<String, Totals> totals() {
        return Collections.unmodifiableNavigableMap(totals);
    }

    public long openInterestSteps(String symbol) {
        Totals value = totals.get(OrderReservation.normalizeSymbol(symbol));
        return value == null ? 0 : Math.max(value.longQuantity(), value.shortQuantity());
    }

    void apply(java.util.List<RuntimeFactFrame.PositionChange> changes, RuntimeFactFrame.IdentityView identities) {
        for (RuntimeFactFrame.PositionChange change : changes) {
            apply(change.positionKey(), change.after(), identities);
        }
    }

    void apply(long positionKey, PositionRuntime after, RuntimeFactFrame.IdentityView identities) {
        RuntimePositionIndexValue previous = positions.removeKey(positionKey);
        if (previous != null) remove(previous);
        if (after != null) {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(after, identities);
            positions.put(positionKey, indexed);
            add(indexed);
        }
    }

    public void rebuild(TradingCoreState state) {
        totals.clear();
        state.users().values().forEach(user -> user.positions().values().forEach(this::add));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        this.identities = identities;
        totals.clear();
        positions.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            long positionKey = identities.positionKey(user.userId(), key);
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            positions.put(positionKey, indexed);
            add(indexed);
        }));
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

    private void add(RuntimePositionIndexValue position) {
        update(position, true);
    }

    private void remove(RuntimePositionIndexValue position) {
        update(position, false);
    }

    private void update(RuntimePositionIndexValue position, boolean add) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        Totals current = totals.getOrDefault(position.symbol(), Totals.EMPTY);
        long longDelta = quantity > 0 ? quantity : 0;
        long shortDelta = quantity < 0 ? Math.negateExact(quantity) : 0;
        Totals next = add
                ? new Totals(Math.addExact(current.longQuantity(), longDelta),
                Math.addExact(current.shortQuantity(), shortDelta))
                : new Totals(Math.subtractExact(current.longQuantity(), longDelta),
                Math.subtractExact(current.shortQuantity(), shortDelta));
        if (next.longQuantity() == 0 && next.shortQuantity() == 0) totals.remove(position.symbol());
        else totals.put(position.symbol(), next);
    }

    public record Totals(long longQuantity, long shortQuantity) {
        private static final Totals EMPTY = new Totals(0, 0);
    }
}
