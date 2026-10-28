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
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证准入统计的分组切换、原位成交、极值合约编号和空单元缓存生命周期；复用断言针对具体对象与缓存上限，不是分配采样。
 */
class LaneAdmissionReuseTest {
    /**
     * 组合普通、只减仓和不同保证金模式订单，执行部分成交、替换、删除及另一用户新组，逐步核对数量与模式条数。
     */
    @Test void replacementPartialFillAndGroupReuseKeepExactPendingAndMarginCounts() {
        // 已绑定当前线程、登记两个用户的测试 Lane。
        var lane = lane();
        lane.putOrder(order(11, 7, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 10));
        lane.putOrder(order(12, 7, 0, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, false, 6));
        lane.putOrder(order(13, 7, 0, CoreOrderSide.SELL, CorePositionSide.LONG, CoreMarginMode.CROSS, true, 4));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.ISOLATED, 10, 0, 1);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 6, 4, 1);
        lane.updateOrderInPlace(11, 4, 6, 0, CoreOrderStatus.OPEN, 2, 0, 0);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 6, 0, 1);
        lane.putOrder(order(12, 7, 0, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.CROSS, false, 3));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 3, 4, 0);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 3, 4, 2);
        lane.removeOrder(11); lane.removeOrder(12); lane.removeOrder(13);
        lane.putOrder(order(21, 8, 1, CoreOrderSide.SELL, CorePositionSide.SHORT, CoreMarginMode.ISOLATED, false, 9));
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
        summary(lane, 8, 1, CorePositionSide.SHORT, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 9, 0, 1);
        summary(lane, 8, 1, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.CROSS, 0, 0, 0);
    }

    /**
     * 保留最大合约编号上的订单，让另一用户在零合约编号反复完成并删除订单，检查两组统计互不覆盖。
     */
    @Test void zeroAndLargestSymbolIdsStayDistinctAcrossUsersAndRepeatedTerminalCycles() {
        // 已绑定当前线程、登记两个用户的测试 Lane。
        var lane = lane();
        lane.putOrder(order(1, 7, Integer.MAX_VALUE, CoreOrderSide.SELL, CorePositionSide.NET, CoreMarginMode.ISOLATED, false, 17));
        for (/* 零合约编号订单从创建到终态删除的周转轮次。 */ int round = 0; round < 1_000; round++) {
            // 本轮使用的新订单编号，避免反复覆盖同一订单键。
            long id = 1_000_000L + round;
            lane.putOrder(order(id, 8, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 11));
            summary(lane, 8, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 11, 0, 1);
            lane.updateOrderInPlace(id, 11, 0, 0, CoreOrderStatus.FILLED, 2, 0, 0);
            lane.removeOrder(id);
            summary(lane, 8, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
            summary(lane, 7, Integer.MAX_VALUE, CorePositionSide.NET, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 17, 0, 1);
        }
        assertThat(lane.orders.size()).isOne();
    }

    /**
     * 一次创建六百合约组再全部删除，检查空单元缓存不超过二百五十六，并在删除用户时释放空分组表。
     */
    @Test void emptyCellCacheIsBoundedAndUserRemovalReleasesTheRetainedTable() throws Exception {
        // 已绑定当前线程、登记两个用户的测试 Lane。
        var lane = lane();
        for (/* 本轮创建或删除准入汇总的稠密合约编号。 */ int symbol = 0; symbol < 600; symbol++)
            lane.putOrder(order(symbol + 1, 7, symbol, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 1));
        for (/* 本轮创建或删除准入汇总的稠密合约编号。 */ int symbol = 0; symbol < 600; symbol++) lane.removeOrder(symbol + 1);
        // Lane 内真实的准入索引对象，供反射检查空单元池和用户表。
        Object index = field(lane, "admissionOrderIndex");
        // 准入索引真实的用户到合约汇总表。
        var users = (org.agrona.collections.Long2ObjectHashMap<?>) field(index, "summariesByUser");
        assertThat(users.size()).isOne();
        assertThat((java.util.ArrayDeque<?>) field(index, "emptyAggregates")).hasSizeLessThanOrEqualTo(256);
        lane.removeUser(7);
        assertThat(users.size()).isZero();
        lane.registerUser(7);
        lane.putOrder(order(999, 7, 0, CoreOrderSide.SELL, CorePositionSide.SHORT, CoreMarginMode.ISOLATED, true, 3));
        summary(lane, 7, 0, CorePositionSide.SHORT, CoreOrderSide.SELL, CoreMarginMode.ISOLATED, 0, 3, 1);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 0, 0, 0);
    }

    /**
     * 先删除用户成员仍保留其最后订单统计，最后订单离开后才释放用户准入表。
     */
    @Test void removingUserBeforeItsLastOrderKeepsTheIndexUntilThatOrderLeaves() throws Exception {
        // 已绑定当前线程、登记两个用户的测试 Lane。
        var lane = lane();
        lane.putOrder(order(11, 7, 0, CoreOrderSide.BUY, CorePositionSide.NET, CoreMarginMode.CROSS, false, 10));
        lane.removeUser(7);
        summary(lane, 7, 0, CorePositionSide.NET, CoreOrderSide.BUY, CoreMarginMode.CROSS, 10, 0, 1);
        lane.removeOrder(11);
        // 准入索引真实的用户到合约汇总表。
        var users = (org.agrona.collections.Long2ObjectHashMap<?>) field(field(lane, "admissionOrderIndex"), "summariesByUser");
        assertThat(users.size()).isZero();
    }

    /**
     * 创建由当前线程持有且已登记用户七和八的测试 Lane。
     */
    private static AccountLaneState lane() {
        // 已绑定当前线程、登记两个用户的测试 Lane。
        var lane = new AccountLaneState(0, 256);
        lane.assertOwner(); lane.registerUser(7); lane.registerUser(8);
        return lane;
    }

    /**
     * 按指定用户、合约及分组创建全部未成交的测试限价单。
     * @param id 测试订单编号
     * @param user 测试订单或汇总所属的用户编号
     * @param symbol 稠密合约编号，可包含零和最大 int 值
     * @param side 订单或查询的买卖方向
     * @param position 订单或查询的持仓腿
     * @param margin 订单或查询的保证金模式
     * @param reduce 测试订单是否只减仓
     * @param quantity 测试订单初始数量步数
     */
    private static OrderRuntime order(long id, long user, int symbol, CoreOrderSide side,
            CorePositionSide position, CoreMarginMode margin, boolean reduce, long quantity) {
        return new OrderRuntime(id, user, symbol, CoreStateTestFixtures.runtimeInstrument(), side, 100,
                reduce, margin, position, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 0, 0,
                quantity, 0, quantity, false);
    }

    /**
     * 立即读取复用准入槽并核对普通剩余量、只减仓量及保证金模式订单条数。
     * @param lane 要检查准入索引的测试 Lane
     * @param user 测试订单或汇总所属的用户编号
     * @param symbol 稠密合约编号，可包含零和最大 int 值
     * @param position 订单或查询的持仓腿
     * @param side 订单或查询的买卖方向
     * @param margin 订单或查询的保证金模式
     * @param pending 预期非只减仓剩余数量合计
     * @param reduce 预期只减仓剩余数量合计
     * @param count 预期持仓腿及保证金模式订单条数
     */
    private static void summary(AccountLaneState lane, long user, int symbol, CorePositionSide position,
            CoreOrderSide side, CoreMarginMode margin, long pending, long reduce, int count) {
        // 本次 inspect 的复用结果，下一次调用前立即完成三个断言。
        var result = lane.admissionOrderIndex(symbol).inspect(user, "1", position, side, margin);
        assertThat(result.pendingQuantity()).isEqualTo(pending);
        assertThat(result.reduceOnlyQuantity()).isEqualTo(reduce);
        assertThat(result.marginModeCount()).isEqualTo(count);
    }

    /**
     * 反射读取指定实例私有字段，仅用于检查内部容器与缓存生命周期。
     * @param owner 要反射读取字段的对象实例
     * @param name 需要读取的字段名称
     */
    private static Object field(Object owner, String name) throws Exception {
        // 按字段名称找到并开放读取的反射 Field。
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(owner);
    }
}
