package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class JwtTokenService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String createAccessToken(long userId, String username, List<String> roles, Instant now) {
        Instant expiresAt = now.plus(properties.getSecurity().getAccessTokenTtl());
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getSecurity().getIssuer());
        claims.put("sub", Long.toString(userId));
        claims.put("username", username);
        claims.put("roles", roles);
        claims.put("typ", "access");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        String unsigned = base64Json(header) + "." + base64Json(claims);
        return unsigned + "." + sign(unsigned);
    }

    public JwtPrincipal verifyAccessToken(String token) {
        Map<String, Object> claims = verify(token);
        if (!properties.getSecurity().getIssuer().equals(asString(claims.get("iss")))
                || !"access".equals(asString(claims.get("typ")))) {
            throw new IllegalArgumentException("invalid access token");
        }
        Instant expiresAt = Instant.ofEpochSecond(asLong(claims.get("exp")));
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("access token expired");
        }
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles") instanceof List<?> values
                ? values.stream().map(Object::toString).toList()
                : List.of("USER");
        return new JwtPrincipal(Long.parseLong(asString(claims.get("sub"))),
                asString(claims.get("username")), "UNKNOWN", roles, expiresAt);
    }

    private Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("missing access token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid access token");
        }
        String unsigned = parts[0] + "." + parts[1];
        byte[] expected = sign(unsigned).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("invalid access token signature");
        }
        return objectMapper.readValue(new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
    }

    private String base64Json(Object value) {
        return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign access token", ex);
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("missing token claim");
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
