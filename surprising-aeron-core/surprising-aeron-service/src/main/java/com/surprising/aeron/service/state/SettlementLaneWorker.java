package com.surprising.aeron.service.state;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Permanent SPSC event loop for one Account Lane. */
final class SettlementLaneWorker implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.settlement-wait-strategy";

    interface Command {
        void execute(AccountLaneState lane);
    }

    private final Command[] commands;
    private final int indexMask;
    private final WaitStrategy waitStrategy;
    private final AccountLaneState lane;
    private final Thread thread;
    private volatile long producerSequence;
    private volatile long consumerSequence;
    private volatile boolean started;
    private volatile boolean running = true;
    private volatile Throwable failure;

    SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity) {
        if (role == null || role.isBlank() || lane == null || requestedCapacity <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        commands = new Command[capacity];
        indexMask = capacity - 1;
        waitStrategy = configuredWaitStrategy();
        this.lane = lane;
        thread = Thread.ofPlatform().daemon(true)
                .name("core-" + role + "-lane-" + lane.laneId())
                .start(this::run);
        while (!started && failure == null) Thread.onSpinWait();
        rethrowFailure();
    }

    long submit(Command command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = producerSequence;
        if (next - consumerSequence >= commands.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        commands[(int) next & indexMask] = command;
        producerSequence = next + 1;
        if (waitStrategy == WaitStrategy.BLOCKING && next == consumerSequence) LockSupport.unpark(thread);
        return next + 1;
    }

    void awaitConsumed(long ticket) {
        if (ticket <= 0 || ticket > producerSequence) {
            throw new IllegalArgumentException("invalid account lane consumer ticket");
        }
        while (consumerSequence < ticket) {
            rethrowFailure();
            Thread.onSpinWait();
        }
    }

    int depth() {
        return Math.toIntExact(producerSequence - consumerSequence);
    }

    Throwable failure() {
        return failure;
    }

    void assertHealthy() {
        rethrowFailure();
    }

    private void run() {
        long next = consumerSequence;
        try {
            lane.bindOwner();
            started = true;
            while (running || next < producerSequence) {
                if (next < producerSequence) {
                    int index = (int) next & indexMask;
                    Command command = commands[index];
                    if (command == null) throw new IllegalStateException("settlement lane publication gap");
                    command.execute(lane);
                    commands[index] = null;
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
        } catch (Throwable laneFailure) {
            failure = laneFailure;
            running = false;
            started = true;
        } finally {
            lane.releaseOwnerForHandoff();
        }
    }

    private void rethrowFailure() {
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
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
        rethrowFailure();
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

    private enum WaitStrategy { BUSY_SPIN, YIELDING, BLOCKING }
}
