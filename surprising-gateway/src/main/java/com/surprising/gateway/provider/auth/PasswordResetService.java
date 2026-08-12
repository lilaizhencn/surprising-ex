package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    private static final String PURPOSE = "PASSWORD_RESET";
    private final GatewayProperties properties;
    private final AuthPersistenceService persistence;
    private final GatewayAuthChallengeRepository challenges;
    private final EmailMessageSender sender;
    private final PasswordHasher passwordHasher;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(GatewayProperties properties,
                                AuthPersistenceService persistence,
                                GatewayAuthChallengeRepository challenges,
                                EmailMessageSender sender,
                                PasswordHasher passwordHasher) {
        this.properties = properties;
        this.persistence = persistence;
        this.challenges = challenges;
        this.sender = sender;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public ResetResult requestPasswordReset(String identifier, String requestIp, Instant now) {
        String normalized = normalizeIdentifier(identifier);
        Optional<GatewayUserRepository.UserCredential> credential = credential(normalized);
        if (credential.isPresent() && credential.get().email() != null) {
            String email = credential.get().email().toLowerCase(Locale.ROOT);
            String code = String.format("%06d", secureRandom.nextInt(1_000_000));
            Instant expiresAt = now.plus(properties.getSecurity().getVerificationCodeTtl());
            challenges.create(credential.get().userId(), PURPOSE, "EMAIL", email,
                    digest(code, PURPOSE, email), expiresAt, requestIp, now);
            sender.send(email, "Reset your Surprising password",
                    "Your password reset code is " + code + ". It expires in 10 minutes. If you did not request this, ignore this email.");
        }
        return new ResetResult(true);
    }

    @Transactional
    public ResetResult resetPassword(String identifier, String code, String newPassword, Instant now) {
        validatePassword(newPassword);
        String normalized = normalizeIdentifier(identifier);
        GatewayUserRepository.UserCredential credential = credential(normalized).orElse(null);
        if (credential == null || credential.email() == null || code == null || !code.matches("\\d{6}")) {
            return new ResetResult(false);
        }
        String email = credential.email().toLowerCase(Locale.ROOT);
        GatewayAuthChallengeRepository.Challenge challenge = challenges
                .findActive(credential.userId(), PURPOSE, email, now).orElse(null);
        if (challenge == null || challenge.attempts() >= 5) {
            return new ResetResult(false);
        }
        String expected = digest(code, PURPOSE, email);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                challenge.codeHash().getBytes(StandardCharsets.UTF_8))) {
            challenges.incrementAttempts(challenge.challengeId(), credential.userId(), now);
            return new ResetResult(false);
        }
        if (!challenges.consume(challenge.challengeId(), credential.userId(), now)) {
            return new ResetResult(false);
        }
        persistence.updatePasswordHash(credential.userId(), passwordHasher.hash(newPassword), now);
        persistence.revokeUserRefreshSessions(credential.userId(), now);
        return new ResetResult(true);
    }

    String digestForTest(String code, String purpose, String destination) {
        return digest(code, purpose, destination);
    }

    private Optional<GatewayUserRepository.UserCredential> credential(String identifier) {
        if (identifier.contains("@")) {
            return persistence.credentialByEmail(identifier);
        }
        if (identifier.startsWith("+") && properties.getSecurity().isPhoneRegistrationEnabled()) {
            return persistence.credentialByPhone(identifier);
        }
        return Optional.empty();
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("email or phone is required");
        }
        String normalized = identifier.trim();
        if (normalized.contains("@")) {
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (normalized.length() > 254 || !normalized.contains("@")) {
                throw new IllegalArgumentException("invalid email");
            }
            return normalized;
        }
        if (!normalized.matches("\\+[1-9]\\d{7,14}")) {
            throw new IllegalArgumentException("email or phone is required");
        }
        return normalized;
    }

    private String digest(String code, String purpose, String destination) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((properties.getSecurity().getVerificationCodePepper() + "|"
                            + purpose + "|" + destination + "|" + code).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("verification code digest unavailable", ex);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password length must be 8-128");
        }
    }

    public record ResetResult(boolean accepted) {
    }
}
