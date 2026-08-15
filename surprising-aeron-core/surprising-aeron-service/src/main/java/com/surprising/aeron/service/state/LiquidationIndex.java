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

    public void update(TradingCoreState before, TradingCoreState after) {
        Set<Long> changed = after.changedLiquidationIdsSince(before);
        if (changed == null) {
            rebuild(after);
            return;
        }
        for (Long id : changed) {
            if (id == null) continue;
            CoreLiquidationState previous = before.riskState().liquidations().get(id);
            CoreLiquidationState current = after.riskState().liquidations().get(id);
            if (isActive(previous)) remove(previous);
            if (isActive(current)) add(current);
        }
    }

    public void rebuild(TradingCoreState state) {
        activeIds.clear();
        allActiveIds.clear();
        state.riskState().liquidations().values().stream()
                .filter(LiquidationIndex::isActive)
                .forEach(this::add);
    }

    private static boolean isActive(CoreLiquidationState value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    private void add(CoreLiquidationState value) {
        allActiveIds.add(value.liquidationId());
        activeIds.computeIfAbsent(new LiquidationKey(value.userId(), value.symbol(), value.positionSide()),
                ignored -> new TreeSet<>()).add(value.liquidationId());
    }

    private void remove(CoreLiquidationState value) {
        allActiveIds.remove(value.liquidationId());
        LiquidationKey key = new LiquidationKey(value.userId(), value.symbol(), value.positionSide());
        NavigableSet<Long> ids = activeIds.get(key);
        if (ids == null) return;
        ids.remove(value.liquidationId());
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
