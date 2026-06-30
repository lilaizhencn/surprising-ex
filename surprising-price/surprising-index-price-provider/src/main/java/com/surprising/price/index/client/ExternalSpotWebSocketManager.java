package com.surprising.price.index.client;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.service.LatestSourceQuoteStore;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ExternalSpotWebSocketManager {

    private static final Logger log = LoggerFactory.getLogger(ExternalSpotWebSocketManager.class);

    private final IndexPriceProperties properties;
    private final ExternalSpotPriceClient externalSpotPriceClient;
    private final LatestSourceQuoteStore latestSourceQuoteStore;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean running;

    public ExternalSpotWebSocketManager(IndexPriceProperties properties,
                                        ExternalSpotPriceClient externalSpotPriceClient,
                                        LatestSourceQuoteStore latestSourceQuoteStore) {
        this.properties = properties;
        this.externalSpotPriceClient = externalSpotPriceClient;
        this.latestSourceQuoteStore = latestSourceQuoteStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getWebSocket().isEnabled()) {
            log.info("External spot WebSocket collector is disabled");
            return;
        }
        Map<String, List<TrackedSource>> grouped = groupedSources();
        if (grouped.isEmpty()) {
            log.info("No external spot WebSocket sources configured");
            return;
        }

        running = true;
        grouped.forEach((url, sources) -> {
            WsSession session = new WsSession(url, sources);
            sessions.put(url, session);
            connect(session);
        });
        long intervalMs = Math.max(1000L, properties.getWebSocket().getHealthCheckInterval().toMillis());
        scheduler.scheduleAtFixedRate(this::checkIdleSessions, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        sessions.values().forEach(session -> {
            WebSocket webSocket = session.webSocket.get();
            if (webSocket != null) {
                webSocket.abort();
            }
        });
        scheduler.shutdownNow();
    }

    private Map<String, List<TrackedSource>> groupedSources() {
        Map<String, List<TrackedSource>> grouped = new LinkedHashMap<>();
        for (IndexPriceProperties.SymbolConfig symbol : properties.getSymbols()) {
            for (IndexPriceProperties.SourceConfig source : symbol.getSources()) {
                if (source.isEnabled() && source.isWebsocketEnabled() && hasText(source.getWebsocketUrl())) {
                    grouped.computeIfAbsent(source.getWebsocketUrl(), ignored -> new ArrayList<>())
                            .add(new TrackedSource(symbol.getSymbol(), source));
                }
            }
        }
        return grouped;
    }

    private void connect(WsSession session) {
        if (!running || !session.connecting.compareAndSet(false, true)) {
            return;
        }
        httpClient.newWebSocketBuilder()
                .connectTimeout(properties.getHttp().getConnectTimeout())
                .buildAsync(URI.create(session.url), new SourceWebSocketListener(session))
                .whenComplete((webSocket, ex) -> {
                    session.connecting.set(false);
                    if (ex != null) {
                        scheduleReconnect(session, "connect failed: " + ex.getMessage());
                        return;
                    }
                    session.webSocket.set(webSocket);
                    session.reconnectScheduled.set(false);
                    log.info("Connected external spot WebSocket url={} subscriptions={}", session.url, session.sources.size());
                });
    }

    private void scheduleReconnect(WsSession session, String reason) {
        if (!running || !session.reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        WebSocket webSocket = session.webSocket.getAndSet(null);
        if (webSocket != null) {
            webSocket.abort();
        }
        session.connecting.set(false);

        int attempt = session.reconnectAttempts.incrementAndGet();
        long delayMs = reconnectDelayMillis(attempt);
        log.warn("Scheduling external spot WebSocket reconnect url={} attempt={} delayMs={} reason={}",
                session.url, attempt, delayMs, reason);
        scheduler.schedule(() -> {
            session.reconnectScheduled.set(false);
            connect(session);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long reconnectDelayMillis(int attempt) {
        long initial = Math.max(250L, properties.getWebSocket().getReconnectInitialDelay().toMillis());
        long max = Math.max(initial, properties.getWebSocket().getReconnectMaxDelay().toMillis());
        int exponent = Math.min(Math.max(0, attempt - 1), 10);
        long delay = initial;
        for (int i = 0; i < exponent; i++) {
            delay = Math.min(max, delay * 2);
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(1000L, initial) + 1);
        return Math.min(max, delay + jitter);
    }

    private void checkIdleSessions() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        long idleTimeoutMs = Math.max(1000L, properties.getWebSocket().getIdleTimeout().toMillis());
        sessions.values().forEach(session -> {
            if (now - session.lastFrameEpochMillis.get() > idleTimeoutMs) {
                scheduleReconnect(session, "idle timeout");
            }
        });
    }

    private void handlePayload(WsSession session, String payload) {
        Instant receivedAt = Instant.now();
        boolean matched = false;
        for (TrackedSource trackedSource : session.sources) {
            Optional<SourceQuote> quote = externalSpotPriceClient.parseWebSocketPayload(
                    trackedSource.source(), payload, receivedAt);
            if (quote.isPresent() && quote.get().healthy()) {
                latestSourceQuoteStore.put(trackedSource.symbol(), trackedSource.source(), quote.get());
                matched = true;
            }
        }
        if (matched) {
            session.reconnectAttempts.set(0);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private class SourceWebSocketListener implements WebSocket.Listener {

        private final WsSession session;
        private final StringBuilder buffer = new StringBuilder();

        private SourceWebSocketListener(WsSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            session.lastFrameEpochMillis.set(System.currentTimeMillis());
            Set<String> subscribeMessages = new LinkedHashSet<>();
            for (TrackedSource trackedSource : session.sources) {
                String message = trackedSource.source().getWebsocketSubscribeMessage();
                if (hasText(message)) {
                    subscribeMessages.add(message);
                }
            }
            subscribeMessages.forEach(message -> webSocket.sendText(message, true));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            session.lastFrameEpochMillis.set(System.currentTimeMillis());
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                handlePayload(session, payload);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            session.lastFrameEpochMillis.set(System.currentTimeMillis());
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            session.lastFrameEpochMillis.set(System.currentTimeMillis());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            scheduleReconnect(session, "closed status=" + statusCode + " reason=" + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduleReconnect(session, "error: " + error.getMessage());
        }
    }

    private static class WsSession {
        private final String url;
        private final List<TrackedSource> sources;
        private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
        private final AtomicBoolean connecting = new AtomicBoolean();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private final AtomicInteger reconnectAttempts = new AtomicInteger();
        private final AtomicLong lastFrameEpochMillis = new AtomicLong(System.currentTimeMillis());

        private WsSession(String url, List<TrackedSource> sources) {
            this.url = url;
            this.sources = List.copyOf(sources);
        }
    }

    private record TrackedSource(String symbol, IndexPriceProperties.SourceConfig source) {
    }
}
