package com.surprising.aeron.exporter;

import java.util.Objects;

public final class AdaptiveExportLoop {

    public static final long MIN_IDLE_MILLIS = 25L;
    public static final long MAX_IDLE_MILLIS = 1_000L;

    private final ExportCycle cycle;
    private final Sleeper sleeper;
    private long idleMillis;

    public AdaptiveExportLoop(ExportCycle cycle, Sleeper sleeper) {
        this(cycle, sleeper, MIN_IDLE_MILLIS);
    }

    public AdaptiveExportLoop(ExportCycle cycle, Sleeper sleeper, long initialIdleMillis) {
        this.cycle = Objects.requireNonNull(cycle, "cycle");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (initialIdleMillis <= 0) {
            throw new IllegalArgumentException("initial idle delay must be positive");
        }
        idleMillis = Math.max(MIN_IDLE_MILLIS, Math.min(initialIdleMillis, MAX_IDLE_MILLIS));
    }

    public ReliableCoreExporter.ExportCycleResult runOnce() throws Exception {
        ReliableCoreExporter.ExportCycleResult result = cycle.run();
        if (result.publishedEvents() > 0 || result.status().pendingCount() > 0) {
            idleMillis = MIN_IDLE_MILLIS;
            return result;
        }
        long delay = idleMillis;
        sleeper.sleep(delay);
        idleMillis = nextIdleMillis(delay, MAX_IDLE_MILLIS);
        return result;
    }

    public long idleMillis() {
        return idleMillis;
    }

    public static long nextIdleMillis(long current, long maximum) {
        if (current <= 0 || maximum < MIN_IDLE_MILLIS) {
            throw new IllegalArgumentException("invalid idle bounds");
        }
        return Math.min(maximum, Math.multiplyExact(current, 2));
    }

    @FunctionalInterface
    public interface ExportCycle {
        ReliableCoreExporter.ExportCycleResult run() throws Exception;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
