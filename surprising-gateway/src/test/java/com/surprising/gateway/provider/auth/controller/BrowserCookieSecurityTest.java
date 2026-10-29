package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class BrowserCookieSecurityTest {
    @Test void productionRefreshAndCsrfCookiesHaveHostOnlySecurePrefixes() {
        var properties = new GatewayProperties();
        var request = new MockHttpServletRequest(); request.addHeader("X-Auth-Client", "web");
        String refresh = BrowserSessionController.cookie(request, "opaque-test-value", Duration.ofMinutes(5), true).toString();
        assertThat(refresh).startsWith("__Host-surprising-web-refresh=")
                .contains("Path=/;", "Secure", "HttpOnly", "SameSite=Strict").doesNotContain("Domain=");
        var csrf = new BrowserCsrfTokens(properties); var response = new MockHttpServletResponse();
        csrf.saveToken(csrf.generateToken(request), request, response);
        assertThat(response.getHeader("Set-Cookie")).startsWith("__Host-surprising-web-csrf=")
                .contains("Path=/;", "Secure", "HttpOnly").doesNotContain("Domain=");
        // MockHttpServletResponse does not serialize arbitrary Servlet Cookie attributes into its header.
        assertThat(response.getCookie("__Host-surprising-web-csrf").getAttribute("SameSite")).isEqualTo("Strict");
        request = new MockHttpServletRequest(); request.addHeader("X-Auth-Client", "admin");
        assertThat(BrowserSessionController.cookie(request, "opaque-test-value", Duration.ZERO, true).toString())
                .startsWith("__Host-surprising-admin-refresh=").contains("Max-Age=0");
    }
}
