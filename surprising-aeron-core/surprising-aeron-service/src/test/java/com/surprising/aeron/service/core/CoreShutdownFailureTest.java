package com.surprising.aeron.service.core;

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
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        core.activate();
        try {
            var entered = new CompletableFuture<Void>();
            var blockedRead = core.matcherPipeline().readAtSubmissionFence(0, () -> {
                entered.complete(null);
                release.join();
                return true;
            });
            entered.get(5, TimeUnit.SECONDS);
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
            assertThat(blockedRead.get(5, TimeUnit.SECONDS)).isTrue();
            core.snapshots.beginSnapshot(102, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        } finally {
            release.complete(null);
            core.close();
        }
    }

    @Test void accountFailureStillStopsAllWorkers() {
        var laneFailure = new NoSuchMethodError("account worker failed");
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        core.activate();
        var accounts = core.runtimeState();
        Thread[] workers = new Thread[accounts.laneCount()];
        for (int lane = 0; lane < workers.length; lane++) workers[lane] = accounts.accountWorkerThread(lane);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            accounts.submitAccountCommand(0, lane -> { throw laneFailure; });
            while (AccountLaneTestAccess.failure(accounts).get() == null) {
                if (System.nanoTime() >= deadline) throw new AssertionError("account failure was not published");
                Thread.onSpinWait();
            }
            assertThatThrownBy(core::close).isSameAs(laneFailure);
            assertThat(core.matcherPipeline().workersStopped()).isTrue();
            for (Thread worker : workers) assertThat(worker.isAlive()).isFalse();
        } finally {
            catchThrowable(core::close);
            catchThrowable(accounts::close);
        }
    }
}
