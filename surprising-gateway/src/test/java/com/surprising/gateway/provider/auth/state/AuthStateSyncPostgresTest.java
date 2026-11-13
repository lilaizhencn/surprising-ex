package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;

import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Postgres 的写入点（用户、角色、封禁、会话、API Key）同步到 Valkey 状态，以及启动时补齐缺失状态。 */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class AuthStateSyncPostgresTest {

    JdbcTemplate jdbc;
    AuthStateFixture fixture;
    AuthStateSync sync;
    GatewayRefreshSessionRepository sessions;
    GatewayUserRepository users;
    GatewayUserRoleRepository userRoles;
    GatewayRolePermissionRepository rolePermissions;
    GatewayUserAccessBlockRepository blocks;
    GatewayApiKeyRepository apiKeys;
    long userId;
    final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        jdbc.execute("TRUNCATE gateway_users CASCADE");
        fixture = new AuthStateFixture();
        sync = new AuthStateSync(jdbc, fixture.state, fixture.properties);
        sessions = new GatewayRefreshSessionRepository(jdbc); sessions.setAuthState(sync);
        users = new GatewayUserRepository(jdbc); users.setAuthState(sync);
        userRoles = new GatewayUserRoleRepository(jdbc); userRoles.setAuthState(sync);
        rolePermissions = new GatewayRolePermissionRepository(jdbc); rolePermissions.setAuthState(sync);
        blocks = new GatewayUserAccessBlockRepository(jdbc); blocks.setAuthState(sync);
        apiKeys = new GatewayApiKeyRepository(jdbc); apiKeys.setAuthState(sync);
        userId = users.create("sync_user", "sync@example.test", "hash", now).userId();
    }

    private long session(String hash) {
        return sessions.save(userId, hash, now.plus(Duration.ofDays(7)), "ua", "203.0.113.7", null, now);
    }

    @Test
    void 用户状态变化立即写进Valkey() {
        sync.reloadUser(userId);
        assertThat(fixture.state.user(userId).status()).isEqualTo("NORMAL");
        users.updateStatus(userId, "FROZEN", now);
        assertThat(fixture.state.user(userId).status()).isEqualTo("FROZEN");
    }

    @Test
    void 角色权限和封禁变化立即写进Valkey() {
        long roleId = jdbc.queryForObject("SELECT role_id FROM gateway_roles WHERE role_code='ADMIN'", Long.class);
        long permissionId = jdbc.queryForObject("SELECT permission_id FROM gateway_permissions ORDER BY permission_id LIMIT 1", Long.class);
        String permission = jdbc.queryForObject("SELECT permission_code FROM gateway_permissions WHERE permission_id=?", String.class, permissionId);
        userRoles.replace(userId, List.of(roleId), now);
        assertThat(fixture.state.user(userId).roles()).contains("ADMIN");
        rolePermissions.replace(roleId, List.of(permissionId), now);
        assertThat(fixture.state.user(userId).permissions()).containsExactly(permission);
        rolePermissions.replace(roleId, List.of(), now);
        assertThat(fixture.state.user(userId).permissions()).isEmpty();
        blocks.block(userId, "IP", "203.0.113.7", now);
        assertThat(fixture.state.user(userId).blockedIps()).containsExactly("203.0.113.7");
        blocks.unblock(userId, "IP", "203.0.113.7", now);
        assertThat(fixture.state.user(userId).blockedIps()).isEmpty();
    }

    @Test
    void 撤销会话同时删除Valkey会话() {
        long a = session("a"), b = session("b"), c = session("c");
        for (long id : List.of(a, b, c)) fixture.startSession(userId, id);
        assertThat(sessions.revokeActiveForUserExcept(userId, b, now)).isEqualTo(2);
        assertThat(fixture.state.sessionExists(a)).isFalse();
        assertThat(fixture.state.sessionExists(b)).isTrue();
        assertThat(fixture.state.sessionExists(c)).isFalse();
        assertThat(sessions.revokeActive(b, now)).isEqualTo(1);
        assertThat(fixture.state.sessionExists(b)).isFalse();
        assertThat(fixture.state.session(b)).isNull();
    }

    @Test
    void API_Key创建撤销同步_并且用户成为常驻状态() {
        apiKeys.create(java.util.UUID.randomUUID(), userId, "sx_sync_key", "cipher", "bot", "READ", "", now);
        assertThat(fixture.state.apiKey("sx_sync_key").permissions()).isEqualTo("READ");
        assertThat(fixture.state.user(userId).hasApiKey()).isTrue();
        assertThat(fixture.backend.ttlMillis("auth:user:" + userId)).isEqualTo(-1);
        assertThat(apiKeys.revoke(userId, "sx_sync_key", now)).isTrue();
        assertThat(fixture.state.apiKey("sx_sync_key")).isNull();
        assertThat(fixture.state.user(userId).hasApiKey()).isFalse();
    }

    @Test
    void 启动补齐只恢复有效会话和有效API_Key且不覆盖已有状态() {
        long live = session("live"), revoked = session("revoked");
        sessions.revokeActive(revoked, now);
        apiKeys.create(java.util.UUID.randomUUID(), userId, "sx_rebuild", "cipher", "bot", "READ", "", now);
        fixture.backend.delete("auth:apikey:sx_rebuild", "auth:user:" + userId);
        fixture.backend.writes = 0;
        sync.rebuildMissing();
        assertThat(fixture.state.sessionExists(live)).isTrue();
        assertThat(fixture.state.sessionExists(revoked)).isFalse();
        assertThat(fixture.state.apiKeyExists("sx_rebuild")).isTrue();
        assertThat(fixture.state.user(userId).username()).isEqualTo("sync_user");
        assertThat(fixture.backend.ttlMillis("auth:session:" + live)).isBetween(Duration.ofDays(6).toMillis(), Duration.ofDays(7).toMillis());
        // 第二次补齐不改动已有的键
        int writes = fixture.backend.writes;
        sync.rebuildMissing();
        assertThat(fixture.backend.writes).isEqualTo(writes);
    }
}
