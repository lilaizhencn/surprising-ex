package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.repository.GatewayApiKeyRepository;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.ApiKeyAuthenticationRequest;
import com.surprising.gateway.provider.auth.model.ApiKeyModels.ApiKeyView;
import com.surprising.gateway.provider.auth.model.ApiKeyModels.CreatedApiKey;
import com.surprising.gateway.provider.auth.exception.ApiKeyAccessBlockedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class GatewayApiKeyService {

    private final GatewayApiKeyRepository repository;
    private final AuthService authService;
    private final TotpService totpService;
    private final SensitiveActionVerificationService verificationService;
    private final ClientIpResolver clientIpResolver;
    private final com.surprising.gateway.provider.auth.port.CurrentAuthClient currentClient;
    private final SecureRandom random = new SecureRandom();

    public GatewayApiKeyService(GatewayApiKeyRepository repository,
                                AuthService authService,
                                TotpService totpService,
                                SensitiveActionVerificationService verificationService,
                                com.surprising.gateway.provider.config.GatewayProperties properties,
                                com.surprising.gateway.provider.auth.port.CurrentAuthClient currentClient) {
        this.repository = repository;
        this.authService = authService;
        this.totpService = totpService;
        this.verificationService = verificationService;
        this.clientIpResolver = new ClientIpResolver(properties);
        this.currentClient = currentClient;
    }

    public CreatedApiKey create(String authorization, String label, String permissions,
                                String ipAllowlist,
                                String emailCode, String totpCode, String requestIp) {
        requireWriteIp(permissions, ipAllowlist);
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        requireSecurity(principal.userId(), emailCode, totpCode);
        String apiKey = "sx_" + randomToken(24);
        String secret = randomToken(32);
        GatewayApiKeyRepository.ApiKeyRecord record = repository.create(
                UUID.randomUUID(), principal.userId(), apiKey, totpService.encryptSecret(secret),
                label, permissions, ipAllowlist, Instant.now());
        return new CreatedApiKey(view(record), secret);
    }

    public List<ApiKeyView> list(String authorization) {
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        return repository.list(principal.userId()).stream().map(this::view).toList();
    }

    public void revoke(String authorization, String apiKey, String emailCode, String totpCode,
                       String requestIp) {
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        requireSecurity(principal.userId(), emailCode, totpCode);
        if (!repository.revoke(principal.userId(), apiKey, Instant.now())) {
            throw new IllegalArgumentException("active api key not found");
        }
    }

    public void update(String authorization, String apiKey, String label, String permissions, String ipAllowlist,
                                  String emailCode, String totpCode, String requestIp) {
        requireWriteIp(permissions, ipAllowlist);
        JwtPrincipal principal = authService.authenticateBearer(authorization);
        requireSecurity(principal.userId(), emailCode, totpCode);
        if (!repository.update(principal.userId(), apiKey, label, permissions, ipAllowlist)) {
            throw new IllegalArgumentException("active api key not found");
        }
    }

    public long authenticate(ApiKeyAuthenticationRequest request, String requiredPermission) {
        var record = requireAccess(request.apiKey(), request.ipAddress(), requiredPermission);
        long now = System.currentTimeMillis();
        long window = request.recvWindow();
        if (window < 1 || window > 60_000 || request.timestamp() < now - window || request.timestamp() > now + window) {
            throw new IllegalArgumentException("request timestamp is outside recvWindow");
        }
        String expected = sign(totpService.decryptSecret(record.secretCiphertext()), request.canonical());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                request.signature().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("invalid api signature");
        }
        currentClient.authenticated(record.userId(), record.apiKey());
        repository.markUsed(record.apiKeyId(), Instant.now());
        return record.userId();
    }

    /** Recheck the stored key and current account/IP/scopes; an established socket does not retain grants. */
    public long requireWebSocketAccess(String apiKey, String ipAddress) {
        return requireAccess(apiKey, ipAddress, "READ").userId();
    }

    private GatewayApiKeyRepository.ApiKeyRecord requireAccess(String apiKey, String ipAddress, String permission) {
        var record = repository.active(apiKey).orElseThrow(() -> new IllegalArgumentException("invalid api key"));
        try {
            authService.requireApiKeyAccess(record.userId(), ipAddress, permission);
            requireIpAllowlist(record.ipAllowlist(), ipAddress);
            requirePermission(record.permissions(), permission);
            requireWriteIp(record.permissions(), record.ipAllowlist());
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ApiKeyAccessBlockedException(ex.getMessage(), ex);
        }
        return record;
    }

    public String sign(String secret, String canonicalQuery) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonicalQuery.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("api signature is unavailable", ex);
        }
    }

    private void requireSecurity(long userId, String emailCode, String totpCode) {
        if (!verificationService.verify(userId, "SECURITY_SETTINGS", emailCode, totpCode, Instant.now())) {
            throw new IllegalArgumentException("security verification is required or invalid");
        }
    }

    private ApiKeyView view(GatewayApiKeyRepository.ApiKeyRecord record) {
        return new ApiKeyView(record.apiKey(), record.label(), record.permissions(), record.status(),
                splitIpAllowlist(record.ipAllowlist()), record.createdAt(), record.lastUsedAt(), record.revokedAt());
    }

    private List<String> splitIpAllowlist(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private void requireIpAllowlist(String value, String remoteAddress) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (remoteAddress == null || remoteAddress.isBlank()
                || !clientIpResolver.isAllowed(remoteAddress, splitIpAllowlist(value))) {
            throw new IllegalArgumentException("api key IP address is not allowed");
        }
    }

    private static void requireWriteIp(String permissions, String ipAllowlist) {
        var scopes = Set.of(permissions.split(","));
        if ((scopes.contains("TRADE") || scopes.contains("WITHDRAW"))
                && (ipAllowlist == null || ipAllowlist.isBlank())) {
            throw new IllegalArgumentException("IP allowlist is required for TRADE or WITHDRAW permission");
        }
        if (scopes.contains("TRADE") || scopes.contains("WITHDRAW")) {
            for (String rule : ipAllowlist.split(",")) {
                int slash = rule.indexOf('/');
                if (slash >= 0 && Integer.parseInt(rule.substring(slash + 1)) == 0) {
                    throw new IllegalArgumentException("TRADE or WITHDRAW permission cannot use an unrestricted CIDR range");
                }
            }
        }
    }

    private void requirePermission(String permissions, String required) {
        if (required == null || required.isBlank()) return;
        Set<String> values = Set.of(permissions.split(","));
        if (!values.contains(required.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("api key permission is insufficient");
        }
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

}
