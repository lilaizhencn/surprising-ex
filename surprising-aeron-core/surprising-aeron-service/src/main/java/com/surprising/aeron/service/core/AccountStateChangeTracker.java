package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.change.StateChangeBuffer;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.settlement.TradeSettlementChanges;
import com.surprising.aeron.service.settlement.TradeSettlementPosting;
import com.surprising.aeron.service.treasury.FundsTotal;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/** 状态变更追踪缓冲、实时捕获、事实索引同步与提交清理协作器。 */
final class AccountStateChangeTracker {
    private final AccountBook book;

    AccountStateChangeTracker(AccountBook book) {
        this.book = book;
    }

    void flushPublishedChanges(int laneId,
            PrimitiveLongChangeSet changedUsers,
            PrimitiveLongChangeSet changedOrders) {
        AccountPartitionCommit.Delta changes = book.laneDeltas[laneId];
        changes.recordControlChanges(book, laneId);
        if (book.accountRollback.balancePatch(laneId).size() != 0) book.markBalancesChanged();
        changes.publishTriggersToOwner(book);
        changes.drainTo(book, changedUsers, changedOrders);
    }

    LongLongMap metadataOrderUsers(Iterable<Long> orderIds) {
        book.assertOwner();
        book.metadataOrderUsers.clear();
        if (orderIds instanceof PrimitiveLongView ids) {
            for (int i = 0; i < ids.primitiveSize(); i++) book.metadataOrderUsers.put(ids.primitiveValueAt(i), 0);
        } else {
            for (Long id : orderIds) if (id != null) book.metadataOrderUsers.put(id, 0);
        }
        if (book.metadataOrderUsers.isEmpty()) return book.metadataOrderUsers;
        book.changedOrders.forEach(book.captureMetadataUser);
        if (book.bizIndex != null) book.metadataOrderUsers.forEachKeyValue(book.captureActiveMetadataUser);
        return book.metadataOrderUsers;
    }

    CompletableFuture<TradingRealtimeBoundary.UserSnapshot> realtimeSnapshot(long userId) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        if (!book.accountLanesStarted()) {
            try { return CompletableFuture.completedFuture(book.onLane(userId, lane -> TradingRealtimeBoundary.UserSnapshot.capture(lane, userId))); }
            catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }
        var result = new CompletableFuture<TradingRealtimeBoundary.UserSnapshot>();
        boolean accepted = book.dispatchAccountQuery(1L << laneId, lane -> {
            try { result.complete(book.readAccountLane(lane, owned -> TradingRealtimeBoundary.UserSnapshot.capture(owned, userId))); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        });
        if (!accepted) result.completeExceptionally(new IllegalStateException("realtime snapshot Lane mailbox is full"));
        return result;
    }

    void captureUserMetadata(UserAccount value) {
        if (book.realtimeCapture == null) return;
        try { book.realtimeCapture.metadata(value); }
        catch (RuntimeException failure) { book.realtimeCapture.failed(); }
    }

    void captureReservation(OrderFundsReservation value) {
        if (book.realtimeCapture == null) return;
        try { book.realtimeCapture.reservation(value); }
        catch (RuntimeException failure) { book.realtimeCapture.failed(); }
    }

    void captureRealtimeChanges(TradingRealtimeBoundary.StateCapture capture) {
        book.assertOwner();
        if (capture == null || !capture.active()) return;
        try {
            book.changedRiskSnapshots.forEach((id, value) -> capture.risk(value));
            if (!book.accountLanesStarted()) {
                book.changedUsers.forEach(id -> capture.metadata(book.user(id)));
                book.changedReservations.forEach(id -> capture.reservation(book.reservation(id)));
            }
            book.changedOrders.forEach((orderId, value) -> capture.order(value));
            book.changedPositions.forEach((positionKey, value) -> {
                if (value == null) capture.removedPosition(book.currentPatchPositionBefore(positionKey));
                else capture.position(value);
            });
        } catch (RuntimeException failure) { capture.failed(); }
    }

    void acceptChangedUserIds(LongConsumer consumer) {
        book.assertOwner();
        if (consumer == null) throw new IllegalArgumentException("changed user consumer is required");
        book.changedUsers.forEach(consumer::accept);
    }

    void markBalanceChanged(long userId) {
        book.assertOwner();
        book.markBalancesChanged();
        book.markUserChanged(userId);
    }

    void markReservationChanged(long orderId) {
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) book.changedReservations.add(orderId);
        else book.laneDelta(lane.laneId()).putReservation(orderId, lane.reservation(orderId));
    }

    void changedOrder(long orderId) {
        if (book.currentLaneCommandScope() == null && book.accountLanesStarted()) {
            if (!book.changedOrders.containsKey(orderId))
                throw new IllegalStateException("order change requires a collected Lane receipt: " + orderId);
            return;
        }
        changedOrder(orderId, book.order(orderId));
    }

    void changedOrder(long orderId, Order order) {
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) book.changedOrders.put(orderId, order);
        else book.laneDelta(lane.laneId()).putOrder(orderId, order);
    }

    void changedPosition(long positionKey) {
        if (book.currentLaneCommandScope() == null && book.accountLanesStarted()) {
            if (!book.changedPositions.containsKey(positionKey))
                throw new IllegalStateException("position change requires a collected Lane receipt: " + positionKey);
            return;
        }
        changedPosition(positionKey, book.position(positionKey));
    }

    void changedPosition(long positionKey, Position position) {
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) book.changedPositions.put(positionKey, position);
        else book.laneDelta(lane.laneId()).putPosition(positionKey, position);
    }

    void applyChangedIndexes() {
        book.assertOwner();
        if (book.bizIndex == null) throw new IllegalStateException("fact indexes are not bound");
        applyChangedOrderIndexes(book.changedOrders.directBuffer());
        long remaining = book.changedOrders.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            applyChangedOrderIndexes(book.changedOrders.laneBuffer(laneId));
        }
        for (int index = 0; index < book.changedLiquidations.size(); index++) {
            RiskScanner.LiquidationOrder value = book.changedLiquidations.valueAt(index);
            if (book.realtimeCapture != null && book.realtimeCapture.active()) {
                try { book.realtimeCapture.liquidation(value); }
                catch (RuntimeException failure) { book.realtimeCapture.failed(); }
            }
            book.bizIndex.liquidations().apply(book.changedLiquidations.keyAt(index), value, book.indexIdentities);
        }
        for (int index = 0; index < book.changedAlgoOrders.size(); index++)
            book.bizIndex.algos().apply(book.changedAlgoOrders.keyAt(index), book.changedAlgoOrders.valueAt(index));
        for (int index = 0; index < book.changedTriggerOrders.size(); index++) {
            long triggerOrderId = book.changedTriggerOrders.keyAt(index);
            CoreTriggerOrderState value = book.changedTriggerOrders.valueAt(index);
            if (book.realtimeCapture != null && book.realtimeCapture.active()) {
                try { book.realtimeCapture.trigger(value); } catch (RuntimeException failure) { book.realtimeCapture.failed(); }
            }
            book.bizIndex.triggers().apply(triggerOrderId, value);
        }
        if (!book.changedCancelAllAfterTimers.isEmpty())
            for (var key : book.changedCancelAllAfterTimers) book.bizIndex.timers().apply(key, book.cancelAllAfterTimer(key));
    }

    private void applyChangedOrderIndexes(StateChangeBuffer<Order> orders) {
        for (int index = 0; index < orders.size(); index++)
            book.bizIndex.activeOrders().apply(orders.keyAt(index), orders.valueAt(index), book.indexIdentities);
    }

    void clearCommittedChanges(IdIndex identities) {
        book.assertOwner();
        if (identities == null) throw new IllegalArgumentException("runtime identities are required");
        for (int index = 0; index < book.changedRiskSnapshots.size(); index++) {
            long positionKey = book.changedRiskSnapshots.keyAt(index);
            if (book.changedRiskSnapshots.valueAt(index) == null && book.changedRiskSnapshots.hasPreparedAt(index)
                    && Boolean.TRUE.equals(book.changedRiskSnapshots.preparedAt(index))
                    && book.changedPositions.get(positionKey) == null)
                releaseRetiredPositionIdentity(identities, positionKey);
        }
        book.changedRiskSnapshots.clear();
        releaseRetiredPositionIdentities(identities, book.changedPositions.directBuffer());
        long remaining = book.changedPositions.retainedLaneMask();
        while (remaining != 0) {
            int laneId = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            releaseRetiredPositionIdentities(identities, book.changedPositions.laneBuffer(laneId));
        }
        book.changedPositions.clear();
        clearChangedKeys();
    }

    private void releaseRetiredPositionIdentities(IdIndex identities, StateChangeBuffer<Position> positions) {
        for (int index = 0; index < positions.size(); index++)
            if (positions.valueAt(index) == null) releaseRetiredPositionIdentity(identities, positions.keyAt(index));
    }

    void releaseRetiredPositionIdentity(IdIndex identities, long positionKey) {
        if (!book.riskPositionIdentities.contains(positionKey)) {
            identities.releasePositionKey(positionKey);
        }
    }

    void retainDirectPositionIdentity(AccountPartition lane, IdIndex identities, CoreInstrument instrument, Order order) {
        if (!book.productLine.isDerivative()) return;
        TradeSettlementChanges changes = lane.matcherSettlementChanges();
        if (changes == null || !changes.directPositionIdentities() || (!lane.commandScopeActive() && book.ownerLaneScope != lane))
            throw new IllegalStateException("direct position identity requires its settlement Lane");
        long key = order.positionIdentityKey();
        var output = changes.laneDelta(lane.laneId()).positions();
        if (output.indexOf(key) >= 0) return;
        long retained = identities.retainPositionInLane(
                lane, key, order.userId(), instrument.instrumentId(), order.positionSide());
        if (retained != key) throw new IllegalStateException("position identity changed during retention");
        output.put(key, lane.position(key));
    }

    void validateControlFunds(boolean externalAdjustment) {
        book.assertOwner();
        if (book.ledgerControl != null) {
            book.ledgerControl.validateFunds(externalAdjustment);
            return;
        }
        boolean accountFundsChanged = false;
        for (int laneIndex = 0; laneIndex < book.accountRollback.balancePatchCount(); laneIndex++) {
            AccountTransactionRollback.BalancePatches patches = book.accountRollback.balancePatch(laneIndex);
            accountFundsChanged |= patches.size() != 0;
        }
        if (!accountFundsChanged && book.changedPositions.isEmpty()) return;
        if (!book.controlFundsValidated) {
            appendControlAccountFunds(book.controlValidationFunds);
            book.controlValidationFunds.requireConserved(externalAdjustment);
            book.controlValidationFunds.clear();
            book.applyLedgerControlPositions(book.controlPositionUndo);
            book.controlFundsValidated = true;
        }
    }

    void appendControlAccountFunds(FundsTotal target) {
        for (int laneIndex = 0; laneIndex < book.accountRollback.balancePatchCount(); laneIndex++) {
            AccountTransactionRollback.BalancePatches patches = book.accountRollback.balancePatch(laneIndex);
            appendBalanceFundsDelta(patches, target);
        }
    }

    static void prepareBalanceFundsDelta(AccountTransactionRollback.BalancePatches patches, FundsTotal accumulator) {
        accumulator.clear();
        appendBalanceFundsDelta(patches, accumulator);
    }

    static void appendBalanceFundsDelta(AccountTransactionRollback.BalancePatches balances, FundsTotal accumulator) {
        for (int index = 0; index < balances.size(); index++) {
            long userId = balances.userId(index);
            int assetId = balances.assetId(index);
            if (!balances.capturedAfter(index)) {
                throw new IllegalStateException("balance mutation did not publish its lane after-state");
            }
            long beforeAvailable = balances.presentBefore(index) ? balances.availableBefore(index) : 0;
            long beforeLocked = balances.presentBefore(index) ? balances.lockedBefore(index) : 0;
            long afterAvailable = balances.presentAfter(index) ? balances.availableAfter(index) : 0;
            long afterLocked = balances.presentAfter(index) ? balances.lockedAfter(index) : 0;
            accumulator.add(assetId, TradeSettlementPosting.OwnerKind.USER, userId,
                    TradeSettlementPosting.Subledger.AVAILABLE,
                    Math.subtractExact(afterAvailable, beforeAvailable));
            accumulator.add(assetId, TradeSettlementPosting.OwnerKind.USER, userId,
                    TradeSettlementPosting.Subledger.LOCKED,
                    Math.subtractExact(afterLocked, beforeLocked));
        }
    }

    void clearChangedKeys() {
        book.assertOwner();
        book.balancesChanged = false;
        book.accountRollback.clearNonBalanceImages();
        clearChanged(book.changedUsers);
        book.changedOrders.clear();
        clearChanged(book.changedReservations);
        book.changedPositions.clear();
        book.changedLiquidations.clear();
        clearChanged(book.changedMarkPrices);
        book.changedRiskSnapshots.clear();
        clearChanged(book.changedRiskScans);
        book.changedLeverages.clear();
        book.changedAlgoOrders.clear();
        book.changedCancelAllAfterTimers.clear();
        book.changedTriggerOrders.clear();
        clearChanged(book.changedFeePolicies);
        if (book.ledgerControl != null) {
            book.ledgerControl.commitScope();
            book.ledgerControl = null;
        } else {
            book.systemFunds.clearChangedKeys();
        }
        book.accountRollback.publishAndClearBalances();
        if (book.controlFundsValidated) {
            book.controlPositionUndo.clear();
            book.controlFundsValidated = false;
        }
    }

    static void clearChanged(LongHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= AccountBook.CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }

    static void clearChanged(IntHashSet values) {
        if (values.isEmpty()) return;
        boolean compact = values.size() >= AccountBook.CHANGE_KEY_COMPACTION_THRESHOLD;
        values.clear();
        if (compact) values.compact();
    }
}
