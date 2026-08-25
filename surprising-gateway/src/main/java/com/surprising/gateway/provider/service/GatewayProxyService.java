package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import com.surprising.gateway.provider.auth.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.AdminAuditRepository;
import com.surprising.gateway.provider.auth.AdminAuditRepository.AdminOperationRecord;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 执行网关路由解析、身份校验、后台审批、审计记录和后端请求转发。
 */
@Service
public class GatewayProxyService {

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
            "X-Forwarded-For",
            GatewayTraceFilter.TRACE_ID_HEADER);

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final AdminAuditRepository adminAuditRepository;
    private final AdminApprovalRepository adminApprovalRepository;
    private final ObjectMapper objectMapper;
    private final ProductTransferCoordinator productTransferCoordinator;
    private final ProductTransferSecurityService productTransferSecurityService;

    public GatewayProxyService(GatewayProperties properties, RestTemplate restTemplate) {
        this(properties, restTemplate, null, null, null, new ObjectMapper(), null);
    }

    public GatewayProxyService(GatewayProperties properties, RestTemplate restTemplate, AuthService authService) {
        this(properties, restTemplate, authService, null, null, new ObjectMapper(), null);
    }

    public GatewayProxyService(GatewayProperties properties,
                               RestTemplate restTemplate,
                               AuthService authService,
                               AdminAuditRepository adminAuditRepository,
                               AdminApprovalRepository adminApprovalRepository) {
        this(properties, restTemplate, authService, adminAuditRepository, adminApprovalRepository,
                new ObjectMapper(), null);
    }

    public GatewayProxyService(GatewayProperties properties,
                               RestTemplate restTemplate,
                               AuthService authService,
                               AdminAuditRepository adminAuditRepository,
                               AdminApprovalRepository adminApprovalRepository,
                               ObjectMapper objectMapper) {
        this(properties, restTemplate, authService, adminAuditRepository, adminApprovalRepository,
                objectMapper, null);
    }

    public GatewayProxyService(GatewayProperties properties,
                               RestTemplate restTemplate,
                               AuthService authService,
                               AdminAuditRepository adminAuditRepository,
                               AdminApprovalRepository adminApprovalRepository,
                               ObjectMapper objectMapper,
                               ProductTransferCoordinator productTransferCoordinator) {
        this(properties, restTemplate, authService, adminAuditRepository, adminApprovalRepository,
                objectMapper, productTransferCoordinator, null);
    }

    @Autowired
    public GatewayProxyService(GatewayProperties properties,
                               RestTemplate restTemplate,
                               AuthService authService,
                               AdminAuditRepository adminAuditRepository,
                               AdminApprovalRepository adminApprovalRepository,
                               ObjectMapper objectMapper,
                               ProductTransferCoordinator productTransferCoordinator,
                               ProductTransferSecurityService productTransferSecurityService) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.authService = authService;
        this.adminAuditRepository = adminAuditRepository;
        this.adminApprovalRepository = adminApprovalRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.productTransferCoordinator = productTransferCoordinator;
        this.productTransferSecurityService = productTransferSecurityService;
    }

    public ResponseEntity<byte[]> proxy(String service,
                                        HttpMethod method,
                                        HttpServletRequest request,
                                        byte[] body) {
        long startedNanos = System.nanoTime();
        boolean adminRequest = isAdminRequest(request);
        GatewayProperties.BackendRoute route = resolveProductRoute(route(service, adminRequest), request, body);
        GatewayIdentity identity = adminRequest ? enforceAdminIdentity(request) : enforceIdentity(route, request);
        if (adminRequest) {
            enforceAdminPermission(service, method, identity);
        }
        enforceUserStatusRestrictions(service, method, request, identity);
        if (!adminRequest && method == HttpMethod.POST && isProductTransferRequest(service, request)) {
            return transferResponse(identity, request, body);
        }
        URI target = targetUri(service, route, request);
        String bodyHash = bodySha256(body);
        ResponseEntity<byte[]> response;
        try {
            enforceAdminApprovalIfRequired(service, method, request, bodyHash, identity);
            response = exchange(target, method, body, request, identity, route);
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
        GatewayProperties.BackendRoute route = resolveProductRoute(route(service, false), request, body);
        GatewayIdentity identity = authenticatedUserId == null
                ? enforceIdentity(route, request)
                : userIdentity(Long.toString(authenticatedUserId));
        enforceUserStatusRestrictions(service, method, request, identity);
        if (method == HttpMethod.POST && "account".equalsIgnoreCase(service)
                && "/transfers".equalsIgnoreCase(targetSuffix)) {
            return transferResponse(identity, request, body);
        }
        URI target = compatibilityTarget(route, targetSuffix, targetQuery);
        ResponseEntity<byte[]> response = exchange(target, method, body, request, identity, route);
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
            if (productTransferSecurityService != null) {
                productTransferSecurityService.requireIfNeeded(Long.parseLong(identity.userId()), body,
                        request.getHeader("X-Security-Email-Code"),
                        request.getHeader("X-Security-TOTP-Code"), Instant.now());
            }
            ProductTransferResult result = productTransferCoordinator.transferJson(
                    Long.parseLong(identity.userId()), body, request.getHeader("Idempotency-Key"), objectMapper);
            HttpStatus status = result.status() == ProductTransferStatus.COMPLETED
                    ? HttpStatus.OK
                    : result.status().terminal() ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(result));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private ResponseEntity<byte[]> exchange(URI target,
                                            HttpMethod method,
                                            byte[] body,
                                            HttpServletRequest request,
                                            GatewayIdentity identity,
                                            GatewayProperties.BackendRoute route) {
        try {
            return restTemplate.exchange(target, method, new HttpEntity<>(body, headers(request, identity, route)),
                    byte[].class);
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
        if (!instrumentResponse && !containsJsonField(body, "instrumentVersion")) {
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
            mutable.remove("instrumentVersion");
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

    private GatewayProperties.BackendRoute resolveProductRoute(GatewayProperties.BackendRoute route,
                                                               HttpServletRequest request,
                                                               byte[] body) {
        if (route == null || !route.hasProductRoutes()) {
            return route;
        }
        ProductLine productLine = productLine(request, body);
        if (productLine == null) {
            return route;
        }
        GatewayProperties.BackendRoute resolved = route.resolve(productLine);
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "product route is not configured: " + productLine.name());
        }
        return resolved;
    }

    private ProductLine productLine(HttpServletRequest request, byte[] body) {
        String value = firstNonBlank(
                request.getHeader("X-Product-Line"),
                request.getHeader("X-Account-Type"),
                request.getHeader("X-Contract-Type"),
                request.getParameter("productLine"),
                request.getParameter("product-line"),
                request.getParameter("product_line"),
                request.getParameter("accountType"),
                request.getParameter("account-type"),
                request.getParameter("account_type"),
                request.getParameter("contractType"),
                request.getParameter("contract-type"),
                request.getParameter("contract_type"),
                bodyProductLine(body),
                pathProductLine(request.getRequestURI()));
        if (value == null) {
            return null;
        }
        return parseProductLine(value);
    }

    private String pathProductLine(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        if (requestUri.equals("/fapi/v1") || requestUri.startsWith("/fapi/v1/")) {
            return ProductLine.LINEAR_PERPETUAL.name();
        }
        if (requestUri.equals("/dapi/v1") || requestUri.startsWith("/dapi/v1/")) {
            return ProductLine.INVERSE_PERPETUAL.name();
        }
        if (requestUri.equals("/eapi/v1") || requestUri.startsWith("/eapi/v1/")) {
            return ProductLine.OPTION.name();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String bodyProductLine(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            return firstNonBlank(
                    stringValue(payload.get("productLine")),
                    stringValue(payload.get("product-line")),
                    stringValue(payload.get("product_line")),
                    stringValue(payload.get("accountType")),
                    stringValue(payload.get("account-type")),
                    stringValue(payload.get("account_type")),
                    stringValue(payload.get("contractType")),
                    stringValue(payload.get("contract-type")),
                    stringValue(payload.get("contract_type")));
        } catch (JacksonException | IllegalArgumentException ex) {
            return null;
        }
    }

    private ProductLine parseProductLine(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        ProductLine byAccountType = ProductLine.fromAccountTypeCode(normalized).orElse(null);
        if (byAccountType != null) {
            return byAccountType;
        }
        ProductLine byContractType = ProductLine.fromContractTypeCode(normalized).orElse(null);
        if (byContractType != null) {
            return byContractType;
        }
        String enumName = normalized.toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace('.', '_');
        for (ProductLine productLine : ProductLine.values()) {
            if (productLine.name().equals(enumName)
                    || productLine.topicSegment().equalsIgnoreCase(normalized)) {
                return productLine;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported product line: " + value);
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
            String value = GatewayTraceFilter.TRACE_ID_HEADER.equals(name)
                    ? traceId(request)
                    : request.getHeader(name);
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
        if (adminApprovalRepository == null) {
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
            adminApprovalRepository.consumeApproved(
                    approvalId,
                    Long.parseLong(identity.userId()),
                    service,
                    method == null ? "GET" : method.name(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodyHash,
                    traceId(request),
                    Instant.now());
        } catch (IllegalArgumentException | IllegalStateException ex) {
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
        String status = identity.status() == null ? "" : identity.status().trim().toUpperCase(Locale.ROOT);
        if ("TRADE_DISABLED".equals(status) && isTradingWrite(service, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "user trading is disabled");
        }
        if ("WITHDRAW_DISABLED".equals(status) && isWalletWithdrawWrite(service, request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "user withdrawal is disabled");
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
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return false;
        }
        if (!isAdminRequest(request) || method == null || isReadMethod(method)) {
            return false;
        }
        String normalizedService = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        String path = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase(Locale.ROOT);
        if (List.of("account", "instrument-admin", "insurance-admin", "trading-fees",
                "trading-orders", "market-maker", "risk-admin", "liquidation-admin", "wallet-admin").contains(normalizedService)) {
            return true;
        }
        return switch (normalizedService) {
            case "risk", "liquidation", "funding", "adl" -> path.endsWith("/admin/runtime-config");
            default -> false;
        };
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
        if (adminAuditRepository == null || identity == null || !identity.admin()) {
            return;
        }
        adminAuditRepository.record(new AdminOperationRecord(
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
                traceId(request),
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
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
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

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return request.getHeader(GatewayTraceFilter.TRACE_ID_HEADER);
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
