package com.surprising.aeron.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class LifecycleGuardTest {
    @Test
    void rejectsSameAssetSpotBeforeInstallingInstrument() {
        try (var h = LinearPerpetualBenchmarkSupport.Harness.create(4, ProductLine.SPOT)) {
            var response = h.state().apply(h.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("BAD", 1,
                            ContractType.SPOT.ordinal(), "BTC", "BTC", "BTC", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            assertThat(response.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.INVALID_COMMAND);
            assertThat(h.state().tradingState().instruments()).isEmpty();
            assertThat(h.state().tradingState().users()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void enforcesExpiryBeforeMatcherMutation(ProductLine line) {
        try (var h = LinearPerpetualBenchmarkSupport.Harness.create(4, line)) {
            boolean inverse = line == ProductLine.INVERSE_DELIVERY;
            boolean option = line == ProductLine.OPTION;
            ContractType type = inverse ? ContractType.INVERSE_DELIVERY
                    : option ? ContractType.VANILLA_OPTION : ContractType.LINEAR_DELIVERY;
            h.execute(h.command(CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("EXP", 1,
                            type.ordinal(), "BTC", inverse ? "USD" : "USDT", inverse ? "BTC" : "USDT",
                            1, 1, 1, 100_000, 50_000, 0, 0, 2_000_000_000_000L, option ? 0 : -1, option ? 100 : 0))));
            var early = h.state().apply(h.command(CoreMessageType.SETTLE_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(10, "EXP", 1, 100, 0))));
            assertThat(early.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(h.state().tradingState().treasuryState().lifecycleSettlements()).isEmpty();
            h.advanceClockTo(2_000_000_000_000L);
            var expired = h.state().apply(h.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, 1000,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(h.nextOrderId(), "EXP", 1,
                            CoreOrderSide.BUY, 100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""))));
            assertThat(expired.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(h.state().tradingState().orders()).isEmpty();
            h.execute(h.command(CoreMessageType.SETTLE_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(10, "EXP", 1, 100, 0))));
            assertThat(h.state().tradingState().treasuryState().lifecycleSettlements()).containsEntry("EXP", 10L);
        }
    }
}
