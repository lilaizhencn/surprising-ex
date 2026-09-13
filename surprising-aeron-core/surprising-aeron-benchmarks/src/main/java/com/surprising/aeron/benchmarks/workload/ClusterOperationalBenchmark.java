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
    @Param({"MIXED", "FILL_HEAVY"})
    public String tradingProfile;
    /** 单项批量与20项批量共用真实Lane路径；结果同时报告Core消息和展开业务项。 */
    @Param({"1", "20"})
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

    /** batchSize=1在128币对连续提交批量与普通单，覆盖每批结束后的延期队首推进及普通单空撤单集合路径；
     * 批次上下文只挂在PendingMatching环槽；持续跨环周转覆盖有序批次链接摘除和事件池复用，不维护第二份sequence到batch的Map。
     * batchSize=20持续部分成交，覆盖Matcher直接发布批量结果、Lane持仓身份在途保护和事件池跨环复用；
     * FILL_HEAVY在128币对执行双向成交，覆盖直接路径开仓/平仓及Owner有序收集后的身份退休。
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
