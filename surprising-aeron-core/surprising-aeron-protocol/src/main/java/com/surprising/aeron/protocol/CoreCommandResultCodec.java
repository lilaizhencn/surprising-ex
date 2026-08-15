package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public final class CoreCommandResultCodec {

    private static final int VERSION = 1;
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
        int length = Math.addExact(Math.addExact(Integer.BYTES, Integer.BYTES), orders.length);
        length = Math.addExact(length, Integer.BYTES);
        length = Math.addExact(length, Math.multiplyExact(result.executions().size(), EXECUTION_LENGTH));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION);
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
        requireRemaining(buffer, Integer.BYTES * 2);
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new ProtocolException("unsupported command result version: " + version);
        }
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
        return new CoreCommandResultView(orderViews, executions);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new ProtocolException("truncated command result");
        }
    }
}
