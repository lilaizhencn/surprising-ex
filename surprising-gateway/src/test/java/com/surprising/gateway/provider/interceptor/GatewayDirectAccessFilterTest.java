package com.surprising.gateway.provider.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AdminOperationRecord;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayDirectAccessFilterTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(instance);
        return p;
    }

    private GatewayDirectAccessFilter filter(AuthService authService,
                                             GatewayApiKeyService apiKeys,
                                             AdminApprovalService adminApproval) {
        GatewayProductServices products = mock(GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL));
        doThrow(new ProductUnavailableException(ProductLine.OPTION))
                .when(products).requireEnabled(ProductLine.OPTION);
        var selection = new GatewayProductSelection(products, new tools.jackson.databind.ObjectMapper());
        return new GatewayDirectAccessFilter(
                new GatewayProperties(),
                provider(authService),
                provider(apiKeys),
                provider(adminApproval),
                provider(selection));
    }

    private GatewayDirectAccessFilter filter(AuthService authService) {
        return filter(authService, mock(GatewayApiKeyService.class), mock(AdminApprovalService.class));
    }

    @Test
    void publicDirectRouteInjectsProductLineAndStripsSpoofedIdentityHeaders() throws Exception {
        var f = filter(mock(AuthService.class));
        for (String path : List.of(
                "/api/v1/price/index/latest",
                "/api/v1/price/mark/latest",
                "/api/v1/candlestick/trades/recent",
                "/api/v1/funding/rates/latest")) {
            var req = new MockHttpServletRequest("GET", path);
            req.setParameter("instrumentId", "604");
            req.setParameter("productLine", "LINEAR_PERPETUAL");
            req.addHeader("X-User-Id", "999");
            req.addHeader("X-Admin-User-Id", "1");
            req.addHeader("X-Admin-Roles", "SUPER_ADMIN");
            var res = new MockHttpServletResponse();
            var seenProduct = new AtomicReference<String>();
            var seenUser = new AtomicReference<String>("sentinel");
            var seenAdmin = new AtomicReference<String>("sentinel");
            var seenRoles = new AtomicReference<String>("sentinel");
            FilterChain chain = (r, s) -> {
                var http = (HttpServletRequest) r;
                seenProduct.set(http.getHeader("X-Product-Line"));
                seenUser.set(http.getHeader("X-User-Id"));
                seenAdmin.set(http.getHeader("X-Admin-User-Id"));
                seenRoles.set(http.getHeader("X-Admin-Roles"));
            };

            f.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(seenProduct.get()).isEqualTo("LINEAR_PERPETUAL");
            assertThat(seenUser.get()).isNull();
            assertThat(seenAdmin.get()).isNull();
            assertThat(seenRoles.get()).isNull();
        }
    }

    @Test
    void sharedPriceFxRouteDoesNotRequireProductLine() throws Exception {
        var f = filter(mock(AuthService.class));
        var req = new MockHttpServletRequest("GET", "/api/v1/price/fx/rates");
        req.setParameter("baseCurrency", "USD");
        var res = new MockHttpServletResponse();
        var invoked = new AtomicReference<>(false);
        FilterChain chain = (r, s) -> invoked.set(true);

        f.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(invoked.get()).isTrue();
    }

    @Test
    void directRoutesEnforceProductLineSelectionAndIsolation() throws Exception {
        var f = filter(mock(AuthService.class));

        var missingReq = new MockHttpServletRequest("GET", "/api/v1/price/mark/latest");
        missingReq.setParameter("instrumentId", "604");
        var missingRes = new MockHttpServletResponse();
        f.doFilter(missingReq, missingRes, (r, s) -> {});
        assertThat(missingRes.getStatus()).isEqualTo(400);

        var disabledReq = new MockHttpServletRequest("GET", "/api/v1/price/mark/latest");
        disabledReq.setParameter("instrumentId", "604");
        disabledReq.setParameter("productLine", "OPTION");
        var disabledRes = new MockHttpServletResponse();
        f.doFilter(disabledReq, disabledRes, (r, s) -> {});
        assertThat(disabledRes.getStatus()).isEqualTo(404);

        var conflictReq = new MockHttpServletRequest("GET", "/api/v1/funding/rates/latest");
        conflictReq.setParameter("productLine", "SPOT");
        conflictReq.addHeader("X-Product-Line", "LINEAR_PERPETUAL");
        var conflictRes = new MockHttpServletResponse();
        f.doFilter(conflictReq, conflictRes, (r, s) -> {});
        assertThat(conflictRes.getStatus()).isEqualTo(400);
    }

    @Test
    void privateRoutesRequireAuthAndEnforceUserOwnership() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticateBearer("Bearer tok4")).thenReturn(new JwtPrincipal(
                4L, "u4", "NORMAL", List.of("USER"), Instant.now().plusSeconds(60), 1L));
        var f = filter(auth);

        for (String path : List.of(
                "/api/v1/risk/account/latest",
                "/api/v1/liquidations/orders",
                "/api/v1/insurance/fund",
                "/api/v1/adl/events")) {
            var unauthReq = new MockHttpServletRequest("GET", path);
            unauthReq.setParameter("productLine", "LINEAR_PERPETUAL");
            var unauthRes = new MockHttpServletResponse();
            f.doFilter(unauthReq, unauthRes, (r, s) -> {});
            assertThat(unauthRes.getStatus()).isEqualTo(401);
        }

        var mismatchReq = new MockHttpServletRequest("GET", "/api/v1/risk/account/latest");
        mismatchReq.setParameter("productLine", "LINEAR_PERPETUAL");
        mismatchReq.setParameter("userId", "99");
        mismatchReq.addHeader("Authorization", "Bearer tok4");
        var mismatchRes = new MockHttpServletResponse();
        f.doFilter(mismatchReq, mismatchRes, (r, s) -> {});
        assertThat(mismatchRes.getStatus()).isEqualTo(403);

        var okReq = new MockHttpServletRequest("GET", "/api/v1/risk/account/latest");
        okReq.setParameter("productLine", "LINEAR_PERPETUAL");
        okReq.setParameter("userId", "4");
        okReq.addHeader("Authorization", "Bearer tok4");
        okReq.addHeader("X-Admin-User-Id", "1");
        var okRes = new MockHttpServletResponse();
        var seenUser = new AtomicReference<String>();
        var seenAdmin = new AtomicReference<String>("sentinel");
        f.doFilter(okReq, okRes, (r, s) -> {
            var http = (HttpServletRequest) r;
            seenUser.set(http.getHeader("X-User-Id"));
            seenAdmin.set(http.getHeader("X-Admin-User-Id"));
        });
        assertThat(okRes.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("4");
        assertThat(seenAdmin.get()).isNull();
    }

    @Test
    void privateRouteApiKeySupportsReadsAndRejectsWritesOrDualAuth() throws Exception {
        GatewayApiKeyService apiKeys = mock(GatewayApiKeyService.class);
        when(apiKeys.authenticate(any(), eq("READ"))).thenReturn(77L);
        var f = filter(mock(AuthService.class), apiKeys, mock(AdminApprovalService.class));

        var dualReq = new MockHttpServletRequest("GET", "/api/v1/risk/account/latest");
        dualReq.setParameter("productLine", "LINEAR_PERPETUAL");
        dualReq.addHeader("Authorization", "Bearer tok");
        dualReq.addHeader("X-API-Key", "k1");
        var dualRes = new MockHttpServletResponse();
        f.doFilter(dualReq, dualRes, (r, s) -> {});
        assertThat(dualRes.getStatus()).isEqualTo(400);

        var writeReq = new MockHttpServletRequest("POST", "/api/v1/risk/account/check");
        writeReq.setParameter("productLine", "LINEAR_PERPETUAL");
        writeReq.addHeader("X-API-Key", "k1");
        var writeRes = new MockHttpServletResponse();
        f.doFilter(writeReq, writeRes, (r, s) -> {});
        assertThat(writeRes.getStatus()).isEqualTo(403);

        var readReq = new MockHttpServletRequest("GET", "/api/v1/risk/account/latest");
        readReq.setQueryString("productLine=LINEAR_PERPETUAL&userId=77&timestamp=" + System.currentTimeMillis() + "&signature=sig");
        readReq.setParameter("productLine", "LINEAR_PERPETUAL");
        readReq.setParameter("userId", "77");
        readReq.addHeader("X-API-Key", "sx_test_key_1234567890123456");
        var readRes = new MockHttpServletResponse();
        var seenUser = new AtomicReference<String>();
        f.doFilter(readReq, readRes, (r, s) -> seenUser.set(((HttpServletRequest) r).getHeader("X-User-Id")));
        assertThat(readRes.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("77");
    }

    @Test
    void adminDirectRoutesEnforceRolePermissionApprovalAndAudit() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticateBearer("Bearer userTok")).thenReturn(new JwtPrincipal(
                4L, "u4", "NORMAL", List.of("USER"), Instant.now().plusSeconds(60), 1L));
        when(auth.authenticateBearer("Bearer adminTok")).thenReturn(new JwtPrincipal(
                3L, "admin3", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60), 2L));
        AdminApprovalService approval = mock(AdminApprovalService.class);
        when(approval.requiresGatewayApproval("risk-admin", "POST", "/api/v1/admin/risk/limits", true))
                .thenReturn(true);

        var f = filter(auth, mock(GatewayApiKeyService.class), approval);

        var nonAdminReq = new MockHttpServletRequest("POST", "/api/v1/admin/risk/limits");
        nonAdminReq.setParameter("productLine", "LINEAR_PERPETUAL");
        nonAdminReq.addHeader("Authorization", "Bearer userTok");
        var nonAdminRes = new MockHttpServletResponse();
        f.doFilter(nonAdminReq, nonAdminRes, (r, s) -> {});
        assertThat(nonAdminRes.getStatus()).isEqualTo(403);

        var missingApprovalReq = new MockHttpServletRequest("POST", "/api/v1/admin/risk/limits");
        missingApprovalReq.setParameter("productLine", "LINEAR_PERPETUAL");
        missingApprovalReq.addHeader("Authorization", "Bearer adminTok");
        var missingApprovalRes = new MockHttpServletResponse();
        f.doFilter(missingApprovalReq, missingApprovalRes, (r, s) -> {});
        assertThat(missingApprovalRes.getStatus()).isEqualTo(428);

        var okReq = new MockHttpServletRequest("POST", "/api/v1/admin/risk/limits");
        okReq.setParameter("productLine", "LINEAR_PERPETUAL");
        okReq.addHeader("Authorization", "Bearer adminTok");
        okReq.addHeader("X-Admin-Approval-Id", "88");
        var okRes = new MockHttpServletResponse();
        var seenAdmin = new AtomicReference<String>();
        var seenRoles = new AtomicReference<String>();
        f.doFilter(okReq, okRes, (r, s) -> {
            var http = (HttpServletRequest) r;
            seenAdmin.set(http.getHeader("X-Admin-User-Id"));
            seenRoles.set(http.getHeader("X-Admin-Roles"));
        });
        assertThat(okRes.getStatus()).isEqualTo(200);
        assertThat(seenAdmin.get()).isEqualTo("3");
        assertThat(seenRoles.get()).isEqualTo("ADMIN");
        verify(auth, times(2)).requireAdminPermission(3L, List.of("ADMIN"), "admin.gateway.risk-admin.write");
        verify(approval).consumeGatewayApproval(eq(88L), eq(3L), eq("risk-admin"), eq("POST"),
                eq("/api/v1/admin/risk/limits"), any(), any(), any());
        verify(approval, times(2)).recordGatewayOperation(any(AdminOperationRecord.class));

        doThrow(new IllegalStateException("missing permission"))
                .when(auth).requireAdminPermission(3L, List.of("ADMIN"), "admin.gateway.liquidation-admin.read");
        var forbiddenAdminReq = new MockHttpServletRequest("GET", "/api/v1/admin/liquidations/jobs");
        forbiddenAdminReq.setParameter("productLine", "LINEAR_PERPETUAL");
        forbiddenAdminReq.addHeader("Authorization", "Bearer adminTok");
        var forbiddenAdminRes = new MockHttpServletResponse();
        f.doFilter(forbiddenAdminReq, forbiddenAdminRes, (r, s) -> {});
        assertThat(forbiddenAdminRes.getStatus()).isEqualTo(403);

        var wsAdminReq = new MockHttpServletRequest("GET", "/api/v1/admin/websocket/metrics");
        wsAdminReq.addHeader("Authorization", "Bearer adminTok");
        var wsAdminRes = new MockHttpServletResponse();
        var seenWsAdmin = new AtomicReference<String>();
        f.doFilter(wsAdminReq, wsAdminRes, (r, s) -> seenWsAdmin.set(((HttpServletRequest) r).getHeader("X-Admin-User-Id")));
        assertThat(wsAdminRes.getStatus()).isEqualTo(200);
        assertThat(seenWsAdmin.get()).isEqualTo("3");
        verify(auth).requireAdminPermission(3L, List.of("ADMIN"), "admin.gateway.websocket-admin.read");
    }
}
