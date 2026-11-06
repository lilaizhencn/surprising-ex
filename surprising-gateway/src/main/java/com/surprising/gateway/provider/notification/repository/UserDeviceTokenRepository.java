package com.surprising.gateway.provider.notification.repository;

import com.surprising.gateway.provider.notification.model.UserDeviceToken;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserDeviceTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserDeviceTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertToken(long userId, String deviceToken, String platform, String deviceName, String locale, Instant now) {
        String cleanPlatform = (platform == null || platform.isBlank()) ? "ANDROID" : platform.trim().toUpperCase();
        String cleanLocale = (locale == null || locale.isBlank()) ? "en-US" : locale.trim();
        Timestamp ts = Timestamp.from(now);

        // 如果该 token 已被其他用户占用，先将其失效，确保 token 与当前登录用户严格绑定
        jdbcTemplate.update("""
                UPDATE gateway_user_device_tokens
                   SET enabled = false, updated_at = ?
                 WHERE device_token = ? AND user_id <> ?
                """, ts, deviceToken, userId);

        // 插入或更新属于当前用户的 token
        jdbcTemplate.update("""
                INSERT INTO gateway_user_device_tokens (user_id, device_token, platform, device_name, locale, enabled, last_active_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, true, ?, ?, ?)
                ON CONFLICT (user_id, device_token) DO UPDATE SET
                    platform = EXCLUDED.platform,
                    device_name = COALESCE(EXCLUDED.device_name, gateway_user_device_tokens.device_name),
                    locale = EXCLUDED.locale,
                    enabled = true,
                    last_active_at = EXCLUDED.last_active_at,
                    updated_at = EXCLUDED.updated_at
                """, userId, deviceToken, cleanPlatform, deviceName, cleanLocale, ts, ts, ts);
    }

    public boolean unregisterToken(long userId, String deviceToken, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_user_device_tokens
                   SET enabled = false, updated_at = ?
                 WHERE user_id = ? AND device_token = ?
                """, Timestamp.from(now), userId, deviceToken);
        return updated > 0;
    }

    public List<UserDeviceToken> findActiveTokens(long userId) {
        return jdbcTemplate.query("""
                SELECT token_id, user_id, device_token, platform, device_name, locale, enabled, last_active_at, created_at, updated_at
                  FROM gateway_user_device_tokens
                 WHERE user_id = ? AND enabled = true
                 ORDER BY last_active_at DESC
                """, (rs, rowNum) -> new UserDeviceToken(
                rs.getLong("token_id"),
                rs.getLong("user_id"),
                rs.getString("device_token"),
                rs.getString("platform"),
                rs.getString("device_name"),
                rs.getString("locale"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("last_active_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), userId);
    }

    public List<UserDeviceToken> findUserDevices(long userId) {
        return jdbcTemplate.query("""
                SELECT token_id, user_id, device_token, platform, device_name, locale, enabled, last_active_at, created_at, updated_at
                  FROM gateway_user_device_tokens
                 WHERE user_id = ?
                 ORDER BY last_active_at DESC
                 LIMIT 20
                """, (rs, rowNum) -> new UserDeviceToken(
                rs.getLong("token_id"),
                rs.getLong("user_id"),
                rs.getString("device_token"),
                rs.getString("platform"),
                rs.getString("device_name"),
                rs.getString("locale"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("last_active_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), userId);
    }
}
