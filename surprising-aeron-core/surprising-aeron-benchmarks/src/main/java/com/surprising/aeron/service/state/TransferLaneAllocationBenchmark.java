package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Actual Lane transfer/control handoffs; excludes codec, Core idempotency, Ledger and Aeron. */
@State(Scope.Thread) @BenchmarkMode(Mode.Throughput) @OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1) @Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class TransferLaneAllocationBenchmark {
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private final AccountControlTask task = new AccountControlTask();
    private final TransferFundsCommand command = new TransferFundsCommand(91, ProductLine.SPOT,
            ProductLine.LINEAR_PERPETUAL, "SPOT", "USDT_PERPETUAL", "USDT", 1, "benchmark", "", 7, 8);
    private long transfers;

    @Setup public void setup() {
        identities = new RuntimeIdentityRegistry();
        runtime = RuntimeStateProjector.project(TradingCoreState.empty(ProductLine.SPOT), identities,
                new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, 4,
                        LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 256, 256, 256));
        RuntimeAccountStateTransitions.adjustBalance(runtime, identities, 7,
                new BalanceAdjustmentCommand("USDT", 1_000_000_000_000L));
        runtime.clearChangedKeys();
        runtime.startAccountLanes();
    }

    /** One direct control fence is active at a time; this is not the 256-window throughput workload. */
    @Benchmark @OperationsPerInvocation(2)
    public long debitAndComplete() {
        task.prepareTransferOut(runtime, identities, 7, command);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!task.poll()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("transfer debit timed out");
            Thread.onSpinWait();
        }
        runtime.clearChangedKeys();
        task.prepareTransferCompletion(runtime, 7, command.transferId());
        while (!task.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("transfer completion timed out");
            Thread.onSpinWait();
        }
        runtime.clearChangedKeys();
        return ++transfers;
    }

    @TearDown public void close() { runtime.close(); }
}
