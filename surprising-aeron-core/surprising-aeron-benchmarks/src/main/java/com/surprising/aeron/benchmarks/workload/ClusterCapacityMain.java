package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.CoreRiskLimitBracket;

import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreLaneMetricsCodec;
import com.surprising.aeron.protocol.CoreLaneMetricsView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.core.CoreAcceptFreezeBenchmark;
import com.surprising.aeron.service.core.CoreInMemoryBenchmark;
import com.surprising.aeron.service.core.CorePerpetualEndToEndBenchmark;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.Histogram;

@Slf4j
public final class ClusterCapacityMain implements AutoCloseable {

    private static final long FUNDING_UNITS = 1_000_000_000_000L;
    private static final long PRICE_TICKS = 100;
    private static final long QUANTITY_STEPS = 1;

    private final ProductLine productLine;
    private final List<String> symbols;
    private final long seed;
    private final int workers;
    private final int connections;
    private final int userCount;
    private final int pairCount;
    private final int asyncInFlight;
    private final int warmupSeconds;
    private final int durationSeconds;
    private final long offeredCommandsPerSecond;
    private final Workload workload;
    private final int ordersPerRequest;
    private final AeronClientPool clients;
    private final AtomicLong nextOrderId;
    private final AtomicLong nextPriceSequence = new AtomicLong();
    private final AtomicLong nextPermitNanos = new AtomicLong();
    private final AtomicLong matches = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
    private final Object[] symbolLocks;
    private final ClusterMarkPriceGate[] markGates;
    private final AtomicLong marketDataCommands = new AtomicLong();
    private final AtomicLong transientOfferRetries = new AtomicLong();
    private final CapacityMetrics capacityMetrics;
    private final CapacityMetrics makerMetrics = new CapacityMetrics(0);
    private final CapacityMetrics takerMetrics = new CapacityMetrics(0);
    private final AtomicLong activeRequests = new AtomicLong();
    private final AtomicLong submittedRequests = new AtomicLong();
    private final AtomicLong completedRequests = new AtomicLong();
    private final LongAccumulator peakRequests = new LongAccumulator(Long::max, 0);
    private long occupancySamples;
    private long occupancySum;

    private ClusterCapacityMain(
            ProductLine productLine,
            List<String> hosts,
            String egress,
            List<String> symbols,
            long seed,
            int workers,
            int connections,
            int userCount,
            int asyncInFlight,
            int warmupSeconds,
            int durationSeconds,
            long offeredCommandsPerSecond,
            Workload workload) {
        this.productLine = productLine;
        this.symbols = List.copyOf(symbols);
        this.seed = seed;
        this.workers = workers;
        this.connections = connections;
        this.userCount = userCount;
        this.pairCount = userCount / 2;
        this.asyncInFlight = asyncInFlight;
        this.warmupSeconds = warmupSeconds;
        this.durationSeconds = durationSeconds;
        this.offeredCommandsPerSecond = offeredCommandsPerSecond;
        this.workload = workload;
        this.ordersPerRequest = workload == Workload.MATCH_BATCH_STREAM
                ? positiveInt("surprising.aeron.capacity-batch-size", 20) : 1;
        if (ordersPerRequest > PlaceOrderBatchCommand.MAX_ORDERS) {
            throw new IllegalArgumentException("capacity-batch-size exceeds protocol limit");
        }
        this.symbolLocks = java.util.stream.IntStream.range(0, symbols.size())
                .mapToObj(ignored -> new Object()).toArray(Object[]::new);
        this.markGates = java.util.stream.IntStream.range(0, symbols.size())
                .mapToObj(ignored -> new ClusterMarkPriceGate()).toArray(ClusterMarkPriceGate[]::new);
        this.capacityMetrics = new CapacityMetrics(offeredCommandsPerSecond == 0
                ? 0 : Math.max(1, 1_000_000_000L / offeredCommandsPerSecond));
        this.clients = new AeronClientPool("capacity", productLine, hosts, egress, Duration.ofSeconds(10),
                connections, "capacity-" + productLine + '-' + seed);
        this.nextOrderId = new AtomicLong(40_000_000_000L + seed * 1_000_000L);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--local-baseline".equals(args[0])) {
            runLocalBaseline(args);
            return;
        }
        ProductLine productLine = ProductLine.requireExternalCode(
                System.getProperty("surprising.aeron.product-line", "LINEAR_PERPETUAL"));
        List<String> hosts = Arrays.stream(System.getProperty(
                        "surprising.aeron.hostnames", "localhost,localhost,localhost").split(","))
                .map(String::trim).toList();
        String egress = System.getProperty("surprising.aeron.egress-hostname", "localhost");
        String symbolPrefix = System.getProperty("surprising.aeron.instrumentId", "15")
                .trim().toUpperCase();
        int symbolCount = positiveInt("surprising.aeron.capacity-instrumentId-count", 128);
        int baseSymbolId = Integer.parseInt(symbolPrefix);
        List<String> symbols = java.util.stream.IntStream.range(0, symbolCount)
                .mapToObj(index -> String.valueOf(symbolCount == 1 ? baseSymbolId : baseSymbolId * 1000 + index + 1))
                .toList();
        long seed = positiveLong("surprising.aeron.capacity-seed", 9901);
        int workers = positiveInt("surprising.aeron.capacity-workers", 4);
        int connections = positiveInt("surprising.aeron.capacity-connections", workers);
        int userCount = positiveInt("surprising.aeron.capacity-user-count", 100);
        if ((userCount & 1) != 0) throw new IllegalArgumentException("capacity-user-count must be even");
        int asyncInFlight = positiveInt("surprising.aeron.capacity-async-in-flight", 1);
        int warmupSeconds = nonNegativeInt("surprising.aeron.capacity-warmup-seconds", 5);
        int durationSeconds = positiveInt("surprising.aeron.capacity-duration-seconds", 15);
        long offered = nonNegativeLong("surprising.aeron.capacity-offered-commands-per-second", 0);
        Workload workload = Workload.valueOf(System.getProperty(
                "surprising.aeron.capacity-workload", "MATCH").trim().toUpperCase());
        if (workload.asynchronous()
                && (workers > asyncInFlight || offered != 0)) {
            throw new IllegalArgumentException("async workloads require workers <= total in-flight and offered=0");
        }
        String mode = System.getProperty("surprising.aeron.capacity-mode", "run").trim().toLowerCase();
        try (ClusterCapacityMain benchmark = new ClusterCapacityMain(productLine, hosts, egress, symbols, seed,
                workers, connections, userCount, asyncInFlight, warmupSeconds, durationSeconds, offered, workload)) {
            if ("verify".equals(mode)) {
                benchmark.cancelOrders(System.getProperty("surprising.aeron.capacity-cancel-orders", ""));
                benchmark.verifyFundsAndBook();
                log.info("capacityVerify=PASS productLine={} workers={} symbols={} fundsDiff=0 bookLevels=0", productLine, workers, symbols.size());
            } else if ("run".equals(mode)) {
                benchmark.run();
            } else {
                throw new IllegalArgumentException("surprising.aeron.capacity-mode must be run or verify");
            }
        }
    }

    private void run() throws Exception {
        setup();
        if (warmupSeconds > 0) {
            execute(warmupSeconds, false);
        }
        auditLaneMetrics("before");
        long warmupMatches = matches.getAndSet(0);
        failures.set(0);
        firstFailure.set(null);
        capacityMetrics.reset();
        makerMetrics.reset();
        takerMetrics.reset();
        if (activeRequests.get() != 0) throw new IllegalStateException("warmup requests not drained");
        submittedRequests.set(0);
        completedRequests.set(0);
        peakRequests.reset();
        occupancySamples = 0;
        occupancySum = 0;
        marketDataCommands.set(0);
        transientOfferRetries.set(0);
        nextPermitNanos.set(System.nanoTime());
        MeasurementWindow measurement = execute(durationSeconds, true);
        long elapsedNanos = measurement.finishedNanos() - measurement.startedNanos();
        long steadyNanos = measurement.deadlineNanos() - measurement.startedNanos();
        if (activeRequests.get() != 0 || submittedRequests.get() != completedRequests.get()) {
            throw new IllegalStateException("measurement requests not drained");
        }
        if (workload.asynchronous() && peakRequests.get() > asyncInFlight) {
            throw new IllegalStateException("global request window exceeded");
        }
        verifyFundsAndBook();
        auditLaneMetrics("after");
        long matchCount = matches.get();
        log.info("warmupMatches={} totalRunMatches={}", warmupMatches, Math.addExact(warmupMatches, matchCount));
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        MetricsSnapshot metrics = capacityMetrics.snapshot(elapsedNanos);
        SteadyMetricsSnapshot steady = capacityMetrics.steadySnapshot(steadyNanos);
        long drainFinalized = Math.subtractExact(metrics.finalized(), steady.finalized());
        if (workload.asynchronous() && (metrics.offered() != metrics.finalized()
                || metrics.accepted() != metrics.finalized() || metrics.finalized() != 2 * matchCount
                || metrics.finalized() % ordersPerRequest != 0
                || submittedRequests.get() != metrics.finalized() / ordersPerRequest + marketDataCommands.get())) {
            throw new IllegalStateException("terminal business/Core/fill counters disagree: offered="
                    + metrics.offered() + " accepted=" + metrics.accepted() + " terminal=" + metrics.finalized()
                    + " fills=" + matchCount + " submitted=" + submittedRequests.get()
                    + " mark=" + marketDataCommands.get());
        }
        log.info("{}", String.format(java.util.Locale.ROOT, "capacity=PASS scope=CLUSTER_NETWORK productLine=%s workload=%s symbols=%d users=%d workers=%d connections=%d "
                        + "targetOfferedPerSec=%d offered=%d accepted=%d finalized=%d matches=%d failures=%d elapsedSeconds=%.3f "
                        + "inclusiveFinalizedPerSec=%.3f inclusiveCoreMatchEventsPerSec=%.3f requestToTerminalP50Micros=%d "
                        + "requestToTerminalP99Micros=%d requestToTerminalP999Micros=%d "
                        + "pendingMax=-1 completionQueueMax=-1 fundsDiff=0 bookLevels=0%n",
                productLine, workload, symbols.size(), userCount, workers, connections, offeredCommandsPerSecond,
                metrics.offered(), metrics.accepted(), metrics.finalized(), matchCount, failures.get(), elapsedSeconds,
                metrics.finalizedPerSecond(), matchCount / elapsedSeconds, metrics.p50Micros(), metrics.p99Micros(),
                metrics.p999Micros()).stripTrailing());
        log.info("{}", String.format(java.util.Locale.ROOT,
                "steadyCapacity elapsedSeconds=%.6f terminalBusinessOperations=%d businessOpsPerSec=%.3f "
                        + "requestToTerminalP50Micros=%d requestToTerminalP90Micros=%d requestToTerminalP95Micros=%d "
                        + "requestToTerminalP99Micros=%d requestToTerminalP999Micros=%d requestToTerminalMaxMicros=%d%n",
                steadyNanos / 1e9, steady.finalized(), steady.finalizedPerSecond(), steady.p50Micros(),
                steady.p90Micros(), steady.p95Micros(), steady.p99Micros(), steady.p999Micros(), steady.maxMicros()).stripTrailing());
        log.info("drain elapsedNanos={} terminalBusinessOperations={} unfinishedRequests={}",
                Math.max(0, measurement.finishedNanos() - measurement.deadlineNanos()), drainFinalized, activeRequests.get());
        log.info("{}", String.format(java.util.Locale.ROOT, "marketDataCommands=%d marketDataCommandsPerSec=%.3f totalTerminalCoreMessages=%d%n",
                marketDataCommands.get(), marketDataCommands.get() / elapsedSeconds,
                Math.addExact(metrics.finalized() / ordersPerRequest, marketDataCommands.get())).stripTrailing());
        log.info("ordersPerRequest={} terminalOrderRequests={} latencyScope={}", ordersPerRequest, metrics.finalized() / ordersPerRequest,
                workload == Workload.MATCH_BATCH_STREAM ? "BATCH_TERMINAL_PER_ITEM" : "COMMAND_TERMINAL");
        log.info("transientOfferRetries={}", transientOfferRetries.get());
        log.info("totalWindow={} observedRequestMax={} unfinishedRequests={} submittedRequests={} completedRequests={}", asyncInFlight, peakRequests.get(), activeRequests.get(), submittedRequests.get(), completedRequests.get());
        log.info("{}", String.format(java.util.Locale.ROOT, "requestOccupancySampleIntervalSeconds=1 samples=%d mean=%.3f%n",
                occupancySamples, occupancySamples == 0 ? 0.0 : (double) occupancySum / occupancySamples).stripTrailing());
        printLatency(workload.stream() ? "sell" : "maker", makerMetrics.steadySnapshot(steadyNanos));
        printLatency(workload.stream() ? "buy" : "taker", takerMetrics.steadySnapshot(steadyNanos));
        capacityMetrics.printHistogram();
    }

    private void setup() {
        for (String instrumentId : symbols) {
            applied(CoreMessageType.REGISTER_INSTRUMENT, 1,
                    TradingCommandCodec.encodeRegisterInstrument(instrument(instrumentId), productLine), stableId("instrument:" + instrumentId));
        }
        for (int pair = 0; pair < pairCount; pair++) {
            long first = firstUser(pair);
            long second = secondUser(pair);
            if (productLine == ProductLine.SPOT) {
                adjust(first, "BTC", FUNDING_UNITS);
                adjust(first, "USDT", FUNDING_UNITS);
                adjust(second, "BTC", FUNDING_UNITS);
                adjust(second, "USDT", FUNDING_UNITS);
            } else {
                adjust(first, settleAsset(), FUNDING_UNITS);
                adjust(second, settleAsset(), FUNDING_UNITS);
            }
        }
    }

    private MeasurementWindow execute(int seconds, boolean measured) throws InterruptedException {
        // Start the window only after every load worker is ready; initialization is not offered load.
        AtomicLong deadline = new AtomicLong();
        long startedNanos;
        long startedEpochMillis;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long workerDeadline = deadline.get();
                    long cycle = 0;
                    while (!stop.get() && System.nanoTime() < workerDeadline) {
                        try {
                            if (workload == Workload.MATCH) {
                                matchCycle(workerId, cycle++, measured);
                            } else if (workload == Workload.MATCH_ASYNC) {
                                asyncMatch(workerId, workerDeadline, measured);
                            } else if (workload.stream()) {
                                streamMatch(workerId, workerDeadline, measured);
                            } else if (workload == Workload.CANCEL) {
                                cancelCycle(workerId, measured);
                            } else if (workload == Workload.PLACE_ONLY) {
                                placeOnlyCycle(workerId, cycle++, measured);
                            } else {
                                markPriceCycle(workerId, cycle++, measured);
                            }
                        } catch (RuntimeException exception) {
                            failures.incrementAndGet();
                            firstFailure.compareAndSet(null, exception);
                            stop.set(true);
                        }
                    }
                    return null;
                });
            }
            ready.await();
            startedNanos = System.nanoTime();
            startedEpochMillis = System.currentTimeMillis();
            deadline.set(Math.addExact(startedNanos, TimeUnit.SECONDS.toNanos(seconds)));
            if (measured) {
                capacityMetrics.measureBetween(startedNanos, deadline.get());
                makerMetrics.measureBetween(startedNanos, deadline.get());
                takerMetrics.measureBetween(startedNanos, deadline.get());
            }
            start.countDown();
            if (measured) {
                log.info("measurementStartEpochMillis={} measurementEndEpochMillis={} measurementDurationNanos={}",
                        startedEpochMillis, Math.addExact(startedEpochMillis, TimeUnit.SECONDS.toMillis(seconds)),
                        deadline.get() - startedNanos);
            }
            executor.shutdown();
            long lastReport = System.nanoTime();
            long lastFinalized = 0;
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                long now = System.nanoTime();
                if (measured) {
                    occupancySamples++;
                    occupancySum += activeRequests.get();
                    if (now - lastReport >= TimeUnit.SECONDS.toNanos(10)) {
                        long finalized = capacityMetrics.snapshot(Math.max(1, now - lastReport)).finalized();
                        log.info("{}", String.format(java.util.Locale.ROOT, "progress terminalBusinessOps=%d intervalBusinessOpsPerSec=%.3f requestsInFlight=%d%n",
                                finalized, (finalized - lastFinalized) * 1e9 / (now - lastReport), activeRequests.get()).stripTrailing());
                        lastReport = now;
                        lastFinalized = finalized;
                    }
                }
                if (now > deadline.get() + TimeUnit.SECONDS.toNanos(30)) {
                    stop.set(true);
                    executor.shutdownNow();
                    throw new IllegalStateException("capacity workers did not terminate");
                }
            }
        }
        if (failures.get() != 0) {
            throw new IllegalStateException("capacity workload failed count=" + failures.get(), firstFailure.get());
        }
        return new MeasurementWindow(startedNanos, deadline.get(), System.nanoTime());
    }

    /** Fixed offered-load window; finishedNanos includes the separately reported drain. */
    private record MeasurementWindow(long startedNanos, long deadlineNanos, long finishedNanos) { }

    private void matchCycle(int worker, long cycle, boolean measured) {
        String instrumentId = instrumentId(worker, cycle);
        synchronized (symbolLocks[symbols.indexOf(instrumentId)]) {
            boolean reverse = (cycle & 1L) != 0;
            int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
            long makerUser = firstUser(pair);
            long takerUser = secondUser(pair);
            CoreOrderSide makerSide = reverse ? CoreOrderSide.BUY : CoreOrderSide.SELL;
            CoreOrderSide takerSide = reverse ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            long makerOrder = nextOrderId.incrementAndGet();
            long takerOrder = nextOrderId.incrementAndGet();
            submitOrder(makerUser, order(instrumentId, makerOrder, makerSide, CoreTimeInForce.GTC), measured);
            submitOrder(takerUser, order(instrumentId, takerOrder, takerSide, CoreTimeInForce.IOC), measured);
            matches.incrementAndGet();
        }
    }

    private void asyncMatch(int worker, long deadline, boolean measured) {
        // The sum of partitions is the configured GLOBAL window, also with several load workers.
        int slots = slotsForWorker(asyncInFlight, workers, worker);
        CompletableFuture<?>[] pending = new CompletableFuture<?>[slots];
        long cycle = 0;
        int count = 0;
        while (System.nanoTime() < deadline || count != 0) {
            boolean progress = false;
            for (int slot = 0; slot < slots; slot++) {
                if (pending[slot] != null && pending[slot].isDone()) {
                    pending[slot].join();
                    pending[slot] = null;
                    count--;
                    progress = true;
                }
                if (pending[slot] != null || System.nanoTime() >= deadline) continue;
                int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
                String instrumentId = instrumentId(worker, cycle);
                long makerOrder = nextOrderId.incrementAndGet();
                long takerOrder = nextOrderId.incrementAndGet();
                throttle();
                pending[slot] = ClusterAsyncPair.start(refreshMarkAsync(instrumentId, measured), () -> {
                    if (measured) capacityMetrics.recordOffered();
                    return commandAsync(
                        CoreMessageType.PLACE_ORDER, stableId("async-maker:" + makerOrder), firstUser(pair),
                        TradingCommandCodec.encodePlaceOrder(order(instrumentId, makerOrder, CoreOrderSide.SELL, CoreTimeInForce.GTC)));
                }, () -> {
                    if (measured) capacityMetrics.recordOffered();
                    return commandAsync(CoreMessageType.PLACE_ORDER,
                            stableId("async-taker:" + takerOrder), secondUser(pair),
                            TradingCommandCodec.encodePlaceOrder(order(instrumentId, takerOrder, CoreOrderSide.BUY, CoreTimeInForce.IOC)));
                }, (response, nanos) -> {
                    record(response, nanos, measured);
                    if (measured) makerMetrics.recordFinalized(nanos);
                }, (response, nanos) -> {
                    record(response, nanos, measured);
                    if (measured) {
                        takerMetrics.recordFinalized(nanos);
                    }
                    matches.incrementAndGet();
                }, System::nanoTime);
                count++;
                cycle++;
                progress = true;
            }
            if (!progress) LockSupport.parkNanos(10_000L);
        }
    }

    private void streamMatch(int worker, long deadline, boolean measured) {
        int slots = slotsForWorker(asyncInFlight, workers, worker);
        CompletableFuture<?>[] pending = new CompletableFuture<?>[slots];
        long cycle = 0;
        int count = 0;
        boolean buyDue = false;
        while (System.nanoTime() < deadline || count != 0 || buyDue) {
            for (int slot = 0; slot < slots; slot++) {
                if (pending[slot] != null && pending[slot].isDone()) {
                    pending[slot].join(); // Already terminal: checks errors, never waits for a reply.
                    pending[slot] = null;
                    count--;
                }
                if (pending[slot] != null || (!buyDue && System.nanoTime() >= deadline)) continue;
                int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
                String instrumentId = instrumentId(worker, cycle);
                boolean buy = buyDue;
                long user = buy ? secondUser(pair) : firstUser(pair);
                long firstOrderId = nextOrderId.getAndAdd(ordersPerRequest) + 1;
                // Both sides are GTC: separate sessions may deliver either side first. No order
                // waits for its counterpart's terminal. An already-started pair is balanced at
                // the deadline, then every outstanding order is drained before verification.
                pending[slot] = ClusterAsyncPair.independent(refreshMarkAsync(instrumentId, measured), () -> {
                    if (measured) for (int item = 0; item < ordersPerRequest; item++) capacityMetrics.recordOffered();
                    boolean batch = workload == Workload.MATCH_BATCH_STREAM;
                    byte[] payload;
                    if (batch) {
                        var orders = new java.util.ArrayList<PlaceOrderCommand>(ordersPerRequest);
                        for (int item = 0; item < ordersPerRequest; item++) {
                            orders.add(order(instrumentId, firstOrderId + item,
                                    buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, CoreTimeInForce.GTC));
                        }
                        payload = TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders));
                    } else {
                        payload = TradingCommandCodec.encodePlaceOrder(order(instrumentId, firstOrderId,
                                buy ? CoreOrderSide.BUY : CoreOrderSide.SELL, CoreTimeInForce.GTC));
                    }
                    return commandAsync(batch ? CoreMessageType.PLACE_ORDER_BATCH : CoreMessageType.PLACE_ORDER,
                            stableId("stream:" + firstOrderId), user, payload);
                }, (response, nanos) -> {
                    if (response.commandStatus() != ResponseStatus.APPLIED) {
                        throw new IllegalStateException("stream command rejected " + response.resultCode());
                    }
                    long fills = workload == Workload.MATCH_BATCH_STREAM
                            ? batchStreamFillCount(TradingOrderBatchCodec.decodeResult(response.data()), firstOrderId, ordersPerRequest)
                            : streamFillCount(com.surprising.aeron.protocol.CoreCommandResultCodec.decode(response.data()), firstOrderId);
                    for (int item = 0; item < ordersPerRequest; item++) record(response, nanos, measured);
                    if (measured) {
                        for (int item = 0; item < ordersPerRequest; item++) {
                            (buy ? takerMetrics : makerMetrics).recordFinalized(nanos);
                        }
                    }
                    matches.addAndGet(fills);
                }, System::nanoTime);
                count++;
                if (buy) cycle++;
                buyDue = !buy;
            }
            Thread.onSpinWait(); // Full window only: no timed sleep or per-order blocking.
        }
    }

    static long streamFillCount(com.surprising.aeron.protocol.CoreCommandResultView result, long orderId) {
        // Core deliberately omits execution detail arrays. For a NEW order of exactly one unit,
        // its immediate executed quantity is exactly the fills generated by this command. Do
        // not count counterpart order views: that would count both sides of the same fill.
        for (var order : result.orders()) {
            if (order.orderId() != orderId) continue;
            if (order.quantitySteps() != QUANTITY_STEPS || order.priceTicks() != PRICE_TICKS
                    || order.executedQuantitySteps() < 0 || order.executedQuantitySteps() > QUANTITY_STEPS
                    || order.remainingQuantitySteps() != QUANTITY_STEPS - order.executedQuantitySteps()) {
                throw new IllegalStateException("unexpected stream order quantities");
            }
            return order.executedQuantitySteps();
        }
        throw new IllegalStateException("stream response is missing submitted order state");
    }

    static long batchStreamFillCount(CoreOrderBatchResult result, long firstOrderId, int expectedCount) {
        if (result.items().size() != expectedCount) throw new IllegalStateException("batch item count mismatch");
        long fills = 0;
        for (int index = 0; index < expectedCount; index++) {
            var item = result.items().get(index);
            var order = item.order();
            if (item.index() != index || item.orderId() != firstOrderId + index
                    || item.status() != ResponseStatus.APPLIED) {
                throw new IllegalStateException("invalid batch stream item expectedId=" + (firstOrderId + index) + " actual=" + item);
            }
            // Batch responses retain actual execution events. Filled orders may already be
            // removed from the active order index, so their optional order view can be null.
            long executed = 0;
            for (var execution : item.executions()) {
                if (execution.takerOrderId() != item.orderId() || execution.priceTicks() != PRICE_TICKS
                        || execution.quantitySteps() != QUANTITY_STEPS) {
                    throw new IllegalStateException("unexpected batch stream execution " + execution);
                }
                executed += execution.quantitySteps();
            }
            if (executed > QUANTITY_STEPS || (order == null && executed != QUANTITY_STEPS)
                    || (order != null && (order.orderId() != item.orderId()
                    || order.quantitySteps() != QUANTITY_STEPS || order.priceTicks() != PRICE_TICKS
                    || order.executedQuantitySteps() != executed
                    || order.remainingQuantitySteps() != QUANTITY_STEPS - executed))) {
                throw new IllegalStateException("invalid batch stream quantities item=" + item);
            }
            fills += executed;
        }
        return fills;
    }

    private void cancelOrders(String encodedOrders) {
        if (encodedOrders == null || encodedOrders.isBlank()) {
            return;
        }
        for (String encodedOrder : encodedOrders.split(",")) {
            String[] fields = encodedOrder.trim().split(":");
            if (fields.length != 2) {
                throw new IllegalArgumentException("invalid capacity cleanup order: " + encodedOrder);
            }
            long userId = Long.parseLong(fields[0]);
            long orderId = Long.parseLong(fields[1]);
            var response = clients.command(CoreMessageType.CANCEL_ORDER, stableId("cleanup:" + orderId), userId,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
            if (response.commandStatus() != ResponseStatus.APPLIED) {
                throw new IllegalStateException("capacity cleanup cancel rejected orderId=" + orderId
                        + " status=" + response.commandStatus());
            }
        }
    }

    private void cancelCycle(int worker, boolean measured) {
        int pair = Math.floorMod(worker, pairCount);
        long userId = firstUser(pair);
        long orderId = nextOrderId.incrementAndGet();
        String instrumentId = instrumentId(worker, orderId);
        submitOrder(userId, order(instrumentId, orderId, CoreOrderSide.SELL, CoreTimeInForce.GTC, 110), measured);
        throttle();
        long started = System.nanoTime();
        if (measured) capacityMetrics.recordOffered();
        var response = clients.command(CoreMessageType.CANCEL_ORDER, stableId("cancel:" + orderId), userId,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)));
        record(response, System.nanoTime() - started, measured);
    }

    private void placeOnlyCycle(int worker, long cycle, boolean measured) {
        int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
        long userId = firstUser(pair);
        long orderId = nextOrderId.incrementAndGet();
        String instrumentId = instrumentId(worker, cycle);
        submitOrder(userId, order(instrumentId, orderId, CoreOrderSide.BUY, CoreTimeInForce.IOC, 90), measured);
    }

    private void markPriceCycle(int worker, long cycle, boolean measured) {
        String instrumentId = instrumentId(worker, cycle);
        synchronized (symbolLocks[symbols.indexOf(instrumentId)]) {
            throttle();
            long started = System.nanoTime();
            if (measured) capacityMetrics.recordOffered();
            long sequence = nextPriceSequence.incrementAndGet();
            var response = clients.command(CoreMessageType.APPLY_MARK_PRICE,
                    stableId("mark-price:" + worker + ':' + cycle), firstUser(Math.floorMod(worker, pairCount)),
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                            instrumentId, PRICE_TICKS + (cycle & 1L), sequence, System.currentTimeMillis())));
            record(response, System.nanoTime() - started, measured);
        }
    }

    private void submitOrder(long userId, PlaceOrderCommand command, boolean measured) {
        refreshMarkIfDue(command.instrumentId(), measured);
        throttle();
        long started = System.nanoTime();
        if (measured) capacityMetrics.recordOffered();
        var response = clients.command(CoreMessageType.PLACE_ORDER, stableId("order:" + command.orderId()), userId,
                TradingCommandCodec.encodePlaceOrder(command));
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException("capacity order rejected orderId=" + command.orderId()
                    + " userId=" + userId + " status=" + response.commandStatus()
                    + " result=" + response.resultCode());
        }
        record(response, System.nanoTime() - started, measured);
    }

    private void record(CoreResponse response, long latencyNanos, boolean measured) {
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException("capacity command rejected status=" + response.commandStatus());
        }
        if (measured) {
            capacityMetrics.recordAccepted();
            capacityMetrics.recordFinalized(latencyNanos);
        }
    }

    private void throttle() {
        if (offeredCommandsPerSecond == 0) {
            return;
        }
        long interval = Math.max(1, 1_000_000_000L / offeredCommandsPerSecond);
        long permit = nextPermitNanos.getAndAdd(interval);
        long remaining = permit - System.nanoTime();
        if (remaining > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    private void refreshMarkIfDue(String instrumentId, boolean measured) {
        refreshMarkAsync(instrumentId, measured).join();
    }

    private CompletableFuture<Void> refreshMarkAsync(String instrumentId, boolean measured) {
        if (productLine == ProductLine.SPOT) return CompletableFuture.completedFuture(null);
        int index = symbols.indexOf(instrumentId);
        return markGates[index].refresh(System.currentTimeMillis(), now -> {
            long sequence = nextPriceSequence.incrementAndGet();
            return commandAsync(CoreMessageType.APPLY_MARK_PRICE, stableId("feed:" + seed + ':' + sequence), 1,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(instrumentId, PRICE_TICKS, sequence, now)))
                    .thenAccept(response -> {
                        if (response.commandStatus() != ResponseStatus.APPLIED) throw new IllegalStateException("mark rejected " + response.resultCode());
                        if (measured) marketDataCommands.incrementAndGet();
                    });
        });
    }

    private void verifyFundsAndBook() {
        var book = OrderBookBootstrapLoader.load((type, payload) -> {
            var response = clients.query(type, stableId("book-query:" + java.util.Arrays.hashCode(payload)),
                    0, payload);
            if (response.status() != ResponseStatus.OK) {
                throw new IllegalStateException(type + " query failed: " + response.resultCode());
            }
            return response.data();
        });
        long unexpectedLevels = book.levels().stream()
                .filter(level -> symbols.contains(level.instrumentId()))
                .count();
        if (unexpectedLevels != 0) {
            throw new IllegalStateException("capacity book is not empty symbols=" + symbols
                    + " levels=" + unexpectedLevels);
        }
        long actual = 0;
        for (int pair = 0; pair < pairCount; pair++) {
            actual = Math.addExact(actual, economicFunds(firstUser(pair)));
            actual = Math.addExact(actual, economicFunds(secondUser(pair)));
        }
        long expectedMultiplier = productLine == ProductLine.SPOT ? 4L : 2L;
        long expected = Math.multiplyExact(Math.multiplyExact(FUNDING_UNITS, pairCount), expectedMultiplier);
        if (actual != expected) {
            throw new IllegalStateException("capacity funds mismatch expected=" + expected + " actual=" + actual);
        }
    }

    /** Read actual topology and drained queues outside the offered-load window. */
    private void auditLaneMetrics(String phase) {
        var response = clients.query(CoreMessageType.LANE_METRICS_QUERY, stableId("lane-metrics:" + phase),
                0, new byte[0]);
        if (response.status() != ResponseStatus.OK)
            throw new IllegalStateException("Lane metrics query failed: " + response.resultCode());
        CoreLaneMetricsView view = CoreLaneMetricsCodec.decode(response.data());
        requireDrainedTopology(view, positiveInt("surprising.aeron.matching-engines", 1),
                positiveInt("surprising.aeron.account-lanes", 2));
        log.info("actualLaneMetrics phase={} matchingEngines={} accountLanes={} matcherDispatchDepth={} "
                        + "matchingCompletionDepth={} commandContextDepth={} accountQueueDepths={} "
                        + "accountQueueHighWater={} committedCoreSequence={}", phase, view.matchingEngineCount(),
                view.accountLaneCount(), view.matcherDispatchDepth(), view.matchingCompletionDepth(),
                view.commandContextDepth(), Arrays.toString(view.accountLaneQueueDepths()),
                Arrays.toString(view.accountLaneQueueHighWaterMarks()), view.committedCoreSequence());
    }

    static void requireDrainedTopology(CoreLaneMetricsView view, int expectedMatchers, int expectedLanes) {
        if (view.matchingEngineCount() != expectedMatchers || view.accountLaneCount() != expectedLanes)
            throw new IllegalStateException("actual Core topology differs from the requested benchmark topology");
        if (view.matcherDispatchDepth() != 0 || view.matchingCompletionDepth() != 0
                || view.commandContextDepth() != 0 || Arrays.stream(view.accountLaneQueueDepths()).anyMatch(depth -> depth != 0)
                || Arrays.stream(view.accountLaneOldestPendingSequences()).anyMatch(sequence -> sequence != 0))
            throw new IllegalStateException("Core queues were not drained at the measurement boundary");
    }

    private long economicFunds(long userId) {
        var response = clients.query(CoreMessageType.USER_STATE_QUERY, stableId("user-query:" + userId), userId,
                new byte[0]);
        var user = CoreStateQueryCodec.decodeUserState(response.data());
        if (productLine == ProductLine.SPOT) {
            return user.balances().stream().mapToLong(value -> Math.addExact(
                    value.availableUnits(), value.lockedUnits())).sum();
        }
        return user.balances().stream().filter(value -> value.asset().equals(settleAsset()))
                .mapToLong(value -> Math.addExact(value.availableUnits(), value.lockedUnits())).sum();
    }

    private String instrumentId(int worker, long cycle) {
        return symbols.get(Math.floorMod(worker + cycle, symbols.size()));
    }

    private PlaceOrderCommand order(
            String instrumentId, long orderId, CoreOrderSide side, CoreTimeInForce timeInForce) {
        return order(instrumentId, orderId, side, timeInForce, PRICE_TICKS);
    }

    private PlaceOrderCommand order(
            String instrumentId, long orderId, CoreOrderSide side, CoreTimeInForce timeInForce, long price) {
        return new PlaceOrderCommand(orderId, instrumentId, side, price, QUANTITY_STEPS, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, timeInForce, false, "");
    }

    private RegisterInstrumentCommand instrument(String instrumentId) {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new RegisterInstrumentCommand(
                instrumentId, "BTC", "USDT", settleAsset(), 1, 1, type.isInverse() ? 1_000 : 1,
                (type == ContractType.SPOT ? null : new CoreInstrumentRiskLimits(
                        100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                        java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000)))),
                0, 0, expiry, type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private void adjust(long userId, String asset, long units) {
        applied(CoreMessageType.ADJUST_BALANCE, userId,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)),
                stableId("fund:" + userId + ':' + asset));
    }

    private void applied(CoreMessageType type, long userId, byte[] payload, UUID commandId) {
        var response = commandAsync(type, commandId, userId, payload).join();
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(type + " rejected status=" + response.commandStatus());
        }
    }

    private CompletableFuture<CoreResponse> commandAsync(
            CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        peakRequests.accumulate(activeRequests.incrementAndGet());
        submittedRequests.incrementAndGet();
        return ClusterOfferRetry.submit(() -> clients.commandAsync(type, commandId, userId, payload),
                transientOfferRetries::incrementAndGet).whenComplete((response, failure) -> {
                    activeRequests.decrementAndGet();
                    completedRequests.incrementAndGet();
                });
    }

    private long firstUser(int pair) {
        return 50_000_000_000L + seed * 1_000L + pair * 2L;
    }

    private long secondUser(int pair) {
        return firstUser(pair) + 1;
    }

    private String settleAsset() {
        return productLine == ProductLine.INVERSE_PERPETUAL || productLine == ProductLine.INVERSE_DELIVERY
                ? "BTC" : "USDT";
    }

    private static void runLocalBaseline(String[] args) {
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 9901L;
        if (seed <= 0) throw new IllegalArgumentException("baseline seed must be positive");
        int makerDepth = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        CoreAcceptFreezeBenchmark.main(new String[]{"25", "5"});
        CoreInMemoryBenchmark.main(new String[]{"25", "5"});
        CorePerpetualEndToEndBenchmark.BaselineResult perpetual =
                CorePerpetualEndToEndBenchmark.measure(25, 5, makerDepth);
        CapacityMetrics finalizationMetrics = new CapacityMetrics(TimeUnit.MILLISECONDS.toNanos(1));
        for (long latency : perpetual.latenciesNanos()) {
            finalizationMetrics.recordOffered();
            finalizationMetrics.recordAccepted();
            finalizationMetrics.recordFinalized(latency);
        }
        MetricsSnapshot finalization = finalizationMetrics.snapshot(perpetual.elapsedNanos());
        log.info("{}", String.format(java.util.Locale.ROOT, "perpetualEndToEndBenchmark=PASS cycles=%d makerDepth=%d orders=%d matchedQuantity=%d elapsedSeconds=%.3f "
                        + "finalizedPerSec=%.3f corrected=true expectedIntervalMicros=1000 "
                        + "p50Micros=%d p99Micros=%d p999Micros=%d pendingMatching=%d%n",
                perpetual.cycles(), perpetual.makerDepth(), perpetual.finalizedOrders(), perpetual.matchedQuantity(),
                perpetual.elapsedNanos() / 1_000_000_000.0,
                finalization.finalizedPerSecond(), finalization.p50Micros(), finalization.p99Micros(),
                finalization.p999Micros(), perpetual.pendingMatching()).stripTrailing());
        log.info("clusterCapacityBaseline=PASS seed={} suite=baseline makerDepth={}", seed, makerDepth);
    }

    static final class CapacityMetrics {
        private static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.MINUTES.toNanos(1);

        private final long expectedIntervalNanos;
        private final Histogram finalizationLatency = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
        private final Histogram steadyFinalizationLatency = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
        private long measurementStart = Long.MIN_VALUE;
        private long measurementEnd = Long.MAX_VALUE;
        private long steadyFinalized;
        private long offered;
        private long accepted;
        private long finalized;

        CapacityMetrics(long expectedIntervalNanos) {
            if (expectedIntervalNanos < 0) throw new IllegalArgumentException("expected interval must be non-negative");
            this.expectedIntervalNanos = expectedIntervalNanos;
        }

        synchronized void recordOffered() {
            offered++;
        }

        synchronized void recordAccepted() {
            accepted++;
        }

        synchronized void measureBetween(long startNanos, long endNanos) {
            if (endNanos <= startNanos) throw new IllegalArgumentException("measurement window must be positive");
            measurementStart = startNanos;
            measurementEnd = endNanos;
        }

        void recordFinalized(long acceptanceToFinalizationNanos) {
            recordFinalizedAt(acceptanceToFinalizationNanos, System.nanoTime());
        }

        synchronized void recordFinalizedAt(long acceptanceToFinalizationNanos, long terminalNanos) {
            if (acceptanceToFinalizationNanos <= 0) {
                throw new IllegalArgumentException("acceptance-to-finalization latency must be positive");
            }
            finalizationLatency.recordValueWithExpectedInterval(
                    Math.min(acceptanceToFinalizationNanos, HIGHEST_TRACKABLE_NANOS), expectedIntervalNanos);
            finalized++;
            if (terminalNanos >= measurementStart && terminalNanos < measurementEnd) {
                steadyFinalizationLatency.recordValueWithExpectedInterval(
                        Math.min(acceptanceToFinalizationNanos, HIGHEST_TRACKABLE_NANOS), expectedIntervalNanos);
                steadyFinalized++;
            }
        }

        synchronized SteadyMetricsSnapshot steadySnapshot(long elapsedNanos) {
            if (elapsedNanos <= 0) throw new IllegalArgumentException("elapsed time must be positive");
            return new SteadyMetricsSnapshot(steadyFinalized,
                    steadyFinalized * 1_000_000_000.0 / elapsedNanos,
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getValueAtPercentile(50)),
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getValueAtPercentile(99)),
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getValueAtPercentile(99.9)),
                    steadyFinalizationLatency.getTotalCount(),
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getValueAtPercentile(90)),
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getValueAtPercentile(95)),
                    TimeUnit.NANOSECONDS.toMicros(steadyFinalizationLatency.getMaxValue()));
        }

        synchronized MetricsSnapshot snapshot(long elapsedNanos) {
            if (elapsedNanos <= 0) throw new IllegalArgumentException("elapsed time must be positive");
            return new MetricsSnapshot(offered, accepted, finalized,
                    finalized * 1_000_000_000.0 / elapsedNanos,
                    micros(50), micros(99), micros(99.9), finalizationLatency.getTotalCount(),
                    micros(90), micros(95), TimeUnit.NANOSECONDS.toMicros(finalizationLatency.getMaxValue()));
        }

        synchronized void printHistogram() {
            log.info("{}", "requestToTerminalHistogramMicros highestTrackableNanos=" + HIGHEST_TRACKABLE_NANOS + " significantDigits=3");
            var output = new java.io.ByteArrayOutputStream();
            try (var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
                finalizationLatency.outputPercentileDistribution(stream, 1000.0);
            }
            log.info("{}", output.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing());
        }

        synchronized void reset() {
            offered = 0;
            accepted = 0;
            finalized = 0;
            steadyFinalized = 0;
            measurementStart = Long.MIN_VALUE;
            measurementEnd = Long.MAX_VALUE;
            finalizationLatency.reset();
            steadyFinalizationLatency.reset();
        }

        private long micros(double percentile) {
            return TimeUnit.NANOSECONDS.toMicros(finalizationLatency.getValueAtPercentile(percentile));
        }
    }

    record MetricsSnapshot(long offered, long accepted, long finalized, double finalizedPerSecond,
                           long p50Micros, long p99Micros, long p999Micros, long correctedSampleCount,
                           long p90Micros, long p95Micros, long maxMicros) {
    }

    /** Terminal increments and latency within the fixed window, excluding warmup and drain. */
    record SteadyMetricsSnapshot(long finalized, double finalizedPerSecond,
                                 long p50Micros, long p99Micros, long p999Micros, long correctedSampleCount,
                                 long p90Micros, long p95Micros, long maxMicros) { }

    private static void printLatency(String business, SteadyMetricsSnapshot m) {
        log.info("latencyBusiness={} samples={} requestToTerminalMicros p50={} p90={} p95={} p99={} p999={} max={}", business, m.finalized(), m.p50Micros(), m.p90Micros(), m.p95Micros(), m.p99Micros(), m.p999Micros(), m.maxMicros());
    }

    static int slotsForWorker(int total, int workers, int worker) {
        if (workers <= 0 || total < workers || worker < 0 || worker >= workers) throw new IllegalArgumentException("invalid window partition");
        return total / workers + (worker < total % workers ? 1 : 0);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int positiveInt(String name, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(String name, int defaultValue) {
        int value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static long positiveLong(String name, long defaultValue) {
        long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long nonNegativeLong(String name, long defaultValue) {
        long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private enum Workload {
        MATCH,
        MATCH_ASYNC,
        MATCH_STREAM,
        MATCH_BATCH_STREAM,
        PLACE_ONLY,
        CANCEL,
        MARK_PRICE;

        boolean stream() { return this == MATCH_STREAM || this == MATCH_BATCH_STREAM; }
        boolean asynchronous() { return this == MATCH_ASYNC || stream(); }
    }

    @Override
    public void close() {
        clients.close();
    }
}
