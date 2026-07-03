package com.surprising.gateway.provider.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AuthModels {

    private AuthModels() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 254) String email) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password,
            String totpCode) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record AuthenticatedUser(
            long userId,
            String username,
            String email,
            String status,
            List<String> roles,
            Instant createdAt) {
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public record AuthResponse(
            AuthenticatedUser user,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public record JwtPrincipal(
            long userId,
            String username,
            String status,
            List<String> roles,
            Instant expiresAt) {
    }

    public record AdminUserQueryResponse(
            int count,
            List<AuthenticatedUser> users,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {

        public AdminUserQueryResponse(int count, List<AuthenticatedUser> users) {
            this(count, users, null, false, null, count);
        }
    }

    public record AdminUserStatusRequest(
            @NotBlank String status) {
    }

    public record AdminUserRolesRequest(
            List<@NotBlank String> roles) {
    }

    public record AdminRoleResponse(
            String roleCode,
            String roleName,
            int permissionCount,
            Instant createdAt) {
    }

    public record AdminRoleQueryResponse(
            int count,
            List<AdminRoleResponse> roles) {
    }

    public record AdminPermissionResponse(
            String permissionCode,
            String permissionName,
            String description,
            Instant createdAt) {
    }

    public record AdminPermissionQueryResponse(
            int count,
            List<AdminPermissionResponse> permissions) {
    }

    public record AdminRolePermissionsResponse(
            String roleCode,
            List<String> permissions) {
    }

    public record AdminRolePermissionsRequest(
            List<@NotBlank String> permissions) {
    }

    public record AdminRefreshSessionResponse(
            long sessionId,
            long userId,
            boolean active,
            Instant expiresAt,
            Instant revokedAt,
            String userAgent,
            String ipAddress,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AdminRefreshSessionQueryResponse(
            int count,
            List<AdminRefreshSessionResponse> sessions,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {

        public AdminRefreshSessionQueryResponse(int count, List<AdminRefreshSessionResponse> sessions) {
            this(count, sessions, null, false, null, count);
        }
    }

    public record AdminSessionRevokeResponse(
            int revoked,
            Instant revokedAt) {
    }

    public record AdminMfaStatusResponse(
            boolean enabled,
            Instant verifiedAt) {
    }

    public record AdminMfaEnrollmentResponse(
            boolean enabled,
            String secret,
            String otpauthUri,
            Instant generatedAt) {
    }

    public record AdminMfaVerificationRequest(
            @NotBlank String totpCode) {
    }

    public record LoginLogResponse(
            long loginId,
            Long userId,
            String result,
            String reason,
            String userAgent,
            String ipAddress,
            Instant createdAt) {
    }

    public record LoginLogQueryResponse(
            int count,
            List<LoginLogResponse> logs,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public LoginLogQueryResponse(int count, List<LoginLogResponse> logs) {
            this(count, logs, null, false, "createdAt.desc", count);
        }
    }

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
            String traceId,
            String userAgent,
            String ipAddress,
            Instant createdAt) {
    }

    public record AdminOperationLogQueryResponse(
            int count,
            List<AdminOperationLogResponse> logs,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AdminOperationLogQueryResponse(int count, List<AdminOperationLogResponse> logs) {
            this(count, logs, null, false, "createdAt.desc", count);
        }
    }

    public record AdminApprovalCreateRequest(
            @NotBlank String service,
            @NotBlank String httpMethod,
            @NotBlank String requestPath,
            String queryString,
            String requestBodySha256,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record AdminApprovalDecisionRequest(
            @Size(max = 1000) String reason) {
    }

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
            Instant consumedAt,
            String consumedTraceId) {
    }

    public record AdminApprovalQueryResponse(
            int count,
            List<AdminApprovalResponse> approvals,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AdminApprovalQueryResponse(int count, List<AdminApprovalResponse> approvals) {
            this(count, approvals, null, false, "requestedAt.desc", count);
        }
    }

    public record AdminExportCreateRequest(
            @NotBlank String exportType,
            Map<String, String> params) {
    }

    public record AdminExportJobResponse(
            long exportId,
            long requestedByUserId,
            String requestedByUsername,
            String exportType,
            String status,
            String format,
            String queryParams,
            String fileName,
            String contentType,
            int rowCount,
            long byteSize,
            String errorMessage,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant expiresAt) {
    }

    public record AdminExportJobQueryResponse(
            int count,
            List<AdminExportJobResponse> exports,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AdminExportJobQueryResponse(int count, List<AdminExportJobResponse> exports) {
            this(count, exports, null, false, "requestedAt.desc", count);
        }
    }

    public record AdminQueryTaskCreateRequest(
            @NotBlank String queryType,
            Map<String, String> params) {
    }

    public record AdminQueryTaskResponse(
            long queryTaskId,
            long requestedByUserId,
            String requestedByUsername,
            String queryType,
            String status,
            String queryParams,
            String resultJson,
            int rowCount,
            long byteSize,
            String errorMessage,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant expiresAt,
            Instant archivedAt,
            String archiveReason) {
    }

    public record AdminQueryTaskQueryResponse(
            int count,
            List<AdminQueryTaskResponse> tasks,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
        public AdminQueryTaskQueryResponse(int count, List<AdminQueryTaskResponse> tasks) {
            this(count, tasks, null, false, "requestedAt.desc", count);
        }
    }

    public record AdminQueryTaskArchiveRequest(
            Integer olderThanDays,
            Integer limit,
            String reason) {
    }

    public record AdminQueryTaskArchiveResponse(
            int archivedCount,
            Instant archivedBefore,
            int limit,
            String reason) {
    }

    public record AdminQueryTaskLimitsResponse(
            long activeTasksForUser,
            int maxActiveTasksPerUser,
            long activeTasksGlobal,
            int maxActiveTasksGlobal,
            long createdByUserInWindow,
            int maxCreatedByUserInWindow,
            long creationWindowSeconds,
            long retainedResultBytes,
            long maxRetainedResultBytes,
            long expiredTasksReadyToArchive) {
    }
}
