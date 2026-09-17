package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MatchingNotificationProbeTest {
    @Test
    void concurrentLaneSettlementsCannotBeLostWhileOwnerClearsReadyBits() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            int rounds = 2000;
            var consumed = new java.util.concurrent.atomic.AtomicIntegerArray(2);
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var workers = new Thread[2];
            for (int i = 0; i < rounds; i++) runtime.expectMatcherSettlement(3);
            for (int lane = 0; lane < 2; lane++) {
                int id = lane;
                workers[lane] = Thread.ofPlatform().start(() -> {
                    for (int seq = 1; seq <= rounds && !stop.get(); seq++) {
                        while (consumed.get(id) < seq - 1 && !stop.get()) Thread.onSpinWait();
                        if (stop.get()) return;
                        runtime.publishMatcherSettlementReady(id, seq);
                    }
                });
            }
            int[] settlements = new int[2];
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            try {
                while (settlements[0] + settlements[1] < rounds * 2) {
                    assertThat(System.nanoTime() < deadline).as("ready notification lost").isTrue();
                    long ready = runtime.takeMatcherSettlementReadyLaneMask();
                    for (int lane = 0; lane < 2; lane++) if ((ready & (1L << lane)) != 0) {
                        long seq;
                        while ((seq = runtime.pollMatcherSettlementReady(lane)) != 0)
                            assertThat(seq).isEqualTo(++settlements[lane]);
                    }
                    for (int lane = 0; lane < 2; lane++) consumed.set(lane, settlements[lane]);
                }
            } finally {
                stop.set(true);
                for (var worker : workers) worker.join(2000);
            }
            assertThat(settlements).containsExactly(rounds, rounds);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    @Test
    void batchAdmissionWakeBitsAreShardScopedAndCoalesce() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 2, 0, 1, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            runtime.publishPlaceBatchAdmissionReady(1);
            runtime.publishPlaceBatchAdmissionReady(0);
            runtime.publishPlaceBatchAdmissionReady(1);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.takePlaceBatchAdmissionReadyShardMask()).isEqualTo(3);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    @Test
    void lateSettlementNotificationRemainsVisibleAfterFirstIsConsumed() {
        try (var runtime = new TradingRuntimeState()) {
            runtime.expectMatcherSettlement(1);
            runtime.expectMatcherSettlement(1);
            assertThat(runtime.takeMatcherSettlementReadyLaneMask()).isZero();
            runtime.publishMatcherSettlementReady(0, 1);
            assertThat(runtime.pollMatcherSettlementReady(0)).isEqualTo(1);
            assertThat(runtime.takeMatcherSettlementReadyLaneMask()).isZero();
            runtime.publishMatcherSettlementReady(0, 2);
            assertThat(runtime.takeMatcherSettlementReadyLaneMask()).isOne();
            assertThat(runtime.pollMatcherSettlementReady(0)).isEqualTo(2);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    @Test
    void cursorProbeDoesNotConsumeTheLastLaneSettlementNotification() throws Exception {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 2,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (var runtime = new TradingRuntimeState(topology)) {
            assertThat(runtime.hasMatchingNotifications()).isFalse();
            runtime.expectMatcherSettlement(3);
            var worker = Thread.ofPlatform().start(() -> {
                runtime.publishMatcherSettlementReady(0, 42);
                runtime.publishMatcherSettlementReady(1, 42);
            });
            worker.join();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.pollMatcherSettlementReady(0)).isEqualTo(42);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.pollMatcherSettlementReady(1)).isEqualTo(42);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }
}
