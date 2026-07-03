package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminApprovalResponse create(JwtPrincipal requester,
                                        AdminApprovalCreateRequest request,
                                        Duration ttl,
                                        Instant now) {
        String service = normalizeService(request.service());
        String method = normalizeMethod(request.httpMethod());
        String requestPath = normalizeRequestPath(request.requestPath());
        String queryString = blankToNull(request.queryString());
        String bodyHash = normalizeBodyHash(request.requestBodySha256());
        String reason = normalizeReason(request.reason());
        Instant expiresAt = now.plus(ttl == null || ttl.isNegative() || ttl.isZero()
                ? Duration.ofMinutes(30)
                : ttl);

        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_approval_requests (
                    requester_user_id, requester_username, service, http_method, request_path,
                    query_string, request_body_sha256, reason, status, requested_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                RETURNING approval_id, requester_user_id, requester_username, approver_user_id,
                          approver_username, service, http_method, request_path, query_string,
                          request_body_sha256, reason, decision_reason, status, requested_at,
                          expires_at, decided_at, consumed_at, consumed_trace_id
                """, this::toResponse,
                requester.userId(), requester.username(), service, method, requestPath, queryString,
                bodyHash, reason, Timestamp.from(now), Timestamp.from(expiresAt));
    }

    @Transactional
    public AdminApprovalResponse approve(long approvalId,
                                         JwtPrincipal approver,
                                         String reason,
                                         Instant now) {
        AdminApprovalResponse current = requireForUpdate(approvalId);
        requirePending(current, now);
        if (current.requesterUserId() == approver.userId()) {
            throw new IllegalStateException("approval requires a different administrator");
        }
        return updateDecision(approvalId, approver, "APPROVED", reason, now);
    }

    @Transactional
    public AdminApprovalResponse reject(long approvalId,
                                        JwtPrincipal approver,
                                        String reason,
                                        Instant now) {
        AdminApprovalResponse current = requireForUpdate(approvalId);
        requirePending(current, now);
        if (current.requesterUserId() == approver.userId()) {
            throw new IllegalStateException("approval requires a different administrator");
        }
        return updateDecision(approvalId, approver, "REJECTED", reason, now);
    }

    @Transactional
    public AdminApprovalResponse consumeApproved(long approvalId,
                                                 long requesterUserId,
                                                 String service,
                                                 String method,
                                                 String requestPath,
                                                 String queryString,
                                                 String bodyHash,
                                                 String traceId,
                                                 Instant now) {
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
        if (!Objects.equals(current.service(), normalizeService(service))
                || !Objects.equals(current.httpMethod(), normalizeMethod(method))
                || !Objects.equals(current.requestPath(), normalizeRequestPath(requestPath))
                || !Objects.equals(blankToNull(current.queryString()), blankToNull(queryString))
                || !Objects.equals(blankToNull(current.requestBodySha256()), normalizeBodyHash(bodyHash))) {
            throw new IllegalStateException("approval does not match this request");
        }
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_approval_requests
                   SET status = 'CONSUMED',
                       consumed_at = ?,
                       consumed_trace_id = ?
                 WHERE approval_id = ?
                RETURNING approval_id, requester_user_id, requester_username, approver_user_id,
                          approver_username, service, http_method, request_path, query_string,
                          request_body_sha256, reason, decision_reason, status, requested_at,
                          expires_at, decided_at, consumed_at, consumed_trace_id
                """, this::toResponse, Timestamp.from(now), blankToNull(traceId), approvalId);
    }

    public List<AdminApprovalResponse> approvals(String status,
                                                 Long requesterUserId,
                                                 Long approverUserId,
                                                 String service,
                                                 int limit) {
        return approvalPage(status, requesterUserId, approverUserId, service, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminApprovalResponse> approvalPage(String status,
                                                                          Long requesterUserId,
                                                                          Long approverUserId,
                                                                          String service,
                                                                          int limit,
                                                                          String cursor,
                                                                          String sort) {
        String normalizedStatus = normalizeStatusFilter(status);
        String normalizedService = service == null || service.isBlank() ? null : normalizeService(service);
        int safeLimit = AdminCursorPage.limit(limit, 500);
        AdminCursorPage.SortSpec requestedAtDesc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "approval_id", true);
        AdminCursorPage.SortSpec requestedAtAsc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "approval_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, requestedAtDesc, List.of(requestedAtDesc, requestedAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        args.add(requesterUserId);
        args.add(requesterUserId);
        args.add(approverUserId);
        args.add(approverUserId);
        args.add(normalizedService);
        args.add(normalizedService);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminApprovalResponse> rows = jdbcTemplate.query("""
                SELECT approval_id, requester_user_id, requester_username, approver_user_id,
                       approver_username, service, http_method, request_path, query_string,
                       request_body_sha256, reason, decision_reason, status, requested_at,
                       expires_at, decided_at, consumed_at, consumed_trace_id
                  FROM gateway_admin_approval_requests
                 WHERE (CAST(? AS text) IS NULL OR status = ?)
                   AND (CAST(? AS text) IS NULL OR requester_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR approver_user_id = ?)
                   AND (CAST(? AS text) IS NULL OR service = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                this::toResponse,
                args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, AdminApprovalResponse::requestedAt,
                AdminApprovalResponse::approvalId);
    }

    private AdminApprovalResponse updateDecision(long approvalId,
                                                 JwtPrincipal approver,
                                                 String status,
                                                 String reason,
                                                 Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_approval_requests
                   SET status = ?,
                       approver_user_id = ?,
                       approver_username = ?,
                       decision_reason = ?,
                       decided_at = ?
                 WHERE approval_id = ?
                RETURNING approval_id, requester_user_id, requester_username, approver_user_id,
                          approver_username, service, http_method, request_path, query_string,
                          request_body_sha256, reason, decision_reason, status, requested_at,
                          expires_at, decided_at, consumed_at, consumed_trace_id
                """, this::toResponse,
                status, approver.userId(), approver.username(), blankToNull(reason),
                Timestamp.from(now), approvalId);
    }

    private AdminApprovalResponse requireForUpdate(long approvalId) {
        List<AdminApprovalResponse> rows = jdbcTemplate.query("""
                SELECT approval_id, requester_user_id, requester_username, approver_user_id,
                       approver_username, service, http_method, request_path, query_string,
                       request_body_sha256, reason, decision_reason, status, requested_at,
                       expires_at, decided_at, consumed_at, consumed_trace_id
                  FROM gateway_admin_approval_requests
                 WHERE approval_id = ?
                 FOR UPDATE
                """, this::toResponse, approvalId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("approval not found");
        }
        return rows.get(0);
    }

    private void requirePending(AdminApprovalResponse current, Instant now) {
        if (!"PENDING".equals(current.status())) {
            throw new IllegalStateException("approval is not pending");
        }
        if (current.expiresAt().isBefore(now)) {
            throw new IllegalStateException("approval has expired");
        }
    }

    private AdminApprovalResponse toResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminApprovalResponse(
                rs.getLong("approval_id"),
                rs.getLong("requester_user_id"),
                rs.getString("requester_username"),
                nullableLong(rs, "approver_user_id"),
                rs.getString("approver_username"),
                rs.getString("service"),
                rs.getString("http_method"),
                rs.getString("request_path"),
                rs.getString("query_string"),
                rs.getString("request_body_sha256"),
                rs.getString("reason"),
                rs.getString("decision_reason"),
                rs.getString("status"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                nullableInstant(rs, "decided_at"),
                nullableInstant(rs, "consumed_at"),
                rs.getString("consumed_trace_id"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String normalizeService(String service) {
        String normalized = service == null ? "" : service.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid approval service");
        }
        return normalized;
    }

    private String normalizeMethod(String method) {
        String normalized = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,16}")) {
            throw new IllegalArgumentException("invalid approval method");
        }
        return normalized;
    }

    private String normalizeRequestPath(String requestPath) {
        String normalized = requestPath == null ? "" : requestPath.trim();
        if (!(normalized.startsWith("/api/v1/admin/gateway/") || normalized.startsWith("/api/v1/admin/"))
                || normalized.length() > 2048) {
            throw new IllegalArgumentException("invalid approval request path");
        }
        return normalized;
    }

    private String normalizeBodyHash(String bodyHash) {
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

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank() || normalized.length() > 1000) {
            throw new IllegalArgumentException("approval reason is required");
        }
        return normalized;
    }

    private String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "APPROVED", "REJECTED", "CONSUMED").contains(normalized)) {
            throw new IllegalArgumentException("invalid approval status");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
