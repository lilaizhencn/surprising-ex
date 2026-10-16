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
    private static final int CACHE_SLOTS = 512;
    private static final int MAX_CACHED_FRAME_BYTES = 16_384;
    /** Owned by one serial ingress callback or benchmark producer. */
    private final CoreMessage[] commandSlots = new CoreMessage[CACHE_SLOTS];
    /** Circular lists of exact-length buffers; metadata is written only by the ingress producer. */
    private final int[] lengthHeads = new int[MAX_CACHED_FRAME_BYTES + 1];
    private final int[] next = new int[CACHE_SLOTS];
    private final int[] previous = new int[CACHE_SLOTS];
    private final int[] lengths = new int[CACHE_SLOTS];
    private int replacementCursor;
    private final DirectMessageReader messageReader = new DirectMessageReader();

    public CoreMessageFlyweightDecoder() { reset(); }

    public void reset() {
        java.util.Arrays.fill(commandSlots, null);
        java.util.Arrays.fill(lengthHeads, -1);
        java.util.Arrays.fill(lengths, -1);
        replacementCursor = 0;
    }

    public DirectMessageReader reader(DirectBuffer buffer, int offset, int length) {
        return messageReader.wrap(buffer, offset, length);
    }

    /** Bounded reuse cache. Busy slots remain untouched, even when ingress exceeds the command window. */
    public CoreMessage decodeBorrowed(DirectBuffer buffer, int offset, int length) {
        DirectMessageReader reader = reader(buffer, offset, length);
        CoreMessage slot = length > MAX_CACHED_FRAME_BYTES ? null : acquire(reader.payloadLength());
        if (slot == null) return decodeInto(buffer, reader, null);
        try { return decodeInto(buffer, reader, slot); }
        catch (RuntimeException invalid) { slot.releaseDecoderSlot(); throw invalid; }
    }

    private CoreMessage acquire(int payloadLength) {
        int head = lengthHeads[payloadLength];
        if (head >= 0) {
            int index = head;
            do {
                if (commandSlots[index].acquireDecoderSlot()) {
                    lengthHeads[payloadLength] = next[index];
                    return commandSlots[index];
                }
                index = next[index];
            } while (index != head);
        }
        // A new size may replace only an unleased slot. The cache remains at most 512
        // exact-length arrays (8 MiB), even for an unbounded stream of different sizes.
        for (int checked = 0; checked < CACHE_SLOTS; checked++) {
            int index = replacementCursor++ & (CACHE_SLOTS - 1);
            CoreMessage slot = commandSlots[index];
            if (slot == null) commandSlots[index] = slot = CoreMessage.decoderSlot();
            if (!slot.acquireDecoderSlot()) continue;
            unlink(index);
            lengths[index] = payloadLength;
            int first = lengthHeads[payloadLength];
            if (first < 0) {
                next[index] = previous[index] = index;
            } else {
                int last = previous[first];
                next[last] = index;
                previous[index] = last;
                next[index] = first;
                previous[first] = index;
            }
            lengthHeads[payloadLength] = index;
            return slot;
        }
        return null;
    }

    private void unlink(int index) {
        int oldLength = lengths[index];
        if (oldLength < 0) return;
        if (next[index] == index) lengthHeads[oldLength] = -1;
        else {
            next[previous[index]] = next[index];
            previous[next[index]] = previous[index];
            if (lengthHeads[oldLength] == index) lengthHeads[oldLength] = next[index];
        }
    }

    public CoreMessage decode(DirectBuffer buffer, int offset, int length) {
        return decodeInto(buffer, reader(buffer, offset, length), null);
    }

    private CoreMessage decodeInto(DirectBuffer buffer, DirectMessageReader reader, CoreMessage slot) {
        long commandIdMsb = reader.commandIdMsb();
        long commandIdLsb = reader.commandIdLsb();
        CoreRoute route = CoreRoute.fromWireCodes(reader.shardCode(), reader.routeVersion());
        int payloadLen = reader.payloadLength();
        byte[] payload = slot != null ? slot.decoderPayloadBuffer(payloadLen)
                : payloadLen == 0 ? CoreMessage.EMPTY_PAYLOAD : new byte[payloadLen];
        if (payloadLen > 0) {
            buffer.getBytes(reader.payloadOffset(), payload);
        }
        if (slot == null) return CoreMessage.owned(new CoreMessageHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(),
                commandIdMsb, commandIdLsb,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId()), payload);
        slot.decoderHeader(
                reader.schemaVersion(), reader.kind(), reader.messageType(),
                commandIdMsb, commandIdLsb,
                reader.productLine(), route, reader.source(), reader.sourceId(),
                reader.sourceSequence(), reader.userId(), reader.submittedAtEpochMillis(),
                reader.correlationId());
        return slot;
    }
}
