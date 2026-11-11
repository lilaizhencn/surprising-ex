package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.product.api.ProductLine;

/** 对外查看快照配对信息的清单，列出产品线、版本、Core/Matcher 水位、路由和校验值。 */
public record CoreSnapshotManifest(
        ProductLine productLine,
        int schemaVersion,
        long snapshotId,
        long clusterTimestamp,
        long clusterPosition,
        long appliedCommandCount,
        long matcherSequence,
        long businessStateHash,
        int engineStateHash,
        long sourceSequenceDigest,
        LaneTopology topology,
        long checksum) {

    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || snapshotId < 0 || appliedCommandCount < 0 || clusterTimestamp < 0 || clusterPosition < 0
                || matcherSequence < 0
                || topology == null
                || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }
}
