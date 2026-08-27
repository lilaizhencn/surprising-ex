package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpotMixedWorkloadTest {

    private final String originalAccountLanes = System.getProperty("surprising.aeron.account-lanes");

    @AfterEach
    void restoreAccountLaneProperty() {
        if (originalAccountLanes == null) System.clearProperty("surprising.aeron.account-lanes");
        else System.setProperty("surprising.aeron.account-lanes", originalAccountLanes);
    }

    @Test
    void multiPairFourLaneWorkloadCompletesAndPreservesEveryAsset() {
        var template = SpotMixedWorkload.template(4, 32, 4);

        assertThatCode(() -> {
            try (var scenario = SpotMixedWorkload.scenario(template, 2, 8)) {
                assertThat(scenario.run()).isNotZero();
                assertThat(scenario.operations()).isGreaterThan(100);
                assertThat(scenario.acceptedOperations()).isEqualTo(scenario.terminalOperations());
                assertThat(scenario.acceptedCoreMessages()).isEqualTo(scenario.terminalCoreMessages());
                assertThat(scenario.acceptedOperations()).isGreaterThan(scenario.acceptedCoreMessages());
                assertThat(scenario.maxBacklog()).isGreaterThan(0);
                scenario.verify();
            }
        }).doesNotThrowAnyException();
    }
}
