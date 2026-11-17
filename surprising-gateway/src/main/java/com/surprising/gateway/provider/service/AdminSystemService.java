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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 执行后台系统路由汇总和服务健康探测。
 */
@Service
public class AdminSystemService {

    private final AuthService authService;
    private final GatewayProperties properties;
    private final SystemHealthClient healthClient;
    private JdbcTemplate jdbcTemplate;

    public AdminSystemService(AuthService authService,
                              GatewayProperties properties,
                              SystemHealthClient healthClient) {
        this.authService = authService;
        this.properties = properties;
        this.healthClient = healthClient;
    }

    @Autowired(required = false)
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    private static final java.util.Set<String> LOCAL_SERVICES = java.util.Set.of(
            "instrument", "instrument-admin", "trading", "trading-orders", "trading-leverage",
            "trading-fees", "trading-market", "trading-trigger", "account", "account-public");

    private static boolean isLocalService(String service) {
        return service != null && LOCAL_SERVICES.contains(service.toLowerCase(java.util.Locale.ROOT));
    }

    private List<RouteResponse> routeResponses(String routeType, Map<String, BackendRoute> routes) {
        List<RouteResponse> responses = new ArrayList<>();
        routes.forEach((service, route) -> responses.add(new RouteResponse(
                routeType,
                service,
                isLocalService(service) ? "local:" : route.getBaseUrl(),
                route.getTargetPrefix(),
                route.isPrivateRoute(),
                route.hasBasicAuth())));
        return responses;
    }

    private void addTarget(Map<String, HealthTarget> targets,
                           String routeType,
                           String service,
                           BackendRoute route) {
        String baseUrl = isLocalService(service)
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

    public Map<String, Object> overview(String authorization) {
        authenticateAdmin(authorization);
        long totalUsers = queryLong("SELECT count(*) FROM gateway_users");
        long todayRegistrations = queryLong(
                "SELECT count(*) FROM gateway_users WHERE created_at >= date_trunc('day', now())");
        BigDecimal totalDepositsUsdt = queryDecimal(
                "SELECT COALESCE(SUM(amount_units), 0) / 1000000.0 FROM account_ledger_entries WHERE amount_units > 0 AND reference_type LIKE '%DEPOSIT%'");
        BigDecimal todayWithdrawalsUsdt = queryDecimal(
                "SELECT COALESCE(SUM(usdt_value), 0) FROM gateway_wallet_withdrawals WHERE status NOT IN ('REJECTED', 'REFUNDED') AND created_at >= date_trunc('day', now())");
        BigDecimal totalWithdrawalsUsdt = queryDecimal(
                "SELECT COALESCE(SUM(usdt_value), 0) FROM gateway_wallet_withdrawals WHERE status NOT IN ('REJECTED', 'REFUNDED')");

        Map<String, long[]> countsByLine = new LinkedHashMap<>();
        if (jdbcTemplate != null) {
            jdbcTemplate.query(
                    "SELECT product_line, count(*) AS total_cnt, count(*) FILTER (WHERE status = 'TRADING') AS trading_cnt FROM instruments GROUP BY product_line",
                    rs -> {
                        countsByLine.put(rs.getString("product_line"), new long[] {
                                rs.getLong("total_cnt"),
                                rs.getLong("trading_cnt")
                        });
                    });
        }
        List<String> allLines = List.of(
                "SPOT",
                "LINEAR_PERPETUAL",
                "INVERSE_PERPETUAL",
                "LINEAR_DELIVERY",
                "INVERSE_DELIVERY",
                "OPTION");
        List<Map<String, Object>> productLines = new ArrayList<>();
        for (String line : allLines) {
            long[] cnt = countsByLine.getOrDefault(line, new long[] { 0L, 0L });
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productLine", line);
            item.put("totalInstruments", cnt[0]);
            item.put("tradingInstruments", cnt[1]);
            productLines.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now());
        result.put("totalUsers", totalUsers);
        result.put("todayRegistrations", todayRegistrations);
        result.put("totalDepositsUsdt", totalDepositsUsdt);
        result.put("todayWithdrawalsUsdt", todayWithdrawalsUsdt);
        result.put("totalWithdrawalsUsdt", totalWithdrawalsUsdt);
        result.put("productLines", productLines);
        return result;
    }

    private long queryLong(String sql) {
        if (jdbcTemplate == null) {
            return 0L;
        }
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value != null ? value : 0L;
    }

    private BigDecimal queryDecimal(String sql) {
        if (jdbcTemplate == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value != null ? value : BigDecimal.ZERO;
    }

    private record HealthTarget(
            String routeType,
            String service,
            BackendRoute route,
            String baseUrl) {
    }








}
