package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 验证用户状态发布在锁内重读：并发同步不会把已冻结用户发成 ACTIVE。
 */
class AuthStateSyncPublishLockTest {

    @Test
    void concurrentReloadDoesNotPublishStaleActiveAfterFreeze() throws Exception {
        var db = new LockedUserDatabase();
        db.upsert(7, "NORMAL");
        var fixture = new AuthStateFixture();
        var sync = new AuthStateSync(db.jdbc(), fixture.state, new GatewayProperties(), db.transactions());

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        db.afterLock = () -> {};
        db.afterRead = () -> {
            if (firstLocked.getCount() == 1) {
                firstLocked.countDown();
                try {
                    if (!releaseFirst.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first publisher was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
        };
        Thread stale = new Thread(() -> sync.reloadUser(7), "stale-active-publisher");
        stale.start();
        assertThat(firstLocked.await(3, TimeUnit.SECONDS)).isTrue();

        db.upsert(7, "FROZEN");
        Thread fresh = new Thread(() -> sync.reloadUser(7), "frozen-publisher");
        fresh.start();
        Thread.sleep(80);
        releaseFirst.countDown();
        stale.join(3_000);
        fresh.join(3_000);
        assertThat(stale.isAlive()).isFalse();
        assertThat(fresh.isAlive()).isFalse();
        assertThat(fixture.state.user(7).status()).isEqualTo("FROZEN");
        assertThat(db.lockWaiters.get()).isGreaterThan(0);
    }

    @Test
    void publishedStateIncludesFrozenSubAccountRelation() {
        var db = new LockedUserDatabase();
        db.upsert(9, "NORMAL");
        db.relations.put(9L, new String[]{"FROZEN", "TRADE_SPOT"});
        var fixture = new AuthStateFixture();
        var sync = new AuthStateSync(db.jdbc(), fixture.state, new GatewayProperties(), db.transactions());
        assertThat(sync.reloadUser(9)).isPresent();
        UserState published = fixture.state.user(9);
        assertThat(published.status()).isEqualTo("NORMAL");
        assertThat(published.subAccountStatus()).isEqualTo("FROZEN");
        assertThat(published.subAccountFrozen()).isTrue();
    }

    static final class LockedUserDatabase {
        private final Map<Long, String> users = new ConcurrentHashMap<>();
        final Map<Long, String[]> relations = new ConcurrentHashMap<>();
        private final ReentrantLock userLock = new ReentrantLock();
        private final ThreadLocal<List<ReentrantLock>> held = ThreadLocal.withInitial(ArrayList::new);
        final AtomicInteger lockWaiters = new AtomicInteger();
        volatile Runnable afterLock = () -> {};
        volatile Runnable afterRead = () -> {};

        void upsert(long userId, String status) {
            users.put(userId, status);
        }

        PlatformTransactionManager transactions() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }
                @Override
                public void commit(TransactionStatus status) { unlockHeld(); }
                @Override
                public void rollback(TransactionStatus status) { unlockHeld(); }
            };
        }

        private void lockUser(long userId) {
            if (userLock.isHeldByCurrentThread()) return;
            if (userLock.isLocked()) lockWaiters.incrementAndGet();
            userLock.lock();
            held.get().add(userLock);
            afterLock.run();
        }

        private void unlockHeld() {
            List<ReentrantLock> locks = held.get();
            for (int i = locks.size() - 1; i >= 0; i--) {
                ReentrantLock lock = locks.get(i);
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
            locks.clear();
        }

        JdbcTemplate jdbc() {
            return new JdbcTemplate() {
                @Override
                public void query(String sql, RowCallbackHandler rch, @org.springframework.lang.Nullable Object... args) {
                    if (sql.contains("FOR UPDATE") && sql.contains("gateway_users")) {
                        if (sql.contains("IN (")) {
                            for (Object arg : args) lockUser((Long) arg);
                        } else {
                            lockUser((Long) args[0]);
                        }
                    }
                }

                @Override
                public <T> List<T> query(String sql, RowMapper<T> rowMapper, @org.springframework.lang.Nullable Object... args) {
                    try {
                        if (sql.contains("gateway_sub_account_relations")) {
                            long userId = (Long) args[0];
                            String[] relation = relations.get(userId);
                            if (relation == null) return List.of();
                            ResultSet rs = result(relation[0], relation[1]);
                            return List.of(rowMapper.mapRow(rs, 0));
                        }
                        if (sql.contains("FROM gateway_users") && sql.contains("user_id = ?")) {
                            long userId = (Long) args[0];
                            String status = users.get(userId);
                            if (status == null) return List.of();
                            ResultSet rs = result("user" + userId, "u" + userId + "@example.test", "User", Instant.EPOCH, status);
                            List<T> mapped = List.of(rowMapper.mapRow(rs, 0));
                            afterRead.run();
                            return mapped;
                        }
                        return List.of();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public <T> List<T> queryForList(String sql, Class<T> elementType, @org.springframework.lang.Nullable Object... args) {
                    return List.of();
                }

                @Override
                public <T> T queryForObject(String sql, Class<T> requiredType, @org.springframework.lang.Nullable Object... args) {
                    if (requiredType == Boolean.class) return requiredType.cast(Boolean.FALSE);
                    return null;
                }
            };
        }

        private static ResultSet result(Object... values) throws Exception {
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            for (int i = 0; i < values.length; i++) {
                int idx = i + 1;
                Object value = values[i];
                if (value instanceof String s) org.mockito.Mockito.when(rs.getString(idx)).thenReturn(s);
                else if (value instanceof Instant instant)
                    org.mockito.Mockito.when(rs.getTimestamp(idx)).thenReturn(java.sql.Timestamp.from(instant));
                else if (value instanceof Long l) org.mockito.Mockito.when(rs.getLong(idx)).thenReturn(l);
            }
            return rs;
        }
    }
}
