package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository;
import com.surprising.gateway.provider.auth.repository.TelegramLoginFlowRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramLoginService {
    private final TelegramLoginFlowRepository flows;
    private final TelegramOidcClient oidc;
    private final TelegramIdentityRepository identities;
    private final GatewayUserRepository userRows;
    private final AuthPersistenceService users;
    private final AuthService auth;
    private final SecureRandom random = new SecureRandom();

    public TelegramLoginService(TelegramLoginFlowRepository flows, TelegramIdentityRepository identities, GatewayUserRepository userRows, TelegramOidcClient oidc,
                                AuthPersistenceService users, AuthService auth) {
        this.flows = flows;
        this.identities = identities;
        this.userRows = userRows;
        this.oidc = oidc;
        this.users = users;
        this.auth = auth;
    }

    public boolean enabled() { return oidc.enabled(); }

    @Transactional
    public Started start(String deviceId, boolean returnToApp) {
        requireDevice(deviceId);
        if (!enabled()) throw new IllegalStateException("Telegram login is not configured");
        flows.lockDevice(deviceId);
        flows.removeExpired();
        if (flows.activeCount(deviceId) >= 5) throw new IllegalArgumentException("too many Telegram login attempts");
        String state = random(), verifier = random(), nonce = random(), pkce = random();
        Instant expires = Instant.now().plusSeconds(300);
        flows.create(hash(state), hash(verifier), nonce, pkce, deviceId, expires, returnToApp);
        return new Started(state, verifier, oidc.authorizationUrl(state, nonce, hash(pkce)), expires);
    }

    /** Claim is committed before network I/O; duplicate callbacks cannot exchange the code twice. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Callback callback(String state, String code, String error) {
        requireToken(state);
        String key = hash(state);
        var flow = flows.claimCallback(key, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("Telegram login expired or already used"));
        String returnUrl = flow.returnToApp() ? "surprising://auth/telegram?flowId=" + state : null;
        if (error != null || code == null || code.isBlank() || code.length() > 4096) {
            flows.failed(key);
            return new Callback(false, returnUrl);
        }
        try {
            var profile = oidc.exchange(code, flow.codeVerifier(), flow.nonce());
            flows.authorized(key, profile.subject(), profile.displayName());
            return new Callback(true, returnUrl);
        } catch (RuntimeException failure) {
            // Never reflect provider tokens, authorization codes or remote error bodies.
            flows.failed(key);
            return new Callback(false, returnUrl);
        }
    }

    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public AuthModels.LoginResult complete(String state, String verifier, String deviceId,
                                           HttpServletRequest request) {
        requireToken(state);
        requireToken(verifier);
        requireDevice(deviceId);
        String key = hash(state);
        var result = flows.lockResult(key, hash(verifier), deviceId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("Telegram login expired or already used"));
        if (result.failed()) throw new IllegalArgumentException("Telegram authorization was not completed");
        if (result.subject() == null) return null;
        Instant now = Instant.now();
        long userId = identities.lockIdentity(result.subject()).orElseGet(() -> {
            // No password, email, phone or nickname is used to identify or auto-link an account.
            var user = users.createUser(null, null, null, now);
            users.ensureDefaultRole(user.userId(), now);
            identities.bind(result.subject(), user.userId(), now);
            return user.userId();
        });
        userRows.updateNickname(userId, result.displayName(), now);
        var login = auth.loginTelegram(userId, request);
        flows.consume(key);
        return login;
    }

    private String random() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void requireToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("invalid Telegram login request");
    }

    private static void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128)
            throw new IllegalArgumentException("device identifier is required");
    }

    public record Callback(boolean success, String returnUrl) {}
    public record Started(String flowId, String verifier, String authorizationUrl, Instant expiresAt) {}
}
