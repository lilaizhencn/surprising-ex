package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CoreUserState {

    private final ProductLine productLine;
    private final long userId;
    private final long revision;
    private final Map<String, AssetBalance> balances;
    private final Map<Long, OrderReservation> reservations;
    private final Map<String, CorePositionState> positions;
    private final CorePositionMode positionMode;
    private final Map<String, Long> explainedLocks;

    public CoreUserState(ProductLine productLine,
                         long userId,
                         long revision,
                         Map<String, AssetBalance> balances,
                         Map<Long, OrderReservation> reservations,
                         Map<String, CorePositionState> positions,
                         CorePositionMode positionMode) {
        this(productLine, userId, revision, balances, reservations, positions, positionMode,
                calculateExplainedLocks(reservations, positions), false);
    }

    public CoreUserState(ProductLine productLine,
                         long userId,
                         long revision,
                         Map<String, AssetBalance> balances,
                         Map<Long, OrderReservation> reservations,
                         Map<String, CorePositionState> positions) {
        this(productLine, userId, revision, balances, reservations, positions, CorePositionMode.ONE_WAY);
    }

    private CoreUserState(ProductLine productLine,
                          long userId,
                          long revision,
                          Map<String, AssetBalance> balances,
                          Map<Long, OrderReservation> reservations,
                          Map<String, CorePositionState> positions,
                          CorePositionMode positionMode,
                          Map<String, Long> explainedLocks,
                          boolean incremental) {
        if (productLine == null || userId <= 0 || revision < 0 || balances == null || reservations == null
                || positions == null || positionMode == null || explainedLocks == null) {
            throw new IllegalArgumentException("invalid user state");
        }
        validateEntries(balances, reservations, positions, incremental);
        validateLocks(balances, explainedLocks, incremental);
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.balances = StateMapSupport.freezeSorted(balances);
        this.reservations = StateMapSupport.freezeSorted(reservations);
        this.positions = StateMapSupport.freezeSorted(positions);
        this.positionMode = positionMode;
        this.explainedLocks = StateMapSupport.freezeSorted(explainedLocks);
    }

    public static CoreUserState empty(ProductLine productLine, long userId) {
        return new CoreUserState(productLine, userId, 0, Map.of(), Map.of(), Map.of(),
                CorePositionMode.ONE_WAY);
    }

    CoreUserState transition(long nextRevision,
                             Map<String, AssetBalance> nextBalances,
                             Map<Long, OrderReservation> nextReservations,
                             Map<String, CorePositionState> nextPositions,
                             CorePositionMode nextPositionMode) {
        requireLineage("balances", balances, nextBalances);
        requireLineage("reservations", reservations, nextReservations);
        requireLineage("positions", positions, nextPositions);
        Map<String, Long> nextExplainedLocks = StateMapSupport.delta(explainedLocks);
        for (Long orderId : StateMapSupport.changedKeys(nextReservations)) {
            OrderReservation previous = reservations.get(orderId);
            OrderReservation current = nextReservations.get(orderId);
            if (previous != null) adjustExplainedLock(nextExplainedLocks, previous.asset(), -previous.remainingUnits());
            if (current != null) adjustExplainedLock(nextExplainedLocks, current.asset(), current.remainingUnits());
        }
        for (String positionKey : StateMapSupport.changedKeys(nextPositions)) {
            CorePositionState previous = positions.get(positionKey);
            CorePositionState current = nextPositions.get(positionKey);
            if (previous != null) {
                adjustExplainedLock(nextExplainedLocks, previous.marginAsset(), -previous.positionMarginUnits());
            }
            if (current != null) {
                adjustExplainedLock(nextExplainedLocks, current.marginAsset(), current.positionMarginUnits());
            }
        }
        return new CoreUserState(productLine, userId, nextRevision, nextBalances, nextReservations, nextPositions,
                nextPositionMode, nextExplainedLocks, true);
    }

    void requireIncrementalLineage(CoreUserState before) {
        if (before == null || before == this) return;
        requireDescendantLineage("user balances", before.balances, balances);
        requireDescendantLineage("user reservations", before.reservations, reservations);
        requireDescendantLineage("user positions", before.positions, positions);
        requireDescendantLineage("user explained locks", before.explainedLocks, explainedLocks);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public long userId() {
        return userId;
    }

    public long revision() {
        return revision;
    }

    public Map<String, AssetBalance> balances() {
        return balances;
    }

    public Map<Long, OrderReservation> reservations() {
        return reservations;
    }

    public Map<String, CorePositionState> positions() {
        return positions;
    }

    public CorePositionMode positionMode() {
        return positionMode;
    }

    public Set<String> changedBalanceAssetsSince(CoreUserState before) {
        return before == null ? balances.keySet() : StateMapSupport.changedKeys(before.balances, balances);
    }

    public Set<Long> changedReservationIdsSince(CoreUserState before) {
        return before == null ? reservations.keySet() : StateMapSupport.changedKeys(before.reservations, reservations);
    }

    public Set<String> changedPositionKeysSince(CoreUserState before) {
        return before == null ? positions.keySet() : StateMapSupport.changedKeys(before.positions, positions);
    }

    public long totalUnits(String asset) {
        AssetBalance balance = balances.get(AssetBalance.normalizeAsset(asset));
        return balance == null ? 0 : balance.totalUnits();
    }

    private static void validateEntries(Map<String, AssetBalance> balances,
                                        Map<Long, OrderReservation> reservations,
                                        Map<String, CorePositionState> positions,
                                        boolean incremental) {
        keysToValidate(balances, incremental).forEach(asset -> {
            AssetBalance balance = balances.get(asset);
            if (balance != null && !asset.equals(balance.asset())) {
                throw new IllegalArgumentException("balance key does not match asset");
            }
        });
        keysToValidate(reservations, incremental).forEach(orderId -> {
            OrderReservation reservation = reservations.get(orderId);
            if (reservation != null && orderId != reservation.orderId()) {
                throw new IllegalArgumentException("reservation key does not match orderId");
            }
        });
        keysToValidate(positions, incremental).forEach(key -> {
            CorePositionState position = positions.get(key);
            if (position != null && !key.equals(position.key())) {
                throw new IllegalArgumentException("position key does not match position identity");
            }
        });
    }

    private static void validateLocks(Map<String, AssetBalance> balances,
                                      Map<String, Long> explainedLocks,
                                      boolean incremental) {
        if (!incremental) {
            for (String asset : explainedLocks.keySet()) validateLock(balances, explainedLocks, asset);
            return;
        }
        Set<String> changedBalances = StateMapSupport.changedKeys(balances);
        Set<String> changedLocks = StateMapSupport.changedKeys(explainedLocks);
        for (String asset : changedBalances) validateLock(balances, explainedLocks, asset);
        for (String asset : changedLocks) {
            if (!changedBalances.contains(asset)) validateLock(balances, explainedLocks, asset);
        }
    }

    private static void validateLock(Map<String, AssetBalance> balances,
                                     Map<String, Long> explainedLocks,
                                     String asset) {
        long units = explainedLocks.getOrDefault(asset, 0L);
        AssetBalance balance = balances.get(asset);
        if (units < 0 || (units > 0 && (balance == null || units > balance.lockedUnits()))) {
            throw new IllegalStateException("reservation locks exceed balance asset=" + asset);
        }
    }

    private static Map<String, Long> calculateExplainedLocks(Map<Long, OrderReservation> reservations,
                                                              Map<String, CorePositionState> positions) {
        if (reservations == null || positions == null) return Map.of();
        Map<String, Long> result = new HashMap<>();
        reservations.values().forEach(reservation ->
                adjustExplainedLock(result, reservation.asset(), reservation.remainingUnits()));
        positions.values().forEach(position ->
                adjustExplainedLock(result, position.marginAsset(), position.positionMarginUnits()));
        return result;
    }

    private static void adjustExplainedLock(Map<String, Long> locks, String asset, long delta) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        long next = Math.addExact(locks.getOrDefault(normalizedAsset, 0L), delta);
        if (next < 0) throw new IllegalStateException("negative explained lock asset=" + normalizedAsset);
        if (next == 0) locks.remove(normalizedAsset);
        else locks.put(normalizedAsset, next);
    }

    private static <K> Set<K> keysToValidate(Map<K, ?> values, boolean incremental) {
        return incremental ? StateMapSupport.changedKeys(values) : values.keySet();
    }

    private static void requireLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (!StateMapSupport.isDirectDeltaOf(before, after)) {
            throw new IllegalStateException(name + " lineage is unavailable");
        }
    }

    private static void requireDescendantLineage(String name, Map<?, ?> before, Map<?, ?> after) {
        if (!StateMapSupport.isDeltaDescendantOf(before, after)) {
            throw new IllegalStateException(name + " lineage is unavailable");
        }
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof CoreUserState other)) return false;
        return userId == other.userId && revision == other.revision && productLine == other.productLine
                && balances.equals(other.balances) && reservations.equals(other.reservations)
                && positions.equals(other.positions) && positionMode == other.positionMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(productLine, userId, revision, balances, reservations, positions, positionMode);
    }

    @Override
    public String toString() {
        return "CoreUserState[productLine=" + productLine + ", userId=" + userId + ", revision=" + revision
                + ", balances=" + balances + ", reservations=" + reservations + ", positions=" + positions
                + ", positionMode=" + positionMode + ']';
    }
}
