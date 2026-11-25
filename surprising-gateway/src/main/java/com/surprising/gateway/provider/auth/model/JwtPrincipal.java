package com.surprising.gateway.provider.auth.model;

import java.time.Instant;
import java.util.List;

public record JwtPrincipal(
        long userId,
        String username,
        String status,
        List<String> roles,
        Instant expiresAt,
        long sessionId,
        long operatingUserId) {
    public JwtPrincipal {
        if (operatingUserId <= 0) operatingUserId = userId;
    }
    public JwtPrincipal(long userId, String username, String status, List<String> roles, Instant expiresAt) {
        this(userId, username, status, roles, expiresAt, 0L, userId);
    }
    public JwtPrincipal(long userId, String username, String status, List<String> roles, Instant expiresAt,
                        long sessionId) {
        this(userId, username, status, roles, expiresAt, sessionId, userId);
    }
}
