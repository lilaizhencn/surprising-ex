package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

final class CoreExportState {

    static final int MAX_PENDING_EVENTS = 1_000_000;
    static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private long acknowledgedSequence;
    private long nextSequence;
    private final ArrayDeque<CoreMessage> pending;
    private long pendingBytes;
    private long pendingDigest;

    CoreExportState() {
        this(0, 1, List.of());
    }

    private CoreExportState(long acknowledgedSequence, long nextSequence, List<CoreMessage> pending) {
        if (acknowledgedSequence < 0 || nextSequence <= acknowledgedSequence || pending == null
                || pending.size() > MAX_PENDING_EVENTS || nextSequence - acknowledgedSequence - 1 != pending.size()) {
            throw new IllegalArgumentException("invalid export state");
        }
        this.acknowledgedSequence = acknowledgedSequence;
        this.nextSequence = nextSequence;
        this.pending = new ArrayDeque<>(pending);
        long expectedSequence = Math.incrementExact(acknowledgedSequence);
        for (CoreMessage event : this.pending) {
            if (event.header().kind() != WireMessageKind.EXPORT_EVENT
                    || event.header().sourceSequence() != expectedSequence
                    || CoreExportCodec.decodeEvent(event.payload()).exportSequence() != expectedSequence) {
                throw new IllegalArgumentException("non-contiguous export state");
            }
            pendingBytes = Math.addExact(pendingBytes, encodedLength(event));
            pendingDigest ^= eventDigest(event);
            expectedSequence = Math.incrementExact(expectedSequence);
        }
        if (pendingBytes > MAX_PENDING_BYTES) {
            throw new IllegalArgumentException("export state exceeds byte limit");
        }
    }

    static CoreExportState restore(long acknowledgedSequence, long nextSequence, List<CoreMessage> pending) {
        return new CoreExportState(acknowledgedSequence, nextSequence, pending);
    }

    void append(CoreMessage command, ResponseStatus status, com.surprising.aeron.protocol.CoreResultCode resultCode,
                long appliedCommandCount, long businessStateHash) {
        if (pending.size() >= MAX_PENDING_EVENTS) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached hard limit");
        }
        long sequence = nextSequence;
        CoreExportEvent event = new CoreExportEvent(sequence, appliedCommandCount, businessStateHash,
                command.header().commandId(), command.header().messageType(), status, resultCode,
                command.header().userId(), command.payload());
        CoreMessage message = new CoreMessage(command.header().exportEvent(sequence),
                CoreExportCodec.encodeEvent(event));
        long eventBytes = encodedLength(message);
        if (pendingBytes + eventBytes > MAX_PENDING_BYTES) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached byte limit");
        }
        pending.add(message);
        pendingBytes = Math.addExact(pendingBytes, eventBytes);
        pendingDigest ^= eventDigest(message);
        nextSequence = Math.incrementExact(nextSequence);
    }

    boolean hasCapacity() {
        return pending.size() < MAX_PENDING_EVENTS;
    }

    boolean hasCapacityFor(CoreMessage command) {
        long eventBytes = Math.addExact(CoreProtocol.HEADER_LENGTH,
                Math.addExact(64L, command.payload().length));
        return hasCapacity() && pendingBytes + eventBytes <= MAX_PENDING_BYTES;
    }

    void acknowledge(AckExportCommand command) {
        if (command.throughSequence() <= acknowledgedSequence) {
            return;
        }
        long highestPending = Math.subtractExact(nextSequence, 1);
        if (command.throughSequence() > highestPending) {
            throw new CoreStateRejectedException("EXPORT_ACK_AHEAD", "export ack exceeds emitted sequence");
        }
        int removeCount = Math.toIntExact(command.throughSequence() - acknowledgedSequence);
        for (int index = 0; index < removeCount; index++) {
            CoreMessage removed = pending.removeFirst();
            pendingBytes = Math.subtractExact(pendingBytes, encodedLength(removed));
            pendingDigest ^= eventDigest(removed);
        }
        acknowledgedSequence = command.throughSequence();
    }

    List<CoreMessage> batch(int maxEvents) {
        int count = 0;
        long encodedLength = Integer.BYTES;
        int limit = Math.min(maxEvents, pending.size());
        ArrayList<CoreMessage> batch = new ArrayList<>(limit);
        Iterator<CoreMessage> iterator = pending.iterator();
        while (count < limit && iterator.hasNext()) {
            CoreMessage event = iterator.next();
            int eventLength = CoreMessageCodec.encode(event).length;
            long nextLength = Math.addExact(encodedLength, Math.addExact(Integer.BYTES, eventLength));
            if (nextLength > CoreExportCodec.MAX_BATCH_ENCODED_LENGTH) {
                break;
            }
            encodedLength = nextLength;
            batch.add(event);
            count++;
        }
        return List.copyOf(batch);
    }

    CoreExportStatus status() {
        return new CoreExportStatus(acknowledgedSequence, nextSequence, pending.size(), pendingBytes,
                MAX_PENDING_EVENTS, MAX_PENDING_BYTES);
    }

    long acknowledgedSequence() {
        return acknowledgedSequence;
    }

    long nextSequence() {
        return nextSequence;
    }

    List<CoreMessage> pending() {
        return List.copyOf(pending);
    }

    int pendingCount() {
        return pending.size();
    }

    long pendingDigest() {
        return pendingDigest;
    }

    private static int encodedLength(CoreMessage message) {
        return Math.addExact(CoreProtocol.HEADER_LENGTH, message.payload().length);
    }

    private static long eventDigest(CoreMessage message) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : CoreMessageCodec.encode(message)) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
