package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthModels.LoginRequest;
import com.surprising.gateway.provider.auth.AuthModels.RegisterRequest;
import com.surprising.gateway.provider.auth.AuthModels.RefreshRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthServiceTest {

    private final AuthPersistenceService repository = mock(AuthPersistenceService.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final TotpService totpService = mock(TotpService.class);
    private final EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
    private final AuthService service = new AuthService(new GatewayProperties(), repository,
            passwordHasher, jwtTokenService, totpService, emailVerificationService);

    @Test
    void adminRefreshSessionsReturnsRepositoryRows() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        AdminRefreshSessionResponse session = new AdminRefreshSessionResponse(
                88L, 42L, true, now.plusSeconds(3600), null, "ua", "127.0.0.1", now, now);
        when(repository.refreshSessions(42L, true, 100)).thenReturn(List.of(session));

        var response = service.adminRefreshSessions("Bearer admin-token", 42L, true, 100);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.sessions()).containsExactly(session);
    }

    @Test
    void adminRefreshSessionsWithCursorReturnsRepositoryPage() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        AdminRefreshSessionResponse session = new AdminRefreshSessionResponse(
                88L, 42L, true, now.plusSeconds(3600), null, "ua", "127.0.0.1", now, now);
        when(repository.refreshSessionsPage(42L, true, 25, "cursor", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(session), "next", true,
                        "createdAt.asc", 25));

        var response = service.adminRefreshSessions("Bearer admin-token", 42L, true, 25,
                "cursor", "createdAt.asc");

        assertThat(response.sessions()).containsExactly(session);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(25);
        verify(repository).refreshSessionsPage(42L, true, 25, "cursor", "createdAt.asc");
    }

    @Test
    void adminUsersWithCursorReturnsRepositoryPage() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        AuthenticatedUser user = new AuthenticatedUser(42L, "user", null, "NORMAL", List.of("USER"), now);
        when(repository.usersPage("user", "NORMAL", 50, "cursor", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(user), "next", true,
                        "createdAt.asc", 50));

        var response = service.adminUsers("Bearer admin-token", "user", "NORMAL", 50,
                "cursor", "createdAt.asc");

        assertThat(response.users()).containsExactly(user);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(repository).usersPage("user", "NORMAL", 50, "cursor", "createdAt.asc");
    }

    @Test
    void revokeUserRefreshSessionsRequiresTargetUserAndReturnsCount() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, "user", null, "NORMAL", List.of("USER"), now)));
        when(repository.revokeUserRefreshSessions(eq(42L), any())).thenReturn(3);

        var response = service.revokeUserRefreshSessions("Bearer admin-token", 42L);

        assertThat(response.revoked()).isEqualTo(3);
        assertThat(response.revokedAt()).isNotNull();
        verify(repository).revokeUserRefreshSessions(eq(42L), any());
    }

    @Test
    void revokeRefreshSessionReturnsRevokedCount() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        when(repository.revokeRefreshSessionForAdmin(eq(88L), any())).thenReturn(1);

        var response = service.revokeRefreshSession("Bearer admin-token", 88L);

        assertThat(response.revoked()).isEqualTo(1);
        verify(repository).revokeRefreshSessionForAdmin(eq(88L), any());
    }

    @Test
    void requireAdminPermissionAllowsWildcardRolePermission() {
        when(repository.permissionsForUser(7L)).thenReturn(List.of("admin.gateway.*.write"));

        service.requireAdminPermission(7L, List.of("ADMIN"), "admin.gateway.account.write");
    }

    @Test
    void requireAdminPermissionRejectsMissingPermission() {
        when(repository.permissionsForUser(7L)).thenReturn(List.of("admin.users.read"));

        assertThatThrownBy(() -> service.requireAdminPermission(7L, List.of("ADMIN"), "admin.users.write"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin permission required: admin.users.write");
    }

    @Test
    void superAdminBypassesPermissionLookup() {
        service.requireAdminPermission(7L, List.of("SUPER_ADMIN"), "admin.permissions.write");

        verifyNoInteractions(repository);
    }

    @Test
    void supportRoleCanAuthenticateAsAdminButNeedsSpecificPermission() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("support-token"))
                .thenReturn(new JwtPrincipal(9L, "support", "UNKNOWN", List.of("SUPPORT"), now.plusSeconds(60)));
        when(repository.user(9L)).thenReturn(Optional.of(new AuthenticatedUser(
                9L, "support", null, "NORMAL", List.of("SUPPORT"), now)));
        when(repository.permissionsForUser(9L)).thenReturn(List.of("admin.support.read"));

        JwtPrincipal principal = service.requireAdminPermission("Bearer support-token", "admin.support.read");

        assertThat(principal.roles()).containsExactly("SUPPORT");
        assertThatThrownBy(() -> service.requireAdminPermission("Bearer support-token", "admin.users.write"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin permission required: admin.users.write");
    }

    @Test
    void loginRequiresTotpWhenAdminMfaIsEnabled() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.credentialByUsername("admin")).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                7L, "admin", null, "hash", "NORMAL", now)));
        when(passwordHasher.matches("password", "hash")).thenReturn(true);
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        when(repository.mfaCredential(7L)).thenReturn(Optional.of(new GatewayUserMfaRepository.MfaCredential(
                7L, "ciphertext", true, now, now, now)));
        when(totpService.decryptSecret("ciphertext")).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), eq("123456"), any())).thenReturn(true);
        when(jwtTokenService.createAccessToken(eq(7L), eq("admin"), eq(List.of("ADMIN")), any()))
                .thenReturn("access");
        HttpServletRequest request = new MockHttpServletRequest();

        var response = service.login(new LoginRequest("admin", "password", "123456"), request);

        assertThat(response.accessToken()).isEqualTo("access");
        verify(repository).saveRefreshSession(eq(7L), any(), any(), any(), any(), any());
    }

    @Test
    void loginDoesNotCheckMfaForNonAdminUsers() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.credentialByUsername("user")).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                42L, "user", null, "hash", "NORMAL", now)));
        when(passwordHasher.matches("password", "hash")).thenReturn(true);
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, "user", null, "NORMAL", List.of("USER"), now)));
        when(jwtTokenService.createAccessToken(eq(42L), eq("user"), eq(List.of("USER")), any()))
                .thenReturn("access");

        var response = service.login(new LoginRequest("user", "password", null), new MockHttpServletRequest());

        assertThat(response.accessToken()).isEqualTo("access");
        verifyNoInteractions(totpService);
    }

    @Test
    void refreshConsumesOnlyOneActiveSession() {
        Instant now = Instant.now();
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600), null)));
        when(repository.consumeRefreshSession(eq(88L), any())).thenReturn(1);
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, null, "user@example.com", "NORMAL", List.of("USER"), now)));
        when(jwtTokenService.createAccessToken(eq(42L), eq(null), eq(List.of("USER")), any()))
                .thenReturn("access");

        var response = service.refresh(new RefreshRequest("refresh-token"), new MockHttpServletRequest());

        assertThat(response.accessToken()).isEqualTo("access");
        verify(repository).consumeRefreshSession(eq(88L), any());
        verify(repository, org.mockito.Mockito.never()).revokeRefreshSession(any(Long.class), any());
    }

    @Test
    void refreshRejectsSessionConsumedByAnotherRequest() {
        Instant now = Instant.now();
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600), null)));
        when(repository.consumeRefreshSession(eq(88L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.refresh(
                new RefreshRequest("refresh-token"), new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid refresh token");
        verify(repository, org.mockito.Mockito.never()).user(any(Long.class));
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void emailIsAcceptedAsLoginIdentifier() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.credentialByEmail("user@example.com")).thenReturn(Optional.of(
                new GatewayUserRepository.UserCredential(
                        42L, null, "user@example.com", "hash", "NORMAL", now)));
        when(passwordHasher.matches("password", "hash")).thenReturn(true);
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, null, "user@example.com", "NORMAL", List.of("USER"), now)));
        when(jwtTokenService.createAccessToken(eq(42L), eq(null), eq(List.of("USER")), any()))
                .thenReturn("access");

        var response = service.login(new LoginRequest("USER@EXAMPLE.COM", "password", null),
                new MockHttpServletRequest());

        assertThat(response.user().email()).isEqualTo("user@example.com");
        verify(repository).credentialByEmail("user@example.com");
    }

    @Test
    void registerAcceptsEmailAsPrimaryIdentityWithoutUsername() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        AuthenticatedUser user = new AuthenticatedUser(42L, null, "user@example.com", "NORMAL",
                List.of("USER"), now);
        when(passwordHasher.hash("StrongPassword1!"))
                .thenReturn("hash");
        when(repository.createUser(eq(null), eq("user@example.com"), eq("hash"), any()))
                .thenReturn(user);
        when(repository.user(42L)).thenReturn(Optional.of(user));
        when(jwtTokenService.createAccessToken(eq(42L), eq(null), eq(List.of("USER")), any()))
                .thenReturn("access");

        var response = service.register(
                new RegisterRequest(null, "StrongPassword1!", "user@example.com"),
                new MockHttpServletRequest());

        assertThat(response.user().email()).isEqualTo("user@example.com");
        assertThat(response.user().username()).isNull();
    }

    @Test
    void phoneRegistrationRemainsDisabledByDefault() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest(null, "StrongPassword1!", null, "+8613800138000"),
                new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("phone registration is not enabled");
        verifyNoInteractions(passwordHasher, repository);
    }

    private AuthenticatedUser admin(Instant now) {
        return new AuthenticatedUser(7L, "admin", null, "NORMAL", List.of("ADMIN"), now);
    }
}
