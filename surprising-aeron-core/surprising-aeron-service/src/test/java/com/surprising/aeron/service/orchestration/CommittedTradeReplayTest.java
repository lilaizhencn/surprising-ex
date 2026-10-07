package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CommittedTradeReplayTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void capturesCommittedMakerAndTakerOrdersAndResumesFromSnapshot(ProductLine line) {
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(line, null)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("604",
                            type.ordinal(), "BTC", "USDT", type.isInverse() ? "BTC" : "USDT", 1, 1,
                            type.isInverse() ? 1000 : 1, 100_000, 50_000, 0, 0,
                            type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
            for (long user : new long[]{7, 8}) for (String asset : List.of("BTC", "USDT"))
                send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 1_000_000)));
            if (line != ProductLine.SPOT) send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(line == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("604", 100, 100, 100, 1, TIME)
                            : new ApplyMarkPriceCommand("604", 100, 1, TIME)));
            var resting = place(replay, line, 7, 201, CoreOrderSide.SELL);
            assertThat(resting).hasSize(1);
            assertThat(order(resting.getFirst()).status()).isEqualTo("OPEN");
            place(replay, line, 7, 209, CoreOrderSide.SELL);
            var batchCancel = send(replay, line, CoreMessageType.CANCEL_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(List.of(
                            new CancelOrderCommand(999), new CancelOrderCommand(209)))));
            assertThat(batchCancel.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(o -> {
                        assertThat(o.orderId()).isEqualTo(209);
                        assertThat(o.status()).isEqualTo("CANCELED");
                    });
            var amended = send(replay, line, CoreMessageType.AMEND_ORDER_BATCH, 7,
                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(List.of(
                            new AmendOrderCommand(201, 211, "repriced-211", 99L, null, null, null)))));
            assertThat(amended.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER)
                    .map(CommittedTradeReplayTest::order)).anySatisfy(o -> {
                        assertThat(o.orderId()).isEqualTo(211);
                        assertThat(o.priceTicks()).isEqualTo(99);
                    });
            snapshot = replay.snapshot();
        }
        try (var restored = new CommittedTradeReplay(line, snapshot)) {
            var filled = place(restored, line, 8, 202, CoreOrderSide.BUY);
            assertThat(filled.stream().filter(f -> f.kind() == RealtimeFrame.Kind.TRADE)).hasSize(1);
            var orders = filled.stream().filter(f -> f.kind() == RealtimeFrame.Kind.ORDER).map(CommittedTradeReplayTest::order).toList();
            assertThat(orders).hasSize(2).allSatisfy(o -> {
                assertThat(o.status()).isEqualTo("FILLED");
                assertThat(o.executedQuantitySteps()).isEqualTo(2);
                assertThat(o.remainingQuantitySteps()).isZero();
                assertThat(o.getAveragePriceTicks()).isEqualTo("99");
                assertThat(o.instrumentId()).isEqualTo("604");
                assertThat(o.productLine()).isEqualTo(line);
            });
            place(restored, line, 7, 203, CoreOrderSide.SELL);
            var canceled = send(restored, line, CoreMessageType.CANCEL_ORDER, 7,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(203)));
            assertThat(canceled).hasSize(1);
            assertThat(order(canceled.getFirst()).status()).isEqualTo("CANCELED");
            assertThat(restored.exportSequence()).isPositive();
            // A rejected cancellation must not manufacture an order or advance its revision.
            assertThat(send(restored, line, CoreMessageType.CANCEL_ORDER, 8,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(999)))).isEmpty();
        }
    }

    private List<RealtimeFrame> place(CommittedTradeReplay replay, ProductLine line, long user, long id, CoreOrderSide side) {
        return send(replay, line, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "604", side, 100, 2, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replay-" + id)));
    }

    private List<RealtimeFrame> send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        var command = new CoreMessage(CoreMessageHeader.command(type, new UUID(99, seq), line,
                CommandSource.OPERATIONS, 991, seq, user, TIME + seq, seq), payload);
        return replay.apply(CoreMessageCodec.encode(command), TIME + seq, seq);
    }

    private static CoreOrderStateView order(RealtimeFrame frame) {
        assertThat(frame.kind()).isEqualTo(RealtimeFrame.Kind.ORDER);
        return CoreStateQueryCodec.decodeOrderState(frame.payload());
    }
}
