package com.surprising.trading.api;

import java.util.UUID;

/**
 * Per-request trace id holder used by synchronous HTTP entrypoints before events enter Kafka.
 *
 * <p>Kafka payloads carry the trace id explicitly because worker threads, outbox publishers, and
 * consumers cannot rely on a ThreadLocal crossing process boundaries.</p>
 */
public final class TraceContext {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final String TRACE_ID_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private TraceContext() {
    }

    public static String currentOrCreate() {
        String current = CURRENT.get();
        if (current != null && !current.isBlank()) {
            return current;
        }
        String generated = newTraceId();
        CURRENT.set(generated);
        return generated;
    }

    public static void set(String traceId) {
        CURRENT.set(normalizeOrCreate(traceId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String normalizeOrCreate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return newTraceId();
        }
        String normalized = traceId.trim();
        if (!normalized.matches(TRACE_ID_PATTERN)) {
            return newTraceId();
        }
        return normalized;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
