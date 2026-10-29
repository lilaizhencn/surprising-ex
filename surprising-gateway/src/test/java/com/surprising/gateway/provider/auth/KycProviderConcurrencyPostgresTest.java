package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.client.KycProvider;
import com.surprising.gateway.provider.auth.client.KycProviderRegistry;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.repository.ComplianceKycRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.repository.KycProviderEventRepository;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="LOGIN_TEST_JDBC_URL",matches=".+")
class KycProviderConcurrencyPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private JdbcTemplate admin,jdbc,independent;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private ComplianceService service;
    private ComplianceKycRepository profiles;
    private KycProviderRegistry providers;
    private final AtomicInteger lockedReads=new AtomicInteger();
    private final CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
    private volatile boolean pauseRead;
    private volatile Runnable afterEvent;
    private DriverManagerDataSource source(String app) {
        String url=System.getenv("LOGIN_TEST_JDBC_URL");
        return new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+"&ApplicationName="+app,System.getenv("LOGIN_TEST_DB_USER"),System.getenv("LOGIN_TEST_DB_PASSWORD"));
    }
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("LOGIN_TEST_JDBC_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("LOGIN_TEST_DB_USER"),System.getenv("LOGIN_TEST_DB_PASSWORD")));
        schema="mvc_kyc_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=source("mvc_kyc_callback");jdbc=new JdbcTemplate(source);independent=new JdbcTemplate(source("mvc_kyc_resubmitter"));
        jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY)");jdbc.execute("INSERT INTO gateway_users VALUES(42)");
        String init=Files.readString(Path.of("../init.sql"));
        for(String table:List.of("gateway_user_kyc_profiles","gateway_kyc_provider_events")) {
            int a=init.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");jdbc.execute(init.substring(a,init.indexOf("\n);",a)+3));
        }
        profiles=new ComplianceKycRepository(jdbc) {
            @Override public KycProfile findForUpdate(long userId) {
                var row=super.findForUpdate(userId);lockedReads.incrementAndGet();
                if(pauseRead) {
                    held.countDown();
                    try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }catch(InterruptedException ex){throw new RuntimeException(ex);}
                }
                return row;
            }
        };
        profiles.submit(42,request("session-old"),"[]",Instant.now());
        var events=new KycProviderEventRepository(jdbc) {
            @Override public boolean record(String provider,String key,Instant now) {
                boolean created=super.record(provider,key,now);
                var hook=afterEvent;if(hook!=null){afterEvent=null;hook.run();}return created;
            }
        };
        providers=mock(KycProviderRegistry.class);
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(new KycProvider.KycProviderResult("session-old","42","event-1","VERIFIED",null, java.time.Instant.now(), null, null)));
        when(providers.simulationEnabled()).thenReturn(true);when(providers.verificationValidity()).thenReturn(java.time.Duration.ofDays(365));when(providers.selectedProvider()).thenReturn("SUMSUB");
        var auth=mock(AuthService.class);when(auth.authenticateBearer("auth")).thenReturn(new AuthModels.JwtPrincipal(42,"user","NORMAL",List.of(),Instant.now().plusSeconds(600)));
        var countries=mock(com.surprising.gateway.provider.auth.repository.CountryRepository.class);
        when(countries.supported("SG")).thenReturn(true);
        when(providers.start(eq(42L),any(),anyList())).thenReturn(new KycProvider.KycProviderSession("SUMSUB","session-new", "token",null,"PENDING",false));
        var documents=mock(KycDocumentService.class);
        when(documents.references(anyList(),any(),any(),any())).thenReturn("[]");
        var target=new ComplianceService(mock(),profiles,events,mock(),mock(),auth,mock(),documents,mock(),mock(),mock(),providers,countries);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));context.registerBean(ComplianceService.class,()->target);context.refresh();service=context.getBean(ComplianceService.class);
    }
    @AfterEach void close() { release.countDown();if(context!=null)context.close();if(schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private KycSubmissionRequest request(String reference) { return new KycSubmissionRequest("INDIVIDUAL","STANDARD","SG","PASSPORT","SUMSUB",reference,null,"PENDING",List.of()); }
    private KycProfile callback() { return service.applyProviderCallback("SUMSUB",Map.of(),new byte[0]); }
    private int events() { return jdbc.queryForObject("SELECT count(*) FROM gateway_kyc_provider_events",Integer.class); }

    @Test void failedProfileWriteRollsBackReceiptAndTheSameEventCanRetry() {
        jdbc.execute("ALTER TABLE gateway_user_kyc_profiles ADD CONSTRAINT mvc_result_fault CHECK(status<>'VERIFIED') NOT VALID");
        try { assertThatThrownBy(this::callback).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(events()).isZero();assertThat(profiles.find(42).status()).isEqualTo("PENDING"); }
        finally { jdbc.execute("ALTER TABLE gateway_user_kyc_profiles DROP CONSTRAINT mvc_result_fault"); }
        assertThat(callback().status()).isEqualTo("VERIFIED");assertThat(events()).isEqualTo(1);
    }
    @Test void duplicateConcurrentCallbacksApplyOneCurrentSessionResult() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<KycProfile>>();
            for(int i=0;i<6;i++)results.add(executor.submit(()->{start.await();return callback();}));
            start.countDown();for(var result:results)assertThat(result.get(5,TimeUnit.SECONDS).status()).isEqualTo("VERIFIED");
        }
        assertThat(events()).isEqualTo(1);assertThat(lockedReads.get()).isEqualTo(1);
    }
    @Test void resubmissionWaitsUntilTheCheckedOldSessionResultCommits() throws Exception {
        pauseRead=true;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var callback=executor.submit(this::callback);Future<KycProfile> submission=null;
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                submission=executor.submit(()->new ComplianceKycRepository(independent).submit(42,request("session-new"),"[]",Instant.now()));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean blocked=false;
                while(System.nanoTime()<deadline && !submission.isDone()) {
                    blocked=admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='mvc_kyc_resubmitter' AND wait_event_type='Lock'",Integer.class)>0;
                    if(blocked)break;Thread.sleep(10);
                }
                assertThat(blocked).as("new application waits while the old callback validates and updates its locked profile").isTrue();
            }finally{release.countDown();callback.get(5,TimeUnit.SECONDS);}
            assertThat(submission.get(5,TimeUnit.SECONDS).status()).isEqualTo("PENDING");
        }
        assertThat(profiles.find(42).providerReference()).isEqualTo("session-new");assertThat(profiles.find(42).status()).isEqualTo("PENDING");
    }
    @Test void simulationDoesNotApplyAnOldDecisionAfterItsSnapshotWasReplaced() {
        afterEvent=()->new ComplianceKycRepository(independent).submit(42,request("session-new"),"[]",Instant.now());
        assertThatThrownBy(()->service.completeKycSimulation("auth","APPROVED")).isInstanceOf(IllegalStateException.class).hasMessageContaining("session changed");
        assertThat(profiles.find(42).providerReference()).isEqualTo("session-new");assertThat(profiles.find(42).status()).isEqualTo("PENDING");assertThat(events()).isZero();
    }

    @Test void newerPendingAndOlderFinalCallbacksCannotReplaceTheLatestVerifiedDecision() {
        Instant time=Instant.now();
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-old","42","approved","VERIFIED",null,time,null, null)));
        assertThat(callback().status()).isEqualTo("VERIFIED");
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-old","42","late-pending","PENDING",null,time.plusSeconds(1),null, null)));
        assertThat(callback().status()).isEqualTo("VERIFIED");
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-old","42","old-rejection","REJECTED","old",time.minusSeconds(1),null, null)));
        assertThat(callback().status()).isEqualTo("VERIFIED");
        assertThat(profiles.find(42).faceVerificationStatus()).isEqualTo("VERIFIED");
    }

    @Test void expiredVerifiedProfileCanSubmitAgainButValidProfileCannot() {
        profiles.upsert(42,42,new com.surprising.gateway.provider.auth.model.ComplianceModels.KycUpdateRequest(
                "STANDARD","VERIFIED","SG","PASSPORT","SUMSUB","session-old",null,Instant.now().plusSeconds(600)),Instant.now());
        assertThatThrownBy(()->service.submitUserKyc("auth",request(null))).hasMessageContaining("already pending or complete");
        profiles.updateExpiry(42,Instant.now().minusSeconds(1));
        var renewed=service.submitUserKyc("auth",request(null));
        assertThat(renewed.profile().status()).isEqualTo("PENDING");
        assertThat(profiles.find(42).providerReference()).isEqualTo("session-new");
        verify(providers,times(1)).start(eq(42L),any(),anyList());
    }

    @Test void concurrentFirstSubmissionsStartExactlyOneProviderSession() throws Exception {
        jdbc.execute("DELETE FROM gateway_user_kyc_profiles");
        var start=new CountDownLatch(1);var accepted=new AtomicInteger();var rejected=new AtomicInteger();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(()->{
                start.await();
                try { service.submitUserKyc("auth",request(null));accepted.incrementAndGet(); }
                catch(IllegalStateException expected) { rejected.incrementAndGet(); }
                return null;
            }));
            start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertThat(accepted.get()).isEqualTo(1);assertThat(rejected.get()).isEqualTo(5);
        verify(providers,times(1)).start(eq(42L),any(),anyList());
    }

    @Test void providerEvidenceExpiryCapsVerificationAndExpiredEvidenceCannotGrantAccess() {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for(Instant expiry:List.of(now.plusSeconds(90),now.minusSeconds(1))) {
            profiles.submit(42,request("session-old"),"[]",now);
            when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                    new KycProvider.KycProviderResult("session-old","42",expiry.toString(),"VERIFIED",null,now,null,expiry)));
            var result=callback();
            assertThat(result.status()).isEqualTo(expiry.isAfter(now)?"VERIFIED":"EXPIRED");
            assertThat(result.expiresAt()).isEqualTo(expiry);
        }
    }

    @Test void manualReviewRejectsOldCallbacksButANewerProviderRejectionRevokesApproval() {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        profiles.upsert(42,42,new com.surprising.gateway.provider.auth.model.ComplianceModels.KycUpdateRequest(
                "STANDARD","VERIFIED","SG","PASSPORT","SUMSUB","session-old",null,now.plusSeconds(600)),now);
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-old","42","old-final","REJECTED","old",now.minusSeconds(1),null,null)));
        assertThat(callback().status()).isEqualTo("VERIFIED");
        when(providers.verifyCallback(eq("SUMSUB"),any(),any())).thenReturn(Optional.of(
                new KycProvider.KycProviderResult("session-old","42","new-final","REJECTED","new risk decision",now.plusSeconds(1),null,null)));
        assertThat(callback().status()).isEqualTo("REJECTED");
        assertThat(profiles.find(42).faceVerificationStatus()).isEqualTo("FAILED");
    }
}
