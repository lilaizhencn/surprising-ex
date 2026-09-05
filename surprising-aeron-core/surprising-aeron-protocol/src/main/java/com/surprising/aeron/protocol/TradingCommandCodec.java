package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TradingCommandCodec {

    private static final int PLACE_ORDER_VERSION = 4;
    private static final int INSTRUMENT_RISK_V2_MARKER = 0x49525632;
    private static final int AMEND_ORDER_V1_MARKER = 0x414d5631;
    private static final int TRANSFER_FUNDS_VERSION = 1;

    private static final int MAX_TEXT_BYTES = 64;

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
        return ByteBuffer.allocate(Integer.BYTES * 3 + Long.BYTES * 2 + Short.BYTES * 5
                        + sourceAccount.length + targetAccount.length + asset.length + reference.length + reason.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TRANSFER_FUNDS_VERSION)
                .putLong(command.transferId())
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
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES + Integer.BYTES * 2);
        int version = buffer.getInt();
        if (version != TRANSFER_FUNDS_VERSION) {
            throw new ProtocolException("unsupported transfer funds version: " + version);
        }
        long transferId = buffer.getLong();
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
                asset, amount, reference, reason);
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
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Integer.BYTES * 2 + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) symbol.length).put(symbol)
                .putInt(command.marginMode().wireCode())
                .putInt(command.positionSide().wireCode())
                .putLong(command.amountUnits()).array();
    }

    public static AdjustPositionMarginCommand decodeAdjustPositionMargin(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Integer.BYTES * 2 + Long.BYTES);
        AdjustPositionMarginCommand command = new AdjustPositionMarginCommand(symbol,
                CoreMarginMode.fromWireCode(buffer.getInt()), CorePositionSide.fromWireCode(buffer.getInt()),
                buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeUpdateLeverage(UpdateLeverageCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Integer.BYTES + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) symbol.length).put(symbol)
                .putInt(command.marginMode().wireCode())
                .putLong(command.leveragePpm()).array();
    }

    public static UpdateLeverageCommand decodeUpdateLeverage(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES);
        UpdateLeverageCommand command = new UpdateLeverageCommand(symbol,
                CoreMarginMode.fromWireCode(buffer.getInt()), buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodePlaceOrder(PlaceOrderCommand command) {
        byte[] symbol = text(command.symbol());
        byte[] clientOrderId = optionalText(command.clientOrderId());
        byte[] payload = new byte[Integer.BYTES + Long.BYTES * 4 + Short.BYTES * 2
                + symbol.length + clientOrderId.length + Integer.BYTES * 5 + Byte.BYTES * 2];
        int offset = 0;
        offset = putInt(payload, offset, PLACE_ORDER_VERSION);
        offset = putLong(payload, offset, command.orderId());
        offset = putLong(payload, offset, command.instrumentVersion());
        offset = putShort(payload, offset, symbol.length);
        System.arraycopy(symbol, 0, payload, offset, symbol.length);
        offset += symbol.length;
        offset = putInt(payload, offset, command.side().wireCode());
        offset = putLong(payload, offset, command.limitPriceTicks());
        offset = putLong(payload, offset, command.quantitySteps());
        payload[offset++] = (byte) (command.reduceOnly() ? 1 : 0);
        offset = putInt(payload, offset, command.marginMode().wireCode());
        offset = putInt(payload, offset, command.positionSide().wireCode());
        offset = putInt(payload, offset, command.orderType().wireCode());
        offset = putInt(payload, offset, command.timeInForce().wireCode());
        payload[offset++] = (byte) (command.postOnly() ? 1 : 0);
        offset = putShort(payload, offset, clientOrderId.length);
        System.arraycopy(clientOrderId, 0, payload, offset, clientOrderId.length);
        return payload;
    }

    static int encodedPlaceOrderLength(PlaceOrderCommand command) {
        return Integer.BYTES + Long.BYTES * 4 + Short.BYTES * 2 + Integer.BYTES * 5 + Byte.BYTES * 2
                + commandTextLength(command.symbol(), false) + commandTextLength(command.clientOrderId(), true);
    }

    static void writePlaceOrder(ByteBuffer output, PlaceOrderCommand command) {
        output.putInt(PLACE_ORDER_VERSION).putLong(command.orderId()).putLong(command.instrumentVersion());
        putCommandText(output, command.symbol(), false);
        output.putInt(command.side().wireCode()).putLong(command.limitPriceTicks()).putLong(command.quantitySteps())
                .put((byte) (command.reduceOnly() ? 1 : 0)).putInt(command.marginMode().wireCode())
                .putInt(command.positionSide().wireCode()).putInt(command.orderType().wireCode())
                .putInt(command.timeInForce().wireCode()).put((byte) (command.postOnly() ? 1 : 0));
        putCommandText(output, command.clientOrderId(), true);
    }

    static int encodedAmendOrderLength(AmendOrderCommand command) {
        return Integer.BYTES * 2 + Long.BYTES * 2
                + (command.priceTicks() == null ? 0 : Long.BYTES)
                + (command.quantitySteps() == null ? 0 : Long.BYTES)
                + (command.timeInForce() == null ? 0 : Integer.BYTES)
                + (command.postOnly() == null ? 0 : Byte.BYTES)
                + (command.newClientOrderId() == null ? 0
                : Short.BYTES + commandTextLength(command.newClientOrderId(), true));
    }

    static void writeAmendOrder(ByteBuffer output, AmendOrderCommand command) {
        int mask = (command.priceTicks() == null ? 0 : 1)
                | (command.quantitySteps() == null ? 0 : 2)
                | (command.timeInForce() == null ? 0 : 4)
                | (command.postOnly() == null ? 0 : 8)
                | (command.newClientOrderId() == null ? 0 : 16);
        output.putInt(AMEND_ORDER_V1_MARKER).putLong(command.originalOrderId())
                .putLong(command.replacementOrderId()).putInt(mask);
        if ((mask & 1) != 0) output.putLong(command.priceTicks());
        if ((mask & 2) != 0) output.putLong(command.quantitySteps());
        if ((mask & 4) != 0) output.putInt(command.timeInForce().wireCode());
        if ((mask & 8) != 0) output.put((byte) (command.postOnly() ? 1 : 0));
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
        byte[] bytes = optional ? optionalText(value) : text(value);
        output.putShort((short) bytes.length).put(bytes);
    }

    public static PlaceOrderCommand decodePlaceOrder(byte[] payload) {
        return decodePlaceOrder(payload, 0, payload == null ? 0 : payload.length);
    }

    static PlaceOrderCommand decodePlaceOrder(byte[] payload, int start, int length) {
        requireRange(payload, start, length);
        int limit = start + length;
        requireRange(payload, start, Integer.BYTES + Long.BYTES * 2, limit);
        int offset = start;
        int version = getInt(payload, offset);
        offset += Integer.BYTES;
        if (version != PLACE_ORDER_VERSION) {
            throw new ProtocolException("unsupported Core protocol version: " + version);
        }
        long orderId = getLong(payload, offset);
        offset += Long.BYTES;
        long instrumentVersion = getLong(payload, offset);
        offset += Long.BYTES;
        requireRange(payload, offset, Short.BYTES, limit);
        int symbolLength = getUnsignedShort(payload, offset);
        offset += Short.BYTES;
        if (symbolLength == 0 || symbolLength > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid text length: " + symbolLength);
        }
        requireRange(payload, offset, symbolLength, limit);
        String symbol = new String(payload, offset, symbolLength, StandardCharsets.UTF_8);
        offset += symbolLength;
        requireRange(payload, offset, Integer.BYTES + Long.BYTES * 2 + Byte.BYTES + Integer.BYTES * 4
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
        requireRange(payload, offset, clientLength, limit);
        String clientOrderId = new String(payload, offset, clientLength, StandardCharsets.UTF_8);
        offset += clientLength;
        if (offset != limit) throw new ProtocolException("trailing bytes in trading command payload");
        return new PlaceOrderCommand(orderId, symbol, instrumentVersion, side, limitPriceTicks,
                quantitySteps, reduceOnlyCode == 1, marginMode, positionSide,
                orderType, timeInForce, postOnlyCode == 1, clientOrderId);
    }

    public static byte[] encodeUpsertFeePolicy(UpsertFeePolicyCommand command) {
        byte[] symbol = optionalText(command.symbol());
        return ByteBuffer.allocate(Long.BYTES * 7 + Integer.BYTES + Byte.BYTES + Short.BYTES + symbol.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.policyId())
                .putLong(command.policyRevision())
                .putLong(command.userId())
                .putShort((short) symbol.length)
                .put(symbol)
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
        String symbol = readOptionalText(buffer);
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
            return new UpsertFeePolicyCommand(policyId, policyRevision, userId, symbol,
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
        requireRange(payload, offset, length);
        if (length != Long.BYTES) {
            throw new ProtocolException("cancel order payload must be 8 bytes");
        }
        return new CancelOrderCommand(getLong(payload, offset));
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
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES + Integer.BYTES);
        long originalOrderId = buffer.getLong();
        int replacementLength = buffer.getInt();
        if (replacementLength <= 0 || replacementLength != buffer.remaining()) {
            throw new ProtocolException("invalid replacement payload length");
        }
        byte[] replacement = new byte[replacementLength];
        buffer.get(replacement);
        requireConsumed(buffer);
        return new ReplaceOrderCommand(originalOrderId, decodePlaceOrder(replacement));
    }

    public static byte[] encodeAmendOrder(AmendOrderCommand command) {
        byte[] clientOrderId = optionalText(command.newClientOrderId());
        int mask = 0;
        if (command.priceTicks() != null) mask |= 1;
        if (command.quantitySteps() != null) mask |= 1 << 1;
        if (command.timeInForce() != null) mask |= 1 << 2;
        if (command.postOnly() != null) mask |= 1 << 3;
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
        if ((mask & 1) != 0) buffer.putLong(command.priceTicks());
        if ((mask & (1 << 1)) != 0) buffer.putLong(command.quantitySteps());
        if ((mask & (1 << 2)) != 0) buffer.putInt(command.timeInForce().wireCode());
        if ((mask & (1 << 3)) != 0) buffer.put((byte) (command.postOnly() ? 1 : 0));
        if ((mask & (1 << 4)) != 0) buffer.putShort((short) clientOrderId.length).put(clientOrderId);
        return buffer.array();
    }

    public static AmendOrderCommand decodeAmendOrder(byte[] payload) {
        return decodeAmendOrder(payload, 0, payload == null ? 0 : payload.length);
    }

    static AmendOrderCommand decodeAmendOrder(byte[] payload, int offset, int length) {
        requireRange(payload, offset, length);
        ByteBuffer buffer = ByteBuffer.wrap(payload, offset, length).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES * 2 + Integer.BYTES);
        if (buffer.getInt() != AMEND_ORDER_V1_MARKER) {
            throw new ProtocolException("invalid amend order marker");
        }
        long originalOrderId = buffer.getLong();
        long replacementOrderId = buffer.getLong();
        int mask = buffer.getInt();
        if (mask <= 0 || (mask & ~0x1f) != 0) {
            throw new ProtocolException("invalid amend order field mask");
        }
        Long priceTicks = null;
        if ((mask & 1) != 0) {
            requireRemaining(buffer, Long.BYTES);
            priceTicks = buffer.getLong();
        }
        Long quantitySteps = null;
        if ((mask & (1 << 1)) != 0) {
            requireRemaining(buffer, Long.BYTES);
            quantitySteps = buffer.getLong();
        }
        CoreTimeInForce timeInForce = (mask & (1 << 2)) == 0 ? null
                : readTimeInForce(buffer);
        Boolean postOnly = (mask & (1 << 3)) == 0 ? null
                : readBoolean(buffer);
        String clientOrderId = (mask & (1 << 4)) == 0 ? null : readOptionalText(buffer);
        requireConsumed(buffer);
        return new AmendOrderCommand(originalOrderId, replacementOrderId, clientOrderId,
                priceTicks, quantitySteps, timeInForce, postOnly);
    }

    public static byte[] encodeUpsertInstrument(UpsertInstrumentCommand command) {
        byte[] symbol = text(command.symbol());
        byte[] base = text(command.baseAsset());
        byte[] quote = text(command.quoteAsset());
        byte[] settle = text(command.settleAsset());
        int bracketBytes = command.riskLimitBrackets().size() * (Integer.BYTES + Long.BYTES * 5);
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES * 4 + symbol.length + base.length + quote.length + settle.length
                        + Integer.BYTES * 4 + Long.BYTES * 14 + bracketBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) symbol.length).put(symbol)
                .putLong(command.instrumentVersion()).putInt(command.contractTypeCode())
                .putShort((short) base.length).put(base)
                .putShort((short) quote.length).put(quote)
                .putShort((short) settle.length).put(settle)
                .putLong(command.notionalMultiplierUnits()).putLong(command.priceTickUnits())
                .putLong(command.settleScaleUnits()).putLong(command.initialMarginRatePpm())
                .putLong(command.maintenanceMarginRatePpm()).putLong(command.makerFeeRatePpm())
                .putLong(command.takerFeeRatePpm()).putLong(command.expiryEpochMillis())
                .putInt(command.optionTypeCode()).putLong(command.strikePriceTicks())
                .putInt(INSTRUMENT_RISK_V2_MARKER)
                .putLong(command.maxLeveragePpm()).putLong(command.maxPositionNotionalUnits())
                .putLong(command.userOpenInterestLimitRatePpm())
                .putLong(command.userOpenInterestLimitFloorUnits())
                .putInt(command.riskLimitBrackets().size());
        for (CoreRiskLimitBracket bracket : command.riskLimitBrackets()) {
            buffer.putInt(bracket.bracketNo()).putLong(bracket.notionalFloorUnits())
                    .putLong(bracket.notionalCapUnits()).putLong(bracket.maxLeveragePpm())
                    .putLong(bracket.initialMarginRatePpm()).putLong(bracket.maintenanceMarginRatePpm());
        }
        return buffer.array();
    }

    public static UpsertInstrumentCommand decodeUpsertInstrument(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES + Integer.BYTES);
        long version = buffer.getLong();
        int contractTypeCode = buffer.getInt();
        String base = readText(buffer);
        String quote = readText(buffer);
        String settle = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 9 + Integer.BYTES);
        long multiplier = buffer.getLong();
        long priceTick = buffer.getLong();
        long settleScale = buffer.getLong();
        long initialMargin = buffer.getLong();
        long maintenanceMargin = buffer.getLong();
        long makerFee = buffer.getLong();
        long takerFee = buffer.getLong();
        long expiry = buffer.getLong();
        int optionType = buffer.getInt();
        long strike = buffer.getLong();
        requireRemaining(buffer, Integer.BYTES + Long.BYTES * 4 + Integer.BYTES);
        if (buffer.getInt() != INSTRUMENT_RISK_V2_MARKER) {
            throw new ProtocolException("invalid instrument risk policy marker");
        }
        long maxLeverage = buffer.getLong();
        long maxPosition = buffer.getLong();
        long openInterestRate = buffer.getLong();
        long openInterestFloor = buffer.getLong();
        int bracketCount = buffer.getInt();
        if (bracketCount <= 0 || bracketCount > 128) {
            throw new ProtocolException("invalid risk bracket count");
        }
        requireRemaining(buffer, bracketCount * (Integer.BYTES + Long.BYTES * 5));
        java.util.List<CoreRiskLimitBracket> brackets = new java.util.ArrayList<>(bracketCount);
        for (int index = 0; index < bracketCount; index++) {
            brackets.add(new CoreRiskLimitBracket(buffer.getInt(), buffer.getLong(), buffer.getLong(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong()));
        }
        UpsertInstrumentCommand command = new UpsertInstrumentCommand(symbol, version, contractTypeCode,
                base, quote, settle, multiplier, priceTick, settleScale, initialMargin, maintenanceMargin,
                makerFee, takerFee, expiry, optionType, strike, maxLeverage, maxPosition, openInterestRate,
                openInterestFloor, brackets);
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeApplyMarkPrice(ApplyMarkPriceCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 4)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.instrumentVersion()).putLong(command.markPriceTicks())
                .putLong(command.priceSequence()).putLong(command.generatedAtEpochMillis()).array();
    }

    public static ApplyMarkPriceCommand decodeApplyMarkPrice(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 4);
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand(symbol, buffer.getLong(), buffer.getLong(),
                buffer.getLong(), buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeApplyFunding(ApplyFundingCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 4 + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.settlementId()).putLong(command.instrumentVersion())
                .putLong(command.fundingRatePpm()).putLong(command.cursorUserId())
                .putInt(command.maxUsers()).array();
    }

    public static ApplyFundingCommand decodeApplyFunding(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 3);
        long settlementId = buffer.getLong();
        long instrumentVersion = buffer.getLong();
        long fundingRatePpm = buffer.getLong();
        requireRemaining(buffer, Long.BYTES + Integer.BYTES);
        long cursorUserId = buffer.getLong();
        int maxUsers = buffer.getInt();
        ApplyFundingCommand command = new ApplyFundingCommand(settlementId, symbol, instrumentVersion,
                fundingRatePpm, cursorUserId, maxUsers);
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeSettleInstrument(SettleInstrumentCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 6 + Integer.BYTES * 2)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.settlementId()).putLong(command.instrumentVersion())
                .putLong(command.settlementPriceTicks()).putLong(command.optionCashUnitsPerContract())
                .putLong(command.cursorUserId()).putInt(command.maxUsers())
                .putLong(command.cursorOrderId()).putInt(command.maxOrders()).array();
    }

    public static SettleInstrumentCommand decodeSettleInstrument(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 6 + Integer.BYTES * 2);
        SettleInstrumentCommand command;
        try {
            command = new SettleInstrumentCommand(buffer.getLong(), symbol, buffer.getLong(),
                    buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getInt(),
                    buffer.getLong(), buffer.getInt());
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
            byte[] symbol = text(action.symbol());
            symbols.add(symbol);
            length = Math.addExact(length, Long.BYTES * 6 + Short.BYTES + symbol.length);
        }
        byte[] continuationSymbol = null;
        if (command.riskScanContinuation() != null) {
            continuationSymbol = text(command.riskScanContinuation().symbol());
            length = Math.addExact(length, Short.BYTES + continuationSymbol.length + Long.BYTES * 2);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ExecuteLiquidationBatchCommand.WIRE_VERSION)
                .putInt(command.actions().size());
        for (int index = 0; index < command.actions().size(); index++) {
            ExecuteLiquidationBatchAction action = command.actions().get(index);
            byte[] symbol = symbols.get(index);
            buffer.putLong(action.liquidationId()).putLong(action.userId())
                    .putShort((short) symbol.length).put(symbol)
                    .putLong(action.instrumentVersion()).putLong(action.triggerPriceSequence())
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
                String symbol = readText(buffer);
                requireRemaining(buffer, Long.BYTES * 4);
                actions.add(new ExecuteLiquidationBatchAction(liquidationId, userId, symbol,
                        buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong()));
            }
            requireRemaining(buffer, Integer.BYTES + Long.BYTES + Byte.BYTES);
            int maxCancelOrders = buffer.getInt();
            long liquidationFeeRatePpm = buffer.getLong();
            boolean hasContinuation = readBoolean(buffer);
            CoreRiskScanContinuation continuation = null;
            if (hasContinuation) {
                String symbol = readText(buffer);
                requireRemaining(buffer, Long.BYTES * 2);
                continuation = new CoreRiskScanContinuation(symbol, buffer.getLong(), buffer.getLong());
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
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Long.BYTES * 7 + Integer.BYTES * 2 + Short.BYTES + symbol.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.liquidationId()).putLong(command.targetUserId())
                .putShort((short) symbol.length).put(symbol)
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
        String symbol = readText(buffer);
        requireRemaining(buffer, Integer.BYTES * 2 + Long.BYTES * 5);
        ExecuteAdlCommand command = new ExecuteAdlCommand(liquidationId, targetUserId, symbol,
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

    public static ResolveLiquidationCommand decodeResolveLiquidation(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES + Integer.BYTES + Long.BYTES);
        long liquidationId = buffer.getLong();
        int resolutionCode = buffer.getInt();
        if (resolutionCode < 0 || resolutionCode >= ResolveLiquidationCommand.Resolution.values().length) {
            throw new ProtocolException("invalid liquidation resolution: " + resolutionCode);
        }
        ResolveLiquidationCommand command = new ResolveLiquidationCommand(liquidationId,
                ResolveLiquidationCommand.Resolution.values()[resolutionCode], buffer.getLong());
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
        byte[] encoded = new byte[length];
        buffer.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static String readOptionalText(ByteBuffer buffer) {
        requireRemaining(buffer, Short.BYTES);
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length > MAX_TEXT_BYTES) {
            throw new ProtocolException("invalid optional text length: " + length);
        }
        requireRemaining(buffer, length);
        byte[] encoded = new byte[length];
        buffer.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
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
        byte[] encoded = new byte[length];
        buffer.get(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static CoreTimeInForce readTimeInForce(ByteBuffer buffer) {
        requireRemaining(buffer, Integer.BYTES);
        return CoreTimeInForce.fromWireCode(buffer.getInt());
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

    private static void requireRange(byte[] payload, int offset, int length, int limit) {
        if (length < 0 || offset < 0 || offset > limit - length) {
            throw new ProtocolException("truncated trading command payload");
        }
    }

    private static int putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        return offset + Short.BYTES;
    }

    private static int putInt(byte[] target, int offset, int value) {
        for (int index = 0; index < Integer.BYTES; index++) target[offset + index] = (byte) (value >>> (index * 8));
        return offset + Integer.BYTES;
    }

    private static int putLong(byte[] target, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) target[offset + index] = (byte) (value >>> (index * 8));
        return offset + Long.BYTES;
    }

    private static int getUnsignedShort(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset]) | Byte.toUnsignedInt(source[offset + 1]) << 8;
    }

    private static int getInt(byte[] source, int offset) {
        int value = 0;
        for (int index = 0; index < Integer.BYTES; index++) {
            value |= Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        return value;
    }

    private static long getLong(byte[] source, int offset) {
        long value = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        return value;
    }
}
