package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * 双重提交 CSRF：令牌放在前端可读的 Cookie 里，前端在请求头 X-CSRF-Token 回传。
 * Web 和后台各用独立 Cookie，登录互不覆盖；Cookie 的 Domain/Secure 与登录 Cookie 保持一致。
 */
public final class BrowserCsrfTokens implements CsrfTokenRepository {
    private final Map<String, CookieCsrfTokenRepository> repositories;

    public BrowserCsrfTokens(GatewayProperties properties) {
        repositories = Map.of("web", repository(properties, "web"), "admin", repository(properties, "admin"));
    }

    private static CookieCsrfTokenRepository repository(GatewayProperties properties, String client) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(BrowserSessionCookies.csrfCookieName(client));
        repository.setHeaderName("X-CSRF-Token");
        repository.setCookieCustomizer(cookie -> {
            cookie.httpOnly(false).secure(properties.getSecurity().isBrowserCookieSecure())
                    .sameSite("Lax").path("/");
            String domain = properties.getSecurity().getBrowserCookieDomain();
            if (domain != null && !domain.isBlank()) cookie.domain(domain);
        });
        return repository;
    }

    private CookieCsrfTokenRepository repository(HttpServletRequest request) {
        String client = BrowserSessionCookies.client(request);
        var repository = client == null ? null : repositories.get(client);
        if (repository == null) throw new IllegalArgumentException("browser client is required");
        return repository;
    }

    @Override public CsrfToken generateToken(HttpServletRequest request) { return repository(request).generateToken(request); }
    @Override public CsrfToken loadToken(HttpServletRequest request) { return repository(request).loadToken(request); }
    @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        repository(request).saveToken(token, request, response);
    }
}
