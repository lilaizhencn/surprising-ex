package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeTreasuryDeltaTest {

    @Test
    void appliesOnlyChangedTreasuryEntriesAndPreservesRuntimeParity() {
        UUID fundingCommandId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID lifecycleCommandId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        CoreTreasuryState beforeTreasury = new CoreTreasuryState(
                Map.of("USDT", 100L, "BTC", 200L),
                Map.of("USDT", 50L),
                Map.of(),
                Map.of("BTC-USDT", 7L),
                Map.of("ETH-USDT", 3L),
                Map.of("BTC-USDT", new CoreTreasuryState.FundingProgress(7, 1, 10_000, 11, fundingCommandId)),
                Map.of("ETH-USDT", new CoreTreasuryState.LifecycleProgress(3, 1, 60_000,
                        0, true, 0, 12, lifecycleCommandId)));
        TradingCoreState before = state(beforeTreasury, 1);

        CoreTreasuryState afterTreasury = beforeTreasury
                .adjustFee("USDT", -100)
                .adjustInsurance("USDT", -100)
                .recordFunding("BTC-USDT", 8)
                .recordLifecycle("ETH-USDT", 4);
        TradingCoreState after = state(afterTreasury, 2);

        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        RuntimeStateDeltaApplier.apply(before, after, runtime, identities);

        TreasuryRuntime treasury = runtime.treasury();
        assertThat(treasury.fee(identities.assetId("BTC"))).isEqualTo(200L);
        assertThat(treasury.fee(identities.assetId("USDT"))).isZero();
        assertThat(treasury.insurance(identities.assetId("USDT"))).isZero();
        assertThat(treasury.insuranceDeficit(identities.assetId("USDT"))).isEqualTo(50L);
        assertThat(treasury.fundingSettlement(identities.symbolId("BTC-USDT"))).isEqualTo(8L);
        assertThat(treasury.fundingProgress(identities.symbolId("BTC-USDT"))).isNull();
        assertThat(treasury.lifecycleSettlement(identities.symbolId("ETH-USDT"))).isEqualTo(4L);
        assertThat(treasury.lifecycleProgress(identities.symbolId("ETH-USDT"))).isNull();
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(after);
    }

    @Test
    void rejectsNonDeltaTreasuryTransitionInsteadOfRebuildingOnlineState() {
        TradingCoreState before = state(CoreTreasuryState.empty(), 1);
        TradingCoreState after = state(new CoreTreasuryState(
                Map.of("USDT", 3L), Map.of(), Map.of(), Map.of(), Map.of()), 2);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);

        assertThatThrownBy(() -> RuntimeStateDeltaApplier.apply(before, after, runtime, identities))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("online treasury transition is not a delta");
    }

    private static TradingCoreState state(CoreTreasuryState treasury, long revision) {
        return new TradingCoreState(ProductLine.LINEAR_PERPETUAL, revision,
                Map.of(), Map.of(), Map.of(), CoreRiskState.empty(), treasury);
    }
}
