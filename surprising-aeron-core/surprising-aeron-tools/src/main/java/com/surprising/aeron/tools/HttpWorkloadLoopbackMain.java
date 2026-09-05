package com.surprising.aeron.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpWorkloadLoopbackMain {

    private HttpWorkloadLoopbackMain() {
    }

    public static void main(String[] arguments) throws IOException {
        Properties properties = arguments(arguments);
        AtomicLong requests = new AtomicLong();
        AtomicLong resources = new AtomicLong(10_000L);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1_024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> handle(exchange, requests, resources));
        server.start();
        properties.setProperty("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(HttpWorkloadConfig.from(properties)).run();
            requireQa(summary);
            System.out.printf("loopbackHttpQa=PASS requests=%d scheduled=%d completed=%d outstanding=%d "
                            + "deliberately_aborted=%d maxInFlight=%d classifications=%s%n",
                    requests.get(), summary.scheduled(), summary.completed(), summary.outstanding(),
                    summary.deliberatelyAborted(), summary.maxObservedInFlight(), summary.classifications());
        } finally {
            server.stop(0);
        }
    }

    static void requireQa(HttpOpenLoopWorkload.Summary summary) {
        if (summary.scheduled() == 0 || summary.completed() * 100L < summary.scheduled() * 99L) {
            throw new IllegalStateException("loopback HTTP QA completed below 99% of scheduled arrivals: " + summary);
        }
        if (summary.outstanding() != 0
                || summary.classifications().getOrDefault(HttpOutcome.TIMEOUT, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.TRANSPORT_ERROR, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.SERVER_5XX, 0L) != 0
                || summary.classifications().getOrDefault(HttpOutcome.ORACLE_MISMATCH, 0L) != 0) {
            throw new IllegalStateException("loopback HTTP QA has unresolved or technical failures: " + summary);
        }
    }

    private static Properties arguments(String[] arguments) {
        Properties properties = new Properties();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (separator <= 0 || separator == argument.length() - 1) {
                throw new IllegalArgumentException("arguments must use name=value: " + argument);
            }
            properties.setProperty(argument.substring(0, separator), argument.substring(separator + 1));
        }
        return properties;
    }

    private static void handle(HttpExchange exchange, AtomicLong requests, AtomicLong resources) throws IOException {
        exchange.getRequestBody().readAllBytes();
        long request = requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String body;
        int status;
        if (path.contains("/commands/")) {
            status = 200;
            body = "{\"code\":\"APPLIED\",\"orderId\":" + resources.incrementAndGet() + "}";
        } else if (path.contains("trigger-orders/cancel")) {
            status = 200;
            body = "{\"status\":\"CANCELED\",\"triggerOrderId\":" + resources.incrementAndGet() + "}";
        } else if (path.endsWith("trigger-orders")) {
            status = 200;
            body = "{\"status\":\"PENDING\",\"triggerOrderId\":" + resources.incrementAndGet() + "}";
        } else if (request % 10 == 0) {
            status = 202;
            UUID commandId = UUID.nameUUIDFromBytes(("loopback:" + request).getBytes(StandardCharsets.UTF_8));
            body = "{\"commandId\":\"" + commandId + "\",\"code\":\"MATCHING_PENDING\","
                    + "\"commandResultUrl\":\"/api/v1/trading/orders/commands/" + commandId + "\"}";
        } else {
            status = 200;
            body = "{\"code\":\"APPLIED\",\"orderId\":" + resources.incrementAndGet() + "}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
