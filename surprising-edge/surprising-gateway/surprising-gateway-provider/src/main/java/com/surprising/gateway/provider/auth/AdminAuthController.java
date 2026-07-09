package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalDecisionRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminPermissionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRolePermissionsRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminRolePermissionsResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRoleQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminUserQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminUserRolesRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminUserStatusRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminOperationLogQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogQueryResponse;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuthController {

    private final AuthService authService;
    private final AdminAuditRepository adminAuditRepository;
    private final AdminApprovalRepository adminApprovalRepository;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AdminAuthController(AuthService authService,
                               AdminAuditRepository adminAuditRepository,
                               AdminApprovalRepository adminApprovalRepository,
                               GatewayProperties properties,
                               ObjectMapper objectMapper) {
        this.authService = authService;
        this.adminAuditRepository = adminAuditRepository;
        this.adminApprovalRepository = adminApprovalRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/users")
    public AdminUserQueryResponse users(@RequestHeader("Authorization") String authorization,
                                        @RequestParam(value = "query", required = false) String query,
                                        @RequestParam(value = "status", required = false) String status,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit,
                                        @RequestParam(value = "cursor", required = false) String cursor,
                                        @RequestParam(value = "sort", required = false) String sort) {
        try {
            return authService.adminUsers(authorization, query, status, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/users/{userId}")
    public AuthenticatedUser user(@RequestHeader("Authorization") String authorization,
                                  @PathVariable("userId") long userId) {
        try {
            return authService.adminUser(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/sessions")
    public AdminRefreshSessionQueryResponse sessions(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        try {
            return authService.adminRefreshSessions(authorization, userId, active, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/users/{userId}/sessions")
    public AdminRefreshSessionQueryResponse userSessions(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("userId") long userId,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.adminUser(authorization, userId);
            return authService.adminRefreshSessions(authorization, userId, active, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public AdminSessionRevokeResponse revokeSession(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable("sessionId") long sessionId,
                                                    @RequestBody(required = false) byte[] body,
                                                    HttpServletRequest httpRequest) {
        try {
            requireLocalAdminApproval(authorization, httpRequest, body);
            return authService.revokeRefreshSession(authorization, sessionId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/sessions/revoke")
    public AdminSessionRevokeResponse revokeUserSessions(@RequestHeader("Authorization") String authorization,
                                                         @PathVariable("userId") long userId,
                                                         @RequestBody(required = false) byte[] body,
                                                         HttpServletRequest httpRequest) {
        try {
            requireLocalAdminApproval(authorization, httpRequest, body);
            return authService.revokeUserRefreshSessions(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/status")
    public AuthenticatedUser updateStatus(@RequestHeader("Authorization") String authorization,
                                          @PathVariable("userId") long userId,
                                          @RequestBody byte[] body,
                                          HttpServletRequest httpRequest) {
        try {
            AdminUserStatusRequest request = readBody(body, AdminUserStatusRequest.class);
            requireLocalAdminApproval(authorization, httpRequest, body);
            return authService.updateUserStatus(authorization, userId, request.status());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/users/{userId}/roles")
    public AuthenticatedUser replaceRoles(@RequestHeader("Authorization") String authorization,
                                          @PathVariable("userId") long userId,
                                          @RequestBody byte[] body,
                                          HttpServletRequest httpRequest) {
        try {
            AdminUserRolesRequest request = readBody(body, AdminUserRolesRequest.class);
            requireLocalAdminApproval(authorization, httpRequest, body);
            return authService.replaceUserRoles(authorization, userId, request.roles());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/audit/login-logs")
    public LoginLogQueryResponse loginLogs(@RequestHeader("Authorization") String authorization,
                                           @RequestParam(value = "userId", required = false) Long userId,
                                           @RequestParam(value = "result", required = false) String result,
                                           @RequestParam(value = "limit", defaultValue = "100") int limit,
                                           @RequestParam(value = "cursor", required = false) String cursor,
                                           @RequestParam(value = "sort", required = false) String sort) {
        try {
            return authService.loginLogs(authorization, userId, result, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/audit/operations")
    public AdminOperationLogQueryResponse operationLogs(@RequestHeader("Authorization") String authorization,
                                                        @RequestParam(value = "adminUserId", required = false) Long adminUserId,
                                                        @RequestParam(value = "service", required = false) String service,
                                                        @RequestParam(value = "method", required = false) String method,
                                                        @RequestParam(value = "success", required = false) Boolean success,
                                                        @RequestParam(value = "limit", defaultValue = "100") int limit,
                                                        @RequestParam(value = "cursor", required = false) String cursor,
                                                        @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.authenticateAdminBearer(authorization);
            var page = adminAuditRepository.operationLogPage(adminUserId, service, method, success, limit, cursor, sort);
            return new AdminOperationLogQueryResponse(page.items().size(), page.items(),
                    page.nextCursor(), page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/roles")
    public AdminRoleQueryResponse roles(@RequestHeader("Authorization") String authorization) {
        try {
            return authService.adminRoles(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/permissions")
    public AdminPermissionQueryResponse permissions(@RequestHeader("Authorization") String authorization) {
        try {
            return authService.adminPermissions(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/roles/{roleCode}/permissions")
    public AdminRolePermissionsResponse rolePermissions(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable("roleCode") String roleCode) {
        try {
            return authService.adminRolePermissions(authorization, roleCode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/roles/{roleCode}/permissions")
    public AdminRolePermissionsResponse replaceRolePermissions(@RequestHeader("Authorization") String authorization,
                                                               @PathVariable("roleCode") String roleCode,
                                                               @RequestBody byte[] body,
                                                               HttpServletRequest httpRequest) {
        try {
            AdminRolePermissionsRequest request = readBody(body, AdminRolePermissionsRequest.class);
            requireLocalAdminApproval(authorization, httpRequest, body);
            return authService.replaceRolePermissions(authorization, roleCode, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/approvals")
    public AdminApprovalResponse createApproval(@RequestHeader("Authorization") String authorization,
                                                @Valid @RequestBody AdminApprovalCreateRequest request) {
        try {
            var principal = authService.authenticateAdminBearer(authorization);
            return adminApprovalRepository.create(principal, request,
                    properties.getSecurity().getAdminApprovalTtl(), Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/approvals")
    public AdminApprovalQueryResponse approvals(@RequestHeader("Authorization") String authorization,
                                                @RequestParam(value = "status", required = false) String status,
                                                @RequestParam(value = "requesterUserId", required = false) Long requesterUserId,
                                                @RequestParam(value = "approverUserId", required = false) Long approverUserId,
                                                @RequestParam(value = "service", required = false) String service,
                                                @RequestParam(value = "limit", defaultValue = "100") int limit,
                                                @RequestParam(value = "cursor", required = false) String cursor,
                                                @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.authenticateAdminBearer(authorization);
            var page = adminApprovalRepository.approvalPage(
                    status, requesterUserId, approverUserId, service, limit, cursor, sort);
            return new AdminApprovalQueryResponse(page.items().size(), page.items(),
                    page.nextCursor(), page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public AdminApprovalResponse approve(@RequestHeader("Authorization") String authorization,
                                         @PathVariable("approvalId") long approvalId,
                                         @Valid @RequestBody(required = false) AdminApprovalDecisionRequest request) {
        try {
            var principal = authService.authenticateAdminBearer(authorization);
            return adminApprovalRepository.approve(approvalId, principal,
                    request == null ? null : request.reason(), Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public AdminApprovalResponse reject(@RequestHeader("Authorization") String authorization,
                                        @PathVariable("approvalId") long approvalId,
                                        @Valid @RequestBody(required = false) AdminApprovalDecisionRequest request) {
        try {
            var principal = authService.authenticateAdminBearer(authorization);
            return adminApprovalRepository.reject(approvalId, principal,
                    request == null ? null : request.reason(), Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private <T> T readBody(byte[] body, Class<T> type) {
        try {
            return objectMapper.readValue(body == null ? new byte[0] : body, type);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("invalid request body", ex);
        }
    }

    private void requireLocalAdminApproval(String authorization, HttpServletRequest request, byte[] body) {
        if (!properties.getSecurity().isRequireApprovalForHighRiskAdminWrites()) {
            return;
        }
        var principal = authService.authenticateAdminBearer(authorization);
        String approvalIdHeader = request.getHeader(properties.getSecurity().getAdminApprovalHeader());
        if (approvalIdHeader == null || approvalIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "admin approval required");
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(approvalIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "invalid admin approval id", ex);
        }
        try {
            adminApprovalRepository.consumeApproved(
                    approvalId,
                    principal.userId(),
                    "gateway-admin",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    bodySha256(body),
                    traceId(request),
                    Instant.now());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage(), ex);
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

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE);
        if (value instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return request.getHeader(GatewayTraceFilter.TRACE_ID_HEADER);
    }
}
