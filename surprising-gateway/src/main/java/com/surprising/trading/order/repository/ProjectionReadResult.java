package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.OrderResponse;
import java.util.List;
import java.util.Objects;

public record ProjectionReadResult(
        Status status,
        List<OrderResponse> orders,
        String nextCursor,
        boolean hasMore,
        long observedExportSequence,
        long requiredExportSequence,
        int encodedBytes) {

    public enum Status {
        OK,
        PROJECTION_LAG,
        RESPONSE_TOO_LARGE
    }

    public ProjectionReadResult {
        Objects.requireNonNull(status, "status");
        orders = orders == null ? List.of() : List.copyOf(orders);
        if (observedExportSequence < 0 || requiredExportSequence < 0 || encodedBytes < 0) {
            throw new IllegalArgumentException("projection read counters must not be negative");
        }
        if (status == Status.OK && observedExportSequence < requiredExportSequence) {
            throw new IllegalArgumentException("ready projection result is below required watermark");
        }
        if (status == Status.RESPONSE_TOO_LARGE && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("oversized projection result requires a continuation cursor");
        }
    }

    public static ProjectionReadResult ok(List<OrderResponse> orders, String nextCursor, boolean hasMore,
                                          long observedExportSequence, long requiredExportSequence) {
        return new ProjectionReadResult(Status.OK, orders, nextCursor, hasMore,
                observedExportSequence, requiredExportSequence, 0);
    }

    public static ProjectionReadResult ok(List<OrderResponse> orders, String nextCursor, boolean hasMore,
                                          long observedExportSequence, long requiredExportSequence,
                                          int encodedBytes) {
        return new ProjectionReadResult(Status.OK, orders, nextCursor, hasMore,
                observedExportSequence, requiredExportSequence, encodedBytes);
    }

    public static ProjectionReadResult lag(long observedExportSequence, long requiredExportSequence) {
        return new ProjectionReadResult(Status.PROJECTION_LAG, List.of(), null, false,
                observedExportSequence, requiredExportSequence, 0);
    }

    public static ProjectionReadResult responseTooLarge(long observedExportSequence, long requiredExportSequence,
                                                        String nextCursor) {
        return new ProjectionReadResult(Status.RESPONSE_TOO_LARGE, List.of(), nextCursor, true,
                observedExportSequence, requiredExportSequence, 0);
    }

    public boolean ready() {
        return status == Status.OK;
    }

    public String code() {
        return status.name();
    }

    public static final class ProjectionLagException extends IllegalStateException {
        private final long observedExportSequence;
        private final long requiredExportSequence;

        public ProjectionLagException(long observedExportSequence, long requiredExportSequence) {
            super("PROJECTION_LAG observed=" + observedExportSequence + " required=" + requiredExportSequence);
            this.observedExportSequence = observedExportSequence;
            this.requiredExportSequence = requiredExportSequence;
        }

        public long observedExportSequence() {
            return observedExportSequence;
        }

        public long requiredExportSequence() {
            return requiredExportSequence;
        }
    }

    public static final class ResponseTooLargeException extends IllegalStateException {
        private final long observedExportSequence;
        private final long requiredExportSequence;
        private final String nextCursor;

        public ResponseTooLargeException(long observedExportSequence, long requiredExportSequence, String nextCursor) {
            super("PROJECTION_RESPONSE_TOO_LARGE observed=" + observedExportSequence
                    + " required=" + requiredExportSequence + " nextCursor=" + nextCursor);
            if (nextCursor == null || nextCursor.isBlank()) {
                throw new IllegalArgumentException("oversized projection result requires a continuation cursor");
            }
            this.observedExportSequence = observedExportSequence;
            this.requiredExportSequence = requiredExportSequence;
            this.nextCursor = nextCursor;
        }

        public long observedExportSequence() {
            return observedExportSequence;
        }

        public long requiredExportSequence() {
            return requiredExportSequence;
        }

        public String nextCursor() {
            return nextCursor;
        }
    }
}
