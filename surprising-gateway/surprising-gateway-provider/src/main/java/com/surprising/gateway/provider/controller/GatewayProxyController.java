package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal allowlisted REST gateway for frontend/BFF traffic.
 *
 * <p>Business modules keep their own internal controllers; this gateway exposes a stable public
 * prefix, enforces private-route identity checks, and only proxies configured service names.</p>
 */
@RestController
public class GatewayProxyController {

    private static final String GATEWAY_PREFIX = "/api/v1/gateway";
    private static final List<String> FORWARDED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Request-Id",
            "X-User-Id",
            "X-Forwarded-For",
            GatewayTraceFilter.TRACE_ID_HEADER);

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public GatewayProxyController(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @RequestMapping(path = {GATEWAY_PREFIX + "/{service}", GATEWAY_PREFIX + "/{service}/**"})
    public ResponseEntity<byte[]> proxy(@PathVariable String service,
                                        HttpMethod method,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        GatewayProperties.BackendRoute route = route(service);
        enforceIdentity(route, request);
        URI target = targetUri(service, route, request);
        ResponseEntity<byte[]> response = restTemplate.exchange(target, method,
                new HttpEntity<>(body, headers(request)), byte[].class);
        HttpHeaders responseHeaders = new HttpHeaders();
        if (response.getHeaders().getContentType() != null) {
            responseHeaders.setContentType(response.getHeaders().getContentType());
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(responseHeaders)
                .body(response.getBody());
    }

    URI targetUri(String service, GatewayProperties.BackendRoute route, HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String servicePrefix = GATEWAY_PREFIX + "/" + service;
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

    private GatewayProperties.BackendRoute route(String service) {
        String normalized = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        GatewayProperties.BackendRoute route = properties.getRoutes().get(normalized);
        if (route == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown gateway service: " + service);
        }
        return route;
    }

    private void enforceIdentity(GatewayProperties.BackendRoute route, HttpServletRequest request) {
        if (!route.isPrivateRoute() || !properties.getSecurity().isRequireIdentityForPrivateRoutes()) {
            return;
        }
        String userHeader = request.getHeader(properties.getSecurity().getUserIdHeader());
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if ((userHeader == null || userHeader.isBlank()) && (authorization == null || authorization.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private gateway route requires identity");
        }
    }

    private HttpHeaders headers(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : FORWARDED_HEADERS) {
            String value = GatewayTraceFilter.TRACE_ID_HEADER.equals(name)
                    ? traceId(request)
                    : request.getHeader(name);
            if (value != null && !value.isBlank()) {
                headers.add(name, value);
            }
        }
        return headers;
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
}
