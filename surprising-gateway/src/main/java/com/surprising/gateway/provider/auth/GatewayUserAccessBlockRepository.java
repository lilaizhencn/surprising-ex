package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 gateway_user_access_blocks，保存账号所有者的设备/IP 访问限制。 */
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

    public Set<String> activeValues(long userId, String type, List<String> values) {
        if (values.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        var arguments = new ArrayList<Object>();
        arguments.add(userId);
        arguments.add(type);
        arguments.addAll(values);
        return new HashSet<>(jdbc.queryForList("""
                SELECT block_value FROM gateway_user_access_blocks
                WHERE user_id=? AND block_type=? AND revoked_at IS NULL AND block_value IN (
                """ + placeholders + ")", String.class, arguments.toArray()));
    }

    public record BlockedAccess(String value, Instant createdAt) {}
}
