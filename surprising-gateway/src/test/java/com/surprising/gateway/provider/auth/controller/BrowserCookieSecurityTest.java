package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class BrowserCookieSecurityTest {
    @Test void sessionCookieIsHttpOnlyLaxAndCarriesTheConfiguredDomain() {
        var properties = new GatewayProperties();
        properties.getSecurity().setBrowserCookieSecure(true);
        properties.getSecurity().setBrowserCookieDomain(".tokdou.com");
        var request = new MockHttpServletRequest(); request.addHeader("X-Auth-Client", "web");
        String session = BrowserSessionCookies.sessionCookie(properties, "web", "jwt-test-value", Duration.ofDays(365)).toString();
        assertThat(session).startsWith("surprising-web-session=jwt-test-value")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Domain=.tokdou.com");
        var csrf = new BrowserCsrfTokens(properties); var response = new MockHttpServletResponse();
        csrf.saveToken(csrf.generateToken(request), request, response);
        assertThat(response.getHeader("Set-Cookie")).startsWith("surprising-web-csrf=")
                .contains("Path=/", "Secure", "Domain=.tokdou.com").doesNotContain("HttpOnly");
        assertThat(response.getCookie("surprising-web-csrf").getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(csrf.generateToken(request).getHeaderName()).isEqualTo("X-CSRF-Token");
    }

    @Test void adminAndWebCookiesUseSeparateNamesAndLogoutClearsTheSession() {
        var properties = new GatewayProperties();
        assertThat(BrowserSessionCookies.clearedSessionCookie(properties, "admin").toString())
                .startsWith("surprising-admin-session=").contains("Max-Age=0");
    }
}
