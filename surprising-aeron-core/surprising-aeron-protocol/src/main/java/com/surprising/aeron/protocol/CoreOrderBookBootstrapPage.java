package com.surprising.aeron.protocol;

import java.util.List;

public record CoreOrderBookBootstrapPage(
        String snapshotId,
        long exportSequence,
        String nextSymbolCursor,
        boolean complete,
        List<CoreBookLevelView> levels) {

    public CoreOrderBookBootstrapPage {
        snapshotId = snapshotId == null ? "" : snapshotId.trim();
        nextSymbolCursor = nextSymbolCursor == null ? "" : nextSymbolCursor.trim();
        if (snapshotId.isEmpty() || exportSequence < 0 || levels == null
                || complete && !nextSymbolCursor.isEmpty()
                || !complete && nextSymbolCursor.isEmpty()) {
            throw new IllegalArgumentException("invalid order-book bootstrap page");
        }
        if (snapshotId.length() != 36) throw new IllegalArgumentException("invalid bootstrap snapshot id");
        try {
            java.util.UUID.fromString(snapshotId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid bootstrap snapshot id", exception);
        }
        levels = List.copyOf(levels);
    }
}
