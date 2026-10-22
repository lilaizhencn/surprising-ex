package com.surprising.aeron.service.treasury;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷账本状态的增量共享和七子账独立性测试，直接构造内存值并核对资产变化键，不启动在线账本。
 */
class CoreTreasuryStateTest {

    /**
     * 只调整 USDT 费用，核对变化键以及保险、亏空 Map 复用原引用。
     */
    @Test
    void feeAdjustmentKeepsUnchangedTreasuryMapsAndReportsOnlyChangedAsset() {
        // 费用调整前全部为空的冷账本。
        CoreTreasuryState before = CoreTreasuryState.empty();

        // 仅新增 USDT 费用五单位的新冷账本。
        CoreTreasuryState after = before.adjustFee("USDT", 5);

        assertThat(after.changedAssets()).containsExactly("USDT");
        assertThat(after.insuranceBalances()).isSameAs(before.insuranceBalances());
        assertThat(after.insuranceDeficits()).isSameAs(before.insuranceDeficits());
    }

    /**
     * 给七个子账设置各自正负金额并逐项核对，反射确认没有把强平工作暂存亏空写入冷状态 record。
     */
    @Test
    void keepsAllSevenSubledgersIndependent() {
        // 含七个独立正负金额子账的冷账本测试值。
        CoreTreasuryState treasury = CoreTreasuryState.ofSubledgers(
                Map.of("USDT", -1L), Map.of("USDT", 2L), Map.of("USDT", 3L),
                Map.of("USDT", -4L), Map.of("USDT", 5L), Map.of("USDT", -6L),
                Map.of("USDT", 7L));

        assertThat(treasury.feeBalances()).containsEntry("USDT", -1L);
        assertThat(treasury.insuranceBalances()).containsEntry("USDT", 2L);
        assertThat(treasury.liquidationFeeBalances()).containsEntry("USDT", 3L);
        assertThat(treasury.fundingResidualBalances()).containsEntry("USDT", -4L);
        assertThat(treasury.roundingResidualBalances()).containsEntry("USDT", 5L);
        assertThat(treasury.clearingPnlBalances()).containsEntry("USDT", -6L);
        assertThat(treasury.deficitBalances()).containsEntry("USDT", 7L);
        assertThat(java.util.Arrays.stream(CoreTreasuryState.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("liquidationWorkDeficits");
    }
}
