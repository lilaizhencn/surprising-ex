package com.surprising.aeron.benchmarks.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClusterCapacityMetricsTest {

    @Test
    void exposesCorrectedAcceptanceToFinalizationMetrics() {
        ClusterCapacityMain.CapacityMetrics metrics =
                new ClusterCapacityMain.CapacityMetrics(10_000_000L);
        metrics.recordOffered();
        metrics.recordAccepted();
        metrics.recordFinalized(100_000_000L);

        ClusterCapacityMain.MetricsSnapshot snapshot = metrics.snapshot(1_000_000_000L);
        assertThat(snapshot.offered()).isEqualTo(1);
        assertThat(snapshot.accepted()).isEqualTo(1);
        assertThat(snapshot.finalized()).isEqualTo(1);
        assertThat(snapshot.finalizedPerSecond()).isEqualTo(1.0);
        assertThat(snapshot.correctedSampleCount()).isEqualTo(10);
        assertThat(snapshot.p50Micros()).isBetween(49_000L, 51_000L);
        assertThat(snapshot.p999Micros()).isBetween(99_000L, 101_000L);
    }
    @Test
    void excludesWarmupAndDrainFromFixedWindowWithoutDroppingTerminalAudit() {
        var metrics = new ClusterCapacityMain.CapacityMetrics(0);
        metrics.measureBetween(1_000, 2_000);
        for (long terminal : new long[]{999, 1_000, 1_999, 2_000, 2_001}) {
            metrics.recordOffered();
            metrics.recordAccepted();
            metrics.recordFinalizedAt(terminal < 2_000 ? 10_000 : 1_000_000, terminal);
        }

        var audit = metrics.snapshot(1_000_000_000);
        var steady = metrics.steadySnapshot(1_000_000_000);
        assertThat(audit.offered()).isEqualTo(5);
        assertThat(audit.accepted()).isEqualTo(audit.finalized()).isEqualTo(5);
        assertThat(steady.finalized()).isEqualTo(2);
        assertThat(steady.finalizedPerSecond()).isEqualTo(2.0);
        assertThat(steady.correctedSampleCount()).isEqualTo(2);
        assertThat(steady.maxMicros()).isEqualTo(10);
        assertThat(audit.maxMicros()).isGreaterThanOrEqualTo(1_000);
        assertThat(audit.finalized() - steady.finalized()).isEqualTo(3);
    }

    @Test
    void resetDropsThePreviousWindowAndBothHistograms() {
        var metrics = new ClusterCapacityMain.CapacityMetrics(0);
        metrics.measureBetween(1_000, 2_000);
        metrics.recordFinalizedAt(10_000, 1_500);
        metrics.reset();
        metrics.measureBetween(3_000, 4_000);
        metrics.recordFinalizedAt(20_000, 3_500);

        assertThat(metrics.snapshot(1_000_000_000).finalized()).isEqualTo(1);
        var steady = metrics.steadySnapshot(1_000_000_000);
        assertThat(steady.finalized()).isEqualTo(1);
        assertThat(steady.maxMicros()).isEqualTo(20);
        assertThatThrownBy(() -> metrics.measureBetween(4_000, 4_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.steadySnapshot(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void requiresActualTopologyAndAllQueuesToBeDrained() {
        var drained = laneMetrics(0);
        ClusterCapacityMain.requireDrainedTopology(drained, 1, 2);
        assertThatThrownBy(() -> ClusterCapacityMain.requireDrainedTopology(drained, 1, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("topology");
        assertThatThrownBy(() -> ClusterCapacityMain.requireDrainedTopology(drained, 2, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("topology");
        assertThatThrownBy(() -> ClusterCapacityMain.requireDrainedTopology(laneMetrics(1), 1, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not drained");
    }

    private static com.surprising.aeron.protocol.CoreLaneMetricsView laneMetrics(int dispatchDepth) {
        return new com.surprising.aeron.protocol.CoreLaneMetricsView(1, 2,
                dispatchDepth, 64, dispatchDepth, 0, 64, 0, 0, 64, 0, 0,
                new long[2], new long[2], new long[2], new int[2], new int[]{64, 64}, new int[2],
                new long[2], new long[2], new long[8], new long[8], new long[8], new long[8]);
    }

}
