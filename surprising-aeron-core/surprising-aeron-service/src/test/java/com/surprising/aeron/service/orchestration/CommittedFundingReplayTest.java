package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CommittedFundingReplayTest {
    private long sequence;
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void exportsExactPaymentsAcrossPagesSnapshotRestartAndDuplicateCommand(ProductLine line) {
        var type = ContractType.valueOf(line.contractTypeCode());
        String asset = type.isInverse() ? "BTC" : "USDT";
        byte[] snapshot;
        try (var replay = new CommittedTradeReplay(line, null)) {
            send(replay, line, CoreMessageType.REGISTER_INSTRUMENT, 0,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("1", type.ordinal(),
                            "BTC", "USDT", asset, 1, 1, type.isInverse() ? 1000 : 1,
                            100_000, 50_000, 0, 0, 0, -1, 0)));
            send(replay, line, CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long user = 1; user <= 2; user++) {
                send(replay, line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 100_000)));
                send(replay, line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                user == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "funding-" + user)));
            }
            send(replay, line, CoreMessageType.APPLY_FUNDING, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, 0, 1)));
            var page = replay.fundingPage();
            assertThat(page).isNotNull();
            assertThat(page.progress().complete()).isFalse();
            assertThat(page.progress().nextCursorUserId()).isEqualTo(1);
            assertThat(page.payments()).hasSize(1);
            assertThat(page.payments().getFirst().userId()).isEqualTo(1);
            assertThat(page.payments().getFirst().amountUnits()).isEqualTo(type.isInverse() ? 1 : 10);
            snapshot = replay.snapshot();
        }
        try (var restored = new CommittedTradeReplay(line, snapshot)) {
            var message = message(line, CoreMessageType.APPLY_FUNDING, 0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(900, "1", 10_000, 1, 1)));
            var bytes = CoreMessageCodec.encode(message);
            restored.apply(bytes, TIME, sequence * 4096);
            var page = restored.fundingPage();
            assertThat(page.progress().complete()).isTrue();
            assertThat(page.payments()).hasSize(1);
            assertThat(page.payments().getFirst().userId()).isEqualTo(2);
            assertThat(page.payments().getFirst().amountUnits()).isEqualTo(type.isInverse() ? -1 : -10);
            long hash = restored.businessHash();
            restored.apply(bytes, TIME, (sequence + 1) * 4096);
            assertThat(restored.fundingPage()).isNull();
            assertThat(restored.businessHash()).isEqualTo(hash);
        }
    }

    private void send(CommittedTradeReplay replay, ProductLine line, CoreMessageType type, long user, byte[] data) {
        var command = message(line, type, user, data);
        replay.apply(CoreMessageCodec.encode(command), TIME, sequence * 4096);
    }
    private CoreMessage message(ProductLine line, CoreMessageType type, long user, byte[] data) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(91, seq), line,
                CommandSource.OPERATIONS, 871, seq, user, TIME, seq), data);
    }
}
