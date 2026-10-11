package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.product.api.ProductLine;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/** Owner risk metadata calculation over completed immutable Lane changes, excluding trading and transport. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx1g", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class PendingOpenInterestBenchmark {
    @Param({"0", "128"}) public int changedPositions;
    private TradingRuntimeState runtime;
    private RuntimeIdentityRegistry identities;
    private OpenInterestIndex totals;
    private AdlPositionIndex committed;

    @Setup public void setup() {
        var before = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        runtime = new TradingRuntimeState(); runtime.bindOwner();
        identities = new RuntimeIdentityRegistry();
        totals = new OpenInterestIndex(before);
        committed = new AdlPositionIndex(before, identities);
        for (int lane = 0; lane < 4; lane++) {
            var changes = new RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue>();
            for (int index = lane; index < changedPositions; index += 4) {
                long key = index + 1;
                var previous = new RuntimePositionIndexValue(key, "1", "USDT", CorePositionSide.NET,
                        index % 2 == 0 ? 10 : -10);
                totals.apply(null, previous); committed.apply(key, null, previous);
                changes.putIndexed(key, null, true, new RuntimePositionIndexValue(key, "1", "USDT",
                        CorePositionSide.NET, index % 2 == 0 ? 15 : -15));
            }
            runtime.changedPositions.adopt(lane, changes);
        }
    }

    @Benchmark public long includeCompletedBatchFills() {
        return totals.pendingOpenInterestSteps("1", runtime, committed, identities);
    }

    @TearDown public void close() { runtime.close(); }
}
