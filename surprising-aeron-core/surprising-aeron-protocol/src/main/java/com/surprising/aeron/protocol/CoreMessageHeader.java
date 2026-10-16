package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;

public final class CoreMessageHeader {
    private final CoreMessage decoderOwner;
    private int schemaVersion;
    private WireMessageKind kind;
    private CoreMessageType messageType;
    private UUID commandId;
    private ProductLine productLine;
    private CoreRoute route;
    private CommandSource source;
    private long sourceId;
    private long sourceSequence;
    private long userId;
    private long submittedAtEpochMillis;
    private long correlationId;

    public CoreMessageHeader(
        int schemaVersion,
        WireMessageKind kind,
        CoreMessageType messageType,
        UUID commandId,
        ProductLine productLine,
        CoreRoute route,
        CommandSource source,
        long sourceId,
        long sourceSequence,
        long userId,
        long submittedAtEpochMillis,
        long correlationId) {
        decoderOwner = null;
        assign(schemaVersion, kind, messageType, commandId, productLine, route, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }

    CoreMessageHeader(CoreMessage decoderOwner) { this.decoderOwner = decoderOwner; }

    void assign(
        int schemaVersion,
        WireMessageKind kind,
        CoreMessageType messageType,
        UUID commandId,
        ProductLine productLine,
        CoreRoute route,
        CommandSource source,
        long sourceId,
        long sourceSequence,
        long userId,
        long submittedAtEpochMillis,
        long correlationId) {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(productLine, "productLine");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(source, "source");
        if (messageType.kind() != kind) {
            throw new IllegalArgumentException("messageType does not belong to kind: " + messageType);
        }
        if (sourceSequence < 0) {
            throw new IllegalArgumentException("sourceSequence must not be negative");
        }
        if (submittedAtEpochMillis < 0) {
            throw new IllegalArgumentException("submittedAtEpochMillis must not be negative");
        }
        this.schemaVersion = schemaVersion;
        this.kind = kind;
        this.messageType = messageType;
        this.commandId = commandId;
        this.productLine = productLine;
        this.route = route;
        this.source = source;
        this.sourceId = sourceId;
        this.sourceSequence = sourceSequence;
        this.userId = userId;
        this.submittedAtEpochMillis = submittedAtEpochMillis;
        this.correlationId = correlationId;
    }

    public int schemaVersion() { return schemaVersion; }
    public WireMessageKind kind() { return kind; }
    public CoreMessageType messageType() { return messageType; }
    public UUID commandId() { return commandId; }
    public ProductLine productLine() { return productLine; }
    public CoreRoute route() { return route; }
    public CommandSource source() { return source; }
    public long sourceId() { return sourceId; }
    public long sourceSequence() { return sourceSequence; }
    public long userId() { return userId; }
    public long submittedAtEpochMillis() { return submittedAtEpochMillis; }
    public long correlationId() { return correlationId; }

    /** Queue an asynchronous response reader before releasing the command's decoder lease. */
    public void retainForEgress() { if (decoderOwner != null) decoderOwner.retainDecoderReader(); }
    public void releaseFromEgress() { if (decoderOwner != null) decoderOwner.releaseDecoderReader(); }

    public CoreMessageHeader immutableValue() {
        return decoderOwner == null ? this : new CoreMessageHeader(schemaVersion, kind, messageType, commandId, productLine, route, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }

    void clearDecoderReferences() {
        kind = null; messageType = null; commandId = null; productLine = null;
        route = null; source = null;
    }

    public static CoreMessageHeader command(
            CoreMessageType messageType,
            UUID commandId,
            ProductLine productLine,
            CommandSource source,
            long sourceId,
            long sourceSequence,
            long userId,
            long submittedAtEpochMillis,
            long correlationId) {
        return new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION, WireMessageKind.COMMAND, messageType,
                commandId, productLine, CoreRoute.DEFAULT, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
    }

    public static CoreMessageHeader query(
            CoreMessageType messageType,
            UUID queryId,
            ProductLine productLine,
            CommandSource source,
            long sourceId,
            long sourceSequence,
            long userId,
            long submittedAtEpochMillis,
            long correlationId) {
        return new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION, WireMessageKind.QUERY, messageType,
                queryId, productLine, CoreRoute.DEFAULT, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
    }

    public CoreMessageHeader response(CoreMessageType responseType) {
        return new CoreMessageHeader(schemaVersion, WireMessageKind.RESPONSE, responseType, commandId,
                productLine, route, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
    }

    @Override public boolean equals(Object other) { return other instanceof CoreMessageHeader value && schemaVersion == value.schemaVersion && java.util.Objects.equals(kind, value.kind) && java.util.Objects.equals(messageType, value.messageType) && java.util.Objects.equals(commandId, value.commandId) && java.util.Objects.equals(productLine, value.productLine) && java.util.Objects.equals(route, value.route) && java.util.Objects.equals(source, value.source) && sourceId == value.sourceId && sourceSequence == value.sourceSequence && userId == value.userId && submittedAtEpochMillis == value.submittedAtEpochMillis && correlationId == value.correlationId; }
    @Override public int hashCode() { return Objects.hash(schemaVersion, kind, messageType, commandId, productLine, route, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId); }
    @Override public String toString() { return "CoreMessageHeader[messageType=" + messageType + ", commandId=" + commandId + ", productLine=" + productLine + ", sourceSequence=" + sourceSequence + "]"; }
}
