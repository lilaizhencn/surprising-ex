package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.trading.api.utils.AdminCursorPage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LoginSessionRepositoryTest {
    ValkeySessionFixture fixture;
    @BeforeEach void start() { fixture=new ValkeySessionFixture();fixture.user(42,false); }
    @AfterEach void stop() { fixture.close(); }
    long create(String device) { return fixture.sessions.create(42,Duration.ofDays(7),"browser","127.0.0.1",device,Instant.now()); }
    AuthPageQuery page(int limit,AdminCursorPage.Cursor cursor) { return new AuthPageQuery(limit,cursor,AuthPageQuery.Order.CREATED_DESC); }

    @Test void realValkeyPermanentIdentityAndCrossGatewayInvalidation() throws Exception {
        var secondBackend=new ValkeyAuthStateBackend(fixture.redis);
        try {
            var second=new AuthState(secondBackend,fixture.properties);
            var user=new UserState(42,"telegram",null,null,Instant.EPOCH,"NORMAL",List.of("USER"),List.of(),Set.of(),Set.of(),false,true);
            fixture.state.putUser(user);fixture.state.putTelegramIdentity("123",42);
            long id=create("device");
            assertThat(second.user(42).status()).isEqualTo("NORMAL");
            assertThat(second.telegramUserId("123")).isEqualTo(42);
            assertThat(second.session(id)).isNotNull();
            assertThat(fixture.redis.getExpire("auth:telegram:123")).isEqualTo(-1);
            assertThat(fixture.redis.getExpire("auth:user:42")).isEqualTo(-1);
            fixture.state.putUser(new UserState(42,"telegram",null,null,Instant.EPOCH,"FROZEN",List.of("USER"),List.of(),Set.of(),Set.of(),false,true));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(second.user(42).status()).isEqualTo("FROZEN"));
            fixture.sessions.revokeActive(id,Instant.now());
            fixture.state.deleteTelegramIdentity("123");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(()->{
                assertThat(second.session(id)).isNull();assertThat(second.telegramUserId("123")).isNull();
            });
        } finally { secondBackend.stop(); }
    }

    @Test void renewalThrottleIsAtomicAcrossGateways() {
        long id=create("device");
        String key="auth:session:"+id;
        long now=Instant.now().toEpochMilli();
        fixture.redis.opsForHash().put(key,"renewedAt",Long.toString(now-700_000));
        assertThat(fixture.backend.renewSession(key,"auth:user:42",Duration.ofDays(7),now,Duration.ofMinutes(10))).isTrue();
        assertThat(fixture.backend.renewSession(key,"auth:user:42",Duration.ofDays(7),now+5000,Duration.ofMinutes(10))).isTrue();
        assertThat(fixture.redis.opsForHash().get(key,"renewedAt")).isEqualTo(Long.toString(now));
    }

    @Test void expiredUserIndexEntriesCannotPreventLoginBehindGlobalCleanupBacklog() {
        for(int i=1;i<=100;i++) fixture.redis.opsForZSet().add("auth:sessions:user:42","42:"+i,i);
        long id=create("new-device");
        assertThat(fixture.state.sessionExists(id)).isTrue();
        assertThat(fixture.redis.opsForZSet().zCard("auth:sessions:user:42")).isEqualTo(1);
    }

    @Test void indexedPagesPreserveOwnerCursorAndMetadataWithoutPostgresReads() {
        long a=create("a"),b=create("b");
        fixture.user(99,false);fixture.sessions.create(99,Duration.ofHours(8),"admin","127.0.0.2","c",Instant.now());
        var first=fixture.sessions.findPage(42L,true,page(1,null));
        assertThat(first.items()).hasSize(1);assertThat(first.items().getFirst().sessionId()).isEqualTo(b);
        assertThat(first.items().getFirst().deviceId()).isEqualTo("b");
        var second=fixture.sessions.findPage(42L,true,page(1,AdminCursorPage.decodeCursor(first.nextCursor())));
        assertThat(second.items().getFirst().sessionId()).isEqualTo(a);assertThat(second.hasMore()).isFalse();
        assertThat(fixture.sessions.findPage(null,true,page(10,null)).items()).hasSize(3);
        assertThat(fixture.sessions.deviceIdForSession(99,a)).isEmpty();
        assertThat(fixture.state.session(a).authenticatedAtMillis()).isPositive();
        verifyNoInteractions(fixture.logs);
    }
    @Test void transactionRollbackCannotCreateUsableSession() {
        TransactionSynchronizationManager.initSynchronization();
        long id;
        try {
            id=create("device");assertThat(fixture.state.sessionExists(id)).isFalse();
            for(var callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertThat(fixture.state.sessionExists(id)).isFalse();
        assertThat(fixture.sessions.findPage(null,true,page(10,null)).items()).isEmpty();
    }
    @Test void transactionCommitCreatesSessionOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        long id;
        try {
            id=create("device");assertThat(fixture.state.sessionExists(id)).isFalse();
            for(var callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertThat(fixture.state.sessionExists(id)).isTrue();
    }
    @Test void revokeAfterSlidingWindowAndConcurrentRenewNeverResurrects() throws Exception {
        long id=create("device");
        fixture.redis.opsForHash().put("auth:session:"+id,"renewedAt",Long.toString(Instant.now().minus(Duration.ofDays(8)).toEpochMilli()));
        assertThat(fixture.sessions.revokeActiveForUserSession(99,id,Instant.now())).isZero();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new java.util.ArrayList<Callable<Object>>();
            for(int i=0;i<50;i++) jobs.add(()->fixture.state.session(id));
            jobs.add(()->fixture.sessions.revokeActive(id,Instant.now()));
            for(var result:pool.invokeAll(jobs)) result.get();
        }
        assertThat(fixture.state.sessionExists(id)).isFalse();
        assertThat(fixture.sessions.findPage(42L,true,page(10,null)).items()).isEmpty();
        verify(fixture.logs).append(eq(42L),eq("REVOKE_session"),anyList(),any());
    }
    @Test void deviceAndIpRevocationStayOwnerScopedAndClearCaches() {
        long keep=create("keep"),a=create("drop"),b=create("drop");
        fixture.user(99,false);long other=fixture.sessions.create(99,Duration.ofDays(7),"browser","127.0.0.1","drop",Instant.now());
        fixture.state.session(a);
        assertThat(fixture.sessions.revokeActiveForDevice(42,"drop",Instant.now())).isEqualTo(2);
        assertThat(fixture.state.session(a)).isNull();assertThat(fixture.state.session(b)).isNull();
        assertThat(fixture.state.session(other)).isNotNull();
        assertThat(fixture.sessions.revokeActiveForIp(42,"127.0.0.1",Instant.now())).isEqualTo(1);
        assertThat(fixture.state.session(keep)).isNull();
    }
    @Test void expiryPrunesAllIndicesAndCannotRestoreDeletedSessions() {
        long id=fixture.sessions.create(42,Duration.ofMillis(1),"b","127.0.0.1","x",Instant.now());
        org.awaitility.Awaitility.await().until(()->!fixture.redis.hasKey("auth:session:"+id));
        fixture.sessions.pruneExpired();
        assertThat(fixture.redis.opsForZSet().zCard("auth:sessions")).isZero();
        assertThat(fixture.redis.opsForZSet().zCard("auth:sessions:user:42")).isZero();
        assertThat(fixture.redis.opsForZSet().zCard("auth:sessions:expiry")).isZero();
    }
    @Test void tooManySessionsAreRejectedWithoutOrphanIndices() {
        for(int i=0;i<100;i++) create("d"+i);
        assertThatThrownBy(()->create("extra")).isInstanceOf(AuthStateUnavailableException.class);
        assertThat(fixture.redis.opsForZSet().zCard("auth:sessions:user:42")).isEqualTo(100);
    }
}
