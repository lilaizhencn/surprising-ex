package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MatchingNotificationProbeTest {
    @Test
    void concurrentLanePublicationsCannotBeLostWhileOwnerClearsReadyBits() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            int rounds = 2000;
            var consumed = new java.util.concurrent.atomic.AtomicIntegerArray(2);
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var workers = new Thread[2];
            for (int i = 0; i < rounds; i++) {
                runtime.expectPlaceAdmission(0); runtime.expectPlaceAdmission(1);
                runtime.expectMatcherSettlement(3);
            }
            for (int lane = 0; lane < 2; lane++) {
                int id = lane;
                workers[lane] = Thread.ofPlatform().start(() -> {
                    for (int seq = 1; seq <= rounds && !stop.get(); seq++) {
                        while (consumed.get(id) < seq - 1 && !stop.get()) Thread.onSpinWait();
                        if (stop.get()) return;
                        runtime.publishPlaceAdmissionReady(id, seq);
                        runtime.publishMatcherSettlementReady(id, seq);
                    }
                });
            }
            int[] admissions = new int[2], settlements = new int[2];
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            try {
                while (admissions[0] + admissions[1] + settlements[0] + settlements[1] < rounds * 4) {
                    assertThat(System.nanoTime() < deadline).as("ready notification lost").isTrue();
                    long ready = runtime.takePlaceAdmissionReadyLaneMask();
                    for (int lane = 0; lane < 2; lane++) if ((ready & (1L << lane)) != 0) {
                        long seq;
                        while ((seq = runtime.pollPlaceAdmissionReady(lane)) != 0)
                            assertThat(seq).isEqualTo(++admissions[lane]);
                    }
                    ready = runtime.takeMatcherSettlementReadyLaneMask();
                    for (int lane = 0; lane < 2; lane++) if ((ready & (1L << lane)) != 0) {
                        long seq;
                        while ((seq = runtime.pollMatcherSettlementReady(lane)) != 0)
                            assertThat(seq).isEqualTo(++settlements[lane]);
                    }
                    for (int lane = 0; lane < 2; lane++) consumed.set(lane, Math.min(admissions[lane], settlements[lane]));
                }
                assertThat(admissions).containsExactly(rounds, rounds);
            } finally {
                stop.set(true);
                for (var worker : workers) worker.join(2000);
            }
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    @Test
    void observedReadyMaskDoesNotHideOtherLanesOrLaterNotifications() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            for (int n = 1; n <= 100; n++) {
                runtime.expectPlaceAdmission(0);
                runtime.expectPlaceAdmission(1);
                runtime.expectMatcherSettlement(3);
                runtime.publishPlaceAdmissionReady(1, n);
                assertThat(runtime.hasMatchingNotifications()).isTrue();
                runtime.publishPlaceAdmissionReady(0, n);
                assertThat(runtime.takePlaceAdmissionReadyLaneMask()).isEqualTo(3);
                assertThat(runtime.pollPlaceAdmissionReady(0)).isEqualTo(n);
                assertThat(runtime.pollPlaceAdmissionReady(1)).isEqualTo(n);
                assertThat(runtime.hasMatchingNotifications()).isFalse();
                runtime.publishMatcherSettlementReady(0, n);
                assertThat(runtime.hasMatchingNotifications()).isTrue();
                assertThat(runtime.pollMatcherSettlementReady(0)).isEqualTo(n);
                runtime.publishMatcherSettlementReady(1, n);
                assertThat(runtime.takeMatcherSettlementReadyLaneMask()).isEqualTo(2);
                assertThat(runtime.pollMatcherSettlementReady(1)).isEqualTo(n);
                assertThat(runtime.hasMatchingNotifications()).isFalse();
            }
        }
    }

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
