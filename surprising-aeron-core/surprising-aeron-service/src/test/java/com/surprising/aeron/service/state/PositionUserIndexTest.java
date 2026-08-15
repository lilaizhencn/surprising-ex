package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PositionUserIndexTest {

    @Test
    void updatesOnlyUsersChangedByTheAuthoritativeState() {
        CoreUserState positioned = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 1, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, 10)), Map.of(), Map.of("BTC-USDT", new CorePositionState(
                        "BTC-USDT", "USDT", 1, 1, 100, 100, 0, 10)), CorePositionMode.ONE_WAY);
        Map<Long, CoreUserState> beforeUsers = new TreeMap<>();
        beforeUsers.put(1L, positioned);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, beforeUsers,
                Map.of(), CoreBookState.empty(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        PositionUserIndex index = new PositionUserIndex(before);
        assertThat(index.users("btc-usdt")).containsExactly(1L);

        Map<Long, CoreUserState> afterUsers = new TreeMap<>();
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), CoreBookState.empty(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.update(before, after);

        assertThat(index.users("BTC-USDT")).isEmpty();
    }

    @Test
    void updatesOpenInterestWithoutScanningUnchangedUsers() {
        CoreUserState positioned = new CoreUserState(ProductLine.LINEAR_PERPETUAL, 1, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, 10)), Map.of(), Map.of("BTC-USDT",
                        new CorePositionState("BTC-USDT", "USDT", 1, -2, 100, 200, 0, 10)),
                CorePositionMode.ONE_WAY);
        Map<Long, CoreUserState> beforeUsers = new TreeMap<>();
        beforeUsers.put(1L, positioned);
        TradingCoreState before = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1, beforeUsers,
                Map.of(), CoreBookState.empty(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        OpenInterestIndex index = new OpenInterestIndex(before);
        assertThat(index.totals().get("BTC-USDT").shortQuantity()).isEqualTo(2);

        Map<Long, CoreUserState> afterUsers = new TreeMap<>();
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), CoreBookState.empty(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.update(before, after);

        assertThat(index.totals()).isEmpty();
    }
}
