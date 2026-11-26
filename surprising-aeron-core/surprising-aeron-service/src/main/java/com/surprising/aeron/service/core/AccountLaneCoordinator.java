package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.LaneThread;
import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.AccountTransactionRollback;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.change.AccountPartitionCommit;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderCheckService;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.order.OrderResultTarget;
import com.surprising.aeron.service.order.PendingOrderBatch;
import com.surprising.aeron.service.order.PlaceBatchCheckEvent;
import com.surprising.aeron.service.order.PlaceOrderCheckEvent;
import com.surprising.aeron.service.order.ReplaceOrderCheck;
import com.surprising.aeron.service.order.ReplaceOrderCheckEvent;
import com.surprising.aeron.service.order.ResolvedPlaceOrder;
import com.surprising.aeron.service.settlement.TradeSettlement;
import com.surprising.aeron.service.settlement.TradeSettlementChanges;
import com.surprising.aeron.service.settlement.TradeSettlementPlan;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import com.surprising.aeron.support.collection.PrimitiveLongView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/** Lane 作用域调度、读写栅栏、对象池、准入派发与撮合结算交接协作器。 */
final class AccountLaneCoordinator {
    private final AccountBook book;

    AccountLaneCoordinator(AccountBook book) {
        this.book = book;
    }

    void readFence(long userId, long committedCoreSequence) {
        book.assertOwner();
        boolean acquired = book.accountLanesStarted() && !book.ownerLaneAccess();
        try {
            if (acquired) while (!book.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            book.onLane(book.topology.accountLaneId(userId), lane -> {
                lane.readFence(committedCoreSequence);
                return null;
            });
        } finally {
            if (acquired) book.releaseOwnerLaneAccess();
        }
    }

    void readFenceAll(long committedCoreSequence) {
        book.assertOwner();
        boolean acquired = book.accountLanesStarted() && !book.ownerLaneAccess();
        try {
            if (acquired) while (!book.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                final int currentLaneId = laneId;
                book.onLane(currentLaneId, lane -> {
                    lane.requireReadFence(committedCoreSequence);
                    return null;
                });
            }
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                final int currentLaneId = laneId;
                book.onLane(currentLaneId, lane -> {
                    lane.advanceReadFence(committedCoreSequence);
                    return null;
                });
            }
        } finally {
            if (acquired) book.releaseOwnerLaneAccess();
        }
    }

    <T> T readAccountLane(AccountPartition lane, Function<AccountPartition, T> query) {
        if (!book.accountLanesStarted()) {
            if (lane == null || query == null || lane.laneId() >= book.accountLanes.length
                    || book.accountLanes[lane.laneId()] != lane) {
                throw new IllegalArgumentException("invalid account lane read scope");
            }
            enterLaneCommandScope(lane);
            lane.queryScopeActive(true);
            try { return query.apply(lane); }
            finally {
                lane.queryScopeActive(false);
                exitLaneCommandScope(lane, false);
            }
        }
        if (lane == null || query == null || lane.laneId() >= book.accountLanes.length
                || book.accountLanes[lane.laneId()] != lane || book.laneLifecycle.worker(lane.laneId()) == null) {
            throw new IllegalArgumentException("invalid account lane read scope");
        }
        Thread current = Thread.currentThread();
        if (current instanceof LaneThread laneThread) {
            if (laneThread.lane() != lane) {
                throw new IllegalStateException("account query must run on its owning Lane thread");
            }
        } else if (current != book.laneLifecycle.worker(lane.laneId()).thread()) {
            throw new IllegalStateException("account query must run on its owning Lane thread");
        }
        enterLaneCommandScope(lane);
        lane.queryScopeActive(true);
        try { return query.apply(lane); }
        finally {
            lane.queryScopeActive(false);
            exitLaneCommandScope(lane, false);
        }
    }

    boolean dispatchAccountQuery(long laneMask, AccountLaneCommand query) {
        book.assertOwner();
        if (Thread.currentThread() != book.owner)
            throw new IllegalStateException("account query producer must be Owner");
        long validMask = book.accountLanes.length == Long.SIZE ? -1L : (1L << book.accountLanes.length) - 1;
        if (query == null || laneMask == 0 || (laneMask & ~validMask) != 0)
            throw new IllegalArgumentException("invalid account query dispatch");
        if (!book.accountLanesStarted()) {
            long lanes = laneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
                query.execute(book.accountLanes[laneId]);
            }
            return true;
        }
        book.assertAccountLanesHealthy();
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            if (!book.laneLifecycle.worker(laneId).hasCapacity()) return false;
        }
        book.releaseOwnerLaneAccess();
        lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
            book.laneLifecycle.worker(laneId).submit(query);
        }
        return true;
    }

    void enterLaneCommandScope(AccountPartition lane) {
        if (lane == null || lane.laneId() >= book.accountLanes.length || book.accountLanes[lane.laneId()] != lane) {
            throw new IllegalStateException("invalid account lane command scope");
        }
        Thread current = Thread.currentThread();
        if (book.owner == null) book.bindOwner();
        if (current == book.owner ? book.ownerLaneScope != null : lane.commandScopeActive())
            throw new IllegalStateException("invalid account lane command scope");
        SettlementLaneWorker worker = book.laneLifecycle.worker(lane.laneId());
        if (current != book.owner && (worker == null || worker.thread() != current)) {
            throw new IllegalStateException("account command must run on its owning Lane thread");
        }
        if (current == book.owner && book.accountLanesStarted() && !book.ownerLaneAccess()) {
            throw new IllegalStateException("active account access must run on its owning Lane thread");
        }
        if (current != book.owner || !book.accountLanesStarted()) {
            lane.assertOwner();
        }
        if (current == book.owner) {
            book.ownerLaneScope = lane;
            if (!book.accountLanesStarted()) lane.commandScopeActive(true);
        } else {
            lane.commandScopeActive(true);
        }
    }

    void enterMatcherSettlementScope(AccountPartition lane, TradeSettlementChanges changes) {
        if (changes == null || lane == null || lane.matcherSettlementChanges() != null
                || Thread.currentThread() == book.owner && book.ownerMatcherSettlementChangesScope != null
                || (changes.activeLaneMask() & (1L << lane.laneId())) == 0) {
            throw new IllegalStateException("invalid matcher settlement scope");
        }
        enterLaneCommandScope(lane);
        if (Thread.currentThread() == book.owner) book.ownerMatcherSettlementChangesScope = changes;
        lane.matcherSettlementChanges(changes);
    }

    void exitLaneCommandScope(AccountPartition lane) {
        exitLaneCommandScope(lane, true);
    }

    void exitLaneCommandScope(AccountPartition lane, boolean captureRetirementProof) {
        Thread current = Thread.currentThread();
        if (lane == null || (current == book.owner
                ? book.ownerLaneScope != lane : !lane.commandScopeActive())) {
            throw new IllegalStateException("account lane command scope is not active");
        }
        if (current != book.owner || !book.accountLanesStarted()) {
            lane.assertOwner();
            if (captureRetirementProof) book.laneDeltas[lane.laneId()].preparePositionRetirement(lane);
        }
        if (current == book.owner) {
            book.ownerLaneScope = null;
            if (!book.accountLanesStarted()) lane.commandScopeActive(false);
            book.flushPublishedChanges(lane.laneId());
        } else {
            lane.commandScopeActive(false);
        }
    }

    void exitMatcherSettlementScope(AccountPartition lane, TradeSettlementChanges changes) {
        if (changes == null || lane == null || (Thread.currentThread() == book.owner
                ? book.ownerMatcherSettlementChangesScope != changes : lane.matcherSettlementChanges() != changes)) {
            throw new IllegalStateException("matcher settlement scope is not active");
        }
        if (Thread.currentThread() == book.owner) book.ownerMatcherSettlementChangesScope = null;
        lane.matcherSettlementChanges(null);
        exitLaneCommandScope(lane, false);
    }

    void ensureLaneWorkerCapacity(long laneMask) {
        book.assertAccountLanesHealthy();
        if (!book.accountLanesStarted()) return;
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!book.laneLifecycle.worker(laneId).hasCapacity()) {
                throw new RejectedExecutionException("Lane " + laneId + " is full");
            }
        }
    }

    void applyLaneUsers(AccountPartition lane, LongArrayList users, long coreSequence) {
        LongIterator iterator = users.longIterator();
        while (iterator.hasNext()) {
            long userId = iterator.next();
            if (!lane.owns(userId)) lane.registerUser(userId);
        }
        lane.applied(coreSequence);
        lane.committed(coreSequence);
        publishLaneCommittedSequence(lane);
    }

    void publishLaneCommittedSequence(AccountPartition lane) {
        int laneId = lane.laneId();
        long committed = lane.committedSequence();
        AccountBook.COMMITTED_SEQUENCES.setVolatile(
                book.publishedLaneCommittedSlots, laneId * AccountBook.COMMITTED_SEQUENCE_STRIDE, committed);
    }

    TradeSettlementChanges acquireMatcherSettlementChanges(long laneMask) {
        book.assertOwner();
        if (book.freeMatcherSettlementChangeCount == 0) {
            throw new IllegalStateException("AccountPartitionCommit.Delta ring is full");
        }
        TradeSettlementChanges changes = book.matcherSettlementChangesRing[
                book.freeMatcherSettlementChangeSlots[--book.freeMatcherSettlementChangeCount]];
        if (changes.inUse()) throw new IllegalStateException("AccountPartitionCommit.Delta ring slot is already active");
        changes.inUse(true);
        changes.activeLaneMask(laneMask);
        changes.ensureActiveLanes(laneMask);
        return changes;
    }

    void releaseMatcherSettlementChanges(TradeSettlementChanges changes) {
        if (changes == null || !changes.inUse()) throw new IllegalStateException("AccountPartitionCommit.Delta slot is not active");
        changes.returnRetiredOrders(book);
        changes.clear();
        changes.inUse(false);
        book.freeMatcherSettlementChangeSlots[book.freeMatcherSettlementChangeCount++] = changes.ringIndex();
    }

    AccountPartitionCommit dispatchLaneMutation(long coreSequence, Iterable<Long> userIds,
            LongLongMap orderUsers, Iterable<Long> triggerIds, long timestamp, long position) {
        book.assertOwner();
        if (coreSequence <= 0 || userIds == null || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid account metadata commit");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderUsers, triggerIds, timestamp, position,
                null, null, -1);
    }

    AccountPartitionCommit dispatchBatchCommit(long coreSequence, Iterable<Long> userIds,
            LongLongMap orderUsers, long timestamp, long position, long responseUserId,
            OrderResultTarget target, IdIndex identities) {
        book.assertOwner();
        if (coreSequence <= 0 || userIds == null || timestamp < 0 || position < 0
                || responseUserId <= 0 || target == null || identities == null)
            throw new IllegalArgumentException("invalid final batch account task");
        clearLaneUserScratch();
        addLaneUsers(userIds);
        return dispatchLaneMutationFromScratch(coreSequence, orderUsers, null, timestamp, position,
                target, identities, book.topology.accountLaneId(responseUserId));
    }

    private void addLaneUsers(Iterable<Long> userIds) {
        if (userIds instanceof ImmutableLongArrayList primitive) {
            primitive.forEachLong(userId -> { if (userId > 0) addLaneUser(userId); });
            return;
        }
        if (userIds instanceof PrimitiveLongView view) {
            for (int i = 0; i < view.primitiveSize(); i++) {
                long userId = view.primitiveValueAt(i);
                if (userId > 0) addLaneUser(userId);
            }
            return;
        }
        for (Long userId : userIds) if (userId != null && userId > 0) addLaneUser(userId);
    }

    void clearLaneUserScratch() {
        for (LongArrayList users : book.laneUserScratch) {
            users.clear();
        }
    }

    void addLaneUser(long userId) {
        int laneId = book.topology.accountLaneId(userId);
        book.laneUserScratch[laneId].add(userId);
    }

    private AccountPartitionCommit dispatchLaneMutationFromScratch(long coreSequence,
            LongLongMap orderUsers, Iterable<Long> triggerIds, long timestamp, long position,
            OrderResultTarget target, IdIndex identities, int responseLaneId) {
        long laneMask = 0;
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            LongArrayList users = book.laneUserScratch[laneId];
            if (users.isEmpty()) continue;
            if (coreSequence <= Math.max(
                    (long) AccountBook.COMMITTED_SEQUENCES.getVolatile(book.publishedLaneCommittedSlots,
                            laneId * AccountBook.COMMITTED_SEQUENCE_STRIDE), book.dispatchedLaneCommitSequences[laneId])) {
                throw new IllegalStateException("account lane apply is out of order");
            }
            laneMask |= 1L << laneId;
        }
        long commitMask = laneMask;
        if (target != null) laneMask |= 1L << responseLaneId;
        if (laneMask == 0) return null;
        if (book.accountLanesStarted()) book.releaseOwnerLaneAccess();
        ensureLaneWorkerCapacity(laneMask);
        AccountPartitionCommit event = book.laneCommitEventPool.pollFirst();
        if (event == null) event = new AccountPartitionCommit(book.accountLanes.length);
        event.prepare(coreSequence, laneMask, book.laneUserScratch, book);
        try {
            if (target != null) event.prepareResponse(target, identities, responseLaneId, commitMask);
            event.prepareMetadata(orderUsers, timestamp, position);
            event.prepareTriggerCancellations(triggerIds);
        } catch (RuntimeException failure) {
            event.discard();
            book.laneCommitEventPool.addFirst(event);
            throw failure;
        }
        long lanes = laneMask;
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            if (!book.accountLanesStarted()) {
                event.execute(book.accountLanes[laneId]);
            } else {
                book.laneLifecycle.worker(laneId).submit(event);
            }
            if ((commitMask & (1L << laneId)) != 0)
                book.dispatchedLaneCommitSequences[laneId] = coreSequence;
        }
        return event;
    }

    boolean laneCommitComplete(AccountPartitionCommit event) {
        book.assertOwner();
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        book.assertAccountLanesHealthy();
        return event.complete();
    }

    void releaseLaneCommit(AccountPartitionCommit event) {
        if (event == null) throw new IllegalArgumentException("Account Lane commit event is required");
        event.publishAndClear();
        book.laneCommitEventPool.addFirst(event);
    }

    PlaceOrderCheckEvent acquirePlaceOrderCheck() {
        PlaceOrderCheckEvent event = book.placeOrderCheckEventPool.pollFirst();
        return event == null ? new PlaceOrderCheckEvent() : event;
    }

    void discardPlaceOrderCheck(PlaceOrderCheckEvent event) {
        if (event == null) return;
        event.discard();
        book.placeOrderCheckEventPool.addFirst(event);
    }

    PlaceOrderCheckEvent dispatchPlaceOrderCheck(
            long coreSequence, long userId, PlaceOrderCheckEvent event, UUID commandId,
            SystemFunds.SettlementStatus settlementStatus, int assetId, IdIndex identities,
            long timestamp, long position) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        event.prepare(coreSequence, userId, commandId, settlementStatus,
                assetId, laneId, book, identities, timestamp, position);
        if (event.instrument().usesOpenInterestForUserLimit() && !event.reduceOnly()) {
            event.bindOpenInterest(book.ledgerOrderCheckOpenInterest(event.instrumentId()));
        }
        try {
            if (book.accountLanesStarted() && !book.laneLifecycle.worker(laneId).hasOrderCheckCapacity())
                throw new RejectedExecutionException("account admission mailbox is full");
            if (!book.accountLanesStarted()) {
                event.execute(book.accountLanes[laneId]);
            } else {
                book.releaseOwnerLaneAccess();
                book.laneLifecycle.worker(laneId).submitOrderCheck(event);
            }
        } catch (RuntimeException | Error failure) {
            if (!event.complete()) event.discard();
            book.placeOrderCheckEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    void releasePlaceOrderCheck(PlaceOrderCheckEvent event) {
        if (event == null) return;
        event.clear();
        book.placeOrderCheckEventPool.addFirst(event);
    }

    ReplaceOrderCheckEvent dispatchReplaceOrderCheck(long sequence, long userId,
            long originalOrderId, AmendOrderCommand amend, ReplaceOrderCommand replace,
            OrderCheckService.Context decision, int matcherShard, IdIndex identities) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        ensureLaneWorkerCapacity(1L << laneId);
        ReplaceOrderCheckEvent event = book.replacementOrderCheckEventPool.pollFirst();
        if (event == null) event = new ReplaceOrderCheckEvent();
        event.prepare(sequence, userId, originalOrderId, amend, replace, decision, matcherShard, book, identities);
        if (decision.instrument().usesOpenInterestForUserLimit()
                && (replace == null || replace.replacement() == null || !replace.replacement().reduceOnly())) {
            event.bindOpenInterest(book.ledgerOrderCheckOpenInterest(decision.instrument().instrumentId()));
        }
        try {
            if (!book.accountLanesStarted()) {
                event.execute(book.accountLanes[laneId]);
            } else {
                book.releaseOwnerLaneAccess();
                book.laneLifecycle.worker(laneId).submit(event);
            }
        } catch (RuntimeException | Error failure) {
            event.discard();
            book.replacementOrderCheckEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    void releaseReplaceOrderCheck(ReplaceOrderCheckEvent event) {
        book.assertOwner();
        if (event == null) return;
        event.clear();
        book.replacementOrderCheckEventPool.addFirst(event);
    }

    PlaceBatchCheckEvent dispatchPlaceBatchOrderCheck(
            long coreSequence, long userId, UUID commandId, ResolvedPlaceOrder[] orders, long[] clientKeys,
            Order[] admittedOrders, int itemCount, int matcherShard, IdIndex identities,
            PendingOrderBatch source, long timestamp, long position) {
        book.assertOwner();
        int laneId = book.topology.accountLaneId(userId);
        TradeSettlementChanges changes = acquireMatcherSettlementChanges(1L << laneId);
        changes.ensureOrderCheckCapacity(laneId, itemCount);
        PlaceBatchCheckEvent event = book.placeBatchOrderCheckEventPool.pollFirst();
        if (event == null) event = new PlaceBatchCheckEvent();
        event.prepare(coreSequence, userId, commandId, orders, clientKeys, admittedOrders,
                itemCount, laneId, matcherShard, book, changes, identities, source, timestamp, position);
        try {
            if (!book.accountLanesStarted()) {
                event.execute(book.accountLanes[laneId]);
            } else {
                book.releaseOwnerLaneAccess();
                book.laneLifecycle.worker(laneId).submitOrderCheck(event);
            }
        } catch (RuntimeException | Error failure) {
            releaseMatcherSettlementChanges(event.discardChanges());
            event.clear();
            book.placeBatchOrderCheckEventPool.addFirst(event);
            throw failure;
        }
        return event;
    }

    void registerPlaceBatchOrderCheck(PlaceBatchCheckEvent event) {
        book.assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        long userId = event.userId();
        book.pendingReservations.registerBatch(event.coreSequence(), userId, event.admittedOrders(), event.itemCount());
        book.revision = Math.addExact(book.revision, event.itemCount());
    }

    void collectPlaceBatchOrderCheck(PlaceBatchCheckEvent event) {
        book.assertOwner();
        if (event == null || !event.complete() || event.rejection() != null) {
            throw new IllegalStateException("place batch admission is not collectable");
        }
        int laneId = book.topology.accountLaneId(event.userId());
        book.accountRollback.captureBatchOrderCheckBefore(event.userId(), event.userBefore(), event.admittedOrders(), event.itemCount(), laneId);
        event.copyBalanceBeforeTo(book.accountRollback.balancePatch(laneId));
        TradeSettlementChanges changes = event.takeChanges();
        try {
            changes.laneDelta(laneId).commitBatchOrderCheckToOwner(book, laneId);
            AccountTransactionRollback.BalancePatches balances = changes.balancePatch(laneId);
            for (int index = 0; index < balances.size(); index++) {
                balances.publishAvailableAt(book, index);
                if (book.changedUsers != null) book.changedUsers.add(balances.userId(index));
                book.markBalancesChanged();
            }
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    void discardPlaceBatchOrderCheck(PlaceBatchCheckEvent event) {
        book.assertOwner();
        if (event == null) return;
        releaseMatcherSettlementChanges(event.takeChanges());
    }

    void releasePlaceBatchOrderCheck(PlaceBatchCheckEvent event) {
        book.assertOwner();
        if (event == null) return;
        event.clear();
        book.placeBatchOrderCheckEventPool.addFirst(event);
    }

    void rollbackPlaceBatchOrderCheckInLane(
            AccountPartition lane, long userId, long coreSequence, ResolvedPlaceOrder[] orders,
            long[] clientKeys, int admittedCount, UserAccount userBefore) {
        lane.assertOwner();
        for (int index = admittedCount - 1; index >= 0; index--) {
            long orderId = orders[index].orderId();
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation == null) {
                throw new IllegalStateException("batch admission rollback reservation is missing");
            }
            lane.completePendingReservation(orderId, coreSequence);
            if (clientKeys[index] != 0) {
                AccountBook.removeClientOrderIndex(lane, userId, clientKeys[index]);
            }
            lane.removeReservationValue(orderId);
            lane.removeReservationId(userId, orderId);
            lane.removeOrder(orderId);
            IntObjectHashMap<AccountBalance> balances = lane.userBalances(userId);
            AccountBalance balance = balances == null ? null : balances.get(reservation.assetId());
            if (balance == null) throw new IllegalStateException("batch admission rollback balance is missing");
            balance.release(reservation.reservedUnits());
        }
        if (userBefore != null) {
            UserAccount current = lane.user(userId);
            lane.storeUser(userId, (book.accountLanesStarted() ? current.laneValue() : current)
                    .withState(userBefore.revision(), userBefore.positionMode()));
        }
    }

    ResolvedPlaceOrder collectPlaceOrderCheck(PlaceOrderCheckEvent event) {
        book.assertOwner();
        if (event == null || !event.complete()) return null;
        if (event.rejection() != null) return null;
        long orderId = event.orderId();
        long userId = event.userId();
        if (book.pendingReservations.hasPendingUser(orderId)) {
            throw new IllegalStateException("place admission was collected twice");
        }
        book.pendingReservations.indexPendingReservation(userId, orderId, event.coreSequence(),
                Math.incrementExact(book.pendingReservations.totalPendingReservations()));
        book.revision = Math.incrementExact(book.revision);
        return event.resolvedOrder();
    }

    void dispatchMatcherFinancialSettlement(TradeSettlement event) {
        book.assertOwner();
        if (event == null) return;
        if (book.ledgerControl != null) throw new IllegalStateException("Matcher money cannot enter an unfinished control scope");
        if (!event.hasLedgerSettlement()) event.submitSystemFundsSettlement();
    }

    boolean pollMatcherFinancialSettlement(TradeSettlement event) {
        if (event == null) return true;
        dispatchMatcherFinancialSettlement(event);
        return event.complete();
    }

    boolean collectMatcherSettlement(
            TradeSettlement event,
            AccountBook.TerminalOrderSink terminalOrderSink,
            PrimitiveLongChangeSet changedUsers,
            PrimitiveLongChangeSet changedOrders) {
        book.assertOwner();
        if (event == null || !event.complete()) return false;
        event.markCollected();
        boolean ownerPending = book.pendingReservations.containsSequence(event.plan().coreSequence());
        for (int index = 0; index < event.planCount(); index++) {
            if (ownerPending) book.pendingReservations.unindexMatcherPendingReservations(event.plan(index));
            if (event.plan(index).completedTrigger() != null) book.revision = Math.incrementExact(book.revision);
        }
        long laneMask = event.requiredLaneMask();
        TradeSettlementChanges changes = event.takeChanges();
        try {
            long remainingLanes = laneMask;
            int completed = 0;
            while (remainingLanes != 0) {
                int laneId = Long.numberOfTrailingZeros(remainingLanes);
                remainingLanes &= remainingLanes - 1;
                completed += changes.laneDelta(laneId).completedPending();
                if (changes.directPositionIdentities()) {
                    var positions = changes.laneDelta(laneId).positions();
                    for (int index = 0; index < positions.size(); index++)
                        event.identities().releasePublishedPosition(positions.keyAt(index));
                }
                changes.laneDelta(laneId).commitTerminalToOwner(book, laneId, terminalOrderSink, changedUsers,
                        changedOrders);
                AccountTransactionRollback.BalancePatches balances = changes.balancePatch(laneId);
                for (int index = 0; index < balances.size(); index++) {
                    balances.publishAvailableAt(book, index);
                    if (changedUsers != null) changedUsers.add(balances.userId(index));
                    book.markBalancesChanged();
                }
            }
            if (terminalOrderSink != null) terminalOrderSink.completeSequence();
            book.pendingReservations.completedBatchItems(event.plan().coreSequence(), completed);
            return true;
        } finally {
            releaseMatcherSettlementChanges(changes);
        }
    }

    void completeMatcherPendingReservations(AccountPartition lane, TradeSettlementPlan plan, Order orderCheckVersion) {
        for (int index = 0; index < plan.orderCount(); index++) {
            long orderId = plan.orderId(index);
            if (lane.pendingReservationSequence(orderId) != plan.coreSequence()) continue;
            OrderFundsReservation reservation = lane.reservation(orderId);
            if (reservation == null || book.topology.accountLaneId(reservation.userId()) != lane.laneId()) {
                throw new IllegalStateException("matcher pending reservation is missing from its owner lane");
            }
            TradeSettlementChanges changes = lane.matcherSettlementChanges();
            if (changes == null) {
                book.accountRollback.captureUserBefore(reservation.userId());
                book.accountRollback.captureOrderBefore(orderId);
                book.accountRollback.captureReservationBefore(orderId);
                lane.clientKeysByOrderId().forEach(orderId,
                        clientKey -> book.accountRollback.captureClientOrderBefore(reservation.userId(), clientKey));
            }
            book.accountRollback.captureBalanceBefore(lane, reservation.userId(), reservation.assetId());
            lane.completePendingReservation(orderId, plan.coreSequence());
            if (changes != null) changes.laneDelta(lane.laneId()).completedPending(changes.laneDelta(lane.laneId()).completedPending() + 1);
            if (changes == null || !changes.laneDelta(lane.laneId()).orders().containsKey(orderId)) {
                Order admitted = lane.order(orderId);
                book.publishOrder(lane, orderId, orderCheckVersion != null && orderCheckVersion.equals(admitted)
                        ? orderCheckVersion : admitted);
                if (changes == null) {
                    book.changedOrder(orderId, admitted);
                    book.markUserChanged(reservation.userId());
                }
            }
            book.accountRollback.captureBalanceAfter(lane, reservation.userId(), reservation.assetId());
        }
    }

    void stampMatcherOrders(AccountPartition lane, TradeSettlementPlan plan, long timestamp, long clusterPosition) {
        for (int index = 0; index < plan.orderCount(); index++) {
            Order order = lane.order(plan.orderId(index));
            if (order == null || order.updatedAtEpochMillis() == timestamp
                    && order.clusterPosition() == clusterPosition) {
                continue;
            }
            if (lane.matcherSettlementChanges() == null) book.replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
            else book.updateOrderStatusInLane(lane, order.orderId(), order.status(), order.revision(), timestamp, clusterPosition);
        }
    }

    TradeSettlement prepareDirectMatcherSettlement(long sequence, long laneMask,
            Order single, Order[] orders, int count, UUID commandId, int shard,
            IdIndex identities, long timestamp, long position, List<Long> cancellations,
            OrderResultTarget target) {
        return prepareDirectMatcherSettlement(sequence, sequence, laneMask, single, orders, count, commandId, shard,
                identities, timestamp, position, cancellations, target, null);
    }

    TradeSettlement prepareDirectMatcherSettlement(
            long sequence, long laneMask, PlaceOrderCheckEvent orderCheck,
            UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, List<Long> cancellations, OrderResultTarget target) {
        book.assertOwner();
        if (orderCheck == null) throw new IllegalArgumentException("place admission is missing");
        ResolvedPlaceOrder resolved = orderCheck.matchingOrder();
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = book.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        TradeSettlement.BatchStorage storage = event.batchStorage(1);
        CoreInstrument instrument = book.instrument(identities.instrumentId(resolved.symbolId()));
        if (instrument == null || instrument != resolved.instrument()) {
            throw new IllegalStateException("direct settlement instrument changed");
        }
        storage.instruments[0] = instrument;
        storage.baseAssetIds[0] = identities.assetId(instrument.baseAsset());
        storage.quoteAssetIds[0] = identities.assetId(instrument.quoteAsset());
        storage.settleAssetIds[0] = identities.assetId(instrument.settleAsset());
        storage.metadataSlots.put(resolved.symbolId(), 0);
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                book, identities, 1, cancellations, target);
        event.orderCheckRoute(orderCheck.laneId());
        event.orderCheckOrder(resolved, orderCheck.userId());
        return event;
    }

    TradeSettlement prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, Order single, Order[] orders, int count, UUID commandId,
            int shard, IdIndex identities, long timestamp, long position,
            List<Long> cancellations, OrderResultTarget target) {
        return prepareDirectMatcherSettlement(coreSequence, commitSequence, laneMask, single, orders, count,
                commandId, shard, identities, timestamp, position, cancellations, target, null);
    }

    private TradeSettlement prepareDirectMatcherSettlement(long coreSequence, long commitSequence,
            long laneMask, Order single, Order[] orders, int count, UUID commandId,
            int shard, IdIndex identities, long timestamp, long position,
            List<Long> cancellations, OrderResultTarget target, ReplaceOrderCheck replacement) {
        book.assertOwner();
        if (count <= 0 || replacement == null && single == null && (orders == null || count > orders.length))
            throw new IllegalArgumentException("direct settlement admission is missing");
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = book.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        TradeSettlement.BatchStorage storage = event.batchStorage(count);
        if (replacement != null) {
            if (count != 1) throw new IllegalStateException("replacement must have one admission slot");
            var resolved = replacement.resolved();
            single = storage.replacementOrder = Order.prepareCommandOrder(storage.replacementOrder,
                    book.productLine, replacement.userId(), resolved, commandId, resolved.symbolId(), timestamp, position);
        }
        for (int index = 0; index < count; index++) {
            Order order = single == null ? orders[index] : single;
            if (order == null) throw new IllegalStateException("direct settlement order was not admitted");
            storage.admittedOrders[index] = order;
            int prior = storage.metadataSlots.get(order.symbolId());
            if (prior < 0) {
                CoreInstrument instrument = book.instrument(identities.instrumentId(order.symbolId()));
                if (instrument == null || instrument != order.instrument())
                    throw new IllegalStateException("direct settlement instrument changed");
                storage.instruments[index] = instrument;
                storage.baseAssetIds[index] = identities.assetId(instrument.baseAsset());
                storage.quoteAssetIds[index] = identities.assetId(instrument.quoteAsset());
                storage.settleAssetIds[index] = identities.assetId(instrument.settleAsset());
                storage.metadataSlots.put(order.symbolId(), index);
            } else {
                storage.instruments[index] = storage.instruments[prior];
                storage.baseAssetIds[index] = storage.baseAssetIds[prior];
                storage.quoteAssetIds[index] = storage.quoteAssetIds[prior];
                storage.settleAssetIds[index] = storage.settleAssetIds[prior];
            }
        }
        event.prepareDirect(coreSequence, commitSequence, laneMask, timestamp, position, commandId, shard,
                book, identities, count, cancellations, target);
        return event;
    }

    TradeSettlement prepareDirectReplacement(long sequence, long commitSequence, long laneMask,
            ReplaceOrderCheck orderCheck, UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, List<Long> cancellations, OrderResultTarget resultTarget) {
        book.assertOwner();
        var resolved = orderCheck.resolved();
        TradeSettlement event = prepareDirectMatcherSettlement(sequence, commitSequence, laneMask, null, null, 1,
                commandId, shard, identities, timestamp, position, cancellations, resultTarget, orderCheck);
        event.replacement(orderCheck, identities.assetId(resolved.reservationAsset()));
        return event;
    }

    TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            UUID commandId, int shard, IdIndex identities, long timestamp, long position) {
        return prepareDirectCancellation(sequence, userId, orderId, commandId, shard, identities,
                timestamp, position, null);
    }

    TradeSettlement prepareDirectCancellation(long sequence, long userId, long orderId,
            UUID commandId, int shard, IdIndex identities, long timestamp, long position,
            OrderResultTarget resultTarget) {
        book.assertOwner();
        if (userId <= 0 || orderId <= 0) throw new IllegalArgumentException("cancel order is missing");
        long laneMask = book.topology.accountLaneMask(userId);
        ensureLaneWorkerCapacity(laneMask);
        TradeSettlement event = book.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        event.batchStorage(1).cancellationOrderIds[0] = orderId;
        event.prepareDirect(sequence, laneMask, timestamp, position, commandId, shard,
                book, identities, 1, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    TradeSettlement prepareDirectCancelBatch(long sequence, boolean finalChunk, long userId, long[] orders,
            int count, UUID commandId, int shard, IdIndex identities,
            long timestamp, long position, OrderResultTarget resultTarget) {
        book.assertOwner();
        if (count <= 0 || count > orders.length) throw new IllegalArgumentException("invalid cancel chunk");
        long mask = book.topology.accountLaneMask(userId);
        ensureLaneWorkerCapacity(mask);
        TradeSettlement event = book.matcherSettlementEventPool.pollFirst();
        if (event == null) event = new TradeSettlement();
        System.arraycopy(orders, 0, event.batchStorage(count).cancellationOrderIds, 0, count);
        event.prepareDirect(sequence, finalChunk ? sequence : 0, mask, timestamp, position, commandId, shard,
                book, identities, count, List.of(), resultTarget);
        event.cancellation(userId);
        return event;
    }

    void publishMatcherOwned(TradeSettlement event) {
        if (event == null || event.accountBook() != book || !event.direct()
                || !event.dispatched() || !event.resultPrepared())
            throw new IllegalStateException("invalid Matcher-owned settlement publication");
        if (event.dispatched() && event.queuedLaneMask() != 0)
            throw new IllegalStateException("Matcher settlement was already dispatched");
        long lanes = event.routedLaneMask();
        if (lanes == 0) throw new IllegalStateException("Matcher settlement has no routed Lane");
        if (!book.accountLanesStarted()) return;
        int shard = event.directShard();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            event.markLaneQueued(laneId);
            book.publishMatcherSettlement(laneId, shard, event);
        }
    }

    void releaseMatcherSettlement(TradeSettlement event) {
        book.assertOwner();
        if (event == null) return;
        event.clear();
        book.matcherSettlementEventPool.addFirst(event);
    }

    void discardMatcherSettlement(TradeSettlement event) {
        book.assertOwner();
        if (event == null) return;
        if (!event.complete()) throw new IllegalStateException("rejected direct settlement is incomplete");
        if (event.hasChanges()) {
            releaseMatcherSettlementChanges(event.takeChanges());
        }
        event.clear();
        book.matcherSettlementEventPool.addFirst(event);
    }

    void collectOrderBatchMatcherSettlement(TradeSettlement event, AccountBook.TerminalOrderSink terminalOrderSink) {
        book.assertOwner();
        if (!book.orderBatchMutationScope() || !event.complete())
            throw new IllegalStateException("batch item settlement is not ready to collect");
        book.assertAccountLanesHealthy();
        long lanes = event.requiredLaneMask();
        while (lanes != 0) {
            int laneId = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            book.accountRollback.balancePatch(laneId).mergeSequential(event.changes().balancePatches[laneId]);
        }
        collectMatcherSettlement(event, terminalOrderSink, null, null);
    }
}
