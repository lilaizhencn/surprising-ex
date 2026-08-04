package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class GatewayApiKeyService {

    private static final Set<String> PERMISSIONS = Set.of("READ", "TRADE", "WITHDRAW");
    private static final long MAX_TIMESTAMP_SKEW_MILLIS = 5_000L;
    private final GatewayApiKeyRepository repository;
    private final AuthService authService;
    private final TotpService totpService;
    private final SensitiveActionVerificationService verificationService;
    private final SecureRandom random = new SecureRandom();

    public GatewayApiKeyService(GatewayApiKeyRepository repository,
                                AuthService authService,
                                TotpService totpService,
                                SensitiveActionVerificationService verificationService) {
        this.repository = repository;
        this.authService = authService;
        this.totpService = totpService;
        this.verificationService = verificationService;
    }

    public CreatedApiKey create(String authorization, String label, List<String> permissions,
                                String emailCode, String totpCode, String requestIp) {
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        requireSecurity(principal.userId(), emailCode, totpCode, requestIp);
        String normalizedLabel = label == null ? "" : label.trim();
        if (normalizedLabel.isBlank() || normalizedLabel.length() > 80) {
            throw new IllegalArgumentException("api key label must be 1-80 characters");
        }
        String normalizedPermissions = normalizePermissions(permissions);
        String apiKey = "sx_" + randomToken(24);
        String secret = randomToken(32);
        GatewayApiKeyRepository.ApiKeyRecord record = repository.create(
                UUID.randomUUID(), principal.userId(), apiKey, totpService.encryptSecret(secret),
                normalizedLabel, normalizedPermissions, Instant.now());
        return new CreatedApiKey(view(record), secret);
    }

    public List<ApiKeyView> list(String authorization) {
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        return repository.list(principal.userId()).stream().map(this::view).toList();
    }

    public void revoke(String authorization, String apiKey, String emailCode, String totpCode,
                       String requestIp) {
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        requireSecurity(principal.userId(), emailCode, totpCode, requestIp);
        if (!repository.revoke(principal.userId(), requireApiKey(apiKey), Instant.now())) {
            throw new IllegalArgumentException("active api key not found");
        }
    }

    public long authenticate(HttpServletRequest request, String requiredPermission) {
        String apiKey = requireApiKey(request.getHeader("X-MBX-APIKEY"));
        GatewayApiKeyRepository.ApiKeyRecord record = repository.active(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("invalid api key"));
        requirePermission(record.permissions(), requiredPermission);
        String timestamp = request.getParameter("timestamp");
        long timestampValue = parseLong(timestamp, "timestamp");
        long recvWindow = request.getParameter("recvWindow") == null
                ? 5_000L : parseLong(request.getParameter("recvWindow"), "recvWindow");
        if (recvWindow < 1L || recvWindow > 60_000L
                || Math.abs(System.currentTimeMillis() - timestampValue) > Math.min(recvWindow, 60_000L)) {
            throw new IllegalArgumentException("request timestamp is outside recvWindow");
        }
        String signature = request.getParameter("signature");
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }
        String canonical = canonicalQuery(request.getQueryString());
        String expected = sign(totpService.decryptSecret(record.secretCiphertext()), canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("invalid api signature");
        }
        repository.markUsed(record.apiKeyId(), Instant.now());
        return record.userId();
    }

    String canonicalQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(queryString.split("&"))
                .filter(item -> !item.startsWith("signature="))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    String sign(String secret, String canonicalQuery) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormatSupport.hex(mac.doFinal(canonicalQuery.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("api signature is unavailable", ex);
        }
    }

    private void requireSecurity(long userId, String emailCode, String totpCode, String requestIp) {
        if (!verificationService.verify(userId, "SECURITY_SETTINGS", emailCode, totpCode, Instant.now())) {
            throw new IllegalArgumentException("security verification is required or invalid");
        }
    }

    private ApiKeyView view(GatewayApiKeyRepository.ApiKeyRecord record) {
        return new ApiKeyView(record.apiKey(), record.label(), record.permissions(), record.status(),
                record.createdAt(), record.lastUsedAt(), record.revokedAt());
    }

    private String normalizePermissions(List<String> permissions) {
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

    private void requirePermission(String permissions, String required) {
        if (required == null || required.isBlank()) return;
        Set<String> values = Set.of(permissions.split(","));
        if (!values.contains(required.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("api key permission is insufficient");
        }
    }

    private String requireApiKey(String value) {
        if (value == null || !value.matches("sx_[A-Za-z0-9_-]{20,80}")) {
            throw new IllegalArgumentException("api key is invalid");
        }
        return value;
    }

    private long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " is invalid", ex);
        }
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record CreatedApiKey(ApiKeyView apiKey, String secret) {
    }

    public record ApiKeyView(String apiKey, String label, String permissions, String status,
                             Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
    }

    private static final class HexFormatSupport {
        private static String hex(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }
}
