package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Single-producer/single-consumer matcher stage. The Aeron owner publishes immutable work,
 * one matcher thread owns exchange-core, and the Aeron owner consumes completions in order.
 */
final class MatcherCommandPipeline implements AutoCloseable {
    private static final int IDLE_SPINS = 1_024;
    private static final long IDLE_PARK_NANOS = 1_000L;
    private static final int WORKER_IDLE_SPINS = 64;
    private static final long WORKER_IDLE_PARK_NANOS = 100_000L;

    private final Slot[] slots;
    private final int mask;
    private volatile Thread worker;
    private Runnable startupAction;
    private volatile long submittedPosition;
    private volatile long consumedPosition;
    private volatile long workerPosition;
    private volatile long completedPosition;
    private volatile boolean accepting;
    private volatile int submissionHighWaterMark;
    private volatile int completionHighWaterMark;
    private volatile boolean started;
    private volatile Throwable startupFailure;
    private long controlSequence;
    private Runnable shutdownAction;
    private volatile Throwable shutdownFailure;
    private final int workerId;

    MatcherCommandPipeline(int requestedCapacity) {
        this(0, requestedCapacity, true);
    }

    MatcherCommandPipeline(int requestedCapacity, boolean startImmediately) {
        this(0, requestedCapacity, startImmediately);
    }

    MatcherCommandPipeline(int workerId, int requestedCapacity, boolean startImmediately) {
        if (workerId < 0) throw new IllegalArgumentException("matcher worker id must be non-negative");
        if (requestedCapacity <= 0 || (requestedCapacity & (requestedCapacity - 1)) != 0) {
            throw new IllegalArgumentException("matcher pipeline capacity must be a power of two");
        }
        this.workerId = workerId;
        slots = new Slot[requestedCapacity];
        for (int index = 0; index < requestedCapacity; index++) slots[index] = new Slot();
        mask = requestedCapacity - 1;
        if (startImmediately) start(null);
    }

    synchronized void start(Runnable action) {
        if (worker != null) return;
        if (started || startupFailure != null) {
            throw new IllegalStateException("matcher pipeline cannot be restarted");
        }
        startupAction = action;
        accepting = true;
        worker = Thread.ofPlatform().daemon(true).name("core-matcher-" + workerId).start(this::run);
        awaitStartup();
    }

    void submit(long coreSequence, Supplier<CoreMatchingResult> command) {
        if (coreSequence <= 0 || command == null) {
            throw new IllegalArgumentException("matcher pipeline command is invalid");
        }
        submitInternal(coreSequence, command);
    }

    <T> T call(Supplier<T> command, long timeoutNanos) {
        if (command == null || timeoutNanos <= 0) {
            throw new IllegalArgumentException("matcher control call is invalid");
        }
        long token = -Math.incrementExact(controlSequence);
        submitInternal(token, command);
        Object result = awaitResult(token, timeoutNanos);
        if (result == null) throw new IllegalStateException("matcher control call timed out");
        @SuppressWarnings("unchecked")
        T typed = (T) result;
        return typed;
    }

    private void submitInternal(long token, Supplier<?> command) {
        if (token == 0 || command == null) throw new IllegalArgumentException("matcher command is invalid");
        if (!accepting) throw new RejectedExecutionException("matcher pipeline is closed");
        long position = submittedPosition;
        if (position - consumedPosition >= slots.length) {
            throw new RejectedExecutionException("matcher pipeline is full");
        }
        Slot slot = slots[(int) position & mask];
        if (slot.command != null || slot.result != null || slot.failure != null || slot.token != 0) {
            throw new IllegalStateException("matcher pipeline slot was not released");
        }
        slot.token = token;
        slot.command = command;
        submittedPosition = position + 1;
        int depth = Math.toIntExact(submittedPosition - consumedPosition);
        submissionHighWaterMark = Math.max(submissionHighWaterMark, depth);
        if (position == workerPosition) LockSupport.unpark(worker);
    }

    CoreMatchingResult poll(long expectedCoreSequence) {
        if (expectedCoreSequence <= 0) throw new IllegalArgumentException("matcher sequence must be positive");
        Object result = pollResult(expectedCoreSequence);
        if (result == null) return null;
        if (result instanceof CoreMatchingResult matchingResult) return matchingResult;
        throw new IllegalStateException("matcher pipeline returned an invalid matching result");
    }

    private Object pollResult(long expectedToken) {
        if (expectedToken == 0) throw new IllegalArgumentException("matcher token must be non-zero");
        long position = consumedPosition;
        if (position >= completedPosition) return null;
        Slot slot = slots[(int) position & mask];
        // A shard still publishes completions in its own submission order. The owner can probe
        // pending commands in a different (Core sequence) order, so a non-head token is simply
        // not consumable yet; the pass that probes the shard head will release it first.
        if (slot.token != expectedToken) return null;
        if (slot.command == null) throw new IllegalStateException("matcher completion publication gap");
        Object result = slot.result;
        Throwable failure = slot.failure;
        slot.clear();
        consumedPosition = position + 1;
        if (failure != null) {
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("matcher pipeline command failed", failure);
        }
        if (result == null) throw new IllegalStateException("matcher pipeline returned no result");
        return result;
    }

    CoreMatchingResult await(long expectedCoreSequence, long timeoutNanos) {
        if (timeoutNanos <= 0) return null;
        Object result = awaitResult(expectedCoreSequence, timeoutNanos);
        if (result == null) return null;
        if (result instanceof CoreMatchingResult matchingResult) return matchingResult;
        throw new IllegalStateException("matcher pipeline returned an invalid matching result");
    }

    private Object awaitResult(long expectedToken, long timeoutNanos) {
        if (timeoutNanos <= 0) return null;
        long deadline = System.nanoTime() + timeoutNanos;
        int idle = 0;
        while (true) {
            Object result = pollResult(expectedToken);
            if (result != null) return result;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            if (idle++ < IDLE_SPINS) Thread.onSpinWait();
            else LockSupport.parkNanos(this, Math.min(remaining, IDLE_PARK_NANOS));
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("matcher pipeline wait was interrupted");
            }
        }
    }

    int submissionDepth() {
        return Math.toIntExact(submittedPosition - workerPosition);
    }

    int completionDepth() {
        return Math.toIntExact(completedPosition - consumedPosition);
    }

    int inFlight() {
        return Math.toIntExact(submittedPosition - consumedPosition);
    }

    int capacity() {
        return slots.length;
    }

    int submissionHighWaterMark() {
        return submissionHighWaterMark;
    }

    int completionHighWaterMark() {
        return completionHighWaterMark;
    }

    private void run() {
        try {
            if (startupAction != null) startupAction.run();
        } catch (Throwable failure) {
            startupFailure = failure;
            accepting = false;
        } finally {
            started = true;
        }
        if (startupFailure != null) return;
        long position = workerPosition;
        int idle = 0;
        while (accepting || position < submittedPosition) {
            if (position >= submittedPosition) {
                if (idle++ < WORKER_IDLE_SPINS) Thread.onSpinWait();
                else LockSupport.parkNanos(this, WORKER_IDLE_PARK_NANOS);
                continue;
            }
            idle = 0;
            Slot slot = slots[(int) position & mask];
            Supplier<?> command = slot.command;
            if (command == null || slot.token == 0) {
                slot.failure = new IllegalStateException("matcher command publication gap");
            } else {
                try {
                    slot.result = command.get();
                } catch (Throwable failure) {
                    slot.failure = failure;
                }
            }
            position++;
            workerPosition = position;
            completedPosition = position;
            completionHighWaterMark = Math.max(completionHighWaterMark,
                    Math.toIntExact(position - consumedPosition));
        }
        if (shutdownAction != null) {
            try {
                shutdownAction.run();
            } catch (Throwable failure) {
                shutdownFailure = failure;
            }
        }
    }

    private void awaitStartup() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int idle = 0;
        while (!started && System.nanoTime() < deadline) {
            if (idle++ < IDLE_SPINS) Thread.onSpinWait();
            else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
        }
        if (!started) {
            accepting = false;
            LockSupport.unpark(worker);
            throw new IllegalStateException("matcher pipeline startup timed out");
        }
        if (startupFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (startupFailure instanceof Error error) throw error;
        if (startupFailure != null) {
            throw new IllegalStateException("matcher pipeline startup failed", startupFailure);
        }
    }

    @Override
    public void close() {
        close(null);
    }

    void close(Runnable action) {
        Thread activeWorker = worker;
        if (activeWorker == null) {
            accepting = false;
            if (action != null) action.run();
            return;
        }
        if (!accepting) {
            if (activeWorker.isAlive()) {
                try {
                    activeWorker.join(TimeUnit.SECONDS.toMillis(5));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("matcher pipeline shutdown was interrupted", exception);
                }
            }
            if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
            if (action != null) action.run();
            return;
        }
        shutdownAction = action;
        accepting = false;
        LockSupport.unpark(activeWorker);
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (activeWorker.isAlive() && System.nanoTime() < deadline) {
            try {
                activeWorker.join(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
        if (shutdownFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (shutdownFailure instanceof Error error) throw error;
        if (shutdownFailure != null) {
            throw new IllegalStateException("matcher pipeline shutdown failed", shutdownFailure);
        }
    }

    private static final class Slot {
        private long token;
        private Supplier<?> command;
        private Object result;
        private Throwable failure;

        private void clear() {
            token = 0;
            command = null;
            result = null;
            failure = null;
        }
    }
}
