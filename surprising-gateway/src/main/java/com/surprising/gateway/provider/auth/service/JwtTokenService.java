package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.JwtPrincipal;
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
    private static final String JWT_HEADER_B64 = URL_ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private volatile String cachedSecret;
    private volatile SecretKeySpec cachedSecretKey;

    public JwtTokenService(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 登录令牌：带用户ID、会话ID、用户名和角色，签名后不查库即可得到用户ID；是否仍有效由 Valkey 会话决定。 */
    public String createSessionToken(long userId, String username, List<String> roles, long sessionId, Instant now) {
        Instant expiresAt = now.plus(properties.getSecurity().getSessionTokenMaxLifetime());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getSecurity().getIssuer());
        claims.put("sub", Long.toString(userId));
        claims.put("username", username);
        claims.put("roles", roles);
        claims.put("typ", "session");
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sid", sessionId);
        String unsigned = JWT_HEADER_B64 + "." + base64Json(claims);
        return unsigned + "." + sign(unsigned);
    }

    public JwtPrincipal verifySessionToken(String token) {
        Map<String, Object> claims = verify(token);
        if (!properties.getSecurity().getIssuer().equals(asString(claims.get("iss")))
                || !"session".equals(asString(claims.get("typ")))) {
            throw new IllegalArgumentException("invalid session token");
        }
        Instant expiresAt = Instant.ofEpochSecond(asLong(claims.get("exp")));
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("session token expired");
        }
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles") instanceof List<?> values
                ? values.stream().map(Object::toString).toList()
                : List.of("USER");
        Object usernameClaim = claims.get("username");
        return new JwtPrincipal(Long.parseLong(asString(claims.get("sub"))),
                usernameClaim == null ? null : asString(usernameClaim),
                "UNKNOWN", roles, expiresAt, optionalLong(claims.get("sid")));
    }

    private Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("missing access token");
        }
        int firstDot = token.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
        if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == token.length() - 1
                || token.indexOf('.', secondDot + 1) >= 0) {
            throw new IllegalArgumentException("invalid access token");
        }
        String unsigned = token.substring(0, secondDot);
        if (!com.surprising.gateway.provider.support.Sha256Digests.constantTimeEquals(
                sign(unsigned), token.substring(secondDot + 1))) {
            throw new IllegalArgumentException("invalid access token signature");
        }
        String payloadPart = token.substring(firstDot + 1, secondDot);
        return objectMapper.readValue(URL_DECODER.decode(payloadPart), Map.class);
    }

    private String base64Json(Object value) {
        return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String unsigned) {
        return URL_ENCODER.encodeToString(com.surprising.gateway.provider.support.Sha256Digests.hmacSha256(
                secretKeySpec(), unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    private SecretKeySpec secretKeySpec() {
        String secret = properties.getSecurity().getJwtSecret();
        SecretKeySpec spec = cachedSecretKey;
        if (spec != null && secret.equals(cachedSecret)) {
            return spec;
        }
        SecretKeySpec created = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        cachedSecret = secret;
        cachedSecretKey = created;
        return created;
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

    private static long optionalLong(Object value) {
        return value == null ? 0L : asLong(value);
    }
}
