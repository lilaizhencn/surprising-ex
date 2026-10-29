package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.auth.model.AuthClientMetadata;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginResult;
import com.surprising.gateway.provider.auth.repository.GoogleLoginRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleLoginService {
    private final GoogleLoginRepository identities;
    private final GoogleIdentityClient google;
    private final GatewayUserRepository userRows;
    private final AuthPersistenceService users;
    private final AuthService auth;
    private final com.surprising.affiliate.service.AffiliateService affiliates;
    private final SecureRandom random = new SecureRandom();

    public GoogleLoginService(GoogleLoginRepository identities, GoogleIdentityClient google,
                              GatewayUserRepository userRows, AuthPersistenceService users, AuthService auth, com.surprising.affiliate.service.AffiliateService affiliates) {
        this.identities = identities;
        this.google = google;
        this.userRows = userRows;
        this.users = users;
        this.auth = auth;
        this.affiliates = affiliates;
    }

    @Transactional
    public Challenge challenge(String device) {
        requireDevice(device);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plusSeconds(300);
        identities.createChallenge(hash(nonce), device, expires);
        return new Challenge(nonce, expires);
    }

    @Transactional(noRollbackFor = LoginVerificationService.VerificationFailure.class)
    public LoginResult login(String credential, String nonce, String platform, String inviteCode, AuthClientMetadata request) {
        requireDevice(request.deviceId());
        if ("WEB".equals(platform) && (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}")))
            throw new IllegalArgumentException("Invalid Google login challenge");
        var profile = google.verify(credential, nonce, platform);
        Instant now = Instant.now();
        if ("WEB".equals(platform) && !identities.consumeChallenge(hash(nonce), request.deviceId(), now))
            throw new IllegalArgumentException("Google login expired or already used");
        long userId = identities.lockIdentity(profile.subject()).orElseGet(() -> {
            // Email is not an account linking credential, especially for funded exchange accounts.
            var user = users.createUser(null, null, null, now);
            users.ensureDefaultRole(user.userId(), now);
            affiliates.register(user.userId(), inviteCode);
            identities.bind(profile.subject(), user.userId(), now);
            return user.userId();
        });
        userRows.updateNickname(userId, profile.displayName(), now);
        return auth.loginGoogle(userId, request);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireDevice(String device) {
        if (device == null || device.isBlank() || device.length() > 128)
            throw new IllegalArgumentException("Device identifier is required");
    }
    public record Challenge(String nonce, Instant expiresAt) {}
}
