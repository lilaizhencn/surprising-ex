package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.gateway.provider.config.GatewayTraceFilter;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.auth.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

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
    void adminGatewayUsesSeparateAdminRoutesAndPrefix() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/gateway/account/ledger");
        request.setQueryString("userId=42&asset=USDT");

        URI target = controller.targetUri("account", properties.getAdminRoutes().get("account"), request);

        assertThat(target.toString())
                .isEqualTo("http://account:9086/api/v1/admin/accounts/ledger?userId=42&asset=USDT");
    }

    @Test
    void adminTradingTriggerRouteUsesAdminTriggerOrderPrefix() {
        GatewayProperties properties = properties();
        GatewayProxyController controller = new GatewayProxyController(properties, new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/gateway/trading-trigger");
        request.setQueryString("userId=42&symbol=BTC-USDT");

        URI target = controller.targetUri("trading-trigger",
                properties.getAdminRoutes().get("trading-trigger"), request);

        assertThat(target.toString())
                .isEqualTo("http://trigger:9095/api/v1/admin/trading/trigger-orders?userId=42&symbol=BTC-USDT");
    }

    @Test
    void adminGatewayNeverFallsBackToUserIdHeader() {
        GatewayProxyController controller = new GatewayProxyController(properties(), new RestTemplate());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/gateway/account/ledger");
        request.addHeader("X-User-Id", "42");

        assertThatThrownBy(() -> controller.proxy("account", HttpMethod.GET, request, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void highRiskAdminWriteRequiresApproval() {
        AuthService authService = adminAuthService();
        GatewayProxyController controller = new GatewayProxyController(
                properties(), new RestTemplate(), authService, null, new FakeApprovalRepository());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/admin/gateway/account/balance-adjustments");
        request.addHeader("Authorization", "Bearer admin");

        assertThatThrownBy(() -> controller.proxy("account", HttpMethod.POST, request, "{}".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
    }

    @Test
    void riskAdminWriteRequiresApproval() {
        AuthService authService = adminAuthService();
        GatewayProxyController controller = new GatewayProxyController(
                properties(), new RestTemplate(), authService, null, new FakeApprovalRepository());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/admin/gateway/risk-admin/rules/GLOBAL_MARGIN_POLICY");
        request.addHeader("Authorization", "Bearer admin");

        assertThatThrownBy(() -> controller.proxy("risk-admin", HttpMethod.POST, request, "{}".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
    }

    @Test
    void adminGatewayRequiresServicePermissionBeforeProxying() {
        AuthService authService = adminAuthService();
        doThrow(new IllegalStateException("admin permission required: admin.gateway.account.write"))
                .when(authService).requireAdminPermission(7L, List.of("ADMIN"), "admin.gateway.account.write");
        GatewayProxyController controller = new GatewayProxyController(
                properties(), new RestTemplate(), authService, null, new FakeApprovalRepository());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/admin/gateway/account/balance-adjustments");
        request.addHeader("Authorization", "Bearer admin");

        assertThatThrownBy(() -> controller.proxy("account", HttpMethod.POST, request, "{}".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("admin permission required: admin.gateway.account.write");
    }

    @Test
    void approvedHighRiskAdminWriteConsumesApprovalAndProxies() {
        GatewayProperties properties = properties();
        AuthService authService = adminAuthService();
        FakeApprovalRepository approvalRepository = new FakeApprovalRepository();
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        GatewayProxyController controller = new GatewayProxyController(
                properties, restTemplate, authService, null, approvalRepository);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/admin/gateway/account/balance-adjustments");
        request.addHeader("Authorization", "Bearer admin");
        request.addHeader("X-Admin-Approval-Id", "99");
        byte[] body = "{\"amountUnits\":100}".getBytes();

        controller.proxy("account", HttpMethod.POST, request, body);

        assertThat(approvalRepository.approvalId).isEqualTo(99L);
        assertThat(approvalRepository.requesterUserId).isEqualTo(7L);
        assertThat(approvalRepository.service).isEqualTo("account");
        assertThat(approvalRepository.method).isEqualTo("POST");
        assertThat(approvalRepository.bodyHash).hasSize(64);
        assertThat(restTemplate.requestEntity.getBody()).isEqualTo(body);
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
    void tradeDisabledUserCannotPlaceOrders() {
        AuthService authService = userAuthService("TRADE_DISABLED");
        GatewayProxyController controller = new GatewayProxyController(properties(), new RestTemplate(), authService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/gateway/trading");
        request.addHeader("Authorization", "Bearer user");

        assertThatThrownBy(() -> controller.proxy("trading", HttpMethod.POST, request, "{}".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("user trading is disabled");
    }

    @Test
    void tradeDisabledUserCanStillCancelOrders() {
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        AuthService authService = userAuthService("TRADE_DISABLED");
        GatewayProxyController controller = new GatewayProxyController(properties(), restTemplate, authService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/gateway/trading/cancel");
        request.addHeader("Authorization", "Bearer user");

        ResponseEntity<byte[]> response = controller.proxy("trading", HttpMethod.POST, request, "{}".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.requestEntity.getHeaders().getFirst("X-User-Id")).isEqualTo("42");
    }

    @Test
    void withdrawDisabledUserCannotCallWalletWithdraw() {
        AuthService authService = userAuthService("WITHDRAW_DISABLED");
        GatewayProxyController controller = new GatewayProxyController(properties(), new RestTemplate(), authService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/gateway/wallet/app/withdraw");
        request.addHeader("Authorization", "Bearer user");

        assertThatThrownBy(() -> controller.proxy("wallet", HttpMethod.POST, request, "{}".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("user withdrawal is disabled");
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
    void routeBasicAuthOverridesIncomingAuthorizationHeader() {
        GatewayProperties properties = properties();
        GatewayProperties.BackendRoute route = properties.getRoutes().get("candlestick");
        route.setBasicAuthUsername("wallet");
        route.setBasicAuthPassword("secret");
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        GatewayProxyController controller = new GatewayProxyController(properties, restTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/gateway/candlestick/BTC-USDT/1m");
        request.addHeader("Authorization", "Bearer browser-token");

        controller.proxy("candlestick", HttpMethod.GET, request, null);

        assertThat(restTemplate.requestEntity.getHeaders().getFirst("Authorization"))
                .isEqualTo("Basic d2FsbGV0OnNlY3JldA==");
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
        routes.put("trading", new GatewayProperties.BackendRoute(
                "http://order:9084", "/api/v1/trading/orders", true));
        routes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/accounts", true));
        routes.put("market-maker", new GatewayProperties.BackendRoute(
                "http://market-maker:9096", "/api/v1/market-maker", true));
        routes.put("wallet", new GatewayProperties.BackendRoute(
                "http://wallet:8002", "/wallet/v1", true));
        properties.setRoutes(routes);
        Map<String, GatewayProperties.BackendRoute> adminRoutes = new LinkedHashMap<>();
        adminRoutes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/admin/accounts", true));
        adminRoutes.put("trading-trigger", new GatewayProperties.BackendRoute(
                "http://trigger:9095", "/api/v1/admin/trading/trigger-orders", true));
        adminRoutes.put("risk-admin", new GatewayProperties.BackendRoute(
                "http://risk:9087", "/api/v1/admin/risk", true));
        properties.setAdminRoutes(adminRoutes);
        return properties;
    }

    private AuthService adminAuthService() {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer admin"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                        Instant.now().plusSeconds(60)));
        return authService;
    }

    private AuthService userAuthService(String status) {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateBearer("Bearer user"))
                .thenReturn(new JwtPrincipal(42L, "user", status, List.of("USER"),
                        Instant.now().plusSeconds(60)));
        return authService;
    }

    private static final class FakeApprovalRepository extends AdminApprovalRepository {
        private long approvalId;
        private long requesterUserId;
        private String service;
        private String method;
        private String bodyHash;

        private FakeApprovalRepository() {
            super(null);
        }

        @Override
        public com.surprising.gateway.provider.auth.AuthModels.AdminApprovalResponse consumeApproved(
                long approvalId,
                long requesterUserId,
                String service,
                String method,
                String requestPath,
                String queryString,
                String bodyHash,
                String traceId,
                Instant now) {
            this.approvalId = approvalId;
            this.requesterUserId = requesterUserId;
            this.service = service;
            this.method = method;
            this.bodyHash = bodyHash;
            return null;
        }
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
