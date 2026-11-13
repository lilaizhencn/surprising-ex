package com.surprising.gateway.provider.interceptor;

import com.surprising.gateway.provider.auth.controller.BrowserSessionCookies;
import com.surprising.gateway.provider.auth.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录校验入口：登录令牌可以来自 Authorization: Bearer 请求头（App、后台工具、脚本），
 * 也可以来自浏览器的 HttpOnly 登录 Cookie（Web、后台页面）。Cookie 来源的请求会被补上 Authorization 头，
 * 下游控制器统一按 Bearer 处理。校验只看签名和 Valkey 会话，不访问 Postgres。
 */
public final class GatewayBearerFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final com.surprising.gateway.provider.config.GatewayProperties properties;
    public GatewayBearerFilter(AuthService auth, com.surprising.gateway.provider.config.GatewayProperties properties) {
        this.auth = auth; this.properties = properties;
    }

    @Override protected void doFilterInternal(HttpServletRequest original, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest request = original;
        boolean declaredBrowser = BrowserSessionCookies.client(request) != null;
        boolean browser = declaredBrowser || "cookie".equals(request.getHeader("X-Auth-Transport"))
                || request.getRequestURI().equals("/api/v1/auth/csrf");
        if (browser && !declaredBrowser) {
            GatewaySecurityErrors.write(response, 400, "INVALID_BROWSER_CLIENT"); return;
        }
        String origin = request.getHeader("Origin");
        if (browser && "admin".equals(request.getHeader("X-Auth-Client")) && ((origin == null && !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()))
                || (origin != null && !properties.getSecurity().getAdminBrowserAllowedOrigins().contains(origin)))) {
            GatewaySecurityErrors.write(response, 403, "ADMIN_ORIGIN_DENIED"); return;
        }
        String header = request.getHeader("Authorization");
        if (header == null) {
            String cookieToken = BrowserSessionCookies.sessionToken(request);
            if (cookieToken != null) {
                header = "Bearer " + cookieToken;
                request = new AuthorizationHeaderRequest(request, header);
            }
        }
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

    /** 把 Cookie 里的登录令牌当作 Authorization 请求头交给下游。 */
    private static final class AuthorizationHeaderRequest extends HttpServletRequestWrapper {
        private final String authorization;
        AuthorizationHeaderRequest(HttpServletRequest request, String authorization) {
            super(request);
            this.authorization = authorization;
        }
        @Override public String getHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? authorization : super.getHeader(name);
        }
        @Override public Enumeration<String> getHeaders(String name) {
            return "Authorization".equalsIgnoreCase(name) ? Collections.enumeration(List.of(authorization)) : super.getHeaders(name);
        }
        @Override public Enumeration<String> getHeaderNames() {
            var names = new LinkedHashSet<String>(Collections.list(super.getHeaderNames()));
            names.add("Authorization");
            return Collections.enumeration(names);
        }
    }
}
