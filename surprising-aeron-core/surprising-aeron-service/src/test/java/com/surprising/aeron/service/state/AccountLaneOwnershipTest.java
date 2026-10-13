package com.surprising.aeron.service.state;

import com.surprising.aeron.service.lane.SettlementLaneWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class AccountLaneOwnershipTest {
    @Test
    void failedWorkerKeepsItsOriginalCauseWhenRuntimeIsClosed() {
        var runtime = new TradingRuntimeState(LaneTopology.characterization());
        runtime.startAccountLanes();
        var original = new IllegalStateException("worker failure");
        assertThat(runtime.dispatchAccountQuery(1, ignored -> { throw original; })).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        RuntimeException observed = null;
        while (observed == null) {
            try { runtime.assertAccountLanesHealthy(); }
            catch (RuntimeException failure) { observed = failure; }
            if (System.nanoTime() >= deadline) throw new AssertionError("worker failure timeout");
            Thread.onSpinWait();
        }
        assertThat(observed).isSameAs(original);
        assertThatThrownBy(runtime::close).isInstanceOf(IllegalStateException.class).hasCause(original);
    }

    @Test
    void workerOwnershipRejectsForeignReadsAndReturnsToColdOwnerAfterClose() throws Exception {
        var lane = new AccountLaneState(0, 16);
        lane.registerUser(7);
        var done = new CountDownLatch(1);
        try (var worker = new SettlementLaneWorker("ownership", lane, 16)) {
            assertThatThrownBy(lane::userCount).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> lane.pendingReservation(1)).isInstanceOf(IllegalStateException.class);
            worker.submit(owned -> { assertThat(owned.owns(7)).isTrue(); done.countDown(); });
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            worker.assertHealthy();
        }
        assertThat(lane.userCount()).isOne();
    }

    @Test
    void activeRuntimeRejectsOwnerScopesAndReadsButDispatchesQueriesToActualWorker() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.characterization())) {
            runtime.bindOwner();
            runtime.startAccountLanes();
            assertThatThrownBy(() -> runtime.onLane(0, lane -> lane.userCount())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.enterLaneCommandScope(runtime.accountLanes[0])).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.user(7)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.order(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.reservation(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.position(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.triggerOrder(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.algoOrder(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> runtime.liquidation(1)).isInstanceOf(IllegalStateException.class);
            var done = new CountDownLatch(1);
            assertThat(runtime.dispatchAccountQuery(1, lane -> {
                runtime.readAccountLane(lane, owned -> { assertThat(runtime.user(7)).isNull(); return null; });
                done.countDown();
            })).isTrue();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            runtime.assertAccountLanesHealthy();
        }
    }

    @Test
    void handoffPausesAdmissionsUntilColdOwnerReleasesTheLane() throws Exception {
        var lane = new AccountLaneState(0, 16);
        lane.registerUser(7);
        try (var worker = new SettlementLaneWorker("handoff", lane, 16)) {
            worker.requestHandoff(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!worker.handoffReady(1)) {
                worker.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("handoff timeout");
                Thread.onSpinWait();
            }
            lane.bindOwner();
            var admitted = new CountDownLatch(1);
            try {
                worker.submitAdmission(owned -> { owned.registerUser(8); admitted.countDown(); });
                assertThat(admitted.await(50, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(lane.userCount()).isOne();
            } finally { lane.releaseOwnerForHandoff(); worker.resumeHandoff(1); }
            assertThat(admitted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.assertHealthy();
        }
        assertThat(lane.userCount()).isEqualTo(2);
    }
}
