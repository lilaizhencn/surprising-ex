package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskScanner;
import java.util.Collections;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 账户实体读取、风险游标扫描与敞口查询协作器。 */
final class AccountEntityQueries {
    private final AccountBook book;

    AccountEntityQueries(AccountBook book) {
        this.book = book;
    }

    UserAccount user(long userId) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            return scoped.laneId() == laneId ? scoped.user(userId) : null;
        }
        AccountPartition lane = book.accountLanes[laneId];
        book.enterLaneCommandScope(lane);
        try {
            return lane.user(userId);
        } finally {
            book.exitLaneCommandScope(lane);
        }
    }

    AccountBalance balance(long userId, int assetId) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            if (scoped.laneId() != laneId) {
                throw new IllegalStateException("balance query crossed its owner lane");
            }
            IntObjectHashMap<AccountBalance> balances = scoped.userBalances(userId);
            return balances == null ? null : balances.get(assetId);
        }
        AccountPartition lane = book.accountLanes[laneId];
        book.enterLaneCommandScope(lane);
        try {
            return AccountBook.copyBalance(lane.userBalances(userId), assetId);
        } finally {
            book.exitLaneCommandScope(lane);
        }
    }

    long nextRiskReservationId(long userId, long cursor) {
        AccountPartition lane = riskCursorLane(userId);
        var ids = lane.reservationIds(userId);
        if (ids == null || ids.isEmpty()) return 0;
        if (ids.size() == 1) {
            long single = ids.iterator().nextValue();
            return single > cursor ? single : 0;
        }
        long selected = 0;
        var iterator = ids.iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextValue();
            if (id > cursor && (selected == 0 || id < selected)) selected = id;
        }
        return selected;
    }

    long nextRiskPositionKey(long userId, String cursor, IdIndex identities) {
        AccountPartition lane = riskCursorLane(userId);
        LongHashSet ids = lane.positionKeys(userId);
        if (ids == null || ids.isEmpty()) return 0;
        if (ids.size() == 1) {
            long single = ids.longIterator().next();
            if ("-".equals(cursor)) return single;
            return identities.positionKey(userId, single).compareTo(cursor) > 0 ? single : 0;
        }
        long selected = 0;
        String selectedIdentity = null;
        var iterator = ids.longIterator();
        while (iterator.hasNext()) {
            long id = iterator.next();
            String identity = identities.positionKey(userId, id);
            if (!"-".equals(cursor) && identity.compareTo(cursor) <= 0) continue;
            if (selectedIdentity == null || identity.compareTo(selectedIdentity) < 0) {
                selected = id;
                selectedIdentity = identity;
            }
        }
        return selected;
    }

    AccountPartition riskCursorLane(long userId) {
        book.assertOwner();
        book.requireAccountReadOwnership();
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) {
            lane = book.accountLanes[book.topology.accountLaneId(userId)];
        }
        if (lane == null || lane.laneId() != book.topology.accountLaneId(userId)) {
            throw new IllegalStateException("risk cursor must run in the user's Lane");
        }
        lane.assertOwner();
        return lane;
    }

    boolean hasOpenAccountExposure(long userId) {
        book.assertOwner();
        return book.onLane(userId, lane -> {
            var orders = lane.activeOrderIds(userId);
            if (orders != null && !orders.isEmpty()) return true;
            LongHashSet positions = lane.positionKeys(userId);
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    Position position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0) return true;
                }
            }
            var reservations = lane.reservationIds(userId);
            if (reservations != null) {
                var it = reservations.iterator();
                while (it.hasNext()) {
                    OrderFundsReservation reservation = lane.reservation(it.nextValue());
                    if (reservation != null && reservation.reservedUnits() != 0) return true;
                }
            }
            return false;
        });
    }

    boolean hasOpenLeverageExposure(CoreLeverageKey key, int symbolId) {
        book.assertOwner();
        return book.onLane(key.userId(), lane -> {
            var orders = lane.activeOrderIds(key.userId());
            if (orders != null) {
                var it = orders.iterator();
                while (it.hasNext()) {
                    Order order = lane.order(it.nextValue());
                    if (order != null && order.status() == CoreOrderStatus.OPEN
                            && order.symbolId() == symbolId && order.marginMode() == key.marginMode()) return true;
                }
            }
            LongHashSet positions = lane.positionKeys(key.userId());
            if (positions != null) {
                var it = positions.longIterator();
                while (it.hasNext()) {
                    Position position = lane.position(it.next());
                    if (position != null && position.signedQuantitySteps() != 0
                            && position.symbolId() == symbolId && position.marginMode() == key.marginMode()) return true;
                }
            }
            return false;
        });
    }

    long[] reservationIdsForUser(AccountPartition lane, long userId) {
        book.requireLaneAccess(lane, userId);
        var ids = lane.reservationIds(userId);
        if (ids == null) return new long[0];
        long[] result = new long[ids.size()];
        var iterator = ids.iterator();
        for (int index = 0; index < result.length; index++) result[index] = iterator.nextValue();
        return result;
    }

    NavigableSet<CoreLeverageKey> leverageKeysForUser(AccountPartition lane, long userId) {
        book.requireLaneAccess(lane, userId);
        Set<CoreLeverageKey> keys = lane.leverageKeys(userId);
        return keys == null ? Collections.emptyNavigableSet()
                : Collections.unmodifiableNavigableSet(new TreeSet<>(keys));
    }

    Order order(long orderId) {
        book.assertOwner();
        book.requireAccountReadOwnership();
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            Order order = scoped.order(orderId);
            if (order != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == book.owner && scoped.pendingReservation(orderId)) {
                    if (book.changedOrders.get(orderId) != null || book.orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    return null;
                }
                return order;
            }
            return null;
        }
        if (book.bizIndex != null) {
            var route = book.bizIndex.activeOrders().activeOrderRoute(orderId);
            if (route != null) {
                AccountPartition lane = book.accountLanes[book.topology.accountLaneId(route.userId())];
                lane.assertOwner();
                Order order = lane.order(orderId);
                if (order != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (book.changedOrders.get(orderId) != null || book.orderBatchMutationScope) {
                            return order;
                        }
                        if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                            return order;
                        }
                        return null;
                    }
                    return order;
                }
            }
        }
        for (int i = 0; i < book.accountLanes.length; i++) {
            book.accountLanes[i].assertOwner();
            Order order = book.accountLanes[i].order(orderId);
            if (order != null) {
                if (book.accountLanes[i].pendingReservation(orderId)) {
                    if (book.changedOrders.get(orderId) != null || book.orderBatchMutationScope) {
                        return order;
                    }
                    if (order.clientOrderId() != null && order.clientOrderId().startsWith("TRIGGER:")) {
                        return order;
                    }
                    continue;
                }
                return order;
            }
        }
        return null;
    }

    OrderFundsReservation reservation(long orderId) {
        book.assertOwner();
        book.requireAccountReadOwnership();
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            OrderFundsReservation reservation = scoped.reservation(orderId);
            if (reservation != null) {
                if (scoped.queryScopeActive() && scoped.pendingReservation(orderId)) {
                    return null;
                }
                if (Thread.currentThread() == book.owner && scoped.pendingReservation(orderId)) {
                    if (book.changedReservations.contains(orderId) || book.orderBatchMutationScope) {
                        return reservation;
                    }
                    return null;
                }
                return reservation;
            }
            return null;
        }
        if (book.bizIndex != null) {
            var route = book.bizIndex.activeOrders().activeOrderRoute(orderId);
            if (route != null) {
                AccountPartition lane = book.accountLanes[book.topology.accountLaneId(route.userId())];
                lane.assertOwner();
                OrderFundsReservation reservation = lane.reservation(orderId);
                if (reservation != null) {
                    if (lane.pendingReservation(orderId)) {
                        if (book.changedReservations.contains(orderId) || book.orderBatchMutationScope) {
                            return reservation;
                        }
                        return null;
                    }
                    return reservation;
                }
            }
        }
        for (int i = 0; i < book.accountLanes.length; i++) {
            book.accountLanes[i].assertOwner();
            OrderFundsReservation reservation = book.accountLanes[i].reservation(orderId);
            if (reservation != null) {
                if (book.accountLanes[i].pendingReservation(orderId)) {
                    if (book.changedReservations.contains(orderId) || book.orderBatchMutationScope) {
                        return reservation;
                    }
                    continue;
                }
                return reservation;
            }
        }
        return null;
    }

    Position position(long positionKey) {
        book.assertOwner();
        book.requireAccountReadOwnership();
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            return scoped.position(positionKey);
        }
        if (book.indexIdentities != null) {
            var identity = book.indexIdentities.positionIdentity(positionKey);
            if (identity != null) {
                AccountPartition lane = book.accountLanes[book.topology.accountLaneId(identity.userId())];
                lane.assertOwner();
                Position position = lane.position(positionKey);
                if (position != null) return position;
            }
        }
        for (int i = 0; i < book.accountLanes.length; i++) {
            book.accountLanes[i].assertOwner();
            Position position = book.accountLanes[i].position(positionKey);
            if (position != null) return position;
        }
        return null;
    }

    LongArrayList positionKeysForUserAndSymbolPrimitive(long userId, int symbolId) {
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped == null || scoped.laneId() != book.topology.accountLaneId(userId)) {
            throw new IllegalStateException("primitive position index access requires owning Lane scope");
        }
        LongObjectHashMap<LongArrayList> byUser = scoped.symbolPositionKeys(symbolId);
        LongArrayList keys = byUser == null ? null : byUser.get(userId);
        return keys == null ? AccountBook.EMPTY_POSITION_KEYS : keys;
    }

    RiskScanner.LiquidationOrder liquidation(long liquidationId) {
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) return scoped.liquidation(liquidationId);
        book.assertOwner();
        book.requireAccountReadOwnership();
        for (AccountPartition lane : book.accountLanes) {
            lane.assertOwner();
            RiskScanner.LiquidationOrder value = lane.liquidation(liquidationId);
            if (value != null) return value;
        }
        return null;
    }

    RiskScanner.LiquidationOrder activeLiquidation(long userId, int symbolId, CorePositionSide positionSide) {
        book.assertOwner();
        return book.onLane(userId, lane -> {
            IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(userId);
            LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(symbolId);
            Long liquidationId = bySide == null ? null : bySide.get(positionSide.ordinal());
            return liquidationId == null ? null : lane.liquidation(liquidationId);
        });
    }

    boolean hasUnresolvedLiquidation(String instrumentId, int symbolId) {
        book.assertOwner();
        if (book.currentLaneCommandScope() != null) throw new IllegalStateException("instrument check belongs to Core owner");
        if (book.bizIndex != null) return book.bizIndex.liquidations().hasUnresolved(
                symbolId < 0 ? null : instrumentId);
        book.requireAccountReadOwnership();
        for (AccountPartition lane : book.accountLanes) {
            lane.assertOwner();
            if (lane.anyLiquidation(value ->
                    (symbolId < 0 || value.symbolId() == symbolId)
                    && value.status() != CoreLiquidationState.Status.COMPLETED
                    && value.status() != CoreLiquidationState.Status.CANCELED)) return true;
        }
        return false;
    }
}
