package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.SurprisingClusteredService;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.surprising.product.api.ProductLine;

class ClusteredBatchTradingBenchmarkTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class, names = "SPOT",
            mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void batchRiskScanCommitsAndRestoresFunds(ProductLine line) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = line;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        workload.setup();
        try (workload) {
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().batchRiskScanBetweenOpenAndClose(workload, counters);
            assertThat(counters.terminalBusinessOperations).isGreaterThan(1024);
            assertThat(counters.terminalBusinessOperations).isEqualTo(counters.acceptedBusinessOperations);
            assertThat(counters.terminalCoreMessages).isEqualTo(counters.acceptedCoreMessages);
            assertThat(counters.terminalTrades).isEqualTo(512);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ProductLine.class, names = "SPOT",
            mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void riskScanBetweenTradesKeepsLaneOwnershipAndRestoresFunds(ProductLine line) throws Exception {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = line;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        workload.setup();
        try (workload) {
            var serviceField = workload.getClass().getDeclaredField("service");
            serviceField.setAccessible(true);
            var service = (SurprisingClusteredService) serviceField.get(workload);
            var runtime = service.state().runtimeState;
            var epoch = runtime.getClass().getDeclaredField("laneHandoffEpoch");
            epoch.setAccessible(true);
            long before = epoch.getLong(runtime);
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().riskScanBetweenOpenAndClose(workload, counters);
            assertThat(epoch.getLong(runtime)).isEqualTo(before);
            assertThat(counters.terminalBusinessOperations).isGreaterThan(1024);
            assertThat(counters.terminalBusinessOperations).isEqualTo(counters.acceptedBusinessOperations);
            assertThat(counters.terminalTrades).isEqualTo(512);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void batchMatchingCloseCancelsProtectiveTriggers(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().batchCloseWithPendingTriggers(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2048, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(1280, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(1280, counters.terminalTrades);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalItems);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void partialCloseRetainsTriggerUntilPositionIsFullyClosed(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().partialThenFullCloseWithTriggers(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(1792, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(768, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void matchingCloseCancelsPendingProtectiveTriggers(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().closePositionWithPendingTriggers(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(1280, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(512, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void finalizationFailurePreservesFundsAndSubsequentTrading(ProductLine productLine) throws Exception {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            var benchmark = new ClusteredBatchTradingBenchmark();
            benchmark.finalizationFailureAndTrading(workload, counters);
            benchmark.finalizationFailureAndTrading(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2050, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(2050, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(2, counters.rejectedBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void algoRollbackRestoresLaneBeforeNextUpdateAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            var benchmark = new ClusteredBatchTradingBenchmark();
            benchmark.algoLaneRollbackAndTrading(workload, counters);
            benchmark.algoLaneRollbackAndTrading(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2178, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(2178, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(2, counters.rejectedBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void algoLaneControlsPreserveTradingAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            var benchmark = new ClusteredBatchTradingBenchmark();
            benchmark.algoLaneAndTrading(workload, counters);
            benchmark.algoLaneAndTrading(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2176, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(2176, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void ownerTimerControlsPreserveTradingAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            var benchmark = new ClusteredBatchTradingBenchmark();
            benchmark.ownerTimerAndTrading(workload, counters);
            benchmark.ownerTimerAndTrading(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2304, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(2304, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void singleOrderPlanReusePreservesSettlementAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().singleOrderSettlementReuse(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(2048, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(2048, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(1024, counters.terminalTrades);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void changingBatchSizesPreservesSettlementAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 4;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().variableBatchSettlementReuse(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(7680, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(4096, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(5632, counters.terminalTrades);
            org.junit.jupiter.api.Assertions.assertEquals(counters.acceptedBusinessOperations,
                    counters.terminalBusinessOperations);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void fullWindowCommitPreservesFundsAndSnapshot(ProductLine productLine) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = productLine;
        workload.accountLanes = 4;
        workload.batchSize = 1;
        workload.maxInFlight = 256;
        try (workload) {
            workload.setup();
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().fullWindowCommit(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(512, counters.terminalBusinessOperations);
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
            var counters = new ClusteredBatchTradingBenchmark.Counters();
            new ClusteredBatchTradingBenchmark().repeatedAmendMetadata(workload, counters);
            org.junit.jupiter.api.Assertions.assertEquals(5120, counters.terminalBusinessOperations);
            org.junit.jupiter.api.Assertions.assertEquals(3072, counters.terminalCoreMessages);
            org.junit.jupiter.api.Assertions.assertEquals(2048, counters.terminalTrades);
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
