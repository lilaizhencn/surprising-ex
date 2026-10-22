package com.surprising.aeron.service.core;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;
import static com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor.*;

/**
 * 资金和风险一致性测试的辅助入口，每次从业务状态创建独立运行时，再调用真实标记价或风险续扫实现，让测试把其结果与业务状态参照比对。
 */
public final class RuntimeDerivativeRiskFixture {
    /**
     * 校验输入并投影独立运行时，登记新标记价；扫描启用时按配置工作量继续一批，最后把运行时交调用方检查和关闭。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次要在隔离运行时应用的标记价命令
     * @param indexedUserIds 本次风险扫描使用的有序用户编号集合
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static TradingAccountRuntime simulateMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                                        Iterable<Long> indexedUserIds,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk simulation");
        }
        // 由本次业务状态新投影的独立运行时，真实风险实现只修改它，不改输入记录。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        applyMarkPriceRuntime(command, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            applyContinuationRuntime(runtime.riskScanControl().scanBatchSize(), indexedUserIds, runtime, identities);
        }
        return runtime;
    }

    /**
     * 从检查点重新投影独立运行时，校验本次预算后应用一次风险续扫，供快照或分批一致性测试比对。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param maxWork 本次继续风险扫描允许的工作量，范围为 1 到 4096
     * @param indexedUserIds 本次风险扫描使用的有序用户编号集合
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static TradingAccountRuntime simulateContinuation(TradingCoreState before, int maxWork,
                                                           Iterable<Long> indexedUserIds,
                                                           RuntimeIdentityRegistry identities) {
        if (before == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        // 由本次业务状态新投影的独立运行时，真实风险实现只修改它，不改输入记录。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        applyContinuationRuntime(maxWork, indexedUserIds, runtime, identities);
        return runtime;
    }
}
