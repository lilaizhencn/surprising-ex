package com.surprising.aeron.service.core.index;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.CoreRiskState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import com.surprising.aeron.service.treasury.CoreTreasuryState;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 持仓用户路由和未平仓量索引的业务结果测试，覆盖移除持仓后的重建、游标后继、逐 Lane 用户选择，以及含空仓记录的风险查询规模限制。
 */
class PositionUserIndexTest {

    /**
     * 把唯一有仓用户替换为空用户后调用 rebuild，确认合约用户成员清空；本用例实际验证重建结果，没有构造未变化用户或检查扫描次数。
     */
    @Test
    void updatesOnlyUsersChangedByTheAuthoritativeState() {
        // 初始唯一有持仓的用户记录，分别用于用户成员或空头总量重建测试。
        CoreUserState positioned = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 1, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, 10)), Map.of(), Map.of("1", new CorePositionState(
                        "1", "USDT", 1, 100, 100, 0, 10)), CorePositionMode.ONE_WAY);
        // 重建前包含唯一有仓用户的用户映射。
        Map<Long, CoreUserState> beforeUsers = new TreeMap<>();
        beforeUsers.put(1L, positioned);
        // 初始业务状态，用于构造需要检查的持仓用户或未平仓量索引。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, beforeUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例操作的索引，成员场景用 PositionUserIndex，总量场景用 OpenInterestIndex。
        PositionUserIndex index = new PositionUserIndex(before);
        assertThat(index.usersAfter("1", 0)).containsExactly(1L);

        // 原用户映射的变更视图，把唯一有仓用户替换为无持仓用户。
        Map<Long, CoreUserState> afterUsers = new TreeMap<>(before.users());
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        // 移除目标用户持仓后的业务状态，重建后旧合约应没有有效成员或非零总量。
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.rebuild(after);

        assertThat(index.usersAfter("1", 0)).isEmpty();
    }

    /**
     * 从一份两步空头检查多空累计值，再以移除持仓的后状态调用 rebuild 并确认总量归零；本用例没有观测未变化用户是否被扫描。
     */
    @Test
    void updatesOpenInterestWithoutScanningUnchangedUsers() {
        // 初始唯一有持仓的用户记录，分别用于用户成员或空头总量重建测试。
        CoreUserState positioned = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 1, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, 10)), Map.of(), Map.of("1",
                        new CorePositionState("1", "USDT", -2, 100, 200, 0, 10)),
                CorePositionMode.ONE_WAY);
        // 重建前包含唯一有仓用户的用户映射。
        Map<Long, CoreUserState> beforeUsers = new TreeMap<>();
        beforeUsers.put(1L, positioned);
        // 初始业务状态，用于构造需要检查的持仓用户或未平仓量索引。
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, beforeUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例操作的索引，成员场景用 PositionUserIndex，总量场景用 OpenInterestIndex。
        OpenInterestIndex index = new OpenInterestIndex(before);
        assertThat(index.totals()).containsExactly(org.assertj.core.api.Assertions.entry(
                "1", new OpenInterestIndex.Totals(0, 2)));
        assertThat(index.openInterestSteps("1")).isEqualTo(2);
        assertThat(index.openInterestSteps("2")).isZero();

        // 原用户映射的变更视图，把唯一有仓用户替换为无持仓用户。
        Map<Long, CoreUserState> afterUsers = new TreeMap<>(before.users());
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        // 移除目标用户持仓后的业务状态，重建后旧合约应没有有效成员或非零总量。
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.rebuild(after);

        assertThat(index.totals()).isEmpty();
        assertThat(index.openInterestSteps("1")).isZero();
    }

    /**
     * 构造用户 2、7、11 的持仓路由，检查严格游标后继和惰性遍历；只变数量不改变成员，再按拓扑逐 Lane 核对最小用户编号。
     */
    @Test
    void returnsTheNextUserWithoutScanningBeforeTheCursor() {
        // 可被多个测试用户共用的业务持仓记录，保证每用户都生成同合约路由。
        CorePositionState position = new CorePositionState("1", "USDT", 1, 100, 100, 0, 10);
        // 包含本用例多个用户和持仓记录的完整业务状态。
        TradingCoreState state = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(2L, positionedUser(2, position), 7L, positionedUser(7, position),
                        11L, positionedUser(11, position)),
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        // 生产默认账户 Lane 拓扑，索引分组和期望用户筛选必须一致。
        LaneTopology topology = com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY;
        // 本用例操作的索引，成员场景用 PositionUserIndex，总量场景用 OpenInterestIndex。
        PositionUserIndex index = new PositionUserIndex(state, topology);

        assertThat(index.higherUserId("1", 7)).isEqualTo(11L);
        assertThat(index.higherUserId("1", 11)).isZero();
        assertThat(index.usersAfter("1", 2)).containsExactly(7L, 11L);
        index.apply(new RuntimePositionIndexValue(7, "1", "USDT", CorePositionSide.NET, 1),
                new RuntimePositionIndexValue(7, "1", "USDT", CorePositionSide.NET, 9));
        assertThat(index.usersAfter("1", 0)).containsExactly(2L, 7L, 11L);
        for (/* 当前核对后继用户的账户 Lane 下标。 */ int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            // 供流式筛选回调固定使用的本轮 Lane 编号。
            int expectedLane = laneId;
            // 业务状态中属于当前 Lane 的最小用户编号，若没有用户则为 0。
            long expected = state.users().keySet().stream()
                    .filter(/* 当前筛选的业务用户编号，根据拓扑判断是否属于目标 Lane。 */ userId -> topology.accountLaneId(userId) == expectedLane)
                    .mapToLong(Long::longValue).min().orElse(0);
            assertThat(index.higherUserId("1", laneId, 0)).isEqualTo(expected);
        }
    }

    /**
     * 一个用户含有仓和空仓记录，另一个只有有仓记录；检查全局和单用户路由都把空仓算入规模上限，不存在用户仍保留查询用户编号。
     */
    @Test
    void riskQueryUsersUseMembershipAndEnforceGlobalAndUserScanBounds() {
        // 可被多个测试用户共用的业务持仓记录，保证每用户都生成同合约路由。
        var position = new CorePositionState("1", "USDT", 1, 100, 100, 0, 10);
        // 合约 2 的零数量持仓仍是一份记录，必须计入查询规模。
        var flat = new CorePositionState("2", "USDT", 0, 0, 0, 0, 0);
        // 用户 1 的两份持仓记录，一份有仓、一份空仓，用来区分记录数与非空仓数。
        var first = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 1, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, 10)), Map.of(), Map.of("1", position, "2", flat));
        // 包含本用例多个用户和持仓记录的完整业务状态。
        var state = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 0,
                Map.of(1L, first, 7L, positionedUser(7, position)), Map.of(), Map.of(),
                CoreRiskState.empty(), CoreTreasuryState.empty());
        // 本用例操作的索引，成员场景用 PositionUserIndex，总量场景用 OpenInterestIndex。
        var index = new PositionUserIndex(state);
        // 最多允许三份持仓记录的全局风险路由结果，用户编号去重并排序。
        assertThat(index.riskQueryUserIds(0, 3)).containsExactly(1L, 7L);
        assertThat(index.riskQueryUserIds(1, 2)).containsExactly(1L);
        assertThat(index.riskQueryUserIds(7, 1)).containsExactly(7L);
        assertThat(index.riskQueryUserIds(99, 1)).containsExactly(99L);
        assertThatThrownBy(() -> index.riskQueryUserIds(0, 2))
                .isInstanceOf(com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException.class);
        assertThatThrownBy(() -> index.riskQueryUserIds(1, 1))
                .isInstanceOf(com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException.class);
    }

    /**
     * 创建一个指定持仓的线性永续用户，冻结余额等于该持仓保证金，供路由分组测试复用。
     * @param userId 要构造的测试用户编号
     * @param position 需要放入该用户持仓映射的业务持仓记录
     */
    private static CoreUserState positionedUser(long userId, CorePositionState position) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, position.positionMarginUnits())), Map.of(),
                Map.of(position.key(), position));
    }
}
