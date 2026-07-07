package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserProfileController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AuthService authService;
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public AdminUserProfileController(AuthService authService,
                                      GatewayProperties properties,
                                      RestTemplate restTemplate) {
        this.authService = authService;
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @GetMapping("/{userId}/profile")
    public AdminUserProfileResponse profile(@RequestHeader("Authorization") String authorization,
                                            @PathVariable("userId") long userId,
                                            @RequestHeader(value = "X-Product-Line", required = false)
                                            String productLineHeader,
                                            @RequestParam(value = "productLine", required = false)
                                            String productLineValue,
                                            @RequestParam(value = "settleAsset", defaultValue = "USDT") String settleAsset,
                                            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                            HttpServletRequest request) {
        JwtPrincipal admin = authenticateAdmin(authorization);
        int boundedLimit = boundLimit(limit);
        String normalizedSettleAsset = normalizeAsset(settleAsset);
        ProductLine productLine = productLine(productLineValue, productLineHeader);
        AuthenticatedUser user = user(authorization, userId);
        AdminRefreshSessionQueryResponse sessions = authService.adminRefreshSessions(
                authorization, userId, null, boundedLimit);
        LoginLogQueryResponse loginLogs = authService.loginLogs(authorization, userId, null, boundedLimit);

        List<AdminUserProfileError> errors = new ArrayList<>();
        AdminDownstreamContext context = new AdminDownstreamContext(admin, productLine, request, errors);
        UserAccountProfile account = new UserAccountProfile(
                fetch(context, "account", "balances", "/balances",
                        withProductLine(productLine, Map.of("userId", userId))),
                fetch(context, "account", "productBalances", "/product-balances",
                        withProductLine(productLine, Map.of("userId", userId))),
                fetch(context, "account", "positions", "/positions",
                        withProductLine(productLine, Map.of("userId", userId))),
                fetch(context, "account", "accountLedger", "/ledger",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))),
                fetch(context, "account", "productLedger", "/product-ledger",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))),
                fetch(context, "account", "transfers", "/transfers",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))));
        UserTradingProfile trading = new UserTradingProfile(
                fetch(context, "trading-orders", "orders", "",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))),
                fetch(context, "trading-orders", "trades", "/trades",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))),
                fetch(context, "trading-trigger", "triggerOrders", "",
                        withProductLine(productLine, Map.of("userId", userId, "limit", boundedLimit))));
        UserRiskProfile risk = productLine == null || productLine.isMarginProduct()
                ? new UserRiskProfile(
                        fetch(context, "risk", "accountLatest", "/account/latest",
                                riskAccountQuery(userId, normalizedSettleAsset, productLine)),
                        fetch(context, "risk", "positionsLatest", "/positions/latest",
                                withProductLine(productLine, Map.of("userId", userId))))
                : new UserRiskProfile(null, Map.of("count", 0, "positions", List.of()));

        return new AdminUserProfileResponse(
                Instant.now(),
                user,
                sessions,
                loginLogs,
                account,
                trading,
                risk,
                errors);
    }

    private JwtPrincipal authenticateAdmin(String authorization) {
        try {
            return authService.authenticateAdminBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private AuthenticatedUser user(String authorization, long userId) {
        try {
            return authService.adminUser(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private Object fetch(AdminDownstreamContext context,
                         String service,
                         String section,
                         String path,
                         Map<String, ?> queryParams) {
        BackendRoute route = adminRoute(service);
        if (route == null) {
            context.errors().add(new AdminUserProfileError(service, section, null, null, "admin route is not configured"));
            return null;
        }
        BackendRoute resolvedRoute = route.resolve(context.productLine());
        if (resolvedRoute == null) {
            context.errors().add(new AdminUserProfileError(service, section, null, null,
                    "product route is not configured: " + context.productLine().name()));
            return null;
        }
        URI uri;
        try {
            uri = targetUri(resolvedRoute, path, queryParams);
        } catch (IllegalArgumentException ex) {
            context.errors().add(new AdminUserProfileError(service, section, null, null, ex.getMessage()));
            return null;
        }
        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers(context, resolvedRoute)), Object.class);
            return response.getBody();
        } catch (RestClientResponseException ex) {
            context.errors().add(new AdminUserProfileError(
                    service, section, uri.toString(), ex.getStatusCode().value(), responseBodyOrMessage(ex)));
        } catch (ResourceAccessException ex) {
            context.errors().add(new AdminUserProfileError(
                    service, section, uri.toString(), HttpStatus.GATEWAY_TIMEOUT.value(), "backend request timed out"));
        } catch (RestClientException | IllegalArgumentException ex) {
            context.errors().add(new AdminUserProfileError(
                    service, section, uri.toString(), HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
        }
        return null;
    }

    private HttpHeaders headers(AdminDownstreamContext context, BackendRoute route) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Admin-User-Id", Long.toString(context.admin().userId()));
        headers.set("X-Admin-Username", context.admin().username());
        headers.set("X-Admin-Roles", String.join(",", context.admin().roles()));
        String requestId = context.request().getHeader("X-Request-Id");
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId);
        }
        String forwardedFor = context.request().getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        if (context.productLine() != null) {
            headers.set("X-Product-Line", context.productLine().name());
        }
        if (route.hasBasicAuth()) {
            headers.setBasicAuth(route.getBasicAuthUsername(), route.getBasicAuthPassword());
        }
        return headers;
    }

    private BackendRoute adminRoute(String service) {
        if (service == null) {
            return null;
        }
        return properties.getAdminRoutes().get(service.trim().toLowerCase(Locale.ROOT));
    }

    private URI targetUri(BackendRoute route, String path, Map<String, ?> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                trimTrailingSlash(route.getBaseUrl()) + ensureLeadingSlash(route.getTargetPrefix()) + normalizeSuffix(path));
        queryParams.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                builder.queryParam(key, value);
            }
        });
        return builder.build().toUri();
    }

    private Map<String, Object> withProductLine(ProductLine productLine, Map<String, ?> queryParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        queryParams.forEach(result::put);
        if (productLine != null) {
            result.putIfAbsent("productLine", productLine.name());
        }
        return result;
    }

    private Map<String, Object> riskAccountQuery(long userId, String settleAsset, ProductLine productLine) {
        Map<String, Object> result = withProductLine(productLine,
                Map.of("userId", userId, "settleAsset", settleAsset));
        if (productLine != null) {
            result.put("accountType", productLine.accountTypeCode());
        }
        return result;
    }

    private ProductLine productLine(String queryValue, String headerValue) {
        String value = firstNonBlank(queryValue, headerValue);
        if (value == null) {
            return null;
        }
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(value).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(value).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = value.toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace('.', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName)
                    || productLine.topicSegment().equalsIgnoreCase(value)) {
                return productLine;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported productLine: " + value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private int boundLimit(int limit) {
        if (limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be positive");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalizeAsset(String asset) {
        String normalized = asset == null || asset.isBlank() ? "USDT" : asset.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settleAsset is too long");
        }
        return normalized;
    }

    private String responseBodyOrMessage(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            return body.length() <= 1000 ? body : body.substring(0, 1000);
        }
        return ex.getMessage();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String normalizeSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private record AdminDownstreamContext(
            JwtPrincipal admin,
            ProductLine productLine,
            HttpServletRequest request,
            List<AdminUserProfileError> errors) {
    }

    public record AdminUserProfileResponse(
            Instant generatedAt,
            AuthenticatedUser user,
            AdminRefreshSessionQueryResponse sessions,
            LoginLogQueryResponse loginLogs,
            UserAccountProfile account,
            UserTradingProfile trading,
            UserRiskProfile risk,
            List<AdminUserProfileError> errors) {
    }

    public record UserAccountProfile(
            Object balances,
            Object productBalances,
            Object positions,
            Object accountLedger,
            Object productLedger,
            Object transfers) {
    }

    public record UserTradingProfile(
            Object orders,
            Object trades,
            Object triggerOrders) {
    }

    public record UserRiskProfile(
            Object accountLatest,
            Object positionsLatest) {
    }

    public record AdminUserProfileError(
            String service,
            String section,
            String targetUri,
            Integer httpStatus,
            String message) {
    }
}
