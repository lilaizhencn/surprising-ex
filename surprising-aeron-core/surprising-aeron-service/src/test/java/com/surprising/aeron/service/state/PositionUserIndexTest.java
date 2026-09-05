package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
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
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        PositionUserIndex index = new PositionUserIndex(before);
        assertThat(index.users("btc-usdt")).containsExactly(1L);

        Map<Long, CoreUserState> afterUsers = StateMapSupport.delta(before.users());
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.rebuild(after);

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
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        OpenInterestIndex index = new OpenInterestIndex(before);
        assertThat(index.totals().get("BTC-USDT").shortQuantity()).isEqualTo(2);
        assertThat(index.longQuantity("btc-usdt")).isZero();
        assertThat(index.shortQuantity("btc-usdt")).isEqualTo(2);
        assertThat(index.longQuantity("ETH-USDT")).isZero();
        assertThat(index.shortQuantity("ETH-USDT")).isZero();

        Map<Long, CoreUserState> afterUsers = StateMapSupport.delta(before.users());
        afterUsers.put(1L, CoreUserState.empty(ProductLine.LINEAR_PERPETUAL, 1));
        TradingCoreState after = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 2, afterUsers,
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        index.rebuild(after);

        assertThat(index.totals()).isEmpty();
        assertThat(index.shortQuantity("BTC-USDT")).isZero();
    }

    @Test
    void returnsTheNextUserWithoutScanningBeforeTheCursor() {
        CorePositionState position = new CorePositionState("BTC-USDT", "USDT", 1,
                1, 100, 100, 0, 10);
        TradingCoreState state = new TradingCoreState(ProductLine.LINEAR_PERPETUAL, 1,
                Map.of(2L, positionedUser(2, position), 7L, positionedUser(7, position),
                        11L, positionedUser(11, position)),
                Map.of(), Map.of(), CoreRiskState.empty(), CoreTreasuryState.empty());
        LaneTopology topology = LaneTopology.productionDefault();
        PositionUserIndex index = new PositionUserIndex(state, new RuntimeIdentityRegistry(), topology);

        assertThat(index.higherUser("BTC-USDT", 7)).isEqualTo(11L);
        assertThat(index.higherUser("BTC-USDT", 11)).isNull();
        assertThat(index.usersAfter("BTC-USDT", 2)).containsExactly(7L, 11L);
        index.apply(new RuntimePositionIndexValue(7, "BTC-USDT", "USDT", CorePositionSide.NET, 1),
                new RuntimePositionIndexValue(7, "BTC-USDT", "USDT", CorePositionSide.NET, 9));
        assertThat(index.usersAfter("BTC-USDT", 0)).containsExactly(2L, 7L, 11L);
        for (int laneId = 0; laneId < topology.accountLaneCount(); laneId++) {
            int expectedLane = laneId;
            long expected = state.users().keySet().stream()
                    .filter(userId -> topology.accountLaneId(userId) == expectedLane)
                    .mapToLong(Long::longValue).min().orElse(0);
            assertThat(index.higherUserId("BTC-USDT", laneId, 0)).isEqualTo(expected);
        }
    }

    private static CoreUserState positionedUser(long userId, CorePositionState position) {
        return new CoreUserState(ProductLine.LINEAR_PERPETUAL, userId, 0,
                Map.of("USDT", new AssetBalance("USDT", 0, position.positionMarginUnits())), Map.of(),
                Map.of(position.key(), position));
    }
}
