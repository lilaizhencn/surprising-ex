package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Browser refresh credentials never cross the JavaScript boundary. Mobile continues to use its explicit token body. */
@RestController
@RequestMapping("/api/v1/auth")
public class BrowserSessionController {
    private final AuthService auth;
    private final com.surprising.gateway.provider.config.GatewayProperties properties;
    private final AuthClientMetadataBinding clients;
    public BrowserSessionController(AuthService auth, AuthClientMetadataBinding clients, com.surprising.gateway.provider.config.GatewayProperties properties) { this.auth = auth; this.clients = clients; this.properties = properties; }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/browser/refresh")
    public AuthResponse refresh(HttpServletRequest request) {
        try { return auth.refresh(new RefreshRequest(refreshToken(request, properties.getSecurity().isBrowserCookieSecure())), clients.bind(request)); }
        catch (IllegalArgumentException invalid) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid browser session"); }
        catch (IllegalStateException denied) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "browser session unavailable"); }
    }

    @PostMapping("/browser/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String name = cookieName(request, properties.getSecurity().isBrowserCookieSecure());
        if (request.getCookies() != null) for (var cookie : request.getCookies())
            if (name.equals(cookie.getName())) auth.logout(new RefreshRequest(cookie.getValue()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cookie(request, "", java.time.Duration.ZERO, properties.getSecurity().isBrowserCookieSecure()).toString()).build();
    }

    @PostMapping("/browser/revoke-others")
    public AdminSessionRevokeResponse revokeOthers(@RequestHeader("Authorization") String authorization, HttpServletRequest request) {
        return auth.revokeOtherRefreshSessions(authorization, refreshToken(request, properties.getSecurity().isBrowserCookieSecure()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(org.springframework.web.server.ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "BROWSER_SESSION_UNAVAILABLE", "message", "Browser session is not available"));
    }

    static String refreshToken(HttpServletRequest request, boolean secure) {
        String name = cookieName(request, secure);
        if (request.getCookies() != null) for (var cookie : request.getCookies())
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) return cookie.getValue();
        throw new IllegalArgumentException("missing browser session");
    }

    private static String cookieName(HttpServletRequest request, boolean secure) {
        String name = switch (request.getHeader("X-Auth-Client") == null ? "" : request.getHeader("X-Auth-Client")) {
            case "web" -> "surprising-web-refresh";
            case "admin" -> "surprising-admin-refresh";
            default -> throw new IllegalArgumentException("browser client is required");
        };
        return secure ? "__Host-" + name : name;
    }

    static ResponseCookie cookie(HttpServletRequest request, String value, java.time.Duration age, boolean secure) {
        return ResponseCookie.from(cookieName(request, secure), value).httpOnly(true).secure(secure).sameSite("Strict")
                .path(secure ? "/" : "/api/v1/auth/browser").maxAge(age).build();
    }
}
