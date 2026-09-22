package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FundingMathTest {

    @Test
    void clampsRateByInstrumentLimits() {
        assertThat(FundingMath.clampRate(5_000, -3_000, 3_000)).isEqualTo(3_000);
        assertThat(FundingMath.clampRate(-5_000, -3_000, 3_000)).isEqualTo(-3_000);
        assertThat(FundingMath.clampRate(1_000, -3_000, 3_000)).isEqualTo(1_000);
    }
}
