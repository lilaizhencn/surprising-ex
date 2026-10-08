package com.surprising.derivatives.lifecycle;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.task.FundingMaintenanceTask;
import org.springframework.beans.factory.ObjectProvider;
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

/** Owns lifecycle deadlines; funding keeps its separate scheduler and perpetual-only activation. */
@Component
public final class LifecycleMaintenanceScheduling implements SmartLifecycle {
    private final List<Job> jobs = new ArrayList<>();
    private volatile boolean running;

    public LifecycleMaintenanceScheduling(@Qualifier("taskScheduler") TaskScheduler scheduler,
            DerivativesLifecycleMaintenanceTask tasks, LifecycleBusinessSettingsService settings,
            ObjectProvider<FundingMaintenanceTask> fundingTasks, ObjectProvider<FundingProperties> fundingProperties,
            @Qualifier("fundingScheduler") ObjectProvider<TaskScheduler> fundingScheduler) {
        jobs.add(new Job(scheduler, tasks::processLiquidationWork, () -> settings.current().settings().liquidation().delayMs()));
        jobs.add(new Job(scheduler, tasks::coverInsuranceDeficits, () -> settings.current().settings().insurance().scanDelayMs()));
        jobs.add(new Job(scheduler, tasks::processAdlDeficits, () -> settings.current().settings().adl().scanDelayMs()));
        var funding = fundingTasks.getIfAvailable();
        if (funding != null) {
            var properties = fundingProperties.getObject();
            var separateScheduler = fundingScheduler.getObject();
            jobs.add(new Job(separateScheduler, funding::publishRates, () -> properties.getCalculation().getPublishDelayMs()));
            jobs.add(new Job(separateScheduler, funding::settleDueRates, () -> properties.getSettlement().getSettleDelayMs()));
        }
    }
    @EventListener public void settingsInstalled(LifecycleBusinessSettingsService.Settings settings) {
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
