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
                4L * LinearPerpetualBenchmarkSupport.MAX_BENCHMARK_SCALE);

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

    private record NamedScenario(
            String name,
            Supplier<LinearPerpetualBenchmarkSupport.Scenario> factory) {
    }
}
