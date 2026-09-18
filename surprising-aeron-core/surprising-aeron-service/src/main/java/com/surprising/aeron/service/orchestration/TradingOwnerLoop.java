package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.cluster.ClusterTopology;
import com.surprising.aeron.service.orchestration.cluster.OwnerIdleStrategy;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns the trading Owner thread and its single-producer/single-consumer input boundary. */
@Component
public final class TradingOwnerLoop implements Runnable {
    private static final long DEADLINE_NS = 30_000_000_000L;
    private static final long INPUT_BYTES = 64L * 1024 * 1024;

    private final OneToOneConcurrentArrayQueue<Input> input = new OneToOneConcurrentArrayQueue<>(8192);
    private final ClusterServiceEgress egress;
    private final TradingCoreOwner processor;
    private final OwnerIdleStrategy ownerIdle = new OwnerIdleStrategy(this::ownerWorkAvailable);
    private final boolean ownerPollDiagnostics = Boolean.getBoolean("surprising.owner.poll-diagnostics");

    private long inputProduced;
    private volatile long inputConsumed;
    private volatile Throwable failure;
    private boolean stopping;
    private Thread ownerThread;
    private Cluster cluster;
    private OwnerLogContext logContext;
    private long ownerEpoch;
    private long ownerPollCalls;
    private long ownerNoProgressPolls;
    private long ownerNoProgressNanos;
    private long ownerGateSkippedPending;

    @Autowired
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress) {
        this(topology.productLine(), egress);
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress) {
        this.egress = egress;
        processor = new TradingCoreOwner(productLine, this::publishResponse);
    }

    /** Starts the Owner thread after the Aeron Cluster service has started. */
    public void start(Cluster cluster, byte[] restored) {
        this.cluster = cluster;
        inputProduced = 0;
        inputConsumed = 0;
        ownerPollCalls = ownerNoProgressPolls = ownerNoProgressNanos = ownerGateSkippedPending = 0;
        failure = null;
        stopping = false;
        ownerEpoch = 0;
        logContext = new OwnerLogContext(cluster.memberId(), cluster.timeUnit(), cluster.role(),
                cluster.time(), cluster.logPosition());
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
    public void enqueue(CoreMessage command, ClientSession session, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        enqueue(command, session, timestamp, position, CoreMatchingPhaseMetrics.sampleStart(command.header()), fingerprint);
    }

    void enqueue(CoreMessage command, ClientSession session, long timestamp, long position,
                 long enqueuedNanos, CommandFingerprint fingerprint) {
        enqueue(new Input(command, session, timestamp, position, null, enqueuedNanos, fingerprint));
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
        }, true);
    }

    public byte[] captureSnapshot(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return processor.captureSnapshot(Math.max(1, position));
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
            input.clear();
            if (ownerPollDiagnostics) {
                System.out.printf("Aeron core owner-poll calls=%d noProgress=%d noProgressNanos=%d "
                                + "gateSkippedPending=%d%n",
                        ownerPollCalls, ownerNoProgressPolls, ownerNoProgressNanos, ownerGateSkippedPending);
            }
        }
    }

    public void checkFailure() {
        if (failure != null) throw new AgentTerminationException(failure);
    }

    @Override
    public void run() {
        ownerIdle.bindOwner();
        boolean initialized = false;
        try {
            while (!stopping) {
                int work = 0;
                for (int i = 0; i < 64 && !stopping; i++) {
                    Input next = input.poll();
                    if (next == null) break;
                    if (next.action != null) {
                        next.action.run();
                        initialized = true;
                    } else {
                        logContext.timestamp = next.timestamp;
                        logContext.position = next.position;
                        CoreMatchingPhaseMetrics.recordBoundary(
                                "transportToOwner", next.command.header(), next.enqueuedNanos);
                        processor.enqueueCommittedCommand(next.session, next.command, next.timestamp,
                                next.position, next.fingerprint);
                        inputConsumed += next.command.payloadLength();
                    }
                    work++;
                }
                if (initialized && !stopping) {
                    boolean ownerWorkAvailable = processor.ownerWorkAvailable();
                    if (ownerWorkAvailable) {
                        long pollStarted = ownerPollDiagnostics ? System.nanoTime() : 0;
                        int polled = processor.pollCommands();
                        if (ownerPollDiagnostics) {
                            ownerPollCalls++;
                            if (polled == 0) {
                                ownerNoProgressPolls++;
                                ownerNoProgressNanos += System.nanoTime() - pollStarted;
                            }
                        }
                        work += polled;
                    } else if (ownerPollDiagnostics && processor.pendingCommandCount() != 0) {
                        ownerGateSkippedPending++;
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

    private void enqueue(Input event) {
        int bytes = event.command == null ? 0 : event.command.payloadLength();
        if (bytes > INPUT_BYTES) throw new IllegalStateException("command exceeds ingress byte budget");
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (bytes > INPUT_BYTES - (inputProduced - inputConsumed) || !input.offer(event)) {
            awaitProgress(deadline);
        }
        inputProduced += bytes;
        ownerIdle.signal();
    }

    private boolean ownerWorkAvailable() {
        return !input.isEmpty() || processor.ownerCompletionAvailable();
    }

    private <T> T boundary(Supplier<T> action, boolean finish) {
        var completion = new CompletableFuture<T>();
        enqueue(new Input(null, null, 0, 0, () -> {
            try {
                if (finish) processor.finishCommands();
                completion.complete(action.get());
            } catch (Throwable fatal) {
                completion.completeExceptionally(fatal);
                throw fatal;
            }
        }, 0, null));
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!completion.isDone()) awaitProgress(deadline);
        return completion.join();
    }

    private void publishResponse(ClientSession session, CoreMessageHeader header, CoreResponse response,
                                 long committedSequence) {
        egress.publishResponse(processor, session, header, response, committedSequence, ownerEpoch);
    }

    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        }
        egress.drainResponses(processor);
        cluster.idleStrategy().idle();
    }

    private record Input(CoreMessage command, ClientSession session, long timestamp, long position,
                         Runnable action, long enqueuedNanos, CommandFingerprint fingerprint) {}
}
