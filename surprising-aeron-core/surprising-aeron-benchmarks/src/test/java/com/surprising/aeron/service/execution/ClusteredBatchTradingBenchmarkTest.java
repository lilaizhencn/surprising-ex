package com.surprising.aeron.service.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.surprising.product.api.ProductLine;

class ClusteredBatchTradingBenchmarkTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void replayBackpressureRetainsOrdersFundsAndSnapshot(ProductLine productLine) throws Exception {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 1;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().replicatedIngressBackpressure(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(128, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void sequentialBatchHandoffPreservesTerminalFundsAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 1;
        workload.maxInFlight = 256;
        workload.realtime = true;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().sequentialBatchContextHandoff(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(512, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(counters.acceptedBusinessOperations,
                    counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(counters.acceptedCoreMessages,
                    counters.terminalCoreMessages);
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void tradingWithoutInterleavedQueriesRetainsTerminalAndFinancialChecks(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 2;
        workload.maxInFlight = 256;
        workload.settlementSpinLimit = 256;
        workload.interleavedMetrics = false;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            var benchmark = new ClusteredBatchTradingBenchmark();
            benchmark.decodedBatchAdmissionAndSettlement(workload, counters);
            benchmark.decodedBatchAdmissionAndSettlement(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(7168, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(counters.acceptedBusinessOperations,
                    counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(4096, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(2048, counters.terminalTrades);
            org.junit.jupiter.api.Assertions.assertEquals(0, counters.queries);
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void priceScopedPrefixScenarioKeepsFundsAndSnapshotCorrect(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.accountLanes = 4;
        workload.productLine = productLine;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        workload.realtime = true;
        try (workload) {
            workload.setup();
            new ClusteredBatchTradingBenchmark().priceScopedBatchWindows(workload,
                    new ClusteredBatchTradingBenchmark.Counters());
        }
    }
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
