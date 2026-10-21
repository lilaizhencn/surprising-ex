package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TelegramLoginServiceTest {
    TelegramLoginRepository repository = mock(TelegramLoginRepository.class);
    TelegramOidcClient oidc = mock(TelegramOidcClient.class);
    AuthPersistenceService users = mock(AuthPersistenceService.class);
    AuthService auth = mock(AuthService.class);
    TelegramLoginService service = new TelegramLoginService(repository, oidc, users, auth);
    String state = "s".repeat(43), verifier = "v".repeat(43);
    MockHttpServletRequest http = new MockHttpServletRequest();

    @Test
    void startUsesIndependentRandomSecretsAndHashesBearerVerifier() {
        when(oidc.enabled()).thenReturn(true);
        var started = service.start("device-1");
        assertThat(started.flowId()).hasSize(43).isNotEqualTo(started.verifier());
        assertThat(started.expiresAt()).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(301));
        verify(repository).create(eq(TelegramLoginService.hash(started.flowId())),
                eq(TelegramLoginService.hash(started.verifier())), anyString(), anyString(), eq("device-1"), any());
    }

    @Test
    void missingConfigurationAndInvalidInputCannotStartLogin() {
        assertThatThrownBy(() -> service.start("device")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.start("")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void pendingAuthorizationCannotCreateUserOrSession() {
        when(repository.lockResult(any(), any(), any(), any()))
                .thenReturn(Optional.of(new TelegramLoginRepository.Identity(null, null, false)));
        assertThat(service.complete(state, verifier, "device", http)).isNull();
        verifyNoInteractions(users, auth);
    }

    @Test
    void wrongVerifierDeviceOrExpiredFlowCannotLogin() {
        assertThatThrownBy(() -> service.complete(state, verifier, "device", http))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(users, auth);
    }

    @Test
    void registersWithoutPasswordAndReturnsExistingMfaChallenge() {
        when(repository.lockResult(any(), any(), any(), any())).thenReturn(Optional.of(
                new TelegramLoginRepository.Identity("123", "小李", false)));
        when(users.createUser(isNull(), isNull(), isNull(), any()))
                .thenReturn(new AuthModels.AuthenticatedUser(7, null, null, "NORMAL", List.of("USER"), Instant.now()));
        var challenge = new LoginVerificationService.ChallengeResponse(true, "challenge", Instant.now().plusSeconds(300),
                List.of(new LoginVerificationService.Method("TOTP", null)), false);
        when(auth.loginTelegram(7, http)).thenReturn(challenge);
        assertThat(service.complete(state, verifier, "device", http)).isSameAs(challenge);
        verify(repository).bind(eq("123"), eq(7L), any());
        verify(users).ensureDefaultRole(eq(7L), any());
        verify(repository).updateNickname(eq(7L), eq("小李"), any());
        verify(repository).consume(TelegramLoginService.hash(state));
    }

    @Test
    void repeatLoginUsesSubjectEvenWhenNicknameChanges() {
        when(repository.lockResult(any(), any(), any(), any())).thenReturn(Optional.of(
                new TelegramLoginRepository.Identity("123", "新的昵称", false)));
        when(repository.lockIdentity("123")).thenReturn(Optional.of(7L));
        service.complete(state, verifier, "device", http);
        verifyNoInteractions(users);
        verify(repository).updateNickname(eq(7L), eq("新的昵称"), any());
        verify(auth).loginTelegram(7, http);
    }

    @Test
    void duplicateCallbackCannotExchangeAuthorizationCode() {
        assertThatThrownBy(() -> service.callback(state, "code", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(oidc);
    }

    @Test
    void providerRejectionFailsFlowWithoutTrustingClaims() {
        when(repository.claimCallback(any(), any())).thenReturn(Optional.of(new TelegramLoginRepository.Flow("nonce", "pkce")));
        assertThat(service.callback(state, null, "access_denied")).isFalse();
        verify(repository).failed(TelegramLoginService.hash(state));
        verifyNoInteractions(oidc);
    }

    @Test
    void forgedIdentityFailsFlowWithoutCreatingUser() {
        when(repository.claimCallback(any(), any())).thenReturn(Optional.of(new TelegramLoginRepository.Flow("nonce", "pkce")));
        when(oidc.exchange("code", "pkce", "nonce")).thenThrow(new IllegalArgumentException("forged"));
        assertThat(service.callback(state, "code", null)).isFalse();
        verify(repository).failed(TelegramLoginService.hash(state));
        verifyNoInteractions(users, auth);
    }
}
