package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.product.api.ProductLine;

public record CoreSnapshotManifest(
        ProductLine productLine,
        int schemaVersion,
        String coreShardId,
        int routeVersion,
        long snapshotId,
        long coreSequence,
        long clusterTimestamp,
        long clusterPosition,
        long appliedCommandCount,
        long matcherSequence,
        long businessStateHash,
        int engineStateHash,
        int bookStateHash,
        long symbolRegistryHash,
        long userRegistryHash,
        long instrumentRegistryHash,
        long activeOrderHash,
        long sourceSequenceDigest,
        String forkGitSha,
        String artifactSha256,
        long matcherConfigHash,
        CoreExportStatus exportStatus,
        long outboxPendingDigest,
        long checksum) {

    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || appliedCommandCount < 0
                || coreShardId == null || coreShardId.isBlank() || routeVersion <= 0
                || snapshotId < 0 || coreSequence < 0 || clusterTimestamp < 0 || clusterPosition < 0
                || coreSequence != appliedCommandCount || matcherSequence < 0
                || forkGitSha == null || forkGitSha.isBlank()
                || artifactSha256 == null || artifactSha256.isBlank()
                || exportStatus == null || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }

    /** Compatibility constructor for callers compiled against the V8 manifest surface. */
    public CoreSnapshotManifest(
            ProductLine productLine,
            int schemaVersion,
            String coreShardId,
            int routeVersion,
            long appliedCommandCount,
            long matcherSequence,
            long businessStateHash,
            int engineStateHash,
            int bookStateHash,
            long symbolRegistryHash,
            long userRegistryHash,
            long instrumentRegistryHash,
            long activeOrderHash,
            String forkGitSha,
            String artifactSha256,
            long matcherConfigHash,
            CoreExportStatus exportStatus,
            long checksum) {
        this(productLine, schemaVersion, coreShardId, routeVersion,
                0, appliedCommandCount, 0, 0, appliedCommandCount, matcherSequence,
                businessStateHash, engineStateHash, bookStateHash, symbolRegistryHash,
                userRegistryHash, instrumentRegistryHash, activeOrderHash, 0,
                forkGitSha, artifactSha256, matcherConfigHash, exportStatus, 0, checksum);
    }

    public long outboxAcknowledgedSequence() {
        return exportStatus.acknowledgedSequence();
    }

    public long outboxNextSequence() {
        return exportStatus.nextSequence();
    }

    public int outboxPendingCount() {
        return exportStatus.pendingCount();
    }
}
