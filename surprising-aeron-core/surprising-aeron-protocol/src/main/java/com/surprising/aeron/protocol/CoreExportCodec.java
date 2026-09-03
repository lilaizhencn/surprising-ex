package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CoreExportCodec {

    private static final int EVENT_V11_MARKER = 0xC0E7_000B;
    private static final int BATCH_V3_MARKER = 0xC0B2_0003;
    private static final int EVENT_FIXED_LENGTH = 56;
    public static final int MAX_COMMAND_PAYLOAD =
            CoreMessageCodec.MAX_PAYLOAD_LENGTH - EVENT_FIXED_LENGTH;
    private static final int MAX_BATCH_EVENTS = 4096;
    public static final int MAX_BATCH_ENCODED_LENGTH =
            CoreProtocol.CLUSTER_MAX_MESSAGE_LENGTH - CoreProtocol.HEADER_LENGTH
                    - CoreProtocol.RESPONSE_FIXED_PAYLOAD_LENGTH;
    public static final int BATCH_STATUS_FIXED_LENGTH = Integer.BYTES + Long.BYTES * 4 + Integer.BYTES * 2;

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
        byte[] payload = event.commandPayloadUnsafe();
        ByteBuffer output = littleEndian(encodedEventLength(event));
        output.putInt(EVENT_V11_MARKER);
        output.putLong(event.exportSequence());
        output.putLong(event.appliedCommandCount());
        output.putLong(event.commandId().getMostSignificantBits());
        output.putLong(event.commandId().getLeastSignificantBits());
        output.putInt(event.commandType().wireCode());
        output.putInt(event.commandStatus().wireCode());
        output.putInt(event.resultCode().wireCode());
        output.putLong(event.userId());
        output.putInt(payload.length);
        output.put(payload);
        output.putInt(event.executions().size());
        event.executions().forEach(execution -> output.putLong(execution.takerOrderId())
                .putLong(execution.makerOrderId()).putLong(execution.takerUserId())
                .putLong(execution.makerUserId()).putLong(execution.priceTicks())
                .putLong(execution.quantitySteps()));
        output.putInt(event.fundingPayments().size());
        event.fundingPayments().forEach(payment -> putFundingPayment(output, payment));
        output.putInt(event.routeVersion()).putLong(event.committedCoreSequence())
                .putLong(event.clusterPosition());
        output.putInt(event.fundsPostings().size());
        event.fundsPostings().forEach(posting -> {
            putString(output, posting.asset());
            output.putInt(posting.ownerKind().wireCode()).putLong(posting.ownerId())
                    .putInt(posting.subledger().wireCode()).putLong(posting.units());
        });
        output.put(event.commandFingerprint().bytes());
        putLongIds(output, event.terminalIds().orderIds());
        putLongIds(output, event.terminalIds().liquidationIds());
        putLongIds(output, event.terminalIds().triggerOrderIds());
        output.putLong(event.previousCoreSequence()).putLong(event.coreSequence())
                .putLong(event.previousProjectionSequence()).putLong(event.projectionSequence());
        putOptional(output, event.fundingProgress() == null ? null
                : CoreFundingProgressCodec.encode(event.fundingProgress()));
        putOptional(output, event.settlementProgress() == null ? null
                : CoreSettlementProgressCodec.encode(event.settlementProgress()));
        return output.array();
    }

    public static int encodedEventLength(CoreExportEvent event) {
        if (event == null) throw new IllegalArgumentException("export event is required");
        byte[] payload = event.commandPayloadUnsafe();
        long length = Integer.BYTES + EVENT_FIXED_LENGTH + Integer.BYTES * 2L + payload.length;
        length = Math.addExact(length, Math.multiplyExact(event.executions().size(), Long.BYTES * 6L));
        for (CoreFundingPaymentView payment : event.fundingPayments()) {
            length = Math.addExact(length, fundingPaymentLength(payment));
        }
        length = Math.addExact(length, Long.BYTES * 2L + Integer.BYTES * 2L);
        for (CoreFundsPostingView posting : event.fundsPostings()) {
            length = Math.addExact(length, Integer.BYTES * 3L + Long.BYTES * 2L + utf8Length(posting.asset()));
        }
        length = Math.addExact(length, CommandFingerprint.LENGTH);
        length = Math.addExact(length, Integer.BYTES * 3L + Long.BYTES
                * (event.terminalIds().orderIds().size() + event.terminalIds().liquidationIds().size()
                + event.terminalIds().triggerOrderIds().size()));
        length = Math.addExact(length, Long.BYTES * 4L
                + optionalLength(event.fundingProgress() == null ? null
                : CoreFundingProgressCodec.encode(event.fundingProgress()))
                + optionalLength(event.settlementProgress() == null ? null
                : CoreSettlementProgressCodec.encode(event.settlementProgress())));
        if (payload.length > MAX_COMMAND_PAYLOAD || length > CoreMessageCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("export event payload is too large");
        }
        return Math.toIntExact(length);
    }

    public static CoreExportEvent decodeEvent(byte[] encoded) {
        if (encoded == null || encoded.length < EVENT_FIXED_LENGTH) {
            throw new ProtocolException("export event is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != EVENT_V11_MARKER) {
            throw new ProtocolException("unsupported export event version");
        }
        long sequence = input.getLong();
        long appliedCount = input.getLong();
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
        int fundingCount = readCount(input);
        List<CoreFundingPaymentView> fundingPayments = new ArrayList<>(fundingCount);
        for (int index = 0; index < fundingCount; index++) fundingPayments.add(readFundingPayment(input));
        if (input.remaining() < Long.BYTES * 2 + Integer.BYTES * 2) {
            throw new ProtocolException("core fact continuity metadata is truncated");
        }
        int routeVersion = input.getInt();
        long committedCoreSequence = input.getLong();
        long clusterPosition = input.getLong();
        int postingCount = readCount(input);
        List<CoreFundsPostingView> fundsPostings = new ArrayList<>(postingCount);
        for (int index = 0; index < postingCount; index++) {
            String asset = readString(input);
            if (input.remaining() < Integer.BYTES * 2 + Long.BYTES * 2) {
                throw new ProtocolException("funds posting is truncated");
            }
            fundsPostings.add(new CoreFundsPostingView(asset,
                    CoreFundsPostingView.OwnerKind.fromWireCode(input.getInt()), input.getLong(),
                    CoreFundsPostingView.Subledger.fromWireCode(input.getInt()), input.getLong()));
        }
        if (input.remaining() < CommandFingerprint.LENGTH) {
            throw new ProtocolException("Core Fact fingerprint is truncated");
        }
        byte[] fingerprint = new byte[CommandFingerprint.LENGTH];
        input.get(fingerprint);
        CoreExportEvent.TerminalIds terminalIds = new CoreExportEvent.TerminalIds(
                readLongIds(input), readLongIds(input), readLongIds(input));
        requireRemaining(input, Long.BYTES * 4, "Core Fact sequences are truncated");
        long previousCoreSequence = input.getLong();
        long coreSequence = input.getLong();
        long previousProjectionSequence = input.getLong();
        long projectionSequence = input.getLong();
        byte[] fundingProgressPayload = readOptional(input);
        byte[] settlementProgressPayload = readOptional(input);
        CoreFundingProgressView fundingProgress = fundingProgressPayload == null ? null
                : CoreFundingProgressCodec.decode(fundingProgressPayload);
        CoreSettlementProgressView settlementProgress = settlementProgressPayload == null ? null
                : CoreSettlementProgressCodec.decode(settlementProgressPayload);
        if (input.hasRemaining()) throw new ProtocolException("export event has trailing bytes");
        return new CoreExportEvent(sequence, appliedCount, commandId,
                commandType, status, resultCode, userId, payload, executions, fundingPayments,
                routeVersion, committedCoreSequence,
                clusterPosition, fundsPostings, CommandFingerprint.fromBytes(fingerprint), terminalIds,
                previousCoreSequence, coreSequence, previousProjectionSequence, projectionSequence,
                fundingProgress, settlementProgress);
    }

    public static CoreExportEvent decodeEvent(CoreMessage message, ProductLine expectedProductLine) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(expectedProductLine, "expectedProductLine");
        if (message.header().kind() != WireMessageKind.EXPORT_EVENT
                || message.header().messageType() != CoreMessageType.CORE_EVENT
                || message.header().productLine() != expectedProductLine) {
            throw new ProtocolException("Core export event envelope mismatch");
        }
        CoreExportEvent event = decodeEvent(message.payloadUnsafe());
        if (message.header().sourceSequence() != event.exportSequence()
                || !message.header().commandId().equals(event.commandId())
                || message.header().userId() != event.userId()
                || message.header().route().version() != event.routeVersion()) {
            throw new ProtocolException("Core export event identity mismatch");
        }
        return event;
    }

    private static int fundingPaymentLength(CoreFundingPaymentView payment) {
        return Integer.BYTES * 4 + Long.BYTES * 6
                + utf8Length(payment.symbol()) + utf8Length(payment.asset());
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

    private static void putLongIds(ByteBuffer output, List<Long> ids) {
        output.putInt(ids.size());
        ids.forEach(output::putLong);
    }

    private static List<Long> readLongIds(ByteBuffer input) {
        int count = readCount(input);
        requireRemaining(input, Math.multiplyExact(count, Long.BYTES), "Core Fact ids are truncated");
        ArrayList<Long> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(input.getLong());
        return List.copyOf(ids);
    }

    private static void putOptional(ByteBuffer output, byte[] value) {
        if (value == null) output.putInt(-1);
        else output.putInt(value.length).put(value);
    }

    private static byte[] readOptional(ByteBuffer input) {
        requireRemaining(input, Integer.BYTES, "optional Core Fact value is truncated");
        int length = input.getInt();
        if (length == -1) return null;
        if (length <= 0 || length > input.remaining()) {
            throw new ProtocolException("invalid optional Core Fact value length");
        }
        byte[] value = new byte[length];
        input.get(value);
        return value;
    }

    private static long optionalLength(byte[] value) {
        return Integer.BYTES + (value == null ? 0 : value.length);
    }

    private static void requireRemaining(ByteBuffer input, int length, String message) {
        if (length < 0 || input.remaining() < length) throw new ProtocolException(message);
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

    private static int utf8Length(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x80) length++;
            else if (current < 0x800) length += 2;
            else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (Character.isSurrogate(current)) length++;
            else length += 3;
        }
        return length;
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
        List<byte[]> encoded = new ArrayList<>(events.size());
        for (CoreMessage event : events) encoded.add(CoreMessageCodec.encode(event));
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

    public static byte[] encodeBatchWithStatus(CoreExportStatus status, List<CoreMessage> events) {
        if (status == null) {
            throw new IllegalArgumentException("export status is required");
        }
        byte[] batch = encodeBatch(events);
        int length = Math.addExact(BATCH_STATUS_FIXED_LENGTH, batch.length);
        if (length > MAX_BATCH_ENCODED_LENGTH) {
            throw new IllegalArgumentException("export batch exceeds response payload limit");
        }
        return littleEndian(length).putInt(BATCH_V3_MARKER)
                .putLong(status.acknowledgedSequence())
                .putLong(status.nextSequence())
                .putInt(status.pendingCount())
                .putLong(status.pendingBytes())
                .putInt(status.maxPendingCount())
                .putLong(status.maxPendingBytes())
                .put(batch).array();
    }

    public static CoreExportBatch decodeBatchResponse(byte[] encoded) {
        if (encoded == null || encoded.length < BATCH_STATUS_FIXED_LENGTH + Integer.BYTES) {
            throw new ProtocolException("export batch response is truncated");
        }
        if (encoded.length > MAX_BATCH_ENCODED_LENGTH) {
            throw new ProtocolException("export batch response exceeds payload limit");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != BATCH_V3_MARKER) {
            throw new ProtocolException("unsupported export batch response version");
        }
        long acknowledgedSequence = input.getLong();
        long nextSequence = input.getLong();
        int pendingCount = input.getInt();
        long pendingBytes = input.getLong();
        int maxPendingCount = input.getInt();
        long maxPendingBytes = input.getLong();
        byte[] batch = new byte[input.remaining()];
        input.get(batch);
        try {
            return new CoreExportBatch(new CoreExportStatus(acknowledgedSequence, nextSequence, pendingCount,
                    pendingBytes, maxPendingCount, maxPendingBytes), decodeBatch(batch));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("invalid export batch status");
        }
    }

    public static List<CoreMessage> decodeBatch(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BATCH_ENCODED_LENGTH) {
            throw new ProtocolException("invalid export batch length");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        return decodeBatchPayload(input);
    }

    private static List<CoreMessage> decodeBatchPayload(ByteBuffer input) {
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
