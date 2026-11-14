package com.surprising.websocket.provider.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayLimitProperties;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import com.surprising.websocket.provider.service.SubscriptionRegistry;
import com.surprising.websocket.provider.service.WebSocketAuthenticator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NettyWebSocketServerTest {

    @Test
    void nettyWebSocketServerHandlesHandshakePingAndZeroCopyFanout() throws Exception {
        var json = new ObjectMapper();
        var wsProps = new WebSocketProperties();
        wsProps.getNetty().setPort(0);
        wsProps.getSecurity().setAllowedOrigins(List.of("*"));
        var gatewayProps = new GatewayProperties();
        var limitProps = new GatewayLimitProperties();
        limitProps.setEnabled(false);
        var limits = new GatewayRequestLimits(null, limitProps, new SimpleMeterRegistry());
        var registry = new SubscriptionRegistry(json, wsProps);
        var handler = new ClientWebSocketHandler(
                json,
                registry,
                wsProps,
                new WebSocketAuthenticator(mock(AuthService.class), mock(GatewayApiKeyService.class)),
                mock(GatewayProductServices.class),
                limits);

        var server = new NettyWebSocketServer(handler, wsProps, gatewayProps);
        server.start();
        try {
            int port = server.getPort();
            assertThat(port).isPositive();

            var received = new CopyOnWriteArrayList<String>();
            var firstReady = new CompletableFuture<Void>();
            var fanoutReady = new CompletableFuture<Void>();

            try (var http = HttpClient.newHttpClient()) {
                WebSocket ws = http.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + WebSocketApiPaths.WS_V1), new WebSocket.Listener() {
                            private final StringBuilder buf = new StringBuilder();

                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                buf.append(data);
                                if (last) {
                                    String msg = buf.toString();
                                    buf.setLength(0);
                                    received.add(msg);
                                    if (msg.contains("pong")) {
                                        firstReady.complete(null);
                                    }
                                    if (msg.contains("netty-fanout-ok")) {
                                        fanoutReady.complete(null);
                                    }
                                }
                                webSocket.request(1);
                                return null;
                            }
                        })
                        .get(5, TimeUnit.SECONDS);

                ws.sendText(json.writeValueAsString(Map.of("op", "ping", "id", "n1")), true).get(5, TimeUnit.SECONDS);
                firstReady.get(5, TimeUnit.SECONDS);

                ws.sendText(json.writeValueAsString(Map.of(
                        "op", "subscribe",
                        "id", "s1",
                        "channel", "trades",
                        "instrumentId", "1001",
                        "productLine", "SPOT")), true).get(5, TimeUnit.SECONDS);

                var topic = new SubscriptionTopic(WsChannel.TRADES, "1001", null, null, ProductLine.SPOT);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (registry.subscriberCount(topic) == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(registry.subscriberCount(topic)).isEqualTo(1);

                registry.publish(topic, Map.of("msg", "netty-fanout-ok"), Instant.now());
                fanoutReady.get(5, TimeUnit.SECONDS);
                assertThat(received).anyMatch(s -> s.contains("netty-fanout-ok"));

                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            }
        } finally {
            server.stop();
        }
    }
}
