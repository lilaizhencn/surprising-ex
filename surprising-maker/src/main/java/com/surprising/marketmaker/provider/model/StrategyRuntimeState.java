package com.surprising.marketmaker.provider.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StrategyRuntimeState {

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicLong cycleSequence = new AtomicLong();
    private final AtomicLong submittedOrders = new AtomicLong();
    private final AtomicLong canceledOrders = new AtomicLong();
    private final AtomicLong rejectedOrders = new AtomicLong();
    private final AtomicLong skippedCycles = new AtomicLong();
    private volatile String lastTraceId;
    private volatile String lastError;
    private volatile Instant lastCycleTime;

    public boolean paused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public long nextCycleSequence() {
        return cycleSequence.incrementAndGet();
    }

    public long cycleSequence() {
        return cycleSequence.get();
    }

    public void addSubmitted(long count) {
        submittedOrders.addAndGet(count);
    }

    public long submittedOrders() {
        return submittedOrders.get();
    }

    public void addCanceled(long count) {
        canceledOrders.addAndGet(count);
    }

    public long canceledOrders() {
        return canceledOrders.get();
    }

    public void addRejected(long count) {
        rejectedOrders.addAndGet(count);
    }

    public long rejectedOrders() {
        return rejectedOrders.get();
    }

    public void addSkipped(long count) {
        skippedCycles.addAndGet(count);
    }

    public long skippedCycles() {
        return skippedCycles.get();
    }

    public String lastError() {
        return lastError;
    }

    public String lastTraceId() {
        return lastTraceId;
    }

    public Instant lastCycleTime() {
        return lastCycleTime;
    }

    public void markSuccess(String traceId, Instant now) {
        this.lastTraceId = traceId;
        this.lastError = null;
        this.lastCycleTime = now;
    }

    public void markFailure(String traceId, String error, Instant now) {
        this.lastTraceId = traceId;
        this.lastError = error;
        this.lastCycleTime = now;
    }
}
