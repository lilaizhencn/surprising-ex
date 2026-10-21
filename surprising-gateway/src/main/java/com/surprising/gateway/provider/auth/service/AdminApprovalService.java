package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.repository.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;

import com.surprising.gateway.provider.auth.utils.AdminCursorPage;

import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalCreateRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminOperationLogResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
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

    public boolean requiresGatewayApproval(String service, String method, String requestPath, boolean adminRequest) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return false;
        }
        if (!adminRequest || method == null || java.util.Set.of("GET","HEAD","OPTIONS").contains(method)) {
            return false;
        }
        String normalizedService = service == null ? "" : service.trim().toLowerCase(java.util.Locale.ROOT);
        String path = requestPath == null ? "" : requestPath.toLowerCase(java.util.Locale.ROOT);
        // Routine business configuration is versioned and audited by its owning service.
        // Financial adjustments and operational intervention still require dual approval.
        String prefix = "/api/v1/admin/gateway" + "/" + normalizedService;
        String suffix = path.startsWith(prefix + "/") ? path.substring(prefix.length()) : "";
        if ("POST".equals(method) && switch (normalizedService) {
            case "instrument-admin" -> suffix.equals("/upsert") || suffix.equals("/order-settings") || suffix.matches("/[0-9]+/status");
            case "market-maker" -> suffix.equals("/business-settings") || suffix.equals("/strategy-definitions")
                    || suffix.matches("/strategies/[a-z0-9_-]+/(config|pause|resume)");
            case "insurance-admin" -> suffix.equals("/runtime-config");
            case "risk", "liquidation", "funding", "adl" -> suffix.equals("/admin/runtime-config");
            default -> false;
        }) {
            return false;
        }
        if (List.of("account", "instrument-admin", "insurance-admin", "trading-fees", "trading-leverage",
                "trading-orders", "market-maker", "risk-admin", "liquidation-admin", "wallet-admin").contains(normalizedService)) {
            return true;
        }
        return switch (normalizedService) {
            case "risk", "liquidation", "funding", "adl" -> path.endsWith("/admin/runtime-config");
            default -> false;
        };
    }

    public void consumeGatewayApproval(long approvalId, long adminUserId, String service, String method,
            String requestPath, String query, String bodyHash, Instant now) {
        if (approvalRepository == null) throw new AdminApprovalRequiredException("admin approval repository is not available");
        try {
            approvalRepository.consumeApproved(approvalId,adminUserId,service,method,requestPath,query,bodyHash,now);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new AdminApprovalRequiredException(invalid.getMessage(),invalid);
        }
    }

    public void recordGatewayOperation(com.surprising.gateway.provider.auth.model.AdminOperationRecord record) {
        if (auditRepository != null) auditRepository.record(record);
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
            String queryString) {
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
