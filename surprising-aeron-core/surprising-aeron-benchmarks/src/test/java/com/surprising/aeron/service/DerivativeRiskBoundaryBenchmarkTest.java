package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class DerivativeRiskBoundaryBenchmarkTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void validatesMatchedAccountsRiskBoundaryAndSnapshot(ProductLine line) {
        var state = new DerivativeRiskBoundaryBenchmark.BoundaryState();
        state.productLine = line;
        state.maxInFlight = 256;
        state.accountLanes = 4;
        state.setup();
        try {
            var counters = new DerivativeRiskBoundaryBenchmark.Counters();
            new DerivativeRiskBoundaryBenchmark().riskAndAdl(state, counters);
            assertThat(counters.acceptedBusinessOperations).isGreaterThanOrEqualTo(256);
            assertThat(counters.terminalBusinessOperations).isEqualTo(counters.acceptedBusinessOperations);
            assertThat(counters.terminalCoreMessages).isEqualTo(counters.acceptedCoreMessages);
            assertThat(counters.unfinishedBusinessOperations).isZero();
            assertThat(counters.unfinishedCoreMessages).isZero();
            assertThat(counters.queries).isEqualTo(line == ProductLine.OPTION ? 0 : 1);
        } catch (Throwable failure) {
            try { state.teardown(); } catch (Throwable teardown) { failure.addSuppressed(teardown); }
            throw failure;
        }
        {
            state.teardown();
        }
    }
}
