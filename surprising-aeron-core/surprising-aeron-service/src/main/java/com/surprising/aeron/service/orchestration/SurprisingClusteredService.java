package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResponse;
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

/**
 * 兼容用的 Aeron Cluster 回调适配器。
 *
 * <p>生产节点使用 {@link com.surprising.aeron.service.cluster.AeronTradingClusterService} 直接连接
 * {@link TradingCoreOwner}。这个类只保留旧的 ClusteredService 入口，供现有测试和
 * 独立回放工具使用，不拥有交易状态或命令推进逻辑。</p>
 */
public final class SurprisingClusteredService implements ClusteredService {
    private final TradingCoreOwner owner;
    private Cluster cluster;

    public SurprisingClusteredService(ProductLine productLine) {
        this(productLine, null);
    }

    SurprisingClusteredService(ProductLine productLine, TradingCoreOwner.ResponseSink responseSink) {
        owner = new TradingCoreOwner(productLine, responseSink);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        owner.start(cluster);
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
            request = CoreMessageFlyweightDecoder.decode(buffer, offset, length);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        acceptCommittedCommand(session, request, timestamp, header.position());
    }

    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position) {
        owner.acceptCommittedCommand(session, request, timestamp, position);
    }

    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint) {
        owner.acceptCommittedCommand(session, request, timestamp, position, fingerprint);
    }

    void enqueueCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                 CommandFingerprint fingerprint) {
        owner.enqueueCommittedCommand(session, request, timestamp, position, fingerprint);
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication publication) {
        byte[] snapshot = owner.captureSnapshot(Math.max(1, cluster.logPosition()));
        UnsafeBuffer buffer = new UnsafeBuffer(snapshot);
        for (int offset = 0; offset < snapshot.length;) {
            int length = Math.min(publication.maxPayloadLength(), snapshot.length - offset);
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

    byte[] captureSnapshot(long snapshotId) {
        return owner.captureSnapshot(snapshotId);
    }

    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return owner.captureSnapshot(snapshotId, deadlineNanos);
    }

    long snapshotFenceNotReadyCount() {
        return owner.snapshotFenceNotReadyCount();
    }

    long snapshotFenceTimeoutCount() {
        return owner.snapshotFenceTimeoutCount();
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        owner.roleChange(newRole);
    }

    @Override
    public int doBackgroundWork(long nowNs) {
        return owner.pollBackgroundWork(nowNs);
    }

    @Override
    public void onTerminate(Cluster ignored) {
        owner.terminate();
        cluster = null;
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        owner.sessionOpened();
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        if (session != null) owner.sessionClosed(session.id());
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Historical timer callbacks do not produce trading operations.
    }

    int pendingCommandCount() { return owner.pendingCommandCount(); }
    boolean ownerWorkAvailable() { return owner.ownerWorkAvailable(); }
    boolean ownerCompletionAvailable() { return owner.ownerCompletionAvailable(); }
    void ownerCompletionSignal(Runnable signal) { owner.ownerCompletionSignal(signal); }
    int pollCommands() { return owner.pollCommands(); }
    void finishCommands() { owner.finishCommands(); }
    int commandWindowSize() { return owner.commandWindowSize(); }
    int commandWindowHighWaterMark() { return owner.commandWindowHighWaterMark(); }
    ClusterCommandWindow commandWindow() { return owner.commandWindow(); }
    ManyToOneConcurrentArrayQueue<com.surprising.aeron.protocol.RealtimeFrame> snapshotRequests() {
        return owner.snapshotRequests();
    }
    TradingCoreRuntime state() { return owner.state(); }
    void releaseResponse(CoreResponse response) { owner.releaseResponse(response); }

    void attachRealtime(com.surprising.aeron.client.RealtimeOutbox outbox) {
        owner.attachRealtime(outbox, null);
    }

    void restoreSnapshot(byte[] snapshot) {
        owner.restoreSnapshot(snapshot);
    }

    void loadSnapshot(SnapshotFragmentSource source, BooleanSupplier endOfStream) {
        owner.loadSnapshot(source::poll, endOfStream);
    }

    static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        TradingCoreOwner.ensureSnapshotCapacity(currentLength, fragmentLength);
    }

    @FunctionalInterface
    interface SnapshotFragmentSource {
        int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }
}
