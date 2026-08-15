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
    private static final int VERSION = 3;
    private static final int VERSION_2 = 2;
    private static final int VERSION_1 = 1;
    private static final int FIXED_LENGTH_V1 = 32;
    private static final int FIXED_LENGTH = 36;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    private static final int RESULT_LENGTH = 40;
    private static final int EXPORT_FIXED_LENGTH = 20;
    private static final int CHECKSUM_LENGTH = Long.BYTES;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state) {
        byte[] tradingState = TradingStateSnapshotCodec.encode(state.tradingState());
        long exportLength = EXPORT_FIXED_LENGTH;
        for (CoreMessage event : state.exportState().pendingEvents()) {
            exportLength = Math.addExact(exportLength,
                    Math.addExact(Integer.BYTES, CoreMessageCodec.encode(event).length));
        }
        int snapshotLength = Math.toIntExact(Math.addExact(Math.addExact(Math.addExact(
                Math.addExact((long) FIXED_LENGTH,
                        Math.multiplyExact((long) state.lastSourceSequences().size(), SOURCE_SEQUENCE_LENGTH)),
                Math.multiplyExact((long) state.commandResults().size(), RESULT_LENGTH)),
                exportLength), Math.addExact(tradingState.length, CHECKSUM_LENGTH)));
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
        state.exportState().pendingEvents().forEach(event -> {
            byte[] encoded = CoreMessageCodec.encode(event);
            buffer.putInt(encoded.length);
            buffer.put(encoded);
        });
        buffer.put(tradingState);
        CRC32C checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putLong(checksum.getValue());
        return buffer.array();
    }

    static CoreSnapshotManifest manifest(byte[] snapshot, ProductLine expectedProductLine) {
        CoreProbeState state = decode(snapshot, expectedProductLine);
        ByteBuffer header = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt();
        int version = Short.toUnsignedInt(header.getShort());
        long checksum = version == VERSION
                ? ByteBuffer.wrap(snapshot, snapshot.length - CHECKSUM_LENGTH, CHECKSUM_LENGTH)
                        .order(ByteOrder.LITTLE_ENDIAN).getLong()
                : 0;
        return new CoreSnapshotManifest(expectedProductLine, version, state.appliedCommandCount(),
                state.tradingState().businessStateHash(), state.exportState().status(), checksum);
    }

    static CoreProbeState decode(byte[] snapshot, ProductLine expectedProductLine) {
        if (snapshot == null || snapshot.length < FIXED_LENGTH_V1) {
            throw new ProtocolException("snapshot shorter than fixed header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new ProtocolException("invalid snapshot magic");
        }
        int version = Short.toUnsignedInt(buffer.getShort());
        if (version != VERSION && version != VERSION_2 && version != VERSION_1) {
            throw new ProtocolException("unsupported snapshot version: " + version);
        }
        if (version == VERSION) {
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
        int tradingStateLength = version >= VERSION_2 ? buffer.getInt() : 0;
        int fixedLength = version >= VERSION_2 ? FIXED_LENGTH : FIXED_LENGTH_V1;
        if (resultCount < 0 || sourceSequenceCount < 0
                || sourceSequenceCount > CoreProbeState.MAX_SOURCE_SEQUENCES
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
        if (version == VERSION) {
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
            if (buffer.remaining() != tradingStateLength + CHECKSUM_LENGTH) {
                throw new ProtocolException("invalid snapshot manifest length");
            }
        } else if (buffer.remaining() != tradingStateLength) {
            throw new ProtocolException("invalid legacy snapshot length");
        }
        TradingCoreState tradingState;
        if (tradingStateLength == 0) {
            tradingState = TradingCoreState.empty(productLine);
        } else {
            byte[] encodedTradingState = new byte[tradingStateLength];
            buffer.get(encodedTradingState);
            tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        }
        if (version == VERSION) {
            buffer.getLong();
        }
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue,
                results, lastSourceSequences, tradingState, exportState);
    }
}
