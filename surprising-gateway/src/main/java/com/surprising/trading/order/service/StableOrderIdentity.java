package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.gateway.provider.support.Sha256Digests;
import com.surprising.product.api.ProductLine;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class StableOrderIdentity {

    public static final int IDENTITY_VERSION = 1;
    private static final byte[] PLACE_ORDER_NAMESPACE = "place-order\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT_ORDER_NAMESPACE = "replacement-order\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRIGGER_ORDER_NAMESPACE = "trigger-order\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ALGO_ORDER_NAMESPACE = "algo-order\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMAND_NAMESPACE = "command\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT_COMMAND_NAMESPACE = "replacement-command\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRIGGER_COMMAND_NAMESPACE = "trigger-command\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ALGO_COMMAND_NAMESPACE = "algo-command\u0000".getBytes(StandardCharsets.UTF_8);

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private StableOrderIdentity() {
    }

    public static long orderId(ProductLine productLine, long userId, String clientOrderId) {
        return positiveLong(digest(productLine, userId, PLACE_ORDER_NAMESPACE, clientOrderId));
    }

    public static long replacementOrderId(ProductLine productLine, long userId, String clientOrderId) {
        return positiveLong(digest(productLine, userId, REPLACEMENT_ORDER_NAMESPACE, clientOrderId));
    }

    public static long triggerOrderId(ProductLine productLine, long userId, String clientTriggerOrderId) {
        return positiveLong(digest(productLine, userId, TRIGGER_ORDER_NAMESPACE, clientTriggerOrderId));
    }

    public static long algoOrderId(ProductLine productLine, long userId, String clientAlgoOrderId) {
        return positiveLong(digest(productLine, userId, ALGO_ORDER_NAMESPACE, clientAlgoOrderId));
    }

    public static UUID commandId(ProductLine productLine, long userId, String clientRequestId) {
        return uuid(digest(productLine, userId, COMMAND_NAMESPACE, clientRequestId));
    }

    public static UUID replacementCommandId(ProductLine productLine, long userId, String clientRequestId) {
        return uuid(digest(productLine, userId, REPLACEMENT_COMMAND_NAMESPACE, clientRequestId));
    }

    public static UUID triggerCommandId(ProductLine productLine, long userId, String clientTriggerOrderId) {
        return uuid(digest(productLine, userId, TRIGGER_COMMAND_NAMESPACE, clientTriggerOrderId));
    }

    public static UUID algoCommandId(ProductLine productLine, long userId, String clientAlgoOrderId) {
        return uuid(digest(productLine, userId, ALGO_COMMAND_NAMESPACE, clientAlgoOrderId));
    }

    private static byte[] digest(ProductLine productLine, long userId, byte[] namespace, String clientKey) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        byte[] keyBytes = requireKey(clientKey).getBytes(StandardCharsets.UTF_8);
        byte[] header = ByteBuffer.allocate(Integer.BYTES * 6 + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Integer.BYTES)
                .putInt(IDENTITY_VERSION)
                .putInt(Integer.BYTES)
                .putInt(ProductLineWireCode.encode(productLine))
                .putInt(Long.BYTES)
                .putLong(userId)
                .putInt(namespace.length + keyBytes.length)
                .array();
        MessageDigest md = Sha256Digests.newDigest();
        md.update(header);
        md.update(namespace);
        return md.digest(keyBytes);
    }

    private static long positiveLong(byte[] digest) {
        long value = (long) LONG_LE.get(digest, 0) & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    private static UUID uuid(byte[] digest) {
        return new UUID((long) LONG_LE.get(digest, 0), (long) LONG_LE.get(digest, Long.BYTES));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("client identity key is required");
        }
        return key;
    }
}
