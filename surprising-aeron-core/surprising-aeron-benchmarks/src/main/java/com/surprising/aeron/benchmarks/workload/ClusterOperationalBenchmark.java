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
    /** 外部三节点控制任务页大小；0 保持现有口径；小页覆盖行情/账户变化后的估值续页和异步完成，大页覆盖跨 Lane 汇总。 */
    @Param({"0", "1", "2", "4", "8", "64"})
    public int controlPageSize;
    /** 客户端实际在途档位；外部节点须以对应owner-command-window启动，不能由客户端冒充服务端配置。 */
    @Param({"64", "128"})
    public int inFlightWindow;
    /** 同一真实链路分别覆盖原混合负载、双向成交结算负载；不能混合统计。 */
    @Param({"MIXED"})
    public String tradingProfile;
    private ClusterMixedCapacityMain workload;

    @Setup(Level.Trial)
    public void prepare() {
        ClusterMixedCapacityMain.commandCapacity(inFlightWindow, inFlightWindow);
        System.setProperty("surprising.aeron.capacity-async-in-flight", Integer.toString(inFlightWindow));
        System.setProperty("surprising.aeron.capacity-session-in-flight", Integer.toString(inFlightWindow));
        if (!"MIXED".equals(tradingProfile) && !"FILL_HEAVY".equals(tradingProfile))
            throw new IllegalArgumentException("unknown trading profile: " + tradingProfile);
        System.setProperty("surprising.aeron.mixed-fill-heavy", Boolean.toString("FILL_HEAVY".equals(tradingProfile)));
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
