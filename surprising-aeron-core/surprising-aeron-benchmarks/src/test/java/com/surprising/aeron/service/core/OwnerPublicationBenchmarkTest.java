package com.surprising.aeron.service.core;

import com.surprising.aeron.service.core.change.OwnerPublicationBenchmark;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OwnerPublicationBenchmarkTest {
    @Test
    void countBenchmarkAlternatesCapturedLaneCountsWithoutRetainingOldReceipts() {
        var benchmark = new OwnerPublicationBenchmark();
        benchmark.setup();
        try {
            for (int iteration = 0; iteration < 16; iteration++)
                assertThat(benchmark.publishAndReadPositionCount()).isEqualTo((iteration & 1) == 0 ? 1 : 0);
        } finally { benchmark.close(); }
    }
}
