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

/** Postgres 持久配置（用户、角色、封禁、API Key、Telegram）同步到 Valkey；不恢复登录会话。 */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class AuthStateSyncPostgresTest {

    JdbcTemplate jdbc;
    AuthStateFixture fixture;
    AuthStateSync sync;
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
        sync = new AuthStateSync(jdbc, fixture.state, fixture.properties, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        users = new GatewayUserRepository(jdbc); users.setAuthState(sync);
        userRoles = new GatewayUserRoleRepository(jdbc); userRoles.setAuthState(sync);
        rolePermissions = new GatewayRolePermissionRepository(jdbc); rolePermissions.setAuthState(sync);
        blocks = new GatewayUserAccessBlockRepository(jdbc); blocks.setAuthState(sync);
        apiKeys = new GatewayApiKeyRepository(jdbc); apiKeys.setAuthState(sync);
        userId = users.create("sync_user", "sync@example.test", "hash", now).userId();
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
    void 启动同步只处理持久配置而不恢复登录会话() {
        fixture.startSession(userId, 123);
        fixture.backend.delete("auth:session:123");
        apiKeys.create(java.util.UUID.randomUUID(), userId, "sx_rebuild", "cipher", "bot", "READ", "", now);
        fixture.backend.delete("auth:apikey:sx_rebuild", "auth:user:"+userId);
        sync.syncPersistentState();
        assertThat(fixture.state.sessionExists(123)).isFalse();
        assertThat(fixture.state.apiKeyExists("sx_rebuild")).isTrue();
        assertThat(fixture.state.user(userId).username()).isEqualTo("sync_user");
    }
}
