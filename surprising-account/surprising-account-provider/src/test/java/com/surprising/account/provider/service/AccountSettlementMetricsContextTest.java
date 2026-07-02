package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountSettlementMetricsContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AccountSettlementMetrics.class);

    @Test
    void createsMetricsBeanThroughSpringConstructorInjection() {
        contextRunner.run(context -> {
            AccountSettlementMetrics metrics = context.getBean(AccountSettlementMetrics.class);
            metrics.recordSuccess(Instant.now(), System.nanoTime(), true);

            assertThat(context).hasSingleBean(AccountSettlementMetrics.class);
            assertThat(context.getBean(MeterRegistry.class)
                    .get("surprising.account.match_trade.events")
                    .tag("outcome", "processed")
                    .counter()
                    .count()).isEqualTo(1.0d);
        });
    }
}
