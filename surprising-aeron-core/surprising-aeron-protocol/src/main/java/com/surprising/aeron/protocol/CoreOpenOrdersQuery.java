package com.surprising.aeron.protocol;

public record CoreOpenOrdersQuery(String symbol, long beforeOrderId, int limit) {

    public CoreOpenOrdersQuery {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (beforeOrderId < 0 || limit < 1 || limit > 1_001) {
            throw new IllegalArgumentException("invalid open orders query");
        }
    }
}
