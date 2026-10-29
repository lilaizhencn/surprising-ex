package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.controller.AuthClientMetadataBinding;
import com.surprising.gateway.provider.config.GatewayProperties;

import com.surprising.gateway.provider.auth.client.TelegramOidcClient;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.LoginVerificationService;
import com.surprising.gateway.provider.auth.service.TelegramLoginService;

import com.surprising.gateway.provider.auth.repository.TelegramLoginFlowRepository;
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
    TelegramLoginFlowRepository repository;
    TelegramLoginService login;
    TelegramOidcClient oidc;
    AuthService auth;
    GatewayUserRepository userRepository;
    DataSourceTransactionManager manager;
    String device = "a1111111-1111-4111-8111-111111111111";

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
        String mobileMigration = Files.readString(Path.of("../deployment/migrations/20261021-telegram-mobile-return.sql"));
        jdbc.execute(mobileMigration);
        jdbc.execute(mobileMigration);
        repository = new TelegramLoginFlowRepository(jdbc) {
            @Override public int activeCount(String deviceId) {
                int count=super.activeCount(deviceId);
                if(deviceId.equals("parallel-device")) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
                return count;
            }
        };
        oidc = mock(TelegramOidcClient.class);
        when(oidc.enabled()).thenReturn(true);
        when(oidc.authorizationUrl(any(), any(), any())).thenReturn("https://oauth.telegram.org/auth");
        when(oidc.exchange(any(), any(), any())).thenReturn(new TelegramOidcClient.Profile("123456789", "小李 🚀"));
        var users = mock(AuthPersistenceService.class);
        userRepository = new GatewayUserRepository(jdbc);
        when(users.createUser(isNull(), isNull(), isNull(), any())).thenAnswer(invocation -> {
            var created = userRepository.create(null, null, null, invocation.getArgument(3));
            return new AuthModels.AuthenticatedUser(created.userId(), null, null, "NORMAL", List.of("USER"), created.createdAt());
        });
        auth = mock(AuthService.class);
        when(auth.loginTelegram(anyLong(), any())).thenAnswer(invocation -> {
            var found = userRepository.find(invocation.getArgument(0)).orElseThrow();
            return new AuthModels.AuthResponse(new AuthModels.AuthenticatedUser(found.userId(), found.username(),
                    found.email(), found.status(), List.of("USER"), found.createdAt(), found.displayName()),
                    "access", "refresh", Instant.now().plusSeconds(60), Instant.now().plusSeconds(600));
        });
        manager = new DataSourceTransactionManager(ds);
        var proxy = new ProxyFactory(new TelegramLoginService(repository, new com.surprising.gateway.provider.auth.repository.TelegramIdentityRepository(jdbc), userRepository, oidc, users, auth));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        login = (TelegramLoginService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() { if (schema != null && admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    TelegramLoginService.Started authorize() {
        var flow = login.start(device, false);
        assertThat(login.callback(flow.flowId(), "authorization-code", null).success()).isTrue();
        return flow;
    }

    AuthModels.AuthResponse complete(TelegramLoginService.Started flow, String device) {
        var http = new MockHttpServletRequest();
        http.addHeader("X-Device-Id", device);
        return (AuthModels.AuthResponse) login.complete(flow.flowId(), flow.verifier(), device, new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(http));
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
        assertThatThrownBy(() -> login.complete(flow.flowId(), "x".repeat(43), device, new AuthClientMetadataBinding(new GatewayProperties(), org.mockito.Mockito.mock(com.surprising.gateway.provider.service.GatewayRequestLimits.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.controller.GatewayLimitRequestBinding.class)).bind(new MockHttpServletRequest())))
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
    @Test
    void mobileModeSurvivesGatewayInstanceChangeAndWebModeStaysInBrowser() {
        var mobile = login.start(device, true);
        var restarted = new TelegramLoginFlowRepository(jdbc);
        var claimed = restarted.claimCallback(TelegramLoginService.hash(mobile.flowId()), Instant.now()).orElseThrow();
        assertThat(claimed.returnToApp()).isTrue();
        var web = login.start(device, false);
        assertThat(restarted.claimCallback(TelegramLoginService.hash(web.flowId()), Instant.now())
                .orElseThrow().returnToApp()).isFalse();
    }

    @Test void concurrentStartsKeepThePerDeviceLimitAtFive() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<6;i++)results.add(executor.submit(()->{
                start.await();
                try { login.start("parallel-device",false);return true; }
                catch(IllegalArgumentException ex) { assertThat(ex).hasMessageContaining("too many");return false; }
            }));
            start.countDown();int accepted=0;
            for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_login_flows WHERE device_id='parallel-device'",Integer.class)).isEqualTo(5);
        jdbc.update("UPDATE gateway_telegram_login_flows SET expires_at=now()-interval '1 second' WHERE device_id='parallel-device'");
        assertThat(login.start("parallel-device",false)).isNotNull();
    }
    @Test void callbackClaimCommitsBeforeExternalExchangeAndSurvivesCallerRollback() {
        var flow=login.start(device,false);
        when(oidc.exchange(any(),any(),any())).thenAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT callback_claimed FROM gateway_telegram_login_flows WHERE state_hash=?",Boolean.class,TelegramLoginService.hash(flow.flowId()))).isTrue();
            return new TelegramOidcClient.Profile("123456789","小李");
        });
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(login.callback(flow.flowId(),"authorization-code",null).success()).isTrue();status.setRollbackOnly();
        });
        assertThat(repository.claimCallback(TelegramLoginService.hash(flow.flowId()),Instant.now())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT subject FROM gateway_telegram_login_flows WHERE state_hash=?",String.class,TelegramLoginService.hash(flow.flowId()))).isEqualTo("123456789");
    }
    @Test void sessionFailureRollsBackNewIdentityAndAccountAndKeepsAuthorizedFlow() {
        var flow=authorize();
        doThrow(new IllegalStateException("session unavailable")).when(auth).loginTelegram(anyLong(),any());
        assertThatThrownBy(()->complete(flow,device)).hasMessageContaining("session unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_identities",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_login_flows",Integer.class)).isEqualTo(1);
    }
    @Test void secondFactorFailureKeepsTheIdentityAndFlowForTheNextAttempt() {
        var flow=authorize();
        doThrow(new LoginVerificationService.VerificationFailure("LOGIN_MFA_ENROLLMENT_REQUIRED")).when(auth).loginTelegram(anyLong(),any());
        assertThatThrownBy(()->complete(flow,device)).hasMessageContaining("LOGIN_MFA_ENROLLMENT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_users",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_identities",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_telegram_login_flows",Integer.class)).isEqualTo(1);
    }

}
