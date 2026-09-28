package com.surprising.aeron.service.state.model;

import com.surprising.aeron.service.state.OrderReservation;

import com.surprising.aeron.protocol.CoreMarginMode;

public record CoreLeverageKey(long userId, String instrumentId, CoreMarginMode marginMode)
        implements Comparable<CoreLeverageKey> {

    public CoreLeverageKey {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (userId <= 0 || marginMode == null) {
            throw new IllegalArgumentException("invalid leverage key");
        }
    }

    @Override
    public int compareTo(CoreLeverageKey other) {
        int userComparison = Long.compare(userId, other.userId);
        if (userComparison != 0) return userComparison;
        int symbolComparison = instrumentId.compareTo(other.instrumentId);
        return symbolComparison != 0 ? symbolComparison : marginMode.compareTo(other.marginMode);
    }
}
