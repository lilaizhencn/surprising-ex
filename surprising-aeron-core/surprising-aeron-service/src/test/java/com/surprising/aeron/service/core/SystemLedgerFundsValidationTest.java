package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实账本资金守恒拒绝测试，故意只增加手续费而不提供对应资金来源，核对资金校验故障可从健康检查观察。
 */
@Timeout(10)
class SystemLedgerFundsValidationTest {
    /**
     * 启动真实账本并派发不守恒财务命令，轮询校验、从健康检查等待故障，断言原因含 not conserved 且关闭也报告失败。
     */
    @Test
    void unbalancedControlPostingFailsOnLedgerBeforeOwnerCanCommit() {
        // 本例账户与财库运行时，显式启动账本
        var accountRuntime = new TradingAccountRuntime(LaneTopology.characterization());
        accountRuntime.bindOwner();
        accountRuntime.startSystemLedgerLane();
        // 故意产生不守恒分账的匿名财务命令
        var work = /* 只增加手续费的测试财务命令，故意省去平衡资金来源 */ new SystemLedgerCommand() {
            /** 构造时绑定原运行时及财务命令生命周期。 */
            { initializeLedgerCommand(accountRuntime); }
            /**
             * 故意把资产1手续费设为7而不增加平衡分账，构造资金不守恒输入。
             * @param ledger 账本线程实际拥有的财库
             */
            @Override protected void applyLedger(TreasuryRuntime ledger) { ledger.setFee(1, 7); }
        };
        assertThat(accountRuntime.dispatchLedgerCommand(work)).isTrue();
        // 等待校验故障的三秒单调纳秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        // 已从健康检查观察到的原故障，空时继续轮询
        Throwable rejected = null;
        while (rejected == null) {
            accountRuntime.pollControlFundsValidation(false);
            try { accountRuntime.assertAccountLanesHealthy(); }
            catch (/* 健康检查抛出的Throwable，保存后核对类型及原因 */ Throwable failure) { rejected = failure; }
            if (System.nanoTime() >= deadline) throw new AssertionError("unbalanced Ledger posting was not rejected");
            Thread.onSpinWait();
        }
        assertThat(rejected).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not conserved");
        assertThatThrownBy(accountRuntime::close).isInstanceOf(RuntimeException.class);
    }
}
