package com.surprising.instrument.api.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import org.junit.jupiter.api.Test;

class PerpetualContractMathTest {

    @Test
    void inverseFastAndWidePathsMatchIndependentDecimalOracle() {
        java.util.Random random = new java.util.Random(719_003);
        for (int i = 0; i < 5000; i++) {
            long q = (random.nextInt(100_000) + 1L) * (random.nextBoolean() ? 1 : -1);
            long entry = random.nextInt(100_000) + 1L;
            long mark = random.nextInt(100_000) + 1L;
            long multiplier = i % 2 == 0 ? 100 : 1_000_000;
            long scale = i % 2 == 0 ? 100 : 100_000_000;
            long tick = random.nextInt(100) + 1L;
            var quantity = java.math.BigDecimal.valueOf(q);
            var coefficient = java.math.BigDecimal.valueOf(multiplier).multiply(java.math.BigDecimal.valueOf(scale));
            var denominator = java.math.BigDecimal.valueOf(mark).multiply(java.math.BigDecimal.valueOf(tick));
            long notional = quantity.abs().multiply(coefficient)
                    .divide(denominator, 0, java.math.RoundingMode.HALF_UP).longValueExact();
            long pnl = quantity.multiply(coefficient).multiply(java.math.BigDecimal.valueOf(mark - entry))
                    .divide(denominator.multiply(java.math.BigDecimal.valueOf(entry)), 0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
            long margin = quantity.abs().multiply(coefficient).multiply(java.math.BigDecimal.valueOf(50_000))
                    .divide(denominator.multiply(java.math.BigDecimal.valueOf(1_000_000)), 0, java.math.RoundingMode.CEILING)
                    .longValueExact();
            for (ContractType type : new ContractType[]{ContractType.INVERSE_PERPETUAL, ContractType.INVERSE_DELIVERY}) {
                assertThat(PerpetualContractMath.notionalUnits(type, q, mark, multiplier, tick, scale)).isEqualTo(notional);
                assertThat(PerpetualContractMath.unrealizedPnlUnits(type, q, entry, mark, multiplier, tick, scale)).isEqualTo(pnl);
                assertThat(PerpetualContractMath.initialMarginUnits(type, q, mark, multiplier, tick, scale, 50_000)).isEqualTo(margin);
            }
        }
    }

    @Test
    void calculatesLinearNotionalPnlAndMaintenanceMargin() {
        assertThat(PerpetualContractMath.notionalUnits(ContractType.LINEAR_PERPETUAL, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
        assertThat(PerpetualContractMath.notionalPerStepUnits(ContractType.LINEAR_PERPETUAL, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(10_000L);
        assertThat(PerpetualContractMath.unrealizedPnlUnits(ContractType.LINEAR_PERPETUAL, 6L,
                100L, 90L, 100L, 1L, 100_000_000L)).isEqualTo(-6_000L);
        assertThat(PerpetualContractMath.maintenanceMarginUnits(ContractType.LINEAR_PERPETUAL, 6L,
                90L, 100L, 1L, 100_000_000L, 5_000L)).isEqualTo(270L);
        assertThat(PerpetualContractMath.initialMarginUnits(ContractType.LINEAR_PERPETUAL, 6L,
                100L, 100L, 1L, 100_000_000L, 10_000L)).isEqualTo(600L);
    }

    @Test
    void calculatesInverseNotionalPnlAndMaintenanceMargin() {
        assertThat(PerpetualContractMath.notionalUnits(ContractType.INVERSE_PERPETUAL, 10L, 5L,
                100L, 1L, 100L)).isEqualTo(20_000L);
        assertThat(PerpetualContractMath.notionalPerStepUnits(ContractType.INVERSE_PERPETUAL, 6L,
                100L, 1L, 100L)).isEqualTo(1_667L);
        assertThat(PerpetualContractMath.unrealizedPnlUnits(ContractType.INVERSE_PERPETUAL, 10L,
                10L, 5L, 100L, 1L, 100L)).isEqualTo(-10_000L);
        assertThat(PerpetualContractMath.maintenanceMarginUnits(ContractType.INVERSE_PERPETUAL, 10L,
                6L, 100L, 1L, 100L, 100_000L)).isEqualTo(1_667L);
        assertThat(PerpetualContractMath.initialMarginUnits(ContractType.INVERSE_PERPETUAL, 10L,
                5L, 100L, 1L, 100L, 100_000L)).isEqualTo(2_000L);
    }

    @Test
    void deliveryContractsReuseLinearAndInverseFormulas() {
        assertThat(PerpetualContractMath.notionalUnits(ContractType.LINEAR_DELIVERY, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
        assertThat(PerpetualContractMath.unrealizedPnlUnits(ContractType.LINEAR_DELIVERY, 6L,
                100L, 90L, 100L, 1L, 100_000_000L)).isEqualTo(-6_000L);
        assertThat(PerpetualContractMath.notionalUnits(ContractType.INVERSE_DELIVERY, 10L, 5L,
                100L, 1L, 100L)).isEqualTo(20_000L);
        assertThat(PerpetualContractMath.initialMarginUnits(ContractType.INVERSE_DELIVERY, 10L,
                5L, 100L, 1L, 100L, 100_000L)).isEqualTo(2_000L);
    }

    @Test
    void optionContractsUseLinearPremiumFormulas() {
        assertThat(PerpetualContractMath.notionalUnits(ContractType.VANILLA_OPTION, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
        assertThat(PerpetualContractMath.unrealizedPnlUnits(ContractType.VANILLA_OPTION, 6L,
                100L, 20L, 100L, 1L, 100_000_000L)).isEqualTo(-48_000L);
    }

    @Test
    void rejectsSpotContracts() {
        assertThatThrownBy(() -> PerpetualContractMath.notionalUnits(ContractType.SPOT,
                1L, 100L, 1L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported contract type");
    }

    @Test
    void rejectsOverflowInsteadOfWrapping() {
        assertThatThrownBy(() -> PerpetualContractMath.notionalUnits(ContractType.LINEAR_PERPETUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1L, 1L))
                .isInstanceOf(ArithmeticException.class);
    }
}
