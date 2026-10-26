package com.surprising.aeron.service.core;

import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(PipelinedExecutionExtension.class)
class CoreShutdownFailureTest {
    @Test
    void releasedFenceKeepsMatcherCaptureAliveAndRejectsAnotherCaptureUntilCompletion() throws Exception {
        var release = new CompletableFuture<Void>();
        var captures = new java.util.concurrent.atomic.AtomicInteger();
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        core.activate();
        try {
            core.snapshots.matcherSnapshotCapture((id, sequence, hash, state, orders) -> {
                captures.incrementAndGet();
                return release.thenCompose(ignored -> core.snapshots.captureMatcherSnapshot(
                        id, sequence, hash, state, orders));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            core.snapshots.beginSnapshot(101, deadline);
            assertThat(core.snapshots.pollSnapshotSections(0, 0, System.nanoTime())).isNull();
            var capture = core.snapshots.inFlightMatcherSnapshot().get();
            assertThat(capture).isNotNull();
            var cancelledView = capture.toCompletableFuture();
            assertThat(cancelledView).isNotDone();
            assertThat(cancelledView.cancel(false)).isTrue();
            assertThat(core.snapshots.inFlightMatcherSnapshot().get() == capture).isTrue();
            core.snapshots.releaseSnapshotFence();
            assertThat(capture.toCompletableFuture()).isNotDone();
            assertThatThrownBy(() -> core.snapshots.beginSnapshot(102, deadline))
                    .isInstanceOf(TradingCoreRuntime.SnapshotNotReadyException.class);
            var command = new com.surprising.aeron.protocol.CoreMessage(
                    com.surprising.aeron.protocol.CoreMessageHeader.command(
                            com.surprising.aeron.protocol.CoreMessageType.ADJUST_BALANCE,
                            java.util.UUID.randomUUID(), ProductLine.SPOT,
                            com.surprising.aeron.protocol.CommandSource.GATEWAY, 1, 1, 7, 1, 1),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(
                            new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
            assertThatThrownBy(() -> core.apply(command)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot fence is active");
            release.complete(null);
            assertThat(capture.toCompletableFuture().get(5, TimeUnit.SECONDS).modules()).isNotEmpty();
            long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (core.snapshots.inFlightMatcherSnapshot().get() != null) {
                if (System.nanoTime() >= completionDeadline) throw new AssertionError("capture was not released");
                Thread.onSpinWait();
            }
            assertThat(captures).hasValue(1);
            core.snapshots.beginSnapshot(102, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        } finally {
            release.complete(null);
            core.close();
        }
    }

    @Test void snapshotCancelFailureStillStopsAccountAndLedgerAndPreservesLaneFailure() {
        var first = new IllegalStateException("snapshot cancellation failed");
        var laneFailure = new NoSuchMethodError("account worker failed");
        var encoded = new CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { throw first; }
        };
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        core.activate();
        var accounts = core.runtimeState();
        Thread[] workers = new Thread[accounts.laneCount()];
        for (int lane = 0; lane < workers.length; lane++) workers[lane] = accounts.accountWorkerThread(lane);
        try {
            core.snapshots.snapshotEncoder(image -> encoded);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            core.snapshots.beginSnapshot(1, deadline);
            while (core.snapshots.snapshotFence().encodedSnapshot() == null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("snapshot encoder did not start");
                core.snapshots.pollSnapshotSections(0, 0, System.nanoTime());
            }
            accounts.submitAccountCommand(0, lane -> { throw laneFailure; });
            while (AccountLaneTestAccess.failure(accounts).get() == null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("account failure was not published");
                Thread.onSpinWait();
            }
            assertThatThrownBy(core::close).isSameAs(first).hasSuppressedException(laneFailure);
            assertThat(core.matcherPipeline().workersStopped()).isTrue();
            for (Thread worker : workers) assertThat(worker.isAlive()).isFalse();
        } finally {
            // 断言意外失败时仍尝试清理本用例启动的所有线程。
            org.assertj.core.api.Assertions.catchThrowable(core::close);
            org.assertj.core.api.Assertions.catchThrowable(accounts::close);
        }
    }
}
