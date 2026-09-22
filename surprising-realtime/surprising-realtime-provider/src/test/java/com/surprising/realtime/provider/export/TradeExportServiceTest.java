package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

class TradeExportServiceTest {
    @TempDir Path temp;

    @Test void retriesOnItsOwnThreadAndJoinsBeforeStopping() throws Exception {
        var exporter = mock(CommittedTradeExporter.class);
        var attempts = new AtomicInteger();
        var active = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(Thread.currentThread().getName()).isEqualTo("committed-trade-export");
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Kafka unavailable");
            call.<java.util.function.Consumer<Boolean>>getArgument(1).accept(true);
            active.countDown();
            while (call.<AtomicBoolean>getArgument(0).get()) LockSupport.parkNanos(1_000_000);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            stopped.countDown();
            return null;
        }).when(exporter).run(any(), any(), anyLong());
        var service = new TradeExportService(config(), exporter);
        assertThat(service.health().getStatus()).isEqualTo(Status.DOWN);
        try {
            service.start();
            service.start(); // Spring must never create two exporters for the same checkpoint.
            assertThat(active.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.health().getStatus()).isEqualTo(Status.UP);
            assertThat(service.isRunning()).isTrue();
        } finally {
            service.stop();
        }
        assertThat(stopped.getCount()).isZero();
        assertThat(service.isRunning()).isFalse();
        assertThat(service.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test void failedExportIsVisibleWithoutStoppingTheApplication() throws Exception {
        var exporter = mock(CommittedTradeExporter.class);
        doThrow(new IllegalStateException("invalid checkpoint")).when(exporter).run(any(), any(), anyLong());
        var service = new TradeExportService(config(), exporter);
        try {
            service.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"RETRYING".equals(service.health().getDetails().get("state")) && System.nanoTime() < until)
                Thread.sleep(10);
            assertThat(service.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(service.health().getDetails()).containsEntry("state", "RETRYING");
            assertThat(service.isRunning()).isTrue();
        } finally { service.stop(); }
    }

    private TradeExportProperties config() {
        return new TradeExportProperties(temp, "driver", "aeron:ipc", temp.resolve("checkpoint"),
                0, null, Duration.ofSeconds(1), Duration.ofMillis(100));
    }
}
