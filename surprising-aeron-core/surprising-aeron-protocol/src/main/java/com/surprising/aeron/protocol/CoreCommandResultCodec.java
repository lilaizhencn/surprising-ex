package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.UUID;

public final class CoreCommandResultCodec {

    private static final int VERSION = 3;
    private static final int IDENTITY_LENGTH = Long.BYTES * 8;
    private static final int EXECUTION_LENGTH = Long.BYTES * 6;
    private static final int MAX_ITEMS = 100_000;

    private CoreCommandResultCodec() {
    }

    public static byte[] encode(CoreCommandResultView result) {
        if (result == null) {
            throw new IllegalArgumentException("command result is required");
        }
        byte[] orders = CoreStateQueryCodec.encodeOpenOrders(new CoreOpenOrdersView(result.orders()));
        if (result.orders().size() > MAX_ITEMS || result.executions().size() > MAX_ITEMS) {
            throw new IllegalArgumentException("command result is too large");
        }
        int length = Math.addExact(Math.addExact(Integer.BYTES + IDENTITY_LENGTH, Integer.BYTES), orders.length);
        length = Math.addExact(length, Integer.BYTES);
        length = Math.addExact(length, Math.multiplyExact(result.executions().size(), EXECUTION_LENGTH));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION);
        buffer.putLong(result.coreSequence());
        buffer.putLong(result.commandId().getMostSignificantBits());
        buffer.putLong(result.commandId().getLeastSignificantBits());
        buffer.putLong(result.orderId());
        buffer.putLong(result.instrumentVersion());
        buffer.putLong(result.matcherSequence());
        buffer.putLong(result.matcherPrefixBefore());
        buffer.putLong(result.matcherPrefixAfter());
        buffer.putInt(orders.length);
        buffer.put(orders);
        buffer.putInt(result.executions().size());
        result.executions().forEach(execution -> buffer
                .putLong(execution.takerOrderId())
                .putLong(execution.makerOrderId())
                .putLong(execution.takerUserId())
                .putLong(execution.makerUserId())
                .putLong(execution.priceTicks())
                .putLong(execution.quantitySteps()));
        return buffer.array();
    }

    public static CoreCommandResultView decode(byte[] encoded) {
        if (encoded == null) {
            throw new ProtocolException("command result is required");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buffer, Integer.BYTES);
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new ProtocolException("unsupported Core protocol version: " + version);
        }
        requireRemaining(buffer, IDENTITY_LENGTH + Integer.BYTES);
        long coreSequence = buffer.getLong();
        UUID commandId = new UUID(buffer.getLong(), buffer.getLong());
        long orderId = buffer.getLong();
        long instrumentVersion = buffer.getLong();
        long matcherSequence = buffer.getLong();
        long matcherPrefixBefore = buffer.getLong();
        long matcherPrefixAfter = buffer.getLong();
        int ordersLength = buffer.getInt();
        if (ordersLength < 0 || ordersLength > buffer.remaining() - Integer.BYTES) {
            throw new ProtocolException("invalid command result orders length: " + ordersLength);
        }
        byte[] orders = new byte[ordersLength];
        buffer.get(orders);
        List<CoreOrderStateView> orderViews = CoreStateQueryCodec.decodeOpenOrders(orders).orders();
        requireRemaining(buffer, Integer.BYTES);
        int executionCount = buffer.getInt();
        if (executionCount < 0 || executionCount > MAX_ITEMS
                || (long) executionCount * EXECUTION_LENGTH != buffer.remaining()) {
            throw new ProtocolException("invalid command result execution count: " + executionCount);
        }
        java.util.ArrayList<CoreExecutionView> executions = new java.util.ArrayList<>(executionCount);
        for (int index = 0; index < executionCount; index++) {
            executions.add(new CoreExecutionView(buffer.getLong(), buffer.getLong(), buffer.getLong(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong()));
        }
        return new CoreCommandResultView(coreSequence, commandId, orderId, instrumentVersion, matcherSequence,
                matcherPrefixBefore, matcherPrefixAfter, orderViews, executions);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new ProtocolException("truncated command result");
        }
    }
}
