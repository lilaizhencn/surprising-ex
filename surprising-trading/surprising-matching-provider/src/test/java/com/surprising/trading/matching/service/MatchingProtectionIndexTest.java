package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.RecoveredOrderBookOrder;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MatchingProtectionIndexTest {

    @Test
    void restoresOrdersAndChecksSelfTradeAndVersionWithoutDatabase() {
        MatchingProtectionIndex index = new MatchingProtectionIndex(new MatchingProperties());
        index.restore(new RecoveredOrderBookOrder(11L, 100L, "BTC-USDT", OrderSide.BUY,
                TimeInForce.GTC, 100L, 5L, Instant.parse("2026-07-31T00:00:00Z")));
        index.markReady();

        assertThat(index.wouldSelfTrade(100L, "BTC-USDT", 1L, OrderSide.SELL, 99L)).isTrue();
        assertThat(index.wouldSelfTrade(101L, "BTC-USDT", 1L, OrderSide.SELL, 99L)).isFalse();
        assertThat(index.hasOpenOrdersWithDifferentInstrumentVersion("BTC-USDT", 2L, 99L)).isTrue();
        assertThat(index.size()).isEqualTo(1);
    }
}
