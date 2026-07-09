package com.surprising.adl.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdlMathTest {

    @Test
    void calculatesProfitRateAndLeverageInPpm() {
        assertThat(AdlMath.profitRatePpm(100, 1_000)).isEqualTo(100_000);
        assertThat(AdlMath.effectiveLeveragePpm(1_000, 100)).isEqualTo(10_000_000);
        assertThat(AdlMath.priorityScorePpm(100_000, 10_000_000)).isEqualTo(1_000_000);
    }

    @Test
    void calculatesCloseStepsNeededToCoverDeficit() {
        assertThat(AdlMath.closeStepsForCover(250, 10, 1_000)).isEqualTo(3);
        assertThat(AdlMath.closeStepsForCover(1_000, 10, 1_000)).isEqualTo(10);
        assertThat(AdlMath.closeStepsForCover(1_500, 10, 1_000)).isEqualTo(10);
    }

    @Test
    void calculatesProportionalUnits() {
        assertThat(AdlMath.proportionalUnits(1_000, 3, 10)).isEqualTo(300);
        assertThat(AdlMath.proportionalUnits(1_000, 0, 10)).isZero();
    }
}
