package com.surprising.aeron.service.state;

import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.aeron.service.state.account.AccountLaneState;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeExecutionScopeTest {
    @Test
    void ownerCompatibilityScopeCannotClearTheWorkersActiveScope() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            runtime.startAccountLanes();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var ownerFinished = new java.util.concurrent.CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(1, ignored -> {
                    AccountLaneState owned = runtime.currentLaneCommandScope();
                    entered.countDown();
                    try {
                        if (!ownerFinished.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owner scope did not finish");
                    } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                    assertThat(runtime.currentLaneCommandScope()).isSameAs(owned);
                    return owned.laneId();
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                // Only the execution context is exercised here; no Lane business state is read.
                runtime.ownerLaneAccess = true;
                try {
                    runtime.inLaneCommandScope(runtime.accountLanes[0], lane -> null);
                } finally {
                    runtime.ownerLaneAccess = false;
                }
            } finally { ownerFinished.countDown(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.pollControlLanes()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("worker scope did not finish");
                Thread.onSpinWait();
            }
            assertThat(runtime.controlLaneResult(0)).isEqualTo(0);
            assertThat(runtime.currentLaneCommandScope()).isNull();
        }
    }

    @Test
    void unrelatedReaderCannotInheritAnActiveOwnerLaneScope() throws Exception {
        try (var runtime = new TradingRuntimeState(LaneTopology.productionDefault())) {
            var failure = new AtomicReference<Throwable>();
            runtime.inLaneCommandScope(runtime.accountLanes[0], lane -> {
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        assertThat(runtime.currentLaneCommandScope()).isNull();
                        assertThat(runtime.currentMatcherSettlementChangesScope()).isNull();
                        assertThatThrownBy(runtime::assertOwner)
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> runtime.inLaneCommandScope(runtime.accountLanes[1], ignored -> null))
                                .isInstanceOf(IllegalStateException.class);
                    } catch (Throwable thrown) { failure.set(thrown); }
                });
                try { reader.join(TimeUnit.SECONDS.toMillis(5)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                assertThat(reader.isAlive()).isFalse();
                assertThat(runtime.currentLaneCommandScope()).isSameAs(lane);
                return null;
            });
            assertThat(failure.get()).isNull();
            assertThat(runtime.currentLaneCommandScope()).isNull();
        }
    }
}
