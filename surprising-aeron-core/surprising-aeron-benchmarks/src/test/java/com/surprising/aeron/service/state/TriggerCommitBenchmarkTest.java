package com.surprising.aeron.service.state;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TriggerCommitBenchmarkTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void repeatedPendingFixturesPerformRealCancellations(int lanes) {
        var benchmark = new TriggerCommitBenchmark();
        benchmark.accountLanes = lanes;
        benchmark.setup();
        try {
            for (int i = 1; i <= 3; i++) {
                benchmark.preparePendingFixture();
                assertEquals(i, benchmark.cancelWithSequenceCommit());
            }
        } finally { benchmark.close(); }
    }
}
