package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.support.AuthClientMetadataBinding;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.EmailVerificationService;
import com.surprising.gateway.provider.auth.service.JwtTokenService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.PasswordHasher;
import com.surprising.gateway.provider.auth.state.AuthStateFixture;
import com.surprising.gateway.provider.auth.state.SessionState;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperatingAccountSessionTest {

    private final AuthPersistenceService repository = mock(AuthPersistenceService.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final AuthStateFixture fixture = new AuthStateFixture();
    private final AuthService service = new AuthService(new GatewayProperties(), repository,
            mock(PasswordHasher.class), jwtTokenService, mock(EmailVerificationService.class),
            mock(LoginVerificationService.class), mock(GatewayUserAccessBlockRepository.class),
            new AuthClientMetadataBinding(new GatewayProperties(),
                    mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class),
                    mock(com.surprising.gateway.provider.support.GatewayLimitRequestBinding.class)),
            mock(com.surprising.affiliate.service.AffiliateService.class),
            mock(com.surprising.gateway.provider.legal.service.LegalConsentService.class),
            fixture.state, mock(com.surprising.gateway.provider.auth.state.AuthStateSync.class));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void switchToOwnedSubAndBackUsesSessionAttribute() {
        JwtPrincipal parent = loggedIn(42L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 42L));

        var switched = service.switchOperatingAccount(parent, 2001L);
        assertThat(switched.user().userId()).isEqualTo(42L);
        assertThat(switched.operatingAccount().userId()).isEqualTo(2001L);
        assertThat(switched.operatingAccount().parent()).isFalse();
        assertThat(fixture.state.session(parent.sessionId()).effectiveOperatingUserId()).isEqualTo(2001L);

        when(jwtTokenService.verifySessionToken("token")).thenReturn(parent);
        var verified = authenticate("Bearer token");
        assertThat(verified.userId()).isEqualTo(42L);
        assertThat(verified.operatingUserId()).isEqualTo(2001L);

        var restored = service.switchOperatingAccount(parent, 42L);
        assertThat(restored.operatingAccount().userId()).isEqualTo(42L);
        assertThat(restored.operatingAccount().parent()).isTrue();
        assertThat(fixture.state.session(parent.sessionId()).effectiveOperatingUserId()).isEqualTo(42L);
        verifyNoInteractions(repository);
    }

    @Test
    void switchRejectsAnotherParentsSubAccount() {
        JwtPrincipal parent = loggedIn(42L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 99L));
        assertThatThrownBy(() -> service.switchOperatingAccount(parent, 2001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available for this user");
        assertThat(fixture.state.session(parent.sessionId()).effectiveOperatingUserId()).isEqualTo(42L);
    }

    @Test
    void requestDoesNotFallBackToParentWhenOperatingAccountIsUnconfirmed() {
        JwtPrincipal parent = loggedIn(42L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 42L));
        service.switchOperatingAccount(parent, 2001L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 99L));
        when(jwtTokenService.verifySessionToken("token")).thenReturn(parent);
        assertThatThrownBy(() -> authenticate("Bearer token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer available");
        verifyNoInteractions(repository);
    }

    @Test
    void currentSessionClearsStaleOperatingAccountBackToParent() {
        JwtPrincipal parent = loggedIn(42L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 42L));
        service.switchOperatingAccount(parent, 2001L);
        fixture.state.deleteUser(2001L);

        var session = service.currentSession(parent);
        assertThat(session.user().userId()).isEqualTo(42L);
        assertThat(session.operatingAccount().userId()).isEqualTo(42L);
        assertThat(session.operatingAccount().parent()).isTrue();
        assertThat(fixture.state.session(parent.sessionId()).effectiveOperatingUserId()).isEqualTo(42L);
    }

    @Test
    void frozenAndReadOnlySubAccountsCanBeSelectedButWritesAreRejected() {
        JwtPrincipal parent = loggedIn(42L);
        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "FROZEN", "TRADE_SPOT", 42L));
        var frozen = service.switchOperatingAccount(parent, 2001L);
        assertThat(frozen.operatingAccount().userId()).isEqualTo(2001L);
        assertThat(frozen.operatingAccount().subAccountStatus()).isEqualTo("FROZEN");
        when(jwtTokenService.verifySessionToken("token")).thenReturn(parent);
        assertThat(authenticate("Bearer token").operatingUserId()).isEqualTo(2001L);
        assertThatThrownBy(() -> service.requireSubAccountWrite(2001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sub-account is not active");

        fixture.state.putUser(AuthStateFixture.subUser(2002L, "NORMAL", "ACTIVE", "READONLY", 42L));
        var readOnly = service.switchOperatingAccount(parent, 2002L);
        assertThat(readOnly.operatingAccount().subAccountPermissions()).isEqualTo("READONLY");
        assertThatThrownBy(() -> service.requireSubAccountWrite(2002L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sub-account is read-only");
    }

    @Test
    void sessionRoundTripKeepsOperatingUserAndMissingFieldMeansParent() {
        long now = fixture.backend.clock.get();
        var session = new SessionState(8L, 42L, "dev", now, 3600, now, "ua", "127.0.0.1", now, 2001L);
        fixture.state.replaceSession(session, java.time.Duration.ofSeconds(3600));
        assertThat(fixture.state.session(8L).effectiveOperatingUserId()).isEqualTo(2001L);

        fixture.backend.hashPut("auth:session:9", java.util.Map.of(
                "uid", "42",
                "authenticatedAt", Long.toString(now),
                "ttlSeconds", "3600",
                "renewedAt", Long.toString(now),
                "createdAt", Long.toString(now)), java.time.Duration.ofSeconds(3600));
        assertThat(fixture.state.session(9L).effectiveOperatingUserId()).isEqualTo(42L);

        fixture.state.putUser(AuthStateFixture.subUser(2001L, "NORMAL", "ACTIVE", "TRADE_SPOT", 42L));
        assertThat(fixture.state.user(2001L).subAccountParentUserId()).isEqualTo(42L);
    }

    private JwtPrincipal loggedIn(long userId) {
        long sessionId = userId * 1000 + 88;
        fixture.login(userId, sessionId);
        return new JwtPrincipal(userId, "user" + userId, "NORMAL", List.of("USER"), Instant.now().plusSeconds(60), sessionId);
    }

    private JwtPrincipal authenticate(String authorization) {
        var http = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        try {
            return service.authenticateBearer(authorization);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
