package com.surprising.aeron.protocol;

public record CoreTriggerOrderQuery(long triggerOrderId, String symbol, long beforeTriggerOrderId, int limit,
                                    CoreTriggerOrderStatus status, long expiresBeforeEpochMillis) {
    public CoreTriggerOrderQuery(long triggerOrderId, String symbol, long beforeTriggerOrderId, int limit) {
        this(triggerOrderId, symbol, beforeTriggerOrderId, limit, null, 0);
    }

    public CoreTriggerOrderQuery(long triggerOrderId, String symbol, long beforeTriggerOrderId, int limit,
                                 CoreTriggerOrderStatus status) {
        this(triggerOrderId, symbol, beforeTriggerOrderId, limit, status, 0);
    }

    public CoreTriggerOrderQuery {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (triggerOrderId < 0 || beforeTriggerOrderId < 0 || limit < 1 || limit > 1001
                || expiresBeforeEpochMillis < 0) {
            throw new IllegalArgumentException("invalid trigger order query");
        }
    }
}
