package com.surprising.aeron.service.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,OPEN_ONLY", "4,OPEN_ONLY", "1,REVERSE", "4,REVERSE"})
    void inProcessTradingFixtureCompletesAndChecksTerminalFunds(int batchSize, String streamMode) {
        var workload = new InProcessTradingAllocationBenchmark();
        workload.batchSize = batchSize;
        workload.streamMode = streamMode;
        workload.clientIdMode = "ASCII";
        workload.oiLimitMode = "FIXED";
        workload.symbols = 1;
        workload.accountLanes = 2;
        try {
            workload.setup();
            assertThat(workload.continuousMatchStream()).isEqualTo(4096);
        } catch (RuntimeException | Error failure) {
            try { workload.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        workload.close();
    }
}
