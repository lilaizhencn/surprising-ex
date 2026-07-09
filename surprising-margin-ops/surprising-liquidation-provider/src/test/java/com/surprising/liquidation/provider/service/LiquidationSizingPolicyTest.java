package com.surprising.liquidation.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import org.junit.jupiter.api.Test;

class LiquidationSizingPolicyTest {

    private final LiquidationSizingPolicy policy = new LiquidationSizingPolicy();

    @Test
    void closesToLowerRiskTierBeforeRatioClose() {
        var sizing = defaultSizing();
        var decision = policy.decide(new LiquidationSizingInput(
                100L, 100L, 100_000L, 1_000L, 50_000L), 1_100_000L, sizing);

        assertThat(decision.quantitySteps()).isEqualTo(51L);
        assertThat(decision.reason()).isEqualTo("TIER_REDUCTION");
    }

    @Test
    void closesConfiguredRatioWhenAlreadyInLowestTier() {
        var sizing = defaultSizing();
        var decision = policy.decide(new LiquidationSizingInput(
                100L, 100L, 40_000L, 400L, 0L), 1_100_000L, sizing);

        assertThat(decision.quantitySteps()).isEqualTo(50L);
        assertThat(decision.reason()).isEqualTo("PARTIAL_LIQUIDATION");
    }

    @Test
    void doesNotStackTierReductionAlreadyCoveredByPendingCloseOrders() {
        var sizing = defaultSizing();
        var decision = policy.decide(new LiquidationSizingInput(
                100L, 40L, 100_000L, 1_000L, 50_000L), 1_100_000L, sizing);

        assertThat(decision.quantitySteps()).isEqualTo(20L);
        assertThat(decision.reason()).isEqualTo("PARTIAL_LIQUIDATION");
    }

    @Test
    void usesSevereRatioForHighMarginRatio() {
        var sizing = defaultSizing();
        var decision = policy.decide(new LiquidationSizingInput(
                100L, 80L, 40_000L, 400L, 0L), 1_600_000L, sizing);

        assertThat(decision.quantitySteps()).isEqualTo(60L);
    }

    @Test
    void fullClosesWhenAboveFullCloseThreshold() {
        var sizing = defaultSizing();
        var decision = policy.decide(new LiquidationSizingInput(
                100L, 70L, 40_000L, 400L, 0L), 3_000_000L, sizing);

        assertThat(decision.quantitySteps()).isEqualTo(70L);
        assertThat(decision.reason()).isEqualTo("FULL_LIQUIDATION");
    }

    private LiquidationProperties.Sizing defaultSizing() {
        return new LiquidationProperties.Sizing();
    }
}
