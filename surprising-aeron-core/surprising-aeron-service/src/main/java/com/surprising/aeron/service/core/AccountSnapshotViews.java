package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskMarginCalculator;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** 跨 Lane 快照视图、快照捕获恢复与命令回滚协作器。 */
final class AccountSnapshotViews {
    private final AccountBook book;

    AccountSnapshotViews(AccountBook book) {
        this.book = book;
    }

    Map<CoreLeverageKey, Long> leveragesForRuntime() {
        book.assertOwner();
        TreeMap<CoreLeverageKey, Long> values = new TreeMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::leveragesSnapshot));
        }
        return values;
    }

    Map<Long, CoreAlgoOrderState> algoOrdersForRuntime() {
        book.assertOwner();
        TreeMap<Long, CoreAlgoOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLaneReadOnly(laneId, lane -> { lane.forEachAlgoOrder(values::put); return null; });
        }
        return values;
    }

    Map<Long, CoreTriggerOrderState> triggerOrdersForRuntime() {
        book.assertOwner();
        AccountPartition scoped = book.currentLaneCommandScope();
        if (scoped != null) {
            TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
            scoped.forEachTriggerOrder(values::put);
            return values;
        }
        TreeMap<Long, CoreTriggerOrderState> values = new TreeMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLaneReadOnly(laneId, lane -> {
                lane.forEachTriggerOrder(values::put);
                return null;
            });
        }
        return values;
    }

    LongObjectHashMap<UserAccount> usersForSnapshot() {
        LongObjectHashMap<UserAccount> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::usersSnapshot));
        }
        return values;
    }

    LongObjectHashMap<IntObjectHashMap<AccountBalance>> balancesForSnapshot() {
        LongObjectHashMap<IntObjectHashMap<AccountBalance>> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::balancesSnapshot));
        }
        return values;
    }

    LongObjectHashMap<Order> ordersForSnapshot() {
        LongObjectHashMap<Order> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLaneReadOnly(laneId, lane -> {
                lane.forEachOrder(values::put);
                return null;
            });
        }
        return values;
    }

    LongObjectHashMap<OrderFundsReservation> reservationsForSnapshot() {
        LongObjectHashMap<OrderFundsReservation> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLaneReadOnly(laneId, lane -> {
                lane.forEachReservation(values::put);
                return null;
            });
        }
        return values;
    }

    LongObjectHashMap<LongLongHashMap> clientOrderIndexForSnapshot() {
        LongObjectHashMap<LongLongHashMap> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::clientOrdersSnapshot));
        }
        return values;
    }

    LongObjectHashMap<Position> positionsForSnapshot() {
        LongObjectHashMap<Position> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::positionsSnapshot));
        }
        return values;
    }

    LongObjectHashMap<RiskScanner.LiquidationOrder> liquidationsForSnapshot() {
        LongObjectHashMap<RiskScanner.LiquidationOrder> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::liquidationsSnapshot));
        }
        return values;
    }

    IntObjectHashMap<MarkPrice> markPricesForSnapshot() {
        return book.markPrices;
    }

    LongObjectHashMap<RiskMarginCalculator.Snapshot> riskSnapshotsForSnapshot() {
        LongObjectHashMap<RiskMarginCalculator.Snapshot> values = new LongObjectHashMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            values.putAll(book.onLaneReadOnly(laneId, AccountPartition::riskSnapshotsSnapshot));
        }
        return values;
    }

    IntObjectHashMap<RiskScanner.State> riskScansForSnapshot() {
        return book.riskScans;
    }

    List<AccountLaneSnapshot> accountLaneSnapshots(long fenceSequence, TradingCoreState globalState) {
        book.assertOwner();
        if (globalState == null || globalState.productLine() != book.productLine) {
            throw new IllegalArgumentException("global snapshot state is required");
        }
        book.pendingReservations.assertPendingReservationCounts();
        boolean acquired = book.accountLanesStarted() && !book.ownerLaneAccess();
        try {
            if (acquired) {
                while (!book.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            }
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                book.onLane(laneId, lane -> {
                    lane.requireSnapshot(fenceSequence);
                    return null;
                });
            }
            List<AccountLaneSnapshot> snapshots = new ArrayList<>(book.accountLanes.length);
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                int currentLaneId = laneId;
                snapshots.add(book.onLane(currentLaneId, lane -> lane.snapshot(fenceSequence)));
            }
            for (AccountLaneSnapshot snapshot : snapshots) {
                boolean unchanged = book.onLane(snapshot.laneId(), lane ->
                        lane.revision() == snapshot.revision()
                                && lane.appliedSequence() == snapshot.appliedSequence()
                                && lane.committedSequence() == snapshot.committedSequence()
                                && lane.localStateHash() == snapshot.localStateHash()
                                && lane.localFundsHash() == snapshot.localFundsHash());
                if (!unchanged) {
                    throw new IllegalStateException("account lane changed during snapshot capture");
                }
            }
            return List.copyOf(snapshots);
        } finally {
            if (acquired) {
                book.releaseOwnerLaneAccess();
            }
        }
    }

    boolean hasUnfinishedSettlementRing() {
        book.assertOwner();
        if (book.controlLanes.pending()) return true;
        if (book.accountLanesStarted()) {
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                SettlementLaneWorker worker = book.laneLifecycle.worker(laneId);
                if (worker != null && worker.depth() != 0) return true;
            }
        }
        return false;
    }

    void requireSnapshotFenceReady() {
        book.assertOwner();
        if (book.controlLanes.pending()) throw new IllegalStateException("control Lane work is unfinished");
        if (book.accountLanesStarted()) {
            for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
                SettlementLaneWorker worker = book.laneLifecycle.worker(laneId);
                if (worker != null && worker.depth() != 0)
                    throw new IllegalStateException("Matcher settlement ring is unfinished");
            }
        }
        book.pendingReservations.assertPendingReservationCounts();
        if (book.pendingReservations.totalPendingReservations() != 0 || !book.pendingReservations.sequenceIndexEmpty()
                || !book.pendingReservations.pendingUserIndexEmpty() || book.snapshotProjectionStateDirty()) {
            throw new IllegalStateException("runtime contains unfinished reservation or patch work");
        }
    }

    void restoreAccountLaneSnapshots(List<AccountLaneSnapshot> snapshots, long fenceSequence,
            TradingCoreState globalState) {
        book.assertOwner();
        validateAccountLaneSnapshotManifest(snapshots, fenceSequence, globalState, book.topology);
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            for (Long userId : snapshot.userIds()) {
                if (!book.onLane(laneId, lane -> lane.user(userId) != null)) {
                    throw new IllegalArgumentException("account lane contains an incorrectly routed user");
                }
            }
        }
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            int currentLaneId = laneId;
            book.onLane(currentLaneId, lane -> {
                lane.forEachUserId(userId -> {
                    if (!lane.owns(userId)) {
                        throw new IllegalArgumentException("runtime user is absent from its account lane");
                    }
                });
                return null;
            });
        }
        for (AccountLaneSnapshot snapshot : snapshots) {
            book.onLane(snapshot.laneId(), lane -> {
                lane.restore(snapshot);
                book.publishLaneCommittedSequence(lane);
                return null;
            });
        }
        book.pendingReservations.clearRestoredPendingReservations();
    }

    static void validateAccountLaneSnapshotManifest(
            List<AccountLaneSnapshot> snapshots, long fenceSequence,
            TradingCoreState globalState, LaneTopology topology) {
        if (snapshots == null || topology == null || snapshots.size() != topology.accountLaneCount()
                || globalState == null || fenceSequence < 0) {
            throw new IllegalArgumentException("incomplete account lane snapshot set");
        }
        @SuppressWarnings("unchecked")
        TreeSet<Long>[] expectedUsers = new TreeSet[topology.accountLaneCount()];
        for (int laneId = 0; laneId < expectedUsers.length; laneId++) {
            expectedUsers[laneId] = new TreeSet<>();
        }
        globalState.users().keySet().forEach(userId ->
                expectedUsers[topology.accountLaneId(userId)].add(userId));
        boolean[] present = new boolean[topology.accountLaneCount()];
        for (AccountLaneSnapshot snapshot : snapshots) {
            int laneId = snapshot.laneId();
            if (laneId < 0 || laneId >= present.length || present[laneId]
                    || snapshot.appliedSequence() != fenceSequence
                    || snapshot.committedSequence() != fenceSequence) {
                throw new IllegalArgumentException("invalid account lane snapshot manifest");
            }
            TreeSet<Long> actualUsers = new TreeSet<>(snapshot.userIds());
            if (actualUsers.size() != snapshot.userIds().size()
                    || actualUsers.stream().anyMatch(userId -> topology.accountLaneId(userId) != laneId)) {
                throw new IllegalArgumentException("account lane contains an incorrectly routed user");
            }
            if (!expectedUsers[laneId].equals(actualUsers)) {
                throw new IllegalArgumentException("account lane user manifest differs from global state");
            }
            present[laneId] = true;
        }
    }

    BooleanSupplier beginCommandRollback(long revisionCheckpoint, long coreSequence) {
        book.assertOwner();
        if (revisionCheckpoint < 0 || coreSequence <= 0)
            throw new IllegalArgumentException("invalid asynchronous rollback");
        if (book.realtimeCapture != null && book.realtimeCapture.active()) book.realtimeCapture.abort();
        long[] pendingOrders = book.pendingReservations.pendingOrderIds(coreSequence);
        OrderFundsReservation.PendingTracker.PendingBatch batch = book.pendingReservations.batch(coreSequence);
        int expectedPending = pendingOrders.length + (batch == null ? 0 : batch.remaining());
        long laneMask = book.accountLanes.length == 64 ? -1L : (1L << book.accountLanes.length) - 1;
        book.dispatchControlLanes(laneMask, id -> {
            AccountPartition lane = book.currentLaneCommandScope();
            int completed = 0;
            for (long orderId : pendingOrders) {
                long userId = book.pendingReservations.pendingUser(orderId);
                if (book.topology.accountLaneId(userId) != id) continue;
                lane.completePendingReservation(orderId, coreSequence);
                completed++;
            }
            if (batch != null && book.topology.accountLaneId(batch.userId()) == id) {
                for (int index = 0; index < batch.count(); index++) {
                    Order order = batch.order(index);
                    if (order == null || lane.pendingReservationSequence(order.orderId()) != coreSequence)
                        continue;
                    lane.completePendingReservation(order.orderId(), coreSequence);
                    completed++;
                }
            }
            book.accountRollback.restoreLane(lane);
            return completed;
        });
        return new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                if (!book.pollControlLanes()) return false;
                int completed = 0;
                for (int id = 0; id < book.accountLanes.length; id++)
                    completed = Math.addExact(completed, (Integer) book.controlLaneResult(id));
                if (completed != expectedPending) throw new IllegalStateException("rollback reservation count mismatch");
                for (long orderId : pendingOrders) {
                    long userId = book.pendingReservations.pendingUser(orderId);
                    book.pendingReservations.unindexPendingReservation(orderId, coreSequence, userId,
                            Math.subtractExact(book.pendingReservations.totalPendingReservations(), 1));
                }
                if (batch != null) book.pendingReservations.completedBatchItems(coreSequence, batch.remaining());
                book.accountRollback.restoreOwner();
                book.rollbackLedgerControl();
                book.ledgerControl = null;
                book.revision = revisionCheckpoint;
                book.clearChangedKeys();
                return true;
            }
        };
    }

    void rollbackActiveCommand(long revisionCheckpoint, long coreSequence) {
        book.assertOwner();
        if (book.realtimeCapture != null && book.realtimeCapture.active()) book.realtimeCapture.abort();
        if (revisionCheckpoint < 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime command rollback checkpoint");
        }
        if (book.pendingReservations.containsSequence(coreSequence)
                || book.pendingReservations.hasBatch(coreSequence)) {
            book.pendingReservations.completePendingReservations(coreSequence);
        }
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLane(laneId, lane -> {
                book.accountRollback.restoreLane(lane);
                return null;
            });
        }
        book.accountRollback.restoreOwner();
        if (book.ledgerControl != null)
            throw new IllegalStateException("active Ledger rollback requires its asynchronous continuation");
        book.systemFunds.rollbackChangedValues();
        book.revision = revisionCheckpoint;
        book.clearChangedKeys();
    }
}
