package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import feign.Request;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class MarketMakerFeignConnectionTest {
    @Test
    void reusesOneConnectionAndPreservesRequestHeadersAndBodies() throws Exception {
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            ports.add(exchange.getRemoteAddress().getPort());
            assertThat(exchange.getRequestHeaders().getFirst("X-Product-Line")).isEqualTo("LINEAR_PERPETUAL");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("order");
            byte[] body = "confirmed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var config = new MarketMakerFeignConfiguration();
        try (var http = config.marketMakerHttpClient()) {
            var client = config.marketMakerFeignClient(http);
            for (int i = 0; i < 20; i++) {
                var request = Request.create(Request.HttpMethod.POST,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/orders",
                        Map.of("X-Product-Line", java.util.List.of("LINEAR_PERPETUAL")),
                        "order".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, null);
                try (var response = client.execute(request, config.marketMakerRequestOptions())) {
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("confirmed");
                }
            }
            assertThat(ports).hasSize(1);
        } finally { server.stop(0); }
    }
    @Test
    void responseBodyReadsKeepTheirTimeoutAfterHeadersArrive() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (var output = exchange.getResponseBody()) {
                output.write('a'); output.flush();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var config = new MarketMakerFeignConfiguration();
        try (var http = config.marketMakerHttpClient()) {
            var request = Request.create(Request.HttpMethod.GET,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/slow", Map.of(), null,
                    StandardCharsets.UTF_8, null);
            var options = new Request.Options(java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(150), true);
            try (var response = config.marketMakerFeignClient(http).execute(request, options)) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> response.body().asInputStream().readAllBytes())
                        .isInstanceOf(java.net.SocketTimeoutException.class);
            }
        } finally { release.countDown(); server.stop(0); }
    }

}
