package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MatchingNotificationProbeTest {
    @Test
    void lateSecondNotificationRemainsExpectedAfterFirstIsConsumed() {
        try (var runtime = new TradingRuntimeState()) {
            runtime.expectPlaceAdmission(0); runtime.expectPlaceAdmission(0);
            assertThat(runtime.takePlaceAdmissionReadyLaneMask()).isZero();
            runtime.publishPlaceAdmissionReady(0, 1);
            assertThat(runtime.pollPlaceAdmissionReady(0)).isEqualTo(1);
            assertThat(runtime.takePlaceAdmissionReadyLaneMask()).isZero();
            runtime.publishPlaceAdmissionReady(0, 2);
            assertThat(runtime.takePlaceAdmissionReadyLaneMask()).isOne();
            assertThat(runtime.pollPlaceAdmissionReady(0)).isEqualTo(2);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }
    @Test
    void cursorProbeDoesNotConsumeAdmissionOrTheLastLaneSettlementNotification() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            assertThat(runtime.hasMatchingNotifications()).isFalse();
            runtime.expectPlaceAdmission(0);
            runtime.expectMatcherSettlement(3);
            // Publish after the owner's empty probe, as a worker does after completing a stage.
            var worker = Thread.ofPlatform().start(() -> {
                runtime.publishPlaceAdmissionReady(0, 41);
                runtime.publishMatcherSettlementReady(0, 42);
                runtime.publishMatcherSettlementReady(1, 42);
            });
            worker.join();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.pollPlaceAdmissionReady(0)).isEqualTo(41);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.pollMatcherSettlementReady(0)).isEqualTo(42);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.pollMatcherSettlementReady(1)).isEqualTo(42);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }
}
