package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.trade.CoreTestCompletion;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class CommittedDeliveryReplayTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence, now = TIME;
    private TradingCoreRuntime reference;
    @AfterEach void closeReference() { if (reference != null) reference.close(); }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY"})
    void settlementCashIgnoresReleasedMarginAndSurvivesPageSnapshotAndDuplicate(ProductLine line) {
        byte[] snapshot;
        long pnl = line == ProductLine.LINEAR_DELIVERY ? 100 : 9;
        try (var replay = opened(line)) {
            send(replay, line, CoreMessageType.PLACE_ORDER, 1,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(201, "1", CoreOrderSide.SELL,
                            111, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "delivery-resting")));
            now = TIME + 20_000;
            send(replay, line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 110, 0, 0, 1, 0, 10)));
            var progress = assertApplied(replay, line);
            var page = replay.deliveryPage();
            assertThat(page).as("settlement progress " + progress).isNotNull();
            assertThat(page.cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(1,
                    line == ProductLine.LINEAR_DELIVERY ? "USDT" : "BTC", -pnl, 100_000 - pnl));
            snapshot = replay.snapshot();
        }
        try (var replay = new CommittedTradeReplay(line, snapshot)) {
            var message = message(line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 110, 0, 1, 1, 0, 10)));
            byte[] encoded = CoreMessageCodec.encode(message);
            replay.apply(encoded, now, sequence * 4096);
            CoreTestCompletion.applyAsynchronously(reference, message, now, sequence * 4096);
            assertThat(replay.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
            assertThat(replay.deliveryPage().cashFlows()).containsExactly(new CommittedTradeReplay.DeliveryCashFlow(2,
                    line == ProductLine.LINEAR_DELIVERY ? "USDT" : "BTC", pnl, 100_000 + pnl));
            long hash = replay.businessHash();
            replay.apply(encoded, now, (sequence + 1) * 4096);
            CoreTestCompletion.applyAsynchronously(reference, message, now, (sequence + 1) * 4096);
            assertThat(replay.deliveryPage()).isNull();
            assertThat(replay.businessHash()).isEqualTo(hash);
            assertThat(replay.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY"})
    void flatSettlementHasNoInventedCashMovement(ProductLine line) {
        try (var replay = opened(line)) {
            now = TIME + 20_000;
            send(replay, line, CoreMessageType.SETTLE_INSTRUMENT, 0,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(now, "1", 100, 0, 0, 2, 0, 10)));
            assertThat(assertApplied(replay, line).complete()).isTrue();
            assertThat(replay.deliveryPage()).isNull();
        }
    }

    private CommittedTradeReplay opened(ProductLine line) {
        var replay = new CommittedTradeReplay(line, null);
        reference = new TradingCoreRuntime(line);
        var type = ContractType.valueOf(line.contractTypeCode());
        var asset = type.isInverse() ? "BTC" : "USDT";
        send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                        "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                        100_000, 50_000, 0, 0, TIME + 10_000, -1, 0)));
        send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
        for (long user = 1; user <= 2; user++) {
            send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000)));
            send(replay, line, CoreMessageType.PLACE_ORDER, user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                            user == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "delivery-" + user)));
        }
        return replay;
    }

    private void send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] payload) {
        var command = message(line, type, user, payload);
        replay.apply(CoreMessageCodec.encode(command), now, sequence * 4096);
        CoreTestCompletion.applyAsynchronously(reference, command, now, sequence * 4096);
        assertThat(replay.businessHash()).as("replay cash observation preserves online trading state for " + type).isEqualTo(reference.snapshotBusinessStateHash());
        if (user > 0) assertThat(replay.commandResult().status()).as("fixture command " + type + " code=" + replay.commandResult().code()).isEqualTo(ResponseStatus.APPLIED);
    }
    private CoreSettlementProgressView assertApplied(CommittedTradeReplay replay, ProductLine line) {
        try (var probe = TradingCoreRuntime.fromSnapshot(line, replay.snapshot())) {
            var result = probe.resultLedger.get(new UUID(117, sequence));
            assertThat(result).as("committed settlement result").isNotNull();
            assertThat(result.status()).as("settlement code=" + result.resultCode()).isEqualTo(ResponseStatus.APPLIED);
            return CoreSettlementProgressCodec.decode(result.responseData());
        }
    }
    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(117, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, now, seq), payload);
    }
}
