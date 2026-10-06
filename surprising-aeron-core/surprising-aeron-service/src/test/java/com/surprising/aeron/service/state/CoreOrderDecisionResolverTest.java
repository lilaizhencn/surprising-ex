package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;

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

import java.util.List;
import org.junit.jupiter.api.Test;

class CoreOrderDecisionResolverTest {

    @Test
    void configuredProtectionSurvivesCodecAndSnapshotAndControlsOrders() {
        var policy = new com.surprising.aeron.protocol.CoreOrderProtection(20_000, 8_000, true, 30_000, 4_000);
        var command = new com.surprising.aeron.protocol.RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1_000_000, 100_000, 50_000, -10, 25, 0, -1, 0,
                10_000_000, Long.MAX_VALUE, 0, 1,
                List.of(new CoreRiskLimitBracket(1,0,Long.MAX_VALUE,10_000_000,100_000,50_000)),
                1,true,true,true,3,15,1,policy);
        var decoded = com.surprising.aeron.protocol.TradingCommandCodec.decodeRegisterInstrument(
                com.surprising.aeron.protocol.TradingCommandCodec.encodeRegisterInstrument(command));
        assertThat(decoded).isEqualTo(command);
        var instrument=CoreInstrument.from(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,decoded);
        var identities=new RuntimeIdentityRegistry();
        try(var runtime=runtime(instrument)) {
            int symbol=identities.symbolId("1");
            runtime.putMarkPrice(new MarkPriceRuntime(symbol,instrument,60_000,9,1_000));
            var buy=new PlaceOrderCommand(91,"1",CoreOrderSide.BUY,0,2,false,CoreMarginMode.CROSS,
                    CorePositionSide.NET,CoreOrderType.MARKET,CoreTimeInForce.IOC,false,"buy");
            assertThat(CoreOrderDecisionResolver.resolve(runtime,identities,1001,buy,7_000).matchingPriceTicks()).isEqualTo(61_200);
            assertThatThrownBy(()->CoreOrderDecisionResolver.resolve(runtime,identities,1001,buy,9_001)).isInstanceOf(CoreStateRejectedException.class);
            var limit=new PlaceOrderCommand(92,"1",CoreOrderSide.BUY,62_000,2,false,CoreMarginMode.CROSS,
                    CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"limit");
            assertThatThrownBy(()->CoreOrderDecisionResolver.resolve(runtime,identities,1001,limit,1_500))
                    .isInstanceOf(CoreStateRejectedException.class).hasMessageContaining("price band");
        }
        try(var core=new com.surprising.aeron.service.orchestration.TradingCoreRuntime(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL)) {
            var message=new com.surprising.aeron.protocol.CoreMessage(com.surprising.aeron.protocol.CoreMessageHeader.command(
                    com.surprising.aeron.protocol.CoreMessageType.REGISTER_INSTRUMENT,java.util.UUID.randomUUID(),
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,com.surprising.aeron.protocol.CommandSource.OPERATIONS,0,1,0,1,1),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeRegisterInstrument(command));
            assertThat(core.apply(message).commandStatus()).isEqualTo(com.surprising.aeron.protocol.ResponseStatus.APPLIED);
            try(var restored=com.surprising.aeron.service.orchestration.TradingCoreRuntime.fromSnapshot(
                    com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,core.snapshot(71))) {
                assertThat(restored.tradingState().instruments().get("1").orderProtection()).isEqualTo(policy);
            }
        }
    }

    @Test
    void batchContextKeepsItsMarkAndUsesTheCanonicalInstrumentForEveryOrder() {
        var identities = new RuntimeIdentityRegistry();
        try (var runtime = runtime(linearInstrument())) {
            int symbolId = identities.symbolId("1");
            runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 60_000, 9, 1_000));
            var context = CoreOrderDecisionResolver.context(runtime, identities, 1001, "1", 1_500);
            runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 80_000, 10, 1_500));
            var buy = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 0, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, false, "buy");
            var sell = new PlaceOrderCommand(92, "1", CoreOrderSide.SELL, 0, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.MARKET,
                    CoreTimeInForce.IOC, false, "sell");
            assertThat(CoreOrderDecisionResolver.resolve(context, buy).matchingPriceTicks()).isEqualTo(60_600);
            assertThat(CoreOrderDecisionResolver.resolve(context, sell).matchingPriceTicks()).isEqualTo(59_400);
            assertThat(CoreOrderDecisionResolver.resolve(runtime, identities, 1001, buy, 1_500).markPriceTicks())
                    .isEqualTo(80_000);
            var limit = new PlaceOrderCommand(93, "1", CoreOrderSide.BUY, 100, 2,
                    false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                    CoreTimeInForce.GTC, false, "limit");
            assertThat(CoreOrderDecisionResolver.resolve(context, limit).matchingPriceTicks()).isEqualTo(100);
        }
    }

    @Test
    void resolvesProtectionReservationAndFeeInsideCore() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = runtime(linearInstrument());
        int symbolId = identities.symbolId("1");
        runtime.putMarkPrice(new MarkPriceRuntime(symbolId, runtime.instrument("1"), 60_000, 9, 1_000));
        runtime.upsertFeePolicy(new UpsertFeePolicyCommand(
                71, 2, 1001, "1", -25, 75, 4, true, 900, 0));
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.BUY, 0, 2,
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
    }

    @Test
    void rejectsAStaleCoreMarkWithoutReadingProviderState() {
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = runtime(linearInstrument());
        runtime.putMarkPrice(new MarkPriceRuntime(identities.symbolId("1"),
                runtime.instrument("1"), 60_000, 9, 1_000));
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.SELL, 59_000, 2,
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
        identities.symbolId("1");
        PlaceOrderCommand intent = new PlaceOrderCommand(91, "1", CoreOrderSide.SELL, 60_000, 2,
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

    @Test
    void scalesPricesWithoutOverflowingTheIntermediateProduct() {
        long value = 9_000_000_000_000_001L;

        assertThat(CoreOrderDecisionResolver.scalePpm(value, 990_000, false))
                .isEqualTo(8_910_000_000_000_000L);
        assertThat(CoreOrderDecisionResolver.scalePpm(value, 1_010_000, true))
                .isEqualTo(9_090_000_000_000_002L);
        assertThatThrownBy(() -> CoreOrderDecisionResolver.scalePpm(Long.MAX_VALUE, 1_010_000, true))
                .isInstanceOf(ArithmeticException.class);
    }

    private static TradingRuntimeState runtime(CoreInstrument instrument) {
        TradingRuntimeState runtime = new TradingRuntimeState();
        runtime.setMetadata(instrument.contractType().productLine(), 0);
        runtime.registerInstrument(instrument);
        return runtime;
    }

    private static CoreInstrument linearInstrument() {
        return instrument(ContractType.LINEAR_PERPETUAL);
    }

    private static CoreInstrument spotInstrument() {
        return instrument(ContractType.SPOT);
    }

    private static CoreInstrument instrument(ContractType contractType) {
        return new CoreInstrument("1", contractType, "BTC", "USDT", "USDT",
                1, 1, 1_000_000, 100_000, 50_000, -10, 25, 0, null, 0,
                10_000_000, Long.MAX_VALUE, 0, 1,
                List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE,
                        10_000_000, 100_000, 50_000)));
    }
}
