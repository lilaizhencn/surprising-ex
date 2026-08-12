package com.surprising.insurance.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InsuranceMathTest {

    @Test
    void returnsZeroWhenDeficitIsNotPositive() {
        assertThat(InsuranceMath.coverAmount(0, 1_000)).isZero();
        assertThat(InsuranceMath.coverAmount(-1, 1_000)).isZero();
    }

    @Test
    void returnsZeroWhenFundBalanceIsNotPositive() {
        assertThat(InsuranceMath.coverAmount(1_000, 0)).isZero();
        assertThat(InsuranceMath.coverAmount(1_000, -1)).isZero();
    }

    @Test
    void coversFullDeficitWhenFundIsEnough() {
        assertThat(InsuranceMath.coverAmount(1_000, 2_000)).isEqualTo(1_000);
    }

    @Test
    void coversPartialDeficitWhenFundIsInsufficient() {
        assertThat(InsuranceMath.coverAmount(2_000, 750)).isEqualTo(750);
    }
}
