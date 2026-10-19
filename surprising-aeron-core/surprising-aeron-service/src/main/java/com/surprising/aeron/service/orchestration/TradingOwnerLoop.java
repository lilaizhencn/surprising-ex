package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.config.TradingCpuAffinity;

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
    private final OwnerIngressRing input;
    private final ClusterServiceEgress egress;
    private final TradingCoreOwner coreOwner;
    private final OwnerIdleStrategy ownerIdle = new OwnerIdleStrategy(this::ownerWorkAvailable);
    private volatile Throwable failure;
    private boolean stopping;
    private Thread ownerThread;
    private final TradingCpuAffinity affinityConfiguration;
    private TradingCpuAffinity.Binding cpuAffinity;
    private Cluster cluster;
    private OwnerLogContext logContext;
    private long ownerEpoch;
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress) {
        this(topology.productLine(), egress);
    }

    @Autowired
    public TradingOwnerLoop(ClusterTopology topology, ClusterServiceEgress egress,
            TradingCpuAffinity affinity) {
        this(topology.productLine(), egress, affinity);
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress) {
        this(productLine, egress, TradingCpuAffinity.disabled());
    }

    public TradingOwnerLoop(ProductLine productLine, ClusterServiceEgress egress,
            TradingCpuAffinity affinity) {
        this.affinityConfiguration = java.util.Objects.requireNonNull(affinity);
        this.egress = egress;
        coreOwner = new TradingCoreOwner(productLine, this::publishResponse);
        input = coreOwner.ingress();
    }

    /** Starts the Owner thread after the Aeron Cluster service has started. */
    public void start(Cluster cluster, SectionedCoreSnapshotCodec.RecoveryBuffer restored) {
        this.cluster = cluster;
        failure = null;
        stopping = false;
        ownerEpoch = 0;
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition(),
                new BusySpinIdleStrategy());
        ownerThread = new Thread(this, "trading-owner-" + cluster.memberId());
        ownerThread.start();
        boundary(() -> {
            coreOwner.start(logContext);
            if (restored != null) coreOwner.restoreSnapshot(restored);
            coreOwner.ownerCompletionSignal(ownerIdle::signal);
            // Start all workers before narrowing Owner's mask so Ledger and other threads stay unbound.
            coreOwner.state().activate(affinityConfiguration);
            cpuAffinity = affinityConfiguration.owner();
            return null;
        }, false);
    }

    /** Transfers one replicated command from the Aeron callback thread to the Owner thread. */
    public void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                        CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, 0L, fingerprint);
    }

    void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                 long enqueuedNanos, CommandFingerprint fingerprint) {
        enqueue(command, sessionId, timestamp, position, null, enqueuedNanos, fingerprint, DEADLINE_NS);
    }

    public int drainResponses() {
        return egress.drainResponses(coreOwner);
    }

    public void onRoleChange(Cluster.Role role, long nextEpoch) {
        boundary(() -> {
            logContext.role = role;
            ownerEpoch = nextEpoch;
            coreOwner.roleChange(role);
            return null;
        }, true, ROLE_CHANGE_DEADLINE_NS);
    }

    public byte[] captureSnapshot(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return coreOwner.captureSnapshot(Math.max(1, position));
        }, true);
    }

    /** Captures chunks for direct Aeron publication without a second full-size byte array. */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long position, long timestamp) {
        return boundary(() -> {
            logContext.position = position;
            logContext.timestamp = timestamp;
            return coreOwner.captureSnapshotSections(Math.max(1, position));
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
            // The callback producer now owns cleanup. Cover a final offer racing Owner failure.
            input.discard();
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
                var next = input.first();
                if (next != null && next.action != null) {
                    try {
                        next.action.run();
                        initialized = true;
                    } finally { input.remove(); }
                    work++;
                } else if (initialized && !stopping && coreOwner.ownerWorkAvailable()) {
                    work += coreOwner.pollCommands();
                }
                ownerIdle.idle(work, coreOwner.pendingCommandCount() != 0);
            }
        } catch (Throwable fatal) {
            failure = fatal;
        } finally {
            try {
                coreOwner.terminate();
            } catch (Throwable fatal) {
                if (failure == null) failure = fatal;
            } finally {
                if (cpuAffinity != null) {
                    try { cpuAffinity.close(); }
                    catch (Throwable fatal) { if (failure == null) failure = fatal; }
                    cpuAffinity = null;
                }
            }
        }
    }

    private void enqueue(CoreMessage command, long sessionId, long timestamp, long position,
                         Runnable action, long enqueuedNanos, CommandFingerprint fingerprint, long timeoutNanos) {
        checkFailure();
        if (input.offer(command, sessionId, timestamp, position, action, enqueuedNanos, fingerprint)) {
            ownerIdle.signal();
            return;
        }
        long started = System.nanoTime();
        long deadline = started + timeoutNanos;
        boolean reported = false;
        while (true) {
            awaitProgress(deadline);
            checkFailure();
            if (input.offer(command, sessionId, timestamp, position, action, enqueuedNanos, fingerprint)) {
                ownerIdle.signal();
                return;
            }
            if (!reported && System.nanoTime() - started >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                LOG.error("Core owner input has made no capacity for 5 seconds queued={} bytes={} ownerFailure={}",
                        input.size(), input.retainedBytes(), failure);
                reported = true;
            }
        }
    }

    private boolean ownerWorkAvailable() {
        var next = input.first();
        return next != null && next.action != null || coreOwner.ownerWorkAvailable();
    }

    private <T> T boundary(Supplier<T> action, boolean finish) {
        return boundary(action, finish, DEADLINE_NS);
    }

    private <T> T boundary(Supplier<T> action, boolean finish, long timeoutNanos) {
        var completion = new CompletableFuture<T>();
        enqueue(null, NO_SESSION_ID, 0, 0, () -> {
            try {
                if (finish) coreOwner.finishCommands();
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
        egress.publishResponse(coreOwner, sessionId, header, response, committedSequence, ownerEpoch);
    }

    private void awaitProgress(long deadline) {
        checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or ingress deadline");
        }
        egress.drainResponses(coreOwner);
        cluster.idleStrategy().idle();
    }

}
