package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistent account-owned login and request restrictions. */
@Repository
public class GatewayUserAccessBlockRepository {

    private final JdbcTemplate jdbc;

    public GatewayUserAccessBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean blocked(long userId, String type, String value) {
        if (value == null || value.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM gateway_user_access_blocks
                              WHERE user_id = ? AND block_type = ? AND block_value = ? AND revoked_at IS NULL)
                """, Boolean.class, userId, type, value));
    }

    public void block(long userId, String type, String value, Instant now) {
        jdbc.update("""
                INSERT INTO gateway_user_access_blocks (user_id, block_type, block_value, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, block_type, block_value) WHERE revoked_at IS NULL DO NOTHING
                """, userId, type, value, Timestamp.from(now));
    }

    public int unblock(long userId, String type, String value, Instant now) {
        return jdbc.update("""
                UPDATE gateway_user_access_blocks SET revoked_at = ?
                 WHERE user_id = ? AND block_type = ? AND block_value = ? AND revoked_at IS NULL
                """, Timestamp.from(now), userId, type, value);
    }

    public List<BlockedAccess> active(long userId, String type) {
        return jdbc.query("""
                SELECT block_value, created_at FROM gateway_user_access_blocks
                 WHERE user_id = ? AND block_type = ? AND revoked_at IS NULL
                 ORDER BY created_at DESC
                """, (rs, rowNum) -> new BlockedAccess(rs.getString("block_value"),
                rs.getTimestamp("created_at").toInstant()), userId, type);
    }

    public List<KnownIp> knownIps(long userId) {
        return jdbc.query("""
                SELECT ip_address, count(*) AS login_count, max(created_at) AS last_seen
                  FROM gateway_login_logs
                 WHERE user_id = ? AND ip_address IS NOT NULL AND ip_address <> ''
                 GROUP BY ip_address ORDER BY last_seen DESC
                """, (rs, rowNum) -> new KnownIp(rs.getString("ip_address"),
                rs.getLong("login_count"), rs.getTimestamp("last_seen").toInstant()), userId);
    }

    public record BlockedAccess(String value, Instant createdAt) {}
    public record KnownIp(String ipAddress, long loginCount, Instant lastSeen) {}
}
