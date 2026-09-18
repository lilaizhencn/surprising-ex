package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.io.ByteArrayOutputStream;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.AgentTerminationException;
import org.agrona.concurrent.UnsafeBuffer;

/** Aeron Cluster callback adapter for the trading Owner and its session egress. */
public final class AeronTradingClusterService implements ClusteredService {
    private static final long DEADLINE_NS = 30_000_000_000L;

    private final ClusterServiceEgress egress;
    private final TradingOwnerLoop owner;
    private Cluster cluster;

    public AeronTradingClusterService(ProductLine productLine) {
        this(productLine, SessionResponsePublication::new);
    }

    AeronTradingClusterService(ProductLine productLine, ClusterServiceEgress.EgressFactory egressFactory) {
        egress = new ClusterServiceEgress(egressFactory);
        owner = new TradingOwnerLoop(productLine, egress);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshot) {
        this.cluster = cluster;
        egress.start(cluster);
        owner.start(cluster, readSnapshot(snapshot));
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer,
            int offset, int length, Header header) {
        egress.flushSessionClosures();
        CoreMessage command;
        try {
            command = CoreMessageFlyweightDecoder.decode(buffer, offset, length);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        owner.enqueue(command, session == null ? null : egress.transportSession(session), timestamp,
                header.position(), CoreMatchingPhaseMetrics.sampleStart(command.header()),
                command.header().kind() == WireMessageKind.COMMAND ? CommandFingerprint.of(command) : null);
        owner.drainResponses();
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        owner.checkFailure();
        if (session != null) egress.transportSession(session);
        egress.flushSessionClosures();
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        egress.closeSession(session);
        owner.checkFailure();
    }

    /** Old log timers do not drive trading state. */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        owner.checkFailure();
    }

    /** Only publishes immutable Owner responses; it never advances or reads trading state. */
    @Override
    public int doBackgroundWork(long nowNs) {
        owner.checkFailure();
        try {
            return owner.drainResponses();
        } catch (RuntimeException fatal) {
            throw new AgentTerminationException(fatal);
        }
    }

    @Override
    public void onRoleChange(Cluster.Role role) {
        owner.onRoleChange(role, egress.onRoleChange(role));
    }

    @Override
    public void onNewLeadershipTermEvent(long termId, long logPosition, long timestamp,
            long termBaseLogPosition, int leaderMemberId, int logSessionId,
            java.util.concurrent.TimeUnit timeUnit, int appVersion) {
        egress.leadershipTerm(termId);
        owner.checkFailure();
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        byte[] snapshot = owner.captureSnapshot(cluster.logPosition(), cluster.time());
        UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
        long deadline = System.nanoTime() + DEADLINE_NS;
        for (int offset = 0; offset < snapshot.length;) {
            int length = Math.min(publication.maxPayloadLength(), snapshot.length - offset);
            long result = publication.offer(buffer, offset, length);
            if (result >= 0) {
                offset += length;
            } else {
                if (result != io.aeron.Publication.BACK_PRESSURED
                        && result != io.aeron.Publication.ADMIN_ACTION
                        && result != io.aeron.Publication.NOT_CONNECTED) {
                    throw new IllegalStateException("snapshot publication failed: " + result);
                }
                awaitProgress(deadline);
            }
        }
    }

    byte[] captureSnapshot() {
        return owner.captureSnapshot(cluster.logPosition(), cluster.time());
    }

    @Override
    public void onTerminate(Cluster ignored) {
        try {
            owner.terminate();
        } finally {
            egress.clear();
            cluster = null;
        }
    }

    private void awaitProgress(long deadline) {
        owner.checkFailure();
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new AgentTerminationException("trading owner boundary or snapshot deadline");
        }
        owner.drainResponses();
        cluster.idleStrategy().idle();
    }

    private byte[] readSnapshot(Image snapshot) {
        if (snapshot == null) return null;
        var bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + DEADLINE_NS;
        while (!snapshot.isEndOfStream()) {
            int work = snapshot.poll((buffer, offset, length, header) -> {
                TradingCoreOwner.ensureSnapshotCapacity(bytes.size(), length);
                byte[] chunk = new byte[length];
                buffer.getBytes(offset, chunk);
                bytes.writeBytes(chunk);
            }, 10);
            if (System.nanoTime() > deadline) throw new IllegalStateException("snapshot recovery deadline");
            cluster.idleStrategy().idle(work);
        }
        return bytes.toByteArray();
    }
}
