package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CorePerpetualEndToEndBenchmarkTest {

    @ParameterizedTest @EnumSource(TradingExecutionMode.class)
    void coreReservationCoversFragmentedTakerFeesAcrossMakerDepths(TradingExecutionMode mode) {
        String previous = System.setProperty("surprising.aeron.execution-mode", mode.name());
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
            if (previous == null) System.clearProperty("surprising.aeron.execution-mode");
            else System.setProperty("surprising.aeron.execution-mode", previous);
        }
    }
}
