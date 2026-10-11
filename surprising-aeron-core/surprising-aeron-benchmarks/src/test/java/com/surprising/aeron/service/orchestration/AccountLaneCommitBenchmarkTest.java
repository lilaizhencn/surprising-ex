package com.surprising.aeron.service.orchestration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountLaneCommitBenchmarkTest {
    @ParameterizedTest
    @CsvSource({"1,BUSY_SPIN", "2,BUSY_SPIN", "4,BUSY_SPIN", "1,BLOCKING", "2,BLOCKING", "4,BLOCKING"})
    void metadataAndPlainCommitsSharePoolWithoutStalePublication(int lanes, String strategy) {
        var state = new AccountLaneCommitBenchmark.CommitState();
        state.accountLanes = lanes;
        state.maxInFlight = 256;
        state.waitStrategy = strategy;
        state.setUp();
        try {
            var benchmark = new AccountLaneCommitBenchmark();
            assertEquals(256, benchmark.metadataAndSequenceFanout(state));
            assertEquals(512, benchmark.sequenceLocalFanout(state));
            assertEquals(768, benchmark.metadataAndSequenceFanout(state));
        } finally { state.tearDown(); }
    }
}
