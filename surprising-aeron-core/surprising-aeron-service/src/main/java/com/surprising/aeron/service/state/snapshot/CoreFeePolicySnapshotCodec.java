package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.service.state.model.CoreFeePolicyState;

import com.surprising.aeron.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class CoreFeePolicySnapshotCodec {

    private static final int VERSION = 1;
    private static final int FIXED_LENGTH = Long.BYTES * 7 + Integer.BYTES + Byte.BYTES + Short.BYTES;
    private static final int TAIL_LENGTH = Long.BYTES * 4 + Integer.BYTES + Byte.BYTES;

    private CoreFeePolicySnapshotCodec() {
    }

    public static byte[] encode(Map<Long, CoreFeePolicyState> policies) {
        byte[] fields = new SnapshotFields.Writer().list(1, new TreeMap<>(policies).values(), policy ->
                new SnapshotFields.Writer().number(1, policy.policyId()).number(2, policy.policyRevision())
                        .number(3, policy.userId()).text(4, policy.instrumentId())
                        .number(5, policy.makerFeeRatePpm()).number(6, policy.takerFeeRatePpm())
                        .number(7, policy.sourcePriority()).bool(8, policy.active())
                        .number(9, policy.effectiveFromEpochMillis()).number(10, policy.expireAtEpochMillis()).encode()).encode();
        return ByteBuffer.allocate(4 + fields.length).order(ByteOrder.LITTLE_ENDIAN).putInt(2).put(fields).array();
    }

    private static Map<Long, CoreFeePolicyState> decodeFields(byte[] payload) {
        Map<Long, CoreFeePolicyState> policies = new TreeMap<>();
        var root = new SnapshotFields.Reader(java.util.Arrays.copyOfRange(payload, 4, payload.length));
        try {
            for (var r : root.list(1, SnapshotFields.Reader::new)) {
                var policy = new CoreFeePolicyState(r.number(1), r.number(2), r.number(3), r.text(4),
                        r.number(5), r.number(6), r.integer(7), r.bool(8), r.number(9), r.number(10));
                if (policies.size() >= 1_000_000 || policies.put(policy.policyId(), policy) != null)
                    throw new ProtocolException("invalid/duplicate fee policy snapshot");
            }
            return java.util.Collections.unmodifiableMap(policies);
        } catch (IllegalArgumentException invalid) { throw new ProtocolException("invalid fee policy snapshot", invalid); }
    }

    public static Map<Long, CoreFeePolicyState> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated fee policy snapshot");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int version = buffer.getInt();
        if (version == 2) return decodeFields(payload);
        if (version != VERSION) throw new ProtocolException("unsupported fee policy snapshot version");
        int count = buffer.getInt();
        if (count < 0 || count > 1_000_000) throw new ProtocolException("invalid fee policy snapshot count");
        Map<Long, CoreFeePolicyState> policies = new TreeMap<>();
        for (int index = 0; index < count; index++) {
            if (buffer.remaining() < FIXED_LENGTH) throw new ProtocolException("truncated fee policy snapshot item");
            long policyId = buffer.getLong();
            long revision = buffer.getLong();
            long userId = buffer.getLong();
            int symbolLength = Short.toUnsignedInt(buffer.getShort());
            if (symbolLength > 64 || buffer.remaining() < symbolLength + TAIL_LENGTH) {
                throw new ProtocolException("invalid fee policy snapshot instrumentId");
            }
            byte[] instrumentId = new byte[symbolLength];
            buffer.get(instrumentId);
            CoreFeePolicyState policy;
            try {
                policy = new CoreFeePolicyState(policyId, revision, userId,
                        new String(instrumentId, StandardCharsets.UTF_8), buffer.getLong(), buffer.getLong(),
                        buffer.getInt(), readBoolean(buffer), buffer.getLong(), buffer.getLong());
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
            if (policies.put(policyId, policy) != null) {
                throw new ProtocolException("duplicate fee policy snapshot id");
            }
        }
        if (buffer.hasRemaining()) throw new ProtocolException("fee policy snapshot has trailing bytes");
        return java.util.Collections.unmodifiableMap(policies);
    }

    private static boolean readBoolean(ByteBuffer buffer) {
        byte value = buffer.get();
        if (value != 0 && value != 1) throw new ProtocolException("invalid fee policy snapshot active flag");
        return value == 1;
    }
}
