package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MfaRecoveryRepository {
    private final JdbcTemplate jdbc;

    public MfaRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RecoveryRequest create(long userId, String reason, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO gateway_mfa_recovery_requests(user_id, status, reason, submitted_at, updated_at)
                VALUES (?, 'PENDING', ?, ?, ?)
                RETURNING request_id, user_id, status, reason, submitted_at, reviewed_by_user_id,
                          reviewed_at, decision_reason, updated_at
                """, (rs, n) -> map(rs), userId, reason, Timestamp.from(now), Timestamp.from(now));
    }

    public List<RecoveryRequest> pending() {
        return jdbc.query("""
                SELECT request_id, user_id, status, reason, submitted_at, reviewed_by_user_id,
                       reviewed_at, decision_reason, updated_at
                  FROM gateway_mfa_recovery_requests WHERE status = 'PENDING'
                 ORDER BY submitted_at ASC, request_id ASC LIMIT 500
                """, (rs, n) -> map(rs));
    }

    public RecoveryRequest decide(long requestId, long adminUserId, boolean approved,
                                  String reason, Instant now) {
        return jdbc.query("""
                UPDATE gateway_mfa_recovery_requests
                   SET status = ?, reviewed_by_user_id = ?, reviewed_at = ?,
                       decision_reason = ?, updated_at = ?
                 WHERE request_id = ? AND status = 'PENDING'
                RETURNING request_id, user_id, status, reason, submitted_at, reviewed_by_user_id,
                          reviewed_at, decision_reason, updated_at
                """, (rs, n) -> map(rs), approved ? "APPROVED" : "REJECTED", adminUserId,
                Timestamp.from(now), reason, Timestamp.from(now), requestId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pending MFA recovery request not found"));
    }

    public boolean hasPending(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM gateway_mfa_recovery_requests WHERE user_id = ? AND status = 'PENDING')
                """, Boolean.class, userId));
    }

    public RecoveryRequest latestForUser(long userId) {
        return jdbc.query("""
                SELECT request_id, user_id, status, reason, submitted_at, reviewed_by_user_id,
                       reviewed_at, decision_reason, updated_at
                  FROM gateway_mfa_recovery_requests WHERE user_id = ?
                 ORDER BY submitted_at DESC, request_id DESC LIMIT 1
                """, (rs, n) -> map(rs), userId).stream().findFirst().orElse(null);
    }

    private RecoveryRequest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecoveryRequest(rs.getLong("request_id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getString("reason"), rs.getTimestamp("submitted_at").toInstant(),
                nullable(rs, "reviewed_by_user_id"), instant(rs, "reviewed_at"),
                rs.getString("decision_reason"), rs.getTimestamp("updated_at").toInstant());
    }

    private Long nullable(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        long value = rs.getLong(field); return rs.wasNull() ? null : value;
    }
    private Instant instant(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }

    public record RecoveryRequest(long requestId, long userId, String status, String reason,
                                  Instant submittedAt, Long reviewedByUserId, Instant reviewedAt,
                                  String decisionReason, Instant updatedAt) {}
}
