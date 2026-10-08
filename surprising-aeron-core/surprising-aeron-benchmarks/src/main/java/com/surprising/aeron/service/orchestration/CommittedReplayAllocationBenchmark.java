package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Separate-process replay of a maker/taker fill, including matching completion and event capture. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
        "-Dsurprising.aeron.matching-engines=1", "-Dsurprising.aeron.matcher-window-size=256"})
public class CommittedReplayAllocationBenchmark {
    CommittedTradeReplay replay;
    long sequence, orderId;
    static final long TIME = 1_700_000_000_000L;
    @Setup(Level.Iteration) public void setup() {
        sequence = orderId = 0;
        replay = new CommittedTradeReplay(ProductLine.LINEAR_PERPETUAL, null);
        send(CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                new RegisterInstrumentCommand("604", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
        for (long user : new long[]{7, 8}) send(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1_000_000_000_000L)));
        send(CoreMessageType.APPLY_MARK_PRICE, 0,
                TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("604", 100, 1, TIME)));
    }
    @Benchmark public long makerTakerFill() {
        place(7, CoreOrderSide.SELL);
        var frames = place(8, CoreOrderSide.BUY);
        if (frames.stream().filter(f -> f.kind() == RealtimeFrame.Kind.TRADE).count() != 1)
            throw new IllegalStateException("replay lost a committed fill");
        return replay.exportSequence();
    }
    private List<RealtimeFrame> place(long user, CoreOrderSide side) {
        long id = ++orderId;
        return send(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(id, "604", side, 100, 1, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "replay-" + id)));
    }
    private List<RealtimeFrame> send(CoreMessageType type, long user, byte[] payload) {
        long seq = ++sequence;
        return replay.apply(CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(type,
                new UUID(99, seq), ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS, 991, seq, user, TIME, seq), payload)), TIME, seq);
    }
    @TearDown(Level.Iteration) public void close() {
        try {
            byte[] snapshot = replay.snapshot();
            try (var restored = new CommittedTradeReplay(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                if (restored.businessHash() != replay.businessHash() || restored.exportSequence() != replay.exportSequence())
                    throw new IllegalStateException("replay snapshot recovery differs");
            }
        } finally { replay.close(); }
    }
}
