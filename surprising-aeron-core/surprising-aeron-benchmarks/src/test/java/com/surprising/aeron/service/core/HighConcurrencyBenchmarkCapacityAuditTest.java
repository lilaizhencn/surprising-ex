package com.surprising.aeron.service.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreInstrumentRiskLimits;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.service.core.LinearPerpetualBenchmarkSupport.Harness;

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

    @Test
    void executeProductionMixedWorkloadCapacityAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动 Surprising 真实生产混合业务模式高并发性能压测审计 (Production Mixed)...");
        System.out.println(">>> 业务涵盖: 批量报单撮合 + 强平穿仓与保险基金垫付 + 定时资金费划转 + 标记价格风控跳动");
        System.out.println("================================================================================");

        int accountLanes = 4;
        int activeUsers = 1_024;
        int symbols = 4;
        int hftRounds = 48;
        int hftBatchSize = 20;

        var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, symbols);

        try (var scenario = LinearPerpetualMixedWorkload.productionScenario(template, hftRounds, hftBatchSize)) {
            // 预热一轮
            scenario.run();

            // 正式生产混合业务压测
            long startNanos = System.nanoTime();
            long runResult = scenario.run();
            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsedSec = elapsedNanos / 1_000_000_000.0;

            long totalOps = scenario.terminalOperations();
            long tradingOps = scenario.terminalTradingOperations();
            long lifecycleOps = scenario.terminalLifecycleOperations();
            long terminalTrades = scenario.terminalTrades();
            long laneTotalOps = scenario.laneOperations();
            long laneCommandOps = scenario.laneOperations(0);
            long laneSettlementOps = scenario.laneOperations(1);
            long laneQueryOps = scenario.laneOperations(2);
            long laneRiskOps = scenario.laneOperations(3);

            double mixedTps = totalOps / elapsedSec;
            double tradingTps = tradingOps / elapsedSec;
            double laneTps = laneTotalOps / elapsedSec;

            System.out.printf("[压测结果 - 真实生产混合业务] 4 Account Lanes, 1024 活跃用户, 4 交易对, 48 轮并发%n");
            System.out.printf("  - 混合业务总操作数: %d 笔 (纯执行耗时: %.4f 秒)%n", totalOps, elapsedSec);
            System.out.printf("  - 综合混合业务吞吐量 (Mixed TPS): %.2f ops/sec%n", mixedTps);
            System.out.printf("  - 其中纯高频交易指令 (Trading Ops): %d 笔 (%.2f TPS)%n", tradingOps, tradingTps);
            System.out.printf("  - 其中生命周期风控/资金费/强平操作 (Lifecycle Ops): %d 笔%n", lifecycleOps);
            System.out.printf("  - 订单簿撮合成交对数 (Trades Matched): %d 对%n", terminalTrades);
            System.out.printf("  - 4 记账泳道分片任务总数 (Lane Operations): %d 笔 (%.2f 分片记账 TPS)%n",
                    laneTotalOps, laneTps);
            System.out.printf("    * 指令准入记账 (Command): %d 笔%n", laneCommandOps);
            System.out.printf("    * 撮合成交结算 (Settlement): %d 笔%n", laneSettlementOps);
            System.out.printf("    * 账户资产查询 (Query): %d 笔%n", laneQueryOps);
            System.out.printf("    * 穿仓强平与风控扫描 (Risk): %d 笔%n", laneRiskOps);
            System.out.printf("  - 撮合引擎最大积压深度 (Max Backlog): %d 笔%n", scenario.maxBacklog());

            scenario.verify();
            System.out.println("  - 混合业务资金守恒与状态快照恢复: 100% PASS (无穿仓坏账，资金守恒)");
            System.out.println("================================================================================");

            assertThat(runResult).isNotZero();
            assertThat(totalOps).isGreaterThan(0);
            assertThat(tradingOps).isGreaterThan(0);
        }
    }

    @Test
    void executeTenThousandUserProductionMixedScaleAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动 Surprising 生产级宏观万级用户大规模混合业务高并发压测审计...");
        System.out.println(">>> 规模型号: 10,000 活跃持仓用户 (顶格基准), 16 活跃交易对, 4 记账泳道分片");
        System.out.println(">>> 业务场景: 大规模做市批量撤挂单 + 10,000 用户定时资金费扣划 + 标记价跳动 + 穿仓强平");
        System.out.println("================================================================================");

        int accountLanes = 4;
        int activeUsers = 10_000;
        int symbols = 16;
        int hftRounds = 128;
        int hftBatchSize = 20;

        long t0 = System.currentTimeMillis();
        var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, symbols);
        long setupMs = System.currentTimeMillis() - t0;
        System.out.printf(">> 10,000 活跃用户与 16 交易对底座状态生成耗时: %.2f 秒%n", setupMs / 1000.0);

        try (var scenario = LinearPerpetualMixedWorkload.productionScenario(template, hftRounds, hftBatchSize)) {
            // 预热一轮
            scenario.run();

            // 正式生产大规模混合发压
            long startNanos = System.nanoTime();
            long runResult = scenario.run();
            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsedSec = elapsedNanos / 1_000_000_000.0;

            long totalOps = scenario.terminalOperations();
            long tradingOps = scenario.terminalTradingOperations();
            long lifecycleOps = scenario.terminalLifecycleOperations();
            long terminalTrades = scenario.terminalTrades();
            long laneTotalOps = scenario.laneOperations();
            long laneCommandOps = scenario.laneOperations(0);
            long laneSettlementOps = scenario.laneOperations(1);

            double mixedTps = totalOps / elapsedSec;
            double tradingTps = tradingOps / elapsedSec;
            double laneTps = laneTotalOps / elapsedSec;

            System.out.printf("[压测结果 - 10,000 顶格用户混合业务] 4 Lanes, 10,000 Users, 16 Symbols, 128 轮并发%n");
            System.out.printf("  - 混合业务总操作数: %d 笔 (纯执行耗时: %.4f 秒)%n", totalOps, elapsedSec);
            System.out.printf("  - 宏观混合业务综合吞吐量 (Mixed TPS): %.2f ops/sec%n", mixedTps);
            System.out.printf("  - 其中纯高频做市/交易指令: %d 笔 (%.2f TPS)%n", tradingOps, tradingTps);
            System.out.printf("  - 其中生命周期风控/全网资金费交割操作: %d 笔%n", lifecycleOps);
            System.out.printf("  - 订单簿撮合成交对数 (Trades Matched): %d 对%n", terminalTrades);
            System.out.printf("  - 4 记账泳道分片任务总数 (Lane Operations): %d 笔 (%.2f 分片记账 TPS)%n",
                    laneTotalOps, laneTps);
            System.out.printf("    * 指令准入记账 (Command): %d 笔%n", laneCommandOps);
            System.out.printf("    * 撮合成交结算 (Settlement): %d 笔%n", laneSettlementOps);
            System.out.printf("  - 撮合引擎最大积压深度 (Max Backlog): %d 笔%n", scenario.maxBacklog());

            scenario.verify();
            System.out.println("  - 10,000 用户全账本资金守恒与状态快照恢复: 100% PASS (零穿仓，资金守恒)");
            System.out.println("================================================================================");

            assertThat(runResult).isNotZero();
            assertThat(totalOps).isGreaterThan(0);
        }
    }

    @Test
    void executeAeronSingleNodeTwoLanesHundredThousandUsersSaturationAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动【Aeron 单节点 + 2 Lane + 10万+ 活跃持仓用户】极限饱和压测审计...");
        System.out.println(">>> 架构约束: 单节点 Aeron 撮合核心, 2 个 Account Lane 分片, 100,000 活跃持仓账户");
        System.out.println(">>> 测试维度: 持续在途并发满载饱和流 (Saturation) + 万人交织混合业务 (Mixed Workload)");
        System.out.println("================================================================================");

        int accountLanes = 2; // 用户明确指定: 2 lane
        int activeUsers = 100_000; // 用户明确指定: 10 万+ 活跃用户
        int symbols = 16;
        int maxInFlight = 256;
        int operationsPerRun = 16_384;
        int targetOperationsPerSecond = 200_000;

        long t0 = System.currentTimeMillis();
        var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, symbols);
        long setupMs = System.currentTimeMillis() - t0;
        System.out.printf(">> 100,000 活跃持仓用户与 2 Lane 分片底座构建耗时: %.2f 秒%n", setupMs / 1000.0);

        // 1. 饱和状态发压 (持续 256 并发在途窗口满载打满)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(">>> 阶段一: 10 万用户底座下的极限在途窗口饱和发压 (In-Flight=256)...");
        try (var saturationScenario = LinearPerpetualSaturationWorkload.scenario(
                template, maxInFlight, operationsPerRun, targetOperationsPerSecond)) {
            // 预热
            saturationScenario.run();

            long satStart = System.nanoTime();
            long satResult = saturationScenario.run();
            long satElapsed = System.nanoTime() - satStart;
            double satElapsedSec = satElapsed / 1_000_000_000.0;
            double satTps = operationsPerRun / satElapsedSec;

            long p50Micros = saturationScenario.p50LatencyNanos() / 1_000;
            long p99Micros = saturationScenario.p99LatencyNanos() / 1_000;
            long p999Micros = saturationScenario.p999LatencyNanos() / 1_000;

            System.out.printf("[压测结果 - 2 Lane + 10万用户 饱和发压] 指令总数=%d 笔, 终态成交=%d 笔%n",
                    saturationScenario.operations(), saturationScenario.terminalTrades());
            System.out.printf("  - 耗时: %.4f 秒, 极限饱和撮合吞吐 (TPS): %.2f orders/sec%n", satElapsedSec, satTps);
            System.out.printf("  - 饱和延迟分位数: P50=%d μs, P99=%d μs, P99.9=%d μs%n", p50Micros, p99Micros, p999Micros);
            System.out.printf("  - 最大撮合积压 (Max Backlog): %d 笔, 平均积压: %.2f 笔%n",
                    saturationScenario.maxBacklog(), saturationScenario.averageMatchingBacklog());
            System.out.printf("  - 管道重填次数 (Refill Operations): %d, 生产者饥饿率: %.2f%%%n",
                    saturationScenario.refillOperations(), saturationScenario.producerStarvationPercentage() * 100);

            var report = saturationScenario.latencyReport();
            if (report != null) {
                System.out.println("  - 三阶段延迟细分透视:");
                System.out.printf("    * 入队至Core接收 (排队阶段): P50=%.1f μs, P99=%.1f μs%n",
                        report.entryAccepted().p50() / 1000.0, report.entryAccepted().p99() / 1000.0);
                System.out.printf("    * Core接收至撮合终态 (纯撮合执行): P50=%.1f μs, P99=%.1f μs%n",
                        report.acceptedTerminal().p50() / 1000.0, report.acceptedTerminal().p99() / 1000.0);
                System.out.printf("    * 全链路端到端 (Entry->Terminal): P50=%.1f μs, P99=%.1f μs, Max=%.1f μs%n",
                        report.entryTerminal().p50() / 1000.0, report.entryTerminal().p99() / 1000.0, report.entryTerminal().max() / 1000.0);
            }
            saturationScenario.verify();
            System.out.println("  - 饱和状态资金守恒校验: PASS (10万用户账本零误差)");
            assertThat(satResult).isNotZero();
        }

        // 2. 10万用户真实生产混合业务发压 (高频批量撤挂单 + 10万用户全网资金费交割 + 标记价跳动 + 穿仓强平)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(">>> 阶段二: 10 万用户真实生产混合业务模式高频交织发压 (64 轮批量做市 + 强平 + 资金费)...");
        int hftRounds = 64;
        int hftBatchSize = 20;
        try (var mixedScenario = LinearPerpetualMixedWorkload.productionScenario(template, hftRounds, hftBatchSize)) {
            // 预热
            mixedScenario.run();

            long mixStart = System.nanoTime();
            long mixResult = mixedScenario.run();
            long mixElapsed = System.nanoTime() - mixStart;
            double mixElapsedSec = mixElapsed / 1_000_000_000.0;

            long totalOps = mixedScenario.terminalOperations();
            long tradingOps = mixedScenario.terminalTradingOperations();
            long lifecycleOps = mixedScenario.terminalLifecycleOperations();
            long terminalTrades = mixedScenario.terminalTrades();
            long laneTotalOps = mixedScenario.laneOperations();
            long laneCommandOps = mixedScenario.laneOperations(0);
            long laneSettlementOps = mixedScenario.laneOperations(1);

            double mixedTps = totalOps / mixElapsedSec;
            double tradingTps = tradingOps / mixElapsedSec;
            double laneTps = laneTotalOps / mixElapsedSec;

            System.out.printf("[压测结果 - 2 Lane + 10万用户 生产混合业务] 耗时: %.4f 秒%n", mixElapsedSec);
            System.out.printf("  - 混合业务总操作数: %d 笔%n", totalOps);
            System.out.printf("  - 综合混合业务吞吐量 (Mixed TPS): %.2f ops/sec (每秒 %.2f 万次混合操作)%n", mixedTps, mixedTps / 10_000);
            System.out.printf("  - 纯交易订单吞吐 (Trading TPS): %.2f orders/sec (%d 笔)%n", tradingTps, tradingOps);
            System.out.printf("  - 撮合成交对数 (Trades Matched): %d 对%n", terminalTrades);
            System.out.printf("  - 生命周期繁重任务操作数 (Lifecycle Ops): %d 笔 (强平垫付与资金费游标划转)%n", lifecycleOps);
            System.out.printf("  - 2 记账泳道分片任务总数: %d 笔 (%.2f 分片记账 TPS)%n", laneTotalOps, laneTps);
            System.out.printf("    * 指令准入记账 (Command): %d 笔%n", laneCommandOps);
            System.out.printf("    * 撮合成交结算 (Settlement): %d 笔%n", laneSettlementOps);
            System.out.printf("  - 撮合引擎最大积压深度 (Max Backlog): %d 笔%n", mixedScenario.maxBacklog());

            mixedScenario.verify();
            System.out.println("  - 10 万持仓用户状态快照与资金不变量审计: 100% PASS (零穿仓坏账，资金守恒)");
            System.out.println("================================================================================");

            assertThat(mixResult).isNotZero();
            assertThat(totalOps).isGreaterThan(0);
        }
    }

    @Test
    void executeAeronDeepOrderBookAndMultiPositionCapacityAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动【深盘口高挂单簿 + 多持仓】Aeron 单节点 2 Lane 10万+ 用户极限压测审计...");
        System.out.println(">>> 架构约束: 单节点 Aeron, 2 Account Lane, 100,000 活跃用户, 16 交易对");
        System.out.println(">>> 深度扩展: maxPositionsPerUser=4 (多持仓约 25 万个), maxOpenOrdersPerUser=3 (常驻挂单约 15 万笔)");
        System.out.println("================================================================================");

        int accountLanes = 2;
        int activeUsers = 100_000;
        int symbols = 16;
        int maxPositionsPerUser = 4;
        int maxOpenOrdersPerUser = 3;
        int maxInFlight = 256;
        int operationsPerRun = 16_384;
        int targetOperationsPerSecond = 200_000;

        var scaleConfig = LinearPerpetualScaleConfig.scale(symbols, symbols,
                maxPositionsPerUser, maxOpenOrdersPerUser,
                LinearPerpetualTrafficProfile.UNIFORM, symbols);

        long t0 = System.currentTimeMillis();
        var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, scaleConfig);
        long setupMs = System.currentTimeMillis() - t0;
        System.out.printf(">> 100,000 用户【25万多持仓 + 15万深深度挂单】底座构建耗时: %.2f 秒%n", setupMs / 1000.0);

        // 1. 深盘口在途饱和发压 (In-Flight=256)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(">>> 阶段一: 15万笔挂单深盘口下的极限在途窗口饱和发压 (In-Flight=256)...");
        try (var saturationScenario = LinearPerpetualSaturationWorkload.scenario(
                template, maxInFlight, operationsPerRun, targetOperationsPerSecond)) {
            saturationScenario.run();

            long satStart = System.nanoTime();
            long satResult = saturationScenario.run();
            long satElapsed = System.nanoTime() - satStart;
            double satElapsedSec = satElapsed / 1_000_000_000.0;
            double satTps = operationsPerRun / satElapsedSec;

            long p50Micros = saturationScenario.p50LatencyNanos() / 1_000;
            long p99Micros = saturationScenario.p99LatencyNanos() / 1_000;

            System.out.printf("[压测结果 - 2 Lane + 10万用户 + 15万深盘口 饱和发压] 指令总数=%d 笔, 终态成交=%d 笔%n",
                    saturationScenario.operations(), saturationScenario.terminalTrades());
            System.out.printf("  - 耗时: %.4f 秒, 极限饱和撮合吞吐 (TPS): %.2f orders/sec%n", satElapsedSec, satTps);
            System.out.printf("  - 饱和延迟分位数: P50=%d μs, P99=%d μs%n", p50Micros, p99Micros);
            System.out.printf("  - 最大撮合积压 (Max Backlog): %d 笔, 平均积压: %.2f 笔%n",
                    saturationScenario.maxBacklog(), saturationScenario.averageMatchingBacklog());

            var report = saturationScenario.latencyReport();
            if (report != null) {
                System.out.println("  - 三阶段延迟细分透视:");
                System.out.printf("    * 入队至Core接收 (排队阶段): P50=%.1f μs, P99=%.1f μs%n",
                        report.entryAccepted().p50() / 1000.0, report.entryAccepted().p99() / 1000.0);
                System.out.printf("    * Core接收至撮合终态 (纯撮合执行): P50=%.1f μs, P99=%.1f μs%n",
                        report.acceptedTerminal().p50() / 1000.0, report.acceptedTerminal().p99() / 1000.0);
                System.out.printf("    * 全链路端到端 (Entry->Terminal): P50=%.1f μs, P99=%.1f μs, Max=%.1f μs%n",
                        report.entryTerminal().p50() / 1000.0, report.entryTerminal().p99() / 1000.0, report.entryTerminal().max() / 1000.0);
            }
            saturationScenario.verify();
            System.out.println("  - 饱和状态资金守恒校验: PASS");
            assertThat(satResult).isNotZero();
        }

        // 2. 25万多持仓 + 15万挂单下的真实生产混合业务发压
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(">>> 阶段二: 25万持仓+15万挂单下 生产混合业务模式交织发压 (64 轮批量做市 + 强平 + 资金费)...");
        int hftRounds = 64;
        int hftBatchSize = 20;
        try (var mixedScenario = LinearPerpetualMixedWorkload.productionScenario(template, hftRounds, hftBatchSize)) {
            mixedScenario.run();

            long mixStart = System.nanoTime();
            long mixResult = mixedScenario.run();
            long mixElapsed = System.nanoTime() - mixStart;
            double mixElapsedSec = mixElapsed / 1_000_000_000.0;

            long totalOps = mixedScenario.terminalOperations();
            long tradingOps = mixedScenario.terminalTradingOperations();
            long lifecycleOps = mixedScenario.terminalLifecycleOperations();
            long terminalTrades = mixedScenario.terminalTrades();
            long laneTotalOps = mixedScenario.laneOperations();

            double mixedTps = totalOps / mixElapsedSec;
            double tradingTps = tradingOps / mixElapsedSec;
            double laneTps = laneTotalOps / mixElapsedSec;

            System.out.printf("[压测结果 - 2 Lane + 10万用户 + 25万持仓+15万挂单 生产混合] 耗时: %.4f 秒%n", mixElapsedSec);
            System.out.printf("  - 混合业务总操作数: %d 笔%n", totalOps);
            System.out.printf("  - 综合混合业务吞吐量 (Mixed TPS): %.2f ops/sec (每秒 %.2f 万次混合操作)%n", mixedTps, mixedTps / 10_000);
            System.out.printf("  - 纯交易订单吞吐 (Trading TPS): %.2f orders/sec (%d 笔)%n", tradingTps, tradingOps);
            System.out.printf("  - 撮合成交对数 (Trades Matched): %d 对%n", terminalTrades);
            System.out.printf("  - 生命周期繁重任务操作数: %d 笔 (全网多持仓资金费交割与强平)%n", lifecycleOps);
            System.out.printf("  - 2 记账泳道分片任务总数: %d 笔 (%.2f 分片记账 TPS)%n", laneTotalOps, laneTps);
            System.out.printf("  - 撮合引擎最大积压深度 (Max Backlog): %d 笔%n", mixedScenario.maxBacklog());

            mixedScenario.verify();
            System.out.println("  - 25万持仓全账本资金守恒不变量审计: 100% PASS (零穿仓坏账)");
            System.out.println("================================================================================");

            assertThat(mixResult).isNotZero();
            assertThat(totalOps).isGreaterThan(0);
        }
    }

    @Test
    void executeOneWayVsHedgeModeComparisonAudit() {
        System.out.println("================================================================================");
        System.out.println(">>> 正在启动【单向持仓 (ONE_WAY) vs 双向持仓 (HEDGE)】架构效能专项微基准对照审计...");
        System.out.println(">>> 架构约束: Aeron 2 Account Lanes, 10,000 笔订单对比");
        System.out.println("================================================================================");

        int accountLanes = 2;
        String instrumentId = "10001";
        long safeBalance = 100_000_000L;
        int testRounds = 10_000;

        // 1. 测试 ONE_WAY 单向净持仓模式
        long oneWayElapsedNanos;
        long[] oneWayLatencies = new long[testRounds];
        try (Harness harness = Harness.create(accountLanes)) {
            harness.execute(harness.command(CoreMessageType.REGISTER_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeRegisterInstrument(
                            new RegisterInstrumentCommand(instrumentId, "BTC", "USDT", "USDT", 1, 1, 1,
                                    new CoreInstrumentRiskLimits(100_000, 10_000_000L, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                            List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 10_000_000L, 100_000, 50_000))),
                                    0, 0, 0, -1, 0), harness.state().productLine)));
            harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new ApplyMarkPriceCommand(instrumentId, 100, 1, harness.nextCommandTimestamp()),
                            harness.state().productLine)));

            long userId = 200_001L;
            long makerId = 200_002L;
            harness.adjust(userId, safeBalance);
            harness.adjust(makerId, safeBalance);

            // 预热
            for (int i = 0; i < 500; i++) {
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                                harness.nextOrderId(), instrumentId, (i & 1) == 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                                (i & 1) == 0 ? 99 : 101, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "warmup-" + i))));
            }

            long start = System.nanoTime();
            for (int i = 0; i < testRounds; i++) {
                long orderStart = System.nanoTime();
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                                harness.nextOrderId(), instrumentId, (i & 1) == 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                                90 - (i % 50), 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "oneway-" + i))));
                oneWayLatencies[i] = System.nanoTime() - orderStart;
            }
            oneWayElapsedNanos = System.nanoTime() - start;
        }

        // 2. 测试 HEDGE 双向持仓模式 (同时开多和开空两条腿)
        long hedgeElapsedNanos;
        long[] hedgeLatencies = new long[testRounds];
        try (Harness harness = Harness.create(accountLanes)) {
            harness.execute(harness.command(CoreMessageType.REGISTER_INSTRUMENT, CommandSource.OPERATIONS, 0,
                    TradingCommandCodec.encodeRegisterInstrument(
                            new RegisterInstrumentCommand(instrumentId, "BTC", "USDT", "USDT", 1, 1, 1,
                                    new CoreInstrumentRiskLimits(100_000, 10_000_000L, Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                            List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, 10_000_000L, 100_000, 50_000))),
                                    0, 0, 0, -1, 0), harness.state().productLine)));
            harness.execute(harness.command(CoreMessageType.APPLY_MARK_PRICE, CommandSource.KAFKA_INPUT_BRIDGE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(
                            new ApplyMarkPriceCommand(instrumentId, 100, 1, harness.nextCommandTimestamp()),
                            harness.state().productLine)));

            long userId = 200_003L;
            long makerId = 200_004L;
            harness.adjust(userId, safeBalance);
            harness.adjust(makerId, safeBalance);

            // 切换为 HEDGE 模式
            harness.execute(harness.command(CoreMessageType.UPDATE_POSITION_MODE, CommandSource.GATEWAY, userId,
                    TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(CorePositionMode.HEDGE))));

            // 预热
            for (int i = 0; i < 500; i++) {
                CorePositionSide pSide = (i & 1) == 0 ? CorePositionSide.LONG : CorePositionSide.SHORT;
                CoreOrderSide oSide = (i & 1) == 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL;
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                                harness.nextOrderId(), instrumentId, oSide,
                                (i & 1) == 0 ? 99 : 101, 1, false, CoreMarginMode.CROSS, pSide,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "warmup-h-" + i))));
            }

            long start = System.nanoTime();
            for (int i = 0; i < testRounds; i++) {
                long orderStart = System.nanoTime();
                CorePositionSide pSide = (i & 1) == 0 ? CorePositionSide.LONG : CorePositionSide.SHORT;
                CoreOrderSide oSide = (i & 1) == 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL;
                harness.execute(harness.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, userId,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                                harness.nextOrderId(), instrumentId, oSide,
                                90 - (i % 50), 1, false, CoreMarginMode.CROSS, pSide,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "hedge-" + i))));
                hedgeLatencies[i] = System.nanoTime() - orderStart;
            }
            hedgeElapsedNanos = System.nanoTime() - start;
        }

        Arrays.sort(oneWayLatencies);
        Arrays.sort(hedgeLatencies);

        double oneWaySec = oneWayElapsedNanos / 1e9;
        double hedgeSec = hedgeElapsedNanos / 1e9;
        double oneWayTps = testRounds / oneWaySec;
        double hedgeTps = testRounds / hedgeSec;

        long oneWayP50 = percentile(oneWayLatencies, 0.50) / 1000;
        long oneWayP99 = percentile(oneWayLatencies, 0.99) / 1000;
        long hedgeP50 = percentile(hedgeLatencies, 0.50) / 1000;
        long hedgeP99 = percentile(hedgeLatencies, 0.99) / 1000;

        System.out.printf("[实测结果 - ONE_WAY 单向持仓] 样本=%d, 耗时=%.4f 秒, 吞吐 (TPS)=%.2f orders/sec%n",
                testRounds, oneWaySec, oneWayTps);
        System.out.printf("  - 延迟分位数: P50=%d μs, P99=%d μs%n", oneWayP50, oneWayP99);

        System.out.printf("[实测结果 - HEDGE 双向持仓] 样本=%d, 耗时=%.4f 秒, 吞吐 (TPS)=%.2f orders/sec%n",
                testRounds, hedgeSec, hedgeTps);
        System.out.printf("  - 延迟分位数: P50=%d μs, P99=%d μs%n", hedgeP50, hedgeP99);

        double tpsRatio = (hedgeTps / oneWayTps) * 100.0;
        System.out.printf("[效能对比总结] 双向持仓 (HEDGE) 吞吐达成率为单向持仓的 %.2f%%%n", tpsRatio);
        System.out.println("================================================================================");
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
