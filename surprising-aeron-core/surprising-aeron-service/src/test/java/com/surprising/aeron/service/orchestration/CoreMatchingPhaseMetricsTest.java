package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreMatchingPhaseMetricsTest {
    @Test
    void phaseReportsKeepIndependentCountsAndResetBetweenIntervals() {
        var metrics = new CoreMatchingPhaseMetrics();
        metrics.recordPrepare(1_000);
        metrics.recordPrepare(3_000);
        metrics.recordExchange(9_000);
        metrics.recordApply(4_000);
        assertThat(metrics.reportAndReset()).isEqualTo(
                "prepare=avgMicros=2,maxMicros=3,count=2"
                        + " exchange=avgMicros=9,maxMicros=9,count=1"
                        + " apply=avgMicros=4,maxMicros=4,count=1");
        metrics.recordApply(2_000);
        assertThat(metrics.reportAndReset()).isEqualTo(
                "prepare=avgMicros=0,maxMicros=0,count=0"
                        + " exchange=avgMicros=0,maxMicros=0,count=0"
                        + " apply=avgMicros=2,maxMicros=2,count=1");
    }
}
