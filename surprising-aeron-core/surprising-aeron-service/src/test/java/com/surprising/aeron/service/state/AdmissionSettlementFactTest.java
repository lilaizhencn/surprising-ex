package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.model.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AdmissionSettlementFactTest {
    @ParameterizedTest
    @ValueSource(strings = {"CLEAR_TO_FUNDING", "FUNDING_TO_CLEAR", "SETTLED_TO_ROLLBACK"})
    void delayedLaneUsesTheBorrowedFactInsteadOfLaterLedgerStatus(String transition) throws Exception {
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 10_000, 0)), Map.of(), Map.of());
        var ids = new RuntimeIdentityRegistry();
        var release = new CountDownLatch(1);
        try (var runtime = RuntimeStateProjector.project(new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(7L, user), Map.of(), Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty()), ids)) {
            int symbol = ids.symbolId("1"), asset = ids.assetId("USDT");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 100, 1, 1_000));
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            try (var ledger = new SystemLedgerLane(runtime.treasury(), 256, failure -> { })) {
                var funding = new TreasuryRuntime.FundingProgressRuntime(1, instrument, 100,
                        0, 7, new UUID(1, 1), 100, 1);
                if (transition.equals("FUNDING_TO_CLEAR")) run(ledger, actual -> {
                    actual.setFundingProgress(symbol, funding); actual.clearChangedKeys();
                });
                if (transition.equals("SETTLED_TO_ROLLBACK"))
                    run(ledger, actual -> actual.setLifecycleSettlement(symbol, 2));
                var captured = runtime.treasury().settlementStatus(symbol);
                var intent = new PlaceOrderCommand(11, "1", CoreOrderSide.BUY, 100, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "fact");
                var event = runtime.acquirePlaceAdmission();
                CoreOrderDecisionResolver.prepare(event, runtime, ids, 7, intent, 1_001);
                int laneId = runtime.accountLaneId(7);
                var entered = new CountDownLatch(1);
                runtime.laneWorkers[laneId].submit(lane -> { entered.countDown(); await(release); });
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                try {
                    runtime.dispatchPlaceAdmission(2, 7, event, new UUID(1, 2), 0, captured, asset, ids, 1_001, 2);
                    var field = PlaceAdmissionEvent.class.getDeclaredField("settlementStatus");
                    field.setAccessible(true);
                    assertThat(field.get(event)).isSameAs(captured);
                    assertThat(event.complete()).isFalse();
                    run(ledger, actual -> {
                        switch (transition) {
                            case "CLEAR_TO_FUNDING" -> actual.setFundingProgress(symbol, funding);
                            case "FUNDING_TO_CLEAR" -> actual.setFundingSettlement(symbol, 1);
                            case "SETTLED_TO_ROLLBACK" -> actual.rollbackChangedValues();
                            default -> throw new AssertionError(transition);
                        }
                        actual.clearChangedKeys();
                    });
                    assertThat(runtime.treasury().settlementStatus(symbol)).isNotSameAs(captured);
                    release.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!event.complete()) {
                        runtime.assertAccountLanesHealthy();
                        if (System.nanoTime() >= deadline) throw new AssertionError("admission timed out");
                        Thread.onSpinWait();
                    }
                    if (transition.equals("CLEAR_TO_FUNDING")) assertThat(event.rejection()).isNull();
                    else assertThat(((CoreStateRejectedException) event.rejection()).code()).isEqualTo(
                            transition.equals("FUNDING_TO_CLEAR") ? "LIFECYCLE_IN_PROGRESS" : "INSTRUMENT_SETTLED");
                    runtime.dispatchControlLanes(1L << laneId, ignored -> {
                        var balance = runtime.balance(7, asset);
                        assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(10_000);
                        assertThat(runtime.reservationCountForUser(7)).isEqualTo(transition.equals("CLEAR_TO_FUNDING") ? 1 : 0);
                        return null;
                    });
                    while (!runtime.pollControlLanes()) {
                        if (System.nanoTime() >= deadline) throw new AssertionError("funds check timed out");
                        Thread.onSpinWait();
                    }
                    event.cancelMatcherWait();
                    runtime.releasePlaceAdmission(event);
                } finally { release.countDown(); }
            }
        } finally { release.countDown(); }
    }

    private static void run(SystemLedgerLane lane, SystemLedgerLane.Entry work) throws Exception {
        var done = new CountDownLatch(1);
        assertThat(lane.offer(actual -> { work.apply(actual); done.countDown(); })).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        lane.assertHealthy();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out");
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
}
