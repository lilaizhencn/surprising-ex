package com.surprising.aeron.service.core;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Two actual Lane notification writers and their Owner consumer; no business throughput claim. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms128m", "-Xmx128m", "-XX:+UseG1GC"})
@State(Scope.Group)
public class ReadyLaneMaskBenchmark {
    private final TradingRuntimeState.PaddedReadyMask ready = new TradingRuntimeState.PaddedReadyMask(2);
    @Benchmark @Group("laneNotifications") @GroupThreads(1)
    public boolean makerReady() { return ready.setBit(0); }
    @Benchmark @Group("laneNotifications") @GroupThreads(1)
    public boolean takerReady() { return ready.setBit(1); }
    @Benchmark @Group("laneNotifications") @GroupThreads(1)
    public long ownerCollects() { return ready.getAndClear(); }
}
