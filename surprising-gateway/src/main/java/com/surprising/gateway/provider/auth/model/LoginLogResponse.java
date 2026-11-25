package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record LoginLogResponse(
        long loginId,
        Long userId,
        String result,
        String reason,
        String userAgent,
        String ipAddress,
        Instant createdAt) {
}
