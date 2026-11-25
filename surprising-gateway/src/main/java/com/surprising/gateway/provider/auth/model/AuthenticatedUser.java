package com.surprising.gateway.provider.auth.model;

import java.time.Instant;
import java.util.List;

public record AuthenticatedUser(
        long userId,
        String username,
        String email,
        String status,
        List<String> roles,
        Instant createdAt,
        String displayName) {
    public AuthenticatedUser(long userId, String username, String email, String status,
                             List<String> roles, Instant createdAt) {
        this(userId, username, email, status, roles, createdAt, null);
    }
}
