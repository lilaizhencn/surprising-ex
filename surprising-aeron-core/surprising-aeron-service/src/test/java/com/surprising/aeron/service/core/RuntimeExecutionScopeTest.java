package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.service.account.AccountLaneState;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证账户命令执行作用域按实际线程隔离：Owner 临时作用域不清工作线程作用域，普通读线程也不会继承 Owner 访问权限。不在临时 Owner 测试范围中读取账户业务状态。
 */
class RuntimeExecutionScopeTest {
    /**
     * 让真实 Lane 工作线程在自己的作用域等待，同时 Owner 进入并退出临时作用域；放行后核对工作线程作用域仍在，完成后 Owner 无残留。各等待使用五秒预算。
     */
    @Test
    void ownerCompatibilityScopeCannotClearTheWorkersActiveScope() throws Exception {
        try (/* 当前生产拓扑的测试运行时，用例退出时自动关闭 */ var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            // 工作 Lane 进入自身命令作用域后通知 Owner 的门闩
            var entered = new java.util.concurrent.CountDownLatch(1);
            // Owner 临时作用域退出后放行工作线程的门闩
            var ownerFinished = new java.util.concurrent.CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(1, /* 当前控制 Lane 或试图进入的另一 Lane，本回调不读取业务正文 */ ignored -> {
                    // 工作线程进入时保存的实际账户作用域，Owner 操作后应仍为同一实例
                    AccountLaneState owned = runtime.currentLaneCommandScope();
                    entered.countDown();
                    try {
                        if (!ownerFinished.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owner scope did not finish");
                    } catch (/* 等待门闩或普通线程退出时的中断，转为测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    assertThat(runtime.currentLaneCommandScope()).isSameAs(owned);
                    return owned.laneId();
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                // 这里只验证线程执行作用域，不在临时 Owner 范围内读取账户业务状态。
                runtime.ownerLaneAccess = true;
                try {
                    runtime.inLaneCommandScope(runtime.accountLanes[0], /* Owner 当前临时作用域所属 Lane，用于核对退出前没有被其他线程覆盖 */ lane -> null);
                } finally {
                    runtime.ownerLaneAccess = false;
                }
            } finally { ownerFinished.countDown(); }
            // 轮询工作 Lane 完成的单调时钟截止值，最多五秒
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("worker scope did not finish");
                Thread.onSpinWait();
            }
            assertThat(runtime.controlLaneResult(0)).isEqualTo(0);
            assertThat(runtime.currentLaneCommandScope()).isNull();
        }
    }

    /**
     * Owner 在冷作用域内新建普通线程，检查它看不到账户或成交作用域，也不能取得 Owner 权限或进入另一 Lane；线程结束后原 Owner 作用域仍完整并最终清空。
     */
    @Test
    void unrelatedReaderCannotInheritAnActiveOwnerLaneScope() throws Exception {
        try (/* 当前生产拓扑的测试运行时，用例退出时自动关闭 */ var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            // 把普通读取线程的断言异常带回 Owner 测试线程的原子槽
            var failure = new AtomicReference<Throwable>();
            runtime.inLaneCommandScope(runtime.accountLanes[0], /* Owner 当前临时作用域所属 Lane，用于核对退出前没有被其他线程覆盖 */ lane -> {
                // 在 Owner 作用域仍活跃时创建的普通平台线程，不应继承任何业务权限
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        assertThat(runtime.currentLaneCommandScope()).isNull();
                        assertThat(runtime.currentMatcherSettlementChangesScope()).isNull();
                        assertThatThrownBy(runtime::assertOwner)
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> runtime.inLaneCommandScope(runtime.accountLanes[1], /* 当前控制 Lane 或试图进入的另一 Lane，本回调不读取业务正文 */ ignored -> null))
                                .isInstanceOf(IllegalStateException.class);
                    } catch (/* 普通读取线程捕获的断言或门禁异常，保存后让测试线程核对 */ Throwable thrown) { failure.set(thrown); }
                });
                try { reader.join(TimeUnit.SECONDS.toMillis(5)); }
                catch (/* 等待门闩或普通线程退出时的中断，转为测试失败 */ InterruptedException interrupted) { throw new AssertionError(interrupted); }
                assertThat(reader.isAlive()).isFalse();
                assertThat(runtime.currentLaneCommandScope()).isSameAs(lane);
                return null;
            });
            assertThat(failure.get()).isNull();
            assertThat(runtime.currentLaneCommandScope()).isNull();
        }
    }
}
