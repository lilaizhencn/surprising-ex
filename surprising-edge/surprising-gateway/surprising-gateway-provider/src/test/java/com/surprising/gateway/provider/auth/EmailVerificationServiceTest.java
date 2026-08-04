package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmailVerificationServiceTest {

    private final GatewayAuthChallengeRepository repository = mock(GatewayAuthChallengeRepository.class);
    private final EmailMessageSender sender = mock(EmailMessageSender.class);
    private final EmailVerificationService service = new EmailVerificationService(
            new GatewayProperties(), repository, sender);

    @Test
    void verificationCodeIsPersistedAsDigestAndSentThroughEmailProvider() {
        AtomicReference<String> storedDigest = new AtomicReference<>();
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.create(eq(42L), eq("EMAIL_VERIFY"), eq("EMAIL"), eq("user@example.com"),
                any(), any(), eq("127.0.0.1"), any()))
                .thenAnswer(invocation -> {
                    storedDigest.set(invocation.getArgument(4));
                    return new GatewayAuthChallengeRepository.Challenge(
                            11L, 42L, "EMAIL_VERIFY", "EMAIL", "user@example.com",
                            storedDigest.get(), now.plusSeconds(600), 0, null);
                });

        var response = service.issueEmailVerification(42L, "user@example.com", "127.0.0.1", now);

        assertThat(response.challengeId()).isEqualTo(11L);
        assertThat(storedDigest.get()).isNotBlank().doesNotMatch("\\d{6}");
        verify(sender).send(eq("user@example.com"), eq("Verify your Surprising account"),
                any(String.class));
    }

    @Test
    void verificationConsumesOnlyAnUnexpiredChallenge() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.findActive(eq(42L), eq("EMAIL_VERIFY"), eq("user@example.com"), eq(now)))
                .thenReturn(Optional.of(new GatewayAuthChallengeRepository.Challenge(
                        11L, 42L, "EMAIL_VERIFY", "EMAIL", "user@example.com",
                        service.digestForTest("123456", "EMAIL_VERIFY", "user@example.com"),
                        now.plusSeconds(600), 0, null)));
        when(repository.consume(eq(11L), eq(42L), any())).thenReturn(true);

        assertThat(service.verifyEmail(42L, "user@example.com", "123456", now)).isTrue();
        verify(repository).markEmailVerified(eq(42L), eq(now));
    }
}
