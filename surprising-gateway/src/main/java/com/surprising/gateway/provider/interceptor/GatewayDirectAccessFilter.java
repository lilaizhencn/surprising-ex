package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.support.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport.GatewayIdentity;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces gateway authentication, product-line isolation, admin permissions,
 * two-person approvals, and audit logging on direct in-process module controller routes.
 */
public class GatewayDirectAccessFilter extends OncePerRequestFilter {

    private static final String MATCHED_ROUTE_ATTR = GatewayDirectAccessFilter.class.getName() + ".route";

    private record DirectRoute(String service, boolean privateRoute, boolean admin, boolean shared) {}

    private static final DirectRoute ROUTE_PRICE_INDEX_PUBLIC = new DirectRoute("price-index", false, false, false);
    private static final DirectRoute ROUTE_PRICE_INDEX_ADMIN = new DirectRoute("price-index", true, true, false);
    private static final DirectRoute ROUTE_PRICE_FX_PUBLIC = new DirectRoute("price-fx", false, false, true);
    private static final DirectRoute ROUTE_PRICE_FX_ADMIN = new DirectRoute("price-fx", true, true, true);
    private static final DirectRoute ROUTE_PRICE_MARK_PUBLIC = new DirectRoute("price-mark", false, false, false);
    private static final DirectRoute ROUTE_PRICE_MARK_ADMIN = new DirectRoute("price-mark", true, true, false);
    private static final DirectRoute ROUTE_CANDLESTICK_PUBLIC = new DirectRoute("candlestick", false, false, false);
    private static final DirectRoute ROUTE_CANDLESTICK_ADMIN = new DirectRoute("candlestick", true, true, false);
    private static final DirectRoute ROUTE_FUNDING_PUBLIC = new DirectRoute("funding", false, false, false);
    private static final DirectRoute ROUTE_FUNDING_ADMIN = new DirectRoute("funding", true, true, false);
    private static final DirectRoute ROUTE_RISK_PRIVATE = new DirectRoute("risk", true, false, false);
    private static final DirectRoute ROUTE_RISK_ADMIN = new DirectRoute("risk-admin", true, true, false);
    private static final DirectRoute ROUTE_LIQUIDATION_PRIVATE = new DirectRoute("liquidation", true, false, false);
    private static final DirectRoute ROUTE_LIQUIDATION_ADMIN = new DirectRoute("liquidation-admin", true, true, false);
    private static final DirectRoute ROUTE_INSURANCE_PRIVATE = new DirectRoute("insurance", true, false, false);
    private static final DirectRoute ROUTE_INSURANCE_ADMIN = new DirectRoute("insurance-admin", true, true, false);
    private static final DirectRoute ROUTE_ADL_PRIVATE = new DirectRoute("adl", true, false, false);
    private static final DirectRoute ROUTE_ADL_ADMIN = new DirectRoute("adl", true, true, false);
    private static final DirectRoute ROUTE_WEBSOCKET_ADMIN = new DirectRoute("websocket-admin", true, true, true);
    private static final DirectRoute ROUTE_ACCOUNT_PRIVATE = new DirectRoute("account", true, false, false);
    private static final DirectRoute ROUTE_ACCOUNT_ADMIN = new DirectRoute("account", true, true, false);
    private static final DirectRoute ROUTE_TRADING_MARKET = new DirectRoute("trading-market", false, false, false);
    private static final DirectRoute ROUTE_TRADING_TRIGGER_PRIVATE = new DirectRoute("trading-trigger", true, false, false);
    private static final DirectRoute ROUTE_TRADING_TRIGGER_ADMIN = new DirectRoute("trading-trigger", true, true, false);
    private static final DirectRoute ROUTE_TRADING_FEES_PRIVATE = new DirectRoute("trading-fees", true, false, false);
    private static final DirectRoute ROUTE_TRADING_FEES_PUBLIC = new DirectRoute("trading-fees", false, false, true);
    private static final DirectRoute ROUTE_TRADING_FEES_ADMIN = new DirectRoute("trading-fees", true, true, false);
    private static final DirectRoute ROUTE_TRADING_LEVERAGE_PRIVATE = new DirectRoute("trading-leverage", true, false, false);
    private static final DirectRoute ROUTE_TRADING_LEVERAGE_ADMIN = new DirectRoute("trading-leverage", true, true, false);
    private static final DirectRoute ROUTE_TRADING_PRIVATE = new DirectRoute("trading", true, false, false);
    private static final DirectRoute ROUTE_TRADING_ADMIN = new DirectRoute("trading", true, true, false);
    private static final DirectRoute ROUTE_INSTRUMENT_ADMIN_PRODUCT = new DirectRoute("instrument-admin", true, true, false);
    private static final DirectRoute ROUTE_INSTRUMENT_ADMIN_SHARED = new DirectRoute("instrument-admin", true, true, true);
    private static final DirectRoute ROUTE_INSTRUMENT_PUBLIC = new DirectRoute("instrument", false, false, true);

    private final GatewayProperties properties;
    private final AuthService authService;
    private final GatewayApiKeyService apiKeys;
    private final ApiKeyRequestBinding apiKeyRequests;
    private final AdminApprovalService adminOperations;
    private final GatewayProductSelection productSelection;

    public GatewayDirectAccessFilter(GatewayProperties properties,
                                     AuthService authService,
                                     ObjectProvider<GatewayApiKeyService> apiKeys,
                                     ObjectProvider<AdminApprovalService> adminOperations,
                                     ObjectProvider<GatewayProductSelection> productSelection) {
        this.properties = properties;
        this.authService = authService;
        this.apiKeys = apiKeys.getIfAvailable();
        this.apiKeyRequests = new ApiKeyRequestBinding(properties);
        this.adminOperations = adminOperations.getIfAvailable();
        this.productSelection = productSelection.getIfAvailable();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        DirectRoute route = matchRoute(request.getRequestURI());
        if (route == null) {
            return true;
        }
        request.setAttribute(MATCHED_ROUTE_ATTR, route);
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        DirectRoute route = request.getAttribute(MATCHED_ROUTE_ATTR) instanceof DirectRoute cached
                ? cached
                : matchRoute(request.getRequestURI());
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }
        long startedNanos = System.nanoTime();
        byte[] body = cachedBody(request);
        GatewayIdentity identity = null;
        String bodyHash = null;
        HttpStatus failureStatus = null;
        String failureReason = null;
        try {
            ProductLine product = productSelection == null ? null
                    : productSelection.resolve(request, body, !route.shared());
            identity = route.admin()
                    ? GatewayAccessSupport.authenticateAdmin(authService, properties, request, "admin route requires auth service")
                    : enforceIdentity(route, request, body);
            if (route.admin()) {
                GatewayAccessSupport.enforceAdminPermission(authService, route.service(),
                        GatewayAccessSupport.isReadMethod(request.getMethod()), identity);
                bodyHash = GatewayAccessSupport.bodySha256(body, true);
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
            if (route.admin()) {
                int status = failureStatus != null ? failureStatus.value() : response.getStatus();
                boolean success = failureStatus == null && status < 400;
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
                GatewayAccessSupport.recordAdminOperation(adminOperations, identity, route.service(),
                        request.getMethod(), request, request.getRequestURI(), bodyHash, status,
                        elapsedMs, success, failureReason, request.getRemoteAddr());
            }
        }
    }

    private DirectRoute matchRoute(String uri) {
        if (uri == null || !uri.startsWith("/api/v1/")) return null;
        boolean hasAdminSegment = uri.contains("/admin/");
        if (uri.startsWith("/api/v1/price/index")) {
            return hasAdminSegment ? ROUTE_PRICE_INDEX_ADMIN : ROUTE_PRICE_INDEX_PUBLIC;
        }
        if (uri.startsWith("/api/v1/price/fx")) {
            return hasAdminSegment ? ROUTE_PRICE_FX_ADMIN : ROUTE_PRICE_FX_PUBLIC;
        }
        if (uri.startsWith("/api/v1/price/mark")) {
            return hasAdminSegment ? ROUTE_PRICE_MARK_ADMIN : ROUTE_PRICE_MARK_PUBLIC;
        }
        if (uri.startsWith("/api/v1/candlestick")) {
            return hasAdminSegment ? ROUTE_CANDLESTICK_ADMIN : ROUTE_CANDLESTICK_PUBLIC;
        }
        if (uri.startsWith("/api/v1/funding")) {
            return hasAdminSegment ? ROUTE_FUNDING_ADMIN : ROUTE_FUNDING_PUBLIC;
        }
        if (uri.startsWith("/api/v1/admin/risk")) {
            return ROUTE_RISK_ADMIN;
        }
        if (uri.startsWith("/api/v1/risk")) {
            return hasAdminSegment ? ROUTE_RISK_ADMIN : ROUTE_RISK_PRIVATE;
        }
        if (uri.startsWith("/api/v1/admin/liquidations")) {
            return ROUTE_LIQUIDATION_ADMIN;
        }
        if (uri.startsWith("/api/v1/liquidations")) {
            return hasAdminSegment ? ROUTE_LIQUIDATION_ADMIN : ROUTE_LIQUIDATION_PRIVATE;
        }
        if (uri.startsWith("/api/v1/insurance")) {
            return hasAdminSegment ? ROUTE_INSURANCE_ADMIN : ROUTE_INSURANCE_PRIVATE;
        }
        if (uri.startsWith("/api/v1/adl")) {
            return hasAdminSegment ? ROUTE_ADL_ADMIN : ROUTE_ADL_PRIVATE;
        }
        if (uri.startsWith("/api/v1/admin/websocket")) {
            return ROUTE_WEBSOCKET_ADMIN;
        }
        if (uri.startsWith("/api/v1/accounts/admin/") || uri.startsWith("/api/v1/admin/accounts/")) {
            return ROUTE_ACCOUNT_ADMIN;
        }
        if (uri.startsWith("/api/v1/accounts/")) {
            return ROUTE_ACCOUNT_PRIVATE;
        }
        if (uri.startsWith("/api/v1/trading/market/")) {
            return ROUTE_TRADING_MARKET;
        }
        if (uri.startsWith("/api/v1/admin/trading/trigger-orders")) {
            return ROUTE_TRADING_TRIGGER_ADMIN;
        }
        if (uri.startsWith("/api/v1/admin/trading/fees")) {
            return ROUTE_TRADING_FEES_ADMIN;
        }
        if (uri.startsWith("/api/v1/admin/trading/leverage")) {
            return ROUTE_TRADING_LEVERAGE_ADMIN;
        }
        if (uri.startsWith("/api/v1/admin/trading/")) {
            return ROUTE_TRADING_ADMIN;
        }
        if (uri.startsWith("/api/v1/trading/trigger-orders")) {
            return ROUTE_TRADING_TRIGGER_PRIVATE;
        }
        if (uri.equals("/api/v1/trading/fees/vip/levels")) {
            return ROUTE_TRADING_FEES_PUBLIC;
        }
        if (uri.startsWith("/api/v1/trading/fees")) {
            return ROUTE_TRADING_FEES_PRIVATE;
        }
        if (uri.startsWith("/api/v1/trading/leverage")) {
            return ROUTE_TRADING_LEVERAGE_PRIVATE;
        }
        if (uri.startsWith("/api/v1/trading/")) {
            return ROUTE_TRADING_PRIVATE;
        }
        if (uri.equals("/api/v1/instruments/admin/order-settings")) {
            return ROUTE_INSTRUMENT_ADMIN_PRODUCT;
        }
        if (uri.startsWith("/api/v1/instruments/admin/")) {
            return ROUTE_INSTRUMENT_ADMIN_SHARED;
        }
        if (uri.startsWith("/api/v1/instruments/")) {
            return ROUTE_INSTRUMENT_PUBLIC;
        }
        return null;
    }

    private GatewayIdentity enforceIdentity(DirectRoute route, HttpServletRequest request, byte[] body) {
        if (!route.privateRoute() || !properties.getSecurity().isRequireIdentityForPrivateRoutes()) {
            return null;
        }
        String internalToken = request.getHeader("X-Internal-Service-Token");
        String expectedSecret = properties.getSecurity().getInternalServiceToken();
        if (("trading".equals(route.service()) || "account".equals(route.service()))
                && !route.admin()
                && internalToken != null && !internalToken.isBlank()
                && expectedSecret != null && !expectedSecret.isBlank()
                && com.surprising.gateway.provider.support.Sha256Digests.constantTimeEquals(
                        internalToken, expectedSecret)) {
            return null;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            if (authorization != null && !authorization.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choose bearer or API key authentication");
            }
            String requiredScope;
            if (GatewayAccessSupport.isReadMethod(request.getMethod())) {
                requiredScope = "READ";
            } else if (supportsTradeApiKey(route.service())) {
                requiredScope = "TRADE";
            } else {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key writes are not supported for this endpoint");
            }
            return GatewayAccessSupport.authenticateApiKey(apiKeys, apiKeyRequests, request, body, requiredScope);
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            GatewayIdentity identity = GatewayAccessSupport.authenticateBearerUser(authService, authorization);
            enforceSubAccountWrite(route, request, identity);
            return identity;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private route requires bearer token");
    }

    private void enforceAdminApprovalIfRequired(String service, HttpServletRequest request,
                                                String bodyHash, GatewayIdentity identity) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        if (adminOperations == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval repository is not available");
        }
        if (!adminOperations.requiresGatewayApproval(service, request.getMethod(), request.getRequestURI(), true)) {
            return;
        }
        GatewayAccessSupport.consumeAdminApproval(adminOperations, properties, service,
                request.getMethod(), request, bodyHash, identity);
    }

    private static void enforceQueryUserIdOwnership(HttpServletRequest request, GatewayIdentity identity) {
        String query = request.getQueryString();
        if (query == null || !query.contains("userId")) {
            return;
        }
        String requestedUserId = request.getParameter("userId");
        if (requestedUserId != null && !requestedUserId.isBlank() && !identity.userId().equals(requestedUserId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
        }
    }

    private HttpServletRequest wrapHeaders(HttpServletRequest request, ProductLine product, GatewayIdentity identity) {
        String userIdHeader = properties.getSecurity().getUserIdHeader();
        String productLine = product != null ? product.name() : null;
        String userId = null;
        String adminUserId = null;
        String adminUsername = null;
        String adminRoles = null;
        if (identity != null) {
            if (identity.admin()) {
                adminUserId = identity.userId();
                adminUsername = identity.username();
                adminRoles = String.join(",", identity.roles());
            } else {
                userId = identity.userId();
            }
        }
        return new InjectedHeadersRequestWrapper(
                request, userIdHeader, userId, adminUserId, adminUsername, adminRoles, productLine);
    }

    private static byte[] cachedBody(HttpServletRequest request) {
        Object attr = request.getAttribute(ProductRequestBodyFilter.BODY);
        return attr instanceof byte[] bytes ? bytes : null;
    }

    private void enforceSubAccountWrite(DirectRoute route, HttpServletRequest request, GatewayIdentity identity) {
        if (identity == null || authService == null || GatewayAccessSupport.isReadMethod(request.getMethod())) return;
        if (!supportsTradeApiKey(route.service())) return;
        try {
            authService.requireSubAccountWrite(Long.parseLong(identity.userId()));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    private static boolean supportsTradeApiKey(String service) {
        return switch (service) {
            case "trading", "trading-orders", "trading-trigger", "trading-leverage", "account" -> true;
            default -> false;
        };
    }

    private static final class InjectedHeadersRequestWrapper extends HttpServletRequestWrapper {
        private static final String UNMATCHED = "\u0000UNMATCHED";

        private final String userIdHeader;
        private final String userId;
        private final String adminUserId;
        private final String adminUsername;
        private final String adminRoles;
        private final String productLine;

        InjectedHeadersRequestWrapper(
                HttpServletRequest request,
                String userIdHeader,
                String userId,
                String adminUserId,
                String adminUsername,
                String adminRoles,
                String productLine) {
            super(request);
            this.userIdHeader = userIdHeader;
            this.userId = userId;
            this.adminUserId = adminUserId;
            this.adminUsername = adminUsername;
            this.adminRoles = adminRoles;
            this.productLine = productLine;
        }

        private String resolveOverride(String name) {
            if (name == null) {
                return UNMATCHED;
            }
            if (name.equalsIgnoreCase(userIdHeader)) {
                return userId;
            }
            if (name.equalsIgnoreCase("x-user-id")) {
                return null;
            }
            if (name.equalsIgnoreCase("x-admin-user-id")) {
                return adminUserId;
            }
            if (name.equalsIgnoreCase("x-admin-username")) {
                return adminUsername;
            }
            if (name.equalsIgnoreCase("x-admin-roles")) {
                return adminRoles;
            }
            if (productLine != null && name.equalsIgnoreCase("x-product-line")) {
                return productLine;
            }
            return UNMATCHED;
        }

        @Override
        public String getHeader(String name) {
            String resolved = resolveOverride(name);
            return resolved != UNMATCHED ? resolved : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String resolved = resolveOverride(name);
            if (resolved != UNMATCHED) {
                return resolved == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(resolved));
            }
            return super.getHeaders(name);
        }
    }
}
