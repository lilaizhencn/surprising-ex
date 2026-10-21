package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.SystemHealthClient;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import com.surprising.gateway.provider.model.RouteResponse;
import com.surprising.gateway.provider.model.SystemRoutesResponse;
import com.surprising.gateway.provider.model.SystemHealthItem;
import com.surprising.gateway.provider.model.SystemHealthResponse;
import com.surprising.gateway.provider.exception.AdminSystemUnauthorizedException;
import com.surprising.gateway.provider.exception.AdminSystemForbiddenException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 执行后台系统路由汇总和服务健康探测。
 */
@Service
public class AdminSystemService {

    private final AuthService authService;
    private final GatewayProperties properties;
    private final SystemHealthClient healthClient;

    public AdminSystemService(AuthService authService,
                              GatewayProperties properties,
                              SystemHealthClient healthClient) {
        this.authService = authService;
        this.properties = properties;
        this.healthClient = healthClient;
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
                .map(target -> healthClient.probe(target.routeType(), target.service(), target.route(), target.baseUrl()))
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
                com.surprising.gateway.provider.local.LocalBusinessApi.isLocalService(service)
                        ? "local:" : route.getBaseUrl(),
                route.getTargetPrefix(),
                route.isPrivateRoute(),
                route.hasBasicAuth())));
        return responses;
    }

    private void addTarget(Map<String, HealthTarget> targets,
                           String routeType,
                           String service,
                           BackendRoute route) {
        String baseUrl = com.surprising.gateway.provider.local.LocalBusinessApi.isLocalService(service)
                ? "local:" : trimTrailingSlash(route.getBaseUrl());
        String key = baseUrl + "|" + route.hasBasicAuth();
        targets.putIfAbsent(key, new HealthTarget(routeType, service, route, baseUrl));
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record HealthTarget(
            String routeType,
            String service,
            BackendRoute route,
            String baseUrl) {
    }








}
