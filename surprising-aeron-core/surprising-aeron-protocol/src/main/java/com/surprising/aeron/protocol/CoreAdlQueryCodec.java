package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CoreAdlQueryCodec {

    private CoreAdlQueryCodec() {
    }

    public static byte[] encodeQuery(String asset, int limit) {
        String normalized = asset.trim().toUpperCase();
        int byteLength = CoreStateQueryCodec.utf8Length(normalized);
        if (byteLength < 2 || byteLength > 20 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("invalid ADL candidate query");
        }
        ByteBuffer output = ByteBuffer.allocate(Integer.BYTES * 2 + byteLength)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(byteLength);
        CoreStateQueryCodec.writeUtf8Bytes(output, normalized);
        return output.putInt(limit).array();
    }

    public static Query decodeQuery(byte[] payload) {
        ByteBuffer input = input(payload);
        String asset = string(payload, input);
        if (input.remaining() != Integer.BYTES) throw new ProtocolException("invalid ADL query length");
        int limit = input.getInt();
        if (limit < 1 || limit > 1000) throw new ProtocolException("invalid ADL query limit");
        return new Query(asset, limit);
    }

    public static byte[] encodeCandidates(List<CoreAdlCandidateView> candidates) {
        int length = Integer.BYTES;
        for (var value : candidates) {
            length = Math.addExact(length, Integer.BYTES * 4 + Long.BYTES * 11
                    + CoreStateQueryCodec.utf8Length(value.instrumentId())
                    + CoreStateQueryCodec.utf8Length(value.asset()));
        }
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(candidates.size());
        candidates.forEach(value -> {
            output.putLong(value.userId());
            putString(output, value.instrumentId());
            putString(output, value.asset());
            output.putInt(value.marginMode().wireCode()).putInt(value.positionSide().wireCode())
                    .putLong(value.signedQuantitySteps()).putLong(value.entryPriceTicks())
                    .putLong(value.markPriceTicks()).putLong(value.markPriceSequence())
                    .putLong(value.notionalUnits()).putLong(value.unrealizedProfitUnits())
                    .putLong(value.marginUnits()).putLong(value.profitRatePpm())
                    .putLong(value.effectiveLeveragePpm()).putLong(value.priorityScorePpm());
        });
        return output.array();
    }

    public static List<CoreAdlCandidateView> decodeCandidates(byte[] payload) {
        ByteBuffer input = input(payload);
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("ADL candidates are truncated");
        int count = input.getInt();
        if (count < 0 || count > 1000) throw new ProtocolException("invalid ADL candidate count");
        List<CoreAdlCandidateView> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Long.BYTES) throw new ProtocolException("ADL candidate is truncated");
            long userId = input.getLong();
            String instrumentId = string(payload, input);
            String asset = string(payload, input);
            if (input.remaining() < Integer.BYTES * 2 + Long.BYTES * 10) {
                throw new ProtocolException("ADL candidate is truncated");
            }
            result.add(new CoreAdlCandidateView(userId, instrumentId, asset,
                    CoreMarginMode.fromWireCode(input.getInt()), CorePositionSide.fromWireCode(input.getInt()),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong()));
        }
        if (input.hasRemaining()) throw new ProtocolException("ADL candidates have trailing bytes");
        return List.copyOf(result);
    }

    private static void putString(ByteBuffer output, String value) {
        output.putInt(CoreStateQueryCodec.utf8Length(value));
        CoreStateQueryCodec.writeUtf8Bytes(output, value);
    }

    private static String string(byte[] payload, ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("ADL text is truncated");
        int length = input.getInt();
        if (length <= 0 || length > 64 || input.remaining() < length) throw new ProtocolException("invalid ADL text");
        String value = new String(payload, input.position(), length, StandardCharsets.UTF_8);
        input.position(input.position() + length);
        return value;
    }

    private static ByteBuffer input(byte[] payload) {
        if (payload == null) throw new ProtocolException("ADL payload is required");
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public record Query(String asset, int limit) {
    }
}
