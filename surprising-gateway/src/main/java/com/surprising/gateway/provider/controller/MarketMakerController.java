package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport.GatewayIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller for Market Maker service requests.
 * Authenticates client and forwards calls to the market-maker backend process.
 */
@RestController
public class MarketMakerController {

    private static final String MARKET_MAKER_PREFIX = "/api/v1/market-maker";
    private static final String ADMIN_MARKET_MAKER_PREFIX = "/api/v1/admin/market-maker";

    private static final List<String> FORWARDED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Request-Id",
            "X-User-Id",
            "X-Admin-User-Id",
            "X-Admin-Username",
            "X-Product-Line",
            "X-Account-Type",
            "X-Contract-Type",
            "X-Forwarded-For"
    );

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final AdminApprovalService adminApprovalService;

    @Autowired
    public MarketMakerController(GatewayProperties properties,
                                 RestTemplate restTemplate,
                                 AuthService authService,
                                 AdminApprovalService adminApprovalService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.authService = Objects.requireNonNull(authService, "authService");
        this.adminApprovalService = adminApprovalService;
    }

    @RequestMapping(path = {MARKET_MAKER_PREFIX, MARKET_MAKER_PREFIX + "/**"})
    public ResponseEntity<byte[]> handlePublic(HttpMethod method,
                                               HttpServletRequest request,
                                               @RequestBody(required = false) byte[] body) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        GatewayIdentity identity = null;
        if (authHeader != null && !authHeader.isBlank()) {
            identity = GatewayAccessSupport.authenticateBearerUser(authService, authHeader);
        }
        return forward(request, method, body, identity, false);
    }

    @RequestMapping(path = {ADMIN_MARKET_MAKER_PREFIX, ADMIN_MARKET_MAKER_PREFIX + "/**"})
    public ResponseEntity<byte[]> handleAdmin(HttpMethod method,
                                              HttpServletRequest request,
                                              @RequestBody(required = false) byte[] body) {
        long startedNanos = System.nanoTime();
        GatewayIdentity identity = GatewayAccessSupport.authenticateAdmin(authService, properties, request, "admin authorization required");
        boolean readMethod = GatewayAccessSupport.isReadMethod(method.name());
        GatewayAccessSupport.enforceAdminPermission(authService, "market-maker", readMethod, identity);

        String bodyHash = GatewayAccessSupport.bodySha256(body, false);
        URI targetUri = resolveTargetUri(request);
        try {
            ResponseEntity<byte[]> response = forward(request, method, body, identity, true);
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    response.getStatusCode().value(), elapsedMillis(startedNanos), true, null);
            return response;
        } catch (ResponseStatusException ex) {
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    ex.getStatusCode().value(), elapsedMillis(startedNanos), false, ex.getReason());
            throw ex;
        } catch (RuntimeException ex) {
            recordAdminOperation(method, request, identity, targetUri, bodyHash,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(), elapsedMillis(startedNanos), false, ex.getMessage());
            throw ex;
        }
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request,
                                           HttpMethod method,
                                           byte[] body,
                                           GatewayIdentity identity,
                                           boolean admin) {
        URI targetUri = resolveTargetUri(request);
        HttpHeaders headers = buildForwardHeaders(request, identity, admin);
        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(targetUri, method, entity, byte[].class);
            HttpHeaders responseHeaders = new HttpHeaders();
            if (response.getHeaders().getContentType() != null) {
                responseHeaders.setContentType(response.getHeaders().getContentType());
            }
            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());
        } catch (HttpStatusCodeException ex) {
            HttpHeaders responseHeaders = new HttpHeaders();
            if (ex.getResponseHeaders() != null && ex.getResponseHeaders().getContentType() != null) {
                responseHeaders.setContentType(ex.getResponseHeaders().getContentType());
            }
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(responseHeaders)
                    .body(ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "market-maker backend unreachable", ex);
        }
    }

    private URI resolveTargetUri(HttpServletRequest request) {
        String baseUrl = properties.getRoutes().containsKey("market-maker")
                ? properties.getRoutes().get("market-maker").getBaseUrl()
                : "http://localhost:9096";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String fullUrl = baseUrl + path + (query != null && !query.isBlank() ? "?" + query : "");
        return URI.create(fullUrl);
    }

    private HttpHeaders buildForwardHeaders(HttpServletRequest request, GatewayIdentity identity, boolean admin) {
        HttpHeaders headers = new HttpHeaders();
        for (String headerName : FORWARDED_HEADERS) {
            Enumeration<String> values = request.getHeaders(headerName);
            while (values != null && values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }
        if (identity != null) {
            if (admin) {
                headers.set("X-Admin-User-Id", identity.userId());
                if (identity.username() != null) {
                    headers.set("X-Admin-Username", identity.username());
                }
            } else {
                headers.set("X-User-Id", identity.userId());
            }
        }
        return headers;
    }

    private void recordAdminOperation(HttpMethod method,
                                        HttpServletRequest request,
                                        GatewayIdentity identity,
                                        URI target,
                                        String bodyHash,
                                        int statusCode,
                                        long elapsedMs,
                                        boolean success,
                                        String error) {
        if (adminApprovalService == null) return;
        GatewayAccessSupport.recordAdminOperation(adminApprovalService, identity, "market-maker",
                method.name(), request, target.toString(), bodyHash, statusCode, elapsedMs, success, error,
                request.getRemoteAddr());
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
