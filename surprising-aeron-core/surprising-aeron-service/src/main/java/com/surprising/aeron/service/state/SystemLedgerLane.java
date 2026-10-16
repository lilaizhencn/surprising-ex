package com.surprising.aeron.service.state;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** The original Treasury ledger has one writer; the Owner only publishes financial work. */
public final class SystemLedgerLane implements AutoCloseable {
    /** Input and completion belong to the existing command slot throughout this handoff. */
    public interface Entry {
        void apply(TreasuryRuntime ledger);
        default boolean ready() { return true; }
    }

    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.system-ledger-wait-strategy";
    public enum WaitStrategy { BUSY_SPIN, BLOCKING }

    private final TreasuryRuntime ledger;
    private final OneToOneConcurrentArrayQueue<Entry> entries;
    private final Thread producer;
    private final Thread thread;
    private final Consumer<Throwable> failurePublisher;
    private final WaitStrategy waitStrategy;
    private volatile boolean running = true;
    private volatile boolean started;
    private volatile boolean parked;
    private volatile Throwable failure;

    SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher) {
        if (ledger == null || capacity <= 0 || failurePublisher == null)
            throw new IllegalArgumentException("system ledger, capacity and failure publisher are required");
        this.ledger = ledger;
        this.failurePublisher = failurePublisher;
        this.waitStrategy = configuredWaitStrategy();
        entries = new OneToOneConcurrentArrayQueue<>(capacity);
        producer = Thread.currentThread();
        thread = new Thread(this::run, "core-system-ledger-lane");
        thread.setDaemon(true);
        ledger.handoffTo(thread);
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!started && System.nanoTime() < deadline) LockSupport.parkNanos(this, 100_000L);
        if (!started) {
            running = false;
            throw new IllegalStateException("system ledger Lane did not start");
        }
        assertHealthy();
    }

    boolean offer(Entry entry) {
        if (Thread.currentThread() != producer)
            throw new IllegalStateException("system ledger mailbox has one Owner producer");
        if (entry == null) throw new IllegalArgumentException("system ledger entry is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("system ledger Lane is closed");
        if (!entries.offer(entry)) return false;
        signalWork();
        return true;
    }

    void signalWork() {}

    Thread thread() { return thread; }
    int depth() { return entries.size(); }
    boolean hasCapacity() { return running && failure == null && entries.size() < entries.capacity(); }
    boolean parked() { return parked; }

    void assertHealthy() {
        Throwable problem = failure;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("system ledger Lane failed", problem);
    }

    private void run() {
        int spins = 0;
        try {
            ledger.bindOwner();
            started = true;
            while (running || !entries.isEmpty()) {
                Entry entry = entries.peek();
                if (entry != null && entry.ready()) {
                    // The worker now owns the ready head. Release transport capacity before
                    // applying money; business completion still comes from the entry's release
                    // marker. Owner can continuously enqueue even before a response retires.
                    entries.poll();
                    entry.apply(ledger);
                    // apply may publish completion and immediately allow slot reuse. Never
                    // read entry fields again after that ownership handoff.
                    spins = 0;
                    continue;
                }
                if (!running && entry != null)
                    throw new IllegalStateException("system ledger stopped before settlement was ready");
                if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                    Thread.onSpinWait();
                    continue;
                }
                if (spins++ < 256) { Thread.onSpinWait(); continue; }
                parked = true;
                try {
                    Entry next = entries.peek();
                    if (running && (next == null || !next.ready()))
                        LockSupport.parkNanos(this, 100_000L);
                } finally { parked = false; }
                spins = 0;
            }
        } catch (Throwable problem) {
            failure = problem;
            running = false;
            try { failurePublisher.accept(problem); }
            catch (Throwable reportingFailure) { problem.addSuppressed(reportingFailure); }
        } finally {
            ledger.releaseOwnerForHandoff();
            started = true;
        }
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BUSY_SPIN.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or BLOCKING", failure);
        }
    }

    @Override public void close() {
        running = false;
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(10L); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new IllegalStateException("system ledger Lane did not stop");
        assertHealthy();
    }
}
