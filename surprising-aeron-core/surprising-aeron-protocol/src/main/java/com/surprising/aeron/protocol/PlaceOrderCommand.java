package com.surprising.aeron.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Immutable API values, or a decoder-owned slot retained until command retirement. */
public final class PlaceOrderCommand {
    private long orderId;
    private String instrumentId;
    private CoreOrderSide side;
    private long limitPriceTicks;
    private long quantitySteps;
    private boolean reduceOnly;
    private CoreMarginMode marginMode;
    private CorePositionSide positionSide;
    private CoreOrderType orderType;
    private CoreTimeInForce timeInForce;
    private boolean postOnly;
    private String clientOrderId;
    private final boolean commandSlot;
    /** Lazy, bounded wire scratch owned by this decoder slot; never shared with the resulting String. */
    private CharsetDecoder clientNameDecoder;
    private ByteBuffer clientNameBytes;
    private CharBuffer clientNameChars;

    public PlaceOrderCommand(
        long orderId,
        String instrumentId,
        CoreOrderSide side,
        long limitPriceTicks,
        long quantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId) {
        commandSlot = false;
        assign(orderId, instrumentId, side, limitPriceTicks, quantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId);
    }

    private PlaceOrderCommand() { commandSlot = true; }
    public static PlaceOrderCommand commandSlot() { return new PlaceOrderCommand(); }

    String decodeClientOrderId(byte[] payload, int offset, int length) {
        if (!commandSlot) throw new IllegalStateException("cannot decode into an immutable order command");
        if (length == 0) return "";
        for (int index = offset; index < offset + length; index++) {
            if (payload[index] >= 0) continue;
            if (clientNameDecoder == null) {
                clientNameDecoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                // The command wire format permits at most 64 client-name bytes. Decoding,
                // including replacement of malformed bytes, cannot exceed 64 UTF-16 units.
                clientNameBytes = ByteBuffer.allocate(64);
                clientNameChars = CharBuffer.allocate(64);
            }
            clientNameBytes.clear();
            clientNameBytes.put(payload, offset, length).flip();
            clientNameChars.clear();
            clientNameDecoder.reset();
            if (!clientNameDecoder.decode(clientNameBytes, clientNameChars, true).isUnderflow()
                    || !clientNameDecoder.flush(clientNameChars).isUnderflow())
                throw new ProtocolException("client order id exceeds decode buffer");
            return new String(clientNameChars.array(), 0, clientNameChars.position());
        }
        return new String(payload, offset, length, StandardCharsets.UTF_8);
    }

    void decodeInto(
        long orderId,
        String instrumentId,
        CoreOrderSide side,
        long limitPriceTicks,
        long quantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId) {
        if (!commandSlot) throw new IllegalStateException("place order is not a decoder slot");
        assign(orderId, instrumentId, side, limitPriceTicks, quantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId);
    }

    private void assign(
        long orderId,
        String instrumentId,
        CoreOrderSide side,
        long limitPriceTicks,
        long quantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId) {
        if (orderId <= 0 || instrumentId == null || instrumentId.isBlank()
                || side == null || limitPriceTicks < 0
                || quantitySteps <= 0 || marginMode == null || positionSide == null
                || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64
                || orderType == CoreOrderType.LIMIT && limitPriceTicks <= 0
                || orderType == CoreOrderType.MARKET && limitPriceTicks != 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)) {
            throw new IllegalArgumentException("invalid place order command");
        }
        this.orderId = orderId;
        this.instrumentId = instrumentId;
        this.side = side;
        this.limitPriceTicks = limitPriceTicks;
        this.quantitySteps = quantitySteps;
        this.reduceOnly = reduceOnly;
        this.marginMode = marginMode;
        this.positionSide = positionSide;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.postOnly = postOnly;
        this.clientOrderId = clientOrderId;
    }

    boolean isCommandSlot() { return commandSlot; }
    PlaceOrderCommand immutableValue() {
        return commandSlot ? new PlaceOrderCommand(orderId, instrumentId, side, limitPriceTicks, quantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId) : this;
    }

    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable order command");
        instrumentId = clientOrderId = null;
        side = null; marginMode = null; positionSide = null; orderType = null; timeInForce = null;
        orderId = 0;
    }

    public long orderId() { return orderId; }
    public String instrumentId() { return instrumentId; }
    public CoreOrderSide side() { return side; }
    public long limitPriceTicks() { return limitPriceTicks; }
    public long quantitySteps() { return quantitySteps; }
    public boolean reduceOnly() { return reduceOnly; }
    public CoreMarginMode marginMode() { return marginMode; }
    public CorePositionSide positionSide() { return positionSide; }
    public CoreOrderType orderType() { return orderType; }
    public CoreTimeInForce timeInForce() { return timeInForce; }
    public boolean postOnly() { return postOnly; }
    public String clientOrderId() { return clientOrderId; }

    @Override public boolean equals(Object other) {
        return other instanceof PlaceOrderCommand value && orderId == value.orderId && java.util.Objects.equals(instrumentId, value.instrumentId) && java.util.Objects.equals(side, value.side) && limitPriceTicks == value.limitPriceTicks && quantitySteps == value.quantitySteps && reduceOnly == value.reduceOnly && java.util.Objects.equals(marginMode, value.marginMode) && java.util.Objects.equals(positionSide, value.positionSide) && java.util.Objects.equals(orderType, value.orderType) && java.util.Objects.equals(timeInForce, value.timeInForce) && postOnly == value.postOnly && java.util.Objects.equals(clientOrderId, value.clientOrderId);
    }
    @Override public int hashCode() { return java.util.Objects.hash(orderId, instrumentId, side, limitPriceTicks, quantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId); }
    @Override public String toString() { return "PlaceOrderCommand[orderId=" + orderId + ", instrumentId=" + instrumentId + ", quantitySteps=" + quantitySteps + "]"; }
}
