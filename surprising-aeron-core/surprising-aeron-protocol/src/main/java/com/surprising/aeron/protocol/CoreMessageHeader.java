package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;

public final class CoreMessageHeader {
    private final CoreMessage decoderOwner;
    private int schemaVersion;
    private CoreMessageType messageType;
    private long commandIdMsb;
    private long commandIdLsb;
    private ProductLine productLine;
    private CommandSource source;
    private long sourceId;
    private long sourceSequence;
    private long userId;
    private long submittedAtEpochMillis;
    private long correlationId;
    private UUID cachedCommandId;

    public CoreMessageHeader(
        int schemaVersion,
        CoreMessageType messageType,
        long commandIdMsb,
        long commandIdLsb,
        ProductLine productLine,
        CommandSource source,
        long sourceId,
        long sourceSequence,
        long userId,
        long submittedAtEpochMillis,
        long correlationId) {
        decoderOwner = null;
        assign(schemaVersion, messageType, commandIdMsb, commandIdLsb, productLine, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
    }

    public CoreMessageHeader(
        int schemaVersion,
        CoreMessageType messageType,
        UUID commandId,
        ProductLine productLine,
        CommandSource source,
        long sourceId,
        long sourceSequence,
        long userId,
        long submittedAtEpochMillis,
        long correlationId) {
        this(schemaVersion, messageType, Objects.requireNonNull(commandId, "commandId").getMostSignificantBits(), commandId.getLeastSignificantBits(), productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
        this.cachedCommandId = commandId;
    }

    CoreMessageHeader(CoreMessage decoderOwner) { this.decoderOwner = decoderOwner; }

    void assign(int schemaVersion, CoreMessageType messageType, long commandIdMsb, long commandIdLsb, ProductLine productLine,
            CommandSource source, long sourceId, long sourceSequence, long userId, long submittedAtEpochMillis,
            long correlationId) {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(productLine, "productLine");
        Objects.requireNonNull(source, "source");
        if (sourceSequence < 0) {
            throw new IllegalArgumentException("sourceSequence must not be negative");
        }
        if (submittedAtEpochMillis < 0) {
            throw new IllegalArgumentException("submittedAtEpochMillis must not be negative");
        }
        this.schemaVersion = schemaVersion;
        this.messageType = messageType;
        this.commandIdMsb = commandIdMsb;
        this.commandIdLsb = commandIdLsb;
        this.productLine = productLine;
        this.source = source;
        this.sourceId = sourceId;
        this.sourceSequence = sourceSequence;
        this.userId = userId;
        this.submittedAtEpochMillis = submittedAtEpochMillis;
        this.correlationId = correlationId;
        this.cachedCommandId = null;
    }

    void assign(int schemaVersion, CoreMessageType messageType, UUID commandId, ProductLine productLine, CommandSource source,
            long sourceId, long sourceSequence, long userId, long submittedAtEpochMillis, long correlationId) {
        Objects.requireNonNull(commandId, "commandId");
        assign(schemaVersion, messageType, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
        this.cachedCommandId = commandId;
    }

    public int schemaVersion() { return schemaVersion; }
    public WireMessageKind kind() { return messageType.kind(); }
    public CoreMessageType messageType() { return messageType; }
    public long commandIdMsb() { return commandIdMsb; }
    public long commandIdLsb() { return commandIdLsb; }
    public UUID commandId() {
        UUID id = cachedCommandId;
        if (id == null) {
            id = new UUID(commandIdMsb, commandIdLsb);
            cachedCommandId = id;
        }
        return id;
    }
    public ProductLine productLine() { return productLine; }
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
        if (decoderOwner == null) return this;
        CoreMessageHeader copy = new CoreMessageHeader(schemaVersion, messageType, commandIdMsb, commandIdLsb, productLine,
                source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
        copy.cachedCommandId = this.cachedCommandId;
        return copy;
    }

    void clearDecoderReferences() {
        messageType = null; commandIdMsb = 0L; commandIdLsb = 0L; productLine = null;
        source = null; cachedCommandId = null;
    }

    public static CoreMessageHeader command(
            CoreMessageType messageType,
            long commandIdMsb,
            long commandIdLsb,
            ProductLine productLine,
            CommandSource source,
            long sourceId,
            long sourceSequence,
            long userId,
            long submittedAtEpochMillis,
            long correlationId) {
        if (messageType.kind() != WireMessageKind.COMMAND)
            throw new IllegalArgumentException("not a command type: " + messageType);
        return new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION, messageType, commandIdMsb, commandIdLsb, productLine,
                source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
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
        Objects.requireNonNull(commandId, "commandId");
        CoreMessageHeader header = command(messageType, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
        header.cachedCommandId = commandId;
        return header;
    }

    public static CoreMessageHeader query(
            CoreMessageType messageType,
            long queryIdMsb,
            long queryIdLsb,
            ProductLine productLine,
            CommandSource source,
            long sourceId,
            long sourceSequence,
            long userId,
            long submittedAtEpochMillis,
            long correlationId) {
        if (messageType.kind() != WireMessageKind.QUERY)
            throw new IllegalArgumentException("not a query type: " + messageType);
        return new CoreMessageHeader(CoreProtocol.SCHEMA_VERSION, messageType, queryIdMsb, queryIdLsb, productLine, source,
                sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
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
        Objects.requireNonNull(queryId, "queryId");
        return query(messageType, queryId.getMostSignificantBits(), queryId.getLeastSignificantBits(),
                productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }

    public CoreMessageHeader response(CoreMessageType responseType) {
        if (responseType.kind() != WireMessageKind.RESPONSE)
            throw new IllegalArgumentException("not a response type: " + responseType);
        return new CoreMessageHeader(schemaVersion, responseType, commandIdMsb, commandIdLsb, productLine, source, sourceId,
                sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }

    @Override public boolean equals(Object other) {
        return other instanceof CoreMessageHeader value
                && schemaVersion == value.schemaVersion
                && messageType == value.messageType
                && commandIdMsb == value.commandIdMsb
                && commandIdLsb == value.commandIdLsb
                && productLine == value.productLine
                && source == value.source
                && sourceId == value.sourceId
                && sourceSequence == value.sourceSequence
                && userId == value.userId
                && submittedAtEpochMillis == value.submittedAtEpochMillis
                && correlationId == value.correlationId;
    }

    @Override public int hashCode() {
        return Objects.hash(schemaVersion, messageType, commandIdMsb, commandIdLsb, productLine,
                source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }

    @Override public String toString() { return "CoreMessageHeader[messageType=" + messageType + ", commandIdMsb=" + commandIdMsb + ", commandIdLsb=" + commandIdLsb + ", productLine=" + productLine + ", sourceSequence=" + sourceSequence + "]"; }
}
