package com.surprising.aeron.service.state;

public final class RuntimeCancelOrderDeltaApplier {

    private RuntimeCancelOrderDeltaApplier() {
    }

    public static void apply(TradingCoreState before, TradingCoreState after,
                             long userId, long orderId, TradingRuntimeState runtime,
                             RuntimeIdentityRegistry identities) {
        if (before == null || after == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("states, runtime and identities are required");
        }
        CoreOrderState previousOrder = before.orders().get(orderId);
        CoreOrderState nextOrder = after.orders().get(orderId);
        if (previousOrder == null || nextOrder == null || previousOrder.userId() != userId
                || nextOrder.userId() != userId || previousOrder.status() != CoreOrderStatus.OPEN
                || nextOrder.status() != CoreOrderStatus.CANCELED) {
            throw new IllegalStateException("delta is not an open order cancellation: " + orderId);
        }
        CoreUserState previousUser = before.users().get(userId);
        CoreUserState nextUser = after.users().get(userId);
        OrderReservation previousReservation = previousUser == null
                ? null : previousUser.reservations().get(orderId);
        OrderReservation nextReservation = nextUser == null
                ? null : nextUser.reservations().get(orderId);
        if (previousReservation == null || nextReservation == null
                || nextReservation.remainingUnits() != 0
                || nextReservation.orderQuantitySteps() != previousReservation.orderQuantitySteps()
                || nextReservation.consumedUnits() < previousReservation.consumedUnits()
                || nextReservation.releasedUnits() < previousReservation.releasedUnits()) {
            throw new IllegalStateException("cancellation reservation delta is invalid: " + orderId);
        }
        long consumedUnits = Math.subtractExact(nextReservation.consumedUnits(),
                previousReservation.consumedUnits());
        long releasedUnits = Math.subtractExact(nextReservation.releasedUnits(),
                previousReservation.releasedUnits());
        if (consumedUnits < 0 || releasedUnits <= 0
                || Math.addExact(consumedUnits, releasedUnits) != previousReservation.remainingUnits()) {
            throw new IllegalStateException("cancellation settlement delta is invalid: " + orderId);
        }
        long executedQuantityDelta = Math.subtractExact(nextOrder.executedQuantitySteps(),
                previousOrder.executedQuantitySteps());
        if (executedQuantityDelta < 0
                || nextOrder.remainingQuantitySteps() != Math.subtractExact(previousOrder.remainingQuantitySteps(),
                        executedQuantityDelta)
                || executedQuantityDelta > previousOrder.remainingQuantitySteps()) {
            throw new IllegalStateException("cancellation execution delta is invalid: " + orderId);
        }
        AssetBalance previousBalance = previousUser.balances().get(previousReservation.asset());
        AssetBalance nextBalance = nextUser.balances().get(previousReservation.asset());
        if (previousBalance == null || nextBalance == null) {
            throw new IllegalStateException("cancellation balance delta is missing: " + orderId);
        }
        long releaseUnits = Math.subtractExact(nextBalance.availableUnits(), previousBalance.availableUnits());
        long lockedDecrease = Math.subtractExact(previousBalance.lockedUnits(), nextBalance.lockedUnits());
        if (releaseUnits <= 0 || lockedDecrease != Math.addExact(consumedUnits, releasedUnits)
                || releaseUnits != releasedUnits) {
            throw new IllegalStateException("cancellation balance delta mismatch: " + orderId);
        }
        BalanceRuntime runtimeBalance = runtime.balance(userId, identities.assetId(previousReservation.asset()));
        ReservationRuntime runtimeReservation = runtime.reservation(orderId);
        if (runtimeBalance == null || runtimeReservation == null
                || runtimeReservation.reservedUnits() != releaseUnits
                || runtimeBalance.availableUnits() != previousBalance.availableUnits()
                || runtimeBalance.lockedUnits() != previousBalance.lockedUnits()) {
            throw new IllegalStateException("runtime cancellation precondition mismatch: " + orderId);
        }
        runtime.cancelOrder(orderId, userId, releaseUnits);
        runtime.putUser(new UserRuntime(nextUser.productLine(), userId, nextUser.revision(), nextUser.positionMode()));
        runtime.replaceOrder(RuntimeStateProjector.toRuntimeOrder(nextOrder, identities));
        runtime.replaceReservation(RuntimeStateProjector.toRuntimeReservation(userId, nextReservation, identities));
        runtime.setMetadata(after.productLine(), after.revision());
        runtimeBalance = runtime.balance(userId, identities.assetId(previousReservation.asset()));
        if (runtimeBalance.availableUnits() != nextBalance.availableUnits()
                || runtimeBalance.lockedUnits() != nextBalance.lockedUnits()) {
            throw new IllegalStateException("runtime cancellation balance mismatch: " + orderId);
        }
    }
}
