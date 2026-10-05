package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CoreSpotAssetUnitsTest {
    private long sequence;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void freezesSettlesAndReleasesConfiguredAssetUnits(boolean fragmented, boolean restoreBeforeFill) {
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        TradingCoreRuntime runtime = original;
        try {
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            byte[] encoded = com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec.encode(runtime.tradingState());
            var fields = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertThat(fields.getInt()).isEqualTo(37);
            long minimumReader = 0;
            while (fields.hasRemaining()) {
                int fieldId = fields.getInt(); int length = fields.getInt(); int end = fields.position() + length;
                if (fieldId == 12) minimumReader = fields.getLong();
                fields.position(end);
            }
            assertThat(minimumReader).as("old readers must refuse non-unit asset accounting").isEqualTo(2);
            fund(runtime, 101, "USDT", 100_000_000_000L);
            fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 202, 1, CoreOrderSide.SELL, fragmented ? 1 : 5, 500_000, CoreTimeInForce.GTC);
            if (fragmented) place(runtime, 202, 2, CoreOrderSide.SELL, 4, 500_000, CoreTimeInForce.GTC);
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isEqualTo(500_000);

            if (restoreBeforeFill) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(71));
            assertThat(runtime.tradingState().instruments().get("1").quantityStepUnits()).isEqualTo(100_000);
            var filled = place(runtime, 101, 3, CoreOrderSide.BUY, 3, 500_000, CoreTimeInForce.IOC);

            var state = runtime.tradingState();
            assertThat(filled.remainingQuantitySteps()).isZero();
            assertThat(filled.status()).isEqualTo("FILLED");
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(300_000);
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(84_992_500_000L);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(700_000);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(14_997_000_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(10_500_000L);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
            assertThat(state.user(101).totalUnits("BTC") + state.user(202).totalUnits("BTC")).isEqualTo(1_000_000);
            assertThat(state.user(202).balances().get("BTC").lockedUnits()).isEqualTo(200_000);

            apply(runtime, CoreMessageType.CANCEL_ORDER, 202,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(fragmented ? 2 : 1)));
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
            place(runtime, 101, 4, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(8_004_000_000L);
            apply(runtime, CoreMessageType.CANCEL_ORDER, 101, TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(4)));
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(runtime.tradingState().user(101).totalUnits("USDT")).isEqualTo(84_992_500_000L);
        } finally {
            if (runtime != original) runtime.close();
            original.close();
        }
    }

    private void fund(TradingCoreRuntime runtime, long user, String asset, long units) {
        apply(runtime, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    private CoreOrderStateView place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side, long quantity, long price, CoreTimeInForce tif) {
        var response = apply(runtime, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, "1", side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, tif, false, "spot-units-" + id)));
        return CoreCommandResultCodec.decode(response.data()).orders().stream()
                .filter(order -> order.orderId() == id).findFirst().orElseThrow();
    }

    private CoreResponse apply(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        var result = runtime.apply(message);
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = com.surprising.aeron.service.orchestration.CoreTestCompletion.completeMatchingSynchronously(
                    runtime, runtime.matchingSequence(message.header().commandId()), 1_700_000_000_000L + seq, seq);
        }
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return result;
    }
}
