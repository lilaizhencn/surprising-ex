package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        ReplaceOrderCommand replaceOrder = new ReplaceOrderCommand(6, placeOrder);
        AmendOrderCommand amendOrder = new AmendOrderCommand(6, 8, "client-8", 61_000L,
                4L, CoreTimeInForce.GTC, true);
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 3, 1,
                "BTC", "USDT", "USDT", 1, 1, 100_000_000, 100_000, 50_000, -10, 20,
                0, -1, 0, 10_000_000, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                java.util.List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, 10_000_000, 100_000, 50_000)));
        ApplyMarkPriceCommand markPrice = new ApplyMarkPriceCommand("BTC-USDT", 3, 60_500, 9,
                1_700_000_000_000L);
        ApplyFundingCommand funding = new ApplyFundingCommand(11, "BTC-USDT", 3, 100, 0, 128);
        ApplyFundingCommand chunkedFunding = new ApplyFundingCommand(12, "BTC-USDT", 3, -100,
                42, 128);
        SettleInstrumentCommand settlement = new SettleInstrumentCommand(12, "BTC-USDT", 3, 61_000, 0, 0, 128);
        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(13, 9, 59_000, 25_000);
        ExecuteLiquidationCommand chunkedLiquidation = new ExecuteLiquidationCommand(
                14, 10, 58_000, 30_000, 91, 512);
        ExecuteAdlCommand adl = new ExecuteAdlCommand(13, 18, "BTC-USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, -3, 58_000, 9, 2, 7);
        ResolveLiquidationCommand resolution = new ResolveLiquidationCommand(13,
                ResolveLiquidationCommand.Resolution.INSURANCE, 7);
        ContinueRiskScanCommand continuation = new ContinueRiskScanCommand(256);
        UpdatePositionModeCommand mode = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        AdjustPositionMarginCommand margin = new AdjustPositionMarginCommand("BTC-USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 500);
        AdjustInsuranceFundCommand insuranceFund = new AdjustInsuranceFundCommand("USDT", 500);
        UpdateLeverageCommand leverage = new UpdateLeverageCommand("BTC-USDT", CoreMarginMode.CROSS, 5_000_000L);

        assertThat(TradingCommandCodec.decodeBalanceAdjustment(
                TradingCommandCodec.encodeBalanceAdjustment(adjustment))).isEqualTo(adjustment);
        assertThat(TradingCommandCodec.decodePlaceOrder(
                TradingCommandCodec.encodePlaceOrder(placeOrder))).isEqualTo(placeOrder);
        assertThat(TradingCommandCodec.decodeCancelOrder(
                TradingCommandCodec.encodeCancelOrder(cancelOrder))).isEqualTo(cancelOrder);
        assertThat(TradingCommandCodec.decodeReplaceOrder(
                TradingCommandCodec.encodeReplaceOrder(replaceOrder))).isEqualTo(replaceOrder);
        assertThat(TradingCommandCodec.decodeAmendOrder(
                TradingCommandCodec.encodeAmendOrder(amendOrder))).isEqualTo(amendOrder);
        assertThat(TradingCommandCodec.decodeUpsertInstrument(
                TradingCommandCodec.encodeUpsertInstrument(instrument))).isEqualTo(instrument);
        assertThat(TradingCommandCodec.decodeApplyMarkPrice(
                TradingCommandCodec.encodeApplyMarkPrice(markPrice))).isEqualTo(markPrice);
        assertThat(TradingCommandCodec.decodeApplyFunding(
                TradingCommandCodec.encodeApplyFunding(funding))).isEqualTo(funding);
        assertThat(TradingCommandCodec.decodeApplyFunding(
                TradingCommandCodec.encodeApplyFunding(chunkedFunding))).isEqualTo(chunkedFunding);
        assertThat(TradingCommandCodec.decodeSettleInstrument(
                TradingCommandCodec.encodeSettleInstrument(settlement))).isEqualTo(settlement);
        assertThat(settlement.cursorOrderId()).isZero();
        assertThat(settlement.maxOrders()).isEqualTo(SettleInstrumentCommand.DEFAULT_MAX_ORDERS);
        assertThat(TradingCommandCodec.decodeExecuteLiquidation(
                TradingCommandCodec.encodeExecuteLiquidation(liquidation))).isEqualTo(liquidation);
        assertThat(liquidation.cursorOrderId()).isZero();
        assertThat(liquidation.maxOrders()).isEqualTo(ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS);
        assertThat(TradingCommandCodec.decodeExecuteLiquidation(
                TradingCommandCodec.encodeExecuteLiquidation(chunkedLiquidation))).isEqualTo(chunkedLiquidation);
        assertThat(TradingCommandCodec.decodeExecuteAdl(
                TradingCommandCodec.encodeExecuteAdl(adl))).isEqualTo(adl);
        assertThat(TradingCommandCodec.decodeResolveLiquidation(
                TradingCommandCodec.encodeResolveLiquidation(resolution))).isEqualTo(resolution);
        assertThat(TradingCommandCodec.decodeContinueRiskScan(
                TradingCommandCodec.encodeContinueRiskScan(continuation))).isEqualTo(continuation);
        assertThat(TradingCommandCodec.decodeUpdatePositionMode(
                TradingCommandCodec.encodeUpdatePositionMode(mode))).isEqualTo(mode);
        assertThat(TradingCommandCodec.decodeAdjustPositionMargin(
                TradingCommandCodec.encodeAdjustPositionMargin(margin))).isEqualTo(margin);
        assertThat(TradingCommandCodec.decodeAdjustInsuranceFund(
                TradingCommandCodec.encodeAdjustInsuranceFund(insuranceFund))).isEqualTo(insuranceFund);
        assertThat(TradingCommandCodec.decodeUpdateLeverage(
                TradingCommandCodec.encodeUpdateLeverage(leverage))).isEqualTo(leverage);
    }

    @Test
    void rejectsInvalidSingleActionOrderBoundsFromWire() {
        byte[] liquidation = TradingCommandCodec.encodeExecuteLiquidation(
                new ExecuteLiquidationCommand(13, 9, 59_000, 25_000));
        byte[] settlement = TradingCommandCodec.encodeSettleInstrument(
                new SettleInstrumentCommand(12, "BTC-USDT", 3, 61_000, 0));

        for (int invalid : new int[] {0, 1_025}) {
            ByteBuffer.wrap(liquidation).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(liquidation.length - Integer.BYTES, invalid);
            assertThatThrownBy(() -> TradingCommandCodec.decodeExecuteLiquidation(liquidation))
                    .isInstanceOf(ProtocolException.class);
            ByteBuffer.wrap(settlement).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(settlement.length - Integer.BYTES, invalid);
            assertThatThrownBy(() -> TradingCommandCodec.decodeSettleInstrument(settlement))
                    .isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void preservesAppendOnlyWireCodes() {
        assertThat(CoreMessageType.EXECUTE_TRIGGER_ORDER.wireCode()).isEqualTo(42);
        assertThat(CoreMessageType.EXECUTE_LIQUIDATION_BATCH.wireCode()).isEqualTo(43);
        assertThat(CoreResultCode.MATCHING_PENDING.wireCode()).isEqualTo(66);
        assertThat(CoreResultCode.LIFECYCLE_IN_PROGRESS.wireCode()).isEqualTo(67);
        assertThat(CoreResultCode.MATCHING_CONTINUATION_FAILED.wireCode()).isEqualTo(68);
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
    void rejectsInstrumentPayloadWithoutRiskPolicy() {
        UpsertInstrumentCommand command = new UpsertInstrumentCommand("BTC-USDT", 1, 1,
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0,
                0, -1, 0);
        byte[] current = TradingCommandCodec.encodeUpsertInstrument(command);
        int appendedRiskPolicyBytes = Integer.BYTES * 3 + Long.BYTES * 9;
        byte[] withoutRiskPolicy = java.util.Arrays.copyOf(current, current.length - appendedRiskPolicyBytes);

        assertThatThrownBy(() -> TradingCommandCodec.decodeUpsertInstrument(withoutRiskPolicy))
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
