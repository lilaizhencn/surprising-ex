package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaRecoveryService {
    private static final String PURPOSE = "MFA_RECOVERY";
    private final AuthPersistenceService users;
    private final GatewayAuthChallengeRepository challenges;
    private final LoginVerificationService loginVerification;
    private final MfaRecoveryRepository recovery;
    private final ComplianceKycRepository kyc;
    private final KycDocumentService documents;
    private final PasswordHasher passwords;
    private final GatewayProperties properties;
    private final EmailMessageSender email;
    private final ObjectProvider<SmsMessageSender> sms;
    private final SecureRandom random = new SecureRandom();

    public MfaRecoveryService(AuthPersistenceService users, GatewayAuthChallengeRepository challenges,
                              LoginVerificationService loginVerification,
                              MfaRecoveryRepository recovery, ComplianceKycRepository kyc,
                              KycDocumentService documents, PasswordHasher passwords,
                              GatewayProperties properties, EmailMessageSender email,
                              ObjectProvider<SmsMessageSender> sms) {
        this.users = users; this.challenges = challenges; this.loginVerification = loginVerification;
        this.recovery = recovery;
        this.kyc = kyc; this.documents = documents; this.passwords = passwords;
        this.properties = properties; this.email = email; this.sms = sms;
    }

    @Transactional
    public RecoveryChallenge issue(long userId, String currentPassword, String requestIp, Instant now) {
        requirePassword(userId, currentPassword);
        requireRecoveryEligible(userId);
        if (recovery.hasPending(userId)) throw new IllegalStateException("MFA recovery is already under review");
        var account = users.credential(userId).orElseThrow();
        String channel;
        String destination;
        String emailAddress = account.email() == null ? "" : account.email().trim().toLowerCase(Locale.ROOT);
        String phoneNumber = account.phone() == null ? "" : account.phone().trim();
        List<LoginVerificationService.Setting> methods = loginVerification.settings(userId);
        boolean emailBound = methods.stream().anyMatch(setting -> setting.type().equals("EMAIL") && setting.bound());
        boolean phoneBound = methods.stream().anyMatch(setting -> setting.type().equals("PHONE") && setting.bound());
        if (emailBound && emailAddress.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            channel = "EMAIL"; destination = emailAddress;
        } else if (phoneBound && phoneNumber.matches("\\+[1-9][0-9]{6,14}")) {
            channel = "PHONE"; destination = phoneNumber;
        } else if (emailAddress.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            channel = "EMAIL"; destination = emailAddress;
        } else if (phoneNumber.matches("\\+[1-9][0-9]{6,14}")) {
            channel = "PHONE"; destination = phoneNumber;
        } else throw new IllegalStateException("No verified email or phone is available for recovery");
        if (challenges.findActive(userId, PURPOSE, destination, now).isPresent()) {
            throw new IllegalStateException("A recovery code was already sent. Check your inbox or messages.");
        }
        boolean simulated = properties.getSecurity().isSimulatedVerificationCodesEnabled();
        String code = simulated ? "123456" : String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        var challenge = challenges.create(userId, PURPOSE, channel, destination,
                digest(code, userId, destination), now.plusSeconds(300), requestIp, now);
        try {
            if (simulated) return new RecoveryChallenge(challenge.challengeId(), channel,
                    mask(destination), challenge.expiresAt(), true);
            if ("EMAIL".equals(channel)) {
                email.send(destination, "Surprising MFA recovery code",
                        "Your MFA recovery code is " + code + ". It expires in 5 minutes.");
            } else {
                SmsMessageSender sender = sms.getIfAvailable();
                if (sender == null) throw new IllegalStateException("SMS recovery is not configured");
                sender.send(destination, code);
            }
        } catch (RuntimeException ex) {
            challenges.consume(challenge.challengeId(), userId, now);
            throw new IllegalStateException("Recovery code delivery failed", ex);
        }
        return new RecoveryChallenge(challenge.challengeId(), channel, mask(destination), challenge.expiresAt(), false);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public MfaRecoveryRepository.RecoveryRequest submit(long userId, RecoverySubmission request, Instant now) {
        requirePassword(userId, request.currentPassword());
        requireRecoveryEligible(userId);
        if (recovery.hasPending(userId)) throw new IllegalStateException("MFA recovery is already under review");
        var account = users.credential(userId).orElseThrow();
        var challenge = challenges.findActive(request.challengeId(), userId, PURPOSE, now)
                .orElseThrow(() -> new IllegalArgumentException("recovery code expired; request a new code"));
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 8 || reason.length() > 500) {
            throw new IllegalArgumentException("explain how you lost access to the authenticator");
        }
        String expected = digest(request.code(), userId, challenge.destination());
        if (request.code() == null || !request.code().matches("\\d{6}")
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        challenge.codeHash().getBytes(StandardCharsets.UTF_8))) {
            challenges.incrementAttempts(challenge.challengeId(), userId, now);
            throw new IllegalArgumentException("recovery code is invalid");
        }
        if (!challenges.consume(challenge.challengeId(), userId, now)) {
            throw new IllegalArgumentException("recovery code expired; request a new code");
        }
        return recovery.create(userId, reason, now);
    }

    public MfaRecoveryRepository.RecoveryRequest status(long userId) {
        return recovery.latestForUser(userId);
    }

    public List<MfaRecoveryRepository.RecoveryRequest> pending() { return recovery.pending(); }

    @Transactional
    public MfaRecoveryRepository.RecoveryRequest decide(long requestId, long adminUserId,
                                                        boolean approved, String reason, Instant now) {
        MfaRecoveryRepository.RecoveryRequest request = recovery.decide(requestId, adminUserId,
                approved, reason, now);
        if (approved) {
            users.disableMfa(request.userId(), now);
            users.recordSecurityChange(request.userId(), now);
        }
        users.user(request.userId()).map(AuthModels.AuthenticatedUser::email)
                .filter(address -> address != null && !address.isBlank())
                .ifPresent(address -> {
                    try {
                        email.send(address, "Surprising authenticator recovery update",
                                approved ? "Your identity verification passed. The previous authenticator was removed. "
                                        + "Withdrawals will be available after the 24-hour security hold."
                                        : "Your authenticator recovery request was not approved. "
                                        + (reason == null ? "" : "Reason: " + reason));
                    } catch (RuntimeException ignored) {
                        // The durable review decision must survive a temporary email delivery failure.
                    }
                });
        return request;
    }

    private void requireRecoveryEligible(long userId) {
        var credential = users.mfaCredential(userId).orElse(null);
        if (credential == null || !credential.enabled()) {
            throw new IllegalStateException("Authenticator recovery is only available for an enrolled account");
        }
        var profile = kyc.find(userId);
        if (profile == null || !"VERIFIED".equalsIgnoreCase(profile.status())
                || documents.findForUser(userId).isEmpty()) {
            throw new IllegalStateException("Complete verified KYC before requesting authenticator recovery");
        }
    }

    private void requirePassword(long userId, String password) {
        var credential = users.credential(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!passwords.matches(password, credential.passwordHash())) {
            throw new IllegalArgumentException("current password is invalid");
        }
    }

    private String digest(String code, long userId, String destination) {
        try {
            String payload = properties.getSecurity().getVerificationCodePepper() + "|" + PURPOSE + "|"
                    + userId + "|" + destination + "|" + code;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("recovery code digest unavailable", ex); }
    }

    private String mask(String destination) {
        int at = destination.indexOf('@');
        return at > 0 ? destination.substring(0, 1) + "***" + destination.substring(at)
                : "***" + destination.substring(Math.max(0, destination.length() - 4));
    }

    public record RecoveryChallenge(long challengeId, String channel, String destination, Instant expiresAt,
                                    boolean simulated) {}
    public record RecoverySubmission(long challengeId, String currentPassword, String code, String reason) {}
}
