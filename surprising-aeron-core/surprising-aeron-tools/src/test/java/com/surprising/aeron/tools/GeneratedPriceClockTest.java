package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GeneratedPriceClockTest {
    @Test void preservesActualGenerationTimeAndWaitsBeforePublication() {
        long before = System.currentTimeMillis(), start = System.nanoTime();
        long generated = GeneratedPriceClock.timestamp();
        assertThat(generated).isBetween(before, System.currentTimeMillis());
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(2_000_000L);
    }

    @Test void interruptionStopsPublicationAndPreservesTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(GeneratedPriceClock::timestamp).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
