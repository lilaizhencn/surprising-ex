package com.surprising.aeron.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.HdrHistogram.Histogram;

final class HttpOpenLoopWorkload {

    private static final long HIGHEST_TRACKABLE_NANOS = Duration.ofHours(1).toNanos();
    private static final Pattern JSON_STRING = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern JSON_NUMBER = Pattern.compile("\\\"%s\\\"\\s*:\\s*([0-9]+)");

    private final HttpWorkloadConfig config;
    private final HttpClient client;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, Future<?>> active = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObservedInFlight = new AtomicInteger();
    private final EnumMap<HttpOutcome, Long> classifications = new EnumMap<>(HttpOutcome.class);
    private final Histogram httpLatency = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
    private final Histogram finalizationLatency = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
    private final Object metricsLock = new Object();
    private final PrerequisitePools pools = new PrerequisitePools();
    private volatile StableIdentityLedger ledger;

    HttpOpenLoopWorkload(HttpWorkloadConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.permits = new Semaphore(config.maxInFlight());
        this.client = HttpClient.newBuilder().connectTimeout(config.requestTimeout())
                .version(HttpClient.Version.HTTP_1_1).build();
        for (HttpOutcome outcome : HttpOutcome.values()) classifications.put(outcome, 0L);
    }

    Summary run() {
        verifyHttpSurface();
        try (StableIdentityLedger opened = StableIdentityLedger.open(
                config.outputDirectory(), config.runId(), config.seed())) {
            ledger = opened;
            rebuildPools(opened.snapshots());
            for (StableIdentityLedger.Intent intent : opened.outstanding()) {
                if (cancelled.get()) break;
                dispatch(intent, false);
            }
            scheduleNew(opened);
            awaitDrain();
            opened.flush();
            Summary summary = summary(opened);
            writeArtifacts(summary);
            return summary;
        } finally {
            ledger = null;
            executor.shutdownNow();
        }
    }

    private void verifyHttpSurface() {
        HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve("/actuator/health"))
                .timeout(config.requestTimeout()).GET().build();
        Response response = send(request);
        if (response.status() == 0) {
            throw new IllegalStateException("HTTP workload surface is not reachable: " + response.outcome());
        }
    }

    void cancelOutstanding(String reason) {
        cancelled.set(true);
        StableIdentityLedger current = ledger;
        if (current != null) {
            long now = EpochNanoClock.now();
            for (StableIdentityLedger.Intent intent : current.outstanding()) current.aborted(intent.sequence(), now, reason);
        }
        active.values().forEach(future -> future.cancel(true));
    }

    private void scheduleNew(StableIdentityLedger current) {
        long target = config.totalIntents();
        if (current.maxSequence() >= target) return;
        long firstIntended = current.snapshots().stream().mapToLong(StableIdentityLedger.Snapshot::intendedNanos)
                .min().orElseGet(EpochNanoClock::now);
        for (long sequence = current.maxSequence() + 1; sequence <= target && !cancelled.get(); sequence++) {
            long intended = Math.addExact(firstIntended,
                    Math.multiplyExact(sequence - 1, config.expectedIntervalNanos()));
            waitUntil(intended);
            WorkloadOperation operation = operation(sequence);
            long userId = user(sequence);
            String symbol = symbol(sequence);
            String targetIdentity = pools.reserve(operation, sequence, userId, symbol).orElse("");
            StableIdentityLedger.Intent intent = current.intent(sequence, operation, userId, symbol,
                    expected(operation), targetIdentity);
            current.scheduled(intent, intended);
            if (requiresPrerequisite(operation, sequence) && targetIdentity.isEmpty()) {
                current.aborted(sequence, EpochNanoClock.now(), "missing valid prerequisite");
            } else {
                dispatch(intent, true);
            }
        }
    }

    private void dispatch(StableIdentityLedger.Intent intent, boolean abortWhenSaturated) {
        boolean acquired;
        if (abortWhenSaturated) {
            acquired = permits.tryAcquire();
        } else {
            try {
                permits.acquire();
                acquired = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
        }
        if (!acquired) {
            ledger.aborted(intent.sequence(), EpochNanoClock.now(), "bounded in-flight capacity reached");
            pools.release(intent);
            return;
        }
        int current = inFlight.incrementAndGet();
        maxObservedInFlight.accumulateAndGet(current, Math::max);
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(intent);
            return null;
        });
        active.put(intent.sequence(), task);
        executor.execute(task);
    }

    private void execute(StableIdentityLedger.Intent intent) {
        try {
            if (cancelled.get()) {
                ledger.aborted(intent.sequence(), EpochNanoClock.now(), "workload cancelled");
                pools.release(intent);
                return;
            }
            long sent = EpochNanoClock.now();
            ledger.sent(intent.sequence(), sent);
            Response response = send(request(intent));
            long httpAt = EpochNanoClock.now();
            HttpOutcome initial = response.outcome();
            record(initial);
            ledger.http(intent.sequence(), httpAt, response.status(), initial);
            recordLatency(httpLatency, httpAt - ledgerSnapshot(intent.sequence()).intendedNanos());
            if (response.status() == 202) response = pollAccepted(response.body());
            if (cancelled.get()) {
                ledger.aborted(intent.sequence(), EpochNanoClock.now(), "workload cancelled");
                pools.release(intent);
                return;
            }
            if (response.status() == 202) return;
            if (response.outcome() != initial || response.status() != ledgerSnapshot(intent.sequence()).httpStatus()) {
                record(response.outcome());
                ledger.http(intent.sequence(), EpochNanoClock.now(), response.status(), response.outcome());
            }
            String actual = terminalState(response, intent);
            HttpOutcome terminalOutcome = response.outcome();
            if (terminalOutcome == HttpOutcome.SUCCESS_2XX && !intent.expectedFinalState().equals(actual)) {
                terminalOutcome = HttpOutcome.ORACLE_MISMATCH;
                record(terminalOutcome);
            }
            long finalAt = EpochNanoClock.now();
            ledger.finished(intent.sequence(), finalAt, actual, resourceIdentity(response.body(), intent));
            recordLatency(finalizationLatency, finalAt - ledgerSnapshot(intent.sequence()).intendedNanos());
            if (terminalOutcome == HttpOutcome.SUCCESS_2XX) pools.complete(intent, resourceIdentity(response.body(), intent));
            else pools.release(intent);
        } catch (RuntimeException exception) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                ledger.aborted(intent.sequence(), EpochNanoClock.now(), "in-flight request cancelled");
            } else {
                record(HttpOutcome.TRANSPORT_ERROR);
                ledger.http(intent.sequence(), EpochNanoClock.now(), 0, HttpOutcome.TRANSPORT_ERROR);
                ledger.finished(intent.sequence(), EpochNanoClock.now(), "TRANSPORT_ERROR", "");
            }
            pools.release(intent);
        } finally {
            active.remove(intent.sequence());
            inFlight.decrementAndGet();
            permits.release();
        }
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), HttpOutcome.fromStatus(response.statusCode()));
        } catch (HttpTimeoutException exception) {
            return new Response(0, "", HttpOutcome.TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", interrupted);
        } catch (IOException exception) {
            return new Response(0, "", HttpOutcome.TRANSPORT_ERROR);
        }
    }

    private Response pollAccepted(String initialBody) {
        String resultUrl = stringField(initialBody, "commandResultUrl").orElseGet(() ->
                stringField(initialBody, "commandId").map(id -> "/api/v1/trading/orders/commands/" + id).orElse(""));
        if (resultUrl.isEmpty()) return new Response(202, initialBody, HttpOutcome.ACCEPTED_202);
        URI uri = resultUrl.startsWith("http") ? URI.create(resultUrl) : config.baseUri().resolve(resultUrl);
        Response response = new Response(202, initialBody, HttpOutcome.ACCEPTED_202);
        for (int attempt = 0; attempt < config.maxPolls() && response.status() == 202 && !cancelled.get(); attempt++) {
            if (!config.pollInterval().isZero()) LockSupport.parkNanos(config.pollInterval().toNanos());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.requestTimeout()).GET()
                    .header("Accept", "application/json").build();
            response = send(request);
        }
        return response;
    }

    private HttpRequest request(StableIdentityLedger.Intent intent) {
        RequestSpec spec = requestSpec(intent);
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.baseUri().resolve(spec.path()))
                .timeout(config.requestTimeout()).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Workload-Intent-Id", intent.intentId().toString())
                .header("X-Client-Request-Id", intent.clientIdentity());
        return "GET".equals(spec.method()) ? builder.GET().build()
                : builder.method(spec.method(), HttpRequest.BodyPublishers.ofString(spec.body())).build();
    }

    private RequestSpec requestSpec(StableIdentityLedger.Intent intent) {
        String clientId = intent.clientIdentity();
        return switch (intent.operation()) {
            case PLACE -> post("/api/v1/trading/orders", orderJson(intent, "LIMIT", "GTC", false));
            case CANCEL -> post("/api/v1/trading/orders/cancel",
                    "{\"userId\":" + intent.userId() + ",\"orderId\":" + intent.targetIdentity() + "}");
            case AMEND -> post("/api/v1/trading/orders/amend",
                    "{\"userId\":" + intent.userId() + ",\"orderId\":" + intent.targetIdentity()
                            + ",\"newClientOrderId\":\"" + clientId + "\",\"priceTicks\":101,"
                            + "\"quantitySteps\":1,\"timeInForce\":\"GTC\",\"postOnly\":false,"
                            + "\"clientRequestId\":\"" + clientId + "\"}");
            case MARKET_IOC_CLOSE -> intent.sequence() % 2 == 0
                    ? post("/api/v1/trading/orders", orderJson(intent, "MARKET", "IOC", true))
                    : post("/api/v1/trading/orders/close-position",
                            "{\"userId\":" + intent.userId() + ",\"clientOrderId\":\"" + clientId
                                    + "\",\"symbol\":\"" + intent.symbol()
                                    + "\",\"marginMode\":\"CROSS\",\"positionSide\":\"NET\"}");
            case TRIGGER -> post("/api/v1/trading/trigger-orders",
                    "{\"userId\":" + intent.userId() + ",\"clientTriggerOrderId\":\"" + clientId
                            + "\",\"symbol\":\"" + intent.symbol() + "\",\"side\":\"SELL\","
                            + "\"triggerType\":\"STOP_LOSS\",\"triggerPriceTicks\":90,\"orderType\":\"MARKET\","
                            + "\"timeInForce\":\"IOC\",\"priceTicks\":0,\"quantitySteps\":1,"
                            + "\"marginMode\":\"CROSS\",\"positionSide\":\"NET\"}");
            case TRIGGER_CANCEL -> post("/api/v1/trading/trigger-orders/cancel",
                    "{\"userId\":" + intent.userId() + ",\"triggerOrderId\":" + intent.targetIdentity() + "}");
            case BATCH_ALGO_CONTROL -> controlRequest(intent);
        };
    }

    private RequestSpec controlRequest(StableIdentityLedger.Intent intent) {
        return switch ((int) Math.floorMod(intent.sequence(), 3)) {
            case 0 -> post("/api/v1/trading/orders/batch", "{\"batchKey\":\"" + intent.clientIdentity()
                    + "\",\"orders\":[" + orderJson(intent, "LIMIT", "GTC", false) + "]}");
            case 1 -> post("/api/v1/trading/orders/algo", "{\"userId\":" + intent.userId()
                    + ",\"clientAlgoOrderId\":\"" + intent.clientIdentity() + "\",\"symbol\":\""
                    + intent.symbol() + "\",\"algoType\":\"TWAP\",\"side\":\"BUY\",\"priceTicks\":100,"
                    + "\"quantitySteps\":1,\"childQuantitySteps\":1,\"intervalSeconds\":1,"
                    + "\"durationSeconds\":1,\"marginMode\":\"CROSS\",\"positionSide\":\"NET\","
                    + "\"reduceOnly\":false,\"postOnly\":true,\"timeInForce\":\"GTC\"}");
            default -> post("/api/v1/trading/orders/cancel-all-after", "{\"userId\":" + intent.userId()
                    + ",\"symbol\":\"" + intent.symbol() + "\",\"countdownMs\":60000}");
        };
    }

    private String orderJson(StableIdentityLedger.Intent intent, String type, String tif, boolean reduceOnly) {
        return "{\"userId\":" + intent.userId() + ",\"clientOrderId\":\"" + intent.clientIdentity()
                + "\",\"symbol\":\"" + intent.symbol() + "\",\"side\":\"BUY\",\"orderType\":\""
                + type + "\",\"timeInForce\":\"" + tif + "\",\"priceTicks\":"
                + ("MARKET".equals(type) ? 0 : 100) + ",\"quantitySteps\":1,\"marginMode\":\"CROSS\","
                + "\"positionSide\":\"NET\",\"reduceOnly\":" + reduceOnly + ",\"postOnly\":false}";
    }

    private String terminalState(Response response, StableIdentityLedger.Intent intent) {
        if (response.outcome() != HttpOutcome.SUCCESS_2XX) return response.outcome().name();
        return stringField(response.body(), "code").or(() -> stringField(response.body(), "status"))
                .orElse(intent.expectedFinalState());
    }

    private String resourceIdentity(String body, StableIdentityLedger.Intent intent) {
        String field = intent.operation() == WorkloadOperation.TRIGGER ? "triggerOrderId" : "orderId";
        return numberField(body, field).orElseGet(() ->
                numberField(body, "prospectiveOrderIds").orElseGet(() -> stablePositive(intent.clientIdentity())));
    }

    private void awaitDrain() {
        long deadline = System.nanoTime() + config.requestTimeout().multipliedBy(config.maxPolls() + 2L).toNanos();
        while (inFlight.get() > 0 && !cancelled.get() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }

    private Summary summary(StableIdentityLedger current) {
        long scheduled = current.scheduledCount();
        long completed = current.completedCount();
        long outstanding = current.outstandingCount();
        long aborted = current.abortedCount();
        if (scheduled != completed + outstanding + aborted) {
            throw new IllegalStateException("accounting invariant violated scheduled=" + scheduled + " completed="
                    + completed + " outstanding=" + outstanding + " deliberately_aborted=" + aborted);
        }
        synchronized (metricsLock) {
            return new Summary(scheduled, completed, outstanding, aborted, maxObservedInFlight.get(),
                    Map.copyOf(classifications));
        }
    }

    private void writeArtifacts(Summary summary) {
        try {
            Files.createDirectories(config.outputDirectory());
            writeHistogram(config.outputDirectory().resolve("http-corrected.hdr"), httpLatency);
            writeHistogram(config.outputDirectory().resolve("finalization-corrected.hdr"), finalizationLatency);
            String json = "{\"runId\":\"" + config.runId() + "\",\"seed\":" + config.seed()
                    + ",\"scheduled\":" + summary.scheduled() + ",\"completed\":" + summary.completed()
                    + ",\"outstanding\":" + summary.outstanding() + ",\"deliberately_aborted\":"
                    + summary.deliberatelyAborted() + ",\"maxObservedInFlight\":" + summary.maxObservedInFlight()
                    + ",\"accounting\":\"PASS\",\"classifications\":\"" + summary.classifications() + "\"}\n";
            Files.writeString(config.outputDirectory().resolve("accounting.json"), json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write workload artifacts", exception);
        }
    }

    private void writeHistogram(Path path, Histogram histogram) throws IOException {
        try (PrintStream output = new PrintStream(Files.newOutputStream(path), false, StandardCharsets.UTF_8)) {
            output.println("# coordinated-omission-corrected expectedIntervalNanos=" + config.expectedIntervalNanos());
            synchronized (metricsLock) {
                histogram.outputPercentileDistribution(output, 1_000.0);
            }
        }
    }

    private void record(HttpOutcome outcome) {
        synchronized (metricsLock) {
            classifications.merge(outcome, 1L, Math::addExact);
        }
    }

    private void recordLatency(Histogram histogram, long value) {
        long bounded = Math.max(1L, Math.min(HIGHEST_TRACKABLE_NANOS, value));
        synchronized (metricsLock) {
            histogram.recordValueWithExpectedInterval(bounded, config.expectedIntervalNanos());
        }
    }

    private StableIdentityLedger.Snapshot ledgerSnapshot(long sequence) {
        return ledger.snapshot(sequence);
    }

    private WorkloadOperation operation(long sequence) {
        int bucket = (int) Math.floorMod(sequence - 1 + config.seed(), 100L);
        int boundary = 0;
        for (WorkloadOperation operation : WorkloadOperation.values()) {
            boundary += config.traffic().get(operation);
            if (bucket < boundary) return operation;
        }
        throw new IllegalStateException("traffic mix does not cover bucket " + bucket);
    }

    private long user(long sequence) {
        long[] users = config.users();
        long mixed = mix(sequence ^ config.seed());
        boolean hot = config.skew() == TrafficSkew.HOT_USER || config.skew() == TrafficSkew.COMBINED_HOT;
        int bound = hot && Math.floorMod(mixed, 100L) < 50 ? Math.max(1, users.length / 100) : users.length;
        return users[Math.floorMod((int) mixed, bound)];
    }

    private String symbol(long sequence) {
        String[] symbols = config.symbols();
        long mixed = mix(sequence + config.seed());
        boolean hot = config.skew() == TrafficSkew.HOT_SYMBOL || config.skew() == TrafficSkew.COMBINED_HOT;
        int bound = hot && Math.floorMod(mixed, 100L) < 70 ? Math.max(1, symbols.length / 20) : symbols.length;
        return symbols[Math.floorMod((int) (mixed >>> 32), bound)];
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static String expected(WorkloadOperation operation) {
        return switch (operation) {
            case TRIGGER -> "PENDING";
            case TRIGGER_CANCEL -> "CANCELED";
            default -> "APPLIED";
        };
    }

    private static boolean requiresPrerequisite(WorkloadOperation operation, long sequence) {
        return operation == WorkloadOperation.CANCEL || operation == WorkloadOperation.AMEND
                || operation == WorkloadOperation.TRIGGER_CANCEL
                || operation == WorkloadOperation.MARKET_IOC_CLOSE && sequence % 2 != 0;
    }

    private static RequestSpec post(String path, String body) {
        return new RequestSpec("POST", path, body);
    }

    private static Optional<String> stringField(String json, String name) {
        Matcher matcher = Pattern.compile(JSON_STRING.pattern().formatted(Pattern.quote(name))).matcher(json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> numberField(String json, String name) {
        Matcher matcher = Pattern.compile(JSON_NUMBER.pattern().formatted(Pattern.quote(name))).matcher(json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String stablePositive(String value) {
        return Long.toString(Math.max(1L, Math.abs((long) value.hashCode()) + 1L));
    }

    private static void waitUntil(long epochNanos) {
        while (true) {
            long remaining = epochNanos - EpochNanoClock.now();
            if (remaining <= 0) return;
            LockSupport.parkNanos(Math.min(remaining, Duration.ofMillis(1).toNanos()));
        }
    }

    private void rebuildPools(List<StableIdentityLedger.Snapshot> snapshots) {
        for (StableIdentityLedger.Snapshot snapshot : snapshots) {
            if (snapshot.terminal() && snapshot.outcome() == HttpOutcome.SUCCESS_2XX) {
                pools.complete(snapshot.intent(), snapshot.resourceIdentity());
            }
        }
    }

    record Summary(long scheduled, long completed, long outstanding, long deliberatelyAborted,
                   int maxObservedInFlight, Map<HttpOutcome, Long> classifications) {
    }

    private record Response(int status, String body, HttpOutcome outcome) {
    }

    private record RequestSpec(String method, String path, String body) {
    }

    private record Resource(long userId, String symbol, String identity) {
    }

    private static final class PrerequisitePools {
        private final Deque<Resource> orders = new ArrayDeque<>();
        private final Deque<Resource> triggers = new ArrayDeque<>();
        private final Deque<Resource> positions = new ArrayDeque<>();

        synchronized Optional<String> reserve(WorkloadOperation operation, long sequence, long userId, String symbol) {
            Deque<Resource> source = operation == WorkloadOperation.TRIGGER_CANCEL ? triggers
                    : operation == WorkloadOperation.MARKET_IOC_CLOSE ? positions : orders;
            if (!requiresPrerequisite(operation, sequence)) return Optional.of("");
            for (Resource resource : source) {
                if (resource.userId() == userId && resource.symbol().equals(symbol)) {
                    if (operation != WorkloadOperation.AMEND) source.remove(resource);
                    return Optional.of(resource.identity());
                }
            }
            return Optional.empty();
        }

        synchronized void complete(StableIdentityLedger.Intent intent, String resourceIdentity) {
            if (intent.operation() == WorkloadOperation.PLACE && !resourceIdentity.isBlank()) {
                orders.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
            } else if (intent.operation() == WorkloadOperation.TRIGGER && !resourceIdentity.isBlank()) {
                triggers.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
            } else if (intent.operation() == WorkloadOperation.MARKET_IOC_CLOSE
                    && intent.sequence() % 2 == 0 && !resourceIdentity.isBlank()) {
                positions.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
            }
        }

        synchronized void release(StableIdentityLedger.Intent intent) {
            if (intent.targetIdentity().isBlank()) return;
            Resource resource = new Resource(intent.userId(), intent.symbol(), intent.targetIdentity());
            if (intent.operation() == WorkloadOperation.TRIGGER_CANCEL) triggers.addFirst(resource);
            else if (intent.operation() == WorkloadOperation.CANCEL) orders.addFirst(resource);
            else if (intent.operation() == WorkloadOperation.MARKET_IOC_CLOSE) positions.addFirst(resource);
        }
    }

    private static final class EpochNanoClock {
        private static final long OFFSET = Math.multiplyExact(System.currentTimeMillis(), 1_000_000L) - System.nanoTime();

        static long now() {
            return Math.addExact(OFFSET, System.nanoTime());
        }
    }
}
