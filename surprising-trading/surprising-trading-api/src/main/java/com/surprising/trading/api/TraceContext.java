package com.surprising.trading.api;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Per-request trace id holder used by synchronous HTTP entrypoints before events enter Kafka.
 *
 * <p>Kafka payloads carry the trace id explicitly because worker threads, outbox publishers, and
 * consumers cannot rely on a ThreadLocal crossing process boundaries.</p>
 */
public final class TraceContext {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final java.util.regex.Pattern TRACE_ID_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private TraceContext() {
    }

    public static String currentOrCreate() {
        String current = current();
        if (current != null && !current.isBlank()) {
            return current;
        }
        String generated = newTraceId();
        set(generated);
        return generated;
    }

    public static void set(String traceId) {
        String normalized = normalizeOrCreate(traceId);
        MDC.put("traceId", normalized);
    }

    public static void clear() {
        MDC.remove("traceId");
    }

    public static String current() {
        return MDC.get("traceId");
    }

    /** Capture an id before an async handoff; open this scope on the receiving thread. */
    public static Scope open(String traceId) {
        return new Scope(traceId);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous = MDC.get("traceId");
        private boolean closed;
        private Scope(String traceId) { set(traceId); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }

    public static String normalizeOrCreate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return newTraceId();
        }
        String normalized = traceId.trim();
        if (!isValid(normalized)) {
            return newTraceId();
        }
        return normalized;
    }

    public static boolean isValid(String traceId) {
        return traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches();
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
