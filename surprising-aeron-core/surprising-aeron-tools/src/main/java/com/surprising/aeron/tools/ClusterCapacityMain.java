package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.CoreAcceptFreezeBenchmark;
import com.surprising.aeron.service.CoreAcceptFreezeConcurrentBenchmark;
import com.surprising.aeron.service.CoreInMemoryBenchmark;
import com.surprising.aeron.service.CorePerpetualEndToEndBenchmark;
import com.surprising.aeron.service.ExchangeCoreConcurrentBenchmark;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;

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
    private final AeronClientPool clients;
    private final AtomicLong nextOrderId;
    private final AtomicLong nextPriceSequence = new AtomicLong();
    private final AtomicLong nextPermitNanos = new AtomicLong();
    private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong commands = new AtomicLong();
    private final AtomicLong matches = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
    private final Object[] symbolLocks;
    private final CapacityMetrics capacityMetrics;

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
        this.symbolLocks = java.util.stream.IntStream.range(0, symbols.size())
                .mapToObj(ignored -> new Object()).toArray(Object[]::new);
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
        String symbolPrefix = System.getProperty("surprising.aeron.symbol", "P9-CAPACITY-BTC-USDT")
                .trim().toUpperCase();
        int symbolCount = positiveInt("surprising.aeron.capacity-symbol-count", 1);
        List<String> symbols = java.util.stream.IntStream.range(0, symbolCount)
                .mapToObj(index -> symbolCount == 1 ? symbolPrefix : symbolPrefix + '-' + (index + 1))
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
        String mode = System.getProperty("surprising.aeron.capacity-mode", "run").trim().toLowerCase();
        try (ClusterCapacityMain benchmark = new ClusterCapacityMain(productLine, hosts, egress, symbols, seed,
                workers, connections, userCount, asyncInFlight, warmupSeconds, durationSeconds, offered, workload)) {
            if ("verify".equals(mode)) {
                benchmark.cancelOrders(System.getProperty("surprising.aeron.capacity-cancel-orders", ""));
                benchmark.verifyFundsAndBook();
                System.out.printf("capacityVerify=PASS productLine=%s workers=%d symbols=%d "
                                + "fundsDiff=0 bookLevels=0%n",
                        productLine, workers, symbols.size());
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
        latenciesNanos.clear();
        commands.set(0);
        matches.set(0);
        failures.set(0);
        firstFailure.set(null);
        capacityMetrics.reset();
        nextPermitNanos.set(System.nanoTime());
        long started = System.nanoTime();
        execute(durationSeconds, true);
        long elapsedNanos = System.nanoTime() - started;
        verifyFundsAndBook();
        List<Long> sorted;
        synchronized (latenciesNanos) {
            sorted = new ArrayList<>(latenciesNanos);
        }
        Collections.sort(sorted);
        long commandCount = commands.get();
        long matchCount = matches.get();
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        MetricsSnapshot metrics = capacityMetrics.snapshot(elapsedNanos);
        System.out.printf("capacity=PASS scope=LOCAL_CAPACITY productLine=%s workload=%s symbols=%d users=%d workers=%d connections=%d "
                        + "targetOfferedPerSec=%d offered=%d accepted=%d finalized=%d matches=%d failures=%d elapsedSeconds=%.3f "
                        + "finalizedPerSec=%.3f coreMatchEventsPerSec=%.3f acceptanceToFinalizationP50Micros=%d "
                        + "acceptanceToFinalizationP99Micros=%d acceptanceToFinalizationP999Micros=%d "
                        + "pendingMax=-1 completionQueueMax=-1 outboxMaxSequence=%d fundsDiff=0 bookLevels=0%n",
                productLine, workload, symbols.size(), userCount, workers, connections, offeredCommandsPerSecond,
                metrics.offered(), metrics.accepted(), metrics.finalized(), matchCount, failures.get(), elapsedSeconds,
                metrics.finalizedPerSecond(), matchCount / elapsedSeconds, metrics.p50Micros(), metrics.p99Micros(),
                metrics.p999Micros(), metrics.outboxMaxSequence());
    }

    private void setup() {
        for (String symbol : symbols) {
            applied(CoreMessageType.UPSERT_INSTRUMENT, 1,
                    TradingCommandCodec.encodeUpsertInstrument(instrument(symbol)), stableId("instrument:" + symbol));
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

    private void execute(int seconds, boolean measured) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long cycle = 0;
                    while (!stop.get() && System.nanoTime() < deadline) {
                        try {
                            if (workload == Workload.MATCH) {
                                matchCycle(workerId, cycle++, measured);
                            } else if (workload == Workload.MATCH_ASYNC) {
                                asyncMatch(workerId, deadline, measured);
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
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(seconds + 30L, TimeUnit.SECONDS)) {
                stop.set(true);
                executor.shutdownNow();
                throw new IllegalStateException("capacity workers did not terminate");
            }
        }
        if (failures.get() != 0) {
            throw new IllegalStateException("capacity workload failed count=" + failures.get(), firstFailure.get());
        }
    }

    private void matchCycle(int worker, long cycle, boolean measured) {
        String symbol = symbol(worker, cycle);
        synchronized (symbolLocks[symbols.indexOf(symbol)]) {
            boolean reverse = (cycle & 1L) != 0;
            int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
            long makerUser = firstUser(pair);
            long takerUser = secondUser(pair);
            CoreOrderSide makerSide = reverse ? CoreOrderSide.BUY : CoreOrderSide.SELL;
            CoreOrderSide takerSide = reverse ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            long makerOrder = nextOrderId.incrementAndGet();
            long takerOrder = nextOrderId.incrementAndGet();
            submitOrder(makerUser, order(symbol, makerOrder, makerSide, CoreTimeInForce.GTC), measured);
            submitOrder(takerUser, order(symbol, takerOrder, takerSide, CoreTimeInForce.IOC), measured);
            if (measured) {
                matches.incrementAndGet();
            }
        }
    }

    private void asyncMatch(int worker, long deadline, boolean measured) {
        List<AsyncPair> pending = new ArrayList<>();
        long cycle = 0;
        while (System.nanoTime() < deadline || !pending.isEmpty()) {
            while (System.nanoTime() < deadline && pending.size() < asyncInFlight) {
                int pair = Math.floorMod(worker + Math.toIntExact(cycle), pairCount);
                String symbol = symbol(worker, cycle);
                long makerOrder = nextOrderId.incrementAndGet();
                long takerOrder = nextOrderId.incrementAndGet();
                CoreOrderSide makerSide = CoreOrderSide.SELL;
                CoreOrderSide takerSide = CoreOrderSide.BUY;
                long started = System.nanoTime();
                throttle();
                if (measured) capacityMetrics.recordOffered();
                CompletableFuture<CoreResponse> maker = clients.commandAsync(
                        CoreMessageType.PLACE_ORDER, stableId("async-maker:" + makerOrder), firstUser(pair),
                        TradingCommandCodec.encodePlaceOrder(order(symbol, makerOrder, makerSide, CoreTimeInForce.GTC)));
                throttle();
                CompletableFuture<CoreResponse> pairResult = maker.thenCompose(response -> {
                    if (response.commandStatus() != ResponseStatus.APPLIED) {
                        throw new IllegalStateException("async maker rejected status=" + response.commandStatus());
                    }
                    record(response, System.nanoTime() - started, measured);
                    if (measured) capacityMetrics.recordOffered();
                    return clients.commandAsync(CoreMessageType.PLACE_ORDER,
                            stableId("async-taker:" + takerOrder), secondUser(pair),
                            TradingCommandCodec.encodePlaceOrder(order(symbol, takerOrder, takerSide, CoreTimeInForce.IOC)));
                });
                pending.add(new AsyncPair(pairResult, started));
                cycle++;
            }
            if (!pending.isEmpty()) {
                AsyncPair pair = pending.getFirst();
                if (!pair.result().isDone()) {
                    LockSupport.parkNanos(100_000L);
                    continue;
                }
                pending.removeFirst();
                CoreResponse response = pair.result().getNow(null);
                record(response, System.nanoTime() - pair.startedNanos(), measured);
                if (measured) {
                    commands.incrementAndGet();
                    matches.incrementAndGet();
                }
            }
        }
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
        String symbol = symbol(worker, orderId);
        submitOrder(userId, order(symbol, orderId, CoreOrderSide.SELL, CoreTimeInForce.GTC, 110), measured);
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
        String symbol = symbol(worker, cycle);
        submitOrder(userId, order(symbol, orderId, CoreOrderSide.BUY, CoreTimeInForce.IOC, 90), measured);
    }

    private void markPriceCycle(int worker, long cycle, boolean measured) {
        String symbol = symbol(worker, cycle);
        synchronized (symbolLocks[symbols.indexOf(symbol)]) {
            throttle();
            long started = System.nanoTime();
            if (measured) capacityMetrics.recordOffered();
            long sequence = nextPriceSequence.incrementAndGet();
            var response = clients.command(CoreMessageType.APPLY_MARK_PRICE,
                    stableId("mark-price:" + worker + ':' + cycle), firstUser(Math.floorMod(worker, pairCount)),
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                            symbol, 1, PRICE_TICKS + (cycle & 1L), sequence, 1_700_000_000_000L)));
            record(response, System.nanoTime() - started, measured);
        }
    }

    private void submitOrder(long userId, PlaceOrderCommand command, boolean measured) {
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
            commands.incrementAndGet();
            latenciesNanos.add(latencyNanos);
            capacityMetrics.recordAccepted();
            capacityMetrics.recordFinalized(latencyNanos, response.requiredExportSequence());
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
                .filter(level -> symbols.contains(level.symbol()))
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

    private String symbol(int worker, long cycle) {
        return symbols.get(Math.floorMod(worker + cycle, symbols.size()));
    }

    private PlaceOrderCommand order(
            String symbol, long orderId, CoreOrderSide side, CoreTimeInForce timeInForce) {
        return order(symbol, orderId, side, timeInForce, PRICE_TICKS);
    }

    private PlaceOrderCommand order(
            String symbol, long orderId, CoreOrderSide side, CoreTimeInForce timeInForce, long price) {
        String reservationAsset = productLine == ProductLine.SPOT
                ? (side == CoreOrderSide.BUY ? "USDT" : "BTC") : settleAsset();
        return new PlaceOrderCommand(orderId, symbol, 1, side, price, QUANTITY_STEPS, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, timeInForce, false, "");
    }

    private UpsertInstrumentCommand instrument(String symbol) {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new UpsertInstrumentCommand(symbol, 1, type.ordinal(), "BTC", "USDT", settleAsset(), 1, 1,
                type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0, expiry,
                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private void adjust(long userId, String asset, long units) {
        applied(CoreMessageType.ADJUST_BALANCE, userId,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, units)),
                stableId("fund:" + userId + ':' + asset));
    }

    private void applied(CoreMessageType type, long userId, byte[] payload, UUID commandId) {
        var response = clients.command(type, commandId, userId, payload);
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(type + " rejected status=" + response.commandStatus());
        }
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

    private static long percentileMicros(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return TimeUnit.NANOSECONDS.toMicros(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private static void runLocalBaseline(String[] args) {
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 9901L;
        if (seed <= 0) throw new IllegalArgumentException("baseline seed must be positive");
        int makerDepth = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        ExchangeCoreConcurrentBenchmark.main(new String[]{"500", "100", "64", "2"});
        CoreAcceptFreezeBenchmark.main(new String[]{"25", "5"});
        CoreInMemoryBenchmark.main(new String[]{"25", "5"});
        CoreAcceptFreezeConcurrentBenchmark.main(new String[]{"50", "10", "2"});
        CorePerpetualEndToEndBenchmark.BaselineResult perpetual =
                CorePerpetualEndToEndBenchmark.measure(25, 5, makerDepth);
        CapacityMetrics finalizationMetrics = new CapacityMetrics(TimeUnit.MILLISECONDS.toNanos(1));
        for (long latency : perpetual.latenciesNanos()) {
            finalizationMetrics.recordOffered();
            finalizationMetrics.recordAccepted();
            finalizationMetrics.recordFinalized(latency, 0);
        }
        MetricsSnapshot finalization = finalizationMetrics.snapshot(perpetual.elapsedNanos());
        System.out.printf("perpetualEndToEndBenchmark=PASS cycles=%d makerDepth=%d orders=%d matchedQuantity=%d elapsedSeconds=%.3f "
                        + "finalizedPerSec=%.3f corrected=true expectedIntervalMicros=1000 "
                        + "p50Micros=%d p99Micros=%d p999Micros=%d pendingMatching=%d%n",
                perpetual.cycles(), perpetual.makerDepth(), perpetual.finalizedOrders(), perpetual.matchedQuantity(),
                perpetual.elapsedNanos() / 1_000_000_000.0,
                finalization.finalizedPerSecond(), finalization.p50Micros(), finalization.p99Micros(),
                finalization.p999Micros(), perpetual.pendingMatching());
        System.out.printf("clusterCapacityBaseline=PASS seed=%d suite=baseline makerDepth=%d%n", seed, makerDepth);
    }

    static final class CapacityMetrics {
        private static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.MINUTES.toNanos(1);

        private final long expectedIntervalNanos;
        private final Histogram finalizationLatency = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
        private long offered;
        private long accepted;
        private long finalized;
        private long outboxMaxSequence;

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

        synchronized void recordFinalized(long acceptanceToFinalizationNanos, long requiredExportSequence) {
            if (acceptanceToFinalizationNanos <= 0) {
                throw new IllegalArgumentException("acceptance-to-finalization latency must be positive");
            }
            finalizationLatency.recordValueWithExpectedInterval(
                    Math.min(acceptanceToFinalizationNanos, HIGHEST_TRACKABLE_NANOS), expectedIntervalNanos);
            finalized++;
            outboxMaxSequence = Math.max(outboxMaxSequence, requiredExportSequence);
        }

        synchronized MetricsSnapshot snapshot(long elapsedNanos) {
            if (elapsedNanos <= 0) throw new IllegalArgumentException("elapsed time must be positive");
            return new MetricsSnapshot(offered, accepted, finalized,
                    finalized * 1_000_000_000.0 / elapsedNanos,
                    micros(50), micros(99), micros(99.9), finalizationLatency.getTotalCount(), outboxMaxSequence);
        }

        synchronized void reset() {
            offered = 0;
            accepted = 0;
            finalized = 0;
            outboxMaxSequence = 0;
            finalizationLatency.reset();
        }

        private long micros(double percentile) {
            return TimeUnit.NANOSECONDS.toMicros(finalizationLatency.getValueAtPercentile(percentile));
        }
    }

    record MetricsSnapshot(long offered, long accepted, long finalized, double finalizedPerSecond,
                           long p50Micros, long p99Micros, long p999Micros, long correctedSampleCount,
                           long outboxMaxSequence) {
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
        PLACE_ONLY,
        CANCEL,
        MARK_PRICE
    }

    private record AsyncPair(CompletableFuture<CoreResponse> result, long startedNanos) {
    }

    @Override
    public void close() {
        clients.close();
    }
}
