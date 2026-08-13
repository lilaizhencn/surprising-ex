package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record TradingCoreState(
        ProductLine productLine,
        long revision,
        Map<Long, CoreUserState> users,
        Map<Long, CoreOrderState> orders) {

    public TradingCoreState {
        if (productLine == null || revision < 0 || users == null || orders == null) {
            throw new IllegalArgumentException("invalid trading core state");
        }
        Map<Long, CoreUserState> sortedUsers = Collections.unmodifiableMap(new TreeMap<>(users));
        Map<Long, CoreOrderState> sortedOrders = Collections.unmodifiableMap(new TreeMap<>(orders));
        sortedUsers.forEach((userId, user) -> {
            if (userId != user.userId() || user.productLine() != productLine) {
                throw new IllegalArgumentException("user state belongs to another partition");
            }
        });
        sortedOrders.forEach((orderId, order) -> {
            if (orderId != order.orderId() || order.productLine() != productLine
                    || !sortedUsers.containsKey(order.userId())) {
                throw new IllegalArgumentException("order state belongs to another partition");
            }
        });
        users = sortedUsers;
        orders = sortedOrders;
    }

    public static TradingCoreState empty(ProductLine productLine) {
        return new TradingCoreState(productLine, 0, Map.of(), Map.of());
    }

    public CoreUserState user(long userId) {
        return users.get(userId);
    }

    public CoreOrderState order(long orderId) {
        return orders.get(orderId);
    }

    public long businessStateHash() {
        long hash = CoreStateHash.mix(CoreStateHash.start(), productLine.ordinal());
        hash = CoreStateHash.mix(hash, revision);
        for (CoreUserState user : users.values()) {
            hash = hashUser(hash, user);
        }
        for (CoreOrderState order : orders.values()) {
            hash = hashOrder(hash, order);
        }
        return hash;
    }

    public long userStateHash(long userId) {
        CoreUserState user = users.get(userId);
        return user == null ? 0 : hashUser(CoreStateHash.start(), user);
    }

    public long orderStateHash(long orderId) {
        CoreOrderState order = orders.get(orderId);
        return order == null ? 0 : hashOrder(CoreStateHash.start(), order);
    }

    private static long hashUser(long initial, CoreUserState user) {
        long hash = CoreStateHash.mix(initial, user.productLine().ordinal());
        hash = CoreStateHash.mix(hash, user.userId());
        hash = CoreStateHash.mix(hash, user.revision());
        for (AssetBalance balance : user.balances().values()) {
            hash = CoreStateHash.mix(hash, balance.asset());
            hash = CoreStateHash.mix(hash, balance.availableUnits());
            hash = CoreStateHash.mix(hash, balance.lockedUnits());
        }
        for (OrderReservation reservation : user.reservations().values()) {
            hash = CoreStateHash.mix(hash, reservation.orderId());
            hash = CoreStateHash.mix(hash, reservation.symbol());
            hash = CoreStateHash.mix(hash, reservation.instrumentVersion());
            hash = CoreStateHash.mix(hash, reservation.kind().wireCode());
            hash = CoreStateHash.mix(hash, reservation.asset());
            hash = CoreStateHash.mix(hash, reservation.reservedUnits());
            hash = CoreStateHash.mix(hash, reservation.releasedUnits());
            hash = CoreStateHash.mix(hash, reservation.consumedUnits());
            hash = CoreStateHash.mix(hash, reservation.orderQuantitySteps());
        }
        for (CorePositionState position : user.positions().values()) {
            hash = CoreStateHash.mix(hash, position.symbol());
            hash = CoreStateHash.mix(hash, position.marginAsset());
            hash = CoreStateHash.mix(hash, position.instrumentVersion());
            hash = CoreStateHash.mix(hash, position.signedQuantitySteps());
            hash = CoreStateHash.mix(hash, position.entryPriceTicks());
            hash = CoreStateHash.mix(hash, position.entryValueTicks());
            hash = CoreStateHash.mix(hash, position.realizedPnlUnits());
            hash = CoreStateHash.mix(hash, position.positionMarginUnits());
        }
        return hash;
    }

    private static long hashOrder(long initial, CoreOrderState order) {
        long hash = CoreStateHash.mix(initial, order.orderId());
        hash = CoreStateHash.mix(hash, order.productLine().ordinal());
        hash = CoreStateHash.mix(hash, order.userId());
        hash = CoreStateHash.mix(hash, order.symbol());
        hash = CoreStateHash.mix(hash, order.instrumentVersion());
        hash = CoreStateHash.mix(hash, order.side().wireCode());
        hash = CoreStateHash.mix(hash, order.priceTicks());
        hash = CoreStateHash.mix(hash, order.quantitySteps());
        hash = CoreStateHash.mix(hash, order.executedQuantitySteps());
        hash = CoreStateHash.mix(hash, order.remainingQuantitySteps());
        hash = CoreStateHash.mix(hash, order.reduceOnly());
        hash = CoreStateHash.mix(hash, order.status().ordinal());
        return CoreStateHash.mix(hash, order.revision());
    }
}
