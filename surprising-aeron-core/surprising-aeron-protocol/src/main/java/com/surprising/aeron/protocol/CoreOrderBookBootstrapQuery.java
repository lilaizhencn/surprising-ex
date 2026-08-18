package com.surprising.aeron.protocol;

import java.util.Locale;

public record CoreOrderBookBootstrapQuery(
        String snapshotId,
        String symbolCursor,
        int limit,
        int depth) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    public CoreOrderBookBootstrapQuery {
        snapshotId = snapshotId == null ? "" : snapshotId.trim();
        symbolCursor = symbolCursor == null ? "" : symbolCursor.trim().toUpperCase(Locale.ROOT);
        if (!snapshotId.isEmpty()) {
            if (snapshotId.length() != 36) throw new IllegalArgumentException("invalid bootstrap snapshot id");
            try {
                java.util.UUID.fromString(snapshotId);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid bootstrap snapshot id", exception);
            }
        }
        if (!symbolCursor.isEmpty() && !CoreOrderBookQuery.validSymbol(symbolCursor)) {
            throw new IllegalArgumentException("invalid bootstrap symbol cursor");
        }
        if (snapshotId.isEmpty() && !symbolCursor.isEmpty()) {
            throw new IllegalArgumentException("bootstrap cursor requires snapshot id");
        }
        if (limit == 0) limit = DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("invalid bootstrap symbol limit");
        }
        if (depth == 0) depth = CoreOrderBookQuery.DEFAULT_DEPTH;
        if (depth < 1 || depth > CoreOrderBookQuery.MAX_DEPTH) {
            throw new IllegalArgumentException("invalid bootstrap book depth");
        }
    }
}
