package com.surprising.aeron.service.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CorePerpetualEndToEndBenchmarkTest {

    @Test
    void coreReservationCoversFragmentedTakerFeesAcrossMakerDepths() {
        try {
            for (int makerDepth : List.of(1, 10, 100)) {
                CorePerpetualEndToEndBenchmark.BaselineResult result =
                        CorePerpetualEndToEndBenchmark.measure(1, 1, makerDepth);

                assertThat(result.makerDepth()).isEqualTo(makerDepth);
                assertThat(result.finalizedOrders()).isEqualTo(makerDepth + 1L);
                assertThat(result.matchedQuantity()).isEqualTo(makerDepth);
                assertThat(result.latenciesNanos()).hasSize(makerDepth + 1);
                assertThat(result.pendingMatching()).isZero();
                assertThat(result.terminalTombstones()).isGreaterThanOrEqualTo(makerDepth + 1);
            }
        } finally {
        }
    }
}
