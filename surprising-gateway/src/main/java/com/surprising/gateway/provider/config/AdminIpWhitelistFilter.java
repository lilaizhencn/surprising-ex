package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminIpWhitelistFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";

    private final GatewayProperties properties;
    private final ClientIpResolver clientIpResolver;

    public AdminIpWhitelistFilter(GatewayProperties properties) {
        this.properties = properties;
        this.clientIpResolver = new ClientIpResolver(properties);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isAdminRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        List<String> allowlist = properties.getSecurity().getAdminIpAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientIp = clientIpResolver.resolve(request);
        if (isAllowed(clientIp, allowlist)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "admin ip is not allowed");
    }

    boolean isAllowed(String clientIp, List<String> allowlist) {
        return clientIpResolver.isAllowed(clientIp, allowlist);
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.equals("/api/v1/admin") || uri.startsWith(ADMIN_PREFIX));
    }

}
