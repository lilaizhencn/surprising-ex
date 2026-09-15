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
    /** 单成员吞吐基线不注入控制分页；控制分页由独立生命周期诊断覆盖。 */
    @Param({"0"})
    public int controlPageSize;
    /** 客户端实际在途档位；节点 owner-command-window 必须使用同一基线值。 */
    @Param({"64"})
    public int inFlightWindow;
    /** 昨日吞吐基线使用连续混合交易；双向成交负载另行诊断，不混入基线。 */
    @Param({"MIXED"})
    public String tradingProfile;
    /** 昨日吞吐基线固定20项批量；单项批量另行诊断，不混入基线。 */
    @Param({"20"})
    public int batchSize;
    private ClusterMixedCapacityMain workload;

    @Setup(Level.Trial)
    public void prepare() {
        ClusterMixedCapacityMain.commandCapacity(inFlightWindow, inFlightWindow);
        System.setProperty("surprising.aeron.capacity-async-in-flight", Integer.toString(inFlightWindow));
        System.setProperty("surprising.aeron.capacity-session-in-flight", Integer.toString(inFlightWindow));
        if (!"MIXED".equals(tradingProfile) && !"FILL_HEAVY".equals(tradingProfile))
            throw new IllegalArgumentException("unknown trading profile: " + tradingProfile);
        System.setProperty("surprising.aeron.mixed-fill-heavy", Boolean.toString("FILL_HEAVY".equals(tradingProfile)));
        workload = new ClusterMixedCapacityMain(controlPageSize, batchSize);
        try {
            workload.verifyDependencyCollisionCoverage();
            workload.prepareMeasuredRun();
        }
        catch (RuntimeException | Error failure) { workload.close(); throw failure; }
    }

    /** 256币对、20项批量持续部分成交，覆盖Matcher直接发布批量结果、Lane持仓身份在途保护和事件池跨环复用；
     * 测量后按完整生命周期核对资金、持仓、冻结及终态计数；外部驱动执行真实Archive快照重启校验。 */
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
