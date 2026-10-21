package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.model.ProductTransferCommand;
import com.surprising.gateway.provider.model.ProductTransferWireRequest;


import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import com.surprising.gateway.provider.service.ProductTransferSecurityService;
import com.surprising.gateway.provider.model.ProductTransferResult;
import com.surprising.gateway.provider.model.ProductTransferStatus;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import org.springframework.web.bind.annotation.*;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 绑定网关HTTP协议与路由，委托认证/审批/审计和交易业务，转换HTTP结果。
 */
@RestController
public class GatewayProxyController {

    public static final String GATEWAY_PREFIX = "/api/v1/gateway";
    public static final String ADMIN_GATEWAY_PREFIX = "/api/v1/admin/gateway";
    private static final List<String> FORWARDED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Request-Id",
            "X-User-Id",
            "X-Product-Line",
            "X-Account-Type",
            "X-Contract-Type",
            "X-Forwarded-For");

    private final com.surprising.gateway.provider.product.GatewayProductServices products;
    private final com.surprising.gateway.provider.local.LocalBusinessApi sharedBusinessApi;
    private final com.surprising.gateway.provider.product.GatewayProductSelection productSelection;

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final AdminApprovalService adminOperations;
    private final ObjectMapper objectMapper;
    private final ProductTransferCoordinator productTransferCoordinator;
    private final ProductTransferSecurityService productTransferSecurityService;

    @Autowired
    public GatewayProxyController(GatewayProperties properties,
                               RestTemplate restTemplate,
                               AuthService authService,
                               AdminApprovalService adminOperations,
                               ObjectMapper objectMapper,
                               ProductTransferCoordinator productTransferCoordinator,
                               ProductTransferSecurityService productTransferSecurityService,
                               com.surprising.gateway.provider.product.GatewayProductServices products,
                               com.surprising.gateway.provider.local.LocalBusinessApi sharedBusinessApi,
                               com.surprising.gateway.provider.product.GatewayProductSelection productSelection) {
        this.products = java.util.Objects.requireNonNull(products, "products");
        this.sharedBusinessApi = java.util.Objects.requireNonNull(sharedBusinessApi, "sharedBusinessApi");
        this.productSelection = java.util.Objects.requireNonNull(productSelection, "productSelection");
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.authService = authService;
        this.adminOperations = adminOperations;
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.productTransferCoordinator = productTransferCoordinator;
        this.productTransferSecurityService = productTransferSecurityService;
    }

    @GetMapping("/api/v1/gateway/runtime")
    public Map<String, List<ProductLine>> runtime() {
        return Map.of("productLines", products.enabled(), "disabledProductLines", products.disabled());
    }

    @RequestMapping(path = {GATEWAY_PREFIX + "/{service}", GATEWAY_PREFIX + "/{service}/**",
            ADMIN_GATEWAY_PREFIX + "/{service}", ADMIN_GATEWAY_PREFIX + "/{service}/**"})
    public ResponseEntity<byte[]> proxy(@PathVariable String service,
                                        HttpMethod method,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        long startedNanos = System.nanoTime();
        boolean adminRequest = isAdminRequest(request);
        GatewayProperties.BackendRoute route = !adminRequest && method == HttpMethod.POST && isProductTransferRequest(service, request)
                ? route(service, false) : resolveRoute(service, adminRequest, request, body);
        GatewayIdentity identity = adminRequest ? enforceAdminIdentity(request) : enforceIdentity(route, request);
        if (adminRequest) {
            enforceAdminPermission(service, method, identity);
        }
        enforceUserStatusRestrictions(service, method, request, identity);
        if (!adminRequest && method == HttpMethod.POST && isProductTransferRequest(service, request)) {
            return transferResponse(identity, request, body);
        }
        URI target = targetUri(service, route, request);
        String bodyHash = adminRequest ? bodySha256(body) : null;
        ResponseEntity<byte[]> response;
        try {
            enforceAdminApprovalIfRequired(service, method, request, bodyHash, identity);
            response = exchange(service, target, method, body, request, identity, route);
            recordAdminOperation(service, method, request, body, identity, target,
                    bodyHash, response.getStatusCode().value(), elapsedMillis(startedNanos), true, null);
        } catch (ResponseStatusException ex) {
            recordAdminOperation(service, method, request, body, identity, target,
                    bodyHash, ex.getStatusCode().value(), elapsedMillis(startedNanos), false, ex.getReason());
            throw ex;
        } catch (RuntimeException ex) {
            recordAdminOperation(service, method, request, body, identity, target,
                    bodyHash, HttpStatus.INTERNAL_SERVER_ERROR.value(), elapsedMillis(startedNanos), false, ex.getMessage());
            throw ex;
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        if (response.getHeaders().getContentType() != null) {
            responseHeaders.setContentType(response.getHeaders().getContentType());
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(responseHeaders)
                .body(sanitizePublicContractFields(service, response.getBody()));
    }

    public ResponseEntity<byte[]> proxyCompat(String service,
                                              String targetSuffix,
                                              String targetQuery,
                                              HttpMethod method,
                                              HttpServletRequest request,
                                              byte[] body,
                                              Long authenticatedUserId) {
        GatewayProperties.BackendRoute route = method == HttpMethod.POST && "account".equalsIgnoreCase(service) && "/transfers".equals(targetSuffix)
                ? route(service, false) : resolveRoute(service, false, request, body);
        GatewayIdentity identity = authenticatedUserId == null
                ? enforceIdentity(route, request)
                : userIdentity(Long.toString(authenticatedUserId));
        enforceUserStatusRestrictions(service, method, request, identity);
        if (method == HttpMethod.POST && "account".equalsIgnoreCase(service)
                && "/transfers".equalsIgnoreCase(targetSuffix)) {
            return transferResponse(identity, request, body);
        }
        URI target = compatibilityTarget(route, targetSuffix, targetQuery);
        ResponseEntity<byte[]> response = exchange(service, target, method, body, request, identity, route);
        HttpHeaders responseHeaders = new HttpHeaders();
        if (response.getHeaders().getContentType() != null) {
            responseHeaders.setContentType(response.getHeaders().getContentType());
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(responseHeaders)
                .body(sanitizePublicContractFields(service, response.getBody()));
    }

    private boolean isProductTransferRequest(String service, HttpServletRequest request) {
        return "account".equalsIgnoreCase(service)
                && request.getRequestURI().endsWith("/account/transfers");
    }

    private ResponseEntity<byte[]> transferResponse(GatewayIdentity identity,
                                                     HttpServletRequest request,
                                                     byte[] body) {
        if (productTransferCoordinator == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "product transfer coordinator is not configured");
        }
        try {
            var input = transferRequest(body);
            var amount = transferAmount(input);
            if (productTransferSecurityService != null) {
                productTransferSecurityService.requireIfNeeded(Long.parseLong(identity.userId()), amount,
                        request.getHeader("X-Security-Email-Code"),
                        request.getHeader("X-Security-TOTP-Code"), Instant.now());
            }
            ProductTransferResult result = productTransferCoordinator.transfer(transferCommand(
                    Long.parseLong(identity.userId()),input,amount,request.getHeader("Idempotency-Key")));
            HttpStatus status = result.status() == ProductTransferStatus.COMPLETED
                    ? HttpStatus.OK
                    : result.status().terminal() ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(result));
        } catch (ProductUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, unavailable.getMessage(), unavailable);
        } catch (com.surprising.gateway.provider.exception.ProductTransferSecurityException rejected) {
            throw new ResponseStatusException(switch (rejected.reason()) {
                case VALUATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case VERIFICATION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            }, rejected.getMessage(), rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private ProductTransferWireRequest transferRequest(byte[] body) {
        if (body == null || body.length == 0) throw new IllegalArgumentException("transfer request body is required");
        try {
            var input = objectMapper.readValue(body,ProductTransferWireRequest.class);
            if (input == null) throw new IllegalArgumentException("transfer request body is invalid");
            return input;
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("transfer request body is invalid",invalid);
        }
    }

    private com.surprising.gateway.provider.model.ProductTransferAmount transferAmount(ProductTransferWireRequest input) {
        String asset = input.asset() == null ? "" : input.asset().trim().toUpperCase(Locale.ROOT);
        if (asset.isBlank()) throw new IllegalArgumentException("transfer asset is required");
        BigDecimal units = input.amountUnits();
        if (units == null || units.signum() <= 0 || units.scale() > 0)
            throw new IllegalArgumentException("transfer amountUnits must be a positive integer");
        if (!asset.matches("[A-Z0-9]{1,20}")) throw new IllegalArgumentException("asset is invalid");
        try {
            return new com.surprising.gateway.provider.model.ProductTransferAmount(asset,units.longValueExact());
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("transfer amount is invalid",invalid);
        }
    }

    private ProductTransferCommand transferCommand(long userId, ProductTransferWireRequest request, com.surprising.gateway.provider.model.ProductTransferAmount amount, String suppliedKey) {
        String key = suppliedKey == null || suppliedKey.isBlank() ? request.referenceId() : suppliedKey;
        if (userId <= 0 || request.recipientUserId() != null && request.recipientUserId() <= 0)
            throw new IllegalArgumentException("userId is required");
        if (key == null || !key.trim().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("idempotency key must be 1-128 safe characters");
        if (request.referenceId() == null || request.referenceId().isBlank() || request.referenceId().trim().length() > 128)
            throw new IllegalArgumentException("amount and referenceId are invalid");
        return new ProductTransferCommand(userId,key,transferAccountType(request.sourceAccountType()),transferAccountType(request.targetAccountType()),
                request.asset(),amount.amountUnits(),request.referenceId(),request.reason(),request.recipientUserId() == null ? userId : request.recipientUserId());
    }

    private com.surprising.account.api.model.AccountType transferAccountType(String value) {
        try { return com.surprising.account.api.model.AccountType.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("unsupported account type: " + value,invalid); }
    }

    private ResponseEntity<byte[]> exchange(String service, URI target,
                                            HttpMethod method,
                                            byte[] body,
                                            HttpServletRequest request,
                                            GatewayIdentity identity,
                                            GatewayProperties.BackendRoute route) {
        if (com.surprising.gateway.provider.local.LocalBusinessApi.isLocalService(service)) {
            HttpHeaders trustedHeaders = headers(request, identity, route);
            ProductLine product = productSelection.resolve(request, body, !isSharedLocalService(service, request));
            if (product != null) trustedHeaders.set("X-Product-Line", product.name());
            else trustedHeaders.remove("X-Product-Line");
            var local = isSharedLocalService(service, request) ? sharedBusinessApi : products.local(product);
            return local.invoke(service, target, method, trustedHeaders, body,
                    properties.getHttpClient().getReadTimeout());
        }
        try {
            return restTemplate.exchange(target, method, new HttpEntity<>(body, headers(request, identity, route)),
                    byte[].class);
        } catch (ProductUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, unavailable.getMessage(), unavailable);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "backend request timed out", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "backend request failed", ex);
        }
    }

    /**
     * 网关是唯一面向客户端的代理边界。内部 DTO 仍保留规格标识供撮合、风控和恢复使用，
     * 但不把这个内部生命周期字段泄露到公共 API。
     */
    private byte[] sanitizePublicContractFields(String service,
                                                byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        boolean instrumentResponse = normalizedService.equals("instrument")
                || normalizedService.equals("instrument-admin");
        if (!instrumentResponse && !containsJsonField(body, "instrumentChangeId")
                && !containsJsonField(body, "commandResultUrl")) {
            return body;
        }
        try {
            Object value = objectMapper.readValue(body, Object.class);
            removeInternalContractFields(value, instrumentResponse);
            return objectMapper.writeValueAsBytes(value);
        } catch (JacksonException | IllegalArgumentException ex) {
            // 非 JSON 响应或后端返回错误正文时保持原始内容，不能因脱敏失败改变业务响应。
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private void removeInternalContractFields(Object value, boolean instrumentResponse) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.remove("instrumentChangeId");
            if (mutable.get("commandResultUrl") instanceof String url
                    && url.startsWith("/api/v1/trading/orders/commands/")) {
                mutable.put("commandResultUrl", GATEWAY_PREFIX + "/trading/commands/"
                        + url.substring("/api/v1/trading/orders/commands/".length()));
            }
            if (instrumentResponse) {
                mutable.remove("version");
                stringifyUnitField(mutable, "priceTickUnits");
                stringifyUnitField(mutable, "quantityStepUnits");
                stringifyUnitField(mutable, "strikePriceUnits");
            }
            mutable.values().forEach(child -> removeInternalContractFields(child, instrumentResponse));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> removeInternalContractFields(child, instrumentResponse));
        }
    }

    private void stringifyUnitField(Map<Object, Object> value, String fieldName) {
        Object field = value.get(fieldName);
        if (field instanceof Number || field instanceof String) {
            value.put(fieldName, String.valueOf(field));
        }
    }

    private boolean containsJsonField(byte[] body, String fieldName) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return text.contains("\"" + fieldName + "\"");
    }

    public URI targetUri(String service, GatewayProperties.BackendRoute route, HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String servicePrefix = gatewayPrefix(request) + "/" + service;
        String suffix = requestUri.length() > servicePrefix.length()
                ? requestUri.substring(servicePrefix.length())
                : "";
        String base = trimTrailingSlash(route.getBaseUrl());
        String prefix = ensureLeadingSlash(route.getTargetPrefix());
        StringBuilder target = new StringBuilder(base).append(prefix).append(suffix);
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            target.append('?').append(request.getQueryString());
        }
        return URI.create(target.toString());
    }

    private URI compatibilityTarget(GatewayProperties.BackendRoute route,
                                    String targetSuffix,
                                    String targetQuery) {
        String base = trimTrailingSlash(route.getBaseUrl());
        String prefix = ensureLeadingSlash(route.getTargetPrefix());
        String suffix = targetSuffix == null ? "" : ensureLeadingSlash(targetSuffix);
        StringBuilder target = new StringBuilder(base).append(prefix).append(suffix);
        if (targetQuery != null && !targetQuery.isBlank()) {
            target.append('?').append(targetQuery);
        }
        return URI.create(target.toString());
    }

    private GatewayProperties.BackendRoute route(String service, boolean adminRequest) {
        String normalized = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        GatewayProperties.BackendRoute route = (adminRequest ? properties.getAdminRoutes() : properties.getRoutes())
                .get(normalized);
        if (route == null) {
            String routeType = adminRequest ? "admin gateway service" : "gateway service";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown " + routeType + ": " + service);
        }
        return route;
    }

    private boolean isSharedLocalService(String service, HttpServletRequest request) {
        return java.util.Set.of("instrument", "instrument-admin", "websocket-admin").contains(service.toLowerCase(Locale.ROOT))
                && !request.getRequestURI().endsWith("/order-settings");
    }

    private GatewayProperties.BackendRoute resolveRoute(String service, boolean admin,
                                                         HttpServletRequest request, byte[] body) {
        GatewayProperties.BackendRoute configured = route(service, admin);
        if (com.surprising.gateway.provider.local.LocalBusinessApi.isLocalService(service)) {
            ProductLine requested = productSelection.resolve(request, body, !isSharedLocalService(service, request));
            if (requested != null && !isSharedLocalService(service, request)) products.local(requested).validateProductSelectors(request, body);
            return configured;
        }
        return resolveProductRoute(configured, request, body);
    }

    private GatewayProperties.BackendRoute resolveProductRoute(GatewayProperties.BackendRoute route,
                                                               HttpServletRequest request,
                                                               byte[] body) {
        if (route == null || !route.hasProductRoutes()) {
            return route;
        }
        ProductLine productLine = productSelection.resolve(request, body, true);
        if (productLine == null) {
            return route;
        }
        var endpoint = route.getProductRoutes().get(productLine);
        if (products.enabled().size() > 1 && (endpoint == null || endpoint.getBaseUrl() == null
                || endpoint.getBaseUrl().isBlank())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "product route is not configured: " + productLine.name());
        }
        GatewayProperties.BackendRoute resolved = route.resolve(productLine);
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "product route is not configured: " + productLine.name());
        }
        return resolved;
    }

    private GatewayIdentity enforceIdentity(GatewayProperties.BackendRoute route, HttpServletRequest request) {
        if (!route.isPrivateRoute() || !properties.getSecurity().isRequireIdentityForPrivateRoutes()) {
            return null;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (authService == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "user auth service is not configured");
            }
            try {
                JwtPrincipal principal = authService.authenticateBearer(authorization);
                return new GatewayIdentity(Long.toString(principal.userId()), principal.username(),
                        principal.status(), principal.roles(), false);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
            } catch (IllegalStateException ex) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private gateway route requires bearer token");
    }

    private GatewayIdentity enforceAdminIdentity(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authService == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin gateway route requires auth service");
        }
        try {
            JwtPrincipal principal = authService.authenticateBearer(authorization);
            if (principal.roles().stream().noneMatch(properties.getSecurity().getAdminRoles()::contains)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
            }
            return new GatewayIdentity(Long.toString(principal.userId()), principal.username(), principal.status(),
                    principal.roles(), true);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private HttpHeaders headers(HttpServletRequest request,
                                GatewayIdentity identity,
                                GatewayProperties.BackendRoute route) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : FORWARDED_HEADERS) {
            String value = request.getHeader(name);
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    && route != null
                    && route.hasBasicAuth()) {
                value = null;
            }
            if (properties.getSecurity().getUserIdHeader().equalsIgnoreCase(name)
                    && identity != null
                    && !identity.admin()) {
                value = identity.userId();
            }
            if (value != null && !value.isBlank()) {
                headers.add(name, value);
            }
        }
        if (identity != null && identity.admin()) {
            headers.set("X-Admin-User-Id", identity.userId());
            headers.set("X-Admin-Username", identity.username());
            headers.set("X-Admin-Roles", String.join(",", identity.roles()));
        }
        if (route != null && route.hasBasicAuth()) {
            headers.setBasicAuth(route.getBasicAuthUsername(), route.getBasicAuthPassword());
        }
        return headers;
    }

    private void enforceAdminApprovalIfRequired(String service,
                                                HttpMethod method,
                                                HttpServletRequest request,
                                                String bodyHash,
                                                GatewayIdentity identity) {
        if (!requiresHighRiskAdminApproval(service, method, request)) {
            return;
        }
        if (identity == null || !identity.admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity required");
        }
        if (adminOperations == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval repository is not available");
        }
        String approvalIdHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalIdHeader == null || approvalIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            adminOperations.consumeGatewayApproval(
                    approvalId,
                    Long.parseLong(identity.userId()),
                    service,
                    method == null ? "GET" : method.name(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodyHash,
                    Instant.now());
        } catch (AdminApprovalService.AdminApprovalRequiredException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
        }
    }

    private void enforceAdminPermission(String service, HttpMethod method, GatewayIdentity identity) {
        if (identity == null || !identity.admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity required");
        }
        try {
            authService.requireAdminPermission(
                    Long.parseLong(identity.userId()),
                    identity.roles(),
                    adminGatewayPermission(service, method));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private String adminGatewayPermission(String service, HttpMethod method) {
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        if (!normalizedService.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid admin gateway service");
        }
        String action = method == null || isReadMethod(method) ? "read" : "write";
        return "admin.gateway." + normalizedService + "." + action;
    }

    private void enforceUserStatusRestrictions(String service,
                                               HttpMethod method,
                                               HttpServletRequest request,
                                               GatewayIdentity identity) {
        if (identity == null || identity.admin() || method == null || isReadMethod(method)) {
            return;
        }
        try {
            authService.requireUserGatewayWrite(identity.status(),isTradingWrite(service,request),isWalletWithdrawWrite(service,request));
        } catch (IllegalStateException forbidden) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,forbidden.getMessage(),forbidden);
        }
    }

    private boolean isReadMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method) || HttpMethod.OPTIONS.equals(method);
    }

    private boolean isTradingWrite(String service, HttpServletRequest request) {
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        if (!List.of("trading", "trading-trigger").contains(normalizedService)) {
            return false;
        }
        String path = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase(Locale.ROOT);
        return !path.endsWith("/cancel");
    }

    private boolean isWalletWithdrawWrite(String service, HttpServletRequest request) {
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        String path = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase(Locale.ROOT);
        return "wallet".equals(normalizedService) && path.contains("/withdraw");
    }

    public boolean requiresHighRiskAdminApproval(String service, HttpMethod method, HttpServletRequest request) {
        if (!isAdminRequest(request)) return false;
        if (adminOperations == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"admin approval service is not available");
        return adminOperations.requiresGatewayApproval(service,method == null ? null : method.name(),request.getRequestURI(),true);
    }

    private void recordAdminOperation(String service,
                                      HttpMethod method,
                                      HttpServletRequest request,
                                      byte[] body,
                                      GatewayIdentity identity,
                                      URI target,
                                      String bodyHash,
                                      Integer responseStatus,
                                      Long durationMs,
                                      boolean success,
                                      String errorMessage) {
        if (adminOperations == null || identity == null || !identity.admin()) {
            return;
        }
        adminOperations.recordGatewayOperation(new AdminOperationRecord(
                parseLong(identity.userId()),
                identity.username(),
                identity.roles(),
                service == null ? "unknown" : service,
                method == null ? "GET" : method.name(),
                request.getRequestURI(),
                request.getQueryString(),
                target == null ? null : target.toString(),
                bodyHash,
                responseStatus,
                durationMs,
                success,
                errorMessage,
                request.getHeader(HttpHeaders.USER_AGENT),
                clientIp(request),
                Instant.now()));
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String bodySha256(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private GatewayIdentity userIdentity(String userId) {
        return userId == null ? null : new GatewayIdentity(userId, null, null, List.of(), false);
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().startsWith(ADMIN_GATEWAY_PREFIX + "/");
    }

    private String gatewayPrefix(HttpServletRequest request) {
        return isAdminRequest(request) ? ADMIN_GATEWAY_PREFIX : GATEWAY_PREFIX;
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

    private record GatewayIdentity(String userId, String username, String status, List<String> roles, boolean admin) {
    }
}
