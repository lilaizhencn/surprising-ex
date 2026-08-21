package com.surprising.aeron.exporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExporterMetricsHttpServer implements AutoCloseable {

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;
    private final ExecutorService executor;

    private ExporterMetricsHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static ExporterMetricsHttpServer start(ExporterMetrics metrics, InetSocketAddress address)
            throws IOException {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(address, "address");
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "exporter-metrics-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/metrics", exchange -> serveMetrics(exchange, metrics));
        server.start();
        return new ExporterMetricsHttpServer(server, executor);
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    private static void serveMetrics(HttpExchange exchange, ExporterMetrics metrics) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/metrics".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = new ExporterMetricsPrometheusAdapter(metrics).scrape().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        exchange.sendResponseHeaders(200, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
