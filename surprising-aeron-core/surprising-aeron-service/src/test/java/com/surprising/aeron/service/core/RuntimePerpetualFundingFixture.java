package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import static com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.*;

/**
 * 从只读交易状态构建独立运行时，供资金费状态对照测试使用。计算仍调用同一个生产资金费处理器，因此对照结果不是独立的资金公式证明，也不等于真实 Cluster 日志恢复。
 */
public final class RuntimePerpetualFundingFixture {
    /**
     * 校验基础输入，把旧状态投影成独立运行时；未传用户索引时使用旧状态用户集合，再推进实际资金费工作；冷投影不启动账户或账本线程。
     * @param before 用于投影独立运行时的旧交易状态
     * @param command 本次资金费请求
     * @param indexedUserIds 可选的递增用户来源，为空时取旧状态用户集合
     * @param identities 投影和资金计算共用的内部身份表
     */
    public static FundingResult simulate(TradingCoreState before, ApplyFundingCommand command,
                                         Iterable<Long> indexedUserIds,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding simulation");
        }
        // 从旧状态新投影出的独立可变运行时，不替换被测实例。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        // 本次模拟使用的用户索引来源。
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        return applyRuntime(command, users, runtime, identities);
    }

    /** 在测试拥有的运行时推进真实资金费工作，返回原计算结果。 */
    public static FundingResult applyRuntime(ApplyFundingCommand command, Iterable<Long> userIds,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        var work = com.surprising.aeron.service.funding.RuntimePerpetualFundingProcessor.prepare(
                null, command, userIds, runtime, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work::poll);
        var result = work.result();
        runtime.clearChangedKeys();
        return result;
    }
}
