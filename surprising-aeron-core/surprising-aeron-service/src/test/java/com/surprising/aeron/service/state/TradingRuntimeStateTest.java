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
        assertThat(state.balance(7, 3)).isSameAs(balance);
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
}
