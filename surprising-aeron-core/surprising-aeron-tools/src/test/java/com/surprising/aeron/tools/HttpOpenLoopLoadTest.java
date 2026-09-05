package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOpenLoopLoadTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loopbackVerdictCannotReportMisleadingSuccess() {
        var classifications = new java.util.EnumMap<HttpOutcome, Long>(HttpOutcome.class);
        for (HttpOutcome outcome : HttpOutcome.values()) classifications.put(outcome, 0L);
        assertThatThrownBy(() -> HttpWorkloadLoopbackMain.requireQa(
                new HttpOpenLoopWorkload.Summary(1_000, 980, 0, 20, 4, classifications)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99%");
        HttpWorkloadLoopbackMain.requireQa(
                new HttpOpenLoopWorkload.Summary(1_000, 990, 0, 10, 4, classifications));
    }

    @Test
    void defaultTrafficMaintainsValidPrerequisitesForACompleteCycle() throws Exception {
        AtomicLong resources = new AtomicLong(10_000);
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (path.contains("trigger-orders/cancel")) {
                respond(exchange, 200, "{\"status\":\"CANCELED\"}");
            } else if (path.endsWith("trigger-orders")) {
                respond(exchange, 200, "{\"status\":\"PENDING\",\"triggerOrderId\":"
                        + resources.incrementAndGet() + "}");
            } else {
                respond(exchange, 200, "{\"code\":\"APPLIED\",\"orderId\":"
                        + resources.incrementAndGet() + "}");
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("prerequisites"),
                    "prerequisite-run", 100, Duration.ofSeconds(1), 128, Duration.ofSeconds(1), 4);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.scheduled()).isEqualTo(100);
            var abortedEvents = Files.readAllLines(config.outputDirectory().resolve("events.jsonl")).stream()
                    .filter(line -> line.contains("\"event\":\"ABORTED\"")).toList();
            assertThat(summary.deliberatelyAborted()).as("aborted events: %s", abortedEvents).isZero();
            assertThat(summary.completed()).isEqualTo(summary.scheduled());
        }
    }

    @Test
    void resolvesAcceptedResponsesToTerminalAndWritesAccountingArtifacts() throws Exception {
        AtomicInteger resultPolls = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/commands/")) {
                int poll = resultPolls.incrementAndGet();
                respond(exchange, poll % 2 == 1 ? 202 : 200,
                        poll % 2 == 1 ? receipt("MATCHING_PENDING") : receipt("APPLIED"));
            } else {
                respond(exchange, 202, receipt("MATCHING_PENDING"));
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("accepted"),
                    "accepted-run", 40, Duration.ofMillis(500), 32, Duration.ofMillis(200), 4);
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.scheduled()).isEqualTo(20);
            assertThat(summary.completed() + summary.outstanding() + summary.deliberatelyAborted())
                    .isEqualTo(summary.scheduled());
            assertThat(summary.classifications().get(HttpOutcome.ACCEPTED_202)).isPositive();
            assertThat(summary.classifications().get(HttpOutcome.SUCCESS_2XX)).isPositive();
            assertThat(summary.outstanding()).isZero();
            assertThat(Files.size(config.outputDirectory().resolve("http-corrected.hdr"))).isPositive();
            assertThat(Files.size(config.outputDirectory().resolve("finalization-corrected.hdr"))).isPositive();
            assertThat(Files.size(config.outputDirectory().resolve("events.jsonl"))).isPositive();
        }
    }

    @Test
    void pollsMisleadingOkReceiptUntilTheAuthoritativeCommandResultIsTerminal() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            if (exchange.getRequestURI().getPath().contains("/commands/")) {
                polls.incrementAndGet();
                respond(exchange, 200, receipt("APPLIED"));
            } else {
                respond(exchange, 200, "{\"commandId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"code\":\"MATCHING_PENDING\",\"commandResultUrl\":"
                        + "\"/api/v1/trading/orders/commands/00000000-0000-0000-0000-000000000001\"}");
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("misleading-ok"),
                    "misleading-ok-run", 1, Duration.ofSeconds(1), 1, Duration.ofMillis(200), 2);
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(polls).hasValue(1);
            assertThat(summary.completed()).isEqualTo(1);
            assertThat(summary.classifications().get(HttpOutcome.ORACLE_MISMATCH)).isZero();
        }
    }

    @Test
    void terminalWrapperUsesNestedOrderStateWithoutPolling() throws Exception {
        AtomicInteger resultPolls = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            if (exchange.getRequestURI().getPath().contains("/commands/")) {
                resultPolls.incrementAndGet();
                respond(exchange, 500, "{\"code\":\"UNEXPECTED\"}");
            } else {
                respond(exchange, 200, "{\"outcome\":\"TERMINAL\",\"code\":\"NONE\",\"result\":{"
                        + "\"resultType\":\"order\",\"status\":\"FILLED\",\"orderId\":9001}}");
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("terminal-wrapper"),
                    "terminal-wrapper-run", 1, Duration.ofSeconds(1), 1, Duration.ofMillis(200), 4);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(resultPolls).hasValue(0);
            assertThat(summary.completed()).isEqualTo(1);
            assertThat(summary.classifications().get(HttpOutcome.ORACLE_MISMATCH)).isZero();
        }
    }

    @Test
    void aBareOkBodyIsNotSilentlyAcceptedAsTheExpectedFinalState() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            respond(exchange, 200, "{\"message\":\"queued\"}");
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("bare-ok"),
                    "bare-ok-run", 1, Duration.ofSeconds(1), 1, Duration.ofMillis(200), 1);
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.completed()).isZero();
            assertThat(summary.outstanding()).isEqualTo(1);
            assertThat(summary.scheduled()).isEqualTo(summary.completed() + summary.outstanding()
                    + summary.deliberatelyAborted());
        }
    }

    @Test
    void classifiesRateLimitTimeoutAndServerFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            int current = requests.incrementAndGet();
            if (current % 3 == 0) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, receipt("APPLIED"));
            } else if (current % 2 == 0) {
                respond(exchange, 429, receipt("CLIENT_BACKPRESSURED"));
            } else {
                respond(exchange, 500, receipt("UNKNOWN"));
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("failures"),
                    "failure-run", 100, Duration.ofSeconds(1), 16, Duration.ofMillis(50), 1);
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.classifications().get(HttpOutcome.RATE_LIMITED_429)).isPositive();
            assertThat(summary.classifications().get(HttpOutcome.SERVER_5XX)).isPositive();
            assertThat(summary.classifications().get(HttpOutcome.TIMEOUT)).isPositive();
            assertThat(summary.completed() + summary.outstanding() + summary.deliberatelyAborted())
                    .isEqualTo(summary.scheduled());
            assertThat(summary.maxObservedInFlight()).isLessThanOrEqualTo(16);
        }
    }

    @Test
    void accountsForInFlightCancellationWithoutExceedingBound() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            blocked.countDown();
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, receipt("APPLIED"));
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("cancelled"),
                    "cancelled-run", 20, Duration.ofSeconds(1), 4, Duration.ofSeconds(1), 1);
            HttpOpenLoopWorkload workload = new HttpOpenLoopWorkload(config);
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(workload::run);
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
            workload.cancelOutstanding("test interruption");
            HttpOpenLoopWorkload.Summary summary = future.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(summary.deliberatelyAborted()).isPositive();
            assertThat(summary.completed() + summary.outstanding() + summary.deliberatelyAborted())
                    .isEqualTo(summary.scheduled());
            assertThat(summary.maxObservedInFlight()).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void resumesOutstandingIntentWithoutChangingOrDuplicatingIdentity() throws Exception {
        Path output = temporaryDirectory.resolve("resume");
        Set<String> receivedIntentIds = ConcurrentHashMap.newKeySet();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            receivedIntentIds.add(exchange.getRequestHeaders().getFirst("X-Workload-Intent-Id"));
            respond(exchange, 200, receipt("APPLIED"));
        })) {
            HttpWorkloadConfig config = config(server.uri(), output, "restart-run",
                    1, Duration.ofSeconds(1), 2, Duration.ofMillis(200), 2);
            StableIdentityLedger.Intent original;
            try (StableIdentityLedger ledger = StableIdentityLedger.open(
                    output, "restart-run", 41L, config.fingerprint())) {
                original = ledger.intent(1L, WorkloadOperation.PLACE, 1_001L, "BTC-USDT", "APPLIED");
                ledger.scheduled(original, System.nanoTime());
                ledger.sent(original.sequence(), System.nanoTime());
            }
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();
            assertThat(summary.scheduled()).isEqualTo(1);
            assertThat(summary.completed()).isEqualTo(1);
            assertThat(receivedIntentIds).containsExactly(original.intentId().toString());

            Set<String> scheduledIds = new HashSet<>();
            for (String line : Files.readAllLines(output.resolve("events.jsonl"))) {
                if (line.contains("\"event\":\"SCHEDULED\"")) {
                    scheduledIds.add(extract(line, "intentId"));
                }
            }
            assertThat(scheduledIds).containsExactly(original.intentId().toString());
        }
    }

    private static HttpWorkloadConfig config(URI baseUri, Path output, String runId, long rate,
                                             Duration duration, int maxInFlight, Duration timeout, int maxPolls) {
        return new HttpWorkloadConfig(baseUri, output, runId, 41L, rate, duration, maxInFlight,
                timeout, Duration.ofMillis(5), maxPolls, 700_000L, 700_000L, new long[] {1_001L, 1_002L},
                new String[] {"BTC-USDT", "ETH-USDT"}, TrafficSkew.UNIFORM,
                HttpWorkloadConfig.defaultTraffic());
    }

    private static String receipt(String code) {
        return "{\"commandId\":\"00000000-0000-0000-0000-000000000001\",\"code\":\"" + code + "\"}";
    }

    private static String extract(String json, String field) {
        String prefix = "\"" + field + "\":\"";
        int start = json.indexOf(prefix) + prefix.length();
        return json.substring(start, json.indexOf('"', start));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/actuator/health")) return false;
        respond(exchange, 200, "{\"status\":\"UP\"}");
        return true;
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;

        private Loopback(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
