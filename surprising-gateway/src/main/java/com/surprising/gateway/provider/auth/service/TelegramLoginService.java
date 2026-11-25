package com.surprising.gateway.provider.auth.service;


import com.surprising.gateway.provider.auth.model.LoginResult;
import com.surprising.gateway.provider.auth.client.TelegramOidcClient;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;

import com.surprising.gateway.provider.auth.repository.TelegramLoginFlowRepository;
import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.surprising.gateway.provider.auth.utils.AuthCodeFormats;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramLoginService {
    private final TelegramLoginFlowRepository flows;
    private final TelegramOidcClient oidc;
    private final TelegramIdentityService identities;
    private final GatewayUserRepository userRows;
    private final AuthService auth;
    private final com.surprising.gateway.provider.telegram.bot.service.TelegramWebAppInitDataVerifier webAppVerifier;
    private final com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties botProperties;

    public TelegramLoginService(TelegramLoginFlowRepository flows, TelegramIdentityService identities, GatewayUserRepository userRows, TelegramOidcClient oidc,
                                AuthService auth) {
        this(flows, identities, userRows, oidc, auth, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramLoginService(TelegramLoginFlowRepository flows, TelegramIdentityService identities, GatewayUserRepository userRows, TelegramOidcClient oidc,
                                AuthService auth,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                com.surprising.gateway.provider.telegram.bot.service.TelegramWebAppInitDataVerifier webAppVerifier,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                com.surprising.gateway.provider.telegram.bot.config.TelegramBotProperties botProperties) {
        this.flows = flows;
        this.identities = identities;
        this.userRows = userRows;
        this.oidc = oidc;
        this.auth = auth;
        this.webAppVerifier = webAppVerifier;
        this.botProperties = botProperties;
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
    public LoginResult complete(String state, String verifier, String deviceId,
                                           AuthClientMetadata request) {
        requireToken(state);
        requireToken(verifier);
        requireDevice(deviceId);
        String key = hash(state);
        var result = flows.lockResult(key, hash(verifier), deviceId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("Telegram login expired or already used"));
        if (result.failed()) throw new IllegalArgumentException("Telegram authorization was not completed");
        if (result.subject() == null) return null;
        Instant now = Instant.now();
        // No password, email, phone or nickname is used to identify or auto-link an account.
        long userId = identities.registerOrLogin(result.subject(), null);
        userRows.updateNickname(userId, result.displayName(), now);
        var login = auth.loginTelegram(userId, request);
        flows.consume(key);
        return login;
    }

    private String random() {
        byte[] value = new byte[32];
        AuthCodeFormats.SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String value) {
        return com.surprising.gateway.provider.support.Sha256Digests.base64Url(value);
    }

    private static void requireToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("invalid Telegram login request");
    }

    private static void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128)
            throw new IllegalArgumentException("device identifier is required");
    }

    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public LoginResult loginWebApp(String initData, AuthClientMetadata request) {
        if (webAppVerifier == null || botProperties == null || !botProperties.isConfigured()) {
            throw new IllegalStateException("Telegram bot token is not configured");
        }
        var verifiedOpt = webAppVerifier.verify(initData, botProperties.getToken());
        if (verifiedOpt.isEmpty()) {
            throw new IllegalArgumentException("invalid Telegram WebApp initData");
        }
        var user = verifiedOpt.get();
        String subject = String.valueOf(user.telegramId());
        Instant now = Instant.now();
        long userId = identities.registerOrLogin(subject, null);
        userRows.updateNickname(userId, user.getDisplayName(), now);
        return auth.loginTelegram(userId, request);
    }

    public record Callback(boolean success, String returnUrl) {}
    public record Started(String flowId, String verifier, String authorizationUrl, Instant expiresAt) {}
}
