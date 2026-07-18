package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PositionCacheAccelerationWorkerTest {

    @Test
    void coalescesNewerSnapshotsForAHotPositionWithoutBlockingSubmitter() throws Exception {
        RedisPositionCache cache = mock(RedisPositionCache.class);
        PositionCacheMetrics metrics = mock(PositionCacheMetrics.class);
        AccountProperties properties = new AccountProperties();
        properties.getPositionCache().setAcceleratorThreads(1);
        properties.getPositionCache().setAcceleratorQueueCapacity(8);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondApplied = new CountDownLatch(1);
        List<Long> appliedRevisions = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            PositionCacheEvent event = invocation.getArgument(0);
            appliedRevisions.add(event.revision());
            if (event.revision() == 1L) {
                firstStarted.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                secondApplied.countDown();
            }
            return true;
        }).when(cache).apply(any(PositionCacheEvent.class), eq(false));
        PositionCacheAccelerationWorker worker =
                new PositionCacheAccelerationWorker(cache, metrics, properties);
        try {
            worker.submit(event(1L));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            worker.submit(event(2L));
            worker.submit(event(3L));
            releaseFirst.countDown();

            assertThat(secondApplied.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(appliedRevisions).containsExactly(1L, 3L);
            verify(metrics, atLeastOnce()).recordAcceleratorCoalesced();
        } finally {
            worker.shutdown();
        }
    }

    private PositionCacheEvent event(long revision) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(
                revision, ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 7L,
                MarginMode.CROSS, PositionSide.NET, 3L, 60_000L, 180_000L, 100L,
                "USDT", 20_000L, now, now, revision);
    }
}
