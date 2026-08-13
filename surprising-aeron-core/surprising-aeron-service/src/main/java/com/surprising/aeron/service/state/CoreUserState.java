package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record CoreUserState(
        ProductLine productLine,
        long userId,
        long revision,
        Map<String, AssetBalance> balances,
        Map<Long, OrderReservation> reservations,
        Map<String, CorePositionState> positions) {

    public CoreUserState {
        if (productLine == null || userId <= 0 || revision < 0
                || balances == null || reservations == null || positions == null) {
            throw new IllegalArgumentException("invalid user state");
        }
        balances = immutableSorted(balances);
        reservations = Collections.unmodifiableMap(new TreeMap<>(reservations));
        positions = immutableSorted(positions);
        balances.forEach((asset, balance) -> {
            if (!asset.equals(balance.asset())) {
                throw new IllegalArgumentException("balance key does not match asset");
            }
        });
        reservations.forEach((orderId, reservation) -> {
            if (orderId != reservation.orderId()) {
                throw new IllegalArgumentException("reservation key does not match orderId");
            }
        });
        positions.forEach((symbol, position) -> {
            if (!symbol.equals(position.symbol())) {
                throw new IllegalArgumentException("position key does not match symbol");
            }
        });
        validateLocks(balances, reservations, positions);
    }

    public static CoreUserState empty(ProductLine productLine, long userId) {
        return new CoreUserState(productLine, userId, 0, Map.of(), Map.of(), Map.of());
    }

    public long totalUnits(String asset) {
        AssetBalance balance = balances.get(AssetBalance.normalizeAsset(asset));
        return balance == null ? 0 : balance.totalUnits();
    }

    private static void validateLocks(
            Map<String, AssetBalance> balances,
            Map<Long, OrderReservation> reservations,
            Map<String, CorePositionState> positions) {
        Map<String, Long> explainedLocks = new TreeMap<>();
        reservations.values().forEach(reservation -> explainedLocks.merge(
                reservation.asset(), reservation.remainingUnits(), Math::addExact));
        positions.values().forEach(position -> explainedLocks.merge(
                position.marginAsset(), position.positionMarginUnits(), Math::addExact));
        explainedLocks.forEach((asset, units) -> {
            AssetBalance balance = balances.get(asset);
            if (balance == null || units > balance.lockedUnits()) {
                throw new IllegalStateException("reservation locks exceed balance asset=" + asset);
            }
        });
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> immutableSorted(Map<K, V> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
}
