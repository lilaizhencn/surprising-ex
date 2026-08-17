package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class TradingOrderBatchCodec {

    public static final int MAX_BATCH_PAYLOAD_BYTES = 1 * 1024 * 1024;
    public static final int MAX_BATCH_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int FRAME_LENGTH_BYTES = Integer.BYTES;
    private static final int RESULT_EXECUTION_LENGTH = Long.BYTES * 6;

    private TradingOrderBatchCodec() {
    }

    public static byte[] encodePlaceOrderBatch(PlaceOrderBatchCommand command) {
        return encodeCommand(command.orders(), TradingCommandCodec::encodePlaceOrder);
    }

    public static byte[] encodeCancelOrderBatch(CancelOrderBatchCommand command) {
        return encodeCommand(command.orders(), TradingCommandCodec::encodeCancelOrder);
    }

    public static byte[] encodeAmendOrderBatch(AmendOrderBatchCommand command) {
        return encodeCommand(command.orders(), TradingCommandCodec::encodeAmendOrder);
    }

    public static byte[] encode(PlaceOrderBatchCommand command) {
        return encodePlaceOrderBatch(command);
    }

    public static byte[] encode(CancelOrderBatchCommand command) {
        return encodeCancelOrderBatch(command);
    }

    public static byte[] encode(AmendOrderBatchCommand command) {
        return encodeAmendOrderBatch(command);
    }

    public static PlaceOrderBatchCommand decodePlaceOrderBatch(byte[] encoded) {
        return new PlaceOrderBatchCommand(decodeCommand(encoded, PlaceOrderBatchCommand.MAX_ORDERS,
                TradingCommandCodec::decodePlaceOrder));
    }

    public static CancelOrderBatchCommand decodeCancelOrderBatch(byte[] encoded) {
        return new CancelOrderBatchCommand(decodeCommand(encoded, CancelOrderBatchCommand.MAX_ORDERS,
                TradingCommandCodec::decodeCancelOrder));
    }

    public static AmendOrderBatchCommand decodeAmendOrderBatch(byte[] encoded) {
        return new AmendOrderBatchCommand(decodeCommand(encoded, AmendOrderBatchCommand.MAX_ORDERS,
                TradingCommandCodec::decodeAmendOrder));
    }

    public static byte[] encodeResult(CoreOrderBatchResult result) {
        if (result == null) {
            throw new IllegalArgumentException("order batch result is required");
        }
        List<byte[]> frames = result.items().stream().map(TradingOrderBatchCodec::encodeResultFrame).toList();
        int length = Integer.BYTES * 2;
        for (byte[] frame : frames) {
            length = Math.addExact(length, Math.addExact(FRAME_LENGTH_BYTES, frame.length));
        }
        if (length > MAX_BATCH_RESPONSE_BYTES) {
            throw new IllegalArgumentException("order batch result is too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PlaceOrderBatchCommand.WIRE_VERSION)
                .putInt(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            buffer.putInt(frame.length).put(frame);
        }
        return buffer.array();
    }

    public static byte[] encodeBatchResult(CoreOrderBatchResult result) {
        return encodeResult(result);
    }

    public static CoreOrderBatchResult decodeResult(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BATCH_RESPONSE_BYTES
                || encoded.length < Integer.BYTES * 2) {
            throw new ProtocolException("invalid order batch result payload");
        }
        ByteBuffer buffer = readable(encoded);
        int version = buffer.getInt();
        if (version != PlaceOrderBatchCommand.WIRE_VERSION) {
            throw new ProtocolException("unsupported order batch result version: " + version);
        }
        int count = readCount(buffer, CoreOrderBatchResult.MAX_ITEMS, "result");
        List<CoreOrderBatchResult.Item> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] frame = readFrame(buffer, "result");
            CoreOrderBatchResult.Item item = decodeResultFrame(frame);
            if (item.index() != index) {
                throw new ProtocolException("order batch result indexes must be contiguous");
            }
            items.add(item);
        }
        requireConsumed(buffer, "order batch result");
        try {
            return new CoreOrderBatchResult(items);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }

    public static CoreOrderBatchResult decodeBatchResult(byte[] encoded) {
        return decodeResult(encoded);
    }

    private static <T> byte[] encodeCommand(List<T> commands, Function<T, byte[]> encoder) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("order batch must not be empty");
        }
        List<byte[]> items = commands.stream().map(command -> {
            if (command == null) throw new IllegalArgumentException("order batch item is required");
            byte[] encoded = encoder.apply(command);
            if (encoded.length == 0) throw new IllegalArgumentException("order batch item is empty");
            return encoded;
        }).toList();
        int length = Integer.BYTES * 2;
        for (byte[] item : items) {
            length = Math.addExact(length, Integer.BYTES * 2 + item.length);
        }
        if (length > MAX_BATCH_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("order batch payload is too large");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PlaceOrderBatchCommand.WIRE_VERSION)
                .putInt(items.size());
        for (int index = 0; index < items.size(); index++) {
            buffer.putInt(index).putInt(items.get(index).length).put(items.get(index));
        }
        return buffer.array();
    }

    private static <T> List<T> decodeCommand(byte[] encoded, int maxItems, Function<byte[], T> decoder) {
        if (encoded == null || encoded.length > MAX_BATCH_PAYLOAD_BYTES
                || encoded.length < Integer.BYTES * 2) {
            throw new ProtocolException("invalid order batch payload");
        }
        ByteBuffer buffer = readable(encoded);
        int version = buffer.getInt();
        if (version != PlaceOrderBatchCommand.WIRE_VERSION) {
            throw new ProtocolException("unsupported order batch version: " + version);
        }
        int count = readCount(buffer, maxItems, "command");
        List<T> items = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int itemIndex = readInt(buffer, "command item index");
            if (itemIndex != index) {
                throw new ProtocolException("order batch indexes must be contiguous");
            }
            byte[] item = readFrame(buffer, "command item");
            try {
                items.add(decoder.apply(item));
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException("invalid order batch item: " + exception.getMessage());
            }
        }
        requireConsumed(buffer, "order batch");
        return items;
    }

    private static byte[] encodeResultFrame(CoreOrderBatchResult.Item item) {
        byte[] order = item.order() == null ? new byte[0]
                : CoreStateQueryCodec.encodeOpenOrders(new CoreOpenOrdersView(List.of(item.order())));
        int length = Integer.BYTES + Long.BYTES * 3 + Integer.BYTES * 3 + order.length
                + Math.addExact(Integer.BYTES, Math.multiplyExact(item.executions().size(), RESULT_EXECUTION_LENGTH));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(item.index())
                .putLong(item.orderId())
                .putLong(item.originalOrderId())
                .putLong(item.replacementOrderId())
                .putInt(item.status().wireCode())
                .putInt(item.resultCode().wireCode())
                .putInt(order.length)
                .put(order)
                .putInt(item.executions().size());
        for (CoreExecutionView execution : item.executions()) {
            buffer.putLong(execution.takerOrderId()).putLong(execution.makerOrderId())
                    .putLong(execution.takerUserId()).putLong(execution.makerUserId())
                    .putLong(execution.priceTicks()).putLong(execution.quantitySteps());
        }
        return buffer.array();
    }

    private static CoreOrderBatchResult.Item decodeResultFrame(byte[] encoded) {
        ByteBuffer buffer = readable(encoded);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES * 3 + Integer.BYTES * 3,
                "result item");
        int index = buffer.getInt();
        long orderId = buffer.getLong();
        long originalOrderId = buffer.getLong();
        long replacementOrderId = buffer.getLong();
        ResponseStatus status = ResponseStatus.fromWireCode(buffer.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(buffer.getInt());
        int orderLength = readLength(buffer, "result order");
        if (orderLength > buffer.remaining() - Integer.BYTES) {
            throw new ProtocolException("invalid result order length: " + orderLength);
        }
        byte[] orderBytes = new byte[orderLength];
        buffer.get(orderBytes);
        CoreOrderStateView order = null;
        if (orderLength > 0) {
            List<CoreOrderStateView> orders = CoreStateQueryCodec.decodeOpenOrders(orderBytes).orders();
            if (orders.size() != 1) throw new ProtocolException("result item must contain one order");
            order = orders.getFirst();
        }
        requireRemaining(buffer, Integer.BYTES, "result executions");
        int executionCount = buffer.getInt();
        if (executionCount < 0 || executionCount > 100_000
                || (long) executionCount * RESULT_EXECUTION_LENGTH != buffer.remaining()) {
            throw new ProtocolException("invalid result execution count: " + executionCount);
        }
        List<CoreExecutionView> executions = new ArrayList<>(executionCount);
        for (int item = 0; item < executionCount; item++) {
            executions.add(new CoreExecutionView(buffer.getLong(), buffer.getLong(), buffer.getLong(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong()));
        }
        requireConsumed(buffer, "result item");
        try {
            return new CoreOrderBatchResult.Item(index, orderId, originalOrderId, replacementOrderId,
                    status, resultCode, order, executions);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }

    private static ByteBuffer readable(byte[] encoded) {
        return ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int readCount(ByteBuffer buffer, int maximum, String kind) {
        int count = readInt(buffer, kind + " count");
        if (count <= 0 || count > maximum) {
            throw new ProtocolException("invalid order batch " + kind + " count: " + count);
        }
        return count;
    }

    private static byte[] readFrame(ByteBuffer buffer, String kind) {
        int length = readLength(buffer, kind + " length");
        if (length == 0 || length > buffer.remaining()) {
            throw new ProtocolException("invalid " + kind + " length: " + length);
        }
        byte[] frame = new byte[length];
        buffer.get(frame);
        return frame;
    }

    private static int readLength(ByteBuffer buffer, String kind) {
        int length = readInt(buffer, kind);
        if (length < 0) throw new ProtocolException("negative " + kind + ": " + length);
        return length;
    }

    private static int readInt(ByteBuffer buffer, String kind) {
        requireRemaining(buffer, Integer.BYTES, kind);
        return buffer.getInt();
    }

    private static void requireRemaining(ByteBuffer buffer, int length, String kind) {
        if (length < 0 || buffer.remaining() < length) {
            throw new ProtocolException("truncated " + kind);
        }
    }

    private static void requireConsumed(ByteBuffer buffer, String kind) {
        if (buffer.hasRemaining()) throw new ProtocolException("trailing " + kind + " bytes");
    }
}
