package com.surprising.aeron.protocol;

public record CoreOpenOrdersQuery(String instrumentId, long beforeOrderId, int limit) {

    public CoreOpenOrdersQuery {
        instrumentId = instrumentId == null ? "" : instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
        if (beforeOrderId < 0 || limit < 1 || limit > 1_001) {
            throw new IllegalArgumentException("invalid open orders query");
        }
    }
}
