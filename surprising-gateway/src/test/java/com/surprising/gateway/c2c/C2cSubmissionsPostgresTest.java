package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cSubmissionsPostgresTest {
    private C2cDatabaseFixture db;
    private C2cSubmissions rows;
    private final String key="original-request-00001",hash="a".repeat(64);
    @BeforeEach void setup() throws Exception {
        db=new C2cDatabaseFixture();rows=new C2cSubmissions(db.jdbc);
        db.jdbc.update("INSERT INTO gateway_users(user_id) VALUES(11),(12)");
    }
    @AfterEach void cleanup() {if(db!=null)db.close();}

    @Test void queryingAnUnknownRequestNeverSubmitsOrCancelsIt() {
        assertThat(rows.get(11,key,false)).isNull();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_submissions",Integer.class)).isZero();
        rows.admit(11,key,"AD_PUBLISH",hash);
        assertThat(rows.get(12,key,false)).isNull();
        assertThatThrownBy(()->rows.admit(11,key,"ORDER_CREATE",hash)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->rows.admit(11,key,"AD_PUBLISH","b".repeat(64))).isInstanceOf(IllegalStateException.class);
    }

    @Test void stopBeforeAdmissionFencesALateOriginalPost() {
        assertThat(db.transaction(()->rows.stop(11,key,"AD_PUBLISH",hash)).status()).isEqualTo("REJECTED");
        assertThat(rows.admit(11,key,"AD_PUBLISH",hash).status()).isEqualTo("REJECTED");
        assertThatThrownBy(()->db.transaction(()->rows.begin(11,key,"AD_PUBLISH",hash))).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isZero();
    }

    @Test void stopRacingACommittingBusinessTransactionReturnsTheAcceptedResult() throws Exception {
        rows.admit(11,key,"AD_PUBLISH",hash);UUID result=UUID.randomUUID();
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var business=pool.submit(()->db.transaction(()->{
                rows.begin(11,key,"AD_PUBLISH",hash);entered.countDown();await(finish);
                rows.succeed(11,key,result);return null;
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var stopping=pool.submit(()->db.transaction(()->rows.stop(11,key,"AD_PUBLISH",hash)));
            finish.countDown();business.get(10,TimeUnit.SECONDS);
            var resolved=stopping.get(10,TimeUnit.SECONDS);
            assertThat(resolved.status()).isEqualTo("SUCCEEDED");assertThat(resolved.resultId()).isEqualTo(result);
        } finally {finish.countDown();}
        rows.reject(11,key,"AD_PUBLISH",hash,"REQUEST_FAILED","late error");
        assertThat(rows.get(11,key,false).status()).isEqualTo("SUCCEEDED");
    }

    @Test void recoveryExpirySkipsARealInFlightTransactionButRejectsUnstartedWork() throws Exception {
        rows.admit(11,key,"AD_PUBLISH",hash);
        rows.admit(12,key,"AD_PUBLISH",hash);
        db.jdbc.update("UPDATE c2c_submissions SET created_at=now()-interval '3 minutes'");
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var business=pool.submit(()->db.transaction(()->{
                rows.begin(11,key,"AD_PUBLISH",hash);entered.countDown();await(finish);
                rows.succeed(11,key,UUID.randomUUID());return null;
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            db.transaction(()->{rows.expireUnstarted(20);return null;});
            assertThat(rows.get(11,key,false).status()).isEqualTo("PROCESSING");
            assertThat(rows.get(12,key,false).status()).isEqualTo("REJECTED");
            finish.countDown();business.get(10,TimeUnit.SECONDS);
            assertThat(rows.get(11,key,false).status()).isEqualTo("SUCCEEDED");
        } finally {finish.countDown();}
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("test business gate timed out");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
}
