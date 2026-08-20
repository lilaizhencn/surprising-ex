package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClusterCapacityMetricsTest {

    @Test
    void exposesCorrectedAcceptanceToFinalizationMetrics() {
        ClusterCapacityMain.CapacityMetrics metrics =
                new ClusterCapacityMain.CapacityMetrics(10_000_000L);
        metrics.recordOffered();
        metrics.recordAccepted();
        metrics.recordFinalized(100_000_000L, 17L);

        ClusterCapacityMain.MetricsSnapshot snapshot = metrics.snapshot(1_000_000_000L);
        assertThat(snapshot.offered()).isEqualTo(1);
        assertThat(snapshot.accepted()).isEqualTo(1);
        assertThat(snapshot.finalized()).isEqualTo(1);
        assertThat(snapshot.finalizedPerSecond()).isEqualTo(1.0);
        assertThat(snapshot.correctedSampleCount()).isEqualTo(10);
        assertThat(snapshot.p50Micros()).isBetween(49_000L, 51_000L);
        assertThat(snapshot.p999Micros()).isBetween(99_000L, 101_000L);
        assertThat(snapshot.outboxMaxSequence()).isEqualTo(17L);
    }
}
