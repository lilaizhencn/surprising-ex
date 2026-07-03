package com.surprising.gateway.provider.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminIpWhitelistFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";

    private final GatewayProperties properties;

    public AdminIpWhitelistFilter(GatewayProperties properties) {
        this.properties = properties;
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
        String clientIp = clientIp(request);
        if (isAllowed(clientIp, allowlist)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "admin ip is not allowed");
    }

    boolean isAllowed(String clientIp, List<String> allowlist) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        for (String rule : allowlist) {
            if (matchesRule(clientIp.trim(), rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdminRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.equals("/api/v1/admin") || uri.startsWith(ADMIN_PREFIX));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean matchesRule(String clientIp, String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String normalizedRule = rule.trim();
        if (normalizedRule.contains("/")) {
            return matchesCidr(clientIp, normalizedRule);
        }
        return clientIp.equals(normalizedRule);
    }

    private boolean matchesCidr(String clientIp, String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(clientIp);
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] addressBytes = address.getAddress();
            byte[] networkBytes = network.getAddress();
            if (addressBytes.length != networkBytes.length) {
                return false;
            }
            int prefixLength = Integer.parseInt(parts[1]);
            int maxPrefix = addressBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                return false;
            }
            BigInteger addressValue = new BigInteger(1, addressBytes);
            BigInteger networkValue = new BigInteger(1, networkBytes);
            BigInteger mask = BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE)
                    .shiftRight(maxPrefix - prefixLength)
                    .shiftLeft(maxPrefix - prefixLength);
            return addressValue.and(mask).equals(networkValue.and(mask));
        } catch (NumberFormatException | UnknownHostException ex) {
            return false;
        }
    }
}
