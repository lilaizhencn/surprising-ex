package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TradingRuntimeStateTest {

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
        assertThat(state.changedClientOrders().contains(91L)).isTrue();
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
    void routesStateAndReadFencesThroughTheFixedAccountOwner() {
        TradingRuntimeState state = new TradingRuntimeState(LaneTopology.characterization());
        state.putUser(new UserRuntime(7));
        state.putBalance(new BalanceRuntime(7, 3, 1_000, 0));
        state.startAccountLanes();
        try {
            var result = new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ACCEPTED")
                    .withCoreSequence(1);
            var apply = state.applyAndCommitLaneSequence(1, java.util.List.of(7L), result, 3, 5, null);
            assertThat(apply.acknowledgements()).filteredOn(java.util.Objects::nonNull).hasSize(1);
            long settlementOperations = state.accountLaneMetricsById(state.topology().accountLaneId(7))
                    .completedOperations()[AccountLaneOperationType.SETTLEMENT.ordinal()];
            assertThat(settlementOperations)
                    .as("lane apply, acknowledgement and commit must share one owner-lane operation")
                    .isEqualTo(1);
            state.readFence(7, 1);

            AccountLaneView lane = state.accountLane(7);
            assertThat(lane.ownerThreadName()).startsWith("account-lane-");
            assertThat(lane.appliedSequence()).isEqualTo(1);
            assertThat(lane.committedSequence()).isEqualTo(1);
            assertThat(lane.queueHighWaterMark()).isGreaterThan(0);
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
            var result = new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ACCEPTED")
                    .withCoreSequence(1);
            state.applyAndCommitLaneSequence(1, java.util.List.of(userInLastLane), result, 3, 5, null);
            state.readFenceAll(1);
            assertThat(state.accountLaneById(0).committedSequence()).isEqualTo(1);
        } finally {
            state.close();
        }
    }

    @Test
    void applyAndCommitFailuresReclaimEverySubmittedLaneTicket() {
        LaneTopology topology = LaneTopology.productionDefault();
        TradingRuntimeState state = new TradingRuntimeState(topology);
        long laneZeroUser = userForLane(topology, 0);
        long laneOneUser = userForLane(topology, 1);
        state.putUser(new UserRuntime(laneZeroUser));
        state.putUser(new UserRuntime(laneOneUser));
        state.startAccountLanes();
        try {
            var sequenceTwo = new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ACCEPTED")
                    .withCoreSequence(2);
            var sequenceOne = new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ACCEPTED")
                    .withCoreSequence(1);
            state.applyAndCommitLaneSequence(2, java.util.List.of(laneZeroUser), sequenceTwo, 3, 5, null);
            state.applyAndCommitLaneSequence(1, java.util.List.of(laneOneUser), sequenceOne, 3, 5, null);

            assertThatThrownBy(() -> state.applyAndCommitLaneSequence(1,
                    java.util.List.of(laneZeroUser, laneOneUser), sequenceOne, 7, 11, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("out of order");
            assertThat(state.executeUserSettlement(laneOneUser, () -> "apply-reclaimed"))
                    .isEqualTo("apply-reclaimed");
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
        var result = new com.surprising.aeron.service.matching.CoreMatchingResult(true, "ACCEPTED")
                .withCoreSequence(2);
        state.applyAndCommitLaneSequence(2, java.util.List.of(laneZeroUser), result, 3, 5, null);
        AccountLaneView beforeRestore = state.accountLaneById(0);

        java.util.List<AccountLaneSnapshot> invalid = new java.util.ArrayList<>(snapshots);
        AccountLaneSnapshot corrupted = snapshots.get(1);
        invalid.set(1, new AccountLaneSnapshot(corrupted.laneId(), corrupted.revision(),
                corrupted.appliedSequence(), corrupted.committedSequence(),
                corrupted.localStateHash() ^ 1L, corrupted.localFundsHash(),
                corrupted.userIds(), corrupted.state()));

        assertThatThrownBy(() -> state.restoreAccountLaneSnapshots(invalid, 1, global))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash mismatch");
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
            assertThatThrownBy(() -> state.executeOwnerSettlements(java.util.List.of(203L, 8L), laneId -> {
                if (laneId == 0) throw new IllegalStateException("injected lifecycle lane failure");
                return laneId;
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("injected lifecycle lane failure");

            assertThat(state.executeUserSettlement(8, () -> "reclaimed")).isEqualTo("reclaimed");
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
}
