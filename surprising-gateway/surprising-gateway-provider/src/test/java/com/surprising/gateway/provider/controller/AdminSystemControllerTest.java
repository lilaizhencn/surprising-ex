package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class AdminSystemControllerTest {

    @Test
    void routesExposeAdminAndPublicRoutesWithoutSecrets() {
        GatewayProperties properties = properties();
        AdminSystemController controller = new AdminSystemController(
                adminAuthService(), properties, new RestTemplate());

        var response = controller.routes("Bearer admin");

        assertThat(response.publicRoutes()).hasSize(1);
        assertThat(response.adminRoutes()).hasSize(1);
        assertThat(response.adminRoutes().get(0).basicAuthConfigured()).isTrue();
        assertThat(response.adminRoutes().get(0).service()).isEqualTo("wallet-admin");
    }

    @Test
    void healthChecksActuatorHealthForConfiguredBackend() {
        GatewayProperties properties = properties();
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        AdminSystemController controller = new AdminSystemController(
                adminAuthService(), properties, restTemplate);

        var response = controller.health("Bearer admin", false);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.up()).isEqualTo(1);
        assertThat(response.services().get(0).healthUrl())
                .isEqualTo("http://wallet:8002/actuator/health");
        assertThat(restTemplate.url.toString()).isEqualTo("http://wallet:8002/actuator/health");
        assertThat(restTemplate.requestEntity.getHeaders().getFirst("Authorization"))
                .isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> routes = new LinkedHashMap<>();
        routes.put("instrument", new GatewayProperties.BackendRoute(
                "http://instrument:9080", "/api/v1/instruments", false));
        properties.setRoutes(routes);

        GatewayProperties.BackendRoute walletAdmin = new GatewayProperties.BackendRoute(
                "http://wallet:8002", "/wallet/v1/admin", true);
        walletAdmin.setBasicAuthUsername("admin");
        walletAdmin.setBasicAuthPassword("secret");
        Map<String, GatewayProperties.BackendRoute> adminRoutes = new LinkedHashMap<>();
        adminRoutes.put("wallet-admin", walletAdmin);
        properties.setAdminRoutes(adminRoutes);
        return properties;
    }

    private AuthService adminAuthService() {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticateAdminBearer("Bearer admin"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"),
                        Instant.now().plusSeconds(60)));
        return authService;
    }

    private static final class CapturingRestTemplate extends RestTemplate {
        private URI url;
        private HttpEntity<?> requestEntity;

        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            this.url = url;
            this.requestEntity = requestEntity;
            return ResponseEntity.ok(responseType.cast("{\"status\":\"UP\"}"));
        }
    }
}
