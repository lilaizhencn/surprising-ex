package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
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
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlaceLaneDecisionTest {
    @Test void priceAndFeeResolveOnlyAfterLaneConsumesAndRejectedSlotsAreReusable() throws Exception {
        var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        var user = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 1,
                Map.of("USDT", new AssetBalance("USDT", 10_000, 0)), Map.of(), Map.of());
        var state = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, Map.of(7L, user), Map.of(),
                Map.of("1", instrument), CoreRiskState.empty(), CoreTreasuryState.empty());
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = RuntimeStateProjector.project(state, identities)) {
            int symbol = identities.symbolId("1"), asset = identities.assetId("USDT");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol, instrument, 100, 1, 1_000));
            runtime.upsertFeePolicy(new UpsertFeePolicyCommand(1, 1, 7, "1", -10, 20, 0, true, 1, 0));
            runtime.startAccountLanes();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            int laneId = runtime.topology().accountLaneId(7);
            runtime.laneWorkers[laneId].submit(lane -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lane gate timed out"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                var command = new PlaceOrderCommand(11, "1", CoreOrderSide.BUY, 0, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                        CoreTimeInForce.IOC, false, "client");
                var rejected = runtime.acquirePlaceAdmission();
                CoreOrderDecisionResolver.prepare(rejected, runtime, identities, 7, command, 6_001);
                runtime.dispatchPlaceAdmission(2, 7, rejected, new UUID(1, 2), runtime.treasury().settlementStatus(symbol), asset,
                        identities, 6_001, 2);
                assertThat(rejected.complete()).isFalse();
                assertThat(rejected.matchingOrder().intent()).isSameAs(command);
                assertThat(rejected.resolvedOrderInitialized()).isFalse();
                assertThat(rejected.matchingPriceTicks()).isZero();
                release.countDown();
                await(runtime, rejected);
                assertThat(rejected.rejection()).isInstanceOf(com.surprising.aeron.service.exception.CoreStateRejectedException.class)
                        .hasMessageContaining("freshness");
                assertThat(rejected.clientKey()).isZero();
                rejected.cancelMatcherWait();
                runtime.releasePlaceAdmission(rejected);
                var accepted = runtime.acquirePlaceAdmission();
                assertThat(accepted).isSameAs(rejected);
                CoreOrderDecisionResolver.prepare(accepted, runtime, identities, 7, command, 1_001);
                runtime.dispatchPlaceAdmission(3, 7, accepted, new UUID(1, 3), runtime.treasury().settlementStatus(symbol), asset,
                        identities, 1_001, 3);
                await(runtime, accepted);
                assertThat(accepted.rejection()).isNull();
                assertThat(accepted.resolvedOrder().matchingPriceTicks()).isEqualTo(101);
                assertThat(accepted.makerFeeRatePpm()).isEqualTo(-10);
                assertThat(accepted.takerFeeRatePpm()).isEqualTo(20);
                accepted.cancelMatcherWait();
                runtime.releasePlaceAdmission(accepted);
            } finally { release.countDown(); }
        }
    }

    private static void await(TradingRuntimeState runtime, PlaceAdmissionEvent event) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!event.complete()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("admission timed out");
            Thread.onSpinWait();
        }
    }
}
