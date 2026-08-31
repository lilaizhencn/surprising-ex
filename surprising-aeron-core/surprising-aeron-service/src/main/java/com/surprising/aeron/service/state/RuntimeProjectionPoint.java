package com.surprising.aeron.service.state;

public final class RuntimeProjectionPoint {
    private final long sequence;
    private volatile TradingCoreState state;
    private volatile boolean completed;

    public RuntimeProjectionPoint(long sequence, TradingCoreState state) {
        if (sequence < 0 || sequence == 0 && state == null) {
            throw new IllegalArgumentException("invalid runtime projection point");
        }
        this.sequence = sequence;
        this.state = state;
        completed = state != null;
    }

    public long sequence() {
        return sequence;
    }

    public boolean projected() {
        return state != null;
    }

    public boolean completed() {
        return completed;
    }

    TradingCoreState state() {
        return state;
    }

    void complete(TradingCoreState projectedState) {
        if (projectedState == null || state != null) {
            throw new IllegalStateException("runtime projection point was already completed");
        }
        state = projectedState;
        completed = true;
    }

    void completeSequence() {
        completed = true;
    }
}
