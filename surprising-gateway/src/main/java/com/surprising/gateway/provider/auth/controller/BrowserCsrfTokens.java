package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.security.web.csrf.*;

/** Separate CSRF cookies prevent Web/Admin login and refresh from replacing each other's challenge. */
public final class BrowserCsrfTokens implements CsrfTokenRepository {
    private final Map<String, CookieCsrfTokenRepository> repositories;
    public BrowserCsrfTokens(GatewayProperties properties) {
        repositories = Map.of("web", repository(properties, "surprising-web-csrf"),
                "admin", repository(properties, "surprising-admin-csrf"));
    }
    private static CookieCsrfTokenRepository repository(GatewayProperties properties, String name) {
        var repository = new CookieCsrfTokenRepository(); repository.setCookieName(properties.getSecurity().isBrowserCookieSecure() ? "__Host-" + name : name);
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.getSecurity().isBrowserCookieSecure())
                .sameSite("Strict").path(properties.getSecurity().isBrowserCookieSecure() ? "/" : "/api/v1/auth"));
        return repository;
    }
    private CookieCsrfTokenRepository repository(HttpServletRequest request) {
        String client = request.getHeader("X-Auth-Client");
        var repository = client == null ? null : repositories.get(client);
        if (repository == null) throw new IllegalArgumentException("browser client is required");
        return repository;
    }
    @Override public CsrfToken generateToken(HttpServletRequest request) { return repository(request).generateToken(request); }
    @Override public CsrfToken loadToken(HttpServletRequest request) { return repository(request).loadToken(request); }
    @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) { repository(request).saveToken(token, request, response); }
}
