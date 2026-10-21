package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CoreSpotAssetUnitsTest {
    private long sequence;

    @ParameterizedTest
    @CsvSource({"false,false,LIMIT,IOC", "true,false,LIMIT,IOC", "false,true,LIMIT,IOC", "true,true,LIMIT,IOC",
            "false,false,MARKET,IOC", "true,false,MARKET,IOC", "false,true,MARKET,IOC", "true,true,MARKET,IOC",
            "false,false,MARKET,FOK", "true,false,MARKET,FOK", "false,true,MARKET,FOK", "true,true,MARKET,FOK"})
    void freezesSettlesAndReleasesConfiguredAssetUnits(boolean fragmented, boolean restoreBeforeFill,
            CoreOrderType type, CoreTimeInForce tif) {
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
            var filled = place(runtime, 101, 3, CoreOrderSide.BUY, 3, type == CoreOrderType.MARKET ? 500_050 : 500_000, tif, type);

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
            if (type == CoreOrderType.MARKET) {
                place(runtime, 101, 5, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
                var sell = place(runtime, 202, 6, CoreOrderSide.SELL, 2, 399_960, tif, CoreOrderType.MARKET);
                assertThat(sell.status()).isEqualTo("FILLED");
                assertThat(runtime.tradingState().user(101).totalUnits("BTC")).isEqualTo(500_000);
                assertThat(runtime.tradingState().user(202).totalUnits("BTC")).isEqualTo(500_000);
                assertThat(runtime.tradingState().user(101).totalUnits("USDT")).isEqualTo(76_990_900_000L);
                assertThat(runtime.tradingState().user(202).totalUnits("USDT")).isEqualTo(22_993_000_000L);
                assertThat(runtime.tradingState().treasuryState().feeBalances().get("USDT")).isEqualTo(16_100_000L);
                assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isZero();
                assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
            }
        } finally {
            if (runtime != original) runtime.close();
            original.close();
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void amendedSpotReserveSurvivesRestoreAndInsufficientFundsLeaveTheOriginalOpen(boolean restore, boolean partiallyFilled) {
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        TradingCoreRuntime runtime = original;
        try {
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            fund(runtime, 101, "USDT", 100_000_000_000L);
            fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 101, 41, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            if (partiallyFilled) place(runtime, 202, 40, CoreOrderSide.SELL, 1, 400_000, CoreTimeInForce.IOC);
            if (restore) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(73));
            // Until replacement/cancel, the original reservation retains its unused fee
            // buffer: initial 8_004_000_000 minus the 4_000_800_000 actual maker fill.
            long originalReserve = runtime.tradingState().user(101).balances().get("USDT").lockedUnits();
            assertThat(originalReserve).isEqualTo(partiallyFilled ? 4_003_200_000L : 8_004_000_000L);
            var rejected = execute(runtime, CoreMessageType.AMEND_ORDER, 101,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(41, 42, "amend-rejected",
                            450_000L, 1000L, CoreTimeInForce.GTC, false)));
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(originalReserve);
            var amended = apply(runtime, CoreMessageType.AMEND_ORDER, 101,
                    TradingCommandCodec.encodeAmendOrder(new AmendOrderCommand(41, 43, "amend-valid",
                            450_000L, 3L, CoreTimeInForce.GTC, false)));
            var result = CoreCommandResultCodec.decode(amended.data()).orders();
            assertThat(result).anySatisfy(order -> { assertThat(order.orderId()).isEqualTo(41); assertThat(order.status()).isEqualTo("CANCELED"); });
            assertThat(result).anySatisfy(order -> { assertThat(order.orderId()).isEqualTo(43); assertThat(order.status()).isEqualTo("OPEN"); });
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(13_506_750_000L);
            place(runtime, 202, 44, CoreOrderSide.SELL, 3, 450_000, CoreTimeInForce.IOC);
            var state = runtime.tradingState();
            assertThat(state.user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(partiallyFilled ? 82_496_500_000L : 86_497_300_000L);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(partiallyFilled ? 17_491_250_000L : 13_493_250_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(partiallyFilled ? 12_250_000L : 9_450_000L);
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(partiallyFilled ? 400_000 : 300_000);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(partiallyFilled ? 600_000 : 700_000);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
        } finally {
            if (runtime != original) runtime.close();
            original.close();
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelBatchReleasesOnlyOwnedOrdersAndPreservesMixedResultsAfterRestore(boolean restore) {
        var original = new TradingCoreRuntime(ProductLine.SPOT);
        TradingCoreRuntime runtime = original;
        try {
            var registration = new RegisterInstrumentCommand("1", 0, "BTC", "USDT", "USDT",
                    10_000, 10_000_000, 1, 1_000_000, 1_000_000, 200, 500, 0, -1, 0,
                    1_000_000, 1_000_000_000_000_000L, 0, 1_000_000_000_000_000L,
                    List.of(new CoreRiskLimitBracket(1, 0, 1_000_000_000_000_000L, 1_000_000, 1_000_000, 1_000_000)),
                    1, true, true, false, 0b11, 0b1111, 100_000);
            apply(runtime, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(registration));
            fund(runtime, 101, "USDT", 100_000_000_000L); fund(runtime, 202, "BTC", 1_000_000L);
            place(runtime, 101, 41, CoreOrderSide.BUY, 2, 400_000, CoreTimeInForce.GTC);
            place(runtime, 101, 43, CoreOrderSide.BUY, 3, 410_000, CoreTimeInForce.GTC);
            place(runtime, 202, 40, CoreOrderSide.SELL, 1, 410_000, CoreTimeInForce.IOC);
            place(runtime, 202, 44, CoreOrderSide.SELL, 1, 500_000, CoreTimeInForce.GTC);
            if (restore) runtime = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot(74));
            long beforeRejected = runtime.tradingState().user(101).balances().get("USDT").lockedUnits();
            var rejected = execute(runtime, CoreMessageType.CANCEL_ORDER_BATCH, 101,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(41), new CancelOrderCommand(44)))));
            assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.ORDER_OWNER_MISMATCH);
            assertThat(runtime.tradingState().user(101).balances().get("USDT").lockedUnits()).isEqualTo(beforeRejected);
            var result = apply(runtime, CoreMessageType.CANCEL_ORDER_BATCH, 101,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(41), new CancelOrderCommand(43),
                            new CancelOrderCommand(999)))));
            var items = TradingOrderBatchCodec.decodeResult(result.data()).items();
            assertThat(items).extracting(CoreOrderBatchResult.Item::status).containsExactly(
                    ResponseStatus.APPLIED, ResponseStatus.APPLIED, ResponseStatus.REJECTED);
            assertThat(items.get(2).resultCode()).isEqualTo(CoreResultCode.ORDER_NOT_FOUND);
            var state = runtime.tradingState();
            assertThat(state.user(101).balances().get("USDT").lockedUnits()).isZero();
            assertThat(state.user(202).balances().get("BTC").lockedUnits()).isEqualTo(100_000);
            assertThat(state.user(101).totalUnits("BTC")).isEqualTo(100_000);
            assertThat(state.user(202).totalUnits("BTC")).isEqualTo(900_000);
            assertThat(state.user(101).totalUnits("USDT")).isEqualTo(95_899_180_000L);
            assertThat(state.user(202).totalUnits("USDT")).isEqualTo(4_097_950_000L);
            assertThat(state.treasuryState().feeBalances().get("USDT")).isEqualTo(2_870_000L);
            assertThat(state.user(101).totalUnits("USDT") + state.user(202).totalUnits("USDT")
                    + state.treasuryState().feeBalances().get("USDT")).isEqualTo(100_000_000_000L);
            apply(runtime, CoreMessageType.CANCEL_ORDER, 202, TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(44)));
            assertThat(runtime.tradingState().user(202).balances().get("BTC").lockedUnits()).isZero();
        } finally { if (runtime != original) runtime.close(); original.close(); }
    }

    private CoreResponse apply(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        var result = execute(runtime, type, user, payload);
        assertThat(result.commandStatus()).as(type + " " + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return result;
    }

    private void fund(TradingCoreRuntime runtime, long user, String asset, long units) {
        apply(runtime, CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)));
    }

    private CoreOrderStateView place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side, long quantity, long price, CoreTimeInForce tif) {
        return place(runtime, user, id, side, quantity, price, tif, CoreOrderType.LIMIT);
    }

    private CoreOrderStateView place(TradingCoreRuntime runtime, long user, long id, CoreOrderSide side,
            long quantity, long price, CoreTimeInForce tif, CoreOrderType type) {
        var response = apply(runtime, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, "1", side, price, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                type, tif, false, "spot-units-" + id)));
        return CoreCommandResultCodec.decode(response.data()).orders().stream()
                .filter(order -> order.orderId() == id).findFirst().orElseThrow();
    }

    private CoreResponse execute(TradingCoreRuntime runtime, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 991, seq, user, 1_700_000_000_000L + seq, seq), payload);
        var result = runtime.apply(message);
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (result == null && runtime.directCommand.active() && System.nanoTime() < directDeadline) {
            result = runtime.directCommand.poll(runtime);
            if (result == null) Thread.onSpinWait();
        }
        if (result == null && runtime.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + type);
        }
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = com.surprising.aeron.service.orchestration.trade.CoreTestCompletion.completeMatchingSynchronously(
                    runtime, runtime.matchingSequence(message.header().commandId()), 1_700_000_000_000L + seq, seq);
        }
        return result;
    }
}
