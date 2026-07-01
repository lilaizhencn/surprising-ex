package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarginReleaseMathTest {

    @Test
    void releasesRemainingProportion() {
        assertThat(MarginReleaseMath.releaseForRemaining(1000L, 0L, 0L, 10L, 4L)).isEqualTo(400L);
    }

    @Test
    void capsReleaseByUnreleasedAmount() {
        assertThat(MarginReleaseMath.releaseForRemaining(1000L, 800L, 0L, 10L, 5L)).isEqualTo(200L);
    }

    @Test
    void ignoresMarginAlreadyMovedToPosition() {
        assertThat(MarginReleaseMath.releaseForRemaining(1000L, 0L, 600L, 10L, 5L)).isEqualTo(400L);
    }

    @Test
    void releasesRoundedRemainderWithoutLeavingDust() {
        assertThat(MarginReleaseMath.releaseForRemaining(100L, 33L, 0L, 3L, 2L)).isEqualTo(67L);
    }

    @Test
    void releasesNothingForFilledOrder() {
        assertThat(MarginReleaseMath.releaseForRemaining(1000L, 0L, 0L, 10L, 0L)).isZero();
    }
}
