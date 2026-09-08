package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductRecoveryLifecycleTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void nonemptySnapshotAndLogRecoverOrdersBatchesTriggersAndContinueTrading(ProductLine product) {
        ContractType type = ContractType.valueOf(product.contractTypeCode());
        String asset = type.isInverse() ? "BTC" : "USDT";
        try (CoreProbeState live = new CoreProbeState(product)) {
            apply(live, command(product, 1, 1, CoreMessageType.UPSERT_INSTRUMENT,
                    TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(
                            "BTC-USDT", 1, type.ordinal(), "BTC", "USDT", asset, 1, 1,
                            type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0,
                            type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))));
            apply(live, new CoreMessage(CoreMessageHeader.command(CoreMessageType.APPLY_MARK_PRICE,
                    UUID.randomUUID(), product, CommandSource.OPERATIONS, 992, 1, 1,
                    1_700_000_000_000L, 99), TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                    ? new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 100, 100, 1, 1_700_000_000_000L)
                    : new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, 1_700_000_000_000L))));
            apply(live, command(product, 2, 11, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(
                            product == ProductLine.SPOT ? "BTC" : asset, product == ProductLine.SPOT ? 20 : 20_000))));
            apply(live, command(product, 3, 22, CoreMessageType.ADJUST_BALANCE,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20_000))));
            apply(live, command(product, 4, 11, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(order(101, CoreOrderSide.SELL, 100, 10))));
            CoreMessage partial = command(product, 5, 22, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(order(102, CoreOrderSide.BUY, 100, 4)));
            CoreResponse partialResult = apply(live, partial);
            assertThat(live.tradingState().order(101).executedQuantitySteps()).isEqualTo(4);
            var trigger = new CoreTriggerOrderStateView(501, product, 22, "recovery-trigger", "", "BTC-USDT",
                    product == ProductLine.SPOT ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                    CoreTriggerOrderType.STOP_LOSS, CoreTriggerCondition.GREATER_OR_EQUAL,
                    200, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                    0, 0, 0, "", "qa", 0, 0, 0, 0, 1, 1, 0, 0);
            apply(live, command(product, 6, 22, CoreMessageType.PLACE_TRIGGER_ORDER,
                    CoreTriggerOrderCodec.encodeState(trigger)));
            byte[] checkpoint = live.snapshot(100);
            try (CoreProbeState recovered = CoreProbeState.fromSnapshot(product, checkpoint)) {
                parity(live, recovered);
                assertThat(apply(recovered, partial).data()).isEqualTo(partialResult.data());
                parity(live, recovered);
                List<CoreMessage> tail = List.of(
                        command(product, 7, 22, CoreMessageType.PLACE_ORDER_BATCH,
                                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                        order(201, CoreOrderSide.BUY, 90, 1), order(202, CoreOrderSide.BUY, 80, 1))))),
                        command(product, 8, 22, CoreMessageType.AMEND_ORDER_BATCH,
                                TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                                        new AmendOrderCommand(201, 203, "amended", 85L, 1L, CoreTimeInForce.GTC, false))))),
                        command(product, 9, 22, CoreMessageType.CANCEL_ORDER_BATCH,
                                TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                                        new CancelOrderCommand(203), new CancelOrderCommand(202))))),
                        command(product, 10, 22, CoreMessageType.CANCEL_TRIGGER_ORDER, CoreTriggerOrderCodec.encodeId(501)),
                        command(product, 11, 22, CoreMessageType.PLACE_ORDER,
                                TradingCommandCodec.encodePlaceOrder(order(103, CoreOrderSide.BUY, 100, 6))));
                for (CoreMessage message : tail) {
                    CoreResponse expected = apply(live, message);
                    CoreResponse actual = apply(recovered, message);
                    assertThat(actual.data()).isEqualTo(expected.data());
                    if (message.header().messageType().name().endsWith("_BATCH")) {
                        assertThat(TradingOrderBatchCodec.decodeResult(actual.data()).items())
                                .allSatisfy(item -> assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED));
                    }
                    assertThat(apply(recovered, message).data()).isEqualTo(actual.data());
                    parity(live, recovered);
                    try (CoreProbeState boundary = CoreProbeState.fromSnapshot(product, recovered.snapshot(200 + message.header().sourceSequence()))) {
                        parity(recovered, boundary);
                    }
                }
                assertThat(recovered.tradingState().orders()).isEmpty();
                assertThat(recovered.tradingState().user(11).reservations()).isEmpty();
                assertThat(recovered.tradingState().user(22).reservations()).isEmpty();
                if (product == ProductLine.SPOT) {
                    assertThat(recovered.tradingState().user(11).totalUnits("BTC")).isEqualTo(10);
                    assertThat(recovered.tradingState().user(22).totalUnits("BTC")).isEqualTo(10);
                    assertThat(recovered.tradingState().user(11).totalUnits("USDT")).isEqualTo(1_000);
                    assertThat(recovered.tradingState().user(22).totalUnits("USDT")).isEqualTo(19_000);
                } else {
                    assertThat(recovered.tradingState().user(11).totalUnits(asset)
                            + recovered.tradingState().user(22).totalUnits(asset)).isEqualTo(40_000);
                }
                var takeProfit = new CoreTriggerOrderStateView(502, product, 22, "recovery-take-profit", "", "BTC-USDT",
                        CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL,
                        120, 0, 0, 0, 0, 0, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 120, 2,
                        CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                        0, 0, 0, "", "qa", 0, 0, 0, 0, 1, 1, 0, 0);
                CoreMessage placeTrigger = command(product, 12, 22, CoreMessageType.PLACE_TRIGGER_ORDER,
                        CoreTriggerOrderCodec.encodeState(takeProfit));
                apply(live, placeTrigger);
                apply(recovered, placeTrigger);
                try (CoreProbeState afterPendingTrigger = CoreProbeState.fromSnapshot(product, recovered.snapshot(300))) {
                    CoreMessage price = command(product, 13, 1, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                    ? new ApplyMarkPriceCommand("BTC-USDT", 1, 120, 120, 120, 2, 1_700_000_000_013L)
                                    : new ApplyMarkPriceCommand("BTC-USDT", 1, 120, 2, 1_700_000_000_013L)));
                    apply(live, price);
                    apply(afterPendingTrigger, price);
                    CoreMessage execute = command(product, 14, 22, CoreMessageType.EXECUTE_TRIGGER_ORDER,
                            CoreTriggerOrderCodec.encodeExecute(502, 2, 120, 1_700_000_000_014L));
                    apply(live, execute);
                    apply(afterPendingTrigger, execute);
                    parity(live, afterPendingTrigger);
                    assertThat(afterPendingTrigger.tradingState().triggerOrders().get(502L).status())
                            .isEqualTo(CoreTriggerOrderStatus.TRIGGERED);
                    assertThat(afterPendingTrigger.tradingState().orders()).hasSize(1);
                    apply(afterPendingTrigger, price);
                    assertThat(afterPendingTrigger.tradingState().orders()).hasSize(1);
                    try (CoreProbeState afterTriggered = CoreProbeState.fromSnapshot(product, afterPendingTrigger.snapshot(301))) {
                        parity(live, afterTriggered);
                        CoreMessage close = command(product, 15, 11, CoreMessageType.PLACE_ORDER,
                                TradingCommandCodec.encodePlaceOrder(order(104, CoreOrderSide.BUY, 120, 2)));
                        apply(live, close);
                        apply(afterTriggered, close);
                        parity(live, afterTriggered);
                        assertThat(afterTriggered.tradingState().orders()).isEmpty();
                        assertThat(afterTriggered.tradingState().user(11).reservations()).isEmpty();
                        assertThat(afterTriggered.tradingState().user(22).reservations()).isEmpty();
                    }
                }
            }
        }
    }

    private static void parity(CoreProbeState expected, CoreProbeState actual) {
        assertThat(actual.tradingState().businessStateHash()).isEqualTo(expected.tradingState().businessStateHash());
        assertThat(actual.matchingStateHashAsync().join()).isEqualTo(expected.matchingStateHashAsync().join());
        assertThat(actual.committedCoreSequence()).isEqualTo(expected.committedCoreSequence());
    }

    private static PlaceOrderCommand order(long id, CoreOrderSide side, long price, long qty) {
        return new PlaceOrderCommand(id, "BTC-USDT", 1, side, price, qty, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "qa-" + id);
    }

    private static CoreMessage command(ProductLine product, long sequence, long user, CoreMessageType type, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), product, CommandSource.OPERATIONS,
                991, sequence, user, 1_700_000_000_000L + sequence, sequence), payload);
    }

    private static CoreResponse apply(CoreProbeState state, CoreMessage message) {
        CoreResponse result = state.apply(message);
        if (result.resultCode() == CoreResultCode.MATCHING_PENDING) {
            result = state.completeMatchingSynchronously(state.matchingSequence(message.header().commandId()),
                    message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        }
        while (state.firstPendingMatchingSequence() != 0) {
            state.completeMatchingSynchronously(state.firstPendingMatchingSequence(),
                    message.header().submittedAtEpochMillis(), message.header().sourceSequence());
        }
        assertThat(result.commandStatus()).as("%s: %s", message.header().messageType(), result.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
        return result;
    }
}
