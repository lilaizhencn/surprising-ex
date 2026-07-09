package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.funding.provider.model.FundingBalanceState;
import org.junit.jupiter.api.Test;

class FundingMathTest {

    @Test
    void clampsRateByInstrumentLimits() {
        assertThat(FundingMath.clampRate(5_000L, -3_000L, 3_000L)).isEqualTo(3_000L);
        assertThat(FundingMath.clampRate(-5_000L, -3_000L, 3_000L)).isEqualTo(-3_000L);
        assertThat(FundingMath.clampRate(1_000L, -3_000L, 3_000L)).isEqualTo(1_000L);
    }

    @Test
    void positiveRateChargesLongsAndPaysShorts() {
        assertThat(FundingMath.paymentAmount(10L, 1_000_000L, 1_000L)).isEqualTo(-1_000L);
        assertThat(FundingMath.paymentAmount(-10L, 1_000_000L, 1_000L)).isEqualTo(1_000L);
    }

    @Test
    void negativeRatePaysLongsAndChargesShorts() {
        assertThat(FundingMath.paymentAmount(10L, 1_000_000L, -1_000L)).isEqualTo(1_000L);
        assertThat(FundingMath.paymentAmount(-10L, 1_000_000L, -1_000L)).isEqualTo(-1_000L);
    }

    @Test
    void appliesPaymentWithoutNegativeBalanceColumns() {
        assertThat(FundingMath.applyPayment(100L, 50L, 0L, -180L))
                .isEqualTo(new FundingBalanceState(0L, 0L, 30L));
        assertThat(FundingMath.applyPayment(100L, 50L, 30L, 40L))
                .isEqualTo(new FundingBalanceState(110L, 50L, 0L));
    }

    @Test
    void limitsFundingChargeToEligibleLockedCollateral() {
        assertThat(FundingMath.applyPayment(20L, 100L, 0L, -90L, 50L))
                .isEqualTo(new FundingBalanceState(0L, 50L, 20L));
    }

    @Test
    void rejectsMinimumFundingRateInsteadOfOverflowingAbsoluteValue() {
        assertThatThrownBy(() -> FundingMath.paymentAmount(10L, 1_000_000L, Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
