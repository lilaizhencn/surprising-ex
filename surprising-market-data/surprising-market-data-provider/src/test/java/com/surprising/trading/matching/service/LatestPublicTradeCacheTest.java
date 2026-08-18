package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LatestPublicTradeCacheTest {

    @Test
    void keepsTheNewestSequencePerNormalizedSymbol() {
        LatestPublicTradeCache cache = new LatestPublicTradeCache();
        cache.put(trade("btc-usdt", 2L));
        cache.put(trade("BTC-USDT", 1L));

        assertThat(cache.latest(" BTC-USDT ")).get().extracting(PublicTradeEvent::sequence).isEqualTo(2L);
        assertThat(cache.size()).isEqualTo(1);
    }

    private PublicTradeEvent trade(String symbol, long sequence) {
        return new PublicTradeEvent("trade-" + sequence, sequence, symbol, 1L, OrderSide.BUY,
                100L, 2L, Instant.parse("2026-07-31T00:00:00Z"), "trace");
    }
}
