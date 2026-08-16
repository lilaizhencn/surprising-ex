package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.zip.CRC32C;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;

final class CoreStateSnapshotCodec {

    private static final int MAGIC = 0x5358534E;
    private static final int VERSION = 5;
    private static final int FIXED_LENGTH = 40;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    private static final int RESULT_LENGTH = 40;
    private static final int EXPORT_FIXED_LENGTH = 20;
    private static final int CHECKSUM_LENGTH = Long.BYTES;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state) {
        byte[] tradingState = TradingStateSnapshotCodec.encode(state.tradingState());
        java.util.ArrayList<byte[]> pendingEvents = new java.util.ArrayList<>(state.exportState().pendingCount());
        for (CoreMessage event : state.exportState().pendingEvents()) {
            pendingEvents.add(CoreMessageCodec.encode(event));
        }
        long exportLength = EXPORT_FIXED_LENGTH;
        for (byte[] event : pendingEvents) {
            exportLength = Math.addExact(exportLength, Math.addExact(Integer.BYTES, event.length));
        }
        long pendingLength = 0;
        java.util.ArrayList<byte[]> pendingCommands = new java.util.ArrayList<>(state.pendingMatching().size());
        for (PendingMatching pending : state.pendingMatching().values()) {
            byte[] encoded = CoreMessageCodec.encode(pending.command());
            pendingCommands.add(encoded);
            pendingLength = Math.addExact(pendingLength,
                    Math.addExact(44L, encoded.length));
        }
        int snapshotLength = Math.toIntExact(Math.addExact(Math.addExact(Math.addExact(
                Math.addExact((long) FIXED_LENGTH,
                        Math.multiplyExact((long) state.lastSourceSequences().size(), SOURCE_SEQUENCE_LENGTH)),
                Math.multiplyExact((long) state.commandResults().size(), RESULT_LENGTH)),
                Math.addExact(exportLength, pendingLength)), Math.addExact(tradingState.length, CHECKSUM_LENGTH)));
        ByteBuffer buffer = ByteBuffer.allocate(snapshotLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.put((byte) ProductLineWireCode.encode(state.productLine()));
        buffer.put((byte) 0);
        buffer.putLong(state.appliedCommandCount());
        buffer.putLong(state.probeValue());
        buffer.putInt(state.commandResults().size());
        buffer.putInt(state.lastSourceSequences().size());
        buffer.putInt(tradingState.length);
        buffer.putInt(state.pendingMatching().size());
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
        int pendingIndex = 0;
        for (PendingMatching pending : state.pendingMatching().values()) {
            byte[] encoded = pendingCommands.get(pendingIndex++);
            buffer.putLong(pending.sequence());
            buffer.putInt(pending.operation().ordinal());
            buffer.putLong(pending.attemptGeneration());
            buffer.putLong(pending.attemptDeadline());
            buffer.putInt(pending.recoveryAttempts());
            buffer.putLong(pending.attemptToken());
            buffer.putInt(encoded.length);
            buffer.put(encoded);
        }
        buffer.put(tradingState);
        CRC32C checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putLong(checksum.getValue());
        return buffer.array();
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
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
        buffer.get();
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        long appliedCommandCount = buffer.getLong();
        buffer.getLong();
        int resultCount = buffer.getInt();
        int sourceSequenceCount = buffer.getInt();
        int tradingStateLength = buffer.getInt();
        int pendingCount = buffer.getInt();
        if (resultCount < 0 || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || pendingCount < 0 || pendingCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS
                || tradingStateLength < 0
                || FIXED_LENGTH + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * RESULT_LENGTH + tradingStateLength > snapshot.length) {
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
            if (eventLength <= 0 || eventLength > buffer.remaining() - tradingStateLength - CHECKSUM_LENGTH) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        for (int index = 0; index < pendingCount; index++) {
            if (buffer.remaining() < 44 + CHECKSUM_LENGTH) {
                throw new ProtocolException("truncated pending matching entry");
            }
            long sequence = buffer.getLong();
            int operationOrdinal = buffer.getInt();
            long attemptGeneration = buffer.getLong();
            long attemptDeadline = buffer.getLong();
            int recoveryAttempts = buffer.getInt();
            long attemptToken = buffer.getLong();
            int messageLength = buffer.getInt();
            if (sequence <= 0 || messageLength <= 0 || messageLength > buffer.remaining() - CHECKSUM_LENGTH
                    || operationOrdinal < 0 || operationOrdinal >= PendingMatching.Operation.values().length) {
                throw new ProtocolException("invalid pending matching entry");
            }
            buffer.position(buffer.position() + messageLength);
        }
        if (buffer.remaining() != tradingStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        byte[] encodedTradingState = new byte[tradingStateLength];
        buffer.get(encodedTradingState);
        TradingCoreState tradingState = tradingStateLength == 0
                ? TradingCoreState.empty(productLine)
                : TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        buffer.getLong();
        CoreExportState exportState = CoreExportState.restore(acknowledgedSequence, nextSequence, events);
        return new CoreSnapshotManifest(productLine, version, appliedCommandCount,
                tradingState.businessStateHash(), exportState.status(), storedChecksum);
    }

    static CoreProbeState decode(byte[] snapshot, ProductLine expectedProductLine) {
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
        buffer.get();
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        long appliedCommandCount = buffer.getLong();
        long probeValue = buffer.getLong();
        int resultCount = buffer.getInt();
        int sourceSequenceCount = buffer.getInt();
        int tradingStateLength = buffer.getInt();
        int pendingCount = buffer.getInt();
        int fixedLength = FIXED_LENGTH;
        if (resultCount < 0 || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
                || pendingCount < 0 || pendingCount > CoreProbeState.MAX_IDEMPOTENCY_RESULTS
                || tradingStateLength < 0
                || fixedLength + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * RESULT_LENGTH + tradingStateLength > snapshot.length) {
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
            if (eventLength <= 0 || eventLength > buffer.remaining() - tradingStateLength - CHECKSUM_LENGTH) {
                throw new ProtocolException("invalid snapshot export event length");
            }
            byte[] event = new byte[eventLength];
            buffer.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        exportState = CoreExportState.restore(acknowledgedSequence, nextSequence, events);
        Map<Long, PendingMatching> pendingMatching = new LinkedHashMap<>();
        for (int index = 0; index < pendingCount; index++) {
            if (buffer.remaining() < 44 + CHECKSUM_LENGTH) {
                throw new ProtocolException("truncated pending matching entry");
            }
            long sequence = buffer.getLong();
            int operationOrdinal = buffer.getInt();
            long attemptGeneration = buffer.getLong();
            long attemptDeadline = buffer.getLong();
            int recoveryAttempts = buffer.getInt();
            long attemptToken = buffer.getLong();
            int messageLength = buffer.getInt();
            if (sequence <= 0 || messageLength <= 0 || messageLength > buffer.remaining() - CHECKSUM_LENGTH
                    || operationOrdinal < 0 || operationOrdinal >= PendingMatching.Operation.values().length) {
                throw new ProtocolException("invalid pending matching entry");
            }
            byte[] encoded = new byte[messageLength];
            buffer.get(encoded);
            PendingMatching pending = new PendingMatching(sequence,
                    PendingMatching.Operation.values()[operationOrdinal], CoreMessageCodec.decode(encoded),
                    attemptGeneration, attemptDeadline, recoveryAttempts, attemptToken);
            if (pendingMatching.put(sequence, pending) != null) {
                throw new ProtocolException("duplicate pending matching sequence");
            }
        }
        if (buffer.remaining() != tradingStateLength + CHECKSUM_LENGTH) {
            throw new ProtocolException("invalid snapshot manifest length");
        }
        TradingCoreState tradingState;
        if (tradingStateLength == 0) {
            tradingState = TradingCoreState.empty(productLine);
        } else {
            byte[] encodedTradingState = new byte[tradingStateLength];
            buffer.get(encodedTradingState);
            tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        }
        buffer.getLong();
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue,
                results, lastSourceSequences, pendingMatching, tradingState, exportState);
    }
}
