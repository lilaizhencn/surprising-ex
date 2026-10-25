package com.surprising.gateway.provider.auth.repository;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.UserQueryFilter;

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
        return create(username, email, null, passwordHash, now);
    }

    public UserRecord create(String username, String email, String phone, String passwordHash, Instant now) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO gateway_users (username, email, phone, password_hash, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'NORMAL', ?, ?)
                    RETURNING user_id, username, email, phone, status, created_at, display_name
                    """, (rs, rowNum) -> toUserRecord(rs),
                    username, email, phone, passwordHash, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("email, phone or username already exists", ex);
        }
    }

    public Optional<UserCredential> findCredentialByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, password_hash, status, created_at
                  FROM gateway_users
                 WHERE username = ?
                """, (rs, rowNum) -> new UserCredential(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                username).stream().findFirst();
    }

    public Optional<UserCredential> findCredentialByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, password_hash, status, created_at
                  FROM gateway_users
                 WHERE lower(email) = lower(?)
                """, (rs, rowNum) -> toCredential(rs), email).stream().findFirst();
    }

    public Optional<UserCredential> findCredentialByPhone(String phone) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, password_hash, status, created_at
                 FROM gateway_users
                WHERE phone = ?
                """, (rs, rowNum) -> toCredential(rs), phone).stream().findFirst();
    }

    public Optional<UserCredential> findCredentialByUserId(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, password_hash, status, created_at
                  FROM gateway_users
                 WHERE user_id = ?
                """, (rs, rowNum) -> toCredential(rs), userId).stream().findFirst();
    }

    public Optional<UserRecord> find(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, status, created_at, display_name
                  FROM gateway_users
                 WHERE user_id = ?
                """, (rs, rowNum) -> toUserRecord(rs), userId).stream().findFirst();
    }

    /** 与C2C设置/商家审核串行；只锁用户行，锁随调用方业务事务释放。 */
    public boolean lockForNoKeyUpdate(long userId) {
        return !jdbcTemplate.query("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",
                (row, n) -> row.getLong(1), userId).isEmpty();
    }

    public List<UserRecord> find(UserQueryFilter filter, int limit) {
        String normalizedQuery = likeParameter(filter.term());
        String normalizedStatus = filter.status();
        int safeLimit = limit;
        return jdbcTemplate.query("""
                SELECT user_id, username, email, phone, status, created_at, display_name
                  FROM gateway_users
                 WHERE (CAST(? AS text) IS NULL OR username LIKE ? OR CAST(user_id AS TEXT) = ? OR lower(email) LIKE ? OR phone LIKE ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                ORDER BY created_at DESC, user_id DESC
                LIMIT ?
                """, (rs, rowNum) -> toUserRecord(rs),
                normalizedQuery, normalizedQuery, filter.exactUserIdText(), normalizedQuery, normalizedQuery,
                normalizedStatus, normalizedStatus, safeLimit);
    }

    public AdminCursorPage.CursorPage<UserRecord> findPage(UserQueryFilter filter,
                                                            AuthPageQuery pageQuery) {
        String normalizedQuery = likeParameter(filter.term());
        String normalizedStatus = filter.status();
        int safeLimit = pageQuery.limit();
        AdminCursorPage.SortSpec sortSpec = SORTS.stream()
                .filter(spec -> spec.field().equals(pageQuery.order().field()) && spec.descending() == pageQuery.order().descending())
                .findFirst().orElseThrow(() -> new IllegalStateException("unsupported native page order"));
        AdminCursorPage.Cursor decodedCursor = pageQuery.after();
        List<Object> args = new ArrayList<>();
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(filter.exactUserIdText());
        args.add(normalizedQuery);
        args.add(normalizedQuery);
        args.add(normalizedStatus);
        args.add(normalizedStatus);
        String sql = """
                SELECT user_id, username, email, phone, status, created_at, display_name
                  FROM gateway_users
                 WHERE (CAST(? AS text) IS NULL OR username LIKE ? OR CAST(user_id AS TEXT) = ? OR lower(email) LIKE ? OR phone LIKE ?)
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
                RETURNING user_id, username, email, phone, status, created_at, display_name
                """, (rs, rowNum) -> toUserRecord(rs), normalizeStatus(status),
                Timestamp.from(now), userId).stream().findFirst();
    }

    public int updatePasswordHash(long userId, String passwordHash, Instant now) {
        return jdbcTemplate.update("""
                UPDATE gateway_users
                   SET password_hash = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """, passwordHash, Timestamp.from(now), userId);
    }

    public void recordSecurityChange(long userId, Instant restrictedUntil, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_users
                   SET withdrawal_restricted_until = GREATEST(
                           COALESCE(withdrawal_restricted_until, ?), ?),
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(restrictedUntil), Timestamp.from(restrictedUntil), Timestamp.from(now), userId);
    }

    public Optional<Instant> withdrawalRestrictedUntil(long userId) {
        return jdbcTemplate.query("SELECT withdrawal_restricted_until FROM gateway_users WHERE user_id = ?",
                (rs, rowNum) -> {
                    Timestamp value = rs.getTimestamp("withdrawal_restricted_until");
                    return value == null ? null : value.toInstant();
                }, userId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private UserRecord toUserRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRecord(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("display_name"));
    }

    private UserCredential toCredential(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserCredential(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String likeParameter(String term) {
        return term == null ? null : "%" + term.replace("%", "\\%").replace("_", "\\_") + "%";
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
            String phone,
            String status,
            Instant createdAt,
            String displayName) {
        public UserRecord(long userId, String username, String email, String phone,
                          String status, Instant createdAt) {
            this(userId, username, email, phone, status, createdAt, null);
        }
        public UserRecord(long userId, String username, String email, String status, Instant createdAt) {
            this(userId, username, email, null, status, createdAt);
        }
    }

    public record UserCredential(
            long userId,
            String username,
            String email,
            String phone,
            String passwordHash,
            String status,
            Instant createdAt) {
        public UserCredential(long userId, String username, String email, String passwordHash,
                              String status, Instant createdAt) {
            this(userId, username, email, null, passwordHash, status, createdAt);
        }
    }
    public void markEmailVerified(long userId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_users
                   SET email_verified_at = COALESCE(email_verified_at, ?),
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(now), Timestamp.from(now), userId);
    }
    public void lockForUpdate(long userId) {
        jdbcTemplate.queryForObject("SELECT user_id FROM gateway_users WHERE user_id=? FOR UPDATE",Long.class,userId);
    }
    public void updateVerifiedEmailContact(long userId,String email,Instant now) {
        jdbcTemplate.update("UPDATE gateway_users SET email=?,updated_at=?,email_verified_at=CURRENT_TIMESTAMP WHERE user_id=?",email,Timestamp.from(now),userId);
    }
    public void updatePhoneContact(long userId,String phone,Instant now) {
        jdbcTemplate.update("UPDATE gateway_users SET phone=?,updated_at=? WHERE user_id=?",phone,Timestamp.from(now),userId);
    }

    public void updateNickname(long userId, String name, Instant now) {
        jdbcTemplate.update("UPDATE gateway_users SET display_name = ?, updated_at = ? WHERE user_id = ?",
                name, Timestamp.from(now), userId);
    }

}
