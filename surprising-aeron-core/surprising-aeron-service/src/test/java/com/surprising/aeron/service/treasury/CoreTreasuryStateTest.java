package com.surprising.aeron.service.treasury;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷账本快照的规范化和七子账独立性测试，不启动在线账本。
 */
class CoreTreasuryStateTest {

    /** 规范化完整快照的资产名称并移除零额，不混入其他子账。 */
    @Test
    void normalizesAssetsAndDropsZeroBalances() {
        CoreTreasuryState treasury = new CoreTreasuryState(Map.of(" usdt ", 5L, "BTC", 0L), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

        assertThat(treasury.feeBalances()).containsExactlyEntriesOf(Map.of("USDT", 5L));
        assertThat(treasury.insuranceBalances()).isEmpty();
        assertThat(treasury.insuranceDeficits()).isEmpty();
    }

    /**
     * 给七个子账设置各自正负金额并逐项核对，反射确认没有把强平工作暂存亏空写入冷状态 record。
     */
    @Test
    void keepsAllSevenSubledgersIndependent() {
        // 含七个独立正负金额子账的冷账本测试值。
        CoreTreasuryState treasury = new CoreTreasuryState(Map.of("USDT", -1L), Map.of("USDT", 2L), Map.of("USDT", 7L), Map.of("USDT", 3L), Map.of("USDT", -4L), Map.of("USDT", 5L), Map.of("USDT", -6L), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

        assertThat(treasury.feeBalances()).containsEntry("USDT", -1L);
        assertThat(treasury.insuranceBalances()).containsEntry("USDT", 2L);
        assertThat(treasury.liquidationFeeBalances()).containsEntry("USDT", 3L);
        assertThat(treasury.fundingResidualBalances()).containsEntry("USDT", -4L);
        assertThat(treasury.roundingResidualBalances()).containsEntry("USDT", 5L);
        assertThat(treasury.clearingPnlBalances()).containsEntry("USDT", -6L);
        assertThat(treasury.insuranceDeficits()).containsEntry("USDT", 7L);
        assertThat(java.util.Arrays.stream(CoreTreasuryState.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("liquidationWorkDeficits");
    }
}
