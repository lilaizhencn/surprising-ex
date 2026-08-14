package com.surprising.aeron.protocol;

public record CoreTriggerOrderQuery(long triggerOrderId, String symbol, long beforeTriggerOrderId, int limit) {
    public CoreTriggerOrderQuery {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (triggerOrderId < 0 || beforeTriggerOrderId < 0 || limit < 1 || limit > 1001) {
            throw new IllegalArgumentException("invalid trigger order query");
        }
    }
}
