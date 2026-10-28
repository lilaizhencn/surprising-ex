package com.surprising.aeron.service.core;

import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实账本资金守恒拒绝测试，故意只增加手续费而不提供对应资金来源，核对资金校验故障可从健康检查观察。
 */
@Timeout(10)
class SystemLedgerFundsValidationTest {
    /**
     * 启动真实账本并派发不守恒财务命令，同步校验并检查同一故障，断言原因含 not conserved 且关闭也报告失败。
     */
    @Test
    void unbalancedControlPostingFailsOnLedgerBeforeOwnerCanCommit() {
        // 本例账户与财库运行时，显式启动账本
        var accountRuntime = new TradingAccountRuntime(com.surprising.aeron.service.core.CoreStateTestFixtures.SINGLE_LANE_TOPOLOGY);
        accountRuntime.bindOwner();
        accountRuntime.bindLedgerPositionOwner();
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
        accountRuntime.dispatchLedgerCommand(work);
        Throwable rejected = catchThrowable(() -> accountRuntime.validateControlFunds(false));
        assertThat(rejected).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not conserved");
        assertThatThrownBy(accountRuntime::assertAccountLanesHealthy).isSameAs(rejected);
        assertThatThrownBy(accountRuntime::close).isInstanceOf(RuntimeException.class);
    }
}
