package com.surprising.aeron.service.core;

import com.surprising.aeron.service.risk.RiskMarginCalculator;

import static com.surprising.aeron.service.risk.RiskMarginCalculator.applyMarkPriceRuntime;

import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;

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
    public static AccountBook simulateMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                                        IdIndex identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk simulation");
        }
        // 由本次业务状态新投影的独立运行时，真实风险实现只修改它，不改输入记录。
        AccountBook accountBook = SnapshotLoader.loadSnapshot(before, identities);
        applyMarkPriceRuntime(command, accountBook, identities);
        if (accountBook.riskScanControl().enabled()) {
            continueRiskBudget(accountBook.riskScanControl().scanBatchSize(),
                    new PositionUserIndex(before, accountBook.topology()), accountBook, identities);
        }
        return accountBook;
    }

    /** 测试侧使用实际风险协调器和预算，不保留第二套续扫循环。 */
    public static int continueRiskBudget(int maxWork,
            PositionUserIndex positionUsers,
            AccountBook accountBook, IdIndex identities) {
        var work = new com.surprising.aeron.service.risk.RiskScanner(maxWork, positionUsers, accountBook, identities);
        com.surprising.aeron.service.order.CoreTestCompletion.completeControlWork(work::poll);
        return work.completedWork();
    }
}
