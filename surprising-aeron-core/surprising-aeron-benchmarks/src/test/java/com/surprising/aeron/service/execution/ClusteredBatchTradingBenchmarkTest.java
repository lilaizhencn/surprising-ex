package com.surprising.aeron.service.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.surprising.product.api.ProductLine;

class ClusteredBatchTradingBenchmarkTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentBatchWindowsRestoreFundsAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4; workload.productLine = productLine;
        workload.batchSize = 20; workload.maxInFlight = 256; workload.realtime = true;
        try (workload) {
            workload.setup();
            workload.runIndependentBatchWindows();
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void independentWindowsRestoreFundsAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4; workload.productLine = productLine;
        workload.batchSize = 2; workload.maxInFlight = 256; workload.realtime = true;
        try (workload) {
            workload.setup();
            workload.runIndependentCommandWindows();
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void triggerPublicationAndBatchCompletionSurviveRepeatedUse(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        workload.realtime = true;
        try (workload) {
            workload.setup();
            var benchmark = new ClusteredBatchTradingBenchmark();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            benchmark.ownerTriggerAndBatchCompletion(workload, counters);
            benchmark.ownerTriggerAndBatchCompletion(workload, counters);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void multiFillSettlementAndRawEncodingPreserveFundsAndSnapshotsAcrossReuse(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4; workload.productLine = productLine;
        workload.batchSize = 20; workload.maxInFlight = 256; workload.realtime = true;
        try (workload) {
            workload.setup();
            workload.runMultiFillRoundTrip();
            workload.runMultiFillRoundTrip();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ownerBatchCompletionClosesFundsAndRestoresSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            new ClusteredBatchTradingBenchmark().ownerBatchCompletion(
                    workload, new ClusteredBatchTradingBenchmark.Counters());
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void amendTradesRestoreFundsAndPositionsAndRetainSnapshotAcrossReuse(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 2;
        workload.maxInFlight = 256;
        workload.realtime = true;
        try (workload) {
            workload.setup();
            workload.runAmendRoundTripTrades();
            workload.runAmendRoundTripTrades();
        }
    }

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
