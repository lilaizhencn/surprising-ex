package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.Objects;

public record UserMutation(
        int schemaVersion,
        String commandId,
        ProductLine productLine,
        long userId,
        String mutationType,
        String source,
        String sourceReference,
        String dependsOnCommandId,
        String payload,
        Instant occurredAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_COMMAND_ID_LENGTH = 200;
    private static final int MAX_MUTATION_TYPE_LENGTH = 80;
    private static final int MAX_SOURCE_LENGTH = 64;
    private static final int MAX_SOURCE_REFERENCE_LENGTH = 200;
    private static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    public UserMutation {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported user mutation schemaVersion: " + schemaVersion);
        }
        commandId = requireText(commandId, "commandId", MAX_COMMAND_ID_LENGTH);
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        mutationType = requireText(mutationType, "mutationType", MAX_MUTATION_TYPE_LENGTH);
        source = requireText(source, "source", MAX_SOURCE_LENGTH);
        sourceReference = requireText(sourceReference, "sourceReference", MAX_SOURCE_REFERENCE_LENGTH);
        dependsOnCommandId = normalizeOptional(dependsOnCommandId, MAX_COMMAND_ID_LENGTH);
        payload = requireText(payload, "payload", MAX_PAYLOAD_LENGTH);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        traceId = normalizeOptional(traceId, MAX_COMMAND_ID_LENGTH);
    }

    public String partitionKey() {
        return partitionKey(productLine, userId);
    }

    public UserPartitionKey userPartition() {
        return new UserPartitionKey(productLine, userId);
    }

    public boolean dependsOn(UserMutation mutation) {
        return mutation != null && Objects.equals(dependsOnCommandId, mutation.commandId());
    }

    public static String partitionKey(ProductLine productLine, long userId) {
        if (productLine == null || userId <= 0L) {
            throw new IllegalArgumentException("user mutation partition key is invalid");
        }
        return productLine.name() + ':' + userId;
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
