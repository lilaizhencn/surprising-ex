package com.surprising.aeron.service.orchestration.snapshot;

import com.surprising.product.api.ProductLine;
import com.surprising.aeron.service.state.LaneTopology;

public record CoreSnapshotManifest(
        ProductLine productLine,
        int schemaVersion,
        long snapshotId,
        long coreSequence,
        long clusterTimestamp,
        long clusterPosition,
        long appliedCommandCount,
        long matcherSequence,
        long businessStateHash,
        int engineStateHash,
        long sourceSequenceDigest,
        LaneTopology topology,
        long globalFundsHash,
        long checksum) {

    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || appliedCommandCount < 0
                || snapshotId < 0 || coreSequence < 0 || clusterTimestamp < 0 || clusterPosition < 0
                || coreSequence != appliedCommandCount || matcherSequence < 0
                || topology == null || globalFundsHash == 0
                || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }
}
