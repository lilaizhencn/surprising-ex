package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.provider.model.BalanceSettlementState;
import org.junit.jupiter.api.Test;

class PnlSettlementMathTest {

    @Test
    void addsProfitToAvailableWhenThereIsNoDeficit() {
        assertThat(PnlSettlementMath.apply(100L, 50L, 0L, 25L))
                .isEqualTo(new BalanceSettlementState(125L, 50L, 0L));
    }

    @Test
    void usesProfitToClearDeficitBeforeAvailableBalance() {
        assertThat(PnlSettlementMath.apply(100L, 50L, 30L, 25L))
                .isEqualTo(new BalanceSettlementState(100L, 50L, 5L));
        assertThat(PnlSettlementMath.apply(100L, 50L, 30L, 40L))
                .isEqualTo(new BalanceSettlementState(110L, 50L, 0L));
    }

    @Test
    void deductsLossFromAvailableThenLocked() {
        assertThat(PnlSettlementMath.apply(100L, 50L, 0L, -120L))
                .isEqualTo(new BalanceSettlementState(0L, 30L, 0L));
    }

    @Test
    void recordsDeficitWhenLossExceedsWalletBuckets() {
        assertThat(PnlSettlementMath.apply(100L, 50L, 0L, -180L))
                .isEqualTo(new BalanceSettlementState(0L, 0L, 30L));
    }

    @Test
    void limitsLossDebitToEligibleLockedCollateral() {
        assertThat(PnlSettlementMath.apply(20L, 100L, 0L, -90L, 50L))
                .isEqualTo(new BalanceSettlementState(0L, 50L, 20L));
    }

    @Test
    void calculatesNetEquityWithDeficit() {
        assertThat(PnlSettlementMath.netEquityUnits(100L, 50L, 30L)).isEqualTo(120L);
    }
}
