package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
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
import org.springframework.web.client.RestTemplate;

class AdminSystemObservabilityControllerTest {

    @Test
    void observabilityAggregatesWebSocketMetricsAndPrometheusScrape() {
        AuthService authService = mock(AuthService.class);
        when(authService.requireAdminPermission("Bearer admin", "admin.system.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                        Instant.parse("2026-07-03T00:00:00Z")));
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> adminRoutes = new LinkedHashMap<>();
        adminRoutes.put("websocket-admin", new GatewayProperties.BackendRoute(
                "http://websocket:9093", "/api/v1/admin/websocket", true));
        properties.setAdminRoutes(adminRoutes);
        properties.getObservability().getKafka().setEnabled(false);

        AdminSystemObservabilityController controller = new AdminSystemObservabilityController(
                authService, properties, new FakeRestTemplate());

        var response = controller.observability("Bearer admin");

        assertThat(response.kafka().enabled()).isFalse();
        assertThat(response.webSocket().status()).isEqualTo("UP");
        assertThat(response.webSocket().activeConnections()).isEqualTo(12);
        assertThat(response.webSocket().totalSubscriptions()).isEqualTo(34);
        assertThat(response.prometheus().enabled()).isTrue();
        assertThat(response.prometheus().up()).isEqualTo(1);
        assertThat(response.prometheus().targets().get(0).sampleCount()).isEqualTo(2);
        verify(authService).requireAdminPermission("Bearer admin", "admin.system.read");
    }

    private static final class FakeRestTemplate extends RestTemplate {

        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            if (url.toString().endsWith("/api/v1/admin/websocket/metrics")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("activeConnections", 12);
                body.put("authenticatedConnections", 10);
                body.put("anonymousConnections", 2);
                body.put("totalSubscriptions", 34);
                body.put("uniqueTopics", 8);
                body.put("maxSubscriptionsPerSession", 6);
                body.put("channels", List.of(Map.of("channel", "TRADES", "topicCount", 2, "subscriberCount", 20)));
                return new ResponseEntity<>(responseType.cast(body), HttpStatus.OK);
            }
            if (url.toString().endsWith("/actuator/prometheus")) {
                String body = """
                        # HELP surprising_websocket_connections_active Active WebSocket client connections.
                        surprising_websocket_connections_active 12.0
                        http_server_requests_seconds_count{uri="/api/v1/admin/system/observability"} 1.0
                        """;
                return new ResponseEntity<>(responseType.cast(body), HttpStatus.OK);
            }
            throw new IllegalArgumentException(url.toString());
        }
    }
}
