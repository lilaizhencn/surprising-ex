package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;

public record CoreLeverageKey(long userId, String symbol, CoreMarginMode marginMode)
        implements Comparable<CoreLeverageKey> {

    public CoreLeverageKey {
        symbol = OrderReservation.normalizeSymbol(symbol);
        if (userId <= 0 || marginMode == null) {
            throw new IllegalArgumentException("invalid leverage key");
        }
    }

    @Override
    public int compareTo(CoreLeverageKey other) {
        int userComparison = Long.compare(userId, other.userId);
        if (userComparison != 0) return userComparison;
        int symbolComparison = symbol.compareTo(other.symbol);
        return symbolComparison != 0 ? symbolComparison : marginMode.compareTo(other.marginMode);
    }
}
