package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.surprising.gateway.provider.auth.repository.GatewayAuthChallengeRepository;
import com.surprising.gateway.provider.auth.repository.LoginChallengeRepository;
import com.surprising.gateway.provider.auth.repository.LoginFactorRepository;
import com.surprising.gateway.provider.auth.model.LoginVerificationRows;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AuthTableBoundariesPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc,admin;
    private DriverManagerDataSource adminSource;
    private GatewayUserRepository userRows;
    private GatewayUserMfaRepository mfaRows;
    private AuthPersistenceService users;
    private LoginVerificationService login;
    private EmailVerificationService email;
    private long userId;
    private Instant now;

    private DriverManagerDataSource source(String name) {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL");
        return new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"ApplicationName="+name,System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var source=source("mvc_auth_target");adminSource=source("mvc_auth_admin");admin=new JdbcTemplate(adminSource);jdbc=new JdbcTemplate(source);
        admin.execute("TRUNCATE gateway_users CASCADE");
        now=Instant.now();userRows=new GatewayUserRepository(jdbc);mfaRows=new GatewayUserMfaRepository(jdbc);
        userId=userRows.create("mvc_auth_user","before@example.test","hash",now).userId();
        var properties=new GatewayProperties();properties.getSecurity().setSimulatedVerificationCodesEnabled(true);
        var passwords=mock(PasswordHasher.class);when(passwords.matches("password","hash")).thenReturn(true);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(GatewayProperties.class,()->properties);
        context.registerBean(GatewayUserRepository.class,()->userRows);context.registerBean(GatewayUserMfaRepository.class,()->mfaRows);
        context.registerBean(LoginChallengeRepository.class,()->new LoginChallengeRepository(jdbc));
        context.registerBean(LoginFactorRepository.class,()->new LoginFactorRepository(jdbc));
        context.registerBean(GatewayAuthChallengeRepository.class,()->new GatewayAuthChallengeRepository(jdbc));
        context.registerBean(AuthPersistenceService.class,()->new AuthPersistenceService(userRows,new GatewayRoleRepository(jdbc),
                new GatewayPermissionRepository(jdbc),new GatewayUserRoleRepository(jdbc),new GatewayRolePermissionRepository(jdbc),
                new GatewayLoginLogRepository(jdbc),mfaRows,new GatewayRefreshSessionRepository(jdbc)));
        context.registerBean(TotpService.class,()->new TotpService(properties));context.registerBean(PasswordHasher.class,()->passwords);
        context.registerBean(EmailMessageSender.class,()->mock(EmailMessageSender.class));
        context.register(EmailVerificationService.class,LoginVerificationService.class);context.refresh();
        users=context.getBean(AuthPersistenceService.class);login=context.getBean(LoginVerificationService.class);email=context.getBean(EmailVerificationService.class);
    }
    @AfterEach void close() { if(context!=null)context.close(); }

    @Test void userWriteFailureRollsBackEmailChallengeConsumption() {
        email.issueEmailVerification(userId,"before@example.test","127.0.0.1",now);
        jdbc.execute("ALTER TABLE gateway_users ADD CONSTRAINT mvc_verified_fault CHECK(email_verified_at IS NULL) NOT VALID");
        try {
            assertThatThrownBy(()->email.verifyEmail(userId,"before@example.test","123456",now.plusSeconds(1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM gateway_auth_challenges WHERE user_id=?",Boolean.class,userId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM gateway_users WHERE user_id=?",Boolean.class,userId)).isTrue();
        } finally { jdbc.execute("ALTER TABLE gateway_users DROP CONSTRAINT mvc_verified_fault"); }
        assertThat(email.verifyEmail(userId,"before@example.test","123456",now.plusSeconds(2))).isTrue();
        assertThat(email.verifyEmail(userId,"before@example.test","123456",now.plusSeconds(3))).isFalse();
    }
    @Test void contactFailureRollsBackFactorAndKeepsTheBindingChallengeUsable() {
        var issued=login.beginBinding(userId,"EMAIL",new LoginVerificationService.SettingRequest("password","changed@example.test",true),now);
        var codes=new LoginVerificationService.VerifyRequest(issued.challenge().challengeToken(),"123456",null,null);
        var confirmation=new LoginVerificationService.SettingConfirmation("password",codes);
        jdbc.execute("ALTER TABLE gateway_users ADD CONSTRAINT mvc_contact_fault CHECK(email<>'changed@example.test') NOT VALID");
        try {
            assertThatThrownBy(()->login.confirmBinding(userId,"EMAIL",confirmation,now.plusSeconds(1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_login_factors WHERE user_id=?",Integer.class,userId)).isZero();
            assertThat(jdbc.queryForObject("SELECT consumed FROM gateway_login_challenges WHERE user_id=? AND purpose='EMAIL'",Boolean.class,userId)).isFalse();
            assertThat(userRows.findCredentialByUserId(userId).orElseThrow().email()).isEqualTo("before@example.test");
        } finally { jdbc.execute("ALTER TABLE gateway_users DROP CONSTRAINT mvc_contact_fault"); }
        login.confirmBinding(userId,"EMAIL",confirmation,now.plusSeconds(2));
        assertThat(userRows.findCredentialByUserId(userId).orElseThrow().email()).isEqualTo("changed@example.test");
        assertThat(jdbc.queryForObject("SELECT enabled FROM gateway_login_factors WHERE user_id=? AND method='EMAIL'",Boolean.class,userId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT consumed FROM gateway_login_challenges WHERE user_id=? AND purpose='EMAIL'",Boolean.class,userId)).isTrue();
        assertThat(users.withdrawalRestrictedUntil(userId)).isPresent();
    }
    @ParameterizedTest @ValueSource(strings={"UPSERT","ENABLE","DISABLE"})
    void mfaAggregationHoldsTheUserLockWhileWaitingForItsOwnTable(String action) throws Exception {
        users.upsertMfaSecret(userId,"pending-secret",now);
        if(action.equals("DISABLE"))users.enableMfa(userId,now);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var holder=executor.submit(()->new TransactionTemplate(new DataSourceTransactionManager(adminSource)).execute(status->{
                admin.queryForObject("SELECT user_id FROM gateway_user_mfa WHERE user_id=? FOR UPDATE",Long.class,userId);held.countDown();
                try { assertThat(release.await(8,TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException ex){throw new RuntimeException(ex);}return 1;
            }));
            Future<?> mfa=null,editor=null;
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                mfa=executor.submit(()->{switch(action) {
                    case "UPSERT" -> users.upsertMfaSecret(userId,"changed-secret",now.plusSeconds(1));
                    case "ENABLE" -> users.enableMfa(userId,now.plusSeconds(1));
                    case "DISABLE" -> users.disableMfa(userId,now.plusSeconds(1));
                    default -> throw new IllegalArgumentException(action);
                }});
                assertThat(waitForLock("mvc_auth_target","gateway_user_mfa",mfa)).as("MFA write reached its table with user lock held").isTrue();
                editor=executor.submit(()->new JdbcTemplate(source("mvc_auth_editor")).update("UPDATE gateway_users SET updated_at=now() WHERE user_id=?",userId));
                assertThat(waitForLock("mvc_auth_editor","gateway_users",editor)).as("user changes wait for complete MFA operation").isTrue();
            } finally { release.countDown();holder.get(5,TimeUnit.SECONDS); }
            mfa.get(5,TimeUnit.SECONDS);editor.get(5,TimeUnit.SECONDS);
        }
        var credential=mfaRows.find(userId).orElseThrow();
        assertThat(credential.enabled()).isEqualTo(action.equals("ENABLE"));
        if(action.equals("UPSERT"))assertThat(credential.totpSecretCiphertext()).isEqualTo("changed-secret");
        if(action.equals("DISABLE"))assertThat(credential.verifiedAt()).isNull();
    }
    private boolean waitForLock(String name,String table,Future<?> operation) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline && !operation.isDone()) {
            if(admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND query LIKE ?",Integer.class,name,"%"+table+"%")>0)return true;
            Thread.sleep(10);
        }
        return false;
    }
    @Test void loginFactorRepresentationRemainsStableForPersistedFingerprints() {
        var time=Instant.parse("2026-10-21T00:00:00Z");
        assertThat(new LoginVerificationRows.Factor("EMAIL","u@example.test",true,time,time).toString())
                .isEqualTo("Factor[method=EMAIL, destination=u@example.test, enabled=true, verifiedAt=2026-10-21T00:00:00Z, updatedAt=2026-10-21T00:00:00Z]");
    }
}
