package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import org.junit.jupiter.api.Test;

class OrderMarginMathTest {

    @Test
    void calculatesLinearLimitMarginWithLongUnits() {
        long margin = OrderMarginMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL, OrderSide.BUY,
                OrderType.LIMIT, 100L, 6L, null, 0L,
                100L, 1L, 100_000_000L, 10_000L);

        assertThat(margin).isEqualTo(600L);
    }

    @Test
    void linearSellLimitMarginUsesProtectedMarkWhenLimitCanFillAtBetterHigherPrice() {
        long effectivePrice = OrderMarginMath.collateralPriceTicks(OrderSide.SELL, OrderType.LIMIT,
                99L, 100L, 10_000L, ContractType.LINEAR_PERPETUAL);

        assertThat(effectivePrice).isEqualTo(101L);
    }

    @Test
    void linearSellLimitMarginRequiresFreshMarkForProtectedCollateralPrice() {
        assertThatThrownBy(() -> OrderMarginMath.collateralPriceTicks(OrderSide.SELL, OrderType.LIMIT,
                99L, null, 10_000L, ContractType.LINEAR_PERPETUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fresh mark price ticks");
    }

    @Test
    void linearBuyLimitMarginDoesNotRequireFreshMarkBecauseFillCannotNeedMoreCollateral() {
        long effectivePrice = OrderMarginMath.collateralPriceTicks(OrderSide.BUY, OrderType.LIMIT,
                101L, null, 10_000L, ContractType.LINEAR_PERPETUAL);

        assertThat(effectivePrice).isEqualTo(101L);
    }

    @Test
    void protectedLinearSellLimitCanSkipFreshMarkWhenCallerKnowsFillOnlyReducesExposure() {
        long effectivePrice = OrderMarginMath.collateralPriceTicks(OrderSide.SELL, OrderType.LIMIT,
                99L, null, 10_000L, ContractType.LINEAR_PERPETUAL, false);

        assertThat(effectivePrice).isEqualTo(99L);
    }

    @Test
    void linearMarketMarginUsesUpperBoundForBothSides() {
        long buyMargin = OrderMarginMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL, OrderSide.BUY,
                OrderType.MARKET, 0L, 6L, 100L, 10_000L,
                100L, 1L, 100_000_000L, 10_000L);
        long sellMargin = OrderMarginMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL, OrderSide.SELL,
                OrderType.MARKET, 0L, 6L, 100L, 10_000L,
                100L, 1L, 100_000_000L, 10_000L);

        assertThat(buyMargin).isEqualTo(606L);
        assertThat(sellMargin).isEqualTo(606L);
    }

    @Test
    void calculatesInverseLimitMarginWithCeilingRounding() {
        long margin = OrderMarginMath.initialMarginUnits(ContractType.INVERSE_PERPETUAL, OrderSide.BUY,
                OrderType.LIMIT, 5L, 10L, 5L, 0L,
                100L, 1L, 100L, 100_000L);

        assertThat(margin).isEqualTo(2_000L);
    }

    @Test
    void deliveryContractsReuseLinearAndInverseMarginRules() {
        assertThat(OrderMarginMath.initialMarginUnits(ContractType.LINEAR_DELIVERY, OrderSide.BUY,
                OrderType.LIMIT, 100L, 6L, null, 0L,
                100L, 1L, 100_000_000L, 10_000L)).isEqualTo(600L);
        assertThat(OrderMarginMath.initialMarginUnits(ContractType.INVERSE_DELIVERY, OrderSide.BUY,
                OrderType.LIMIT, 5L, 10L, 5L, 0L,
                100L, 1L, 100L, 100_000L)).isEqualTo(2_000L);
        assertThat(OrderMarginMath.collateralPriceTicks(OrderSide.BUY, OrderType.LIMIT,
                101L, 100L, 10_000L, ContractType.INVERSE_DELIVERY)).isEqualTo(99L);
    }

    @Test
    void inverseMarketUsesLowerEffectivePriceForCollateralProtection() {
        long effectivePrice = OrderMarginMath.collateralPriceTicks(OrderSide.BUY, OrderType.MARKET,
                0L, 100L, 10_000L, ContractType.INVERSE_PERPETUAL);

        assertThat(effectivePrice).isEqualTo(99L);
    }

    @Test
    void inverseBuyLimitMarginUsesProtectedMarkWhenLimitCanFillAtBetterLowerPrice() {
        long effectivePrice = OrderMarginMath.collateralPriceTicks(OrderSide.BUY, OrderType.LIMIT,
                101L, 100L, 10_000L, ContractType.INVERSE_PERPETUAL);

        assertThat(effectivePrice).isEqualTo(99L);
    }

    @Test
    void inverseBuyLimitMarginRequiresFreshMarkForProtectedCollateralPrice() {
        assertThatThrownBy(() -> OrderMarginMath.collateralPriceTicks(OrderSide.BUY, OrderType.LIMIT,
                101L, null, 10_000L, ContractType.INVERSE_PERPETUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fresh mark price ticks");
    }

    @Test
    void exposesMarketExecutionBoundsForNotionalValidation() {
        assertThat(OrderMarginMath.lowerBoundPriceTicks(OrderType.MARKET, 0L, 100L, 10_000L))
                .isEqualTo(99L);
        assertThat(OrderMarginMath.upperBoundPriceTicks(OrderType.MARKET, 0L, 100L, 10_000L))
                .isEqualTo(101L);
    }

    @Test
    void convertsLeveragePpmToInitialMarginRatePpm() {
        assertThat(OrderMarginMath.initialMarginRateFromLeveragePpm(10_000_000L)).isEqualTo(100_000L);
        assertThat(OrderMarginMath.initialMarginRateFromLeveragePpm(100_000_000L)).isEqualTo(10_000L);
    }

    @Test
    void calculatesLinearOrderNotionalUnits() {
        assertThat(OrderMarginMath.notionalUnits(ContractType.LINEAR_PERPETUAL, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
    }

    @Test
    void optionMarginUsesLinearPremiumFormula() {
        assertThat(OrderMarginMath.initialMarginUnits(ContractType.VANILLA_OPTION,
                OrderSide.BUY, OrderType.LIMIT, 100L, 6L, null, 0L,
                100L, 1L, 100_000_000L, 10_000L)).isEqualTo(600L);
        assertThat(OrderMarginMath.notionalUnits(ContractType.VANILLA_OPTION, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
    }

    @Test
    void rejectsSpotMarginFormulas() {
        assertThatThrownBy(() -> OrderMarginMath.notionalUnits(ContractType.SPOT, 1L, 100L,
                1L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported contract type");
    }

    @Test
    void rejectsMarketMarginWithoutFreshMarkTicks() {
        assertThatThrownBy(() -> OrderMarginMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL,
                OrderSide.BUY, OrderType.MARKET, 0L, 1L, null, 0L,
                1L, 1L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fresh mark price ticks");
    }

    @Test
    void rejectsOverflowInsteadOfWrappingMargin() {
        assertThatThrownBy(() -> OrderMarginMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL,
                OrderSide.BUY, OrderType.LIMIT, Long.MAX_VALUE, Long.MAX_VALUE, null, 0L,
                Long.MAX_VALUE, 1L, 1L, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
