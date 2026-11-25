package com.surprising.gateway.provider.auth.model;

import java.time.Instant;
import java.util.List;

public record AdminOperationLogResponse(
        long operationId,
        Long adminUserId,
        String adminUsername,
        List<String> adminRoles,
        String service,
        String httpMethod,
        String requestPath,
        String queryString,
        String targetUri,
        String requestBodySha256,
        Integer responseStatus,
        Long durationMs,
        boolean success,
        String errorMessage,
        String userAgent,
        String ipAddress,
        Instant createdAt) {
}
