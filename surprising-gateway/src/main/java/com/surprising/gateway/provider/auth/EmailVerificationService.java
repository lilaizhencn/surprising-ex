package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    private static final String PURPOSE = "EMAIL_VERIFY";
    private final GatewayProperties properties;
    private final GatewayAuthChallengeRepository repository;
    private final EmailMessageSender sender;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(GatewayProperties properties,
                                    GatewayAuthChallengeRepository repository,
                                    EmailMessageSender sender) {
        this.properties = properties;
        this.repository = repository;
        this.sender = sender;
    }

    @Transactional
    public IssuedChallenge issueEmailVerification(long userId, String email, String requestIp, Instant now) {
        String destination = normalizeEmail(email);
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = now.plus(properties.getSecurity().getVerificationCodeTtl());
        GatewayAuthChallengeRepository.Challenge challenge = repository.create(userId, PURPOSE, "EMAIL", destination,
                digest(code, PURPOSE, destination), expiresAt, requestIp, now);
        sender.send(destination, "Verify your Surprising account",
                "Your verification code is " + code + ". It expires in 10 minutes. If you did not request this, ignore this email.");
        return new IssuedChallenge(challenge.challengeId(), destination, expiresAt);
    }

    @Transactional
    public boolean verifyEmail(long userId, String email, String code, Instant now) {
        String destination = normalizeEmail(email);
        GatewayAuthChallengeRepository.Challenge challenge = repository
                .findActive(userId, PURPOSE, destination, now)
                .orElse(null);
        if (challenge == null || challenge.attempts() >= 5 || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        String expected = digest(code, PURPOSE, destination);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                challenge.codeHash().getBytes(StandardCharsets.UTF_8))) {
            repository.incrementAttempts(challenge.challengeId(), userId, now);
            return false;
        }
        if (!repository.consume(challenge.challengeId(), userId, now)) {
            return false;
        }
        repository.markEmailVerified(userId, now);
        return true;
    }

    String digestForTest(String code, String purpose, String destination) {
        return digest(code, purpose, destination);
    }

    private String digest(String code, String purpose, String destination) {
        try {
            String pepper = properties.getSecurity().getVerificationCodePepper();
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((pepper + "|" + purpose + "|" + destination + "|" + code)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("verification code digest unavailable", ex);
        }
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    public record IssuedChallenge(long challengeId, String destination, Instant expiresAt) {
    }
}
