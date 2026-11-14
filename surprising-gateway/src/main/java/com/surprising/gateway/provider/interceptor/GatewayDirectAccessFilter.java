package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.controller.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces gateway authentication, product-line isolation, admin permissions,
 * two-person approvals, and audit logging on direct in-process module controller routes.
 */
public class GatewayDirectAccessFilter extends OncePerRequestFilter {

    private record DirectRoute(String service, boolean privateRoute, boolean admin, boolean shared) {}

    private record Identity(String userId, String username, List<String> roles, boolean admin) {}

    private final GatewayProperties properties;
    private final AuthService authService;
    private final GatewayApiKeyService apiKeys;
    private final ApiKeyRequestBinding apiKeyRequests;
    private final AdminApprovalService adminOperations;
    private final GatewayProductSelection productSelection;

    public GatewayDirectAccessFilter(GatewayProperties properties,
                                     ObjectProvider<AuthService> authService,
                                     ObjectProvider<GatewayApiKeyService> apiKeys,
                                     ObjectProvider<AdminApprovalService> adminOperations,
                                     ObjectProvider<GatewayProductSelection> productSelection) {
        this.properties = properties;
        this.authService = authService.getIfAvailable();
        this.apiKeys = apiKeys.getIfAvailable();
        this.apiKeyRequests = new ApiKeyRequestBinding(properties);
        this.adminOperations = adminOperations.getIfAvailable();
        this.productSelection = productSelection.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return matchRoute(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        DirectRoute route = matchRoute(request.getRequestURI());
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }
        long startedNanos = System.nanoTime();
        byte[] body = cachedBody(request);
        Identity identity = null;
        String bodyHash = null;
        HttpStatus failureStatus = null;
        String failureReason = null;
        try {
            ProductLine product = productSelection == null ? null
                    : productSelection.resolve(request, body, !route.shared());
            identity = route.admin() ? enforceAdminIdentity(request) : enforceIdentity(route, request, body);
            if (route.admin()) {
                enforceAdminPermission(route.service(), request.getMethod(), identity);
                bodyHash = bodySha256(body);
                enforceAdminApprovalIfRequired(route.service(), request, bodyHash, identity);
            } else if (identity != null) {
                enforceQueryUserIdOwnership(request, identity);
            }
            HttpServletRequest wrapped = wrapHeaders(request, product, identity);
            filterChain.doFilter(wrapped, response);
        } catch (ResponseStatusException ex) {
            failureStatus = HttpStatus.resolve(ex.getStatusCode().value());
            failureReason = ex.getReason();
            GatewaySecurityErrors.write(response, ex.getStatusCode().value(),
                    ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString());
        } finally {
            if (route.admin() && identity != null && adminOperations != null) {
                int status = failureStatus != null ? failureStatus.value() : response.getStatus();
                boolean success = failureStatus == null && status < 400;
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
                adminOperations.recordGatewayOperation(new AdminOperationRecord(
                        parseLong(identity.userId()),
                        identity.username(),
                        identity.roles(),
                        route.service(),
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        request.getRequestURI(),
                        bodyHash,
                        status,
                        elapsedMs,
                        success,
                        failureReason,
                        request.getHeader(HttpHeaders.USER_AGENT),
                        request.getRemoteAddr(),
                        Instant.now()));
            }
        }
    }

    private DirectRoute matchRoute(String uri) {
        if (uri == null) return null;
        boolean hasAdminSegment = uri.contains("/admin/");
        if (uri.startsWith("/api/v1/price/index")) {
            return new DirectRoute("price-index", hasAdminSegment, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/price/fx")) {
            return new DirectRoute("price-fx", hasAdminSegment, hasAdminSegment, true);
        }
        if (uri.startsWith("/api/v1/price/mark")) {
            return new DirectRoute("price-mark", hasAdminSegment, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/candlestick")) {
            return new DirectRoute("candlestick", hasAdminSegment, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/funding")) {
            return new DirectRoute("funding", hasAdminSegment, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/admin/risk")) {
            return new DirectRoute("risk-admin", true, true, false);
        }
        if (uri.startsWith("/api/v1/risk")) {
            return new DirectRoute(hasAdminSegment ? "risk-admin" : "risk", true, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/admin/liquidations")) {
            return new DirectRoute("liquidation-admin", true, true, false);
        }
        if (uri.startsWith("/api/v1/liquidations")) {
            return new DirectRoute(hasAdminSegment ? "liquidation-admin" : "liquidation", true, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/insurance")) {
            return new DirectRoute(hasAdminSegment ? "insurance-admin" : "insurance", true, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/adl")) {
            return new DirectRoute("adl", true, hasAdminSegment, false);
        }
        if (uri.startsWith("/api/v1/admin/websocket")) {
            return new DirectRoute("websocket-admin", true, true, true);
        }
        if (uri.startsWith("/api/v1/accounts/admin/") || uri.startsWith("/api/v1/admin/accounts/")) {
            return new DirectRoute("account", true, true, false);
        }
        if (uri.startsWith("/api/v1/accounts/")) {
            return new DirectRoute("account", true, false, false);
        }
        if (uri.startsWith("/api/v1/trading/market/")) {
            return new DirectRoute("trading-market", false, false, false);
        }
        if (uri.startsWith("/api/v1/admin/trading/trigger-orders")) {
            return new DirectRoute("trading-trigger", true, true, false);
        }
        if (uri.startsWith("/api/v1/admin/trading/fees")) {
            return new DirectRoute("trading-fees", true, true, false);
        }
        if (uri.startsWith("/api/v1/admin/trading/leverage")) {
            return new DirectRoute("trading-leverage", true, true, false);
        }
        if (uri.startsWith("/api/v1/admin/trading/")) {
            return new DirectRoute("trading", true, true, false);
        }
        if (uri.startsWith("/api/v1/trading/trigger-orders")) {
            return new DirectRoute("trading-trigger", true, false, false);
        }
        if (uri.startsWith("/api/v1/trading/fees")) {
            return new DirectRoute("trading-fees", true, false, false);
        }
        if (uri.startsWith("/api/v1/trading/leverage")) {
            return new DirectRoute("trading-leverage", true, false, false);
        }
        if (uri.startsWith("/api/v1/trading/")) {
            return new DirectRoute("trading", true, false, false);
        }
        if (uri.equals("/api/v1/instruments/admin/order-settings")) {
            return new DirectRoute("instrument-admin", true, true, false);
        }
        if (uri.startsWith("/api/v1/instruments/admin/")) {
            return new DirectRoute("instrument-admin", true, true, true);
        }
        if (uri.startsWith("/api/v1/instruments/")) {
            return new DirectRoute("instrument", false, false, true);
        }
        return null;
    }

    private Identity enforceIdentity(DirectRoute route, HttpServletRequest request, byte[] body) {
        if (!route.privateRoute() || !properties.getSecurity().isRequireIdentityForPrivateRoutes()) {
            return null;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            if (authorization != null && !authorization.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choose bearer or API key authentication");
            }
            String requiredScope;
            if (isReadMethod(request.getMethod())) {
                requiredScope = "READ";
            } else if (supportsTradeApiKey(route.service())) {
                requiredScope = "TRADE";
            } else {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key writes are not supported for this endpoint");
            }
            try {
                long user = apiKeys.authenticate(apiKeyRequests.bindNative(request, body), requiredScope);
                return new Identity(Long.toString(user), null, List.of(), false);
            } catch (ApiKeyAccessBlockedException blocked) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, blocked.getMessage(), blocked);
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, invalid.getMessage(), invalid);
            }
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (authService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "user auth service is not configured");
            }
            try {
                JwtPrincipal principal = authService.authenticateBearer(authorization);
                return new Identity(Long.toString(principal.userId()), principal.username(), principal.roles(), false);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
            } catch (IllegalStateException ex) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private route requires bearer token");
    }

    private Identity enforceAdminIdentity(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authService == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin route requires auth service");
        }
        try {
            JwtPrincipal principal = authService.authenticateBearer(authorization);
            if (principal.roles().stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
            }
            return new Identity(Long.toString(principal.userId()), principal.username(), principal.roles(), true);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private void enforceAdminPermission(String service, String method, Identity identity) {
        String action = isReadMethod(method) ? "read" : "write";
        String permission = "admin.gateway." + service + "." + action;
        try {
            authService.requireAdminPermission(Long.parseLong(identity.userId()), identity.roles(), permission);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private void enforceAdminApprovalIfRequired(String service, HttpServletRequest request,
                                                String bodyHash, Identity identity) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        if (adminOperations == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval repository is not available");
        }
        if (!adminOperations.requiresGatewayApproval(service, request.getMethod(), request.getRequestURI(), true)) {
            return;
        }
        String approvalHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalHeader == null || approvalHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            adminOperations.consumeGatewayApproval(approvalId, Long.parseLong(identity.userId()),
                    service, request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    bodyHash, Instant.now());
        } catch (AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private static void enforceQueryUserIdOwnership(HttpServletRequest request, Identity identity) {
        String requestedUserId = request.getParameter("userId");
        if (requestedUserId != null && !requestedUserId.isBlank() && !identity.userId().equals(requestedUserId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
        }
    }

    private HttpServletRequest wrapHeaders(HttpServletRequest request, ProductLine product, Identity identity) {
        Map<String, String> injected = new LinkedHashMap<>();
        String userIdHeader = properties.getSecurity().getUserIdHeader();
        injected.put(userIdHeader, null);
        injected.put("X-User-Id", null);
        injected.put("X-Admin-User-Id", null);
        injected.put("X-Admin-Username", null);
        injected.put("X-Admin-Roles", null);
        if (product != null) {
            injected.put("X-Product-Line", product.name());
        }
        if (identity != null) {
            if (identity.admin()) {
                injected.put("X-Admin-User-Id", identity.userId());
                if (identity.username() != null) injected.put("X-Admin-Username", identity.username());
                injected.put("X-Admin-Roles", String.join(",", identity.roles()));
            } else {
                injected.put(userIdHeader, identity.userId());
            }
        }
        return new InjectedHeadersRequestWrapper(request, injected);
    }

    private static byte[] cachedBody(HttpServletRequest request) {
        Object attr = request.getAttribute(ProductRequestBodyFilter.BODY);
        return attr instanceof byte[] bytes ? bytes : null;
    }

    private static boolean isReadMethod(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
    }

    private static boolean supportsTradeApiKey(String service) {
        return switch (service) {
            case "trading", "trading-orders", "trading-trigger", "trading-leverage", "account" -> true;
            default -> false;
        };
    }

    private static String bodySha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Long parseLong(String value) {
        try { return value == null ? null : Long.parseLong(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private static final class InjectedHeadersRequestWrapper extends HttpServletRequestWrapper {
        private final Map<String, String> overrides;

        InjectedHeadersRequestWrapper(HttpServletRequest request, Map<String, String> overrides) {
            super(request);
            this.overrides = new LinkedHashMap<>();
            overrides.forEach((k, v) -> this.overrides.put(k.toLowerCase(Locale.ROOT), v));
        }

        @Override
        public String getHeader(String name) {
            if (name != null) {
                String key = name.toLowerCase(Locale.ROOT);
                if (overrides.containsKey(key)) return overrides.get(key);
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (name != null) {
                String key = name.toLowerCase(Locale.ROOT);
                if (overrides.containsKey(key)) {
                    String overridden = overrides.get(key);
                    return overridden == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(overridden));
                }
            }
            return super.getHeaders(name);
        }
    }
}
