package com.surprising.aeron.service.lane;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.service.state.AccountLaneState;
import com.surprising.aeron.service.state.MatcherSettlementEvent;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

abstract class SettlementLaneWorkerLhsPadding {
    @SuppressWarnings("unused")
    private long p00, p01, p02, p03, p04, p05, p06, p07;
}

abstract class SettlementLaneWorkerHandoffRequested extends SettlementLaneWorkerLhsPadding {
    /** owner 请求的交接代次；同一代次只派发一次预分配任务。 */
    protected volatile long requestedHandoff;
}

abstract class SettlementLaneWorkerMidPadding1 extends SettlementLaneWorkerHandoffRequested {
    @SuppressWarnings("unused")
    private long p10, p11, p12, p13, p14, p15, p16, p17;
}

abstract class SettlementLaneWorkerHandoffCompleted extends SettlementLaneWorkerMidPadding1 {
    /** Lane 完成此前所有任务并释放所有权后发布的代次。 */
    protected volatile long completedHandoff;
}

abstract class SettlementLaneWorkerMidPadding2 extends SettlementLaneWorkerHandoffCompleted {
    @SuppressWarnings("unused")
    private long p20, p21, p22, p23, p24, p25, p26, p27;
}

abstract class SettlementLaneWorkerHandoffResumed extends SettlementLaneWorkerMidPadding2 {
    /** owner 释放临时所有权后发布的恢复代次。 */
    protected volatile long resumedHandoff;
}

abstract class SettlementLaneWorkerMidPadding3 extends SettlementLaneWorkerHandoffResumed {
    @SuppressWarnings("unused")
    private long p30, p31, p32, p33, p34, p35, p36, p37;
}

abstract class SettlementLaneWorkerParked extends SettlementLaneWorkerMidPadding3 {
    /** Announced before every park and before rechecking all publication sources. */
    protected volatile boolean parked;
}

abstract class SettlementLaneWorkerRhsPadding extends SettlementLaneWorkerParked {
    @SuppressWarnings("unused")
    private long p40, p41, p42, p43, p44, p45, p46, p47;
}

/** Permanent SPSC event loop for one Account Lane. */
@Slf4j
public final class SettlementLaneWorker extends SettlementLaneWorkerRhsPadding implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.settlement-wait-strategy";
    private static final String SPIN_LIMIT_PROPERTY = "surprising.aeron.settlement-spin-limit";
    /**
     * A direct matcher event may be queued before its payload is published.  Keep a short
     * low-latency spin for that dependency, then park until the matcher signals publication.
     * This is deliberately a fixed local policy: it adds no per-command state and does not
     * change the configured strategy for executable Lane work.
     */
    private static final int DIRECT_READY_SPIN_LIMIT = 256;
    private static final int BUSY_SPIN_YIELD_THRESHOLD = 1024;

    public interface Command {
        void execute(AccountLaneState lane);
        /** An already-queued command may still be waiting for its ordered Ledger input. */
        default boolean accountReady() { return true; }
    }

    /** 首次失败发布到所属 runtime；正常任务不触碰共享故障状态。 */
    private final java.util.function.Consumer<Throwable> failurePublisher;
    private final Command[] commands;
    /**
     * Admission has a separate SPSC mailbox. A direct settlement may legitimately wait for the
     * Matcher, so it must never occupy the only queue head needed to publish a later admission.
     */
    private final Command[] admissionCommands;
    /** One Matcher-owned SPSC mailbox per Matcher shard; the Lane merges these with control mail. */
    private final MatcherSettlementRing[] matcherSettlementRings;
    private final int indexMask;
    private final int admissionIndexMask;
    private final WaitStrategy waitStrategy;
    private final int spinLimit;
    private final AccountLaneState lane;
    private final Thread thread;
    private final PaddedSequence producerSequence = new PaddedSequence();
    private final PaddedSequence consumerSequence = new PaddedSequence();
    private final PaddedSequence admissionProducerSequence = new PaddedSequence();
    private final PaddedSequence admissionConsumerSequence = new PaddedSequence();
    private volatile boolean started;
    private volatile boolean running = true;
    private volatile Throwable failure;
    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity) {
        this(role, lane, requestedCapacity, ignored -> { });
    }

    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher) {
        this(role, lane, requestedCapacity, failurePublisher, 1);
    }

    public SettlementLaneWorker(String role, AccountLaneState lane, int requestedCapacity,
                         java.util.function.Consumer<Throwable> failurePublisher,
                         int matcherShardCount) {
        this.failurePublisher = java.util.Objects.requireNonNull(failurePublisher);
        if (role == null || role.isBlank() || lane == null || requestedCapacity <= 0
                || matcherShardCount <= 0) {
            throw new IllegalArgumentException("invalid settlement lane worker");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        commands = new Command[capacity];
        admissionCommands = new Command[capacity];
        matcherSettlementRings = new MatcherSettlementRing[matcherShardCount];
        for (int shard = 0; shard < matcherShardCount; shard++)
            matcherSettlementRings[shard] = new MatcherSettlementRing(capacity);
        indexMask = capacity - 1;
        admissionIndexMask = capacity - 1;
        waitStrategy = configuredWaitStrategy();
        spinLimit = Integer.parseInt(System.getProperty(SPIN_LIMIT_PROPERTY, "0"));
        if (spinLimit < 0 || spinLimit > 4096)
            throw new IllegalArgumentException("settlement spin limit must be in [0,4096]");
        this.lane = lane;
        thread = new LaneThread("core-" + role + "-lane-" + lane.laneId(), this::run, lane);
        lane.bindWorker(thread);
        thread.start();
        while (!started && failure == null) Thread.onSpinWait();
        rethrowFailure();
    }

    /** 控制阶段交接任务不修改资金，仅转移唯一写入权。 */
    private final Command handoffCommand = lane -> {
        lane.releaseOwnerForHandoff();
        completedHandoff = requestedHandoff;
    };

    public void requestHandoff(long epoch) {
        requestedHandoff = epoch;
        submit(handoffCommand);
    }
    public boolean handoffReady(long epoch) { return completedHandoff == epoch; }
    public void resumeHandoff(long epoch) {
        resumedHandoff = epoch;
        unparkIfParked();
    }

    public Thread thread() { return thread; }
    public boolean isParked() { return parked; }

    public long submit(Command command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = producerSequence.value;
        long consumed = consumerSequence.value;
        if (next - consumed >= commands.length || depth() >= commands.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        commands[(int) next & indexMask] = command;
        producerSequence.value = next + 1;
        unparkIfParked();
        return next + 1;
    }

    /**
     * Owner-only admission publication after {@link #admissionDepthIfAvailable()} succeeds.
     * The Owner is the sole producer, so only the physical mailbox needs rechecking here;
     * consumers can only increase the available capacity between probe and publication.
     */
    public long submitAdmission(Command command) {
        if (command == null) throw new IllegalArgumentException("admission command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        long next = admissionProducerSequence.value;
        if (next - admissionConsumerSequence.value >= admissionCommands.length) {
            throw new RejectedExecutionException("settlement lane admission queue is full");
        }
        admissionCommands[(int) next & admissionIndexMask] = command;
        admissionProducerSequence.value = next + 1;
        unparkIfParked();
        return next + 1;
    }

    /** Matcher-only producer path. The caller must be the sole producer for this shard ring. */
    public long publishMatcherSettlement(int matcherShard, MatcherSettlementEvent event) {
        if (event == null || matcherShard < 0 || matcherShard >= matcherSettlementRings.length)
            throw new IllegalArgumentException("invalid Matcher settlement publication");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        MatcherSettlementRing ring = matcherSettlementRings[matcherShard];
        while (ring.isFull()) {
            rethrowFailure();
            if (!running) throw new RejectedExecutionException("settlement lane is closed");
            Thread.onSpinWait();
        }
        long position = ring.publish(event);
        unparkIfParked();
        return position + 1;
    }

    private void unparkIfParked() {
        if (parked) {
            java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    public int depth() {
        long direct = 0;
        for (MatcherSettlementRing ring : matcherSettlementRings) direct += ring.depth();
        return Math.toIntExact((producerSequence.value - consumerSequence.value)
                + (admissionProducerSequence.value - admissionConsumerSequence.value) + direct);
    }

    /** 观测容量包含控制、准入及各Matcher邮箱；不改变任一邮箱准入上限。 */
    public int metricsQueueCapacity() {
        int capacity = Math.addExact(commands.length, admissionCommands.length);
        for (MatcherSettlementRing ring : matcherSettlementRings) capacity = Math.addExact(capacity, ring.events.length);
        return capacity;
    }

    public boolean hasCapacity() {
        return running && failure == null && depth() < commands.length;
    }

    /** Returns the sampled total depth, or -1 when the dedicated admission mailbox is full. */
    public int admissionDepthIfAvailable() {
        if (!running || failure != null
                || admissionProducerSequence.value - admissionConsumerSequence.value >= admissionCommands.length) {
            return -1;
        }
        int currentDepth = depth();
        return currentDepth < admissionCommands.length ? currentDepth : -1;
    }

    public Throwable failure() {
        return failure;
    }

    public void assertHealthy() {
        rethrowFailure();
    }

    private void run() {
        long next = consumerSequence.value;
        long admissionNext = admissionConsumerSequence.value;
        int idleSpins = 0;
        try {
            lane.bindOwner();
            started = true;
            long reboundHandoff = 0;
            int directReadySpins = 0;
            int payloadWaitSpins = 0;
            while (running || next < producerSequence.value
                    || admissionNext < admissionProducerSequence.value
                    || hasMatcherSettlementWork()) {
                if (completedHandoff > reboundHandoff) {
                    if (resumedHandoff < completedHandoff) {
                        if (!running) break;
                        switch (waitStrategy) {
                            case BUSY_SPIN -> Thread.onSpinWait();
                            case YIELDING -> Thread.yield();
                            case BLOCKING -> parkUntilWork(true);
                        }
                        continue;
                    }
                    lane.bindOwner();
                    reboundHandoff = completedHandoff;
                }
                // Admissions are independent producer mail and must be drained even when the
                // main queue head is a Matcher result that has not been published yet.
                if (admissionNext < admissionProducerSequence.value) {
                    int admissionIndex = (int) admissionNext & admissionIndexMask;
                    Command admission = admissionCommands[admissionIndex];
                    if (admission == null) throw new IllegalStateException("admission publication gap");
                    if (ready(admission)) {
                        admissionCommands[admissionIndex] = null;
                        admissionNext++;
                        admissionConsumerSequence.value = admissionNext;
                        execute(admission);
                        idleSpins = 0;
                        payloadWaitSpins = 0;
                        continue;
                    }
                }
                // Handoff is an ownership barrier.  Do not consume a Matcher ring after the
                // worker has released its Lane to the Owner and before the Owner resumes it.
                MatcherSettlementEvent direct = peekMatcherSettlement();
                Command main = next < producerSequence.value ? commands[(int) next & indexMask] : null;
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    if (ready(direct)) {
                        pollMatcherSettlement(direct);
                        execute(direct);
                        idleSpins = 0;
                        directReadySpins = 0;
                        payloadWaitSpins = 0;
                        continue;
                    }
                    if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                        Thread.onSpinWait();
                    } else if (directReadySpins++ >= DIRECT_READY_SPIN_LIMIT) {
                        Thread.onSpinWait();
                        directReadySpins = 0;
                    } else {
                        Thread.onSpinWait();
                    }
                    continue;
                }
                if (next < producerSequence.value) {
                    int index = (int) next & indexMask;
                    Command command = commands[index];
                    if (command == null) throw new IllegalStateException("settlement lane publication gap");
                    if (ready(command)) {
                        // The command reference is now local, so the ring slot can be released before
                        // execution publishes its terminal notification. This makes queue depth and
                        // capacity describe queued work only; observing terminal completion can no
                        // longer race the worker's trailing cursor bookkeeping.
                        commands[index] = null;
                        next++;
                        consumerSequence.value = next;
                        execute(command);
                        idleSpins = 0;
                        directReadySpins = 0;
                        payloadWaitSpins = 0;
                        continue;
                    }
                    if (!running) throw new IllegalStateException("Lane stopped before its matcher payload arrived");

                    // The queue head is a valid command whose Matcher payload has not arrived.
                    // Spinning forever here burns a whole core without doing settlement work and
                    // can starve the Owner/Matcher that will make the event executable. After a
                    // short hand-tuned spin, park until the Matcher signals publication.
                    if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                        backoffPayloadWait(payloadWaitSpins++);
                        continue;
                    }
                }
                switch (waitStrategy) {
                    case BUSY_SPIN -> Thread.onSpinWait();
                    case YIELDING -> Thread.yield();
                    case BLOCKING -> {
                        if (idleSpins < spinLimit) {
                            idleSpins++;
                            Thread.onSpinWait();
                        } else {
                            parkUntilWork(false);
                        }
                    }
                }
            }
        } catch (Throwable laneFailure) {
            failure = laneFailure;
            running = false;
            started = true;
            try { failurePublisher.accept(laneFailure); }
            catch (Throwable reportingFailure) {
                if (reportingFailure != laneFailure) laneFailure.addSuppressed(reportingFailure);
            }
        } finally {
            lane.releaseOwnerForHandoff();
        }
    }

    private void parkUntilWork(boolean waitingForHandoff) {
        parked = true;
        try {
            if (!running) return;
            if (admissionConsumerSequence.value < admissionProducerSequence.value) {
                Command admission = admissionCommands[(int) admissionConsumerSequence.value & admissionIndexMask];
                if (admission != null && ready(admission)) return;
            }
            if (waitingForHandoff) {
                if (resumedHandoff >= completedHandoff) return;
            } else {
                Command main = consumerSequence.value < producerSequence.value
                        ? commands[(int) consumerSequence.value & indexMask] : null;
                MatcherSettlementEvent direct = peekMatcherSettlement();
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    if (ready(direct)) return;
                } else if (main != null && ready(main)) {
                    return;
                }
            }
            LockSupport.parkNanos(this, 100_000L);
        } finally {
            parked = false;
        }
    }

    private void backoffPayloadWait(int spins) {
        if (spins < BUSY_SPIN_YIELD_THRESHOLD) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }

    private void execute(Command command) {
        command.execute(lane);
    }

    private void rethrowFailure() {
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
    }

    private static boolean ready(Object command) {
        return command instanceof MatcherSettlementEvent event ? event.readyForLane()
                : ((Command) command).accountReady();
    }

    public boolean hasAdmissionCapacity() {
        rethrowFailure();
        return running && admissionProducerSequence.value - admissionConsumerSequence.value < admissionCommands.length;
    }

    private MatcherSettlementEvent peekMatcherSettlement() {
        MatcherSettlementEvent selected = null;
        long selectedSequence = Long.MAX_VALUE;
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            MatcherSettlementEvent candidate = ring.peek();
            if (candidate == null) continue;
            long sequence = candidate.coreSequence();
            if (selected == null || sequence < selectedSequence) {
                selected = candidate;
                selectedSequence = sequence;
            }
        }
        return selected;
    }

    private boolean hasMatcherSettlementWork() {
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            if (ring.depth() != 0) return true;
        }
        return false;
    }

    private void pollMatcherSettlement(MatcherSettlementEvent expected) {
        for (MatcherSettlementRing ring : matcherSettlementRings) {
            if (ring.peek() == expected) {
                ring.poll();
                return;
            }
        }
        throw new IllegalStateException("Matcher settlement publication disappeared");
    }

    private static boolean shouldRunMatcherSettlement(MatcherSettlementEvent direct, Command main) {
        if (main == null) return true;
        long mainSequence = commandSequence(main);
        // Unsequenced control work (handoff and metrics) stays ahead of business mail.
        if (mainSequence == 0) return false;
        if (!ready(main)) return false;
        return direct.coreSequence() < mainSequence;
    }

    private static long commandSequence(Command command) {
        if (command instanceof MatcherSettlementEvent event) return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.state.LaneCancelEvent event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.state.LaneCommitEvent event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.state.PlaceBatchAdmissionEvent event)
            return event.coreSequence();
        if (command instanceof com.surprising.aeron.service.state.ReplacementAdmissionEvent event)
            return event.coreSequence();
        return 0;
    }

    @Override
    public void close() {
        running = false;
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
        String configured = System.getProperty(WAIT_STRATEGY_PROPERTY, WaitStrategy.BUSY_SPIN.name());
        try {
            return WaitStrategy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "settlement wait strategy must be BUSY_SPIN, YIELDING or BLOCKING", exception);
        }
    }

    /**
     * Wait policy used by the lane event loop.  Keep this type public because
     * the worker is part of the service module API and IDE/compiler clients
     * may resolve the worker's nested type while inspecting its bytecode.
     */
    public enum WaitStrategy { BUSY_SPIN, YIELDING, BLOCKING }

    private static class PaddedSequenceLhsPadding {
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    private static class PaddedSequenceValue extends PaddedSequenceLhsPadding {
        protected volatile long value;
    }

    private static final class PaddedSequence extends PaddedSequenceValue {
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    private static class MatcherSettlementRingLhsPadding {
        @SuppressWarnings("unused")
        private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a, p0b, p0c, p0d, p0e;
    }

    private static class MatcherSettlementRingProducerPosition extends MatcherSettlementRingLhsPadding {
        protected volatile long producerPosition;
        protected long cachedConsumerPosition;
    }

    private static class MatcherSettlementRingMidPadding extends MatcherSettlementRingProducerPosition {
        @SuppressWarnings("unused")
        private long p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p1a, p1b, p1c, p1d, p1e;
    }

    private static class MatcherSettlementRingConsumerPosition extends MatcherSettlementRingMidPadding {
        protected volatile long consumerPosition;
        protected long cachedProducerPosition;
    }

    private static class MatcherSettlementRingRhsPadding extends MatcherSettlementRingConsumerPosition {
        @SuppressWarnings("unused")
        private long p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p2a, p2b, p2c, p2d, p2e;
    }

    /** Fixed reference ring with one producer and one consumer; cache lines are padded to prevent false sharing. */
    private static final class MatcherSettlementRing extends MatcherSettlementRingRhsPadding {
        private static final java.lang.invoke.VarHandle CONSUMER_POSITION_VH;
        static {
            try {
                CONSUMER_POSITION_VH = java.lang.invoke.MethodHandles.lookup()
                        .findVarHandle(MatcherSettlementRingConsumerPosition.class, "consumerPosition", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final MatcherSettlementEvent[] events;
        private final int mask;

        MatcherSettlementRing(int capacity) {
            events = new MatcherSettlementEvent[capacity];
            mask = capacity - 1;
        }

        boolean isFull() {
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                return position - cachedConsumerPosition >= events.length;
            }
            return false;
        }

        long publish(MatcherSettlementEvent event) {
            long position = producerPosition;
            if (position - cachedConsumerPosition >= events.length) {
                cachedConsumerPosition = consumerPosition;
                if (position - cachedConsumerPosition >= events.length) {
                    throw new RejectedExecutionException("Matcher settlement ring is full");
                }
            }
            int index = (int) position & mask;
            if (events[index] != null) {
                throw new IllegalStateException("Matcher settlement ring slot was not released");
            }
            events[index] = event;
            producerPosition = position + 1;
            return position;
        }

        MatcherSettlementEvent peek() {
            long position = consumerPosition;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    return null;
                }
            }
            return events[(int) position & mask];
        }

        void poll() {
            long position = consumerPosition;
            int index = (int) position & mask;
            if (position >= cachedProducerPosition) {
                cachedProducerPosition = producerPosition;
                if (position >= cachedProducerPosition) {
                    throw new IllegalStateException("Matcher settlement ring is empty");
                }
            }
            if (events[index] == null) {
                throw new IllegalStateException("Matcher settlement ring is empty");
            }
            events[index] = null;
            CONSUMER_POSITION_VH.setRelease(this, position + 1);
        }

        long depth() {
            return Math.max(0L, producerPosition - consumerPosition);
        }
    }
}
