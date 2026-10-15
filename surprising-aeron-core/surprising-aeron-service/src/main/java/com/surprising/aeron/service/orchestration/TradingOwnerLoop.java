package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.orchestration.cluster.OwnerIdleStrategy;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import static com.surprising.aeron.service.orchestration.ClusterServiceEgress.NO_SESSION_ID;
import io.aeron.cluster.service.Cluster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns the trading Owner thread and its single-producer/single-consumer input boundary. */
@Component
public final class TradingOwnerLoop implements Runnable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TradingOwnerLoop.class);
    private static final long DEADLINE_NS = 30_000_000_000L;
    private static final String ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY =
            "surprising.aeron.owner-role-change-timeout-seconds";
    private static final long DEFAULT_ROLE_CHANGE_TIMEOUT_SECONDS = 600;
    private static final long ROLE_CHANGE_DEADLINE_NS = configuredRoleChangeDeadlineNanos(
            System.getProperty(ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY));
    private static final long INPUT_BYTES = 64L * 1024 * 1024;
    private static final String INPUT_BATCH_SIZE_PROPERTY =
            "surprising.aeron.owner-input-batch-size";
    private static final int INPUT_BATCH_SIZE = configuredInputBatchSize();

    private static final int INPUT_SLOTS = 8192;
    private final OneToOneConcurrentArrayQueue<Input> input = new OneToOneConcurrentArrayQueue<>(INPUT_SLOTS);
    /** Owner returns wrappers only after the command has entered its existing ingress FIFO. */
    private final OneToOneConcurrentArrayQueue<Input> availableInput = new OneToOneConcurrentArrayQueue<>(INPUT_SLOTS);
    /** Callback-thread slot retained if publication fails before ownership transfers. */
    private Input unpublishedInput;
    private final ClusterServiceEgress egress;
    private final TradingCoreOwner processor;
    private final OwnerIdleStrategy ownerIdle = new OwnerIdleStrategy(this::ownerWorkAvailable);
    private long inputProduced;
    private volatile long inputConsumed;
    private volatile Throwable failure;
    private boolean stopping;
    private Thread ownerThread;
    private Cluster cluster;
    private OwnerLogContext logContext;
    private long ownerEpoch;
    @Autowired
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress) {
        this(topology.productLine(), egress);
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress) {
        this.egress = egress;
        processor = new TradingCoreOwner(productLine, this::publishResponse);
        for (int i = 0; i < INPUT_SLOTS; i++) availableInput.add(new Input());
    }

    /** Starts the Owner thread after the Aeron Cluster service has started. */
    public void start(Cluster cluster, SectionedCoreSnapshotCodec.RecoveryBuffer restored) {
        this.cluster = cluster;
        inputProduced = 0;
        inputConsumed = 0;
        failure = null;
        stopping = false;
        ownerEpoch = 0;
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition(),
                new BusySpinIdleStrategy());
        ownerThread = new Thread(this, "trading-owner-" + cluster.memberId());
        ownerThread.start();
        boundary(() -> {
            processor.start(logContext);
            if (restored != null) processor.restoreSnapshot(restored);
            processor.ownerCompletionSignal(ownerIdle::signal);
            return null;
        }, false);
    }

    /** Transfers one replicated command from the Aeron callback thread to the Owner thread. */
    public void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, CoreMatchingPhaseMetrics.sampleStart(command.header()), fingerprint);
    }

    void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                 long enqueuedNanos, CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, null, enqueuedNanos, fingerprint, DEADLINE_NS);
    }

    public int drainResponses() {
        return egress.drainResponses(processor);
    }

    public void onRoleChange(Cluster.Role role, long nextEpoch) {
        boundary(() -> {
            logContext.role = role;
            ownerEpoch = nextEpoch;
            processor.roleChange(role);
            return null;
        }, true, ROLE_CHANGE_DEADLINE_NS);
    }

    public byte[] captureSnapshot(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return processor.captureSnapshot(Math.max(1, position));
        }, true);
    }

    /** Captures chunks for direct Aeron publication without a second full-size byte array. */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return processor.captureSnapshotSections(Math.max(1, position));
        }, true);
    }

    public void terminate() {
        if (ownerThread == null) return;
        try {
            if (failure == null && ownerThread.isAlive()) {
                boundary(() -> {
                    stopping = true;
                    return null;
                }, true);
            }
        } finally {
            try {
                ownerThread.join(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (ownerThread.isAlive()) {
                ownerThread.interrupt();
                throw new IllegalStateException("trading owner did not terminate");
            }
            Input pending;
            while ((pending = input.poll()) != null) returnInput(pending);
        }
    }

    public void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    private static int configuredInputBatchSize() {
        String configured = System.getProperty(INPUT_BATCH_SIZE_PROPERTY, "64");
        try {
            int value = Integer.parseInt(configured.trim());
            if (value < 1 || value > 64) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    INPUT_BATCH_SIZE_PROPERTY + " must be an integer in [1,64]", failure);
        }
    }

    @Override
    public void run() {
        ownerIdle.bindOwner();
        boolean initialized = false;
        try {
            while (!stopping) {
                int work = 0;
                for (int i = 0; i < INPUT_BATCH_SIZE && !stopping; i++) {
                    Input next = input.poll();
                    if (next == null) break;
                    try {
                        if (next.action != null) {
                            next.action.run();
                            initialized = true;
                        } else {
                            logContext.timestamp = next.timestamp;
                            logContext.position = next.position;
                            CoreMatchingPhaseMetrics.recordBoundary(
                                    "transportToOwner", next.command.header(), next.enqueuedNanos);
                            processor.enqueueCommittedCommand(next.sessionId, next.command, next.timestamp,
                                    next.position, next.fingerprint);
                            inputConsumed += next.command.payloadLength();
                        }
                    } finally { returnInput(next); }
                    work++;
                }
                if (initialized && !stopping) {
                    boolean ownerWorkAvailable = processor.ownerWorkAvailable();
                    if (ownerWorkAvailable) {
                        work += processor.pollCommands();
                    }
                }
                ownerIdle.idle(work, processor.pendingCommandCount() != 0);
            }
        } catch (Throwable fatal) {
            failure = fatal;
        } finally {
            try {
                processor.terminate();
            } catch (Throwable fatal) {
                if (failure == null) failure = fatal;
            }
        }
    }

    private void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                         Runnable action, long enqueuedNanos, CommandFingerprint fingerprint, long timeoutNanos) {
        int bytes = command == null ? 0 : command.payloadLength();
        if (bytes > INPUT_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        long started = System.nanoTime();
        long deadline = started + timeoutNanos;
        boolean reported = false;
        Input event = unpublishedInput;
        unpublishedInput = null;
        boolean published = false;
        try {
            while (true) {
                if (event == null) event = availableInput.poll();
                if (event != null && bytes <= INPUT_BYTES - (inputProduced - inputConsumed)) break;
                if (!reported && System.nanoTime() - started >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                    LOG.error("Core owner input has made no capacity for 5 seconds queued={} bytes={} ownerFailure={}",
                            input.size(), inputProduced - inputConsumed, failure);
                    reported = true;
                }
                awaitProgress(deadline);
            }
            event.prepare(command, sessionId, timestamp, position, action, enqueuedNanos, fingerprint);
            // A claimed wrapper consumes one of the same fixed number of queue positions.
            if (!input.offer(event)) throw new IllegalStateException("claimed owner input has no queue position");
            published = true;
            inputProduced += bytes;
            ownerIdle.signal();
        } finally {
            if (!published && event != null) {
                event.clear();
                unpublishedInput = event;
            }
        }
    }

    private void returnInput(Input event) {
        event.clear();
        if (!availableInput.offer(event)) throw new IllegalStateException("owner input returned twice");
    }

    private boolean ownerWorkAvailable() {
        return !input.isEmpty() || processor.ownerCompletionAvailable();
    }

    private <T> T boundary(Supplier<T> action, boolean finish) {
        return boundary(action, finish, DEADLINE_NS);
    }

    private <T> T boundary(Supplier<T> action, boolean finish, long timeoutNanos) {
        var completion = new CompletableFuture<T>();
        enqueue(null, NO_SESSION_ID, 0, 0, () -> {
            try {
                if (finish) processor.finishCommands();
                completion.complete(action.get());
            } catch (Throwable fatal) {
                completion.completeExceptionally(fatal);
                throw fatal;
            }
        }, 0, null, timeoutNanos);
        long deadline = System.nanoTime() + timeoutNanos;
        while (!completion.isDone()) awaitProgress(deadline);
        return completion.join();
    }

    static long configuredRoleChangeDeadlineNanos(String configuredSeconds) {
        long seconds = configuredSeconds == null || configuredSeconds.isBlank()
                ? DEFAULT_ROLE_CHANGE_TIMEOUT_SECONDS
                : Long.parseLong(configuredSeconds.trim());
        if (seconds < 30 || seconds > 1_800) {
            throw new IllegalArgumentException(ROLE_CHANGE_TIMEOUT_SECONDS_PROPERTY + " must be in [30,1800]");
        }
        return java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
    }

    private void publishResponse(long sessionId, CoreMessageHeader header, CoreResponse response,
                                 long committedSequence) {
        egress.publishResponse(processor, sessionId, header, response, committedSequence, ownerEpoch);
    }

    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        }
        egress.drainResponses(processor);
        cluster.idleStrategy().idle();
    }

    private static final class Input {
        CoreMessage command;
        long sessionId = NO_SESSION_ID;
        long timestamp, position, enqueuedNanos;
        Runnable action;
        CommandFingerprint fingerprint;

        void prepare(CoreMessage command, long sessionId, long timestamp, long position,
                     Runnable action, long enqueuedNanos, CommandFingerprint fingerprint) {
            this.command = command;
            this.sessionId = sessionId;
            this.timestamp = timestamp;
            this.position = position;
            this.action = action;
            this.enqueuedNanos = enqueuedNanos;
            this.fingerprint = fingerprint;
        }

        void clear() {
            command = null;
            sessionId = NO_SESSION_ID;
            action = null;
            fingerprint = null;
            timestamp = position = enqueuedNanos = 0;
        }
    }
}
