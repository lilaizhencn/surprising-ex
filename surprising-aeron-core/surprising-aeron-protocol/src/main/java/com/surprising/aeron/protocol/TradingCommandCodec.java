package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class TradingCommandCodec {

    private static final int PLACE_ORDER_V2_MARKER = 0x504f5632;
    private static final int INSTRUMENT_RISK_V2_MARKER = 0x49525632;

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
        byte[] baseAsset = text(command.baseAsset());
        byte[] quoteAsset = text(command.quoteAsset());
        byte[] settleAsset = text(command.settleAsset());
        byte[] asset = text(command.reservationAsset());
        byte[] clientOrderId = optionalText(command.clientOrderId());
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 2 + Short.BYTES + symbol.length
                        + Short.BYTES + baseAsset.length + Short.BYTES + quoteAsset.length
                        + Short.BYTES + settleAsset.length + Integer.BYTES
                        + Long.BYTES * 6 + Byte.BYTES * 2 + Integer.BYTES * 5
                        + Short.BYTES * 2 + asset.length + clientOrderId.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PLACE_ORDER_V2_MARKER)
                .putLong(command.orderId())
                .putLong(command.instrumentVersion())
                .putShort((short) symbol.length)
                .put(symbol)
                .putShort((short) baseAsset.length)
                .put(baseAsset)
                .putShort((short) quoteAsset.length)
                .put(quoteAsset)
                .putShort((short) settleAsset.length)
                .put(settleAsset)
                .putInt(command.side().wireCode())
                .putLong(command.priceTicks())
                .putLong(command.quantitySteps())
                .put((byte) (command.reduceOnly() ? 1 : 0))
                .putInt(command.marginMode().wireCode())
                .putInt(command.positionSide().wireCode())
                .putInt(command.reservationKind().wireCode())
                .putShort((short) asset.length)
                .put(asset)
                .putLong(command.reservedUnits())
                .putInt(command.orderType().wireCode())
                .putInt(command.timeInForce().wireCode())
                .putLong(command.matchingPriceTicks())
                .put((byte) (command.postOnly() ? 1 : 0))
                .putShort((short) clientOrderId.length)
                .put(clientOrderId)
                .putLong(command.makerFeeRatePpm())
                .putLong(command.takerFeeRatePpm())
                .array();
    }

    public static PlaceOrderCommand decodePlaceOrder(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 2);
        boolean version2 = buffer.remaining() >= Integer.BYTES && buffer.getInt(buffer.position()) == PLACE_ORDER_V2_MARKER;
        if (version2) buffer.getInt();
        long orderId = buffer.getLong();
        long instrumentVersion = buffer.getLong();
        String symbol = readText(buffer);
        String baseAsset = readText(buffer);
        String quoteAsset = readText(buffer);
        String settleAsset = readText(buffer);
        requireRemaining(buffer, Integer.BYTES + Long.BYTES * 2 + Byte.BYTES + Integer.BYTES);
        CoreOrderSide side = CoreOrderSide.fromWireCode(buffer.getInt());
        long priceTicks = buffer.getLong();
        long quantitySteps = buffer.getLong();
        byte reduceOnlyCode = buffer.get();
        if (reduceOnlyCode != 0 && reduceOnlyCode != 1) {
            throw new ProtocolException("invalid reduceOnly flag: " + reduceOnlyCode);
        }
        CoreMarginMode marginMode = CoreMarginMode.CROSS;
        CorePositionSide positionSide = CorePositionSide.NET;
        int firstCode = buffer.getInt();
        ReservationKind reservationKind;
        if (firstCode >= 0 && firstCode <= 1 && buffer.remaining() >= Integer.BYTES * 2 + Short.BYTES + Long.BYTES) {
            marginMode = CoreMarginMode.fromWireCode(firstCode);
            positionSide = CorePositionSide.fromWireCode(buffer.getInt());
            reservationKind = ReservationKind.fromWireCode(buffer.getInt());
        } else {
            reservationKind = ReservationKind.fromWireCode(firstCode);
        }
        String asset = readText(buffer);
        requireRemaining(buffer, Long.BYTES);
        long reservedUnits = buffer.getLong();
        CoreOrderType orderType = CoreOrderType.LIMIT;
        CoreTimeInForce timeInForce = CoreTimeInForce.GTC;
        long matchingPriceTicks = priceTicks;
        boolean postOnly = false;
        if (version2) {
            requireRemaining(buffer, Integer.BYTES * 2 + Long.BYTES + Byte.BYTES);
            orderType = CoreOrderType.fromWireCode(buffer.getInt());
            timeInForce = CoreTimeInForce.fromWireCode(buffer.getInt());
            matchingPriceTicks = buffer.getLong();
            byte postOnlyCode = buffer.get();
            if (postOnlyCode != 0 && postOnlyCode != 1) {
                throw new ProtocolException("invalid postOnly flag: " + postOnlyCode);
            }
            postOnly = postOnlyCode == 1;
        }
        String clientOrderId = "";
        long makerFeeRatePpm = 0;
        long takerFeeRatePpm = 0;
        if (version2 && buffer.hasRemaining()) {
            clientOrderId = readOptionalText(buffer);
            requireRemaining(buffer, Long.BYTES * 2);
            makerFeeRatePpm = buffer.getLong();
            takerFeeRatePpm = buffer.getLong();
        }
        requireConsumed(buffer);
        return new PlaceOrderCommand(orderId, symbol, instrumentVersion, baseAsset, quoteAsset, settleAsset,
                side, priceTicks, quantitySteps, reduceOnlyCode == 1, marginMode, positionSide,
                reservationKind, asset, reservedUnits, orderType, timeInForce, matchingPriceTicks, postOnly,
                clientOrderId, makerFeeRatePpm, takerFeeRatePpm);
    }

    public static byte[] encodeCancelOrder(CancelOrderCommand command) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(command.orderId()).array();
    }

    public static CancelOrderCommand decodeCancelOrder(byte[] payload) {
        if (payload == null || payload.length != Long.BYTES) {
            throw new ProtocolException("cancel order payload must be 8 bytes");
        }
        return new CancelOrderCommand(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getLong());
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
        if (!buffer.hasRemaining()) {
            return new UpsertInstrumentCommand(symbol, version, contractTypeCode, base, quote, settle,
                    multiplier, priceTick, settleScale, initialMargin, maintenanceMargin, makerFee, takerFee,
                    expiry, optionType, strike);
        }
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
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 3)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.instrumentVersion()).putLong(command.markPriceTicks())
                .putLong(command.priceSequence()).array();
    }

    public static ApplyMarkPriceCommand decodeApplyMarkPrice(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 3);
        ApplyMarkPriceCommand command = new ApplyMarkPriceCommand(symbol, buffer.getLong(), buffer.getLong(),
                buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeApplyFunding(ApplyFundingCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 3)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.settlementId()).putLong(command.instrumentVersion())
                .putLong(command.fundingRatePpm()).array();
    }

    public static ApplyFundingCommand decodeApplyFunding(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 3);
        ApplyFundingCommand command = new ApplyFundingCommand(buffer.getLong(), symbol, buffer.getLong(),
                buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeSettleInstrument(SettleInstrumentCommand command) {
        byte[] symbol = text(command.symbol());
        return ByteBuffer.allocate(Short.BYTES + symbol.length + Long.BYTES * 4)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) symbol.length).put(symbol)
                .putLong(command.settlementId()).putLong(command.instrumentVersion())
                .putLong(command.settlementPriceTicks()).putLong(command.optionCashUnitsPerContract()).array();
    }

    public static SettleInstrumentCommand decodeSettleInstrument(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        String symbol = readText(buffer);
        requireRemaining(buffer, Long.BYTES * 4);
        SettleInstrumentCommand command = new SettleInstrumentCommand(buffer.getLong(), symbol, buffer.getLong(),
                buffer.getLong(), buffer.getLong());
        requireConsumed(buffer);
        return command;
    }

    public static byte[] encodeExecuteLiquidation(ExecuteLiquidationCommand command) {
        return ByteBuffer.allocate(Long.BYTES * 4).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(command.liquidationId()).putLong(command.triggerPriceSequence())
                .putLong(command.executionPriceTicks()).putLong(command.liquidationFeeRatePpm()).array();
    }

    public static ExecuteLiquidationCommand decodeExecuteLiquidation(byte[] payload) {
        ByteBuffer buffer = readable(payload);
        requireRemaining(buffer, Long.BYTES * 2);
        long liquidationId = buffer.getLong();
        if (buffer.remaining() == Long.BYTES) {
            ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(liquidationId, buffer.getLong());
            requireConsumed(buffer);
            return command;
        }
        requireRemaining(buffer, Long.BYTES * 3);
        ExecuteLiquidationCommand command = new ExecuteLiquidationCommand(liquidationId, buffer.getLong(),
                buffer.getLong(), buffer.getLong());
        requireConsumed(buffer);
        return command;
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
}
