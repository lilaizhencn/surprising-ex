package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.service.matching.CoreMatch;
import java.util.List;
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
        return replaceUser(state, nextUser, state.orders(), state.bookState());
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        requireUserId(userId);
        if (state.orders().containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        boolean versionConflict = state.bookState().openOrders().values().stream()
                .filter(order -> order.symbol().equals(OrderReservation.normalizeSymbol(command.symbol())))
                .map(order -> state.order(order.orderId()))
                .anyMatch(order -> order.instrumentVersion() != command.instrumentVersion());
        if (versionConflict) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH",
                    "open book contains another instrument version");
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
        return replaceUser(state, nextUser, orders, state.bookState());
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
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        bookOrders.remove(command.orderId());
        CoreBookState bookState = new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders);
        return replaceUser(state, nextUser, orders, bookState);
    }

    public TradingCoreState applyMatches(
            TradingCoreState state,
            long takerOrderId,
            String baseAsset,
            String quoteAsset,
            List<CoreMatch> matches) {
        if (matches == null) {
            throw new IllegalArgumentException("matches are required");
        }
        if (state.productLine().isOptionProduct() && !matches.isEmpty()) {
            throw new CoreStateRejectedException("OPTION_MATCH_REQUIRES_PREMIUM_MODEL",
                    "option fills require the P4 premium settlement model");
        }
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        long nextPrioritySequence = state.bookState().nextPrioritySequence();
        CoreOrderState taker = requireOpenOrder(orders, takerOrderId);
        for (CoreMatch match : matches) {
            CoreOrderState maker = requireOpenOrder(orders, match.makerOrderId());
            if (!taker.symbol().equals(maker.symbol()) || taker.side() == maker.side()
                    || maker.userId() != match.makerUserId()) {
                throw new IllegalStateException("exchange-core match does not match authoritative orders");
            }
            if (taker.userId() == maker.userId()) {
                throw new CoreStateRejectedException("SELF_TRADE_PREVENTED", "self trade is not allowed");
            }
            if (state.productLine().isDerivative()) {
                users.put(taker.userId(), applyDerivativeOpenFill(users.get(taker.userId()), taker,
                        match.priceTicks(), match.quantitySteps()));
                users.put(maker.userId(), applyDerivativeOpenFill(users.get(maker.userId()), maker,
                        match.priceTicks(), match.quantitySteps()));
            } else {
                long quoteUnits = Math.multiplyExact(match.priceTicks(), match.quantitySteps());
                CoreOrderState buyerOrder = taker.side() == CoreOrderSide.BUY ? taker : maker;
                CoreOrderState sellerOrder = taker.side() == CoreOrderSide.SELL ? taker : maker;
                users.put(buyerOrder.userId(), applySpotFill(users.get(buyerOrder.userId()), buyerOrder,
                        AssetBalance.normalizeAsset(quoteAsset), quoteUnits,
                        AssetBalance.normalizeAsset(baseAsset), match.quantitySteps()));
                users.put(sellerOrder.userId(), applySpotFill(users.get(sellerOrder.userId()), sellerOrder,
                        AssetBalance.normalizeAsset(baseAsset), match.quantitySteps(),
                        AssetBalance.normalizeAsset(quoteAsset), quoteUnits));
            }
            taker = taker.fill(match.quantitySteps());
            maker = maker.fill(match.quantitySteps());
            orders.put(taker.orderId(), taker);
            orders.put(maker.orderId(), maker);
            if (maker.status() == CoreOrderStatus.OPEN) {
                CoreBookOrder previous = bookOrders.get(maker.orderId());
                if (previous == null) {
                    throw new IllegalStateException("maker order missing from book state");
                }
                bookOrders.put(maker.orderId(), new CoreBookOrder(maker.orderId(), maker.userId(), maker.symbol(),
                        maker.side(), maker.priceTicks(), maker.remainingQuantitySteps(), previous.prioritySequence()));
            } else {
                bookOrders.remove(maker.orderId());
                users.put(maker.userId(), releaseTerminalReservation(users.get(maker.userId()), maker.orderId()));
            }
        }
        if (taker.status() == CoreOrderStatus.OPEN) {
            bookOrders.put(taker.orderId(), new CoreBookOrder(taker.orderId(), taker.userId(), taker.symbol(),
                    taker.side(), taker.priceTicks(), taker.remainingQuantitySteps(), nextPrioritySequence));
            nextPrioritySequence = Math.incrementExact(nextPrioritySequence);
        } else {
            users.put(taker.userId(), releaseTerminalReservation(users.get(taker.userId()), taker.orderId()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(nextPrioritySequence, bookOrders));
    }

    public TradingCoreState prepareReplace(
            TradingCoreState state,
            long userId,
            ReplaceOrderCommand command) {
        CoreOrderState order = requireOpenOrder(state.orders(), command.orderId());
        if (order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        CoreUserState user = state.user(userId);
        OrderReservation reservation = requireReservation(user, order.orderId());
        OrderReservation nextReservation = reservation.replaceReservedUnits(command.newReservedUnits());
        long delta = Math.subtractExact(nextReservation.remainingUnits(), reservation.remainingUnits());
        AssetBalance balance = requireBalance(user, reservation.asset());
        AssetBalance nextBalance = delta > 0 ? balance.reserve(delta)
                : delta < 0 ? balance.release(Math.negateExact(delta)) : balance;
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, reservations, user.positions());
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        CoreOrderState replaced = order.replacePrice(command.newPriceTicks());
        orders.put(order.orderId(), replaced);
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        bookOrders.remove(order.orderId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders));
    }

    private static CoreOrderState requireOpenOrder(Map<Long, CoreOrderState> orders, long orderId) {
        CoreOrderState order = orders.get(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("matched order is not open orderId=" + orderId);
        }
        return order;
    }

    private static CoreUserState applySpotFill(
            CoreUserState user,
            CoreOrderState order,
            String debitAsset,
            long debitUnits,
            String creditAsset,
            long creditUnits) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        if (!reservation.asset().equals(debitAsset)) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(debitAsset, requireBalance(user, debitAsset).consumeLocked(debitUnits));
        AssetBalance credit = balances.getOrDefault(creditAsset, new AssetBalance(creditAsset, 0, 0));
        balances.put(creditAsset, credit.credit(creditUnits));
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), reservation.consume(debitUnits));
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                balances, reservations, user.positions());
    }

    private static CoreUserState applyDerivativeOpenFill(
            CoreUserState user,
            CoreOrderState order,
            long fillPriceTicks,
            long fillQuantitySteps) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        CorePositionState current = user.positions().get(order.symbol());
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        if (current != null && current.signedQuantitySteps() != 0
                && Long.signum(current.signedQuantitySteps()) != Long.signum(signedFill)) {
            throw new CoreStateRejectedException("DERIVATIVE_CLOSE_REQUIRES_PNL_MODEL",
                    "derivative closing fills wait for the P4 product-specific pnl model");
        }
        long executedAfter = Math.addExact(order.executedQuantitySteps(), fillQuantitySteps);
        long consumedTarget = executedAfter == order.quantitySteps()
                ? reservation.reservedUnits()
                : Math.multiplyExact(reservation.reservedUnits(), executedAfter) / order.quantitySteps();
        long marginUnits = Math.subtractExact(consumedTarget, reservation.consumedUnits());
        if (marginUnits <= 0) {
            throw new CoreStateRejectedException("INVALID_MARGIN_ALLOCATION", "fill allocates no position margin");
        }
        OrderReservation nextReservation = reservation.consume(marginUnits);
        long currentQuantity = current == null ? 0 : current.signedQuantitySteps();
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long currentEntryValue = current == null ? 0 : current.entryValueTicks();
        long fillValue = Math.multiplyExact(fillPriceTicks, fillQuantitySteps);
        long nextEntryValue = Math.addExact(currentEntryValue, fillValue);
        long nextMargin = Math.addExact(current == null ? 0 : current.positionMarginUnits(), marginUnits);
        CorePositionState position = new CorePositionState(order.symbol(), reservation.asset(),
                order.instrumentVersion(), nextQuantity, nextEntryValue / Math.abs(nextQuantity), nextEntryValue,
                current == null ? 0 : current.realizedPnlUnits(), nextMargin);
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        Map<String, CorePositionState> positions = new TreeMap<>(user.positions());
        positions.put(order.symbol(), position);
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                user.balances(), reservations, positions);
    }

    private static CoreUserState releaseTerminalReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = requireReservation(user, orderId);
        long releaseUnits = reservation.remainingUnits();
        if (releaseUnits == 0) {
            return user;
        }
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(reservation.asset(), requireBalance(user, reservation.asset()).release(releaseUnits));
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(orderId, reservation.releaseAll());
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                balances, reservations, user.positions());
    }

    private static OrderReservation requireReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = user.reservations().get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("open order reservation is missing orderId=" + orderId);
        }
        return reservation;
    }

    private static AssetBalance requireBalance(CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        if (balance == null) {
            throw new IllegalStateException("reservation balance is missing asset=" + asset);
        }
        return balance;
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders,
            CoreBookState bookState) {
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(user.userId(), user);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                bookState);
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
