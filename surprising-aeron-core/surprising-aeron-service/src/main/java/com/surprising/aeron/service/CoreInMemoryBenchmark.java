package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.UUID;

public final class CoreInMemoryBenchmark {

    private static final String SYMBOL = "BENCH-BTC-USDT";
    private static final long USER_ID = 1001;
    private static final long BALANCE_UNITS = 1_000_000_000_000L;

    private CoreInMemoryBenchmark() {
    }

    public static void main(String[] args) {
        int orders = positive(args, 0, 100_000);
        int warmupOrders = positive(args, 1, 10_000);
        run(warmupOrders, false);
        Result result = run(orders, true);
        System.out.printf("inMemoryCoreBenchmark=PASS orders=%d elapsedSeconds=%.3f "
                        + "ordersPerSec=%.3f p50Micros=%d p95Micros=%d p99Micros=%d maxMicros=%d%n",
                result.orders(), result.elapsedNanos() / 1_000_000_000.0,
                result.orders() / (result.elapsedNanos() / 1_000_000_000.0),
                percentile(result.latencies(), 0.50), percentile(result.latencies(), 0.95),
                percentile(result.latencies(), 0.99), percentile(result.latencies(), 1.0));
    }

    private static Result run(int orderCount, boolean measured) {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            long gatewaySequence = 1;
            long operationsSequence = 1;
            applied(state, CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, operationsSequence++, 1,
                    TradingCommandCodec.encodeUpsertInstrument(instrument()));
            applied(state, CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, gatewaySequence++, 2,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", BALANCE_UNITS)));
            long[] latencies = measured ? new long[orderCount] : new long[0];
            long started = System.nanoTime();
            for (int index = 0; index < orderCount; index++) {
                long orderId = 1_000_000L + index;
                long placeSequence = gatewaySequence++;
                long cancelSequence = gatewaySequence++;
                long operationStarted = System.nanoTime();
                applied(state, CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, placeSequence,
                        10L + index * 2, TradingCommandCodec.encodePlaceOrder(place(orderId)));
                applied(state, CoreMessageType.CANCEL_ORDER, CommandSource.GATEWAY, cancelSequence,
                        11L + index * 2,
                        TradingCommandCodec.encodeCancelOrder(new com.surprising.aeron.protocol.CancelOrderCommand(orderId)));
                if (measured) latencies[index] = System.nanoTime() - operationStarted;
                if ((index & 255) == 255 || index == orderCount - 1) {
                    long throughSequence = 2L + (index + 1L) * 4L;
                    applied(state, CoreMessageType.ACK_EXPORT, CommandSource.OPERATIONS,
                            operationsSequence++, 2_000_000L + index,
                            CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));
                }
            }
            return new Result(orderCount, System.nanoTime() - started, latencies);
        }
    }

    private static PlaceOrderCommand place(long orderId) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, "BTC", "USDT", "USDT", CoreOrderSide.BUY,
                1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, ReservationKind.SPOT_ASSET,
                "USDT", 1_000, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 1_000, false,
                "bench-" + orderId, 0, 0);
    }

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT",
                1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }

    private static void applied(CoreProbeState state, CoreMessageType type, CommandSource source,
                                long sourceSequence, long correlationId, byte[] payload) {
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                source, source == CommandSource.OPERATIONS ? 9 : 7, sourceSequence, type == CoreMessageType.ACK_EXPORT
                        ? 0 : USER_ID, 1_000, correlationId), payload);
        int pendingBefore = state.pendingMatchingCount();
        ResponseStatus status = state.apply(message).status();
        if (status != ResponseStatus.APPLIED && status != ResponseStatus.OK) {
            throw new IllegalStateException("benchmark command rejected type=" + type);
        }
        while (state.pendingMatchingCount() > pendingBefore) {
            long sequence = state.firstPendingMatchingSequence();
            com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (matching == null && System.nanoTime() < deadline) {
                matching = state.takeMatchingResult(sequence);
                if (matching == null) Thread.onSpinWait();
            }
            if (matching == null) throw new IllegalStateException("benchmark matching timed out");
            if (state.completeMatching(sequence, matching, message.header().submittedAtEpochMillis(), sequence)
                    == null) {
                throw new IllegalStateException("benchmark matching completion lost");
            }
        }
    }

    private static int positive(String[] args, int index, int fallback) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value <= 0) throw new IllegalArgumentException("benchmark counts must be positive");
        return value;
    }

    private static long percentile(long[] values, double fraction) {
        if (values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000L;
    }

    private record Result(int orders, long elapsedNanos, long[] latencies) {
    }
}
