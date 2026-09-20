package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.business.derivative.FuturesOrderAdmission;
import com.surprising.aeron.service.business.option.OptionOrderAdmission;
import com.surprising.aeron.service.business.spot.SpotOrderAdmission;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingCoreState.ClientOrderKey;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CorePositionState;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireBalance;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireReservation;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.userOrders;

/** Owns immutable order, reservation, and order-index transitions in the core state. */
final class OrderStateTransitions {

    private static final long PPM = 1_000_000L;

    private OrderStateTransitions() {
    }

    static TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        return placeOrder(state, userId, command, new UUID(0, command.orderId()), -1, null);
    }

    static TradingCoreState placeOrder(TradingCoreState state, long userId, ResolvedPlaceOrder command) {
        return placeOrder(state, userId, command, new UUID(0, command.orderId()), -1, null);
    }

    static TradingCoreState placeOrder(
            TradingCoreState state, long userId, PlaceOrderCommand command, UUID commandId) {
        return placeOrder(state, userId, command, commandId, -1, null);
    }

    static TradingCoreState placeOrder(
            TradingCoreState state, long userId, PlaceOrderCommand command,
            UUID commandId, long indexedOpenInterestSteps) {
        return placeOrder(state, userId, command, commandId, indexedOpenInterestSteps, null);
    }

    static TradingCoreState placeOrder(
            TradingCoreState state, long userId, PlaceOrderCommand command,
            UUID commandId, long indexedOpenInterestSteps, ActiveOrderIndex activeOrderIndex) {
        return placeOrder(state, userId, CoreOrderDecisionResolver.resolve(state, command), commandId,
                indexedOpenInterestSteps, activeOrderIndex);
    }

    static TradingCoreState placeOrder(
            TradingCoreState state, long userId, ResolvedPlaceOrder command,
            UUID commandId, long indexedOpenInterestSteps, ActiveOrderIndex activeOrderIndex) {
        long requiredReservation = requiredReservationForAcceptedPlaceOrder(
                state, userId, command, indexedOpenInterestSteps, activeOrderIndex);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.reservationAsset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.reserve(requiredReservation);
        OrderReservation reservation = OrderReservation.create(command.orderId(), command.symbol(),
                command.reservationKind(), asset, requiredReservation, command.quantitySteps());
        CoreOrderState order = new CoreOrderState(command.orderId(), state.productLine(), userId,
                command.symbol(), command.side(), command.limitPriceTicks(),
                command.matchingPriceTicks(), command.quantitySteps(), 0, command.quantitySteps(),
                command.reduceOnly(), command.marginMode(), command.positionSide(), command.orderType(),
                command.timeInForce(), command.postOnly(), command.clientOrderId(), commandId,
                command.makerFeeRatePpm(), command.takerFeeRatePpm(), CoreOrderStatus.OPEN, 1);

        Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
        balances.put(asset, nextBalance);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
        reservations.put(command.orderId(), reservation);
        CoreUserState nextUser = currentUser.transition(Math.incrementExact(currentUser.revision()),
                balances, reservations, currentUser.positions(), currentUser.positionMode());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        orders.put(order.orderId(), order);
        Map<ClientOrderKey, Long> clientOrderIndex = StateMapSupport.delta(state.clientOrderIndex());
        if (!order.clientOrderId().isEmpty()) {
            clientOrderIndex.put(new ClientOrderKey(order.userId(), order.clientOrderId()), order.orderId());
        }
        return replaceUser(state, nextUser, orders, clientOrderIndex);
    }

    static long requiredReservationForAcceptedPlaceOrder(
            TradingCoreState state, long userId, PlaceOrderCommand command,
            long indexedOpenInterestSteps, ActiveOrderIndex activeOrderIndex) {
        return requiredReservationForAcceptedPlaceOrder(state, userId,
                CoreOrderDecisionResolver.resolve(state, command), indexedOpenInterestSteps, activeOrderIndex);
    }

    static long requiredReservationForAcceptedPlaceOrder(
            TradingCoreState state, long userId, ResolvedPlaceOrder command,
            long indexedOpenInterestSteps, ActiveOrderIndex activeOrderIndex) {
        requireUserId(userId);
        if (state.orders().containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (!command.clientOrderId().isEmpty() && state.order(userId, command.clientOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        CoreInstrument instrument = requireInstrument(state, command.symbol());
        if (state.treasuryState().lifecycleSettlements().containsKey(instrument.symbol())) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        validateReservationRule(state, command);
        validateInstrumentOrder(instrument, command);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        validatePositionIdentity(state, currentUser, command, activeOrderIndex);
        validateReduceOnlyCapacity(state, currentUser, command, activeOrderIndex);
        validateDerivativeRiskLimits(state, instrument, currentUser, command, activeOrderIndex,
                indexedOpenInterestSteps < 0 ? symbolOpenInterestSteps(state, instrument.symbol())
                        : indexedOpenInterestSteps);
        return requiredReservationUnits(state, instrument, currentUser, command, activeOrderIndex);
    }

    static TradingCoreState cancelOrder(TradingCoreState state, long userId, CancelOrderCommand command) {
        requireUserId(userId);
        CoreOrderState currentOrder = state.orders().get(command.orderId());
        if (currentOrder == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        if (currentOrder.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (currentOrder.status().terminal()) return state;
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
        AssetBalance nextBalance = releaseUnits == 0 ? currentBalance : currentBalance.release(releaseUnits);
        OrderReservation nextReservation = releaseUnits == 0
                ? currentReservation : currentReservation.releaseAll();
        Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
        reservations.put(command.orderId(), nextReservation);
        CoreUserState nextUser = currentUser.transition(Math.incrementExact(currentUser.revision()),
                balances, reservations, currentUser.positions(), currentUser.positionMode());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        orders.put(command.orderId(), currentOrder.cancel());
        return replaceUser(state, nextUser, orders, StateMapSupport.delta(state.clientOrderIndex()));
    }

    static TradingCoreState rejectPlaceOrder(TradingCoreState state, long userId, long orderId) {
        requireUserId(userId);
        CoreOrderState order = state.orders().get(orderId);
        if (order == null || order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        CoreUserState user = state.users().get(userId);
        OrderReservation reservation = user == null ? null : user.reservations().get(orderId);
        if (user == null || reservation == null) {
            throw new IllegalStateException("rejected order reservation is missing");
        }
        long releaseUnits = reservation.remainingUnits();
        AssetBalance balance = user.balances().get(reservation.asset());
        if (balance == null) {
            throw new IllegalStateException("rejected order balance is missing");
        }
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(balance.asset(), releaseUnits == 0 ? balance : balance.release(releaseUnits));
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.remove(orderId);
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()),
                balances, reservations, user.positions(), user.positionMode());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        orders.put(orderId, order.reject());
        return replaceUser(state, nextUser, orders, StateMapSupport.delta(state.clientOrderIndex()));
    }

    static TradingCoreState pruneAcknowledgedTerminalReservations(
            TradingCoreState state, Collection<Long> acknowledgedOrderIds) {
        if (acknowledgedOrderIds == null || acknowledgedOrderIds.isEmpty()) return state;
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        boolean changed = false;
        for (Long orderId : acknowledgedOrderIds) {
            if (orderId == null) continue;
            CoreOrderState order = state.orders().get(orderId);
            if (order == null || !order.status().terminal()) continue;
            CoreUserState user = users.get(order.userId());
            if (user == null) continue;
            OrderReservation reservation = user.reservations().get(orderId);
            if (reservation == null || reservation.remainingUnits() != 0) continue;
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
            reservations.remove(orderId);
            users.put(order.userId(), user.transition(Math.incrementExact(user.revision()),
                    user.balances(), reservations, user.positions(), user.positionMode()));
            changed = true;
        }
        if (!changed) return state;
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    static TradingCoreState cancelUserSymbolOrders(TradingCoreState state, long userId, String symbol) {
        CoreUserState user = state.user(userId);
        if (user == null) return state;
        List<CoreOrderState> orders = userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && order.symbol().equals(symbol))
                .toList();
        return cancelOrders(state, orders);
    }

    static TradingCoreState cancelOrders(TradingCoreState state, List<CoreOrderState> openOrders) {
        if (openOrders == null || openOrders.isEmpty()) return state;
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        Map<Long, CoreOrderState> orders = StateMapSupport.delta(state.orders());
        boolean changed = false;
        for (CoreOrderState order : openOrders) {
            CoreOrderState currentOrder = orders.get(order.orderId());
            if (currentOrder == null || currentOrder.status() != CoreOrderStatus.OPEN) continue;
            CoreUserState currentUser = users.get(currentOrder.userId());
            if (currentUser == null) {
                throw new IllegalStateException("order owner is missing orderId=" + currentOrder.orderId());
            }
            OrderReservation reservation = requireReservation(currentUser, currentOrder.orderId());
            long releaseUnits = reservation.remainingUnits();
            AssetBalance balance = requireBalance(currentUser, reservation.asset());
            Map<String, AssetBalance> balances = StateMapSupport.delta(currentUser.balances());
            if (releaseUnits != 0) balances.put(reservation.asset(), balance.release(releaseUnits));
            Map<Long, OrderReservation> reservations = StateMapSupport.delta(currentUser.reservations());
            reservations.put(currentOrder.orderId(), reservation.releaseAll());
            users.put(currentUser.userId(), currentUser.transition(Math.incrementExact(currentUser.revision()),
                    balances, reservations, currentUser.positions(), currentUser.positionMode()));
            orders.put(currentOrder.orderId(), currentOrder.cancel());
            changed = true;
        }
        if (!changed) return state;
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                state.instruments(), state.riskState(), state.treasuryState(), state.leverages(),
                state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(), state.triggerOrders());
    }

    static CoreUserState releaseTerminalReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = requireReservation(user, orderId);
        long releaseUnits = reservation.remainingUnits();
        if (releaseUnits == 0) return user;
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(reservation.asset(), requireBalance(user, reservation.asset()).release(releaseUnits));
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(orderId, reservation.releaseAll());
        return user.transition(Math.incrementExact(user.revision()), balances, reservations,
                user.positions(), user.positionMode());
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state, CoreUserState user, Map<Long, CoreOrderState> orders,
            Map<ClientOrderKey, Long> clientOrderIndex) {
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(user.userId(), user);
        Map<Long, CoreOrderState> nextOrders = StateMapSupport.isDelta(orders)
                ? orders : StateMapSupport.delta(orders);
        Map<ClientOrderKey, Long> nextClientOrderIndex = clientOrderIndex == null
                ? StateMapSupport.delta(state.clientOrderIndex()) : clientOrderIndex;
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, nextOrders,
                state.instruments(), state.riskState(), state.treasuryState(), state.leverages(),
                state.algoOrders(), state.cancelAllAfterTimers(), nextClientOrderIndex, state.triggerOrders());
    }

    private static void validateReservationRule(TradingCoreState state, ResolvedPlaceOrder command) {
        String reservationAsset = AssetBalance.normalizeAsset(command.reservationAsset());
        if (state.productLine().isDerivative()) {
            if (command.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                        "derivative orders require DERIVATIVE_MARGIN");
            }
            if (!reservationAsset.equals(command.instrument().settleAsset())) {
                throw new CoreStateRejectedException("INVALID_DERIVATIVE_RESERVATION_ASSET",
                        "derivative orders reserve the instrument settle asset");
            }
            return;
        }
        if (command.reservationKind() != ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("INVALID_RESERVATION_KIND", "spot orders require SPOT_ASSET");
        }
        String expectedAsset = AssetBalance.normalizeAsset(command.side() == CoreOrderSide.BUY
                ? command.instrument().quoteAsset() : command.instrument().baseAsset());
        if (!reservationAsset.equals(expectedAsset)) {
            throw new CoreStateRejectedException("INVALID_SPOT_RESERVATION_ASSET",
                    "spot buy reserves quote asset and spot sell reserves base asset");
        }
    }

    private static void validateInstrumentOrder(CoreInstrument instrument, ResolvedPlaceOrder command) {
        if (instrument != command.instrument()) {
            throw new CoreStateRejectedException("INSTRUMENT_ORDER_MISMATCH",
                    "order assets do not match instrument state");
        }
        if (command.matchingPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_ORDER_PRICE", "matching price must be positive");
        }
    }

    private static long requiredReservationUnits(
            TradingCoreState state, CoreInstrument instrument, CoreUserState user,
            ResolvedPlaceOrder command, ActiveOrderIndex activeOrderIndex) {
        return switch (instrument.contractType().productLine()) {
            case SPOT -> SpotOrderAdmission.reservationUnitsForState(
                    state, instrument, user, command, activeOrderIndex);
            case OPTION -> OptionOrderAdmission.reservationUnitsForState(
                    state, instrument, user, command, activeOrderIndex);
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL, LINEAR_DELIVERY, INVERSE_DELIVERY ->
                    FuturesOrderAdmission.reservationUnitsForState(
                            state, instrument, user, command, activeOrderIndex);
        };
    }

    private static void validateDerivativeRiskLimits(
            TradingCoreState state, CoreInstrument instrument, CoreUserState user,
            ResolvedPlaceOrder command, ActiveOrderIndex activeOrderIndex, long indexedOpenInterestSteps) {
        if (!state.productLine().isDerivative() || command.reduceOnly()) return;
        long projectedNotional = projectedPositionNotionalUnits(state, instrument, user, command, activeOrderIndex);
        if (projectedNotional > instrument.maxPositionNotionalUnits()) {
            throw new CoreStateRejectedException("POSITION_NOTIONAL_LIMIT_EXCEEDED",
                    "projected position exceeds instrument notional limit");
        }
        long openInterestNotional = indexedOpenInterestSteps == 0 ? 0
                : CoreContractMath.riskNotionalUnits(instrument, indexedOpenInterestSteps,
                instrument.contractType().isOption() ? command.indexPriceTicks() : command.markPriceTicks());
        long scaledLimit = java.math.BigInteger.valueOf(openInterestNotional)
                .multiply(java.math.BigInteger.valueOf(instrument.userOpenInterestLimitRatePpm()))
                .divide(java.math.BigInteger.valueOf(PPM))
                .max(java.math.BigInteger.valueOf(instrument.userOpenInterestLimitFloorUnits()))
                .min(java.math.BigInteger.valueOf(instrument.maxPositionNotionalUnits())).longValueExact();
        if (projectedNotional > scaledLimit) {
            throw new CoreStateRejectedException("OPEN_INTEREST_LIMIT_EXCEEDED",
                    "projected position exceeds dynamic open interest limit");
        }
        var bracket = CoreContractMath.riskBracket(instrument, projectedNotional);
        if (projectedNotional > bracket.notionalCapUnits()) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "projected position exceeds risk bracket cap");
        }
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.symbol(), command.marginMode()),
                instrument.maxLeveragePpm());
        if (!instrument.contractType().isOption() && leverage > bracket.maxLeveragePpm()) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET",
                    "configured leverage exceeds projected position risk bracket");
        }
        if (!instrument.contractType().isOption()
                && initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm()) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_RISK_BRACKET",
                    "configured leverage margin rate is below projected position risk bracket");
        }
    }

    private static long projectedPositionNotionalUnits(
            TradingCoreState state, CoreInstrument instrument, CoreUserState user,
            ResolvedPlaceOrder command, ActiveOrderIndex activeOrderIndex) {
        return CoreContractMath.riskNotionalUnits(instrument,
                projectedPositionSteps(state, instrument, user, command, command.quantitySteps(), activeOrderIndex),
                instrument.contractType().isOption() ? command.indexPriceTicks() : command.markPriceTicks());
    }

    private static long projectedPositionSteps(
            TradingCoreState state, CoreInstrument instrument, CoreUserState user,
            ResolvedPlaceOrder command, long additionalQuantitySteps, ActiveOrderIndex activeOrderIndex) {
        return Math.absExact(projectedPositionSignedSteps(state, instrument, user, command,
                additionalQuantitySteps, activeOrderIndex));
    }

    private static long projectedPositionSignedSteps(
            TradingCoreState state, CoreInstrument instrument, CoreUserState user,
            ResolvedPlaceOrder command, long additionalQuantitySteps, ActiveOrderIndex activeOrderIndex) {
        CorePositionState position = user.positions().get(positionKey(instrument.symbol(), command.positionSide()));
        long current = position == null ? 0 : position.signedQuantitySteps();
        long pendingSameSide = activeOrderIndex == null
                ? userOrders(state, user).stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && !order.reduceOnly()
                        && order.symbol().equals(instrument.symbol())
                        && order.positionSide() == command.positionSide() && order.side() == command.side())
                .mapToLong(CoreOrderState::remainingQuantitySteps).reduce(0L, Math::addExact)
                : activeOrderIndex.pendingQuantity(user.userId(), instrument.symbol(),
                command.positionSide(), command.side());
        long totalOrderSteps = Math.addExact(pendingSameSide, additionalQuantitySteps);
        long signedOrderSteps = command.side() == CoreOrderSide.BUY
                ? totalOrderSteps : Math.negateExact(totalOrderSteps);
        return Math.addExact(current, signedOrderSteps);
    }

    private static long symbolOpenInterestSteps(TradingCoreState state, String symbol) {
        long longSteps = 0;
        long shortSteps = 0;
        for (CoreUserState user : state.users().values()) {
            for (CorePositionState position : user.positions().values()) {
                if (!position.symbol().equals(symbol)) continue;
                if (position.signedQuantitySteps() > 0) {
                    longSteps = Math.addExact(longSteps, position.signedQuantitySteps());
                } else if (position.signedQuantitySteps() < 0) {
                    shortSteps = Math.addExact(shortSteps, Math.absExact(position.signedQuantitySteps()));
                }
            }
        }
        return Math.max(longSteps, shortSteps);
    }

    private static long initialMarginRateFromLeverage(long leveragePpm) {
        if (leveragePpm < PPM) throw new IllegalArgumentException("leverage must be at least 1x");
        return CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
    }

    private static void validateReduceOnlyCapacity(
            TradingCoreState state, CoreUserState user, ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        if (!command.reduceOnly()) return;
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_UNSUPPORTED", "spot orders cannot be reduce-only");
        }
        CorePositionState position = user.positions().get(positionKey(command.symbol(), command.positionSide()));
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (command.side() == CoreOrderSide.BUY)) {
            throw new CoreStateRejectedException("REDUCE_ONLY_REQUIRES_POSITION_STATE",
                    "reduce-only side must close an existing position");
        }
        PositionCloseCapacity.inspect(state, user, position.symbol(), command.positionSide(), command.side(),
                activeOrderIndex).require(command.quantitySteps());
    }

    private static void validatePositionIdentity(
            TradingCoreState state, CoreUserState user, ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        if (user.positionMode() == CorePositionMode.ONE_WAY && command.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !command.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "order position side does not match user position mode");
        }
        if (command.marginMode() == CoreMarginMode.ISOLATED
                && command.reservationKind() == ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "spot order cannot use isolated margin");
        }
        CorePositionState position = user.positions().get(positionKey(command.symbol(), command.positionSide()));
        boolean positionConflict = position != null && position.signedQuantitySteps() != 0
                && position.marginMode() != command.marginMode();
        boolean orderConflict = activeOrderIndex == null ? userOrders(state, user).stream().anyMatch(
                order -> order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equalsIgnoreCase(command.symbol())
                        && order.positionSide() == command.positionSide()
                        && order.marginMode() != command.marginMode())
                : activeOrderIndex.hasDifferentMarginMode(user.userId(), command.symbol(),
                command.positionSide(), command.marginMode());
        if (positionConflict || orderConflict) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "margin mode switch requires closing positions and open orders first");
        }
        if (command.positionSide() == com.surprising.aeron.protocol.CorePositionSide.LONG
                && command.reduceOnly() == (command.side() == CoreOrderSide.BUY)
                || command.positionSide() == com.surprising.aeron.protocol.CorePositionSide.SHORT
                && command.reduceOnly() == (command.side() == CoreOrderSide.SELL)) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "hedge position side and order direction are inconsistent");
        }
    }

    private static CoreInstrument requireInstrument(
            TradingCoreState state, String symbol) {
        CoreInstrument instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
