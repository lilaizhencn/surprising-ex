package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** One authoritative bearer check per request; services still enforce ownership and business permissions. */
public final class GatewayBearerFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final com.surprising.gateway.provider.config.GatewayProperties properties;
    public GatewayBearerFilter(AuthService auth, com.surprising.gateway.provider.config.GatewayProperties properties) {
        this.auth = auth; this.properties = properties;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean browser = "cookie".equals(request.getHeader("X-Auth-Transport"))
                || request.getRequestURI().startsWith("/api/v1/auth/browser/") || request.getRequestURI().equals("/api/v1/auth/csrf");
        if (browser
                && !java.util.List.of("web", "admin").contains(request.getHeader("X-Auth-Client") == null ? "" : request.getHeader("X-Auth-Client"))) {
            GatewaySecurityErrors.write(response, 400, "INVALID_BROWSER_CLIENT"); return;
        }
        String origin = request.getHeader("Origin");
        if (browser && "admin".equals(request.getHeader("X-Auth-Client")) && ((origin == null && !java.util.List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()))
                || (origin != null && !properties.getSecurity().getAdminBrowserAllowedOrigins().contains(origin)))) {
            GatewaySecurityErrors.write(response, 403, "ADMIN_ORIGIN_DENIED"); return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (request.getHeader("X-API-Key") != null || request.getHeader("X-MBX-APIKEY") != null) {
                GatewaySecurityErrors.write(response, 400, "AMBIGUOUS_AUTHENTICATION");
                return;
            }
            try {
                var principal = auth.verifyBearer(header);
                var token = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
                SecurityContextHolder.getContext().setAuthentication(token);
            } catch (IllegalArgumentException invalid) {
                GatewaySecurityErrors.write(response, 401, "UNAUTHENTICATED"); return;
            } catch (IllegalStateException forbidden) {
                GatewaySecurityErrors.write(response, 403, "ACCESS_DENIED"); return;
            }
        }
        chain.doFilter(request, response);
    }
}
