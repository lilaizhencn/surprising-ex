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
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;

public final class SurprisingClusteredService implements ClusteredService {

    private final ProductLine productLine;
    private final AtomicReference<Cluster.Role> role = new AtomicReference<>();
    private CoreProbeState state;
    private IdleStrategy idleStrategy;

    public SurprisingClusteredService(ProductLine productLine) {
        this.productLine = productLine;
        this.state = new CoreProbeState(productLine);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
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
    public void onTerminate(Cluster cluster) {
        state.close();
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
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
        org.agrona.concurrent.UnsafeBuffer response = new org.agrona.concurrent.UnsafeBuffer(encoded);
        idleStrategy.reset();
        while (session.offer(response, 0, encoded.length) < 0) {
            idleStrategy.idle();
        }
    }

    private static CoreMessageType responseType(CoreMessage request) {
        return switch (request.header().messageType()) {
            case USER_STATE_QUERY -> CoreMessageType.USER_STATE_RESULT;
            case ORDER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY -> CoreMessageType.ORDER_STATE_RESULT;
            default -> request.header().kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
