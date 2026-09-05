package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class LiquidationIndex {

    private final Map<LiquidationKey, NavigableSet<Long>> activeIds = new TreeMap<>();
    private final NavigableSet<Long> allActiveIds = new TreeSet<>();
    private final LongObjectHashMap<LiquidationKey> keysById = new LongObjectHashMap<>();

    public LiquidationIndex(TradingCoreState state) {
        rebuild(state);
    }

    public long activeId(long userId, String symbol, CorePositionSide positionSide) {
        NavigableSet<Long> ids = activeIds.get(new LiquidationKey(userId, symbol, positionSide));
        return ids == null || ids.isEmpty() ? 0 : ids.first();
    }

    public NavigableSet<Long> activeIds() {
        return allActiveIds;
    }

    void apply(java.util.List<RuntimeFactFrame.LiquidationChange> changes,
               RuntimeFactFrame.IdentityView identities) {
        for (RuntimeFactFrame.LiquidationChange change : changes) {
            apply(change.liquidationId(), change.after(), identities);
        }
    }

    void apply(long liquidationId, LiquidationRuntime after, RuntimeFactFrame.IdentityView identities) {
        LiquidationKey previous = keysById.get(liquidationId);
        LiquidationKey current = isActive(after)
                ? new LiquidationKey(after.userId(), identities.symbol(after.symbolId()), after.positionSide())
                : null;
        if (java.util.Objects.equals(previous, current)) return;
        if (previous != null) {
            keysById.remove(liquidationId);
            remove(liquidationId, previous);
        }
        if (current != null) {
            keysById.put(liquidationId, current);
            add(liquidationId, current);
        }
    }

    public void rebuild(TradingCoreState state) {
        activeIds.clear();
        allActiveIds.clear();
        keysById.clear();
        state.riskState().liquidations().values().stream()
                .filter(LiquidationIndex::isActive)
                .forEach(this::add);
    }

    private static boolean isActive(CoreLiquidationState value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    private static boolean isActive(LiquidationRuntime value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    private void add(CoreLiquidationState value) {
        LiquidationKey key = new LiquidationKey(value.userId(), value.symbol(), value.positionSide());
        keysById.put(value.liquidationId(), key);
        add(value.liquidationId(), key);
    }

    private void add(long liquidationId, LiquidationKey key) {
        allActiveIds.add(liquidationId);
        activeIds.computeIfAbsent(key, ignored -> new TreeSet<>()).add(liquidationId);
    }

    private void remove(CoreLiquidationState value) {
        LiquidationKey key = new LiquidationKey(value.userId(), value.symbol(), value.positionSide());
        keysById.remove(value.liquidationId());
        remove(value.liquidationId(), key);
    }

    private void remove(long liquidationId, LiquidationKey key) {
        allActiveIds.remove(liquidationId);
        NavigableSet<Long> ids = activeIds.get(key);
        if (ids == null) return;
        ids.remove(liquidationId);
        if (ids.isEmpty()) activeIds.remove(key);
    }

    private record LiquidationKey(long userId, String symbol, CorePositionSide positionSide)
            implements Comparable<LiquidationKey> {
        private LiquidationKey {
            symbol = OrderReservation.normalizeSymbol(symbol);
        }

        @Override
        public int compareTo(LiquidationKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = symbol.compareTo(other.symbol);
            return symbolCompare != 0 ? symbolCompare : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
