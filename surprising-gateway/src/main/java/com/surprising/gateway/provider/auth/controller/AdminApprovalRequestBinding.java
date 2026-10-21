package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.AdminApprovalCommand;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalCreateRequest;
import java.util.Locale;

/** 创建审批的格式校验与规范化，状态、身份和有效期由 Service 裁决。 */
public final class AdminApprovalRequestBinding {
    private AdminApprovalRequestBinding() {
    }

    public static AdminApprovalCommand create(AdminApprovalCreateRequest request) {
        return new AdminApprovalCommand(normalizeService(request.service()), normalizeMethod(request.httpMethod()),
                normalizeRequestPath(request.requestPath()), blankToNull(request.queryString()),
                normalizeBodyHash(request.requestBodySha256()), normalizeReason(request.reason()));
    }

    public static String decisionReason(String reason) {
        return blankToNull(reason);
    }

    private static String normalizeService(String service) {
        String normalized = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid approval service");
        }
        return normalized;
    }

    private static String normalizeMethod(String method) {
        String normalized = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,16}")) {
            throw new IllegalArgumentException("invalid approval method");
        }
        return normalized;
    }

    private static String normalizeRequestPath(String requestPath) {
        String normalized = requestPath == null ? "" : requestPath.trim();
        if (!(normalized.startsWith("/api/v1/admin/gateway/") || normalized.startsWith("/api/v1/admin/"))
                || normalized.length() > 2048) {
            throw new IllegalArgumentException("invalid approval request path");
        }
        return normalized;
    }

    private static String normalizeBodyHash(String bodyHash) {
        String normalized = blankToNull(bodyHash);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid request body hash");
        }
        return normalized;
    }

    private static String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank() || normalized.length() > 1000) {
            throw new IllegalArgumentException("approval reason is required");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
