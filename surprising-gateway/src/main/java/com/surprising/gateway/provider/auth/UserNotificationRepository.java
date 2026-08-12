package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserNotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<NotificationView> findForUser(long userId, boolean unreadOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                SELECT notification_id, category, title, body, read_at, created_at
                  FROM gateway_user_notifications
                 WHERE user_id = ?
                   AND (? = FALSE OR read_at IS NULL)
                 ORDER BY created_at DESC, notification_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new NotificationView(
                rs.getLong("notification_id"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()),
                userId, unreadOnly, safeLimit);
    }

    public NotificationView markRead(long userId, long notificationId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_user_notifications
                   SET read_at = COALESCE(read_at, ?)
                 WHERE user_id = ? AND notification_id = ?
                """, Timestamp.from(now), userId, notificationId);
        if (updated == 0) {
            throw new IllegalArgumentException("notification not found");
        }
        return findById(userId, notificationId);
    }

    public int markAllRead(long userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_user_notifications
                   SET read_at = ?
                 WHERE user_id = ? AND read_at IS NULL
                """, Timestamp.from(now), userId);
    }

    public void create(long userId, String category, String title, String body, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_user_notifications (user_id, category, title, body, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, category, title, body, Timestamp.from(now));
    }

    private NotificationView findById(long userId, long notificationId) {
        return jdbcTemplate.queryForObject("""
                SELECT notification_id, category, title, body, read_at, created_at
                  FROM gateway_user_notifications
                 WHERE user_id = ? AND notification_id = ?
                """, (rs, rowNum) -> new NotificationView(
                rs.getLong("notification_id"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), userId, notificationId);
    }

    public record NotificationView(long notificationId,
                                   String category,
                                   String title,
                                   String body,
                                   Instant readAt,
                                   Instant createdAt) {
    }
}
