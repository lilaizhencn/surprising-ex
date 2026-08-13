package com.surprising.aeron.protocol;

public record CoreExportStatus(
        long acknowledgedSequence,
        long nextSequence,
        int pendingCount,
        long pendingBytes,
        int maxPendingCount,
        long maxPendingBytes) {
    public CoreExportStatus {
        if (acknowledgedSequence < 0 || nextSequence <= acknowledgedSequence || pendingCount < 0
                || pendingBytes < 0 || maxPendingCount <= 0 || maxPendingBytes <= 0
                || pendingCount > maxPendingCount || pendingBytes > maxPendingBytes) {
            throw new IllegalArgumentException("invalid export status");
        }
    }

    public boolean acceptingCommands() {
        return pendingCount < maxPendingCount && pendingBytes < maxPendingBytes;
    }
}
