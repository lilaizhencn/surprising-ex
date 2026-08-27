package com.surprising.aeron.service.state;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class AccountLaneWorker implements AutoCloseable {

    @FunctionalInterface
    interface Operation<T> {
        T apply(AccountLaneState state);
    }

    private static final long EMPTY_SEQUENCE = Long.MIN_VALUE;
    enum WaitMode {
        BUSY_SPIN,
        BALANCED,
        PARK;

        private static WaitMode configured() {
            String configured = System.getProperty(
                    "surprising.aeron.account-lane-wait-strategy", "BALANCED");
            try {
                return valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "account lane wait strategy must be BUSY_SPIN, BALANCED or PARK", exception);
            }
        }
    }

    private final AccountLaneState state;
    private final Slot[] slots;
    private final int mask;
    private final Thread thread;
    private final AccountLaneMetricsTracker metrics;
    private final WaitMode waitMode;
    private final int activeIdleSpins;
    private final int activeAwaitSpins;
    private final AtomicReference<Thread> sequencer = new AtomicReference<>();
    private volatile long consumedSequence;
    private volatile boolean running = true;
    private volatile long offeredSequence;
    private volatile long reclaimedSequence;

    AccountLaneWorker(AccountLaneState state, String productLineName) {
        this(state, productLineName, WaitMode.configured(),
                Math.max(0, Integer.getInteger("surprising.aeron.account-lane-idle-spins", 100_000)),
                Math.max(0, Integer.getInteger("surprising.aeron.account-lane-await-spins", 10_000)));
    }

    AccountLaneWorker(AccountLaneState state, String productLineName, WaitMode waitMode,
                      int activeIdleSpins, int activeAwaitSpins) {
        if (state == null || productLineName == null || productLineName.isBlank()) {
            throw new IllegalArgumentException("account lane worker state is required");
        }
        if (waitMode == null || activeIdleSpins < 0 || activeAwaitSpins < 0) {
            throw new IllegalArgumentException("account lane wait configuration is invalid");
        }
        this.state = state;
        this.waitMode = waitMode;
        this.activeIdleSpins = activeIdleSpins;
        this.activeAwaitSpins = activeAwaitSpins;
        this.slots = new Slot[state.queueCapacity()];
        for (int index = 0; index < slots.length; index++) slots[index] = new Slot();
        this.mask = slots.length - 1;
        this.metrics = new AccountLaneMetricsTracker(slots.length);
        this.thread = Thread.ofPlatform()
                .name("account-lane-" + productLineName + '-' + state.laneId())
                .daemon(true)
                .unstarted(this::run);
        this.thread.start();
    }

    <T> T invoke(Operation<T> operation) {
        return invoke(AccountLaneOperationType.COMMAND, operation);
    }

    <T> T invoke(AccountLaneOperationType type, Operation<T> operation) {
        if (operation == null) throw new IllegalArgumentException("account lane operation is required");
        if (Thread.currentThread() == thread) return operation.apply(state);
        return await(submit(type, operation));
    }

    <T> Ticket<T> submit(Operation<T> operation) {
        return submit(AccountLaneOperationType.COMMAND, operation);
    }

    <T> Ticket<T> submit(AccountLaneOperationType type, Operation<T> operation) {
        if (type == null) throw new IllegalArgumentException("account lane operation type is required");
        if (operation == null) throw new IllegalArgumentException("account lane operation is required");
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("account lane owner cannot enqueue its own work");
        }
        bindSequencer();
        if (!running) throw new IllegalStateException("account lane worker is closed");
        long sequence = Math.incrementExact(offeredSequence);
        if (sequence - reclaimedSequence > slots.length) {
            metrics.rejected();
            throw new IllegalStateException("account lane queue is full");
        }
        offeredSequence = sequence;
        Slot slot = slots[(int) sequence & mask];
        slot.operation = operation;
        slot.result = null;
        slot.failure = null;
        int depth = (int) (sequence - consumedSequence);
        metrics.submitted(sequence, type, depth);
        slot.requestSequence = sequence;
        LockSupport.unpark(thread);
        return new Ticket<>(this, slot, sequence);
    }

    <T> T await(Ticket<T> ticket) {
        if (ticket == null || ticket.worker != this) {
            throw new IllegalArgumentException("account lane ticket does not belong to this worker");
        }
        Slot slot = ticket.slot;
        long sequence = ticket.sequence;
        long expectedSequence = Math.incrementExact(reclaimedSequence);
        if (sequence != expectedSequence) {
            throw new IllegalStateException("account lane responses must be reclaimed in sequence: expected="
                    + expectedSequence + ", actual=" + sequence);
        }
        int idleSpins = 0;
        while (slot.responseSequence != sequence) {
            ensureRunning();
            if (waitMode == WaitMode.BUSY_SPIN
                    || waitMode == WaitMode.BALANCED && idleSpins < activeAwaitSpins) {
                idleSpins++;
                Thread.onSpinWait();
            } else {
                LockSupport.park();
            }
        }
        Object result = slot.result;
        Throwable failure = slot.failure;
        slot.operation = null;
        slot.result = null;
        slot.failure = null;
        slot.requestSequence = EMPTY_SEQUENCE;
        slot.responseSequence = EMPTY_SEQUENCE;
        reclaimedSequence = sequence;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("account lane operation failed", failure);
        @SuppressWarnings("unchecked")
        T typed = (T) result;
        return typed;
    }

    int queueDepth() {
        long depth = offeredSequence - consumedSequence;
        if (Thread.currentThread() == thread && depth > 0) depth--;
        return Math.toIntExact(depth);
    }

    int queueCapacity() {
        return slots.length;
    }

    int highWaterMark() {
        return metrics.queueHighWaterMark();
    }

    AccountLaneMetricsSnapshot metricsSnapshot() {
        return metrics.snapshot(queueDepth(), queueCapacity(), oldestPendingSequence());
    }

    long oldestPendingSequence() {
        long next = Math.incrementExact(consumedSequence);
        if (Thread.currentThread() == thread) next = Math.incrementExact(next);
        return next > offeredSequence ? 0 : next;
    }

    String ownerThreadName() {
        return thread.getName();
    }

    boolean ownerThread() {
        return Thread.currentThread() == thread;
    }

    @Override
    public void close() {
        if (Thread.currentThread() == thread) throw new IllegalStateException("account lane cannot close itself");
        bindSequencer();
        if (offeredSequence != reclaimedSequence) {
            throw new IllegalStateException("account lane cannot close with unreclaimed responses");
        }
        running = false;
        LockSupport.unpark(thread);
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void run() {
        state.bindOwner();
        long nextSequence = 1;
        int idleSpins = 0;
        while (running || nextSequence <= offeredSequence) {
            Slot slot = slots[(int) nextSequence & mask];
            if (slot.requestSequence != nextSequence) {
                if (waitMode == WaitMode.BUSY_SPIN
                        || waitMode == WaitMode.BALANCED && idleSpins < activeIdleSpins) {
                    idleSpins++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.park();
                    idleSpins = 0;
                }
                continue;
            }
            idleSpins = 0;
            try {
                slot.result = slot.operation.apply(state);
            } catch (Throwable failure) {
                slot.failure = failure;
            }
            metrics.completed(nextSequence);
            slot.responseSequence = nextSequence;
            consumedSequence = nextSequence;
            Thread boundSequencer = sequencer.get();
            if (boundSequencer != null) LockSupport.unpark(boundSequencer);
            nextSequence = Math.incrementExact(nextSequence);
        }
        state.releaseOwner();
    }

    private void bindSequencer() {
        Thread current = Thread.currentThread();
        Thread bound = sequencer.get();
        if (bound == null) {
            if (!sequencer.compareAndSet(null, current)) bound = sequencer.get();
            else bound = current;
        }
        if (bound != current && !bound.isAlive()
                && offeredSequence == reclaimedSequence
                && sequencer.compareAndSet(bound, current)) {
            bound = current;
        }
        if (bound != current) throw new IllegalStateException("account lane queue has multiple sequencer writers");
    }

    private void ensureRunning() {
        if (!running && !thread.isAlive()) throw new IllegalStateException("account lane worker terminated");
    }

    static final class Ticket<T> {
        private final AccountLaneWorker worker;
        private final Slot slot;
        private final long sequence;

        private Ticket(AccountLaneWorker worker, Slot slot, long sequence) {
            this.worker = worker;
            this.slot = slot;
            this.sequence = sequence;
        }
    }

    private static final class Slot {
        private volatile long requestSequence = EMPTY_SEQUENCE;
        private volatile long responseSequence = EMPTY_SEQUENCE;
        private Operation<?> operation;
        private Object result;
        private Throwable failure;
    }
}
