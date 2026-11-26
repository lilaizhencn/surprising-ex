package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.position.PositionIndexOps;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlementChanges;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** 账户用户、余额、订单、预留、持仓、强平与风险快照写入变更协作器。 */
final class AccountEntityMutations {
    private final AccountBook book;

    AccountEntityMutations(AccountBook book) {
        this.book = book;
    }

    void putUser(UserAccount user) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(user.userId());
        book.onLane(user.userId(), lane -> {
            UserAccount current = lane.user(user.userId());
            UserAccount value = user;
            if (book.accountLanesStarted()) {
                value = current == null || current.productLine() != user.productLine() ? user.laneValue()
                        : current.laneValue().withState(user.revision(), user.positionMode());
            }
            lane.storeUser(user.userId(), value);
            lane.registerUser(user.userId());
            return null;
        });
        book.publishUser(user.userId(), user);
        if (book.currentLaneCommandScope() == null) book.markUserChanged(user.userId());
    }

    void advanceUserRevision(long userId, long count) {
        if (count <= 0) throw new IllegalArgumentException("revision advance must be positive");
        book.assertOwner();
        TradeSettlementChanges settlementChanges = book.currentMatcherSettlementChangesScope();
        if (settlementChanges != null) {
            AccountPartition lane = book.currentLaneCommandScope();
            if (lane == null || book.topology.accountLaneId(userId) != lane.laneId()) {
                throw new IllegalStateException("user revision crossed its owner lane");
            }
            if (lane.user(userId) == null) {
                throw new IllegalArgumentException("runtime user is not registered: " + userId);
            }
            settlementChanges.addUserRevision(lane.laneId(), userId, count);
            return;
        }
        UserAccount current = book.requireUser(userId);
        setUserState(userId, Math.addExact(current.revision(), count), current.positionMode());
    }

    void advanceUserRevision(AccountPartition lane, long userId, long count) {
        book.requireLaneAccess(lane, userId);
        if (count <= 0) return;
        if (lane.matcherSettlementChanges() == null) {
            advanceUserRevision(userId, count);
            return;
        }
        if (lane.user(userId) == null)
            throw new IllegalArgumentException("runtime user is not registered: " + userId);
        lane.matcherSettlementChanges().addUserRevision(lane.laneId(), userId, count);
    }

    void setUserState(long userId, long revision, CorePositionMode mode) {
        book.assertOwner();
        if (userId <= 0 || revision < 0 || mode == null) throw new IllegalArgumentException("invalid runtime user");
        book.accountRollback.captureUserBefore(userId);
        UserAccount updated = book.onLane(userId, lane -> {
            UserAccount current = lane.user(userId);
            UserAccount value = current == null ? new UserAccount(book.productLine, userId, revision, mode)
                    : (book.accountLanesStarted() ? current.laneValue() : current).withState(revision, mode);
            if (book.accountLanesStarted()) value = value.laneValue();
            lane.storeUser(userId, value);
            lane.registerUser(userId);
            return value;
        });
        book.publishUser(userId, updated);
        if (book.currentLaneCommandScope() == null) book.markUserChanged(userId);
    }

    void putBalance(AccountBalance balance) {
        book.assertOwner();
        long userId = balance.userId();
        int assetId = balance.assetId();
        long availableUnits = balance.availableUnits();
        long lockedUnits = balance.lockedUnits();
        book.onLane(userId, lane -> {
            book.accountRollback.captureUserBefore(userId);
            book.accountRollback.captureBalanceBefore(userId, assetId);
            IntObjectHashMap<AccountBalance> userBalances = lane.userBalances(userId);
            if (userBalances == null) {
                userBalances = new IntObjectHashMap<>();
                lane.storeUserBalances(userId, userBalances);
            }
            userBalances.put(assetId, new AccountBalance(userId, assetId, availableUnits, lockedUnits));
            book.accountRollback.captureBalanceAfter(lane, userId, assetId);
            return null;
        });
        if (book.currentLaneCommandScope() == null) {
            book.markBalancesChanged();
            book.markUserChanged(userId);
        }
    }

    void putBalance(AccountPartition lane, AccountBalance balance) {
        book.requireLaneAccess(lane, balance.userId());
        if (lane.matcherSettlementChanges() == null) {
            putBalance(balance);
            return;
        }
        book.accountRollback.captureBalanceBefore(lane, balance.userId(), balance.assetId());
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(balance.userId());
        if (balances == null) {
            balances = new IntObjectHashMap<>();
            lane.storeUserBalances(balance.userId(), balances);
        }
        balances.put(balance.assetId(), balance);
        book.accountRollback.captureBalanceAfter(lane, balance.userId(), balance.assetId());
    }

    void putOrder(Order order) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(order.userId());
        book.accountRollback.captureOrderBefore(order.orderId());
        book.onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        book.publishOrder(order.orderId(), order);
        book.changedOrder(order.orderId(), order);
        book.markUserChanged(order.userId());
    }

    void putReservation(OrderFundsReservation reservation) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(reservation.userId());
        book.accountRollback.captureReservationBefore(reservation.orderId());
        OrderFundsReservation previous = book.reservation(reservation.orderId());
        if (previous != null && book.onLane(previous.userId(), lane -> lane.pendingReservation(previous.orderId()))) {
            throw new IllegalStateException("pending reservation must be replaced through its owner lane");
        }
        if (previous != null) {
            book.onLane(previous.userId(), lane -> {
                lane.removeReservationValue(previous.orderId());
                lane.removeReservationId(previous.userId(), previous.orderId());
                return null;
            });
        }
        book.onLane(reservation.userId(), lane -> {
            lane.storeReservation(reservation.orderId(), reservation.laneValue());
            lane.addReservationId(reservation.userId(), reservation.orderId());
            return null;
        });
        book.publishReservation(reservation.orderId(), reservation);
        book.markReservationChanged(reservation.orderId());
        book.markUserChanged(reservation.userId());
        if (previous != null) book.markUserChanged(previous.userId());
    }

    void replaceOrder(Order order) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(order.userId());
        book.accountRollback.captureOrderBefore(order.orderId());
        Order previous = book.order(order.orderId());
        if (previous != null && previous.userId() != order.userId()) {
            throw new IllegalArgumentException("runtime order owner cannot change");
        }
        book.onLane(order.userId(), lane -> { lane.putOrder(order); return null; });
        book.publishOrder(order.orderId(), order);
        if (book.currentMatcherSettlementChangesScope() == null) {
            book.changedOrder(order.orderId(), order);
            book.markUserChanged(order.userId());
        }
    }

    void replaceBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        book.assertOwner();
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null && scoped.laneId() != book.topology.accountLaneId(userId))
            throw new IllegalStateException("balance write crossed its owner Lane");
        if (scoped != null) {
            book.accountRollback.captureUserBefore(userId);
            book.accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(scoped, userId, assetId, availableUnits, lockedUnits);
            return;
        }
        book.onLane(userId, lane -> {
            book.accountRollback.captureUserBefore(userId);
            book.accountRollback.captureBalanceBefore(userId, assetId);
            replaceBalanceInLane(lane, userId, assetId, availableUnits, lockedUnits);
            return null;
        });
        book.markBalancesChanged();
        book.markUserChanged(userId);
    }

    private void replaceBalanceInLane(AccountPartition lane, long userId, int assetId,
            long availableUnits, long lockedUnits) {
        IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
        AccountBalance current = balances == null ? null : balances.get(assetId);
        if (current == null) throw new IllegalArgumentException("runtime balance is not registered");
        current.replace(availableUnits, lockedUnits);
        book.accountRollback.captureBalanceAfter(lane, userId, assetId);
    }

    void replaceBalance(AccountPartition lane, long userId, int assetId, long available, long locked) {
        book.requireLaneAccess(lane, userId);
        if (lane.matcherSettlementChanges() == null) {
            replaceBalance(userId, assetId, available, locked);
            return;
        }
        book.accountRollback.captureBalanceBefore(lane, userId, assetId);
        replaceBalanceInLane(lane, userId, assetId, available, locked);
    }

    Position updatePositionInLane(AccountPartition lane, long positionKey, long userId, int symbolId, int assetId,
            CoreInstrument instrument, long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
            long realizedPnlUnits, long positionMarginUnits, CoreMarginMode marginMode, CorePositionSide positionSide) {
        lane.assertOwner();
        if (lane == null || lane.laneId() != book.topology.accountLaneId(userId)) {
            throw new IllegalStateException("position update must execute in its owning Account Lane");
        }
        Position current = lane.position(positionKey);
        if (current == null) {
            Position created = new Position(userId, symbolId, assetId, marginMode, positionSide,
                    instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                    realizedPnlUnits, positionMarginUnits);
            lane.storePosition(positionKey, created.laneValue());
            AccountBook.indexPosition(lane, positionKey, created);
            book.publishPosition(lane, positionKey, created);
            return created;
        }
        boolean wasOpen = current.signedQuantitySteps() != 0;
        int oldSymbol = current.symbolId();
        current.applyInPlace(instrument, signedQuantitySteps, entryPriceTicks, entryValueTicks,
                realizedPnlUnits, positionMarginUnits);
        boolean isOpen = current.signedQuantitySteps() != 0;
        if (wasOpen && (!isOpen || oldSymbol != current.symbolId()))
            PositionIndexOps.unindexOpenPosition(lane, positionKey, current.userId(), oldSymbol);
        if ((!wasOpen && isOpen) || oldSymbol != current.symbolId()) PositionIndexOps.indexOpenPosition(lane, positionKey, current);
        book.publishPosition(lane, positionKey, current);
        return current;
    }

    Order updateOrderInLane(AccountPartition lane, long orderId, long executed, long remaining, long feeUnits,
            long executedValueHigh, long executedValueLow, CoreOrderStatus status, long revision,
            long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order update must execute in its owning Account Lane");
        }
        Order order = lane.updateOrderInPlace(orderId, executed, remaining, feeUnits,
                status, revision, commitTimestamp, commitPosition);
        order.executionValue(executedValueHigh, executedValueLow);
        book.publishOrder(lane, orderId, order);
        return order;
    }

    Order updateOrderStatusInLane(AccountPartition lane, long orderId, CoreOrderStatus status, long revision,
            long commitTimestamp, long commitPosition) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("order status update must execute in its owning Account Lane");
        }
        Order order = lane.updateOrderStatusInPlace(orderId, status, revision, commitTimestamp, commitPosition);
        book.publishOrder(lane, orderId, order);
        return order;
    }

    OrderFundsReservation updateReservationInLane(AccountPartition lane, long orderId, long nextConsumed,
            long nextReleased, boolean publish) {
        lane.assertOwner();
        if (lane == null) {
            throw new IllegalStateException("reservation update must execute in its owning Account Lane");
        }
        OrderFundsReservation reservation = lane.reservation(orderId);
        if (reservation == null) throw new IllegalArgumentException("runtime reservation is not registered: " + orderId);
        long previousReserved = reservation.reservedUnits();
        if (nextReleased < reservation.releasedUnits())
            throw new IllegalArgumentException("reservation release moved backwards");
        reservation.setConsumedInPlace(nextConsumed);
        if (nextReleased != reservation.releasedUnits()) reservation.releaseInPlace(nextReleased - reservation.releasedUnits());
        lane.updatePendingReservationInPlace(reservation, previousReserved);
        if (publish) book.publishReservation(lane, orderId, reservation);
        return reservation;
    }

    void replacePosition(long positionKey, Position position) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(position.userId());
        book.accountRollback.capturePositionBefore(positionKey, position.userId());
        Position previous = book.position(positionKey);
        if (previous != null && previous.userId() != position.userId()) {
            throw new IllegalArgumentException("runtime position owner cannot change");
        }
        book.onLane(position.userId(), lane -> {
            lane.storePosition(positionKey, position.laneValue());
            PositionIndexOps.replacePositionIndexes(lane, positionKey, previous, position);
            return null;
        });
        book.publishPosition(positionKey, position);
        if (book.currentLaneCommandScope() == null) {
            book.changedPosition(positionKey, position);
            book.markUserChanged(position.userId());
            if (previous != null) book.markUserChanged(previous.userId());
        }
    }

    void putLiquidation(RiskScanner.LiquidationOrder liquidation) {
        book.assertOwner();
        book.accountRollback.captureUserBefore(liquidation.userId());
        if (book.liquidation(liquidation.liquidationId()) != null) {
            throw new IllegalArgumentException("runtime liquidation already exists: " + liquidation.liquidationId());
        }
        book.onLane(liquidation.userId(), lane -> {
            lane.storeLiquidation(liquidation.liquidationId(), liquidation);
            AccountBook.indexActiveLiquidation(lane, liquidation);
            book.publishLiquidation(liquidation.liquidationId(), liquidation);
            return null;
        });
        if (book.currentLaneCommandScope() == null) {
            book.changedLiquidations.put(liquidation.liquidationId(), liquidation);
        }
        book.markUserChanged(liquidation.userId());
    }

    void putRiskSnapshot(long positionKey, RiskMarginCalculator.Snapshot snapshot) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("risk snapshot");
        book.accountRollback.captureUserBefore(snapshot.userId());
        book.accountRollback.captureRiskSnapshotBefore(positionKey, snapshot.userId());
        book.onLane(snapshot.userId(), lane -> {
            lane.storeRiskSnapshot(positionKey, snapshot);
            book.publishRiskSnapshot(positionKey, snapshot);
            return null;
        });
        if (book.currentLaneCommandScope() == null) book.changedRiskSnapshots.put(positionKey, snapshot);
        book.markUserChanged(snapshot.userId());
    }
}
