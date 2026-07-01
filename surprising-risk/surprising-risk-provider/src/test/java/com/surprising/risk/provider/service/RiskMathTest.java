package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.risk.api.model.RiskStatus;
import org.junit.jupiter.api.Test;

class RiskMathTest {

    @Test
    void calculatesEquityAndMarginRatioPpm() {
        long equity = RiskMath.equity(10_000L, -2_000L);

        assertThat(equity).isEqualTo(8_000L);
        assertThat(RiskMath.marginRatioPpm(4_000L, equity)).isEqualTo(500_000L);
    }

    @Test
    void treatsNonPositiveEquityAsLiquidationRisk() {
        assertThat(RiskMath.marginRatioPpm(1L, 0L)).isEqualTo(RiskMath.INFINITE_MARGIN_RATIO);
        assertThat(RiskMath.marginRatioPpm(1L, -1L)).isEqualTo(RiskMath.INFINITE_MARGIN_RATIO);
    }

    @Test
    void mapsStatusByThresholds() {
        assertThat(RiskMath.status(799_999L, 800_000L, 1_000_000L)).isEqualTo(RiskStatus.NORMAL);
        assertThat(RiskMath.status(800_000L, 800_000L, 1_000_000L)).isEqualTo(RiskStatus.WARNING);
        assertThat(RiskMath.status(1_000_000L, 800_000L, 1_000_000L)).isEqualTo(RiskStatus.LIQUIDATION);
    }

    @Test
    void calculatesLinearPositionRiskWithLongUnits() {
        assertThat(RiskMath.notionalUnits(ContractType.LINEAR_PERPETUAL, 6L, 100L,
                100L, 1L, 100_000_000L)).isEqualTo(60_000L);
        assertThat(RiskMath.unrealizedPnlUnits(ContractType.LINEAR_PERPETUAL, 6L, 100L,
                90L, 100L, 1L, 100_000_000L)).isEqualTo(-6_000L);
        assertThat(RiskMath.maintenanceMarginUnits(ContractType.LINEAR_PERPETUAL, 6L, 90L,
                100L, 1L, 100_000_000L, 5_000L)).isEqualTo(270L);
    }

    @Test
    void calculatesInversePositionRiskWithIntegerRounding() {
        assertThat(RiskMath.notionalUnits(ContractType.INVERSE_PERPETUAL, 10L, 5L,
                100L, 1L, 100L)).isEqualTo(20_000L);
        assertThat(RiskMath.unrealizedPnlUnits(ContractType.INVERSE_PERPETUAL, 10L, 10L,
                5L, 100L, 1L, 100L)).isEqualTo(-10_000L);
        assertThat(RiskMath.maintenanceMarginUnits(ContractType.INVERSE_PERPETUAL, 10L, 6L,
                100L, 1L, 100L, 100_000L)).isEqualTo(1_667L);
    }

    @Test
    void rejectsOverflowInsteadOfWrappingRiskAmounts() {
        assertThatThrownBy(() -> RiskMath.notionalUnits(ContractType.LINEAR_PERPETUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1L, 1L))
                .isInstanceOf(ArithmeticException.class);
    }
}
