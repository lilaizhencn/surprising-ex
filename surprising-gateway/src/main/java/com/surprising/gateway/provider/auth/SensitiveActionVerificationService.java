package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensitiveActionVerificationService {

    private static final String PURPOSE = "SENSITIVE_ACTION";
    private static final Set<String> MANDATORY_SCENES = Set.of("WITHDRAWAL", "API_WITHDRAWAL");
    private static final Set<String> ALWAYS_VERIFIED_SCENES = Set.of("SECURITY_SETTINGS");
    private final GatewayProperties properties;
    private final AuthPersistenceService persistence;
    private final UserSecurityService securityService;
    private final GatewayAuthChallengeRepository challengeRepository;
    private final EmailMessageSender sender;
    private final TotpService totpService;
    private final SecureRandom secureRandom = new SecureRandom();

    public SensitiveActionVerificationService(GatewayProperties properties,
                                              AuthPersistenceService persistence,
                                              UserSecurityService securityService,
                                              GatewayAuthChallengeRepository challengeRepository,
                                              EmailMessageSender sender,
                                              TotpService totpService) {
        this.properties = properties;
        this.persistence = persistence;
        this.securityService = securityService;
        this.challengeRepository = challengeRepository;
        this.sender = sender;
        this.totpService = totpService;
    }

    @Transactional
    public IssuedChallenge issue(long userId, String sceneCode, String requestIp, Instant now) {
        String normalizedScene = normalizeScene(sceneCode);
        if (!isEnabled(userId, normalizedScene) && !ALWAYS_VERIFIED_SCENES.contains(normalizedScene)) {
            return new IssuedChallenge(0L, "", now);
        }
        var user = persistence.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        String destination = normalizeEmail(user.email());
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = now.plus(properties.getSecurity().getVerificationCodeTtl());
        var challenge = challengeRepository.create(userId, PURPOSE, "EMAIL", destination,
                digest(code, normalizedScene, destination), expiresAt, requestIp, now);
        sender.send(destination, "Verify your Surprising security action",
                "Your security verification code is " + code + ". It expires in 10 minutes. If you did not request this, ignore this email.");
        return new IssuedChallenge(challenge.challengeId(), destination, expiresAt);
    }

    @Transactional
    public boolean verify(long userId, String sceneCode, String emailCode, String totpCode, Instant now) {
        String normalizedScene = normalizeScene(sceneCode);
        if (!isEnabled(userId, normalizedScene) && !ALWAYS_VERIFIED_SCENES.contains(normalizedScene)) {
            return true;
        }
        var user = persistence.user(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        String destination = normalizeEmail(user.email());
        var challenge = challengeRepository.findActive(userId, PURPOSE, destination, now).orElse(null);
        if (challenge == null || challenge.attempts() >= 5 || emailCode == null || !emailCode.matches("\\d{6}")) {
            return false;
        }
        if (!MessageDigest.isEqual(digest(emailCode, normalizedScene, destination)
                .getBytes(StandardCharsets.UTF_8), challenge.codeHash().getBytes(StandardCharsets.UTF_8))) {
            challengeRepository.incrementAttempts(challenge.challengeId(), userId, now);
            return false;
        }
        var credential = persistence.mfaCredential(userId).orElse(null);
        if (credential == null || !credential.enabled()) {
            return challengeRepository.consume(challenge.challengeId(), userId, now);
        }
        String secret = totpService.decryptSecret(credential.totpSecretCiphertext());
        if (!totpService.verify(secret, totpCode, now)) {
            challengeRepository.incrementAttempts(challenge.challengeId(), userId, now);
            return false;
        }
        return challengeRepository.consume(challenge.challengeId(), userId, now);
    }

    private String digest(String code, String sceneCode, String destination) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((properties.getSecurity().getVerificationCodePepper() + "|"
                            + PURPOSE + "|" + sceneCode + "|" + destination + "|" + code)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("verification code digest unavailable", ex);
        }
    }

    private boolean isEnabled(long userId, String sceneCode) {
        return MANDATORY_SCENES.contains(sceneCode) || securityService.isSceneEnabled(userId, sceneCode);
    }

    private String normalizeScene(String sceneCode) {
        String normalized = sceneCode == null ? "" : sceneCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("security scene is required");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.contains("@")) {
            throw new IllegalArgumentException("email verification is unavailable for this account");
        }
        return normalized;
    }

    public record IssuedChallenge(long challengeId, String destination, Instant expiresAt) {
    }
}
