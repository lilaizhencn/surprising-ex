package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
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

    private final ProductLine productLine;
    private final AtomicReference<Cluster.Role> role = new AtomicReference<>();
    private CoreProbeState state;
    private IdleStrategy idleStrategy;
    private final Map<Long, PendingEgress> pendingEgress = new HashMap<>();

    public SurprisingClusteredService(ProductLine productLine) {
        this.productLine = productLine;
        this.state = new CoreProbeState(productLine);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        pendingEgress.clear();
        idleStrategy = cluster.idleStrategy();
        role.set(cluster.role());
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
        byte[] encoded = new byte[length];
        buffer.getBytes(offset, encoded);
        CoreMessage request;
        try {
            request = CoreMessageCodec.decode(encoded);
        } catch (IllegalArgumentException exception) {
            return;
        }
        var result = state.apply(request, timestamp, header.position());
        if (session != null) {
            CoreMessage response = new CoreMessage(request.header().response(responseType(request)),
                    CoreProtocol.responsePayload(result));
            offer(session, CoreMessageCodec.encode(response));
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        byte[] snapshot = state.snapshot();
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
        state.close();
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        pendingEgress.put(session.id(), new PendingEgress(session));
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        pendingEgress.remove(session.id());
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
    }

    CoreProbeState state() {
        return state;
    }

    private void loadSnapshot(Image snapshotImage) {
        ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) -> {
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
        CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot.toByteArray());
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

    private static CoreMessageType responseType(CoreMessage request) {
        return switch (request.header().messageType()) {
            case USER_STATE_QUERY -> CoreMessageType.USER_STATE_RESULT;
            case ORDER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY -> CoreMessageType.ORDER_STATE_RESULT;
            case BOOK_STATE_QUERY -> CoreMessageType.BOOK_STATE_RESULT;
            case LIQUIDATION_WORK_QUERY -> CoreMessageType.LIQUIDATION_WORK_RESULT;
            case USER_OPEN_ORDERS_QUERY -> CoreMessageType.USER_OPEN_ORDERS_RESULT;
            case TRIGGER_ORDER_QUERY -> CoreMessageType.TRIGGER_ORDER_RESULT;
            case USER_OPEN_TRIGGER_ORDERS_QUERY -> CoreMessageType.USER_OPEN_TRIGGER_ORDERS_RESULT;
            case FUNDING_PROGRESS_QUERY -> CoreMessageType.FUNDING_PROGRESS_RESULT;
            case SETTLEMENT_PROGRESS_QUERY -> CoreMessageType.SETTLEMENT_PROGRESS_RESULT;
            default -> request.header().kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
