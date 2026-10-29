package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.utils.ApiSignatureFormats;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
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
        return bind(request, body, false);
    }

    public ApiKeyAuthenticationRequest bindNative(HttpServletRequest request, byte[] body) {
        return bind(request, body, true);
    }

    private ApiKeyAuthenticationRequest bind(HttpServletRequest request, byte[] body, boolean nativeApi) {
        String apiKey = request.getHeader(nativeApi ? "X-API-Key" : "X-MBX-APIKEY");
        if (apiKey == null || !apiKey.matches("sx_[A-Za-z0-9_-]{20,80}")) {
            throw new IllegalArgumentException("api key is invalid");
        }
        var parameters = formParameters(request, nativeApi ? null : body);
        long timestamp = parseLong(single(parameters, "timestamp"), "timestamp");
        String window = single(parameters, "recvWindow");
        long recvWindow = window == null ? 5_000L : parseLong(window, "recvWindow");
        if (recvWindow < 1L || recvWindow > 60_000L) {
            throw new IllegalArgumentException("request timestamp is outside recvWindow");
        }
        String signature = single(parameters, "signature");
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature is required");
        String canonical;
        if (nativeApi) {
            var query = new LinkedHashMap<String, String[]>();
            parameters.forEach((name, values) -> query.put(name, values.toArray(String[]::new)));
            canonical = ApiSignatureFormats.canonicalRequest(request.getMethod(), request.getRequestURI(), query, body);
        } else canonical = canonicalQuery(request, body);
        return new ApiKeyAuthenticationRequest(apiKey, addresses.resolve(request), timestamp, recvWindow, signature, canonical);
    }

    /** Read the supplied bytes: Servlet form parsing may already be exhausted by @RequestBody. */
    public Map<String, List<String>> formParameters(HttpServletRequest request, byte[] body) {
        var parameters = new LinkedHashMap<String, List<String>>();
        addForm(parameters, request.getQueryString());
        if (body != null && body.length > 0) {
            String text = new String(body, StandardCharsets.UTF_8);
            if (text.stripLeading().startsWith("{")) {
                throw new IllegalArgumentException("signed API requests require query or form parameters");
            }
            addForm(parameters, text);
        }
        if (parameters.isEmpty()) request.getParameterMap().forEach((key, values) ->
                parameters.put(key, values == null ? List.of() : List.of(values)));
        return parameters;
    }

    private static void addForm(Map<String, List<String>> parameters, String form) {
        if (form == null || form.isBlank()) return;
        for (String field : form.split("&")) {
            if (field.isEmpty()) continue;
            int separator = field.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? field : field.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(separator < 0 ? "" : field.substring(separator + 1), StandardCharsets.UTF_8);
            parameters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
    }

    private static String single(Map<String, List<String>> parameters, String name) {
        var values = parameters.get(name);
        if (values == null) return null;
        if (values.size() != 1) throw new IllegalArgumentException(name + " must occur exactly once");
        return values.getFirst();
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
