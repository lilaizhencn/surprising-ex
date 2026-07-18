package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * Versioned durable command for the single-writer account boundary.
 *
 * <p>Every producer must use {@link #partitionKey(ProductLine, long)} as the Kafka key. Payload is
 * JSON encoded separately so command types can evolve without making the envelope polymorphic.</p>
 */
public record AccountUserCommand(
        int schemaVersion,
        String commandId,
        ProductLine productLine,
        long userId,
        AccountUserCommandType commandType,
        String source,
        String sourceReference,
        String dependsOnCommandId,
        String payload,
        Instant occurredAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AccountUserCommand {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported account command schemaVersion: " + schemaVersion);
        }
        commandId = requireText(commandId, "commandId", 160);
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (commandType == null) {
            throw new IllegalArgumentException("commandType is required");
        }
        source = requireText(source, "source", 64);
        sourceReference = requireText(sourceReference, "sourceReference", 160);
        dependsOnCommandId = normalizeOptional(dependsOnCommandId, 160);
        payload = requireText(payload, "payload", 1_000_000);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        traceId = normalizeOptional(traceId, 160);
    }

    public String partitionKey() {
        return partitionKey(productLine, userId);
    }

    public static String partitionKey(ProductLine productLine, long userId) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return productLine.name() + ":" + userId;
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = normalizeOptional(value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("value length must be <= " + maxLength);
        }
        return normalized;
    }
}
