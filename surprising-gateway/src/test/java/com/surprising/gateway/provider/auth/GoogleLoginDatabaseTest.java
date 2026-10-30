package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.auth.model.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIfEnvironmentVariable(named = "GOOGLE_TEST_JDBC_URL", matches = ".+")
class GoogleLoginDatabaseTest {
    JdbcTemplate admin, jdbc;
    String schema;
    GoogleLoginService login;
    GoogleLoginRepository identities;
    AuthService auth;
    final AuthClientMetadata device = new AuthClientMetadata("qa", "127.0.0.1", "google-test-device", java.util.List.of(), false);

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("GOOGLE_TEST_JDBC_URL"), user = System.getenv("GOOGLE_TEST_DB_USER"), password = System.getenv("GOOGLE_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "google_qa_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE gateway_users(user_id bigserial PRIMARY KEY, username text, email text, phone text, password_hash text NOT NULL, status text NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)");
        String migration = Files.readString(Path.of("../deployment/migrations/20261029-google-login.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        identities = new GoogleLoginRepository(jdbc);
        var google = mock(GoogleIdentityClient.class);
        when(google.verify(anyString(), any(), anyString())).thenReturn(new GoogleIdentityClient.Profile("same-google-subject", "小李 🚀"));
        var users = mock(AuthPersistenceService.class);
        var rows = new GatewayUserRepository(jdbc);
        when(users.createUser(isNull(), isNull(), isNull(), any())).thenAnswer(call -> {
            var row = rows.create(null, null, null, call.getArgument(3));
            return new AuthenticatedUser(row.userId(), null, null, "NORMAL", List.of("USER"), row.createdAt());
        });
        auth = mock(AuthService.class);
        when(auth.loginGoogle(anyLong(), any())).thenAnswer(call -> {
            var row = rows.find(call.getArgument(0)).orElseThrow();
            return new AuthResponse(new AuthenticatedUser(row.userId(), null, null, "NORMAL", List.of("USER"), row.createdAt(), row.displayName()),
                    "access", "refresh", Instant.now().plusSeconds(300), Instant.now().plusSeconds(3600));
        });
        var proxy = new ProxyFactory(new GoogleLoginService(identities, google, rows, users, auth, mock(com.surprising.affiliate.service.AffiliateService.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        login = (GoogleLoginService) proxy.getProxy();
    }
    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    AuthResponse complete(GoogleLoginService.Challenge flow, AuthClientMetadata request) {
        return (AuthResponse) login.login("signed-fixture", flow.nonce(), "WEB", null, request);
    }
    @Test void firstAndReturningLoginKeepOnePasswordlessAccount() {
        var first = complete(login.challenge(device.deviceId()), device);
        var second = complete(login.challenge(device.deviceId()), device);
        assertThat(second.user().userId()).isEqualTo(first.user().userId());
        assertThat(second.user().displayName()).isEqualTo("小李 🚀");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users WHERE password_hash IS NULL AND email IS NULL", Integer.class)).isEqualTo(1);
    }
    @Test void wrongDeviceExpiryAndReplayCannotCreateAnotherSession() {
        var flow = login.challenge(device.deviceId());
        assertThatThrownBy(() -> complete(flow, new AuthClientMetadata("qa", "127.0.0.1", "other-device", java.util.List.of(), false))).isInstanceOf(IllegalArgumentException.class);
        complete(flow, device);
        assertThatThrownBy(() -> complete(flow, device)).isInstanceOf(IllegalArgumentException.class);
        var expired = login.challenge(device.deviceId());
        jdbc.update("UPDATE gateway_google_login_challenges SET expires_at = now() - interval '1 second'");
        assertThatThrownBy(() -> complete(expired, device)).isInstanceOf(IllegalArgumentException.class);
        verify(auth, times(1)).loginGoogle(anyLong(), any());
    }
    @Test void simultaneousFirstLoginsCreateOneIdentity() throws Exception {
        var a = login.challenge(device.deviceId()); var b = login.challenge("second-device");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var one = pool.submit(() -> { gate.await(); return complete(a, device); });
            var two = pool.submit(() -> { gate.await(); return complete(b, new AuthClientMetadata("qa", "127.0.0.1", "second-device", java.util.List.of(), false)); });
            gate.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS).user().userId()).isEqualTo(two.get(20, TimeUnit.SECONDS).user().userId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_google_identities", Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousReplayIsConsumedExactlyOnce() throws Exception {
        var flow = login.challenge(device.deviceId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> work = () -> { gate.await(); try { complete(flow, device); return true; } catch (IllegalArgumentException expected) { return false; } };
            var one = pool.submit(work); var two = pool.submit(work); gate.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        verify(auth, times(1)).loginGoogle(anyLong(), any());
    }
    @Test void failedAccountGateRollsBackRegistrationAndChallengeConsumption() {
        var flow = login.challenge(device.deviceId());
        doThrow(new IllegalStateException("frozen")).when(auth).loginGoogle(anyLong(), any());
        assertThatThrownBy(() -> complete(flow, device)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_google_login_challenges", Integer.class)).isEqualTo(1);
    }
    @Test void unusedChallengesAreBoundedPerDevice() {
        var oldest = login.challenge(device.deviceId());
        for (int i = 0; i < 5; i++) login.challenge(device.deviceId());
        var latest = login.challenge(device.deviceId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_google_login_challenges", Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> complete(oldest, device)).isInstanceOf(IllegalArgumentException.class);
        complete(latest, device);
    }
}
