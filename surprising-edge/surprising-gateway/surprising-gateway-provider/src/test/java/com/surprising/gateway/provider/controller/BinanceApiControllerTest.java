package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.CustodyWalletClient;
import com.surprising.gateway.provider.service.GatewayProxyService;
import com.surprising.gateway.provider.service.SpotAccountClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class BinanceApiControllerTest {

    @Test
    void allOrdersReadsHistoryRouteAndReturnsAscendingBinanceOrderList() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));

        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        String payload = "{\"orders\":["
                + "{\"orderId\":2,\"symbol\":\"BTCUSDT\",\"clientOrderId\":\"b\","
                + "\"createdAt\":\"2026-08-01T00:00:02Z\",\"orderType\":\"LIMIT\","
                + "\"timeInForce\":\"GTC\",\"side\":\"SELL\",\"status\":\"FILLED\","
                + "\"priceTicks\":12345,\"quantitySteps\":2000,\"executedQuantitySteps\":2000},"
                + "{\"orderId\":1,\"symbol\":\"BTCUSDT\",\"clientOrderId\":\"a\","
                + "\"createdAt\":\"2026-08-01T00:00:01Z\",\"orderType\":\"LIMIT\","
                + "\"timeInForce\":\"GTC\",\"side\":\"BUY\",\"status\":\"CANCELED\","
                + "\"priceTicks\":12300,\"quantitySteps\":1000,\"executedQuantitySteps\":0}]}";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/allOrders");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        request.setParameter("orderId", "1");
        request.setParameter("startTime", "1754006400000");
        when(proxy.proxyCompat(anyString(), eq("/history"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), eq(1001L)))
                .thenReturn(ResponseEntity.ok(payload.getBytes(StandardCharsets.UTF_8)));

        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), authService, mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(SpotAccountClient.class), new ObjectMapper());

        var response = controller.handle(request, null);
        List<Map<String, Object>> orders = new ObjectMapper().readValue(response.getBody(), List.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(orders).extracting(row -> ((Number) row.get("orderId")).longValue())
                .containsExactly(1L, 2L);
        assertThat(orders.getFirst().get("status")).isEqualTo("CANCELED");
        verify(proxy).proxyCompat(anyString(), eq("/history"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), eq(1001L));
    }

    @Test
    void ticker24hrConvertsFactSummaryToBinanceFields() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        String payload = "{\"symbol\":\"BTCUSDT\",\"firstTradeId\":10,\"lastTradeId\":11,"
                + "\"tradeCount\":2,\"openPriceTicks\":10000,\"highPriceTicks\":11000,"
                + "\"lowPriceTicks\":9900,\"lastPriceTicks\":10500,\"volumeSteps\":\"3000\","
                + "\"quoteVolumeTicksSteps\":\"31000000\",\"lastQuantitySteps\":\"2000\","
                + "\"openTime\":\"2026-08-01T00:00:00Z\",\"closeTime\":\"2026-08-01T00:01:00Z\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/ticker/24hr");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        when(proxy.proxyCompat(anyString(), eq("/ticker-24hr"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull()))
                .thenReturn(ResponseEntity.ok(payload.getBytes(StandardCharsets.UTF_8)));
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), authService, mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(SpotAccountClient.class), new ObjectMapper());

        var response = controller.handle(request, null);
        Map<String, Object> ticker = new ObjectMapper().readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ticker).containsEntry("symbol", "BTCUSDT")
                .containsEntry("priceChange", "5")
                .containsEntry("priceChangePercent", "5")
                .containsEntry("lastPrice", "105")
                .containsEntry("volume", "3")
                .containsEntry("quoteVolume", "310");
        verify(proxy).proxyCompat(anyString(), eq("/ticker-24hr"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull());
    }

    @Test
    void tickerPriceReadsLatestTradeFromPublicMarketRoute() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.SymbolScale scale = new GatewayProperties.SymbolScale();
        scale.setPriceScale(2);
        scale.setQuantityScale(3);
        properties.getBinanceApi().setSymbolScales(Map.of("BTCUSDT", scale));
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(1001L, "alice", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        when(proxy.proxyCompat(anyString(), eq("/latest-trade"), anyString(), eq(HttpMethod.GET),
                any(), isNull(), isNull()))
                .thenReturn(ResponseEntity.ok("{\"priceTicks\":12345}".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/ticker/price");
        request.addHeader("Authorization", "Bearer token");
        request.setParameter("symbol", "BTCUSDT");
        BinanceApiController controller = new BinanceApiController(properties, proxy,
                mock(GatewayApiKeyService.class), authService, mock(ComplianceService.class),
                mock(CustodyWalletClient.class), mock(SpotAccountClient.class), new ObjectMapper());

        var response = controller.handle(request, null);
        Map<String, Object> ticker = new ObjectMapper().readValue(response.getBody(), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ticker).containsEntry("symbol", "BTCUSDT").containsEntry("price", "123.45");
    }
}
