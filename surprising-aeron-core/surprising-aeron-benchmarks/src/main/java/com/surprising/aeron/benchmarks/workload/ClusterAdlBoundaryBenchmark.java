package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Real fresh single-member Cluster, 128 symbols and the shared financial lifecycle fixture.
 * Includes population setup, three bounded liquidation cancellation pages, insurance, ADL and queries; not steady-state throughput. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
public class ClusterAdlBoundaryBenchmark {
    @Benchmark
    public void insuranceAndAdlOnOwningLanes() {
        try (var workload = new ClusterMixedCapacityMain(0, 20)) {
            workload.verifyLossBoundary();
        }
    }
}
