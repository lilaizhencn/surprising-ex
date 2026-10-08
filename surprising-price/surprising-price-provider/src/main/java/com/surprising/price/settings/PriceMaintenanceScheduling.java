package com.surprising.price.settings;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.task.IndexPriceMaintenanceTask;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.task.MarkPriceMaintenanceTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** Schedules price maintenance at business deadlines on the existing price scheduler. */
@Component
public final class PriceMaintenanceScheduling implements SmartLifecycle {
    private final List<Job> jobs = new ArrayList<>();
    private volatile boolean running;

    public PriceMaintenanceScheduling(@Qualifier("taskScheduler") TaskScheduler scheduler,
            IndexPriceMaintenanceTask indexTasks, MarkPriceMaintenanceTask markTasks,
            IndexPriceProperties index, MarkPriceProperties mark) {
        jobs.add(new Job(scheduler, indexTasks::refreshExternalConnections, () -> index.getWebSocket().getRefreshDelayMs()));
        jobs.add(new Job(scheduler, indexTasks::refreshFiatRates, () -> index.getFiat().getRefreshDelayMs()));
        jobs.add(new Job(scheduler, indexTasks::refreshStableCoinRate, () -> index.getFiat().getStableCoin().getRefreshDelayMs()));
        jobs.add(new Job(scheduler, indexTasks::calculateAndPublish, () -> index.getCalculation().getPollDelayMs()));
        jobs.add(new Job(scheduler, markTasks::publishMarkPrices, () -> mark.getCalculation().getPublishIntervalMs()));
    }
    @EventListener public void settingsInstalled(PriceBusinessSettingsService.Snapshot snapshot) {
        jobs.forEach(Job::refresh);
    }

    @Override public synchronized void start() {
        if (running) return;
        jobs.forEach(Job::start);
        running = true;
    }
    @Override public synchronized void stop() {
        running = false;
        jobs.forEach(Job::stop);
    }
    @Override public boolean isRunning() { return running; }

    /** One task owns its deadline/future. Configuration changes never overlap a running invocation. */
    private static final class Job {
        private final TaskScheduler scheduler;
        private final Runnable action;
        private final LongSupplier delayMillis;
        private ScheduledFuture<?> future;
        private long periodMillis, lastStartedNanos, lastCompletedNanos, generation;
        private boolean stopped = true, executing;

        Job(TaskScheduler scheduler, Runnable action, LongSupplier delayMillis) {
            this.scheduler = scheduler;
            this.action = action;
            this.delayMillis = delayMillis;
        }

        synchronized void start() {
            if (!stopped) return;
            periodMillis = positiveDelay();
            stopped = false;
            lastStartedNanos = 0;
            if (!executing) schedule();
        }

        synchronized void refresh() {
            long period = positiveDelay();
            if (period == periodMillis) return;
            periodMillis = period;
            if (!stopped && !executing) {
                if (future != null) future.cancel(false);
                schedule();
            }
        }

        synchronized void stop() {
            stopped = true;
            generation++;
            if (future != null) future.cancel(false);
        }

        private long positiveDelay() {
            long value = delayMillis.getAsLong();
            if (value <= 0) throw new IllegalArgumentException("maintenance interval must be positive");
            return value;
        }

        private void schedule() {
            long token = ++generation;
            long now = System.nanoTime();
            long remaining = lastStartedNanos == 0 ? 0
                    : Math.max(TimeUnit.MILLISECONDS.toNanos(periodMillis) - (now - lastStartedNanos),
                            TimeUnit.MILLISECONDS.toNanos(25) - (now - lastCompletedNanos));
            remaining = Math.max(0, remaining);
            future = scheduler.schedule(() -> run(token), scheduler.getClock().instant().plusNanos(remaining));
        }

        private void run(long token) {
            synchronized (this) {
                if (stopped || token != generation) return;
                executing = true;
                lastStartedNanos = System.nanoTime();
            }
            try {
                action.run();
            } finally {
                synchronized (this) {
                    executing = false;
                    lastCompletedNanos = System.nanoTime();
                    if (!stopped) schedule();
                }
            }
        }
    }
}
