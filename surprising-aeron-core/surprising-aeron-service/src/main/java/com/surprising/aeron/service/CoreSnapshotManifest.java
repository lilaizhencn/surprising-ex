package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.product.api.ProductLine;

public record CoreSnapshotManifest(
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

    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || appliedCommandCount < 0
                || coreShardId == null || coreShardId.isBlank() || routeVersion <= 0
                || matcherSequence < 0 || forkGitSha == null || forkGitSha.isBlank()
                || artifactSha256 == null || artifactSha256.isBlank()
                || exportStatus == null || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }
}
