package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "TELEGRAM_TEST_JDBC_URL", matches = ".+")
class TelegramLoginDatabaseTest {
    JdbcTemplate admin, jdbc;
    String schema;
    TelegramLoginRepository repository;
    TelegramLoginService login;
    String device = "telegram-test-device";

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("TELEGRAM_TEST_JDBC_URL");
        String user = System.getenv("TELEGRAM_TEST_DB_USER");
        String password = System.getenv("TELEGRAM_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "telegram_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE gateway_users (
                    user_id bigserial PRIMARY KEY, username text UNIQUE, email text, phone text,
                    password_hash text NOT NULL, status text NOT NULL DEFAULT 'NORMAL',
                    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
                )
                """);
        String migration = Files.readString(Path.of("../deployment/migrations/20261020-telegram-login.sql"));
        jdbc.execute(migration);
        // Re-running migrations must preserve existing identities and flows.
        jdbc.execute(migration);
        repository = new TelegramLoginRepository(jdbc);
        var oidc = mock(TelegramOidcClient.class);
        when(oidc.enabled()).thenReturn(true);
        when(oidc.authorizationUrl(any(), any(), any())).thenReturn("https://oauth.telegram.org/auth");
        when(oidc.exchange(any(), any(), any())).thenReturn(new TelegramOidcClient.Profile("123456789", "小李 🚀"));
        var users = mock(AuthPersistenceService.class);
        var userRepository = new GatewayUserRepository(jdbc);
        when(users.createUser(isNull(), isNull(), isNull(), any())).thenAnswer(invocation -> {
            var created = userRepository.create(null, null, null, invocation.getArgument(3));
            return new AuthModels.AuthenticatedUser(created.userId(), null, null, "NORMAL", List.of("USER"), created.createdAt());
        });
        var auth = mock(AuthService.class);
        when(auth.loginTelegram(anyLong(), any())).thenAnswer(invocation -> {
            var found = userRepository.find(invocation.getArgument(0)).orElseThrow();
            return new AuthModels.AuthResponse(new AuthModels.AuthenticatedUser(found.userId(), found.username(),
                    found.email(), found.status(), List.of("USER"), found.createdAt(), found.displayName()),
                    "access", "refresh", Instant.now().plusSeconds(60), Instant.now().plusSeconds(600));
        });
        var proxy = new ProxyFactory(new TelegramLoginService(repository, oidc, users, auth));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        login = (TelegramLoginService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() { if (schema != null && admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    TelegramLoginService.Started authorize() {
        var flow = login.start(device);
        assertThat(login.callback(flow.flowId(), "authorization-code", null)).isTrue();
        return flow;
    }

    AuthModels.AuthResponse complete(TelegramLoginService.Started flow, String device) {
        var http = new MockHttpServletRequest();
        http.addHeader("X-Device-Id", device);
        return (AuthModels.AuthResponse) login.complete(flow.flowId(), flow.verifier(), device, http);
    }

    @Test
    void registrationAndRepeatLoginPreserveOnePasswordlessAccountAndNickname() {
        var first = complete(authorize(), device);
        var second = complete(authorize(), device);
        assertThat(second.user().userId()).isEqualTo(first.user().userId());
        assertThat(second.user().displayName()).isEqualTo("小李 🚀");
        assertThat(second.requiresEmailVerification()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM gateway_users", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_login_flows", Integer.class)).isZero();
    }

    @Test
    void cannotRedeemOnDifferentDeviceWithWrongVerifierOrAfterConsumption() {
        var flow = authorize();
        assertThatThrownBy(() -> complete(flow, "other-device")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> login.complete(flow.flowId(), "x".repeat(43), device, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(complete(flow, device).accessToken()).isEqualTo("access");
        assertThatThrownBy(() -> complete(flow, device)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentFirstLoginsCreateOnlyOneAccount() throws Exception {
        var one = authorize();
        var two = authorize();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return complete(one, device); });
            var b = executor.submit(() -> { start.await(); return complete(two, device); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).user().userId()).isEqualTo(b.get(10, TimeUnit.SECONDS).user().userId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateCallbackAndExpiredFlowAreRejected() {
        var flow = authorize();
        assertThatThrownBy(() -> login.callback(flow.flowId(), "another-code", null)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE gateway_telegram_login_flows SET expires_at = now() - interval '1 second'");
        assertThatThrownBy(() -> complete(flow, device)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isZero();
    }
}
