package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.api.model.OrderSide;
import org.junit.jupiter.api.Test;

class LiquidationSideResolverTest {

    @Test
    void closesLongBySelling() {
        assertThat(LiquidationSideResolver.closeSide(10L)).isEqualTo(OrderSide.SELL);
        assertThat(LiquidationSideResolver.closeQuantity(10L)).isEqualTo(10L);
    }

    @Test
    void closesShortByBuying() {
        assertThat(LiquidationSideResolver.closeSide(-7L)).isEqualTo(OrderSide.BUY);
        assertThat(LiquidationSideResolver.closeQuantity(-7L)).isEqualTo(7L);
    }

    @Test
    void rejectsZeroPosition() {
        assertThatThrownBy(() -> LiquidationSideResolver.closeSide(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMinimumSignedQuantityInsteadOfOverflowingAbsoluteValue() {
        assertThatThrownBy(() -> LiquidationSideResolver.closeQuantity(Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
