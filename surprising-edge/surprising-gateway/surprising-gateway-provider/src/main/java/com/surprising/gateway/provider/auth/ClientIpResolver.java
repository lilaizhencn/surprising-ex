package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public final class ClientIpResolver {

    private final GatewayProperties properties;

    public ClientIpResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddress = literalValue(request.getRemoteAddr());
        if (remoteAddress == null) {
            return request.getRemoteAddr();
        }
        List<String> trustedProxies = properties.getSecurity().getTrustedProxyIpAllowlist();
        if (!isAllowed(remoteAddress, trustedProxies)) {
            return remoteAddress;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        String current = remoteAddress;
        String[] chain = forwarded.split(",", -1);
        for (int index = chain.length - 1; index >= 0; index--) {
            if (!isAllowed(current, trustedProxies)) {
                return current;
            }
            String candidate = chain[index].trim();
            if (literalValue(candidate) == null) {
                return candidate;
            }
            current = candidate;
        }
        return current;
    }

    public boolean isAllowed(String clientIp, List<String> allowlist) {
        if (literalValue(clientIp) == null || allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        for (String rule : allowlist) {
            if (matchesRule(clientIp, rule)) {
                return true;
            }
        }
        return false;
    }

    public boolean isValidRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String[] parts = rule.trim().split("/", -1);
        if (parts.length > 2 || literalValue(parts[0]) == null) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= addressBits(parts[0]);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private boolean matchesRule(String clientIp, String rule) {
        if (!isValidRule(rule)) {
            return false;
        }
        String[] parts = rule.trim().split("/", -1);
        InetAddress client = parseLiteral(clientIp);
        InetAddress network = parseLiteral(parts[0]);
        if (client == null || network == null || client.getAddress().length != network.getAddress().length) {
            return false;
        }
        if (parts.length == 1) {
            return client.equals(network);
        }
        int prefix = Integer.parseInt(parts[1]);
        byte[] clientBytes = client.getAddress();
        byte[] networkBytes = network.getAddress();
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

    private int addressBits(String value) {
        InetAddress address = parseLiteral(value);
        return address == null ? -1 : address.getAddress().length * 8;
    }

    private String literalValue(String value) {
        return parseLiteral(value) == null ? null : value.trim();
    }

    private InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isBlank()) {
            return null;
        }
        if (candidate.indexOf(':') >= 0) {
            if (!candidate.matches("[0-9A-Fa-f:.]+")) {
                return null;
            }
        } else {
            if (!candidate.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
                return null;
            }
            for (String octet : candidate.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException | NumberFormatException ex) {
            return null;
        }
    }
}
