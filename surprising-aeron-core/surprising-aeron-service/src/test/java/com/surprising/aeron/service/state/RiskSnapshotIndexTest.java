package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskSnapshotIndexTest {

    @Test
    void updateMaintainsUserAndGlobalSnapshotKeysIncrementally() {
        CoreRiskSnapshot first = new CoreRiskSnapshot(11, "BTC-USDT", CorePositionSide.NET,
                1, 100, 0, 10, 100_000, CoreRiskStatus.NORMAL);
        TradingCoreState before = state(Map.of(first.key(), first));
        RiskSnapshotIndex index = new RiskSnapshotIndex(before);

        CoreRiskSnapshot second = new CoreRiskSnapshot(12, "ETH-USDT", CorePositionSide.NET,
                2, 200, 0, 20, 100_000, CoreRiskStatus.NORMAL);
        var snapshots = StateMapSupport.delta(before.riskState().snapshots());
        snapshots.remove(first.key());
        snapshots.put(second.key(), second);
        CoreRiskState risk = new CoreRiskState(before.riskState().markPrices(), snapshots,
                before.riskState().liquidations(), before.riskState().scans(), before.riskState().nextLiquidationId());
        TradingCoreState after = new TradingCoreState(ProductLine.SPOT, 2, before.users(), before.orders(),
                before.bookState(), before.instruments(), risk, before.treasuryState());

        index.update(before, after);

        assertThat(index.keys(11)).isEmpty();
        assertThat(index.keys(12)).containsExactly("12:ETH-USDT");
        assertThat(index.keys()).containsExactly("12:ETH-USDT");
    }

    private static TradingCoreState state(Map<String, CoreRiskSnapshot> snapshots) {
        return new TradingCoreState(ProductLine.SPOT, 1, Map.of(), Map.of(), CoreBookState.empty(), Map.of(),
                new CoreRiskState(Map.of(), snapshots, Map.of(), Map.of(), 1), CoreTreasuryState.empty());
    }
}
