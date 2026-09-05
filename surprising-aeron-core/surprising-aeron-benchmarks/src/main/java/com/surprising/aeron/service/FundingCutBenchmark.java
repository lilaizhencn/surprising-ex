package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class FundingCutBenchmark {
    @Param({"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"}) public ProductLine productLine;
    @Param({"256"}) public int maxInFlight;
    private LifecyclePaginationBenchmark workload;

    @Setup(Level.Trial)
    public void prepare() {
        workload = new LifecyclePaginationBenchmark();
        workload.productLine = productLine;
        workload.maxInFlight = maxInFlight;
        workload.prepare();
    }
    @Setup(Level.Invocation)
    public void restore() { workload.restore(); }
    @Benchmark
    @OperationsPerInvocation(18)
    public long fundingWithMarkUpdate() { return workload.fundingPages(false); }
    public long fundingWithRecovery() { return workload.fundingPages(true); }
    @TearDown(Level.Invocation)
    public void verify() { workload.verify(); }
}
