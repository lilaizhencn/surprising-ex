package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;

import com.surprising.gateway.provider.auth.controller.UserSecurityController;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.service.UserSecurityService;

import com.surprising.trading.api.utils.AdminCursorPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;
import com.surprising.gateway.provider.model.UserDeviceStatus;
import com.surprising.gateway.provider.service.UserAccessService;

class UserSecurityControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final UserSecurityService securityService = mock(UserSecurityService.class);
    private final LoginVerificationService loginVerification = mock(LoginVerificationService.class);
    private final SensitiveActionVerificationService verificationService =
            mock(SensitiveActionVerificationService.class);
    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final UserAccessService access = mock(UserAccessService.class);
    private final UserSecurityController controller = new UserSecurityController(
            authService, securityService, loginVerification, verificationService, persistence,
            access, new com.surprising.gateway.provider.config.GatewayProperties());

    @Test
    void devicePagePassesTheAuthenticatedIdentityAndPageToBusinessService() {
        Instant now = Instant.now();
        String current = "153a20c8-d24f-4952-b378-5409de885ab5";
        String other = "fe7b9fee-479b-4986-a1da-4fa0db57aa8b";
        var principal = new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60), 77L);
        when(authService.authenticateBearer("Bearer token")).thenReturn(principal);
        when(access.devices(principal, 25, 10)).thenReturn(List.of(
                new UserDeviceStatus(77L, current, "browser", "127.0.0.1", now, true, true, false),
                new UserDeviceStatus(88L, other, "phone", "127.0.0.2", now, false, false, true)));

        var response = controller.devices("Bearer token", 25, 10);

        assertThat(response).hasSize(2);
        assertThat(response.get(0).current()).isTrue();
        assertThat(response.get(1).blocked()).isTrue();
        verify(access).devices(principal, 25, 10);
    }

    @Test
    void cannotBlockCurrentDevice() {
        Instant now = Instant.now();
        String current = "153a20c8-d24f-4952-b378-5409de885ab5";
        when(authService.authenticateBearer("Bearer token")).thenReturn(
                new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60), 77L));
        doThrow(new IllegalArgumentException("cannot block the current device"))
                .when(access).blockDevice(any(), eq(current), eq("123456"), eq(""));

        assertThatThrownBy(() -> controller.blockDevice("Bearer token", current,
                new UserSecurityController.AccessChangeRequest("123456", "")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void cannotBlockCurrentIp() {
        Instant now = Instant.now();
        when(authService.authenticateBearer("Bearer token")).thenReturn(
                new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        doThrow(new IllegalArgumentException("cannot block the current IP"))
                .when(access).blockIp(42, "127.0.0.1", "127.0.0.1", "123456", "");

        assertThatThrownBy(() -> controller.blockIp("Bearer token",
                new UserSecurityController.IpAccessChangeRequest("127.0.0.1", "123456", ""), request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void rejectsInvalidAccessPageAtControllerBoundary() {
        assertThatThrownBy(() -> controller.devices("Bearer token", 501, 0))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
        assertThatThrownBy(() -> controller.ips("Bearer token", 100, -1))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
        verifyNoInteractions(access);
    }

    @Test
    void deviceHttpContractKeepsListFieldsAndAppliesDefaultPagination() throws Exception {
        Instant now = Instant.now();
        var principal = new JwtPrincipal(42, "user", "NORMAL", List.of("USER"), now.plusSeconds(60), 77L);
        when(authService.authenticateBearer("Bearer token")).thenReturn(principal);
        when(access.devices(principal, 100, 0)).thenReturn(List.of(
                new UserDeviceStatus(77, "153a20c8-d24f-4952-b378-5409de885ab5", "browser", "127.0.0.1", now, true, true, false)));
        var http = standaloneSetup(controller).build();

        http.perform(get("/api/v1/security/devices")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(77))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].blocked").value(false));
        verify(access).devices(principal, 100, 0);
    }

    @Test
    void ipHttpContractRejectsOutOfRangePaginationBeforeBusinessQueries() throws Exception {
        var http = standaloneSetup(controller).build();
        http.perform(get("/api/v1/security/ips")
                        .header("Authorization", "Bearer token").param("limit", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(access);
    }

    @Test
    void sessionsAreScopedToAuthenticatedUser() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        when(authService.authenticateBearer("Bearer token"))
                .thenReturn(new JwtPrincipal(42L, "user", "ACTIVE", List.of("USER"), now.plusSeconds(60)));
        AdminRefreshSessionResponse session = new AdminRefreshSessionResponse(
                7L, 42L, true, now.plusSeconds(3600), null, "browser", "127.0.0.1", now, now);
        when(persistence.refreshSessionsPage(42L, true, new AuthPageQuery(25,null,AuthPageQuery.Order.CREATED_DESC)))
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
