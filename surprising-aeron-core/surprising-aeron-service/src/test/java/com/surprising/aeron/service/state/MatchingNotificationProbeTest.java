package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MatchingNotificationProbeTest {
    @Test
    void cursorProbeDoesNotConsumeAdmissionOrTheLastLaneSettlementNotification() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            assertThat(runtime.hasMatchingNotifications()).isFalse();
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
