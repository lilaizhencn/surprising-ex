package com.surprising.aeron.service.account;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.aeron.service.snapshot.state.StateMapSupport;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证用户冷状态的增量继承关系、预留及仓位保证金对冻结余额的解释，以及重建后的值比较；不把这些直接状态操作当作真实 Cluster 恢复。
 */
class CoreUserStateTest {

    /**
     * 依次新增、释放、删除预留，核对解释冻结额与最终资产总额随增量状态正确变化。
     */
    @Test
    void reservationTransitionsMaintainExplainedLocksIncrementally() {
        // 本场景转换之前的用户冷状态，后续 Map 必须从它对应的原表派生。
        CoreUserState user = fundedUser(1_000, 0);
        // 本场景新增并随后释放的保证金预留。
        OrderReservation reservation = reservation(11, 300);

        // 从原预留表直接派生、准备放入新预留的增量 Map。
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(reservation.orderId(), reservation);
        // 本次变化对应的余额增量 Map，保持可用与冻结合计一千。
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put("USDT", new AssetBalance("USDT", 700, 300));
        // 已加入预留且可追溯到原状态的用户值。
        CoreUserState reserved = user.transition(1, balances, reservations, user.positions(), user.positionMode());

        // 从冻结后状态派生的预留变更，保留记录但把剩余量释放为零。
        Map<Long, OrderReservation> releasedReservations = StateMapSupport.delta(reserved.reservations());
        releasedReservations.put(reservation.orderId(), reservation.releaseAll());
        // 恢复可用一千、冻结为零的余额增量。
        Map<String, AssetBalance> releasedBalances = StateMapSupport.delta(reserved.balances());
        releasedBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        // 已经释放全部预留的中间用户状态。
        CoreUserState released = reserved.transition(2, releasedBalances, releasedReservations,
                reserved.positions(), reserved.positionMode());

        // 从释放后状态派生、准备删除空预留的增量表。
        Map<Long, OrderReservation> removedReservations = StateMapSupport.delta(released.reservations());
        removedReservations.remove(reservation.orderId());
        // 空预留已被移除的最终用户状态。
        CoreUserState removed = released.transition(3, released.balances(), removedReservations,
                released.positions(), released.positionMode());

        assertThat(reserved.reservations().get(11L).remainingUnits()).isEqualTo(300);
        assertThat(released.reservations().get(11L).remainingUnits()).isZero();
        assertThat(removed.reservations()).isEmpty();
        assertThat(removed.totalUnits("USDT")).isEqualTo(1_000);
    }

    /**
     * 增减并删除仓位保证金，核对持仓和冻结余额结果；本用例只有 USDT，没有观测未变化资产的访问次数。
     */
    @Test
    void positionMarginTransitionsValidateOnlyAffectedAssetLocks() {
        // 本场景转换之前的用户冷状态，后续 Map 必须从它对应的原表派生。
        CoreUserState user = fundedUser(800, 200);
        // 保证金为二百的初始测试持仓。
        CorePositionState position = position(200);
        // 从原用户持仓表派生、准备加入初始持仓的增量 Map。
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        positions.put(position.key(), position);
        // 已登记初始仓位保证金的用户状态。
        CoreUserState opened = user.transition(1, user.balances(), user.reservations(), positions,
                user.positionMode());

        // 把持仓保证金从二百减到七十五的增量表。
        Map<String, CorePositionState> reducedPositions = StateMapSupport.delta(opened.positions());
        reducedPositions.put(position.key(), position(75));
        // 本次变化对应的余额增量 Map，保持可用与冻结合计一千。
        Map<String, AssetBalance> balances = StateMapSupport.delta(opened.balances());
        balances.put("USDT", new AssetBalance("USDT", 925, 75));
        // 保证金和余额同步减仓后的用户状态。
        CoreUserState reduced = opened.transition(2, balances, opened.reservations(), reducedPositions,
                opened.positionMode());

        // 准备删除该持仓记录的直接增量 Map。
        Map<String, CorePositionState> closedPositions = StateMapSupport.delta(reduced.positions());
        closedPositions.remove(position.key());
        // 删除持仓时把冻结全部转回可用的余额变更。
        Map<String, AssetBalance> closedBalances = StateMapSupport.delta(reduced.balances());
        closedBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        // 持仓删除且冻结归零的用户状态。
        CoreUserState closed = reduced.transition(3, closedBalances, reduced.reservations(), closedPositions,
                reduced.positionMode());

        assertThat(reduced.positions().get("1").positionMarginUnits()).isEqualTo(75);
        assertThat(reduced.balances().get("USDT").lockedUnits()).isEqualTo(75);
        assertThat(closed.positions()).isEmpty();
        assertThat(closed.balances().get("USDT").lockedUnits()).isZero();
    }

    /**
     * 保留三百预留却把冻结改为一百，要求增量转换拒绝无法解释的冻结余额。
     */
    @Test
    void rejectsBalanceBelowIncrementalExplainedLocks() {
        // 本场景转换之前的用户冷状态，后续 Map 必须从它对应的原表派生。
        CoreUserState user = fundedUser(700, 300);
        // 从原预留表直接派生、准备放入新预留的增量 Map。
        Map<Long, OrderReservation> reservations = StateMapSupport.delta(user.reservations());
        reservations.put(11L, reservation(11, 300));
        // 已加入预留且可追溯到原状态的用户值。
        CoreUserState reserved = user.transition(1, user.balances(), reservations, user.positions(),
                user.positionMode());
        // 本次变化对应的余额增量 Map，保持可用与冻结合计一千。
        Map<String, AssetBalance> balances = StateMapSupport.delta(reserved.balances());
        balances.put("USDT", new AssetBalance("USDT", 900, 100));

        assertThatThrownBy(() -> reserved.transition(2, balances, reserved.reservations(), reserved.positions(),
                reserved.positionMode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation locks exceed balance");
    }

    /**
     * 检查普通替换 Map 不能作为直接增量，并检查冷重建用户不能伪装为原用户的增量后代。
     */
    @Test
    void rejectsNestedStateWithoutDirectDeltaLineage() {
        // 本场景转换之前的用户冷状态，后续 Map 必须从它对应的原表派生。
        CoreUserState user = fundedUser(1_000, 0);
        // 独立创建的余额 Map，故意缺少增量父表关系。
        Map<String, AssetBalance> replacement = Map.of("USDT", new AssetBalance("USDT", 900, 100));

        assertThatThrownBy(() -> user.transition(1, replacement, user.reservations(), user.positions(),
                user.positionMode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("balances lineage is unavailable");

        // 通过完整构造器重建的用户，业务字段有效但没有原用户的增量继承关系。
        CoreUserState coldReplacement = new CoreUserState(user.productLine(), user.userId(), 1,
                replacement, user.reservations(), user.positions(), user.positionMode());
        assertThatThrownBy(() -> coldReplacement.requireIncrementalLineage(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user balances lineage is unavailable");
    }

    /**
     * 在同一条增量继承链中先冻结再释放，要求最终用户仍能追溯到命令前状态。
     */
    @Test
    void acceptsMultipleUserTransitionsWithinOneCommandLineage() {
        // 本场景转换之前的用户冷状态，后续 Map 必须从它对应的原表派生。
        CoreUserState user = fundedUser(1_000, 0);
        // 同一命令中第一次状态转换的冻结余额增量。
        Map<String, AssetBalance> reservedBalances = StateMapSupport.delta(user.balances());
        reservedBalances.put("USDT", new AssetBalance("USDT", 700, 300));
        // 同一命令中第一次状态转换的新增预留增量。
        Map<Long, OrderReservation> reservedOrders = StateMapSupport.delta(user.reservations());
        reservedOrders.put(11L, reservation(11, 300));
        // 已加入预留且可追溯到原状态的用户值。
        CoreUserState reserved = user.transition(1, reservedBalances, reservedOrders, user.positions(),
                user.positionMode());

        // 从冻结后状态继续派生、准备恢复余额的增量表。
        Map<String, AssetBalance> settledBalances = StateMapSupport.delta(reserved.balances());
        settledBalances.put("USDT", new AssetBalance("USDT", 1_000, 0));
        // 从冻结后状态继续派生、准备释放预留的增量表。
        Map<Long, OrderReservation> settledOrders = StateMapSupport.delta(reserved.reservations());
        settledOrders.put(11L, reservedOrders.get(11L).releaseAll());
        // 沿同一继承链完成第二次转换的最终用户。
        CoreUserState settled = reserved.transition(2, settledBalances, settledOrders, reserved.positions(),
                reserved.positionMode());

        assertThat(settled).satisfies(/* 断言回调接收的最终用户，要求它仍能追溯到原状态。 */ value -> value.requireIncrementalLineage(user));
    }

    /**
     * 用相同业务字段重新构造用户，核对 equals 与 hashCode 不受派生解释冻结表影响；这里没有实际编解码快照。
     */
    @Test
    void derivedLocksDoNotChangeSnapshotValueSemantics() {
        // 同时包含一百预留和二百持仓保证金的原用户值。
        CoreUserState original = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 3,
                Map.of("USDT", new AssetBalance("USDT", 700, 300)),
                Map.of(11L, reservation(11, 100)),
                Map.of("1", position(200)), CorePositionMode.ONE_WAY);
        // 使用相同字段重新构造的用户；变量名虽为 decoded，本用例没有执行解码器。
        CoreUserState decoded = new CoreUserState(original.productLine(), original.userId(), original.revision(),
                original.balances(), original.reservations(), original.positions(), original.positionMode());

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.hashCode()).isEqualTo(original.hashCode());
    }

    /**
     * 创建只有 USDT 余额的 U 本位永续测试用户，预留与持仓初始为空。
     * @param availableUnits 初始 USDT 可用整数金额
     * @param lockedUnits 初始 USDT 冻结整数金额
     */
    private static CoreUserState fundedUser(long availableUnits, long lockedUnits) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, 7, 0,
                Map.of("USDT", new AssetBalance("USDT", availableUnits, lockedUnits)),
                Map.of(), Map.of(), CorePositionMode.ONE_WAY);
    }

    /**
     * 创建指定金额的衍生品保证金预留，合约与资产采用固定测试值。
     * @param orderId 测试预留对应的订单编号
     * @param units 测试预留总金额
     */
    private static OrderReservation reservation(long orderId, long units) {
        return OrderReservation.create(orderId, "1", ReservationKind.DERIVATIVE_MARGIN,
                "USDT", units, 1);
    }

    /**
     * 创建带指定仓位保证金的净仓持仓，供解释冻结额校验使用。
     * @param marginUnits 测试持仓要占用的保证金金额
     */
    private static CorePositionState position(long marginUnits) {
        return new CorePositionState("1", "USDT", CoreMarginMode.CROSS, CorePositionSide.NET,
                1, 100, 100, 0, marginUnits);
    }
}
