package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class TradingCommandCodec {

    private static final int MAX_TEXT_BYTES = 64;

    private TradingCommandCodec() {
    }

    public static byte[] encodeBalanceAdjustment(BalanceAdjustmentCommand command) {
        byte[] asset = text(command.asset());
        return ByteBuffer.allocate(Short.BYTES + asset.length + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) asset.length)
                .put(asset)
                .putLong(command.deltaUnits())
                .array();
    }

    public static BalanceAdjustmentCommand decodeBalanceAdjustment(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String asset = readText(buffer);
        requireRemaining(buffer, Long.BYTES);
        long delta = buffer.getLong();
        requireConsumed(buffer);
        return new BalanceAdjustmentCommand(asset, delta);
    }

    public static byte[] encodePlaceOrder(PlaceOrderCommand command) {
        byte[] symbol = text(command.symbol());
        byte[] baseAsset = text(command.baseAsset());
        byte[] quoteAsset = text(command.quoteAsset());
        byte[] settleAsset = text(command.settleAsset());
        byte[] asset = text(command.reservationAsset());
        return ByteBuffer.allocate(Long.BYTES * 2 + Short.BYTES + symbol.length
                        + Short.BYTES + baseAsset.length + Short.BYTES + quoteAsset.length
                        + Short.BYTES + settleAsset.length + Integer.BYTES
                        + Long.BYTES * 3 + Byte.BYTES + Integer.BYTES + Short.BYTES + asset.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.orderId())
                .putLong(command.instrumentVersion())
                .putShort((short) symbol.length)
                .put(symbol)
                .putShort((short) baseAsset.length)
                .put(baseAsset)
                .putShort((short) quoteAsset.length)
                .put(quoteAsset)
                .putShort((short) settleAsset.length)
                .put(settleAsset)
                .putInt(command.side().wireCode())
                .putLong(command.priceTicks())
                .putLong(command.quantitySteps())
                .put((byte) (command.reduceOnly() ? 1 : 0))
                .putInt(command.reservationKind().wireCode())
                .putShort((short) asset.length)
                .put(asset)
                .putLong(command.reservedUnits())
                .array();
    }

    public static PlaceOrderCommand decodePlaceOrder(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 2);
        long orderId = buffer.getLong();
        long instrumentVersion = buffer.getLong();
        String symbol = readText(buffer);
        String baseAsset = readText(buffer);
        String quoteAsset = readText(buffer);
        String settleAsset = readText(buffer);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES * 2 + Byte.BYTES + Integer.BYTES);
        CoreOrderSide side = CoreOrderSide.fromWireCode(buffer.getInt());
        long priceTicks = buffer.getLong();
        long quantitySteps = buffer.getLong();
        byte reduceOnlyCode = buffer.get();
        if (reduceOnlyCode != 0 && reduceOnlyCode != 1) {
            throw new ProtocolException("invalid reduceOnly flag: " + reduceOnlyCode);
        }
        ReservationKind reservationKind = ReservationKind.fromWireCode(buffer.getInt());
        String asset = readText(buffer);
        requireRemaining(buffer, Long.BYTES);
        long reservedUnits = buffer.getLong();
        requireConsumed(buffer);
        return new PlaceOrderCommand(orderId, symbol, instrumentVersion, baseAsset, quoteAsset, settleAsset,
                side, priceTicks, quantitySteps, reduceOnlyCode == 1, reservationKind, asset, reservedUnits);
    }

    public static byte[] encodeCancelOrder(CancelOrderCommand command) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(command.orderId()).array();
    }

    public static CancelOrderCommand decodeCancelOrder(byte[] payload) {
        if (payload == null || payload.length != Long.BYTES) {
            throw new ProtocolException("cancel order payload must be 8 bytes");
        }
        return new CancelOrderCommand(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong());
    }

    public static byte[] encodeOrderStateQuery(long orderId) {
        return encodeCancelOrder(new CancelOrderCommand(orderId));
    }

    public static long decodeOrderStateQuery(byte[] payload) {
        return decodeCancelOrder(payload).orderId();
    }

    private static ByteBuffer readable(byte[] payload) {
        if (payload == null) {
            throw new ProtocolException("payload is required");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] text(String value) {
        if (value == null) {
            throw new IllegalArgumentException("text is required");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text length must be 1.." + MAX_TEXT_BYTES + " bytes");
        }
        return encoded;
    }

    private static String readText(ByteBuffer buffer) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || length > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid text length: " + length);
        }
        requireRemaining(buffer, length);
        byte[] encoded = new byte[length];
        buffer.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new ProtocolException("truncated trading command payload");
        }
    }

    private static void requireConsumed(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            throw new ProtocolException("trailing bytes in trading command payload");
        }
    }
}
