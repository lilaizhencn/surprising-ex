package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Postgres → Valkey 的低频同步：登录、管理员改状态/角色/权限、封禁 IP/设备、API Key 增删改时，
 * 从 Postgres 读出最新数据写入 Valkey；撤销会话时删除对应键。请求路径不会调用这里。
 * 只用 JdbcTemplate 直接查询，避免和各个仓储互相依赖。
 */
@Component
public class AuthStateSync {

    private static final Logger log = LoggerFactory.getLogger(AuthStateSync.class);

    private final JdbcTemplate jdbc;
    private final AuthState state;
    private final GatewayProperties properties;

    public AuthStateSync(JdbcTemplate jdbc, AuthState state, GatewayProperties properties) {
        this.jdbc = jdbc;
        this.state = state;
        this.properties = properties;
    }

    /** 读取用户最新状态写入 Valkey；用户不存在则删除。 */
    public Optional<UserState> reloadUser(long userId) {
        var rows = jdbc.query("""
                SELECT username, email, display_name, created_at, status FROM gateway_users WHERE user_id = ?
                """, (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getString(5)}, userId);
        if (rows.isEmpty()) {
            state.deleteUser(userId);
            return Optional.empty();
        }
        Object[] user = rows.get(0);
        List<String> roles = jdbc.queryForList("""
                SELECT r.role_code FROM gateway_user_roles ur JOIN gateway_roles r ON r.role_id = ur.role_id
                 WHERE ur.user_id = ? ORDER BY r.role_code
                """, String.class, userId);
        if (roles.isEmpty()) roles = List.of("USER");
        List<String> permissions = jdbc.queryForList("""
                SELECT DISTINCT p.permission_code
                  FROM gateway_user_roles ur
                  JOIN gateway_role_permissions rp ON rp.role_id = ur.role_id
                  JOIN gateway_permissions p ON p.permission_id = rp.permission_id
                 WHERE ur.user_id = ? ORDER BY p.permission_code
                """, String.class, userId);
        Set<String> blockedIps = new LinkedHashSet<>();
        Set<String> blockedDevices = new LinkedHashSet<>();
        jdbc.query("""
                SELECT block_type, block_value FROM gateway_user_access_blocks
                 WHERE user_id = ? AND revoked_at IS NULL
                """, rs -> {
            if ("IP".equals(rs.getString(1))) blockedIps.add(rs.getString(2));
            else if ("DEVICE".equals(rs.getString(1))) blockedDevices.add(rs.getString(2));
        }, userId);
        boolean hasApiKey = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM gateway_api_keys WHERE user_id = ? AND status = 'ACTIVE')",
                Boolean.class, userId));
        var loaded = new UserState(userId, (String) user[0], (String) user[1], (String) user[2], (Instant) user[3],
                (String) user[4], roles, permissions, blockedIps, blockedDevices, hasApiKey);
        state.putUser(loaded);
        return Optional.of(loaded);
    }

    /** 角色权限变更：拥有该角色的用户全部重新写入。 */
    public void roleChanged(long roleId) {
        for (Long userId : jdbc.queryForList("SELECT user_id FROM gateway_user_roles WHERE role_id = ?",
                Long.class, roleId)) {
            reloadUser(userId);
        }
    }

    /** API Key 创建、修改或撤销：有效就写入，否则删除；同时刷新所属用户。 */
    public void apiKeyChanged(String apiKey) {
        var rows = jdbc.query("""
                SELECT api_key_id, user_id, permissions, ip_allowlist, secret_ciphertext, status
                  FROM gateway_api_keys WHERE api_key = ?
                """, (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)}, apiKey);
        if (rows.isEmpty()) {
            state.deleteApiKey(apiKey);
            return;
        }
        Object[] row = rows.get(0);
        if ("ACTIVE".equals(row[5])) {
            state.putApiKey(new ApiKeyState(apiKey, (UUID) row[0], (Long) row[1], (String) row[2],
                    (String) row[3], (String) row[4]));
        } else {
            state.deleteApiKey(apiKey);
        }
        reloadUser((Long) row[1]);
    }

    public void sessionsRevoked(List<Long> sessionIds) {
        state.endSessions(sessionIds);
    }

    /**
     * 网关启动时只补缺失的部分：Postgres 里仍有效的 API Key、会话和对应用户。
     * 不覆盖 Valkey 里已有的数据，不在请求路径上。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildMissingOnStart() {
        if (!properties.getSecurity().isAuthStateRebuildOnStart()) return;
        try {
            rebuildMissing();
        } catch (RuntimeException ex) {
            log.warn("auth state rebuild skipped: {}", ex.getMessage());
        }
    }

    public void rebuildMissing() {
        Set<Long> userIds = new LinkedHashSet<>();
        var keys = jdbc.query("SELECT api_key, user_id FROM gateway_api_keys WHERE status = 'ACTIVE'",
                (rs, row) -> new Object[]{rs.getString(1), rs.getLong(2)});
        for (Object[] key : keys) {
            userIds.add((Long) key[1]);
            if (!state.apiKeyExists((String) key[0])) apiKeyChanged((String) key[0]);
        }
        record SessionRow(long sessionId, long userId, Instant expiresAt, String deviceId,
                          Instant authenticatedAt) {}
        List<SessionRow> sessions = jdbc.query("""
                SELECT session_id, user_id, expires_at, device_id::text, authenticated_at
                  FROM gateway_refresh_sessions WHERE revoked_at IS NULL AND expires_at > now()
                """, (rs, row) -> {
            Timestamp authenticatedAt = rs.getTimestamp(5);
            return new SessionRow(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                    rs.getString(4), authenticatedAt == null ? null : authenticatedAt.toInstant());
        });
        sessions.forEach(session -> userIds.add(session.userId()));
        var loaded = new java.util.HashMap<Long, UserState>();
        for (Long userId : userIds) {
            UserState user = state.userExists(userId) ? state.user(userId) : reloadUser(userId).orElse(null);
            if (user != null) loaded.put(userId, user);
        }
        int restored = 0;
        Instant now = Instant.now();
        for (SessionRow session : sessions) {
            UserState user = loaded.get(session.userId());
            if (user == null || state.sessionExists(session.sessionId())) continue;
            boolean admin = user.roles().stream().anyMatch(properties.getSecurity().getAdminRoles()::contains);
            Duration window = admin ? properties.getSecurity().getAdminSessionTtl() : properties.getSecurity().getSessionTtl();
            Duration remaining = Duration.between(now, session.expiresAt());
            if (remaining.isNegative() || remaining.isZero()) continue;
            state.startSession(new SessionState(session.sessionId(), session.userId(),
                    session.deviceId(), session.authenticatedAt() == null ? 0 : session.authenticatedAt().toEpochMilli(),
                    window.toSeconds(), now.toEpochMilli()), remaining);
            restored++;
        }
        log.info("auth state rebuild: {} api keys scanned, {} users, {} sessions restored",
                keys.size(), loaded.size(), restored);
    }
}
