package com.surprising.trading.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketPriceProtectionTest {

    @Test
    void appliesBuySlippageByRoundingUp() {
        assertThat(MarketPriceProtection.protectedPriceTicks(OrderSide.BUY, 100_000L, 10_000L))
                .isEqualTo(101_000L);
        assertThat(MarketPriceProtection.protectedPriceTicks(OrderSide.BUY, 101L, 10_000L))
                .isEqualTo(103L);
    }

    @Test
    void appliesSellSlippageByRoundingDown() {
        assertThat(MarketPriceProtection.protectedPriceTicks(OrderSide.SELL, 100_000L, 10_000L))
                .isEqualTo(99_000L);
        assertThat(MarketPriceProtection.protectedPriceTicks(OrderSide.SELL, 101L, 10_000L))
                .isEqualTo(100L);
    }

    @Test
    void neverReturnsLessThanOneTickForSell() {
        assertThat(MarketPriceProtection.protectedPriceTicks(OrderSide.SELL, 1L, 999_999L))
                .isEqualTo(1L);
    }
}
