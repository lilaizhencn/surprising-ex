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
        byte[] encoded = asset.trim().toUpperCase().getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 2 || encoded.length > 20 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("invalid ADL candidate query");
        }
        return ByteBuffer.allocate(Integer.BYTES * 2 + encoded.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(encoded.length).put(encoded).putInt(limit).array();
    }

    public static Query decodeQuery(byte[] payload) {
        ByteBuffer input = input(payload);
        String asset = string(input);
        if (input.remaining() != Integer.BYTES) throw new ProtocolException("invalid ADL query length");
        int limit = input.getInt();
        if (limit < 1 || limit > 1000) throw new ProtocolException("invalid ADL query limit");
        return new Query(asset, limit);
    }

    public static byte[] encodeCandidates(List<CoreAdlCandidateView> candidates) {
        int length = Integer.BYTES;
        for (var value : candidates) {
            length = Math.addExact(length, Integer.BYTES * 5 + Long.BYTES * 11
                    + bytes(value.symbol()).length + bytes(value.asset()).length);
        }
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(candidates.size());
        candidates.forEach(value -> {
            output.putLong(value.userId());
            putString(output, value.symbol());
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
            String symbol = string(input);
            String asset = string(input);
            if (input.remaining() < Integer.BYTES * 2 + Long.BYTES * 10) {
                throw new ProtocolException("ADL candidate is truncated");
            }
            result.add(new CoreAdlCandidateView(userId, symbol, asset,
                    CoreMarginMode.fromWireCode(input.getInt()), CorePositionSide.fromWireCode(input.getInt()),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong(),
                    input.getLong(), input.getLong(), input.getLong(), input.getLong(), input.getLong()));
        }
        if (input.hasRemaining()) throw new ProtocolException("ADL candidates have trailing bytes");
        return List.copyOf(result);
    }

    private static void putString(ByteBuffer output, String value) {
        byte[] encoded = bytes(value);
        output.putInt(encoded.length).put(encoded);
    }

    private static String string(ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("ADL text is truncated");
        int length = input.getInt();
        if (length <= 0 || length > 64 || input.remaining() < length) throw new ProtocolException("invalid ADL text");
        byte[] encoded = new byte[length];
        input.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ByteBuffer input(byte[] payload) {
        if (payload == null) throw new ProtocolException("ADL payload is required");
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public record Query(String asset, int limit) {
    }
}
