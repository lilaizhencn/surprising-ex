package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.UUID;

/** Measures the local perpetual path from maker submission through taker fill application. */
public final class CorePerpetualEndToEndBenchmark {

    private static final String SYMBOL = "BENCH-BTC-USDT";
    private static final long MAKER_USER_ID = 1001L;
    private static final long TAKER_USER_ID = 1002L;
    private static final long BALANCE_UNITS = 1_000_000_000_000L;

    private CorePerpetualEndToEndBenchmark() {
    }

    public static void main(String[] args) {
        int cycles = positive(args, 0, 1_000);
        int warmupCycles = positive(args, 1, 100);
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            Sequences sequences = new Sequences();
            setup(state, sequences);
            run(state, sequences, warmupCycles, false);
            Result result = run(state, sequences, cycles, true);
            System.out.printf("perpetualEndToEndBenchmark=PASS cycles=%d orders=%d elapsedSeconds=%.3f "
                            + "matchedCyclesPerSec=%.3f ordersPerSec=%.3f p50Micros=%d p95Micros=%d "
                            + "p99Micros=%d maxMicros=%d pendingMatching=%d%n",
                    result.cycles(), Math.multiplyExact(result.cycles(), 2), result.elapsedNanos() / 1_000_000_000.0,
                    result.cycles() / (result.elapsedNanos() / 1_000_000_000.0),
                    Math.multiplyExact(result.cycles(), 2) / (result.elapsedNanos() / 1_000_000_000.0),
                    percentile(result.latencies(), .50), percentile(result.latencies(), .95),
                    percentile(result.latencies(), .99), percentile(result.latencies(), 1.0),
                    state.pendingMatchingCount());
        }
    }

    private static void setup(CoreProbeState state, Sequences sequences) {
        apply(state, sequences, CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 0,
                TradingCommandCodec.encodeUpsertInstrument(instrument()));
        adjust(state, sequences, MAKER_USER_ID);
        adjust(state, sequences, TAKER_USER_ID);
    }

    private static void adjust(CoreProbeState state, Sequences sequences, long userId) {
        apply(state, sequences, CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, userId,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", BALANCE_UNITS)));
    }

    private static Result run(CoreProbeState state, Sequences sequences, int cycles, boolean measured) {
        long[] latencies = measured ? new long[cycles] : new long[0];
        long started = System.nanoTime();
        long lastExportSequence = 0;
        for (int index = 0; index < cycles; index++) {
            boolean reverse = (sequences.orderId & 1L) != 0;
            CoreOrderSide makerSide = reverse ? CoreOrderSide.BUY : CoreOrderSide.SELL;
            CoreOrderSide takerSide = reverse ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            long cycleStarted = System.nanoTime();
            lastExportSequence = placeAndComplete(state, sequences, MAKER_USER_ID, makerSide,
                    CoreTimeInForce.GTC);
            lastExportSequence = placeAndComplete(state, sequences, TAKER_USER_ID, takerSide,
                    CoreTimeInForce.IOC);
            if (measured) latencies[index] = System.nanoTime() - cycleStarted;
            if ((index & 255) == 255) {
                apply(state, sequences, CoreMessageType.ACK_EXPORT, CommandSource.OPERATIONS, 0,
                        CoreExportCodec.encodeAck(new AckExportCommand(lastExportSequence)));
            }
        }
        return new Result(cycles, System.nanoTime() - started, latencies);
    }

    private static long placeAndComplete(CoreProbeState state, Sequences sequences, long userId,
                                         CoreOrderSide side, CoreTimeInForce timeInForce) {
        long orderId = sequences.orderId++;
        CoreResponse accepted = apply(state, sequences, CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                TradingCommandCodec.encodePlaceOrder(order(orderId, side, timeInForce)));
        if (accepted.status() != ResponseStatus.OK) {
            throw new IllegalStateException("place order was not accepted: " + accepted.status());
        }
        long sequence = state.firstPendingMatchingSequence();
        if (sequence == 0) throw new IllegalStateException("matching was not queued");
        CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        if (matching == null) throw new IllegalStateException("matching timed out");
        CoreResponse completed = state.completeMatching(sequence, matching, 1_000L, sequences.clusterPosition++);
        if (completed == null || completed.status() != ResponseStatus.APPLIED) {
            throw new IllegalStateException("matching was not applied");
        }
        return completed.requiredExportSequence();
    }

    private static CoreResponse apply(CoreProbeState state, Sequences sequences, CoreMessageType type,
                                      CommandSource source, long userId, byte[] payload) {
        long sourceSequence = source == CommandSource.OPERATIONS
                ? sequences.operationsSequence++ : sequences.gatewaySequence++;
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, source, source == CommandSource.OPERATIONS ? 9 : 7,
                sourceSequence, userId, 1_000L, sequences.clusterPosition++), payload);
        CoreResponse response = state.apply(message);
        if (response.status() != ResponseStatus.APPLIED && response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("command rejected type=" + type + " status=" + response.status());
        }
        return response;
    }

    private static PlaceOrderCommand order(long orderId, CoreOrderSide side, CoreTimeInForce timeInForce) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, "BTC", "USDT", "USDT", side,
                1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000, CoreOrderType.LIMIT, timeInForce,
                1_000, false, "perpetual-e2e-" + orderId, 0, 0);
    }

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
                "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0);
    }

    private static int positive(String[] args, int index, int fallback) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value <= 0) throw new IllegalArgumentException("benchmark counts must be positive");
        return value;
    }

    private static long percentile(long[] values, double fraction) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000L;
    }

    private static final class Sequences {
        private long gatewaySequence = 1;
        private long operationsSequence = 1;
        private long clusterPosition = 1;
        private long orderId = 1_000_000L;
    }

    private record Result(int cycles, long elapsedNanos, long[] latencies) {
    }
}
