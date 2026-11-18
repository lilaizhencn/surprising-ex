package com.surprising.gateway.provider.auth.state;

import com.surprising.gateway.provider.config.GatewayProperties;
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
 * 从 Postgres 读出最新持久配置写入 Valkey。普通身份校验不会调用这里；不存储或恢复登录会话。
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

    private void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { action.run(); }
                    });
        } else action.run();
    }

    /** 读取用户最新状态；事务提交后才发布到 Valkey。 */
    public Optional<UserState> reloadUser(long userId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommit(() -> loadUser(userId));
            return Optional.empty();
        }
        return loadUser(userId);
    }

    private Optional<UserState> loadUser(long userId) {
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
        boolean telegramBound = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM gateway_telegram_identities WHERE user_id = ? AND revoked_at IS NULL)",
                Boolean.class, userId));
        var loaded = new UserState(userId, (String) user[0], (String) user[1], (String) user[2], (Instant) user[3],
                (String) user[4], roles, permissions, blockedIps, blockedDevices, hasApiKey, telegramBound);
        state.putUser(loaded);
        return Optional.of(loaded);
    }

    /** 仅用于已经提交的绑定回调；独立只读事务结束后立即发布用户配置。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=true)
    public void reloadCommittedUser(long userId) { loadUser(userId); }

    /** 角色权限变更：拥有该角色的用户批量读取后写入 Valkey，避免按用户 N+1 查询。 */
    public void roleChanged(long roleId) {
        List<Long> userIds = jdbc.queryForList("SELECT user_id FROM gateway_user_roles WHERE role_id = ?",
                Long.class, roleId);
        if (userIds.isEmpty()) {
            return;
        }
        afterCommit(() -> reloadUsers(userIds));
    }

    private void reloadUsers(List<Long> userIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        Object[] args = userIds.toArray();

        var users = new java.util.LinkedHashMap<Long, Object[]>();
        jdbc.query("""
                SELECT user_id, username, email, display_name, created_at, status
                  FROM gateway_users WHERE user_id IN (%s)
                """.formatted(placeholders), rs -> {
            users.put(rs.getLong(1), new Object[]{
                    rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5).toInstant(), rs.getString(6)
            });
        }, args);

        var rolesByUser = new java.util.HashMap<Long, List<String>>();
        jdbc.query("""
                SELECT ur.user_id, r.role_code
                  FROM gateway_user_roles ur JOIN gateway_roles r ON r.role_id = ur.role_id
                 WHERE ur.user_id IN (%s) ORDER BY ur.user_id, r.role_code
                """.formatted(placeholders), rs -> {
            rolesByUser.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        }, args);

        var permsByUser = new java.util.HashMap<Long, List<String>>();
        jdbc.query("""
                SELECT DISTINCT ur.user_id, p.permission_code
                  FROM gateway_user_roles ur
                  JOIN gateway_role_permissions rp ON rp.role_id = ur.role_id
                  JOIN gateway_permissions p ON p.permission_id = rp.permission_id
                 WHERE ur.user_id IN (%s) ORDER BY ur.user_id, p.permission_code
                """.formatted(placeholders), rs -> {
            permsByUser.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        }, args);

        var blockedIpsByUser = new java.util.HashMap<Long, Set<String>>();
        var blockedDevicesByUser = new java.util.HashMap<Long, Set<String>>();
        jdbc.query("""
                SELECT user_id, block_type, block_value FROM gateway_user_access_blocks
                 WHERE user_id IN (%s) AND revoked_at IS NULL
                """.formatted(placeholders), rs -> {
            long uid = rs.getLong(1);
            String type = rs.getString(2);
            if ("IP".equals(type)) blockedIpsByUser.computeIfAbsent(uid, ignored -> new LinkedHashSet<>()).add(rs.getString(3));
            else if ("DEVICE".equals(type)) blockedDevicesByUser.computeIfAbsent(uid, ignored -> new LinkedHashSet<>()).add(rs.getString(3));
        }, args);

        var activeApiKeyUsers = new java.util.HashSet<Long>();
        jdbc.query("""
                SELECT DISTINCT user_id FROM gateway_api_keys WHERE user_id IN (%s) AND status = 'ACTIVE'
                """.formatted(placeholders), rs -> {
            activeApiKeyUsers.add(rs.getLong(1));
        }, args);

        var telegramUsers = new java.util.HashSet<Long>();
        jdbc.query("SELECT DISTINCT user_id FROM gateway_telegram_identities WHERE user_id IN (%s) AND revoked_at IS NULL".formatted(placeholders),
                rs -> { telegramUsers.add(rs.getLong(1)); }, args);

        for (Long userId : userIds) {
            Object[] user = users.get(userId);
            if (user == null) {
                state.deleteUser(userId);
                continue;
            }
            List<String> roles = rolesByUser.getOrDefault(userId, List.of("USER"));
            List<String> perms = permsByUser.getOrDefault(userId, List.of());
            Set<String> blockedIps = blockedIpsByUser.getOrDefault(userId, Set.of());
            Set<String> blockedDevices = blockedDevicesByUser.getOrDefault(userId, Set.of());
            UserState loaded = new UserState(userId, (String) user[0], (String) user[1], (String) user[2],
                    (Instant) user[3], (String) user[4], roles, perms, blockedIps, blockedDevices,
                    activeApiKeyUsers.contains(userId), telegramUsers.contains(userId));
            state.putUser(loaded);
        }
    }

    /** API Key 创建、修改或撤销：有效就写入，否则删除；同时刷新所属用户。 */
    public void apiKeyChanged(String apiKey) {
        afterCommit(() -> loadApiKey(apiKey));
    }

    private void loadApiKey(String apiKey) {
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
        loadUser((Long) row[1]);
    }

    /** 只同步持久配置，不读取或恢复任何浏览器/App 登录会话。 */
    @EventListener(ApplicationReadyEvent.class)
    public void syncPersistentOnStart() {
        if (!properties.getSecurity().isAuthPersistentSyncOnStart()) return;
        syncPersistentState();
    }

    public void syncPersistentState() {
        record KeyConfig(String key, UUID id, long userId, String permissions, String ips, String secret, String status) {}
        String after = "";
        while (true) {
            var keys = jdbc.query("""
                    SELECT api_key,api_key_id,user_id,permissions,ip_allowlist,secret_ciphertext,status
                    FROM gateway_api_keys WHERE api_key > ? ORDER BY api_key LIMIT 500
                    """, (rs,row)->new KeyConfig(rs.getString(1),rs.getObject(2,UUID.class),rs.getLong(3),
                    rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),after);
            if (keys.isEmpty()) break;
            reloadUsers(keys.stream().map(KeyConfig::userId).distinct().toList());
            for (KeyConfig key:keys) {
                if ("ACTIVE".equals(key.status())) state.putApiKey(new ApiKeyState(key.key(),key.id(),key.userId(),key.permissions(),key.ips(),key.secret()));
                else state.deleteApiKey(key.key());
            }
            after = keys.get(keys.size() - 1).key();
        }
        after = "";
        while (true) {
            record Binding(String subject, long userId, boolean revoked) {}
            var bindings = jdbc.query("SELECT subject, user_id, revoked_at IS NOT NULL FROM gateway_telegram_identities WHERE subject > ? ORDER BY subject LIMIT 500",
                    (rs, row) -> new Binding(rs.getString(1), rs.getLong(2), rs.getBoolean(3)), after);
            if (bindings.isEmpty()) break;
            reloadUsers(bindings.stream().map(Binding::userId).distinct().toList());
            for (Binding binding : bindings) {
                if (binding.revoked()) state.deleteTelegramIdentity(binding.subject());
                else state.putTelegramIdentity(binding.subject(), binding.userId());
            }
            after = bindings.get(bindings.size() - 1).subject();
        }
        log.info("persistent auth configuration synchronized; login sessions are never restored");
    }
}
