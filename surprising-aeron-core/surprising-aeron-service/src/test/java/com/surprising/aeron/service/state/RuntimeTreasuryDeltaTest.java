package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeTreasuryDeltaTest {

    @Test
    void everyTreasurySubledgerContributesToTheBusinessStateHash() {
        CoreTreasuryState empty = CoreTreasuryState.empty();
        Set<Long> hashes = Set.of(
                state(empty, 1).businessStateHash(),
                state(empty.adjustFee("USDT", 1), 1).businessStateHash(),
                state(empty.adjustInsurance("USDT", 1), 1).businessStateHash(),
                state(empty.adjustDeficit("USDT", 1), 1).businessStateHash(),
                state(empty.adjustLiquidationFee("USDT", 1), 1).businessStateHash(),
                state(empty.adjustFundingResidual("USDT", 1), 1).businessStateHash(),
                state(empty.adjustRoundingResidual("USDT", 1), 1).businessStateHash(),
                state(empty.adjustClearingPnl("USDT", 1), 1).businessStateHash());

        assertThat(hashes).hasSize(8);
    }

    @Test
    void projectsEveryTreasurySubledgerWithoutDroppingFunds() {
        CoreTreasuryState treasury = CoreTreasuryState.ofSubledgers(
                Map.of("USDT", 1L), Map.of("USDT", 2L), Map.of("USDT", 3L),
                Map.of("USDT", -4L), Map.of("USDT", 5L), Map.of("USDT", -6L),
                Map.of("USDT", 7L));
        TradingCoreState expected = state(treasury, 1);
        RuntimeIdentityRegistry identities = new RuntimeIdentityRegistry();

        TradingRuntimeState runtime = RuntimeStateProjector.project(expected, identities);

        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(expected);
        assertThat(RollingBusinessStateHash.compute(expected)).isEqualTo(expected.businessStateHash());
    }

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

        TreasuryRuntime treasury = runtime.treasury();
        int usdt = identities.assetId("USDT");
        treasury.setFee(usdt, 0);
        treasury.setInsurance(usdt, -50, 0);
        treasury.setFundingSettlement(identities.symbolId("BTC-USDT"), 8);
        treasury.setLifecycleSettlement(identities.symbolId("ETH-USDT"), 4);
        runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 2);

        assertThat(treasury.fee(identities.assetId("BTC"))).isEqualTo(200L);
        assertThat(treasury.fee(identities.assetId("USDT"))).isZero();
        assertThat(treasury.insurance(identities.assetId("USDT"))).isEqualTo(-50L);
        assertThat(treasury.insuranceDeficit(identities.assetId("USDT"))).isZero();
        assertThat(treasury.fundingSettlement(identities.symbolId("BTC-USDT"))).isEqualTo(8L);
        assertThat(treasury.fundingProgress(identities.symbolId("BTC-USDT"))).isNull();
        assertThat(treasury.lifecycleSettlement(identities.symbolId("ETH-USDT"))).isEqualTo(4L);
        assertThat(treasury.lifecycleProgress(identities.symbolId("ETH-USDT"))).isNull();
        assertThat(RuntimeStateMaterializer.materialize(runtime, identities)).isEqualTo(after);
    }

    private static TradingCoreState state(CoreTreasuryState treasury, long revision) {
        return new TradingCoreState(ProductLine.LINEAR_PERPETUAL, revision,
                Map.of(), Map.of(), Map.of(), CoreRiskState.empty(), treasury);
    }
}
