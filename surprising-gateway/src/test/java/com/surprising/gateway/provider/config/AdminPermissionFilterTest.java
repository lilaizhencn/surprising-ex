package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.interceptor.AdminPermissionFilter;

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
    void mapsWalletWithdrawalReadAndWriteToDedicatedPermissions() throws ServletException, IOException {
        AuthService authService = authService();
        AdminPermissionFilter filter = new AdminPermissionFilter(authService);
        MockHttpServletRequest readRequest = new MockHttpServletRequest(
                "GET", "/api/v1/admin/wallet/withdrawals");
        readRequest.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        filter.doFilter(readRequest, readResponse, new MockFilterChain());

        MockHttpServletRequest writeRequest = new MockHttpServletRequest(
                "POST", "/api/v1/admin/wallet/withdrawals/00000000-0000-0000-0000-000000000001/approve");
        writeRequest.addHeader("Authorization", "Bearer admin");
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        filter.doFilter(writeRequest, writeResponse, new MockFilterChain());

        verify(authService).requireAdminPermission("Bearer admin", "admin.wallet.read");
        verify(authService).requireAdminPermission("Bearer admin", "admin.wallet.write");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "GET,/api/v1/admin/c2c/configuration,admin.c2c.read",
            "GET,/api/v1/admin/c2c/payment-accounts,admin.c2c.read",
            "GET,/api/v1/admin/c2c/orders/test-order,admin.c2c.read",
            "GET,/api/v1/admin/c2c/payment-accounts/test-account,admin.c2c.sensitive.read",
            "GET,/api/v1/admin/c2c/orders/test-order/chat,admin.c2c.sensitive.read",
            "GET,/api/v1/admin/c2c/orders/test-order/chat/attachments/test-file,admin.c2c.sensitive.read",
            "POST,/api/v1/admin/c2c/orders/test-order/chat,admin.c2c.write",
            "HEAD,/api/v1/admin/c2c/orders/test-order/payment,admin.c2c.sensitive.read",
            "PUT,/api/v1/admin/c2c/configuration,admin.c2c.write",
            "POST,/api/v1/admin/c2c/payment-accounts/test-account/review,admin.c2c.write",
            "POST,/api/v1/admin/c2c/ads/test-ad/close,admin.c2c.resolve",
            "POST,/api/v1/admin/c2c/appeals/test-appeal/decision,admin.c2c.resolve",
            "GET,/api/v1/admin/c2c-unrelated,admin.unmapped.read"
    })
    void appliesC2cPermissionsAtTheActualServletBoundary(String method, String path, String permission)
            throws ServletException, IOException {
        AuthService auth = mock(AuthService.class);
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer scoped-admin");
        var response = new MockHttpServletResponse();
        var filter = new AdminPermissionFilter(auth);
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
        verify(auth).requireAdminPermission("Bearer scoped-admin", permission);
    }

    @Test
    void ordinaryC2cWritePermissionCannotPassTheMoneyDecisionFilter() throws ServletException, IOException {
        AuthService auth = mock(AuthService.class);
        doThrow(new IllegalStateException("Missing C2C resolution permission"))
                .when(auth).requireAdminPermission("Bearer ordinary-admin", "admin.c2c.resolve");
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/c2c/appeals/test-appeal/decision");
        request.addHeader("Authorization", "Bearer ordinary-admin");
        var response = new MockHttpServletResponse();
        new AdminPermissionFilter(auth).doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
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
