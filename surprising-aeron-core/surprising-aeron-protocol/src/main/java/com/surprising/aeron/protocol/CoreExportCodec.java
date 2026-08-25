package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CoreExportCodec {

    private static final int EVENT_V9_MARKER = 0xC0E7_0009;
    private static final int BATCH_V3_MARKER = 0xC0B2_0003;
    private static final int EVENT_FIXED_LENGTH = 64;
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
        List<byte[]> users = new ArrayList<>(event.changedUsers().size());
        for (CoreUserStateView user : event.changedUsers()) users.add(CoreStateQueryCodec.encodeUserState(user));
        List<byte[]> orders = new ArrayList<>(event.changedOrders().size());
        for (CoreOrderStateView order : event.changedOrders()) orders.add(CoreStateQueryCodec.encodeOrderState(order));
        long length = Integer.BYTES + EVENT_FIXED_LENGTH + Integer.BYTES * 7L + payload.length;
        for (byte[] user : users) length = Math.addExact(length, Integer.BYTES + user.length);
        for (byte[] order : orders) length = Math.addExact(length, Integer.BYTES + order.length);
        length = Math.addExact(length, Math.multiplyExact(event.executions().size(), Long.BYTES * 6L));
        for (CoreFundingPaymentView payment : event.fundingPayments()) {
            length = Math.addExact(length, fundingPaymentLength(payment));
        }
        for (CoreLiquidationView liquidation : event.changedLiquidations()) {
            length = Math.addExact(length, liquidationLength(liquidation));
        }
        for (CoreTreasuryAssetView treasury : event.changedTreasuryAssets()) {
            length = Math.addExact(length, treasuryLength(treasury));
        }
        for (CoreTriggerOrderStateView trigger : event.changedTriggerOrders()) {
            byte[] encoded = CoreTriggerOrderCodec.encodeState(trigger);
            length = Math.addExact(length, Integer.BYTES + encoded.length);
        }
        length = Math.addExact(length, Long.BYTES * 11L + Integer.BYTES * 3L);
        for (CoreFundsPostingView posting : event.fundsPostings()) {
            length = Math.addExact(length, Integer.BYTES * 3L + Long.BYTES * 2L + utf8(posting.asset()).length);
        }
        if (payload.length > MAX_COMMAND_PAYLOAD || length > CoreMessageCodec.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("export event payload is too large");
        }
        ByteBuffer output = littleEndian(Math.toIntExact(length));
        output.putInt(EVENT_V9_MARKER);
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
        output.putInt(event.changedLiquidations().size());
        event.changedLiquidations().forEach(liquidation -> putLiquidation(output, liquidation));
        output.putInt(event.changedTreasuryAssets().size());
        event.changedTreasuryAssets().forEach(treasury -> putTreasury(output, treasury));
        output.putInt(event.changedTriggerOrders().size());
        event.changedTriggerOrders().forEach(trigger -> {
            byte[] encoded = CoreTriggerOrderCodec.encodeState(trigger);
            output.putInt(encoded.length).put(encoded);
        });
        CoreMatcherTransition matcher = event.matcherTransition();
        output.putLong(event.beforeBusinessStateHash()).putLong(event.beforeFundsStateHash())
                .putLong(event.fundsStateHash()).putInt(event.routeVersion())
                .putLong(event.topologyHash()).putLong(event.laneRevisionHash())
                .putLong(event.committedCoreSequence()).putInt(matcher.matcherShardId())
                .putLong(matcher.sequenceBefore())
                .putLong(matcher.sequenceAfter()).putLong(matcher.prefixBefore()).putLong(matcher.prefixAfter())
                .putLong(event.clusterPosition());
        output.putInt(event.fundsPostings().size());
        event.fundsPostings().forEach(posting -> {
            putString(output, posting.asset());
            output.putInt(posting.ownerKind().wireCode()).putLong(posting.ownerId())
                    .putInt(posting.subledger().wireCode()).putLong(posting.units());
        });
        return output.array();
    }

    public static CoreExportEvent decodeEvent(byte[] encoded) {
        if (encoded == null || encoded.length < EVENT_FIXED_LENGTH) {
            throw new ProtocolException("export event is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != EVENT_V9_MARKER) {
            throw new ProtocolException("unsupported export event version");
        }
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
        int fundingCount = readCount(input);
        List<CoreFundingPaymentView> fundingPayments = new ArrayList<>(fundingCount);
        for (int index = 0; index < fundingCount; index++) fundingPayments.add(readFundingPayment(input));
        int liquidationCount = readCount(input);
        List<CoreLiquidationView> liquidations = new ArrayList<>(liquidationCount);
        for (int index = 0; index < liquidationCount; index++) {
            liquidations.add(readLiquidation(input));
        }
        int treasuryCount = readCount(input);
        List<CoreTreasuryAssetView> treasuryAssets = new ArrayList<>(treasuryCount);
        for (int index = 0; index < treasuryCount; index++) treasuryAssets.add(readTreasury(input));
        int triggerCount = readCount(input);
        List<CoreTriggerOrderStateView> triggerOrders = new ArrayList<>(triggerCount);
        for (int index = 0; index < triggerCount; index++) {
            int length = readCount(input);
            if (input.remaining() < length) throw new ProtocolException("invalid trigger state length");
            byte[] triggerPayload = new byte[length]; input.get(triggerPayload);
            triggerOrders.add(CoreTriggerOrderCodec.decodeState(triggerPayload));
        }
        if (input.remaining() < Long.BYTES * 11 + Integer.BYTES * 3) {
            throw new ProtocolException("core fact continuity metadata is truncated");
        }
        long beforeBusinessHash = input.getLong();
        long beforeFundsHash = input.getLong();
        long fundsHash = input.getLong();
        int routeVersion = input.getInt();
        long topologyHash = input.getLong();
        long laneRevisionHash = input.getLong();
        long committedCoreSequence = input.getLong();
        int matcherShardId = input.getInt();
        long matcherSequenceBefore = input.getLong();
        long matcherSequenceAfter = input.getLong();
        long matcherPrefixBefore = input.getLong();
        long matcherPrefixAfter = input.getLong();
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
        if (input.hasRemaining()) throw new ProtocolException("export event has trailing bytes");
        return new CoreExportEvent(sequence, appliedCount, businessHash, commandId,
                commandType, status, resultCode, userId, payload, users, orders, executions, fundingPayments,
                liquidations, treasuryAssets, triggerOrders, beforeBusinessHash, beforeFundsHash, fundsHash,
                routeVersion, topologyHash, laneRevisionHash, committedCoreSequence,
                new CoreMatcherTransition(routeVersion, matcherShardId, matcherSequenceBefore, matcherSequenceAfter,
                        matcherPrefixBefore, matcherPrefixAfter),
                clusterPosition, fundsPostings);
    }

    private static int liquidationLength(CoreLiquidationView liquidation) {
        return Long.BYTES * 10 + Integer.BYTES * 5
                + utf8(liquidation.symbol()).length + utf8(liquidation.asset()).length
                + utf8(liquidation.status()).length;
    }

    private static void putLiquidation(ByteBuffer output, CoreLiquidationView liquidation) {
        output.putLong(liquidation.liquidationId()).putLong(liquidation.userId());
        putString(output, liquidation.symbol());
        putString(output, liquidation.asset());
        output.putInt(liquidation.marginMode().ordinal()).putInt(liquidation.positionSide().ordinal())
                .putLong(liquidation.instrumentVersion())
                .putLong(liquidation.triggerPriceSequence()).putLong(liquidation.closeQuantitySteps())
                .putLong(liquidation.signedQuantitySteps()).putLong(liquidation.deficitUnits())
                .putLong(liquidation.executionPriceTicks()).putLong(liquidation.liquidationFeeRatePpm())
                .putLong(liquidation.liquidationFeeUnits());
        putString(output, liquidation.status());
    }

    private static CoreLiquidationView readLiquidation(ByteBuffer input) {
        if (input.remaining() < Long.BYTES * 7 + Integer.BYTES * 4) {
            throw new ProtocolException("liquidation fact is truncated");
        }
        long liquidationId = input.getLong();
        long userId = input.getLong();
        String symbol = readString(input);
        String asset = readString(input);
        int remainingFixedLength = Integer.BYTES * 2 + Long.BYTES * 8;
        if (input.remaining() < remainingFixedLength) {
            throw new ProtocolException("liquidation fact is truncated");
        }
        int marginMode = input.getInt();
        int positionSide = input.getInt();
        long instrumentVersion = input.getLong();
        long triggerPriceSequence = input.getLong();
        long closeQuantitySteps = input.getLong();
        long signedQuantitySteps = input.getLong();
        long deficitUnits = input.getLong();
        long executionPriceTicks = input.getLong();
        long liquidationFeeRatePpm = input.getLong();
        long liquidationFeeUnits = input.getLong();
        String status = readString(input);
        if (marginMode < 0 || marginMode >= CoreMarginMode.values().length
                || positionSide < 0 || positionSide >= CorePositionSide.values().length) {
            throw new ProtocolException("invalid liquidation position side");
        }
        return new CoreLiquidationView(liquidationId, userId, symbol, asset,
                CoreMarginMode.values()[marginMode], CorePositionSide.values()[positionSide], instrumentVersion,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits,
                executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits, status);
    }

    private static int treasuryLength(CoreTreasuryAssetView treasury) {
        return Integer.BYTES + Long.BYTES * 7 + utf8(treasury.asset()).length;
    }

    private static void putTreasury(ByteBuffer output, CoreTreasuryAssetView treasury) {
        putString(output, treasury.asset());
        output.putLong(treasury.feeBalanceUnits()).putLong(treasury.insuranceBalanceUnits())
                .putLong(treasury.insuranceDeficitUnits()).putLong(treasury.liquidationFeeBalanceUnits())
                .putLong(treasury.fundingResidualBalanceUnits()).putLong(treasury.roundingResidualBalanceUnits())
                .putLong(treasury.clearingPnlBalanceUnits());
    }

    private static CoreTreasuryAssetView readTreasury(ByteBuffer input) {
        String asset = readString(input);
        if (input.remaining() < Long.BYTES * 7) throw new ProtocolException("treasury fact is truncated");
        return new CoreTreasuryAssetView(asset, input.getLong(), input.getLong(), input.getLong(),
                input.getLong(), input.getLong(), input.getLong(), input.getLong());
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
