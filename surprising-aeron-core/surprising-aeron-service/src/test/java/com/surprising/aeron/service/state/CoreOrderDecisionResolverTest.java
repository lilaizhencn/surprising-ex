package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreOrderDecisionResolverTest {

    @Test
    void resolvesProtectionReservationAndFeeInsideCore() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = runtime(linearInstrument());
        int symbolId = identities.symbolId("BTC-USDT");
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, 1, 60_000, 9, 1_000));
        runtime.upsertFeePolicy(new UpsertFeePolicyCommand(
                71, 2, 1001, "BTC-USDT", -25, 75, 4, true, 900, 0));
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "BTC-USDT", 1, CoreOrderSide.BUY, 0, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, false, "client-91");

        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 1_500);

        assertThat(resolved.matchingPriceTicks()).isEqualTo(60_600);
        assertThat(resolved.reservationPriceTicks()).isEqualTo(60_600);
        assertThat(resolved.markPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationKind()).isEqualTo(ReservationKind.DERIVATIVE_MARGIN);
        assertThat(resolved.reservationAsset()).isEqualTo("USDT");
        assertThat(resolved.makerFeeRatePpm()).isEqualTo(-25);
        assertThat(resolved.takerFeeRatePpm()).isEqualTo(75);
        assertThat(resolved.feePolicyVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAStaleCoreMarkWithoutReadingProviderState() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = runtime(linearInstrument());
        runtime.putMarkPrice(new MarkPriceRuntime(identities.symbolId("BTC-USDT"), 1, 60_000, 9, 1_000));
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "BTC-USDT", 1, CoreOrderSide.SELL, 59_000, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-91");

        assertThatThrownBy(() -> CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 6_001))
                .isInstanceOf(CoreStateRejectedException.class)
                .hasMessageContaining("freshness")
                .satisfies(exception -> {
                    CoreStateRejectedException rejection = (CoreStateRejectedException) exception;
                    assertThat(rejection.code()).isEqualTo("STALE_MARK_PRICE");
                    assertThat(CoreResultCode.fromRejectionCode(rejection.code()))
                            .isEqualTo(CoreResultCode.STALE_MARK_PRICE);
                });
    }

    @Test
    void spotLimitUsesItsLimitAndInstrumentDefaultWithoutAMark() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = runtime(spotInstrument());
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "BTC-USDT", 1, CoreOrderSide.SELL, 60_000, 2,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "client-91");

        ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime, identities, 1001, intent, 1_500);

        assertThat(resolved.matchingPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationPriceTicks()).isEqualTo(60_000);
        assertThat(resolved.reservationKind()).isEqualTo(ReservationKind.SPOT_ASSET);
        assertThat(resolved.reservationAsset()).isEqualTo("BTC");
        assertThat(resolved.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(resolved.takerFeeRatePpm()).isEqualTo(25);
    }

    private static TradingRuntimeState runtime(CoreInstrumentState instrument) {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.setMetadata(instrument.contractType().productLine(), 0);
        runtime.putInstrument(instrument);
        return runtime;
    }

    private static CoreInstrumentState linearInstrument() {
        return instrument(ContractType.LINEAR_PERPETUAL);
    }

    private static CoreInstrumentState spotInstrument() {
        return instrument(ContractType.SPOT);
    }

    private static CoreInstrumentState instrument(ContractType contractType) {
        return new CoreInstrumentState("BTC-USDT", 1, contractType, "BTC", "USDT", "USDT",
                1, 1, 1_000_000, 100_000, 50_000, -10, 25, 0, null, 0,
                10_000_000, Long.MAX_VALUE, 0, 1,
                List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE,
                        10_000_000, 100_000, 50_000)));
    }
}
