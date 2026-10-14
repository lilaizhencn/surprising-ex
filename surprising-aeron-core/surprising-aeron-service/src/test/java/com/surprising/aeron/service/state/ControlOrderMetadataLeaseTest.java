package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class ControlOrderMetadataLeaseTest {
    @Test void metadataKeepsPooledAuthorityAndPublishesAnIndependentFrozenValue() throws Exception {
        try (var state = fundedRuntime()) {
            state.startAccountLanes();
            var authority = new AtomicReference<OrderRuntime>();
            int laneId = state.accountLaneId(7);
            state.dispatchControlLanes(1L << laneId, ignored -> {
                RuntimeOrderStateTransitions.placePrepared(state, 7, intent(11), new UUID(1, 11), 200, 91, 5, 3);
                authority.set(state.order(11));
                return null;
            });
            await(state, state::pollControlLanes);
            var event = state.dispatchLaneMutation(1, List.of(7L), List.of(11L), 100, 200);
            await(state, () -> state.laneCommitComplete(event));
            var later = new CountDownLatch(1);
            assertThat(state.dispatchAccountQuery(1L << laneId, lane -> {
                var live = lane.orders.get(11);
                assertThat(live).isSameAs(authority.get());
                assertThatThrownBy(() -> live.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 3, 200))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("already prepared");
                live.applyCommitMetadataInPlace(101, 201);
                later.countDown();
            })).isTrue();
            assertThat(later.await(3, TimeUnit.SECONDS)).isTrue();
            state.releaseLaneCommit(event);
            assertThat(state.changedOrders.get(11).updatedAtEpochMillis()).isEqualTo(100);
            assertThat(state.changedOrders.get(11).clusterPosition()).isEqualTo(200);
            assertThat(state.changedOrders.get(11)).isNotSameAs(authority.get());
        }
    }

    @Test void metadataRollbackRestoresAnExistingOrdersPreCommandFields() {
        try (var state = fundedRuntime()) {
            state.startAccountLanes();
            var authority = new AtomicReference<OrderRuntime>();
            state.dispatchControlLanes(state.topology().accountLaneMask(7), ignored -> {
                RuntimeOrderStateTransitions.placePrepared(state, 7, intent(11), new UUID(1, 11), 200, 91, 5, 3);
                authority.set(state.order(11)); return null;
            });
            await(state, state::pollControlLanes);
            long revision = state.revision();
            state.clearChangedKeys();
            state.enterAsynchronousCommandScope();
            try {
                var event = state.dispatchLaneMutation(1, List.of(7L), List.of(11L), 100, 200);
                await(state, () -> state.laneCommitComplete(event));
                state.releaseLaneCommit(event);
                assertThat(state.changedOrders.get(11).clusterPosition()).isEqualTo(200);
                await(state, state.beginCommandRollback(revision, 1));
                state.dispatchControlLanes(state.topology().accountLaneMask(7), ignored -> {
                    var order = state.order(11);
                    assertThat(order).isSameAs(authority.get());
                    assertThatThrownBy(() -> order.prepareReservation(ReservationKind.DERIVATIVE_MARGIN, 3, 200))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(order.createdAtEpochMillis()).isZero();
                    assertThat(order.updatedAtEpochMillis()).isZero();
                    assertThat(order.clusterPosition()).isZero();
                    assertThat(state.balance(7, 3).availableUnits()).isEqualTo(800);
                    assertThat(state.balance(7, 3).lockedUnits()).isEqualTo(200);
                    assertThat(state.reservation(11).reservedUnits()).isEqualTo(200);
                    return null;
                });
                await(state, state::pollControlLanes);
            } finally { state.exitAsynchronousCommandScope(); }
        }
    }

    private static TradingRuntimeState fundedRuntime() {
        var state = new TradingRuntimeState();
        state.putUser(new UserRuntime(7)); state.putBalance(new BalanceRuntime(7, 3, 1000, 0));
        state.clearChangedKeys();
        return state;
    }

    private static ResolvedPlaceOrder intent(long id) {
        var command = new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 100, 2, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "control-" + id);
        return new ResolvedPlaceOrder(command, CoreStateTestFixtures.runtimeInstrument(), 5,
                100, 100, 100, ReservationKind.DERIVATIVE_MARGIN, "USDT", 0, 0);
    }

    private static void await(TradingRuntimeState state, BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!done.getAsBoolean()) {
            state.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("control lease test timed out");
            Thread.onSpinWait();
        }
    }
}
