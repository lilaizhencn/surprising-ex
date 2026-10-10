package com.surprising.aeron.service.state;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Sole writer of system fees, insurance and settlement residuals; owns the existing ledger itself. */
final class SystemLedgerLane implements AutoCloseable {
    /** A pooled settlement or control slot provides the input; no Owner-side money merge is required. */
    interface Entry {
        void apply(TreasuryRuntime ledger);
        default boolean ready() { return true; }
    }

    private final TreasuryRuntime ledger;
    private final OneToOneConcurrentArrayQueue<Entry> entries;
    private final Thread producer;
    private final Thread thread;
    private final Consumer<Throwable> failurePublisher;
    private volatile boolean running = true;
    private volatile boolean started;
    private volatile Throwable failure;

    SystemLedgerLane(TreasuryRuntime ledger, int capacity, Consumer<Throwable> failurePublisher) {
        if (ledger == null || capacity <= 0 || failurePublisher == null) {
            throw new IllegalArgumentException("system ledger, capacity and failure publisher are required");
        }
        this.ledger = ledger;
        this.failurePublisher = failurePublisher;
        entries = new OneToOneConcurrentArrayQueue<>(capacity);
        producer = Thread.currentThread();
        thread = new Thread(this::run, "core-system-ledger-lane");
        thread.setDaemon(true);
        ledger.handoffTo(thread);
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!started && System.nanoTime() < deadline) LockSupport.parkNanos(100_000);
        if (!started) {
            running = false;
            LockSupport.unpark(thread);
            throw new IllegalStateException("system ledger Lane did not start");
        }
        assertHealthy();
    }

    boolean offer(Entry entry) {
        if (Thread.currentThread() != producer) {
            throw new IllegalStateException("system ledger mailbox has one Owner producer");
        }
        if (entry == null) throw new IllegalArgumentException("system ledger entry is required");
        assertHealthy();
        if (!running) throw new RejectedExecutionException("system ledger Lane is closed");
        if (!entries.offer(entry)) return false;
        LockSupport.unpark(thread);
        return true;
    }

    boolean hasCapacity() { return running && failure == null && entries.size() < entries.capacity(); }
    int depth() { return entries.size(); }

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
                if (entry != null) {
                    if (entry.ready()) {
                        if (entries.poll() != entry) throw new IllegalStateException("system ledger publication gap");
                        entry.apply(ledger);
                        spins = 0;
                        continue;
                    }
                    if (!running) throw new IllegalStateException("system ledger stopped before settlement was ready");
                    if (spins++ < 256) Thread.onSpinWait();
                    else { LockSupport.parkNanos(10_000); spins = 0; }
                } else if (spins++ < 256) {
                    Thread.onSpinWait();
                } else {
                    if (running && entries.isEmpty()) LockSupport.park(this);
                    spins = 0;
                }
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
        if (thread.isAlive()) throw new IllegalStateException("system ledger Lane did not stop");
        assertHealthy();
    }
}
