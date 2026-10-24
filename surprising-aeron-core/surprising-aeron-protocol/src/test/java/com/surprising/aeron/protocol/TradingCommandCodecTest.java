package com.surprising.aeron.protocol;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class TradingCommandCodecTest {

    @Test
    void registrationsCarryOnlyTheFieldsUsedByTheirProduct() {
        for (ProductLine line : ProductLine.values()) {
            boolean spot = !line.isDerivative();
            boolean option = line.isOptionProduct();
            boolean inverse = line == ProductLine.INVERSE_PERPETUAL || line == ProductLine.INVERSE_DELIVERY;
            var rawRisk = spot ? null : new CoreInstrumentRiskLimits(100_000, option ? -99 : 10_000_000,
                    1_000_000, 0, 1_000_000, List.of(new CoreRiskLimitBracket(
                    1_000_000, option ? -88 : 10_000_000, 100_000, 50_000, option ? 1_200_000 : -77)));
            var expectedRisk = spot ? null : rawRisk.forProduct(line);
            var canonical = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 2, inverse ? 3 : 1,
                    inverse ? 100 : 1, expectedRisk, -10, 20, line.isDeliveryProduct() ? 2_000 : 0,
                    option ? 0 : -1, option ? 100 : 0, 1, true, true, true, 3, 15, spot ? 7 : 1);
            var unusedInputs = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 2, inverse ? 3 : -99,
                    inverse ? 100 : -99, rawRisk, -10, 20, line.isDeliveryProduct() ? 2_000 : -99,
                    option ? 0 : -99, option ? 100 : -99, 1, true, true, true, 3, 15, spot ? 7 : -99,
                    spot ? new CoreOrderProtection(20_000, 8_000, true, 30_000, 4_000) : canonical.orderProtection());
            byte[] encoded = TradingCommandCodec.encodeRegisterInstrument(canonical, line);
            assertThat(TradingCommandCodec.encodeRegisterInstrument(unusedInputs, line)).isEqualTo(encoded);
            assertThat(TradingCommandCodec.decodeRegisterInstrument(encoded, line)).isEqualTo(canonical);
            int expectedBytes = switch (line) {
                case SPOT -> 71;
                case LINEAR_PERPETUAL -> 172;
                case INVERSE_PERPETUAL -> 188;
                case LINEAR_DELIVERY -> 180;
                case INVERSE_DELIVERY -> 196;
                case OPTION -> 184;
            };
            assertThat(encoded).hasSize(expectedBytes);
        }
    }

    @Test
    void riskLimitsValidateOnlyTheParametersUsedByTheProduct() {
        var option = new CoreInstrumentRiskLimits(100_000, -1, 200, 0, 1,
                List.of(new CoreRiskLimitBracket(200, -1, 100_000, 50_000, 1_200_000)))
                .forProduct(ProductLine.OPTION);
        assertThat(option.maxLeveragePpm()).isZero();
        assertThat(option.riskLimitBrackets().getFirst().maxLeveragePpm()).isZero();
        assertThat(option.riskLimitBrackets().getFirst().optionMarginFactorPpm()).isEqualTo(1_200_000);
        assertThatThrownBy(() -> new CoreInstrumentRiskLimits(100_000, 10_000_000, 200, 0, 1,
                List.of(new CoreRiskLimitBracket(200, 20_000_000, 100_000, 50_000, -1)))
                .forProduct(ProductLine.LINEAR_PERPETUAL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreInstrumentRiskLimits(100_000, -1, 200, 0, 1,
                List.of(new CoreRiskLimitBracket(200, -1, 100_000, 50_000, 0)))
                .forProduct(ProductLine.OPTION)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void riskBracketsRequireIncreasingCapsAndCoverThePositionLimit() {
        var first = new CoreRiskLimitBracket(100, 10_000_000, 100_000, 50_000);
        var second = new CoreRiskLimitBracket(200, 5_000_000, 200_000, 100_000);
        assertThat(new CoreInstrumentRiskLimits(100_000, 10_000_000, 200, 0, 1,
                List.of(first, second)).riskLimitBrackets()).containsExactly(first, second);
        assertThatThrownBy(() -> new CoreInstrumentRiskLimits(100_000, 10_000_000, 200, 0, 1,
                List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreInstrumentRiskLimits(100_000, 10_000_000, 200, 0, 1,
                List.of(second, first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoreInstrumentRiskLimits(100_000, 10_000_000, 201, 0, 1,
                List.of(first, second))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPriceRequiresOnlyTheCompleteLayoutOfItsProduct() {
        for (ProductLine line : ProductLine.values()) {
            boolean option = line.isOptionProduct();
            var command = new ApplyMarkPriceCommand("1", 100, 99, option ? 98 : 0, 1, 1_000, 0);
            byte[] payload = TradingCommandCodec.encodeApplyMarkPrice(command, line);
            assertThat(payload).hasSize(option ? 51 : 43);
            assertThat(TradingCommandCodec.decodeApplyMarkPrice(payload, line)).isEqualTo(command);
            if (!option) {
                var unusedForward = new ApplyMarkPriceCommand("1", 100, 99, 777, 1, 1_000, 0);
                assertThat(TradingCommandCodec.encodeApplyMarkPrice(unusedForward, line)).isEqualTo(payload);
            }
            assertThatThrownBy(() -> TradingCommandCodec.decodeApplyMarkPrice(
                    java.util.Arrays.copyOf(payload, payload.length - Long.BYTES), line))
                    .isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> TradingCommandCodec.decodeApplyMarkPrice(
                    java.util.Arrays.copyOf(payload, payload.length + Long.BYTES), line))
                    .isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void zeroBalanceInitializationKeepsTheExistingWireFormat() {
        var command = new BalanceAdjustmentCommand("USDT", 0);
        assertThat(TradingCommandCodec.decodeBalanceAdjustment(
                TradingCommandCodec.encodeBalanceAdjustment(command))).isEqualTo(command);
    }

    @Test
    void collidingInstrumentTextRemainsDistinctAndDoesNotRetainInputBytes() {
        // Both decimal IDs select the same slot of the bounded text cache.
        byte[] first = "10000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] second = "10088".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String retained = TradingCommandCodec.decodeAsciiSymbol(first, 0, first.length);
        java.util.Arrays.fill(first, (byte) '9');
        assertThat(TradingCommandCodec.decodeAsciiSymbol(second, 0, second.length)).isEqualTo("10088");
        byte[] original = "10000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(TradingCommandCodec.decodeAsciiSymbol(original, 0, original.length)).isEqualTo("10000");
        assertThat(retained).isEqualTo("10000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "1", "1023", "1024", "10000", "10127", "999999", "2147483647"})
    void reusesInstrumentTextAcrossOwnedAndBorrowedOrderDecoding(String instrumentId) {
        var command = new PlaceOrderCommand(71, instrumentId, CoreOrderSide.BUY, 101, 6,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "");
        byte[] encoded = TradingCommandCodec.encodePlaceOrder(command);
        String retained = TradingCommandCodec.decodePlaceOrder(encoded).instrumentId();
        var slot = PlaceOrderCommand.commandSlot();
        TradingCommandCodec.decodePlaceOrderInto(encoded, 0, encoded.length, slot);
        assertThat(slot.instrumentId()).isSameAs(retained);
        assertThat(TradingCommandCodec.decodePlaceOrder(encoded).instrumentId()).isSameAs(retained);
        java.util.Arrays.fill(encoded, (byte) 0);
        assertThat(retained).isEqualTo(instrumentId);
        assertThat(slot.instrumentId()).isEqualTo(instrumentId);
    }

    @Test
    void leverageRepricingRequiresAnExplicitVersionAndKeepsOldJournalBytes() {
        byte[] original = java.nio.ByteBuffer.allocate(15).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1).put((byte) '1').putInt(CoreMarginMode.CROSS.wireCode())
                .putLong(5_000_000).array();
        var old = TradingCommandCodec.decodeUpdateLeverage(original);
        assertThat(old.repriceCrossMargin()).isFalse();
        assertThat(TradingCommandCodec.encodeUpdateLeverage(old)).isEqualTo(original);
        var command = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000, true);
        byte[] encoded = TradingCommandCodec.encodeUpdateLeverage(command);
        assertThat(TradingCommandCodec.decodeUpdateLeverage(encoded)).isEqualTo(command);
        encoded[15] = 3;
        assertThatThrownBy(() -> TradingCommandCodec.decodeUpdateLeverage(encoded))
                .hasMessageContaining("unsupported leverage command version");
        assertThatThrownBy(() -> TradingCommandCodec.decodeUpdateLeverage(java.util.Arrays.copyOf(original, 16)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void decodedTextOwnsItsBytesAndRespectsAnEmbeddedAmendFrame() {
        for (String clientId : new String[]{"", "client-71", "订单-é-😀"}) {
            var command = new AmendOrderCommand(71, 72, clientId, null, null, null, null);
            byte[] encoded = TradingCommandCodec.encodeAmendOrder(command);
            byte[] envelope = new byte[encoded.length + 13];
            System.arraycopy(encoded, 0, envelope, 7, encoded.length);
            var decoded = TradingCommandCodec.decodeAmendOrder(envelope, 7, encoded.length);
            java.util.Arrays.fill(envelope, (byte) 0);
            assertThat(decoded).isEqualTo(command);
            if (clientId.isEmpty()) assertThat(decoded.newClientOrderId()).isSameAs("");
            for (int length = 0; length < encoded.length; length++) {
                int truncated = length;
                assertThatThrownBy(() -> TradingCommandCodec.decodeAmendOrder(encoded, 0, truncated))
                        .isInstanceOf(ProtocolException.class);
            }
        }
    }

    @Test
    void replacementDecodesWithinItsFrameAndDoesNotRetainTheInput() {
        var order = new PlaceOrderCommand(72, "1", CoreOrderSide.BUY, 101, 6,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "订单-é-😀");
        var command = new ReplaceOrderCommand(71, order);
        byte[] encoded = TradingCommandCodec.encodeReplaceOrder(command);
        assertThat(TradingCommandCodec.decodeReplaceOrder(encoded)).isEqualTo(command);
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> TradingCommandCodec.decodeReplaceOrder(truncated))
                    .isInstanceOf(ProtocolException.class);
        }
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> TradingCommandCodec.decodeReplaceOrder(trailing))
                .isInstanceOf(ProtocolException.class);
        var decoded = TradingCommandCodec.decodeReplaceOrder(encoded);
        java.util.Arrays.fill(encoded, (byte) 0);
        assertThat(decoded).isEqualTo(command);
    }

    @Test
    void requiredAndTransferTextSurviveInputReuse() {
        var balance = new BalanceAdjustmentCommand("USDT", 10);
        byte[] encoded = TradingCommandCodec.encodeBalanceAdjustment(balance);
        var restored = TradingCommandCodec.decodeBalanceAdjustment(encoded);
        java.util.Arrays.fill(encoded, (byte) 0);
        assertThat(restored).isEqualTo(balance);
        var transfer = new TransferFundsCommand(91L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 250L,
                "transfer-91", "调拨-é-😀", 7L, 7L);
        byte[] bytes = TradingCommandCodec.encodeTransferFunds(transfer);
        var decoded = TradingCommandCodec.decodeTransferFunds(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        assertThat(decoded).isEqualTo(transfer);
    }

    @Test
    void roundTripsLeanPlaceOrderIntent() {
        PlaceOrderCommand command = new PlaceOrderCommand(71, "1", CoreOrderSide.BUY, 101, 6, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prices-71");

        PlaceOrderCommand restored = TradingCommandCodec.decodePlaceOrder(
                TradingCommandCodec.encodePlaceOrder(command));

        assertThat(restored).isEqualTo(command);
    }

    @Test
    void roundTripsProductTransferCommands() {
        TransferFundsCommand transfer = new TransferFundsCommand(91L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 250L,
                "transfer-91", "product allocation", 7L, 7L);

        assertThat(TradingCommandCodec.decodeTransferFunds(
                TradingCommandCodec.encodeTransferFunds(transfer))).isEqualTo(transfer);
        assertThat(TradingCommandCodec.decodeCompleteTransfer(
                TradingCommandCodec.encodeCompleteTransfer(new CompleteTransferCommand(91L))))
                .isEqualTo(new CompleteTransferCommand(91L));
        assertThat(CorePendingTransferCodec.decode(CorePendingTransferCodec.encode(java.util.List.of(transfer))))
                .containsExactly(transfer);
        assertThatThrownBy(() -> new TransferFundsCommand(92L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, "USDT_PERPETUAL", "FUNDING", "USDT", 250L,
                "transfer-92", "invalid route", 7L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match product line");
    }

    @Test
    void rejectsTransferWithoutExplicitSenderAndRecipient() {
        var transfer = new TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 37, "old-format", "", 7, 7);
        byte[] current = TradingCommandCodec.encodeTransferFunds(transfer);
        byte[] implicitUsers = new byte[current.length - 16];
        java.nio.ByteBuffer.wrap(implicitUsers).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putLong(91);
        System.arraycopy(current, 28, implicitUsers, 12, current.length - 28);
        assertThatThrownBy(() -> TradingCommandCodec.decodeTransferFunds(implicitUsers))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsV2PlaceOrder() {
        PlaceOrderCommand command = new PlaceOrderCommand(72, "1", CoreOrderSide.SELL, 101, 2, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "prices-72");
        byte[] encoded = TradingCommandCodec.encodePlaceOrder(command);
        ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 2);

        assertThatThrownBy(() -> TradingCommandCodec.decodePlaceOrder(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported Core protocol version: 2");
    }

    @Test
    void roundTripsAllP2Commands() {
        BalanceAdjustmentCommand adjustment = new BalanceAdjustmentCommand("USDT", 10_000);
        PlaceOrderCommand placeOrder = new PlaceOrderCommand(7, "1", CoreOrderSide.BUY, 0, 3, false, CoreMarginMode.ISOLATED, CorePositionSide.LONG, CoreOrderType.MARKET, CoreTimeInForce.FOK, false, "client-7");
        CancelOrderCommand cancelOrder = new CancelOrderCommand(7);
        ReplaceOrderCommand replaceOrder = new ReplaceOrderCommand(6, placeOrder);
        AmendOrderCommand amendOrder = new AmendOrderCommand(6, 8, "client-8", 61_000L,
                4L, CoreTimeInForce.GTC, true);
        RegisterInstrumentCommand instrument = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE,
                        10_000_000, 100_000, 50_000, 0))),
                -10, 20, 0, -1, 0);
        ApplyMarkPriceCommand markPrice = new ApplyMarkPriceCommand("1", 60_500,
                60_000, 60_250, 9, 1_700_000_000_000L);
        ApplyFundingCommand funding = new ApplyFundingCommand(11, "1", 100, 0, 128);
        ApplyFundingCommand chunkedFunding = new ApplyFundingCommand(12, "1", -100,
                42, 128);
        SettleInstrumentCommand settlement = new SettleInstrumentCommand(12, "1", 61_000, 0, 128);
        ExecuteLiquidationCommand liquidation = new ExecuteLiquidationCommand(13, 9, 59_000, 25_000);
        ExecuteLiquidationCommand chunkedLiquidation = new ExecuteLiquidationCommand(
                14, 10, 58_000, 30_000, 91, 512);
        ExecuteAdlCommand adl = new ExecuteAdlCommand(13, 18, "1", CoreMarginMode.CROSS,
                CorePositionSide.NET, -3, 58_000, 9, 2, 7);
        ResolveLiquidationCommand resolution = new ResolveLiquidationCommand(13,
                ResolveLiquidationCommand.Resolution.INSURANCE, 7);
        ContinueRiskScanCommand continuation = new ContinueRiskScanCommand(256);
        UpdatePositionModeCommand mode = new UpdatePositionModeCommand(CorePositionMode.HEDGE);
        AdjustPositionMarginCommand margin = new AdjustPositionMarginCommand("1", CorePositionSide.LONG, 500);
        AdjustInsuranceFundCommand insuranceFund = new AdjustInsuranceFundCommand("USDT", 500);
        UpdateLeverageCommand leverage = new UpdateLeverageCommand("1", CoreMarginMode.CROSS, 5_000_000L);
        UpsertFeePolicyCommand feePolicy = new UpsertFeePolicyCommand(31, 7, 1001, "1",
                -25, 100, 4, true, 1_700_000_000_000L, 0);

        assertThat(TradingCommandCodec.decodeBalanceAdjustment(
                TradingCommandCodec.encodeBalanceAdjustment(adjustment))).isEqualTo(adjustment);
        assertThat(TradingCommandCodec.decodePlaceOrder(
                TradingCommandCodec.encodePlaceOrder(placeOrder))).isEqualTo(placeOrder);
        assertThat(TradingCommandCodec.decodeCancelOrder(
                TradingCommandCodec.encodeCancelOrder(cancelOrder))).isEqualTo(cancelOrder);
        assertThat(TradingCommandCodec.decodeReplaceOrder(
                TradingCommandCodec.encodeReplaceOrder(replaceOrder))).isEqualTo(replaceOrder);
        assertThat(TradingCommandCodec.decodeUpsertFeePolicy(
                TradingCommandCodec.encodeUpsertFeePolicy(feePolicy))).isEqualTo(feePolicy);
        assertThat(TradingCommandCodec.decodeAmendOrder(
                TradingCommandCodec.encodeAmendOrder(amendOrder))).isEqualTo(amendOrder);
        assertThat(TradingCommandCodec.decodeRegisterInstrument(
                TradingCommandCodec.encodeRegisterInstrument(instrument, ProductLine.LINEAR_PERPETUAL), ProductLine.LINEAR_PERPETUAL)).isEqualTo(instrument);
        assertThat(TradingCommandCodec.decodeApplyMarkPrice(
                TradingCommandCodec.encodeApplyMarkPrice(markPrice, ProductLine.OPTION), ProductLine.OPTION)).isEqualTo(markPrice);
        byte[] archivedMark = java.util.Arrays.copyOf(
                TradingCommandCodec.encodeApplyMarkPrice(markPrice, ProductLine.OPTION),
                TradingCommandCodec.encodeApplyMarkPrice(markPrice, ProductLine.OPTION).length - Long.BYTES);
        assertThatThrownBy(() -> TradingCommandCodec.decodeApplyMarkPrice(archivedMark, ProductLine.OPTION))
                .isInstanceOf(ProtocolException.class);
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
                new SettleInstrumentCommand(12, "1", 61_000));

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
        RegisterInstrumentCommand command = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                0, 0, 0, -1, 0);
        byte[] current = TradingCommandCodec.encodeRegisterInstrument(command, ProductLine.LINEAR_PERPETUAL);
        int appendedRiskPolicyBytes = Integer.BYTES * 3 + Long.BYTES * 9;
        byte[] withoutRiskPolicy = java.util.Arrays.copyOf(current, current.length - appendedRiskPolicyBytes);

        assertThatThrownBy(() -> TradingCommandCodec.decodeRegisterInstrument(withoutRiskPolicy, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void registerInstrumentRoundTripPreservesTradingAdmissionSettings() {
        var command = new RegisterInstrumentCommand(
                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                new CoreInstrumentRiskLimits(
                        100_000, 10_000_000, 1_000_000, 0, 1_000_000,
                        java.util.List.of(new CoreRiskLimitBracket(1_000_000, 10_000_000,
                        100_000, 50_000, 0))),
                0, 0, 0, -1, 0, 3, false, false, true, 0b01, 0b0011, 1L);

        assertThat(TradingCommandCodec.decodeRegisterInstrument(
                TradingCommandCodec.encodeRegisterInstrument(command, ProductLine.LINEAR_PERPETUAL), ProductLine.LINEAR_PERPETUAL)).isEqualTo(command);
        byte[] encoded = TradingCommandCodec.encodeRegisterInstrument(command, ProductLine.LINEAR_PERPETUAL);
        assertThatThrownBy(() -> TradingCommandCodec.decodeRegisterInstrument(
                java.util.Arrays.copyOf(encoded, encoded.length - Long.BYTES), ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void spotRegistrationHasNoDerivativeRiskPayload() {
        var risk = new CoreInstrumentRiskLimits(100_000, 10_000_000, 1_000_000, 0, 1_000_000,
                List.of(new CoreRiskLimitBracket(1_000_000, 10_000_000, 100_000, 50_000, 0)));
        var spot = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 1, 1, 1,
                null, 0, 0, 0, -1, 0);
        var derivative = new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT", 1, 1, 1,
                risk, 0, 0, 0, -1, 0);
        byte[] spotBytes = TradingCommandCodec.encodeRegisterInstrument(spot, ProductLine.SPOT);
        byte[] derivativeBytes = TradingCommandCodec.encodeRegisterInstrument(derivative, ProductLine.LINEAR_PERPETUAL);
        assertThat(TradingCommandCodec.decodeRegisterInstrument(spotBytes, ProductLine.SPOT)).isEqualTo(spot);
        assertThat(TradingCommandCodec.decodeRegisterInstrument(derivativeBytes, ProductLine.LINEAR_PERPETUAL))
                .isEqualTo(derivative);
        assertThat(derivativeBytes.length - spotBytes.length).isEqualTo(101);
        assertThatThrownBy(() -> TradingCommandCodec.decodeRegisterInstrument(spotBytes, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TradingCommandCodec.decodeRegisterInstrument(derivativeBytes, ProductLine.SPOT))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void responseRoundTripPreservesOriginalCommandStatusAndData() {
        CoreResponse response = new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.REJECTED,
                CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE, 9, new byte[] {1, 2, 3});

        CoreResponse restored = CoreProtocol.decodeResponse(CoreProtocol.responsePayload(response));

        assertThat(restored.status()).isEqualTo(response.status());
        assertThat(restored.commandStatus()).isEqualTo(response.commandStatus());
        assertThat(restored.resultCode()).isEqualTo(response.resultCode());
        assertThat(restored.committedCoreSequence()).isEqualTo(response.appliedCommandCount());
        assertThat(restored.appliedCommandCount()).isEqualTo(response.appliedCommandCount());
        assertThat(restored.data()).containsExactly(response.data());
    }
}
