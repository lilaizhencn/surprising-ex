package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import java.util.concurrent.atomic.AtomicReference;
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
        CoreMessage request = CoreMessageCodec.decode(encoded);
        var result = state.apply(request);
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
        while (snapshotPublication.offer(buffer, 0, snapshot.length) < 0) {
            idleStrategy.idle();
        }
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        role.set(newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
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
        final byte[][] snapshot = new byte[1][];
        while (!snapshotImage.isEndOfStream()) {
            int fragments = snapshotImage.poll((buffer, offset, length, header) -> {
                if (snapshot[0] != null) {
                    throw new IllegalStateException("Aeron core snapshot must contain one fragment");
                }
                byte[] data = new byte[length];
                buffer.getBytes(offset, data);
                snapshot[0] = data;
            }, 1);
            idleStrategy.idle(fragments);
        }
        if (snapshot[0] == null) {
            throw new IllegalStateException("incomplete Aeron core snapshot");
        }
        state = CoreProbeState.fromSnapshot(productLine, snapshot[0]);
    }

    private void offer(ClientSession session, byte[] encoded) {
        org.agrona.concurrent.UnsafeBuffer response = new org.agrona.concurrent.UnsafeBuffer(encoded);
        idleStrategy.reset();
        while (session.offer(response, 0, encoded.length) < 0) {
            idleStrategy.idle();
        }
    }

    private static CoreMessageType responseType(CoreMessage request) {
        return request.header().kind() == WireMessageKind.QUERY
                ? CoreMessageType.STATE_HASH_RESULT
                : CoreMessageType.COMMAND_RESULT;
    }
}
