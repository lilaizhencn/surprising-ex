package com.surprising.aeron.service.account;

import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用实际 Lane 并发与受控阻塞验证可复用杠杆查找槽按 Lane 隔离，并检查余额写入不能越过账户所有者；循环复用断言不等同于分配采样。
 */
class RuntimeLeverageLookupTest {
    /**
     * 替换一条 Lane 的杠杆表，在实际 get 中阻塞到另一条 Lane 查找结束，确认另一条查找及清理不会改坏被阻塞的查找键，最后恢复原表。
     */
    @Test
    void blockedLaneLookupSurvivesAnotherLanesLookupAndCleanup() throws Exception {
        try (/* 测试使用默认拓扑的独立运行时，结束关闭常驻账户线程。 */ var runtime = new TradingAccountRuntime(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
            // 在杠杆表读取中主动阻塞的用户编号。
            // 用于寻找与参照用户落在不同 Lane 的候选用户编号。
            long blockedUser = 8, candidate = 9;
            while (runtime.accountLaneId(candidate) == runtime.accountLaneId(blockedUser)) candidate++;
            // 确定落到另一条 Lane 的用户编号，供被阻塞查找的并发对照。
            long otherUser = candidate;
            runtime.putLeverage(new CoreLeverageKey(blockedUser, "1", CoreMarginMode.CROSS), 2_000_000);
            runtime.putLeverage(new CoreLeverageKey(otherUser, "2", CoreMarginMode.ISOLATED), 3_000_000);
            // 通知被测试的实际 get 已经开始等待的门闩。
            var blockedEntered = new CountDownLatch(1);
            // 另一条 Lane 查找及清理完成后释放被阻塞读取的门闩。
            var otherFinished = new CountDownLatch(1);
            // 实际持有被阻塞用户的账户 Lane。
            AccountLaneState blockedLane = runtime.accountLane(runtime.accountLaneId(blockedUser));
            // 反射替换前的原杠杆表，在 finally 中恢复。
            var original = blockedLane.cold.leverages;
            // 带受控阻塞 get 的测试表，用来观察同一查询键在并发窗口中的稳定性。
            var blockedLookup = /* 测试专用的杠杆表，重写 get 在实际查找期间等待另一 Lane 完成，用于验证复用查找键不会跨 Lane 被覆盖。 */ new java.util.concurrent.ConcurrentHashMap<CoreLeverageKey, Long>(original) {
                /**
                 * 通知已进入当前键查找，等待另一条 Lane 完成后用同一个查找键读取原并发表，主动制造复用查找槽被覆盖的检测窗口。
                 * @param key 运行时传入的杠杆查找键对象，等待另一 Lane 执行后仍必须代表同一查询
                 */
                @Override public Long get(Object key) {
                    blockedEntered.countDown();
                    await(otherFinished);
                    return super.get(key);
                }
            };
            // LaneColdState 中杠杆表的反射字段，用于安装并恢复测试阻塞表。
            var field = LaneColdState.class.getDeclaredField("leverages");
            field.setAccessible(true);
            field.set(blockedLane.cold, blockedLookup);
            runtime.startAccountLanes();
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.leverage(blockedLane, blockedUser, "1", CoreMarginMode.CROSS))
                        .isInstanceOf(IllegalStateException.class);
                // 包含两个不同账户 Lane 的派发位集合。
                long mask = runtime.topology().accountLaneMask(blockedUser) | runtime.topology().accountLaneMask(otherUser);
                runtime.dispatchControlLanes(mask, /* 本次控制回调接收的实际 Lane 编号。 */ lane -> {
                    if (lane == runtime.accountLaneId(blockedUser))
                        return runtime.leverage(blockedLane, blockedUser, "1", CoreMarginMode.CROSS);
                    await(blockedEntered);
                    try { return runtime.leverage(runtime.accountLane(runtime.accountLaneId(otherUser)), otherUser, "2", CoreMarginMode.ISOLATED); }
                    finally { otherFinished.countDown(); }
                });
                // 当前控制派发最多等待五秒的单调时钟截止时间。
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

    /**
     * 两条不同 Lane 同时重复查已配置与未配置模式，并在本用户内写余额；Owner 直接读取及跨 Lane 修改另一用户必须失败。
     */
    @Test
    void activeLanesReuseTheirOwnLookupAndUpdateBalancesWithoutOwnerAccess() throws Exception {
        try (/* 测试使用默认拓扑的独立运行时，结束关闭常驻账户线程。 */ var runtime = new TradingAccountRuntime(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
            // 用于寻找与参照用户落在不同 Lane 的候选用户编号。
            // 第一个账户用户编号，作为寻找不同 Lane 的参照。
            long first = 7, candidate = 8;
            while (runtime.accountLaneId(candidate) == runtime.accountLaneId(first)) candidate++;
            // 确定属于另一条 Lane 的第二个用户编号。
            long second = candidate;
            for (long /* 当前初始化或回调操作的用户编号，每条 Lane 只能写自己账户。 */ user : new long[]{first, second}) {
                runtime.putUser(new com.surprising.aeron.service.account.UserRuntime(user));
                runtime.putBalance(new com.surprising.aeron.service.account.BalanceRuntime(user, 3, 1000, 0));
            }
            runtime.putLeverage(new CoreLeverageKey(first, "1", CoreMarginMode.CROSS), 2_000_000);
            runtime.putLeverage(new CoreLeverageKey(second, "2", CoreMarginMode.ISOLATED), 3_000_000);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.leverage(runtime.accountLane(runtime.accountLaneId(first)), first, "1", CoreMarginMode.CROSS))
                    .isInstanceOf(IllegalStateException.class);
            // 计数为二的门闩，要求两个 Lane 回调均已进入再开始重复查找。
            var entered = new CountDownLatch(2);
            // 包含两个不同账户 Lane 的派发位集合。
            long mask = runtime.topology().accountLaneMask(first) | runtime.topology().accountLaneMask(second);
            runtime.dispatchControlLanes(mask, /* 本次控制回调接收的实际 Lane 编号。 */ lane -> {
                entered.countDown(); await(entered);
                // 当前回调是否属于第一个用户，用于选择独立合约、模式和预期杠杆。
                boolean isFirst = lane == runtime.accountLaneId(first);
                // 当前初始化或回调操作的用户编号，每条 Lane 只能写自己账户。
                long user = isFirst ? first : second;
                // 当前 Lane 用户对应的业务合约编号文本。
                String symbol = isFirst ? "1" : "2";
                // 当前 Lane 用户已配置的全仓或逐仓保证金模式。
                CoreMarginMode mode = isFirst ? CoreMarginMode.CROSS : CoreMarginMode.ISOLATED;
                for (/* 当前 Lane 内重复查找和余额更新的轮次。 */ int i = 0; i < 256; i++) {
                    assertThat(runtime.leverage(runtime.accountLane(lane), user, symbol, mode)).isEqualTo(isFirst ? 2_000_000L : 3_000_000L);
                    assertThat(runtime.leverage(runtime.accountLane(lane), user, symbol, mode == CoreMarginMode.CROSS
                            ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS)).isNull();
                    runtime.replaceBalance(user, 3, 900, 100);
                }
                assertThat(runtime.balance(user, 3).availableUnits()).isEqualTo(900);
                assertThat(runtime.balance(user, 3).lockedUnits()).isEqualTo(100);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.replaceBalance(
                        isFirst ? second : first, 3, 0, 0)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("balance write crossed its owner Lane");
                return runtime.leverage(runtime.accountLane(lane), user, symbol, mode);
            });
            // 当前控制派发最多等待五秒的单调时钟截止时间。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Lane reads/writes did not finish");
                Thread.onSpinWait();
            }
            assertThat(runtime.controlLaneResult(runtime.accountLaneId(first))).isEqualTo(2_000_000L);
            assertThat(runtime.controlLaneResult(runtime.accountLaneId(second))).isEqualTo(3_000_000L);
        }
    }

    /**
     * 最多等待五秒指定门闩，超时失败；中断时恢复中断标记再抛出断言错误。
     * @param latch 需要在五秒内被另一执行方释放的同步门闩
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("lookup handoff timed out");
        } catch (/* 等待门闩时的中断原因，转为断言失败并恢复线程中断状态。 */ InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
