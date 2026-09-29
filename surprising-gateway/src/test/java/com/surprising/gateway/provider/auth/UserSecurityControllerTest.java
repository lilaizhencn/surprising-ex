package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;

class UserSecurityControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final UserSecurityService securityService = mock(UserSecurityService.class);
    private final SensitiveActionVerificationService verificationService =
            mock(SensitiveActionVerificationService.class);
    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final GatewayUserAccessBlockRepository accessBlocks = mock(GatewayUserAccessBlockRepository.class);
    private final UserSecurityController controller = new UserSecurityController(
            authService, securityService, verificationService, persistence,
            accessBlocks, new com.surprising.gateway.provider.config.GatewayProperties());

    @Test
    void devicesShowCurrentAndBlockedStatusFromAccountOwnedHistory() {
        Instant now = Instant.now();
        String current = "153a20c8-d24f-4952-b378-5409de885ab5";
        String other = "fe7b9fee-479b-4986-a1da-4fa0db57aa8b";
        when(authService.authenticateBearer("Bearer token")).thenReturn(
                new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60), 77L));
        when(persistence.deviceIdForSession(42L, 77L)).thenReturn(java.util.Optional.of(current));
        when(persistence.devices(42L)).thenReturn(List.of(
                new GatewayRefreshSessionRepository.DeviceView(77L, current, "browser", "127.0.0.1", now, true),
                new GatewayRefreshSessionRepository.DeviceView(88L, other, "phone", "127.0.0.2", now, false)));
        when(accessBlocks.blocked(42L, "DEVICE", other)).thenReturn(true);

        var response = controller.devices("Bearer token");

        assertThat(response).hasSize(2);
        assertThat(response.get(0).current()).isTrue();
        assertThat(response.get(1).blocked()).isTrue();
    }

    @Test
    void cannotBlockCurrentDevice() {
        Instant now = Instant.now();
        String current = "153a20c8-d24f-4952-b378-5409de885ab5";
        when(authService.authenticateBearer("Bearer token")).thenReturn(
                new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60), 77L));
        when(persistence.deviceIdForSession(42L, 77L)).thenReturn(java.util.Optional.of(current));

        assertThatThrownBy(() -> controller.blockDevice("Bearer token", current,
                new UserSecurityController.AccessChangeRequest("123456", "")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
        verify(accessBlocks, never()).block(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cannotBlockCurrentIp() {
        Instant now = Instant.now();
        when(authService.authenticateBearer("Bearer token")).thenReturn(
                new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThatThrownBy(() -> controller.blockIp("Bearer token",
                new UserSecurityController.IpAccessChangeRequest("127.0.0.1", "123456", ""), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
    }

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
