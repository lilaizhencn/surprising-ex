package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommittedLiquidationReplayTest {
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test void originalCommandFactsSurviveSnapshotAndDoNotChangeTradingState() {
        try (var reference = new TradingCoreRuntime(LINE)) {
            byte[] snapshot;
            LiquidationChange planned;
            try (var replay = new CommittedTradeReplay(LINE, null)) {
                send(replay, reference, CoreMessageType.REGISTER_INSTRUMENT, 0,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                "1", "BTC", "USDT", "USDT", 1, 1, 1,
                                new CoreInstrumentRiskLimits(100_000, 10_000_000, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                        List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 10_000_000, 100_000, 90_000))),
                                0, 0, 0, -1, 0), LINE));
                send(replay, reference, CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 100, 1, TIME), LINE));
                for (long user = 1; user <= 2; user++) {
                    send(replay, reference, CoreMessageType.ADJUST_BALANCE, user,
                            TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 1 ? 100_000 : 100)));
                    send(replay, reference, CoreMessageType.PLACE_ORDER, user,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "1",
                                    user == 1 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 10, false,
                                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                    CoreTimeInForce.GTC, false, "liquidation-" + user)));
                }
                var changes = new ArrayList<LiquidationChange>();
                send(replay, reference, CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("1", 95, 2, TIME + sequence + 1), LINE));
                changes.addAll(replay.liquidationChanges());
                for (int i = 0; changes.isEmpty() && i < 8; i++) {
                    send(replay, reference, CoreMessageType.CONTINUE_RISK_SCAN, 0,
                            TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(
                                    reference.runtimeState().riskScanControl().scanBatchSize())));
                    changes.addAll(replay.liquidationChanges());
                }
                planned = changes.stream().filter(c -> c.state().userId() == 2).findFirst().orElseThrow();
                assertThat(planned.logPosition()).isPositive();
                assertThat(planned.occurredAt()).isEqualTo(TIME + planned.logPosition() / 4096);
                snapshot = replay.snapshot();
            }
            try (var restored = new CommittedTradeReplay(LINE, snapshot)) {
                assertThat(restored.liquidationChanges()).isEmpty();
                var command = message(CoreMessageType.EXECUTE_LIQUIDATION, 0,
                        TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                                planned.state().liquidationId(), planned.state().triggerPriceSequence(), 95, 100_000)));
                byte[] bytes = CoreMessageCodec.encode(command);
                long position = sequence * 4096, time = TIME + sequence;
                restored.apply(bytes, time, position);
                var response = CoreTestCompletion.applyAsynchronously(reference, command, time, position);
                assertThat(response.commandStatus()).as("liquidation execution: %s", response.resultCode())
                        .isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
                var change = restored.liquidationChanges().stream()
                        .filter(c -> c.state().liquidationId() == planned.state().liquidationId()).findFirst().orElseThrow();
                var actual = reference.tradingState().riskState().liquidations().get(planned.state().liquidationId());
                assertThat(change.logPosition()).isEqualTo(position);
                assertThat(change.occurredAt()).isEqualTo(time);
                assertThat(change.state().executionPriceTicks()).isEqualTo(actual.executionPriceTicks()).isEqualTo(95);
                assertThat(change.state().liquidationFeeRatePpm()).isEqualTo(actual.liquidationFeeRatePpm());
                assertThat(change.state().liquidationFeeUnits()).isEqualTo(actual.liquidationFeeUnits());
                restored.apply(bytes, time, position + 4096);
                assertThat(restored.liquidationChanges()).isEmpty();
                assertThat(change.state().executionPriceTicks()).isEqualTo(95);
            }
        }
    }

    private void send(CommittedTradeReplay replay, TradingCoreRuntime reference, CoreMessageType type, long user, byte[] payload) {
        var command = message(type, user, payload);
        replay.apply(CoreMessageCodec.encode(command), TIME + sequence, sequence * 4096);
        var response = CoreTestCompletion.applyAsynchronously(reference, command, TIME + sequence, sequence * 4096);
        assertThat(response.commandStatus()).as("fixture %s: %s", type, response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(replay.businessHash()).isEqualTo(reference.snapshotBusinessStateHash());
    }

    private CoreMessage message(CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type, new UUID(130, seq), LINE,
                CommandSource.OPERATIONS, 830, seq, user, TIME + seq, seq), payload);
    }
}
