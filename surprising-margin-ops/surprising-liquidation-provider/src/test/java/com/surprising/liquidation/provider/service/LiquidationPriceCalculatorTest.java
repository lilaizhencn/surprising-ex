package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import org.junit.jupiter.api.Test;

class LiquidationPriceCalculatorTest {

    private final LiquidationPriceCalculator calculator = new LiquidationPriceCalculator();

    @Test
    void linearLongPricesMoveBelowMark() {
        var decision = calculator.decide(new LiquidationPricingInput(
                ContractType.LINEAR_PERPETUAL, 10L, 100L, 200L, 50L, 1L, 1L, 100_000_000L), 3_000L);

        assertThat(decision.bankruptcyPriceTicks()).isEqualTo(80L);
        assertThat(decision.takeoverPriceTicks()).isEqualTo(80L);
        assertThat(decision.liquidationFeeRatePpm()).isEqualTo(3_000L);
        assertThat(decision.liquidationFeeUnits()).isEqualTo(3L);
    }

    @Test
    void linearShortPricesMoveAboveMark() {
        var decision = calculator.decide(new LiquidationPricingInput(
                ContractType.LINEAR_PERPETUAL, -10L, 100L, 200L, 50L, 1L, 1L, 100_000_000L), 3_000L);

        assertThat(decision.bankruptcyPriceTicks()).isEqualTo(120L);
        assertThat(decision.takeoverPriceTicks()).isEqualTo(120L);
        assertThat(decision.liquidationFeeRatePpm()).isEqualTo(3_000L);
        assertThat(decision.liquidationFeeUnits()).isEqualTo(3L);
    }

    @Test
    void inverseShortCanHaveNoReachableBankruptcyPrice() {
        var decision = calculator.decide(new LiquidationPricingInput(
                ContractType.INVERSE_PERPETUAL, -1L, 100L, 2_000L, 50L, 100L, 1L, 100L), 3_000L);

        assertThat(decision.bankruptcyPriceTicks()).isZero();
        assertThat(decision.takeoverPriceTicks()).isZero();
        assertThat(decision.liquidationFeeRatePpm()).isEqualTo(3_000L);
        assertThat(decision.liquidationFeeUnits()).isEqualTo(1L);
    }
}
