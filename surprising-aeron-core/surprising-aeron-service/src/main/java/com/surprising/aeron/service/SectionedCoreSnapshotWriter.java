package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshotCodec;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.state.CoreFeePolicySnapshotCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32C;

final class SectionedCoreSnapshotWriter {

    private SectionedCoreSnapshotWriter() {
    }

    static SectionedCoreSnapshotCodec.SectionedSnapshot encode(
            CoreProbeState state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        if (!state.pendingMatching().isEmpty()) {
            throw new IllegalStateException("pending matcher continuations cannot be snapshotted");
        }
        var snapshotState = state.snapshotTradingState();
        matcherSnapshot.verifyCoreState(snapshotState, state.appliedCommandCount());
        if (snapshotId != matcherSnapshot.snapshotId() || coreSequence != matcherSnapshot.coreSequence()
                || coreSequence != state.appliedCommandCount() || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalStateException("snapshot fence and matcher manifest do not match");
        }
        byte[][] payloads = {
                header(state, snapshotState, matcherSnapshot, snapshotId, coreSequence,
                        clusterTimestamp, clusterPosition),
                sources(state),
                results(state),
                outbox(state.exportState()),
                MatcherSnapshotCodec.encode(matcherSnapshot),
                TradingStateSnapshotCodec.encode(snapshotState),
                CoreFeePolicySnapshotCodec.encode(state.feePolicies()),
                state.terminalRetention().encode()
        };
        long totalLength = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                + (long) SectionedCoreSnapshotCodec.SECTION_COUNT * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                + SectionedCoreSnapshotCodec.FOOTER_LENGTH;
        for (byte[] payload : payloads) {
            requireSectionLength(payload.length);
            totalLength = Math.addExact(totalLength, payload.length);
        }
        if (totalLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("core snapshot exceeds maximum size");
        }

        byte[] envelope = ByteBuffer.allocate(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SectionedCoreSnapshotCodec.MAGIC)
                .putShort((short) SectionedCoreSnapshotCodec.VERSION)
                .putShort((short) 0)
                .putInt(SectionedCoreSnapshotCodec.SECTION_COUNT)
                .array();
        CRC32C checksum = new CRC32C();
        checksum.update(envelope, 0, envelope.length);
        ArrayList<byte[]> chunks = new ArrayList<>(1 + SectionedCoreSnapshotCodec.SECTION_COUNT * 2);
        chunks.add(envelope);
        for (int index = 0; index < payloads.length; index++) {
            byte[] sectionHeader = sectionHeader(index + 1, payloads[index].length);
            chunks.add(sectionHeader);
            chunks.add(payloads[index]);
            checksum.update(sectionHeader, 0, sectionHeader.length);
            checksum.update(payloads[index], 0, payloads[index].length);
        }
        byte[] footer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.FOOTER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(checksum.getValue()).array();
        chunks.add(sectionHeader(SectionedCoreSnapshotCodec.SECTION_COUNT, footer.length));
        chunks.add(footer);
        return new SectionedCoreSnapshotCodec.SectionedSnapshot(chunks, Math.toIntExact(totalLength));
    }

    private static byte[] header(
            CoreProbeState state,
            com.surprising.aeron.service.state.TradingCoreState snapshotState,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        ByteBuffer buffer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) ProductLineWireCode.encode(state.productLine()))
                .put((byte) 0)
                .putInt(MatcherSnapshot.ROUTE_VERSION)
                .putLong(state.appliedCommandCount())
                .putLong(state.probeValue())
                .putLong(snapshotId)
                .putLong(coreSequence)
                .putLong(clusterTimestamp)
                .putLong(clusterPosition)
                .putLong(matcherSnapshot.matcherSequence())
                .putLong(snapshotState.businessStateHash())
                .putInt(matcherSnapshot.engineStateHash())
                .putInt(matcherSnapshot.bookStateHash())
                .putLong(matcherSnapshot.symbolRegistryHash())
                .putLong(matcherSnapshot.userRegistryHash())
                .putLong(matcherSnapshot.instrumentRegistryHash())
                .putLong(matcherSnapshot.activeOrderHash())
                .putLong(state.sourceSequenceDigest())
                .putLong(state.exportState().acknowledgedSequence())
                .putLong(state.exportState().nextSequence())
                .putInt(state.exportState().pendingCount())
                .putLong(state.exportState().pendingDigest())
                .putLong(matcherSnapshot.matcherConfigHash());
        putFixedAscii(buffer, matcherSnapshot.forkGitSha(), SectionedCoreSnapshotCodec.FORK_GIT_SHA_LENGTH);
        putFixedAscii(buffer, matcherSnapshot.artifactSha256(), SectionedCoreSnapshotCodec.ARTIFACT_SHA256_LENGTH);
        return buffer.array();
    }

    private static void putFixedAscii(ByteBuffer buffer, String value, int expectedLength) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length != expectedLength) throw new IllegalArgumentException("invalid snapshot identity length");
        buffer.put(encoded);
    }

    private static byte[] sources(CoreProbeState state) {
        int count = state.lastSourceSequences().size();
        int length = Math.toIntExact(Integer.BYTES + Math.multiplyExact(
                (long) count, SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(count);
        state.lastSourceSequences().forEach((sourceKey, sequence) -> {
            buffer.putInt(sourceKey.source().wireCode());
            buffer.putInt(0);
            buffer.putLong(sourceKey.sourceId());
            buffer.putLong(sequence);
        });
        return buffer.array();
    }

    private static byte[] results(CoreProbeState state) {
        long length = Integer.BYTES;
        for (CoreProbeState.StoredResult result : state.commandResults().values()) {
            length = Math.addExact(length, Math.addExact(Integer.BYTES, resultEntryLength(result)));
        }
        requireSectionLength(Math.toIntExact(length));
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(state.commandResults().size());
        state.commandResults().forEach((commandId, result) -> putResult(buffer, commandId, result));
        return buffer.array();
    }

    private static byte[] outbox(CoreExportState exportState) {
        long length = SectionedCoreSnapshotCodec.OUTBOX_FIXED_LENGTH;
        for (CoreMessage event : exportState.pendingEvents()) {
            length = Math.addExact(length, Math.addExact(Integer.BYTES, CoreMessageCodec.encodedLength(event)));
        }
        requireSectionLength(Math.toIntExact(length));
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(exportState.acknowledgedSequence())
                .putLong(exportState.nextSequence())
                .putInt(exportState.pendingCount());
        for (CoreMessage event : exportState.pendingEvents()) {
            byte[] encoded = CoreMessageCodec.encode(event);
            buffer.putInt(encoded.length).put(encoded);
        }
        return buffer.array();
    }

    private static void putResult(ByteBuffer buffer, UUID commandId, CoreProbeState.StoredResult result) {
        byte[] responseData = result.responseData();
        buffer.putInt(resultEntryLength(result));
        buffer.putLong(commandId.getMostSignificantBits());
        buffer.putLong(commandId.getLeastSignificantBits());
        buffer.put(result.fingerprint().bytes());
        buffer.putInt(result.status().wireCode());
        buffer.putInt(result.resultCode().wireCode());
        buffer.putLong(result.appliedCommandCount());
        buffer.putLong(result.requiredExportSequence());
        buffer.putLong(result.stateHash());
        buffer.putLong(result.retentionSequence());
        buffer.putInt(responseData.length);
        buffer.put(responseData);
    }

    private static int resultEntryLength(CoreProbeState.StoredResult result) {
        return Math.addExact(CoreStateSnapshotCodec.RESULT_FIXED_LENGTH, result.responseData().length);
    }

    private static byte[] sectionHeader(int id, int payloadLength) {
        return ByteBuffer.allocate(SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(payloadLength).array();
    }

    private static void requireSectionLength(int length) {
        if (length <= 0 || length > SectionedCoreSnapshotCodec.MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("invalid snapshot section length");
        }
    }
}
