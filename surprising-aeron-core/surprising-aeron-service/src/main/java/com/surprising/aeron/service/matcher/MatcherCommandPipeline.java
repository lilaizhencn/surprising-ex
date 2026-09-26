package com.surprising.aeron.service.matcher;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.orchestration.CommandSlot;
import com.surprising.aeron.service.state.MatcherSettlementEvent;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * Single-producer/single-consumer matcher stage. The Aeron owner publishes immutable work,
 * one matcher thread owns exchange-core, and the Aeron owner consumes completions in order.
 */
@Slf4j
public final class MatcherCommandPipeline implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY =
            "surprising.aeron.matcher-pipeline-wait-strategy";
    private static final int IDLE_SPINS = 1_024;
    private static final long IDLE_PARK_NANOS = 1_000L;
    private static final int WORKER_IDLE_SPINS = 64;
    private static final long WORKER_IDLE_PARK_NANOS = 100_000L;
    private static final boolean WAIT_DIAGNOSTICS =
            Boolean.getBoolean("surprising.matcher.wait-diagnostics");
    private static final long DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Slot[] slots;
    private final int mask;
    private final WaitStrategy waitStrategy;
    private volatile Thread worker;
    private Runnable startupAction;
    private final PaddedSequence submittedPosition = new PaddedSequence();
    private final PaddedSequence consumedPosition = new PaddedSequence();
    private final PaddedSequence workerPosition = new PaddedSequence();
    private final PaddedSequence completedPosition = new PaddedSequence();
    private volatile boolean accepting;
    private volatile int submissionHighWaterMark;
    private volatile int completionHighWaterMark;
    private volatile boolean started;
    private volatile Throwable startupFailure;
    private volatile Throwable publicationFailure;
    private long controlSequence;
    private Runnable shutdownAction;
    private volatile Throwable shutdownFailure;
    private final int workerId;
    private long idleWaitNanos;
    private long executeNanos;
    private long idleEntries;
    private long executeCount;
    private long parkCount;
    private long idleStartedNanos;
    private long nextDiagnosticNanos;
    /** Owner enqueues query requests; matcher consumes them at their submission fence.
     * Bounded by the command window; requests own no copy of trading state. */
    private final OneToOneConcurrentArrayQueue<BackgroundRead<?>> backgroundReads;
    private volatile Runnable completionSignal;
    public void completionSignal(Runnable signal) { completionSignal = signal; }
    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(Supplier<T> read) {
        var future = new java.util.concurrent.CompletableFuture<T>();
        if (!accepting || !backgroundReads.offer(new BackgroundRead<>(submittedPosition.value, read, future))) {
            future.completeExceptionally(new RejectedExecutionException("matcher read mailbox unavailable"));
        } else {
            LockSupport.unpark(worker);
        }
        return future;
    }
    private record BackgroundRead<T>(long fence,Supplier<T> read,java.util.concurrent.CompletableFuture<T> future) {
        void execute() {try {future.complete(read.get());}catch(RuntimeException failure){future.completeExceptionally(failure);}}
    }

    public MatcherCommandPipeline(int requestedCapacity) {
        this(0, requestedCapacity, true);
    }

    public MatcherCommandPipeline(int requestedCapacity, boolean startImmediately) {
        this(0, requestedCapacity, startImmediately);
    }

    public MatcherCommandPipeline(int workerId, int requestedCapacity, boolean startImmediately) {
        if (workerId < 0) throw new IllegalArgumentException("matcher worker id must be non-negative");
        if (requestedCapacity <= 0 || (requestedCapacity & (requestedCapacity - 1)) != 0) {
            throw new IllegalArgumentException("matcher pipeline capacity must be a power of two");
        }
        this.workerId = workerId;
        this.waitStrategy = configuredWaitStrategy();
        backgroundReads = new OneToOneConcurrentArrayQueue<>(requestedCapacity);
        slots = new Slot[requestedCapacity];
        for (int index = 0; index < requestedCapacity; index++) slots[index] = new Slot();
        mask = requestedCapacity - 1;
        if (startImmediately) start(null);
    }

    public synchronized void start(Runnable action) {
        if (worker != null) return;
        if (started || startupFailure != null) {
            throw new IllegalStateException("matcher pipeline cannot be restarted");
        }
        startupAction = action;
        accepting = true;
        worker = Thread.ofPlatform().daemon(true).name("core-matcher-" + workerId).start(this::run);
        awaitStartup();
    }

    public void submit(long coreSequence, Supplier<?> command) {
        submit(coreSequence, command, null);
    }

    public void submit(long coreSequence, Supplier<?> command,
                MatcherSettlementEvent settlement) {
        if (coreSequence <= 0 || command == null) {
            throw new IllegalArgumentException("matcher pipeline command is invalid");
        }
        submitInternal(coreSequence, command, settlement);
    }

    void submit(long coreSequence, Supplier<?> command,
                MatcherSettlementEvent settlement,
                CommandSlot resultTarget) {
        if (coreSequence <= 0 || resultTarget == null || settlement != null && settlement.direct())
            throw new IllegalArgumentException("invalid command result target");
        submitInternal(coreSequence, command, settlement, resultTarget);
    }

    public <T> T call(Supplier<T> command, long timeoutNanos) {
        if (command == null || timeoutNanos <= 0) {
            throw new IllegalArgumentException("matcher control call is invalid");
        }
        long token = submitControl(command);
        Object result = awaitResult(token, timeoutNanos);
        if (result == null) throw new IllegalStateException("matcher control call timed out");
        @SuppressWarnings("unchecked")
        T typed = (T) result;
        return typed;
    }

    /** 控制命令使用独立负 token；提交和收集分离，owner 不等待 matcher。 */
    public long submitControl(Supplier<?> command) {
        controlSequence = Math.incrementExact(controlSequence);
        long token = -controlSequence;
        submitInternal(token, command);
        return token;
    }

    public Object pollControl(long token) {
        if (token >= 0) throw new IllegalArgumentException("matcher control token must be negative");
        return pollResult(token);
    }

    private void submitInternal(long token, Supplier<?> command) {
        submitInternal(token, command, null);
    }

    private void submitInternal(long token, Supplier<?> command,
                                MatcherSettlementEvent settlement) {
        submitInternal(token, command, settlement, null);
    }

    private void submitInternal(long token, Supplier<?> command,
                                MatcherSettlementEvent settlement,
                                CommandSlot resultTarget) {
        if (token == 0 || command == null) throw new IllegalArgumentException("matcher command is invalid");
        rethrowPublicationFailure();
        if (!accepting) throw new RejectedExecutionException("matcher pipeline is closed");
        skipReadyDirectSlots();
        long position = submittedPosition.value;
        if (position - consumedPosition.value >= slots.length) {
            throw new RejectedExecutionException("matcher pipeline is full");
        }
        Slot slot = slots[(int) position & mask];
        if (slot.command != null || slot.result != null || slot.failure != null || slot.token != 0) {
            throw new IllegalStateException("matcher pipeline slot was not released");
        }
        slot.token = token;
        slot.command = command;
        slot.settlement = settlement;
        slot.resultTarget = resultTarget;
        slot.directSettlement = settlement != null && settlement.direct();
        submittedPosition.value = position + 1;
        int depth = Math.toIntExact(position + 1 - consumedPosition.value);
        submissionHighWaterMark = Math.max(submissionHighWaterMark, depth);
        // An unpark token is sticky, so publishing unconditionally closes the
        // publish/park race without another state flag or reflective VarHandle.
        LockSupport.unpark(worker);
    }

    public CoreMatchingResult poll(long expectedCoreSequence) {
        if (expectedCoreSequence <= 0) throw new IllegalArgumentException("matcher sequence must be positive");
        Object result = pollResult(expectedCoreSequence);
        if (result == null) return null;
        if (result instanceof CoreMatchingResult matchingResult) return matchingResult;
        throw new IllegalStateException("matcher pipeline returned an invalid matching result: "
                + result.getClass().getName());
    }

    /**
     * Returns the positive matching token at the completion head, or zero when the head is
     * either not complete or belongs to a control call. The owner uses this probe
     * to drain completed matching work without probing every pending Core sequence.
     */
    public long completedMatchingSequence() {
        while (true) {
            rethrowPublicationFailure();
            skipReadyDirectSlots();
            long position = consumedPosition.value;
            if (position >= completedPosition.value) return 0;
            Slot slot = slots[(int) position & mask];
            // Matcher may publish a direct completion between skipReadyDirectSlots() and
            // the completed-position read above. Re-check the cell before exposing its token;
            // direct native results belong to the event/Lane path, not the ordinary result path.
            if (slot.token > 0 && slot.directSettlement) continue;
            return slot.token > 0 ? slot.token : 0;
        }
    }

    private Object pollResult(long expectedToken) {
        rethrowPublicationFailure();
        if (expectedToken == 0) throw new IllegalArgumentException("matcher token must be non-zero");
        long position = consumedPosition.value;
        if (position >= completedPosition.value) return null;
        Slot slot = slots[(int) position & mask];
        // A shard still publishes completions in its own submission order. The owner can probe
        // pending commands in a different (Core sequence) order, so a non-head token is simply
        // not consumable yet; the pass that probes the shard head will release it first.
        if (slot.token != expectedToken) return null;
        if (slot.command == null) throw new IllegalStateException("matcher completion publication gap");
        Object result = slot.result;
        Throwable failure = slot.failure;
        slot.clear();
        consumedPosition.value = position + 1;
        if (failure != null) {
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("matcher pipeline command failed", failure);
        }
        if (result == null) throw new IllegalStateException("matcher pipeline returned no result");
        skipReadyDirectSlots();
        return result;
    }

    /**
     * 直达事件已经在 Matcher 线程完成事实构造并释放了提交路由，Owner 不再需要读取结果
     * 或写入 sequence context。只有前序普通结果已消费时才能安全跳过该 slot。
     */
    private void skipReadyDirectSlots() {
        while (true) {
            long position = consumedPosition.value;
            if (position >= completedPosition.value) return;
            Slot slot = slots[(int) position & mask];
            // Only Owner retires transport cells, after Matcher publishes completedPosition.
            // The event may already be recycled; completion belongs to this cell's cursor.
            if (slot.token <= 0 || !slot.directSettlement) return;
            if (slot.failure != null) {
                pollResult(slot.token); // Propagate the failed command; never silently skip it.
                return;
            }
            slot.clear();
            consumedPosition.value = position + 1;
        }
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
            if (idle++ < IDLE_SPINS) {
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(this, Math.min(remaining, IDLE_PARK_NANOS));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("matcher pipeline wait was interrupted");
                }
            }
        }
    }

    public int submissionDepth() {
        return Math.toIntExact(submittedPosition.value - workerPosition.value);
    }

    public int completionDepth() {
        return Math.toIntExact(completedPosition.value - consumedPosition.value);
    }

    public int inFlight() {
        return Math.toIntExact(submittedPosition.value - consumedPosition.value);
    }

    public long submittedPosition() {
        return submittedPosition.value;
    }

    public int capacity() {
        return slots.length;
    }

    public int submissionHighWaterMark() {
        return submissionHighWaterMark;
    }

    public int completionHighWaterMark() {
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
        long position = workerPosition.value;
        int idle = 0;
        if (WAIT_DIAGNOSTICS) nextDiagnosticNanos = System.nanoTime() + DIAGNOSTIC_INTERVAL_NANOS;
        while (accepting || position < submittedPosition.value) {
            diagnosticSample();
            // Observe the submission cursor before the read mailbox. A later command publication
            // therefore cannot overtake a read published by the same owner at an earlier fence.
            long submitted=submittedPosition.value;
            BackgroundRead<?> read=backgroundReads.peek();
            if(read!=null && position>=read.fence()) {
                backgroundReads.poll();read.execute();continue;
            }
            if (position >= submitted) {
                enterIdle();
                if (waitStrategy == WaitStrategy.BUSY_SPIN) Thread.onSpinWait();
                else if (idle++ < WORKER_IDLE_SPINS) Thread.onSpinWait();
                else {
                    if (accepting && position >= submittedPosition.value && backgroundReads.isEmpty()) {
                        if (WAIT_DIAGNOSTICS) parkCount++;
                        LockSupport.parkNanos(this, WORKER_IDLE_PARK_NANOS);
                    }
                }
                continue;
            }
            leaveIdle();
            idle = 0;
            Slot slot = slots[(int) position & mask];
            Supplier<?> command = slot.command;
            long executeStarted = WAIT_DIAGNOSTICS ? System.nanoTime() : 0;
            if (command == null || slot.token == 0) {
                slot.failure = new IllegalStateException("matcher command publication gap");
            } else {
                MatcherSettlementEvent settlement = slot.settlement;
                try {
                    if (settlement != null && settlement.direct()) settlement.beginMatcherPublication();
                    Object result = command.get();
                    // Bind the Core sequence on the matcher worker while the completion is
                    // already in its slot; publish that same result into the command slot.
                    slot.result = slot.token > 0 && result instanceof CoreMatchingResult matchingResult
                            ? matchingResult.withCoreSequenceInPlace(slot.token) : result;
                    if (settlement != null && !settlement.resultPrepared()) {
                        throw new IllegalStateException("direct matcher did not publish its native result");
                    }
                } catch (Throwable failure) {
                    if (settlement != null) settlement.failDirect(failure);
                    slot.failure = failure;
                } finally {
                    if (settlement != null && settlement.direct()) settlement.completeMatcherPublication();
                }
            }
            if (WAIT_DIAGNOSTICS) {
                executeNanos += Math.max(0, System.nanoTime() - executeStarted);
                executeCount++;
            }
            // Retire transport before exposing the command result. Owner may immediately reuse
            // its command slot after this publication; only local references are used below.
            CommandSlot target = slot.resultTarget;
            CoreMatchingResult routedResult = target != null && slot.failure == null
                    ? (CoreMatchingResult) slot.result : null;
            position++;
            workerPosition.value = position;
            completedPosition.value = position;
            if (routedResult != null) {
                try { target.publishMatcherResult(workerId, routedResult); }
                catch (Throwable failure) {
                    publicationFailure = failure;
                    accepting = false;
                }
            }
            Runnable signal = completionSignal;
            if (signal != null) signal.run();
            completionHighWaterMark = Math.max(completionHighWaterMark,
                    Math.toIntExact(position - consumedPosition.value));
            if (publicationFailure != null) break;
        }
        leaveIdle();
        diagnosticSample();
        if (WAIT_DIAGNOSTICS) {
            log.info("matcher-worker final worker={} idleWaitNanos={} executeNanos={} idleEntries={} "
                            + "executeCount={} parkCount={} submissionDepth={} completionDepth={}",
                    workerId, idleWaitNanos, executeNanos, idleEntries, executeCount, parkCount,
                    submissionDepth(), completionDepth());
        }
        BackgroundRead<?> unfinishedRead;
        while ((unfinishedRead = backgroundReads.poll()) != null) {
            unfinishedRead.future().completeExceptionally(new RejectedExecutionException("matcher stopped"));
        }
        if (shutdownAction != null) {
            try {
                shutdownAction.run();
            } catch (Throwable failure) {
                shutdownFailure = failure;
            }
        }
    }

    private static WaitStrategy configuredWaitStrategy() {
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.ADAPTIVE.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    WAIT_STRATEGY_PROPERTY + " must be BUSY_SPIN or ADAPTIVE", failure);
        }
    }

    private enum WaitStrategy { BUSY_SPIN, ADAPTIVE }

    private void enterIdle() {
        if (!WAIT_DIAGNOSTICS || idleStartedNanos != 0) return;
        idleStartedNanos = System.nanoTime();
        idleEntries++;
    }

    private void leaveIdle() {
        if (!WAIT_DIAGNOSTICS || idleStartedNanos == 0) return;
        idleWaitNanos += Math.max(0, System.nanoTime() - idleStartedNanos);
        idleStartedNanos = 0;
    }

    private void diagnosticSample() {
        if (!WAIT_DIAGNOSTICS) return;
        long now = System.nanoTime();
        if (now < nextDiagnosticNanos) return;
        long currentIdle = idleWaitNanos;
        if (idleStartedNanos != 0) currentIdle += Math.max(0, now - idleStartedNanos);
        log.info("matcher-worker sample worker={} idleWaitNanos={} executeNanos={} idleEntries={} "
                        + "executeCount={} parkCount={} submissionDepth={} completionDepth={} inFlight={}",
                workerId, currentIdle, executeNanos, idleEntries, executeCount, parkCount,
                submissionDepth(), completionDepth(), inFlight());
        nextDiagnosticNanos = now + DIAGNOSTIC_INTERVAL_NANOS;
    }

    private void rethrowPublicationFailure() {
        Throwable failure = publicationFailure;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("matcher result publication failed", failure);
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
        private CommandSlot resultTarget;
        private Object result;
        private Throwable failure;
        private com.surprising.aeron.service.state.MatcherSettlementEvent settlement;
        /** Stable slot metadata; the settlement event itself is recycled by the owner. */
        private boolean directSettlement;

        private void clear() {
            token = 0;
            command = null;
            resultTarget = null;
            result = null;
            failure = null;
            settlement = null;
            directSettlement = false;
        }
    }

    private static final class PaddedSequence {
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07;
        private volatile long value;
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17;
    }
}
