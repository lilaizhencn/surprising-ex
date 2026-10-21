package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.utils.ApiSignatureFormats;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/** Binance签名协议的请求字段与原始签名字节绑定，不访问账号或密钥仓储。 */
public final class ApiKeyRequestBinding {
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

}
