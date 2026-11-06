package com.surprising.gateway.provider.notification.repository;

import com.surprising.gateway.provider.notification.model.PushOutboxItem;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PushOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public PushOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean enqueue(UUID pushId, long userId, String deviceToken, String platform,
                           String category, String title, String body, String route,
                           String routeParams, String idempotencyKey, Instant now) {
        Timestamp ts = Timestamp.from(now);
        int rows = jdbcTemplate.update("""
                INSERT INTO gateway_push_notification_outbox (
                    push_id, user_id, device_token, platform,
                    category, title, body, route, route_params,
                    status, attempts, next_attempt_at, idempotency_key, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, pushId, userId, deviceToken, platform,
                category, title, body, route, routeParams,
                ts, idempotencyKey, ts);
        return rows > 0;
    }

    public List<PushOutboxItem> claimPending(Instant now, int limit) {
        Timestamp ts = Timestamp.from(now);
        // 查找 PENDING 且到期的任务，直接乐观更新为 SENDING 并返回
        return jdbcTemplate.query("""
                WITH claimed AS (
                    SELECT push_id
                      FROM gateway_push_notification_outbox
                     WHERE status = 'PENDING'
                       AND next_attempt_at <= ?
                     ORDER BY next_attempt_at ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE gateway_push_notification_outbox outbox
                   SET status = 'SENDING', attempts = attempts + 1
                  FROM claimed
                 WHERE outbox.push_id = claimed.push_id
             RETURNING outbox.push_id, outbox.user_id, outbox.device_token, outbox.platform,
                       outbox.category, outbox.title, outbox.body, outbox.route, outbox.route_params,
                       outbox.status, outbox.attempts, outbox.next_attempt_at, outbox.last_error,
                       outbox.idempotency_key, outbox.created_at, outbox.sent_at
                """, (rs, rowNum) -> new PushOutboxItem(
                (UUID) rs.getObject("push_id"),
                rs.getLong("user_id"),
                rs.getString("device_token"),
                rs.getString("platform"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("route"),
                rs.getString("route_params"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("last_error"),
                rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()
        ), ts, limit);
    }

    public void markSent(UUID pushId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_push_notification_outbox
                   SET status = 'SENT', sent_at = ?
                 WHERE push_id = ?
                """, Timestamp.from(now), pushId);
    }

    public void markFailed(UUID pushId, Instant nextAttempt, String error, boolean permanent) {
        String newStatus = permanent ? "FAILED" : "PENDING";
        jdbcTemplate.update("""
                UPDATE gateway_push_notification_outbox
                   SET status = ?, next_attempt_at = ?, last_error = ?
                 WHERE push_id = ?
                """, newStatus, Timestamp.from(nextAttempt), error, pushId);
    }

    public int deleteSentBefore(Instant before) {
        return jdbcTemplate.update("""
                DELETE FROM gateway_push_notification_outbox
                 WHERE status = 'SENT' AND sent_at < ?
                """, Timestamp.from(before));
    }
}
