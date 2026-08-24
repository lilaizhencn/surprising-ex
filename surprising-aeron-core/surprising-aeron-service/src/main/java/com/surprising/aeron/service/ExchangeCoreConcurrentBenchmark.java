package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExchangeCoreConcurrentBenchmark {

    private static final String SYMBOL = "BENCH-BTC-USDT";
    private static final long USER_ID = 1001L;

    private ExchangeCoreConcurrentBenchmark() {
    }

    public static void main(String[] args) {
        int orders = positive(args, 0, 100_000);
        int warmupOrders = positive(args, 1, 10_000);
        int asyncInFlight = positive(args, 2, 4_096);
        int producers = positive(args, 3, 8);
        ExecutorService producerPool = Executors.newFixedThreadPool(producers);
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            run(adapter, producerPool, warmupOrders, asyncInFlight, producers, false);
            Result result = run(adapter, producerPool, orders, asyncInFlight, producers, true);
            System.out.printf("exchangeCoreConcurrentBenchmark=PASS orders=%d asyncInFlight=%d "
                            + "producers=%d elapsedSeconds=%.3f completePerSec=%.3f "
                            + "p50Micros=%d p95Micros=%d p99Micros=%d maxMicros=%d failures=%d%n",
                    result.orders(), asyncInFlight, producers, result.elapsedNanos() / 1_000_000_000.0,
                    result.orders() / (result.elapsedNanos() / 1_000_000_000.0),
                    percentile(result.latencies(), 0.50), percentile(result.latencies(), 0.95),
                    percentile(result.latencies(), 0.99), percentile(result.latencies(), 1.0),
                    result.failures());
        } finally {
            producerPool.shutdownNow();
        }
    }

    private static Result run(DeterministicExchangeCoreAdapter adapter, ExecutorService producerPool,
                              int orderCount, int asyncInFlight, int producers, boolean measured) {
        long[] latencies = measured ? new long[orderCount] : new long[0];
        AtomicInteger failures = new AtomicInteger();
        long submitStarted = System.nanoTime();
        CompletableFuture<?>[] producerRuns = new CompletableFuture[producers];
        for (int producer = 0; producer < producers; producer++) {
            int start = orderCount * producer / producers;
            int end = orderCount * (producer + 1) / producers;
            producerRuns[producer] = CompletableFuture.runAsync(
                    () -> submitRange(adapter, start, end, asyncInFlight, measured, latencies, failures),
                    producerPool);
        }
        CompletableFuture.allOf(producerRuns).join();
        long submitNanos = System.nanoTime() - submitStarted;
        return new Result(orderCount, submitNanos, System.nanoTime() - submitStarted, latencies, failures.get());
    }

    private static void submitRange(DeterministicExchangeCoreAdapter adapter, int start, int end,
                                    int asyncInFlight, boolean measured, long[] latencies,
                                    AtomicInteger failures) {
        int submitted = start;
        while (submitted < end) {
            int batchEnd = Math.min(end, submitted + asyncInFlight);
            List<CompletableFuture<CoreMatchingResult>> futures = new ArrayList<>(batchEnd - submitted);
            for (int index = submitted; index < batchEnd; index++) {
                long started = System.nanoTime();
                long orderId = (measured ? 10_000_000L : 1_000_000L) + index;
                CompletableFuture<CoreMatchingResult> future = adapter.placeAsync(USER_ID, place(orderId));
                if (measured) {
                    int latencyIndex = index;
                    future.whenComplete((result, failure) -> {
                        latencies[latencyIndex] = System.nanoTime() - started;
                        if (failure != null) failures.incrementAndGet();
                    });
                }
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            submitted = batchEnd;
        }
    }

    private static PlaceOrderCommand place(long orderId) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, "BTC", "USDT", "USDT", CoreOrderSide.BUY,
                1_000, 1_001, 1_100, 999, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.SPOT_ASSET, "USDT", 1_000, CoreOrderType.LIMIT, CoreTimeInForce.IOC, false,
                "bench-concurrent-" + orderId, 0, 0);
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

    private record Result(int orders, long submitNanos, long elapsedNanos, long[] latencies, int failures) {
    }
}
