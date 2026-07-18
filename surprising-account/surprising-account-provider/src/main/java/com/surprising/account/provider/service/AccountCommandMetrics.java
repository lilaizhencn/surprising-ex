package com.surprising.account.provider.service;

import com.surprising.account.provider.service.AccountUserCommandProcessor.ProcessingOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountCommandMetrics {

    private final Map<ProcessingOutcome, Counter> eventCounters;
    private final Map<ProcessingOutcome, Timer> processingTimers;
    private final Map<ProcessingOutcome, Timer> eventLagTimers;
    private final Counter failedCounter;
    private final Timer failedProcessingTimer;
    private final Timer failedEventLagTimer;

    public AccountCommandMetrics(MeterRegistry meterRegistry) {
        this.eventCounters = new EnumMap<>(ProcessingOutcome.class);
        this.processingTimers = new EnumMap<>(ProcessingOutcome.class);
        this.eventLagTimers = new EnumMap<>(ProcessingOutcome.class);
        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            String outcomeTag = outcome.name().toLowerCase(java.util.Locale.ROOT);
            eventCounters.put(outcome, eventCounter(meterRegistry, outcomeTag));
            processingTimers.put(outcome, processingTimer(meterRegistry, outcomeTag));
            eventLagTimers.put(outcome, eventLagTimer(meterRegistry, outcomeTag));
        }
        this.failedCounter = eventCounter(meterRegistry, "failed");
        this.failedProcessingTimer = processingTimer(meterRegistry, "failed");
        this.failedEventLagTimer = eventLagTimer(meterRegistry, "failed");
    }

    public void record(ProcessingOutcome outcome, Instant eventTime, long startedAtNanos) {
        eventCounters.get(outcome).increment();
        recordTimers(processingTimers.get(outcome), eventLagTimers.get(outcome), eventTime, startedAtNanos);
    }

    public void recordFailure(Instant eventTime, long startedAtNanos) {
        failedCounter.increment();
        recordTimers(failedProcessingTimer, failedEventLagTimer, eventTime, startedAtNanos);
    }

    private void recordTimers(Timer processingTimer,
                              Timer eventLagTimer,
                              Instant eventTime,
                              long startedAtNanos) {
        processingTimer.record(Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos)));
        if (eventTime != null) {
            Duration lag = Duration.between(eventTime, Instant.now());
            eventLagTimer.record(lag.isNegative() ? Duration.ZERO : lag);
        }
    }

    private static Counter eventCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("surprising.account.command.events")
                .description("Account user commands by processing outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Timer processingTimer(MeterRegistry meterRegistry, String outcome) {
        return Timer.builder("surprising.account.command.processing")
                .description("Account user command processing duration")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Timer eventLagTimer(MeterRegistry meterRegistry, String outcome) {
        return Timer.builder("surprising.account.command.event_lag")
                .description("Lag from command occurrence to account consumer observation")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
