package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AmlCase(
        long caseId,
        long userId,
        String status,
        int riskScore,
        String source,
        String summary,
        Long assignedAdminUserId,
        Long createdByUserId,
        Long reviewedByUserId,
        Instant reviewedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt) {
}
