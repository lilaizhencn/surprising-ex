package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.utils.ApiSignatureFormats;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** API Key管理格式及Binance签名原始字节绑定，不访问账号或密钥仓储。 */
public final class ApiKeyRequestBinding {
    private static final Set<String> PERMISSIONS = Set.of("READ", "TRADE", "WITHDRAW");
    private final ClientIpResolver addresses;
    public ApiKeyRequestBinding(GatewayProperties properties) { addresses = new ClientIpResolver(properties); }

    public ApiKeyAuthenticationRequest bind(HttpServletRequest request, byte[] body) {
        String apiKey = request.getHeader("X-MBX-APIKEY");
        if (apiKey == null || !apiKey.matches("sx_[A-Za-z0-9_-]{20,80}")) {
            throw new IllegalArgumentException("api key is invalid");
        }
        long timestamp = parseLong(request.getParameter("timestamp"), "timestamp");
        long recvWindow = request.getParameter("recvWindow") == null ? 5_000L
                : parseLong(request.getParameter("recvWindow"), "recvWindow");
        if (recvWindow < 1L || recvWindow > 60_000L) {
            throw new IllegalArgumentException("request timestamp is outside recvWindow");
        }
        String signature = request.getParameter("signature");
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature is required");
        return new ApiKeyAuthenticationRequest(apiKey, addresses.resolve(request), timestamp, recvWindow,
                signature, canonicalQuery(request, body));
    }

    private static long parseLong(String value, String field) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(field + " is invalid", ex); }
    }

    public String canonicalQuery(HttpServletRequest request) {
        return canonicalQuery(request, null);
    }

    public String canonicalQuery(HttpServletRequest request, byte[] body) {
        String queryString = request.getQueryString();
        String query = queryString == null || queryString.isBlank() ? "" : ApiSignatureFormats.canonicalQuery(queryString);
        String requestBody = body == null || body.length == 0
                ? "" : ApiSignatureFormats.canonicalQuery(new String(body, StandardCharsets.UTF_8));
        if (!query.isBlank() && !requestBody.isBlank()) {
            return query + requestBody;
        }
        if (!query.isBlank()) {
            return query;
        }
        if (!requestBody.isBlank()) {
            return requestBody;
        }
        return ApiSignatureFormats.canonicalParameters(request.getParameterMap());
    }

    public String label(String label) {
        String normalizedLabel = label == null ? "" : label.trim();
        if (normalizedLabel.isBlank() || normalizedLabel.length() > 80) {
            throw new IllegalArgumentException("api key label must be 1-80 characters");
        }
        return normalizedLabel;
    }

    public String normalizePermissions(List<String> permissions) {
        Set<String> normalized = new LinkedHashSet<>();
        if (permissions != null) {
            permissions.forEach(value -> {
                String item = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
                if (!PERMISSIONS.contains(item)) throw new IllegalArgumentException("unsupported api key permission");
                normalized.add(item);
            });
        }
        normalized.add("READ");
        return String.join(",", normalized);
    }

    public String normalizeIpAllowlist(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.size() > 100) {
            throw new IllegalArgumentException("api key IP allowlist cannot contain more than 100 addresses");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = value == null ? "" : value.trim();
            if (item.isBlank() || item.length() > 64 || !addresses.isValidRule(item)) {
                throw new IllegalArgumentException("api key IP allowlist contains an invalid address");
            }
            normalized.add(item);
        }
        return String.join(",", normalized);
    }

    public String requireApiKey(String value) {
        if (value == null || !value.matches("sx_[A-Za-z0-9_-]{20,80}")) {
            throw new IllegalArgumentException("api key is invalid");
        }
        return value;
    }

}
