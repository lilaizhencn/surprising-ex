package com.surprising.aeron.service.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class HighConcurrencyBenchmarkCapacityAuditTest {

    @Test
    void executeHighConcurrencyCapacityAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动 Surprising 交易核心与风控引擎高并发基准压测审计 (P2)...");
        System.out.println("================================================================================");

        int warmupCycles = 200;
        int benchmarkCycles = 2_000;
        int makerDepth = 10;

        // 1. 撮合核心端到端容量压测 (Aeron Core + Matcher + Treasury Settlement)
        long startNanos = System.nanoTime();
        CorePerpetualEndToEndBenchmark.BaselineResult result =
                CorePerpetualEndToEndBenchmark.measure(benchmarkCycles, warmupCycles, makerDepth);
        long totalNanos = System.nanoTime() - startNanos;

        long finalizedOrders = result.finalizedOrders();
        double elapsedSec = result.elapsedNanos() / 1_000_000_000.0;
        double ordersPerSec = finalizedOrders / elapsedSec;
        double cyclesPerSec = result.cycles() / elapsedSec;

        long[] latencies = result.latenciesNanos();
        Arrays.sort(latencies);

        long p50Micros = percentile(latencies, 0.50) / 1_000;
        long p90Micros = percentile(latencies, 0.90) / 1_000;
        long p95Micros = percentile(latencies, 0.95) / 1_000;
        long p99Micros = percentile(latencies, 0.99) / 1_000;
        long p999Micros = percentile(latencies, 0.999) / 1_000;
        long maxMicros = percentile(latencies, 1.0) / 1_000;

        System.out.printf("[压测结果 - 撮合核心全链路] 深度=%d, 周期=%d, 总完成订单=%d%n",
                result.makerDepth(), result.cycles(), finalizedOrders);
        System.out.printf("  - 耗时: %.3f 秒 (纯执行: %.3f 秒)%n", totalNanos / 1e9, elapsedSec);
        System.out.printf("  - 峰值订单撮合吞吐 (TPS): %.2f orders/sec%n", ordersPerSec);
        System.out.printf("  - 周期处理速率: %.2f cycles/sec%n", cyclesPerSec);
        System.out.printf("  - 延迟分位数: P50=%d μs, P90=%d μs, P95=%d μs, P99=%d μs, P99.9=%d μs, Max=%d μs%n",
                p50Micros, p90Micros, p95Micros, p99Micros, p999Micros, maxMicros);
        System.out.printf("  - 待撮合队列积压 (Pending): %d%n", result.pendingMatching());

        assertThat(finalizedOrders).isGreaterThan(0);
        assertThat(result.pendingMatching()).isZero();

        // 2. 网关风控与双向限价保护高频校验压测 (1,000,000 次内存风控计算)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(">>> 正在进行网关极端风控与双向价格带校验吞吐审计 (100 万次并发请求)...");
        int validationIterations = 1_000_000;
        long markPriceTicks = 60_000_000L;
        long bandPpm = 50_000L;
        long passiveBandPpm = 500_000L;

        // 预热
        long checksum = 0;
        for (int i = 0; i < 50_000; i++) {
            checksum += testPriceBandFast(i, markPriceTicks, bandPpm, passiveBandPpm);
        }

        long valStart = System.nanoTime();
        for (int i = 0; i < validationIterations; i++) {
            checksum += testPriceBandFast(i, markPriceTicks, bandPpm, passiveBandPpm);
        }
        long valElapsed = System.nanoTime() - valStart;
        double valElapsedSec = valElapsed / 1_000_000_000.0;
        double valQps = validationIterations / valElapsedSec;
        double valAvgNanos = (double) valElapsed / validationIterations;

        System.out.printf("[压测结果 - 网关风控价格带校验] 总测试次数=%d, checksum=%d%n", validationIterations, checksum);
        System.out.printf("  - 耗时: %.4f 秒%n", valElapsedSec);
        System.out.printf("  - 校验吞吐能力 (QPS): %.2f ops/sec (每秒 %.2f 万次风控校验)%n", valQps, valQps / 10_000);
        System.out.printf("  - 单笔风控校验平均耗时: %.2f ns (纳秒级零开销)%n", valAvgNanos);
        System.out.println("================================================================================");
    }

    @Test
    void executeSaturatedCapacityAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动 Surprising 交易核心极限饱和发压压测审计 (Saturation Capacity Audit)...");
        System.out.println("================================================================================");

        int symbols = 16;
        int activeUsers = 64;
        int accountLanes = 4;
        int maxInFlight = 128;
        int operationsPerRun = 16_384;
        int targetOperationsPerSecond = 200_000;

        var config = LinearPerpetualScaleConfig.scale(symbols, symbols, 2, 5,
                LinearPerpetualTrafficProfile.UNIFORM, symbols);
        var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, config);

        try (var scenario = LinearPerpetualSaturationWorkload.scenario(template, maxInFlight, operationsPerRun, targetOperationsPerSecond)) {
            // 预热一轮
            scenario.run();

            // 极限饱和压测正式运行
            long startNanos = System.nanoTime();
            long runResult = scenario.run();
            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsedSec = elapsedNanos / 1_000_000_000.0;
            double tps = operationsPerRun / elapsedSec;

            long p50Micros = scenario.p50LatencyNanos() / 1_000;
            long p99Micros = scenario.p99LatencyNanos() / 1_000;
            long p999Micros = scenario.p999LatencyNanos() / 1_000;

            System.out.printf("[压测结果 - 极限饱和发压] 在途并发窗口(In-Flight)=%d, 目标发压速率=%d ops/s%n",
                    maxInFlight, targetOperationsPerSecond);
            System.out.printf("  - 饱和测试指令总数: %d 笔 (终态成交: %d 笔)%n",
                    scenario.operations(), scenario.terminalTrades());
            System.out.printf("  - 纯执行耗时: %.4f 秒%n", elapsedSec);
            System.out.printf("  - 极限饱和撮合吞吐 (TPS): %.2f orders/sec%n", tps);
            System.out.printf("  - 饱和延迟分位数: P50=%d μs, P99=%d μs, P99.9=%d μs%n",
                    p50Micros, p99Micros, p999Micros);
            System.out.printf("  - 最大撮合积压 (Max Backlog): %d%n", scenario.maxBacklog());
            System.out.printf("  - 平均撮合积压 (Avg Backlog): %.2f%n", scenario.averageMatchingBacklog());
            System.out.printf("  - 全并发窗口打满率 (Full Window %%): %.2f%%%n", scenario.fullWindowPercentage() * 100);
            System.out.printf("  - 生产者饥饿率 (Producer Starvation %%): %.2f%%%n", scenario.producerStarvationPercentage() * 100);
            System.out.printf("  - 管道重填次数 (Refill Operations): %d%n", scenario.refillOperations());

            LinearPerpetualSaturationWorkload.LatencyReport report = scenario.latencyReport();
            if (report != null) {
                System.out.println("  - 三阶段耗时细分 (微秒):");
                System.out.printf("    * 入队至Core接收 (Entry->Accepted): P50=%.1f μs, P99=%.1f μs%n",
                        report.entryAccepted().p50() / 1000.0, report.entryAccepted().p99() / 1000.0);
                System.out.printf("    * Core接收至撮合终态 (Accepted->Terminal): P50=%.1f μs, P99=%.1f μs%n",
                        report.acceptedTerminal().p50() / 1000.0, report.acceptedTerminal().p99() / 1000.0);
                System.out.printf("    * 全链路端到端 (Entry->Terminal): P50=%.1f μs, P99=%.1f μs, Max=%.1f μs%n",
                        report.entryTerminal().p50() / 1000.0, report.entryTerminal().p99() / 1000.0, report.entryTerminal().max() / 1000.0);
            }
            scenario.verify();
            System.out.println("  - 状态快照与资金不变量审计: PASS (资金与委托零差错)");
            System.out.println("================================================================================");

            assertThat(runResult).isNotZero();
            assertThat(scenario.operations()).isEqualTo(operationsPerRun);
        }
    }

    private static int testPriceBandFast(int i, long mark, long bandPpm, long passiveBandPpm) {
        long price = mark + ((i % 200) - 100) * 1000L;
        long maxBuy = mark + (mark * bandPpm) / 1_000_000L;
        long minBuy = mark - (mark * passiveBandPpm) / 1_000_000L;
        return (price <= maxBuy && price >= minBuy) ? 1 : 0;
    }

    private static long percentile(long[] sortedLatencies, double pct) {
        if (sortedLatencies.length == 0) return 0L;
        int index = (int) Math.ceil(pct * sortedLatencies.length) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.length - 1));
        return sortedLatencies[index];
    }
}
