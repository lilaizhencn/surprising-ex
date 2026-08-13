package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TradingCommandCodecTest {

    @Test
    void roundTripsAllP2Commands() {
        BalanceAdjustmentCommand adjustment = new BalanceAdjustmentCommand("USDT", 10_000);
        PlaceOrderCommand placeOrder = new PlaceOrderCommand(7, "BTC-USDT", 3, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 0, 3, false, CoreMarginMode.ISOLATED, CorePositionSide.LONG,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 2_000,
                CoreOrderType.MARKET, CoreTimeInForce.FOK, 60_000, false,
                "client-7", -10, 20);
        CancelOrderCommand cancelOrder = new CancelOrderCommand(7);
        ReplaceOrderCommand replaceOrder = new ReplaceOrderCommand(7, "BTC", "USDT", 61_000, 2_100);
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 3, 1,
                "BTC", "USDT", "USDT", 1, 1, 100_000_000, 100_000, 50_000, -10, 20,
                0, -1, 0);
        ApplyMarkPriceCommand markPrice = new ApplyMarkPriceCommand("BTC-USDT", 3, 60_500, 9);
        ApplyFundingCommand funding = new ApplyFundingCommand(11, "BTC-USDT", 3, 100);
        SettleInstrumentCommand settlement = new SettleInstrumentCommand(12, "BTC-USDT", 3, 61_000, 0);
        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(13, 59_000);
        ResolveLiquidationCommand resolution = new ResolveLiquidationCommand(13,
                ResolveLiquidationCommand.Resolution.INSURANCE, 7);
        ContinueRiskScanCommand continuation = new ContinueRiskScanCommand(256);
        UpdatePositionModeCommand mode = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        AdjustPositionMarginCommand margin = new AdjustPositionMarginCommand("BTC-USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 500);

        assertThat(TradingCommandCodec.decodeBalanceAdjustment(
                TradingCommandCodec.encodeBalanceAdjustment(adjustment))).isEqualTo(adjustment);
        assertThat(TradingCommandCodec.decodePlaceOrder(
                TradingCommandCodec.encodePlaceOrder(placeOrder))).isEqualTo(placeOrder);
        assertThat(TradingCommandCodec.decodeCancelOrder(
                TradingCommandCodec.encodeCancelOrder(cancelOrder))).isEqualTo(cancelOrder);
        assertThat(TradingCommandCodec.decodeReplaceOrder(
                TradingCommandCodec.encodeReplaceOrder(replaceOrder))).isEqualTo(replaceOrder);
        assertThat(TradingCommandCodec.decodeUpsertInstrument(
                TradingCommandCodec.encodeUpsertInstrument(instrument))).isEqualTo(instrument);
        assertThat(TradingCommandCodec.decodeApplyMarkPrice(
                TradingCommandCodec.encodeApplyMarkPrice(markPrice))).isEqualTo(markPrice);
        assertThat(TradingCommandCodec.decodeApplyFunding(
                TradingCommandCodec.encodeApplyFunding(funding))).isEqualTo(funding);
        assertThat(TradingCommandCodec.decodeSettleInstrument(
                TradingCommandCodec.encodeSettleInstrument(settlement))).isEqualTo(settlement);
        assertThat(TradingCommandCodec.decodeExecuteLiquidation(
                TradingCommandCodec.encodeExecuteLiquidation(liquidation))).isEqualTo(liquidation);
        assertThat(TradingCommandCodec.decodeResolveLiquidation(
                TradingCommandCodec.encodeResolveLiquidation(resolution))).isEqualTo(resolution);
        assertThat(TradingCommandCodec.decodeContinueRiskScan(
                TradingCommandCodec.encodeContinueRiskScan(continuation))).isEqualTo(continuation);
        assertThat(TradingCommandCodec.decodeUpdatePositionMode(
                TradingCommandCodec.encodeUpdatePositionMode(mode))).isEqualTo(mode);
        assertThat(TradingCommandCodec.decodeAdjustPositionMargin(
                TradingCommandCodec.encodeAdjustPositionMargin(margin))).isEqualTo(margin);
    }

    @Test
    void rejectsTruncatedAndTrailingPayloads() {
        byte[] valid = TradingCommandCodec.encodeBalanceAdjustment(
                new BalanceAdjustmentCommand("USDT", 1));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThatThrownBy(() -> TradingCommandCodec.decodeBalanceAdjustment(
                java.util.Arrays.copyOf(valid, valid.length - 1)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingCommandCodec.decodeBalanceAdjustment(trailing))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void responseRoundTripPreservesOriginalCommandStatusAndData() {
        CoreResponse response = new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.REJECTED,
                CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE, 9, 17, new byte[] {1, 2, 3});

        CoreResponse restored = CoreProtocol.decodeResponse(CoreProtocol.responsePayload(response));

        assertThat(restored.status()).isEqualTo(response.status());
        assertThat(restored.commandStatus()).isEqualTo(response.commandStatus());
        assertThat(restored.resultCode()).isEqualTo(response.resultCode());
        assertThat(restored.appliedCommandCount()).isEqualTo(response.appliedCommandCount());
        assertThat(restored.stateHash()).isEqualTo(response.stateHash());
        assertThat(restored.data()).containsExactly(response.data());
    }
}
