package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;

final class CoreStateSnapshotCodec {

    private static final int MAGIC = 0x5358534E;
    private static final int VERSION = 0;
    private static final int FIXED_LENGTH = 48;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    static final int RESULT_FIXED_LENGTH = 92;
    private static final int EXPORT_FIXED_LENGTH = 20;
    private static final int CHECKSUM_LENGTH = Long.BYTES;
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    static final int MAX_SECTION_BYTES = SectionedCoreSnapshotCodec.MAX_SECTION_BYTES;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state, MatcherSnapshot matcherSnapshot) {
        return SectionedCoreSnapshotCodec.encode(state, matcherSnapshot).toByteArray();
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        if (SectionedCoreSnapshotCodec.isSectioned(snapshot)) {
            return SectionedCoreSnapshotCodec.manifest(snapshot, expectedProductLine);
        }
        if (snapshot == null || snapshot.length < FIXED_LENGTH) {
            throw new ProtocolException("snapshot shorter than fixed header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new ProtocolException("invalid snapshot magic");
        }
        int version = Short.toUnsignedInt(buffer.getShort());
        if (version != VERSION) {
            throw new ProtocolException("unsupported snapshot version: " + version);
        }
        if (snapshot.length < FIXED_LENGTH + EXPORT_FIXED_LENGTH + CHECKSUM_LENGTH) {
            throw new ProtocolException("snapshot manifest is truncated");
        }
        long storedChecksum = ByteBuffer.wrap(snapshot, snapshot.length - CHECKSUM_LENGTH, CHECKSUM_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0, snapshot.length - CHECKSUM_LENGTH);
        if (storedChecksum != checksum.getValue()) {
            throw new ProtocolException("snapshot checksum mismatch");
        }
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.get()));
        int shardCode = Byte.toUnsignedInt(buffer.get());
        int routeVersion = buffer.getInt();
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        if (shardCode != 0 || routeVersion != MatcherSnapshot.ROUTE_VERSION) {
            throw new ProtocolException("unsupported matcher route");
        }
        long appliedCommandCount = buffer.getLong();
        buffer.getLong();
        int resultCount = buffer.getInt();
        int sourceSequenceCount = buffer.getInt();
        int tradingStateLength = buffer.getInt();
        int matcherStateLength = buffer.getInt();
        int retentionStateLength = buffer.getInt();
        int pendingCount = 0;
        if (resultCount < 0 || resultCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || retentionStateLength <= 0 || tradingStateLength <= 0 || matcherStateLength <= 0
                || FIXED_LENGTH + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * (Integer.BYTES + RESULT_FIXED_LENGTH)
                        + EXPORT_FIXED_LENGTH + tradingStateLength + matcherStateLength + retentionStateLength
                        + CHECKSUM_LENGTH
                        > snapshot.length) {
            throw new ProtocolException("invalid snapshot manifest counts");
        }
        buffer.position(Math.toIntExact(FIXED_LENGTH
                + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH));
        for (int index = 0; index < resultCount; index++) {
            readResult(buffer, matcherStateLength, tradingStateLength, retentionStateLength);
        }
        long acknowledgedSequence = buffer.getLong();
        long nextSequence = buffer.getLong();
        int eventCount = buffer.getInt();
        if (eventCount < 0 || eventCount > CoreExportState.MAX_PENDING_EVENTS) {
            throw new ProtocolException("invalid snapshot export count");
        }
        ArrayList<CoreMessage> events = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            if (buffer.remaining() < Integer.BYTES) {
                throw new ProtocolException("truncated snapshot export event");
            }
            int eventLength = buffer.getInt();
            long eventBudget = (long) buffer.remaining() - matcherStateLength
                    - tradingStateLength - retentionStateLength - CHECKSUM_LENGTH;
            if (eventLength <= 0 || eventLength > eventBudget) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        if (buffer.remaining() != matcherStateLength + tradingStateLength + retentionStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        byte[] encodedMatcherState = new byte[matcherStateLength];
        buffer.get(encodedMatcherState);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(encodedMatcherState);
        byte[] encodedTradingState = new byte[tradingStateLength];
        buffer.get(encodedTradingState);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        byte[] encodedRetentionState = new byte[retentionStateLength];
        buffer.get(encodedRetentionState);
        TerminalStateRetention.decode(encodedRetentionState);
        matcherSnapshot.verifyCoreState(tradingState, appliedCommandCount);
        buffer.getLong();
        CoreExportState exportState = CoreExportState.restore(acknowledgedSequence, nextSequence, events);
        return new CoreSnapshotManifest(productLine, version, matcherSnapshot.coreShardId(), routeVersion,
                appliedCommandCount, matcherSnapshot.matcherSequence(), tradingState.businessStateHash(),
                matcherSnapshot.engineStateHash(), matcherSnapshot.bookStateHash(), matcherSnapshot.symbolRegistryHash(),
                matcherSnapshot.userRegistryHash(), matcherSnapshot.instrumentRegistryHash(),
                matcherSnapshot.activeOrderHash(), matcherSnapshot.forkGitSha(),
                matcherSnapshot.artifactSha256(), matcherSnapshot.matcherConfigHash(),
                exportState.status(), storedChecksum);
    }

    static CoreProbeState decode(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
        if (SectionedCoreSnapshotCodec.isSectioned(snapshot)) {
            return SectionedCoreSnapshotCodec.decode(snapshot, expectedProductLine);
        }
        if (snapshot == null || snapshot.length < FIXED_LENGTH) {
            throw new ProtocolException("snapshot shorter than fixed header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new ProtocolException("invalid snapshot magic");
        }
        int version = Short.toUnsignedInt(buffer.getShort());
        if (version != VERSION) {
            throw new ProtocolException("unsupported snapshot version: " + version);
        }
        if (snapshot.length < FIXED_LENGTH + EXPORT_FIXED_LENGTH + CHECKSUM_LENGTH) {
            throw new ProtocolException("snapshot manifest is truncated");
        }
        long storedChecksum = ByteBuffer.wrap(snapshot, snapshot.length - CHECKSUM_LENGTH, CHECKSUM_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0, snapshot.length - CHECKSUM_LENGTH);
        if (storedChecksum != checksum.getValue()) {
            throw new ProtocolException("snapshot checksum mismatch");
        }
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.get()));
        int shardCode = Byte.toUnsignedInt(buffer.get());
        int routeVersion = buffer.getInt();
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        if (shardCode != 0 || routeVersion != MatcherSnapshot.ROUTE_VERSION) {
            throw new ProtocolException("unsupported matcher route");
        }
        long appliedCommandCount = buffer.getLong();
        long probeValue = buffer.getLong();
        int resultCount = buffer.getInt();
        int sourceSequenceCount = buffer.getInt();
        int tradingStateLength = buffer.getInt();
        int matcherStateLength = buffer.getInt();
        int retentionStateLength = buffer.getInt();
        int pendingCount = 0;
        if (resultCount < 0 || resultCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || retentionStateLength <= 0 || tradingStateLength <= 0 || matcherStateLength <= 0
                || FIXED_LENGTH + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * (Integer.BYTES + RESULT_FIXED_LENGTH)
                        + EXPORT_FIXED_LENGTH + tradingStateLength + matcherStateLength + retentionStateLength
                        + CHECKSUM_LENGTH
                        > snapshot.length) {
            throw new ProtocolException("invalid snapshot result count: " + resultCount);
        }
        Map<CoreProbeState.SourceKey, Long> lastSourceSequences = new LinkedHashMap<>();
        for (int index = 0; index < sourceSequenceCount; index++) {
            CommandSource source = CommandSource.fromWireCode(buffer.getInt());
            buffer.getInt();
            long sourceId = buffer.getLong();
            long sequence = buffer.getLong();
            if (sequence < 0) {
                throw new ProtocolException("negative snapshot source sequence");
            }
            var sourceKey = new CoreProbeState.SourceKey(source, sourceId);
            if (lastSourceSequences.put(sourceKey, sequence) != null) {
                throw new ProtocolException("duplicate snapshot command source: " + sourceKey);
            }
        }
        Map<UUID, CoreProbeState.StoredResult> results = new LinkedHashMap<>();
        for (int index = 0; index < resultCount; index++) {
            SnapshotResult result = readResult(buffer, matcherStateLength, tradingStateLength, retentionStateLength);
            if (results.put(result.commandId(), result.value()) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result: " + result.commandId());
            }
        }
        CoreExportState exportState = new CoreExportState();
        long acknowledgedSequence = buffer.getLong();
        long nextSequence = buffer.getLong();
        int eventCount = buffer.getInt();
        if (eventCount < 0 || eventCount > CoreExportState.MAX_PENDING_EVENTS) {
            throw new ProtocolException("invalid snapshot export count");
        }
        ArrayList<CoreMessage> events = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            if (buffer.remaining() < Integer.BYTES) {
                throw new ProtocolException("truncated snapshot export event");
            }
            int eventLength = buffer.getInt();
            long eventBudget = (long) buffer.remaining() - matcherStateLength
                    - tradingStateLength - retentionStateLength - CHECKSUM_LENGTH;
            if (eventLength <= 0 || eventLength > eventBudget) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        exportState = CoreExportState.restore(acknowledgedSequence, nextSequence, events);
        if (buffer.remaining() != matcherStateLength + tradingStateLength + retentionStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        byte[] encodedMatcherState = new byte[matcherStateLength];
        buffer.get(encodedMatcherState);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(encodedMatcherState);
        byte[] encodedTradingState = new byte[tradingStateLength];
        buffer.get(encodedTradingState);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        byte[] encodedRetentionState = new byte[retentionStateLength];
        buffer.get(encodedRetentionState);
        TerminalStateRetention terminalRetention = TerminalStateRetention.decode(encodedRetentionState);
        matcherSnapshot.verifyCoreState(tradingState, appliedCommandCount);
        buffer.getLong();
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue,
                results, lastSourceSequences, tradingState, exportState, terminalRetention, matcherSnapshot);
    }

    private static int resultEntryLength(CoreProbeState.StoredResult result) {
        return Math.addExact(RESULT_FIXED_LENGTH, result.responseData().length);
    }

    private static byte[] encodeResult(UUID commandId, CoreProbeState.StoredResult result) {
        byte[] responseData = result.responseData();
        ByteBuffer buffer = ByteBuffer.allocate(resultEntryLength(result)).order(ByteOrder.LITTLE_ENDIAN);
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
        return buffer.array();
    }

    private static SnapshotResult readResult(ByteBuffer source, int matcherStateLength, int tradingStateLength,
                                             int retentionStateLength) {
        if (source.remaining() < Integer.BYTES) {
            throw new ProtocolException("truncated snapshot command result");
        }
        int encodedLength = source.getInt();
        long resultBudget = (long) source.remaining() - matcherStateLength - tradingStateLength
                - retentionStateLength - CHECKSUM_LENGTH;
        if (encodedLength < RESULT_FIXED_LENGTH || encodedLength > resultBudget) {
            throw new ProtocolException("invalid snapshot command result length");
        }
        byte[] encoded = new byte[encodedLength];
        source.get(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        UUID commandId = new UUID(buffer.getLong(), buffer.getLong());
        byte[] fingerprint = new byte[CommandFingerprint.LENGTH];
        buffer.get(fingerprint);
        ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(buffer.getInt());
        long appliedCommandCount = buffer.getLong();
        long requiredExportSequence = buffer.getLong();
        long stateHash = buffer.getLong();
        long retentionSequence = buffer.getLong();
        int responseLength = buffer.getInt();
        if (appliedCommandCount < 0 || requiredExportSequence < 0 || retentionSequence <= 0
                || responseLength < 0 || responseLength != buffer.remaining()) {
            throw new ProtocolException("invalid snapshot command result metadata");
        }
        byte[] responseData = new byte[responseLength];
        buffer.get(responseData);
        return new SnapshotResult(commandId, new CoreProbeState.StoredResult(
                CommandFingerprint.fromBytes(fingerprint), status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData, retentionSequence));
    }

    private record SnapshotResult(UUID commandId, CoreProbeState.StoredResult value) {
    }

    private static void rejectOversizedSnapshot(byte[] snapshot) {
        if (snapshot != null && snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }
}
