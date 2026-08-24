package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FundsDeltaTest {

    @Test
    void sortsCoalescesAndConservesPerAsset() {
        FundsPosting userDebit = new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                FundsPosting.Subledger.AVAILABLE, -3);
        FundsDelta delta = new FundsDelta(List.of(
                new FundsPosting("USDT", FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.FEE, 5),
                new FundsPosting("BTC", FundsPosting.OwnerKind.EXTERNAL, 0,
                        FundsPosting.Subledger.EXTERNAL_ADJUSTMENT, -1),
                userDebit,
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, -2),
                new FundsPosting("BTC", FundsPosting.OwnerKind.USER, 8,
                        FundsPosting.Subledger.AVAILABLE, 1)));

        assertThat(delta.postings()).containsExactly(
                new FundsPosting("BTC", FundsPosting.OwnerKind.USER, 8,
                        FundsPosting.Subledger.AVAILABLE, 1),
                new FundsPosting("BTC", FundsPosting.OwnerKind.EXTERNAL, 0,
                        FundsPosting.Subledger.EXTERNAL_ADJUSTMENT, -1),
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, -5),
                new FundsPosting("USDT", FundsPosting.OwnerKind.TREASURY, 0,
                        FundsPosting.Subledger.FEE, 5));
        assertThat(delta.unitsByAsset()).containsExactlyEntriesOf(
                new java.util.TreeMap<>(java.util.Map.of("BTC", 0L, "USDT", 0L)));
    }

    @Test
    void rejectsUnbalancedAsset() {
        assertThatThrownBy(() -> new FundsDelta(List.of(
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, -1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Funds delta is not conserved for asset USDT");
    }

    @Test
    void rejectsOverflow() {
        assertThatThrownBy(() -> new FundsDelta(List.of(
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, Long.MAX_VALUE),
                new FundsPosting("USDT", FundsPosting.OwnerKind.USER, 7,
                        FundsPosting.Subledger.AVAILABLE, 1))))
                .isInstanceOf(ArithmeticException.class);
    }
}
