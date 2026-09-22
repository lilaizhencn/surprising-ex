package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountMarginReleaseMathTest {

    @Test
    void releasesReservationShareForExecutedQuantity() {
        assertThat(AccountMarginReleaseMath.releaseForExecuted(1_000L, 0L, 0L, 10L, 4L))
                .isEqualTo(600L);
    }

    @Test
    void subtractsAmountsAlreadyReleasedOrConsumed() {
        assertThat(AccountMarginReleaseMath.releaseForExecuted(1_000L, 200L, 300L, 10L, 4L))
                .isEqualTo(100L);
    }

    @Test
    void cumulativeSnapshotsReleaseRoundingDustOnlyAtTheFinalFill() {
        long first = AccountMarginReleaseMath.releaseForExecuted(100L, 0L, 0L, 3L, 2L);
        long second = AccountMarginReleaseMath.releaseForExecuted(100L, first, 0L, 3L, 1L);
        long third = AccountMarginReleaseMath.releaseForExecuted(100L, first + second, 0L, 3L, 0L);

        assertThat(first).isEqualTo(33L);
        assertThat(second).isEqualTo(33L);
        assertThat(third).isEqualTo(34L);
    }

    @Test
    void neverReleasesMoreThanTheUnconsumedReservation() {
        assertThat(AccountMarginReleaseMath.releaseForExecuted(1_000L, 100L, 700L, 10L, 0L))
                .isEqualTo(200L);
    }
}
