package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminSessionResponse(
        long sessionId,
        long userId,
        boolean active,
        Instant expiresAt,
        Instant revokedAt,
        String userAgent,
        String ipAddress,
        String deviceId,
        Instant createdAt,
        Instant updatedAt) {
    public AdminSessionResponse(long sessionId, long userId, boolean active, Instant expiresAt,
                                       Instant revokedAt, String userAgent, String ipAddress,
                                       Instant createdAt, Instant updatedAt) {
        this(sessionId, userId, active, expiresAt, revokedAt, userAgent, ipAddress, null,
                createdAt, updatedAt);
    }
}
