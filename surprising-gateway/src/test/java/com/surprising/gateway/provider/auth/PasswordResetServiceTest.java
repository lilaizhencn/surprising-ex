package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordResetServiceTest {

    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final GatewayAuthChallengeRepository challenges = mock(GatewayAuthChallengeRepository.class);
    private final EmailMessageSender sender = mock(EmailMessageSender.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final PasswordResetService service = new PasswordResetService(
            new GatewayProperties(), persistence, challenges, sender, passwordHasher);

    @Test
    void resetRequestDoesNotRevealWhetherEmailExists() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(persistence.credentialByEmail("user@example.com")).thenReturn(Optional.empty());

        var response = service.requestPasswordReset("user@example.com", "127.0.0.1", now);

        assertThat(response.accepted()).isTrue();
    }

    @Test
    void validResetCodeChangesPasswordAndRevokesSessions() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(persistence.credentialByEmail("user@example.com")).thenReturn(Optional.of(
                new GatewayUserRepository.UserCredential(42L, null, "user@example.com", "hash", "NORMAL", now)));
        when(challenges.create(eq(42L), eq("PASSWORD_RESET"), eq("EMAIL"), eq("user@example.com"),
                any(), any(), eq("127.0.0.1"), any()))
                .thenReturn(new GatewayAuthChallengeRepository.Challenge(
                        12L, 42L, "PASSWORD_RESET", "EMAIL", "user@example.com",
                        service.digestForTest("123456", "PASSWORD_RESET", "user@example.com"),
                        now.plusSeconds(600), 0, null));
        when(challenges.findActive(eq(42L), eq("PASSWORD_RESET"), eq("user@example.com"), eq(now)))
                .thenReturn(Optional.of(new GatewayAuthChallengeRepository.Challenge(
                        12L, 42L, "PASSWORD_RESET", "EMAIL", "user@example.com",
                        service.digestForTest("123456", "PASSWORD_RESET", "user@example.com"),
                        now.plusSeconds(600), 0, null)));
        when(challenges.consume(eq(12L), eq(42L), eq(now))).thenReturn(true);
        when(passwordHasher.hash("NewPassword1!")).thenReturn("new-hash");

        service.requestPasswordReset("USER@EXAMPLE.COM", "127.0.0.1", now);
        assertThat(service.resetPassword("user@example.com", "123456", "NewPassword1!", now).accepted())
                .isTrue();

        verify(persistence).updatePasswordHash(eq(42L), eq("new-hash"), eq(now));
        verify(persistence).revokeUserRefreshSessions(eq(42L), eq(now));
    }
}
