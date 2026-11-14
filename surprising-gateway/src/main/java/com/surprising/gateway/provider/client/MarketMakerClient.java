package com.surprising.gateway.provider.client;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.interceptor.GatewayAccessSupport.GatewayIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 做市商后端服务客户端，负责将网关校验后的管理请求转发至独立运行的 market-maker 进程。
 */
@Component
public class MarketMakerClient {

    private static final List<String> FORWARDED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            "X-Request-Id",
            "X-Admin-User-Id",
            "X-Admin-Username",
            "X-Product-Line",
            "X-Account-Type",
            "X-Contract-Type",
            "X-Forwarded-For"
    );

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public MarketMakerClient(GatewayProperties properties, RestTemplate restTemplate) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    public URI resolveTargetUri(HttpServletRequest request) {
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

    public ResponseEntity<byte[]> forwardAdmin(HttpServletRequest request,
                                              HttpMethod method,
                                              byte[] body,
                                              GatewayIdentity identity) {
        URI targetUri = resolveTargetUri(request);
        HttpHeaders headers = buildForwardHeaders(request, identity);
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

    private HttpHeaders buildForwardHeaders(HttpServletRequest request, GatewayIdentity identity) {
        HttpHeaders headers = new HttpHeaders();
        for (String headerName : FORWARDED_HEADERS) {
            Enumeration<String> values = request.getHeaders(headerName);
            while (values != null && values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }
        if (identity != null) {
            headers.set("X-Admin-User-Id", identity.userId());
            if (identity.username() != null) {
                headers.set("X-Admin-Username", identity.username());
            }
        }
        return headers;
    }
}
