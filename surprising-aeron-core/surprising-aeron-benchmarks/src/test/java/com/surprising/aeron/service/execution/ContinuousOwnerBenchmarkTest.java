package com.surprising.aeron.service.execution;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContinuousOwnerBenchmarkTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void snapshotIncludesOutstandingOrdersAndRoleChangeKeepsOwnerOwnership(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            workload.verifyPendingSnapshotBoundary();
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void continuousOwnerCompletesTailAndRestoresFundsWithoutTimers(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            workload.placeCancelWithoutTimers(counters);
            assertEquals(512, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }
}
