package com.surprising.gateway.provider.auth.repository;

import com.surprising.gateway.provider.auth.model.NotificationView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserNotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<NotificationView> findForUser(long userId, boolean unreadOnly, String category, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT notification_id, category, title, body, read_at, created_at, route, route_params
                  FROM gateway_user_notifications
                 WHERE user_id = ?
                """);
        if (unreadOnly) {
            sql.append(" AND read_at IS NULL");
        }
        if (category != null && !category.isBlank() && !category.equalsIgnoreCase("ALL")) {
            sql.append(" AND category = '").append(category.trim().toUpperCase()).append("'");
        }
        sql.append(" ORDER BY created_at DESC, notification_id DESC LIMIT ?");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new NotificationView(
                rs.getLong("notification_id"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("route"),
                rs.getString("route_params")),
                userId, limit);
    }

    public List<NotificationView> findForUser(long userId, boolean unreadOnly, int limit) {
        return findForUser(userId, unreadOnly, null, limit);
    }

    public long countUnread(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM gateway_user_notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);
        return count != null ? count : 0L;
    }

    public Optional<NotificationView> markRead(long userId, long notificationId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_user_notifications
                   SET read_at = COALESCE(read_at, ?)
                 WHERE user_id = ? AND notification_id = ?
                """, Timestamp.from(now), userId, notificationId);
        if (updated == 0) return Optional.empty();
        return Optional.of(findById(userId, notificationId));
    }

    public int markAllRead(long userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_user_notifications
                   SET read_at = ?
                 WHERE user_id = ? AND read_at IS NULL
                """, Timestamp.from(now), userId);
    }

    public long create(long userId, String category, String title, String body, String route, String routeParams, Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_user_notifications (user_id, category, title, body, route, route_params, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING notification_id
                """, Long.class, userId, category, title, body, route, routeParams, Timestamp.from(now));
    }

    public void create(long userId, String category, String title, String body, Instant now) {
        create(userId, category, title, body, null, null, now);
    }

    private NotificationView findById(long userId, long notificationId) {
        return jdbcTemplate.queryForObject("""
                SELECT notification_id, category, title, body, read_at, created_at, route, route_params
                  FROM gateway_user_notifications
                 WHERE user_id = ? AND notification_id = ?
                """, (rs, rowNum) -> new NotificationView(
                rs.getLong("notification_id"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("route"),
                rs.getString("route_params")), userId, notificationId);
    }
}
