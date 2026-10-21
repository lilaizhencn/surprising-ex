package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;

import static com.surprising.aeron.service.state.settlement.RuntimeDerivativeMatchProcessor.*;

/**
 * 测试用衍生品运行时投影与修订对齐辅助类。simulate 仍调用生产 applyRuntime；simulateTransition 及 applyTransition 再按期望冷状态的变化用户修订计划补齐测试元数据。不是独立资金公式或在线异步交易入口。
 */
public final class RuntimeDerivativeMatchFixture {
    /**
     * 检查衍生品输入，投影独立内存运行时并调用生产通用成交入口，返回结果供状态一致性测试检查。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingRuntimeState simulate(TradingCoreState before, long takerOrderId,
                                               List<MatcherEvent> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /**
     * 检查前后产品线并从起点投影运行时，再委托带期望修订计划的测试转换入口。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，提供变化用户集合及修订号计划，不替代成交金额计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingRuntimeState simulateTransition(TradingCoreState before, TradingCoreState expected,
                                                         long takerOrderId, List<MatcherEvent> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    /**
     * 先在给定运行时应用生产成交计算，再只遍历期望变化用户对齐修订号，最后对齐产品修订；账户金额仍由实际成交入口计算，不复制期望余额覆盖结果。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，提供变化用户集合及修订号计划，不替代成交金额计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param runtime 已经由起点投影且由当前测试持有的运行时
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingRuntimeState applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<MatcherEvent> matches,
                                                       TradingRuntimeState runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isDerivative()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
        for (Long /* 期望冷状态变化集合中的当前用户编号，仅这些用户参与修订对齐。 */ userId : expected.changedUserIds()) {
            // 期望结果中的对应用户值，用来读取产品线和修订号。
            CoreUserState planned = expected.users().get(userId);
            if (planned == null) {
                throw new IllegalStateException("runtime match changed user is missing: " + userId);
            }
            // 生产成交执行后的用户权威值，金额和持仓不从期望结果复制。
            UserRuntime actual = runtime.user(userId);
            if (actual == null || actual.productLine() != planned.productLine()) {
                throw new IllegalStateException("runtime match user is missing: " + userId);
            }
            if (actual.revision() != planned.revision()) {
                runtime.putUser(new UserRuntime(actual.productLine(), userId, planned.revision(),
                        actual.positionMode()));
            }
        }
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }
}
