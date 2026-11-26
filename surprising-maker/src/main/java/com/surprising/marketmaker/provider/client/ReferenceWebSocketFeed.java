package com.surprising.marketmaker.provider.client;

import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 外部参考市场 WebSocket 连接池、订阅管理与消息路由。 */
@Slf4j
final class ReferenceWebSocketFeed {

    @FunctionalInterface
    interface PayloadHandler {
        void onPayload(MarketMakerProperties.ReferenceMarket.Source source,
                       String instrumentId,
                       InstrumentResponse instrument,
                       JsonNode root,
                       Instant receivedAt);
    }

    private final MarketMakerProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PayloadHandler payloadHandler;
    private final Map<String, WebSocketState> webSockets = new ConcurrentHashMap<>();

    ReferenceWebSocketFeed(MarketMakerProperties properties,
                           HttpClient httpClient,
                           ObjectMapper objectMapper,
                           PayloadHandler payloadHandler) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.payloadHandler = payloadHandler;
    }

    void reset() {
        for (WebSocketState state : webSockets.values()) {
            state.retired = true;
            if (state.webSocket != null) {
                state.webSocket.abort();
            }
        }
        webSockets.clear();
    }

    void ensureConnections(MarketMakerProperties.ReferenceMarket referenceMarket,
                           ProductLine productLine,
                           String instrumentId,
                           InstrumentResponse instrument,
                           Instant now) {
        if (!referenceMarket.isWebSocketEnabled()) {
            return;
        }
        for (MarketMakerProperties.ReferenceMarket.Source source : referenceMarket.getSources()) {
            if (!RestReferenceMarketProvider.matches(source, productLine, instrumentId)
                    || !RestReferenceMarketProvider.hasText(source.getWebSocketUrl())) {
                continue;
            }
            String resolvedUrl = RestReferenceMarketProvider.template(source.getWebSocketUrl(), source);
            WebSocketState state = webSockets.computeIfAbsent(resolvedUrl, ignored -> new WebSocketState());
            String bindingKey = RestReferenceMarketProvider.liveKey(source, instrumentId);
            String subscribeMessage = RestReferenceMarketProvider.hasText(source.getWebSocketSubscribeMessage())
                    ? RestReferenceMarketProvider.template(source.getWebSocketSubscribeMessage(), source)
                    : null;
            WebSocketBinding binding = new WebSocketBinding(bindingKey, source, instrumentId, instrument, subscribeMessage);
            state.attach(binding);
            WebSocket activeSocket = state.webSocket;
            if (activeSocket != null && !activeSocket.isInputClosed() && !activeSocket.isOutputClosed()) {
                for (String message : subscriptions(subscribeMessage)) {
                    if (state.sentSubscriptions.add(message)) activeSocket.sendText(message, true);
                }
            }
            if (!shouldConnect(state, now)) {
                continue;
            }
            synchronized (state) {
                if (!shouldConnect(state, now)) {
                    continue;
                }
                state.connecting = true;
                state.lastAttempt = now;
            }
            try {
                httpClient.newWebSocketBuilder()
                        .connectTimeout(timeout())
                        .buildAsync(URI.create(resolvedUrl),
                                new ReferenceMarketWebSocketListener(resolvedUrl, state))
                        .whenComplete((webSocket, error) -> {
                            if (error != null) {
                                state.connecting = false;
                                state.webSocket = null;
                                log.debug("Reference market websocket connect failed url={} error={}",
                                        resolvedUrl, error.getMessage());
                            }
                        });
            } catch (RuntimeException ex) {
                state.connecting = false;
                state.webSocket = null;
                log.debug("Reference market websocket connect rejected url={} error={}",
                        resolvedUrl, ex.getMessage());
            }
        }
    }

    private boolean shouldConnect(WebSocketState state, Instant now) {
        WebSocket webSocket = state.webSocket;
        if (state.connecting || (webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed())) {
            return false;
        }
        return state.lastAttempt.plus(reconnectBackoff()).isBefore(now);
    }

    private List<String> subscriptions(String subscribeMessage) {
        if (!RestReferenceMarketProvider.hasText(subscribeMessage)) return List.of();
        List<String> messages = new ArrayList<>();
        messages.add(subscribeMessage);
        String trades = subscribeMessage.replace("\"channel\":\"books\"", "\"channel\":\"trades\"");
        if (!trades.equals(subscribeMessage)) messages.add(trades);
        return messages;
    }

    private Duration timeout() {
        Duration timeout = properties.getReferenceMarket().getRequestTimeout();
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(2) : timeout;
    }

    private Duration reconnectBackoff() {
        Duration reconnectBackoff = properties.getReferenceMarket().getReconnectBackoff();
        return reconnectBackoff == null || reconnectBackoff.isNegative() || reconnectBackoff.isZero()
                ? Duration.ofSeconds(5)
                : reconnectBackoff;
    }

    private record WebSocketBinding(String key, MarketMakerProperties.ReferenceMarket.Source source,
                                    String instrumentId, InstrumentResponse instrument, String subscribeMessage) {
    }

    private static final class WebSocketState {
        private volatile WebSocket webSocket;
        private volatile boolean retired;
        private volatile boolean connecting;
        private volatile Instant lastAttempt = Instant.EPOCH;
        private final Map<String, WebSocketBinding> bindings = new ConcurrentHashMap<>();
        private final Set<String> sentSubscriptions = ConcurrentHashMap.newKeySet();

        void attach(WebSocketBinding binding) {
            bindings.putIfAbsent(binding.key(), binding);
        }
    }

    private final class ReferenceMarketWebSocketListener implements WebSocket.Listener {
        private final String url;
        private final WebSocketState state;
        private final StringBuilder text = new StringBuilder();

        private ReferenceMarketWebSocketListener(String url, WebSocketState state) {
            this.url = url;
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (state.retired) { webSocket.abort(); return; }
            state.sentSubscriptions.clear();
            state.webSocket = webSocket;
            if (state.retired) { webSocket.abort(); return; }
            state.connecting = false;
            for (WebSocketBinding binding : state.bindings.values()) {
                for (String message : subscriptions(binding.subscribeMessage())) {
                    if (state.sentSubscriptions.add(message)) webSocket.sendText(message, true);
                }
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (state.retired) { webSocket.abort(); return CompletableFuture.completedFuture(null); }
            text.append(data);
            if (last) {
                String payload = text.toString();
                text.setLength(0);
                try {
                    JsonNode root = objectMapper.readTree(payload);
                    String externalSymbol = extractExternalSymbol(root);
                    Instant now = Instant.now();
                    if (!state.retired) {
                        for (WebSocketBinding binding : state.bindings.values()) {
                            if (matchesBinding(binding, externalSymbol, root, state.bindings.size())) {
                                payloadHandler.onPayload(binding.source(), binding.instrumentId(),
                                        binding.instrument(), root, now);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Reference market websocket message ignored url={} error={}",
                            url, ex.getMessage());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            state.webSocket = null;
            state.connecting = false;
            state.sentSubscriptions.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            state.webSocket = null;
            state.connecting = false;
            state.sentSubscriptions.clear();
            log.debug("Reference market websocket failed url={} error={}",
                    url, error.getMessage());
        }
    }

    private static String extractExternalSymbol(JsonNode root) {
        if (root == null) return "";
        String okxInst = root.path("arg").path("instId").asString("");
        if (!okxInst.isEmpty()) return okxInst;
        String bybitSymbol = root.path("data").path("s").asString("");
        if (!bybitSymbol.isEmpty()) return bybitSymbol;
        String binanceSymbol = root.path("s").asString("");
        if (!binanceSymbol.isEmpty()) return binanceSymbol;
        String topic = root.path("topic").asString("");
        if (!topic.isEmpty()) {
            int dot = topic.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < topic.length()) return topic.substring(dot + 1);
        }
        return "";
    }

    private static boolean matchesBinding(WebSocketBinding binding, String externalSymbol, JsonNode root, int totalBindings) {
        if (externalSymbol != null && !externalSymbol.isEmpty()) {
            return externalSymbol.equalsIgnoreCase(binding.source().getExternalSymbol());
        }
        String topic = root.path("topic").asString("");
        if (!topic.isEmpty()) {
            return topic.toUpperCase(Locale.ROOT).contains(binding.source().getExternalSymbol().toUpperCase(Locale.ROOT));
        }
        return totalBindings == 1;
    }
}
