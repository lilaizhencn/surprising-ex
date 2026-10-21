package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels;
import com.surprising.gateway.provider.auth.AuthPersistenceService;
import com.surprising.gateway.provider.auth.EmailMessageSender;
import com.surprising.gateway.provider.auth.GatewayAuthChallengeRepository;
import com.surprising.gateway.provider.auth.GatewayLoginLogRepository;
import com.surprising.gateway.provider.auth.GatewayRefreshSessionRepository;
import com.surprising.gateway.provider.auth.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.TotpService;
import com.surprising.gateway.provider.auth.UserSecurityService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.exception.SecurityVerificationRequiredException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named = "LOGIN_TEST_JDBC_URL", matches = ".+")
class UserAccessPostgresTest {
    private static final String CURRENT_DEVICE = "153a20c8-d24f-4952-b378-5409de885ab5";
    private static final String OTHER_DEVICE = "fe7b9fee-479b-4986-a1da-4fa0db57aa8b";
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private UserAccessService service;
    private SensitiveActionVerificationService verification;
    private GatewayUserAccessBlockRepository blocks;
    private final AtomicInteger statements = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LOGIN_TEST_JDBC_URL");
        String user = System.getenv("LOGIN_TEST_DB_USER");
        String password = System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "user_access_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new CountingDataSource(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO gateway_users VALUES (42),(99)");
        jdbc.execute("""
                CREATE TABLE gateway_refresh_sessions (
                    session_id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,device_id UUID,
                    user_agent TEXT,ip_address TEXT,created_at TIMESTAMPTZ,updated_at TIMESTAMPTZ,
                    expires_at TIMESTAMPTZ,revoked_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE gateway_login_logs (
                    login_id BIGSERIAL PRIMARY KEY,user_id BIGINT,result TEXT,reason TEXT,
                    user_agent TEXT,ip_address TEXT,created_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE gateway_user_access_blocks (
                    user_id BIGINT,block_type TEXT,block_value TEXT,created_at TIMESTAMPTZ,revoked_at TIMESTAMPTZ)
                """);
        jdbc.execute("CREATE UNIQUE INDEX active_access_block ON gateway_user_access_blocks(user_id,block_type,block_value) WHERE revoked_at IS NULL");
        String init = Files.readString(Path.of("../init.sql"));
        int start = init.indexOf("CREATE TABLE IF NOT EXISTS gateway_auth_challenges (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        Instant now = Instant.now();
        addSession(77, 42, CURRENT_DEVICE, "127.0.0.1", now);
        addSession(88, 42, OTHER_DEVICE, "127.0.0.2", now.minusSeconds(1));
        addSession(89, 42, OTHER_DEVICE, "127.0.0.2", now.minusSeconds(2));
        addSession(91, 42, null, "127.0.0.3", now.minusSeconds(3));
        addSession(90, 99, OTHER_DEVICE, "127.0.0.2", now);
        var sessions = new GatewayRefreshSessionRepository(jdbc);
        var logins = new GatewayLoginLogRepository(jdbc);
        blocks = new GatewayUserAccessBlockRepository(jdbc);
        logins.append(42, "SUCCESS", null, "browser", "127.0.0.1", now);
        logins.append(42, "SUCCESS", null, "phone", "127.0.0.2", now.minusSeconds(1));
        logins.append(42, "SUCCESS", null, "phone", "127.0.0.2", now.minusSeconds(2));
        logins.append(99, "SUCCESS", null, "other user", "127.0.0.4", now);
        var users = mock(AuthPersistenceService.class);
        when(users.user(42)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                42, "user", "user@example.test", "NORMAL", List.of("USER"), now)));
        when(users.mfaCredential(42)).thenReturn(Optional.empty());
        var properties = new GatewayProperties();
        properties.getSecurity().setSimulatedVerificationCodesEnabled(true);
        properties.getSecurity().setVerificationCodePepper("test-access-pepper");
        var security = mock(UserSecurityService.class);
        when(security.isSceneEnabled(42, "CHANGE_PASSWORD")).thenReturn(true);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(SensitiveActionVerificationService.class, () -> new SensitiveActionVerificationService(
                properties, users, security, new GatewayAuthChallengeRepository(jdbc), mock(EmailMessageSender.class), mock(TotpService.class)));
        context.registerBean(UserAccessService.class, () -> new UserAccessService(
                sessions, logins, blocks, context.getBean(SensitiveActionVerificationService.class), security));
        context.refresh();
        service = context.getBean(UserAccessService.class);
        verification = context.getBean(SensitiveActionVerificationService.class);
    }

    @AfterEach
    void cleanUp() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void devicePageUsesBoundedQueriesAndPreservesCurrentAndOwnerScopedBlockStatus() {
        blocks.block(99, "DEVICE", OTHER_DEVICE, Instant.now());
        blocks.block(42, "DEVICE", OTHER_DEVICE, Instant.now());
        statements.set(0);

        var first = service.devices(actor(), 2, 0);

        assertThat(statements.get()).isEqualTo(3);
        assertThat(first).hasSize(2);
        assertThat(first.get(0).deviceId()).isEqualTo(CURRENT_DEVICE);
        assertThat(first.get(0).current()).isTrue();
        assertThat(first.get(0).blocked()).isFalse();
        assertThat(first.get(1).blocked()).isTrue();
        statements.set(0);
        var last = service.devices(actor(), 2, 2);
        assertThat(last).hasSize(1);
        assertThat(last.getFirst().deviceId()).isNull();
        assertThat(last.getFirst().blocked()).isFalse();
        assertThat(statements.get()).isEqualTo(2);
    }

    @Test
    void ipPageAggregatesOnlyThisUserAndFetchesBlockStatusInOneBatch() {
        blocks.block(99, "IP", "127.0.0.1", Instant.now());
        blocks.block(42, "IP", "127.0.0.2", Instant.now());
        statements.set(0);
        var first = service.ips(42, 1, 0);
        assertThat(statements.get()).isEqualTo(2);
        assertThat(first.getFirst().ipAddress()).isEqualTo("127.0.0.1");
        assertThat(first.getFirst().blocked()).isFalse();
        var second = service.ips(42, 1, 1);
        assertThat(second.getFirst().ipAddress()).isEqualTo("127.0.0.2");
        assertThat(second.getFirst().loginCount()).isEqualTo(2);
        assertThat(second.getFirst().blocked()).isTrue();
    }

    @Test
    void blockingDeviceRevokesAllItsSessionsOnlyForTheAuthenticatedOwner() {
        verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        service.blockDevice(actor(), OTHER_DEVICE, "123456", "");

        assertThat(blocks.blocked(42, "DEVICE", OTHER_DEVICE)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE user_id=42 AND revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM gateway_refresh_sessions WHERE session_id=90", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM gateway_refresh_sessions WHERE session_id=77", Timestamp.class)).isNull();
    }

    @Test
    void revokeFailureRollsBackBlockAndChallengeConsumptionSoTheOriginalProofCanBeRetried() {
        var challenge = verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        jdbc.execute("ALTER TABLE gateway_refresh_sessions ADD CONSTRAINT reject_revoke CHECK (revoked_at IS NULL)");

        assertThatThrownBy(() -> service.blockDevice(actor(), OTHER_DEVICE, "123456", ""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(blocks.blocked(42, "DEVICE", OTHER_DEVICE)).isFalse();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM gateway_auth_challenges WHERE challenge_id=?", Timestamp.class, challenge.challengeId()))
                .isNull();
        jdbc.execute("ALTER TABLE gateway_refresh_sessions DROP CONSTRAINT reject_revoke");
        service.blockDevice(actor(), OTHER_DEVICE, "123456", "");
        assertThat(blocks.blocked(42, "DEVICE", OTHER_DEVICE)).isTrue();
    }

    @Test
    void failedVerificationAttemptsCommitEvenThoughTheSecurityActionIsRejected() {
        var challenge = verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> service.blockDevice(actor(), OTHER_DEVICE, "654321", ""))
                    .isInstanceOf(SecurityVerificationRequiredException.class);
            assertThat(jdbc.queryForObject("SELECT attempts FROM gateway_auth_challenges WHERE challenge_id=?", Integer.class, challenge.challengeId()))
                    .isEqualTo(attempt);
        }
        assertThat(blocks.blocked(42, "DEVICE", OTHER_DEVICE)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void ipBlockAndUnblockPreserveOwnerIsolationAndDoNotReactivateRevokedSessions() {
        verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        service.blockIp(42, "127.0.0.2", "127.0.0.1", "123456", "");
        assertThat(blocks.blocked(42, "IP", "127.0.0.2")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE user_id=42 AND revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM gateway_refresh_sessions WHERE session_id=90", Timestamp.class)).isNull();

        verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        service.unblockIp(42, "127.0.0.2", "123456", "");
        assertThat(blocks.blocked(42, "IP", "127.0.0.2")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_refresh_sessions WHERE user_id=42 AND revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectedUnblockSceneAndPasswordActionsAlsoKeepTheirFailedVerificationAttempts() {
        var challenge = verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        List<Runnable> actions = List.of(
                () -> service.unblockDevice(42, OTHER_DEVICE, "654321", ""),
                () -> service.unblockIp(42, "127.0.0.2", "654321", ""),
                () -> service.updateScene(42, "LOGIN", false, "654321", ""),
                () -> service.changePassword(42, "current-password", "new-password", "654321", ""));
        for (int index = 0; index < actions.size(); index++) {
            assertThatThrownBy(actions.get(index)::run).isInstanceOf(SecurityVerificationRequiredException.class);
            assertThat(jdbc.queryForObject("SELECT attempts FROM gateway_auth_challenges WHERE challenge_id=?", Integer.class, challenge.challengeId()))
                    .isEqualTo(index + 1);
        }
    }

    @Test
    void currentDeviceAndEquivalentCurrentIpAreRejectedBeforeConsumingProof() {
        var challenge = verification.issue(42, "SECURITY_SETTINGS", "127.0.0.1", Instant.now());
        assertThatThrownBy(() -> service.blockDevice(actor(), CURRENT_DEVICE, "123456", ""))
                .hasMessage("cannot block the current device");
        assertThatThrownBy(() -> service.blockIp(42, "0:0:0:0:0:0:0:1", "::1", "123456", ""))
                .hasMessage("cannot block the current IP");
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM gateway_auth_challenges WHERE challenge_id=?", Timestamp.class, challenge.challengeId()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_user_access_blocks", Integer.class)).isZero();
    }

    private AuthModels.JwtPrincipal actor() {
        return new AuthModels.JwtPrincipal(42, "user", "NORMAL", List.of("USER"), Instant.now().plusSeconds(3600), 77);
    }

    private void addSession(long id, long user, String device, String ip, Instant created) {
        jdbc.update("""
                INSERT INTO gateway_refresh_sessions(session_id,user_id,device_id,ip_address,created_at,updated_at,expires_at)
                VALUES (?,?,CAST(? AS uuid),?,?,?,?)
                """, id, user, device, ip, Timestamp.from(created), Timestamp.from(created), Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    private final class CountingDataSource extends DelegatingDataSource {
        CountingDataSource(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")) statements.incrementAndGet();
                        try { return method.invoke(target, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
