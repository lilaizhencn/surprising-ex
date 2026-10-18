package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.OrderType;
import org.junit.jupiter.api.Test;

class OrderMarginMathTest {
    @Test
    void exposesMarketExecutionBoundsForNotionalValidation() {
        assertThat(OrderMarginMath.lowerBoundPriceTicks(OrderType.MARKET, 0L, 100L, 10_000L))
                .isEqualTo(99L);
        assertThat(OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0L, 100L, 10_000L))
                .isEqualTo(101L);
    }

    @Test
    void preservesRoundingClampingAndLimitPrices() {
        assertThat(OrderMarginMath.lowerBoundPriceTicks(OrderType.MARKET, 0, 101L, 1)).isEqualTo(100);
        assertThat(OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0, 101L, 1)).isEqualTo(102);
        assertThat(OrderMarginMath.lowerBoundPriceTicks(OrderType.MARKET, 0, 1L, Long.MAX_VALUE)).isEqualTo(1);
        assertThat(OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0, 100L, -1)).isEqualTo(100);
        assertThat(OrderMarginMath.lowerBoundPriceTicks(OrderType.LIMIT, 73, null, 0)).isEqualTo(73);
        assertThat(OrderMarginMath.upperBoundPriceTicks(OrderType.LIMIT, 73, null, 0)).isEqualTo(73);
    }

    @Test
    void rejectsMissingPricesAndOverflow() {
        assertThatThrownBy(() -> OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderMarginMath.lowerBoundPriceTicks(OrderType.LIMIT, 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0, Long.MAX_VALUE, 1))
                .isInstanceOf(ArithmeticException.class);
    }
}
