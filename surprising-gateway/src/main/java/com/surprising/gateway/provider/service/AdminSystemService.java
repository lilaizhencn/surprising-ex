package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 执行后台系统路由汇总和服务健康探测。
 */
@Service
public class AdminSystemService {

    private final AuthService authService;
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public AdminSystemService(AuthService authService,
                              GatewayProperties properties,
                              RestTemplate restTemplate) {
        this.authService = authService;
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public SystemRoutesResponse routes(String authorization) {
        authenticateAdmin(authorization);
        return new SystemRoutesResponse(
                Instant.now(),
                routeResponses("public", properties.getRoutes()),
                routeResponses("admin", properties.getAdminRoutes()));
    }

    public SystemHealthResponse health(String authorization, boolean includePublicRoutes) {
        authenticateAdmin(authorization);
        Map<String, HealthTarget> targets = new LinkedHashMap<>();
        properties.getAdminRoutes().forEach((service, route) -> addTarget(targets, "admin", service, route));
        if (includePublicRoutes) {
            properties.getRoutes().forEach((service, route) -> addTarget(targets, "public", service, route));
        }
        List<SystemHealthItem> items = targets.values().stream()
                .map(this::probe)
                .toList();
        long up = items.stream().filter(item -> "UP".equals(item.status())).count();
        long down = items.stream().filter(item -> "DOWN".equals(item.status())).count();
        long unknown = items.size() - up - down;
        return new SystemHealthResponse(Instant.now(), items.size(), up, down, unknown, items);
    }

    private void authenticateAdmin(String authorization) {
        try {
            authService.authenticateAdminBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new AdminSystemUnauthorizedException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new AdminSystemForbiddenException(ex.getMessage(), ex);
        }
    }

    private List<RouteResponse> routeResponses(String routeType, Map<String, BackendRoute> routes) {
        List<RouteResponse> responses = new ArrayList<>();
        routes.forEach((service, route) -> responses.add(new RouteResponse(
                routeType,
                service,
                route.getBaseUrl(),
                route.getTargetPrefix(),
                route.isPrivateRoute(),
                route.hasBasicAuth())));
        return responses;
    }

    private void addTarget(Map<String, HealthTarget> targets,
                           String routeType,
                           String service,
                           BackendRoute route) {
        String baseUrl = trimTrailingSlash(route.getBaseUrl());
        String key = baseUrl + "|" + route.hasBasicAuth();
        targets.putIfAbsent(key, new HealthTarget(routeType, service, route, baseUrl));
    }

    private SystemHealthItem probe(HealthTarget target) {
        URI healthUri = URI.create(target.baseUrl() + "/actuator/health");
        Instant startedAt = Instant.now();
        try {
            HttpHeaders headers = new HttpHeaders();
            if (target.route().hasBasicAuth()) {
                headers.setBasicAuth(target.route().getBasicAuthUsername(), target.route().getBasicAuthPassword());
            }
            ResponseEntity<String> response = restTemplate.exchange(
                    healthUri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            String status = response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN";
            return new SystemHealthItem(
                    target.routeType(),
                    target.service(),
                    target.baseUrl(),
                    healthUri.toString(),
                    target.route().getTargetPrefix(),
                    target.route().isPrivateRoute(),
                    target.route().hasBasicAuth(),
                    status,
                    response.getStatusCode().value(),
                    latencyMs,
                    truncate(response.getBody(), 2000),
                    null);
        } catch (ResourceAccessException ex) {
            return failed(target, healthUri, startedAt, "DOWN", "backend health request timed out");
        } catch (RestClientException | IllegalArgumentException ex) {
            return failed(target, healthUri, startedAt, "DOWN", ex.getMessage());
        }
    }

    private SystemHealthItem failed(HealthTarget target,
                                    URI healthUri,
                                    Instant startedAt,
                                    String status,
                                    String error) {
        return new SystemHealthItem(
                target.routeType(),
                target.service(),
                target.baseUrl(),
                healthUri.toString(),
                target.route().getTargetPrefix(),
                target.route().isPrivateRoute(),
                target.route().hasBasicAuth(),
                status,
                null,
                Duration.between(startedAt, Instant.now()).toMillis(),
                null,
                truncate(error, 1000));
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record HealthTarget(
            String routeType,
            String service,
            BackendRoute route,
            String baseUrl) {
    }

    public record RouteResponse(
            String routeType,
            String service,
            String baseUrl,
            String targetPrefix,
            boolean privateRoute,
            boolean basicAuthConfigured) {
    }

    public record SystemRoutesResponse(
            Instant generatedAt,
            List<RouteResponse> publicRoutes,
            List<RouteResponse> adminRoutes) {
    }

    public record SystemHealthItem(
            String routeType,
            String service,
            String baseUrl,
            String healthUrl,
            String targetPrefix,
            boolean privateRoute,
            boolean basicAuthConfigured,
            String status,
            Integer httpStatus,
            long latencyMs,
            String responseBody,
            String error) {
    }

    public record SystemHealthResponse(
            Instant generatedAt,
            int count,
            long up,
            long down,
            long unknown,
            List<SystemHealthItem> services) {
    }

    public static class AdminSystemUnauthorizedException extends RuntimeException {
        public AdminSystemUnauthorizedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class AdminSystemForbiddenException extends RuntimeException {
        public AdminSystemForbiddenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
