package com.surprising.aeron.service.settlement;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.service.account.AccountLaneTestAccess;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.change.LaneCommitDelta;
import com.surprising.aeron.service.core.change.LaneCommitTestAccess;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.position.PositionRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 结算变化缓冲复用的回归用例。直接构造 Lane 值或变化列，检查成交累计值、终态清理、原语身份有效前缀、池化清空及持仓发布；不运行 Cluster 或测量整链路对象分配。
 */
class SettlementChangesReuseTest {
    /**
     * 同次包含部分成交和全部成交，核对终态清理及累计费用；清空变化后再次撤单，检查只发布新代订单并保留原累计费用。
     */
    @Test void mixedSettlementKeepsFinalCountersAndTerminalCleanupAcrossReuse() {
        try (/* 本用例独占并在退出时关闭的交易运行时。 */ var runtime = new TradingAccountRuntime()) {
            // 用户七所属账户 Lane 编号。
            int laneId = runtime.topology().accountLaneId(7);
            // 本用例持有的账户 Lane，或池轮转用例当前 Lane 数组下标。
            var lane = runtime.accountLane(laneId);
            // 本次创建或借出的结算变化缓冲。
            var changes = new MatcherSettlementChanges(runtime.topology().accountLaneCount());
            changes.ensureOrderCapacity(2, 1L << laneId);
            // 当前待准备、发布或清理的 Lane 实体及终态变化缓冲。
            var delta = changes.laneDeltas[laneId];
            // 结算收尾需要的运行时身份字典。
            var identities = new RuntimeIdentityRegistry();
            // 先部分成交、下一代再撤销的权威订单。
            var open = CoreStateTestFixtures.order(11, 7, 0, 10);
            // 本代全部成交并从账户表退休的权威订单。
            var filled = CoreStateTestFixtures.order(12, 7, 0, 10);
            // 部分成交订单的预留，先消耗四十再释放剩余六十。
            var openReservation = CoreStateTestFixtures.reservation(11, 7, 0, 100);
            // 全部成交订单的预留，本代消耗全部一百后移除。
            var filledReservation = CoreStateTestFixtures.reservation(12, 7, 0, 100);
            lane.putOrder(open);
            lane.putOrder(filled);
            AccountLaneTestAccess.reservations(lane).put(11, openReservation);
            AccountLaneTestAccess.reservations(lane).put(12, filledReservation);
            delta.putOrder(11, open);
            delta.putOrder(12, filled);
            delta.putReservation(11, openReservation);
            delta.putReservation(12, filledReservation);
            lane.updateOrderInPlace(11, 4, 6, 7, CoreOrderStatus.OPEN,
                    2, 1000, 2000);
            lane.updateOrderInPlace(12, 10, 0, 3, CoreOrderStatus.FILLED,
                    2, 1000, 2000);
            openReservation.setConsumedInPlace(Math.addExact(openReservation.consumedUnits(), 40));
            filledReservation.setConsumedInPlace(Math.addExact(filledReservation.consumedUnits(), 100));

            changes.prepareLaneTerminal(laneId, identities, lane, 1);
            assertThat(lane.order(12)).isNull();
            assertThat(lane.reservation(12)).isNull();
            assertThat(LaneCommitTestAccess.reservation(delta, 11).consumedUnits()).isEqualTo(40);
            assertThat(lane.reservation(11).reservedUnits()).isEqualTo(60);
            assertThat(delta.terminalOrderCount()).isEqualTo(1);
            assertThat(delta.terminalOrderId(0)).isEqualTo(12);
            delta.commitTerminalToOwner(runtime, laneId, null, null, null);
            assertThat(runtime.changedOrderValue(11).executedQuantitySteps()).isEqualTo(4);
            assertThat(runtime.changedOrderValue(11).cumulativeFeeUnits()).isEqualTo(7);
            assertThat(runtime.changedOrderValue(12).remainingQuantitySteps()).isZero();
            runtime.clearChangedKeys();
            delta.clear();

            lane.updateOrderInPlace(11, 4, 6, 0, CoreOrderStatus.CANCELED, 3, 1001, 2001);
            openReservation.releaseInPlace(60);
            delta.putOrder(11, open);
            delta.putReservation(11, openReservation);
            changes.prepareLaneTerminal(laneId, identities, lane, 2);
            assertThat(delta.terminalOrderCount()).isEqualTo(1);
            assertThat(delta.terminalOrderId(0)).isEqualTo(11);
            assertThat(lane.order(11)).isNull();
            assertThat(lane.reservation(11)).isNull();
            delta.commitTerminalToOwner(runtime, laneId, null, null, null);
            assertThat(runtime.changedOrderValue(11).cumulativeFeeUnits()).isEqualTo(7);
            assertThat(runtime.changedOrderValue(12)).isNull();
        }
    }

    /**
     * 在大、小及空终态前缀间反复复用变化缓冲，检查编号和用户有效数、clear 后订单为空，并反射核对客户身份引用列全部释放。
     */
    @Test void terminalReceiptReuseReadsOnlyTheNewCountAndReleasesReferences() throws Exception {
        try (/* 本用例独占并在退出时关闭的交易运行时。 */ var runtime = new TradingAccountRuntime()) {
            // 当前待准备、发布或清理的 Lane 实体及终态变化缓冲。
            var delta = new LaneCommitDelta();
            // 跨复用轮次单调增加的测试订单编号。
            long nextId = 1;
            for (int /* 当前轮需要构造的终态身份数量，包含零项轮次。 */ count : new int[]{65, 1, 0, 7, 0}) {
                // 本轮首个终态订单编号，用于核对有效前缀顺序。
                long firstId = nextId;
                for (/* 当前填充、核对终态项或检查 Lane 列的数组下标。 */ int i = 0; i < count; i++) {
                    // 本轮当前构造的终态订单编号。
                    long id = nextId++;
                    delta.orders().put(id, CoreStateTestFixtures.order(id, id + 100, 0, 1, true).snapshot());
                }
                delta.preparePublication();
                assertThat(delta.terminalOrderCount()).isEqualTo(count);
                for (/* 当前填充、核对终态项或检查 Lane 列的数组下标。 */ int i = 0; i < delta.terminalOrderCount(); i++) {
                    assertThat(delta.terminalOrderId(i)).isEqualTo(firstId + i);
                    assertThat(delta.terminalOrderUser(i)).isEqualTo(firstId + i + 100);
                }
                // 尚未提交的回执也要能清理，故障清理留下的引用和计数不能泄漏到下一代。
                delta.clear();
                assertThat(delta.terminalOrderCount()).isZero();
                assertThat(delta.orders().isEmpty()).isTrue();
                // 反射取得的终态客户身份引用数组字段。
                var field = LaneCommitDelta.class.getDeclaredField("terminalOrderClients");
                field.setAccessible(true);
                assertThat((Object[]) field.get(delta)).containsOnlyNulls();
            }
        }
    }

    /**
     * 以四个 Lane 轮转一百次借还池化变化，检查每次所有 Lane 的旧订单和 pending 完成计数均已清空；仅验证复用结果，不当成分配基准。
     */
    @Test void pooledChangesMoveBetweenLanesWithoutRetainingPreviousOrdersOrPendingCounts() {
        // 池化缓冲跨 Lane 轮转用例使用的四账户 Lane 拓扑。
        var topology = new LaneTopology(1, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 16, 16, 16);
        try (/* 本用例独占并在退出时关闭的交易运行时。 */ var runtime = new TradingAccountRuntime(topology)) {
            for (/* 当前借还变化缓冲的轮次。 */ int turn = 0; turn < 100; turn++) {
                // 本用例持有的账户 Lane，或池轮转用例当前 Lane 数组下标。
                int lane = turn % 4;
                // 本次创建或借出的结算变化缓冲。
                var changes = runtime.acquireMatcherSettlementChanges(1L << lane);
                for (/* 当前填充、核对终态项或检查 Lane 列的数组下标。 */ int i = 0; i < 4; i++) {
                    assertThat(changes.laneDeltas[i].orders().isEmpty()).isTrue();
                    assertThat(changes.laneDeltas[i].completedPending()).isZero();
                }
                changes.laneDeltas[lane].putOrder(turn + 1, CoreStateTestFixtures.order(turn + 1, 7, 0, 1, true));
                changes.laneDeltas[lane].completedPending(3);
                runtime.releaseMatcherSettlementChanges(changes);
            }
        }
    }
    /**
     * 直接把持仓初值、原位更新后值及删除标记写入变化缓冲并提交，检查 Owner 发布变化跟随新代；不代表真实成交持仓计算。
     */
    @Test void positionPublicationTracksChangedPositions() {
        try (/* 本用例独占并在退出时关闭的交易运行时。 */ var runtime = new TradingAccountRuntime()) {
            // 当前待准备、发布或清理的 Lane 实体及终态变化缓冲。
            var delta = new LaneCommitDelta();
            // 直接构造测试持仓使用的规范币对对象。
            var instrument = CoreStateTestFixtures.runtimeInstrument();
            // 用于检查初值、原位更新和删除发布的持仓运行时值。
            var source = new PositionRuntime(7, 0, 0,
                    com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                    com.surprising.aeron.protocol.CorePositionSide.NET,
                    instrument, 2, 100, 200, 0, 20);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, null, null);
            assertThat(runtime.positionChanges().get(1)).isEqualTo(source);
            runtime.clearChangedKeys();
            delta.clear();

            source.applyInPlace(instrument, 3, 100, 300, 5, 30);
            delta.putPosition(1, source);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, null, null);
            assertThat(runtime.positionChanges().get(1)).isEqualTo(source);
            runtime.clearChangedKeys();
            delta.clear();

            delta.putPosition(1, null);
            delta.preparePublication();
            delta.commitTerminalToOwner(runtime, 0, null, null, null);
            assertThat(runtime.positionChanges().toArray()).containsExactly(1);
        }
    }

}
