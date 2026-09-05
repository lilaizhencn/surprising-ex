package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;

final class CoreStateSnapshotCodec {

    static final int RESULT_FIXED_LENGTH = 92;
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    static final int MAX_SECTION_BYTES = SectionedCoreSnapshotCodec.MAX_SECTION_BYTES;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotCodec.encode(state, matcherSnapshot).toByteArray();
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        return SectionedCoreSnapshotCodec.manifest(snapshot, expectedProductLine);
    }

    static CoreProbeState decode(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        return SectionedCoreSnapshotCodec.decode(snapshot, expectedProductLine);
    }

    private static void rejectOversizedSnapshot(byte[] snapshot) {
        if (snapshot != null && snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }
}
