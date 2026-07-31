package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminPermissionFilterTest {

    @Test
    void mapsLocalAdminReadToUsersReadPermission() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).requireAdminPermission("Bearer admin", "admin.users.read");
    }

    @Test
    void skipsAdminGatewayBecauseProxyDoesServicePermissionCheck() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/gateway/account/balance-adjustments");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forbiddenWhenPermissionIsMissing() throws ServletException, IOException {
        AuthService authService = authService();
        doThrow(new IllegalStateException("admin permission required: admin.permissions.write"))
                .when(authService).requireAdminPermission("Bearer admin", "admin.permissions.write");
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/roles/ADMIN/permissions");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void mapsComplianceWriteToComplianceWritePermission() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/compliance/users/42/kyc");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).requireAdminPermission("Bearer admin", "admin.compliance.write");
    }

    @Test
    void mapsSupportOverviewToSupportReadPermission() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/support/users/42/overview");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).requireAdminPermission("Bearer admin", "admin.support.read");
    }

    @Test
    void mapsSupportTicketWriteToSupportWritePermission() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/support/tickets/42/notes");
        request.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(authService).requireAdminPermission("Bearer admin", "admin.support.write");
    }

    @Test
    void publicGatewayIsNotRestrictedByAdminPermissions() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gateway/trading-market/orderbook");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private AuthService authService() {
        AuthService authService = mock(AuthService.class);
        org.mockito.Mockito.when(authService.requireAdminPermission("Bearer admin", "admin.users.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60)));
        org.mockito.Mockito.when(authService.requireAdminPermission("Bearer admin", "admin.compliance.write"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60)));
        org.mockito.Mockito.when(authService.requireAdminPermission("Bearer admin", "admin.support.read"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60)));
        org.mockito.Mockito.when(authService.requireAdminPermission("Bearer admin", "admin.support.write"))
                .thenReturn(new JwtPrincipal(7L, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60)));
        return authService;
    }
}
