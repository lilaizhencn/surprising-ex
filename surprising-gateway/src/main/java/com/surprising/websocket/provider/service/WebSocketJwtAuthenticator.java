package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.config.WebSocketProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class WebSocketJwtAuthenticator {

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final WebSocketProperties properties;
    private final ObjectMapper objectMapper;

    public WebSocketJwtAuthenticator(WebSocketProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public long authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("missing websocket token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid websocket token");
        }
        String unsigned = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(unsigned).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("invalid websocket token signature");
        }
        Map<String, Object> claims = objectMapper.readValue(
                new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
        if (!properties.getSecurity().getIssuer().equals(asString(claims.get("iss")))
                || !"access".equals(asString(claims.get("typ")))) {
            throw new IllegalArgumentException("invalid websocket token claims");
        }
        long exp = asLong(claims.get("exp"));
        if (Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
            throw new IllegalArgumentException("websocket token expired");
        }
        return Long.parseLong(asString(claims.get("sub")));
    }

    private String sign(String unsigned) {
        String jwtSecret = properties.getSecurity().getJwtSecret();
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("websocket jwt secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to verify websocket token", ex);
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("missing websocket token claim");
        }
        return value.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(asString(value));
    }
}
