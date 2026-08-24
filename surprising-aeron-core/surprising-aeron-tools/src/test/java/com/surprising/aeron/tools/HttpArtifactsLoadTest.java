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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpArtifactsLoadTest {

    private static final Pattern TOTAL_COUNT = Pattern.compile("Total count\\s*=\\s*(\\d+)");

    @TempDir
    Path temporaryDirectory;

    @Test
    void openLoopArrivalTimestampsDoNotWaitForSlowCompletions() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"code\":\"APPLIED\",\"orderId\":7001}");
        })) {
            Path output = temporaryDirectory.resolve("open-loop");
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(
                    config(server.uri(), output, 100L, Duration.ofMillis(100), 2)).run();

            List<Long> intended = Files.readAllLines(output.resolve("events.jsonl")).stream()
                    .filter(line -> line.contains("\"event\":\"SCHEDULED\""))
                    .map(line -> number(line, "intendedNanos"))
                    .toList();
            assertThat(summary.scheduled()).isEqualTo(10L);
            assertThat(summary.maxObservedInFlight()).isLessThanOrEqualTo(2);
            assertThat(intended).hasSize(10);
            assertThat(intended.getLast() - intended.getFirst()).isEqualTo(Duration.ofMillis(90).toNanos());
        }
    }

    @Test
    void artifactsExposeCorrectedSamplesStructuredClassificationsAndAllTimestamps() throws Exception {
        try (Loopback server = new Loopback(exchange -> {
            if (health(exchange)) return;
            try {
                Thread.sleep(60L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"code\":\"APPLIED\",\"orderId\":7002}");
        })) {
            Path output = temporaryDirectory.resolve("artifacts");
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(
                    config(server.uri(), output, 100L, Duration.ofMillis(10), 1)).run();

            String accounting = Files.readString(output.resolve("accounting.json"));
            String histogram = Files.readString(output.resolve("http-corrected.hdr"));
            String events = Files.readString(output.resolve("events.jsonl"));
            assertThat(summary.completed()).isEqualTo(1L);
            assertThat(accounting).contains("\"classifications\":{", "\"SUCCESS_2XX\":1");
            assertThat(correctedCount(histogram)).isGreaterThan(1L);
            assertThat(events).contains("\"intendedNanos\":", "\"sendNanos\":", "\"httpNanos\":",
                    "\"finalNanos\":");
        }
    }

    @Test
    void unhealthyHttpSurfaceFailsClosedBeforeScheduling() throws Exception {
        try (Loopback server = new Loopback(exchange -> respond(exchange, 503, "{\"status\":\"DOWN\"}"))) {
            Path output = temporaryDirectory.resolve("unhealthy");
            HttpWorkloadConfig config = config(server.uri(), output, 1L, Duration.ofSeconds(1), 1);

            assertThatThrownBy(() -> new HttpOpenLoopWorkload(config).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("healthy");
            assertThat(output.resolve("events.jsonl")).doesNotExist();
        }
    }

    @Test
    void httpStatusClassesRemainDistinct() {
        assertThat(HttpOutcome.fromStatus(200)).isEqualTo(HttpOutcome.SUCCESS_2XX);
        assertThat(HttpOutcome.fromStatus(202)).isEqualTo(HttpOutcome.ACCEPTED_202);
        assertThat(HttpOutcome.fromStatus(429)).isEqualTo(HttpOutcome.RATE_LIMITED_429);
        assertThat(HttpOutcome.fromStatus(409)).isEqualTo(HttpOutcome.CLIENT_4XX);
        assertThat(HttpOutcome.fromStatus(503)).isEqualTo(HttpOutcome.SERVER_5XX);
    }

    private static HttpWorkloadConfig config(URI baseUri, Path output, long rate,
                                             Duration duration, int maxInFlight) {
        EnumMap<WorkloadOperation, Integer> traffic = new EnumMap<>(WorkloadOperation.class);
        for (WorkloadOperation operation : WorkloadOperation.values()) traffic.put(operation, 0);
        traffic.put(WorkloadOperation.PLACE, 100);
        return new HttpWorkloadConfig(baseUri, output, output.getFileName().toString(), 1L, rate, duration,
                maxInFlight, Duration.ofSeconds(2), Duration.ZERO, 2, new long[] {1001L},
                new String[] {"BTC-USDT"}, TrafficSkew.UNIFORM, Map.copyOf(traffic));
    }

    private static long correctedCount(String histogram) {
        Matcher matcher = TOTAL_COUNT.matcher(histogram);
        if (!matcher.find()) throw new AssertionError("HDR output has no total count");
        return Long.parseLong(matcher.group(1));
    }

    private static long number(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + field + "\\\":([0-9]+)").matcher(json);
        if (!matcher.find()) throw new AssertionError("missing " + field);
        return Long.parseLong(matcher.group(1));
    }

    private static boolean health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/actuator/health")) return false;
        respond(exchange, 200, "{\"status\":\"UP\"}");
        return true;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
