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
    @Test void snapshotCancelFailureStillStopsAccountAndLedgerAndPreservesLaneFailure() {
        var first = new IllegalStateException("snapshot cancellation failed");
        var laneFailure = new NoSuchMethodError("account worker failed");
        var encoded = new CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { throw first; }
        };
        var core = new TradingCoreRuntime(ProductLine.SPOT);
        core.activate();
        var accounts = core.runtimeState();
        Thread ledger = AccountLaneTestAccess.ledger(accounts).thread();
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
            assertThat(ledger.isAlive()).isFalse();
        } finally {
            // 断言意外失败时仍尝试清理本用例启动的所有线程。
            org.assertj.core.api.Assertions.catchThrowable(core::close);
            org.assertj.core.api.Assertions.catchThrowable(accounts::close);
        }
    }
}
