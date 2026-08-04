package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SensitiveActionVerificationServiceTest {

    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final UserSecurityService securityService = mock(UserSecurityService.class);
    private final GatewayAuthChallengeRepository challengeRepository = mock(GatewayAuthChallengeRepository.class);
    private final EmailMessageSender sender = mock(EmailMessageSender.class);
    private final TotpService totpService = mock(TotpService.class);
    private final SensitiveActionVerificationService service = new SensitiveActionVerificationService(
            new GatewayProperties(), persistence, securityService, challengeRepository, sender, totpService);

    @Test
    void disabledSceneDoesNotRequireOrIssueVerification() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        when(securityService.isSceneEnabled(42L, "TRANSFER")).thenReturn(false);

        var challenge = service.issue(42L, "TRANSFER", "127.0.0.1", now);
        boolean verified = service.verify(42L, "TRANSFER", null, null, now);

        assertThat(challenge.challengeId()).isZero();
        assertThat(verified).isTrue();
        verifyNoInteractions(persistence, challengeRepository, sender, totpService);
    }

    @Test
    void emailVerificationIsFollowedByTotpWhenMfaIsEnabled() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        when(securityService.isSceneEnabled(42L, "WITHDRAWAL")).thenReturn(true);
        when(persistence.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, null, "user@example.com", "NORMAL", List.of("USER"), now)));
        when(challengeRepository.findActive(42L, "SENSITIVE_ACTION", "user@example.com", now))
                .thenReturn(Optional.of(new GatewayAuthChallengeRepository.Challenge(
                        1L, 42L, "SENSITIVE_ACTION", "EMAIL", "user@example.com",
                        digest("123456", "WITHDRAWAL", "user@example.com"), now.plusSeconds(600), 0, null)));
        when(challengeRepository.consume(1L, 42L, now)).thenReturn(true);
        when(persistence.mfaCredential(42L)).thenReturn(Optional.of(new GatewayUserMfaRepository.MfaCredential(
                42L, "cipher", true, now, now, now)));
        when(totpService.decryptSecret("cipher")).thenReturn("SECRET");
        when(totpService.verify("SECRET", "654321", now)).thenReturn(true);

        assertThat(service.verify(42L, "WITHDRAWAL", "123456", "654321", now)).isTrue();
    }

    private String digest(String code, String scene, String destination) {
        try {
            var bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("local-dev-verification-pepper-change-me|SENSITIVE_ACTION|"
                            + scene + "|" + destination + "|" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
