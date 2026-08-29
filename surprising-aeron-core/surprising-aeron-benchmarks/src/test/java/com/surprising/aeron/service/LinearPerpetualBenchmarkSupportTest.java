package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LinearPerpetualBenchmarkSupportTest {

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
                assertThat(scenario.laneOperations(1))
                        .as("one perpetual place batch must not cross the account lane once per item")
                        .isLessThan(scenario.acceptedOperations() / 2);
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
                assertThat(scenario.maxBacklog()).isEqualTo(8);
                assertThat(scenario.averageMatchingBacklog()).isGreaterThan(7.0);
                assertThat(scenario.fullWindowPercentage()).isGreaterThan(78.0);
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
