package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 连接真实三节点，一次执行无成交/有成交两种触发边界；固定样本要求使用全新集群数据目录。 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
public class ClusterTriggerBoundaryBenchmark {
    /** 包含连接、建仓、两次触发及终态查询，不作为持续交易吞吐或纯触发延迟指标。 */
    @Benchmark
    public void triggerAfterLaneHandoff() {
        SmallControlReproMain.main(new String[]{"trigger"});
    }
}
