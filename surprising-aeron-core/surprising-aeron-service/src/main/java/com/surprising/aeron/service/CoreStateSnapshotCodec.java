package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
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
    private static final int VERSION = 6;
    private static final int FIXED_LENGTH = 48;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    private static final int RESULT_LENGTH = 40;
    private static final int EXPORT_FIXED_LENGTH = 20;
    private static final int CHECKSUM_LENGTH = Long.BYTES;
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state, MatcherSnapshot matcherSnapshot) {
        if (!state.pendingMatching().isEmpty()) {
            throw new IllegalStateException("pending matcher continuations cannot be snapshotted");
        }
        matcherSnapshot.verifyCoreState(state.tradingState(), state.appliedCommandCount());
        byte[] tradingState = TradingStateSnapshotCodec.encode(state.tradingState());
        byte[] matcherState = MatcherSnapshotCodec.encode(matcherSnapshot);
        ArrayList<byte[]> pendingEvents = new ArrayList<>(state.exportState().pendingCount());
        for (CoreMessage event : state.exportState().pendingEvents()) {
            pendingEvents.add(CoreMessageCodec.encode(event));
        }
        long exportLength = EXPORT_FIXED_LENGTH;
        for (byte[] event : pendingEvents) {
            exportLength = Math.addExact(exportLength, Math.addExact(Integer.BYTES, event.length));
        }
        long calculatedLength = Math.addExact(Math.addExact(Math.addExact(
                Math.addExact((long) FIXED_LENGTH,
                        Math.multiplyExact((long) state.lastSourceSequences().size(), SOURCE_SEQUENCE_LENGTH)),
                Math.multiplyExact((long) state.commandResults().size(), RESULT_LENGTH)),
                exportLength), Math.addExact(Math.addExact(matcherState.length, tradingState.length), CHECKSUM_LENGTH));
        if (calculatedLength > MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("core snapshot exceeds maximum size");
        }
        int snapshotLength = Math.toIntExact(calculatedLength);
        ByteBuffer buffer = ByteBuffer.allocate(snapshotLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.put((byte) ProductLineWireCode.encode(state.productLine()));
        buffer.put((byte) 0);
        buffer.putInt(MatcherSnapshot.ROUTE_VERSION);
        buffer.putLong(state.appliedCommandCount());
        buffer.putLong(state.probeValue());
        buffer.putInt(state.commandResults().size());
        buffer.putInt(state.lastSourceSequences().size());
        buffer.putInt(tradingState.length);
        buffer.putInt(matcherState.length);
        buffer.putInt(0);
        state.lastSourceSequences().forEach((sourceKey, sequence) -> {
            buffer.putInt(sourceKey.source().wireCode());
            buffer.putInt(0);
            buffer.putLong(sourceKey.sourceId());
            buffer.putLong(sequence);
        });
        state.commandResults().forEach((commandId, result) -> {
            buffer.putLong(commandId.getMostSignificantBits());
            buffer.putLong(commandId.getLeastSignificantBits());
            buffer.putInt(result.status().wireCode());
            buffer.putInt(result.resultCode().wireCode());
            buffer.putLong(result.appliedCommandCount());
            buffer.putLong(result.stateHash());
        });
        buffer.putLong(state.exportState().acknowledgedSequence());
        buffer.putLong(state.exportState().nextSequence());
        buffer.putInt(state.exportState().pendingCount());
        pendingEvents.forEach(encoded -> {
            buffer.putInt(encoded.length);
            buffer.put(encoded);
        });
        buffer.put(matcherState);
        buffer.put(tradingState);
        CRC32C checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putLong(checksum.getValue());
        return buffer.array();
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        rejectOversizedSnapshot(snapshot);
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
        int pendingCount = buffer.getInt();
        if (resultCount < 0 || resultCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || pendingCount != 0 || tradingStateLength <= 0 || matcherStateLength <= 0
                || FIXED_LENGTH + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * RESULT_LENGTH + tradingStateLength
                        + matcherStateLength > snapshot.length) {
            throw new ProtocolException("invalid snapshot manifest counts");
        }
        int fixedDataEnd = Math.toIntExact(FIXED_LENGTH
                + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                + (long) resultCount * RESULT_LENGTH);
        buffer.position(fixedDataEnd);
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
                    - tradingStateLength - CHECKSUM_LENGTH;
            if (eventLength <= 0 || eventLength > eventBudget) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        if (buffer.remaining() != matcherStateLength + tradingStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        byte[] encodedMatcherState = new byte[matcherStateLength];
        buffer.get(encodedMatcherState);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(encodedMatcherState);
        byte[] encodedTradingState = new byte[tradingStateLength];
        buffer.get(encodedTradingState);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
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
        int pendingCount = buffer.getInt();
        int fixedLength = FIXED_LENGTH;
        if (resultCount < 0 || resultCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || pendingCount != 0 || tradingStateLength <= 0 || matcherStateLength <= 0
                || fixedLength + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * RESULT_LENGTH + tradingStateLength
                        + matcherStateLength > snapshot.length) {
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
            UUID commandId = new UUID(buffer.getLong(), buffer.getLong());
            ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
            CoreResultCode resultCode = CoreResultCode.fromWireCode(buffer.getInt());
            long resultAppliedCount = buffer.getLong();
            long stateHash = buffer.getLong();
            if (resultAppliedCount < 0 || results.put(commandId,
                    new CoreProbeState.StoredResult(status, resultCode, resultAppliedCount, stateHash)) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result: " + commandId);
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
                    - tradingStateLength - CHECKSUM_LENGTH;
            if (eventLength <= 0 || eventLength > eventBudget) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        exportState = CoreExportState.restore(acknowledgedSequence, nextSequence, events);
        if (buffer.remaining() != matcherStateLength + tradingStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        byte[] encodedMatcherState = new byte[matcherStateLength];
        buffer.get(encodedMatcherState);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(encodedMatcherState);
        byte[] encodedTradingState = new byte[tradingStateLength];
        buffer.get(encodedTradingState);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        matcherSnapshot.verifyCoreState(tradingState, appliedCommandCount);
        buffer.getLong();
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue,
                results, lastSourceSequences, tradingState, exportState, matcherSnapshot);
    }

    private static void rejectOversizedSnapshot(byte[] snapshot) {
        if (snapshot != null && snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new ProtocolException("core snapshot exceeds maximum size");
        }
    }
}
