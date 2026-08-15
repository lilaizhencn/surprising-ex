package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreTreasuryStateTest {

    @Test
    void feeAdjustmentKeepsUnchangedTreasuryMapsAndReportsOnlyChangedAsset() {
        CoreTreasuryState before = CoreTreasuryState.empty();

        CoreTreasuryState after = before.adjustFee("USDT", 5);

        assertThat(after.changedAssetsSince(before)).containsExactly("USDT");
        assertThat(after.insuranceBalances()).isSameAs(before.insuranceBalances());
        assertThat(after.insuranceDeficits()).isSameAs(before.insuranceDeficits());
    }
}
