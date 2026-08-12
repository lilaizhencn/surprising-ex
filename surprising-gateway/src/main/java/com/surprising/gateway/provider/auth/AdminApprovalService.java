package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminOperationLogResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 聚合后台身份、审批和操作审计仓储，统一承载后台审批业务规则。
 */
@Service
public class AdminApprovalService {

    private final AuthService authService;
    private final AdminAuditRepository auditRepository;
    private final AdminApprovalRepository approvalRepository;
    private final GatewayProperties properties;

    public AdminApprovalService(AuthService authService,
                                AdminAuditRepository auditRepository,
                                AdminApprovalRepository approvalRepository,
                                GatewayProperties properties) {
        this.authService = authService;
        this.auditRepository = auditRepository;
        this.approvalRepository = approvalRepository;
        this.properties = properties;
    }

    public AdminCursorPage.CursorPage<AdminOperationLogResponse> operationLogs(
            String authorization,
            Long adminUserId,
            String service,
            String method,
            Boolean success,
            int limit,
            String cursor,
            String sort) {
        authService.authenticateAdminBearer(authorization);
        return auditRepository.operationLogPage(adminUserId, service, method, success, limit, cursor, sort);
    }

    public AdminApprovalResponse create(String authorization, AdminApprovalCreateRequest request) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        return approvalRepository.create(
                principal,
                request,
                properties.getSecurity().getAdminApprovalTtl(),
                Instant.now());
    }

    public AdminCursorPage.CursorPage<AdminApprovalResponse> approvals(
            String authorization,
            String status,
            Long requesterUserId,
            Long approverUserId,
            String service,
            int limit,
            String cursor,
            String sort) {
        authService.authenticateAdminBearer(authorization);
        return approvalRepository.approvalPage(
                status, requesterUserId, approverUserId, service, limit, cursor, sort);
    }

    public AdminApprovalResponse approve(String authorization, long approvalId, String reason) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        return approvalRepository.approve(approvalId, principal, reason, Instant.now());
    }

    public AdminApprovalResponse reject(String authorization, long approvalId, String reason) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        return approvalRepository.reject(approvalId, principal, reason, Instant.now());
    }

    public String approvalHeaderName() {
        return properties.getSecurity().getAdminApprovalHeader();
    }

    public JwtPrincipal requireWrite(String authorization,
                                     String permission,
                                     String approvalService,
                                     AdminRequestMetadata request,
                                     byte[] body) {
        JwtPrincipal principal = authService.requireAdminPermission(authorization, permission);
        consumeLocalApproval(principal, approvalService, request, body);
        return principal;
    }

    public void consumeLocalApproval(String authorization,
                                     String approvalService,
                                     AdminRequestMetadata request,
                                     byte[] body) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        consumeLocalApproval(principal, approvalService, request, body);
    }

    private void consumeLocalApproval(JwtPrincipal principal,
                                      String approvalService,
                                      AdminRequestMetadata request,
                                      byte[] body) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        if (request == null || request.approvalId() == null || request.approvalId().isBlank()) {
            throw new AdminApprovalRequiredException("admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(request.approvalId().trim());
        } catch (NumberFormatException ex) {
            throw new AdminApprovalRequiredException("invalid admin approval id", ex);
        }
        try {
            approvalRepository.consumeApproved(
                    approvalId,
                    principal.userId(),
                    approvalService,
                    request.method(),
                    request.requestUri(),
                    request.queryString(),
                    bodySha256(body),
                    request.traceId(),
                    Instant.now());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new AdminApprovalRequiredException(ex.getMessage(), ex);
        }
    }

    private String bodySha256(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Controller 从 HTTP 请求中提取的审批匹配数据，不包含任何持久化对象。
     */
    public record AdminRequestMetadata(
            String approvalId,
            String method,
            String requestUri,
            String queryString,
            String traceId) {
    }

    public static class AdminApprovalRequiredException extends RuntimeException {

        public AdminApprovalRequiredException(String message) {
            super(message);
        }

        public AdminApprovalRequiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
