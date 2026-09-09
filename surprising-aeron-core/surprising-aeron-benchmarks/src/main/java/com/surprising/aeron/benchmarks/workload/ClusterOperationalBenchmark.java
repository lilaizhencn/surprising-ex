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
    /** 外部三节点控制任务页大小；0 保持现有口径，1/64 覆盖续页与跨 Lane 收集。 */
    @Param({"0", "1", "64"})
    public int controlPageSize;
    private ClusterMixedCapacityMain workload;

    @Setup(Level.Trial)
    public void prepare() {
        workload = new ClusterMixedCapacityMain(controlPageSize);
        try {
            workload.verifyDependencyCollisionCoverage();
            workload.prepareMeasuredRun();
        }
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
