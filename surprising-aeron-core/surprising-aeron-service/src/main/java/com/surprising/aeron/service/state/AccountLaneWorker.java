package com.surprising.aeron.service.state;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** One permanent account writer with a bounded, preallocated Owner-to-Lane SPSC mailbox. */
final class AccountLaneWorker implements AutoCloseable {
    /** Command objects belong to existing command slots; the mailbox does not copy account data. */
    interface Task {
        void execute(AccountLaneState lane);

        /** Matcher facts may retain an ordering slot while their immutable payload is in flight. */
        default boolean executable() { return true; }
    }

    private static final int IDLE_SPINS = 256;
    private final AccountLaneState lane;
    private final OneToOneConcurrentArrayQueue<Task> commands;
    private final Thread producer;
    private final Thread thread;
    private final Consumer<Throwable> failurePublisher;
    private final WaitStrategy waitStrategy;
    private volatile boolean running = true;
    private volatile boolean started;
    private volatile Throwable failure;

    AccountLaneWorker(AccountLaneState lane, Consumer<Throwable> failurePublisher) {
        this(lane, failurePublisher, configuredWaitStrategy());
    }

    AccountLaneWorker(AccountLaneState lane, Consumer<Throwable> failurePublisher, WaitStrategy waitStrategy) {
        if (lane == null || failurePublisher == null || waitStrategy == null) {
            throw new IllegalArgumentException("account lane, failure publisher and wait strategy are required");
        }
        this.lane = lane;
        this.failurePublisher = failurePublisher;
        this.waitStrategy = waitStrategy;
        commands = new OneToOneConcurrentArrayQueue<>(lane.queueCapacity());
        producer = Thread.currentThread();
        thread = new Thread(this::run, "core-account-lane-" + lane.laneId());
        thread.setDaemon(true);
        // Initialization is the only Owner-to-worker handoff. Ordinary commands keep Lane ownership.
        lane.handoffTo(thread);
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!started && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        if (!started) {
            running = false;
            signalWork();
            throw new IllegalStateException("account lane did not start");
        }
        assertHealthy();
    }

    /** Returns false for backpressure; no queue node or per-command wrapper is allocated. */
    boolean offer(Task command) {
        if (Thread.currentThread() != producer) {
            throw new IllegalStateException("account lane mailbox has one Owner producer");
        }
        if (command == null) throw new IllegalArgumentException("account lane command is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("account lane is closed");
        if (!commands.offer(command)) return false;
        signalWork();
        return true;
    }

    void submit(Task command) {
        if (!offer(command)) throw new RejectedExecutionException("account lane mailbox is full");
    }

    boolean hasCapacity() { return running && failure == null && depth() < commands.capacity(); }
    int capacity() { return commands.capacity(); }
    int depth() { return commands.size(); }
    Throwable failure() { return failure; }

    void signalWork() {
        // A permit also covers the publication-before-park window; no racy parked-state flag.
        if (waitStrategy == WaitStrategy.BLOCKING) LockSupport.unpark(thread);
    }

    void assertHealthy() {
        Throwable problem = failure;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("account lane failed", problem);
    }

    private void run() {
        int spins = 0;
        try {
            lane.bindOwner();
            started = true;
            while (running || !commands.isEmpty()) {
                Task command = commands.peek();
                if (command != null) {
                    if (command.executable()) {
                        // Release the queue slot before completion can let the Owner recycle the command.
                        if (commands.poll() != command) throw new IllegalStateException("account lane mailbox publication gap");
                        command.execute(lane);
                        spins = 0;
                        continue;
                    }
                    if (!running) throw new IllegalStateException("account lane stopped before its fact was ready");
                    // Readiness may be published by a Matcher rather than this mailbox's producer.
                    // Timed waiting observes that publication without requiring another Owner message.
                    if (spins++ < IDLE_SPINS) Thread.onSpinWait();
                    else { LockSupport.parkNanos(10_000); spins = 0; }
                    continue;
                }
                switch (waitStrategy) {
                    case BUSY_SPIN -> Thread.onSpinWait();
                    case YIELDING -> Thread.yield();
                    case BLOCKING -> {
                        if (spins++ < IDLE_SPINS) Thread.onSpinWait();
                        else {
                            if (running && commands.isEmpty()) LockSupport.park(this);
                            spins = 0;
                        }
                    }
                }
            }
        } catch (Throwable problem) {
            failure = problem;
            running = false;
            try { failurePublisher.accept(problem); }
            catch (Throwable reportingFailure) { problem.addSuppressed(reportingFailure); }
        } finally {
            lane.releaseOwnerForHandoff();
            started = true;
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(10); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("account lane did not stop");
        assertHealthy();
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty("surprising.aeron.settlement-wait-strategy", "BLOCKING");
        try { return WaitStrategy.valueOf(configured.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("settlement wait strategy must be BUSY_SPIN, YIELDING or BLOCKING", invalid);
        }
    }

    enum WaitStrategy { BUSY_SPIN, YIELDING, BLOCKING }

}
