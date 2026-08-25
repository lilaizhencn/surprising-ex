package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshotCodec;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.aeron.service.state.CoreRiskState;
import com.surprising.aeron.service.state.CoreTreasuryState;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class CoreStateSnapshotCodecTest {

    private static final int ENVELOPE_LENGTH = 12;
    private static final int SECTION_HEADER_LENGTH = 8;
    private static final int HEADER_PAYLOAD_OFFSET = ENVELOPE_LENGTH + SECTION_HEADER_LENGTH;

    @Test
    void snapshotRestoresFingerprintResponseBytesAndRetentionMetadata() {
        UUID commandId = UUID.randomUUID();
        byte[] response = {1, 3, 5, 7, 11};
        CommandFingerprint fingerprint = CommandFingerprint.fromBytes(new byte[] {
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
        });
        CoreProbeState.StoredResult stored = new CoreProbeState.StoredResult(
                fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 17, 77, response, 4);
        CoreProbeState original = CoreProbeState.restore(ProductLine.SPOT, 1, 0,
                Map.of(commandId, stored), Map.of(),
                com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                new CoreExportState());

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, original.snapshot());

        assertThat(restored.commandResults().get(commandId).responseData()).containsExactly(response);
        assertThat(restored.commandResults().get(commandId).fingerprint()).isEqualTo(fingerprint);
        assertThat(restored.commandResults().get(commandId).appliedCommandCount()).isEqualTo(1);
        assertThat(restored.commandResults().get(commandId).requiredExportSequence()).isEqualTo(17);
        assertThat(restored.commandResults().get(commandId).stateHash()).isEqualTo(77);
        assertThat(restored.commandResults().get(commandId).retentionSequence()).isEqualTo(4);
    }

    @Test
    void snapshotUsesDeterministicBoundedSectionOrder() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        try {
            byte[] snapshot = state.snapshot(41);
            ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);

            assertThat(Short.toUnsignedInt(buffer.getShort(Integer.BYTES))).isEqualTo(11);
            assertThat(buffer.getInt(8)).isEqualTo(8);
            buffer.position(ENVELOPE_LENGTH);
            int[] sectionIds = new int[8];
            for (int index = 0; index < sectionIds.length; index++) {
                sectionIds[index] = buffer.getInt();
                int sectionLength = buffer.getInt();
                assertThat(sectionLength).isBetween(1, CoreStateSnapshotCodec.MAX_SECTION_BYTES);
                buffer.position(Math.addExact(buffer.position(), sectionLength));
            }
            assertThat(sectionIds).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(buffer.hasRemaining()).isFalse();
        } finally {
            state.close();
        }
    }

    @Test
    void rejectsTruncationOversizeInvalidSectionMetadataChecksumAndTrailingGarbage() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        try {
            byte[] snapshot = state.snapshot(42);
            byte[] invalidCount = snapshot.clone();
            ByteBuffer.wrap(invalidCount).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 9);
            byte[] invalidLength = snapshot.clone();
            ByteBuffer.wrap(invalidLength).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ENVELOPE_LENGTH + Integer.BYTES, CoreStateSnapshotCodec.MAX_SECTION_BYTES + 1);
            byte[] shortHeader = snapshot.clone();
            ByteBuffer.wrap(shortHeader).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ENVELOPE_LENGTH + Integer.BYTES, 1);
            byte[] checksumMismatch = snapshot.clone();
            checksumMismatch[checksumMismatch.length - SECTION_HEADER_LENGTH - Long.BYTES - 1] ^= 1;

            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    Arrays.copyOf(snapshot, snapshot.length - 1), ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    new byte[CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES + 1], ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("maximum size");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(invalidCount, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section count");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(invalidLength, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section length");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(shortHeader, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("section length");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(checksumMismatch, ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
            assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                    Arrays.copyOf(snapshot, snapshot.length + 1), ProductLine.SPOT))
                    .isInstanceOf(ProtocolException.class).hasMessageContaining("trailing");
        } finally {
            state.close();
        }
    }

    @Test
    void rejectsMutationAndTruncationAtEverySectionBoundary() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        try {
            byte[] snapshot = state.snapshot(48);
            ByteBuffer layout = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
            layout.position(ENVELOPE_LENGTH);
            for (int expectedSectionId = 1; expectedSectionId <= 8; expectedSectionId++) {
                assertThat(layout.getInt()).isEqualTo(expectedSectionId);
                int sectionLength = layout.getInt();
                int payloadOffset = layout.position();

                byte[] mutated = snapshot.clone();
                mutated[payloadOffset] ^= 1;
                assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(mutated, ProductLine.SPOT))
                        .as("mutation in section %s", expectedSectionId)
                        .isInstanceOf(ProtocolException.class);

                byte[] truncated = Arrays.copyOf(snapshot, payloadOffset + sectionLength - 1);
                assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(truncated, ProductLine.SPOT))
                        .as("truncation in section %s", expectedSectionId)
                        .isInstanceOf(ProtocolException.class);
                layout.position(payloadOffset + sectionLength);
            }
        } finally {
            state.close();
        }
    }

    @Test
    void rejectsLegacyVersionEightSnapshot() {
        TradingCoreState tradingState = TradingCoreState.empty(ProductLine.SPOT);
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            matcherSnapshot = adapter.snapshotAsync(47, 0, tradingState, List.of()).join();
        }
        byte[] legacy = legacySnapshot(tradingState, matcherSnapshot);

        assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(legacy, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 8");
    }

    @Test
    void fragmentedVersionEightRecoveryFailsClosed() {
        TradingCoreState tradingState = TradingCoreState.empty(ProductLine.SPOT);
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            matcherSnapshot = adapter.snapshotAsync(47, 0, tradingState, List.of()).join();
        }
        byte[] legacy = legacySnapshot(tradingState, matcherSnapshot);
        UnsafeBuffer encoded = new UnsafeBuffer(legacy);
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        assertThatThrownBy(() -> recovery.accept(encoded, 0, legacy.length))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 8");
    }

    @Test
    void byteExactRoundTripPreservesStateHashOpenOrdersAndOutbox() {
        TradingCoreState tradingState = stateWithOpenBid();
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            assertThat(adapter.placeAsync(7, bid()).join().accepted()).isTrue();
            matcherSnapshot = adapter.snapshotAsync(
                    43, 1, tradingState, new ActiveOrderIndex(tradingState).orders()).join();
        }
        CoreProbeState outboxSource = new CoreProbeState(ProductLine.SPOT);
        CoreMessage increment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.fromString("00000000-0000-0000-0000-000000000043"), ProductLine.SPOT,
                CommandSource.GATEWAY, 43, 1, 7, 1_000, 43), CoreProtocol.probePayload(3));
        assertThat(outboxSource.apply(increment).status()).isEqualTo(ResponseStatus.APPLIED);
        CoreProbeState original = CoreProbeState.restore(ProductLine.SPOT, 1, 3,
                outboxSource.commandResults(), outboxSource.lastSourceSequences(), tradingState,
                outboxSource.exportState(), matcherSnapshot);
        CoreProbeState restored = null;
        try {
            byte[] first = CoreStateSnapshotCodec.encode(original, matcherSnapshot);
            restored = CoreStateSnapshotCodec.decode(first, ProductLine.SPOT);
            byte[] second = CoreStateSnapshotCodec.encode(restored, matcherSnapshot);

            assertThat(second).containsExactly(first);
            assertThat(restored.stateHash()).isEqualTo(original.stateHash());
            assertThat(restored.tradingState().orders()).containsExactlyEntriesOf(original.tradingState().orders());
            assertThat(restored.exportState().status()).isEqualTo(original.exportState().status());
            assertThat(restored.exportState().pending()).isEqualTo(original.exportState().pending());
            CoreSnapshotManifest originalManifest = CoreStateSnapshotCodec.manifest(first, ProductLine.SPOT);
            CoreSnapshotManifest restoredMatcherManifest = CoreStateSnapshotCodec.manifest(
                    restored.snapshot(44), ProductLine.SPOT);
            assertThat(restoredMatcherManifest.engineStateHash()).isEqualTo(originalManifest.engineStateHash());
            assertThat(restoredMatcherManifest.bookStateHash()).isEqualTo(originalManifest.bookStateHash());
            assertThat(restoredMatcherManifest.symbolRegistryHash()).isEqualTo(originalManifest.symbolRegistryHash());
            assertThat(restoredMatcherManifest.userRegistryHash()).isEqualTo(originalManifest.userRegistryHash());
            assertThat(restoredMatcherManifest.instrumentRegistryHash())
                    .isEqualTo(originalManifest.instrumentRegistryHash());
            assertThat(restoredMatcherManifest.activeOrderHash()).isEqualTo(originalManifest.activeOrderHash());
            assertThat(restoredMatcherManifest.forkGitSha()).isEqualTo(originalManifest.forkGitSha());
            assertThat(restoredMatcherManifest.artifactSha256()).isEqualTo(originalManifest.artifactSha256());
            assertThat(restoredMatcherManifest.matcherConfigHash()).isEqualTo(originalManifest.matcherConfigHash());
        } finally {
            if (restored != null) restored.close();
            original.close();
            outboxSource.close();
        }
    }

    @Test
    void pairedManifestExposesFencePositionSequencesHashesAndOutboxMetadata() {
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            matcherSnapshot = adapter.snapshotAsync(73, 1, TradingCoreState.empty(ProductLine.SPOT), List.of()).join();
        }
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT,
                (snapshotId, coreSequence, tradingState, activeOrders) ->
                        java.util.concurrent.CompletableFuture.completedFuture(matcherSnapshot))) {
            CoreMessage increment = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.fromString("00000000-0000-0000-0000-000000000073"), ProductLine.SPOT,
                    CommandSource.GATEWAY, 73, 1, 7, 1_000, 73), CoreProtocol.probePayload(3));
            assertThat(state.apply(increment).status()).isEqualTo(ResponseStatus.APPLIED);
            state.beginSnapshot(73, Long.MAX_VALUE);
            byte[] snapshot = state.pollSnapshot(1_234, 5_678, System.nanoTime());
            assertThat(snapshot).isNotNull();

            CoreSnapshotManifest manifest = CoreProbeState.inspectSnapshot(ProductLine.SPOT, snapshot);

            assertThat(manifest.schemaVersion()).isEqualTo(11);
            assertThat(manifest.snapshotId()).isEqualTo(73);
            assertThat(manifest.coreSequence()).isEqualTo(state.appliedCommandCount());
            assertThat(manifest.clusterTimestamp()).isEqualTo(1_234);
            assertThat(manifest.clusterPosition()).isEqualTo(5_678);
            assertThat(manifest.sourceSequenceDigest()).isNotZero();
            assertThat(manifest.outboxAcknowledgedSequence()).isZero();
            assertThat(manifest.outboxNextSequence()).isEqualTo(2);
            assertThat(manifest.outboxPendingCount()).isEqualTo(1);
            assertThat(manifest.outboxPendingDigest()).isNotZero();
            assertThat(manifest.matcherSequence()).isNotNegative();
            assertThat(manifest.businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
            assertThat(manifest.forkGitSha()).isEqualTo(MatcherSnapshot.FORK_GIT_SHA);
            assertThat(manifest.artifactSha256()).isEqualTo(MatcherSnapshot.ARTIFACT_SHA256);
            assertThat(manifest.matcherConfigHash()).isEqualTo(MatcherSnapshot.MATCHER_CONFIG_HASH);
        }
    }

    @Test
    void rejectsEveryPairedManifestMismatchAfterChecksumRecomputation() {
        byte[] snapshot;
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            assertThat(state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.fromString("00000000-0000-0000-0000-000000000074"), ProductLine.SPOT,
                    CommandSource.GATEWAY, 74, 1, 7, 1_000, 74), CoreProtocol.probePayload(3))).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            snapshot = state.snapshot(74);
        }

        Map<String, byte[]> mismatches = new LinkedHashMap<>();
        mismatches.put("product line", mutateHeaderProductLine(snapshot));
        mismatches.put("route", mutateHeaderInt(snapshot, 2));
        mismatches.put("applied sequence", mutateHeaderLong(snapshot, 6));
        mismatches.put("snapshot id", mutateHeaderLong(snapshot, 22));
        mismatches.put("core sequence", mutateHeaderLong(snapshot, 30));
        mismatches.put("matcher sequence", mutateHeaderLong(snapshot, 54));
        mismatches.put("business state hash", mutateHeaderLong(snapshot, 62));
        mismatches.put("engine state hash", mutateHeaderInt(snapshot, 70));
        mismatches.put("book state hash", mutateHeaderInt(snapshot, 74));
        mismatches.put("symbol registry hash", mutateHeaderLong(snapshot, 78));
        mismatches.put("user registry hash", mutateHeaderLong(snapshot, 86));
        mismatches.put("instrument registry hash", mutateHeaderLong(snapshot, 94));
        mismatches.put("active order hash", mutateHeaderLong(snapshot, 102));
        mismatches.put("source sequence digest", mutateHeaderLong(snapshot, 110));
        mismatches.put("outbox acknowledged sequence", mutateHeaderLong(snapshot, 118));
        mismatches.put("outbox next sequence", mutateHeaderLong(snapshot, 126));
        mismatches.put("outbox pending count", mutateHeaderInt(snapshot, 134));
        mismatches.put("outbox pending digest", mutateHeaderLong(snapshot, 138));
        mismatches.put("matcher config", mutateHeaderLong(snapshot, 146));
        mismatches.put("fork identity", mutateHeaderByte(snapshot, 154));
        mismatches.put("artifact identity", mutateHeaderByte(snapshot, 194));

        mismatches.forEach((field, mutated) -> {
            Throwable failure = catchThrowable(() -> CoreStateSnapshotCodec.decode(mutated, ProductLine.SPOT));
            assertThat(failure).as(field).isInstanceOf(ProtocolException.class);
            assertThat(failure).as(field).hasMessageContaining(field);
        });
    }

    @Test
    void rejectsWrongProductLineBeforeDecodingOtherV9Sections() {
        byte[] snapshot;
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            snapshot = state.snapshot(75);
        }

        byte[] mismatched = mutateHeaderProductLine(snapshot);
        ByteBuffer sections = ByteBuffer.wrap(mismatched).order(ByteOrder.LITTLE_ENDIAN);
        sections.position(ENVELOPE_LENGTH);
        for (int sectionId = 1; sectionId <= 5; sectionId++) {
            assertThat(sections.getInt()).isEqualTo(sectionId);
            int sectionLength = sections.getInt();
            if (sectionId == 5) {
                Arrays.fill(mismatched, sections.position(), sections.position() + sectionLength, (byte) 0);
                break;
            }
            sections.position(sections.position() + sectionLength);
        }
        byte[] rejected = rewriteOuterChecksum(mismatched);

        assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(rejected, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("snapshot product line mismatch: OPTION");
    }

    @Test
    void checksumProtectsClusterTimestampAndPositionManifestFields() {
        byte[] snapshot;
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            snapshot = state.snapshot(76);
        }

        assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                mutateHeaderLongWithoutChecksum(snapshot, 38), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
        assertThatThrownBy(() -> CoreStateSnapshotCodec.decode(
                mutateHeaderLongWithoutChecksum(snapshot, 46), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("checksum");
    }

    @Test
    void legacyManifestFailsClosed() {
        TradingCoreState tradingState = TradingCoreState.empty(ProductLine.SPOT);
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            matcherSnapshot = adapter.snapshotAsync(47, 0, tradingState, List.of()).join();
        }

        byte[] legacy = legacySnapshot(tradingState, matcherSnapshot);
        assertThatThrownBy(() -> CoreStateSnapshotCodec.manifest(legacy, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version: 8");
    }

    private static TradingCoreState stateWithOpenBid() {
        CoreOrderState order = new CoreOrderState(1, ProductLine.SPOT, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 2, 0, 2, false, CoreOrderStatus.OPEN, 1);
        return new TradingCoreState(ProductLine.SPOT, 1,
                Map.of(7L, CoreUserState.empty(ProductLine.SPOT, 7)), Map.of(1L, order), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());
    }

    private static PlaceOrderCommand bid() {
        return new PlaceOrderCommand(
                1,
                "BTC-USDT",
                1,
                "BTC",
                "USDT",
                "USDT",
                CoreOrderSide.BUY,
                100,
                100,
                100,
                100,
                2,
                false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                ReservationKind.SPOT_ASSET,
                "USDT",
                200,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT,
                com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                false,
                "",
                0,
                0
        );
    }

    private static byte[] mutateHeaderByte(byte[] snapshot, int fieldOffset) {
        byte[] mutated = snapshot.clone();
        mutated[HEADER_PAYLOAD_OFFSET + fieldOffset] ^= 1;
        return rewriteOuterChecksum(mutated);
    }

    private static byte[] mutateHeaderProductLine(byte[] snapshot) {
        byte[] mutated = snapshot.clone();
        mutated[HEADER_PAYLOAD_OFFSET] = (byte) ProductLineWireCode.encode(ProductLine.OPTION);
        return rewriteOuterChecksum(mutated);
    }

    private static byte[] mutateHeaderInt(byte[] snapshot, int fieldOffset) {
        byte[] mutated = snapshot.clone();
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putInt(offset, buffer.getInt(offset) + 1);
        return rewriteOuterChecksum(mutated);
    }

    private static byte[] mutateHeaderLong(byte[] snapshot, int fieldOffset) {
        byte[] mutated = snapshot.clone();
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putLong(offset, buffer.getLong(offset) + 1);
        return rewriteOuterChecksum(mutated);
    }

    private static byte[] mutateHeaderLongWithoutChecksum(byte[] snapshot, int fieldOffset) {
        byte[] mutated = snapshot.clone();
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        int offset = HEADER_PAYLOAD_OFFSET + fieldOffset;
        buffer.putLong(offset, buffer.getLong(offset) + 1);
        return mutated;
    }

    private static byte[] rewriteOuterChecksum(byte[] snapshot) {
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0, snapshot.length - SECTION_HEADER_LENGTH - Long.BYTES);
        ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(snapshot.length - Long.BYTES, checksum.getValue());
        return snapshot;
    }

    private static byte[] legacySnapshot(TradingCoreState tradingState, MatcherSnapshot matcherSnapshot) {
        byte[] matcher = MatcherSnapshotCodec.encode(matcherSnapshot);
        byte[] trading = TradingStateSnapshotCodec.encode(tradingState);
        byte[] retention = new TerminalStateRetention().encode();
        int length = 48 + 20 + matcher.length + trading.length + retention.length + Long.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x5358534E).putShort((short) 8).put((byte) 1).put((byte) 0)
                .putInt(MatcherSnapshot.ROUTE_VERSION).putLong(0).putLong(0)
                .putInt(0).putInt(0).putInt(trading.length).putInt(matcher.length).putInt(retention.length)
                .putLong(0).putLong(1).putInt(0)
                .put(matcher).put(trading).put(retention);
        CRC32C checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putLong(checksum.getValue());
        return buffer.array();
    }
}
