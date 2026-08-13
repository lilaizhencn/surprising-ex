package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.product.api.ProductLine;

public record CoreSnapshotManifest(
        ProductLine productLine,
        int schemaVersion,
        long appliedCommandCount,
        long businessStateHash,
        CoreExportStatus exportStatus,
        long checksum) {

    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || appliedCommandCount < 0
                || exportStatus == null || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }
}
