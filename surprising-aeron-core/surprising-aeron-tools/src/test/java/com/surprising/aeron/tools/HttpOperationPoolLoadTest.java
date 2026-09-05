package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOperationPoolLoadTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancelUsesTheOwnerOfTheReservedOpenOrder() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, request.path().endsWith("/cancel")
                    ? "{\"code\":\"APPLIED\"}"
                    : "{\"code\":\"APPLIED\",\"orderId\":9001}");
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("order-owner"),
                    traffic(WorkloadOperation.PLACE, 1, WorkloadOperation.CANCEL, 99), 0L, 10L,
                    Duration.ofMillis(200), 4);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.completed()).isEqualTo(2L);
            assertThat(summary.deliberatelyAborted()).isZero();
            assertThat(requests).extracting(Request::path).containsExactly(
                    "/api/v1/trading/orders", "/api/v1/trading/orders/cancel");
            assertThat(requests.get(1).body()).contains("\"userId\":1002", "\"orderId\":9001");
        }
    }

    @Test
    void marketOpenCreatesAPositionThatTheNextCloseConsumes() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, request.path().endsWith("/close-position")
                    ? "{\"status\":\"FILLED\",\"orderId\":9102}"
                    : "{\"code\":\"APPLIED\",\"orderId\":9101}");
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("position-owner"),
                    traffic(WorkloadOperation.MARKET_IOC_CLOSE, 100), 0L, 4L,
                    Duration.ofMillis(500), 2);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.completed()).isEqualTo(2L);
            assertThat(summary.deliberatelyAborted()).isZero();
            assertThat(requests).extracting(Request::path).containsExactly(
                    "/api/v1/trading/orders", "/api/v1/trading/orders/close-position");
            assertThat(requests.getFirst().body()).contains("\"reduceOnly\":false");
            assertThat(requests.getLast().body()).contains("\"userId\":1002", "\"symbol\":\"ETH-USDT\"");
        }
    }

    @Test
    void triggerWithoutAnOpenPositionIsAbortedWithoutSendingASyntheticRequest() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, "{\"triggerOrderId\":9301,\"status\":\"PENDING\"}");
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("trigger-pending"),
                    traffic(WorkloadOperation.TRIGGER, 100), 0L, 1L, Duration.ofSeconds(1), 1);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.completed()).isZero();
            assertThat(summary.deliberatelyAborted()).isEqualTo(1L);
            assertThat(requests).isEmpty();
        }
    }

    @Test
    void failedTriggerRestoresTheReservedOpenPositionForTheNextTrigger() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, requests.size() == 1 ? 400 : 200,
                    requests.size() == 1 ? "{\"code\":\"REJECTED\"}"
                            : "{\"triggerOrderId\":9302,\"status\":\"PENDING\"}");
        })) {
            Path output = temporaryDirectory.resolve("failed-trigger-restores-position");
            HttpWorkloadConfig config = config(server.uri(), output, traffic(WorkloadOperation.TRIGGER, 100),
                    0L, 10L, Duration.ofMillis(300), 1);
            seedTerminal(config, 1L, WorkloadOperation.MARKET_IOC_CLOSE, "APPLIED", "", "9101");

            new HttpOpenLoopWorkload(config).run();

            assertThat(requests).extracting(Request::path).containsExactly(
                    "/api/v1/trading/trigger-orders", "/api/v1/trading/trigger-orders");
            assertThat(requests.getLast().body()).contains("\"userId\":1001", "\"symbol\":\"BTC-USDT\"",
                    "\"triggerPriceTicks\":700000");
        }
    }

    @Test
    void successfulTriggerCancelRestoresPositionCapacityForTheNextTrigger() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, request.path().endsWith("/cancel")
                    ? "{\"code\":\"APPLIED\"}"
                    : "{\"triggerOrderId\":9302,\"status\":\"PENDING\"}");
        })) {
            Path output = temporaryDirectory.resolve("trigger-cancel-restores-position");
            HttpWorkloadConfig config = config(server.uri(), output,
                    traffic(WorkloadOperation.TRIGGER, 99, WorkloadOperation.TRIGGER_CANCEL, 1),
                    97L, 10L, Duration.ofMillis(400), 1);
            seedTerminal(config, 1L, WorkloadOperation.MARKET_IOC_CLOSE, "APPLIED", "", "9101");
            seedTerminal(config, 2L, WorkloadOperation.TRIGGER, "PENDING", "9101", "9301");

            new HttpOpenLoopWorkload(config).run();

            assertThat(requests).extracting(Request::path).containsExactly(
                    "/api/v1/trading/trigger-orders/cancel", "/api/v1/trading/trigger-orders");
            assertThat(requests.getLast().body()).contains("\"userId\":1001", "\"symbol\":\"BTC-USDT\"");
        }
    }

    @Test
    void restartReplayDoesNotDuplicatePositionCapacityAfterTriggerCancellation() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, "{\"triggerOrderId\":9302,\"status\":\"PENDING\"}");
        })) {
            Path output = temporaryDirectory.resolve("restart-trigger-cancel-capacity");
            HttpWorkloadConfig config = config(server.uri(), output,
                    traffic(WorkloadOperation.TRIGGER, 99, WorkloadOperation.TRIGGER_CANCEL, 1),
                    96L, 10L, Duration.ofMillis(600), 1);
            seedTerminal(config, 1L, WorkloadOperation.MARKET_IOC_CLOSE, "APPLIED", "", "9101");
            seedTerminal(config, 2L, WorkloadOperation.TRIGGER, "PENDING", "9101", "9301");
            seedTerminal(config, 3L, WorkloadOperation.TRIGGER_CANCEL, "APPLIED", "9301", "");

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(requests).extracting(Request::path).containsExactly("/api/v1/trading/trigger-orders");
            assertThat(summary.deliberatelyAborted()).isEqualTo(2L);
        }
    }

    @Test
    void triggerWithoutAServiceIssuedIdDoesNotCreateACancelRequest() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            respond(exchange, 200, "{\"status\":\"PENDING\"}");
        })) {
            Path output = temporaryDirectory.resolve("no-synthetic-trigger-id");
            HttpWorkloadConfig config = config(server.uri(), output,
                    traffic(WorkloadOperation.TRIGGER, 2, WorkloadOperation.TRIGGER_CANCEL, 98),
                    0L, 10L, Duration.ofMillis(300), 1);
            seedTerminal(config, 1L, WorkloadOperation.MARKET_IOC_CLOSE, "APPLIED", "", "9101");

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.deliberatelyAborted()).isEqualTo(1L);
            assertThat(requests).extracting(Request::path).containsExactly("/api/v1/trading/trigger-orders");
        }
    }

    @Test
    void directControlResponsesAreAuthoritativeTerminalResults() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            Request request = capture(exchange);
            requests.add(request);
            String path = request.path();
            if (path.endsWith("/algo")) {
                respond(exchange, 200, "{\"status\":\"PENDING\",\"algoOrderId\":9201}");
            } else if (path.endsWith("/cancel-all-after")) {
                respond(exchange, 200, "{\"active\":true,\"countdownMs\":60000}");
            } else {
                respond(exchange, 200, "{\"code\":\"APPLIED\",\"prospectiveOrderIds\":[9203]}");
            }
        })) {
            HttpWorkloadConfig config = config(server.uri(), temporaryDirectory.resolve("control-results"),
                    traffic(WorkloadOperation.BATCH_ALGO_CONTROL, 100), 0L, 20L,
                    Duration.ofMillis(150), 8);

            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();

            assertThat(summary.completed()).isEqualTo(3L);
            assertThat(summary.outstanding()).isZero();
            assertThat(summary.classifications().get(HttpOutcome.ORACLE_MISMATCH)).isZero();
            assertThat(requests).extracting(Request::path).noneMatch(path -> path.contains("/commands/"));
            assertThat(requests.stream().filter(request -> request.path().endsWith("/algo")).map(Request::body))
                    .allSatisfy(body -> assertThat(body).contains("\"postOnly\":false", "\"timeInForce\":\"IOC\""));
        }
    }

    private void seedTerminal(HttpWorkloadConfig config, long sequence, WorkloadOperation operation,
                              String finalState, String targetIdentity, String resourceIdentity) {
        try (StableIdentityLedger ledger = StableIdentityLedger.open(config.outputDirectory(), config.runId(),
                config.seed(), config.fingerprint())) {
            long now = Math.multiplyExact(System.currentTimeMillis(), 1_000_000L);
            StableIdentityLedger.Intent intent = ledger.intent(sequence, operation, 1001L, "BTC-USDT", finalState,
                    targetIdentity);
            ledger.scheduled(intent, now);
            ledger.sent(sequence, now);
            ledger.http(sequence, now, 200, HttpOutcome.SUCCESS_2XX);
            ledger.finished(sequence, now, finalState, resourceIdentity);
        }
    }

    private HttpWorkloadConfig config(URI baseUri, Path output, Map<WorkloadOperation, Integer> traffic,
                                      long seed, long rate, Duration duration, int maxInFlight) {
        return new HttpWorkloadConfig(baseUri, output, output.getFileName().toString(), seed, rate, duration,
                maxInFlight, Duration.ofSeconds(2), Duration.ofMillis(2), 3, 700_000L, 700_000L,
                new long[] {1001L, 1002L}, new String[] {"BTC-USDT", "ETH-USDT"},
                TrafficSkew.UNIFORM, traffic);
    }

    private static Map<WorkloadOperation, Integer> traffic(Object... weights) {
        EnumMap<WorkloadOperation, Integer> result = new EnumMap<>(WorkloadOperation.class);
        for (WorkloadOperation operation : WorkloadOperation.values()) result.put(operation, 0);
        for (int index = 0; index < weights.length; index += 2) {
            result.put((WorkloadOperation) weights[index], (Integer) weights[index + 1]);
        }
        return result;
    }

    private static Request capture(HttpExchange exchange) throws IOException {
        return new Request(exchange.getRequestURI().getPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static boolean health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/actuator/health")) return false;
        respond(exchange, 200, "{\"status\":\"UP\"}");
        return true;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Request(String path, String body) {
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private Loopback(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler::handle);
            server.setExecutor(executor);
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
