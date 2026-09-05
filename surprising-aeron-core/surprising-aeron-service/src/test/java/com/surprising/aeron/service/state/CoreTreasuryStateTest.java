package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreTreasuryStateTest {

    @Test
    void feeAdjustmentKeepsUnchangedTreasuryMapsAndReportsOnlyChangedAsset() {
        CoreTreasuryState before = CoreTreasuryState.empty();

        CoreTreasuryState after = before.adjustFee("USDT", 5);

        assertThat(after.changedAssets()).containsExactly("USDT");
        assertThat(after.insuranceBalances()).isSameAs(before.insuranceBalances());
        assertThat(after.insuranceDeficits()).isSameAs(before.insuranceDeficits());
    }

    @Test
    void keepsAllSevenSubledgersIndependent() {
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
