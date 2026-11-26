package com.surprising.gateway.provider.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class GatewayUserIdentity {
    private GatewayUserIdentity() {}

    public static long requireUserId(Long queryUserId, String headerUserId, String adminUserId) {
        Long resolved = resolveUserId(queryUserId, headerUserId, adminUserId);
        if (resolved == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        return resolved;
    }

    public static Long resolveUserId(Long queryUserId, String headerUserId, String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            if (headerUserId != null && !headerUserId.isBlank()) {
                long authenticated = parseUserId(headerUserId);
                if (queryUserId != null && queryUserId != authenticated) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "userId does not match authenticated user");
                }
                return authenticated;
            }
        }
        return queryUserId;
    }

    public static long requireHeaderUserId(String headerUserId) {
        if (headerUserId == null || headerUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Id is required");
        }
        return parseUserId(headerUserId);
    }

    public static String requireAdminUserId(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin identity is required");
        }
        return adminUserId;
    }

    private static long parseUserId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid X-User-Id", ex);
        }
    }
}
