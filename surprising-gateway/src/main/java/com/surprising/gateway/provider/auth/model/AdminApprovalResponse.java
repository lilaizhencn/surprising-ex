package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record AdminApprovalResponse(
        long approvalId,
        long requesterUserId,
        String requesterUsername,
        Long approverUserId,
        String approverUsername,
        String service,
        String httpMethod,
        String requestPath,
        String queryString,
        String requestBodySha256,
        String reason,
        String decisionReason,
        String status,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        Instant consumedAt) {
}
