package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class LiquidationIndex {

    private final Map<LiquidationKey, NavigableSet<Long>> activeIds = new TreeMap<>();
    private final NavigableSet<Long> allActiveIds = new TreeSet<>();
    private final Map<Long, LiquidationKey> keysById = new TreeMap<>();

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

    void apply(java.util.List<RuntimeCommitPatch.LiquidationChange> changes,
               RuntimeCommitPatch.IdentityView identities) {
        for (RuntimeCommitPatch.LiquidationChange change : changes) {
            LiquidationKey previous = keysById.remove(change.liquidationId());
            if (previous != null) remove(change.liquidationId(), previous);
            if (isActive(change.after())) {
                LiquidationKey key = new LiquidationKey(change.after().userId(),
                        identities.symbol(change.after().symbolId()), change.after().positionSide());
                keysById.put(change.liquidationId(), key);
                add(change.liquidationId(), key);
            }
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
