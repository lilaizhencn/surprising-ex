package com.surprising.gateway.provider.controller;

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
    private final ObjectMapper json;
    public GatewayLimitRequestBinding(ObjectMapper json) { this.json = json; }
    public record Request(Group group, ProductLine product, int cost) {}

    public Group group(HttpServletRequest request) {
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (read && (path.contains("/commands/") || path.contains("/cancellation"))) return Group.RECEIPT;
        if (path.startsWith("/api/v1/admin/")) return Group.ADMIN;
        if (!read && (path.equals("/api/v1/compliance/kyc/documents") || path.startsWith("/api/v1/c2c/attachments/"))) return Group.UPLOAD;
        boolean trading = path.startsWith("/api/v3/") || path.startsWith("/fapi/") || path.startsWith("/dapi/")
                || path.startsWith("/eapi/") || path.contains("/trading");
        if (!read && trading && (path.contains("cancel-open") || ("DELETE".equals(method) && path.endsWith("/openorders")))) return Group.CANCEL_ALL;
        if (!read && trading && ("DELETE".equals(method) || path.contains("cancel"))) return Group.CANCEL;
        if (!read && (path.contains("withdraw") || path.contains("transfer"))) return Group.FUNDS;
        if (!read && (path.contains("/security/") || path.contains("/auth/"))) return Group.SECURITY;
        if (!read && (trading || path.endsWith("/position-mode") || path.endsWith("/position-margin-adjustments"))) return Group.ORDER;
        return Group.READ;
    }

    public boolean isLogin(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"GET".equals(request.getMethod()) && (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/telegram/"));
    }

    /** Read query text only; IP admission must precede form/body parsing and authentication. */
    public int publicCost(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) || request.getQueryString() == null) return 1;
        int cost = 1;
        for (String part : request.getQueryString().split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            String name = java.net.URLDecoder.decode(part.substring(0, separator), java.nio.charset.StandardCharsets.UTF_8);
            if (!name.equals("limit") && !name.equals("depth")) continue;
            try {
                long count = Long.parseLong(java.net.URLDecoder.decode(part.substring(separator + 1), java.nio.charset.StandardCharsets.UTF_8));
                if (count > 0) cost = Math.max(cost, (int) Math.min(10000, 1 + (count - 1) / 50));
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("limit is invalid", invalid); }
        }
        return cost;
    }

    public Request bind(HttpServletRequest request) {
        Group group = group(request);
        ProductLine product = (ProductLine) request.getAttribute(GatewayProductSelection.RESOLVED_PRODUCT);
        if (product == null) {
            String selector = request.getHeader("X-Product-Line");
            if (selector == null) selector = request.getParameter("productLine");
            if (selector != null) product = ProductLine.fromExternalCode(selector).orElseThrow(() -> new IllegalArgumentException("unsupported product line"));
            else if (request.getRequestURI().startsWith("/fapi/")) product = ProductLine.LINEAR_PERPETUAL;
            else if (request.getRequestURI().startsWith("/dapi/")) product = ProductLine.INVERSE_PERPETUAL;
            else if (request.getRequestURI().startsWith("/eapi/")) product = ProductLine.OPTION;
            else if (request.getRequestURI().startsWith("/api/v3/")) product = ProductLine.SPOT;
        }
        int cost = 1;
        if (!"GET".equals(request.getMethod()) && request.getRequestURI().contains("batch")) {
            byte[] body = body(request);
            if (body != null && body.length > 0) {
                var node = json.readTree(body);
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
