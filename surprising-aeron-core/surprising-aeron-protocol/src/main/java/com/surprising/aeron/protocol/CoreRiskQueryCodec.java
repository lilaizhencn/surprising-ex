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
                Long.BYTES * 13 + Integer.BYTES * 5 + bytes(value.symbol()).length
                        + bytes(value.settleAsset()).length + bytes(value.status()).length);
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(values.size());
        values.forEach(value -> {
            output.putLong(value.userId());
            put(output, value.symbol());
            output.putInt(value.marginMode().wireCode()).putInt(value.positionSide().wireCode())
                    .putLong(value.instrumentVersion()); put(output, value.settleAsset());
            output.putLong(value.signedQuantitySteps()).putLong(value.entryPriceTicks())
                    .putLong(value.markPriceTicks()).putLong(value.notionalUnits())
                    .putLong(value.positionMarginUnits()).putLong(value.priceSequence())
                    .putLong(value.walletBalanceUnits()).putLong(value.equityUnits()).putLong(value.unrealizedPnlUnits())
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
            if (input.remaining() < Integer.BYTES * 2 + Long.BYTES) throw new ProtocolException("risk state is truncated");
            CoreMarginMode marginMode = CoreMarginMode.fromWireCode(input.getInt());
            CorePositionSide positionSide = CorePositionSide.fromWireCode(input.getInt());
            long instrumentVersion = input.getLong();
            String settleAsset = text(input);
            if (input.remaining() < Long.BYTES * 11) throw new ProtocolException("risk state is truncated");
            values.add(new CoreRiskSnapshotView(userId, symbol, marginMode, positionSide, instrumentVersion,
                    settleAsset, input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(), text(input)));
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
