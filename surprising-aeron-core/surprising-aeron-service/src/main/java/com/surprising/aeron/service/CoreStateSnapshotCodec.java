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

final class CoreStateSnapshotCodec {

    private static final int MAGIC = 0x5358534E;
    private static final int VERSION = 2;
    private static final int VERSION_1 = 1;
    private static final int FIXED_LENGTH_V1 = 32;
    private static final int FIXED_LENGTH = 36;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    private static final int RESULT_LENGTH = 40;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state) {
        byte[] tradingState = TradingStateSnapshotCodec.encode(state.tradingState());
        int snapshotLength = Math.toIntExact(Math.addExact(Math.addExact(
                Math.addExact((long) FIXED_LENGTH,
                        Math.multiplyExact((long) state.lastSourceSequences().size(), SOURCE_SEQUENCE_LENGTH)),
                Math.multiplyExact((long) state.commandResults().size(), RESULT_LENGTH)), tradingState.length));
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
        buffer.put(tradingState);
        return buffer.array();
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
        if (version != VERSION && version != VERSION_1) {
            throw new ProtocolException("unsupported snapshot version: " + version);
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
        int tradingStateLength = version == VERSION ? buffer.getInt() : 0;
        int fixedLength = version == VERSION ? FIXED_LENGTH : FIXED_LENGTH_V1;
        if (resultCount < 0 || sourceSequenceCount < 0
                || tradingStateLength < 0
                || fixedLength + (long) sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + (long) resultCount * RESULT_LENGTH + tradingStateLength != snapshot.length) {
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
        TradingCoreState tradingState;
        if (tradingStateLength == 0) {
            tradingState = TradingCoreState.empty(productLine);
        } else {
            byte[] encodedTradingState = new byte[tradingStateLength];
            buffer.get(encodedTradingState);
            tradingState = TradingStateSnapshotCodec.decode(encodedTradingState, productLine);
        }
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue,
                results, lastSourceSequences, tradingState);
    }
}
