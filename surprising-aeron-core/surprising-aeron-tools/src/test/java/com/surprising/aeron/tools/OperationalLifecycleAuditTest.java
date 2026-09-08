package com.surprising.aeron.tools;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OperationalLifecycleAuditTest {
    @Test void includesRecurringVictimAndInsuranceFundingWithoutTreatingTransfersAsDeposits() {
        assertThat(OperationalLifecycle.expectedDeposits(0)).isEqualTo(3_000_000_000L);
        assertThat(OperationalLifecycle.expectedDeposits(7)).isEqualTo(3_000_000_875L);
        assertThatThrownBy(()->OperationalLifecycle.expectedDeposits(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OperationalLifecycle.expectedDeposits(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }
}
