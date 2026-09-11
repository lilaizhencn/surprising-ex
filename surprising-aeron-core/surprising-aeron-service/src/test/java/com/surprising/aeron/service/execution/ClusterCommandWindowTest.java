package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.service.state.TradingDependencyMask;
import org.junit.jupiter.api.Test;

class ClusterCommandWindowTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {64, 128, 256, 512, 1024})
    void segmentedSlotsMatchSerialDependenciesAcrossRandomWraps(int capacity) {
        var window = new ClusterCommandWindow(capacity);
        var accounts = new java.util.ArrayList<Long>();
        var orders = new java.util.ArrayList<Long>();
        var random = new java.util.Random(9191);
        for (int step = 0; step < capacity * 12; step++) {
            if (accounts.size() == capacity || !accounts.isEmpty() && random.nextInt(5) == 0) {
                int remove = 1 + random.nextInt(Math.min(83, accounts.size()));
                window.removePrefix(remove);
                accounts.subList(0, remove).clear(); orders.subList(0, remove).clear();
            }
            long account = 1 + random.nextInt(capacity * 2), order = 1 + random.nextInt(capacity * 2);
            window.resetCandidate(account); window.candidateOrder(order);
            int expected = 0;
            for (int i = 0; i < accounts.size(); i++)
                if (accounts.get(i) == account || orders.get(i) == order) expected = i + 1;
            assertThat(window.conflictingPrefixSize()).as("capacity=%s step=%s", capacity, step).isEqualTo(expected);
            window.add(null, null, step, step);
            accounts.add(account); orders.add(order);
        }
        window.clear();
        for (long order : orders) {
            window.resetCandidate(0); window.candidateOrder(order);
            assertThat(window.conflicts()).isFalse();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {64, 256, 512, 1024})
    void fullWrappedWindowKeepsSymbolAndOrderDependenciesInDifferentWords(int capacity) {
        var window = new ClusterCommandWindow(capacity);
        for (int i = 0; i < capacity - 17; i++) {
            window.resetCandidate(0); window.add(null, null, 0, 0); window.removePrefix(1);
        }
        for (int i = 0; i < capacity; i++) {
            window.resetCandidate(0);
            window.candidateOrder(i % 65 + 1, "SYMBOL-" + i, null, 0);
            window.add(null, null, 0, 0).sequence = 1000 + i;
        }
        assertThat(window.capacity()).isEqualTo(capacity);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> window.add(null, null, 0, 0))
                .isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < capacity; i++) {
            window.resetCandidate(0); window.candidateOrder(99999, "SYMBOL-" + i, null, 0);
            assertThat(window.conflictingPrefixSize()).isEqualTo(i + 1);
        }
        window.removePrefix(capacity - 1);
        window.resetCandidate(0); window.candidateOrder((capacity - 1) % 65 + 1);
        assertThat(window.conflictingPrefixSize()).isOne();
        assertThat(window.get(0).sequence).isEqualTo(1000 + capacity - 1);
        window.clear();
        assertThat(window.conflicts()).isFalse();
    }

    @Test
    void rejectsCapacitiesThatAliasOrExceedBoundedWindow() {
        for (int value : new int[]{0, 32, 65, 192, 2048})
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClusterCommandWindow(value))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateOrderAcrossSlotsRetainsNewestDependencyUntilBothAreRemoved() {
        var w = new ClusterCommandWindow();
        for (int i = 0; i < 3; i++) {
            w.resetCandidate(0); w.candidateOrder(i == 1 ? 9 : 7); w.add(null, null, 0, 0);
        }
        w.resetCandidate(0); w.candidateOrder(7);
        assertThat(w.conflictingPrefixSize()).isEqualTo(3);
        w.removePrefix(2);
        assertThat(w.conflictingPrefixSize()).isOne();
        w.removePrefix(1);
        assertThat(w.conflictingPrefixSize()).isZero();
    }

    @Test
    void indexedDependencySlotsKeepNewestPrefixAcrossRepeatedRingWraps() {
        var window = new ClusterCommandWindow();
        long next = 1;
        for (int round = 0; round < 5; round++) {
            long first = next;
            for (int i = 0; i < 64; i++) {
                window.resetCandidate(0); window.candidateOrder(next++);
                window.add(null, null, 0, 0);
            }
            for (int i = 0; i < 64; i++) {
                window.resetCandidate(0); window.candidateOrder(first + i);
                assertThat(window.conflictingPrefixSize()).isEqualTo(i + 1);
            }
            window.removePrefix(31);
            window.resetCandidate(0); window.candidateOrder(first + 30);
            assertThat(window.conflictingPrefixSize()).isZero();
            window.resetCandidate(0); window.candidateOrder(first + 63);
            assertThat(window.conflictingPrefixSize()).isEqualTo(33);
            window.clear();
        }
    }

    @Test
    void earlierFillKeepsTheOpenInterestAdmissionDependencyAcrossDifferentUsers() {
        var window = new ClusterCommandWindow();
        window.resetCandidate(0);
        window.candidateOrder(1, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 100, true);
        window.add(null, null, 0, 0);
        window.resetCandidate(0);
        window.candidateOrder(2, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 90);
        assertThat(window.conflictingPrefixSize()).isOne();
        window.removePrefix(1);
        assertThat(window.conflictingPrefixSize()).isZero();
    }

    @Test
    void sameSymbolProvisionalLiquidityOnlyFencesCrossingOrCancellationScopes() {
        var window = new ClusterCommandWindow();
        window.resetCandidate(0);
        window.candidateOrder(1, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 100);
        window.add(null, null, 0, 0);
        window.resetCandidate(0);
        window.candidateOrder(2, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.SELL, 101);
        assertThat(window.conflictingPrefixSize()).isZero();
        window.resetCandidate(0);
        window.candidateOrder(3, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.SELL, 100);
        assertThat(window.conflictingPrefixSize()).isOne();
        window.resetCandidate(0);
        window.candidateOrder(4, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.SELL, 0);
        assertThat(window.conflictingPrefixSize()).isOne();
        window.resetCandidate(0);
        window.candidateOrder(5, "BTC-USDT", null, 0);
        assertThat(window.conflictingPrefixSize()).isOne();
    }

    @Test
    void identicalRangesAreVisitedOnceButEveryOrderIdentityStillConflicts() {
        var window = new ClusterCommandWindow();
        window.resetCandidate(7);
        for (long id = 1; id <= 20; id++) window.candidateOrder(id, "BTC-USDT",
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 100);
        var entry = window.add(null, null, 0, 0);
        assertThat(entry.scopeCount).isOne();
        assertThat(java.util.Arrays.stream(entry.orders).filter(id -> id != 0).count()).isEqualTo(20);
        for (long id = 1; id <= 20; id++) {
            window.resetCandidate(0);
            window.candidateOrder(id);
            assertThat(window.conflicts()).isTrue();
        }
        window.clear();
        window.resetCandidate(0);
        window.candidateOrder(21, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 100);
        window.candidateOrder(22, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 101);
        window.candidateOrder(23, "BTC-USDT", com.surprising.aeron.protocol.CoreOrderSide.SELL, 100);
        window.candidateOrder(24, "ETH-USDT", com.surprising.aeron.protocol.CoreOrderSide.BUY, 100);
        assertThat(window.add(null, null, 0, 0).scopeCount).isEqualTo(4);
    }

    @Test
    void exactOrderTableHandlesCollisionsAndWraparoundWithoutRetainingRemovedEntries() {
        var window = new ClusterCommandWindow();
        long[] ids = new long[21];
        long next = 1;
        for (int i = 0; i < ids.length; i++) {
            while (TradingDependencyMask.partition(next) != 63) next++;
            ids[i] = next++;
        }
        window.resetCandidate(0);
        for (int i = 0; i < 20; i++) window.candidateOrder(ids[i]);
        var entry = window.add(null, null, 0, 0);
        for (int i = 0; i < ids.length; i++) {
            window.resetCandidate(0);
            window.candidateOrder(ids[i]);
            assertThat(window.conflicts()).isEqualTo(i < 20);
        }
        window.removePrefix(1);
        assertThat(entry.orderCount).isZero();
        assertThat(window.conflicts()).isFalse();
    }
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
