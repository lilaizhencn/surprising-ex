package com.surprising.account.provider.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AccountSettlementMetrics {

    private static final String OUTCOME_PROCESSED = "processed";
    private static final String OUTCOME_DUPLICATE = "duplicate";
    private static final String OUTCOME_FAILED = "failed";

    private final Map<String, Counter> eventCounters;
    private final Map<String, Timer> processingTimers;
    private final Map<String, Timer> eventLagTimers;
    private final Timer userLockWaitTimer;

    @Autowired
    public AccountSettlementMetrics(MeterRegistry meterRegistry) {
        this.eventCounters = Map.of(
                OUTCOME_PROCESSED, eventCounter(meterRegistry, OUTCOME_PROCESSED),
                OUTCOME_DUPLICATE, eventCounter(meterRegistry, OUTCOME_DUPLICATE),
                OUTCOME_FAILED, eventCounter(meterRegistry, OUTCOME_FAILED));
        this.processingTimers = Map.of(
                OUTCOME_PROCESSED, processingTimer(meterRegistry, OUTCOME_PROCESSED),
                OUTCOME_DUPLICATE, processingTimer(meterRegistry, OUTCOME_DUPLICATE),
                OUTCOME_FAILED, processingTimer(meterRegistry, OUTCOME_FAILED));
        this.eventLagTimers = Map.of(
                OUTCOME_PROCESSED, eventLagTimer(meterRegistry, OUTCOME_PROCESSED),
                OUTCOME_DUPLICATE, eventLagTimer(meterRegistry, OUTCOME_DUPLICATE),
                OUTCOME_FAILED, eventLagTimer(meterRegistry, OUTCOME_FAILED));
        this.userLockWaitTimer = userLockWaitTimer(meterRegistry);
    }

    private AccountSettlementMetrics(Map<String, Counter> eventCounters,
                                     Map<String, Timer> processingTimers,
                                     Map<String, Timer> eventLagTimers,
                                     Timer userLockWaitTimer) {
        this.eventCounters = eventCounters;
        this.processingTimers = processingTimers;
        this.eventLagTimers = eventLagTimers;
        this.userLockWaitTimer = userLockWaitTimer;
    }

    public static AccountSettlementMetrics noop() {
        return new AccountSettlementMetrics(Map.of(), Map.of(), Map.of(), null);
    }

    public void recordSuccess(Instant eventTime, long startedAtNanos, boolean processed) {
        record(processed ? OUTCOME_PROCESSED : OUTCOME_DUPLICATE, eventTime, startedAtNanos);
    }

    public void recordFailure(Instant eventTime, long startedAtNanos) {
        record(OUTCOME_FAILED, eventTime, startedAtNanos);
    }

    public void recordUserLockWait(long startedAtNanos) {
        if (userLockWaitTimer != null) {
            userLockWaitTimer.record(Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos)));
        }
    }

    private void record(String outcome, Instant eventTime, long startedAtNanos) {
        Counter counter = eventCounters.get(outcome);
        if (counter != null) {
            counter.increment();
        }
        Timer processingTimer = processingTimers.get(outcome);
        if (processingTimer != null) {
            processingTimer.record(Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos)));
        }
        Timer eventLagTimer = eventLagTimers.get(outcome);
        if (eventLagTimer != null && eventTime != null) {
            Duration lag = Duration.between(eventTime, Instant.now());
            eventLagTimer.record(lag.isNegative() ? Duration.ZERO : lag);
        }
    }

    private static Counter eventCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("surprising.account.match_trade.events")
                .description("Account match-trade consumer events by settlement outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Timer processingTimer(MeterRegistry meterRegistry, String outcome) {
        return Timer.builder("surprising.account.match_trade.processing")
                .description("Account match-trade processing duration")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Timer eventLagTimer(MeterRegistry meterRegistry, String outcome) {
        return Timer.builder("surprising.account.match_trade.event_lag")
                .description("Lag from match-trade event time to account consumer observation")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Timer userLockWaitTimer(MeterRegistry meterRegistry) {
        return Timer.builder("surprising.account.match_trade.user_lock_wait")
                .description("Time spent waiting for per-user match-trade settlement locks")
                .register(meterRegistry);
    }
}
