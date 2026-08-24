package com.surprising.price.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotWebSocketManager;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import com.surprising.price.index.service.IndexPriceCalculator;
import com.surprising.price.index.service.LatestIndexPriceCache;
import com.surprising.price.mark.service.MarkPriceQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketProbeServiceTest {

    @Mock
    private LatestIndexPriceCache indexPriceCache;
    @Mock
    private MarkPriceQueryService markPriceQueryService;
    @Mock
    private ExternalSpotWebSocketManager webSocketManager;

    @Test
    void threeFreshUniquePublicWebSocketExchangesPassTheDefaultQuorum() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 0), webSocketHealth("BINANCE", 20, 0),
                webSocketHealth("BYBIT", 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.sourceMode()).isEqualTo(MarketProbeService.SourceMode.PUBLIC_WEBSOCKET_ONLY);
        assertThat(snapshot.freshSourceCount()).isEqualTo(3);
        assertThat(snapshot.sourceQuorumHealthy()).isTrue();
    }

    @Test
    void twoFreshPublicWebSocketExchangesDoNotPassTheQuorum() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 0), webSocketHealth("BINANCE", 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(2);
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
    }

    @Test
    void configuredFourSourceQuorumDoesNotTreatThreeFreshSourcesAsHealthy() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 0), webSocketHealth("BINANCE", 20, 0),
                webSocketHealth("BYBIT", 20, 0)));
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCalculation().setMinValidSources(4);

        MarketProbeService.MarketProbeSnapshot snapshot = service(properties).snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(3);
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
    }

    @Test
    void reportsDegradedQuorumWhenOneHealthySourceIsStale() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now.minusSeconds(6)))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 2), webSocketHealth("BINANCE", 20, 0),
                webSocketHealth("BYBIT", 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(2);
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
        assertThat(snapshot.webSockets())
                .filteredOn(socket -> socket.sources().stream()
                        .anyMatch(source -> "OKX".equals(source.exchange())))
                .singleElement().extracting(
                ExternalSpotWebSocketManager.WebSocketHealth::reconnectAttempts).isEqualTo(2);
    }

    @Test
    void disconnectedPublicWebSocketExchangeDoesNotPassTheQuorum() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 0), webSocketHealth("BINANCE", 20, 0),
                webSocketHealth("BYBIT", false, 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(2);
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
    }

    @Test
    void duplicateExchangeComponentsDoNotPassTheQuorum() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now, List.of(
                component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("OKX", 20, 0), webSocketHealth("BINANCE", 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(2);
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
    }

    @Test
    void reportsTimestampRegressionAcrossConsecutiveSnapshots() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT"))
                .thenReturn(index(now, List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now.minusSeconds(1), List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now.plusSeconds(1), List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BINANCE", QuoteTransport.PUBLIC_WEBSOCKET, now),
                        component("BYBIT", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT"))
                .thenReturn(mark(now))
                .thenReturn(mark(now.minusSeconds(1)))
                .thenReturn(mark(now.plusSeconds(1)));
        when(webSocketManager.health()).thenReturn(List.of(
                webSocketHealth("BINANCE", 20, 0), webSocketHealth("BYBIT", 20, 0),
                webSocketHealth("OKX", 20, 0)));

        MarketProbeService marketProbeService = service();

        MarketProbeService.MarketProbeSnapshot initial = marketProbeService.snapshot("BTC-USDT");
        MarketProbeService.MarketProbeSnapshot regressed = marketProbeService.snapshot("BTC-USDT");
        MarketProbeService.MarketProbeSnapshot recovered = marketProbeService.snapshot("BTC-USDT");

        assertThat(initial.timestampRegressed()).isFalse();
        assertThat(initial.sourceQuorumHealthy()).isTrue();
        assertThat(regressed.timestampRegressed()).isTrue();
        assertThat(regressed.sourceQuorumHealthy()).isFalse();
        assertThat(recovered.timestampRegressed()).isFalse();
        assertThat(recovered.sourceQuorumHealthy()).isTrue();
    }

    @Test
    void okxRestComponentRemainsRestAndDoesNotPassWhenOkxPublicWebSocketIsHealthy() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now,
                List.of(component("OKX", QuoteTransport.REST, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(webSocketHealth("OKX", 20, 0)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).as("only healthy OKX public WebSocket components count").isZero();
        assertThat(snapshot.sourceQuorumHealthy()).isFalse();
        assertThat(snapshot.sourceHealth()).singleElement()
                .extracting(MarketProbeService.SourceHealth::transport,
                        MarketProbeService.SourceHealth::connected)
                .containsExactly("REST", true);
    }

    @Test
    void missingTransportProvenanceFailsClosedForAnOtherwiseHealthyOkxComponent() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now,
                List.of(component("OKX", null, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(webSocketHealth("OKX", 20, 2)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isZero();
        assertThat(snapshot.sourceHealth()).singleElement().extracting(MarketProbeService.SourceHealth::transport)
                .isEqualTo("MISSING");
    }

    @Test
    void actualOkxPublicWebSocketComponentPassesTheDefaultSourceMode() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now,
                List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(webSocketHealth("OKX", 20, 1)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isEqualTo(1);
        assertThat(snapshot.sourceHealth()).singleElement()
                .extracting(MarketProbeService.SourceHealth::transport)
                .isEqualTo("PUBLIC_WEBSOCKET");
    }

    @Test
    void stalePublicWebSocketFrameDoesNotCountTowardTheQuorum() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(index(now,
                List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(webSocketHealth("OKX", 5_001, 1)));

        MarketProbeService.MarketProbeSnapshot snapshot = service().snapshot("BTC-USDT");

        assertThat(snapshot.freshSourceCount()).isZero();
        assertThat(snapshot.sourceHealth()).singleElement().extracting(MarketProbeService.SourceHealth::frameAgeMillis)
                .isEqualTo(5_001L);
    }

    @Test
    void reportsBoundedCadencePercentilesAndRegressionWithoutSamplingDuplicateSnapshots() {
        Instant now = Instant.now();
        when(indexPriceCache.requireFresh("BTC-USDT"))
                .thenReturn(index(now, List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now, List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now.plusMillis(1_000), List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now.plusMillis(3_000), List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))))
                .thenReturn(index(now.plusMillis(2_000), List.of(component("OKX", QuoteTransport.PUBLIC_WEBSOCKET, now))));
        when(markPriceQueryService.latest("BTC-USDT"))
                .thenReturn(mark(now))
                .thenReturn(mark(now))
                .thenReturn(mark(now.plusMillis(1_000)))
                .thenReturn(mark(now.plusMillis(3_000)))
                .thenReturn(mark(now.plusMillis(2_000)));
        when(webSocketManager.health()).thenReturn(List.of(webSocketHealth("OKX", 20, 0)));

        MarketProbeService marketProbeService = service();

        marketProbeService.snapshot("BTC-USDT");
        assertThat(marketProbeService.snapshot("BTC-USDT").indexCadence().sampleCount()).isZero();
        marketProbeService.snapshot("BTC-USDT");
        marketProbeService.snapshot("BTC-USDT");
        MarketProbeService.MarketProbeSnapshot regressed = marketProbeService.snapshot("BTC-USDT");

        assertThat(regressed.timestampRegressed()).isTrue();
        assertThat(regressed.indexCadence()).extracting(MarketProbeService.CadenceSummary::sampleCount,
                MarketProbeService.CadenceSummary::intervalP50Millis,
                MarketProbeService.CadenceSummary::intervalP99Millis,
                MarketProbeService.CadenceSummary::jitterMillis,
                MarketProbeService.CadenceSummary::timestampRegressed)
                .containsExactly(2, 1_000L, 2_000L, 1_000L, true);
    }

    private MarketProbeService service() {
        return service(new IndexPriceProperties());
    }

    private MarketProbeService service(IndexPriceProperties properties) {
        return new MarketProbeService(indexPriceCache, markPriceQueryService, webSocketManager,
                properties);
    }

    private IndexPriceResponse index(Instant eventTime, List<IndexComponentSnapshot> components) {
        return new IndexPriceResponse("BTC-USDT", BigDecimal.ONE, 1, PriceStatus.HEALTHY,
                components.size(), components.size(), eventTime, components);
    }

    private IndexComponentSnapshot component(String source, QuoteTransport transport, Instant receivedAt) {
        SourceQuote quote = new SourceQuote(source, "BTC-USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, SourceStatus.HEALTHY, null, receivedAt, receivedAt, null, transport);
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getCalculation().setMinValidSources(1);
        return new IndexPriceCalculator(properties)
                .calculate("BTC-USDT", 1, 1, List.of(quote), receivedAt)
                .components().getFirst();
    }

    private ExternalSpotWebSocketManager.WebSocketHealth webSocketHealth(String exchange, long frameAgeMillis,
                                                                           int reconnectAttempts) {
        return webSocketHealth(exchange, true, frameAgeMillis, reconnectAttempts);
    }

    private ExternalSpotWebSocketManager.WebSocketHealth webSocketHealth(String exchange, boolean connected,
                                                                           long frameAgeMillis,
                                                                           int reconnectAttempts) {
        return new ExternalSpotWebSocketManager.WebSocketHealth("wss://" + exchange.toLowerCase(), 1, connected, frameAgeMillis,
                reconnectAttempts, List.of(new ExternalSpotWebSocketManager.WebSocketSourceHealth(
                        "BTC-USDT", exchange, "PUBLIC_WEBSOCKET")));
    }

    private MarkPriceResponse mark(Instant eventTime) {
        return new MarkPriceResponse("BTC-USDT", BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null,
                0, BigDecimal.ZERO, 60, BigDecimal.ONE, BigDecimal.ONE, 1, PriceStatus.HEALTHY, eventTime);
    }
}
