package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.account.AccountCommandService;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.snapshot.state.SnapshotLoader;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Actual Lane transfer/control handoffs; excludes codec, Core idempotency, Ledger and Aeron. */
@State(Scope.Thread) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1) @Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class TransferLaneAllocationBenchmark {
    private AccountBook accountBook;
    private IdIndex identities;
    private final AccountCommandService task = new AccountCommandService();
    private final TransferFundsCommand command = new TransferFundsCommand(91, ProductLine.SPOT,
            ProductLine.LINEAR_PERPETUAL, "SPOT", "USDT_PERPETUAL", "USDT", 1, "benchmark", "", 7, 8);
    private long transfers;

    @Setup public void setup() {
        identities = new IdIndex();
        accountBook = SnapshotLoader.loadSnapshot(TradingCoreState.empty(ProductLine.SPOT), identities,
                new LaneTopology(1, 4, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 256, 256, 256));
        task.prepareBalanceAdjustment(accountBook, identities, 7, "USDT", 1_000_000_000_000L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!task.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("balance setup timed out");
            Thread.onSpinWait();
        }
        accountBook.clearChangedKeys();
        accountBook.startAccountLanes();
    }

    /** One direct control fence is active at a time; this is not the 256-window throughput workload. */
    @Benchmark @OperationsPerInvocation(2)
    public long debitAndComplete() {
        task.prepareTransferOut(accountBook, identities, 7, command);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!task.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("transfer debit timed out");
            Thread.onSpinWait();
        }
        accountBook.clearChangedKeys();
        task.prepareTransferCompletion(accountBook, 7, command.transferId());
        while (!task.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("transfer completion timed out");
            Thread.onSpinWait();
        }
        accountBook.clearChangedKeys();
        return ++transfers;
    }

    @TearDown public void close() { accountBook.close(); }
}
