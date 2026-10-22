package com.surprising.aeron.service.account;

import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionCloseCapacity;
import com.surprising.aeron.service.position.PositionRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平仓容量检查的账户 Lane 测试，区分有效已提交挂单与其他用户、合约、持仓腿、方向、终态、预占或排除订单，并验证最新只减仓订单优先进入冲突撤单列表。
 */
class LaneCloseCapacityTest {
    /**
     * 十步持仓由两笔只减仓和一笔普通卖单占用九步；干扰订单不计容量，新增量冲突时先选择提交更晚的只减仓挂单，普通订单只占量不进入冲突候选。
     */
    @Test
    void capacityUsesOnlyLiveCommittedOrdersInTheSamePositionAndCancelsNewestFirst() {
        // 用户 7 测试持仓所属的账户 Lane，直接在此准备持仓及订单索引。
        var lane = new AccountLaneState(0, 256);
        // 所有测试订单和目标持仓使用的合约配置。
        var instrument = CoreStateTestFixtures.runtimeInstrument();
        lane.positions.put(99, new PositionRuntime(7, 3, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 10, 100, 1000, 0, 100));
        lane.putOrder(order(201, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 3, 10));
        lane.putOrder(order(202, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 4, 20));
        lane.putOrder(order(203, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, false, 2, 30));
        // 下面分别构造其他合约、其他用户、其他持仓腿、反方向、已撤、未提交预占和显式排除订单，都不能占本次平仓容量。
        lane.putOrder(order(204, 7, 4, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(205, 8, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(206, 7, 3, CorePositionSide.LONG, CoreOrderSide.SELL, true, 100, 40));
        lane.putOrder(order(207, 7, 3, CorePositionSide.NET, CoreOrderSide.BUY, true, 100, 40));
        lane.putOrder(order(208, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40)
                .withStatus(CoreOrderStatus.CANCELED, 41));
        lane.putOrder(order(209, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 40));
        lane.reservations.put(209, CoreStateTestFixtures.reservation(209, 7, 0, 100));
        lane.markPendingReservation(209, 1);
        lane.putOrder(order(210, 7, 3, CorePositionSide.NET, CoreOrderSide.SELL, true, 100, 50));

        // 过滤干扰订单并排除订单 210 后的平仓容量结果，应剩一步且冲突按新到旧排序。
        var capacity = PositionCloseCapacity.inspectLane(
                lane, 7, 3, 99, CorePositionSide.NET, CoreOrderSide.SELL, 210);
        assertThat(capacity.requestedQuantitySteps()).isEqualTo(9);
        assertThat(capacity.availableQuantitySteps()).isEqualTo(1);
        assertThat(capacity.conflictsFor(5)).containsExactly(202L);
        assertThat(capacity.conflictsFor(6)).containsExactly(202L, 201L);
        assertThat(capacity.conflictsFor(1)).isEmpty();
    }

    /**
     * 按指定身份、方向及数量创建全仓限价 GTC 测试订单，再补固定提交时间和可比较的新旧日志位置。
     * @param id 测试订单编号
     * @param user 测试订单所属用户编号
     * @param symbol 测试合约整数编号
     * @param position 订单对应的净持仓或双向腿身份
     * @param side 订单买入或卖出方向
     * @param reduceOnly 是否为可参与冲突撤销的只减仓订单
     * @param quantity 该订单初始未成交数量步数
     * @param clusterPosition 用来比较订单新旧的集群日志位置
     */
    private static OrderRuntime order(long id, long user, int symbol, CorePositionSide position,
            CoreOrderSide side, boolean reduceOnly, long quantity, long clusterPosition) {
        return new OrderRuntime(id, user, symbol, CoreStateTestFixtures.runtimeInstrument(), side, 100,
                reduceOnly, CoreMarginMode.CROSS, position, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                0, 0, quantity, 0, quantity, false).withCommitMetadata(1000, clusterPosition);
    }
}
