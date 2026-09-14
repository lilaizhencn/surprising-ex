package com.surprising.aeron.service.orchestration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountLaneCommitBenchmarkTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void metadataAndPlainCommitsSharePoolWithoutStalePublication(int lanes) {
        var state = new AccountLaneCommitBenchmark.CommitState();
        state.accountLanes = lanes;
        state.maxInFlight = 256;
        state.setUp();
        try {
            var benchmark = new AccountLaneCommitBenchmark();
            assertEquals(256, benchmark.metadataAndSequenceFanout(state));
            assertEquals(512, benchmark.sequenceLocalFanout(state));
            assertEquals(768, benchmark.metadataAndSequenceFanout(state));
        } finally { state.tearDown(); }
    }
}
