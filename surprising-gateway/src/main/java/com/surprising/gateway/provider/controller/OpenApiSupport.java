package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.controller.ApiKeyRequestBinding;
import com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 公开 Open API 共享工具：鉴权、参数解析、精度转换与 JSON 响应封装。
 */
@Component
public class OpenApiSupport {

    private final GatewayProperties properties;
    private final ApiKeyRequestBinding apiKeyRequests;
    private final GatewayApiKeyService apiKeyService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public OpenApiSupport(GatewayProperties properties,
                          GatewayApiKeyService apiKeyService,
                          AuthService authService,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.apiKeyRequests = new ApiKeyRequestBinding(properties);
        this.apiKeyService = apiKeyService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    public ProductLine productLine(HttpServletRequest request) {
        String explicit = request.getHeader("X-Product-Line");
        if (explicit == null || explicit.isBlank()) {
            explicit = request.getParameter("productLine");
        }
        if (explicit != null && !explicit.isBlank()) {
            return ProductLine.valueOf(explicit);
        }
        String path = request.getRequestURI();
        if (path.startsWith("/fapi/")) return ProductLine.LINEAR_PERPETUAL;
        if (path.startsWith("/dapi/")) return ProductLine.INVERSE_PERPETUAL;
        if (path.startsWith("/eapi/")) return ProductLine.OPTION;
        return ProductLine.SPOT;
    }

    public long authenticate(HttpServletRequest request, String permission) {
        return authenticate(request, permission, null);
    }

    public long authenticate(HttpServletRequest request, String permission, byte[] body) {
        String apiKey = request.getHeader("X-MBX-APIKEY");
        if (apiKey != null && !apiKey.isBlank()) {
            String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (bearer != null && !bearer.isBlank()) {
                throw new IllegalArgumentException("choose bearer or API key authentication");
            }
            try {
                return apiKeyService.authenticate(apiKeyRequests.bind(request, body), permission);
            } catch (ApiKeyAccessBlockedException ex) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
            }
        }
        try {
            JwtPrincipal principal = authService.authenticateBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
            return principal.userId();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    public Map<String, Object> parameters(HttpServletRequest request, byte[] body) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (body != null && body.length > 0 && new String(body, StandardCharsets.UTF_8).stripLeading().startsWith("{")) {
            request.getParameterMap().forEach((key, values) -> {
                if (values != null && values.length > 0) result.put(key, values[0]);
            });
            readMap(body).forEach(result::putIfAbsent);
        } else {
            apiKeyRequests.formParameters(request, body).forEach((key, values) -> {
                if (!values.isEmpty()) result.put(key, values.getFirst());
            });
        }
        return result;
    }

    public ResponseEntity<byte[]> json(HttpStatus status, Object value) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(jsonBytes(value));
    }

    public ResponseEntity<byte[]> error(HttpStatus status, int code, String message) {
        return json(status, Map.of("code", code, "msg", message == null ? "request failed" : message));
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("request cannot be encoded", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(byte[] body) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, Map.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("response is not a JSON object", ex);
        }
    }

    public String required(Map<String, Object> params, String key) {
        String value = first(params, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    public String requiredParameter(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    public String first(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    public Long longParameter(HttpServletRequest request, String key) {
        String val = request.getParameter(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid", ex);
        }
    }

    public Long longParameter(Map<String, Object> params, String key) {
        try {
            return Long.parseLong(required(params, key));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid", ex);
        }
    }

    public long decimalUnits(String value, int scale) {
        try {
            return new BigDecimal(value).movePointRight(scale).setScale(0, java.math.RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("decimal value does not match symbol precision", ex);
        }
    }

    public String decimalString(long units, int scale) {
        return BigDecimal.valueOf(units, scale).stripTrailingZeros().toPlainString();
    }

    public boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    public int capped(String value) {
        if (value == null || value.isBlank()) return 100;
        try {
            return Math.min(1000, Math.max(1, Integer.parseInt(value)));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("limit is invalid", ex);
        }
    }

    public String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
