package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.client.SystemHealthClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class AdminSystemServiceTest {

    @Test
    void routesExposeAdminAndPublicRoutesWithoutSecrets() {
        GatewayProperties properties = properties();
        AdminSystemService service = new AdminSystemService(
                adminAuthService(), properties, new SystemHealthClient(RestClient.create(), mock(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class)));

        var response = service.routes("Bearer admin");

        assertThat(response.publicRoutes()).hasSize(1);
        assertThat(response.adminRoutes()).hasSize(1);
        assertThat(response.adminRoutes().get(0).basicAuthConfigured()).isTrue();
        assertThat(response.adminRoutes().get(0).service()).isEqualTo("wallet-admin");
    }

    @Test
    void healthChecksActuatorHealthForConfiguredBackend() {
        GatewayProperties properties = properties();
        CapturingRequestFactory http = new CapturingRequestFactory();
        AdminSystemService service = new AdminSystemService(
                adminAuthService(), properties, new SystemHealthClient(
                        RestClient.builder().requestFactory(http).defaultStatusHandler(status -> false, (request, response) -> {
                        }).build(),
                        mock(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class)));

        var response = service.health("Bearer admin", false);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.up()).isEqualTo(1);
        assertThat(response.services().get(0).healthUrl())
                .isEqualTo("http://wallet:8002/actuator/health");
        assertThat(http.url.toString()).isEqualTo("http://wallet:8002/actuator/health");
        assertThat(http.headers.getFirst("Authorization"))
                .isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"})
    void localRoutesShareActualApplicationHealthWithoutHttp(String status) {
        var properties = new GatewayProperties();
        properties.setAdminRoutes(Map.of(
                "account", new GatewayProperties.BackendRoute("local:", "/api/v1/admin/accounts", true),
                "trading", new GatewayProperties.BackendRoute("local:", "/api/v1/admin/trading/orders", true)));
        properties.setRoutes(Map.of("instrument", new GatewayProperties.BackendRoute(
                "http://old-instrument:9080", "/api/v1/instruments", false)));
        var http = mock(RestClient.class);
        var endpoint = mock(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class);
        var health = mock(org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor.class);
        when(health.getStatus()).thenReturn(new org.springframework.boot.health.contributor.Status(status));
        when(endpoint.health()).thenReturn(health);
        var service = new AdminSystemService(adminAuthService(), properties, new SystemHealthClient(http, endpoint));

        var result = service.health("Bearer admin", true);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.services().getFirst().status()).isEqualTo(status);
        assertThat(result.services().getFirst().healthUrl()).isEqualTo("local:/actuator/health");
        assertThat(result.services().getFirst().httpStatus()).isNull();
        assertThat(service.routes("Bearer admin").publicRoutes().getFirst().baseUrl()).isEqualTo("local:");
        org.mockito.Mockito.verify(endpoint).health();
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    @Test
    void localProbeUsesRealSpringHealthAggregation() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration.class,
                        org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration.class))
                .withBean("testDependency", org.springframework.boot.health.contributor.HealthIndicator.class,
                        () -> () -> org.springframework.boot.health.contributor.Health.down().build())
                .withBean(AuthService.class, this::adminAuthService)
                .withBean(RestClient.class, () -> mock(RestClient.class))
                .withBean(GatewayProperties.class, () -> {
                    var properties = new GatewayProperties();
                    properties.setAdminRoutes(Map.of("account", new GatewayProperties.BackendRoute(
                            "local:", "/api/v1/admin/accounts", true)));
                    return properties;
                })
                .withUserConfiguration(AdminSystemService.class, SystemHealthClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var result = context.getBean(AdminSystemService.class).health("Bearer admin", false);
                    assertThat(result.down()).isEqualTo(1);
                    org.mockito.Mockito.verifyNoInteractions(context.getBean(RestClient.class));
                });
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

    private static final class CapturingRequestFactory implements ClientHttpRequestFactory {
        private URI url;
        private HttpHeaders headers;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            this.url = uri;
            return new org.springframework.http.client.AbstractClientHttpRequest() {
                @Override
                protected OutputStream getBodyInternal(HttpHeaders requestHeaders) {
                    return OutputStream.nullOutputStream();
                }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders requestHeaders) throws IOException {
                    headers = requestHeaders;
                    return new MockClientHttpResponse("{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8), 200);
                }

                @Override
                public HttpMethod getMethod() {
                    return httpMethod;
                }

                @Override
                public URI getURI() {
                    return uri;
                }
            };
        }
    }
}
