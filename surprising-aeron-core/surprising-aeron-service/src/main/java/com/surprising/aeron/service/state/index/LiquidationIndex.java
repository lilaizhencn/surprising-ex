package com.surprising.aeron.service.state.index;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

public final class LiquidationIndex {

    private final Map<LiquidationKey, NavigableSet<Long>> activeIds = new TreeMap<>();
    private final NavigableSet<Long> allActiveIds = new TreeSet<>();
    private final LongObjectHashMap<LiquidationRoute> keysById = new LongObjectHashMap<>();

    public LiquidationIndex(TradingCoreState state) {
        rebuild(state);
    }

    public long activeId(long userId, String instrumentId, CorePositionSide positionSide) {
        NavigableSet<Long> ids = activeIds.get(new LiquidationKey(userId, instrumentId, positionSide));
        return ids == null || ids.isEmpty() ? 0 : ids.first();
    }

    public NavigableSet<Long> activeIds() {
        return allActiveIds;
    }

    public record LiquidationRoute(long userId, String instrumentId, CorePositionSide positionSide,
                                   boolean unresolved, boolean executable) {
        LiquidationKey key() { return new LiquidationKey(userId, instrumentId, positionSide); }
    }
    public LiquidationRoute route(long id) { return keysById.get(id); }

    public boolean hasUnresolved(String instrumentId) {
        for (LiquidationRoute route : keysById.values())
            if (route.unresolved() && (instrumentId == null || route.instrumentId().equals(instrumentId))) return true;
        return false;
    }

    public void apply(long liquidationId, LiquidationRuntime after, RuntimeIdentityRegistry identities) {
        LiquidationRoute previous = keysById.remove(liquidationId);
        if (previous != null && previous.unresolved()) remove(liquidationId, previous.key());
        if (after != null) {
            LiquidationRoute current = new LiquidationRoute(after.userId(), identities.instrumentId(after.symbolId()),
                    after.positionSide(), isActive(after), after.status() == CoreLiquidationState.Status.PLANNED
                    || after.status() == CoreLiquidationState.Status.ORDERED);
            keysById.put(liquidationId, current);
            if (current.unresolved()) add(liquidationId, current.key());
        }
    }

    public void rebuild(TradingCoreState state) {
        activeIds.clear();
        allActiveIds.clear();
        keysById.clear();
        state.riskState().liquidations().values().forEach(this::add);
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
        LiquidationKey key = new LiquidationKey(value.userId(), value.instrumentId(), value.positionSide());
        keysById.put(value.liquidationId(), new LiquidationRoute(value.userId(), value.instrumentId(),
                value.positionSide(), isActive(value), value.status() == CoreLiquidationState.Status.PLANNED
                || value.status() == CoreLiquidationState.Status.ORDERED));
        if (isActive(value)) add(value.liquidationId(), key);
    }

    private void add(long liquidationId, LiquidationKey key) {
        allActiveIds.add(liquidationId);
        activeIds.computeIfAbsent(key, ignored -> new TreeSet<>()).add(liquidationId);
    }

    private void remove(CoreLiquidationState value) {
        LiquidationKey key = new LiquidationKey(value.userId(), value.instrumentId(), value.positionSide());
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

    private record LiquidationKey(long userId, String instrumentId, CorePositionSide positionSide)
            implements Comparable<LiquidationKey> {
        private LiquidationKey {
            instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        }

        @Override
        public int compareTo(LiquidationKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
