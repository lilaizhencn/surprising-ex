package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record RiskTag(
        long tagId,
        long userId,
        String tagCode,
        String severity,
        String status,
        String source,
        String reason,
        Long createdByUserId,
        Long resolvedByUserId,
        Instant createdAt,
        Instant resolvedAt,
        Instant updatedAt) {
}
