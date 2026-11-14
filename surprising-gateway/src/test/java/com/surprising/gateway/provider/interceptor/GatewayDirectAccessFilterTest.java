package com.surprising.gateway.provider.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.config.GatewayProperties;
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

    private GatewayDirectAccessFilter filter(AuthService authService) {
        GatewayProductServices products = mock(GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL));
        var selection = new GatewayProductSelection(products, new tools.jackson.databind.ObjectMapper());
        return new GatewayDirectAccessFilter(
                new GatewayProperties(),
                provider(authService),
                provider(mock(GatewayApiKeyService.class)),
                provider(mock(AdminApprovalService.class)),
                provider(selection));
    }

    @Test
    void publicDirectRouteInjectsProductLineWithoutAuth() throws Exception {
        var f = filter(mock(AuthService.class));
        var req = new MockHttpServletRequest("GET", "/api/v1/price/index/latest");
        req.setParameter("instrumentId", "604");
        req.setParameter("productLine", "LINEAR_PERPETUAL");
        var res = new MockHttpServletResponse();
        var seenHeader = new AtomicReference<String>();
        FilterChain chain = (r, s) -> seenHeader.set(((HttpServletRequest) r).getHeader("X-Product-Line"));

        f.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(seenHeader.get()).isEqualTo("LINEAR_PERPETUAL");
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
    void privateRiskRouteRequiresAuthAndEnforcesUserOwnership() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticateBearer("Bearer tok4")).thenReturn(new JwtPrincipal(
                4L, "u4", "NORMAL", List.of("USER"), Instant.now().plusSeconds(60), 1L));
        var f = filter(auth);

        var unauthReq = new MockHttpServletRequest("GET", "/api/v1/risk/account/latest");
        unauthReq.setParameter("productLine", "LINEAR_PERPETUAL");
        var unauthRes = new MockHttpServletResponse();
        f.doFilter(unauthReq, unauthRes, (r, s) -> {});
        assertThat(unauthRes.getStatus()).isEqualTo(401);

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
        var okRes = new MockHttpServletResponse();
        var seenUser = new AtomicReference<String>();
        f.doFilter(okReq, okRes, (r, s) -> seenUser.set(((HttpServletRequest) r).getHeader("X-User-Id")));
        assertThat(okRes.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("4");
    }
}
