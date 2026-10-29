package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;

import com.surprising.gateway.provider.auth.controller.AuthClientMetadataBinding;
import com.surprising.gateway.provider.config.GatewayProperties;

import com.surprising.gateway.provider.auth.model.LoginVerificationModels;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.GatewayRefreshSessionRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.EmailVerificationService;
import com.surprising.gateway.provider.auth.service.JwtTokenService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.PasswordHasher;
import com.surprising.gateway.provider.auth.service.TotpService;

import com.surprising.trading.api.utils.AdminCursorPage;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AuthModels.LoginRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.RegisterRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.RefreshRequest;
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
    private final LoginVerificationService loginVerification = mock(LoginVerificationService.class);
    private final GatewayUserAccessBlockRepository accessBlocks = mock(GatewayUserAccessBlockRepository.class);
    private final AuthService service = new AuthService(new GatewayProperties(), repository,
            passwordHasher, jwtTokenService, emailVerificationService, loginVerification, accessBlocks, new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)), mock(com.surprising.affiliate.service.AffiliateService.class));

    @Test
    void telegramLoginKeepsMfaRequiredForPasswordlessAccount() {
        var user = new AuthenticatedUser(42, null, null, "NORMAL", List.of("USER"), Instant.now(), "小李");
        when(repository.user(42)).thenReturn(Optional.of(user));
        when(repository.credential(42)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                42, null, null, null, "NORMAL", Instant.now())));
        var challenge = new LoginVerificationModels.ChallengeResponse(true, "challenge", Instant.now().plusSeconds(300),
                List.of(new LoginVerificationModels.Method("TOTP", null)), false);
        when(loginVerification.begin(eq(42L), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(challenge);
        assertThat(service.loginTelegram(42, new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()))).isSameAs(challenge);
        verifyNoInteractions(passwordHasher, jwtTokenService);
    }

    @Test
    void frozenTelegramAccountCannotLogin() {
        when(repository.user(42)).thenReturn(Optional.of(new AuthenticatedUser(
                42, null, null, "FROZEN", List.of("USER"), Instant.now(), "小李")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.loginTelegram(42, new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(loginVerification, jwtTokenService);
    }

    @Test
    void blockedIpCannotUseApiKeyAccess() {
        when(accessBlocks.blocked(42L, "IP", "203.0.113.7")).thenReturn(true);

        assertThatThrownBy(() -> service.requireIpAccessAllowed(42L, "203.0.113.7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FROZEN,READ", "TRADE_DISABLED,TRADE", "WITHDRAW_DISABLED,WITHDRAW"})
    void apiKeyAccessHonorsAuthoritativeAccountRestrictions(String status, String permission) {
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42, "bot", null, status, List.of("USER"), Instant.now())));
        assertThatThrownBy(() -> service.requireApiKeyAccess(42L, "203.0.113.7", permission))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminRefreshSessionsReturnsRepositoryRows() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        AdminRefreshSessionResponse session = new AdminRefreshSessionResponse(
                88L, 42L, true, now.plusSeconds(3600), null, "ua", "127.0.0.1", now, now);
        when(repository.refreshSessionsPage(42L, true, new AuthPageQuery(100,null,AuthPageQuery.Order.CREATED_DESC)))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(session),null,false,"createdAt.desc",100));

        var response = service.adminRefreshSessions("Bearer admin-token", 42L, true, new AuthPageQuery(100,null,AuthPageQuery.Order.CREATED_DESC));

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
        when(repository.refreshSessionsPage(42L, true, new AuthPageQuery(25,new AdminCursorPage.Cursor(Instant.parse("2026-07-02T00:00:00Z"),7L),AuthPageQuery.Order.CREATED_ASC)))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(session), "next", true,
                        "createdAt.asc", 25));

        var response = service.adminRefreshSessions("Bearer admin-token", 42L, true, new AuthPageQuery(25,new AdminCursorPage.Cursor(Instant.parse("2026-07-02T00:00:00Z"),7L),AuthPageQuery.Order.CREATED_ASC));

        assertThat(response.sessions()).containsExactly(session);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(25);
        verify(repository).refreshSessionsPage(42L, true, new AuthPageQuery(25,new AdminCursorPage.Cursor(Instant.parse("2026-07-02T00:00:00Z"),7L),AuthPageQuery.Order.CREATED_ASC));
    }

    @Test
    void adminUsersWithCursorReturnsRepositoryPage() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(jwtTokenService.verifyAccessToken("admin-token"))
                .thenReturn(new JwtPrincipal(7L, "admin", "UNKNOWN", List.of("ADMIN"), now.plusSeconds(60)));
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        AuthenticatedUser user = new AuthenticatedUser(42L, "user", null, "NORMAL", List.of("USER"), now);
        when(repository.usersPage(new UserQueryFilter("user",null,"NORMAL"), new AuthPageQuery(50,new AdminCursorPage.Cursor(Instant.parse("2026-07-02T00:00:00Z"),7L),AuthPageQuery.Order.CREATED_ASC)))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(user), "next", true,
                        "createdAt.asc", 50));

        var response = service.adminUsers("Bearer admin-token", new UserQueryFilter("user",null,"NORMAL"), new AuthPageQuery(50,new AdminCursorPage.Cursor(Instant.parse("2026-07-02T00:00:00Z"),7L),AuthPageQuery.Order.CREATED_ASC));

        assertThat(response.users()).containsExactly(user);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(repository).usersPage(new UserQueryFilter("user", null, "NORMAL"), new AuthPageQuery(50, new AdminCursorPage.Cursor(now, 7L), AuthPageQuery.Order.CREATED_ASC));
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
    void revokeOtherRefreshSessionsKeepsTheRefreshSessionFromTheRequest() {
        Instant now = Instant.now();
        when(jwtTokenService.verifyAccessToken("access-token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, "user", null, "ACTIVE", List.of("USER"), now)));
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600), null)));
        when(repository.revokeActiveForUserExcept(eq(42L), eq(88L), any())).thenReturn(2);

        var response = service.revokeOtherRefreshSessions("Bearer access-token", "refresh-token");

        assertThat(response.revoked()).isEqualTo(2);
        verify(repository).revokeActiveForUserExcept(eq(42L), eq(88L), any());
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
    void loginReturnsChallengeWithoutIssuingTokens() {
        Instant now = Instant.now();
        when(repository.credentialByUsername("admin")).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                7L, "admin", null, "hash", "NORMAL", now)));
        when(passwordHasher.matches("password", "hash")).thenReturn(true);
        when(repository.user(7L)).thenReturn(Optional.of(admin(now)));
        var challenge = new LoginVerificationModels.ChallengeResponse(true,"challenge",now.plusSeconds(300),
                List.of(new LoginVerificationModels.Method("TOTP",null)), false);
        when(loginVerification.begin(eq(7L),eq("hash"),any())).thenReturn(challenge);
        assertThat(service.login(new LoginRequest("admin","password"),new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()))).isSameAs(challenge);
        verifyNoInteractions(jwtTokenService);
        verify(repository,org.mockito.Mockito.never()).saveRefreshSession(any(Long.class),any(),any(),any(),any(),any(),any());
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

        var response = (AuthModels.AuthResponse) service.login(new LoginRequest("user", "password"), new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()));

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

        var response = service.refresh(new RefreshRequest("refresh-token"), new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()));

        assertThat(response.accessToken()).isEqualTo("access");
        verify(repository).consumeRefreshSession(eq(88L), any());
        verify(repository, org.mockito.Mockito.never()).revokeRefreshSession(any(Long.class), any());
        verify(repository).saveRefreshSession(eq(42L), anyString(), any(), any(), any(), any(), any(), isNull());
    }

    @Test
    void refreshPreservesOriginalPrimaryAuthenticationInsteadOfRenewingIt() {
        Instant now = Instant.now(), original = now.minusSeconds(3600);
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600),
                        null, null, null, original)));
        when(repository.consumeRefreshSession(eq(88L), any())).thenReturn(1);
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(
                42L, null, null, "NORMAL", List.of("USER"), now)));
        service.refresh(new RefreshRequest("refresh-token"), new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()));
        verify(repository).saveRefreshSession(eq(42L), anyString(), any(), any(), any(), any(), any(), eq(original));
    }

    @Test
    void refreshRejectsSessionConsumedByAnotherRequest() {
        Instant now = Instant.now();
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600), null)));
        when(repository.consumeRefreshSession(eq(88L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.refresh(
                new RefreshRequest("refresh-token"), new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid refresh token");
        verify(repository, org.mockito.Mockito.never()).user(any(Long.class));
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void logoutRevokesOnlyTheRefreshSessionFromTheRequest() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(repository.refreshSession(any())).thenReturn(Optional.of(
                new GatewayRefreshSessionRepository.RefreshSession(88L, 42L, now.plusSeconds(3600), null)));

        service.logout(new RefreshRequest("refresh-token"));

        verify(repository).revokeRefreshSession(eq(88L), any());
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

        var response = (AuthModels.AuthResponse) service.login(new LoginRequest("user@example.com", "password"),
                new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()));

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
                new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest()));

        assertThat(response.user().email()).isEqualTo("user@example.com");
        assertThat(response.user().username()).isNull();
    }

    @Test
    void phoneRegistrationRemainsDisabledByDefault() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest(null, "StrongPassword1!", null, "+8613800138000"),
                new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("phone registration is not enabled");
        verifyNoInteractions(passwordHasher, repository);
    }

    @Test
    void bearerStillChecksIpFromActualRequestContext() {
        Instant now=Instant.now();
        when(jwtTokenService.verifyAccessToken("token")).thenReturn(
                new JwtPrincipal(42L,"user","NORMAL",List.of("USER"),now.plusSeconds(60)));
        when(repository.user(42L)).thenReturn(Optional.of(new AuthenticatedUser(42L,"user",null,"NORMAL",List.of("USER"),now)));
        when(accessBlocks.blocked(42L,"IP","203.0.113.7")).thenReturn(true);
        var http=new MockHttpServletRequest();http.setRemoteAddr("203.0.113.7");
        http.addHeader("X-Device-Id","malformed-device-is-irrelevant-to-bearer");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(http));
        try {
            assertThatThrownBy(()->service.authenticateBearer("Bearer token"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocked");
            verify(accessBlocks).blocked(42L,"IP","203.0.113.7");
        } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }

    private AuthenticatedUser admin(Instant now) {
        return new AuthenticatedUser(7L, "admin", null, "NORMAL", List.of("ADMIN"), now);
    }
}
