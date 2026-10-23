package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过直接发布完成序号检查 Owner 提示探测与清位协议，不执行实际撮合或资金结算。
 */
class MatchingNotificationProbeTest {
    /**
     * 预登记两条 Lane 各两千项预期，生产者每项等前一项确认，Owner 清提示后按 FIFO 消费并累计确认，最后要求计数完整且无剩余通知。
     */
    @Test
    void concurrentLaneSettlementsCannotBeLostWhileOwnerClearsReadyBits() throws Exception {
        // 本场景明确的 Matcher 与账户拓扑，队列容量足以容纳逐项确认窗口
        var topology = new LaneTopology(1, 0, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime(topology)) {
            // 每条 Lane 应发布的完成序号数量，固定两千
            int rounds = 2000;
            // Owner 已消费数量的原子确认数组，限制每生产者最多一个未确认项
            var consumed = new java.util.concurrent.atomic.AtomicIntegerArray(2);
            // 清理时通知各发布线程停止的原子开关
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            // 本例两条序号发布线程，finally 请求停止并逐个 join
            var workers = new Thread[2];
            for (/* 预登记每条 Lane 预期完成次数的循环下标 */ int i = 0; i < rounds; i++) runtime.expectMatcherSettlement(3);
            for (/* 当前生产、消费或确认的账户 Lane 下标 */ int lane = 0; lane < 2; lane++) {
                // 供发布线程捕获的固定账户 Lane 下标
                int id = lane;
                workers[lane] = Thread.ofPlatform().start(() -> {
                    for (/* 本轮正在发布或从原队列取出的正值完成序号 */ int seq = 1; seq <= rounds && !stop.get(); seq++) {
                        while (consumed.get(id) < seq - 1 && !stop.get()) Thread.onSpinWait();
                        if (stop.get()) return;
                        runtime.publishMatcherSettlementReady(id, seq);
                    }
                });
            }
            // Owner 已按 FIFO 取走的各 Lane 实际完成数
            int[] settlements = new int[2];
            // 消费全部序号的十秒单调纳秒截止时间
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            try {
                while (settlements[0] + settlements[1] < rounds * 2) {
                    assertThat(System.nanoTime() < deadline).as("ready notification lost").isTrue();
                    // 本轮清提示后确认确有待消费序号的 Lane 掩码
                    long ready = runtime.takeMatcherSettlementReadyLaneMask();
                    for (/* 当前生产、消费或确认的账户 Lane 下标 */ int lane = 0; lane < 2; lane++) if ((ready & (1L << lane)) != 0) {
                        // 本轮正在发布或从原队列取出的正值完成序号
                        long seq;
                        while ((seq = runtime.pollMatcherSettlementReady(lane)) != 0)
                            assertThat(seq).isEqualTo(++settlements[lane]);
                    }
                    for (/* 当前生产、消费或确认的账户 Lane 下标 */ int lane = 0; lane < 2; lane++) consumed.set(lane, settlements[lane]);
                }
            } finally {
                stop.set(true);
                for (var /* 当前等待退出的发布线程，或本例一次性发布两条通知的线程 */ worker : workers) worker.join(2000);
            }
            assertThat(settlements).containsExactly(rounds, rounds);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    /**
     * 按 Matcher 分片发布0、1及重复1，断言提示合并为两位，取走后无剩余通知。
     */
    @Test
    void batchAdmissionWakeBitsAreShardScopedAndCoalesce() {
        // 本场景明确的 Matcher 与账户拓扑，队列容量足以容纳逐项确认窗口
        var topology = new LaneTopology(2, 1, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime(topology)) {
            runtime.publishAccountAdmissionReadyShard(1);
            runtime.publishAccountAdmissionReadyShard(0);
            runtime.publishAccountAdmissionReadyShard(1);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.takeAccountAdmissionReadyShardMask()).isEqualTo(3);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    /**
     * 同一 Lane 预期两次完成，先直接消费第一次再发布第二次，确认旧提示清理不会遮住后来一次。
     */
    @Test
    void lateSettlementNotificationRemainsVisibleAfterFirstIsConsumed() {
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime()) {
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

    /**
     * 另一线程给两条 Lane 各发布42，多次只探测不能消费；逐 Lane 真正取走后通知才全部消失。
     */
    @Test
    void cursorProbeDoesNotConsumeTheLastLaneSettlementNotification() throws Exception {
        // 本场景明确的 Matcher 与账户拓扑，队列容量足以容纳逐项确认窗口
        var topology = new LaneTopology(1, 0, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime(topology)) {
            assertThat(runtime.hasMatchingNotifications()).isFalse();
            runtime.expectMatcherSettlement(3);
            // 当前等待退出的发布线程，或本例一次性发布两条通知的线程
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
