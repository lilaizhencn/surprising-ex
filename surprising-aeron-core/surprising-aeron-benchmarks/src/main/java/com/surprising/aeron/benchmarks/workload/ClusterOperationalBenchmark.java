package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Real external Cluster only. One sample is a complete fixed-duration async workload round;
 * business throughput and financial invariants are reported by the workload, not JMH invocations/s. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
public class ClusterOperationalBenchmark {
    private ClusterMixedCapacityMain workload;

    @Setup(Level.Trial)
    public void prepare() {
        workload = new ClusterMixedCapacityMain();
        try { workload.prepareMeasuredRun(); }
        catch (RuntimeException | Error failure) { workload.close(); throw failure; }
    }

    @Benchmark
    public long continuousOperations() { return workload.measureRun(); }

    @TearDown(Level.Trial)
    public void verifyAndClose() {
        if (workload != null) {
            try { workload.finishMeasuredRun(); }
            finally { workload.close(); }
        }
    }
}
