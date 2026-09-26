package com.surprising.aeron.service.state;
import com.surprising.aeron.service.command.AccountLaneOperationType;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;

import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.service.state.snapshot.*;

import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreMarginMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TradingRuntimeStateTest {

    @Test
    void futureBatchAdmissionDoesNotEnterEarlierCommandsRollbackBuffers() throws Exception {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            int lane = state.topology().accountLaneId(7);
            var changes = new MatcherSettlementChanges(state.topology().accountLaneCount());
            changes.ensureActiveLanes(1L << lane);
            changes.balancePatches[lane].add(7, 3, new BalanceRuntime(7, 3, 1000, 0), 0);
            changes.balancePatches[lane].after(7, 3, new BalanceRuntime(7, 3, 800, 200), 200);
            var admission = new PlaceBatchAdmissionEvent().prepare(2, 7, java.util.UUID.randomUUID(),
                    new ResolvedPlaceOrder[1], new long[1], new boolean[1], new boolean[1],
                    new long[1], new int[1], new int[1],
                    new OrderRuntime[]{CoreStateTestFixtures.order(11, 7, 5, 2)},
                    1, lane, 0, state, changes, null, null, 0, 0);
            var complete = PlaceBatchAdmissionEvent.class.getDeclaredField("completed");
            complete.setAccessible(true);
            complete.setBoolean(admission, true);
            state.registerPlaceBatchAdmission(admission);
            assertThat(state.accountRollback.patchOrdersBeforeByLane[lane].isEmpty()).isTrue();
            assertThat(state.accountRollback.patchBalancesBeforeByLane[lane].size()).isZero();
            // Completing an earlier commit must not require the future batch's after-image.
            state.clearChangedKeys();
            assertThat(state.pendingReservationCount()).isEqualTo(1);
            assertThat(state.publishedAvailableBalances.get(7).get(3)).isEqualTo(1000);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rollbackPublishesRestoredBalanceEvenWhenMutationFailedBeforeWriting(boolean wroteBalance) {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            if (wroteBalance) state.replaceBalance(7, 3, 800, 0);
            else state.accountRollback.captureBalanceBefore(7, 3);
            state.rollbackActiveCommand(0, 1);
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1000);
            assertThat(state.publishedAvailableBalances.get(7).get(3)).isEqualTo(1000);
            state.clearChangedKeys();
        }
    }

    @Test
    void leverageProbeReadsConfiguredValueWithoutChangingTheStoredKeyMap() {
        try (var state = new TradingRuntimeState()) {
            state.putLeverage(new CoreLeverageKey(7, "BTC-USDT", CoreMarginMode.CROSS), 5_000_000L);

            assertThat(state.leverage(7, "BTC-USDT", CoreMarginMode.CROSS)).isEqualTo(5_000_000L);
            assertThat(state.leverage(7, "BTC-USDT", CoreMarginMode.ISOLATED)).isNull();
            assertThat(state.leverage(new CoreLeverageKey(7, "BTC-USDT", CoreMarginMode.CROSS)))
                    .isEqualTo(5_000_000L);
        }
    }

    @Test
    void commitEventStampsOnLanesPublishesOnOwnerAndClearsReusedMetadata() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var state = new TradingRuntimeState(topology)) {
            long second = 8;
            while (topology.accountLaneId(second) == topology.accountLaneId(7)) second++;
            for (long user : new long[]{7, second}) {
                state.putUser(new UserRuntime(user));
                state.putBalance(new BalanceRuntime(user, 3, 1000, 0));
            }
            CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
            CoreStateTestFixtures.reserveOrder(state, 12, second, 92, 5, 2, 3, 200);
            var original = state.order(11);
            state.clearChangedKeys();
            state.startAccountLanes();
            state.enterAsynchronousCommandScope();
            try {
                var first = state.dispatchLaneMutation(1, java.util.List.of(7L, second),
                        java.util.List.of(11L, 12L), 100, 200);
                awaitMetadataCommit(state, first);
                assertThat(state.order(11)).isSameAs(original);
                assertThat(state.ownerLaneAccess).isFalse();
                state.releaseLaneCommit(first);
                assertThat(state.order(11).updatedAtEpochMillis()).isEqualTo(100);
                assertThat(state.order(12).clusterPosition()).isEqualTo(200);
                var next = state.dispatchLaneMutation(2, java.util.List.of(7L),
                        java.util.List.of(11L), 101, 201);
                assertThat(next).isSameAs(first);
                awaitMetadataCommit(state, next);
                state.releaseLaneCommit(next);
                assertThat(state.order(11).clusterPosition()).isEqualTo(201);
                assertThat(state.order(12).clusterPosition()).isEqualTo(200);
                var plain = state.dispatchLaneMutation(3, java.util.List.of(7L, second));
                awaitMetadataCommit(state, plain);
                state.releaseLaneCommit(plain);
                assertThat(state.order(11).clusterPosition()).isEqualTo(201);
                assertThat(state.order(12).clusterPosition()).isEqualTo(200);
            } finally { state.exitAsynchronousCommandScope(); }
        }
    }

    private static void awaitMetadataCommit(TradingRuntimeState state, LaneCommitEvent event) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.laneCommitComplete(event)) {
            if (System.nanoTime() > deadline) throw new AssertionError("metadata commit timed out");
            Thread.onSpinWait();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void asynchronousRollbackRestoresReservedFundsAndBothReservationIndexes(boolean batch) {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
            state.clearChangedKeys();
            state.startAccountLanes();
            for (int index = 0; index < 2; index++)
                CoreStateTestFixtures.reserveOrder(state, 11 + index, 7, 91 + index, 5, 2, 3, 200);
            if (batch) {
                state.onLane(7L, lane -> {
                    lane.markPendingReservation(11, 4);
                    lane.markPendingReservation(12, 4);
                    return null;
                });
                state.pendingReservations.registerBatch(4, 7,
                        new OrderRuntime[]{state.order(11), state.order(12)}, 2);
            } else {
                state.markPendingReservation(7, 11, 4);
                state.markPendingReservation(7, 12, 4);
            }
            state.enterAsynchronousCommandScope();
            try {
                var rollback = state.beginCommandRollback(0, 4);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!rollback.getAsBoolean()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("account rollback timed out");
                    Thread.onSpinWait();
                }
                assertThat(state.ownerLaneAccess).isFalse();
            } finally { state.exitAsynchronousCommandScope(); }
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1000);
            assertThat(state.balance(7, 3).lockedUnits()).isZero();
            assertThat(state.order(11)).isNull();
            assertThat(state.order(12)).isNull();
            assertThat(state.reservation(11)).isNull();
            assertThat(state.orderIdByClient(7, 91)).isNull();
            assertThat(state.pendingReservationCount()).isZero();
            assertThat(state.pendingReservationCount(7)).isZero();
            assertThat(state.pendingReservedUnits(7, 3)).isZero();
        }
    }

    @Test
    void controlLanesPublishRevisionDeltasOnlyWhenOwnerCollects() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var state = new TradingRuntimeState(topology)) {
            state.setMetadata(com.surprising.product.api.ProductLine.SPOT, 10);
            state.startAccountLanes();
            var executed = new java.util.concurrent.CountDownLatch(2);
            state.dispatchControlLanes(3, id -> {
                state.accountLanes[id].assertOwner();
                state.incrementCommandRevision();
                state.incrementCommandRevision();
                executed.countDown();
                return true;
            });
            assertThat(executed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(state.revision()).isEqualTo(10);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!state.pollControlLanes()) {
                if (System.nanoTime() > deadline) throw new AssertionError("control completion timed out");
                Thread.onSpinWait();
            }
            assertThat(state.revision()).isEqualTo(14);
            assertThat(state.ownerLaneAccess).isFalse();
            assertThat(state.controlLaneResult(0)).isEqualTo(true);
            assertThat(state.controlLaneResult(1)).isEqualTo(true);
        }
    }

    @Test
    void promotionPreservesSetOrderWithoutArrayMaterializationAcrossEveryRemoval() {
        var index = new PendingReservationTracker.PendingReservationSequenceIndex(4);
        for (long order = 1; order <= 100; order++) index.add(19, order);
        while (index.containsKey(19)) {
            long first = index.firstOrderBySequence.get(19);
            var additional = index.additionalOrdersBySequence.get(19);
            long expected = additional == null || additional.isEmpty() ? 0 : additional.toArray()[0];
            index.remove(19, first);
            assertThat(index.firstOrderBySequence.getIfAbsent(19, 0)).isEqualTo(expected);
        }
        assertThat(index.additionalOrdersBySequence.isEmpty()).isTrue();
    }

    @Test
    void admissionOnlyPreallocatesWrittenBuffersInItsOwnerLane() throws Exception {
        var constructor = MatcherSettlementChanges.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var changes = constructor.newInstance(4);
        changes.ensureAdmissionCapacity(2, 20);
        Object[] lanes = changes.laneDeltas;
        for (int lane = 0; lane < lanes.length; lane++) {
            for (String name : new String[]{"users", "orders", "reservations", "positions"}) {
                Field bufferField = lanes[lane].getClass().getDeclaredField(name);
                bufferField.setAccessible(true);
                Object buffer = bufferField.get(lanes[lane]);
                Field keys = RuntimeChangeBuffer.class.getDeclaredField("keys");
                keys.setAccessible(true);
                assertThat(((long[]) keys.get(buffer)).length).as("lane %s %s", lane, name)
                        .isEqualTo(lane == 2 && (name.equals("orders") || name.equals("reservations")) ? 32 : 8);
            }
        }
    }

    @Test
    void pendingReservationSequenceIndexKeepsSingleOrderOnPrimitivePath() {
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);

        index.add(101, 1_001);

        assertThat(index.containsKey(101)).isTrue();
        assertThat(index.contains(101, 1_001)).isTrue();
        assertThat(index.orderIds(101)).containsExactly(1_001);

        index.remove(101, 1_001);

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.orderIds(101)).isEmpty();
    }

    @Test
    void pendingReservationSequenceIndexPromotesBatchOrdersWhenFirstCompletes() {
        PendingReservationTracker.PendingReservationSequenceIndex index =
                new PendingReservationTracker.PendingReservationSequenceIndex(4);
        index.add(101, 1_001);
        index.add(101, 1_002);
        index.add(101, 1_003);
        index.add(102, 2_001);

        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_001, 1_002, 1_003);
        assertThatThrownBy(() -> index.add(101, 1_002))
                .isInstanceOf(IllegalStateException.class);

        index.remove(101, 1_001);
        assertThat(index.orderIds(101)).containsExactlyInAnyOrder(1_002, 1_003);
        index.remove(101, 1_002);
        index.remove(101, 1_003);

        assertThat(index.containsKey(101)).isFalse();
        assertThat(index.orderIds(102)).containsExactly(2_001);
        index.clear();
        assertThat(index.isEmpty()).isTrue();
    }

    @Test
    void accountLaneApplyOnlyAdvancesSequenceAndRevision() throws Exception {
        String source = accountLaneSource();

        assertThat(methodSource(source, "void applied", "void requireApply"))
                .contains("appliedSequence", "revision")
                .doesNotContain("transitionHash", "computeStateHash", "computeFundsHash");
        assertThat(source).doesNotContain("record Checkpoint", "void rollback(Checkpoint",
                "pendingApplyCheckpoint", "pendingApplySequence");
        String runtimeSource = Files.readString(Path.of(
                "src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java"));
        assertThat(methodSource(runtimeSource, "public long commandRevisionCheckpoint", "public void beginOrderBatch"))
                .contains("return revision")
                .doesNotContain("onLane(", "new CommandCheckpoint", "AccountLaneState.Checkpoint");
    }

    @Test
    void snapshotCaptureDoesNotRebaseLiveAccountLaneHashes() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.startAccountLanes();
        try {
            LaneValues[] before = laneValues(state);

            state.accountLaneSnapshots(1, TradingCoreState.empty(
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL));

            LaneValues[] after = laneValues(state);
            for (int laneId = 0; laneId < before.length; laneId++) {
                assertThat(after[laneId].localStateHash())
                        .as("snapshot must not mutate lane %s state hash", laneId)
                        .isEqualTo(before[laneId].localStateHash());
                assertThat(after[laneId].localFundsHash())
                        .as("snapshot must not mutate lane %s funds hash", laneId)
                        .isEqualTo(before[laneId].localFundsHash());
            }
        } finally {
            state.close();
        }
    }

    @Test
    void riskSchedulerSelectsLeastRecentlyServedWorkRegardlessOfUserProgress() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putRiskScan(incompleteRiskScan(1, 0).withLastScheduledRevision(20));
        state.putRiskScan(incompleteRiskScan(2, 900).withLastScheduledRevision(10));

        assertThat(state.firstRiskIncompleteScan().symbolId())
                .as("a low user cursor must not repeatedly preempt older work")
                .isEqualTo(2);
        state.putRiskScan(state.riskScan(2).withLastScheduledRevision(30));
        assertThat(state.firstRiskIncompleteScan().symbolId()).isEqualTo(1);
    }

    @Test
    void keepsHotIndexesFlatAndTracksChangedKeys() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        BalanceRuntime balance = new BalanceRuntime(7, 3, 1_000, 0);
        state.putBalance(balance);
        state.putOrder(CoreStateTestFixtures.order(11, 7, 5, 2));
        state.putReservation(CoreStateTestFixtures.reservation(11, 7, 3, 200));
        state.putClientOrder(7, 91, 11);

        assertThat(state.user(7).userId()).isEqualTo(7);
        assertThat(state.balance(7, 3)).isNotSameAs(balance);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
        assertThat(state.order(11).symbolId()).isEqualTo(5);
        assertThat(state.reservation(11).reservedUnits()).isEqualTo(200);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
        assertThat(state.changedUsers().contains(7L)).isTrue();
        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        assertThat(state.changedOrders().contains(11L)).isTrue();
        assertThat(state.changedReservations().contains(11L)).isTrue();
    }

    @Test
    void discardsExpandedCaptureMapsAfterLargeCommands() {
        ConcurrentHashMap<Long, Long> expanded = new ConcurrentHashMap<>();
        for (long key = 0; key < 512; key++) expanded.put(key, key);

        ConcurrentHashMap<Long, Long> compacted = RuntimeGlobalRollback.clearCaptured(expanded);
        assertThat(compacted).isEmpty();
        assertThat(compacted).isNotSameAs(expanded);

        compacted.put(1L, 1L);
        ConcurrentHashMap<Long, Long> reused = RuntimeGlobalRollback.clearCaptured(compacted);
        assertThat(reused).isEmpty();
        assertThat(reused).isSameAs(compacted);
    }

    @Test
    void protectsSingleWriterBoundary() throws InterruptedException {
        TradingRuntimeState state = new TradingRuntimeState();
        state.bindOwner();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                state.assertOwner();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        other.start();
        other.join();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnedBalanceIsDetachedFromTheLaneOwnedAuthoritativeBalance() throws InterruptedException {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long userId = userForLane(topology, 0);
        state.putUser(new UserRuntime(userId));
        state.putBalance(new BalanceRuntime(userId, 3, 1_000, 0));
        BalanceRuntime retained = state.balance(userId, 3);
        Thread other = new Thread(() -> retained.credit(1));
        other.start();
        other.join();

        assertThat(retained.availableUnits()).isEqualTo(1_001);
        assertThat(state.balance(userId, 3).availableUnits()).isEqualTo(1_000);
    }

    @Test
    void retainedTreasuryReferenceStillEnforcesRuntimeOwner() throws InterruptedException {
        TradingRuntimeState state = new TradingRuntimeState();
        TreasuryRuntime treasury = state.treasury();
        treasury.setFee(3, 7);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                treasury.setFee(3, 8);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        other.start();
        other.join();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(treasury.fee(3)).isEqualTo(7);
    }

    @Test
    void identityRegistryRejectsCrossThreadMutation() throws InterruptedException {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        identities.assetId("USDT");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                identities.assetId("BTC");
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        other.start();
        other.join();

        assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reservesAndReleasesWithoutOverflow() {
        BalanceRuntime balance = new BalanceRuntime(7, 3, 1_000, 0);
        balance.reserve(250);
        assertThat(balance.availableUnits()).isEqualTo(750);
        assertThat(balance.lockedUnits()).isEqualTo(250);
        balance.release(100);
        assertThat(balance.availableUnits()).isEqualTo(850);
        assertThat(balance.lockedUnits()).isEqualTo(150);
        assertThatThrownBy(() -> balance.reserve(851)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedReserveDoesNotPartiallyChangeBalance() {
        BalanceRuntime balance = new BalanceRuntime(7, 3, 100, Long.MAX_VALUE);

        assertThatThrownBy(() -> balance.reserve(1)).isInstanceOf(ArithmeticException.class);
        assertThat(balance.availableUnits()).isEqualTo(100);
        assertThat(balance.lockedUnits()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void reservesOrderAndFundsAsOneRuntimeTransition() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));

        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
        assertThat(state.order(11).quantitySteps()).isEqualTo(2);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
    }

    @Test
    void rejectedReservationCompletionPreservesCountersAndCanCompleteLater() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putReservation(CoreStateTestFixtures.reservation(11, 7, 3, 200));
        state.markPendingReservation(7, 11, 4);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> state.completePendingReservation(7, 11, 5));
        assertThat(state.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(7, 3)).isEqualTo(200);

        state.completePendingReservation(7, 11, 4);
        assertThat(state.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservedUnits(7, 3)).isZero();
    }

    @Test
    void reservationCompletionUsesTheOrderToClientReverseIndex() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);
        state.clearChangedKeys();

        state.completePendingReservation(7, 11, 4);

        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
    }

    @Test
    void removingAClientOrderAlsoRemovesItsReverseIndexEntry() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);
        state.removeClientOrder(7, 91);
        state.clearChangedKeys();

        state.completePendingReservation(7, 11, 4);

        assertThat(state.orderIdByClient(7, 91)).isNull();
    }

    @Test
    void reassigningAliasesDoesNotLeaveStaleReverseMappings() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        CoreStateTestFixtures.reserveOrder(state, 12, 7, 92, 5, 2, 3, 200);
        state.putClientOrder(7, 93, 11);
        state.putClientOrder(7, 94, 11);
        state.putClientOrder(7, 93, 12);
        state.removeClientOrder(7, 91);
        state.removeClientOrder(7, 94);
        state.markPendingReservation(7, 11, 4);
        state.completePendingReservation(7, 11, 4);
        assertThat(state.orderIdByClient(7, 91)).isNull();
        assertThat(state.orderIdByClient(7, 94)).isNull();
        assertThat(state.orderIdByClient(7, 92)).isEqualTo(12);
        assertThat(state.orderIdByClient(7, 93)).isEqualTo(12);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(600);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(400);
    }

    @Test
    void pendingReservationCountersTrackUsersAndAssetsInConstantTime() throws Exception {
        // Given: 10,000 pending reservations spread across 100 users and 10 assets.
        TradingRuntimeState state = new TradingRuntimeState();
        long orderId = 1;
        for (long userId = 1; userId <= 100; userId++) {
            state.putUser(new UserRuntime(userId));
            for (int assetId = 1; assetId <= 10; assetId++) {
                for (int reservation = 0; reservation < 10; reservation++) {
                    state.putReservation(CoreStateTestFixtures.reservation(orderId, userId, assetId, assetId));
                    state.markPendingReservation(userId, orderId, 1);
                    orderId++;
                }
            }
        }

        // When: each user and user/asset counter is read directly.
        for (long userId = 1; userId <= 100; userId++) {
            assertThat(state.pendingReservationCount(userId)).isEqualTo(100);
            for (int assetId = 1; assetId <= 10; assetId++) {
                assertThat(state.pendingReservedUnits(userId, assetId)).isEqualTo(10L * assetId);
            }
        }

        // Then: completion removes every lane and global pending total without changing reservations.
        state.completePendingReservations(1);
        assertThat(state.pendingReservationCount()).isZero();
        assertThat(state.hasPendingReservations()).isFalse();
        for (long userId = 1; userId <= 100; userId++) {
            assertThat(state.pendingReservationCount(userId)).isZero();
            for (int assetId = 1; assetId <= 10; assetId++) {
                assertThat(state.pendingReservedUnits(userId, assetId)).isZero();
            }
        }

        String source = accountLaneSource();
        assertThat(methodSource(source, "long pendingReservedUnits", "int pendingReservationCount"))
                .doesNotContain("forEach", "->", "pendingReservationSequences");
        assertThat(methodSource(source, "int pendingReservationCount", "boolean hasPendingReservations"))
                .doesNotContain("forEach", "->", "pendingReservationSequences");
    }

    @Test
    void duplicateReservationCompletionFailsWithoutCounterDrift() {
        // Given: one marked reservation with locked funds.
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // When: the reservation is completed once and completion is retried.
        state.completePendingReservation(7, 11, 4);
        long revisionAfterFirstCompletion = state.revision();
        BalanceRuntime balanceAfterFirstCompletion = state.balance(7, 3);
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);

        // Then: the failed retry leaves counters, funds, revision, and lane hashes unchanged.
        assertThat(state.pendingReservationCount()).isZero();
        assertThat(state.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservedUnits(7, 3)).isZero();
        assertThat(state.balance(7, 3).availableUnits())
                .isEqualTo(balanceAfterFirstCompletion.availableUnits());
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(balanceAfterFirstCompletion.lockedUnits());
        assertThat(state.revision()).isEqualTo(revisionAfterFirstCompletion);
    }

    @Test
    void duplicateMarkAndMissingCompletionFailWithoutCounterDrift() {
        // Given: one reservation that is marked pending exactly once.
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // When: the mark is duplicated and completion uses a sequence that was never marked.
        assertThatThrownBy(() -> state.markPendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 5))
                .isInstanceOf(IllegalStateException.class);

        // Then: neither failed command changes pending counters or locked funds.
        assertThat(state.pendingReservationCount()).isEqualTo(1);
        assertThat(state.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(7, 3)).isEqualTo(200);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    @Test
    void pendingReservationReplacementAndRestoreKeepTransientCountersExact() {
        // Given: a snapshot fence without in-flight reservations.
        TradingRuntimeState state = new TradingRuntimeState();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int assetId = identities.assetId("USDT");
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, assetId, 1_000, 0));
        TradingCoreState global = RuntimeStateMaterializer.materialize(state, identities);
        var snapshots = state.accountLaneSnapshots(1, global);
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, assetId, 200);
        state.markPendingReservation(7, 11, 2);

        // When: a pending reservation is partially released, then the pre-pending snapshot is restored.
        state.replaceReservation(state.reservation(11).release(50));
        assertThat(state.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(7, assetId)).isEqualTo(150);
        assertThatThrownBy(() -> state.accountLaneSnapshots(2, global))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending reservation");
        state.restoreAccountLaneSnapshots(snapshots, 1, global);

        // Then: no transient pending reservation or counter crosses the restored snapshot fence.
        assertThat(state.pendingReservationCount()).isZero();
        assertThat(state.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservedUnits(7, assetId)).isZero();
        assertThat(state.hasPendingReservations()).isFalse();
        assertThat(state.balance(7, assetId).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, assetId).lockedUnits()).isEqualTo(200);
    }

    @Test
    void pendingReservationConsumptionAndCancellationKeepCountersAndFundsExact() {
        // Given: a pending reservation with locked funds.
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // When: the reservation is partially consumed and then canceled.
        state.replaceReservation(state.reservation(11).consume(50));
        state.replaceBalance(new BalanceRuntime(7, 3, 800, 150));
        assertThat(state.pendingReservedUnits(7, 3)).isEqualTo(150);
        state.cancelOrder(11, 7, 150);

        // Then: the counter reaches zero while the balance release remains exact.
        assertThat(state.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(7, 3)).isZero();
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(950);
        assertThat(state.balance(7, 3).lockedUnits()).isZero();
    }

    @Test
    void rejectsPendingReservationRemovalWithoutCounterOrFundsDrift() {
        // Given: a marked reservation with funds locked in its owner lane.
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // When: a caller attempts to remove it before lifecycle completion.
        assertThatThrownBy(() -> state.removeReservation(11, 7)).isInstanceOf(IllegalStateException.class);

        // Then: the reservation, pending counters, and locked funds remain unchanged.
        assertThat(state.reservation(11)).isNotNull();
        assertThat(state.pendingReservationCount()).isEqualTo(1);
        assertThat(state.pendingReservationCount(7)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(7, 3)).isEqualTo(200);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    @Test
    void laneCounterOverflowFailsBeforePendingStateChanges() {
        // Given: an account lane with one maximum-unit pending reservation.
        AccountLaneState lane = new AccountLaneState(0, 8);
        lane.reservations.put(1, CoreStateTestFixtures.reservation(1, 7, 3, Long.MAX_VALUE));
        lane.markPendingReservation(1, 1);
        lane.reservations.put(2, CoreStateTestFixtures.reservation(2, 7, 3, 1));

        // When: the next mark would overflow reserved units.
        assertThatThrownBy(() -> lane.markPendingReservation(2, 1)).isInstanceOf(ArithmeticException.class);

        // Then: the failed mark leaves the original pending reservation and counters intact.
        assertThat(lane.pendingReservation(1)).isTrue();
        assertThat(lane.pendingReservation(2)).isFalse();
        assertThat(lane.pendingReservationCount(7)).isEqualTo(1);
        assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void globalPendingCounterUnderflowFailsBeforeLaneStateChanges() {
        // Given: a runtime with no pending reservations.
        TradingRuntimeState state = new TradingRuntimeState();

        // When: a completion is attempted without a global pending reservation.
        assertThatThrownBy(() -> state.completePendingReservation(7, 11, 4))
                .isInstanceOf(IllegalStateException.class);

        // Then: global and per-user counters remain at zero.
        assertThat(state.pendingReservationCount()).isZero();
        assertThat(state.pendingReservationCount(7)).isZero();
        assertThat(state.pendingReservedUnits(7, 3)).isZero();
    }

    @Test
    void pendingReservationBatchRejectsLaterLaneBeforeEarlierLaneDrifts() throws Exception {
        // Given: pending reservations owned by two distinct account lanes.
        LaneTopology topology = LaneTopology.productionDefault();
        long firstUser = userForLane(topology, 0);
        long laterUser = userForLane(topology, 1);
        TradingRuntimeState state = new TradingRuntimeState(topology);
        state.putUser(new UserRuntime(firstUser));
        state.putUser(new UserRuntime(laterUser));
        state.putBalance(new BalanceRuntime(firstUser, 3, 1_000, 0));
        state.putBalance(new BalanceRuntime(laterUser, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, firstUser, 91, 5, 2, 3, 200);
        CoreStateTestFixtures.reserveOrder(state, 12, laterUser, 92, 5, 2, 3, 200);
        state.markPendingReservation(firstUser, 11, 4);
        state.markPendingReservation(laterUser, 12, 4);
        ReservationRuntime firstReservation = state.reservation(11);
        BalanceRuntime firstBalance = state.balance(firstUser, 3);
        int firstLaneId = topology.accountLaneId(firstUser);
        LaneValues firstLane = laneValues(state, firstLaneId);
        long[] pendingOrderIds = pendingReservationOrderIds(state, 4);
        long firstPendingOwner = pendingReservationOwner(state, 11);
        long laterPendingOwner = pendingReservationOwner(state, 12);
        accountLaneState(state, topology.accountLaneId(laterUser)).reservations.remove(12);

        // When: preflight reaches the missing reservation in the later lane.
        assertThatThrownBy(() -> state.completePendingReservations(4)).isInstanceOf(IllegalStateException.class);

        // Then: neither the earlier lane nor either global pending index has drifted.
        assertThat(state.reservation(11)).isEqualTo(firstReservation);
        assertThat(state.pendingReservationCount()).isEqualTo(2);
        assertThat(state.pendingReservationCount(firstUser)).isEqualTo(1);
        assertThat(state.pendingReservationCount(laterUser)).isEqualTo(1);
        assertThat(state.pendingReservedUnits(firstUser, 3)).isEqualTo(200);
        assertThat(state.pendingReservation(11, firstUser)).isTrue();
        assertThat(state.pendingReservation(12, laterUser)).isTrue();
        assertThat(pendingReservationOrderIds(state, 4)).containsExactlyInAnyOrder(pendingOrderIds);
        assertThat(pendingReservationOwner(state, 11)).isEqualTo(firstPendingOwner);
        assertThat(pendingReservationOwner(state, 12)).isEqualTo(laterPendingOwner);
        assertThat(state.balance(firstUser, 3).availableUnits()).isEqualTo(firstBalance.availableUnits());
        assertThat(state.balance(firstUser, 3).lockedUnits()).isEqualTo(firstBalance.lockedUnits());
        LaneValues firstLaneAfterFailure = laneValues(state, firstLaneId);
        assertThat(firstLaneAfterFailure.revision()).isEqualTo(firstLane.revision());
        assertThat(firstLaneAfterFailure.localStateHash()).isEqualTo(firstLane.localStateHash());
        assertThat(firstLaneAfterFailure.localFundsHash()).isEqualTo(firstLane.localFundsHash());
    }

    @Test
    void rejectsDuplicateOrderAndClientWithoutChangingFunds() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 11, 7, 92, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 12, 7, 91, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    @Test
    void insufficientFundsRejectsBeforeCreatingRuntimeEntities() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 100, 0));

        assertThatThrownBy(() -> CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.order(11)).isNull();
        assertThat(state.reservation(11)).isNull();
        assertThat(state.orderIdByClient(7, 91)).isNull();
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(100);
        assertThat(state.balance(7, 3).lockedUnits()).isZero();
    }

    @Test
    void doesNotUseCompositeBalanceKeys() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.putBalance(new BalanceRuntime(7, 4, 2_000, 0));

        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        state.clearChangedKeys();
        assertThat(state.publishedAvailableBalances.get(7).get(3)).isEqualTo(1_000);
        assertThat(state.publishedAvailableBalances.get(7).get(4)).isEqualTo(2_000);
        assertThat(state.snapshotProjectionStateDirty()).isFalse();
        state.markBalancesChanged();
        assertThat(state.snapshotProjectionStateDirty()).isTrue();
        state.clearChangedKeys();
        assertThat(state.snapshotProjectionStateDirty()).isFalse();
        state.close();
    }

    @Test
    void capturesDeterministicImmutableSnapshot() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);

        TradingRuntimeSnapshot snapshot = RuntimeSnapshotBuilder.capture(state, 4);

        assertThat(snapshot.revision()).isEqualTo(4);
        assertThat(snapshot.totalAvailableUnits()).isEqualTo(800);
        assertThat(snapshot.totalLockedUnits()).isEqualTo(200);
        assertThat(snapshot.orders()).containsKey(11L);
        assertThatThrownBy(() -> snapshot.orders().put(12L,
                snapshot.orders().get(11L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotIncludesPositionsAndTreasury() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.putPosition(9, new PositionRuntime(7, 5, 3,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 2, 100, 200, 0, 40));
        state.treasury().setFee(3, 7);
        state.treasury().setInsurance(3, 11, 0);

        TradingRuntimeSnapshot snapshot = RuntimeSnapshotBuilder.capture(state, 5);

        assertThat(snapshot.positions()).containsKey(new TradingRuntimeSnapshot.PositionKey(7, 9));
        assertThat(snapshot.positions().get(new TradingRuntimeSnapshot.PositionKey(7, 9)).signedQuantitySteps())
                .isEqualTo(2);
        assertThat(snapshot.treasury().get(3).feeUnits()).isEqualTo(7);
        assertThat(snapshot.treasury().get(3).insuranceUnits()).isEqualTo(11);
        assertThatThrownBy(() -> snapshot.positions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void indexesActiveLiquidationByExactPositionScope() {
        TradingRuntimeState state = new TradingRuntimeState();
        LiquidationRuntime planned = new LiquidationRuntime(1, 7, 5,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2, 0, 0, 0, 0,
                CoreLiquidationState.Status.PLANNED, 0);
        state.putLiquidation(planned);

        assertThat(state.activeLiquidation(7, 5,
                com.surprising.aeron.protocol.CorePositionSide.NET)).isSameAs(planned);

        LiquidationRuntime canceled = new LiquidationRuntime(1, 7, 5,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 9, 2, 2, 0, 0, 0, 0,
                CoreLiquidationState.Status.CANCELED, 0);
        state.replaceLiquidation(canceled);

        assertThat(state.activeLiquidation(7, 5,
                com.surprising.aeron.protocol.CorePositionSide.NET)).isNull();
    }

    @Test
    void routesStateAndReadFencesThroughThePermanentLaneOwner() {
        TradingRuntimeState state = new TradingRuntimeState(LaneTopology.characterization());
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.startAccountLanes();
        try {
            var apply = state.stageLaneMutation(1, java.util.List.of(7L));
            assertThat(apply).isEqualTo(1L << LaneTopology.characterization().accountLaneId(7));
            state.readFence(7, 1);

            LaneValues lane = laneValues(state, state.topology().accountLaneId(7));
            assertThat(lane.appliedSequence()).isEqualTo(1);
            assertThat(lane.committedSequence()).isEqualTo(1);
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
        } finally {
            state.close();
        }
    }

    @Test
    void crossLaneReadFenceDoesNotPartiallyAdvanceBeforeEveryLaneIsReady() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long userInLastLane = userForLane(topology, topology.accountLaneCount() - 1);
        state.putUser(new UserRuntime(userInLastLane));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(userInLastLane));
            state.readFenceAll(1);
            assertThat(laneValues(state, 0).committedSequence()).isEqualTo(1);
        } finally {
            state.close();
        }
    }

    @Test
    void laneApplyPublishesCommittedWatermarkInTheSameOwnerTask() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long laneZeroUser = userForLane(topology, 0);
        long laneOneUser = userForLane(topology, 1);
        state.putUser(new UserRuntime(laneZeroUser));
        state.putUser(new UserRuntime(laneOneUser));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(laneZeroUser, laneOneUser));

            assertThat(laneValues(state, 0).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 1).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 0).committedSequence()).isEqualTo(1);
            assertThat(laneValues(state, 1).committedSequence()).isEqualTo(1);
            state.readFence(laneZeroUser, 1);
        } finally {
            state.close();
        }
    }

    @Test
    void laneCompletionMaskCoversAllFourLanesAndTheEmptyApply() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        java.util.List<Long> users = new java.util.ArrayList<>();
        for (int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            long userId = userForLane(topology, laneId);
            users.add(userId);
            state.putUser(new UserRuntime(userId));
        }
        state.clearChangedKeys();
        long committedLaneMask = state.stageLaneMutation(1, users);

        assertThat(committedLaneMask).isEqualTo(0b1111);
        for (int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            assertThat(laneValues(state, laneId).appliedSequence()).isEqualTo(1);
            assertThat(laneValues(state, laneId).committedSequence()).isEqualTo(1);
        }
        state.clearChangedKeys();
        assertThat(state.stageLaneMutation(2, java.util.List.of())).isZero();
    }

    @Test
    void sameCommandPositionRemovalRetainsTypedOpenBeforeAndMarginMode() {
        TradingRuntimeState state = new TradingRuntimeState();
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        int symbolId = identities.symbolId("BTC-USDT");
        int assetId = identities.assetId("USDT");
        long positionKey = identities.positionKey(7, "BTC-USDT:NET");
        PositionRuntime open = new PositionRuntime(7, symbolId, assetId,
                com.surprising.aeron.protocol.CoreMarginMode.ISOLATED,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), 2, 100, 200, 0, 40);
        state.putPosition(positionKey, open);
        state.clearChangedKeys();

        state.removePosition(positionKey, 7);

        assertThat(state.currentPatchPositionBefore(positionKey)).isSameAs(open);
        assertThat(state.currentPatchPositionBefore(positionKey)).isSameAs(open);
    }

    @Test
    void stagedMutationRejectsAnOlderGlobalSequenceWithoutChangingAnyLane() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long laneZeroUser = userForLane(topology, 0);
        long laneOneUser = userForLane(topology, 1);
        state.putUser(new UserRuntime(laneZeroUser));
        state.putUser(new UserRuntime(laneOneUser));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(2, java.util.List.of(laneZeroUser));
            state.clearChangedKeys();
            state.stageLaneMutation(1, java.util.List.of(laneOneUser));
            state.clearChangedKeys();
            LaneValues[] beforeFailure = laneValues(state);
            long revisionBeforeFailure = state.revision();

            assertThatThrownBy(() -> state.stageLaneMutation(
                    1, java.util.List.of(laneZeroUser, laneOneUser)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("out of order");
            LaneValues[] afterFailure = laneValues(state);
            for (int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
                assertThat(afterFailure[laneId].revision()).isEqualTo(beforeFailure[laneId].revision());
                assertThat(afterFailure[laneId].appliedSequence())
                        .isEqualTo(beforeFailure[laneId].appliedSequence());
                assertThat(afterFailure[laneId].committedSequence())
                        .isEqualTo(beforeFailure[laneId].committedSequence());
                assertThat(afterFailure[laneId].localStateHash())
                        .isEqualTo(beforeFailure[laneId].localStateHash());
                assertThat(afterFailure[laneId].localFundsHash())
                        .isEqualTo(beforeFailure[laneId].localFundsHash());
            }
            assertThat(state.revision()).isEqualTo(revisionBeforeFailure);
            assertThat(state.changedUsers().isEmpty()).isTrue();
            assertThat(state.executeUserSettlement(laneOneUser, () -> "apply-reclaimed"))
                    .isEqualTo("apply-reclaimed");
        } finally {
            state.close();
        }
    }

    @Test
    void accountLanePublishesEachConsecutiveSequenceWithoutASecondCommitTask() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long userId = userForLane(topology, 0);
        state.putUser(new UserRuntime(userId));
        state.startAccountLanes();
        try {
            state.stageLaneMutation(1, java.util.List.of(userId));
            state.stageLaneMutation(2, java.util.List.of(userId));

            LaneValues applied = laneValues(state, 0);
            assertThat(applied.appliedSequence()).isEqualTo(2);
            assertThat(applied.committedSequence()).isEqualTo(2);
        } finally {
            state.close();
        }
    }

    @Test
    void keepsTwoHundredFiftySixSequenceLocalLaneCommitsInFlight() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long[] users = new long[topology.accountLaneCount()];
        for (int laneId = 0; laneId < users.length; laneId++) {
            users[laneId] = userForLane(topology, laneId);
            state.putUser(new UserRuntime(users[laneId]));
        }
        state.startAccountLanes();
        try {
            LaneCommitEvent[] commits = new LaneCommitEvent[256];
            for (int index = 0; index < commits.length; index++) {
                commits[index] = state.dispatchLaneMutation(index + 1L, users);
            }
            for (LaneCommitEvent commit : commits) {
                while (!state.laneCommitComplete(commit)) Thread.onSpinWait();
                assertThat(commit.completedLaneMask()).isEqualTo(commit.requiredLaneMask());
                state.releaseLaneCommit(commit);
            }
            for (int laneId = 0; laneId < users.length; laneId++) {
                assertThat(laneValues(state, laneId).committedSequence()).isEqualTo(256);
            }
        } finally {
            state.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationReturnsBorrowedOwnershipAndRunsOnTheLane(boolean batch) throws Exception {
        TradingRuntimeState state = new TradingRuntimeState(LaneTopology.productionDefault());
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        long userId = 1, orderId = 10_000;
        state.putUser(new UserRuntime(userId));
        state.putBalance(new BalanceRuntime(userId, 3, 2, 0));
        CoreStateTestFixtures.reserveOrder(state, orderId, userId, identities.clientKey(userId, "cancel"), 5, 1, 3, 1);
        state.clearChangedKeys();
        state.startAccountLanes();
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!state.tryAcquireOwnerLaneAccess()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("ownership timeout");
                Thread.yield();
            }
            int laneId = state.topology().accountLaneId(userId);
            var entered = new java.util.concurrent.CountDownLatch(1);
            state.laneWorkers[laneId].submit(lane -> {
                entered.countDown();
                try {
                    if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("Lane release timeout");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            LaneCancelEvent event = batch
                    ? state.dispatchCancelBatch(1, userId, new long[]{orderId}, 1, 1, identities)
                    : state.dispatchCancel(1, userId, orderId, 1, 1, identities);
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // A borrowed Owner must not bypass the Lane queue and mutate the account inline.
            assertThat(event.complete()).isFalse();
            release.countDown();
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!event.complete()) {
                state.assertAccountLanesHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("cancel timeout");
                Thread.yield();
            }
            state.collectCancel(event, null, null);
            state.releaseCancel(event);
            assertThat(state.balance(userId, 3).availableUnits()).isEqualTo(2);
            assertThat(state.balance(userId, 3).lockedUnits()).isZero();
            assertThat(state.order(orderId)).isNull();
            assertThat(state.reservation(orderId)).isNull();
        } finally {
            release.countDown();
            state.releaseOwnerLaneAccess();
            state.close();
        }
    }

    @Test
    void keepsTwoHundredFiftySixLaneOwnedCancellationsInFlightWithoutOwnerWaits() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        LaneCancelEvent[] cancellations = new LaneCancelEvent[256];
        for (int index = 0; index < cancellations.length; index++) {
            long userId = index + 1L;
            long orderId = 10_000L + index;
            long clientKey = identities.clientKey(userId, "cancel-" + index);
            state.putUser(new UserRuntime(userId));
            state.putBalance(new BalanceRuntime(userId, 3, 2, 0));
            CoreStateTestFixtures.reserveOrder(state, orderId, userId, clientKey, 5, 1, 3, 1);
        }
        state.clearChangedKeys();
        state.startAccountLanes();
        try {
            for (int index = 0; index < cancellations.length; index++) {
                cancellations[index] = state.dispatchCancel(
                        index + 1L, index + 1L, 10_000L + index, 1_000L + index, index + 1L,
                        identities);
            }
            for (int index = 0; index < cancellations.length; index++) {
                LaneCancelEvent event = cancellations[index];
                while (!event.complete()) Thread.onSpinWait();
                state.collectCancel(event, null, null);
                state.releaseCancel(event);
                assertThat(state.balance(index + 1L, 3).availableUnits()).isEqualTo(2);
                assertThat(state.balance(index + 1L, 3).lockedUnits()).isZero();
                assertThat(state.order(10_000L + index)).isNull();
                assertThat(state.reservation(10_000L + index)).isNull();
            }
            assertThat(identities.snapshot().clientKeys()).isEmpty();
        } finally {
            state.close();
        }
    }

    @Test
    void invalidSnapshotSetDoesNotPartiallyRestoreEarlierLanes() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingCoreState global = RuntimeStateMaterializer.materialize(state, identities);
        java.util.List<AccountLaneSnapshot> snapshots = state.accountLaneSnapshots(1, global);
        long laneZeroUser = userForLane(topology, 0);
        state.putUser(new UserRuntime(laneZeroUser));
        state.stageLaneMutation(2, java.util.List.of(laneZeroUser));
        LaneValues beforeRestore = laneValues(state, 0);

        java.util.List<AccountLaneSnapshot> invalid = new java.util.ArrayList<>(snapshots);
        AccountLaneSnapshot corrupted = snapshots.get(1);
        invalid.set(1, new AccountLaneSnapshot(corrupted.laneId(), corrupted.revision(),
                corrupted.appliedSequence(), corrupted.committedSequence(),
                corrupted.localStateHash(), corrupted.localFundsHash(), java.util.List.of(laneZeroUser)));

        assertThatThrownBy(() -> state.restoreAccountLaneSnapshots(invalid, 1, global))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incorrectly routed user");
        LaneValues afterFailure = laneValues(state, 0);
        assertThat(afterFailure.appliedSequence()).isEqualTo(beforeRestore.appliedSequence());
        assertThat(afterFailure.committedSequence()).isEqualTo(beforeRestore.committedSequence());
        assertThat(afterFailure.localStateHash()).isEqualTo(beforeRestore.localStateHash());
    }

    @Test
    void lifecycleFailureReclaimsEverySubmittedOwnerTicket() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.startAccountLanes();
        try {
            assertThatThrownBy(() -> state.executeLifecycleSettlements(
                    java.util.List.of(203L, 8L), Long::longValue, laneId -> {
                if (laneId == 0) throw new IllegalStateException("injected lifecycle lane failure");
                return laneId;
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("injected lifecycle lane failure");

            assertThat(state.executeUserSettlement(8, () -> "reclaimed")).isEqualTo("reclaimed");
        } finally {
            state.close();
        }
    }

    @Test
    void lifecycleSettlementUsesPermanentIndependentLaneOwners() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        java.util.List<Long> users = new java.util.ArrayList<>();
        java.util.List<Long> revisions = new java.util.ArrayList<>();
        for (int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            long userId = userForLane(topology, laneId);
            users.add(userId);
            UserRuntime user = new UserRuntime(userId);
            revisions.add(user.revision());
            state.putUser(user);
            state.putBalance(new BalanceRuntime(userId, 3, 1_000 + laneId, 0));
        }
        state.startAccountLanes();
        try {
            Object[] owners = state.executeLifecycleSettlements(users, Long::longValue,
                    laneId -> {
                        state.advanceUserRevision(users.get(laneId));
                        assertThat(state.balance(users.get(laneId), 3).availableUnits())
                                .isEqualTo(1_000 + laneId);
                        return Thread.currentThread().getName();
                    });

            for (int laneId = 0; laneId < owners.length; laneId++) {
                assertThat(owners[laneId]).isEqualTo("core-account-lane-" + laneId);
                assertThat(state.user(users.get(laneId)).revision()).isEqualTo(revisions.get(laneId) + 1);
            }
            assertThat(state.executeUserSettlement(users.getFirst(), () -> "core-owner"))
                    .isEqualTo("core-owner");
        } finally {
            state.close();
        }
    }

    @Test
    void preparedReservationInstallsExactValuesAndRejectsBeforeFreezing() {
        try (TradingRuntimeState state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
            OrderRuntime order = CoreStateTestFixtures.order(11, 7, 5, 2);
            ReservationRuntime reservation = new ReservationRuntime(11, 7, 5,
                    com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN, 3, 200, 0, 0, 2);
            state.startAccountLanes();
            state.reserveOrder(order, reservation, 91);
            assertThat(state.order(11)).isSameAs(order);
            assertThat(state.reservation(11)).isSameAs(reservation);
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
            assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
            assertThatThrownBy(() -> state.reserveOrder(order, reservation, 92))
                    .isInstanceOf(IllegalArgumentException.class);
            var next = CoreStateTestFixtures.order(12, 7, 5, 2);
            var nextReservation = new ReservationRuntime(12, 7, 5,
                    com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN, 3, 200, 0, 0, 2);
            assertThatThrownBy(() -> state.reserveOrder(next, nextReservation, 91))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(state.order(12)).isNull();
            assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
            state.cancelOrder(11, 7, 200);
            assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
            assertThat(state.balance(7, 3).lockedUnits()).isZero();
        }
    }

    @Test
    void directMetricsReadMatchesLaneCountersWithIndependentOwners() {
        LaneTopology topology = LaneTopology.productionDefault();
        try (TradingRuntimeState state = new TradingRuntimeState(topology)) {
            for (int lane = 0; lane < topology.accountLaneCount(); lane++) {
                state.putUser(new UserRuntime(userForLane(topology, lane)));
            }
            state.startAccountLanes();
            for (int lane = 0; lane < topology.accountLaneCount(); lane++) {
                long user = userForLane(topology, lane);
                state.executeUserSettlement(user, () -> { state.advanceUserRevision(user); return null; });
            }
            var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                    1, topology.accountLaneCount(), 0, 16, 0, 0, 16, 0, 0, 16, 0, 0);
            for (int lane = 0; lane < topology.accountLaneCount(); lane++) state.writeAccountLaneMetrics(lane, encoder);
            var decoded = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(encoder.finish());
            for (int lane = 0; lane < topology.accountLaneCount(); lane++) {
                var view = laneValues(state, lane);
                assertThat(decoded.accountLaneRevisions()[lane]).isEqualTo(view.revision());
                assertThat(decoded.accountLaneAppliedSequences()[lane]).isEqualTo(view.appliedSequence());
                assertThat(decoded.accountLaneCommittedSequences()[lane]).isEqualTo(view.committedSequence());
                int offset = lane * 4;
                int settlementIndex = offset + AccountLaneOperationType.SETTLEMENT.ordinal();
                assertThat(decoded.accountLaneCompletedOperations()[settlementIndex]).isEqualTo(1);
                assertThat(decoded.accountLaneLatencySamples()[settlementIndex]).isEqualTo(1);
            }
        }
    }

    @Test
    void activeOrderMembershipSurvivesUpdatesGrowthAndTerminalRemoval() {
        AccountLaneState lane = new AccountLaneState(0, 16);
        for (long id = 1; id <= 100; id++) lane.putOrder(CoreStateTestFixtures.order(id, 7, 5, 2));
        var membership = lane.activeOrderIdsByUser.get(7);
        for (long id = 1; id <= 100; id++) {
            OrderRuntime order = lane.orders.get(id);
            lane.putOrder(order.withExecution(1, 1, CoreOrderStatus.OPEN, 2));
            assertThat(lane.activeOrderIdsByUser.get(7)).isSameAs(membership);
        }
        assertThat(membership.size()).isEqualTo(100);
        for (long id = 1; id <= 100; id++) {
            lane.putOrder(lane.orders.get(id).withStatus(CoreOrderStatus.CANCELED, 3));
        }
        assertThat(lane.activeOrderIdsByUser.get(7)).isSameAs(membership);
        assertThat(membership.size()).isZero();
    }

    @Test
    void metricsIncludeEarlierQueuedAdmissionAfterItsCompletionFence() throws Exception {
        try (TradingRuntimeState state = new TradingRuntimeState()) {
            state.startAccountLanes();
            Field workersField = TradingRuntimeState.class.getDeclaredField("laneWorkers");
            workersField.setAccessible(true);
            SettlementLaneWorker worker = ((SettlementLaneWorker[]) workersField.get(state))[0];
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            worker.submit(lane -> {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test admission was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                state.recordAdmissionLaneOperation(lane, 123);
            });
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread releaser = new Thread(() -> {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                while (worker.depth() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
                release.countDown();
            });
            releaser.start();
            try {
                var encoder = new com.surprising.aeron.protocol.CoreLaneMetricsCodec.Encoder(
                        1, 1, 0, 16, 0, 0, 16, 0, 0, 16, 0, 0);
                state.writeAccountLaneMetrics(0, encoder);
                var metrics = com.surprising.aeron.protocol.CoreLaneMetricsCodec.decode(encoder.finish());
                int command = AccountLaneOperationType.COMMAND.ordinal();
                assertThat(metrics.accountLaneCompletedOperations()[command]).isEqualTo(1);
                assertThat(metrics.accountLaneLatencySamples()[command]).isEqualTo(1);
                assertThat(metrics.accountLaneTotalLatencyNanos()[command]).isEqualTo(123);
            } finally {
                release.countDown();
                releaser.join(5_000);
                assertThat(releaser.isAlive()).isFalse();
            }
        }
    }

    @Test
    void pendingReservationBatchLookupUsesTheExistingBatchReceipt() {
        try (var state = new TradingRuntimeState()) {
            state.putUser(new UserRuntime(7));
            state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
            CoreStateTestFixtures.reserveOrder(state, 11, 7, 91, 5, 2, 3, 200);
            CoreStateTestFixtures.reserveOrder(state, 12, 7, 92, 5, 2, 3, 200);
            state.onLane(7L, lane -> {
                lane.markPendingReservation(11, 4);
                lane.markPendingReservation(12, 4);
                return null;
            });
            state.pendingReservations.registerBatch(4, 7,
                    new OrderRuntime[]{state.order(11), state.order(12)}, 2);

            assertThat(state.pendingReservation(11, 7)).isTrue();
            assertThat(state.pendingReservation(12, 7)).isTrue();
            assertThat(state.pendingReservation(13, 7)).isFalse();
            assertThat(state.pendingReservation(11, 8)).isFalse();
        }
    }

    private static long userForLane(LaneTopology topology, int laneId) {
        for (long userId = 1; userId < 10_000; userId++) {
            if (topology.accountLaneId(userId) == laneId) return userId;
        }
        throw new IllegalStateException("unable to find user for Account Lane");
    }

    private static String accountLaneSource() throws Exception {
        Path testClasses = Path.of(TradingRuntimeStateTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path module = testClasses.getParent().getParent();
        return Files.readString(module.resolve("src/main/java/com/surprising/aeron/service/state/AccountLaneState.java"));
    }

    private static AccountLaneState accountLaneState(TradingRuntimeState state, int laneId) throws Exception {
        Field field = TradingRuntimeState.class.getDeclaredField("accountLanes");
        field.setAccessible(true);
        return ((AccountLaneState[]) field.get(state))[laneId];
    }

    private static LaneValues[] laneValues(TradingRuntimeState state) {
        LaneValues[] values = new LaneValues[state.topology().accountLaneCount()];
        for (int laneId = 0; laneId < values.length; laneId++) values[laneId] = laneValues(state, laneId);
        return values;
    }

    private static LaneValues laneValues(TradingRuntimeState state, int laneId) {
        return state.onLane(laneId, lane -> new LaneValues(lane.revision(), lane.appliedSequence(),
                lane.committedSequence(), lane.localStateHash(), lane.localFundsHash()));
    }

    private record LaneValues(long revision, long appliedSequence, long committedSequence,
                              long localStateHash, long localFundsHash) {
    }

    private static long[] pendingReservationOrderIds(TradingRuntimeState state, long coreSequence) throws Exception {
        Field field = PendingReservationTracker.class.getDeclaredField("pendingReservationsBySequence");
        field.setAccessible(true);
        var pending = (PendingReservationTracker.PendingReservationSequenceIndex) field.get(state.pendingReservations);
        return pending.orderIds(coreSequence);
    }

    private static long pendingReservationOwner(TradingRuntimeState state, long orderId) throws Exception {
        Field field = PendingReservationTracker.class.getDeclaredField("pendingReservationUsers");
        field.setAccessible(true);
        var owners = (org.agrona.collections.Long2LongHashMap) field.get(state.pendingReservations);
        return owners.get(orderId);
    }

    private static String methodSource(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) throw new IllegalArgumentException("account lane lookup method is missing");
        return source.substring(start, end);
    }

    private static RiskScanRuntime incompleteRiskScan(int symbolId, long lastUserId) {
        return new RiskScanRuntime(symbolId, 1, 1, lastUserId, false,
                0, 0, "-", 0, 0, 0, 0, 0,
                true, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
