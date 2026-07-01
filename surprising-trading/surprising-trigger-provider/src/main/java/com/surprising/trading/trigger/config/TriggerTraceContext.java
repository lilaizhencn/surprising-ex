package com.surprising.trading.trigger.config;

public final class TriggerTraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TriggerTraceContext() {
    }

    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(traceId);
        }
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
