package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.AdminRefreshSessionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminPermissionResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminRoleResponse;
import com.surprising.gateway.provider.auth.AuthModels.LoginLogResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAuthRepository {

    private static final int MAX_USER_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec USER_CREATED_AT_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "user_id", true);
    private static final List<AdminCursorPage.SortSpec> USER_SORTS = List.of(
            USER_CREATED_AT_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "user_id", false));
    private static final int MAX_SESSION_QUERY_LIMIT = 500;
    private static final AdminCursorPage.SortSpec SESSION_CREATED_AT_DESC =
            new AdminCursorPage.SortSpec("createdAt", "created_at", "session_id", true);
    private static final List<AdminCursorPage.SortSpec> SESSION_SORTS = List.of(
            SESSION_CREATED_AT_DESC,
            new AdminCursorPage.SortSpec("createdAt", "created_at", "session_id", false));

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
        ensureRole("USER", "Standard user", now);
        jdbcTemplate.update("""
                INSERT INTO gateway_user_roles (user_id, role_id, created_at)
                SELECT ?, role_id, ?
                  FROM gateway_roles
                 WHERE role_code = 'USER'
                ON CONFLICT (user_id, role_id) DO NOTHING
                """, userId, Timestamp.from(now));
    }

    public List<AuthenticatedUser> users(String query, String status, int limit) {
        String normalizedQuery = normalizeLikeQuery(query);
        String normalizedStatus = normalizeStatusFilter(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_USER_QUERY_LIMIT);
        return jdbcTemplate.query("""
                SELECT user_id, username, email, status, created_at
                  FROM gateway_users
                 WHERE (CAST(? AS text) IS NULL OR username LIKE ? OR CAST(user_id AS TEXT) = ? OR lower(email) LIKE ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                 ORDER BY created_at DESC, user_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> {
                long userId = rs.getLong("user_id");
                return toUser(rs, roles(userId));
        }, normalizedQuery, normalizedQuery, exactIdQuery(query), normalizedQuery,
                normalizedStatus, normalizedStatus, safeLimit);
    }

    public AdminCursorPage.CursorPage<AuthenticatedUser> usersPage(String query,
                                                                   String status,
                                                                   int limit,
                                                                   String cursor,
                                                                   String sort) {
        String normalizedQuery = normalizeLikeQuery(query);
        String normalizedStatus = normalizeStatusFilter(status);
        int safeLimit = AdminCursorPage.limit(limit, MAX_USER_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(sort, USER_CREATED_AT_DESC, USER_SORTS);
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
        List<AuthenticatedUser> fetchedRows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            long userId = rs.getLong("user_id");
            return toUser(rs, roles(userId));
        }, args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                AuthenticatedUser::createdAt, AuthenticatedUser::userId);
    }

    public Optional<AuthenticatedUser> updateStatus(long userId, String status, Instant now) {
        return jdbcTemplate.query("""
                UPDATE gateway_users
                   SET status = ?,
                       updated_at = ?
                 WHERE user_id = ?
                RETURNING user_id, username, email, status, created_at
                """, (rs, rowNum) -> toUser(rs, roles(userId)), normalizeStatus(status),
                Timestamp.from(now), userId).stream().findFirst();
    }

    public void replaceRoles(long userId, List<String> roleCodes, Instant now) {
        List<String> normalizedRoles = normalizeRoleCodes(roleCodes);
        for (String roleCode : normalizedRoles) {
            ensureRole(roleCode, roleName(roleCode), now);
        }
        jdbcTemplate.update("DELETE FROM gateway_user_roles WHERE user_id = ?", userId);
        for (String roleCode : normalizedRoles) {
            jdbcTemplate.update("""
                    INSERT INTO gateway_user_roles (user_id, role_id, created_at)
                    SELECT ?, role_id, ?
                      FROM gateway_roles
                     WHERE role_code = ?
                    ON CONFLICT (user_id, role_id) DO NOTHING
                    """, userId, Timestamp.from(now), roleCode);
        }
    }

    public List<LoginLogResponse> loginLogs(Long userId, String result, int limit) {
        return loginLogPage(userId, result, limit, null, null).items();
    }

    public AdminCursorPage.CursorPage<LoginLogResponse> loginLogPage(Long userId,
                                                                     String result,
                                                                     int limit,
                                                                     String cursor,
                                                                     String sort) {
        String normalizedResult = normalizeNullableUpper(result);
        int safeLimit = AdminCursorPage.limit(limit, 500);
        AdminCursorPage.SortSpec createdAtDesc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "login_id", true);
        AdminCursorPage.SortSpec createdAtAsc = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "login_id", false);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, createdAtDesc, List.of(createdAtDesc, createdAtAsc));
        AdminCursorPage.Cursor decodedCursor = AdminCursorPage.decodeCursor(cursor);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        args.add(normalizedResult);
        args.add(normalizedResult);
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
                rs.getTimestamp("created_at").toInstant()), args.toArray());
        return AdminCursorPage.page(rows, safeLimit, sortSpec, LoginLogResponse::createdAt,
                LoginLogResponse::loginId);
    }

    public void ensureRole(String roleCode, String roleName, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_roles (role_code, role_name, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (role_code) DO NOTHING
                """, normalizeRoleCode(roleCode), roleName, Timestamp.from(now));
    }

    public List<AdminRoleResponse> roleSummaries() {
        return jdbcTemplate.query("""
                SELECT r.role_code, r.role_name, r.created_at, COUNT(rp.permission_id) AS permission_count
                  FROM gateway_roles r
                  LEFT JOIN gateway_role_permissions rp ON rp.role_id = r.role_id
                 GROUP BY r.role_id, r.role_code, r.role_name, r.created_at
                 ORDER BY r.role_code
                """, (rs, rowNum) -> new AdminRoleResponse(
                rs.getString("role_code"),
                rs.getString("role_name"),
                rs.getInt("permission_count"),
                rs.getTimestamp("created_at").toInstant()));
    }

    public List<AdminPermissionResponse> permissions() {
        return jdbcTemplate.query("""
                SELECT permission_code, permission_name, description, created_at
                  FROM gateway_permissions
                 ORDER BY permission_code
                """, (rs, rowNum) -> new AdminPermissionResponse(
                rs.getString("permission_code"),
                rs.getString("permission_name"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant()));
    }

    public List<String> rolePermissions(String roleCode) {
        String normalizedRole = normalizeRoleCode(roleCode);
        ensureRoleExists(normalizedRole);
        return jdbcTemplate.query("""
                SELECT p.permission_code
                  FROM gateway_role_permissions rp
                  JOIN gateway_roles r ON r.role_id = rp.role_id
                  JOIN gateway_permissions p ON p.permission_id = rp.permission_id
                 WHERE r.role_code = ?
                 ORDER BY p.permission_code
                """, (rs, rowNum) -> rs.getString("permission_code"), normalizedRole);
    }

    public void replaceRolePermissions(String roleCode, List<String> permissionCodes, Instant now) {
        String normalizedRole = normalizeRoleCode(roleCode);
        long roleId = roleId(normalizedRole);
        List<String> normalizedPermissions = normalizePermissionCodes(permissionCodes);
        ensurePermissionsExist(normalizedPermissions);
        jdbcTemplate.update("DELETE FROM gateway_role_permissions WHERE role_id = ?", roleId);
        for (String permissionCode : normalizedPermissions) {
            jdbcTemplate.update("""
                    INSERT INTO gateway_role_permissions (role_id, permission_id, created_at)
                    SELECT ?, permission_id, ?
                      FROM gateway_permissions
                     WHERE permission_code = ?
                    ON CONFLICT (role_id, permission_id) DO NOTHING
                    """, roleId, Timestamp.from(now), permissionCode);
        }
    }

    public List<String> permissionsForUser(long userId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT p.permission_code
                  FROM gateway_user_roles ur
                  JOIN gateway_role_permissions rp ON rp.role_id = ur.role_id
                  JOIN gateway_permissions p ON p.permission_id = rp.permission_id
                 WHERE ur.user_id = ?
                 ORDER BY p.permission_code
                """, (rs, rowNum) -> rs.getString("permission_code"), userId);
    }

    public Optional<MfaCredential> mfaCredential(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, totp_secret_ciphertext, enabled, verified_at, created_at, updated_at
                  FROM gateway_user_mfa
                 WHERE user_id = ?
                """, (rs, rowNum) -> new MfaCredential(
                rs.getLong("user_id"),
                rs.getString("totp_secret_ciphertext"),
                rs.getBoolean("enabled"),
                nullableInstant(rs, "verified_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), userId).stream().findFirst();
    }

    public void upsertMfaSecret(long userId, String secretCiphertext, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO gateway_user_mfa (
                    user_id, totp_secret_ciphertext, enabled, verified_at, created_at, updated_at
                ) VALUES (?, ?, FALSE, NULL, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET totp_secret_ciphertext = EXCLUDED.totp_secret_ciphertext,
                       enabled = FALSE,
                       verified_at = NULL,
                       updated_at = EXCLUDED.updated_at
                """, userId, secretCiphertext, Timestamp.from(now), Timestamp.from(now));
    }

    public void enableMfa(long userId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_user_mfa
                   SET enabled = TRUE,
                       verified_at = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(now), Timestamp.from(now), userId);
        if (updated == 0) {
            throw new IllegalArgumentException("mfa enrollment not found");
        }
    }

    public void disableMfa(long userId, Instant now) {
        jdbcTemplate.update("""
                UPDATE gateway_user_mfa
                   SET enabled = FALSE,
                       verified_at = NULL,
                       updated_at = ?
                 WHERE user_id = ?
                """, Timestamp.from(now), userId);
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

    public List<AdminRefreshSessionResponse> refreshSessions(Long userId, Boolean active, int limit) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int safeLimit = AdminCursorPage.limit(limit, MAX_SESSION_QUERY_LIMIT);
        return jdbcTemplate.query("""
                SELECT session_id, user_id, expires_at, revoked_at, user_agent, ip_address, created_at, updated_at
                  FROM gateway_refresh_sessions
                 WHERE (CAST(? AS text) IS NULL OR user_id = ?)
                   AND (CAST(? AS text) IS NULL OR (? = TRUE AND revoked_at IS NULL AND expires_at > now())
                         OR (? = FALSE AND (revoked_at IS NOT NULL OR expires_at <= now())))
                 ORDER BY created_at DESC, session_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> toRefreshSessionResponse(rs),
                userId, userId, active, active, active, safeLimit);
    }

    public AdminCursorPage.CursorPage<AdminRefreshSessionResponse> refreshSessionsPage(Long userId,
                                                                                      Boolean active,
                                                                                      int limit,
                                                                                      String cursor,
                                                                                      String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int safeLimit = AdminCursorPage.limit(limit, MAX_SESSION_QUERY_LIMIT);
        AdminCursorPage.SortSpec sortSpec = AdminCursorPage.parseSort(
                sort, SESSION_CREATED_AT_DESC, SESSION_SORTS);
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
        List<AdminRefreshSessionResponse> fetchedRows = jdbcTemplate.query(sql,
                (rs, rowNum) -> toRefreshSessionResponse(rs), args.toArray());
        return AdminCursorPage.page(fetchedRows, safeLimit, sortSpec,
                AdminRefreshSessionResponse::createdAt, AdminRefreshSessionResponse::sessionId);
    }

    public int revokeRefreshSessionForAdmin(long sessionId, Instant now) {
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

    public int revokeUserRefreshSessions(long userId, Instant now) {
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
        String normalized = normalizeNullableUpper(status);
        if (normalized == null || !List.of("NORMAL", "FROZEN", "TRADE_DISABLED", "WITHDRAW_DISABLED")
                .contains(normalized)) {
            throw new IllegalArgumentException("invalid user status");
        }
        return normalized;
    }

    private List<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new IllegalArgumentException("roles are required");
        }
        List<String> normalized = new ArrayList<>();
        for (String roleCode : roleCodes) {
            String item = normalizeRoleCode(roleCode);
            if (!normalized.contains(item)) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private List<String> normalizePermissionCodes(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String permissionCode : permissionCodes) {
            String item = normalizePermissionCode(permissionCode);
            if (!normalized.contains(item)) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private String normalizeRoleCode(String roleCode) {
        String normalized = normalizeNullableUpper(roleCode);
        if (normalized == null || !normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new IllegalArgumentException("invalid role code");
        }
        return normalized;
    }

    private String normalizePermissionCode(String permissionCode) {
        String normalized = permissionCode == null ? null : permissionCode.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !normalized.matches("[a-z0-9*][a-z0-9.*_-]{1,127}")) {
            throw new IllegalArgumentException("invalid permission code");
        }
        return normalized;
    }

    private long roleId(String roleCode) {
        return jdbcTemplate.query("""
                SELECT role_id
                  FROM gateway_roles
                 WHERE role_code = ?
                """, (rs, rowNum) -> rs.getLong("role_id"), roleCode).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("role not found"));
    }

    private void ensureRoleExists(String roleCode) {
        roleId(roleCode);
    }

    private void ensurePermissionsExist(List<String> permissionCodes) {
        for (String permissionCode : permissionCodes) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM gateway_permissions
                     WHERE permission_code = ?
                    """, Integer.class, permissionCode);
            if (count == null || count == 0) {
                throw new IllegalArgumentException("unknown permission: " + permissionCode);
            }
        }
    }

    private String normalizeNullableUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String roleName(String roleCode) {
        return roleCode.replace('_', ' ');
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private AdminRefreshSessionResponse toRefreshSessionResponse(java.sql.ResultSet rs)
            throws java.sql.SQLException {
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

    public record MfaCredential(
            long userId,
            String totpSecretCiphertext,
            boolean enabled,
            Instant verifiedAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
