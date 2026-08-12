package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserSecurityControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final UserSecurityService securityService = mock(UserSecurityService.class);
    private final SensitiveActionVerificationService verificationService =
            mock(SensitiveActionVerificationService.class);
    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final UserSecurityController controller = new UserSecurityController(
            authService, securityService, verificationService, persistence);

    @Test
    void sessionsAreScopedToAuthenticatedUser() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        AdminRefreshSessionResponse session = new AdminRefreshSessionResponse(
                7L, 42L, true, now.plusSeconds(3600), null, "browser", "127.0.0.1", now, now);
        when(persistence.refreshSessionsPage(42L, true, 25, null, null))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(session), null, false,
                        "createdAt.desc", 25));

        var response = controller.sessions("Bearer token", true, 25, null, null);

        assertThat(response.sessions()).containsExactly(session);
        assertThat(response.sessions()).allMatch(value -> value.userId() == 42L);
    }

    @Test
    void revokeSessionRejectsAnotherUserSession() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        when(persistence.revokeRefreshSessionForUser(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> controller.revokeSession("Bearer token", 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void revokeAllSessionsKeepsCurrentSessionByPassingRefreshToken() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        when(authService.revokeOtherRefreshSessions("Bearer token", "refresh-token"))
                .thenReturn(new AuthModels.AdminSessionRevokeResponse(2, now));

        var response = controller.revokeAllSessions("Bearer token",
                new AuthModels.RevokeOtherSessionsRequest("refresh-token"));

        assertThat(response.revoked()).isEqualTo(2);
        verify(authService).revokeOtherRefreshSessions("Bearer token", "refresh-token");
    }
}
