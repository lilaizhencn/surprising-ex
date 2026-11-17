package com.surprising.gateway.provider.auth.utils;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public final class ClientIpResolver {

    private static final List<String> CLIENT_IP_HEADERS = List.of(
            "CF-Connecting-IP",       // Cloudflare
            "True-Client-IP",         // Akamai / Cloudflare Enterprise
            "Fastly-Client-IP",        // Fastly
            "X-Azure-ClientIP",        // Azure Front Door / Application Gateway integrations
            "CloudFront-Viewer-Address" // AWS CloudFront (address may include a port)
    );
    private static final List<String> FALLBACK_CLIENT_IP_HEADERS = List.of("X-Real-IP", "X-Client-IP");

    private final GatewayProperties properties;

    public ClientIpResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return resolve(request.getRemoteAddr(), request::getHeader);
    }

    public String resolve(String rawRemoteAddr, java.util.function.Function<String, String> headerLookup) {
        String remoteAddress = literalValue(rawRemoteAddr);
        if (remoteAddress == null) {
            return rawRemoteAddr;
        }
        List<String> trustedProxies = properties.getSecurity().getTrustedProxyIpAllowlist();
        if (!isAllowed(remoteAddress, trustedProxies)) {
            return remoteAddress;
        }
        // These headers are only meaningful when the trusted ingress overwrites them and the
        // application cannot be reached directly around that ingress.
        for (String header : CLIENT_IP_HEADERS) {
            String candidate = directHeaderIp(headerLookup.apply(header), header);
            if (candidate != null) {
                return candidate;
            }
        }
        // Alibaba SLB/ALB, Tencent CLB, AWS ALB, Azure, Huawei ELB, and Google Cloud
        // load balancers use X-Forwarded-For; resolve it as a trusted hop chain.
        String forwardedClient = forwardedChainClientIp(headerLookup.apply("X-Forwarded-For"),
                remoteAddress, trustedProxies);
        if (forwardedClient != null) return forwardedClient;
        for (String header : FALLBACK_CLIENT_IP_HEADERS) {
            String candidate = literalValue(headerLookup.apply(header));
            if (candidate != null) return candidate;
        }
        return remoteAddress;
    }

    private String forwardedChainClientIp(String forwarded, String remoteAddress, List<String> trustedProxies) {
        if (forwarded == null || forwarded.isBlank()) return null;
        String current = remoteAddress;
        String[] chain = forwarded.split(",", -1);
        for (int index = chain.length - 1; index >= 0; index--) {
            if (!isAllowed(current, trustedProxies)) return current;
            String candidate = literalValue(chain[index]);
            if (candidate == null) return null;
            current = candidate;
        }
        return current;
    }

    private String directHeaderIp(String value, String header) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if ("CloudFront-Viewer-Address".equalsIgnoreCase(header)) {
            if (candidate.startsWith("[")) {
                int closingBracket = candidate.indexOf(']');
                if (closingBracket > 0) {
                    candidate = candidate.substring(1, closingBracket);
                }
            } else {
                int colon = candidate.lastIndexOf(':');
                if (colon > 0 && candidate.indexOf(':') == colon) {
                    candidate = candidate.substring(0, colon);
                }
            }
        }
        return literalValue(candidate);
    }

    public boolean isAllowed(String clientIp, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        InetAddress client = parseLiteral(clientIp);
        if (client == null) {
            return false;
        }
        byte[] clientBytes = client.getAddress();
        for (String rule : allowlist) {
            if (matchesRule(client, clientBytes, rule)) {
                return true;
            }
        }
        return false;
    }

    public boolean isValidRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String trimmed = rule.trim();
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            return parseLiteral(trimmed) != null;
        }
        if (trimmed.indexOf('/', slash + 1) >= 0) {
            return false;
        }
        InetAddress network = parseLiteral(trimmed.substring(0, slash));
        if (network == null) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(trimmed.substring(slash + 1));
            return prefix >= 0 && prefix <= network.getAddress().length * 8;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private boolean matchesRule(InetAddress client, byte[] clientBytes, String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String trimmed = rule.trim();
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            InetAddress network = parseLiteral(trimmed);
            return network != null && clientBytes.length == network.getAddress().length && client.equals(network);
        }
        if (trimmed.indexOf('/', slash + 1) >= 0) {
            return false;
        }
        InetAddress network = parseLiteral(trimmed.substring(0, slash));
        if (network == null) {
            return false;
        }
        byte[] networkBytes = network.getAddress();
        if (clientBytes.length != networkBytes.length) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(trimmed.substring(slash + 1));
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefix < 0 || prefix > networkBytes.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (clientBytes[index] != networkBytes[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (clientBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }

    private String literalValue(String value) {
        return parseLiteral(value) == null ? null : value.trim();
    }

    private InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            if (candidate.indexOf(':') >= 0) {
                for (int i = 0; i < candidate.length(); i++) {
                    char c = candidate.charAt(i);
                    boolean validHexOrSep = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                            || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
                    if (!validHexOrSep) {
                        return null;
                    }
                }
                return InetAddress.getByName(candidate);
            }
            byte[] ipv4 = parseIpv4Bytes(candidate);
            return ipv4 == null ? null : InetAddress.getByAddress(ipv4);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static byte[] parseIpv4Bytes(String candidate) {
        int len = candidate.length();
        if (len < 7 || len > 15) {
            return null;
        }
        byte[] bytes = new byte[4];
        int octet = 0;
        int digits = 0;
        int part = 0;
        for (int i = 0; i < len; i++) {
            char c = candidate.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > 3) {
                    return null;
                }
                octet = octet * 10 + (c - '0');
                if (octet > 255) {
                    return null;
                }
            } else if (c == '.') {
                if (digits == 0 || part >= 3) {
                    return null;
                }
                bytes[part++] = (byte) octet;
                octet = 0;
                digits = 0;
            } else {
                return null;
            }
        }
        if (digits == 0 || part != 3) {
            return null;
        }
        bytes[3] = (byte) octet;
        return bytes;
    }
}
