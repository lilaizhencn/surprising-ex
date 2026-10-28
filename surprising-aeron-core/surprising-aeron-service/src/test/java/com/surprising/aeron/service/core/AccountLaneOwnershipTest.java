package com.surprising.aeron.service.core;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.service.account.AccountLaneState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

/**
 * 账户实际工作线程、临时 Owner 交接和原故障传播的边界测试。
 */
@Timeout(10)
class AccountLaneOwnershipTest {
    /**
     * 派发真实失败任务并等待健康检查观察到原异常，再显式关闭运行时，断言关闭包装仍保留原 cause。
     */
    @Test
    void failedWorkerKeepsItsOriginalCauseWhenRuntimeIsClosed() {
        // 本例账户运行时，按当前显式或资源关闭路径结束工作资源
        var runtime = new TradingAccountRuntime(LaneTopology.characterization());
        runtime.startAccountLanes();
        // 真实工作任务故意抛出的原运行时异常
        var original = new IllegalStateException("worker failure");
        assertThat(runtime.dispatchAccountQuery(1, /* 派发任务收到但本故障场景不使用的账户参数 */ ignored -> { throw original; })).isTrue();
        // 等待交接或原故障可见的单调纳秒截止时间
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        // 测试线程已经从健康检查捕获到的可空原异常
        RuntimeException observed = null;
        while (observed == null) {
            try { runtime.assertAccountLanesHealthy(); }
            catch (/* 本次健康检查抛出的异常，保存后核对对象身份 */ RuntimeException failure) { observed = failure; }
            if (System.nanoTime() >= deadline) throw new AssertionError("worker failure timeout");
            Thread.onSpinWait();
        }
        assertThat(observed).isSameAs(original);
        assertThatThrownBy(runtime::close).isInstanceOf(IllegalStateException.class).hasCause(original);
    }

    /**
     * 工作线程启动后外部直接读取账户被拒绝，所属线程查询已有用户成功；关闭后冷调用者可以重新取得读取所有权。
     */
    @Test
    void workerOwnershipRejectsForeignReadsAndReturnsToColdOwnerAfterClose() throws Exception {
        // 本例原账户状态，启动前登记用户，交接期间由明确拥有者访问
        var lane = new AccountLaneState(0, 16);
        lane.registerUser(7);
        // 所属账户线程读取完成的门闩
        var done = new CountDownLatch(1);
        try (/* 本例实际账户工作对象，资源退出时关闭 */ var worker = new SettlementLaneWorker("ownership", lane, 16)) {
            assertThatThrownBy(lane::userCount).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> lane.pendingReservation(1)).isInstanceOf(IllegalStateException.class);
            worker.submit(/* 已在真实账户线程中取得的所属 Lane，按范围读取或修改 */ owned -> { assertThat(owned.owns(7)).isTrue(); done.countDown(); });
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            worker.assertHealthy();
        }
        assertThat(lane.userCount()).isOne();
    }

    /**
     * 活动 Owner 没有交接权限时，直接账户范围及多种业务读取均拒绝；把查询派发到实际工作线程后可以在只读范围执行。
     */
    @Test
    void activeRuntimeRejectsOwnerScopesAndReadsButDispatchesQueriesToActualWorker() throws Exception {
        try (/* 本例账户运行时，按当前显式或资源关闭路径结束工作资源 */ var runtime = new TradingAccountRuntime(LaneTopology.characterization())) {
            runtime.bindOwner();
            runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.onLane(0, /* 本例原账户状态，启动前登记用户，交接期间由明确拥有者访问 */ lane -> lane.userCount())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.enterLaneCommandScope(runtime.accountLanes[0])).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.user(7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.reservation(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.triggerOrder(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.algoOrder(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.liquidation(1)).isInstanceOf(IllegalStateException.class);
            // 所属账户线程读取完成的门闩
            var done = new CountDownLatch(1);
            assertThat(runtime.dispatchAccountQuery(1, /* 本例原账户状态，启动前登记用户，交接期间由明确拥有者访问 */ lane -> {
                runtime.readAccountLane(lane, /* 已在真实账户线程中取得的所属 Lane，按范围读取或修改 */ owned -> { assertThat(runtime.user(7)).isNull(); return null; });
                done.countDown();
            })).isTrue();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            runtime.assertAccountLanesHealthy();
        }
    }

    /**
     * 等交接完成后测试线程绑定账户，投递准入并观察 50 毫秒内不执行；finally 归还并恢复后，准入才登记第二名用户。
     */
    @Test
    void handoffPausesAdmissionsUntilColdOwnerReleasesTheLane() throws Exception {
        // 本例原账户状态，启动前登记用户，交接期间由明确拥有者访问
        var lane = new AccountLaneState(0, 16);
        lane.registerUser(7);
        try (/* 本例实际账户工作对象，资源退出时关闭 */ var worker = new SettlementLaneWorker("handoff", lane, 16)) {
            worker.requestHandoff(1);
            // 等待交接或原故障可见的单调纳秒截止时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!worker.handoffReady(1)) {
                worker.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("handoff timeout");
                Thread.onSpinWait();
            }
            lane.assertOwner();
            // 交接恢复后准入登记新用户的完成门闩
            var admitted = new CountDownLatch(1);
            try {
                worker.submitAdmission(/* 已在真实账户线程中取得的所属 Lane，按范围读取或修改 */ owned -> { owned.registerUser(8); admitted.countDown(); });
                assertThat(admitted.await(50, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(lane.userCount()).isOne();
            } finally { lane.releaseOwnerForHandoff(); worker.resumeHandoff(1); }
            assertThat(admitted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.assertHealthy();
        }
        assertThat(lane.userCount()).isEqualTo(2);
    }
}
