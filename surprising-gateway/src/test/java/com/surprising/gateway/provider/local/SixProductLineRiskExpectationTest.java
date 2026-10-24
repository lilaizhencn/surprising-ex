package com.surprising.gateway.provider.local;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 实测预期独立保留反向名义金额分数，保证金仅最终向上取整一次。 */
class SixProductLineRiskExpectationTest {
    @Test
    void inverseMaintenanceUsesTheFractionBeforeNotionalRounding() {
        var numerator = new BigDecimal("10000000000000000000");
        var denominator = new BigDecimal("5000500000000");
        var displayedNotional = numerator.divide(denominator, 0, RoundingMode.HALF_UP);
        assertThat(displayedNotional.longValueExact()).isEqualTo(1_999_800);
        assertThat(displayedNotional.multiply(BigDecimal.valueOf(5_000))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING).longValueExact()).isEqualTo(9_999);
        assertThat(SixProductLineContractLiveTest.expectedMaintenanceUnits(numerator, denominator, 5_000))
                .isEqualTo(10_000);
    }

    @Test
    void exactInverseNotionalKeepsItsMaintenance() {
        assertThat(SixProductLineContractLiveTest.expectedMaintenanceUnits(
                new BigDecimal("10000000000000000000"), new BigDecimal("5000000000000"), 5_000))
                .isEqualTo(10_000);
    }

    @Test
    void linearIntegerNotionalKeepsItsMaintenance() {
        assertThat(SixProductLineContractLiveTest.expectedMaintenanceUnits(
                new BigDecimal("5000500000"), BigDecimal.ONE, 5_000)).isEqualTo(25_002_500);
    }
}
