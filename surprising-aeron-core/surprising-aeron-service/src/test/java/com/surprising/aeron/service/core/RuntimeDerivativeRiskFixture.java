package com.surprising.aeron.service.core;

import static com.surprising.aeron.service.risk.RuntimeDerivativeRiskProcessor.applyMarkPriceRuntime;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.snapshot.state.RuntimeStateProjector;

/**
 * 资金和风险一致性测试的辅助入口，每次从业务状态创建独立运行时，再调用真实标记价或风险续扫实现，让测试把其结果与业务状态参照比对。
 */
public final class RuntimeDerivativeRiskFixture {
    /**
     * 校验输入并投影独立运行时，登记新标记价；扫描启用时按配置工作量继续一批，最后把运行时交调用方检查和关闭。
     * @param before 本次模拟开始前的不可变业务状态，先由它投影出隔离运行时
     * @param command 本次要在隔离运行时应用的标记价命令
     * @param identities 本次投影和结果比对共用的业务编号映射
     */
    public static TradingAccountRuntime simulateMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk simulation");
        }
        // 由本次业务状态新投影的独立运行时，真实风险实现只修改它，不改输入记录。
        TradingAccountRuntime runtime = RuntimeStateProjector.project(before, identities);
        applyMarkPriceRuntime(command, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            continueRiskBudget(runtime.riskScanControl().scanBatchSize(),
                    new PositionUserIndex(before, runtime.topology()), runtime, identities);
        }
        return runtime;
    }

    /** 测试侧使用实际风险协调器和预算，不保留第二套续扫循环。 */
    public static int continueRiskBudget(int maxWork,
            PositionUserIndex positionUsers,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities) {
        var work = new com.surprising.aeron.service.risk.RiskScanCoordinator(maxWork, positionUsers, runtime, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work::poll);
        return work.completedWork();
    }
}
