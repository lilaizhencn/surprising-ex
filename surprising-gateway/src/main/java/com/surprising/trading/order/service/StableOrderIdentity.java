package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.product.api.ProductLine;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private static final ThreadLocal<DigestScratch> SCRATCH = ThreadLocal.withInitial(DigestScratch::new);

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
        return SCRATCH.get().digest(ProductLineWireCode.encode(productLine), userId, namespace, keyBytes);
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

    private static final class DigestScratch {
        private final MessageDigest md;
        private final byte[] header = new byte[Integer.BYTES * 6 + Long.BYTES];
        private final ByteBuffer headerView = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        DigestScratch() {
            try {
                this.md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("JDK does not provide SHA-256", exception);
            }
            headerView.putInt(0, Integer.BYTES);
            headerView.putInt(4, IDENTITY_VERSION);
            headerView.putInt(8, Integer.BYTES);
            headerView.putInt(16, Long.BYTES);
        }

        byte[] digest(int productWireCode, long userId, byte[] namespace, byte[] keyBytes) {
            headerView.putInt(12, productWireCode);
            headerView.putLong(20, userId);
            headerView.putInt(28, namespace.length + keyBytes.length);
            md.reset();
            md.update(header);
            md.update(namespace);
            return md.digest(keyBytes);
        }
    }
}
