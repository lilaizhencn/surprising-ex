package com.surprising.gateway.provider.support;

import com.surprising.gateway.provider.config.GatewayLimitProperties.Group;
import com.surprising.gateway.provider.interceptor.ProductRequestBodyFilter;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

/** Converts external aliases to one quota family; financial validation remains in the business service. */
@Component
public final class GatewayLimitRequestBinding {
    public static final String ACTIVE = GatewayLimitRequestBinding.class.getName() + ".active";
    private static final String CACHED_GROUP = GatewayLimitRequestBinding.class.getName() + ".group";
    private final ObjectMapper json;
    public GatewayLimitRequestBinding(ObjectMapper json) { this.json = json; }
    public record Request(Group group, ProductLine product, int cost) {}

    public Group group(HttpServletRequest request) {
        if (request.getAttribute(CACHED_GROUP) instanceof Group cached) {
            return cached;
        }
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        Group resolved;
        if (read && (path.contains("/commands/") || path.contains("/cancellation"))) resolved = Group.RECEIPT;
        else if (path.startsWith("/api/v1/admin/")) resolved = Group.ADMIN;
        else if (!read && (path.equals("/api/v1/compliance/kyc/documents") || path.startsWith("/api/v1/c2c/attachments/"))) resolved = Group.UPLOAD;
        else {
            boolean trading = path.startsWith("/api/v3/") || path.startsWith("/fapi/") || path.startsWith("/dapi/")
                    || path.startsWith("/eapi/") || path.contains("/trading");
            if (!read && trading && (path.contains("cancel-open") || ("DELETE".equals(method) && path.endsWith("/openorders")))) resolved = Group.CANCEL_ALL;
            else if (!read && trading && ("DELETE".equals(method) || path.contains("cancel"))) resolved = Group.CANCEL;
            else if (!read && (path.contains("withdraw") || path.contains("transfer"))) resolved = Group.FUNDS;
            else if (!read && (path.contains("/security/") || path.contains("/auth/"))) resolved = Group.SECURITY;
            else if (!read && (trading || path.endsWith("/position-mode") || path.endsWith("/position-margin-adjustments"))) resolved = Group.ORDER;
            else resolved = Group.READ;
        }
        request.setAttribute(CACHED_GROUP, resolved);
        return resolved;
    }

    public boolean isLogin(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/telegram/bot/")) {
            return false;
        }
        return !"GET".equals(request.getMethod()) && (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/telegram/"));
    }

    /** Read query text only; IP admission must precede form/body parsing and authentication. */
    public int publicCost(HttpServletRequest request) {
        String query = request.getQueryString();
        if (!"GET".equals(request.getMethod()) || query == null || query.isEmpty()) return 1;
        int cost = 1;
        int start = 0;
        int len = query.length();
        while (start <= len) {
            int amp = query.indexOf('&', start);
            int end = amp < 0 ? len : amp;
            int separator = query.indexOf('=', start);
            if (separator >= start && separator < end && isLimitOrDepthParam(query, start, separator)) {
                try {
                    String rawValue = query.substring(separator + 1, end);
                    String decodedValue = rawValue.indexOf('%') >= 0 || rawValue.indexOf('+') >= 0
                            ? java.net.URLDecoder.decode(rawValue, java.nio.charset.StandardCharsets.UTF_8)
                            : rawValue;
                    long count = Long.parseLong(decodedValue);
                    if (count > 0) cost = Math.max(cost, (int) Math.min(10000, 1 + (count - 1) / 50));
                } catch (NumberFormatException invalid) { throw new IllegalArgumentException("limit is invalid", invalid); }
            }
            if (amp < 0) break;
            start = amp + 1;
        }
        return cost;
    }

    private static boolean isLimitOrDepthParam(String query, int start, int separator) {
        int nameLen = separator - start;
        if (nameLen == 5 && (query.regionMatches(start, "limit", 0, 5) || query.regionMatches(start, "depth", 0, 5))) {
            return true;
        }
        if (query.indexOf('%', start) >= start && query.indexOf('%', start) < separator) {
            String decoded = java.net.URLDecoder.decode(query.substring(start, separator), java.nio.charset.StandardCharsets.UTF_8);
            return "limit".equals(decoded) || "depth".equals(decoded);
        }
        return false;
    }

    public Request bind(HttpServletRequest request) {
        Group group = group(request);
        ProductLine product = (ProductLine) request.getAttribute(GatewayProductSelection.RESOLVED_PRODUCT);
        if (product == null) {
            String selector = request.getHeader("X-Product-Line");
            if (selector == null) selector = request.getParameter("productLine");
            if (selector != null) {
                String trimmed = selector.trim();
                if (!trimmed.isEmpty() && !"ALL".equalsIgnoreCase(trimmed)) {
                    product = ProductLine.fromExternalCode(trimmed)
                            .orElseThrow(() -> new IllegalArgumentException("unsupported product line"));
                }
            } else if (request.getRequestURI().startsWith("/fapi/")) product = ProductLine.LINEAR_PERPETUAL;
            else if (request.getRequestURI().startsWith("/dapi/")) product = ProductLine.INVERSE_PERPETUAL;
            else if (request.getRequestURI().startsWith("/eapi/")) product = ProductLine.OPTION;
            else if (request.getRequestURI().startsWith("/api/v3/")) product = ProductLine.SPOT;
        }
        int cost = 1;
        if (!"GET".equals(request.getMethod()) && request.getRequestURI().contains("batch")) {
            byte[] body = body(request);
            if (body != null && body.length > 0) {
                var node = ProductRequestBodyFilter.requestTree(request, body, json);
                var orders = node.isArray() ? node : node.get("orders");
                if (orders != null && orders.isArray()) cost = Math.max(1, orders.size());
            }
        }
        if (group == Group.READ) {
            String value = request.getParameter("limit");
            if (value == null && request.getRequestURI().contains("orderbook")) value = request.getParameter("depth");
            if (value != null) {
                try { long count = Long.parseLong(value); if (count > 0) cost = (int) Math.min(10000, 1 + (count - 1) / 50); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("limit is invalid", invalid); }
            }
        }
        return new Request(group, product, cost);
    }

    public static byte[] body(HttpServletRequest request) {
        Object prepared = request.getAttribute(ProductRequestBodyFilter.BODY);
        if (prepared instanceof byte[] bytes) return bytes;
        var cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        return cached == null ? null : cached.getContentAsByteArray();
    }
}
