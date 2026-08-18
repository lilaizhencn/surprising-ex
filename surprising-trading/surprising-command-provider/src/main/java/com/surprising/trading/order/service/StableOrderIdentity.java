package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class StableOrderIdentity {

    public static final int IDENTITY_VERSION = 1;
    private static final String PLACE_ORDER_NAMESPACE = "place-order\u0000";
    private static final String REPLACEMENT_ORDER_NAMESPACE = "replacement-order\u0000";
    private static final String COMMAND_NAMESPACE = "command\u0000";
    private static final String REPLACEMENT_COMMAND_NAMESPACE = "replacement-command\u0000";

    private StableOrderIdentity() {
    }

    public static long orderId(ProductLine productLine, long userId, String clientOrderId) {
        return positiveLong(tuple(productLine, userId, PLACE_ORDER_NAMESPACE + requireKey(clientOrderId)));
    }

    public static long replacementOrderId(ProductLine productLine, long userId, String clientOrderId) {
        return positiveLong(tuple(productLine, userId,
                REPLACEMENT_ORDER_NAMESPACE + requireKey(clientOrderId)));
    }

    public static UUID commandId(ProductLine productLine, long userId, String clientRequestId) {
        return uuid(tuple(productLine, userId, COMMAND_NAMESPACE + requireKey(clientRequestId)));
    }

    public static UUID replacementCommandId(ProductLine productLine, long userId, String clientRequestId) {
        return uuid(tuple(productLine, userId,
                REPLACEMENT_COMMAND_NAMESPACE + requireKey(clientRequestId)));
    }

    private static byte[] tuple(ProductLine productLine, long userId, String clientKey) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        byte[] key = clientKey.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 6 + Long.BYTES + key.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        putLengthPrefixed(buffer, intBytes(IDENTITY_VERSION));
        putLengthPrefixed(buffer, intBytes(ProductLineWireCode.encode(productLine)));
        putLengthPrefixed(buffer, longBytes(userId));
        putLengthPrefixed(buffer, key);
        return buffer.array();
    }

    private static void putLengthPrefixed(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length).put(value);
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static long positiveLong(byte[] tuple) {
        byte[] digest = digest(tuple);
        long value = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN).getLong();
        value &= Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private static UUID uuid(byte[] tuple) {
        byte[] digest = digest(tuple);
        return new UUID(ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN).getLong(),
                ByteBuffer.wrap(digest, Long.BYTES, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).getLong());
    }

    private static byte[] digest(byte[] tuple) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(tuple);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("client identity key is required");
        }
        return key;
    }
}
