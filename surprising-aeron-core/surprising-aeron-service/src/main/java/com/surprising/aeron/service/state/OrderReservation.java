package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ReservationKind;
import java.util.Locale;

public record OrderReservation(
        long orderId,
        String symbol,
        long instrumentVersion,
        ReservationKind kind,
        String asset,
        long reservedUnits,
        long releasedUnits,
        long consumedUnits,
        long orderQuantitySteps) {

    public OrderReservation {
        if (orderId <= 0 || instrumentVersion <= 0 || kind == null || reservedUnits <= 0
                || releasedUnits < 0 || consumedUnits < 0
                || Math.addExact(releasedUnits, consumedUnits) > reservedUnits || orderQuantitySteps <= 0) {
            throw new IllegalArgumentException("invalid order reservation");
        }
        symbol = normalizeSymbol(symbol);
        asset = AssetBalance.normalizeAsset(asset);
    }

    public static OrderReservation create(
            long orderId,
            String symbol,
            long instrumentVersion,
            ReservationKind kind,
            String asset,
            long reservedUnits,
            long orderQuantitySteps) {
        return new OrderReservation(orderId, symbol, instrumentVersion, kind, asset,
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
        return new OrderReservation(orderId, symbol, instrumentVersion, kind, asset, reservedUnits,
                Math.addExact(releasedUnits, units), consumedUnits, orderQuantitySteps);
    }

    public OrderReservation consume(long units) {
        if (units <= 0 || units > remainingUnits()) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is insufficient for fill");
        }
        return new OrderReservation(orderId, symbol, instrumentVersion, kind, asset, reservedUnits,
                releasedUnits, Math.addExact(consumedUnits, units), orderQuantitySteps);
    }

    public OrderReservation replaceReservedUnits(long newReservedUnits) {
        if (newReservedUnits <= 0 || newReservedUnits < Math.addExact(releasedUnits, consumedUnits)) {
            throw new CoreStateRejectedException("INVALID_REPLACEMENT_RESERVATION",
                    "replacement reservation is below already settled units");
        }
        return new OrderReservation(orderId, symbol, instrumentVersion, kind, asset, newReservedUnits,
                releasedUnits, consumedUnits, orderQuantitySteps);
    }

    static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + value);
        }
        return normalized;
    }
}
