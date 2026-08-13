package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public record UserStateChangelog(
        int schemaVersion,
        ProductLine productLine,
        long userId,
        long sequence,
        byte[] state,
        String stateChecksum,
        Instant checkpointAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_STATE_BYTES = 64 * 1_024 * 1_024;

    public UserStateChangelog {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported user state changelog schemaVersion: " + schemaVersion);
        }
        if (productLine == null || userId <= 0L || sequence < 0L) {
            throw new IllegalArgumentException("invalid user state changelog identity");
        }
        if (state == null || state.length == 0 || state.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("invalid user state changelog state");
        }
        String expectedChecksum = checksum(state);
        if (stateChecksum == null || !expectedChecksum.equalsIgnoreCase(stateChecksum.trim())) {
            throw new IllegalArgumentException("user state changelog checksum mismatch");
        }
        state = Arrays.copyOf(state, state.length);
        stateChecksum = expectedChecksum;
        checkpointAt = checkpointAt == null ? Instant.now() : checkpointAt;
        traceId = traceId == null || traceId.isBlank() ? null : traceId.trim();
    }

    public static UserStateChangelog create(ProductLine productLine,
                                             long userId,
                                             long sequence,
                                             byte[] state,
                                             Instant checkpointAt,
                                             String traceId) {
        return new UserStateChangelog(CURRENT_SCHEMA_VERSION, productLine, userId, sequence, state,
                checksum(state), checkpointAt, traceId);
    }

    public String partitionKey() {
        return UserMutation.partitionKey(productLine, userId);
    }

    public UserPartitionKey userPartition() {
        return new UserPartitionKey(productLine, userId);
    }

    @Override
    public byte[] state() {
        return Arrays.copyOf(state, state.length);
    }

    private static String checksum(byte[] state) {
        Objects.requireNonNull(state, "state");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
