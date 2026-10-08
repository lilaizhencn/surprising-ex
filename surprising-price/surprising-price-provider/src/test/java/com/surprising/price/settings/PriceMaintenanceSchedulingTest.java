package com.surprising.price.settings;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.task.IndexPriceMaintenanceTask;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.task.MarkPriceMaintenanceTask;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriceMaintenanceSchedulingTest {
    @Test
    void schedulesActualIntervalsAndReplacesOnlyChangedDeadline() {
        var h = new Harness();
        h.owner.start();
        assertThat(h.scheduled).hasSize(5);
        for (int i = 0; i < 5; i++) h.scheduled.get(i).task().run();
        assertThat(h.scheduled).hasSize(10);
        verify(h.indexTasks).calculateAndPublish();
        assertThat(h.scheduled.get(6).when()).isAfter(Instant.now().plusSeconds(3000));
        h.index.getCalculation().setPollDelayMs(50);
        h.owner.settingsInstalled(null);
        assertThat(h.scheduled).hasSize(11);
        verify(h.scheduled.get(8).future()).cancel(false);
        h.scheduled.get(8).task().run(); // A cancelled callback racing with rescheduling.
        verify(h.indexTasks, times(1)).calculateAndPublish();
        h.scheduled.get(10).task().run();
        verify(h.indexTasks, times(2)).calculateAndPublish();
        h.owner.stop();
        int stopped = h.scheduled.size();
        h.scheduled.getLast().task().run();
        assertThat(h.scheduled).hasSize(stopped);
    }

    @Test
    void changingIntervalWhileRunningNeverStartsOverlappingWork() throws Exception {
        var h = new Harness();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(2, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(h.indexTasks).calculateAndPublish();
        h.owner.start();
        Thread worker = Thread.ofPlatform().start(h.scheduled.get(3).task());
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            h.index.getCalculation().setPollDelayMs(50);
            h.owner.settingsInstalled(null);
            assertThat(h.scheduled).hasSize(5);
            verify(h.indexTasks, times(1)).calculateAndPublish();
            h.owner.stop();
            h.owner.start();
            assertThat(h.scheduled).hasSize(9); // The running job waits for completion.
            release.countDown();
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(h.scheduled).hasSize(10);
        } finally {
            release.countDown();
            worker.join(2000);
            h.owner.stop();
        }
    }

    @Test
    void failuresRescheduleAndShutdownPreventsFurtherInvocations() {
        var h = new Harness();
        doThrow(new IllegalStateException("feed unavailable")).when(h.indexTasks).calculateAndPublish();
        h.owner.start();
        assertThatThrownBy(h.scheduled.get(3).task()::run).hasMessage("feed unavailable");
        assertThat(h.scheduled).hasSize(6);
        h.owner.stop();
        h.scheduled.getLast().task().run();
        verify(h.indexTasks, times(1)).calculateAndPublish();
    }

    private record Scheduled(Runnable task, Instant when, ScheduledFuture<?> future) {}
    private static final class Harness {
        final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();
        final IndexPriceMaintenanceTask indexTasks = mock(IndexPriceMaintenanceTask.class);
        final MarkPriceMaintenanceTask markTasks = mock(MarkPriceMaintenanceTask.class);
        final IndexPriceProperties index = new IndexPriceProperties();
        final PriceMaintenanceScheduling owner;
        Harness() {
            var scheduler = mock(TaskScheduler.class);
            when(scheduler.getClock()).thenReturn(Clock.systemUTC());
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
                var future = mock(ScheduledFuture.class);
                scheduled.add(new Scheduled(call.getArgument(0), call.getArgument(1), future));
                return future;
            });
            owner = new PriceMaintenanceScheduling(scheduler, indexTasks, markTasks, index, new MarkPriceProperties());
        }
    }
}
