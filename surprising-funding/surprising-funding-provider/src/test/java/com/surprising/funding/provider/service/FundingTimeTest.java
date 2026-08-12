package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FundingTimeTest {

    @Test
    void alignsNextFundingTimeToUtcIntervalBoundary() {
        assertThat(FundingTime.nextFundingTime(Instant.parse("2026-06-30T07:59:59Z"), 8))
                .isEqualTo(Instant.parse("2026-06-30T08:00:00Z"));
        assertThat(FundingTime.nextFundingTime(Instant.parse("2026-06-30T08:00:00Z"), 8))
                .isEqualTo(Instant.parse("2026-06-30T16:00:00Z"));
    }

    @Test
    void formatsPpmAsDecimalRate() {
        assertThat(FundingTime.rateDecimalString(100L)).isEqualTo("0.000100");
        assertThat(FundingTime.rateDecimalString(-3_000L)).isEqualTo("-0.003000");
    }

    @Test
    void rejectsMinimumFundingRateInsteadOfOverflowingAbsoluteValue() {
        assertThatThrownBy(() -> FundingTime.rateDecimalString(Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
