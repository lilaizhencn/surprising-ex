package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.provider.service.AccountUserCommandProcessor.ProcessingOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountCommandMetricsTest {

    @Test
    void recordsTerminalWaitingDuplicateAndFailureOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AccountCommandMetrics metrics = new AccountCommandMetrics(registry);

        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            metrics.record(outcome, Instant.now(), System.nanoTime());
        }
        metrics.recordFailure(null, System.nanoTime());

        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            String tag = outcome.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(registry.get("surprising.account.command.events")
                    .tag("outcome", tag).counter().count()).isEqualTo(1.0d);
            assertThat(registry.get("surprising.account.command.processing")
                    .tag("outcome", tag).timer().count()).isEqualTo(1L);
            assertThat(registry.get("surprising.account.command.event_lag")
                    .tag("outcome", tag).timer().count()).isEqualTo(1L);
        }
        assertThat(registry.get("surprising.account.command.events")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("surprising.account.command.processing")
                .tag("outcome", "failed").timer().count()).isEqualTo(1L);
        assertThat(registry.get("surprising.account.command.event_lag")
                .tag("outcome", "failed").timer().count()).isZero();
    }
}
