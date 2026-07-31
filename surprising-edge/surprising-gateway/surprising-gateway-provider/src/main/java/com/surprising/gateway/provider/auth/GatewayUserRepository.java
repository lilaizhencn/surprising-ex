package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code gateway_users} 表，角色等关联数据由服务层聚合。
 */
@Repository
public class GatewayUserRepository {

    private static final int MAX_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec CREATED_AT_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "user_id", true);
    private static final List<AdminCursorPage.SortSpec> SORTS = List.of(
            CREATED_AT_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "user_id", false));

    private final JdbcTemplate jdbcTemplate;

    public GatewayUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserRecord create(String username, String email, String passwordHash, Instant now) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO gateway_users (username, email, password_hash, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'NORMAL', ?, ?)
                    RETURNING user_id, username, email, status, created_at
                    """, (rs, rowNum) -> toUserRecord(rs),
                    username, email, passwordHash, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("username already exists", ex);
        }
    }

    public Optional<UserCredential> findCredentialByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, password_hash, status, created_at
                  FROM gateway_users
                 WHERE username = ?
                """, (rs, rowNum) -> new UserCredential(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                username).stream().findFirst();
    }

    public Optional<UserRecord> find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, status, created_at
                  FROM gateway_users
                 WHERE user_id = ?
                """, (rs, rowNum) -> toUserRecord(rs), userId).stream().findFirst();
    }

    public List<UserRecord> find(String query, String status, int limit) {
        String normalizedQuery = normalizeLikeQuery(query);
        String normalizedStatus = normalizeStatusFilter(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        return jdbcTemplate.query("""
                SELECT user_id, username, email, status, created_at
                  FROM gateway_users
                 WHERE (CAST(? AS text) IS NULL OR username LIKE ? OR CAST(user_id AS TEXT) = ? OR lower(email) LIKE ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                 ORDER BY created_at DESC, user_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toUserRecord(rs),
                normalizedQuery, normalizedQuery, exactIdQuery(query), normalizedQuery,
                normalizedStatus, normalizedStatus, safeLimit);
    }

    public AdminCursorPage.CursorPage<UserRecord> findPage(String query,
                                                            String status,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        String normalizedQuery = normalizeLikeQuery(query);
        String normalizedStatus = normalizeStatusFilter(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, CREATED_AT_DESC, SORTS);
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(exactIdQuery(query));
        args.add(normalizedQuery);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        String sql = """
                SELECT user_id, username, email, status, created_at
                  FROM gateway_users
                 WHERE (CAST(? AS text) IS NULL OR username LIKE ? OR CAST(user_id AS TEXT) = ? OR lower(email) LIKE ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """ + AdminCursorPage.seekCondition(sortSpec, decodedCursor) + """
                 ORDER BY %s %s, user_id %s
                 LIMIT ?
                """.formatted(sortSpec.column(), sortSpec.directionSql(), sortSpec.directionSql());
        AdminCursorPage.addCursorArgs(args, decodedCursor);
        args.add(safeLimit + 1);
        List<UserRecord> fetchedRows = jdbcTemplate.query(
                sql, (rs, rowNum) -> toUserRecord(rs), args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                UserRecord::createdAt, UserRecord::userId);
    }

    public Optional<UserRecord> updateStatus(long userId, String status, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_users
                   SET status = ?,
                       updated_at = ?
                 WHERE user_id = ?
                RETURNING user_id, username, email, status, created_at
                """, (rs, rowNum) -> toUserRecord(rs), normalizeStatus(status),
                Timestamp.from(now), userId).stream().findFirst();
    }

    private UserRecord toUserRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRecord(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String normalizeLikeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        return "%" + trimmed.replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String exactIdQuery(String query) {
        if (query == null || !query.trim().matches("\\d{1,19}")) {
            return null;
        }
        return query.trim();
    }

    private String normalizeStatusFilter(String status) {
        return status == null || status.isBlank() ? null : normalizeStatus(status);
    }

    private String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !List.of("NORMAL", "FROZEN", "TRADE_DISABLED", "WITHDRAW_DISABLED")
                .contains(normalized)) {
            throw new IllegalArgumentException("invalid user status");
        }
        return normalized;
    }

    public record UserRecord(
            long userId,
            String username,
            String email,
            String status,
            Instant createdAt) {
    }

    public record UserCredential(
            long userId,
            String username,
            String email,
            String passwordHash,
            String status,
            Instant createdAt) {
    }
}
