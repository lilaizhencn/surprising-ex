package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Bounded functional checks for the two affected fixtures; no JMH timing or snapshot recovery. */
class D1BenchmarkBoundaryTest {
    @Test
    void mixedReplyLeasesSurviveRepeatedIdRoutedPublication() {
        var workload = new ReplySlotAllocationBenchmark();
        try {
            workload.setup();
            long first = workload.publishMixedReplies();
            assertThat(first).isPositive();
            assertThat(workload.publishMixedReplies()).isGreaterThan(first);
        } finally { workload.close(); }
    }

    @Test
    void inProcessTradingFixtureCompletesAndChecksTerminalFunds() {
        var workload = new InProcessTradingAllocationBenchmark();
        workload.batchSize = 1;
        workload.streamMode = "OPEN_ONLY";
        workload.clientIdMode = "ASCII";
        workload.oiLimitMode = "FIXED";
        workload.symbols = 1;
        workload.accountLanes = 2;
        try {
            workload.setup();
            assertThat(workload.continuousMatchStream()).isEqualTo(4096);
        }
        finally { workload.close(); }
    }
}
