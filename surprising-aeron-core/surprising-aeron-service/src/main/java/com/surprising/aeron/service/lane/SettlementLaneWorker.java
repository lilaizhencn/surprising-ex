package com.surprising.aeron.service.lane;

import com.surprising.aeron.service.state.AccountLaneState;
import com.surprising.aeron.service.state.MatcherSettlementEvent;

import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Permanent SPSC event loop for one Account Lane. */
public final class SettlementLaneWorker implements AutoCloseable {
    private static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.settlement-wait-strategy";
    private static final String SPIN_LIMIT_PROPERTY = "surprising.aeron.settlement-spin-limit";
    private static final boolean WAIT_DIAGNOSTICS =
            Boolean.getBoolean("surprising.settlement.wait-diagnostics");
    private static final long DIAGNOSTIC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Logger log = LoggerFactory.getLogger(SettlementLaneWorker.class);
    /**
     * A direct matcher event may be queued before its payload is published.  Keep a short
     * low-latency spin for that dependency, then park until the matcher signals publication.
     * This is deliberately a fixed local policy: it adds no per-command state and does not
     * change the configured strategy for executable Lane work.
     */
    private static final int DIRECT_READY_SPIN_LIMIT = 256;

    public interface Command {
        void execute(AccountLaneState lane);
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
    private long idleWaitNanos;
    private long notReadyWaitNanos;
    private long handoffWaitNanos;
    private long executeNanos;
    private long idleEntries;
    private long notReadyEntries;
    private long handoffEntries;
    private long executeCount;
    private long parkCount;
    private WaitPhase waitPhase = WaitPhase.NONE;
    private long waitPhaseStartedNanos;
    private long nextDiagnosticNanos;
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
        thread = Thread.ofPlatform().daemon(true)
                .name("core-" + role + "-lane-" + lane.laneId())
                .start(this::run);
        while (!started && failure == null) Thread.onSpinWait();
        rethrowFailure();
    }

    /** owner 请求的交接代次；同一代次只派发一次预分配任务。 */
    private volatile long requestedHandoff;
    /** Lane 完成此前所有任务并释放所有权后发布的代次。 */
    private volatile long completedHandoff;
    /** owner 释放临时所有权后发布的恢复代次。 */
    private volatile long resumedHandoff;
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
        signalWork();
    }

    public long submit(Command command) {
        if (command == null) throw new IllegalArgumentException("settlement command is required");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        boolean admission = command instanceof com.surprising.aeron.service.state.PlaceAdmissionEvent;
        PaddedSequence producer = admission ? admissionProducerSequence : producerSequence;
        PaddedSequence consumer = admission ? admissionConsumerSequence : consumerSequence;
        Command[] queue = admission ? admissionCommands : commands;
        int mask = admission ? admissionIndexMask : indexMask;
        long next = producer.value;
        long consumed = consumer.value;
        if (next - consumed >= queue.length || depth() >= queue.length) {
            throw new RejectedExecutionException("settlement lane queue is full");
        }
        queue[(int) next & mask] = command;
        producer.value = next + 1;
        // The consumer announces parking BEFORE rechecking producerSequence. These volatile
        // accesses ensure either it sees this publication or we observe its wake-up request.
        // Do not replace this handshake with an empty-queue check on consumerSequence.
        signalWork();
        return next + 1;
    }

    /** Matcher-only producer path. The caller must be the sole producer for this shard ring. */
    public long publishMatcherSettlement(int matcherShard, MatcherSettlementEvent event) {
        if (event == null || matcherShard < 0 || matcherShard >= matcherSettlementRings.length)
            throw new IllegalArgumentException("invalid Matcher settlement publication");
        rethrowFailure();
        if (!running) throw new RejectedExecutionException("settlement lane is closed");
        MatcherSettlementRing ring = matcherSettlementRings[matcherShard];
        int spins = 0;
        while (ring.isFull()) {
            rethrowFailure();
            if (!running) throw new RejectedExecutionException("settlement lane is closed");
            // The Matcher is the only producer.  A full ring means the Lane is catching up;
            // apply the configured low-overhead spin policy rather than allocating or failing
            // a valid settlement merely because a burst crossed the ring capacity.
            if (spins++ < DIRECT_READY_SPIN_LIMIT) Thread.onSpinWait();
            else LockSupport.parkNanos(1L);
        }
        long position = ring.publish(event);
        signalWork();
        return position + 1;
    }

    /** Unpark is idempotent; the consumer rechecks every volatile cursor after waking. */
    public void signalWork() {
        // An unconditional unpark is intentional.  LockSupport coalesces permits, and the
        // consumer rechecks every volatile cursor after waking. Conditioning the wake-up on a
        // racy consumer-state read can lose the only admission notification in the handoff
        // window (the Matcher then waits for a receipt that the Lane never observes).
        if (waitStrategy == WaitStrategy.BLOCKING) LockSupport.unpark(thread);
    }

    /** Matcher publishes a direct payload; this is separate from the normal Owner submission path. */
    public void signalDirectReady() {
        if (waitStrategy == WaitStrategy.BLOCKING) LockSupport.unpark(thread);
    }

    public int depth() {
        long direct = 0;
        for (MatcherSettlementRing ring : matcherSettlementRings) direct += ring.depth();
        return Math.toIntExact((producerSequence.value - consumerSequence.value)
                + (admissionProducerSequence.value - admissionConsumerSequence.value) + direct);
    }

    public boolean hasCapacity() {
        return running && failure == null && depth() < commands.length;
    }

    /** Capacity probe for the dedicated Lane admission mailbox. */
    public boolean hasAdmissionCapacity() {
        return running && failure == null
                && admissionProducerSequence.value - admissionConsumerSequence.value < admissionCommands.length
                && depth() < admissionCommands.length;
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
            if (WAIT_DIAGNOSTICS) nextDiagnosticNanos = System.nanoTime() + DIAGNOSTIC_INTERVAL_NANOS;
            long reboundHandoff = 0;
            int directReadySpins = 0;
            while (running || next < producerSequence.value
                    || admissionNext < admissionProducerSequence.value
                    || hasMatcherSettlementWork()) {
                diagnosticSample();
                // Admissions are independent producer mail and must be drained even when the
                // main queue head is a Matcher result that has not been published yet.
                if (admissionNext < admissionProducerSequence.value) {
                    int admissionIndex = (int) admissionNext & admissionIndexMask;
                    Command admission = admissionCommands[admissionIndex];
                    if (admission == null) throw new IllegalStateException("admission publication gap");
                    admissionCommands[admissionIndex] = null;
                    admissionNext++;
                    admissionConsumerSequence.value = admissionNext;
                    execute(admission);
                    idleSpins = 0;
                    continue;
                }
                if (completedHandoff > reboundHandoff) {
                    if (resumedHandoff < completedHandoff) {
                        if (!running) break;
                        enterWaitPhase(WaitPhase.HANDOFF);
                        switch (waitStrategy) {
                            case BUSY_SPIN -> Thread.onSpinWait();
                            case YIELDING -> Thread.yield();
                            case BLOCKING -> {
                                if (running && resumedHandoff < completedHandoff) LockSupport.park(this);
                            }
                        }
                        continue;
                    }
                    lane.bindOwner();
                    reboundHandoff = completedHandoff;
                }
                // Handoff is an ownership barrier.  Do not consume a Matcher ring after the
                // worker has released its Lane to the Owner and before the Owner resumes it.
                MatcherSettlementEvent direct = peekMatcherSettlement();
                Command main = next < producerSequence.value ? commands[(int) next & indexMask] : null;
                if (direct != null && shouldRunMatcherSettlement(direct, main)) {
                    pollMatcherSettlement(direct);
                    execute(direct);
                    idleSpins = 0;
                    directReadySpins = 0;
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
                        continue;
                    }
                    if (!running) throw new IllegalStateException("Lane stopped before its matcher payload arrived");

                    // The queue head is a valid command whose Matcher payload has not arrived.
                    // Spinning forever here burns a whole core without doing settlement work and
                    // can starve the Owner/Matcher that will make the event executable. After a
                    // short hand-tuned spin, park until the Matcher signals publication.
                    enterWaitPhase(WaitPhase.NOT_READY);
                    if (waitStrategy == WaitStrategy.BUSY_SPIN) {
                        if (directReadySpins++ >= DIRECT_READY_SPIN_LIMIT) {
                            if (running && next < producerSequence.value
                                    && !ready(commands[(int) next & indexMask])) {
                                parkCount++;
                                LockSupport.park(this);
                            }
                            directReadySpins = 0;
                        } else {
                            Thread.onSpinWait();
                        }
                        continue;
                    }
                }
                enterWaitPhase(WaitPhase.IDLE);
                switch (waitStrategy) {
                    case BUSY_SPIN -> Thread.onSpinWait();
                    case YIELDING -> Thread.yield();
                    case BLOCKING -> {
                        if (idleSpins < spinLimit) {
                            idleSpins++;
                            Thread.onSpinWait();
                        } else {
                            if (running && (next >= producerSequence.value
                                    || !ready(commands[(int) next & indexMask]))) {
                                parkCount++;
                                LockSupport.park(this);
                            }
                        }
                    }
                }
            }
        } catch (Throwable laneFailure) {
            failurePublisher.accept(laneFailure);
            failure = laneFailure;
            running = false;
            started = true;
        } finally {
            finishWaitPhase();
            lane.releaseOwnerForHandoff();
        }
    }

    private void execute(Command command) {
        if (!WAIT_DIAGNOSTICS) {
            command.execute(lane);
            return;
        }
        enterWaitPhase(WaitPhase.EXECUTE);
        command.execute(lane);
        executeCount++;
    }

    private void enterWaitPhase(WaitPhase nextPhase) {
        if (!WAIT_DIAGNOSTICS || waitPhase == nextPhase) return;
        long now = System.nanoTime();
        accumulateWaitPhase(now);
        waitPhase = nextPhase;
        waitPhaseStartedNanos = now;
        switch (nextPhase) {
            case IDLE -> idleEntries++;
            case NOT_READY -> notReadyEntries++;
            case HANDOFF -> handoffEntries++;
            case EXECUTE, NONE -> { }
        }
    }

    private void accumulateWaitPhase(long now) {
        if (waitPhase == WaitPhase.NONE) return;
        long elapsed = Math.max(0, now - waitPhaseStartedNanos);
        switch (waitPhase) {
            case IDLE -> idleWaitNanos += elapsed;
            case NOT_READY -> notReadyWaitNanos += elapsed;
            case HANDOFF -> handoffWaitNanos += elapsed;
            case EXECUTE -> executeNanos += elapsed;
            case NONE -> { }
        }
        waitPhaseStartedNanos = now;
    }

    private void finishWaitPhase() {
        if (!WAIT_DIAGNOSTICS) return;
        accumulateWaitPhase(System.nanoTime());
        waitPhase = WaitPhase.NONE;
    }

    private void diagnosticSample() {
        if (!WAIT_DIAGNOSTICS) return;
        long now = System.nanoTime();
        if (now < nextDiagnosticNanos) return;
        log.info("settlement-lane wait lane={} phase={} idleWaitNanos={} notReadyWaitNanos={} "
                        + "handoffWaitNanos={} executeNanos={} idleEntries={} notReadyEntries={} "
                        + "handoffEntries={} executeCount={} parkCount={} mainDepth={} admissionDepth={} directDepth={}",
                lane.laneId(), waitPhase, phaseNanos(WaitPhase.IDLE, now),
                phaseNanos(WaitPhase.NOT_READY, now), phaseNanos(WaitPhase.HANDOFF, now),
                phaseNanos(WaitPhase.EXECUTE, now), idleEntries, notReadyEntries, handoffEntries,
                executeCount, parkCount, producerSequence.value - consumerSequence.value,
                admissionProducerSequence.value - admissionConsumerSequence.value, matcherDepth());
        nextDiagnosticNanos = now + DIAGNOSTIC_INTERVAL_NANOS;
    }

    private long phaseNanos(WaitPhase phase, long now) {
        long total = switch (phase) {
            case IDLE -> idleWaitNanos;
            case NOT_READY -> notReadyWaitNanos;
            case HANDOFF -> handoffWaitNanos;
            case EXECUTE -> executeNanos;
            case NONE -> 0;
        };
        return waitPhase == phase ? total + Math.max(0, now - waitPhaseStartedNanos) : total;
    }

    private long matcherDepth() {
        long depth = 0;
        for (MatcherSettlementRing ring : matcherSettlementRings) depth += ring.depth();
        return depth;
    }

    private enum WaitPhase { NONE, IDLE, NOT_READY, HANDOFF, EXECUTE }

    private void rethrowFailure() {
        Throwable laneFailure = failure;
        if (laneFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (laneFailure instanceof Error error) throw error;
        if (laneFailure != null) throw new IllegalStateException("account lane failed", laneFailure);
    }

    private static boolean ready(Object command) {
        return !(command instanceof MatcherSettlementEvent event) || event.readyForLane();
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
        return 0;
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

    /**
     * Wait policy used by the lane event loop.  Keep this type public because
     * the worker is part of the service module API and IDE/compiler clients
     * may resolve the worker's nested type while inspecting its bytecode.
     */
    public enum WaitStrategy { BUSY_SPIN, YIELDING, BLOCKING }

    private static final class PaddedSequence {
        @SuppressWarnings("unused")
        private long p01, p02, p03, p04, p05, p06, p07;
        private volatile long value;
        @SuppressWarnings("unused")
        private long p11, p12, p13, p14, p15, p16, p17;
    }

    /** Fixed reference ring with one producer and one consumer; no CAS or node allocation. */
    private static final class MatcherSettlementRing {
        private final MatcherSettlementEvent[] events;
        private final int mask;
        private volatile long producerPosition;
        private volatile long consumerPosition;

        MatcherSettlementRing(int capacity) {
            events = new MatcherSettlementEvent[capacity];
            mask = capacity - 1;
        }

        boolean isFull() { return producerPosition - consumerPosition >= events.length; }

        long publish(MatcherSettlementEvent event) {
            long position = producerPosition;
            if (position - consumerPosition >= events.length)
                throw new RejectedExecutionException("Matcher settlement ring is full");
            int index = (int) position & mask;
            if (events[index] != null)
                throw new IllegalStateException("Matcher settlement ring slot was not released");
            events[index] = event;
            producerPosition = position + 1;
            return position;
        }

        MatcherSettlementEvent peek() {
            long position = consumerPosition;
            return position < producerPosition ? events[(int) position & mask] : null;
        }

        void poll() {
            long position = consumerPosition;
            int index = (int) position & mask;
            if (position >= producerPosition || events[index] == null)
                throw new IllegalStateException("Matcher settlement ring is empty");
            events[index] = null;
            consumerPosition = position + 1;
        }

        long depth() { return producerPosition - consumerPosition; }
    }
}
