package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAuthRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AuthenticatedUser createUser(String username, String email, String passwordHash, Instant now) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO gateway_users (username, email, password_hash, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'NORMAL', ?, ?)
                    RETURNING user_id, username, email, status, created_at
                    """, (rs, rowNum) -> toUser(rs, List.of("USER")),
                    username, email, passwordHash, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("username already exists", ex);
        }
    }

    public Optional<UserCredential> credentialByUsername(String username) {
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
                rs.getTimestamp("created_at").toInstant()), username).stream().findFirst();
    }

    public Optional<AuthenticatedUser> user(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, username, email, status, created_at
                  FROM gateway_users
                 WHERE user_id = ?
                """, (rs, rowNum) -> toUser(rs, roles(userId)), userId).stream().findFirst();
    }

    public List<String> roles(long userId) {
        List<String> roles = jdbcTemplate.query("""
                SELECT r.role_code
                  FROM gateway_user_roles ur
                  JOIN gateway_roles r ON r.role_id = ur.role_id
                 WHERE ur.user_id = ?
                 ORDER BY r.role_code
                """, (rs, rowNum) -> rs.getString("role_code"), userId);
        return roles.isEmpty() ? List.of("USER") : roles;
    }

    public void ensureDefaultRole(long userId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_roles (role_code, role_name, created_at)
                VALUES ('USER', 'Standard user', ?)
                ON CONFLICT (role_code) DO NOTHING
                """, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO gateway_user_roles (user_id, role_id, created_at)
                SELECT ?, role_id, ?
                  FROM gateway_roles
                 WHERE role_code = 'USER'
                ON CONFLICT (user_id, role_id) DO NOTHING
                """, userId, Timestamp.from(now));
    }

    public void saveRefreshSession(long userId,
                                   String tokenHash,
                                   Instant expiresAt,
                                   String userAgent,
                                   String ipAddress,
                                   Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_refresh_sessions (
                    user_id, token_hash, expires_at, user_agent, ip_address, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, tokenHash, Timestamp.from(expiresAt), userAgent, ipAddress,
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<RefreshSession> refreshSession(String tokenHash) {
        return jdbcTemplate.query("""
                SELECT session_id, user_id, expires_at, revoked_at
                  FROM gateway_refresh_sessions
                 WHERE token_hash = ?
                """, (rs, rowNum) -> new RefreshSession(
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                tokenHash).stream().findFirst();
    }

    public void revokeRefreshSession(long sessionId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       updated_at = ?
                 WHERE session_id = ?
                """, Timestamp.from(now), Timestamp.from(now), sessionId);
    }

    public void loginLog(long userId, String result, String reason, String userAgent, String ipAddress, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_login_logs (user_id, result, reason, user_agent, ip_address, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId <= 0 ? null : userId, result, reason, userAgent, ipAddress, Timestamp.from(now));
    }

    private AuthenticatedUser toUser(java.sql.ResultSet rs, List<String> roles) throws java.sql.SQLException {
        return new AuthenticatedUser(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("status"),
                roles,
                rs.getTimestamp("created_at").toInstant());
    }

    public record UserCredential(
            long userId,
            String username,
            String email,
            String passwordHash,
            String status,
            Instant createdAt) {
    }

    public record RefreshSession(
            long sessionId,
            long userId,
            Instant expiresAt,
            Instant revokedAt) {
    }
}
