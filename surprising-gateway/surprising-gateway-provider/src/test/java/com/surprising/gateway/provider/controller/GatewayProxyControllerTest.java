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
import org.springframework.web.client.ResourceAccessException;
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
    void privateTradingTriggerRouteProxiesToTriggerProvider() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/trading-trigger/open");
        request.setQueryString("userId=42&symbol=BTC-USDT");

        URI target = controller.targetUri("trading-trigger",
                properties.getRoutes().get("trading-trigger"), request);

        assertThat(target.toString())
                .isEqualTo("http://trigger:9095/api/v1/trading/trigger-orders/open?userId=42&symbol=BTC-USDT");
        assertThat(properties.getRoutes().get("trading-trigger").isPrivateRoute()).isTrue();
    }

    @Test
    void privateMarketMakerRouteProxiesToInternalProvider() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/market-maker/strategies");

        URI target = controller.targetUri("market-maker",
                properties.getRoutes().get("market-maker"), request);

        assertThat(target.toString())
                .isEqualTo("http://market-maker:9096/api/v1/market-maker/strategies");
        assertThat(properties.getRoutes().get("market-maker").isPrivateRoute()).isTrue();
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

    @Test
    void mapsBackendReadTimeoutToGatewayTimeout() {
        GatewayProxyController controller = new GatewayProxyController(properties(), new TimeoutRestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/candlestick/BTC-USDT/1m");

        assertThatThrownBy(() -> controller.proxy("candlestick", HttpMethod.GET, request, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> routes = new LinkedHashMap<>();
        routes.put("candlestick", new GatewayProperties.BackendRoute(
                "http://candles:9081", "/api/v1/candlestick", false));
        routes.put("trading-market", new GatewayProperties.BackendRoute(
                "http://matching:9085", "/api/v1/trading/market", false));
        routes.put("trading-trigger", new GatewayProperties.BackendRoute(
                "http://trigger:9095", "/api/v1/trading/trigger-orders", true));
        routes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true));
        routes.put("market-maker", new GatewayProperties.BackendRoute(
                "http://market-maker:9096", "/api/v1/market-maker", true));
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

    private static final class TimeoutRestTemplate extends RestTemplate {
        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            throw new ResourceAccessException("Read timed out");
        }
    }
}
