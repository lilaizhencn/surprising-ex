package com.surprising.aeron.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.surprising.product.api.ProductLine;

class ClusteredBatchTradingBenchmarkTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "LINEAR_PERPETUAL"})
    void allRejectedContinuationsReachServiceEgressAndKeepSnapshotRecoverable(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            workload.runRejectedContinuations();
            workload.runRejectedContinuations();
            workload.run();
        }
    }
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT", "LINEAR_PERPETUAL"})
    void serviceBatchCycleClosesFundsAndRestoresSnapshotAcrossReuse(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            workload.run();
            workload.run();
        }
    }
}
