package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class CoreStateSnapshotCodec {

    private static final int MAGIC = 0x5358534E;
    private static final int VERSION = 1;
    private static final int FIXED_LENGTH = 32;
    private static final int SOURCE_SEQUENCE_LENGTH = 24;
    private static final int RESULT_LENGTH = 40;

    private CoreStateSnapshotCodec() {
    }

    static byte[] encode(CoreProbeState state) {
        ByteBuffer buffer = ByteBuffer.allocate(FIXED_LENGTH
                        + state.lastSourceSequences().size() * SOURCE_SEQUENCE_LENGTH
                        + state.commandResults().size() * RESULT_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.put((byte) ProductLineWireCode.encode(state.productLine()));
        buffer.put((byte) 0);
        buffer.putLong(state.appliedCommandCount());
        buffer.putLong(state.probeValue());
        buffer.putInt(state.commandResults().size());
        buffer.putInt(state.lastSourceSequences().size());
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
            buffer.putInt(0);
            buffer.putLong(result.appliedCommandCount());
            buffer.putLong(result.stateHash());
        });
        return buffer.array();
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
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(buffer.get()));
        buffer.get();
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        long appliedCommandCount = buffer.getLong();
        long probeValue = buffer.getLong();
        int resultCount = buffer.getInt();
        int sourceSequenceCount = buffer.getInt();
        if (resultCount < 0 || sourceSequenceCount < 0
                || FIXED_LENGTH + sourceSequenceCount * SOURCE_SEQUENCE_LENGTH
                        + resultCount * RESULT_LENGTH != snapshot.length) {
            throw new ProtocolException("invalid snapshot result count: " + resultCount);
        }
        Map<CoreProbeState.SourceKey, Long> lastSourceSequences = new LinkedHashMap<>();
        for (int index = 0; index < sourceSequenceCount; index++) {
            CommandSource source = CommandSource.fromWireCode(buffer.getInt());
            buffer.getInt();
            long sourceId = buffer.getLong();
            long sequence = buffer.getLong();
            var sourceKey = new CoreProbeState.SourceKey(source, sourceId);
            if (lastSourceSequences.put(sourceKey, sequence) != null) {
                throw new ProtocolException("duplicate snapshot command source: " + sourceKey);
            }
        }
        Map<UUID, CoreProbeState.StoredResult> results = new LinkedHashMap<>();
        for (int index = 0; index < resultCount; index++) {
            UUID commandId = new UUID(buffer.getLong(), buffer.getLong());
            ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
            buffer.getInt();
            long resultAppliedCount = buffer.getLong();
            long stateHash = buffer.getLong();
            results.put(commandId, new CoreProbeState.StoredResult(status, resultAppliedCount, stateHash));
        }
        return CoreProbeState.restore(productLine, appliedCommandCount, probeValue, results, lastSourceSequences);
    }
}
