package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CorePositionState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class OpenInterestIndex {

    private final Map<String, MutableTotals> totals = new HashMap<>();
    /** Owner-only scratch over completed Lane changes; never retains account state after the calculation. */
    private final PendingPositions pendingPositions = new PendingPositions();

    public OpenInterestIndex(TradingCoreState state) {
        rebuild(state);
    }

    public OpenInterestIndex(TradingCoreState state, RuntimeIdentityRegistry identities) {
        rebuild(state, identities);
    }

    public NavigableMap<String, Totals> totals() {
        TreeMap<String, Totals> snapshot = new TreeMap<>();
        totals.forEach((instrumentId, value) -> {
            if (value.longQuantity != 0 || value.shortQuantity != 0)
                snapshot.put(instrumentId, new Totals(value.longQuantity, value.shortQuantity));
        });
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

    /** Includes sequential batch fills which have completed on Lanes but are not yet journaled. */
    public long pendingOpenInterestSteps(String instrumentId, TradingRuntimeState runtime,
            AdlPositionIndex committedPositions, RuntimeIdentityRegistry identities) {
        runtime.assertOwner();
        var pending = pendingPositions;
        if (pending.instrumentId != null) throw new IllegalStateException("nested pending open interest calculation");
        pending.instrumentId = instrumentId;
        pending.committedPositions = committedPositions;
        pending.identities = identities;
        pending.longQuantity = longQuantityNormalized(instrumentId);
        pending.shortQuantity = shortQuantityNormalized(instrumentId);
        try {
            // The Owner owns the adopted immutable after-images. No live Account Lane table
            // or temporary position index value is needed to calculate this pending prefix.
            runtime.changedPositions.forEachIndexed(pending);
            if (pending.longQuantity < 0 || pending.shortQuantity < 0)
                throw new IllegalStateException("pending open interest is negative");
            return Math.max(pending.longQuantity, pending.shortQuantity);
        } finally {
            pending.instrumentId = null;
            pending.committedPositions = null;
            pending.identities = null;
        }
    }

    private static final class PendingPositions
            implements RuntimeIndexedChangeBuffer.Consumer<PositionRuntime, RuntimePositionIndexValue> {
        private String instrumentId;
        private AdlPositionIndex committedPositions;
        private RuntimeIdentityRegistry identities;
        private long longQuantity, shortQuantity;

        @Override public void accept(long key, PositionRuntime published, boolean hasPrepared,
                RuntimePositionIndexValue prepared) {
            var previous = committedPositions.value(key);
            // Synchronous control changes use immutable publication values in the same buffer.
            String currentSymbol = hasPrepared ? prepared == null ? null : prepared.instrumentId()
                    : published == null ? null : identities.instrumentId(published.symbolId());
            long currentQuantity = hasPrepared ? prepared == null ? 0 : prepared.signedQuantitySteps()
                    : published == null ? 0 : published.signedQuantitySteps();
            if (previous != null && instrumentId.equals(previous.instrumentId())) {
                longQuantity = Math.subtractExact(longQuantity, longQuantity(previous.signedQuantitySteps()));
                shortQuantity = Math.subtractExact(shortQuantity, shortQuantity(previous.signedQuantitySteps()));
            }
            if (instrumentId.equals(currentSymbol)) {
                longQuantity = Math.addExact(longQuantity, longQuantity(currentQuantity));
                shortQuantity = Math.addExact(shortQuantity, shortQuantity(currentQuantity));
            }
        }
    }

    void apply(RuntimePositionIndexValue previous, RuntimePositionIndexValue current) {
        applyQuantities(previous == null ? null : previous.instrumentId(), previous == null ? 0 : previous.signedQuantitySteps(),
                current == null ? null : current.instrumentId(), current == null ? 0 : current.signedQuantitySteps());
    }

    void applyQuantities(String previousSymbol, long previousQuantity, String currentSymbol, long currentQuantity) {
        if (previousSymbol != null && previousSymbol.equals(currentSymbol)) {
            adjust(currentSymbol, longQuantity(currentQuantity) - longQuantity(previousQuantity),
                    shortQuantity(currentQuantity) - shortQuantity(previousQuantity));
            return;
        }
        if (previousSymbol != null) adjust(previousSymbol, -longQuantity(previousQuantity), -shortQuantity(previousQuantity));
        if (currentSymbol != null) adjust(currentSymbol, longQuantity(currentQuantity), shortQuantity(currentQuantity));
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
