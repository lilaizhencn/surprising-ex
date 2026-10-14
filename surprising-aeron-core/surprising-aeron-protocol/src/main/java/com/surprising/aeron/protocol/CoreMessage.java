package com.surprising.aeron.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class CoreMessage {

    public static final byte[] EMPTY_PAYLOAD = new byte[0];

    private CoreMessageHeader header;
    private byte[] payload;
    private final boolean decoderSlot;
    private volatile boolean decoderLeased;
    private final CoreMessageHeader decoderHeader;
    private final CoreMessageHeader decoderResponseHeader;
    private final CoreResponse decoderResponse;
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
        decoderReaders = null;
        this.header = Objects.requireNonNull(header, "header").immutableValue();
        this.payload = owned
                ? Objects.requireNonNull(payload, "payload")
                : payload == null || payload.length == 0 ? EMPTY_PAYLOAD : Arrays.copyOf(payload, payload.length);
    }

    private CoreMessage() {
        decoderSlot = true; payload = EMPTY_PAYLOAD;
        decoderHeader = new CoreMessageHeader(this);
        decoderResponseHeader = new CoreMessageHeader(this);
        decoderResponse = new CoreResponse();
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
        if (!decoderSlot || !decoderLeased || length < 0) throw new IllegalStateException("decoder slot is not owned");
        if (payload.length != length) payload = length == 0 ? EMPTY_PAYLOAD : new byte[length];
        return payload;
    }

    public void decoderHeader(int schemaVersion, WireMessageKind kind, CoreMessageType messageType,
            java.util.UUID commandId, com.surprising.product.api.ProductLine productLine, CoreRoute route,
            CommandSource source, long sourceId, long sourceSequence, long userId, long submittedAtEpochMillis,
            long correlationId, String traceId) {
        if (!decoderSlot || !decoderLeased) throw new IllegalStateException("decoder slot is not owned");
        decoderHeader.assign(schemaVersion, kind, messageType, commandId, productLine, route, source,
                sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId, traceId);
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
            header = null;
            decoderLeased = false;
        }
    }

    public void releaseDecoderSlot() {
        if (!decoderSlot) return;
        releaseDecoderReader();
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
        decoderResponseHeader.assign(header.schemaVersion(), WireMessageKind.RESPONSE, responseType,
                header.commandId(), header.productLine(), header.route(), header.source(), header.sourceId(),
                header.sourceSequence(), header.userId(), header.submittedAtEpochMillis(), header.correlationId(), header.traceId());
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

    public CoreMessageHeader header() {
        return header;
    }

    public byte[] payload() {
        return payload.length == 0 ? EMPTY_PAYLOAD : Arrays.copyOf(payload, payload.length);
    }

    public byte[] payloadUnsafe() {
        return payload;
    }

    public int payloadLength() {
        return payload.length;
    }

    public void copyPayloadTo(byte[] destination, int offset) {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0 || offset > destination.length - payload.length) {
            throw new IndexOutOfBoundsException("payload does not fit destination");
        }
        System.arraycopy(payload, 0, destination, offset, payload.length);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CoreMessage message
                && header.equals(message.header)
                && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        return 31 * header.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "CoreMessage[header=" + header + ", payload=" + Arrays.toString(payload) + "]";
    }
}
