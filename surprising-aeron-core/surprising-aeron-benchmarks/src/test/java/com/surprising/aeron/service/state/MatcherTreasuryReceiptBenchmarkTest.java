package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Functional fixture check only; no timing, snapshots or recovery. */
class MatcherTreasuryReceiptBenchmarkTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void matcherPublicationReusesSettlementSlotsAndPreservesTreasury(int lanes) {
        var fixture = new MatcherTreasuryReceiptBenchmark();
        fixture.laneCount = lanes;
        try {
            fixture.setup();
            for (long sequence = 1; sequence <= 4; sequence++)
                assertThat(fixture.completeFinancialReceipt()).isEqualTo(sequence);
        } finally { fixture.close(); }
    }
}
