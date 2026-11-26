package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCheckService;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** 订单预留、下单、撤单与终态释放协作器。 */
final class AccountOrderOperations {
    private final AccountBook book;

    AccountOrderOperations(AccountBook book) {
        this.book = book;
    }

    void cancelOrder(long orderId, long userId, long releaseUnits) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(userId);
        book.accountRollback.captureOrderBefore(orderId);
        book.accountRollback.captureReservationBefore(orderId);
        AccountPartition scoped = book.currentLaneCommandScope();
        Order terminalOrder = scoped == null
                ? book.onLane(userId, lane -> cancelOrderInOwnerLane(lane, orderId, userId, releaseUnits))
                : cancelOrderInOwnerLane(scoped, orderId, userId, releaseUnits);
        if (book.currentMatcherSettlementChangesScope() == null) {
            book.changedOrder(orderId, terminalOrder);
            book.markReservationChanged(orderId);
            book.markUserChanged(userId);
            book.markBalancesChanged();
        }
        book.advanceUserRevision(userId);
    }

    private Order cancelOrderInOwnerLane(AccountPartition lane, long orderId, long userId, long releaseUnits) {
        if (lane.laneId() != book.topology.accountLaneId(userId)) {
            throw new IllegalStateException("terminal order release crossed its owner Lane");
        }
        Order order = lane.order(orderId);
        OrderFundsReservation reservation = lane.reservation(orderId);
        if (order == null || reservation == null || order.userId() != userId || order.canceled()) {
            throw new IllegalArgumentException("runtime order is not cancelable: " + orderId);
        }
        if (releaseUnits != reservation.reservedUnits()) {
            throw new IllegalArgumentException("runtime cancellation release mismatch: " + orderId);
        }
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) {
            throw new IllegalArgumentException("runtime cancellation balance is missing: " + orderId);
        }
        book.accountRollback.captureBalanceBefore(userId, reservation.assetId());
        balance.release(releaseUnits);
        if (book.currentMatcherSettlementChangesScope() != null && book.currentLaneCommandScope() == lane) {
            long previousReserved = reservation.reservedUnits();
            reservation.releaseInPlace(releaseUnits);
            lane.updatePendingReservationInPlace(reservation, previousReserved);
            Order terminalOrder = lane.updateOrderStatusInPlace(orderId, CoreOrderStatus.CANCELED,
                    Math.incrementExact(order.revision()), -1, -1);
            book.accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
            book.publishOrder(orderId, terminalOrder);
            book.publishReservation(orderId, reservation);
            return terminalOrder;
        }
        Order terminalOrder = order.withStatus(CoreOrderStatus.CANCELED,
                Math.incrementExact(order.revision()), -1, -1);
        OrderFundsReservation released = reservation.release(releaseUnits);
        lane.replacePendingReservation(reservation, released);
        lane.putOrder(terminalOrder);
        lane.storeReservation(orderId, released.laneValue());
        book.accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        book.publishOrder(orderId, terminalOrder);
        book.publishReservation(orderId, released);
        return terminalOrder;
    }

    void cancelOrderInLane(AccountPartition lane, long userId, long orderId) {
        cancelOrderInLane(lane, userId, orderId, -1, -1);
    }

    void cancelOrderInLane(AccountPartition lane, long userId, Order order, long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, order, null, timestamp, position, CoreOrderStatus.CANCELED);
    }

    void cancelOrderInLane(AccountPartition lane, long userId, long orderId, long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.CANCELED);
    }

    void rejectOrderInLane(AccountPartition lane, long userId, long orderId, long timestamp, long position) {
        releaseOrderToTerminalInLane(lane, userId, orderId, timestamp, position, CoreOrderStatus.REJECTED);
    }

    private void releaseOrderToTerminalInLane(AccountPartition lane, long userId, long orderId,
            long timestamp, long position, CoreOrderStatus status) {
        Order order = lane.order(orderId);
        releaseOrderToTerminalInLane(lane, userId, order, null, timestamp, position, status);
    }

    private void releaseOrderToTerminalInLane(AccountPartition lane, long userId, Order order,
            OrderFundsReservation preparedReservation, long timestamp, long position, CoreOrderStatus status) {
        book.requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null)
            throw new IllegalStateException("terminal order requires its settlement Lane");
        if (order == null || order.userId() != userId || order.status().terminal())
            throw new IllegalStateException("terminal order reservation is missing: " + (order == null ? 0 : order.orderId()));
        long orderId = order.orderId();
        OrderFundsReservation reservation = preparedReservation == null ? lane.reservation(orderId) : preparedReservation;
        if (reservation == null)
            throw new IllegalStateException("terminal order reservation is missing: " + orderId);
        AccountBalance balance = book.balance(lane, userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("terminal order balance is missing: " + orderId);
        long releaseUnits = reservation.reservedUnits();
        book.accountRollback.captureBalanceBefore(lane, userId, reservation.assetId());
        balance.release(releaseUnits);
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, releaseUnits);
        lane.updateOrderStatusInPlace(orderId, status, Math.incrementExact(order.revision()), timestamp, position);
        book.publishOrder(lane, orderId, order);
        book.publishReservation(lane, orderId, reservation);
        book.accountRollback.captureBalanceAfter(lane, userId, reservation.assetId());
        book.advanceUserRevision(lane, userId);
    }

    long releaseTerminalReservationInLane(AccountPartition lane, long orderId) {
        return releaseTerminalReservationInLane(lane, orderId, null, null);
    }

    long releaseTerminalReservationInLane(AccountPartition lane, Order preparedOrder,
            OrderFundsReservation preparedReservation) {
        return releaseTerminalReservationInLane(lane, preparedOrder == null ? 0 : preparedOrder.orderId(),
                preparedOrder, preparedReservation);
    }

    long releaseTerminalReservationInLane(AccountPartition lane, long orderId,
            Order preparedOrder, OrderFundsReservation preparedReservation) {
        lane.assertOwner();
        if (lane == null || lane.matcherSettlementChanges() == null) {
            throw new IllegalStateException("terminal release must execute in its owning Account Lane");
        }
        Order order = preparedOrder == null ? lane.order(orderId) : preparedOrder;
        long targetOrderId = order != null ? order.orderId() : orderId;
        OrderFundsReservation reservation = preparedReservation == null ? lane.reservation(targetOrderId) : preparedReservation;
        if (order == null || reservation == null || !order.canceled()) {
            throw new IllegalArgumentException("runtime order is not terminal: " + targetOrderId);
        }
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits == 0) return 0;
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(order.userId());
        AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
        if (balance == null) throw new IllegalStateException("runtime terminal balance is missing: " + targetOrderId);
        book.accountRollback.captureBalanceBefore(lane, order.userId(), reservation.assetId());
        balance.release(releaseUnits);
        long previousReserved = reservation.reservedUnits();
        reservation.releaseInPlace(releaseUnits);
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        book.publishReservation(lane, targetOrderId, reservation);
        book.accountRollback.captureBalanceAfter(lane, order.userId(), reservation.assetId());
        return releaseUnits;
    }

    void reserveOrder(Order order, OrderFundsReservation reservation, long clientKey) {
        if (order == null || reservation == null || order.orderId() != reservation.orderId()
                || order.userId() != reservation.userId() || order.symbolId() != reservation.symbolId()
                || order.quantitySteps() != reservation.orderQuantitySteps() || clientKey < 0) {
            throw new IllegalArgumentException("invalid prepared reservation");
        }
        reserveOrder(order, reservation, clientKey, reservation.reservedUnits());
    }

    void reserveOrder(Order order, OrderFundsReservation reservation, long clientKey, long reservedUnits) {
        book.assertOwner();
        long orderId = order.orderId();
        long userId = order.userId();
        int assetId = reservation.assetId();
        if (book.order(orderId) != null) {
            throw new IllegalArgumentException("runtime order already exists: " + orderId);
        }
        book.accountRollback.captureUserBefore(userId);
        book.accountRollback.captureOrderBefore(orderId, userId);
        book.accountRollback.captureReservationBefore(orderId, userId);
        book.accountRollback.captureBalanceBefore(userId, assetId);
        if (clientKey != 0) book.accountRollback.captureClientOrderBefore(userId, clientKey);
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) reserveOrderInLane(scoped, order, reservation, clientKey, reservedUnits);
        else book.onLane(userId, lane -> {
            reserveOrderInLane(lane, order, reservation, clientKey, reservedUnits);
            return null;
        });
        if (book.currentLaneCommandScope() == null) {
            book.changedOrder(orderId, order);
            book.markReservationChanged(orderId);
            book.markUserChanged(userId);
            book.markBalancesChanged();
        } else {
            book.publishOrder(orderId, order);
            book.publishReservation(orderId, reservation);
        }
    }

    private void reserveOrderInLane(AccountPartition lane, Order order, OrderFundsReservation reservation,
            long clientKey, long reservedUnits) {
        lane.assertOwner();
        long userId = order.userId(), orderId = order.orderId();
        int assetId = reservation.assetId();
        if (lane.laneId() != book.topology.accountLaneId(userId))
            throw new IllegalStateException("reservation crossed Account Lane");
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey))
            throw new IllegalArgumentException("runtime client order already exists: " + clientKey);
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        if (balance == null)
            throw new IllegalArgumentException("runtime balance is not registered: " + userId + "/" + assetId);
        balance.reserve(reservedUnits);
        lane.putOrder(order);
        lane.storeReservation(orderId, reservation);
        lane.addReservationId(userId, orderId);
        if (clientKey != 0) AccountBook.putClientOrderIndex(lane, userId, clientKey, orderId);
        book.accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    static Order preparedOrder(ProductLine productLine, long userId, ResolvedPlaceOrder command,
            UUID commandId, int symbolId, long timestamp, long position) {
        return new Order(command.orderId(), productLine, userId, symbolId,
                command.instrument(), command.side(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, timestamp, timestamp, position, CoreOrderStatus.OPEN, 1);
    }

    Order prepareControlOrder(long userId, ResolvedPlaceOrder command, UUID commandId, int symbolId) {
        if (!book.accountLanesStarted())
            return preparedOrder(book.productLine, userId, command, commandId, symbolId, 0, 0);
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null || lane.laneId() != book.topology.accountLaneId(userId))
            throw new IllegalStateException("active order creation requires its Account Lane");
        return lane.preparedOrder(book.productLine, userId, command, commandId, symbolId, 0, 0, book.retiredOwnerSequence);
    }

    void placeOrderInLane(AccountPartition lane, long userId, ResolvedPlaceOrder command,
            UUID commandId, long requiredReservation, long clientKey,
            int symbolId, int assetId, long coreSequence, Order prepared, long timestamp, long position) {
        if (lane == null || !(lane.commandScopeActive() || Thread.currentThread() == book.owner && book.ownerLaneScope == lane)
                || lane.laneId() != book.topology.accountLaneId(userId)
                || command == null || commandId == null || userId <= 0 || requiredReservation <= 0
                || clientKey < 0 || symbolId < 0 || assetId < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid Lane-owned provisional place order");
        }
        lane.assertOwner();
        if (lane.hasOrder(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        Long2LongHashMap userClientOrders = lane.userClientOrders(userId);
        if (clientKey != 0 && userClientOrders != null && userClientOrders.containsKey(clientKey)) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserAccount user = lane.user(userId);
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance balance = balances == null ? null : balances.get(assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        Order order = prepared != null && !prepared.commandSlotValue() ? prepared
                : book.accountLanesStarted()
                ? lane.preparedOrder(book.productLine, userId, command, commandId, symbolId, timestamp, position, book.retiredOwnerSequence)
                : preparedOrder(book.productLine, userId, command, commandId, symbolId, timestamp, position);
        OrderFundsReservation reservation = order.prepareReservation(
                command.reservationKind(), assetId, requiredReservation);
        long nextUserRevision = Math.incrementExact(user.revision());
        balance.reserve(requiredReservation);
        lane.putOrder(order);
        lane.storeReservation(reservation.orderId(), reservation.laneValue());
        lane.addReservationId(userId, order.orderId());
        if (clientKey != 0) AccountBook.putClientOrderIndex(lane, userId, clientKey, order.orderId());
        lane.storeUser(userId, (book.accountLanesStarted() ? user.laneValue() : user)
                .withState(nextUserRevision, user.positionMode()));
        if (prepared == null) lane.markPendingReservation(order.orderId(), coreSequence);
    }

    void placePreparedOrder(long userId, ResolvedPlaceOrder command, UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId) {
        if (command == null || commandId == null || userId <= 0
                || requiredReservation <= 0 || clientKey < 0 || symbolId < 0 || assetId < 0) {
            throw new IllegalArgumentException("invalid prepared runtime place order");
        }
        book.assertOwner();
        if (book.order(command.orderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (clientKey != 0 && book.orderIdByClient(userId, clientKey) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        UserAccount user = book.user(userId);
        AccountBalance balance = book.balance(userId, assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        Order order = prepareControlOrder(userId, command, commandId, symbolId);
        OrderFundsReservation reservation = order.prepareReservation(command.reservationKind(), assetId, requiredReservation);
        long userRevision = Math.incrementExact(user.revision());
        reserveOrder(order, reservation, clientKey);
        book.setUserState(userId, userRevision, user.positionMode());
        book.incrementCommandRevision();
    }

    Order placeTriggerChildInLane(long userId, ResolvedPlaceOrder command, UUID commandId,
            long coreSequence, long openInterestSteps, IdIndex identities,
            long clientKey, int assetId, boolean lifecycleSettled, boolean fundingInProgress) {
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null || lane.laneId() != book.topology.accountLaneId(userId))
            throw new IllegalStateException("trigger child requires its Account Lane");
        long positionKey = identities.findPositionKeyValueInLane(lane, userId,
                command.instrument(), command.positionSide());
        long required = OrderCheckService.requiredReservationPrepared(book, lane, userId, command,
                openInterestSteps, clientKey, positionKey, lifecycleSettled, fundingInProgress);
        placePreparedOrder(userId, command, commandId, required, clientKey, command.symbolId(), assetId);
        lane.markPendingReservation(command.orderId(), coreSequence);
        Order receipt = book.laneDelta(lane.laneId()).orders().get(command.orderId());
        if (receipt == null) throw new IllegalStateException("trigger admission receipt is missing");
        return receipt.copyIntoCommandSlot(null);
    }
}
