package com.surprising.gateway.provider.auth.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.CreatedAtPageQuery;

import com.surprising.gateway.provider.auth.model.AuthModels.LoginLogResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_login_logs} 表。
 */
@Repository
public class GatewayLoginLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayLoginLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminCursorPage.CursorPage<LoginLogResponse> findPage(Long userId,
                                                                 String result,
                                                                 CreatedAtPageQuery query) {
        int safeLimit = query.limit();
        AdminCursorPage.SortSpec sortSpec = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "login_id", query.descending());
        AdminCursorPage.Cursor decodedCursor = query.after();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(result);
        args.add(result);
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<LoginLogResponse> rows = jdbcTemplate.query("""
                SELECT login_id, user_id, result, reason, user_agent, ip_address, created_at
                  FROM gateway_login_logs
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR result = ?)
                %s
                 ORDER BY %s %s, %s %s
                 LIMIT ?
                """.formatted(AdminCursorPage.seekCondition(sortSpec, decodedCursor),
                        sortSpec.column(), sortSpec.directionSql(), sortSpec.idColumn(), sortSpec.directionSql()),
                (rs, rowNum) -> new LoginLogResponse(
                        rs.getLong("login_id"),
                        nullableLong(rs, "user_id"),
                        rs.getString("result"),
                        rs.getString("reason"),
                        rs.getString("user_agent"),
                        rs.getString("ip_address"),
                        rs.getTimestamp("created_at").toInstant()),
                args.toArray());
        return AdminCursorPage.page(
                rows, safeLimit, sortSpec, LoginLogResponse::createdAt, LoginLogResponse::loginId);
    }

    public void append(long userId,
                       String result,
                       String reason,
                       String userAgent,
                       String ipAddress,
                       Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_login_logs (user_id, result, reason, user_agent, ip_address, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId <= 0 ? null : userId, result, reason, userAgent, ipAddress, Timestamp.from(now));
    }

    public List<KnownIp> knownIps(long userId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT ip_address,count(*) AS login_count,max(created_at) AS last_seen
                FROM gateway_login_logs
                WHERE user_id=? AND ip_address IS NOT NULL AND ip_address<>''
                GROUP BY ip_address ORDER BY last_seen DESC,ip_address LIMIT ? OFFSET ?
                """, (row, index) -> new KnownIp(row.getString("ip_address"), row.getLong("login_count"),
                        row.getTimestamp("last_seen").toInstant()), userId, limit, offset);
    }

    public record KnownIp(String ipAddress, long loginCount, Instant lastSeen) { }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
