package com.surprising.aeron.service.orchestration.ingress;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreRoute;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.WireMessageKind;
import java.nio.ByteOrder;
import java.util.UUID;
import org.agrona.DirectBuffer;

public final class CoreMessageFlyweightDecoder {
    /** Owned by one serial ingress callback or benchmark producer. */
    private final DirectMessageReader messageReader = new DirectMessageReader();

    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        DirectMessageReader reader = reader(buffer, offset, length);
        UUID commandId = new UUID(reader.commandIdMsb(), reader.commandIdLsb());
        CoreRoute route = CoreRoute.fromWireCodes(reader.shardCode(), reader.routeVersion());
        String traceId = reader.traceId();
        int payloadLen = reader.payloadLength();
        byte[] payload = payloadLen == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLen];
        if (payloadLen > 0) {
            buffer.getBytes(reader.payloadOffset(), payload);
        }
        CoreMessageHeader header = new CoreMessageHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(), commandId,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId(), traceId);
        return CoreMessage.owned(header, payload);
    }
}
