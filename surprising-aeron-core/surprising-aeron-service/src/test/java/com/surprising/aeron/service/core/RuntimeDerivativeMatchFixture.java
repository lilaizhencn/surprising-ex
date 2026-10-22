package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;
import static com.surprising.aeron.service.settlement.RuntimeDerivativeMatchProcessor.*;

/**
 * 测试用衍生品运行时投影与修订对齐辅助类。simulate 仍调用生产 applyRuntime；simulateTransition 及 applyTransition 只对齐测试产品修订，不覆盖实际计算的账户状态。不是独立资金公式或在线异步交易入口。
 */
public final class RuntimeDerivativeMatchFixture {
    /**
     * 检查衍生品输入，投影独立内存运行时并调用生产通用成交入口，返回结果供状态一致性测试检查。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime simulate(TradingCoreState before, long takerOrderId,
                                               List<MatcherEvent> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /**
     * 检查前后产品线并从起点投影运行时，再委托带期望修订计划的测试转换入口。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，仅提供测试产品修订，不替代账户计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime simulateTransition(TradingCoreState before, TradingCoreState expected,
                                                         long takerOrderId, List<MatcherEvent> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        // 由起点冷状态投影出的独立测试运行时。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    /**
     * 在给定运行时应用生产成交计算后对齐测试产品修订；用户修订、余额和持仓均保留实际执行结果。
     * @param before 测试起点冷状态，须为衍生产品线
     * @param expected 期望冷状态，仅提供测试产品修订，不替代账户计算
     * @param takerOrderId 本次主动成交订单编号
     * @param matches 用例直接构造的原生成交事件列表
     * @param runtime 已经由起点投影且由当前测试持有的运行时
     * @param identities 投影和成交共同使用的运行时身份字典
     */
    public static TradingAccountRuntime applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<MatcherEvent> matches,
                                                       TradingAccountRuntime runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isDerivative()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }
}
