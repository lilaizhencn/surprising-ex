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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

public final class SurprisingClusteredService implements ClusteredService {

    private static final int MAX_PENDING_EGRESS_PER_SESSION = 64;
    private static final int MAX_PENDING_EGRESS_BYTES_PER_SESSION = 16 * 1024 * 1024;
    private static final int MAX_RECYCLED_EGRESS_BYTES_PER_SESSION = 64 * 1024;
    private static final int EGRESS_DRAIN_BUDGET = 16;
    private static final int MATCHING_COMPLETION_BATCH_SIZE = 64;
    private static final int DEFERRED_INGRESS_BATCH_SIZE = 64;
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;

    private final ProductLine productLine;
    private CoreProbeState state;
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private final Map<Long, PendingEgress> pendingEgress = new HashMap<>();
    private final Set<Long> activeEgressSessions = new HashSet<>();
    private final Map<Long, ArrayDeque<PendingClient>> pendingClients = new HashMap<>();
    private int pendingQueryCount;
    private final ArrayDeque<DeferredInbound> deferredInbound = new ArrayDeque<>();
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
        pendingEgress.clear();
        activeEgressSessions.clear();
        pendingClients.clear();
        pendingQueryCount = 0;
        deferredInbound.clear();
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
        processRequest(session, request, timestamp, header.position());
    }

    private void processRequest(ClientSession session, CoreMessage request, long timestamp, long clusterPosition) {
        if (!deferredInbound.isEmpty() || shouldDeferWhileMatching(request)) {
            deferredInbound.addLast(new DeferredInbound(session, request, timestamp, clusterPosition));
            return;
        }
        processRequestNow(session, request, timestamp, clusterPosition);
    }

    private boolean shouldDeferWhileMatching(CoreMessage request) {
        if (state.firstPendingMatchingSequence() == 0) return false;
        if (CoreProbeState.isNonFencingQuery(request)) return false;
        return request.header().kind() != WireMessageKind.COMMAND
                || (!CoreProbeState.isMatchingCommand(request.header().messageType())
                && request.header().messageType() != CoreMessageType.ACK_EXPORT);
    }

    private void processRequestNow(
            ClientSession session, CoreMessage request, long timestamp, long clusterPosition) {
        CoreResponse result = state.apply(request, timestamp, clusterPosition);
        long matchingSequence = state.matchingSequence(request.header().commandId());
        if (matchingSequence > 0) {
            if (session != null) {
                pendingClients.computeIfAbsent(matchingSequence, ignored -> new ArrayDeque<>())
                        .addLast(new PendingClient(session, request.header()));
            }
            return;
        }
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            CoreResponse queryResult = state.takeQueryResult(querySequence);
            if (queryResult != null) {
                result = queryResult;
            } else {
                if (session != null) {
                    pendingClients.computeIfAbsent(querySequence, ignored -> {
                                pendingQueryCount++;
                                return new ArrayDeque<>();
                            })
                            .addLast(new PendingClient(session, request.header()));
                }
                return;
            }
        }
        if (session != null) {
            offerResponse(session, request.header().response(responseType(request.header())), result);
        }
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
            drainIngressBeforeSnapshot(deadlineNanos);
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
        int work = 0;
        long clusterTimestamp = cluster == null ? 0 : cluster.time();
        long clusterPosition = cluster == null ? 0 : cluster.logPosition();
        work += commitReadyMatching(clusterTimestamp, clusterPosition, false);
        work += drainDeferredIngress();
        Iterator<Long> sessions = activeEgressSessions.iterator();
        while (sessions.hasNext()) {
            Long sessionId = sessions.next();
            PendingEgress egress = pendingEgress.get(sessionId);
            if (egress == null || egress.session.isClosing()) {
                if (egress != null) egress.clearQueue();
                sessions.remove();
                work++;
                continue;
            }
            work += drain(egress);
            if (egress.queue.isEmpty()) sessions.remove();
        }
        if (pendingQueryCount == 0) return work;
        Iterator<Map.Entry<Long, ArrayDeque<PendingClient>>> queries = pendingClients.entrySet().iterator();
        while (queries.hasNext()) {
            Map.Entry<Long, ArrayDeque<PendingClient>> entry = queries.next();
            if (entry.getKey() >= 0) continue;
            CoreResponse queryResult = state.takeQueryResult(entry.getKey());
            if (queryResult == null) continue;
            for (PendingClient pendingClient : entry.getValue()) {
                if (!pendingClient.session().isClosing()) {
                    offerResponse(pendingClient.session(), pendingClient.requestHeader().response(
                            responseType(pendingClient.requestHeader())), queryResult);
                    work++;
                }
            }
            queries.remove();
            pendingQueryCount--;
        }
        return work;
    }

    private int commitReadyMatching(long clusterTimestamp, long clusterPosition, boolean awaitFirst) {
        return state.commitReadyMatching(MATCHING_COMPLETION_BATCH_SIZE,
                clusterTimestamp, clusterPosition, awaitFirst, this::completeMatchingClients);
    }

    private void completeMatchingClients(long sequence, CoreResponse response) {
        ArrayDeque<PendingClient> clients = pendingClients.remove(sequence);
        if (clients == null) return;
        for (PendingClient client : clients) {
            if (!client.session().isClosing()) {
                offerResponse(client.session(), client.requestHeader().response(
                        responseType(client.requestHeader())), response);
            }
        }
    }

    private int drainDeferredIngress() {
        int deferred = 0;
        while (!deferredInbound.isEmpty()
                && !shouldDeferWhileMatching(deferredInbound.peekFirst().request())
                && deferred < DEFERRED_INGRESS_BATCH_SIZE) {
            DeferredInbound inbound = deferredInbound.removeFirst();
            processRequestNow(inbound.session(), inbound.request(),
                    inbound.timestamp(), inbound.clusterPosition());
            deferred++;
        }
        return deferred;
    }

    private void drainIngressBeforeSnapshot(long deadlineNanos) {
        while (state.firstPendingMatchingSequence() != 0 || !deferredInbound.isEmpty()) {
            int work = commitReadyMatching(cluster == null ? 0 : cluster.time(),
                    cluster == null ? 0 : cluster.logPosition(), false);
            work += drainDeferredIngress();
            if (work == 0) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new CoreProbeState.SnapshotFenceTimeoutException();
                }
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public void onTerminate(Cluster cluster) {
        pendingEgress.clear();
        activeEgressSessions.clear();
        pendingClients.clear();
        pendingQueryCount = 0;
        deferredInbound.clear();
        this.cluster = null;
        if (state != null) {
            state.close();
            state = null;
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        pendingEgress.put(session.id(), new PendingEgress(session));
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        pendingEgress.remove(session.id());
        activeEgressSessions.remove(session.id());
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Reliable cluster timers are reserved for replicated business-time events.
        // Matching, query completion, and egress retry are synchronous or local background work.
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

    private void offer(ClientSession session, CoreMessage message) {
        PendingEgress egress = pendingEgress(session);
        int length = CoreMessageCodec.encodedLength(message);
        egress.ensureScratch(length);
        CoreMessageCodec.encode(message, egress.scratch);
        offerEncoded(egress, length);
    }

    private void offerResponse(ClientSession session, CoreMessageHeader header,
                               CoreResponse response) {
        PendingEgress egress = pendingEgress(session);
        int length = CoreMessageCodec.encodedResponseLength(response);
        egress.ensureScratch(length);
        CoreMessageCodec.encodeResponse(header, response, state.committedCoreSequence(), egress.scratch);
        offerEncoded(egress, length);
    }

    private PendingEgress pendingEgress(ClientSession session) {
        PendingEgress egress = pendingEgress.get(session.id());
        if (egress == null) {
            egress = new PendingEgress(session);
            pendingEgress.put(session.id(), egress);
            return egress;
        }
        if (egress.session != session) {
            // Aeron can reuse a numeric session id after reconnect. Never retain queued
            // responses or a proxy belonging to the previous session incarnation.
            egress.clearQueue();
            egress.session = session;
            activeEgressSessions.remove(session.id());
        }
        return egress;
    }

    private void offerEncoded(PendingEgress egress, int length) {
        if (!egress.queue.isEmpty()) {
            enqueue(egress, egress.scratch, length);
            return;
        }
        long result = egress.session.offer(egress.scratchBuffer, 0, length);
        if (result < 0 && retryableOffer(result)) {
            enqueue(egress, egress.scratch, length);
        } else if (result < 0) {
            egress.clearQueue();
            egress.session.close();
        }
    }

    private static int drain(PendingEgress egress) {
        if (egress.session.isClosing()) {
            egress.clearQueue();
            return 0;
        }
        int work = 0;
        while (!egress.queue.isEmpty() && work < EGRESS_DRAIN_BUDGET) {
            UnsafeBuffer encoded = egress.queue.peekFirst();
            long result = egress.session.offer(encoded, 0, encoded.capacity());
            if (result < 0 && retryableOffer(result)) {
                break;
            }
            egress.queue.removeFirst();
            egress.queuedBytes -= encoded.capacity();
            egress.recycle(encoded);
            if (result < 0) {
                egress.clearQueue();
                egress.session.close();
                break;
            }
            work++;
        }
        return work;
    }

    private void enqueue(PendingEgress egress, byte[] encoded, int length) {
        if (egress.queue.size() >= MAX_PENDING_EGRESS_PER_SESSION
                || length > MAX_PENDING_EGRESS_BYTES_PER_SESSION - egress.queuedBytes) {
            egress.clearQueue();
            egress.session.close();
            return;
        }
        egress.queue.addLast(egress.copyForQueue(encoded, length));
        egress.queuedBytes += length;
        activeEgressSessions.add(egress.session.id());
    }

    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
    }

    private static final class PendingEgress {
        private ClientSession session;
        private final ArrayDeque<UnsafeBuffer> queue = new ArrayDeque<>();
        private final ArrayDeque<UnsafeBuffer> recycled = new ArrayDeque<>();
        private int queuedBytes;
        private int recycledBytes;
        private byte[] scratch = new byte[4 * 1024];
        private UnsafeBuffer scratchBuffer = new UnsafeBuffer(scratch);

        private PendingEgress(ClientSession session) {
            this.session = session;
        }

        private void ensureScratch(int length) {
            if (scratch.length >= length) return;
            scratch = new byte[length];
            scratchBuffer = new UnsafeBuffer(scratch);
        }

        private UnsafeBuffer copyForQueue(byte[] source, int length) {
            UnsafeBuffer target = recycled.pollFirst();
            if (target != null) recycledBytes -= target.capacity();
            if (target == null || target.capacity() != length) {
                target = new UnsafeBuffer(new byte[length]);
            }
            target.putBytes(0, source, 0, length);
            return target;
        }

        private void recycle(UnsafeBuffer buffer) {
            if (recycled.size() < MAX_PENDING_EGRESS_PER_SESSION
                    && buffer.capacity() <= MAX_RECYCLED_EGRESS_BYTES_PER_SESSION - recycledBytes) {
                recycled.addLast(buffer);
                recycledBytes += buffer.capacity();
            }
        }

        private void clearQueue() {
            queue.clear();
            queuedBytes = 0;
            recycled.clear();
            recycledBytes = 0;
        }
    }

    private record PendingClient(ClientSession session,
                                 com.surprising.aeron.protocol.CoreMessageHeader requestHeader) {
    }

    private record DeferredInbound(ClientSession session, CoreMessage request,
                                   long timestamp, long clusterPosition) {
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
