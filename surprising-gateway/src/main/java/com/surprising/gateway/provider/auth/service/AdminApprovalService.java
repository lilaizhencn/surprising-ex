package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.auth.model.AdminApprovalReference;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.repository.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;

import com.surprising.gateway.provider.auth.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;

import com.surprising.gateway.provider.auth.model.AdminApprovalCommand;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminOperationLogResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;
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
            CreatedAtPageQuery query) {
        authService.authenticateAdminBearer(authorization);
        return auditRepository.operationLogPage(adminUserId, service, method, success, query);
    }

    public AdminApprovalResponse create(String authorization, AdminApprovalCommand command) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        Instant now = Instant.now();
        Duration ttl = properties.getSecurity().getAdminApprovalTtl();
        Instant expiresAt = now.plus(ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofMinutes(30) : ttl);
        return approvalRepository.insert(principal.userId(), principal.username(), command, now, expiresAt);
    }

    public AdminCursorPage.CursorPage<AdminApprovalResponse> approvals(
            String authorization,
            String status,
            Long requesterUserId,
            Long approverUserId,
            String service,
            AuthPageQuery pageQuery) {
        authService.authenticateAdminBearer(authorization);
        return approvalRepository.approvalPage(
                status, requesterUserId, approverUserId, service, pageQuery);
    }

    @Transactional
    public AdminApprovalResponse approve(String authorization, long approvalId, String reason) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        return decide(approvalId, principal, "APPROVED", reason, Instant.now());
    }

    @Transactional
    public AdminApprovalResponse reject(String authorization, long approvalId, String reason) {
        JwtPrincipal principal = authService.authenticateAdminBearer(authorization);
        return decide(approvalId, principal, "REJECTED", reason, Instant.now());
    }

    private AdminApprovalResponse decide(long approvalId, JwtPrincipal approver,
                                          String status, String reason, Instant now) {
        AdminApprovalResponse current = requireForUpdate(approvalId);
        requirePending(current, now);
        if (current.requesterUserId() == approver.userId()) {
            throw new IllegalStateException("approval requires a different administrator");
        }
        return approvalRepository.updateDecision(approvalId, approver.userId(), approver.username(), status, reason, now);
    }

    /** 单次消费在同一事务内锁定、校验完整请求并推进状态；C2C 裁决也复用此业务入口。 */
    @Transactional
    public AdminApprovalResponse consumeApproved(long approvalId, long requesterUserId,
                                                 String service, String method, String requestPath,
                                                 String queryString, String bodyHash, Instant now) {
        AdminApprovalResponse current = requireForUpdate(approvalId);
        if (!"APPROVED".equals(current.status())) {
            throw new IllegalStateException("approval is not approved");
        }
        if (current.expiresAt().isBefore(now)) {
            throw new IllegalStateException("approval has expired");
        }
        if (current.requesterUserId() != requesterUserId) {
            throw new IllegalStateException("approval requester does not match current administrator");
        }
        // 比较同一请求的规范身份；HTTP 创建格式由 Controller 绑定，正文仍按原始字节摘要匹配。
        if (!Objects.equals(current.service(), service == null ? "" : service.trim().toLowerCase(Locale.ROOT))
                || !Objects.equals(current.httpMethod(), method == null ? "" : method.trim().toUpperCase(Locale.ROOT))
                || !Objects.equals(current.requestPath(), requestPath == null ? "" : requestPath.trim())
                || !Objects.equals(blankToNull(current.queryString()), blankToNull(queryString))
                || !Objects.equals(blankToNull(current.requestBodySha256()), bodyHash == null ? null : blankToNull(bodyHash.toLowerCase(Locale.ROOT)))) {
            throw new IllegalStateException("approval does not match this request");
        }
        return approvalRepository.markConsumed(approvalId, now);
    }

    private AdminApprovalResponse requireForUpdate(long approvalId) {
        return approvalRepository.findForUpdate(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found"));
    }

    private void requirePending(AdminApprovalResponse current, Instant now) {
        if (!"PENDING".equals(current.status())) {
            throw new IllegalStateException("approval is not pending");
        }
        if (current.expiresAt().isBefore(now)) {
            throw new IllegalStateException("approval has expired");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    @Transactional
    public void consumeGatewayApproval(long approvalId, long adminUserId, String service, String method,
            String requestPath, String query, String bodyHash, Instant now) {
        if (approvalRepository == null) throw new AdminApprovalRequiredException("admin approval repository is not available");
        try {
            consumeApproved(approvalId,adminUserId,service,method,requestPath,query,bodyHash,now);
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

    @Transactional
    public JwtPrincipal requireWrite(String authorization,
                                     String permission,
                                     String approvalService,
                                     AdminRequestMetadata request,
                                     byte[] body) {
        JwtPrincipal principal = authService.requireAdminPermission(authorization, permission);
        consumeLocalApproval(principal, approvalService, request, body);
        return principal;
    }

    @Transactional
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
        AdminApprovalReference approval = request == null ? null : request.approval();
        if (approval == null || !approval.supplied()) {
            throw new AdminApprovalRequiredException("admin approval required");
        }
        if (approval.value() == null) {
            throw new AdminApprovalRequiredException("invalid admin approval id");
        }
        long approvalId = approval.value();
        try {
            consumeApproved(
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

}
