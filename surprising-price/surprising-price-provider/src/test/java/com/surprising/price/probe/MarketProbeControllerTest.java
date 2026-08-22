package com.surprising.price.probe;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.client.ExternalSpotWebSocketManager;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.service.LatestIndexPriceCache;
import com.surprising.price.mark.service.MarkPriceQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketProbeControllerTest {

    @Test
    void returnsActualRestProvenanceOverHttpEvenWhenOkxWebSocketIsHealthy() throws Exception {
        Instant now = Instant.now();
        LatestIndexPriceCache indexPriceCache = mock(LatestIndexPriceCache.class);
        MarkPriceQueryService markPriceQueryService = mock(MarkPriceQueryService.class);
        ExternalSpotWebSocketManager webSocketManager = mock(ExternalSpotWebSocketManager.class);
        IndexComponentSnapshot restComponent = new IndexComponentSnapshot(
                "OKX", "BTC-USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, SourceStatus.HEALTHY, null, now, now, 1L,
                QuoteTransport.REST);
        when(indexPriceCache.requireFresh("BTC-USDT")).thenReturn(new IndexPriceResponse(
                "BTC-USDT", BigDecimal.ONE, 1, PriceStatus.HEALTHY, 1, 1, now, List.of(restComponent)));
        when(markPriceQueryService.latest("BTC-USDT")).thenReturn(mark(now));
        when(webSocketManager.health()).thenReturn(List.of(new ExternalSpotWebSocketManager.WebSocketHealth(
                "wss://ws.okx.com:8443/ws/v5/public", 1, true, 10, 2,
                List.of(new ExternalSpotWebSocketManager.WebSocketSourceHealth(
                        "BTC-USDT", "OKX", "PUBLIC_WEBSOCKET")))));
        MarketProbeService service = new MarketProbeService(indexPriceCache, markPriceQueryService,
                webSocketManager, new IndexPriceProperties());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MarketProbeController(service)).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/price/market-probe")
                        .param("symbol", "BTC-USDT")
                        .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.freshSourceCount").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.sourceQuorumHealthy").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.sourceMode").value("PUBLIC_WEBSOCKET_ONLY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.sourceHealth[0].exchange").value("OKX"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.sourceHealth[0].transport").value("REST"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.sourceHealth[0].connected").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.webSockets[0].reconnectAttempts").value(2));
    }

    @Test
    void rejectsAnUnknownSourceMode() throws Exception {
        MarketProbeService service = org.mockito.Mockito.mock(MarketProbeService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MarketProbeController(service)).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/price/market-probe")
                        .param("symbol", "BTC-USDT")
                        .param("sourceMode", "REST"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    private MarkPriceResponse mark(Instant eventTime) {
        return new MarkPriceResponse("BTC-USDT", BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null,
                0, BigDecimal.ZERO, 60, BigDecimal.ONE, BigDecimal.ONE, 1, PriceStatus.HEALTHY, eventTime);
    }
}
