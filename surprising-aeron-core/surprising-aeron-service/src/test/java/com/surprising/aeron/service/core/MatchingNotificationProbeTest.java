package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过发布直达完成提示检查 Owner 提示探测与清位协议，不执行实际撮合或资金结算。
 */
class MatchingNotificationProbeTest {
    /**
     * 按 Matcher 分片发布0、1及重复1，断言提示合并为两位，取走后无剩余通知。
     */
    @Test
    void batchAdmissionWakeBitsAreShardScopedAndCoalesce() {
        // 本场景明确的 Matcher 与账户拓扑，队列容量足以容纳逐项确认窗口
        var topology = new LaneTopology(2, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime(topology)) {
            runtime.publishAccountAdmissionReadyShard(1);
            runtime.publishAccountAdmissionReadyShard(0);
            runtime.publishAccountAdmissionReadyShard(1);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.takeAccountAdmissionReadyShardMask()).isEqualTo(3);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    /** 清理一次直达提示后，同一 Lane 后续发布仍能唤醒 Owner。 */
    @Test
    void lateSettlementNotificationRemainsVisibleAfterFirstIsConsumed() {
        try (var runtime = new TradingAccountRuntime()) {
            assertThat(runtime.takeDirectMatcherSettlementReadyLaneMask()).isZero();
            runtime.publishDirectMatcherSettlementReady(0);
            assertThat(runtime.takeDirectMatcherSettlementReadyLaneMask()).isOne();
            assertThat(runtime.hasMatchingNotifications()).isFalse();
            runtime.publishDirectMatcherSettlementReady(0);
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.takeDirectMatcherSettlementReadyLaneMask()).isOne();
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }

    /**
     * 另一线程发布两条 Lane 的直达提示；重复探测不清位，消费后提示才消失。
     */
    @Test
    void cursorProbeDoesNotConsumeTheLastLaneSettlementNotification() throws Exception {
        // 本场景明确的 Matcher 与账户拓扑，队列容量足以容纳逐项确认窗口
        var topology = new LaneTopology(1, 2, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 仅用于通知协议的账户运行时，资源退出时关闭 */ var runtime = new TradingAccountRuntime(topology)) {
            assertThat(runtime.hasMatchingNotifications()).isFalse();
            // 当前等待退出的发布线程，或本例一次性发布两条通知的线程
            var worker = Thread.ofPlatform().start(() -> {
                runtime.publishDirectMatcherSettlementReady(0);
                runtime.publishDirectMatcherSettlementReady(1);
            });
            worker.join();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.hasMatchingNotifications()).isTrue();
            assertThat(runtime.takeDirectMatcherSettlementReadyLaneMask()).isEqualTo(3);
            assertThat(runtime.hasMatchingNotifications()).isFalse();
        }
    }
}
