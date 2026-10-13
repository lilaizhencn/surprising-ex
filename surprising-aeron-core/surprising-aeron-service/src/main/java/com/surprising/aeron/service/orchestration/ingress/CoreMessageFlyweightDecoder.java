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
    private final CoreMessage[] commandSlots = new CoreMessage[512];
    private int nextSlot;
    private final DirectMessageReader messageReader = new DirectMessageReader();

    public void reset() { java.util.Arrays.fill(commandSlots, null); nextSlot = 0; }

    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    /** Bounded reuse cache. Busy slots remain untouched, even when ingress exceeds the command window. */
    public CoreMessage decodeBorrowed(DirectBuffer buffer, int offset, int length) {
        if (length > 16_384) return decode(buffer, offset, length);
        int index = nextSlot++ & (commandSlots.length - 1);
        CoreMessage slot = commandSlots[index];
        if (slot == null) commandSlots[index] = slot = CoreMessage.decoderSlot();
        if (!slot.acquireDecoderSlot()) return decode(buffer, offset, length);
        try { return decodeInto(buffer, offset, length, slot); }
        catch (RuntimeException invalid) { slot.releaseDecoderSlot(); throw invalid; }
    }

    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        return decodeInto(buffer, offset, length, null);
    }

    private CoreMessage decodeInto(DirectBuffer buffer, int offset, int length, CoreMessage slot) {
        DirectMessageReader reader = reader(buffer, offset, length);
        UUID commandId = new UUID(reader.commandIdMsb(), reader.commandIdLsb());
        CoreRoute route = CoreRoute.fromWireCodes(reader.shardCode(), reader.routeVersion());
        String traceId = reader.traceId();
        int payloadLen = reader.payloadLength();
        byte[] payload = slot != null ? slot.decoderPayloadBuffer(payloadLen)
                : payloadLen == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLen];
        if (payloadLen > 0) {
            buffer.getBytes(reader.payloadOffset(), payload);
        }
        if (slot == null) return CoreMessage.owned(new CoreMessageHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(), commandId,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId(), traceId), payload);
        slot.decoderHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(), commandId,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId(), traceId);
        return slot;
    }
}
