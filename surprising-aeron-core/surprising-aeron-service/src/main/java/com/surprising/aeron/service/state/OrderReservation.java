package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ReservationKind;
import java.util.Locale;

/** Immutable reservation. Internal amount transitions retain already validated identity strings. */
public final class OrderReservation {
    private final long orderId;
    private final String symbol;
    private final long instrumentChangeId;
    private final ReservationKind kind;
    private final String asset;
    private final long reservedUnits;
    private final long releasedUnits;
    private final long consumedUnits;
    private final long orderQuantitySteps;

    public OrderReservation(long orderId, String symbol, long instrumentChangeId, ReservationKind kind,
                            String asset, long reservedUnits, long releasedUnits, long consumedUnits,
                            long orderQuantitySteps) {
        validateAmounts(orderId, instrumentChangeId, kind, reservedUnits, releasedUnits, consumedUnits,
                orderQuantitySteps);
        this.orderId = orderId;
        this.symbol = normalizeSymbol(symbol);
        this.instrumentChangeId = instrumentChangeId;
        this.kind = kind;
        this.asset = AssetBalance.normalizeAsset(asset);
        this.reservedUnits = reservedUnits;
        this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits;
        this.orderQuantitySteps = orderQuantitySteps;
    }

    private OrderReservation(OrderReservation previous, long reservedUnits, long releasedUnits, long consumedUnits) {
        validateAmounts(previous.orderId, previous.instrumentChangeId, previous.kind, reservedUnits,
                releasedUnits, consumedUnits, previous.orderQuantitySteps);
        orderId = previous.orderId;
        symbol = previous.symbol;
        instrumentChangeId = previous.instrumentChangeId;
        kind = previous.kind;
        asset = previous.asset;
        this.reservedUnits = reservedUnits;
        this.releasedUnits = releasedUnits;
        this.consumedUnits = consumedUnits;
        orderQuantitySteps = previous.orderQuantitySteps;
    }

    private static void validateAmounts(long orderId, long instrumentChangeId, ReservationKind kind,
                                        long reservedUnits, long releasedUnits, long consumedUnits,
                                        long orderQuantitySteps) {
        if (orderId <= 0 || instrumentChangeId <= 0 || kind == null || reservedUnits <= 0
                || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > reservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid order reservation");
        }
    }

    public long orderId() { return orderId; }
    public String symbol() { return symbol; }
    public long instrumentChangeId() { return instrumentChangeId; }
    public ReservationKind kind() { return kind; }
    public String asset() { return asset; }
    public long reservedUnits() { return reservedUnits; }
    public long releasedUnits() { return releasedUnits; }
    public long consumedUnits() { return consumedUnits; }
    public long orderQuantitySteps() { return orderQuantitySteps; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OrderReservation value
                && orderId == value.orderId && instrumentChangeId == value.instrumentChangeId
                && kind == value.kind && reservedUnits == value.reservedUnits
                && releasedUnits == value.releasedUnits && consumedUnits == value.consumedUnits
                && orderQuantitySteps == value.orderQuantitySteps
                && symbol.equals(value.symbol) && asset.equals(value.asset);
    }

    @Override
    public int hashCode() {
        int hash = Long.hashCode(orderId);
        hash = 31 * hash + symbol.hashCode();
        hash = 31 * hash + Long.hashCode(instrumentChangeId);
        hash = 31 * hash + kind.hashCode();
        hash = 31 * hash + asset.hashCode();
        hash = 31 * hash + Long.hashCode(reservedUnits);
        hash = 31 * hash + Long.hashCode(releasedUnits);
        hash = 31 * hash + Long.hashCode(consumedUnits);
        return 31 * hash + Long.hashCode(orderQuantitySteps);
    }

    @Override
    public String toString() {
        return "OrderReservation[orderId=" + orderId + ", symbol=" + symbol
                + ", instrumentChangeId=" + instrumentChangeId + ", kind=" + kind + ", asset=" + asset
                + ", reservedUnits=" + reservedUnits + ", releasedUnits=" + releasedUnits
                + ", consumedUnits=" + consumedUnits + ", orderQuantitySteps=" + orderQuantitySteps + "]";
    }

    public static OrderReservation create(
            long orderId,
            String symbol,
            long instrumentChangeId,
            ReservationKind kind,
            String asset,
            long reservedUnits,
            long orderQuantitySteps) {
        return new OrderReservation(orderId, symbol, instrumentChangeId, kind, asset,
                reservedUnits, 0, 0, orderQuantitySteps);
    }

    public long remainingUnits() {
        return Math.subtractExact(reservedUnits, Math.addExact(releasedUnits, consumedUnits));
    }

    public OrderReservation releaseAll() {
        return release(remainingUnits());
    }

    public OrderReservation release(long units) {
        if (units <= 0 || units > remainingUnits()) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for release");
        }
        return new OrderReservation(this, reservedUnits, Math.addExact(releasedUnits, units), consumedUnits);
    }

    public OrderReservation consume(long units) {
        if (units <= 0 || units > remainingUnits()) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for fill");
        }
        return new OrderReservation(this, reservedUnits, releasedUnits, Math.addExact(consumedUnits, units));
    }

    public OrderReservation replaceReservedUnits(long newReservedUnits) {
        if (newReservedUnits <= 0 || newReservedUnits < Math.addExact(releasedUnits, consumedUnits)) {
            throw new CoreStateRejectedException("INVALID_REPLACEMENT_RESERVATION",
                    "replacement reservation is below already settled units");
        }
        return new OrderReservation(this, newReservedUnits, releasedUnits, consumedUnits);
    }

    static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String trimmed = value.trim();
        if (validSymbol(trimmed)) return trimmed;
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!validSymbol(normalized)) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
        return normalized;
    }

    private static boolean validSymbol(String value) {
        int length = value.length();
        if (length < 2 || length > 64 || !isAsciiAlphaNumeric(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < length; index++) {
            char character = value.charAt(index);
            if (!isAsciiAlphaNumeric(character) && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlphaNumeric(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }
}
