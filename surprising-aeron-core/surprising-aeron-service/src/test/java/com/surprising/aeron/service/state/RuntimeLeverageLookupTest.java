package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLeverageLookupTest {
    @Test
    void blockedLaneLookupSurvivesAnotherLanesLookupAndCleanup() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long blockedUser = 8, candidate = 9;
            while (runtime.accountLaneId(candidate) == runtime.accountLaneId(blockedUser)) candidate++;
            long otherUser = candidate;
            runtime.putLeverage(new CoreLeverageKey(blockedUser, "1", CoreMarginMode.CROSS), 2_000_000);
            runtime.putLeverage(new CoreLeverageKey(otherUser, "2", CoreMarginMode.ISOLATED), 3_000_000);
            var blockedEntered = new CountDownLatch(1);
            var otherFinished = new CountDownLatch(1);
            AccountLaneState blockedLane = runtime.accountLanes[runtime.accountLaneId(blockedUser)];
            var original = blockedLane.cold.leverages;
            var blockedLookup = new java.util.concurrent.ConcurrentHashMap<CoreLeverageKey, Long>(original) {
                @Override public Long get(Object key) {
                    blockedEntered.countDown();
                    await(otherFinished);
                    return super.get(key);
                }
            };
            var field = LaneColdState.class.getDeclaredField("leverages");
            field.setAccessible(true);
            field.set(blockedLane.cold, blockedLookup);
            runtime.startAccountLanes();
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.leverage(blockedUser, "1", CoreMarginMode.CROSS))
                        .isInstanceOf(IllegalStateException.class);
                long mask = runtime.topology().accountLaneMask(blockedUser) | runtime.topology().accountLaneMask(otherUser);
                runtime.dispatchControlLanes(mask, lane -> {
                    if (lane == runtime.accountLaneId(blockedUser))
                        return runtime.leverage(blockedUser, "1", CoreMarginMode.CROSS);
                    await(blockedEntered);
                    try { return runtime.leverage(otherUser, "2", CoreMarginMode.ISOLATED); }
                    finally { otherFinished.countDown(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.pollControlLanes()) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("Lane lookup did not finish");
                    Thread.onSpinWait();
                }
                assertThat(runtime.controlLaneResult(runtime.accountLaneId(otherUser))).isEqualTo(3_000_000L);
                assertThat(runtime.controlLaneResult(runtime.accountLaneId(blockedUser))).isEqualTo(2_000_000L);
            } finally {
                blockedEntered.countDown();
                otherFinished.countDown();
                field.set(blockedLane.cold, original);
            }
        }
    }

    @Test
    void activeLanesReuseTheirOwnLookupAndUpdateBalancesWithoutOwnerAccess() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            long first = 7, candidate = 8;
            while (runtime.accountLaneId(candidate) == runtime.accountLaneId(first)) candidate++;
            long second = candidate;
            for (long user : new long[]{first, second}) {
                runtime.putUser(new com.surprising.aeron.service.state.account.UserRuntime(user));
                runtime.putBalance(new com.surprising.aeron.service.state.account.BalanceRuntime(user, 3, 1000, 0));
            }
            runtime.putLeverage(new CoreLeverageKey(first, "1", CoreMarginMode.CROSS), 2_000_000);
            runtime.putLeverage(new CoreLeverageKey(second, "2", CoreMarginMode.ISOLATED), 3_000_000);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.leverage(first, "1", CoreMarginMode.CROSS))
                    .isInstanceOf(IllegalStateException.class);
            var entered = new CountDownLatch(2);
            long mask = runtime.topology().accountLaneMask(first) | runtime.topology().accountLaneMask(second);
            runtime.dispatchControlLanes(mask, lane -> {
                entered.countDown(); await(entered);
                boolean isFirst = lane == runtime.accountLaneId(first);
                long user = isFirst ? first : second;
                String symbol = isFirst ? "1" : "2";
                CoreMarginMode mode = isFirst ? CoreMarginMode.CROSS : CoreMarginMode.ISOLATED;
                for (int i = 0; i < 256; i++) {
                    assertThat(runtime.leverage(user, symbol, mode)).isEqualTo(isFirst ? 2_000_000L : 3_000_000L);
                    assertThat(runtime.leverage(user, symbol, mode == CoreMarginMode.CROSS
                            ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS)).isNull();
                    runtime.replaceBalance(user, 3, 900, 100);
                }
                assertThat(runtime.balance(user, 3).availableUnits()).isEqualTo(900);
                assertThat(runtime.balance(user, 3).lockedUnits()).isEqualTo(100);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.replaceBalance(
                        isFirst ? second : first, 3, 0, 0)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("balance write crossed its owner Lane");
                return runtime.leverage(user, symbol, mode);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Lane reads/writes did not finish");
                Thread.onSpinWait();
            }
            assertThat(runtime.controlLaneResult(runtime.accountLaneId(first))).isEqualTo(2_000_000L);
            assertThat(runtime.controlLaneResult(runtime.accountLaneId(second))).isEqualTo(3_000_000L);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("lookup handoff timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
