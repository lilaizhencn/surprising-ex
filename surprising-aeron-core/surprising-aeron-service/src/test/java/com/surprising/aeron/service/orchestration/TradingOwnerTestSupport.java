package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.aeron.service.orchestration.pipeline.ClusterCommandWindow;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.function.BooleanSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/** Test-only callback and response fixture for the real trading Owner. */
public final class TradingOwnerTestSupport implements ClusteredService {
    private final TradingCoreOwner owner;
    private final CoreMessageFlyweightDecoder messageDecoder = new CoreMessageFlyweightDecoder();
    private Cluster cluster;
    private OwnerLogContext logContext;
    private final ClusterServiceEgress egress = new ClusterServiceEgress((ignored, session) ->
            new ClusterServiceEgress.SessionEgress() {
                public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                    return session.offer(source, offset, length);
                }
                public void close() { }
            });
    private long epoch;

    public TradingOwnerTestSupport(ProductLine productLine) {
        owner = new TradingCoreOwner(productLine, this::respond);
    }

    private void respond(long sessionId, com.surprising.aeron.protocol.CoreMessageHeader header,
                         CoreResponse response, long sequence) {
        egress.publishResponse(owner, sessionId, header, response, sequence, epoch);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        egress.start(cluster);
        epoch = 0;
        logContext = new OwnerLogContext(cluster.role(), cluster.time(), cluster.logPosition(),
                cluster.idleStrategy());
        owner.start(logContext);
        if (snapshotImage != null) {
            owner.loadSnapshot(snapshotImage::poll, snapshotImage::isEndOfStream);
        }
    }

    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        CoreMessage request;
        try {
            request = messageDecoder.decode(buffer, offset, length);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        acceptCommittedCommand(session, request, timestamp, header.position());
    }

    public void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position) {
        enqueueCommittedCommand(session, request, timestamp, position, null);
        owner.pollCommands();
    }

    public void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint) {
        enqueueCommittedCommand(session, request, timestamp, position, fingerprint);
        owner.pollCommands();
    }

    public void enqueueCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                 CommandFingerprint fingerprint) {
        logContext.timestamp = timestamp;
        logContext.position = position;
        long sessionId = session == null ? ClusterServiceEgress.NO_SESSION_ID : egress.transportSession(session).id();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!owner.ingress().offer(request, sessionId, timestamp, position, null,
                0L, fingerprint)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Owner ingress did not advance");
            owner.pollCommands();
            egress.drainResponses(owner);
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        var snapshot = owner.captureSnapshotSections(Math.max(1, cluster.logPosition()));
        for (byte[] chunk : snapshot.chunks()) {
            UnsafeBuffer buffer = new UnsafeBuffer(chunk);
            for (int offset = 0; offset < chunk.length;) {
                int length = Math.min(publication.maxPayloadLength(), chunk.length - offset);
                long result;
                while ((result = publication.offer(buffer, offset, length)) < 0) {
                    if (!retryableOffer(result)) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    cluster.idleStrategy().idle();
                }
                offset += length;
            }
        }
    }

    public byte[] captureSnapshot(long snapshotId) {
        return owner.captureSnapshot(snapshotId);
    }

    public byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return owner.captureSnapshot(snapshotId, deadlineNanos);
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        epoch = egress.onRoleChange(newRole);
        logContext.role = newRole;
        owner.roleChange(newRole);
    }

    @Override
    public int doBackgroundWork(long nowNs) {
        return owner.pollBackgroundWork(nowNs);
    }

    @Override
    public void onTerminate(Cluster ignored) {
        TradingCoreRuntime runtime = owner.currentStateOrNull();
        try { owner.terminate(); }
        catch (RuntimeException failure) {
            if (runtime == null || runtime.fatalFailure == null) throw failure;
        }
        finally { egress.clear(); }
        cluster = null;
        logContext = null;
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        owner.sessionOpened();
        egress.drainResponses(owner);
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        if (session != null) { egress.closeSession(session); owner.sessionClosed(session.id()); }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Historical timer callbacks do not produce trading operations.
    }

    public int pendingCommandCount() { return owner.pendingCommandCount(); }
    public String stalledCommandDiagnostics() { return owner.stalledCommandDiagnostics(); }
    public boolean ownerWorkAvailable() { return owner.ownerWorkAvailable(); }
    public boolean ownerCompletionAvailable() { return owner.ownerCompletionAvailable(); }
    public void ownerCompletionSignal(Runnable signal) { owner.ownerCompletionSignal(signal); }
    public int pollCommands() {
        int work = owner.pollCommands();
        return work + egress.drainResponses(owner);
    }
    public void finishCommands() { owner.finishCommands(); egress.drainResponses(owner); }
    public int commandWindowSize() { return owner.commandWindowSize(); }
    public int commandWindowHighWaterMark() { return owner.commandWindowHighWaterMark(); }
    public ClusterCommandWindow commandWindow() { return owner.commandWindow(); }
    public ManyToOneConcurrentArrayQueue<com.surprising.aeron.protocol.RealtimeFrame> snapshotRequests() {
        return owner.snapshotRequests();
    }
    public TradingCoreRuntime state() { return owner.state(); }
    public void releaseResponse(CoreResponse response) { owner.releaseResponse(response); }

    public void attachRealtime(com.surprising.aeron.client.RealtimeOutbox outbox) {
        owner.attachRealtime(outbox, null);
    }

    public void restoreSnapshot(byte[] snapshot) {
        owner.restoreSnapshot(snapshot);
    }

    public void loadSnapshot(SnapshotFragmentSource source, BooleanSupplier endOfStream) {
        owner.loadSnapshot(source::poll, endOfStream);
    }

    public static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        TradingCoreOwner.ensureSnapshotCapacity(currentLength, fragmentLength);
    }

    public @FunctionalInterface
    interface SnapshotFragmentSource {
        public int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }
}
