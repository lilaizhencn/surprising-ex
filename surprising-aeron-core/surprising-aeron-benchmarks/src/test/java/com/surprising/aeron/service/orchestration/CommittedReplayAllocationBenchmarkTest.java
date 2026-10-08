package com.surprising.aeron.service.orchestration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommittedReplayAllocationBenchmarkTest {
    @Test void fillReplayAndSnapshotRemainConsistentAcrossReuse() {
        var benchmark = new CommittedReplayAllocationBenchmark();
        benchmark.setup();
        try {
            long previous = 0;
            for (int i = 0; i < 256; i++) {
                long current = benchmark.makerTakerFill();
                assertThat(current).isGreaterThan(previous);
                previous = current;
            }
        } finally { benchmark.close(); }
    }
}
