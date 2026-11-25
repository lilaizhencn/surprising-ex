package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.trading.api.model.OrderSide;
import org.junit.jupiter.api.Test;

class MarketMakerQuoteSlotTest {
    @Test void recognizesExactlyTheExistingCanonicalAccountSideAndLevelPrefix() {
        String prefix = "mm-account-7-";
        for (var side : OrderSide.values()) for (int level = 0; level < 240; level++) {
            String id = prefix + (side == OrderSide.BUY ? "b" : "s") + level + "-cycle";
            assertThat(MakerQuoteMatchingSupport.quoteLevel(id, prefix, side)).isEqualTo(level);
            assertThat(MakerQuoteMatchingSupport.quoteLevel(id, prefix, side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY))
                    .isEqualTo(-1);
            assertThat(MakerQuoteMatchingSupport.quoteLevel(id, "mm-account-70-", side)).isEqualTo(-1);
        }
        assertThat(MakerQuoteMatchingSupport.quoteLevel(prefix + "b2147483647-", prefix, OrderSide.BUY)).isEqualTo(Integer.MAX_VALUE);
        for (String suffix : java.util.List.of("b01-x", "b-1-x", "b1", "b-x", "b", "b2147483648-x", "b999999999999999999999-x", "x1-x"))
            assertThat(MakerQuoteMatchingSupport.quoteLevel(prefix + suffix, prefix, OrderSide.BUY)).isEqualTo(-1);
        assertThat(MakerQuoteMatchingSupport.quoteLevel(null, prefix, OrderSide.BUY)).isEqualTo(-1);
    }
}
