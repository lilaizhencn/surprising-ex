package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminPermissionFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final String ADMIN_GATEWAY_PREFIX = "/api/v1/admin/gateway/";

    private final AuthService authService;

    public AdminPermissionFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String permission = permissionCode(request);
        if (permission == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            authService.requireAdminPermission(request.getHeader(HttpHeaders.AUTHORIZATION), permission);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
        } catch (IllegalStateException ex) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, ex.getMessage());
        }
    }

    String permissionCode(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !(uri.equals("/api/v1/admin") || uri.startsWith(ADMIN_PREFIX))) {
            return null;
        }
        if (uri.startsWith(ADMIN_GATEWAY_PREFIX)) {
            return null;
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        if (HttpMethod.OPTIONS.equals(method)) {
            return null;
        }
        boolean read = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        String path = uri.substring("/api/v1/admin".length()).toLowerCase(Locale.ROOT);
        if (path.startsWith("/users") || path.startsWith("/sessions")) {
            return "admin.users." + (read ? "read" : "write");
        }
        if (path.startsWith("/audit")) {
            return "admin.audit.read";
        }
        if (path.startsWith("/approvals")) {
            return "admin.approvals." + (read ? "read" : "write");
        }
        if (path.startsWith("/system")) {
            return "admin.system.read";
        }
        if (path.startsWith("/traces")) {
            return "admin.traces.read";
        }
        if (path.startsWith("/alerts")) {
            return "admin.alerts." + (read ? "read" : "write");
        }
        if (path.startsWith("/market")) {
            return "admin.market." + (read ? "read" : "write");
        }
        if (path.startsWith("/trading")) {
            return "admin.trading." + (read ? "read" : "write");
        }
        if (path.startsWith("/reports")) {
            return "admin.reports." + (read ? "read" : "write");
        }
        if (path.startsWith("/security/mfa")) {
            return "admin.security.mfa";
        }
        if (path.startsWith("/support")) {
            return "admin.support." + (read ? "read" : "write");
        }
        if (path.startsWith("/compliance")) {
            return "admin.compliance." + (read ? "read" : "write");
        }
        if (path.startsWith("/exports")) {
            return "admin.exports." + (read ? "read" : "write");
        }
        if (path.startsWith("/query-tasks")) {
            return "admin.queries." + (read ? "read" : "write");
        }
        if (path.startsWith("/roles") || path.startsWith("/permissions")) {
            return "admin.permissions." + (read ? "read" : "write");
        }
        return "admin.unmapped." + (read ? "read" : "write");
    }
}
