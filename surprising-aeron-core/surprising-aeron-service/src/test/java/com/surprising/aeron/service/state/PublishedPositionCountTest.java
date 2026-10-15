package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class PublishedPositionCountTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void coldAndWorkerMutationsPublishCountsOnlyWhenOwnerCollectsTheReceipt(ProductLine product) throws Exception {
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            runtime.putUser(new UserRuntime(product, 7, 0, com.surprising.aeron.protocol.CorePositionMode.ONE_WAY));
            runtime.putUser(new UserRuntime(product, 8, 0, com.surprising.aeron.protocol.CorePositionMode.ONE_WAY));
            runtime.putPosition(101, position(product, 7));
            runtime.putPosition(102, position(product, 8));
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            runtime.removePosition(102, 8);
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
            var mutated = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), ignored -> {
                    runtime.putPosition(103, position(product, 7));
                    mutated.countDown();
                    await(release);
                    return null;
                });
                assertThat(mutated.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(runtime.publishedPositionCount()).isOne();
                assertThat(runtime.pollControlLanes()).isFalse();
            } finally { release.countDown(); }
            awaitControl(runtime);
            // Active Owner has no Lane read fence here: the answer must use receipt metadata.
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            runtime.dispatchControlLanes(runtime.topology().accountLaneMask(7), ignored -> {
                runtime.removePosition(101, 7);
                runtime.removePosition(103, 7);
                return null;
            });
            awaitControl(runtime);
            assertThat(runtime.publishedPositionCount()).isZero();
        }
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void earlierReceiptCannotObservePositionsFromLaterLaneWorkAndClearedReceiptsCannotRepublish(ProductLine product) throws Exception {
        try (var runtime = RuntimeStateProjector.project(TradingCoreState.empty(product), new RuntimeIdentityRegistry())) {
            runtime.startAccountLanes();
            var receipts = new LaneCommitDelta[]{new LaneCommitDelta(), new LaneCommitDelta(), new LaneCommitDelta()};
            var completed = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            int laneId = runtime.topology().accountLaneId(7);
            assertThat(runtime.dispatchAccountQuery(1L << laneId, lane -> {
                try {
                    lane.positions.put(101, position(product, 7));
                    receipts[0].preparePositionRetirement(lane);
                    lane.positions.put(102, position(product, 7));
                    receipts[1].preparePositionRetirement(lane);
                    receipts[1].preparePublication();
                    lane.positions.remove(101);
                    receipts[2].preparePositionRetirement(lane);
                    assertThatThrownBy(runtime::publishedPositionCount).isInstanceOf(IllegalStateException.class);
                } catch (Throwable problem) { failure.set(problem); }
                finally { completed.countDown(); }
            })).isTrue();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(runtime.publishedPositionCount()).isZero();
            receipts[0].recordControlChanges(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isOne();
            receipts[1].commitTerminalToOwner(runtime, laneId, null, 1, null, null);
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            receipts[0].clear();
            receipts[0].recordControlChanges(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isEqualTo(2);
            receipts[2].commitBatchAdmissionToOwner(runtime, laneId);
            assertThat(runtime.publishedPositionCount()).isOne();
            runtime.assertAccountLanesHealthy();
        }
    }

    private static PositionRuntime position(ProductLine product, long user) {
        var instrument = CoreInstrument.from(product, CoreStateTestFixtures.instrument(product, "1", "BTC", "USDT",
                CoreStateTestFixtures.settleAsset(product)));
        return new PositionRuntime(user, 0, 0, CoreMarginMode.CROSS, CorePositionSide.NET,
                instrument, 1, 100, 100, 0, 10);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Lane was not released"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static void awaitControl(TradingRuntimeState runtime) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!runtime.pollControlLanes()) {
            if (System.nanoTime() > deadline) throw new AssertionError("control receipt did not complete");
            Thread.onSpinWait();
        }
    }
}
