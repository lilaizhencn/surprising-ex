package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.config.GatewayTraceFilter;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class GatewayProxyControllerTest {

    @Test
    void targetUriUsesAllowlistedBackendAndPreservesQueryString() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/candlestick/BTC-USDT/1m");
        request.setQueryString("limit=100");

        URI target = controller.targetUri("candlestick", properties.getRoutes().get("candlestick"), request);

        assertThat(target.toString())
                .isEqualTo("http://candles:9081/api/v1/candlestick/BTC-USDT/1m?limit=100");
    }

    @Test
    void publicTradingMarketRouteProxiesOrderBookSnapshotToMatchingProvider() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/trading-market/orderbook");
        request.setQueryString("symbol=BTC-USDT&depth=50");

        URI target = controller.targetUri("trading-market", properties.getRoutes().get("trading-market"), request);

        assertThat(target.toString())
                .isEqualTo("http://matching:9085/api/v1/trading/market/orderbook?symbol=BTC-USDT&depth=50");
        assertThat(properties.getRoutes().get("trading-market").isPrivateRoute()).isFalse();
    }

    @Test
    void privateRouteRequiresIdentityBeforeProxying() {
        GatewayProxyController controller = new GatewayProxyController(properties(), new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/account/42/positions");

        assertThatThrownBy(() -> controller.proxy("account", HttpMethod.GET, request, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void forwardsTraceIdFromGatewayFilterAttribute() {
        GatewayProperties properties = properties();
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        GatewayProxyController controller = new GatewayProxyController(properties, restTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/candlestick/BTC-USDT/1m");
        request.setAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE, "trace-gateway-1");

        controller.proxy("candlestick", HttpMethod.GET, request, null);

        assertThat(restTemplate.requestEntity.getHeaders().getFirst(GatewayTraceFilter.TRACE_ID_HEADER))
                .isEqualTo("trace-gateway-1");
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> routes = new LinkedHashMap<>();
        routes.put("candlestick", new GatewayProperties.BackendRoute(
                "http://candles:9081", "/api/v1/candlestick", false));
        routes.put("trading-market", new GatewayProperties.BackendRoute(
                "http://matching:9085", "/api/v1/trading/market", false));
        routes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true));
        properties.setRoutes(routes);
        return properties;
    }

    private static final class CapturingRestTemplate extends RestTemplate {
        private HttpEntity<?> requestEntity;

        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            this.requestEntity = requestEntity;
            return ResponseEntity.ok(responseType.cast(new byte[0]));
        }
    }
}
