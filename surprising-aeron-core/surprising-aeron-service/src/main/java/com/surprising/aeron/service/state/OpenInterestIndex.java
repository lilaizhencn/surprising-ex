package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CorePositionState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class OpenInterestIndex {

    private final Map<String, MutableTotals> totals = new HashMap<>();

    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    public OpenInterestIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableMap<String, Totals> totals() {
        TreeMap<String, Totals> snapshot = new TreeMap<>();
        totals.forEach((instrumentId, value) -> snapshot.put(
                instrumentId, new Totals(value.longQuantity, value.shortQuantity)));
        return Collections.unmodifiableNavigableMap(snapshot);
    }

    public long openInterestSteps(String instrumentId) {
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : Math.max(value.longQuantity, value.shortQuantity);
    }

    public long longQuantity(String instrumentId) {
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.longQuantity;
    }

    public long longQuantityNormalized(String instrumentId) {
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.longQuantity;
    }

    public long shortQuantity(String instrumentId) {
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.shortQuantity;
    }

    public long shortQuantityNormalized(String instrumentId) {
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.shortQuantity;
    }

    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        if (previous != null && current != null && previous.instrumentId().equals(current.instrumentId())) {
            adjust(current.instrumentId(),
                    longQuantity(current.signedQuantitySteps()) - longQuantity(previous.signedQuantitySteps()),
                    shortQuantity(current.signedQuantitySteps()) - shortQuantity(previous.signedQuantitySteps()));
            return;
        }
        if (previous != null) adjust(previous.instrumentId(),
                -longQuantity(previous.signedQuantitySteps()),
                -shortQuantity(previous.signedQuantitySteps()));
        if (current != null) adjust(current.instrumentId(),
                longQuantity(current.signedQuantitySteps()),
                shortQuantity(current.signedQuantitySteps()));
    }

    public void rebuild(TradingCoreState state) {
        totals.clear();
        state.users().values().forEach(user -> user.positions().values().forEach(this::add));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        totals.clear();
        state.users().values().forEach(user -> user.positions().forEach((key, position) -> {
            RuntimePositionIndexValue indexed = RuntimePositionIndexValue.from(user.userId(), position);
            add(indexed);
        }));
    }

    private void add(CorePositionState position) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), longQuantity(quantity), shortQuantity(quantity));
    }

    private void remove(CorePositionState position) {
        long quantity = position.signedQuantitySteps();
        if (quantity == 0) return;
        adjust(position.instrumentId(), -longQuantity(quantity), -shortQuantity(quantity));
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
        long direction = add ? 1 : -1;
        adjust(position.instrumentId(), Math.multiplyExact(longQuantity(quantity), direction),
                Math.multiplyExact(shortQuantity(quantity), direction));
    }

    private void adjust(String instrumentId, long longDelta, long shortDelta) {
        if (longDelta == 0 && shortDelta == 0) return;
        MutableTotals current = totals.get(instrumentId);
        if (current == null) {
            if (longDelta < 0 || shortDelta < 0) {
                throw new IllegalStateException("open interest index is missing instrumentId=" + instrumentId);
            }
            totals.put(instrumentId, new MutableTotals(longDelta, shortDelta));
            return;
        }
        current.longQuantity = Math.addExact(current.longQuantity, longDelta);
        current.shortQuantity = Math.addExact(current.shortQuantity, shortDelta);
        if (current.longQuantity < 0 || current.shortQuantity < 0) {
            throw new IllegalStateException("open interest index is negative for instrumentId=" + instrumentId);
        }
        if (current.longQuantity == 0 && current.shortQuantity == 0) totals.remove(instrumentId);
    }

    private static long longQuantity(long signedQuantity) {
        return signedQuantity > 0 ? signedQuantity : 0;
    }

    private static long shortQuantity(long signedQuantity) {
        return signedQuantity < 0 ? Math.negateExact(signedQuantity) : 0;
    }

    public record Totals(long longQuantity, long shortQuantity) {
    }

    private static final class MutableTotals {
        private long longQuantity;
        private long shortQuantity;

        private MutableTotals(long longQuantity, long shortQuantity) {
            this.longQuantity = longQuantity;
            this.shortQuantity = shortQuantity;
        }
    }
}
