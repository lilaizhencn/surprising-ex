package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_refresh_sessions} 表。
 */
@Repository
public class GatewayRefreshSessionRepository {

    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec CREATED_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "session_id", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(
            CREATED_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "session_id", false));

    private final JdbcTemplate jdbcTemplate;

    public GatewayRefreshSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long save(long userId,
                     String tokenHash,
                     Instant expiresAt,
                     String userAgent,
                     String ipAddress,
                     Instant now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO gateway_refresh_sessions (
                    user_id, token_hash, expires_at, user_agent, ip_address, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING session_id
                """, Long.class, userId, tokenHash, Timestamp.from(expiresAt), userAgent, ipAddress,
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<RefreshSession> find(String tokenHash) {
        return jdbcTemplate.query("""
                SELECT session_id, user_id, expires_at, revoked_at
                  FROM gateway_refresh_sessions
                 WHERE token_hash = ?
                """, (rs, rowNum) -> new RefreshSession(
                        rs.getLong("session_id"),
                        rs.getLong("user_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        nullableInstant(rs, "revoked_at")),
                tokenHash).stream().findFirst();
    }

    public boolean active(long userId, long sessionId, Instant now) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM gateway_refresh_sessions
                     WHERE user_id = ? AND session_id = ? AND revoked_at IS NULL AND expires_at > ?
                )
                """, Boolean.class, userId, sessionId, Timestamp.from(now));
        return Boolean.TRUE.equals(active);
    }

    public void revoke(long sessionId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE session_id = ?
                """, Timestamp.from(now), Timestamp.from(now), sessionId);
    }

    public int consume(long sessionId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = ?,
                       updated_at = ?
                 WHERE session_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(now), Timestamp.from(now), sessionId, Timestamp.from(now));
    }

    public List<AdminRefreshSessionResponse> find(Long userId, Boolean active, int limit) {
        return findPage(userId, active, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<AdminRefreshSessionResponse> findPage(Long userId,
                                                                            Boolean active,
                                                                            int limit,
                                                                            String cursor,
                                                                            String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, CREATED_DESC, SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(active);
        args.add(active);
        args.add(active);
        String sql = """
                SELECT session_id, user_id, expires_at, revoked_at, user_agent, ip_address, created_at, updated_at
                  FROM gateway_refresh_sessions
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR (? = TRUE AND revoked_at IS NULL AND expires_at > now())
                         OR (? = FALSE AND (revoked_at IS NOT NULL OR expires_at <= now())))
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, session_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<AdminRefreshSessionResponse> fetchedRows = jdbcTemplate.query(
                sql, (rs, rowNum) -> toResponse(rs), args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                AdminRefreshSessionResponse::createdAt, AdminRefreshSessionResponse::sessionId);
    }

    public int revokeActive(long sessionId, Instant now) {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be positive");
        }
        return jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE session_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(now), Timestamp.from(now), sessionId, Timestamp.from(now));
    }

    public int revokeActiveForUser(long userId, Instant now) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE user_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(now), Timestamp.from(now), userId, Timestamp.from(now));
    }

    public int revokeActiveForUserExcept(long userId, long excludedSessionId, Instant now) {
        if (userId <= 0 || excludedSessionId <= 0) {
            throw new IllegalArgumentException("userId and excludedSessionId must be positive");
        }
        return jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE user_id = ?
                   AND session_id <> ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(now), Timestamp.from(now), userId, excludedSessionId,
                Timestamp.from(now));
    }

    public int revokeActiveForUserSession(long userId, long sessionId, Instant now) {
        if (userId <= 0 || sessionId <= 0) {
            throw new IllegalArgumentException("userId and sessionId must be positive");
        }
        return jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE user_id = ?
                   AND session_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                """, Timestamp.from(now), Timestamp.from(now), userId, sessionId, Timestamp.from(now));
    }

    private AdminRefreshSessionResponse toResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant revokedAt = nullableInstant(rs, "revoked_at");
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        return new AdminRefreshSessionResponse(
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                revokedAt == null && expiresAt.isAfter(Instant.now()),
                expiresAt,
                revokedAt,
                rs.getString("user_agent"),
                rs.getString("ip_address"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record RefreshSession(
            long sessionId,
            long userId,
            Instant expiresAt,
            Instant revokedAt) {
    }
}
