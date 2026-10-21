package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.CoreStateTestFixtures;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对活跃订单变化与准入汇总同步，覆盖数量、方向、持仓腿、保证金模式以及容器复用；测试直接操作 Lane，不代替真实撮合结算。
 */
class LaneAdmissionAggregateTest {
    /**
     * 长时间增删不同编号的订单和预留，反射核对数组未换、原条目仍在，并显式重建哈希确认周转后业务内容相同。
     */
    @Test void orderAndReservationChurnRetainsTablesAndUnrelatedLiveState() throws Exception {
        // 本场景由当前线程持有的独立账户 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 全程保留的基准订单，用于检查无关订单周转不改变它。
        var held = CoreStateTestFixtures.order(1, 7, 3, 50);
        // 与基准订单对应、全程保留的预留值。
        var heldReservation = CoreStateTestFixtures.reservation(1, 7, 3, 50);
        lane.putOrder(held);
        lane.reservations.put(1, heldReservation);
        lane.rebuildLocalHashes();
        // 首次显式重建的 Lane 状态哈希，周转完后应恢复到相同值。
        long originalHash = lane.localStateHash();
        // Agrona 订单及预留对象表底层 values 数组的反射字段。
        var values = Long2ObjectHashMap.class.getDeclaredField("values");
        values.setAccessible(true);
        // 订单表在测试开始时的底层对象数组。
        Object orderStorage = values.get(lane.orders);
        // 预留表在测试开始时的底层对象数组。
        Object reservationStorage = values.get(lane.reservations);
        for (/* 本轮短暂加入随后删除的订单和预留编号。 */ long id = 2; id < 20_000; id++) {
            lane.putOrder(CoreStateTestFixtures.order(id, 7, 3, 50));
            lane.reservations.put(id, CoreStateTestFixtures.reservation(id, 7, 3, 50));
            lane.removeOrder(id);
            lane.reservations.remove(id);
        }
        assertThat(values.get(lane.orders)).isSameAs(orderStorage);
        assertThat(values.get(lane.reservations)).isSameAs(reservationStorage);
        assertThat(lane.orders.size()).isOne();
        assertThat(lane.reservations.size()).isOne();
        assertThat(lane.orders.get(1)).isSameAs(held);
        assertThat(lane.reservations.get(1)).isSameAs(heldReservation);
        lane.rebuildLocalHashes();
        assertThat(lane.localStateHash()).isEqualTo(originalHash);
    }

    /**
     * 遍历全部持仓腿、方向、保证金模式和只减仓组合，检查改元数据及部分成交不更换汇总；直接写回原订单模拟恢复，没有调用完整命令回滚器。
     */
    @Test
    void metadataPartialFillAndRollbackKeepSameAggregateForEveryScope() throws Exception {
        for (var /* 当前枚举到的净仓、多仓或空仓持仓腿。 */ position : CorePositionSide.values()) for (var /* 当前枚举到的买入或卖出方向。 */ side : CoreOrderSide.values())
            for (var /* 当前枚举到的全仓或逐仓模式。 */ margin : CoreMarginMode.values()) for (boolean /* 当前测试订单是否只减仓。 */ reduce : new boolean[]{false, true}) {
                // 本场景由当前线程持有的独立账户 Lane。
                var lane = new AccountLaneState(0, 256);
                lane.bindOwner();
                // 当前组合的原始订单，稍后直接写回它来模拟恢复数量。
                var order = order(side, position, margin, reduce);
                lane.putOrder(order);
                // 已经选择合约三的复用准入汇总视图。
                var index = lane.admissionOrderIndex(3);
                // 用于读取索引内部用户分组表的反射字段。
                var field = index.getClass().getDeclaredField("summariesByUser");
                field.setAccessible(true);
                // 准入索引中用户编号到合约分组表的实际容器。
                var users = (Long2ObjectHashMap<?>) field.get(index);
                // 用户七按合约编号保存汇总单元的实际容器。
                var symbols = (Int2ObjectHashMap<?>) users.get(7);
                // 用户七合约三的原汇总对象，更新数量和元数据时应保持身份。
                Object aggregate = symbols.get(3);
                lane.putOrder(order.withCommitMetadata(10, 20));
                lane.putOrder(order.withExecution(60, 40, CoreOrderStatus.OPEN, 2));
                assertThat(users.get(7)).isSameAs(symbols);
                assertThat(symbols.get(3)).isSameAs(aggregate);
                // 这次 inspect 返回的复用汇总槽，只在下一次覆盖前断言。
                var summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(reduce ? summary.reduceOnlyQuantity() : summary.pendingQuantity()).isEqualTo(40);
                assertThat(summary.marginModeCount()).isEqualTo(1);
                lane.putOrder(order);
                assertThat(symbols.get(3)).isSameAs(aggregate);
                summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(reduce ? summary.reduceOnlyQuantity() : summary.pendingQuantity()).isEqualTo(100);
                lane.putOrder(order.withStatus(CoreOrderStatus.CANCELED, 3));
                assertThat(users.get(7)).isNull();
                summary = index.inspect(7, "SYM", position, side, margin);
                assertThat(summary.pendingQuantity()).isZero();
                assertThat(summary.reduceOnlyQuantity()).isZero();
                assertThat(summary.marginModeCount()).isZero();
            }
    }

    /**
     * 同一分组放两笔订单，更新并结束其中一笔，要求数量差额和模式条数保留另一笔贡献。
     */
    @Test
    void quantityUpdatePreservesOtherOrdersInTheSameAggregate() {
        // 本场景由当前线程持有的独立账户 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        // 同分组中准备部分成交并结束的第一笔订单。
        var first = order(CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false);
        lane.putOrder(first);
        lane.putOrder(CoreStateTestFixtures.order(2, 7, 3, 50));
        lane.putOrder(first.withExecution(60, 40, CoreOrderStatus.OPEN, 2));
        // 已经选择合约三的复用准入汇总视图。
        var index = lane.admissionOrderIndex(3);
        // 这次 inspect 返回的复用汇总槽，只在下一次覆盖前断言。
        var summary = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(summary.pendingQuantity()).isEqualTo(90);
        assertThat(summary.marginModeCount()).isEqualTo(2);
        lane.putOrder(first.withStatus(CoreOrderStatus.CANCELED, 3));
        summary = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(summary.pendingQuantity()).isEqualTo(50);
        assertThat(summary.marginModeCount()).isEqualTo(1);
    }

    /**
     * 用同编号新订单改变方向、模式和只减仓标记，核对旧分组清空、新分组正确。
     */
    @Test
    void changedSideAndMarginMoveBetweenScopes() {
        // 本场景由当前线程持有的独立账户 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.bindOwner();
        lane.putOrder(order(CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false));
        lane.putOrder(order(CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, true));
        // 已经选择合约三的复用准入汇总视图。
        var index = lane.admissionOrderIndex(3);
        // 旧买入全仓分组的查询结果，在下一次 inspect 之前完成断言。
        var before = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS);
        assertThat(before.pendingQuantity()).isZero();
        assertThat(before.marginModeCount()).isZero();
        // 替换后的卖出逐仓只减仓分组查询结果。
        var after = index.inspect(7, "SYM", CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED);
        assertThat(after.reduceOnlyQuantity()).isEqualTo(100);
        assertThat(after.marginModeCount()).isEqualTo(1);
    }

    /**
     * 创建用户七、合约三、总量一百的指定分组限价单，初始全部未成交。
     * @param side 测试订单买卖方向
     * @param position 测试订单持仓腿
     * @param margin 测试订单保证金模式
     * @param reduce 测试订单是否只减仓
     */
    private static OrderRuntime order(CoreOrderSide side, CorePositionSide position, CoreMarginMode margin, boolean reduce) {
        return new OrderRuntime(1, 7, 3, CoreStateTestFixtures.runtimeInstrument(), side, 100, reduce, margin, position,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0, 100, 0, 100, false);
    }
}
