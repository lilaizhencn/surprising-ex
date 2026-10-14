package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.util.UUID;

/** Owns runtime order creation, cancellation, rejection and reservation handoff. */
public final class RuntimeOrderStateTransitions {

    private RuntimeOrderStateTransitions() {
    }

    static void place(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                      long userId, ResolvedPlaceOrder command, UUID commandId,
                      long requiredReservation) {
        if (runtime == null || identities == null || command == null || commandId == null || userId <= 0
                || requiredReservation <= 0) {
            throw new IllegalArgumentException("invalid runtime place order");
        }
        runtime.assertOwner();
        long clientKey = identities.clientKey(userId, command.clientOrderId());
        int symbolId = identities.symbolId(command.instrumentId());
        int assetId = identities.assetId(command.reservationAsset());
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, symbolId, assetId);
    }

    public static void placePrepared(
            TradingRuntimeState runtime, long userId, ResolvedPlaceOrder command, UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId) {
        if (runtime == null || command == null || commandId == null || userId <= 0
                || requiredReservation <= 0 || clientKey < 0 || symbolId < 0 || assetId < 0) {
            throw new IllegalArgumentException("invalid prepared runtime place order");
        }
        runtime.assertOwner();
        if (runtime.order(command.orderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (clientKey != 0 && runtime.orderIdByClient(userId, clientKey) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserRuntime user = runtime.user(userId);
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        OrderRuntime order = runtime.prepareControlOrder(userId, command, commandId, symbolId);
        ReservationRuntime reservation = order.prepareReservation(command.reservationKind(), assetId, requiredReservation);
        runtime.reserveOrder(order, reservation, clientKey);
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                Math.incrementExact(user.revision()), user.positionMode()));
        runtime.incrementCommandRevision();
    }

    public static OrderRuntime placeTriggerChildInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long coreSequence, long openInterestSteps,
            RuntimeIdentityRegistry identities, long clientKey, int assetId,
            boolean lifecycleSettled, boolean fundingInProgress) {
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null || lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("trigger child requires its Account Lane");
        long positionKey = identities.findPositionKeyValueInLane(lane, userId,
                command.instrument(), command.positionSide());
        long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, userId, command,
                openInterestSteps, lane.admissionOrderIndex(command.symbolId()), clientKey,
                command.symbolId(), positionKey, lifecycleSettled, fundingInProgress);
        placePrepared(runtime, userId, command, commandId, required, clientKey, command.symbolId(), assetId);
        lane.markPendingReservation(command.orderId(), coreSequence);
        OrderRuntime receipt = runtime.laneDelta(lane.laneId()).orders.get(command.orderId());
        if (receipt == null) throw new IllegalStateException("trigger admission receipt is missing");
        // Each queued trigger owns this input through Matcher admission; a later trigger
        // may reuse the control worker and its output buffers before that admission starts.
        return receipt.copyIntoCommandSlot(null);
    }

    public static void reserveBatchOrderInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long requiredReservation,
            long clientKey, int assetId, long coreSequence) {
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null) {
            int laneId = runtime.topology().accountLaneId(userId);
            AccountLaneState targetLane = runtime.accountLanes[laneId];
            runtime.inLaneCommandScope(targetLane, ignored -> {
                placePrepared(runtime, userId, command, commandId, requiredReservation,
                        clientKey, command.symbolId(), assetId);
                targetLane.markPendingReservation(command.orderId(), coreSequence);
                return null;
            });
            return;
        }
        if (lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch reservation requires its Account Lane");
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, command.symbolId(), assetId);
        // Lane keeps the pending marker. The Owner registers routing only after the
        // existing control completion releases the immutable admission receipt.
        lane.markPendingReservation(command.orderId(), coreSequence);
    }

    static boolean cancel(TradingRuntimeState runtime, long userId, long orderId) {
        if (runtime == null || userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel order");
        }
        runtime.assertOwner();
        OrderRuntime order = runtime.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status().terminal()) return false;
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("open order is missing reservation");
        runtime.cancelOrder(orderId, userId, reservation.reservedUnits());
        runtime.incrementCommandRevision();
        return true;
    }

    static void reject(TradingRuntimeState runtime, long userId, long orderId, long coreSequence) {
        if (runtime == null || userId <= 0 || orderId <= 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime rejected order");
        }
        runtime.assertOwner();
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("rejected order reservation is missing");
        if (runtime.pendingReservation(orderId, userId)) {
            runtime.completePendingReservation(userId, orderId, coreSequence);
        }
        BalanceRuntime balance = runtime.balance(userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("rejected order balance is missing");
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits > 0) {
            if (balance.lockedUnits() < releaseUnits) throw new IllegalArgumentException("invalid runtime release");
            runtime.replaceBalance(new BalanceRuntime(userId, reservation.assetId(),
                    Math.addExact(balance.availableUnits(), releaseUnits), balance.lockedUnits() - releaseUnits));
        }
        runtime.replaceOrder(order.withStatus(CoreOrderStatus.REJECTED, Math.incrementExact(order.revision())));
        runtime.removeReservation(orderId, userId);
        runtime.advanceUserRevision(userId);
        runtime.incrementCommandRevision();
    }
}
