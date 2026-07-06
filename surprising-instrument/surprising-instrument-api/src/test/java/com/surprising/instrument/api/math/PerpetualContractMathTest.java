package com.surprising.instrument.api.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import org.junit.jupiter.api.Test;

class PerpetualContractMathTest {

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
    void rejectsSpotAndOptionContracts() {
        assertThatThrownBy(() -> PerpetualContractMath.notionalUnits(ContractType.SPOT,
                1L, 100L, 1L, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported contract type");
        assertThatThrownBy(() -> PerpetualContractMath.notionalUnits(ContractType.VANILLA_OPTION,
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
