package com.surprising.aeron.service.state;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

final class SettlementLaneWorker implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.settlement-wait-strategy";

    private final Runnable[] tasks;
    private final int indexMask;
    private final WaitStrategy waitStrategy;
    private final Thread thread;
    private volatile long producerSequence;
    private volatile long consumerSequence;
    private volatile boolean running = true;

    SettlementLaneWorker(int laneId, int requestedCapacity) {
        if (laneId < 0 || requestedCapacity <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        tasks = new Runnable[capacity];
        indexMask = capacity - 1;
        waitStrategy = configuredWaitStrategy();
        thread = new Thread(this::run, "core-settlement-lane-" + laneId);
        thread.setDaemon(true);
        thread.start();
    }

    void execute(Runnable task) {
        if (task == null) throw new IllegalArgumentException("settlement task is required");
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = producerSequence;
        if (next - consumerSequence >= tasks.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        tasks[(int) next & indexMask] = task;
        producerSequence = next + 1;
        if (waitStrategy == WaitStrategy.BLOCKING) LockSupport.unpark(thread);
    }

    private void run() {
        long next = consumerSequence;
        while (running || next < producerSequence) {
            if (next < producerSequence) {
                int index = (int) next & indexMask;
                Runnable task = tasks[index];
                if (task == null) throw new IllegalStateException("settlement lane task publication gap");
                task.run();
                tasks[index] = null;
                next++;
                consumerSequence = next;
                continue;
            }
            switch (waitStrategy) {
                case BUSY_SPIN -> Thread.onSpinWait();
                case YIELDING -> Thread.yield();
                case BLOCKING -> LockSupport.park(this);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("settlement lane did not stop");
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BLOCKING.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "settlement wait strategy must be BUSY_SPIN, YIELDING or BLOCKING", exception);
        }
    }

    private enum WaitStrategy {
        BUSY_SPIN,
        YIELDING,
        BLOCKING
    }
}
