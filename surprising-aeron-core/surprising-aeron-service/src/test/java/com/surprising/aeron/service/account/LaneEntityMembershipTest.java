package com.surprising.aeron.service.account;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 核对 Lane 的订单、预留、持仓和客户号成员索引随增删及回滚同步变化；反射检查只证明指定容器或底层数组身份保持，不代表完整交易链路零分配。
 */
class LaneEntityMembershipTest {
    /**
     * 反复增加和移除大编号订单及预留，检查长期成员、原语数组与返回副本隔离，移除最后一项后仍保留空成员容器供复用。
     */
    @Test
    void liveOrderAndReservationMembershipSurvivesUniqueIdChurnWithoutReplacingArrays() throws Exception {
        try (/* 本场景独立的交易运行时，测试结束时自动关闭工作资源。 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putUser(new UserRuntime(8));
            state.putOrder(CoreStateTestFixtures.order(1, 7, 1, 2));
            state.putReservation(CoreStateTestFixtures.reservation(1, 7, 3, 13));
            state.putOrder(CoreStateTestFixtures.order(Long.MAX_VALUE, 8, 1, 2));
            state.putReservation(CoreStateTestFixtures.reservation(Long.MAX_VALUE, 8, 3, 17));
            // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
            var lane = state.onLane(7L, /* onLane 回调收到的所属 Lane，这里原样返回以便断言。 */ value -> value);
            // 用户七的活跃订单编号集合，核对原成员与回滚后的成员。
            var orders = lane.activeOrderIdsByUser.get(7);
            // 用户七的预留编号集合或导出副本，分别用于底层复用和成员断言。
            var reservations = lane.reservationIdsByUser.get(7);
            // 首轮容量增长后保留下来的订单成员数组，用于后续身份比较。
            // 首轮容量增长后保留下来的预留成员数组。
            Object orderStorage = null, reservationStorage = null;
            state.clearChangedKeys();
            for (/* 订单与预留编号周转的当前轮次。 */ int round = 0; round < 1_001; round++) {
                // 使用大订单编号，避免把 Eclipse 的小整数位图路径当作生产订单编号路径。
                // 本轮新建二十个订单的起始编号。
                long first = 1_000_000L + round * 20;
                for (/* 当前加入、移除或构造待回滚成员的订单编号。 */ long id = first; id < first + 20; id++) {
                    state.putOrder(CoreStateTestFixtures.order(id, 7, 1, 2));
                    state.putReservation(CoreStateTestFixtures.reservation(id, 7, 3, 5));
                }
                if (round == 0) {
                    orderStorage = membershipStorage(orders);
                    reservationStorage = membershipStorage(reservations);
                }
                assertThat(orders.size()).isEqualTo(21);
                assertThat(reservations.size()).isEqualTo(21);
                for (/* 当前加入、移除或构造待回滚成员的订单编号。 */ long id = first; id < first + 20; id++) {
                    com.surprising.aeron.service.core.CoreStateTestFixtures.deleteOrder(state, id);
                    CoreStateTestFixtures.deleteReservation(state, id, 7);
                }
                assertThat(lane.activeOrderIdsByUser.get(7)).isSameAs(orders);
                assertThat(lane.reservationIdsByUser.get(7)).isSameAs(reservations);
                assertThat(membershipStorage(orders)).isSameAs(orderStorage);
                assertThat(membershipStorage(reservations)).isSameAs(reservationStorage);
                assertThat(orders.size()).isOne();
                assertThat(reservations.size()).isOne();
                assertThat(orders.contains(1)).isTrue();
                assertThat(reservations.contains(1)).isTrue();
                assertThat(state.reservationIdsForUser(8)).containsExactly(Long.MAX_VALUE);
                state.clearChangedKeys();
            }
            // 运行时返回的预留成员副本，修改它应不影响权威集合。
            var detached = state.reservationIdsForUser(7);
            detached[0] = 99;
            assertThat(reservations.contains(1)).isTrue();
            com.surprising.aeron.service.core.CoreStateTestFixtures.deleteOrder(state, 1);
            CoreStateTestFixtures.deleteReservation(state, 1, 7);
            assertThat(orders.size()).isZero();
            assertThat(reservations.size()).isZero();
        }
    }

    /**
     * 捕获命令前状态后撤旧订单、删旧预留并新增一笔，再运行真实 Lane 回滚，核对原成员恢复且新增实体消失。
     */
    @Test
    void rollbackRestoresRemovedMembershipAndDropsNewOrderAndReservation() {
        try (/* 本场景独立的交易运行时，测试结束时自动关闭工作资源。 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            for (long /* 当前加入、移除或构造待回滚成员的订单编号。 */ id : new long[]{1, 2}) {
                state.putOrder(CoreStateTestFixtures.order(id, 7, 1, 2));
                state.putReservation(CoreStateTestFixtures.reservation(id, 7, 3, 5));
            }
            // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
            var lane = state.onLane(7L, /* onLane 回调收到的所属 Lane，这里原样返回以便断言。 */ value -> value);
            state.clearChangedKeys();
            state.onLane(7L, /* 执行待回滚修改的 Lane 回调参数，本测试通过外层 state 发起操作，不读取此参数。 */ current -> {
                state.replaceOrder(state.order(1).withStatus(
                        com.surprising.aeron.model.CoreOrderStatus.CANCELED, 3, -1, -1));
                CoreStateTestFixtures.deleteReservation(state, 1, 7);
                state.putOrder(CoreStateTestFixtures.order(3, 7, 1, 2));
                state.putReservation(CoreStateTestFixtures.reservation(3, 7, 3, 5));
                return null;
            });
            state.accountRollback().restoreLane(lane);
            // 用户七的活跃订单编号集合，核对原成员与回滚后的成员。
            var orders = lane.activeOrderIdsByUser.get(7);
            // 用户七的预留编号集合或导出副本，分别用于底层复用和成员断言。
            var reservations = state.reservationIdsForUser(7);
            assertThat(orders.size()).isEqualTo(2);
            assertThat(orders.contains(1)).isTrue();
            assertThat(orders.contains(2)).isTrue();
            assertThat(orders.contains(3)).isFalse();
            assertThat(reservations).containsExactlyInAnyOrder(1, 2);
            assertThat(lane.orders.get(3)).isNull();
            assertThat(lane.reservations.get(3)).isNull();
        }
    }

    /**
     * 通过反射取集合实例中的非静态 long 数组，用对象身份比较底层存储是否被替换。
     * @param set 要取得底层数组的成员集合对象
     */
    private static Object membershipStorage(Object set) throws Exception {
        for (var /* 反射遍历到的实例字段，只选择非静态 long 数组。 */ field : set.getClass().getDeclaredFields()) {
            if (field.getType() == long[].class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field.get(set);
            }
        }
        throw new AssertionError("membership has no primitive backing array");
    }

    /**
     * 客户号映射删空后继续加入新映射，要求复用同一原语表。
     */
    @Test
    void clientOrderIndexReusesItsPrimitiveTableAfterLastAliasRemoval() {
        try (/* 本场景独立的交易运行时，测试结束时自动关闭工作资源。 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
            var lane = state.onLane(7L, /* onLane 回调收到的所属 Lane，这里原样返回以便断言。 */ value -> value);
            TradingAccountRuntime.putClientOrderIndex(lane, 7, 91, 11);
            // 用户七客户身份键到订单编号的原语映射表。
            var clients = lane.clientOrderIndex.get(7);

            assertThat(TradingAccountRuntime.removeClientOrderIndex(lane, 7, 91)).isEqualTo(11);
            assertThat(lane.clientOrderIndex.get(7)).isSameAs(clients);
            assertThat(clients.size()).isZero();

            TradingAccountRuntime.putClientOrderIndex(lane, 7, 92, 12);
            assertThat(lane.clientOrderIndex.get(7)).isSameAs(clients);
        }
    }

    /**
     * 反复周转待完成序号和客户号映射，检查底层数组、零客户键、长期存活条目及待完成金额不丢失。
     */
    @Test
    void pendingSequenceAndClientTablesSurviveUniqueIdChurnWithoutLosingLiveEntries() throws Exception {
        // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
        var lane = new AccountLaneState(0, 256);
        lane.assertOwner();
        // 始终保留、金额为十三的预留权威记录。
        var held = CoreStateTestFixtures.reservation(1, 7, 3, 13);
        lane.reservations.put(1, held);
        lane.markPendingReservation(1, 2);
        TradingAccountRuntime.putClientOrderIndex(lane, 7, 0, 1);
        // 用户七客户身份键到订单编号的原语映射表。
        var clients = lane.clientOrderIndex.get(7);
        // Agrona long 键值表底层 entries 数组的反射字段。
        var entries = org.agrona.collections.Long2LongHashMap.class.getDeclaredField("entries");
        entries.setAccessible(true);
        // 开始周转前待完成序号表的底层数组。
        Object pendingStorage = entries.get(lane.pendingReservationSequences);
        // 首轮允许增长后客户号表的底层数组，后续应持续复用。
        Object clientStorage = null;
        for (/* 订单与预留编号周转的当前轮次。 */ int round = 0; round < 1_001; round++) {
            // 本轮新建二十个订单的起始编号。
            long first = 2L + round * 20;
            for (/* 当前加入、移除或构造待回滚成员的订单编号。 */ long id = first; id < first + 20; id++) {
                lane.reservations.put(id, CoreStateTestFixtures.reservation(id, 7, 3, 5));
                lane.markPendingReservation(id, 3L + round);
                TradingAccountRuntime.putClientOrderIndex(lane, 7, id, id);
            }
            if (round == 0) clientStorage = entries.get(clients); // 允许首轮按同时存活条数扩容，之后检查同一数组持续复用。
            assertThat(lane.pendingReservationCount()).isEqualTo(21);
            assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(113);
            for (/* 当前加入、移除或构造待回滚成员的订单编号。 */ long id = first; id < first + 20; id++) {
                assertThat(lane.pendingReservationSequence(id)).isEqualTo(3L + round);
                lane.completePendingReservation(id, 3L + round);
                lane.reservations.remove(id);
                assertThat(TradingAccountRuntime.removeClientOrderIndex(lane, 7, id)).isEqualTo(id);
                assertThat(lane.pendingReservationSequence(id)).isZero();
            }
            assertThat(entries.get(lane.pendingReservationSequences)).isSameAs(pendingStorage);
            assertThat(entries.get(clients)).isSameAs(clientStorage);
            assertThat(clients.size()).isOne();
            assertThat(clients.get(0L)).isEqualTo(1);
            assertThat(lane.pendingReservationSequence(1)).isEqualTo(2);
            assertThat(lane.pendingReservationCount()).isOne();
            assertThat(lane.pendingReservedUnits(7, 3)).isEqualTo(13);
            assertThat(lane.reservations.get(1)).isSameAs(held);
        }
        lane.completePendingReservation(1, 2);
        assertThat(lane.pendingReservationCount()).isZero();
        assertThat(lane.pendingReservedUnits(7, 3)).isZero();
    }

    /**
     * 捕获三种客户映射前值后执行改指向、删除和新增，再回滚，核对正向映射及订单别名反向索引一起恢复。
     */
    @Test
    void rollbackRestoresReassignedRemovedAndNewClientAliases() {
        try (/* 本场景独立的交易运行时，测试结束时自动关闭工作资源。 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
            var lane = state.onLane(7L, /* onLane 回调收到的所属 Lane，这里原样返回以便断言。 */ value -> value);
            TradingAccountRuntime.putClientOrderIndex(lane, 7, 91, 11);
            TradingAccountRuntime.putClientOrderIndex(lane, 7, 92, 12);
            state.clearChangedKeys();
            state.accountRollback().captureClientOrderBefore(7, 91);
            state.accountRollback().captureClientOrderBefore(7, 92);
            state.accountRollback().captureClientOrderBefore(7, 93);
            TradingAccountRuntime.putClientOrderIndex(lane, 7, 91, 99);
            TradingAccountRuntime.removeClientOrderIndex(lane, 7, 92);
            TradingAccountRuntime.putClientOrderIndex(lane, 7, 93, 99);

            state.accountRollback().restoreLane(lane);

            assertThat(state.orderIdByClientValue(7, 91)).isEqualTo(11);
            assertThat(state.orderIdByClientValue(7, 92)).isEqualTo(12);
            assertThat(state.orderIdByClientValue(7, 93)).isZero();
            // 收集回滚后订单别名的独立原语集合。
            var restored = new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
            lane.clientKeysByOrderId.forEach(11, restored::add);
            lane.clientKeysByOrderId.forEach(12, restored::add);
            assertThat(restored.toArray()).containsExactlyInAnyOrder(91, 92);
            restored.clear();
            lane.clientKeysByOrderId.forEach(99, restored::add);
            assertThat(restored.size()).isZero();
        }
    }

    /**
     * 更新有仓数量与方向、归零、重新开仓和切换合约，分别核对全部持仓成员及非零敞口索引的生命周期。
     */
    @Test
    void positionUpdatesPreserveMembershipAndOnlyFlatTransitionsChangeExposureIndex() {
        try (/* 本场景独立的交易运行时，测试结束时自动关闭工作资源。 */ var state = new TradingAccountRuntime()) {
            state.putUser(new UserRuntime(7));
            state.putPosition(11, position(7, 1, 10));
            // 用户七实际所属的账户 Lane，检查其权威表和成员索引。
            var lane = state.onLane(7L, /* onLane 回调收到的所属 Lane，这里原样返回以便断言。 */ value -> value);
            // 用户全部持仓键集合，包含仍保留的零仓位记录。
            var all = lane.positionKeysByUser.get(7);
            // 合约一按用户分组的非零持仓键索引。
            var byUser = lane.positionKeysBySymbolAndUser.get(1);
            // 用户七在合约一有非零数量的持仓键数组。
            var open = byUser.get(7);
            for (long /* 本轮替换的非零持仓数量，正负方向变化不应重建成员容器。 */ quantity : new long[]{9, 5, -5, -1}) {
                state.replacePosition(11, position(7, 1, quantity));
                assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
                assertThat(lane.positionKeysBySymbolAndUser.get(1)).isSameAs(byUser);
                assertThat(byUser.get(7)).isSameAs(open);
                assertThat(open.toArray()).containsExactly(11);
            }
            state.putPosition(12, position(7, 1, 2));
            state.replacePosition(11, position(7, 1, 0));
            assertThat(all.toArray()).containsExactlyInAnyOrder(11, 12);
            assertThat(open.toArray()).containsExactly(12);
            state.putPosition(11, position(7, 1, 3));
            assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
            assertThat(open.toArray()).containsExactlyInAnyOrder(11, 12);
            state.replacePosition(11, position(7, 2, 3));
            assertThat(open.toArray()).containsExactly(12);
            assertThat(lane.positionKeysBySymbolAndUser.get(2).get(7).toArray()).containsExactly(11);
            com.surprising.aeron.service.core.CoreStateTestFixtures.deletePosition(state, 12, 7);
            assertThat(lane.positionKeysBySymbolAndUser.get(1)).isNull();
            state.replacePosition(11, position(7, 2, 0));
            assertThat(lane.positionKeysBySymbolAndUser.get(2)).isNull();
            assertThat(lane.positionKeysByUser.get(7)).isSameAs(all);
            assertThat(all.toArray()).containsExactly(11);
            com.surprising.aeron.service.core.CoreStateTestFixtures.deletePosition(state, 11, 7);
            assertThat(lane.positionKeysByUser.get(7)).isNull();
        }
    }

    /**
     * 创建带方向的测试净仓持仓；零数量时清空价格，保证金按绝对数量构造。
     * @param user 测试持仓所属用户编号
     * @param instrumentId 测试持仓使用的稠密合约编号
     * @param quantity 带方向的测试持仓数量步数
     */
    private static PositionRuntime position(long user, int instrumentId, long quantity) {
        return new PositionRuntime(user, instrumentId, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), quantity, quantity == 0 ? 0 : 100,
                Math.abs(quantity) * 100, 0, Math.abs(quantity) * 10);
    }
}
