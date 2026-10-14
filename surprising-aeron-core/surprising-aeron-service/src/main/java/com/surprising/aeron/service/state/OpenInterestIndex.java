package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CorePositionState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class OpenInterestIndex {
    private Thread businessOwner;
    void bindBusinessOwner(Thread thread) { businessOwner = thread; }
    private void assertBusinessOwner() {
        if (businessOwner != null && businessOwner != Thread.currentThread())
            throw new IllegalStateException("position business index requires SystemLedgerLane");
    }

    private final Map<String, MutableTotals> totals = new HashMap<>();

    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    public OpenInterestIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableMap<String, Totals> totals() {
        assertBusinessOwner();
        TreeMap<String, Totals> snapshot = new TreeMap<>();
        totals.forEach((instrumentId, value) -> {
            if (value.longQuantity != 0 || value.shortQuantity != 0)
                snapshot.put(instrumentId, new Totals(value.longQuantity, value.shortQuantity));
        });
        return Collections.unmodifiableNavigableMap(snapshot);
    }

    public long openInterestSteps(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : Math.max(value.longQuantity, value.shortQuantity);
    }

    public long longQuantity(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.longQuantity;
    }

    public long longQuantityNormalized(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.longQuantity;
    }

    public long shortQuantity(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(OrderReservation.requireInstrumentId(instrumentId));
        return value == null ? 0 : value.shortQuantity;
    }

    public long shortQuantityNormalized(String instrumentId) {
        assertBusinessOwner();
        MutableTotals value = totals.get(instrumentId);
        return value == null ? 0 : value.shortQuantity;
    }

    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        applyQuantities(previous == null ? null : previous.instrumentId(), previous == null ? 0 : previous.signedQuantitySteps(),
                current == null ? null : current.instrumentId(), current == null ? 0 : current.signedQuantitySteps());
    }

    void applyQuantities(String previousSymbol, long previousQuantity, String currentSymbol, long currentQuantity) {
        assertBusinessOwner();
        if (previousSymbol != null && previousSymbol.equals(currentSymbol)) {
            adjust(currentSymbol, longQuantity(currentQuantity) - longQuantity(previousQuantity),
                    shortQuantity(currentQuantity) - shortQuantity(previousQuantity));
            return;
        }
        if (previousSymbol != null) adjust(previousSymbol, -longQuantity(previousQuantity), -shortQuantity(previousQuantity));
        if (currentSymbol != null) adjust(currentSymbol, longQuantity(currentQuantity), shortQuantity(currentQuantity));
    }

    public void rebuild(TradingCoreState state) {
        assertBusinessOwner();
        totals.clear();
        state.users().values().forEach(user -> user.positions().values().forEach(this::add));
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        assertBusinessOwner();
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
        long longs = Math.addExact(current.longQuantity, longDelta);
        long shorts = Math.addExact(current.shortQuantity, shortDelta);
        if (longs < 0 || shorts < 0) {
            throw new IllegalStateException("open interest index is negative for instrumentId=" + instrumentId);
        }
        current.longQuantity = longs; current.shortQuantity = shorts;
        // Retain one cell per encountered registered symbol; zero rows stay absent in query output.
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
