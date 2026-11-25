package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProjectionWatermarkWaiter {

    public static final int DEFAULT_MAX_WAIT_MS = 2_000;
    private static final long INITIAL_POLL_MS = 10L;
    private static final long MAX_POLL_MS = 100L;
    private static final int DEFAULT_MAX_ATTEMPTS = 2_048;
    private static final String WATERMARK_SQL = """
            SELECT last_export_sequence FROM projection_watermarks
            WHERE product_line = ?
            """;

    @FunctionalInterface
    public interface WaitStrategy {
        void await(long millis);
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final WaitStrategy waitStrategy;
    private final int maxAttempts;

    @Autowired
    public ProjectionWatermarkWaiter(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC(), millis ->
                LockSupport.parkNanos(Duration.ofMillis(millis).toNanos()), DEFAULT_MAX_ATTEMPTS);
    }

    public ProjectionWatermarkWaiter(JdbcTemplate jdbcTemplate, Clock clock, WaitStrategy waitStrategy) {
        this(jdbcTemplate, clock, waitStrategy, DEFAULT_MAX_ATTEMPTS);
    }

    public ProjectionWatermarkWaiter(JdbcTemplate jdbcTemplate, Clock clock,
                                     WaitStrategy waitStrategy, int maxAttempts) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waitStrategy = Objects.requireNonNull(waitStrategy, "waitStrategy");
        if (maxAttempts < 1 || maxAttempts > 100_000) {
            throw new IllegalArgumentException("maxAttempts must be in [1,100000]");
        }
        this.maxAttempts = maxAttempts;
    }

    public ProjectionReadResult await(ProductLine productLine, long requiredExportSequence, int maxWaitMs) {
        requireProductLine(productLine);
        if (requiredExportSequence < 0) {
            throw new IllegalArgumentException("requiredExportSequence must not be negative");
        }
        if (maxWaitMs < 0 || maxWaitMs > DEFAULT_MAX_WAIT_MS) {
            throw new IllegalArgumentException("maxWaitMs must be in [0, 2000]");
        }

        long observed = observed(productLine);
        if (observed >= requiredExportSequence || maxWaitMs == 0) {
            return observed >= requiredExportSequence
                    ? ProjectionReadResult.ok(java.util.List.of(), null, false,
                    observed, requiredExportSequence)
                    : ProjectionReadResult.lag(observed, requiredExportSequence);
        }

        long deadline = Math.addExact(clock.millis(), maxWaitMs);
        int attempts = 0;
        long pollMs = INITIAL_POLL_MS;
        while (observed < requiredExportSequence && attempts < maxAttempts && clock.millis() < deadline) {
            long remaining = deadline - clock.millis();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) break;
            waitStrategy.await(Math.min(pollMs, remaining));
            if (Thread.currentThread().isInterrupted()) break;
            pollMs = Math.min(MAX_POLL_MS, pollMs * 2);
            attempts++;
            observed = observed(productLine);
        }
        return observed >= requiredExportSequence
                ? ProjectionReadResult.ok(java.util.List.of(), null, false,
                observed, requiredExportSequence)
                : ProjectionReadResult.lag(observed, requiredExportSequence);
    }

    public long observed(ProductLine productLine) {
        requireProductLine(productLine);
        Long value = jdbcTemplate.queryForObject(WATERMARK_SQL, Long.class, productLine.name());
        return value == null ? 0L : value;
    }

    private static void requireProductLine(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("productLine is required");
        }
    }
}
