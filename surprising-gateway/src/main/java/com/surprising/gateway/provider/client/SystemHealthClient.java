package com.surprising.gateway.provider.client;

import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import com.surprising.gateway.provider.model.SystemHealthItem;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** 负责本地 Actuator 与外部健康端点的实际探测；不承担权限、目标去重和结果聚合。 */
@Component
public class SystemHealthClient {
    private final RestTemplate restTemplate;
    private final org.springframework.boot.health.actuate.endpoint.HealthEndpoint healthEndpoint;

    public SystemHealthClient(RestTemplate restTemplate,
                              org.springframework.boot.health.actuate.endpoint.HealthEndpoint healthEndpoint) {
        this.restTemplate = restTemplate;
        this.healthEndpoint = healthEndpoint;
    }

    public SystemHealthItem probe(String routeType, String service, BackendRoute route, String baseUrl) {
        URI healthUri = URI.create(baseUrl + "/actuator/health");
        Instant startedAt = Instant.now();
        try {
            if ("local:".equals(baseUrl)) {
                String status = healthEndpoint.health().getStatus().getCode();
                return new SystemHealthItem(routeType, service, baseUrl,
                        healthUri.toString(), route.getTargetPrefix(), route.isPrivateRoute(),
                        false, status, null, Duration.between(startedAt, Instant.now()).toMillis(), null, null);
            }
            HttpHeaders headers = new HttpHeaders();
            if (route.hasBasicAuth()) {
                headers.setBasicAuth(route.getBasicAuthUsername(), route.getBasicAuthPassword());
            }
            ResponseEntity<String> response = restTemplate.exchange(
                    healthUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            String status = response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN";
            return new SystemHealthItem(
                    routeType,
                    service,
                    baseUrl,
                    healthUri.toString(),
                    route.getTargetPrefix(),
                    route.isPrivateRoute(),
                    route.hasBasicAuth(),
                    status,
                    response.getStatusCode().value(),
                    latencyMs,
                    truncate(response.getBody(), 2000),
                    null);
        } catch (ResourceAccessException ex) {
            return failed(routeType, service, route, baseUrl, healthUri, startedAt, "DOWN", "backend health request timed out");
        } catch (RestClientException | IllegalArgumentException ex) {
            return failed(routeType, service, route, baseUrl, healthUri, startedAt, "DOWN", ex.getMessage());
        }
    }

    private SystemHealthItem failed(String routeType, String service, BackendRoute route, String baseUrl,
                                    URI healthUri,
                                    Instant startedAt,
                                    String status,
                                    String error) {
        return new SystemHealthItem(
                routeType,
                service,
                baseUrl,
                healthUri.toString(),
                route.getTargetPrefix(),
                route.isPrivateRoute(),
                route.hasBasicAuth(),
                status,
                null,
                Duration.between(startedAt, Instant.now()).toMillis(),
                null,
                truncate(error, 1000));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

}
