package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.state.TradingDependencyMask;
import org.junit.jupiter.api.Test;

class ClusterCommandWindowTest {
    @Test
    void accountAndSymbolMaskCollisionsDoNotFenceUnrelatedCommands() {
        var window = new ClusterCommandWindow();
        long first = 1, collision = 2;
        while (TradingDependencyMask.account(first) != TradingDependencyMask.account(collision)) collision++;
        String symbol = "SYM0-USDT", other = null;
        for (int i = 1; other == null; i++) {
            String candidate = "SYM" + i + "-USDT";
            if (TradingDependencyMask.account(symbol.hashCode()) == TradingDependencyMask.account(candidate.hashCode()))
                other = candidate;
        }
        window.resetCandidate(first);
        window.candidateOrder(100, symbol, null, 0);
        window.add(null, null, 0, 0);
        window.resetCandidate(collision);
        window.candidateOrder(200, other, null, 0);
        assertThat(window.conflicts()).isFalse();
        window.resetCandidate(first);
        assertThat(window.conflicts()).isTrue();
        window.resetCandidate(collision);
        window.candidateOrder(300, symbol, null, 0);
        assertThat(window.conflicts()).isTrue();
    }
    @Test
    void reusedRingKeepsSuffixIdentityAndReleasesOnlyCommittedDependencies() {
        var window = new ClusterCommandWindow();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 64; i++) {
                window.resetCandidate(i + 1);
                window.candidateOrder(round * 100L + i + 1);
                window.add(null, null, i, i).sequence = i + 1;
            }
            var suffix = window.get(63);
            window.resetCandidate(18);
            assertThat(window.conflictingPrefixSize()).isEqualTo(18);
            window.removePrefix(18);
            assertThat(window.conflictingPrefixSize()).isZero();
            assertThat(window.get(45)).isSameAs(suffix);
            assertThat(window.get(0).sequence).isEqualTo(19);
            window.clear();
            assertThat(window.size()).isZero();
            window.resetCandidate(0);
            window.candidateOrder(round * 100L + 64);
            assertThat(window.conflicts()).isFalse();
        }
    }

    @Test
    void orderMaskCollisionStillUsesExactOrderIdentityAcrossAccounts() {
        var window = new ClusterCommandWindow();
        long first = 100, collision = first + 1;
        while (TradingDependencyMask.account(first) != TradingDependencyMask.account(collision)) collision++;
        window.resetCandidate(0);
        window.candidateOrder(first);
        window.add(null, null, 0, 0);
        window.resetCandidate(0);
        window.candidateOrder(collision);
        assertThat(window.conflicts()).isFalse();
        window.candidateOrder(first);
        assertThat(window.conflictingPrefixSize()).isOne();
    }
}
