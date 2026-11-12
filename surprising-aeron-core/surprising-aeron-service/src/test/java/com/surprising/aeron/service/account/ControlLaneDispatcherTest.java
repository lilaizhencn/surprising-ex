package com.surprising.aeron.service.account;

import com.surprising.aeron.service.core.AccountLaneQueryTestSupport;
import com.surprising.aeron.service.core.CoreStateTestFixtures;
import com.surprising.aeron.service.core.AccountBook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 用实际常驻账户工作线程与门闩验证控制任务交接、结果收集、拒绝传播以及下一任务复用；没有通过提前读账户表代替完成回执。
 */
class ControlLaneDispatcherTest {
    /**
     * 同时阻塞两条常驻 Lane，确认 Owner 不能提前取结果或再派发；放行后核对返回值，再派单条 Lane 确认旧目标结果不再可读。
     */
    @Test
    void controlTasksExecuteOnPermanentWorkersAndPublishOnlyAfterCompletion() throws Exception {
        try (/* 本场景独立运行时，作用域内启动实际账户工作线程，结束自动关闭。 */ var accountBook = new AccountBook(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
            accountBook.startAccountLanes();
            // 发起控制命令的测试线程，用于核对回调确实在常驻 Lane 线程执行。
            Thread owner = Thread.currentThread();
            // 计数为二的门闩，确认两条目标 Lane 都已进入。
            var entered = new java.util.concurrent.CountDownLatch(2);
            // 控制仍在执行的 Lane 何时放行的门闩，finally 中保证释放。
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                accountBook.dispatchControlLanes(3, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> {
                    assertThat(Thread.currentThread()).isNotSameAs(owner);
                    assertThat(Thread.currentThread().getName()).isEqualTo("core-account-lane-" + lane);
                    entered.countDown();
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("control task was not released");
                    } catch (/* 等待放行时发生的中断，恢复中断标记并转为断言失败。 */ InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return lane + 10;
                });
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(accountBook.pollControlLanes()).isFalse();
                assertThatThrownBy(() -> accountBook.controlLaneResult(0)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> accountBook.dispatchControlLanes(1, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> lane))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
            }
            awaitControl(accountBook);
            assertThat(accountBook.controlLaneResult(0)).isEqualTo(10);
            assertThat(accountBook.controlLaneResult(1)).isEqualTo(11);
            accountBook.dispatchControlLanes(1, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> lane + 20);
            awaitControl(accountBook);
            assertThat(accountBook.controlLaneResult(0)).isEqualTo(20);
            assertThatThrownBy(() -> accountBook.controlLaneResult(1)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 一条 Lane 立即业务拒绝、另一条继续阻塞，要求 Owner 等全部完成后才抛拒绝，工作线程保持健康并能执行下一命令。
     */
    @Test
    void businessRejectionWaitsForOtherLanesAndDoesNotPoisonTheNextCommand() throws Exception {
        try (/* 本场景独立运行时，作用域内启动实际账户工作线程，结束自动关闭。 */ var accountBook = new AccountBook(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
            accountBook.startAccountLanes();
            // 计数为二的门闩，确认两条目标 Lane 都已进入。
            var entered = new java.util.concurrent.CountDownLatch(2);
            // 控制仍在执行的 Lane 何时放行的门闩，finally 中保证释放。
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                accountBook.dispatchControlLanes(3, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> {
                    entered.countDown();
                    if (lane == 0) throw new com.surprising.aeron.exception.CoreStateRejectedException(
                            "TEST_REJECTION", "account command rejected");
                    try {
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("other Lane was not released");
                    } catch (/* 等待放行时发生的中断，恢复中断标记并转为断言失败。 */ InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return lane;
                });
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(accountBook.pollControlLanes()).isFalse();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> awaitControl(accountBook))
                    .isInstanceOf(com.surprising.aeron.exception.CoreStateRejectedException.class)
                    .hasMessage("account command rejected");
            accountBook.assertAccountLanesHealthy();
            accountBook.dispatchControlLanes(1, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> 42);
            awaitControl(accountBook);
            assertThat(accountBook.controlLaneResult(0)).isEqualTo(42);
        }
    }

    /**
     * 由所属 Lane 建立真实订单及资金预留，Owner 收齐后经账户查询保护范围核对订单、可用余额和预留金额。
     */
    @Test
    void reservationAndAccountStateAccessibleAfterDispatch() {
        try (/* 本场景独立运行时，作用域内启动实际账户工作线程，结束自动关闭。 */ var accountBook = new AccountBook()) {
            accountBook.putUser(new UserAccount(7));
            accountBook.putBalance(new com.surprising.aeron.service.account.AccountBalance(7, 3, 1000, 0));
            accountBook.clearChangedKeys();
            accountBook.startAccountLanes();
            // 用户七实际所属的 Lane 编号，只向该分片派发账户操作。
            int laneId = accountBook.topology().accountLaneId(7);
            accountBook.dispatchControlLanes(1L << laneId, /* 回调传入的 Lane 编号，本用例按固定用户七处理，不读取此参数。 */ ignored -> {
                CoreStateTestFixtures.reserveOrder(accountBook, 11, 7, 91, 5, 2, 3, 200);
                return null;
            });
            awaitControl(accountBook);
            assertThat(AccountLaneQueryTestSupport.read(accountBook, 7, () -> accountBook.order(11).orderId())).isEqualTo(11);
            assertThat(AccountLaneQueryTestSupport.read(accountBook, 7, () -> accountBook.balance(7, 3).availableUnits())).isEqualTo(800);
            assertThat(AccountLaneQueryTestSupport.read(accountBook, 7, () -> accountBook.reservation(11).reservedUnits())).isEqualTo(200);
        }
    }

    /**
     * 按默认拓扑要求空位图和超出合法 Lane 范围的全位图派发失败。
     */
    @Test
    void invalidDispatchThrowsIllegalStateException() {
        try (/* 本场景独立运行时，作用域内启动实际账户工作线程，结束自动关闭。 */ var accountBook = new AccountBook(com.surprising.aeron.service.core.CoreStateTestFixtures.DEFAULT_TOPOLOGY)) {
            accountBook.startAccountLanes();
            assertThatThrownBy(() -> accountBook.dispatchControlLanes(0, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> lane))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> accountBook.dispatchControlLanes(-1L, /* 控制派发回调接收的目标 Lane 编号。 */ lane -> lane))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 轮询控制任务完成，使用单调时钟等待最多五秒，超时使测试失败。
     * @param runtime 需要等待控制派发收齐完成回执的运行时
     */
    private static void awaitControl(AccountBook accountBook) {
        // 控制任务完成等待的五秒单调时钟截止时间。
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!accountBook.pollControlLanes()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("control work did not complete");
            Thread.onSpinWait();
        }
    }
}
