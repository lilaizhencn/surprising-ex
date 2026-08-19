package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey;

public final class RuntimePlaceOrderDeltaApplier {

    private RuntimePlaceOrderDeltaApplier() {
    }

    public static void apply(TradingCoreState before, TradingCoreState after,
                             long userId, long orderId, TradingRuntimeState runtime,
                             RuntimeIdentityRegistry identities) {
        if (before == null || after == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("states, runtime and identities are required");
        }
        CoreOrderState previousOrder = before.orders().get(orderId);
        CoreOrderState nextOrder = after.orders().get(orderId);
        if (previousOrder != null || nextOrder == null || nextOrder.userId() != userId) {
            throw new IllegalStateException("delta is not a new order: " + orderId);
        }
        CoreUserState previousUser = before.users().get(userId);
        CoreUserState nextUser = after.users().get(userId);
        if (nextUser == null) throw new IllegalStateException("new order user is missing: " + userId);
        OrderReservation reservation = nextUser.reservations().get(orderId);
        if (reservation == null || reservation.remainingUnits() <= 0) {
            throw new IllegalStateException("new order reservation is missing: " + orderId);
        }
        AssetBalance nextBalance = nextUser.balances().get(reservation.asset());
        AssetBalance previousBalance = previousUser == null
                ? null : previousUser.balances().get(reservation.asset());
        if (nextBalance == null || previousBalance == null) {
            throw new IllegalStateException("place order balance baseline is missing: " + orderId);
        }
        long availableDelta = Math.subtractExact(previousBalance.availableUnits(), nextBalance.availableUnits());
        long lockedDelta = Math.subtractExact(nextBalance.lockedUnits(), previousBalance.lockedUnits());
        if (availableDelta <= 0 || availableDelta != lockedDelta
                || availableDelta != reservation.remainingUnits()) {
            throw new IllegalStateException("place order balance delta mismatch: " + orderId);
        }
        if (nextOrder.status() != CoreOrderStatus.OPEN
                || nextOrder.remainingQuantitySteps() != nextOrder.quantitySteps()) {
            throw new IllegalStateException("new order is not open: " + orderId);
        }
        long clientKey = identities.clientKey(userId, nextOrder.clientOrderId());
        runtime.reserveOrder(orderId, userId, clientKey, identities.symbolId(nextOrder.symbol()),
                nextOrder.quantitySteps(), identities.assetId(reservation.asset()), reservation.remainingUnits());
        if (runtime.balance(userId, identities.assetId(reservation.asset())).availableUnits()
                != nextBalance.availableUnits()) {
            throw new IllegalStateException("runtime available balance mismatch: " + orderId);
        }
        if (runtime.balance(userId, identities.assetId(reservation.asset())).lockedUnits()
                != nextBalance.lockedUnits()) {
            throw new IllegalStateException("runtime locked balance mismatch: " + orderId);
        }
        if (nextOrder.clientOrderId().isEmpty()) return;
        Long projectedOrderId = runtime.orderIdByClient(userId, clientKey);
        if (!Long.valueOf(orderId).equals(projectedOrderId)) {
            throw new IllegalStateException("runtime client order mismatch: "
                    + new ClientOrderKey(userId, nextOrder.clientOrderId()));
        }
    }
}
