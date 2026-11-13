package com.surprising.price.index.client;

import static org.mockito.Mockito.*;

import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.service.IndexInstrumentConfigService;
import com.surprising.price.index.service.LatestSourceQuoteStore;
import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExternalSpotWebSocketManagerTest {
    @Test
    @SuppressWarnings("unchecked")
    void reconnectsContinuouslyArrivingOldQuotesButKeepsFreshConnection() throws Exception {
        IndexPriceProperties properties = new IndexPriceProperties();
        LatestSourceQuoteStore store = new LatestSourceQuoteStore();
        ExternalSpotWebSocketManager manager = new ExternalSpotWebSocketManager(properties,
                mock(IndexInstrumentConfigService.class), mock(ExternalSpotPriceClient.class), store);
        WebSocket socket = mock(WebSocket.class);
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName("BINANCE"); source.setSourceSymbol("BTCUSDT");
        Class<?> tracked = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$TrackedSource");
        var trackedConstructor = tracked.getDeclaredConstructors()[0]; trackedConstructor.setAccessible(true);
        Object trackedSource = trackedConstructor.newInstance("BTC-USDT-SWAP", source);
        Class<?> sessionType = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$WsSession");
        var constructor = sessionType.getDeclaredConstructor(String.class, List.class); constructor.setAccessible(true);
        Object session = constructor.newInstance("ws://127.0.0.1:1", List.of(trackedSource));
        ((AtomicReference<WebSocket>) ReflectionTestUtils.getField(session, "webSocket")).set(socket);
        ((Map<String, Object>) ReflectionTestUtils.getField(manager, "sessions")).put("ws://127.0.0.1:1", session);
        ReflectionTestUtils.setField(manager, "running", true);
        try {
            Instant now = Instant.now();
            store.put("BTC-USDT-SWAP", source, quote(now, now));
            manager.checkIdleSessions();
            verify(socket, never()).abort();
            store.put("BTC-USDT-SWAP", source, quote(now.minusSeconds(100), now));
            manager.checkIdleSessions();
            verify(socket).abort();
        } finally { manager.stop(); }
    }

    @Test
    void initiallyDisabledCollectorCanBeEnabledWithoutRestart() {
        var properties = new IndexPriceProperties(); properties.getWebSocket().setEnabled(false);
        var config = mock(IndexInstrumentConfigService.class);
        when(config.symbols()).thenReturn(List.of());
        var manager = new ExternalSpotWebSocketManager(properties, config, mock(ExternalSpotPriceClient.class), new LatestSourceQuoteStore());
        try {
            manager.start();
            verifyNoInteractions(config);
            properties.getWebSocket().setEnabled(true); manager.refreshConnections();
            verify(config).symbols();
            properties.getWebSocket().setEnabled(false); manager.refreshConnections();
            verifyNoMoreInteractions(config);
            org.assertj.core.api.Assertions.assertThat(manager.health()).isEmpty();
        } finally { manager.stop(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedSocketParsesOneMessageOnceAndRoutesOnlyMatchingSymbol() throws Exception {
        var properties = new IndexPriceProperties();
        var mapper = spy(new tools.jackson.databind.ObjectMapper());
        var client = new ExternalSpotPriceClient(properties, mapper);
        var store = new LatestSourceQuoteStore();
        var manager = new ExternalSpotWebSocketManager(properties,
                mock(IndexInstrumentConfigService.class), client, store);
        Class<?> tracked = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$TrackedSource");
        var tc = tracked.getDeclaredConstructors()[0]; tc.setAccessible(true);
        var sources = new java.util.ArrayList<Object>();
        var configs = new java.util.ArrayList<IndexPriceProperties.SourceConfig>();
        for (String symbol : List.of("BTCUSDT", "ETHUSDT", "SOLUSDT")) {
            var config = new IndexPriceProperties.SourceConfig();
            config.setName("BINANCE"); config.setSourceSymbol(symbol); config.setParser("BINANCE_BOOK_TICKER");
            configs.add(config); sources.add(tc.newInstance(symbol, config));
        }
        Class<?> st = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$WsSession");
        var ctor = st.getDeclaredConstructor(String.class, List.class); ctor.setAccessible(true);
        Object session = ctor.newInstance("ws://127.0.0.1:1", sources);
        ((Map<String, Object>) ReflectionTestUtils.getField(manager, "sessions")).put("ws://127.0.0.1:1", session);
        ReflectionTestUtils.setField(manager, "running", true);
        String payload = "{\"s\":\"ETHUSDT\",\"b\":\"99\",\"a\":\"101\"}";
        try {
            ReflectionTestUtils.invokeMethod(manager, "handlePayload", session, payload);
            verify(mapper, times(1)).readTree(payload);
            org.assertj.core.api.Assertions.assertThat(store.latest("BTCUSDT", configs.get(0))).isEmpty();
            org.assertj.core.api.Assertions.assertThat(store.latest("SOLUSDT", configs.get(2))).isEmpty();
            org.assertj.core.api.Assertions.assertThat(store.latest("ETHUSDT", configs.get(1))).isPresent()
                    .get().satisfies(q -> org.assertj.core.api.Assertions.assertThat(q.price()).isEqualByComparingTo("100"));
            ReflectionTestUtils.invokeMethod(manager, "handlePayload", session, "{");
            org.assertj.core.api.Assertions.assertThat(store.latest("ETHUSDT", configs.get(1))).isPresent();
        } finally { manager.stop(); client.close(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lateCallbacksFromRetiredConnectionCannotAbortItsSuccessor() throws Exception {
        var manager = new ExternalSpotWebSocketManager(new IndexPriceProperties(),
                mock(IndexInstrumentConfigService.class), mock(ExternalSpotPriceClient.class), new LatestSourceQuoteStore());
        Class<?> sessionType = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$WsSession");
        var constructor = sessionType.getDeclaredConstructor(String.class, List.class); constructor.setAccessible(true);
        Object session = constructor.newInstance("ws://127.0.0.1:1", List.of());
        ((Map<String, Object>) ReflectionTestUtils.getField(manager, "sessions")).put("ws://127.0.0.1:1", session);
        ReflectionTestUtils.setField(manager, "running", true);
        Class<?> listenerType = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$SourceWebSocketListener");
        var listenerConstructor = listenerType.getDeclaredConstructor(ExternalSpotWebSocketManager.class, sessionType);
        listenerConstructor.setAccessible(true);
        WebSocket.Listener old = (WebSocket.Listener) listenerConstructor.newInstance(manager, session);
        WebSocket.Listener current = (WebSocket.Listener) listenerConstructor.newInstance(manager, session);
        WebSocket socket = mock(WebSocket.class);
        ReflectionTestUtils.setField(session, "listener", current);
        current.onOpen(socket);
        try {
            old.onError(mock(WebSocket.class), new IllegalStateException("retired socket"));
            old.onClose(mock(WebSocket.class), 1006, "retired socket");
            old.onText(mock(WebSocket.class), "{}", true);
            verify(socket, never()).abort();
            org.assertj.core.api.Assertions.assertThat(((AtomicReference<?>) ReflectionTestUtils.getField(session, "webSocket")).get()).isSameAs(socket);
            org.assertj.core.api.Assertions.assertThat(manager.health().getFirst().reconnectAttempts()).isZero();
        } finally { manager.stop(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void removingSharedSocketSubscriptionRetiresTheOldConnection() throws Exception {
        var manager = new ExternalSpotWebSocketManager(new IndexPriceProperties(),
                mock(IndexInstrumentConfigService.class), mock(ExternalSpotPriceClient.class), new LatestSourceQuoteStore());
        Class<?> sessionType = Class.forName(ExternalSpotWebSocketManager.class.getName() + "$WsSession");
        var constructor = sessionType.getDeclaredConstructor(String.class, List.class); constructor.setAccessible(true);
        Object session = constructor.newInstance("ws://127.0.0.1:1", List.of());
        ((Map<String, Object>) ReflectionTestUtils.getField(manager, "sessions")).put("ws://127.0.0.1:1", session);
        ((java.util.Set<String>) ReflectionTestUtils.getField(session, "sentSubscribeMessages")).add("retired-subscription");
        WebSocket socket = mock(WebSocket.class);
        ((AtomicReference<WebSocket>) ReflectionTestUtils.getField(session, "webSocket")).set(socket);
        ReflectionTestUtils.setField(manager, "running", true);
        try {
            ReflectionTestUtils.invokeMethod(manager, "refreshConnections", Map.of("ws://127.0.0.1:1", List.of()));
            verify(socket).abort();
            org.assertj.core.api.Assertions.assertThat((java.util.Set<?>) ReflectionTestUtils.getField(session, "sentSubscribeMessages")).isEmpty();
        } finally { manager.stop(); }
    }

    private SourceQuote quote(Instant sourceTime, Instant receivedAt) {
        return new SourceQuote("BINANCE", "BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, SourceStatus.HEALTHY, null, sourceTime, receivedAt, null, QuoteTransport.PUBLIC_WEBSOCKET);
    }
}
