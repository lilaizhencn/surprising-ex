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
        ((Map<String, Object>) ReflectionTestUtils.getField(manager, "sessions")).put("test", session);
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

    private SourceQuote quote(Instant sourceTime, Instant receivedAt) {
        return new SourceQuote("BINANCE", "BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, SourceStatus.HEALTHY, null, sourceTime, receivedAt, null, QuoteTransport.PUBLIC_WEBSOCKET);
    }
}
