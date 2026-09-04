package com.surprising.aeron.service.state;

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
    void pendingReservationSequenceIndexKeepsSingleOrderOnPrimitivePath() {
        TradingRuntimeState.PendingReservationSequenceIndex index =
                new TradingRuntimeState.PendingReservationSequenceIndex(4);

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
        TradingRuntimeState.PendingReservationSequenceIndex index =
                new TradingRuntimeState.PendingReservationSequenceIndex(4);
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
            AccountLaneView[] before = state.accountLanes();

            state.accountLaneSnapshots(1, TradingCoreState.empty(
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL));

            AccountLaneView[] after = state.accountLanes();
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
    void riskSchedulerSelectsTheLeastProgressedSymbolInsteadOfTheLowestSymbolId() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putRiskScan(incompleteRiskScan(1, 900));
        state.putRiskScan(incompleteRiskScan(2, 0));

        assertThat(state.firstRiskIncompleteScan().symbolId())
                .as("global risk work must rotate to the least-progressed symbol")
                .isEqualTo(2);
    }

    @Test
    void keepsHotIndexesFlatAndTracksChangedKeys() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        BalanceRuntime balance = new BalanceRuntime(7, 3, 1_000, 0);
        state.putBalance(balance);
        state.putOrder(new OrderRuntime(11, 7, 5, 2));
        state.putReservation(new ReservationRuntime(11, 7, 3, 200));
        state.putClientOrder(7, 91, 11);

        assertThat(state.user(7).userId()).isEqualTo(7);
        assertThat(state.balance(7, 3)).isNotSameAs(balance);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(1_000);
        assertThat(state.order(11).symbolId()).isEqualTo(5);
        assertThat(state.reservation(11).reservedUnits()).isEqualTo(200);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
        assertThat(state.changedUsers().contains(7L)).isTrue();
        assertThat(state.hasChangedBalance(7, 3)).isTrue();
        assertThat(state.changedOrders().contains(11L)).isTrue();
        assertThat(state.changedReservations().contains(11L)).isTrue();
    }

    @Test
    void discardsExpandedCaptureMapsAfterLargeCommands() {
        ConcurrentHashMap<Long, Long> expanded = new ConcurrentHashMap<>();
        for (long key = 0; key < 512; key++) expanded.put(key, key);

        ConcurrentHashMap<Long, Long> compacted = TradingRuntimeState.clearCapturedChanges(expanded);
        assertThat(compacted).isEmpty();
        assertThat(compacted).isNotSameAs(expanded);

        compacted.put(1L, 1L);
        ConcurrentHashMap<Long, Long> reused = TradingRuntimeState.clearCapturedChanges(compacted);
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

        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);

        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
        assertThat(state.order(11).quantitySteps()).isEqualTo(2);
        assertThat(state.orderIdByClient(7, 91)).isEqualTo(11);
    }

    @Test
    void reservationCompletionUsesTheOrderToClientReverseIndex() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
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
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);
        state.removeClientOrder(7, 91);
        state.clearChangedKeys();

        state.completePendingReservation(7, 11, 4);

        assertThat(state.orderIdByClient(7, 91)).isNull();
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
                    state.putReservation(new ReservationRuntime(orderId, userId, assetId, assetId));
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
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
        state.markPendingReservation(7, 11, 4);

        // When: the reservation is completed once and completion is retried.
        state.completePendingReservation(7, 11, 4);
        long revisionAfterFirstCompletion = state.revision();
        AccountLaneView laneAfterFirstCompletion = state.accountLane(7);
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
        assertThat(state.accountLane(7).localStateHash()).isEqualTo(laneAfterFirstCompletion.localStateHash());
        assertThat(state.accountLane(7).localFundsHash()).isEqualTo(laneAfterFirstCompletion.localFundsHash());
    }

    @Test
    void duplicateMarkAndMissingCompletionFailWithoutCounterDrift() {
        // Given: one reservation that is marked pending exactly once.
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
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
        state.reserveOrder(11, 7, 91, 5, 2, assetId, 200);
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
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
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
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);
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
        lane.reservations.put(1, new ReservationRuntime(1, 7, 3, Long.MAX_VALUE));
        lane.markPendingReservation(1, 1);
        lane.reservations.put(2, new ReservationRuntime(2, 7, 3, 1));

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
        state.reserveOrder(11, firstUser, 91, 5, 2, 3, 200);
        state.reserveOrder(12, laterUser, 92, 5, 2, 3, 200);
        state.markPendingReservation(firstUser, 11, 4);
        state.markPendingReservation(laterUser, 12, 4);
        ReservationRuntime firstReservation = state.reservation(11);
        BalanceRuntime firstBalance = state.balance(firstUser, 3);
        AccountLaneView firstLane = state.accountLane(firstUser);
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
        assertThat(state.accountLane(firstUser).revision()).isEqualTo(firstLane.revision());
        assertThat(state.accountLane(firstUser).localStateHash()).isEqualTo(firstLane.localStateHash());
        assertThat(state.accountLane(firstUser).localFundsHash()).isEqualTo(firstLane.localFundsHash());
    }

    @Test
    void rejectsDuplicateOrderAndClientWithoutChangingFunds() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);

        assertThatThrownBy(() -> state.reserveOrder(11, 7, 92, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.reserveOrder(12, 7, 91, 5, 2, 3, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
        assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
    }

    @Test
    void insufficientFundsRejectsBeforeCreatingRuntimeEntities() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 100, 0));

        assertThatThrownBy(() -> state.reserveOrder(11, 7, 91, 5, 2, 3, 101))
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

        assertThat(state.changedBalances(7).contains(3)).isTrue();
        assertThat(state.changedBalances(7).contains(4)).isTrue();
    }

    @Test
    void capturesDeterministicImmutableSnapshot() {
        TradingRuntimeState state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.reserveOrder(11, 7, 91, 5, 2, 3, 200);

        TradingRuntimeSnapshot snapshot = state.snapshot(4);

        assertThat(snapshot.revision()).isEqualTo(4);
        assertThat(snapshot.totalAvailableUnits()).isEqualTo(800);
        assertThat(snapshot.totalLockedUnits()).isEqualTo(200);
        assertThat(snapshot.orders()).containsKey(11L);
        assertThatThrownBy(() -> snapshot.orders().put(12L,
                new TradingRuntimeSnapshot.OrderSnapshot(7, 5, 1)))
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
                1, 2, 100, 200, 0, 40));
        state.treasury().setFee(3, 7);
        state.treasury().setInsurance(3, 11, 0);

        TradingRuntimeSnapshot snapshot = state.snapshot(5);

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
                1, 9, 2, 2, 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0);
        state.putLiquidation(planned);

        assertThat(state.activeLiquidation(7, 5,
                com.surprising.aeron.protocol.CorePositionSide.NET)).isSameAs(planned);

        LiquidationRuntime canceled = new LiquidationRuntime(1, 7, 5,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                1, 9, 2, 2, 0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0);
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

            AccountLaneView lane = state.accountLane(7);
            assertThat(lane.ownerThreadName()).isEqualTo("core-account-lane-0");
            assertThat(lane.appliedSequence()).isEqualTo(1);
            assertThat(lane.committedSequence()).isEqualTo(1);
            assertThat(lane.queueDepth()).isZero();
            assertThat(lane.queueHighWaterMark()).isZero();
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
            assertThat(state.accountLaneById(0).committedSequence()).isEqualTo(1);
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

            assertThat(state.accountLaneById(0).appliedSequence()).isEqualTo(1);
            assertThat(state.accountLaneById(1).appliedSequence()).isEqualTo(1);
            assertThat(state.accountLaneById(0).committedSequence()).isEqualTo(1);
            assertThat(state.accountLaneById(1).committedSequence()).isEqualTo(1);
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
            assertThat(state.accountLaneById(laneId).appliedSequence()).isEqualTo(1);
            assertThat(state.accountLaneById(laneId).committedSequence()).isEqualTo(1);
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
                1, 2, 100, 200, 0, 40);
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
            AccountLaneView[] beforeFailure = state.accountLanes();
            long revisionBeforeFailure = state.revision();

            assertThatThrownBy(() -> state.stageLaneMutation(
                    1, java.util.List.of(laneZeroUser, laneOneUser)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("out of order");
            AccountLaneView[] afterFailure = state.accountLanes();
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

            AccountLaneView applied = state.accountLaneById(0);
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
                assertThat(state.accountLaneById(laneId).committedSequence()).isEqualTo(256);
            }
        } finally {
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
            state.reserveOrder(orderId, userId, clientKey, 5, 1, 3, 1);
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
        AccountLaneView beforeRestore = state.accountLaneById(0);

        java.util.List<AccountLaneSnapshot> invalid = new java.util.ArrayList<>(snapshots);
        AccountLaneSnapshot corrupted = snapshots.get(1);
        invalid.set(1, new AccountLaneSnapshot(corrupted.laneId(), corrupted.revision(),
                corrupted.appliedSequence(), corrupted.committedSequence(),
                corrupted.localStateHash(), corrupted.localFundsHash(), java.util.List.of(laneZeroUser)));

        assertThatThrownBy(() -> state.restoreAccountLaneSnapshots(invalid, 1, global))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incorrectly routed user");
        AccountLaneView afterFailure = state.accountLaneById(0);
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
                AccountLaneMetricsSnapshot metrics = state.accountLaneMetricsById(laneId);
                assertThat(metrics.queueHighWaterMark()).isEqualTo(1);
                assertThat(metrics.completedOperations()[AccountLaneOperationType.SETTLEMENT.ordinal()])
                        .isEqualTo(1);
                assertThat(metrics.latencySamples()[AccountLaneOperationType.SETTLEMENT.ordinal()])
                        .isEqualTo(1);
            }
            assertThat(state.executeUserSettlement(users.getFirst(), () -> "core-owner"))
                    .isEqualTo("core-owner");
        } finally {
            state.close();
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

    private static long[] pendingReservationOrderIds(TradingRuntimeState state, long coreSequence) throws Exception {
        Field field = TradingRuntimeState.class.getDeclaredField("pendingReservationsBySequence");
        field.setAccessible(true);
        var pending = (TradingRuntimeState.PendingReservationSequenceIndex) field.get(state);
        return pending.orderIds(coreSequence);
    }

    private static long pendingReservationOwner(TradingRuntimeState state, long orderId) throws Exception {
        Field field = TradingRuntimeState.class.getDeclaredField("pendingReservationUsers");
        field.setAccessible(true);
        var owners = (org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap) field.get(state);
        return owners.getIfAbsent(orderId, 0);
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
