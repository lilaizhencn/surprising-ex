package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

final class SectionedCoreSnapshotCodec {

    static final int MAGIC = 0x5358534E;
    static final int VERSION = 16;
    static final int ENVELOPE_LENGTH = 12;
    static final int SECTION_HEADER_LENGTH = 8;
    static final int FORK_GIT_SHA_LENGTH = 40;
    static final int ARTIFACT_SHA256_LENGTH = 64;
    static final int HEADER_LENGTH = 318;
    static final int SOURCE_SEQUENCE_LENGTH = 24;
    static final int OUTBOX_FIXED_LENGTH = 20;
    static final int FOOTER_LENGTH = Long.BYTES;
    static final int BASE_SECTION_COUNT = 10;
    static final int SECTION_COUNT = BASE_SECTION_COUNT + 4;
    static final int MAX_SECTION_COUNT = BASE_SECTION_COUNT + Long.SIZE;
    static final int MAX_SECTION_BYTES = CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES
            - ENVELOPE_LENGTH - MAX_SECTION_COUNT * SECTION_HEADER_LENGTH;

    static int sectionCount(int accountLaneCount) {
        if (accountLaneCount < 1 || accountLaneCount > Long.SIZE) {
            throw new IllegalArgumentException("invalid account lane section count");
        }
        return BASE_SECTION_COUNT + accountLaneCount;
    }

    private SectionedCoreSnapshotCodec() {
    }

    static boolean isSectioned(byte[] snapshot) {
        if (snapshot == null || snapshot.length < Integer.BYTES + Short.BYTES) return false;
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt() == MAGIC && Short.toUnsignedInt(buffer.getShort()) == VERSION;
    }

    static SectionedSnapshot encode(CoreProbeState state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotWriter.encode(
                state, matcherSnapshot, matcherSnapshot.snapshotId(), matcherSnapshot.coreSequence(), 0, 0);
    }

    static SectionedSnapshot encode(
            CoreProbeState state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        return SectionedCoreSnapshotWriter.encode(state, matcherSnapshot, snapshotId, coreSequence,
                clusterTimestamp, clusterPosition);
    }

    static CoreSnapshotImage capture(
            CoreProbeState state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        return SectionedCoreSnapshotWriter.capture(state, matcherSnapshot, snapshotId, coreSequence,
                clusterTimestamp, clusterPosition);
    }

    static SectionedSnapshot encode(CoreSnapshotImage image) {
        return SectionedCoreSnapshotWriter.encode(image);
    }

    static CoreProbeState decode(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).decode(expectedProductLine);
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        return recovery(snapshot).manifest(expectedProductLine);
    }

    private static RecoveryBuffer recovery(byte[] snapshot) {
        if (snapshot == null) throw new ProtocolException("snapshot is null");
        RecoveryBuffer recovery = new RecoveryBuffer();
        recovery.accept(new UnsafeBuffer(snapshot), 0, snapshot.length);
        return recovery;
    }

    static final class RecoveryBuffer {
        private final SectionedCoreSnapshotRecovery delegate = new SectionedCoreSnapshotRecovery();

        void accept(DirectBuffer source, int offset, int length) {
            delegate.accept(source, offset, length);
        }

        CoreProbeState decode(ProductLine expectedProductLine) {
            return delegate.decode(expectedProductLine);
        }

        CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            return delegate.manifest(expectedProductLine);
        }

        int ownedSectionCount() {
            return delegate.ownedSectionCount();
        }

        int totalLength() {
            return delegate.totalLength();
        }

        int allocatedBytes() {
            return delegate.allocatedBytes();
        }
    }

    static final class SectionedSnapshot {
        private final List<byte[]> chunks;
        private final int length;

        SectionedSnapshot(List<byte[]> chunks, int length) {
            this.chunks = List.copyOf(chunks);
            this.length = length;
        }

        List<byte[]> chunks() {
            return chunks;
        }

        int length() {
            return length;
        }

        byte[] toByteArray() {
            byte[] snapshot = new byte[length];
            int offset = 0;
            for (byte[] chunk : chunks()) {
                System.arraycopy(chunk, 0, snapshot, offset, chunk.length);
                offset += chunk.length;
            }
            return snapshot;
        }
    }
}
