package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreRiskQueryCodec {
    private CoreRiskQueryCodec() {}

    public static byte[] encode(List<CoreRiskSnapshotView> values) {
        int length = Integer.BYTES;
        for (var value : values) length = Math.addExact(length,
                Long.BYTES * 6 + Integer.BYTES * 3 + bytes(value.symbol()).length + bytes(value.status()).length);
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(values.size());
        values.forEach(value -> {
            output.putLong(value.userId());
            put(output, value.symbol());
            output.putInt(value.positionSide().wireCode()).putLong(value.priceSequence())
                    .putLong(value.equityUnits()).putLong(value.unrealizedPnlUnits())
                    .putLong(value.maintenanceMarginUnits()).putLong(value.marginRatioPpm());
            put(output, value.status());
        });
        return output.array();
    }

    public static List<CoreRiskSnapshotView> decode(byte[] payload) {
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("risk state is truncated");
        int count = input.getInt();
        if (count < 0 || count > 10000) throw new ProtocolException("invalid risk state count");
        List<CoreRiskSnapshotView> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Long.BYTES) throw new ProtocolException("risk state is truncated");
            long userId = input.getLong();
            String symbol = text(input);
            if (input.remaining() < Integer.BYTES + Long.BYTES * 5) throw new ProtocolException("risk state is truncated");
            values.add(new CoreRiskSnapshotView(userId, symbol, CorePositionSide.fromWireCode(input.getInt()),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(), text(input)));
        }
        if (input.hasRemaining()) throw new ProtocolException("risk state has trailing bytes");
        return List.copyOf(values);
    }

    private static void put(ByteBuffer output, String value) { byte[] bytes = bytes(value); output.putInt(bytes.length).put(bytes); }
    private static String text(ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("risk text is truncated");
        int length = input.getInt();
        if (length < 1 || length > 64 || input.remaining() < length) throw new ProtocolException("invalid risk text");
        byte[] value = new byte[length]; input.get(value); return new String(value, StandardCharsets.UTF_8);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
