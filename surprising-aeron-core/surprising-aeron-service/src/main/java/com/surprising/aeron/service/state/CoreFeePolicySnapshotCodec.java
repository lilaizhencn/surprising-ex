package com.surprising.aeron.service.state;

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
        int length = Integer.BYTES * 2;
        for (CoreFeePolicyState policy : policies.values()) {
            length = Math.addExact(length, Math.addExact(FIXED_LENGTH,
                    policy.symbol().getBytes(StandardCharsets.UTF_8).length));
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(VERSION).putInt(policies.size());
        new TreeMap<>(policies).values().forEach(policy -> write(buffer, policy));
        return buffer.array();
    }

    public static Map<Long, CoreFeePolicyState> decode(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES * 2) {
            throw new ProtocolException("truncated fee policy snapshot");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != VERSION) throw new ProtocolException("unsupported fee policy snapshot version");
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
                throw new ProtocolException("invalid fee policy snapshot symbol");
            }
            byte[] symbol = new byte[symbolLength];
            buffer.get(symbol);
            CoreFeePolicyState policy;
            try {
                policy = new CoreFeePolicyState(policyId, revision, userId,
                        new String(symbol, StandardCharsets.UTF_8), buffer.getLong(), buffer.getLong(),
                        buffer.getInt(), readBoolean(buffer), buffer.getLong(), buffer.getLong());
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage());
            }
            if (policies.put(policyId, policy) != null) {
                throw new ProtocolException("duplicate fee policy snapshot id");
            }
        }
        if (buffer.hasRemaining()) throw new ProtocolException("fee policy snapshot has trailing bytes");
        return Map.copyOf(policies);
    }

    private static void write(ByteBuffer buffer, CoreFeePolicyState policy) {
        byte[] symbol = policy.symbol().getBytes(StandardCharsets.UTF_8);
        buffer.putLong(policy.policyId()).putLong(policy.policyRevision()).putLong(policy.userId())
                .putShort((short) symbol.length).put(symbol)
                .putLong(policy.makerFeeRatePpm()).putLong(policy.takerFeeRatePpm())
                .putInt(policy.sourcePriority()).put((byte) (policy.active() ? 1 : 0))
                .putLong(policy.effectiveFromEpochMillis()).putLong(policy.expireAtEpochMillis());
    }

    private static boolean readBoolean(ByteBuffer buffer) {
        byte value = buffer.get();
        if (value != 0 && value != 1) throw new ProtocolException("invalid fee policy snapshot active flag");
        return value == 1;
    }
}
