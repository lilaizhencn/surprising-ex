package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 连接真实外部单成员Cluster，128 币对交替执行无成交/有成交两种触发边界；固定样本要求使用全新集群数据目录。 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
public class ClusterTriggerBoundaryBenchmark {
    /** 包含连接、建仓、128 次触发、OCO取消及终态查询，不作为持续交易吞吐或纯触发延迟指标。 */
    @Benchmark
    public void triggerOnOwningLane() {
        SmallControlReproMain.main(new String[]{"trigger"});
    }
    /** 内部标记价扫描、预算1续页及OCO取消走永久Lane；同样包含初始化，不作稳态容量指标。 */
    @Benchmark
    public void scannedTriggerOnOwningLane() {
        SmallControlReproMain.main(new String[]{"trigger-scan"});
    }
    /** 128币对GTX跨价拒单：Lane解冻与触发失败终态，保留maker挂单检查。 */
    @Benchmark
    public void rejectedTriggerOnOwningLane() {
        SmallControlReproMain.main(new String[]{"trigger-reject"});
    }
    /** 撤旧下新拒绝：前置单与旧单取消后由Lane释放冻结。 */
    @Benchmark
    public void rejectedAmendOnOwningLane() {
        SmallControlReproMain.main(new String[]{"amend-reject"});
    }
    /** 128币对拒绝改单、成功改单、普通/批量准入、REPLACE、普通/批量撤单及不存在订单拒绝，最终冻结清零。 */
    @Benchmark
    public void amendCancelCycleOnOwningLane() {
        SmallControlReproMain.main(new String[]{"amend-cycle"});
    }
}
