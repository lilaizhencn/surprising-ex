package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountSettlementConcurrencyGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AccountProperties.class)
            .withBean(AccountSettlementConcurrencyGuard.class);

    @Test
    void serializesTradesThatShareAUserAcrossSymbols() throws Exception {
        AccountSettlementConcurrencyGuard guard = new AccountSettlementConcurrencyGuard(64);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        try {
            Future<Boolean> first = executor.submit(() -> guard.withTradeUserLocks(
                    trade(1001L, 2001L, "BTC-USDT"), () -> {
                        recordActive(active, maxActive);
                        firstEntered.countDown();
                        await(releaseFirst);
                        active.decrementAndGet();
                        return true;
                    }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return guard.withTradeUserLocks(trade(1001L, 2002L, "ETH-USDT"), () -> {
                    recordActive(active, maxActive);
                    active.decrementAndGet();
                    return true;
                });
            });
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50L);
            assertThat(second.isDone()).isFalse();
            assertThat(maxActive).hasValue(1);

            releaseFirst.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maxActive).hasValue(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void locksParticipantsInStableOrderToAvoidDeadlock() throws Exception {
        AccountSettlementConcurrencyGuard guard = new AccountSettlementConcurrencyGuard(64);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                return guard.withTradeUserLocks(trade(1001L, 2001L, "BTC-USDT"), () -> {
                    sleep(25L);
                    return true;
                });
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                return guard.withTradeUserLocks(trade(2001L, 1001L, "ETH-USDT"), () -> {
                    sleep(25L);
                    return true;
                });
            });

            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidStripeCount() {
        assertThatThrownBy(() -> new AccountSettlementConcurrencyGuard(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userLockStripes must be positive");
    }

    @Test
    void createsGuardBeanThroughSpringConstructorInjection() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(AccountSettlementConcurrencyGuard.class));
    }

    private static void recordActive(AtomicInteger active, AtomicInteger maxActive) {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for latch", ex);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sleeping", ex);
        }
    }

    private static MatchTradeEvent trade(long takerUserId, long makerUserId, String symbol) {
        return new MatchTradeEvent(
                9201L,
                9101L,
                symbol,
                9002L,
                1L,
                takerUserId,
                OrderSide.BUY,
                9001L,
                1L,
                makerUserId,
                5L,
                2L,
                600_000L,
                3L,
                true,
                false,
                Instant.parse("2026-07-01T00:00:00Z"));
    }
}
