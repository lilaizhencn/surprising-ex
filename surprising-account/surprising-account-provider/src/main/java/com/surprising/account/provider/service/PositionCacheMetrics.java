package com.surprising.account.provider.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PositionCacheMetrics {

    private final Counter applied;
    private final Counter stale;
    private final Counter failures;
    private final Counter rebuildRows;

    public PositionCacheMetrics(MeterRegistry registry) {
        this.applied = registry.counter("surprising.account.position.cache.applied");
        this.stale = registry.counter("surprising.account.position.cache.stale");
        this.failures = registry.counter("surprising.account.position.cache.failures");
        this.rebuildRows = registry.counter("surprising.account.position.cache.rebuild.rows");
    }

    public void recordApplied(boolean rebuild) {
        applied.increment();
        if (rebuild) {
            rebuildRows.increment();
        }
    }

    public void recordStale() {
        stale.increment();
    }

    public void recordFailure() {
        failures.increment();
    }
}
