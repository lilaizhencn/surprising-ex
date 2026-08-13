package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CoreExportCodec {

    private static final int EVENT_V2_MARKER = 0xC0E2_0002;
    private static final int EVENT_V3_MARKER = 0xC0E3_0003;
    private static final int EVENT_FIXED_LENGTH = 64;
    public static final int MAX_COMMAND_PAYLOAD =
            CoreMessageCodec.MAX_PAYLOAD_LENGTH - EVENT_FIXED_LENGTH;
    private static final int MAX_BATCH_EVENTS = 4096;
    public static final int MAX_BATCH_ENCODED_LENGTH =
            CoreMessageCodec.MAX_PAYLOAD_LENGTH - CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH;

    private CoreExportCodec() {
    }

    public static byte[] encodeAck(AckExportCommand command) {
        return littleEndian(Long.BYTES).putLong(command.throughSequence()).array();
    }

    public static AckExportCommand decodeAck(byte[] encoded) {
        return new AckExportCommand(exact(encoded, Long.BYTES).getLong());
    }

    public static byte[] encodeBatchQuery(int maxEvents) {
        if (maxEvents <= 0 || maxEvents > MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("invalid export batch size");
        }
        return littleEndian(Integer.BYTES).putInt(maxEvents).array();
    }

    public static int decodeBatchQuery(byte[] encoded) {
        int maxEvents = exact(encoded, Integer.BYTES).getInt();
        if (maxEvents <= 0 || maxEvents > MAX_BATCH_EVENTS) {
            throw new ProtocolException("invalid export batch size");
        }
        return maxEvents;
    }

    public static byte[] encodeEvent(CoreExportEvent event) {
        byte[] payload = event.commandPayload();
        List<byte[]> users = event.changedUsers().stream().map(CoreStateQueryCodec::encodeUserState).toList();
        List<byte[]> orders = event.changedOrders().stream().map(CoreStateQueryCodec::encodeOrderState).toList();
        long length = Integer.BYTES + EVENT_FIXED_LENGTH + Integer.BYTES * 4L + payload.length;
        for (byte[] user : users) length = Math.addExact(length, Integer.BYTES + user.length);
        for (byte[] order : orders) length = Math.addExact(length, Integer.BYTES + order.length);
        length = Math.addExact(length, Math.multiplyExact(event.executions().size(), Long.BYTES * 6L));
        for (CoreFundingPaymentView payment : event.fundingPayments()) {
            length = Math.addExact(length, fundingPaymentLength(payment));
        }
        if (payload.length > MAX_COMMAND_PAYLOAD || length > CoreMessageCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("export event payload is too large");
        }
        ByteBuffer output = littleEndian(Math.toIntExact(length));
        output.putInt(EVENT_V3_MARKER);
        output.putLong(event.exportSequence());
        output.putLong(event.appliedCommandCount());
        output.putLong(event.businessStateHash());
        output.putLong(event.commandId().getMostSignificantBits());
        output.putLong(event.commandId().getLeastSignificantBits());
        output.putInt(event.commandType().wireCode());
        output.putInt(event.commandStatus().wireCode());
        output.putInt(event.resultCode().wireCode());
        output.putLong(event.userId());
        output.putInt(payload.length);
        output.put(payload);
        putItems(output, users);
        putItems(output, orders);
        output.putInt(event.executions().size());
        event.executions().forEach(execution -> output.putLong(execution.takerOrderId())
                .putLong(execution.makerOrderId()).putLong(execution.takerUserId())
                .putLong(execution.makerUserId()).putLong(execution.priceTicks())
                .putLong(execution.quantitySteps()));
        output.putInt(event.fundingPayments().size());
        event.fundingPayments().forEach(payment -> putFundingPayment(output, payment));
        return output.array();
    }

    public static CoreExportEvent decodeEvent(byte[] encoded) {
        if (encoded == null || encoded.length < EVENT_FIXED_LENGTH) {
            throw new ProtocolException("export event is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        int marker = input.remaining() >= Integer.BYTES ? input.getInt(input.position()) : 0;
        boolean version2 = marker == EVENT_V2_MARKER;
        boolean version3 = marker == EVENT_V3_MARKER;
        if (version2 || version3) input.getInt();
        long sequence = input.getLong();
        long appliedCount = input.getLong();
        long businessHash = input.getLong();
        UUID commandId = new UUID(input.getLong(), input.getLong());
        CoreMessageType commandType = CoreMessageType.fromWireCode(input.getInt());
        ResponseStatus status = ResponseStatus.fromWireCode(input.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(input.getInt());
        long userId = input.getLong();
        int payloadLength = input.getInt();
        if (payloadLength < 0 || payloadLength > MAX_COMMAND_PAYLOAD
                || input.remaining() < payloadLength) {
            throw new ProtocolException("invalid export event payload length");
        }
        byte[] payload = new byte[payloadLength];
        input.get(payload);
        if (!version2 && !version3) {
            if (input.hasRemaining()) throw new ProtocolException("export event has trailing bytes");
            return new CoreExportEvent(sequence, appliedCount, businessHash, commandId,
                    commandType, status, resultCode, userId, payload);
        }
        List<CoreUserStateView> users = readItems(input, CoreStateQueryCodec::decodeUserState);
        List<CoreOrderStateView> orders = readItems(input, CoreStateQueryCodec::decodeOrderState);
        int executionCount = readCount(input);
        int executionBytes = Math.multiplyExact(executionCount, Long.BYTES * 6);
        if (input.remaining() < executionBytes) {
            throw new ProtocolException("invalid execution facts length");
        }
        List<CoreExecutionView> executions = new ArrayList<>(executionCount);
        for (int index = 0; index < executionCount; index++) {
            executions.add(new CoreExecutionView(input.getLong(), input.getLong(), input.getLong(), input.getLong(),
                    input.getLong(), input.getLong()));
        }
        if (version2) {
            if (input.hasRemaining()) throw new ProtocolException("export event has trailing bytes");
            return new CoreExportEvent(sequence, appliedCount, businessHash, commandId,
                    commandType, status, resultCode, userId, payload, users, orders, executions, List.of());
        }
        int fundingCount = readCount(input);
        List<CoreFundingPaymentView> fundingPayments = new ArrayList<>(fundingCount);
        for (int index = 0; index < fundingCount; index++) fundingPayments.add(readFundingPayment(input));
        if (input.hasRemaining()) throw new ProtocolException("export event has trailing bytes");
        return new CoreExportEvent(sequence, appliedCount, businessHash, commandId,
                commandType, status, resultCode, userId, payload, users, orders, executions, fundingPayments);
    }

    private static int fundingPaymentLength(CoreFundingPaymentView payment) {
        return Integer.BYTES * 4 + Long.BYTES * 6
                + utf8(payment.symbol()).length + utf8(payment.asset()).length;
    }

    private static void putFundingPayment(ByteBuffer output, CoreFundingPaymentView payment) {
        output.putLong(payment.settlementId()).putLong(payment.userId());
        putString(output, payment.symbol());
        output.putInt(payment.marginMode().ordinal()).putInt(payment.positionSide().ordinal());
        putString(output, payment.asset());
        output.putLong(payment.signedQuantitySteps()).putLong(payment.notionalUnits())
                .putLong(payment.fundingRatePpm()).putLong(payment.amountUnits());
    }

    private static CoreFundingPaymentView readFundingPayment(ByteBuffer input) {
        if (input.remaining() < Long.BYTES * 6 + Integer.BYTES * 4) {
            throw new ProtocolException("funding payment fact is truncated");
        }
        long settlementId = input.getLong();
        long userId = input.getLong();
        String symbol = readString(input);
        int marginMode = input.getInt();
        int positionSide = input.getInt();
        String asset = readString(input);
        if (input.remaining() < Long.BYTES * 4 || marginMode < 0 || marginMode >= CoreMarginMode.values().length
                || positionSide < 0 || positionSide >= CorePositionSide.values().length) {
            throw new ProtocolException("invalid funding payment fact");
        }
        return new CoreFundingPaymentView(settlementId, userId, symbol, CoreMarginMode.values()[marginMode],
                CorePositionSide.values()[positionSide], asset, input.getLong(), input.getLong(),
                input.getLong(), input.getLong());
    }

    private static void putString(ByteBuffer output, String value) {
        byte[] encoded = utf8(value);
        output.putInt(encoded.length).put(encoded);
    }

    private static String readString(ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("string is truncated");
        int length = input.getInt();
        if (length <= 0 || length > input.remaining()) throw new ProtocolException("invalid string length");
        byte[] encoded = new byte[length];
        input.get(encoded);
        return new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void putItems(ByteBuffer output, List<byte[]> items) {
        output.putInt(items.size());
        items.forEach(item -> output.putInt(item.length).put(item));
    }

    private static <T> List<T> readItems(ByteBuffer input, java.util.function.Function<byte[], T> decoder) {
        int count = readCount(input);
        List<T> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Integer.BYTES) throw new ProtocolException("export fact is truncated");
            int length = input.getInt();
            if (length <= 0 || length > input.remaining()) throw new ProtocolException("invalid export fact length");
            byte[] encoded = new byte[length];
            input.get(encoded);
            values.add(decoder.apply(encoded));
        }
        return List.copyOf(values);
    }

    private static int readCount(ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) throw new ProtocolException("export fact count is truncated");
        int count = input.getInt();
        if (count < 0 || count > 100_000) throw new ProtocolException("invalid export fact count");
        return count;
    }

    public static byte[] encodeBatch(List<CoreMessage> events) {
        if (events == null || events.size() > MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("invalid export event batch");
        }
        List<byte[]> encoded = events.stream().map(CoreMessageCodec::encode).toList();
        long total = Integer.BYTES;
        for (byte[] event : encoded) {
            total = Math.addExact(total, Math.addExact(Integer.BYTES, event.length));
        }
        if (total > MAX_BATCH_ENCODED_LENGTH) {
            throw new IllegalArgumentException("export batch exceeds response payload limit");
        }
        ByteBuffer output = littleEndian(Math.toIntExact(total));
        output.putInt(encoded.size());
        encoded.forEach(event -> output.putInt(event.length).put(event));
        return output.array();
    }

    public static List<CoreMessage> decodeBatch(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BATCH_ENCODED_LENGTH) {
            throw new ProtocolException("invalid export batch length");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < Integer.BYTES) {
            throw new ProtocolException("export batch is truncated");
        }
        int count = input.getInt();
        if (count < 0 || count > MAX_BATCH_EVENTS) {
            throw new ProtocolException("invalid export batch count");
        }
        List<CoreMessage> events = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (input.remaining() < Integer.BYTES) {
                throw new ProtocolException("export batch entry is truncated");
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                throw new ProtocolException("invalid export batch entry length");
            }
            byte[] event = new byte[length];
            input.get(event);
            events.add(CoreMessageCodec.decode(event));
        }
        if (input.hasRemaining()) {
            throw new ProtocolException("export batch has trailing bytes");
        }
        return List.copyOf(events);
    }

    public static byte[] encodeStatus(CoreExportStatus status) {
        return littleEndian(Long.BYTES * 4 + Integer.BYTES * 2)
                .putLong(status.acknowledgedSequence()).putLong(status.nextSequence())
                .putInt(status.pendingCount()).putLong(status.pendingBytes())
                .putInt(status.maxPendingCount()).putLong(status.maxPendingBytes()).array();
    }

    public static CoreExportStatus decodeStatus(byte[] encoded) {
        ByteBuffer input = exact(encoded, Long.BYTES * 4 + Integer.BYTES * 2);
        return new CoreExportStatus(input.getLong(), input.getLong(), input.getInt(), input.getLong(),
                input.getInt(), input.getLong());
    }

    private static ByteBuffer exact(byte[] encoded, int length) {
        if (encoded == null || encoded.length != length) {
            throw new ProtocolException("invalid export payload length");
        }
        return ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer littleEndian(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }
}
