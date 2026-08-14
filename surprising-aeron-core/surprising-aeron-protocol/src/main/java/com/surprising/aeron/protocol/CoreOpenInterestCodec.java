package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class CoreOpenInterestCodec {

    private CoreOpenInterestCodec() {
    }

    public static byte[] encode(List<CoreOpenInterestView> values) {
        if (values == null) throw new IllegalArgumentException("open interest values are required");
        int size = Integer.BYTES;
        for (CoreOpenInterestView value : values) {
            size = Math.addExact(size, Math.addExact(Integer.BYTES + value.symbol().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    Long.BYTES * 2));
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(values.size());
        for (CoreOpenInterestView value : values) {
            byte[] symbol = value.symbol().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.putInt(symbol.length).put(symbol);
            buffer.putLong(value.longQuantitySteps()).putLong(value.shortQuantitySteps());
        }
        return buffer.array();
    }

    public static List<CoreOpenInterestView> decode(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload == null ? new byte[0] : payload);
            int count = buffer.getInt();
            if (count < 0 || count > 1_000_000) throw new ProtocolException("invalid open interest count");
            List<CoreOpenInterestView> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int length = buffer.getInt();
                if (length < 1 || length > 64 || length > buffer.remaining() - Long.BYTES * 2) {
                    throw new ProtocolException("invalid open interest symbol");
                }
                byte[] symbol = new byte[length];
                buffer.get(symbol);
                values.add(new CoreOpenInterestView(
                        new String(symbol, java.nio.charset.StandardCharsets.UTF_8),
                        buffer.getLong(), buffer.getLong()));
            }
            if (buffer.hasRemaining()) throw new ProtocolException("trailing open interest bytes");
            return List.copyOf(values);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException exception) {
            if (exception instanceof ProtocolException protocolException) throw protocolException;
            throw new ProtocolException("invalid open interest payload: " + exception.getMessage());
        }
    }
}
