package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.change.LaneCommitDelta;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查 Lane 发布变化中只把终态订单交给 Owner 回调，OPEN 和删除不当作新终态；覆盖摘要扩容、清空复用及准备发布的入口，不启动真实集群。
 */
class LaneTerminalSummaryTest {
    /**
     * 四十个混合 OPEN 与 CANCELED 订单扩展发布摘要，检查回调只收到偶数终态；清空复用后仅一个 OPEN 不再回调旧终态。
     */
    @Test void mixedBatchRetainsOnlyTerminalsAfterSummaryGrowthAndReuse() {
        // 接收测试发布结果的独立冷运行时，不启动账户线程
        var state = new TradingRuntimeState();
        // 本测试反复准备发布和清空的 Lane 变化缓冲
        var changes = new LaneCommitDelta();
        // 偶数取消订单应触发的终态 ID 列表
        var expected = new ArrayList<Long>();
        for (/* 四十个测试订单的当前订单号 */ long id = 1; id <= 40; id++) {
            // 当前构造、按奇偶选择 OPEN 或 CANCELED 的订单
            var order = CoreStateTestFixtures.order(id, 7, 5, 10);
            if ((id & 1) == 0) {
                order = order.withStatus(CoreOrderStatus.CANCELED, 2);
                expected.add(id);
            }
            changes.putOrder(id, order);
        }
        changes.preparePublication();
        // 本轮终态回调实际收到的订单 ID 列表
        var actual = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0,
                (/* 终态回调提供的订单号，本用例只收集它 */ orderId, /* 终态回调提供的原用户，本用例不另作断言 */ userId, /* 终态回调提供的原客户端单号，本用例不另作断言 */ clientOrderId, /* 终态回调对应的提交序号，本用例不另作断言 */ sequence) -> actual.add(orderId), 9, null, null);
        assertThat(actual).containsExactlyElementsOf(expected);
        state.clearChangedKeys();
        changes.clear();
        changes.putOrder(41, CoreStateTestFixtures.order(41, 7, 5, 10));
        changes.preparePublication();
        actual.clear();
        changes.commitTerminalToOwner(state, 0,
                (/* 终态回调提供的订单号，本用例只收集它 */ orderId, /* 终态回调提供的原用户，本用例不另作断言 */ userId, /* 终态回调提供的原客户端单号，本用例不另作断言 */ clientOrderId, /* 终态回调对应的提交序号，本用例不另作断言 */ sequence) -> actual.add(orderId), 10, null, null);
        assertThat(actual).isEmpty();
    }
    /**
     * 同一订单依次 OPEN、FILLED、OPEN、CANCELED、REJECTED，逐轮发布清空并核对终态回调；最后真正删除不能再当作终态保留，正文应消失。
     */
    @Test void publishedOpenTerminalAndRemovedOrdersRemainCorrectAcrossReuse() {
        // 接收测试发布结果的独立冷运行时，不启动账户线程
        var state = new TradingRuntimeState();
        // 本测试反复准备发布和清空的 Lane 变化缓冲
        var changes = new LaneCommitDelta();
        // 本轮终态回调记录的 ID 列表，每轮使用前清空
        var retained = new ArrayList<Long>();
        // 固定终态接收回调，只记录订单 ID
        TradingRuntimeState.TerminalOrderSink sink =
                (/* 终态回调提供的订单号，本用例只收集它 */ orderId, /* 终态回调提供的原用户，本用例不另作断言 */ userId, /* 终态回调提供的原客户端单号，本用例不另作断言 */ clientOrderId, /* 终态回调对应的提交序号，本用例不另作断言 */ sequence) -> retained.add(orderId);
        // 作为各状态派生来源的订单十一 OPEN 值
        var open = CoreStateTestFixtures.order(11, 7, 5, 10);
        for (var /* 本轮把同一订单改成的 OPEN 或终态状态 */ status : new CoreOrderStatus[]{CoreOrderStatus.OPEN, CoreOrderStatus.FILLED,
                CoreOrderStatus.OPEN, CoreOrderStatus.CANCELED, CoreOrderStatus.REJECTED}) {
            changes.putOrder(11, open.withStatus(status, 2));
            changes.preparePublication();
            retained.clear();
            changes.commitTerminalToOwner(state, 0, sink, 9, null, null);
            if (status.terminal()) assertThat(retained).containsExactly(11L);
            else assertThat(retained).isEmpty();
            if (state.order(11) != null) {
                assertThat(state.order(11).status()).isEqualTo(status);
            }
            state.clearChangedKeys();
            changes.clear();
        }
        changes.putOrder(11, null);
        changes.preparePublication();
        retained.clear();
        changes.commitTerminalToOwner(state, 0, sink, 10, null, null);
        assertThat(retained).isEmpty();
        assertThat(state.order(11)).isNull();
    }

    /**
     * 准备一份取消订单变化后直接交 Owner，检查终态回调仍能收到该订单；若冷状态保留正文则状态应为取消。
     */
    @Test void preparedPublicationVisitsTerminalOrdersWithoutLaneSummary() {
        // 接收测试发布结果的独立冷运行时，不启动账户线程
        var state = new TradingRuntimeState();
        // 本测试反复准备发布和清空的 Lane 变化缓冲
        var changes = new LaneCommitDelta();
        changes.putOrder(11, CoreStateTestFixtures.order(11, 7, 5, 10).withStatus(CoreOrderStatus.CANCELED, 2));
        changes.preparePublication();
        // 本轮终态回调记录的 ID 列表，每轮使用前清空
        var retained = new ArrayList<Long>();
        changes.commitTerminalToOwner(state, 0,
                (/* 终态回调提供的订单号，本用例只收集它 */ orderId, /* 终态回调提供的原用户，本用例不另作断言 */ userId, /* 终态回调提供的原客户端单号，本用例不另作断言 */ clientOrderId, /* 终态回调对应的提交序号，本用例不另作断言 */ sequence) -> retained.add(orderId), 9, null, null);
        assertThat(retained).containsExactly(11L);
        if (state.order(11) != null) {
            assertThat(state.order(11).status()).isEqualTo(CoreOrderStatus.CANCELED);
        }
    }
}
