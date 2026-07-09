package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminIpWhitelistFilterTest {

    @Test
    void emptyAllowlistDoesNotRestrictAdminRequests() throws ServletException, IOException {
        GatewayProperties properties = new GatewayProperties();
        AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowlistDoesNotRestrictPublicGatewayRequests() throws ServletException, IOException {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setAdminIpAllowlist(List.of("10.0.0.0/8"));
        AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gateway/trading-market/orderbook");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedIpCanMatchCidrRule() throws ServletException, IOException {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setAdminIpAllowlist(List.of("10.8.0.0/16"));
        AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/gateway/account");
        request.addHeader("X-Forwarded-For", "10.8.2.3, 192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forbiddenWhenAdminIpIsOutsideAllowlist() throws ServletException, IOException {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setAdminIpAllowlist(List.of("10.8.0.0/16", "127.0.0.1"));
        AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
