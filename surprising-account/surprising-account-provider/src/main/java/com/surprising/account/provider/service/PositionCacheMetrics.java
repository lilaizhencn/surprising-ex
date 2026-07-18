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
    private final Counter acceleratorSubmitted;
    private final Counter acceleratorCoalesced;
    private final Counter acceleratorDropped;

    public PositionCacheMetrics(MeterRegistry registry) {
        this.applied = registry.counter("surprising.account.position.cache.applied");
        this.stale = registry.counter("surprising.account.position.cache.stale");
        this.failures = registry.counter("surprising.account.position.cache.failures");
        this.rebuildRows = registry.counter("surprising.account.position.cache.rebuild.rows");
        this.acceleratorSubmitted = registry.counter("surprising.account.position.cache.accelerator.submitted");
        this.acceleratorCoalesced = registry.counter("surprising.account.position.cache.accelerator.coalesced");
        this.acceleratorDropped = registry.counter("surprising.account.position.cache.accelerator.dropped");
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

    public void recordAcceleratorSubmitted() {
        acceleratorSubmitted.increment();
    }

    public void recordAcceleratorCoalesced() {
        acceleratorCoalesced.increment();
    }

    public void recordAcceleratorDropped() {
        acceleratorDropped.increment();
    }
}
