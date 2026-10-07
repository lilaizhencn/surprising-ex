package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MfaRecoveryServiceTest {
    private static final long USER_ID = 71L;
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final AuthPersistenceService users = mock(AuthPersistenceService.class);
    private final GatewayAuthChallengeRepository challenges = mock(GatewayAuthChallengeRepository.class);
    private final LoginVerificationService loginVerification = mock(LoginVerificationService.class);
    private final MfaRecoveryRepository recovery = mock(MfaRecoveryRepository.class);
    private final ComplianceKycRepository kyc = mock(ComplianceKycRepository.class);
    private final KycDocumentService documents = mock(KycDocumentService.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final GatewayProperties properties = new GatewayProperties();
    private final CapturingEmail email = new CapturingEmail();
    private final ObjectProvider<SmsMessageSender> smsProvider = mock(ObjectProvider.class);
    private final MfaRecoveryService service = new MfaRecoveryService(
            users, challenges, loginVerification, recovery, kyc, documents, passwords,
            properties, email, smsProvider);

    @BeforeEach
    void setUp() {
        properties.getSecurity().setVerificationCodePepper("test-recovery-pepper");
        when(users.credential(USER_ID)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                USER_ID, "test-user", "user@example.test", "+12025550123", "hash", "NORMAL", NOW)));
        when(users.mfaCredential(USER_ID)).thenReturn(Optional.of(new GatewayUserMfaRepository.MfaCredential(
                USER_ID, "encrypted-secret", true, NOW, NOW, NOW)));
        when(users.user(USER_ID)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                USER_ID, "test-user", "user@example.test", "NORMAL", List.of("USER"), NOW)));
        when(passwords.matches("correct-password", "hash")).thenReturn(true);
        when(recovery.hasPending(USER_ID)).thenReturn(false);
        when(kyc.find(USER_ID)).thenReturn(new KycProfile(USER_ID, "BASIC", "VERIFIED", "US",
                "PASSPORT", "SELF", null, 99L, NOW, null, null, NOW, NOW));
        when(documents.findForUser(USER_ID)).thenReturn(List.of(new KycDocument(
                1, USER_ID, "PASSPORT", "passport.png", "image/png", 120, "sha", "SUBMITTED", NOW, null)));
    }

    @Test
    void emailCodeSubmitsForReviewAndApprovalRemovesMfaAndStartsWithdrawalHold() throws Exception {
        when(loginVerification.settings(USER_ID)).thenReturn(List.of(
                new LoginVerificationService.Setting("EMAIL", true, true, "u***@example.test"),
                new LoginVerificationService.Setting("PHONE", true, false, "+***0123")));
        var challenge = issueChallenge("EMAIL");

        var issued = service.issue(USER_ID, "correct-password", "127.0.0.1", NOW);
        assertThat(issued.channel()).isEqualTo("EMAIL");
        assertThat(issued.destination()).isEqualTo("u***@example.test");
        assertThat(email.recipient.get()).isEqualTo("user@example.test");
        assertThat(email.code.get()).matches("\\d{6}");

        var pending = new MfaRecoveryRepository.RecoveryRequest(
                901, USER_ID, "PENDING", "I lost my phone", NOW, null, null, null, NOW);
        when(recovery.create(eq(USER_ID), eq("I lost my phone"), eq(NOW))).thenReturn(pending);
        when(challenges.findActive(eq(challenge.challengeId()), eq(USER_ID), eq("MFA_RECOVERY"), eq(NOW)))
                .thenReturn(Optional.of(challengeWithCode(email.code.get(), "EMAIL")));
        var submitted = service.submit(USER_ID, new MfaRecoveryService.RecoverySubmission(
                issued.challengeId(), "correct-password", email.code.get(), "I lost my phone"), NOW);

        assertThat(submitted.status()).isEqualTo("PENDING");
        verify(users, never()).disableMfa(anyLong(), any());

        var approved = new MfaRecoveryRepository.RecoveryRequest(
                901, USER_ID, "APPROVED", "I lost my phone", NOW, 8L, NOW,
                "identity documents checked", NOW);
        when(recovery.decide(901, 8, true, "identity documents checked", NOW)).thenReturn(approved);
        var result = service.decide(901, 8, true, "identity documents checked", NOW);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(users).disableMfa(USER_ID, NOW);
        verify(users).recordSecurityChange(USER_ID, NOW);
        assertThat(email.subjects).contains("Surprising authenticator recovery update");
    }

    @Test
    void phoneCodeIsSimulatedThroughSmsProviderAndRejectedReviewKeepsMfaEnabled() throws Exception {
        when(loginVerification.settings(USER_ID)).thenReturn(List.of(
                new LoginVerificationService.Setting("EMAIL", false, false, null),
                new LoginVerificationService.Setting("PHONE", true, true, "+***0123")));
        AtomicReference<String> smsDestination = new AtomicReference<>();
        AtomicReference<String> smsCode = new AtomicReference<>();
        when(smsProvider.getIfAvailable()).thenReturn((phone, code) -> {
            smsDestination.set(phone);
            smsCode.set(code);
        });
        var challenge = issueChallenge("PHONE");

        var issued = service.issue(USER_ID, "correct-password", "127.0.0.1", NOW);
        assertThat(issued.channel()).isEqualTo("PHONE");
        assertThat(issued.destination()).isEqualTo("***0123");
        assertThat(smsDestination.get()).isEqualTo("+12025550123");
        assertThat(smsCode.get()).matches("\\d{6}");

        when(challenges.findActive(eq(challenge.challengeId()), eq(USER_ID), eq("MFA_RECOVERY"), eq(NOW)))
                .thenReturn(Optional.of(challengeWithCode(smsCode.get(), "PHONE")));
        var pending = new MfaRecoveryRepository.RecoveryRequest(
                902, USER_ID, "PENDING", "device was reset", NOW, null, null, null, NOW);
        when(recovery.create(eq(USER_ID), eq("device was reset"), eq(NOW))).thenReturn(pending);
        assertThat(service.submit(USER_ID, new MfaRecoveryService.RecoverySubmission(
                issued.challengeId(), "correct-password", smsCode.get(), "device was reset"), NOW).status())
                .isEqualTo("PENDING");

        var rejected = new MfaRecoveryRepository.RecoveryRequest(
                902, USER_ID, "REJECTED", "device was reset", NOW, 8L, NOW,
                "documents need clarification", NOW);
        when(recovery.decide(902, 8, false, "documents need clarification", NOW)).thenReturn(rejected);
        assertThat(service.decide(902, 8, false, "documents need clarification", NOW).status())
                .isEqualTo("REJECTED");
        verify(users, never()).disableMfa(anyLong(), any());
        verify(users, never()).recordSecurityChange(anyLong(), any());
    }

    @Test
    void recoveryRequiresVerifiedKycAndCurrentPassword() {
        when(loginVerification.settings(USER_ID)).thenReturn(List.of(
                new LoginVerificationService.Setting("EMAIL", true, true, "u***@example.test")));
        when(passwords.matches("wrong-password", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.issue(USER_ID, "wrong-password", "127.0.0.1", NOW))
                .hasMessage("current password is invalid");
        assertThat(email.recipient.get()).isNull();

        when(passwords.matches("correct-password", "hash")).thenReturn(true);
        when(kyc.find(USER_ID)).thenReturn(new KycProfile(USER_ID, "BASIC", "PENDING", "US",
                "PASSPORT", "SELF", null, null, null, null, null, NOW, NOW));
        assertThatThrownBy(() -> service.issue(USER_ID, "correct-password", "127.0.0.1", NOW))
                .hasMessageContaining("verified KYC");
    }

    private GatewayAuthChallengeRepository.Challenge issueChallenge(String channel) {
        var challenge = new GatewayAuthChallengeRepository.Challenge(
                301, USER_ID, "MFA_RECOVERY", channel,
                "EMAIL".equals(channel) ? "user@example.test" : "+12025550123",
                "pending-hash", NOW.plusSeconds(300), 0, null);
        when(challenges.findActive(eq(USER_ID), eq("MFA_RECOVERY"), eq(challenge.destination()), eq(NOW)))
                .thenReturn(Optional.empty());
        when(challenges.create(eq(USER_ID), eq("MFA_RECOVERY"), eq(channel), eq(challenge.destination()),
                anyString(), eq(NOW.plusSeconds(300)), eq("127.0.0.1"), eq(NOW))).thenReturn(challenge);
        when(challenges.consume(eq(challenge.challengeId()), eq(USER_ID), eq(NOW))).thenReturn(true);
        return challenge;
    }

    private GatewayAuthChallengeRepository.Challenge challengeWithCode(String code, String channel)
            throws Exception {
        String destination = "EMAIL".equals(channel) ? "user@example.test" : "+12025550123";
        String payload = "test-recovery-pepper|MFA_RECOVERY|" + USER_ID + "|" + destination + "|" + code;
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        return new GatewayAuthChallengeRepository.Challenge(
                301, USER_ID, "MFA_RECOVERY", channel, destination, hash, NOW.plusSeconds(300), 0, null);
    }

    private static final class CapturingEmail implements EmailMessageSender {
        private final AtomicReference<String> recipient = new AtomicReference<>();
        private final AtomicReference<String> code = new AtomicReference<>();
        private final List<String> subjects = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void send(String recipient, String subject, String text) {
            this.recipient.set(recipient);
            subjects.add(subject);
            var matcher = java.util.regex.Pattern.compile("\\b\\d{6}\\b").matcher(text);
            if (matcher.find()) code.set(matcher.group());
        }
    }
}
