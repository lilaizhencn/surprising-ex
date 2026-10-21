package com.surprising.aeron.service.state.admission;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.AccountLaneTestAccess;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.fee.FeePolicyBook;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.CoreTreasuryState;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.*;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.collections.Long2ObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReplacementAdmissionEventTest {
    @Test void laneAloneReadsAccountTablesAndReusesItsAdmissionAfterRejection() throws Exception {
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000,
                        0, 0, 0, -1, 0, 10_000_000, 10_000, 0, 10_000,
                        List.of(new CoreRiskLimitBracket(1, 0, 10_000, 10_000_000, 100_000, 50_000))));
        var order = new CoreOrderState(11, ProductLine.LINEAR_PERPETUAL, 7, "1", CoreOrderSide.BUY,
                100, 1, 0, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "original", new UUID(1, 1), 0, 0, CoreOrderStatus.OPEN, 1);
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 800, 100)),
                Map.of(11L, OrderReservation.create(11, "1", ReservationKind.DERIVATIVE_MARGIN, "USDT", 100, 1)),
                Map.of());
        var before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, Map.of(7L, user),
                Map.of(11L, order), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(before, identities)) {
            int laneId = runtime.topology().accountLaneId(7), symbolId = identities.symbolId("1");
            var lane = runtime.accountLane(laneId);
            var originalUser = lane.user(7);
            var originalOrder = lane.order(11);
            Thread owner = Thread.currentThread();
            guard(lane, "orders", agronaGuard(AccountLaneTestAccess.orders(lane), owner));
            guard(lane, "reservations", agronaGuard(AccountLaneTestAccess.reservations(lane), owner));
            guard(lane, "users", eclipseGuard(AccountLaneTestAccess.users(lane), owner));
            guard(lane, "positions", eclipseGuard(AccountLaneTestAccess.positions(lane), owner));
            guard(lane, "balances", eclipseGuard(AccountLaneTestAccess.balances(lane), owner));
            var decision = new CoreOrderDecisionResolver.Context(instrument, symbolId,
                    new MarkPriceRuntime(symbolId, instrument, 100, 1, 1_000),
                    FeePolicyBook.EMPTY, 1_001, runtime.treasury().settlementStatus(symbolId));
            runtime.startAccountLanes();
            var workerHeld = new CountDownLatch(1);
            var releaseWorker = new CountDownLatch(1);
            runtime.laneWorkers[laneId].submit(value -> {
                workerHeld.countDown();
                try { if (!releaseWorker.await(3, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertThat(workerHeld.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                var event = runtime.dispatchReplacementAdmission(2, 7, 11,
                        new AmendOrderCommand(11, 12, "new-client", 101L, 1L, CoreTimeInForce.GTC, false),
                        null, decision, 0, identities);
                assertThat(event.complete()).isFalse();
                assertThatThrownBy(() -> runtime.releaseReplacementAdmission(event))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
                var independent = new CountDownLatch(1);
                runtime.laneWorkers[(laneId + 1) % runtime.topology().accountLaneCount()]
                        .submit(value -> independent.countDown());
                assertThat(independent.await(3, TimeUnit.SECONDS)).isTrue();
                releaseWorker.countDown();
                await(runtime, event);
                assertThat(event.rejection()).isNull();
                assertThat(event.admission().originalOrderRevision()).isEqualTo(originalOrder.revision());
                assertThat(event.admission().userRevision()).isEqualTo(originalUser.revision());
                assertThat(event.admission().requiredReservationUnits()).isPositive();
                assertThat(event.admission().command().limitPriceTicks()).isEqualTo(101);
                assertThat(event.closeCapacityCancellations()).isEmpty();
                runtime.releaseReplacementAdmission(event);
                var retry = runtime.dispatchReplacementAdmission(3, 7, 404,
                        new AmendOrderCommand(404, 405, "missing", 101L, 1L, CoreTimeInForce.GTC, false),
                        null, decision, 0, identities);
                assertThat(retry).isSameAs(event);
                await(runtime, retry);
                assertThat(retry.rejection()).isInstanceOf(com.surprising.aeron.service.exception.CoreStateRejectedException.class);
                assertThat(retry.admission()).isNull();
                runtime.releaseReplacementAdmission(retry);
                var accepted = runtime.dispatchReplacementAdmission(4, 7, 11, null,
                        new ReplaceOrderCommand(11, new PlaceOrderCommand(13, "1", CoreOrderSide.BUY, 100, 1,
                                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "new-client")), decision, 0, identities);
                assertThat(accepted).isSameAs(event);
                await(runtime, accepted);
                assertThat(accepted.rejection()).isNull();
                assertThat(originalOrder.status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(originalUser.revision()).isEqualTo(1);
                runtime.releaseReplacementAdmission(accepted);
            } finally { releaseWorker.countDown(); }
        }
    }

    private static void await(TradingRuntimeState runtime, ReplacementAdmissionEvent event) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!event.complete()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("replacement admission timed out");
            Thread.onSpinWait();
        }
    }
    private static void guard(AccountLaneState lane, String name, Object replacement) throws Exception {
        var field = AccountLaneState.class.getDeclaredField(name); field.setAccessible(true); field.set(lane, replacement);
    }
    private static <T> Long2ObjectHashMap<T> agronaGuard(Long2ObjectHashMap<T> original, Thread owner) {
        var guarded = new Long2ObjectHashMap<T>() {
            @Override public T get(long key) {
                if (Thread.currentThread() == owner) throw new AssertionError("Owner read Account Lane table");
                return super.get(key);
            }
        };
        guarded.putAll(original); return guarded;
    }
    private static <T> LongObjectHashMap<T> eclipseGuard(LongObjectHashMap<T> original, Thread owner) {
        var guarded = new LongObjectHashMap<T>() {
            @Override public T get(long key) {
                if (Thread.currentThread() == owner) throw new AssertionError("Owner read Account Lane table");
                return super.get(key);
            }
        };
        guarded.putAll(original); return guarded;
    }
}
