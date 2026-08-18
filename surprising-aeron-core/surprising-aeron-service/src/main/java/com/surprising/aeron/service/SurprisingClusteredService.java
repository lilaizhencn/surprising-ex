package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;

public final class SurprisingClusteredService implements ClusteredService {

    private static final int MAX_PENDING_EGRESS_PER_SESSION = 64;
    private static final long MATCHING_TIMER_DELAY_MS = 1;

    private final ProductLine productLine;
    private final AtomicReference<Cluster.Role> role = new AtomicReference<>();
    private CoreProbeState state;
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private final Map<Long, PendingEgress> pendingEgress = new HashMap<>();
    private final Map<Long, ArrayDeque<PendingClient>> pendingClients = new HashMap<>();

    public SurprisingClusteredService(ProductLine productLine) {
        this.productLine = productLine;
        this.state = new CoreProbeState(productLine);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        pendingEgress.clear();
        pendingClients.clear();
        idleStrategy = cluster.idleStrategy();
        role.set(cluster.role());
        System.out.printf("Aeron core role productLine=%s role=%s%n", productLine, cluster.role());
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
        schedulePendingMatchingTimers();
    }

    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        byte[] encoded = new byte[length];
        buffer.getBytes(offset, encoded);
        CoreMessage request;
        try {
            request = CoreMessageCodec.decode(encoded);
        } catch (IllegalArgumentException exception) {
            return;
        }
        var result = state.apply(request, timestamp, header.position());
        long matchingSequence = state.matchingSequence(request.header().commandId());
        if (matchingSequence > 0) {
            if (session != null) {
                pendingClients.computeIfAbsent(matchingSequence, ignored -> new ArrayDeque<>())
                        .addLast(new PendingClient(session, request));
            }
            scheduleMatchingTimer(matchingSequence);
            return;
        }
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            if (session != null) {
                pendingClients.computeIfAbsent(querySequence, ignored -> new ArrayDeque<>())
                        .addLast(new PendingClient(session, request));
            }
            scheduleMatchingTimer(querySequence);
            return;
        }
        if (session != null) {
            CoreMessage response = new CoreMessage(request.header().response(responseType(request)),
                    CoreProtocol.responsePayload(result));
            offer(session, CoreMessageCodec.encode(response));
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        byte[] snapshot = state.snapshot(Math.max(1, cluster.logPosition()));
        org.agrona.concurrent.UnsafeBuffer buffer = new org.agrona.concurrent.UnsafeBuffer(snapshot);
        idleStrategy.reset();
        int offset = 0;
        while (offset < snapshot.length) {
            int chunkLength = Math.min(snapshotPublication.maxPayloadLength(), snapshot.length - offset);
            while (snapshotPublication.offer(buffer, offset, chunkLength) < 0) {
                idleStrategy.idle();
            }
            offset += chunkLength;
        }
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        role.set(newRole);
        System.out.printf("Aeron core role-change productLine=%s role=%s%n", productLine, newRole);
        schedulePendingMatchingTimers();
    }

    @Override
    public int doBackgroundWork(long nowNs) {
        int work = 0;
        for (PendingEgress egress : pendingEgress.values()) {
            work += drain(egress);
        }
        return work;
    }

    @Override
    public void onTerminate(Cluster cluster) {
        pendingEgress.clear();
        pendingClients.clear();
        this.cluster = null;
        state.close();
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        pendingEgress.put(session.id(), new PendingEgress(session));
        schedulePendingMatchingTimers();
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        pendingEgress.remove(session.id());
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        if (correlationId < 0) {
            CoreResponse queryResult = state.takeQueryResult(correlationId);
            if (queryResult == null) {
                scheduleMatchingTimer(correlationId);
                return;
            }
            ArrayDeque<PendingClient> clients = pendingClients.remove(correlationId);
            if (clients != null) {
                for (PendingClient pendingClient : clients) {
                    if (pendingClient.session().isClosing()) continue;
                    CoreMessage response = new CoreMessage(pendingClient.request().header().response(
                            responseType(pendingClient.request())), CoreProtocol.responsePayload(queryResult));
                    offer(pendingClient.session(), CoreMessageCodec.encode(response));
                }
            }
            return;
        }
        var matchingResult = state.takeMatchingResult(correlationId);
        if (matchingResult == null) {
            state.markMatchingTimeout(correlationId, timestamp);
            matchingResult = state.takeMatchingResult(correlationId);
        }
        if (matchingResult == null) {
            scheduleMatchingTimer(correlationId);
            return;
        }
        CoreResponse result = state.completeMatching(correlationId, matchingResult, timestamp,
                cluster == null ? 0 : cluster.logPosition());
        if (result == null) {
            scheduleMatchingTimer(correlationId);
            return;
        }
        ArrayDeque<PendingClient> clients = pendingClients.remove(correlationId);
        if (clients != null) {
            for (PendingClient pendingClient : clients) {
                if (pendingClient.session().isClosing()) continue;
                CoreMessage response = new CoreMessage(pendingClient.request().header().response(
                        responseType(pendingClient.request())), CoreProtocol.responsePayload(result));
                offer(pendingClient.session(), CoreMessageCodec.encode(response));
            }
        }
        schedulePendingMatchingTimers();
    }

    CoreProbeState state() {
        return state;
    }

    private void loadSnapshot(Image snapshotImage) {
        ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) -> {
            ensureSnapshotCapacity(snapshot.size(), length);
            byte[] data = new byte[length];
            buffer.getBytes(offset, data);
            snapshot.writeBytes(data);
        });
        while (!snapshotImage.isEndOfStream()) {
            int fragments = snapshotImage.poll(assembler, 10);
            idleStrategy.idle(fragments);
        }
        if (snapshot.size() == 0) {
            throw new IllegalStateException("incomplete Aeron core snapshot");
        }
        restoreSnapshot(snapshot.toByteArray());
    }

    static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        if (currentLength < 0 || fragmentLength < 0
                || currentLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - fragmentLength) {
            throw new IllegalStateException("Aeron core snapshot exceeds maximum size");
        }
    }

    void restoreSnapshot(byte[] snapshot) {
        CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot);
        state.close();
        state = restored;
    }

    private void offer(ClientSession session, byte[] encoded) {
        PendingEgress egress = pendingEgress.computeIfAbsent(session.id(), id -> new PendingEgress(session));
        if (!egress.queue.isEmpty()) {
            enqueue(egress, encoded);
            return;
        }
        org.agrona.concurrent.UnsafeBuffer response = new org.agrona.concurrent.UnsafeBuffer(encoded);
        if (session.offer(response, 0, encoded.length) < 0) {
            enqueue(egress, encoded);
        }
    }

    private static int drain(PendingEgress egress) {
        if (egress.session.isClosing()) {
            egress.queue.clear();
            return 0;
        }
        int work = 0;
        while (!egress.queue.isEmpty()) {
            byte[] encoded = egress.queue.peekFirst();
            if (egress.session.offer(new org.agrona.concurrent.UnsafeBuffer(encoded), 0, encoded.length) < 0) {
                break;
            }
            egress.queue.removeFirst();
            work++;
        }
        return work;
    }

    private static void enqueue(PendingEgress egress, byte[] encoded) {
        if (egress.queue.size() >= MAX_PENDING_EGRESS_PER_SESSION) {
            egress.queue.clear();
            egress.session.close();
            return;
        }
        egress.queue.addLast(encoded);
    }

    private static final class PendingEgress {
        private final ClientSession session;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();

        private PendingEgress(ClientSession session) {
            this.session = session;
        }
    }

    private void schedulePendingMatchingTimers() {
        if (cluster == null) return;
        for (long sequence : state.pendingMatching().keySet()) {
            scheduleMatchingTimer(sequence);
        }
    }

    private void scheduleMatchingTimer(long sequence) {
        if (cluster == null || sequence == 0) return;
        long delay = Math.max(1L, cluster.timeUnit().convert(MATCHING_TIMER_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        long deadline = cluster.time() + delay;
        idleStrategy.reset();
        while (!cluster.scheduleTimer(sequence, deadline)) {
            idleStrategy.idle();
        }
    }

    private record PendingClient(ClientSession session, CoreMessage request) {
    }

    private static CoreMessageType responseType(CoreMessage request) {
        return switch (request.header().messageType()) {
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
            default -> request.header().kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
