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
import java.util.HashMap;
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
                config.outputDirectory(), config.runId(), config.seed(), config.fingerprint())) {
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
        boolean healthy = response.status() >= 200 && response.status() < 300;
        if (!healthy) {
            throw new IllegalStateException("HTTP workload surface is not healthy: status=" + response.status()
                    + " outcome=" + response.outcome());
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
            Optional<Resource> reserved = reservePrerequisite(operation, sequence);
            long intentUserId = reserved.map(Resource::userId).orElse(userId);
            String intentSymbol = reserved.map(Resource::symbol).orElse(symbol);
            String targetIdentity = reserved.map(Resource::identity).orElse("");
            StableIdentityLedger.Intent intent = current.intent(sequence, operation, intentUserId, intentSymbol,
                    expected(operation), targetIdentity);
            current.scheduled(intent, intended);
            if (requiresPrerequisite(operation, sequence) && targetIdentity.isEmpty()) {
                current.aborted(sequence, EpochNanoClock.now(), "missing valid prerequisite");
            } else {
                dispatch(intent, true);
            }
        }
    }

    private Optional<Resource> reservePrerequisite(WorkloadOperation operation, long sequence) {
        Optional<Resource> reserved = pools.reserve(operation, sequence);
        if (!requiresPrerequisite(operation, sequence) || reserved.isPresent()) return reserved;
        long deadline = System.nanoTime() + config.requestTimeout().toNanos();
        while (reserved.isEmpty() && !cancelled.get() && !active.isEmpty()
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            reserved = pools.reserve(operation, sequence);
        }
        return reserved;
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
            if (shouldPoll(response, intent)) response = pollResult(response, intent);
            if (cancelled.get()) {
                ledger.aborted(intent.sequence(), EpochNanoClock.now(), "workload cancelled");
                pools.release(intent);
                return;
            }
            if (isUnresolved(response, intent)) return;
            if (response.outcome() != initial || response.status() != ledgerSnapshot(intent.sequence()).httpStatus()) {
                record(response.outcome());
                ledger.http(intent.sequence(), EpochNanoClock.now(), response.status(), response.outcome());
            }
            String actual = terminalState(response, intent);
            HttpOutcome terminalOutcome = response.outcome();
            if (terminalOutcome == HttpOutcome.SUCCESS_2XX && !isAcceptedTerminalState(intent, actual)) {
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

    private Response pollResult(Response initial, StableIdentityLedger.Intent intent) {
        String initialBody = initial.body();
        String resultUrl = stringField(initialBody, "commandResultUrl").orElseGet(() ->
                stringField(initialBody, "commandId").map(id -> "/api/v1/trading/orders/commands/" + id).orElse(""));
        if (resultUrl.isEmpty()) return initial;
        URI uri = resultUrl.startsWith("http") ? URI.create(resultUrl) : config.baseUri().resolve(resultUrl);
        Response response = initial;
        for (int attempt = 0; attempt < config.maxPolls() && shouldPoll(response, intent)
                && !cancelled.get(); attempt++) {
            if (!config.pollInterval().isZero()) LockSupport.parkNanos(config.pollInterval().toNanos());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(config.requestTimeout()).GET()
                    .header("Accept", "application/json").build();
            response = send(request);
        }
        return response;
    }

    private boolean shouldPoll(Response response, StableIdentityLedger.Intent intent) {
        if (response.status() == 202) return true;
        if (response.outcome() != HttpOutcome.SUCCESS_2XX || isTerminalWrapper(response.body())) return false;
        if (intent.operation() == WorkloadOperation.TRIGGER || intent.operation() == WorkloadOperation.BATCH_ALGO_CONTROL) {
            return false;
        }
        return isPendingCommandReceipt(response.body());
    }

    private boolean isUnresolved(Response response, StableIdentityLedger.Intent intent) {
        if (response.status() == 202) return true;
        if (response.outcome() != HttpOutcome.SUCCESS_2XX) return false;
        if (isTerminalWrapper(response.body())) return false;
        if (intent.operation() == WorkloadOperation.TRIGGER) return stringField(response.body(), "status").isEmpty();
        if (intent.operation() == WorkloadOperation.BATCH_ALGO_CONTROL) return !isDirectControlResult(response.body());
        String actual = terminalState(response, intent);
        return "RESULT_UNKNOWN".equals(actual) || isPendingCommandReceipt(response.body());
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
                            + ",\"newClientOrderId\":\"" + clientId + "\",\"priceTicks\":"
                            + config.limitPriceTicks() + ","
                            + "\"quantitySteps\":1,\"timeInForce\":\"GTC\",\"postOnly\":false,"
                            + "\"clientRequestId\":\"" + clientId + "\"}");
            case MARKET_IOC_CLOSE -> !isPositionCloseIntent(intent.sequence())
                    ? post("/api/v1/trading/orders", orderJson(intent, "MARKET", "IOC", false))
                    : post("/api/v1/trading/orders/close-position",
                            "{\"userId\":" + intent.userId() + ",\"clientOrderId\":\"" + clientId
                                    + "\",\"symbol\":\"" + intent.symbol()
                                    + "\",\"marginMode\":\"CROSS\",\"positionSide\":\"NET\"}");
            case TRIGGER -> post("/api/v1/trading/trigger-orders",
                    "{\"userId\":" + intent.userId() + ",\"clientTriggerOrderId\":\"" + clientId
                            + "\",\"symbol\":\"" + intent.symbol() + "\",\"side\":\"SELL\","
                            + "\"triggerType\":\"STOP_LOSS\",\"triggerPriceTicks\":"
                            + config.triggerPriceTicks() + ",\"orderType\":\"MARKET\","
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
                    + intent.symbol() + "\",\"algoType\":\"TWAP\",\"side\":\"BUY\",\"priceTicks\":"
                    + config.limitPriceTicks() + ","
                    + "\"quantitySteps\":1,\"childQuantitySteps\":1,\"intervalSeconds\":1,"
                    + "\"durationSeconds\":1,\"marginMode\":\"CROSS\",\"positionSide\":\"NET\","
                    + "\"reduceOnly\":false,\"postOnly\":false,\"timeInForce\":\"IOC\"}");
            default -> post("/api/v1/trading/orders/cancel-all-after", "{\"userId\":" + intent.userId()
                    + ",\"symbol\":\"" + intent.symbol() + "\",\"countdownMs\":60000}");
        };
    }

    private String orderJson(StableIdentityLedger.Intent intent, String type, String tif, boolean reduceOnly) {
        return "{\"userId\":" + intent.userId() + ",\"clientOrderId\":\"" + intent.clientIdentity()
                + "\",\"symbol\":\"" + intent.symbol() + "\",\"side\":\"BUY\",\"orderType\":\""
                + type + "\",\"timeInForce\":\"" + tif + "\",\"priceTicks\":"
                + ("MARKET".equals(type) ? 0 : config.limitPriceTicks())
                + ",\"quantitySteps\":1,\"marginMode\":\"CROSS\","
                + "\"positionSide\":\"NET\",\"reduceOnly\":" + reduceOnly + ",\"postOnly\":false}";
    }

    private String terminalState(Response response, StableIdentityLedger.Intent intent) {
        if (response.outcome() != HttpOutcome.SUCCESS_2XX) return response.outcome().name();
        Optional<String> nestedOrderState = nestedStringField(response.body(), "result", "status");
        if (isTerminalWrapper(response.body()) && nestedOrderState.isPresent()) return nestedOrderState.get();
        return stringField(response.body(), "status").or(() -> stringField(response.body(), "code"))
                .or(() -> intent.operation() == WorkloadOperation.BATCH_ALGO_CONTROL
                        && isDirectControlResult(response.body()) ? Optional.of("APPLIED") : Optional.empty())
                .orElse("RESULT_UNKNOWN");
    }

    private static boolean isAcceptedTerminalState(StableIdentityLedger.Intent intent, String state) {
        return switch (intent.operation()) {
            case PLACE -> state.equals("OPEN") || state.equals("FILLED") || state.equals("APPLIED");
            case CANCEL -> state.equals("CANCELED") || state.equals("APPLIED");
            case AMEND -> state.equals("OPEN") || state.equals("APPLIED");
            case MARKET_IOC_CLOSE -> state.equals("FILLED") || state.equals("APPLIED");
            case TRIGGER -> state.equals("PENDING");
            case TRIGGER_CANCEL -> state.equals("CANCELED") || state.equals("APPLIED");
            case BATCH_ALGO_CONTROL -> state.equals("PENDING") || state.equals("APPLIED") || state.equals("CANCELED");
        };
    }

    private String resourceIdentity(String body, StableIdentityLedger.Intent intent) {
        String field = intent.operation() == WorkloadOperation.TRIGGER ? "triggerOrderId" : "orderId";
        return numberField(body, field).or(() -> firstArrayNumber(body, "prospectiveOrderIds")).orElse("");
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
                    + ",\"ratePerSecond\":" + config.ratePerSecond()
                    + ",\"durationSeconds\":" + config.duration().toSeconds()
                    + ",\"users\":" + config.users().length + ",\"symbols\":" + config.symbols().length
                    + ",\"scheduled\":" + summary.scheduled() + ",\"completed\":" + summary.completed()
                    + ",\"outstanding\":" + summary.outstanding() + ",\"deliberately_aborted\":"
                    + summary.deliberatelyAborted() + ",\"maxObservedInFlight\":" + summary.maxObservedInFlight()
                    + ",\"accounting\":\"PASS\",\"classifications\":"
                    + classificationsJson(summary.classifications()) + "}\n";
            Files.writeString(config.outputDirectory().resolve("accounting.json"), json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write workload artifacts", exception);
        }
    }

    private static String classificationsJson(Map<HttpOutcome, Long> classifications) {
        StringBuilder json = new StringBuilder("{");
        for (HttpOutcome outcome : HttpOutcome.values()) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(outcome).append("\":").append(classifications.getOrDefault(outcome, 0L));
        }
        return json.append('}').toString();
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

    private boolean requiresPrerequisite(WorkloadOperation operation, long sequence) {
        return operation == WorkloadOperation.CANCEL || operation == WorkloadOperation.AMEND
                || operation == WorkloadOperation.TRIGGER || operation == WorkloadOperation.TRIGGER_CANCEL
                || operation == WorkloadOperation.MARKET_IOC_CLOSE && isPositionCloseIntent(sequence);
    }

    private boolean isPositionCloseIntent(long sequence) {
        int bucket = (int) Math.floorMod(sequence - 1 + config.seed(), 100L);
        int marketStart = config.traffic().get(WorkloadOperation.PLACE)
                + config.traffic().get(WorkloadOperation.CANCEL)
                + config.traffic().get(WorkloadOperation.AMEND);
        int marketCount = config.traffic().get(WorkloadOperation.MARKET_IOC_CLOSE);
        int triggerCount = config.traffic().get(WorkloadOperation.TRIGGER);
        int closeCount = Math.min(marketCount / 2, Math.max(0, (marketCount - triggerCount) / 2));
        int marketOffset = bucket - marketStart;
        return closeCount > 0 && marketOffset >= 0 && marketOffset < marketCount
                && (marketOffset + 1) * closeCount / marketCount
                > marketOffset * closeCount / marketCount;
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

    private static Optional<String> firstArrayNumber(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name)
                + "\\\"\\s*:\\s*\\[\\s*([0-9]+)").matcher(json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> nestedStringField(String json, String objectName, String fieldName) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(objectName)
                + "\\\"\\s*:\\s*\\{[^}]*\\\"" + Pattern.quote(fieldName)
                + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static boolean isTerminalWrapper(String json) {
        return stringField(json, "outcome").map("TERMINAL"::equals).orElse(false);
    }

    private static boolean isPendingCommandReceipt(String json) {
        boolean hasCommandReference = stringField(json, "commandId").isPresent()
                || stringField(json, "commandResultUrl").isPresent();
        String state = stringField(json, "code").or(() -> stringField(json, "status")).orElse("");
        return hasCommandReference && (state.equals("PENDING") || state.endsWith("_PENDING"));
    }

    private static boolean isDirectControlResult(String json) {
        return stringField(json, "code").isPresent() || stringField(json, "status").isPresent()
                || json.contains("\"active\"");
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
                pools.replayPrerequisiteConsumption(snapshot.intent());
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

    private final class PrerequisitePools {
        private final Deque<Resource> orders = new ArrayDeque<>();
        private final Deque<Resource> triggers = new ArrayDeque<>();
        private final Deque<Resource> positions = new ArrayDeque<>();
        private final Map<String, Resource> reservedTriggers = new HashMap<>();
        private final Map<String, String> triggerPositions = new HashMap<>();

        synchronized Optional<Resource> reserve(WorkloadOperation operation, long sequence) {
            if (!requiresPrerequisite(operation, sequence)) return Optional.empty();
            Deque<Resource> source = switch (operation) {
                case TRIGGER, MARKET_IOC_CLOSE -> positions;
                case TRIGGER_CANCEL -> triggers;
                default -> orders;
            };
            Resource resource = operation == WorkloadOperation.AMEND ? source.peekFirst() : source.pollFirst();
            if (resource != null && operation == WorkloadOperation.TRIGGER_CANCEL) {
                reservedTriggers.put(resource.identity(), resource);
            }
            return Optional.ofNullable(resource);
        }

        synchronized void replayPrerequisiteConsumption(StableIdentityLedger.Intent intent) {
            if (intent.targetIdentity().isBlank()) return;
            switch (intent.operation()) {
                case CANCEL -> removeByIdentity(orders, intent.targetIdentity());
                case MARKET_IOC_CLOSE -> {
                    if (isPositionCloseIntent(intent.sequence())) {
                        removeByIdentity(positions, intent.targetIdentity());
                    }
                }
                case TRIGGER -> removeByIdentity(positions, intent.targetIdentity());
                case TRIGGER_CANCEL -> {
                    Resource trigger = removeByIdentity(triggers, intent.targetIdentity());
                    if (trigger != null) reservedTriggers.put(trigger.identity(), trigger);
                }
                default -> {
                }
            }
        }

        synchronized void complete(StableIdentityLedger.Intent intent, String resourceIdentity) {
            if (intent.operation() == WorkloadOperation.PLACE && !resourceIdentity.isBlank()) {
                orders.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
            } else if (intent.operation() == WorkloadOperation.TRIGGER && !resourceIdentity.isBlank()
                    && !intent.targetIdentity().isBlank()) {
                triggers.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
                triggerPositions.put(resourceIdentity, intent.targetIdentity());
            } else if (intent.operation() == WorkloadOperation.MARKET_IOC_CLOSE
                    && !isPositionCloseIntent(intent.sequence()) && !resourceIdentity.isBlank()) {
                positions.addLast(new Resource(intent.userId(), intent.symbol(), resourceIdentity));
            } else if (intent.operation() == WorkloadOperation.TRIGGER_CANCEL) {
                Resource trigger = reservedTriggers.remove(intent.targetIdentity());
                String positionIdentity = triggerPositions.remove(intent.targetIdentity());
                if (trigger != null && positionIdentity != null) {
                    positions.addLast(new Resource(trigger.userId(), trigger.symbol(), positionIdentity));
                }
            }
        }

        synchronized void release(StableIdentityLedger.Intent intent) {
            if (intent.targetIdentity().isBlank()) return;
            Resource resource = new Resource(intent.userId(), intent.symbol(), intent.targetIdentity());
            if (intent.operation() == WorkloadOperation.TRIGGER_CANCEL) {
                Resource trigger = reservedTriggers.remove(intent.targetIdentity());
                if (trigger != null) triggers.addFirst(trigger);
            } else if (intent.operation() == WorkloadOperation.TRIGGER) positions.addFirst(resource);
            else if (intent.operation() == WorkloadOperation.CANCEL) orders.addFirst(resource);
            else if (intent.operation() == WorkloadOperation.MARKET_IOC_CLOSE) positions.addFirst(resource);
        }

        private static Resource removeByIdentity(Deque<Resource> source, String identity) {
            for (Resource resource : source) {
                if (resource.identity().equals(identity)) {
                    source.remove(resource);
                    return resource;
                }
            }
            return null;
        }
    }

    private static final class EpochNanoClock {
        private static final long OFFSET = Math.multiplyExact(System.currentTimeMillis(), 1_000_000L) - System.nanoTime();

        static long now() {
            return Math.addExact(OFFSET, System.nanoTime());
        }
    }
}
