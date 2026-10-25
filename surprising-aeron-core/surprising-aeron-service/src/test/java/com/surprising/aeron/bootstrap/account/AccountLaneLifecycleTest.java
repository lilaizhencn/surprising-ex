package com.surprising.aeron.bootstrap.account;

import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.account.AccountLaneState;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountLaneLifecycleTest {
    @Test void partialStartupFailureStopsAlreadyStartedLanes() {
        var topology = LaneTopology.productionDefault();
        var lanes = new AccountLaneState[topology.accountLaneCount()];
        lanes[0] = new AccountLaneState(0, topology.accountLaneQueueCapacity());
        // 第二条 Lane 的非法输入模拟创建中途失败；第一条工作线程已经实际启动。
        var lifecycle = new AccountLaneLifecycle(lanes, topology, CoreRuntimeConfiguration.standalone(),
                () -> { });
        assertThatThrownBy(() -> lifecycle.start(TradingCpuAffinity.disabled()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lifecycle.started()).isFalse();
        assertThat(lifecycle.worker(0).thread().isAlive()).isFalse();
        lifecycle.close();
    }

    @Test void ownershipHandoffRemainsOnTheSameLanes() {
        var topology = LaneTopology.productionDefault();
        var lanes = new AccountLaneState[topology.accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++)
            lanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
        try (var lifecycle = new AccountLaneLifecycle(lanes, topology, CoreRuntimeConfiguration.standalone(),
                () -> { })) {
            lifecycle.start(TradingCpuAffinity.disabled());
            assertThat(lifecycle.ownerHasAccess()).isFalse();
            while (!lifecycle.tryAcquireOwnerAccess()) Thread.onSpinWait();
            assertThat(lifecycle.handoffEpoch()).isEqualTo(1);
            for (var lane : lanes) lane.assertOwner();
            lifecycle.releaseOwnerAccess();
            assertThat(lifecycle.ownerHasAccess()).isFalse();
            while (!lifecycle.tryAcquireOwnerAccess()) Thread.onSpinWait();
            assertThat(lifecycle.handoffEpoch()).isEqualTo(2);
        }
    }
    @Test void ownershipReleaseFailureStillStopsAllWorkers() {
        var topology = LaneTopology.productionDefault();
        var lanes = new AccountLaneState[topology.accountLaneCount()];
        for (int laneId = 0; laneId < lanes.length; laneId++)
            lanes[laneId] = new AccountLaneState(laneId, topology.accountLaneQueueCapacity());
        var rejectOwner = new java.util.concurrent.atomic.AtomicBoolean();
        var first = new IllegalStateException("ownership release failed");
        var lifecycle = new AccountLaneLifecycle(lanes, topology, CoreRuntimeConfiguration.standalone(),
                () -> { if (rejectOwner.get()) throw first; });
        lifecycle.start(TradingCpuAffinity.disabled());
        rejectOwner.set(true);
        assertThatThrownBy(lifecycle::close).hasCause(first);
        assertThat(lifecycle.workersStopped()).isTrue();
    }

}
