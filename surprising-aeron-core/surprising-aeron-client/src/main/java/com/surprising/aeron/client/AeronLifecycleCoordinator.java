package com.surprising.aeron.client;

import java.util.function.Supplier;

public final class AeronLifecycleCoordinator {

    private static final AeronLifecycleCoordinator SHARED = new AeronLifecycleCoordinator();

    private final int maxAttempts;
    private final long baseDelayMillis;

    public AeronLifecycleCoordinator() {
        this(20, 25L);
    }

    public static AeronLifecycleCoordinator shared() {
        return SHARED;
    }

    public AeronLifecycleCoordinator(int maxAttempts, long baseDelayMillis) {
        if (maxAttempts < 1 || baseDelayMillis < 1) {
            throw new IllegalArgumentException("invalid lifecycle retry bounds");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
    }

    public <T> T execute(Supplier<T> operation) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                last = exception;
                if (!isLifecycleBusy(exception) || attempt == maxAttempts - 1) throw exception;
                try {
                    Thread.sleep(Math.multiplyExact(baseDelayMillis, attempt + 1L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("lifecycle retry interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("lifecycle operation did not execute") : last;
    }

    private static boolean isLifecycleBusy(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("LIFECYCLE_IN_PROGRESS")) {
                return true;
            }
        }
        return false;
    }
}
