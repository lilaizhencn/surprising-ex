package com.surprising.aeron.service;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
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
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-producer, single-owner benchmark for the order acceptance path before matching completion.
 */
public final class CoreAcceptFreezeConcurrentBenchmark {

    private static final String SYMBOL = "BTC-USDT";
    private static final long USER_ID = 1001L;
    private static final long BALANCE_UNITS = 1_000_000_000_000L;

    private CoreAcceptFreezeConcurrentBenchmark() {
    }

    public static void main(String[] args) {
        int orders = positive(args, 0, 2_000);
        int warmup = positive(args, 1, 200);
        int producers = positive(args, 2, 8);
        System.setProperty("surprising.aeron.benchmark.skip-matching-submit", "true");
        run(warmup, producers, false);
        Result result = run(orders, producers, true);
        System.out.printf("coreAcceptFreezeConcurrentBenchmark=PASS productLine=%s orders=%d producers=%d "
                        + "ingress=ConcurrentLinkedQueue submitSeconds=%.3f submitPerSec=%.3f "
                        + "acceptSeconds=%.3f acceptedPerSec=%.3f p50Micros=%d p95Micros=%d "
                        + "p99Micros=%d maxMicros=%d maxQueueDepth=%d pendingMatching=%d%n",
                ProductLine.LINEAR_PERPETUAL, result.orders(), producers,
                seconds(result.submitNanos()), perSecond(result.orders(), result.submitNanos()),
                seconds(result.acceptNanos()), perSecond(result.orders(), result.acceptNanos()),
                percentile(result.latencies(), 0.50), percentile(result.latencies(), 0.95),
                percentile(result.latencies(), 0.99), percentile(result.latencies(), 1.0),
                result.maxQueueDepth(), result.pendingMatching());
    }

    private static Result run(int orders, int producers, boolean measured) {
        ConcurrentLinkedQueue<SubmittedCommand> ingress = new ConcurrentLinkedQueue<>();
        AtomicInteger enqueued = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger maxQueueDepth = new AtomicInteger();
        CountDownLatch ownerReady = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        long[] latencies = measured ? new long[orders] : new long[0];

        CompletableFuture<Result> owner = CompletableFuture.supplyAsync(() -> {
            try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
                bootstrap(state);
                ownerReady.countDown();
                await(start);
                long acceptStarted = System.nanoTime();
                int accepted = 0;
                while (accepted < orders) {
                    SubmittedCommand submitted = ingress.poll();
                    if (submitted == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    CoreResponse response = state.apply(submitted.command());
                    if (response.status() != ResponseStatus.OK) {
                        throw new IllegalStateException("order was not accepted: status=" + response.status()
                                + ", resultCode=" + response.resultCode());
                    }
                    consumed.incrementAndGet();
                    if (measured) latencies[submitted.index()] = System.nanoTime() - submitted.enqueuedNanos();
                    accepted++;
                }
                return new Result(orders, 0, System.nanoTime() - acceptStarted, latencies,
                        maxQueueDepth.get(), state.pendingMatchingCount());
            } catch (Throwable failure) {
                ownerReady.countDown();
                throw failure;
            }
        });
        await(ownerReady);

        CompletableFuture<?>[] producerRuns = new CompletableFuture[producers];
        for (int producer = 0; producer < producers; producer++) {
            int producerIndex = producer;
            int from = orders * producer / producers;
            int to = orders * (producer + 1) / producers;
            producerRuns[producer] = CompletableFuture.runAsync(() -> {
                await(start);
                long sourceSequence = 1;
                for (int index = from; index < to; index++) {
                    CoreMessage command = place(index, producerIndex, sourceSequence++);
                    long enqueuedNanos = System.nanoTime();
                    ingress.offer(new SubmittedCommand(index, command, enqueuedNanos));
                    int depth = enqueued.incrementAndGet() - consumed.get();
                    maxQueueDepth.accumulateAndGet(depth, Math::max);
                }
            });
        }
        long submitStarted = System.nanoTime();
        start.countDown();
        CompletableFuture.allOf(producerRuns).join();
        long submitNanos = System.nanoTime() - submitStarted;
        Result ownerResult = owner.join();
        return new Result(orders, submitNanos, ownerResult.acceptNanos(), ownerResult.latencies(),
                ownerResult.maxQueueDepth(), ownerResult.pendingMatching());
    }

    private static void bootstrap(CoreProbeState state) {
        applied(state, CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, 9, 1,
                TradingCommandCodec.encodeUpsertInstrument(instrument()));
        applied(state, CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, 7, 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", BALANCE_UNITS)));
    }

    private static CoreMessage place(int index, int producer, long sourceSequence) {
        long orderId = 10_000_000L + index;
        PlaceOrderCommand order = new PlaceOrderCommand(orderId, SYMBOL, 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000, CoreOrderType.LIMIT,
                CoreTimeInForce.IOC, 1_000, false, "accept-freeze-concurrent-" + orderId, 0, 0);
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 100 + producer, sourceSequence, USER_ID,
                1_000, 1_000_000L + index), TradingCommandCodec.encodePlaceOrder(order));
    }

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
                "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0);
    }

    private static void applied(CoreProbeState state, CoreMessageType type, CommandSource source, long sourceId,
                                long sourceSequence, byte[] payload) {
        CoreResponse response = state.apply(new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, source, sourceId, sourceSequence, source == CommandSource.OPERATIONS
                        ? 0 : USER_ID, 1_000, sourceSequence), payload));
        if (response.status() != ResponseStatus.APPLIED && response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("bootstrap command rejected: " + type);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("benchmark interrupted", exception);
        }
    }

    private static int positive(String[] args, int index, int fallback) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value <= 0) throw new IllegalArgumentException("benchmark counts must be positive");
        return value;
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static double perSecond(int orders, long nanos) {
        return orders / seconds(nanos);
    }

    private static long percentile(long[] values, double fraction) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000L;
    }

    private record SubmittedCommand(int index, CoreMessage command, long enqueuedNanos) {
    }

    private record Result(int orders, long submitNanos, long acceptNanos, long[] latencies, int maxQueueDepth,
                          int pendingMatching) {
    }
}
