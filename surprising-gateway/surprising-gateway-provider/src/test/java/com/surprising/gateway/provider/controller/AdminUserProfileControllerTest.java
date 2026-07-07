package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogResponse;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

class AdminUserProfileControllerTest {

    @Test
    void profileAggregatesUserContextAndPropagatesAdminHeaders() {
        AuthService authService = authService();
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        AdminUserProfileController controller = new AdminUserProfileController(
                authService, properties(true), restTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        var response = controller.profile("Bearer admin", 1001L, null, "LINEAR_DELIVERY", "usdt", 50, request);

        assertThat(response.user().userId()).isEqualTo(1001L);
        assertThat(response.sessions().count()).isEqualTo(1);
        assertThat(response.loginLogs().count()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://account:9086/api/v1/admin/accounts/balances?")
                        .contains("userId=1001")
                        .contains("productLine=LINEAR_DELIVERY"));
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://order:9084/api/v1/admin/trading/orders?")
                        .contains("userId=1001")
                        .contains("limit=50")
                        .contains("productLine=LINEAR_DELIVERY"));
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://trigger:9095/api/v1/admin/trading/trigger-orders?")
                        .contains("userId=1001")
                        .contains("limit=50")
                        .contains("productLine=LINEAR_DELIVERY"));
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://risk:9087/api/v1/risk/account/latest?")
                        .contains("userId=1001")
                        .contains("settleAsset=USDT")
                        .contains("productLine=LINEAR_DELIVERY")
                        .contains("accountType=USDT_DELIVERY"));
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://risk:9087/api/v1/risk/positions/latest?")
                        .contains("userId=1001")
                        .contains("productLine=LINEAR_DELIVERY"));
        assertThat(restTemplate.requestEntities).isNotEmpty();
        HttpEntity<?> firstRequest = restTemplate.requestEntities.get(0);
        assertThat(firstRequest.getHeaders().getFirst("X-Admin-User-Id")).isEqualTo("7");
        assertThat(firstRequest.getHeaders().getFirst("X-Admin-Username")).isEqualTo("ops");
        assertThat(firstRequest.getHeaders().getFirst("X-Admin-Roles")).isEqualTo("ADMIN");
        assertThat(firstRequest.getHeaders().getFirst("X-Request-Id")).isEqualTo("request-1");
        assertThat(firstRequest.getHeaders().getFirst("X-Forwarded-For")).isEqualTo("203.0.113.10");
        assertThat(firstRequest.getHeaders().getFirst("X-Product-Line")).isEqualTo("LINEAR_DELIVERY");
    }

    @Test
    void profileReportsMissingDownstreamRouteAsPartialError() {
        AdminUserProfileController controller = new AdminUserProfileController(
                authService(), properties(false), new CapturingRestTemplate());

        var response = controller.profile("Bearer admin", 1001L, null, null, "USDT", 10,
                new MockHttpServletRequest());

        assertThat(response.user().userId()).isEqualTo(1001L);
        assertThat(response.errors())
                .anySatisfy(error -> {
                    assertThat(error.service()).isEqualTo("risk");
                    assertThat(error.message()).isEqualTo("admin route is not configured");
                });
    }

    private AuthService authService() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.authenticateAdminBearer("Bearer admin"))
                .thenReturn(new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), now.plusSeconds(300)));
        when(authService.adminUser("Bearer admin", 1001L))
                .thenReturn(new AuthenticatedUser(1001L, "alice", "alice@example.com", "NORMAL",
                        List.of("USER"), now));
        when(authService.adminRefreshSessions("Bearer admin", 1001L, null, 50))
                .thenReturn(sessions(now));
        when(authService.adminRefreshSessions("Bearer admin", 1001L, null, 10))
                .thenReturn(sessions(now));
        when(authService.loginLogs("Bearer admin", 1001L, null, 50))
                .thenReturn(loginLogs(now));
        when(authService.loginLogs("Bearer admin", 1001L, null, 10))
                .thenReturn(loginLogs(now));
        return authService;
    }

    private AdminRefreshSessionQueryResponse sessions(Instant now) {
        return new AdminRefreshSessionQueryResponse(1, List.of(new AdminRefreshSessionResponse(
                11L, 1001L, true, now.plusSeconds(3600), null, "agent", "127.0.0.1", now, now)));
    }

    private LoginLogQueryResponse loginLogs(Instant now) {
        return new LoginLogQueryResponse(1, List.of(new LoginLogResponse(
                21L, 1001L, "SUCCESS", "LOGIN", "agent", "127.0.0.1", now)));
    }

    private GatewayProperties properties(boolean includeRisk) {
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> adminRoutes = new LinkedHashMap<>();
        adminRoutes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/admin/accounts", true));
        adminRoutes.put("trading-orders", new GatewayProperties.BackendRoute(
                "http://order:9084", "/api/v1/admin/trading/orders", true));
        adminRoutes.put("trading-trigger", new GatewayProperties.BackendRoute(
                "http://trigger:9095", "/api/v1/admin/trading/trigger-orders", true));
        if (includeRisk) {
            adminRoutes.put("risk", new GatewayProperties.BackendRoute(
                    "http://risk:9087", "/api/v1/risk", true));
        }
        properties.setAdminRoutes(adminRoutes);
        return properties;
    }

    private static final class CapturingRestTemplate extends RestTemplate {
        private final List<URI> urls = new ArrayList<>();
        private final List<HttpEntity<?>> requestEntities = new ArrayList<>();

        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            urls.add(url);
            requestEntities.add(requestEntity);
            Map<String, Object> body = Map.of("uri", url.toString());
            return ResponseEntity.ok(responseType.cast(body));
        }
    }
}
