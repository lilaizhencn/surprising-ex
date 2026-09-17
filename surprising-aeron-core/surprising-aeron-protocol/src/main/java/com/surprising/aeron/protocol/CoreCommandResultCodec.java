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
        return encode(result.coreSequence(), result.commandId(), result.orderId(), result.instrumentChangeId(),
                result.matcherSequence(), result.matcherPrefixBefore(), result.matcherPrefixAfter(),
                result.orders(), result.executions());
    }

    public static byte[] encode(long coreSequence, UUID commandId, long orderId, long instrumentChangeId,
                                long matcherSequence, long matcherPrefixBefore, long matcherPrefixAfter,
                                List<? extends CoreOrderStateSource> orders, List<CoreExecutionView> executions) {
        int length = encodedLength(orders, executions);
        byte[] result = new byte[length];
        encodeInto(coreSequence, commandId, orderId, instrumentChangeId, matcherSequence,
                matcherPrefixBefore, matcherPrefixAfter, orders, executions, result, 0);
        return result;
    }

    /** Returns the exact payload length without allocating an output array. */
    public static int encodedLength(List<? extends CoreOrderStateSource> orders,
                                    List<CoreExecutionView> executions) {
        if (orders == null || executions == null) {
            throw new IllegalArgumentException("command result fields are required");
        }
        if (orders.size() > MAX_ITEMS || executions.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("command result is too large");
        }
        int ordersLength = Integer.BYTES * 2;
        for (CoreOrderStateSource order : orders) {
            ordersLength = Math.addExact(ordersLength, CoreStateQueryCodec.encodedOrderStateLength(order));
        }
        int length = Math.addExact(Math.addExact(Integer.BYTES + IDENTITY_LENGTH, Integer.BYTES), ordersLength);
        length = Math.addExact(length, Integer.BYTES);
        return Math.addExact(length, Math.multiplyExact(executions.size(), EXECUTION_LENGTH));
    }

    /** Writes a result into caller-owned storage and returns the number of bytes written. */
    public static int encodeInto(long coreSequence, UUID commandId, long orderId, long instrumentChangeId,
                                 long matcherSequence, long matcherPrefixBefore, long matcherPrefixAfter,
                                 List<? extends CoreOrderStateSource> orders, List<CoreExecutionView> executions,
                                 byte[] destination, int offset) {
        if (commandId == null || destination == null || offset < 0) {
            throw new IllegalArgumentException("command result fields are required");
        }
        int length = encodedLength(orders, executions);
        if (offset > destination.length - length) {
            throw new IllegalArgumentException("command result destination is too small");
        }
        int ordersLength = Integer.BYTES * 2;
        for (CoreOrderStateSource order : orders) {
            ordersLength = Math.addExact(ordersLength, CoreStateQueryCodec.encodedOrderStateLength(order));
        }
        ByteBuffer buffer = ByteBuffer.wrap(destination, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION);
        buffer.putLong(coreSequence);
        buffer.putLong(commandId.getMostSignificantBits());
        buffer.putLong(commandId.getLeastSignificantBits());
        buffer.putLong(orderId);
        buffer.putLong(instrumentChangeId);
        buffer.putLong(matcherSequence);
        buffer.putLong(matcherPrefixBefore);
        buffer.putLong(matcherPrefixAfter);
        buffer.putInt(ordersLength);
        buffer.putInt(1);
        buffer.putInt(orders.size());
        orders.forEach(order -> CoreStateQueryCodec.writeOrderState(buffer, order));
        buffer.putInt(executions.size());
        executions.forEach(execution -> buffer
                .putLong(execution.takerOrderId())
                .putLong(execution.makerOrderId())
                .putLong(execution.takerUserId())
                .putLong(execution.makerUserId())
                .putLong(execution.priceTicks())
                .putLong(execution.quantitySteps()));
        return length;
    }

    /**
     * Hot path for the usual single-order response. It keeps the response
     * format identical to the list overload without allocating a singleton List.
     */
    public static byte[] encodeSingleOrder(long coreSequence, UUID commandId, long orderId,
                                           long instrumentChangeId, long matcherSequence,
                                           long matcherPrefixBefore, long matcherPrefixAfter,
                                           CoreOrderStateSource order) {
        int length = encodedSingleOrderLength(order);
        byte[] result = new byte[length];
        encodeSingleOrderInto(coreSequence, commandId, orderId, instrumentChangeId, matcherSequence,
                matcherPrefixBefore, matcherPrefixAfter, order, result, 0);
        return result;
    }

    public static int encodedSingleOrderLength(CoreOrderStateSource order) {
        if (order == null) throw new IllegalArgumentException("command result order is required");
        int orderStateLength = CoreStateQueryCodec.encodedOrderStateLength(order);
        int ordersLength = Math.addExact(Integer.BYTES * 2, orderStateLength);
        int length = Math.addExact(Math.addExact(Integer.BYTES + IDENTITY_LENGTH, Integer.BYTES), ordersLength);
        return Math.addExact(length, Integer.BYTES);
    }

    public static int encodeSingleOrderInto(long coreSequence, UUID commandId, long orderId,
                                            long instrumentChangeId, long matcherSequence,
                                            long matcherPrefixBefore, long matcherPrefixAfter,
                                            CoreOrderStateSource order, byte[] destination, int offset) {
        if (commandId == null || order == null || destination == null || offset < 0) {
            throw new IllegalArgumentException("command result fields are required");
        }
        return encodeSingleOrderInto(coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), orderId, instrumentChangeId, matcherSequence,
                matcherPrefixBefore, matcherPrefixAfter, order, destination, offset);
    }

    /** Allocation-free identity overload for Lane-owned response encoders. */
    public static int encodeSingleOrderInto(long coreSequence, long commandIdMostSignificantBits,
                                            long commandIdLeastSignificantBits, long orderId,
                                            long instrumentChangeId, long matcherSequence,
                                            long matcherPrefixBefore, long matcherPrefixAfter,
                                            CoreOrderStateSource order, byte[] destination, int offset) {
        if (order == null || destination == null || offset < 0) {
            throw new IllegalArgumentException("command result fields are required");
        }
        int length = encodedSingleOrderLength(order);
        if (offset > destination.length - length) {
            throw new IllegalArgumentException("command result destination is too small");
        }
        int orderStateLength = CoreStateQueryCodec.encodedOrderStateLength(order);
        int ordersLength = Math.addExact(Integer.BYTES * 2, orderStateLength);
        ByteBuffer buffer = ByteBuffer.wrap(destination, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(VERSION);
        buffer.putLong(coreSequence);
        buffer.putLong(commandIdMostSignificantBits);
        buffer.putLong(commandIdLeastSignificantBits);
        buffer.putLong(orderId);
        buffer.putLong(instrumentChangeId);
        buffer.putLong(matcherSequence);
        buffer.putLong(matcherPrefixBefore);
        buffer.putLong(matcherPrefixAfter);
        buffer.putInt(ordersLength);
        buffer.putInt(1);
        buffer.putInt(1);
        CoreStateQueryCodec.writeOrderState(buffer, order);
        buffer.putInt(0);
        return length;
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
        long instrumentChangeId = buffer.getLong();
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
        return new CoreCommandResultView(coreSequence, commandId, orderId, instrumentChangeId, matcherSequence,
                matcherPrefixBefore, matcherPrefixAfter, orderViews, executions);
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new ProtocolException("truncated command result");
        }
    }
}
