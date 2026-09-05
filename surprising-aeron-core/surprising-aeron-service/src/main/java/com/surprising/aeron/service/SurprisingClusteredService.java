package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
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
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

public final class SurprisingClusteredService implements ClusteredService {

    private static final long EGRESS_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
    private static final int MATCHING_COMPLETION_BATCH_SIZE = 64;
    private static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;

    private final ProductLine productLine;
    private CoreProbeState state;
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private byte[] responseScratch = new byte[4 * 1024];
    private final UnsafeBuffer responseBuffer = new UnsafeBuffer(responseScratch);
    // Only the active log callback owns these slots; no command survives its return.
    private long responseSequence;
    private CoreResponse matchingResponse;
    private final CoreProbeState.MatchingCommitHandler matchingCommitHandler = this::completeMatching;
    private long snapshotFenceNotReadyCount;
    private long snapshotFenceTimeoutCount;

    public SurprisingClusteredService(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        if (state == null) state = new CoreProbeState(productLine);
        responseSequence = 0;
        matchingResponse = null;
        snapshotFenceNotReadyCount = 0;
        snapshotFenceTimeoutCount = 0;
        idleStrategy = cluster.idleStrategy();
        System.out.printf("Aeron core role productLine=%s role=%s%n", productLine, cluster.role());
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
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
        } catch (IllegalArgumentException exception) {
            return;
        }
        try {
            processRequest(session, request, timestamp, header.position());
        } catch (org.agrona.concurrent.AgentTerminationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // AgentRunner otherwise logs ordinary exceptions and may consume the next message.
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        }
    }

    private void processRequest(ClientSession session, CoreMessage request, long timestamp, long clusterPosition) {
        state.assertClusterCallbackComplete();
        long deadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
        CoreResponse result = state.apply(request, timestamp, clusterPosition);
        responseSequence = state.matchingSequence(request.header().commandId());
        matchingResponse = null;
        idleStrategy.reset();
        // Includes matcher children created by risk/trigger commands, even without a client session.
        while (state.firstPendingMatchingSequence() != 0) {
            int work = state.commitReadyMatching(MATCHING_COMPLETION_BATCH_SIZE,
                    timestamp, clusterPosition, false, matchingCommitHandler);
            idleCommand(work, deadline);
        }
        if (responseSequence != 0) {
            if (matchingResponse == null) throw new IllegalStateException("missing terminal callback result");
            result = matchingResponse;
        }
        responseSequence = 0;
        matchingResponse = null;
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            CoreResponse queryResult = state.takeQueryResult(querySequence);
            while (queryResult == null) {
                idleCommand(0, deadline);
                queryResult = state.takeQueryResult(querySequence);
            }
            result = queryResult;
        }
        state.assertClusterCallbackComplete();
        if (session != null) {
            offerResponse(session, request.header().response(responseType(request.header())), result);
        }
    }

    private void completeMatching(long sequence, CoreResponse response) {
        if (sequence == responseSequence) matchingResponse = response;
    }

    private void idleCommand(int work, long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // Operational failure, never a replicated business rejection or a deferred completion.
            throw new IllegalStateException("cluster log callback completion interrupted or timed out");
        }
        idleStrategy.idle(work);
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                captureSnapshotSections(Math.max(1, cluster.logPosition()), snapshotDeadline());
        idleStrategy.reset();
        for (byte[] sectionChunk : snapshot.chunks()) {
            UnsafeBuffer buffer = new UnsafeBuffer(sectionChunk);
            int offset = 0;
            while (offset < sectionChunk.length) {
                int chunkLength = Math.min(snapshotPublication.maxPayloadLength(), sectionChunk.length - offset);
                long result;
                while ((result = snapshotPublication.offer(buffer, offset, chunkLength)) < 0) {
                    if (!retryableOffer(result)) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    idleStrategy.idle();
                }
                offset += chunkLength;
            }
        }
    }

    byte[] captureSnapshot(long snapshotId) {
        return captureSnapshot(snapshotId, snapshotDeadline());
    }

    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return captureSnapshotSections(snapshotId, deadlineNanos).toByteArray();
    }

    private long snapshotDeadline() {
        return Math.addExact(System.nanoTime(),
                java.util.concurrent.TimeUnit.SECONDS.toNanos(SNAPSHOT_TIMEOUT_SECONDS));
    }

    private SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long snapshotId, long deadlineNanos) {
        try {
            state.assertClusterCallbackComplete();
            state.beginSnapshot(snapshotId, deadlineNanos);
            idleStrategy.reset();
            while (true) {
                SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = state.pollSnapshotSections(
                        cluster == null ? 0 : cluster.time(),
                        cluster == null ? 0 : cluster.logPosition(),
                        System.nanoTime());
                if (snapshot != null) return snapshot;
                idleStrategy.idle();
            }
        } catch (CoreProbeState.SnapshotNotReadyException notReady) {
            snapshotFenceNotReadyCount++;
            throw notReady;
        } catch (CoreProbeState.SnapshotFenceTimeoutException timeout) {
            snapshotFenceTimeoutCount++;
            throw timeout;
        }
    }

    public long snapshotFenceNotReadyCount() {
        return snapshotFenceNotReadyCount;
    }

    public long snapshotFenceTimeoutCount() {
        return snapshotFenceTimeoutCount;
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        System.out.printf("Aeron core role-change productLine=%s role=%s%n", productLine, newRole);
    }

    @Override
    public int doBackgroundWork(long nowNs) {
        // Aeron 1.53 also prohibits ClientSession.offer from this lifecycle callback.
        return 0;
    }

    @Override
    public void onTerminate(Cluster cluster) {
        responseSequence = 0;
        matchingResponse = null;
        this.cluster = null;
        if (state != null) {
            state.close();
            state = null;
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Reliable cluster timers are reserved for replicated business-time events.
        // Matching and query completion finish in the originating log callback.
    }

    CoreProbeState state() {
        if (state == null) throw new IllegalStateException("clustered service is not started");
        return state;
    }

    private void loadSnapshot(Image snapshotImage) {
        loadSnapshot(snapshotImage::poll, snapshotImage::isEndOfStream);
    }

    void loadSnapshot(SnapshotFragmentSource snapshotSource, BooleanSupplier endOfStream) {
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        FragmentHandler fragmentHandler = (buffer, offset, length, header) ->
                recovery.accept(buffer, offset, length);
        while (!endOfStream.getAsBoolean()) {
            int fragments = snapshotSource.poll(fragmentHandler, 10);
            idleStrategy.idle(fragments);
        }
        replaceState(recovery.decode(productLine));
    }

    static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        if (currentLength < 0 || fragmentLength < 0
                || currentLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - fragmentLength) {
            throw new IllegalStateException("Aeron core snapshot exceeds maximum size");
        }
    }

    void restoreSnapshot(byte[] snapshot) {
        replaceState(CoreProbeState.fromSnapshot(productLine, snapshot));
    }

    private void replaceState(CoreProbeState restored) {
        state.close();
        state = restored;
    }

    @FunctionalInterface
    interface SnapshotFragmentSource {
        int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    private void offerResponse(ClientSession session, CoreMessageHeader header, CoreResponse response) {
        if (session.isClosing()) return;
        int length = CoreMessageCodec.encodedResponseLength(response);
        if (responseScratch.length < length) {
            responseScratch = new byte[length];
            responseBuffer.wrap(responseScratch);
        }
        CoreMessageCodec.encodeResponse(header, response, state.committedCoreSequence(), responseScratch);
        long deadline = System.nanoTime() + EGRESS_TIMEOUT_NANOS;
        idleStrategy.reset();
        while (!session.isClosing()) {
            long result = session.offer(responseBuffer, 0, length);
            if (result >= 0) return;
            if (!retryableOffer(result) || System.nanoTime() >= deadline) {
                // The business operation is already terminal. A missing response is UNKNOWN,
                // never a rollback or a new command; retained commandId results remain queryable.
                session.close();
                return;
            }
            idleStrategy.idle();
        }
    }

    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }

    private static CoreMessageType responseType(com.surprising.aeron.protocol.CoreMessageHeader requestHeader) {
        return switch (requestHeader.messageType()) {
            case USER_STATE_QUERY -> CoreMessageType.USER_STATE_RESULT;
            case ORDER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY -> CoreMessageType.ORDER_STATE_RESULT;
            case BOOK_STATE_QUERY -> CoreMessageType.BOOK_STATE_RESULT;
            case ORDER_BOOK_BOOTSTRAP_QUERY -> CoreMessageType.ORDER_BOOK_BOOTSTRAP_RESULT;
            case LIQUIDATION_WORK_QUERY -> CoreMessageType.LIQUIDATION_WORK_RESULT;
            case USER_OPEN_ORDERS_QUERY -> CoreMessageType.USER_OPEN_ORDERS_RESULT;
            case TRIGGER_ORDER_QUERY -> CoreMessageType.TRIGGER_ORDER_RESULT;
            case USER_OPEN_TRIGGER_ORDERS_QUERY -> CoreMessageType.USER_OPEN_TRIGGER_ORDERS_RESULT;
            case FUNDING_PROGRESS_QUERY -> CoreMessageType.FUNDING_PROGRESS_RESULT;
            case SETTLEMENT_PROGRESS_QUERY -> CoreMessageType.SETTLEMENT_PROGRESS_RESULT;
            case COMMAND_RESULT_QUERY -> CoreMessageType.COMMAND_RESULT_RESULT;
            case RISK_SCAN_CONTROL_QUERY -> CoreMessageType.RISK_SCAN_CONTROL_RESULT;
            default -> requestHeader.kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
