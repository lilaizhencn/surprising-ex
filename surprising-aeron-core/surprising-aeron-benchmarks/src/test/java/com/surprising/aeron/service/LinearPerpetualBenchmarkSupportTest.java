package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessageType;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LinearPerpetualBenchmarkSupportTest {

    @Test
    void longRunningDriverRefreshesPricesEvenWhenRiskWorkIsStillInFlight() throws Exception {
        var template = LinearPerpetualMixedWorkload.template(4, 32, 2);
        try (var harness = LinearPerpetualBenchmarkSupport.Harness.restore(template.snapshot())) {
            var sequencesField = harness.getClass().getDeclaredField("sequences");
            sequencesField.setAccessible(true);
            Object sequences = sequencesField.get(harness);
            var clockField = sequences.getClass().getDeclaredField("clusterPosition");
            clockField.setAccessible(true);
            clockField.setLong(sequences, Math.addExact(clockField.getLong(sequences), 6_000L * 1_024));
            long now = harness.nextCommandTimestamp();
            long before = harness.executedMessages();
            harness.refreshMarkPricesIfDue(template.symbols());
            assertThat(harness.executedMessages() - before).isEqualTo(template.symbols().size());
            for (String symbol : template.symbols()) {
                assertThat(harness.state().runtimeMarkPrice(symbol).generatedAtEpochMillis()).isEqualTo(now);
            }
            long refreshed = harness.executedMessages();
            harness.refreshMarkPricesIfDue(template.symbols());
            assertThat(harness.executedMessages()).isEqualTo(refreshed);
            assertThat(harness.acceptedMessages()).isEqualTo(harness.terminalMessages());
            var snapshot = harness.snapshotTemplate(4);
            try (var restored = LinearPerpetualBenchmarkSupport.Harness.restore(snapshot)) {
                assertThat(restored.state().tradingState().businessStateHash())
                        .isEqualTo(harness.state().tradingState().businessStateHash());
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(8)
    void deepFillsRouteOnlyRelatedEventsAndConserveFundsAt256InFlight() {
        var template = LinearPerpetualBenchmarkSupport.deepFillBurstTemplate(4, 8);
        try (var scenario = LinearPerpetualBenchmarkSupport.deepFillBurst256(template, 8)) {
            scenario.run();
            scenario.verify();
            assertThat(scenario.maxBacklog()).isEqualTo(256);
            assertThat(scenario.terminalTrades()).isEqualTo(2048);
        }
    }

    @Test
    void scalarProgressCountersMatchMaterializedStateWithoutFreezingProjection() {
        try (var harness = LinearPerpetualBenchmarkSupport.Harness.create(4)) {
            harness.adjust(100_001L, 100);
            long before = harness.state().snapshotProjectionFreezeCount();
            assertThat(harness.state().activeOrderCount()).isZero();
            assertThat(harness.state().positionCount()).isZero();
            assertThat(harness.state().triggerOrderCount()).isZero();
            assertThat(harness.state().incompleteFundingCount()).isZero();
            assertThat(harness.state().incompleteRiskScanCount()).isZero();
            assertThat(harness.state().snapshotProjectionFreezeCount()).isEqualTo(before);
        }
    }

    @Test
    void orderContinuationsSustainTheRequiredTwoHundredFiftySixInFlightWindow() {
        var template = LinearPerpetualBenchmarkSupport.orderContinuationTemplate(4);
        for (int round = 0; round < 64; round++) {
            for (var scenario : List.of(
                    LinearPerpetualBenchmarkSupport.cancelBurst256(template),
                    LinearPerpetualBenchmarkSupport.amendBurst256(template))) {
                try (scenario) {
                    assertThatCode(scenario::run).doesNotThrowAnyException();
                    scenario.verify();
                    assertThat(scenario.operations()).isEqualTo(256);
                    assertThat(scenario.maxBacklog()).isEqualTo(256);
                }
            }
        }
    }

    private final String originalAccountLanes = System.getProperty("surprising.aeron.account-lanes");

    @AfterEach
    void restoreAccountLaneProperty() {
        if (originalAccountLanes == null) System.clearProperty("surprising.aeron.account-lanes");
        else System.setProperty("surprising.aeron.account-lanes", originalAccountLanes);
    }

    @Test
    void largeScaleSetupKeepsMarkPriceInsideFreshnessWindow() {
        long firstTimestamp = LinearPerpetualBenchmarkSupport.benchmarkTimestamp(1);
        long lastTimestamp = LinearPerpetualBenchmarkSupport.benchmarkTimestamp(
                1_000_000L);

        assertThat(lastTimestamp - firstTimestamp).isLessThanOrEqualTo(5_000);
    }

    @Test
    void commandConstructionDoesNotRequestImmutableProjection() {
        try (var harness = LinearPerpetualBenchmarkSupport.Harness.create(4)) {
            harness.adjust(100_001L, 100);
            long freezesBefore = harness.state().snapshotProjectionFreezeCount();

            harness.command(CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY,
                    100_001L, new byte[0]);

            assertThat(harness.state().snapshotProjectionFreezeCount()).isEqualTo(freezesBefore);
        }
    }

    @Test
    void soakLeakQualificationUsesAllRealPointsAndResistsOneTransientOutlier() {
        var result = LinearPerpetualScaleSoakMain.exerciseLeakSlopeSmallScale();

        assertThat(result.samples()).isEqualTo(4);
        assertThat(result.liveSetSlope()).isEqualTo(100.0);
        assertThat(result.oldGenerationSlope()).isEqualTo(100.0);
        assertThat(result.directSlope()).isEqualTo(100.0);
        assertThat(result.mappedSlope()).isEqualTo(100.0);
        assertThat(result.pass()).isTrue();
    }

    @Test
    void allLinearPerpetualScenariosCompleteOnFourAccountLanes() {
        int lanes = 4;
        var matchingTemplate = LinearPerpetualBenchmarkSupport.multiLaneMatchingTemplate(lanes, 8);
        var riskTemplate = LinearPerpetualBenchmarkSupport.riskScanTemplate(lanes, 8);
        var recoveryTemplate = LinearPerpetualBenchmarkSupport.recoveryTemplate(lanes, 8);

        List<NamedScenario> scenarios = List.of(
                new NamedScenario("limitOrderPlacement",
                        () -> LinearPerpetualBenchmarkSupport.limitOrderPlacement(lanes)),
                new NamedScenario("fullTakerFill", () -> LinearPerpetualBenchmarkSupport.fullTakerFill(lanes)),
                new NamedScenario("cancelRestingOrder",
                        () -> LinearPerpetualBenchmarkSupport.cancelRestingOrder(lanes)),
                new NamedScenario("amendRestingOrder",
                        () -> LinearPerpetualBenchmarkSupport.amendRestingOrder(lanes)),
                new NamedScenario("partialFill", () -> LinearPerpetualBenchmarkSupport.partialFill(lanes)),
                new NamedScenario("multiLaneMatching",
                        () -> LinearPerpetualBenchmarkSupport.multiLaneMatching(matchingTemplate, 8)),
                new NamedScenario("riskScan", () -> LinearPerpetualBenchmarkSupport.riskScan(riskTemplate)),
                new NamedScenario("liquidationExecution",
                        () -> LinearPerpetualBenchmarkSupport.liquidationExecution(lanes)),
                new NamedScenario("snapshotRecovery",
                        () -> LinearPerpetualBenchmarkSupport.snapshotRecovery(recoveryTemplate)));

        for (NamedScenario named : scenarios) {
            assertThatCode(() -> {
                try (var scenario = named.factory().get()) {
                    assertThat(scenario.run()).isNotZero();
                    scenario.verify();
                }
            }).as(named.name()).doesNotThrowAnyException();
        }
    }

    @Test
    void liquidationBenchmarksExerciseTheFullTwoHundredFiftySixItemPaths() {
        int lanes = 4;
        List<NamedScenario> scenarios = List.of(
                new NamedScenario("multiUserLiquidationPlanning",
                        () -> LinearPerpetualBenchmarkSupport.riskScan(
                                LinearPerpetualBenchmarkSupport.riskScanTemplate(lanes, 256))),
                new NamedScenario("liquidationManyOrderCancellation",
                        () -> LinearPerpetualBenchmarkSupport.liquidationBatchExecution(lanes, 1, 256)),
                new NamedScenario("liquidationBurst256",
                        () -> LinearPerpetualBenchmarkSupport.liquidationBatchExecution(lanes, 256, 0)),
                new NamedScenario("insuranceShortfallAllocation",
                        () -> LinearPerpetualBenchmarkSupport.insuranceShortfall(lanes, 256, false)),
                new NamedScenario("insuranceToAdl",
                        () -> LinearPerpetualBenchmarkSupport.insuranceShortfall(lanes, 256, true)));

        for (NamedScenario named : scenarios) {
            assertThatCode(() -> {
                try (var scenario = named.factory().get()) {
                    assertThat(scenario.run()).isNotZero();
                    scenario.verify();
                }
            }).as(named.name()).doesNotThrowAnyException();
        }
    }

    @Test
    void mixedProductionWorkloadPreservesFundsAndCompletesLifecycleWork() {
        var template = LinearPerpetualMixedWorkload.template(4, 32, 4);

        assertThatCode(() -> {
            try (var scenario = LinearPerpetualMixedWorkload.scenario(template)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.operations()).isGreaterThan(100);
                assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                assertThat(scenario.acceptedCoreMessages()).isEqualTo(scenario.terminalCoreMessages());
                assertThat(scenario.acceptedOperations()).isGreaterThan(scenario.acceptedCoreMessages());
                assertThat(scenario.maxBacklog()).isGreaterThan(1);
                assertThat(scenario.terminalTradingOperations()).isPositive();
                assertThat(scenario.terminalLifecycleOperations()).isPositive();
                assertThat(scenario.terminalTradingOperations() + scenario.terminalLifecycleOperations())
                        .isEqualTo(scenario.terminalOperations());
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void productionWorkloadInterleavesBoundedHeavyWorkWithTrading() {
        var template = LinearPerpetualMixedWorkload.template(4, 512, 4);

        assertThatCode(() -> {
            try (var scenario = LinearPerpetualMixedWorkload.productionScenario(template, 4, 20)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                assertThat(scenario.maxBacklog()).isGreaterThan(1);
                assertThat(scenario.terminalTradingOperations()).isPositive();
                assertThat(scenario.terminalLifecycleOperations()).isPositive();
                assertThat(scenario.laneOperations(1))
                        .as("each immutable matcher fact must visit each of its at most two account lanes once")
                        .isPositive()
                        .isLessThanOrEqualTo(Math.multiplyExact(scenario.acceptedOperations(), 2));
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void scaleWorkloadRestoresDensePopulationAndPreservesFunds() {
        var config = LinearPerpetualScaleConfig.scale(32, 32, 5, 10,
                LinearPerpetualTrafficProfile.UNIFORM, 16);
        var template = LinearPerpetualMixedWorkload.template(4, 64, config);

        assertThatCode(() -> {
            try (var scenario = LinearPerpetualMixedWorkload.scaleScenario(template, 1, 4)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.positions()).isGreaterThan(64);
                assertThat(scenario.activeOrders()).isGreaterThan(64);
                assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                scenario.verify();
                var snapshot = scenario.captureSnapshot();
                try (var restored = LinearPerpetualBenchmarkSupport.Harness.restore(snapshot)) {
                    assertThat(restored.state().tradingState().businessStateHash())
                            .isEqualTo(snapshot.businessStateHash());
                }
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void scaleWorkloadSupportsHundredsOfListedMostlyIdleSymbols() {
        var config = LinearPerpetualScaleConfig.scale(512, 4, 1, 1,
                LinearPerpetualTrafficProfile.MOSTLY_IDLE, 4);

        assertThatCode(() -> {
            var template = LinearPerpetualMixedWorkload.template(4, 32, config);
            assertThat(template.listedSymbols()).hasSize(512);
            assertThat(template.symbols()).hasSize(4);
            try (var scenario = LinearPerpetualMixedWorkload.scaleScenario(template, 1, 4)) {
                assertThat(scenario.run()).isNotZero();
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void markPriceStormPlacesTriggersOnlyForSymbolsThatReceivedTradingPositions() {
        var config = LinearPerpetualScaleConfig.scale(64, 64, 1, 3,
                LinearPerpetualTrafficProfile.MARK_PRICE_STORM, 8);

        assertThatCode(() -> {
            var template = LinearPerpetualMixedWorkload.template(4, 128, config);
            try (var scenario = LinearPerpetualMixedWorkload.scaleScenario(template, 1, 4)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.run()).isNotZero();
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void saturatedWorkloadMaintainsOneSharedCoreWindowAndPreservesFunds() {
        var config = LinearPerpetualScaleConfig.scale(16, 16, 1, 1,
                LinearPerpetualTrafficProfile.UNIFORM, 16);
        var template = LinearPerpetualMixedWorkload.template(4, 32, config);

        assertThatCode(() -> {
            try (var scenario = LinearPerpetualSaturationWorkload.scenario(template, 8, 4_096)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.operations()).isEqualTo(4_096);
                assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                assertThat(scenario.acceptedCoreMessages()).isEqualTo(scenario.terminalCoreMessages());
                assertThat(scenario.terminalTrades()).isEqualTo(2_048);
                assertThat(scenario.laneOperations(0))
                        .as("one Lane-owned admission command per accepted PLACE")
                        .isEqualTo(4_096);
                assertThat(scenario.laneOperations(1))
                        .as("one immutable matcher fact per touched account Lane")
                        .isGreaterThan(4_096)
                        .isLessThanOrEqualTo(8_192);
                assertThat(scenario.laneOperations())
                        .isEqualTo(Math.addExact(scenario.laneOperations(0), scenario.laneOperations(1)))
                        .isGreaterThan(8_192)
                        .isLessThanOrEqualTo(12_288);
                assertThat(scenario.maxBacklog()).isEqualTo(8);
                assertThat(scenario.averageMatchingBacklog()).isPositive();
                assertThat(scenario.fullWindowPercentage()).isPositive();
                assertThat(scenario.refillOperations()).isGreaterThan(3_800);
                assertThat(scenario.producerStarvationPercentage()).isZero();
                assertThat(scenario.completedLatencySamples()).isEqualTo(4_096);
                assertThat(scenario.p99LatencyNanos()).isPositive();
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }

    private record NamedScenario(
            String name,
            Supplier<LinearPerpetualBenchmarkSupport.Scenario> factory) {
    }
}
