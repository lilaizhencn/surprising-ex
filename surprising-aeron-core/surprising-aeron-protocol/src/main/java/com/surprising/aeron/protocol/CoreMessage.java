package com.surprising.aeron.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class CoreMessage {

    public static final byte[] EMPTY_PAYLOAD = new byte[0];

    private CoreMessageHeader header;
    private byte[] payload;
    private int payloadLength;
    private final boolean decoderSlot;
    private volatile boolean decoderLeased;
    private final CoreMessageHeader decoderHeader;
    private final CoreMessageHeader decoderResponseHeader;
    private final CoreResponse decoderResponse;
    /** Internal waiting metadata must not occupy the independent terminal-response lease. */
    private final CoreResponse decoderPendingResponse;
    private final CommandFingerprint decoderFingerprint;
    private final java.util.concurrent.atomic.AtomicInteger decoderReaders;

    public CoreMessage(CoreMessageHeader header, byte[] payload) {
        this(header, payload, false);
    }

    public static CoreMessage owned(CoreMessageHeader header, byte[] payload) {
        return new CoreMessage(header, payload, true);
    }

    private CoreMessage(CoreMessageHeader header, byte[] payload, boolean owned) {
        decoderSlot = false;
        decoderHeader = null;
        decoderResponseHeader = null;
        decoderResponse = null;
        decoderPendingResponse = null;
        decoderFingerprint = null;
        decoderReaders = null;
        this.header = Objects.requireNonNull(header, "header").immutableValue();
        this.payload = owned
                ? Objects.requireNonNull(payload, "payload")
                : payload == null || payload.length == 0 ? EMPTY_PAYLOAD : Arrays.copyOf(payload, payload.length);
        payloadLength = this.payload.length;
    }

    private CoreMessage() {
        decoderSlot = true; payload = EMPTY_PAYLOAD;
        decoderHeader = new CoreMessageHeader(this);
        decoderResponseHeader = new CoreMessageHeader(this);
        decoderResponse = new CoreResponse();
        decoderPendingResponse = new CoreResponse();
        decoderFingerprint = CommandFingerprint.reusableSlot();
        decoderReaders = new java.util.concurrent.atomic.AtomicInteger();
    }
    public static CoreMessage decoderSlot() { return new CoreMessage(); }

    /** One serial decoder producer; release comes from the final Owner/response consumer. */
    public boolean acquireDecoderSlot() {
        if (!decoderSlot) throw new IllegalStateException("message is not a decoder slot");
        if (decoderLeased) return false;
        decoderReaders.set(1);
        decoderLeased = true;
        return true;
    }

    public byte[] decoderPayloadBuffer(int length) {
        return decoderPayloadBuffer(length, length);
    }

    /** The decoder chooses capacity; every reader must use payloadLength, never spare tail bytes. */
    public byte[] decoderPayloadBuffer(int length, int capacity) {
        if (!decoderSlot || !decoderLeased || length < 0 || capacity < length)
            throw new IllegalStateException("decoder slot is not owned or has invalid capacity");
        if (payload.length != capacity) payload = capacity == 0 ? EMPTY_PAYLOAD : new byte[capacity];
        payloadLength = length;
        return payload;
    }

    public void decoderHeader(int schemaVersion, CoreMessageType messageType, long commandIdMsb, long commandIdLsb,
            com.surprising.product.api.ProductLine productLine, CommandSource source, long sourceId, long sourceSequence,
            long userId, long submittedAtEpochMillis, long correlationId) {
        if (!decoderSlot || !decoderLeased) throw new IllegalStateException("decoder slot is not owned");
        decoderHeader.assign(schemaVersion, messageType, commandIdMsb, commandIdLsb, productLine, source, sourceId,
                sourceSequence, userId, submittedAtEpochMillis, correlationId);
        header = decoderHeader;
    }

    public void decoderHeader(int schemaVersion, CoreMessageType messageType, java.util.UUID commandId,
            com.surprising.product.api.ProductLine productLine, CommandSource source, long sourceId, long sourceSequence,
            long userId, long submittedAtEpochMillis, long correlationId) {
        if (!decoderSlot || !decoderLeased) throw new IllegalStateException("decoder slot is not owned");
        decoderHeader.assign(schemaVersion, messageType, commandId, productLine, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
        header = decoderHeader;
    }

    void retainDecoderReader() {
        if (!decoderLeased || decoderReaders.get() <= 0) throw new IllegalStateException("decoder slot is not leased");
        decoderReaders.incrementAndGet();
    }

    void releaseDecoderReader() {
        int remaining = decoderReaders.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("decoder slot was released twice");
        if (remaining == 0) {
            decoderHeader.clearDecoderReferences();
            decoderResponseHeader.clearDecoderReferences();
            decoderResponse.clearCommandSlot();
            decoderPendingResponse.clearCommandSlot();
            decoderFingerprint.clearSlot();
            header = null;
            decoderLeased = false;
        }
    }

    public void releaseDecoderSlot() {
        if (!decoderSlot) return;
        releaseDecoderReader();
    }

    CommandFingerprint fingerprintSlot() {
        if (!decoderSlot) return null;
        if (!decoderLeased) throw new IllegalStateException("decoder slot is not leased");
        return decoderFingerprint;
    }

    /** Transport header shares this input's lease; its consumer must retain before publication. */
    public CoreMessageHeader responseHeader(CoreMessageType responseType) {
        if (!decoderSlot) return header.response(responseType);
        if (!decoderLeased) throw new IllegalStateException("decoder slot is not leased");
        if (decoderResponseHeader.kind() != null) {
            if (decoderResponseHeader.messageType() != responseType)
                throw new IllegalStateException("response header type changed within one command");
            return decoderResponseHeader;
        }
        decoderResponseHeader.assign(header.schemaVersion(), responseType, header.commandIdMsb(), header.commandIdLsb(),
                header.productLine(), header.source(), header.sourceId(), header.sourceSequence(), header.userId(),
                header.submittedAtEpochMillis(), header.correlationId());
        return decoderResponseHeader;
    }

    /**
     * A decoded command owns one terminal response until the final header/egress lease releases.
     * Ordinary independently owned messages keep the stable CoreResponse value API.
     */
    public CoreResponse ownedResponse(ResponseStatus status, ResponseStatus commandStatus,
            CoreResultCode resultCode, long sequence, byte[] data, int offset, int length) {
        if (!decoderSlot) return CoreResponse.owned(status, commandStatus, resultCode, sequence, data, offset, length);
        if (!decoderLeased) throw new IllegalStateException("decoder slot is not leased");
        return decoderResponse.prepareCommandSlot(status, commandStatus, resultCode, sequence, data, offset, length);
    }

    /** Waiting metadata is stable for this input lease, separate from its eventual terminal response. */
    public CoreResponse matchingPendingResponse(CoreResultCode resultCode, long sequence) {
        if (!decoderSlot) return CoreResponse.owned(ResponseStatus.OK, ResponseStatus.OK, resultCode,
                sequence, EMPTY_PAYLOAD);
        if (!decoderLeased) throw new IllegalStateException("decoder slot is not leased");
        if (decoderPendingResponse.status() != null) {
            if (decoderPendingResponse.resultCode() != resultCode
                    || decoderPendingResponse.appliedCommandCount() != sequence)
                throw new IllegalStateException("pending response changed within one input lease");
            return decoderPendingResponse;
        }
        return decoderPendingResponse.prepareCommandSlot(ResponseStatus.OK, ResponseStatus.OK,
                resultCode, sequence, EMPTY_PAYLOAD, 0, 0);
    }

    public CoreMessageHeader header() {
        return header;
    }

    public byte[] payload() {
        return payloadLength == 0 ? EMPTY_PAYLOAD : Arrays.copyOf(payload, payloadLength);
    }

    /** Borrowed backing array; only the first payloadLength() bytes belong to this message. */
    public byte[] payloadUnsafe() {
        return payload;
    }

    public int payloadLength() {
        return payloadLength;
    }

    public void copyPayloadTo(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - payloadLength) {
            throw new IndexOutOfBoundsException("payload does not fit destination");
        }
        System.arraycopy(payload, 0, destination, offset, payloadLength);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CoreMessage message
                && header.equals(message.header)
                && Arrays.equals(payload, 0, payloadLength, message.payload, 0, message.payloadLength);
    }

    @Override
    public int hashCode() {
        int payloadHash = 1;
        for (int index = 0; index < payloadLength; index++) payloadHash = 31 * payloadHash + payload[index];
        return 31 * header.hashCode() + payloadHash;
    }

    @Override
    public String toString() {
        return "CoreMessage[header=" + header + ", payload=" + Arrays.toString(payload()) + "]";
    }
}
