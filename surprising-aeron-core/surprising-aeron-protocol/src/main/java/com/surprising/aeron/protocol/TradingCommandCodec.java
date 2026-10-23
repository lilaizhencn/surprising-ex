package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TradingCommandCodec {

    private static final int PLACE_ORDER_VERSION = 4;
    private static final int INSTRUMENT_MARKER = 0x49525639;
    private static final int AMEND_ORDER_V1_MARKER = 0x414d5631;
    private static final int TRANSFER_FUNDS_VERSION = 2;

    private static final int MAX_TEXT_BYTES = 64;

    private static final int SYMBOL_CACHE_SIZE = 256;
    private static final int SYMBOL_CACHE_MASK = SYMBOL_CACHE_SIZE - 1;

    private static final class CachedSymbol {
        final byte[] bytes;
        final String value;

        CachedSymbol(byte[] bytes, String value) {
            this.bytes = bytes;
            this.value = value;
        }
    }

    private static final CachedSymbol[] SYMBOL_CACHE = new CachedSymbol[SYMBOL_CACHE_SIZE];

    private static final java.lang.invoke.VarHandle INT_LE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final java.lang.invoke.VarHandle LONG_LE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final java.lang.invoke.VarHandle SHORT_LE =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    static String decodeAsciiSymbol(byte[] bytes, int offset, int length) {
        if (length == 0) {
            return "";
        }
        // Cache the exact wire text for every ID range; canonicalization belongs to admission.
        int hash = 0;
        for (int i = 0; i < length; i++) {
            hash = 31 * hash + bytes[offset + i];
        }
        int index = hash & SYMBOL_CACHE_MASK;
        CachedSymbol cached = SYMBOL_CACHE[index];
        if (cached != null && cached.bytes.length == length) {
            boolean match = true;
            for (int i = 0; i < length; i++) {
                if (cached.bytes[i] != bytes[offset + i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return cached.value;
            }
        }
        byte[] copy = Arrays.copyOfRange(bytes, offset, offset + length);
        String created = new String(copy, StandardCharsets.UTF_8);
        SYMBOL_CACHE[index] = new CachedSymbol(copy, created);
        return created;
    }

    private TradingCommandCodec() {
    }

    public static byte[] encodeBalanceAdjustment(BalanceAdjustmentCommand command) {
        byte[] asset = text(command.asset());
        return ByteBuffer.allocate(Short.BYTES + asset.length + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) asset.length)
                .put(asset)
                .putLong(command.deltaUnits())
                .array();
    }

    public static BalanceAdjustmentCommand decodeBalanceAdjustment(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String asset = readText(buffer);
        requireRemaining(buffer, Long.BYTES);
        long delta = buffer.getLong();
        requireConsumed(buffer);
        return new BalanceAdjustmentCommand(asset, delta);
    }

    public static byte[] encodeTransferFunds(TransferFundsCommand command) {
        byte[] sourceAccount = transferText(command.sourceAccountType(), 32, false);
        byte[] targetAccount = transferText(command.targetAccountType(), 32, false);
        byte[] asset = transferText(command.asset(), 20, false);
        byte[] reference = transferText(command.referenceId(), 128, false);
        byte[] reason = transferText(command.reason(), 256, true);
        return ByteBuffer.allocate(Integer.BYTES * 3 + Long.BYTES * 4 + Short.BYTES * 5
                        + sourceAccount.length + targetAccount.length + asset.length + reference.length + reason.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TRANSFER_FUNDS_VERSION)
                .putLong(command.transferId())
                .putLong(command.sourceUserId()).putLong(command.targetUserId())
                .putInt(ProductLineWireCode.encode(command.sourceProductLine()))
                .putInt(ProductLineWireCode.encode(command.targetProductLine()))
                .putShort((short) sourceAccount.length).put(sourceAccount)
                .putShort((short) targetAccount.length).put(targetAccount)
                .putShort((short) asset.length).put(asset)
                .putLong(command.amountUnits())
                .putShort((short) reference.length).put(reference)
                .putShort((short) reason.length).put(reason)
                .array();
    }

    public static TransferFundsCommand decodeTransferFunds(byte[] payload) {
        return decodeTransferFunds(payload, 0, payload == null ? 0 : payload.length);
    }

    static TransferFundsCommand decodeTransferFunds(byte[] payload, int offset, int length) {
        requireRange(payload, offset, length);
        ByteBuffer buffer = ByteBuffer.wrap(payload, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES + Integer.BYTES * 2);
        int version = buffer.getInt();
        if (version != TRANSFER_FUNDS_VERSION) {
            throw new ProtocolException("unsupported transfer funds version: " + version);
        }
        long transferId = buffer.getLong();
        requireRemaining(buffer, Long.BYTES * 2 + Integer.BYTES * 2);
        long sourceUserId = buffer.getLong();
        long targetUserId = buffer.getLong();
        var source = ProductLineWireCode.decode(buffer.getInt());
        var target = ProductLineWireCode.decode(buffer.getInt());
        String sourceAccount = readTransferText(buffer, 32, false);
        String targetAccount = readTransferText(buffer, 32, false);
        String asset = readTransferText(buffer, 20, false);
        requireRemaining(buffer, Long.BYTES);
        long amount = buffer.getLong();
        String reference = readTransferText(buffer, 128, false);
        String reason = readTransferText(buffer, 256, true);
        requireConsumed(buffer);
        return new TransferFundsCommand(transferId, source, target, sourceAccount, targetAccount,
                asset, amount, reference, reason, sourceUserId, targetUserId);
    }

    public static byte[] encodeCompleteTransfer(CompleteTransferCommand command) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.transferId()).array();
    }

    public static CompleteTransferCommand decodeCompleteTransfer(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES);
        CompleteTransferCommand command = new CompleteTransferCommand(buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeAdjustInsuranceFund(AdjustInsuranceFundCommand command) {
        byte[] asset = text(command.asset());
        return ByteBuffer.allocate(Short.BYTES + asset.length + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) asset.length).put(asset)
                .putLong(command.deltaUnits()).array();
    }

    public static AdjustInsuranceFundCommand decodeAdjustInsuranceFund(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String asset = readText(buffer);
        requireRemaining(buffer, Long.BYTES);
        AdjustInsuranceFundCommand command = new AdjustInsuranceFundCommand(asset, buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeUpdatePositionMode(UpdatePositionModeCommand command) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command.positionMode().wireCode()).array();
    }

    public static UpdatePositionModeCommand decodeUpdatePositionMode(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Integer.BYTES);
        UpdatePositionModeCommand command = new UpdatePositionModeCommand(
                CorePositionMode.fromWireCode(buffer.getInt()));
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeAdjustPositionMargin(AdjustPositionMarginCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        return ByteBuffer.allocate(Short.BYTES + instrumentId.length + Integer.BYTES + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) instrumentId.length).put(instrumentId)
                .putInt(command.positionSide().wireCode())
                .putLong(command.amountUnits()).array();
    }

    public static AdjustPositionMarginCommand decodeAdjustPositionMargin(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES);
        AdjustPositionMarginCommand command = new AdjustPositionMarginCommand(instrumentId,
                CorePositionSide.fromWireCode(buffer.getInt()), buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeUpdateLeverage(UpdateLeverageCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES + instrumentId.length + Integer.BYTES + Long.BYTES
                        + (command.repriceCrossMargin() ? Integer.BYTES : 0))
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) instrumentId.length).put(instrumentId)
                .putInt(command.marginMode().wireCode())
                .putLong(command.leveragePpm());
        // Existing journal payloads retain their original exposure-blocking semantics.
        if (command.repriceCrossMargin()) buffer.putInt(2);
        return buffer.array();
    }

    public static UpdateLeverageCommand decodeUpdateLeverage(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES);
        CoreMarginMode marginMode = CoreMarginMode.fromWireCode(buffer.getInt());
        long leveragePpm = buffer.getLong();
        boolean reprice = buffer.hasRemaining();
        if (reprice) {
            requireRemaining(buffer, Integer.BYTES);
            if (buffer.getInt() != 2) throw new IllegalArgumentException("unsupported leverage command version");
        }
        UpdateLeverageCommand command = new UpdateLeverageCommand(instrumentId, marginMode, leveragePpm, reprice);
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodePlaceOrder(PlaceOrderCommand command) {
        byte[] payload = new byte[encodedPlaceOrderLength(command)];
        writePlaceOrder(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN), command);
        return payload;
    }

    public static int encodedPlaceOrderLength(PlaceOrderCommand command) {
        return Integer.BYTES + Long.BYTES * 3 + Short.BYTES * 2 + Integer.BYTES * 5 + Byte.BYTES * 2
                + commandTextLength(command.instrumentId(), false) + commandTextLength(command.clientOrderId(), true);
    }

    /** Writes into an existing little-endian command buffer; no temporary UTF-8 arrays. */
    public static void writePlaceOrder(ByteBuffer output, PlaceOrderCommand command) {
        if (output.order() != ByteOrder.LITTLE_ENDIAN || output.remaining() < encodedPlaceOrderLength(command))
            throw new IllegalArgumentException("place order output must have capacity and little-endian order");
        output.putInt(PLACE_ORDER_VERSION).putLong(command.orderId());
        putCommandText(output, command.instrumentId(), false);
        output.putInt(command.side().wireCode()).putLong(command.limitPriceTicks()).putLong(command.quantitySteps())
                .put((byte) (command.reduceOnly() ? 1 : 0)).putInt(command.marginMode().wireCode())
                .putInt(command.positionSide().wireCode()).putInt(command.orderType().wireCode())
                .putInt(command.timeInForce().wireCode()).put((byte) (command.postOnly() ? 1 : 0));
        putCommandText(output, command.clientOrderId(), true);
    }

    static int encodedAmendOrderLength(AmendOrderCommand command) {
        return Integer.BYTES * 2 + Long.BYTES * 2
                + (command.hasPriceTicks() ? Long.BYTES : 0)
                + (command.hasQuantitySteps() ? Long.BYTES : 0)
                + (command.timeInForce() == null ? 0 : Integer.BYTES)
                + (command.hasPostOnly() ? Byte.BYTES : 0)
                + (command.newClientOrderId() == null ? 0
                : Short.BYTES + commandTextLength(command.newClientOrderId(), true));
    }

    static void writeAmendOrder(ByteBuffer output, AmendOrderCommand command) {
        int mask = (command.hasPriceTicks() ? 1 : 0)
                | (command.hasQuantitySteps() ? 2 : 0)
                | (command.timeInForce() == null ? 0 : 4)
                | (command.hasPostOnly() ? 8 : 0)
                | (command.newClientOrderId() == null ? 0 : 16);
        output.putInt(AMEND_ORDER_V1_MARKER).putLong(command.originalOrderId())
                .putLong(command.replacementOrderId()).putInt(mask);
        if ((mask & 1) != 0) output.putLong(command.rawPriceTicks());
        if ((mask & 2) != 0) output.putLong(command.rawQuantitySteps());
        if ((mask & 4) != 0) output.putInt(command.timeInForce().wireCode());
        if ((mask & 8) != 0) output.put((byte) (command.isPostOnly() ? 1 : 0));
        if ((mask & 16) != 0) putCommandText(output, command.newClientOrderId(), true);
    }

    private static int commandTextLength(String value, boolean optional) {
        int length = value == null ? 0 : CoreStateQueryCodec.utf8Length(value);
        if (length > MAX_TEXT_BYTES || !optional && length == 0) {
            throw new IllegalArgumentException("invalid command text length");
        }
        return length;
    }

    private static void putCommandText(ByteBuffer output, String value, boolean optional) {
        int start = output.position();
        output.putShort((short) 0);
        if (value != null) CoreStateQueryCodec.writeUtf8Bytes(output, value);
        int length = output.position() - start - Short.BYTES;
        if (length > MAX_TEXT_BYTES || !optional && length == 0)
            throw new IllegalArgumentException("invalid command text length");
        output.putShort(start, (short) length);
    }

    public static PlaceOrderCommand decodePlaceOrder(byte[] payload) {
        return decodePlaceOrder(payload, 0, payload == null ? 0 : payload.length);
    }

    static PlaceOrderCommand decodePlaceOrder(byte[] payload, int start, int length) {
        return decodePlaceOrderInto(payload, start, length, null);
    }

    public static PlaceOrderCommand decodePlaceOrderInto(byte[] payload, int start, int length, PlaceOrderCommand target) {
        requireRange(payload, start, length);
        int limit = start + length;
        requireRange(start, Integer.BYTES + Long.BYTES, limit);
        int offset = start;
        int version = getInt(payload, offset);
        offset += Integer.BYTES;
        if (version != PLACE_ORDER_VERSION) {
            throw new ProtocolException("unsupported Core protocol version: " + version);
        }
        long orderId = getLong(payload, offset);
        offset += Long.BYTES;
        requireRange(offset, Short.BYTES, limit);
        int symbolLength = getUnsignedShort(payload, offset);
        offset += Short.BYTES;
        if (symbolLength == 0 || symbolLength > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid text length: " + symbolLength);
        }
        requireRange(offset, symbolLength, limit);
        String instrumentId = decodeAsciiSymbol(payload, offset, symbolLength);
        offset += symbolLength;
        requireRange(offset, Integer.BYTES + Long.BYTES * 2 + Byte.BYTES + Integer.BYTES * 4
                + Byte.BYTES + Short.BYTES, limit);
        CoreOrderSide side = CoreOrderSide.fromWireCode(getInt(payload, offset));
        offset += Integer.BYTES;
        long limitPriceTicks = getLong(payload, offset);
        offset += Long.BYTES;
        long quantitySteps = getLong(payload, offset);
        offset += Long.BYTES;
        byte reduceOnlyCode = payload[offset++];
        if (reduceOnlyCode != 0 && reduceOnlyCode != 1) {
            throw new ProtocolException("invalid reduceOnly flag: " + reduceOnlyCode);
        }
        CoreMarginMode marginMode = CoreMarginMode.fromWireCode(getInt(payload, offset));
        offset += Integer.BYTES;
        CorePositionSide positionSide = CorePositionSide.fromWireCode(getInt(payload, offset));
        offset += Integer.BYTES;
        CoreOrderType orderType = CoreOrderType.fromWireCode(getInt(payload, offset));
        offset += Integer.BYTES;
        CoreTimeInForce timeInForce = CoreTimeInForce.fromWireCode(getInt(payload, offset));
        offset += Integer.BYTES;
        byte postOnlyCode = payload[offset++];
        if (postOnlyCode != 0 && postOnlyCode != 1) {
            throw new ProtocolException("invalid postOnly flag: " + postOnlyCode);
        }
        int clientLength = getUnsignedShort(payload, offset);
        offset += Short.BYTES;
        if (clientLength > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid optional text length: " + clientLength);
        }
        requireRange(offset, clientLength, limit);
        String clientOrderId = clientLength == 0 ? ""
                : target == null ? new String(payload, offset, clientLength, StandardCharsets.UTF_8)
                : target.decodeClientOrderId(payload, offset, clientLength);
        offset += clientLength;
        if (offset != limit) throw new ProtocolException("trailing bytes in trading command payload");
        if (target == null) return new PlaceOrderCommand(orderId, instrumentId, side, limitPriceTicks,
                quantitySteps, reduceOnlyCode == 1, marginMode, positionSide,
                orderType, timeInForce, postOnlyCode == 1, clientOrderId);
        target.decodeInto(orderId, instrumentId, side, limitPriceTicks,
                quantitySteps, reduceOnlyCode == 1, marginMode, positionSide,
                orderType, timeInForce, postOnlyCode == 1, clientOrderId);
        return target;
    }

    public static byte[] encodeUpsertFeePolicy(UpsertFeePolicyCommand command) {
        byte[] instrumentId = optionalText(command.instrumentId());
        return ByteBuffer.allocate(Long.BYTES * 7 + Integer.BYTES + Byte.BYTES + Short.BYTES + instrumentId.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.policyId())
                .putLong(command.policyRevision())
                .putLong(command.userId())
                .putShort((short) instrumentId.length)
                .put(instrumentId)
                .putLong(command.makerFeeRatePpm())
                .putLong(command.takerFeeRatePpm())
                .putInt(command.sourcePriority())
                .put((byte) (command.active() ? 1 : 0))
                .putLong(command.effectiveFromEpochMillis())
                .putLong(command.expireAtEpochMillis())
                .array();
    }

    public static UpsertFeePolicyCommand decodeUpsertFeePolicy(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 3 + Short.BYTES);
        long policyId = buffer.getLong();
        long policyRevision = buffer.getLong();
        long userId = buffer.getLong();
        String instrumentId = readOptionalText(buffer);
        requireRemaining(buffer, Long.BYTES * 4 + Integer.BYTES + Byte.BYTES);
        long makerFeeRatePpm = buffer.getLong();
        long takerFeeRatePpm = buffer.getLong();
        int sourcePriority = buffer.getInt();
        byte activeCode = buffer.get();
        if (activeCode != 0 && activeCode != 1) {
            throw new ProtocolException("invalid fee policy active flag: " + activeCode);
        }
        long effectiveFromEpochMillis = buffer.getLong();
        long expireAtEpochMillis = buffer.getLong();
        requireConsumed(buffer);
        try {
            return new UpsertFeePolicyCommand(policyId, policyRevision, userId, instrumentId,
                    makerFeeRatePpm, takerFeeRatePpm, sourcePriority, activeCode == 1,
                    effectiveFromEpochMillis, expireAtEpochMillis);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }

    public static byte[] encodeCancelOrder(CancelOrderCommand command) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(command.orderId()).array();
    }

    public static CancelOrderCommand decodeCancelOrder(byte[] payload) {
        return decodeCancelOrder(payload, 0, payload == null ? 0 : payload.length);
    }

    static CancelOrderCommand decodeCancelOrder(byte[] payload, int offset, int length) {
        return decodeCancelOrderInto(payload, offset, length, null);
    }

    public static CancelOrderCommand decodeCancelOrderInto(byte[] payload, int offset, int length,
            CancelOrderCommand target) {
        requireRange(payload, offset, length);
        if (length != Long.BYTES) {
            throw new ProtocolException("cancel order payload must be 8 bytes");
        }
        long orderId = getLong(payload, offset);
        if (target == null) return new CancelOrderCommand(orderId);
        target.decodeInto(orderId);
        return target;
    }

    public static byte[] encodeReplaceOrder(ReplaceOrderCommand command) {
        byte[] replacement = encodePlaceOrder(command.replacement());
        return ByteBuffer.allocate(Long.BYTES + Integer.BYTES + replacement.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.originalOrderId())
                .putInt(replacement.length)
                .put(replacement)
                .array();
    }

    public static ReplaceOrderCommand decodeReplaceOrder(byte[] payload) {
        return decodeReplaceOrderInto(payload, 0, payload == null ? 0 : payload.length, null);
    }

    public static ReplaceOrderCommand decodeReplaceOrderInto(byte[] payload, int offset, int length,
            ReplaceOrderCommand target) {
        requireRange(payload, offset, length);
        if (length < Long.BYTES + Integer.BYTES) throw new ProtocolException("truncated replacement payload");
        long originalOrderId = getLong(payload, offset);
        int replacementLength = getInt(payload, offset + Long.BYTES);
        if (replacementLength <= 0 || replacementLength != length - Long.BYTES - Integer.BYTES)
            throw new ProtocolException("invalid replacement payload length");
        PlaceOrderCommand replacement = decodePlaceOrderInto(payload, offset + Long.BYTES + Integer.BYTES,
                replacementLength, target == null ? null : target.replacement());
        if (target == null) return new ReplaceOrderCommand(originalOrderId, replacement);
        target.decodedOriginalOrderId(originalOrderId);
        return target;
    }

    public static byte[] encodeAmendOrder(AmendOrderCommand command) {
        byte[] clientOrderId = optionalText(command.newClientOrderId());
        int mask = 0;
        if (command.hasPriceTicks()) mask |= 1;
        if (command.hasQuantitySteps()) mask |= 1 << 1;
        if (command.timeInForce() != null) mask |= 1 << 2;
        if (command.hasPostOnly()) mask |= 1 << 3;
        if (command.newClientOrderId() != null) mask |= 1 << 4;
        int length = Integer.BYTES + Long.BYTES * 2 + Integer.BYTES;
        if ((mask & 1) != 0) length += Long.BYTES;
        if ((mask & (1 << 1)) != 0) length += Long.BYTES;
        if ((mask & (1 << 2)) != 0) length += Integer.BYTES;
        if ((mask & (1 << 3)) != 0) length += Byte.BYTES;
        if ((mask & (1 << 4)) != 0) length += Short.BYTES + clientOrderId.length;
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(AMEND_ORDER_V1_MARKER)
                .putLong(command.originalOrderId())
                .putLong(command.replacementOrderId())
                .putInt(mask);
        if ((mask & 1) != 0) buffer.putLong(command.rawPriceTicks());
        if ((mask & (1 << 1)) != 0) buffer.putLong(command.rawQuantitySteps());
        if ((mask & (1 << 2)) != 0) buffer.putInt(command.timeInForce().wireCode());
        if ((mask & (1 << 3)) != 0) buffer.put((byte) (command.isPostOnly() ? 1 : 0));
        if ((mask & (1 << 4)) != 0) buffer.putShort((short) clientOrderId.length).put(clientOrderId);
        return buffer.array();
    }

    public static AmendOrderCommand decodeAmendOrder(byte[] payload) {
        return decodeAmendOrder(payload, 0, payload == null ? 0 : payload.length);
    }

    static AmendOrderCommand decodeAmendOrder(byte[] payload, int offset, int length) {
        return decodeAmendOrderInto(payload, offset, length, null);
    }

    public static AmendOrderCommand decodeAmendOrderInto(byte[] payload, int offset, int length,
            AmendOrderCommand target) {
        requireRange(payload, offset, length);
        int limit = offset + length;
        requireRange(offset, Integer.BYTES * 2 + Long.BYTES * 2, limit);
        if (getInt(payload, offset) != AMEND_ORDER_V1_MARKER)
            throw new ProtocolException("invalid amend order marker");
        offset += Integer.BYTES;
        long originalOrderId = getLong(payload, offset);
        offset += Long.BYTES;
        long replacementOrderId = getLong(payload, offset);
        offset += Long.BYTES;
        int mask = getInt(payload, offset);
        offset += Integer.BYTES;
        if (mask <= 0 || (mask & ~0x1f) != 0)
            throw new ProtocolException("invalid amend order field mask");
        long priceTicks = 0L;
        if ((mask & 1) != 0) {
            requireRange(offset, Long.BYTES, limit);
            priceTicks = getLong(payload, offset);
            offset += Long.BYTES;
        }
        long quantitySteps = 0L;
        if ((mask & (1 << 1)) != 0) {
            requireRange(offset, Long.BYTES, limit);
            quantitySteps = getLong(payload, offset);
            offset += Long.BYTES;
        }
        CoreTimeInForce timeInForce = null;
        if ((mask & (1 << 2)) != 0) {
            requireRange(offset, Integer.BYTES, limit);
            timeInForce = CoreTimeInForce.fromWireCode(getInt(payload, offset));
            offset += Integer.BYTES;
        }
        byte postOnly = AmendOrderCommand.UNCHANGED_POST_ONLY;
        if ((mask & (1 << 3)) != 0) {
            requireRange(offset, Byte.BYTES, limit);
            postOnly = payload[offset++];
            if (postOnly != 0 && postOnly != 1) throw new ProtocolException("invalid postOnly flag");
        }
        String clientOrderId = null;
        if ((mask & (1 << 4)) != 0) {
            requireRange(offset, Short.BYTES, limit);
            int textLength = getUnsignedShort(payload, offset);
            offset += Short.BYTES;
            if (textLength > MAX_TEXT_BYTES) throw new ProtocolException("invalid optional text length");
            requireRange(offset, textLength, limit);
            clientOrderId = decodeAsciiSymbol(payload, offset, textLength);
            offset += textLength;
        }
        if (offset != limit) throw new ProtocolException("trailing bytes in trading command payload");
        if (target == null) return new AmendOrderCommand(originalOrderId, replacementOrderId, clientOrderId,
                priceTicks, quantitySteps, timeInForce, postOnly);
        target.decodeInto(originalOrderId, replacementOrderId, clientOrderId,
                priceTicks, quantitySteps, timeInForce, postOnly);
        return target;
    }

    public static byte[] encodeRegisterInstrument(RegisterInstrumentCommand command,
            com.surprising.product.api.ProductLine productLine) {
        java.util.Objects.requireNonNull(productLine, "productLine");
        boolean spot = !productLine.isDerivative();
        boolean option = productLine.isOptionProduct();
        boolean inverse = productLine == com.surprising.product.api.ProductLine.INVERSE_PERPETUAL
                || productLine == com.surprising.product.api.ProductLine.INVERSE_DELIVERY;
        boolean expiring = productLine.isDeliveryProduct();
        if (spot != (command.riskLimits() == null))
            throw new IllegalArgumentException("risk limits are required only for derivative instruments");
        CoreInstrumentRiskLimits risk = spot ? null : command.riskLimits().forProduct(productLine);
        byte[] instrumentId = text(command.instrumentId());
        byte[] base = text(command.baseAsset());
        byte[] quote = text(command.quoteAsset());
        byte[] settle = text(command.settleAsset());
        int riskBytes = spot ? 0 : Long.BYTES * (option ? 4 : 5) + Integer.BYTES
                + risk.riskLimitBrackets().size() * Long.BYTES * 4;
        int productBytes = (inverse ? Long.BYTES : 0) + (expiring ? Long.BYTES : 0)
                + (option ? Integer.BYTES + Long.BYTES : 0) + (spot ? Long.BYTES : 0);
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES * 4 + instrumentId.length + base.length + quote.length
                        + settle.length + Integer.BYTES * 4 + Long.BYTES * 8 + Byte.BYTES * 4 + productBytes + riskBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) instrumentId.length).put(instrumentId)
                .putShort((short) base.length).put(base)
                .putShort((short) quote.length).put(quote)
                .putShort((short) settle.length).put(settle)
                .putLong(command.notionalMultiplierUnits()).putLong(command.priceTickUnits());
        if (inverse) buffer.putLong(command.settleScaleUnits());
        buffer.putLong(command.makerFeeRatePpm()).putLong(command.takerFeeRatePpm());
        if (expiring) buffer.putLong(command.expiryEpochMillis());
        if (option) buffer.putInt(command.optionTypeCode()).putLong(command.strikePriceTicks());
        buffer.putInt(INSTRUMENT_MARKER);
        if (risk != null) {
            buffer.putLong(risk.initialMarginRatePpm());
            if (!option) buffer.putLong(risk.maxLeveragePpm());
            buffer.putLong(risk.maxPositionNotionalUnits()).putLong(risk.userOpenInterestLimitRatePpm())
                    .putLong(risk.userOpenInterestLimitFloorUnits()).putInt(risk.riskLimitBrackets().size());
            for (CoreRiskLimitBracket bracket : risk.riskLimitBrackets()) {
                buffer.putLong(bracket.notionalCapUnits());
                if (!option) buffer.putLong(bracket.maxLeveragePpm());
                buffer.putLong(bracket.initialMarginRatePpm()).putLong(bracket.maintenanceMarginRatePpm());
                if (option) buffer.putLong(bracket.optionMarginFactorPpm());
            }
        }
        buffer.putInt(command.instrumentStatusCode())
                .put((byte) (command.marketOrderEnabled() ? 1 : 0))
                .put((byte) (command.postOnlyEnabled() ? 1 : 0))
                .put((byte) (command.reduceOnlyEnabled() ? 1 : 0))
                .putInt(command.supportedOrderTypeMask()).putInt(command.supportedTimeInForceMask());
        if (spot) buffer.putLong(command.quantityStepUnits());
        buffer.putLong(command.orderProtection().marketMaxSlippagePpm())
                .putLong(command.orderProtection().marketMaxMarkAgeMs())
                .put((byte) (command.orderProtection().limitPriceProtectionEnabled() ? 1 : 0))
                .putLong(command.orderProtection().limitPriceBandPpm())
                .putLong(command.orderProtection().limitPriceMaxMarkAgeMs());
        return buffer.array();
    }


    /** 产品归属由已校验的命令头提供，现货载荷没有衍生品风险段。 */
    public static RegisterInstrumentCommand decodeRegisterInstrument(byte[] payload,
            com.surprising.product.api.ProductLine productLine) {
        if (productLine == null) throw new ProtocolException("instrument product line is required");
        boolean spot = !productLine.isDerivative();
        boolean option = productLine.isOptionProduct();
        boolean inverse = productLine == com.surprising.product.api.ProductLine.INVERSE_PERPETUAL
                || productLine == com.surprising.product.api.ProductLine.INVERSE_DELIVERY;
        boolean expiring = productLine.isDeliveryProduct();
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        String base = readText(buffer);
        String quote = readText(buffer);
        String settle = readText(buffer);
        requireRemaining(buffer, Long.BYTES * (4 + (inverse ? 1 : 0) + (expiring ? 1 : 0) + (option ? 1 : 0))
                + Integer.BYTES * (option ? 2 : 1));
        long multiplier = buffer.getLong();
        long priceTick = buffer.getLong();
        long settleScale = inverse ? buffer.getLong() : 1;
        long makerFee = buffer.getLong();
        long takerFee = buffer.getLong();
        long expiry = expiring ? buffer.getLong() : 0;
        int optionType = option ? buffer.getInt() : -1;
        long strike = option ? buffer.getLong() : 0;
        if (buffer.getInt() != INSTRUMENT_MARKER) throw new ProtocolException("invalid instrument layout marker");
        CoreInstrumentRiskLimits risk = null;
        if (!spot) {
            requireRemaining(buffer, Long.BYTES * (option ? 4 : 5) + Integer.BYTES);
            long initialMargin = buffer.getLong();
            long maxLeverage = option ? 0 : buffer.getLong();
            long maxPosition = buffer.getLong();
            long openInterestRate = buffer.getLong();
            long openInterestFloor = buffer.getLong();
            int bracketCount = buffer.getInt();
            if (bracketCount <= 0 || bracketCount > 128) throw new ProtocolException("invalid risk bracket count");
            requireRemaining(buffer, bracketCount * Long.BYTES * 4);
            List<CoreRiskLimitBracket> brackets = new ArrayList<>(bracketCount);
            for (int index = 0; index < bracketCount; index++) {
                long cap = buffer.getLong();
                long bracketLeverage = option ? 0 : buffer.getLong();
                long initial = buffer.getLong();
                long maintenance = buffer.getLong();
                long factor = option ? buffer.getLong() : 0;
                brackets.add(new CoreRiskLimitBracket(cap, bracketLeverage, initial, maintenance, factor));
            }
            risk = new CoreInstrumentRiskLimits(initialMargin, maxLeverage, maxPosition, openInterestRate,
                    openInterestFloor, brackets).forProduct(productLine);
        }
        requireRemaining(buffer, Integer.BYTES * 3 + Byte.BYTES * 4 + Long.BYTES * (spot ? 5 : 4));
        int statusCode = buffer.getInt();
        boolean marketOrderEnabled = readBoolean(buffer);
        boolean postOnlyEnabled = readBoolean(buffer);
        boolean reduceOnlyEnabled = readBoolean(buffer);
        int supportedOrderTypeMask = buffer.getInt();
        int supportedTimeInForceMask = buffer.getInt();
        long quantityStepUnits = spot ? buffer.getLong() : 1;
        CoreOrderProtection orderProtection = new CoreOrderProtection(buffer.getLong(), buffer.getLong(),
                readBoolean(buffer), buffer.getLong(), buffer.getLong());
        RegisterInstrumentCommand command = new RegisterInstrumentCommand(instrumentId, base, quote, settle,
                multiplier, priceTick, settleScale, risk, makerFee, takerFee, expiry, optionType, strike,
                statusCode, marketOrderEnabled, postOnlyEnabled, reduceOnlyEnabled, supportedOrderTypeMask,
                supportedTimeInForceMask, quantityStepUnits, orderProtection);
        requireConsumed(buffer);
        return command;
    }


    public static byte[] encodeApplyMarkPrice(ApplyMarkPriceCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        return ByteBuffer.allocate(Short.BYTES + instrumentId.length + Long.BYTES * 6)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) instrumentId.length).put(instrumentId)
                .putLong(command.markPriceTicks())
                .putLong(command.indexPriceTicks()).putLong(command.forwardPriceTicks())
                .putLong(command.priceSequence()).putLong(command.generatedAtEpochMillis())
                .putLong(command.lastPriceTicks()).array();
    }

    public static ApplyMarkPriceCommand decodeApplyMarkPrice(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 6);
        long mark = buffer.getLong(), index = buffer.getLong(), forward = buffer.getLong();
        long sequence = buffer.getLong(), generatedAt = buffer.getLong();
        long last = buffer.getLong();
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand(instrumentId, mark, index, forward,
                sequence, generatedAt, last);
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeApplyFunding(ApplyFundingCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        return ByteBuffer.allocate(Short.BYTES + instrumentId.length + Long.BYTES * 3 + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) instrumentId.length).put(instrumentId)
                .putLong(command.settlementId())
                .putLong(command.fundingRatePpm()).putLong(command.cursorUserId())
                .putInt(command.maxUsers()).array();
    }

    public static ApplyFundingCommand decodeApplyFunding(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 2);
        long settlementId = buffer.getLong();
        long fundingRatePpm = buffer.getLong();
        requireRemaining(buffer, Long.BYTES + Integer.BYTES);
        long cursorUserId = buffer.getLong();
        int maxUsers = buffer.getInt();
        ApplyFundingCommand command = new ApplyFundingCommand(settlementId, instrumentId,
                fundingRatePpm, cursorUserId, maxUsers);
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeSettleInstrument(SettleInstrumentCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        return ByteBuffer.allocate(Short.BYTES + instrumentId.length + Long.BYTES * 4 + Integer.BYTES * 2)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) instrumentId.length).put(instrumentId)
                .putLong(command.settlementId())
                .putLong(command.settlementPriceTicks())
                .putLong(command.cursorUserId()).putInt(command.maxUsers())
                .putLong(command.cursorOrderId()).putInt(command.maxOrders()).array();
    }

    public static SettleInstrumentCommand decodeSettleInstrument(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 4 + Integer.BYTES * 2);
        SettleInstrumentCommand command;
        try {
            command = new SettleInstrumentCommand(buffer.getLong(), instrumentId, buffer.getLong(), buffer.getLong(),
                    buffer.getInt(), buffer.getLong(), buffer.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeExecuteLiquidation(ExecuteLiquidationCommand command) {
        return ByteBuffer.allocate(Long.BYTES * 5 + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.liquidationId()).putLong(command.triggerPriceSequence())
                .putLong(command.executionPriceTicks()).putLong(command.liquidationFeeRatePpm())
                .putLong(command.cursorOrderId()).putInt(command.maxOrders()).array();
    }

    public static ExecuteLiquidationCommand decodeExecuteLiquidation(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 5 + Integer.BYTES);
        ExecuteLiquidationCommand command;
        try {
            command = new ExecuteLiquidationCommand(buffer.getLong(), buffer.getLong(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand command) {
        int length = Integer.BYTES * 4 + Long.BYTES + Byte.BYTES;
        List<byte[]> symbols = new ArrayList<>(command.actions().size());
        for (ExecuteLiquidationBatchAction action : command.actions()) {
            byte[] instrumentId = text(action.instrumentId());
            symbols.add(instrumentId);
            length = Math.addExact(length, Long.BYTES * 5 + Short.BYTES + instrumentId.length);
        }
        byte[] continuationSymbol = null;
        if (command.riskScanContinuation() != null) {
            continuationSymbol = text(command.riskScanContinuation().instrumentId());
            length = Math.addExact(length, Short.BYTES + continuationSymbol.length + Long.BYTES * 2);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ExecuteLiquidationBatchCommand.WIRE_VERSION)
                .putInt(command.actions().size());
        for (int index = 0; index < command.actions().size(); index++) {
            ExecuteLiquidationBatchAction action = command.actions().get(index);
            byte[] instrumentId = symbols.get(index);
            buffer.putLong(action.liquidationId()).putLong(action.userId())
                    .putShort((short) instrumentId.length).put(instrumentId)
                    .putLong(action.triggerPriceSequence())
                    .putLong(action.executionPriceTicks()).putLong(action.cursorOrderId());
        }
        buffer.putInt(command.maxCancelOrders()).putLong(command.liquidationFeeRatePpm())
                .put((byte) (command.riskScanContinuation() == null ? 0 : 1));
        if (command.riskScanContinuation() != null) {
            buffer.putShort((short) continuationSymbol.length).put(continuationSymbol)
                    .putLong(command.riskScanContinuation().priceSequence())
                    .putLong(command.riskScanContinuation().lastUserId());
        }
        return buffer.putInt(command.maxRiskScanUsers()).array();
    }

    public static ExecuteLiquidationBatchCommand decodeExecuteLiquidationBatch(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Integer.BYTES * 2);
        int version = buffer.getInt();
        if (version != ExecuteLiquidationBatchCommand.WIRE_VERSION) {
            throw new ProtocolException("unsupported liquidation batch version: " + version);
        }
        int count = buffer.getInt();
        if (count < 0 || count > ExecuteLiquidationBatchCommand.MAX_ACTIONS) {
            throw new ProtocolException("invalid liquidation batch action count");
        }
        List<ExecuteLiquidationBatchAction> actions = new ArrayList<>(count);
        try {
            for (int index = 0; index < count; index++) {
                requireRemaining(buffer, Long.BYTES * 2);
                long liquidationId = buffer.getLong();
                long userId = buffer.getLong();
                String instrumentId = readText(buffer);
                requireRemaining(buffer, Long.BYTES * 3);
                actions.add(new ExecuteLiquidationBatchAction(liquidationId, userId, instrumentId,
                        buffer.getLong(), buffer.getLong(), buffer.getLong()));
            }
            requireRemaining(buffer, Integer.BYTES + Long.BYTES + Byte.BYTES);
            int maxCancelOrders = buffer.getInt();
            long liquidationFeeRatePpm = buffer.getLong();
            boolean hasContinuation = readBoolean(buffer);
            CoreRiskScanContinuation continuation = null;
            if (hasContinuation) {
                String instrumentId = readText(buffer);
                requireRemaining(buffer, Long.BYTES * 2);
                continuation = new CoreRiskScanContinuation(instrumentId, buffer.getLong(), buffer.getLong());
            }
            requireRemaining(buffer, Integer.BYTES);
            int maxRiskScanUsers = buffer.getInt();
            requireConsumed(buffer);
            return new ExecuteLiquidationBatchCommand(actions, maxCancelOrders,
                    liquidationFeeRatePpm, continuation, maxRiskScanUsers);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(exception.getMessage());
        }
    }

    public static byte[] encodeExecuteAdl(ExecuteAdlCommand command) {
        byte[] instrumentId = text(command.instrumentId());
        return ByteBuffer.allocate(Long.BYTES * 7 + Integer.BYTES * 2 + Short.BYTES + instrumentId.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.liquidationId()).putLong(command.targetUserId())
                .putShort((short) instrumentId.length).put(instrumentId)
                .putInt(command.marginMode().wireCode()).putInt(command.positionSide().wireCode())
                .putLong(command.expectedSignedQuantitySteps()).putLong(command.expectedEntryPriceTicks())
                .putLong(command.markPriceSequence()).putLong(command.closeQuantitySteps())
                .putLong(command.coveredUnits()).array();
    }

    public static ExecuteAdlCommand decodeExecuteAdl(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 2);
        long liquidationId = buffer.getLong();
        long targetUserId = buffer.getLong();
        String instrumentId = readText(buffer);
        requireRemaining(buffer, Integer.BYTES * 2 + Long.BYTES * 5);
        ExecuteAdlCommand command = new ExecuteAdlCommand(liquidationId, targetUserId, instrumentId,
                CoreMarginMode.fromWireCode(buffer.getInt()), CorePositionSide.fromWireCode(buffer.getInt()),
                buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeResolveLiquidation(ResolveLiquidationCommand command) {
        return ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.liquidationId()).putInt(command.resolution().ordinal())
                .putLong(command.coveredUnits()).array();
    }

    private static final ResolveLiquidationCommand.Resolution[] LIQUIDATION_RESOLUTIONS =
            ResolveLiquidationCommand.Resolution.values();

    public static ResolveLiquidationCommand decodeResolveLiquidation(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES + Integer.BYTES + Long.BYTES);
        long liquidationId = buffer.getLong();
        int resolutionCode = buffer.getInt();
        if (resolutionCode < 0 || resolutionCode >= LIQUIDATION_RESOLUTIONS.length) {
            throw new ProtocolException("invalid liquidation resolution: " + resolutionCode);
        }
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(liquidationId,
                LIQUIDATION_RESOLUTIONS[resolutionCode], buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeContinueRiskScan(ContinueRiskScanCommand command) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command.maxUsers()).array();
    }

    public static ContinueRiskScanCommand decodeContinueRiskScan(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Integer.BYTES);
        ContinueRiskScanCommand command = new ContinueRiskScanCommand(buffer.getInt());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeOrderStateQuery(long orderId) {
        return encodeCancelOrder(new CancelOrderCommand(orderId));
    }

    public static long decodeOrderStateQuery(byte[] payload) {
        return decodeCancelOrder(payload).orderId();
    }

    private static ByteBuffer readable(byte[] payload) {
        if (payload == null) {
            throw new ProtocolException("payload is required");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] text(String value) {
        if (value == null) {
            throw new IllegalArgumentException("text is required");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text length must be 1.." + MAX_TEXT_BYTES + " bytes");
        }
        return encoded;
    }

    private static byte[] optionalText(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("optional text length must be 0.." + MAX_TEXT_BYTES + " bytes");
        }
        return encoded;
    }

    private static String readText(ByteBuffer buffer) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || length > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid text length: " + length);
        }
        requireRemaining(buffer, length);
        // All command readers wrap the owned heap payload; String retains no reference to it.
        String value = decodeAsciiSymbol(buffer.array(),
                buffer.arrayOffset() + buffer.position(), length);
        buffer.position(buffer.position() + length);
        return value;
    }

    private static String readOptionalText(ByteBuffer buffer) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid optional text length: " + length);
        }
        requireRemaining(buffer, length);
        // All command readers wrap the owned heap payload; String retains no reference to it.
        String value = decodeAsciiSymbol(buffer.array(),
                buffer.arrayOffset() + buffer.position(), length);
        buffer.position(buffer.position() + length);
        return value;
    }

    private static byte[] transferText(String value, int maximumLength, boolean optional) {
        if (value == null) throw new IllegalArgumentException("transfer text is required");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if ((!optional && encoded.length == 0) || encoded.length > maximumLength) {
            throw new IllegalArgumentException("transfer text length is invalid");
        }
        return encoded;
    }

    private static String readTransferText(ByteBuffer buffer, int maximumLength, boolean optional) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if ((!optional && length == 0) || length > maximumLength) {
            throw new ProtocolException("invalid transfer text length: " + length);
        }
        requireRemaining(buffer, length);
        // All command readers wrap the owned heap payload; String retains no reference to it.
        String value = decodeAsciiSymbol(buffer.array(),
                buffer.arrayOffset() + buffer.position(), length);
        buffer.position(buffer.position() + length);
        return value;
    }

    private static Boolean readBoolean(ByteBuffer buffer) {
        requireRemaining(buffer, Byte.BYTES);
        byte value = buffer.get();
        if (value != 0 && value != 1) {
            throw new ProtocolException("invalid boolean value");
        }
        return value == 1;
    }

    private static void requireRemaining(ByteBuffer buffer, int length) {
        if (buffer.remaining() < length) {
            throw new ProtocolException("truncated trading command payload");
        }
    }

    private static void requireConsumed(ByteBuffer buffer) {
        if (buffer.hasRemaining()) {
            throw new ProtocolException("trailing bytes in trading command payload");
        }
    }

    private static void requireRange(byte[] payload, int offset, int length) {
        if (payload == null) throw new ProtocolException("payload is required");
        if (offset < 0 || length < 0 || offset > payload.length - length) {
            throw new ProtocolException("truncated trading command payload");
        }
    }

    private static void requireRange(int offset, int length, int limit) {
        if (length < 0 || offset < 0 || offset > limit - length) {
            throw new ProtocolException("truncated trading command payload");
        }
    }

    private static int getUnsignedShort(byte[] source, int offset) {
        return Short.toUnsignedInt((short) SHORT_LE.get(source, offset));
    }

    private static int getInt(byte[] source, int offset) {
        return (int) INT_LE.get(source, offset);
    }

    private static long getLong(byte[] source, int offset) {
        return (long) LONG_LE.get(source, offset);
    }
}
