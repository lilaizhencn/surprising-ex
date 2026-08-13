package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;

public record CoreMessageHeader(
        int schemaVersion,
        WireMessageKind kind,
        CoreMessageType messageType,
        UUID commandId,
        ProductLine productLine,
        CommandSource source,
        long sourceId,
        long sourceSequence,
        long userId,
        long submittedAtEpochMillis,
        long correlationId) {

    public CoreMessageHeader {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(productLine, "productLine");
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
                commandId, productLine, source, sourceId, sourceSequence, userId,
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
                queryId, productLine, source, sourceId, sourceSequence, userId,
                submittedAtEpochMillis, correlationId);
    }

    public CoreMessageHeader response(CoreMessageType responseType) {
        return new CoreMessageHeader(schemaVersion, WireMessageKind.RESPONSE, responseType, commandId,
                productLine, source, sourceId, sourceSequence, userId, submittedAtEpochMillis, correlationId);
    }
}
