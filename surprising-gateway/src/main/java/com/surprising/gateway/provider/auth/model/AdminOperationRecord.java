package com.surprising.gateway.provider.auth.model;

import java.time.Instant;
import java.util.List;

/** 后台操作的原生审计事实；协议元数据由入口提供。 */
public record AdminOperationRecord(
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
