package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.admission.AdmissionIdentity;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import java.util.UUID;

/** Owns runtime order creation, cancellation, rejection and reservation handoff. */
final class RuntimeOrderStateTransitions {

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
        int symbolId = identities.symbolId(command.symbol());
        int assetId = identities.assetId(command.reservationAsset());
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, symbolId, assetId);
    }

    static void placePrepared(
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
        OrderRuntime order = new OrderRuntime(command.orderId(), runtime.productLine(), userId, symbolId,
                command.instrument(), command.side(), command.limitPriceTicks(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, 0, 0, 0, CoreOrderStatus.OPEN, 1);
        ReservationRuntime reservation = new ReservationRuntime(command.orderId(), userId, symbolId,
                command.reservationKind(), assetId, requiredReservation,
                0, 0, command.quantitySteps());
        runtime.reserveOrder(order, reservation, clientKey);
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                Math.incrementExact(user.revision()), user.positionMode()));
        runtime.incrementCommandRevision();
    }

    static void placeTriggerChildInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long coreSequence, long openInterestSteps,
            AdmissionIdentity identity, long clientKey, int assetId) {
        AccountLaneState lane = runtime.laneCommandScope.get();
        if (lane == null || lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("trigger child requires its Account Lane");
        long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, userId, command,
                openInterestSteps, lane.admissionOrderIndex(command.symbolId()), identity);
        placePrepared(runtime, userId, command, commandId, required, clientKey, command.symbolId(), assetId);
        runtime.pendingReservations.markInCurrentLane(userId, command.orderId(), coreSequence);
    }

    static void reserveBatchOrderInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long requiredReservation,
            long clientKey, int assetId, long coreSequence) {
        AccountLaneState lane = runtime.laneCommandScope.get();
        if (lane == null || lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch reservation requires its Account Lane");
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, command.symbolId(), assetId);
        runtime.pendingReservations.markInCurrentLane(userId, command.orderId(), coreSequence);
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
