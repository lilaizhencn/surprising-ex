package com.surprising.aeron.service.core;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContinuousOwnerBenchmarkTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void smallerCommandsAfterBatchReuseLeaveNoTerminalOrdersOrReservations(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            // Only this regression varies batch size within a trial; JMH parameters remain fixed per fork.
            for (int batchSize : new int[]{20, 1, 20}) {
                workload.batchSize = batchSize;
                workload.placeCancelWithoutTimers(counters);
            }
            assertEquals(512L * 41, counters.terminalBusinessOperations);
            assertEquals(1536, counters.terminalCoreMessages);
            assertEquals(counters.acceptedBusinessOperations, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void pairedAccountsReleaseDependencyFencesAndRestoreFunds(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.batchSize = 20;
            workload.accountPattern = "PAIRED";
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            workload.placeCancelWithoutTimers(counters);
            assertEquals(10240, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void immutableBatchResponsesSurviveDeferredTransportAndSnapshot(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.batchSize = 20;
            workload.setup();
            workload.verifyDeferredResponseHandoff();
        }
    }
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
