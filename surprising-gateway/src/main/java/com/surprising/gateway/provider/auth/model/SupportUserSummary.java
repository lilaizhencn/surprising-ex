package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record SupportUserSummary(
        long userId,
        String username,
        String email,
        String status,
        Instant createdAt) {
}
