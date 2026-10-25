package com.surprising.gateway.provider.auth.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;

import com.surprising.gateway.provider.auth.model.AdminApprovalCommand;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_admin_approval_requests} 表。
 */
@Repository
public class AdminApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminApprovalResponse insert(long requesterUserId, String requesterUsername,
                                         AdminApprovalCommand command, Instant now, Instant expiresAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_admin_approval_requests (
                    requester_user_id, requester_username, service, http_method, request_path,
                    query_string, request_body_sha256, reason, status, requested_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                RETURNING approval_id, requester_user_id, requester_username, approver_user_id,
                          approver_username, service, http_method, request_path, query_string,
                          request_body_sha256, reason, decision_reason, status, requested_at,
                          expires_at, decided_at, consumed_at
                """, this::toResponse,
                requesterUserId, requesterUsername, command.service(), command.httpMethod(), command.requestPath(), command.queryString(),
                command.requestBodySha256(), command.reason(), Timestamp.from(now), Timestamp.from(expiresAt));
    }

    public AdminApprovalResponse markConsumed(long approvalId, Instant now) {
        return jdbcTemplate.queryForObject("""
                UPDATE gateway_admin_approval_requests
                   SET status = 'CONSUMED',
                       consumed_at = ?
                 WHERE approval_id = ?
                RETURNING approval_id, requester_user_id, requester_username, approver_user_id,
                          approver_username, service, http_method, request_path, query_string,
                          request_body_sha256, reason, decision_reason, status, requested_at,
                          expires_at, decided_at, consumed_at
                """, this::toResponse, Timestamp.from(now), approvalId);
    }

    public List<AdminApprovalResponse> approvals(String status,
                                                 Long requesterUserId,
                                                 Long approverUserId,
                                                 String service,
                                                 int limit) {
        return approvalPage(status, requesterUserId, approverUserId, service, new AuthPageQuery(limit, null, AuthPageQuery.Order.REQUESTED_DESC)).items();
    }

    public AdminCursorPage.CursorPage<AdminApprovalResponse> approvalPage(String status,
                                                                          Long requesterUserId,
                                                                          Long approverUserId,
                                                                          String service,
                                                                          AuthPageQuery pageQuery) {
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec requestedAtDesc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "approval_id", true);
        AdminCursorPage.SortSpec requestedAtAsc = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "approval_id", false);
        AdminCursorPage.SortSpec sortSpec = List.of(requestedAtDesc, requestedAtAsc).stream()
                .filter(spec -> spec.field().equals(pageQuery.order().field()) && spec.descending() == pageQuery.order().descending())
                .findFirst().orElseThrow(() -> new IllegalStateException("unsupported native page order"));
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.add(status);
        args.add(requesterUserId);
        args.add(requesterUserId);
        args.add(approverUserId);
        args.add(approverUserId);
        args.add(service);
        args.add(service);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminApprovalResponse> rows = jdbcTemplate.query("""
                SELECT approval_id, requester_user_id, requester_username, approver_user_id,
                       approver_username, service, http_method, request_path, query_string,
                       request_body_sha256, reason, decision_reason, status, requested_at,
                       expires_at, decided_at, consumed_at
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

    public AdminApprovalResponse updateDecision(long approvalId,
                                                 long approverUserId, String approverUsername,
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
                          expires_at, decided_at, consumed_at
                """, this::toResponse,
                status, approverUserId, approverUsername, reason,
                Timestamp.from(now), approvalId);
    }

    public java.util.Optional<AdminApprovalResponse> findForUpdate(long approvalId) {
        List<AdminApprovalResponse> rows = jdbcTemplate.query("""
                SELECT approval_id, requester_user_id, requester_username, approver_user_id,
                       approver_username, service, http_method, request_path, query_string,
                       request_body_sha256, reason, decision_reason, status, requested_at,
                       expires_at, decided_at, consumed_at
                  FROM gateway_admin_approval_requests
                 WHERE approval_id = ?
                 FOR UPDATE
                """, this::toResponse, approvalId);
        return rows.stream().findFirst();
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
                nullableInstant(rs, "consumed_at"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

}
