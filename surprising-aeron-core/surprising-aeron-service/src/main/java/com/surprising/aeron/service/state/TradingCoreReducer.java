package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import java.util.Map;
import java.util.TreeMap;

public final class TradingCoreReducer {

    public TradingCoreState adjustBalance(
            TradingCoreState state,
            long userId,
            BalanceAdjustmentCommand command) {
        requireUserId(userId);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.asset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.adjustAvailable(command.deltaUnits());

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(asset, nextBalance);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances,
                currentUser.reservations(), currentUser.positions());
        return replaceUser(state, nextUser, state.orders());
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        requireUserId(userId);
        if (state.orders().containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (command.reduceOnly()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_REQUIRES_POSITION_STATE",
                    "reduce-only validation is introduced with P3 position execution state");
        }
        validateReservationRule(state, command);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.reservationAsset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.reserve(command.reservedUnits());
        OrderReservation reservation = OrderReservation.create(command.orderId(), command.symbol(),
                command.instrumentVersion(),
                command.reservationKind(), asset, command.reservedUnits(), command.quantitySteps());
        CoreOrderState order = new CoreOrderState(command.orderId(), state.productLine(), userId,
                command.symbol(), command.instrumentVersion(), command.side(), command.priceTicks(),
                command.quantitySteps(), 0,
                command.quantitySteps(), command.reduceOnly(), CoreOrderStatus.OPEN, 1);

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(asset, nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(currentUser.reservations());
        reservations.put(command.orderId(), reservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        orders.put(order.orderId(), order);
        return replaceUser(state, nextUser, orders);
    }

    public TradingCoreState cancelOrder(TradingCoreState state, long userId, CancelOrderCommand command) {
        requireUserId(userId);
        CoreOrderState currentOrder = state.orders().get(command.orderId());
        if (currentOrder == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        if (currentOrder.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (currentOrder.status().terminal()) {
            return state;
        }
        CoreUserState currentUser = state.users().get(userId);
        OrderReservation currentReservation = currentUser.reservations().get(command.orderId());
        if (currentReservation == null) {
            throw new IllegalStateException("open order is missing reservation");
        }
        long releaseUnits = currentReservation.remainingUnits();
        AssetBalance currentBalance = currentUser.balances().get(currentReservation.asset());
        if (currentBalance == null) {
            throw new IllegalStateException("reservation balance is missing");
        }
        AssetBalance nextBalance = releaseUnits == 0
                ? currentBalance : currentBalance.release(releaseUnits);
        OrderReservation nextReservation = releaseUnits == 0
                ? currentReservation : currentReservation.releaseAll();

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(currentUser.reservations());
        reservations.put(command.orderId(), nextReservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        orders.put(command.orderId(), currentOrder.cancel());
        return replaceUser(state, nextUser, orders);
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders) {
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(user.userId(), user);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders);
    }

    private static void validateReservationRule(TradingCoreState state, PlaceOrderCommand command) {
        String reservationAsset = AssetBalance.normalizeAsset(command.reservationAsset());
        if (state.productLine().isDerivative()) {
            if (command.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                        "derivative orders require DERIVATIVE_MARGIN");
            }
            String settleAsset = AssetBalance.normalizeAsset(command.settleAsset());
            if (!reservationAsset.equals(settleAsset)) {
                throw new CoreStateRejectedException("INVALID_DERIVATIVE_RESERVATION_ASSET",
                        "derivative orders reserve the instrument settle asset");
            }
            return;
        }
        if (command.reservationKind() != ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                    "spot orders require SPOT_ASSET");
        }
        String expectedAsset = AssetBalance.normalizeAsset(command.side() == CoreOrderSide.BUY
                ? command.quoteAsset() : command.baseAsset());
        if (!reservationAsset.equals(expectedAsset)) {
            throw new CoreStateRejectedException("INVALID_SPOT_RESERVATION_ASSET",
                    "spot buy reserves quote asset and spot sell reserves base asset");
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
