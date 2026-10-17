package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.service.orchestration.TradingExecutionMode;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExecutionModeComparisonTest {

    @Test
    void runComprehensiveComparison() {
        System.out.println("==========================================================================================");
        System.out.println("          SURPRISING-EX: PIPELINED vs FUSED (SINGLE-THREAD) PERFORMANCE BENCHMARK          ");
        System.out.println("==========================================================================================");

        ProductLine[] productLines = new ProductLine[] {
            ProductLine.SPOT,
            ProductLine.LINEAR_PERPETUAL,
            ProductLine.INVERSE_PERPETUAL,
            ProductLine.LINEAR_DELIVERY,
            ProductLine.INVERSE_DELIVERY,
            ProductLine.OPTION
        };

        List<ComparisonResult> results = new ArrayList<>();

        for (ProductLine product : productLines) {
            System.out.printf("%n>>> Benchmarking Product Line: %s <<<%n", product);
            
            // 1. Measure PIPELINED mode
            BenchmarkStats pipelinedStats = measureMode(product, TradingExecutionMode.PIPELINED, 10, 40);
            
            // 2. Measure FUSED mode
            BenchmarkStats fusedStats = measureMode(product, TradingExecutionMode.FUSED, 10, 40);

            printDetailComparison(product.name(), pipelinedStats, fusedStats);
            results.add(new ComparisonResult(product, pipelinedStats, fusedStats));
        }

        printFinalSummaryTable(results);
    }

    private BenchmarkStats measureMode(ProductLine product, TradingExecutionMode mode, int warmupRounds, int measureRounds) {
        System.setProperty("surprising.aeron.execution-mode", mode.name());

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.batchSize = 1;
            workload.accountPattern = "DISTINCT";
            workload.setup();

            int threadCount = Thread.activeCount();
            var counters = new ContinuousOwnerBenchmark.Counters();

            // Warmup
            for (int i = 0; i < warmupRounds; i++) {
                workload.placeCancelWithoutTimers(counters);
            }

            // Measurement
            long[] latenciesNanos = new long[measureRounds];
            long totalOps = 0;
            long startAlloc = threadBean.getThreadAllocatedBytes(threadId);
            long startTimeNanos = System.nanoTime();

            for (int i = 0; i < measureRounds; i++) {
                long iterStart = System.nanoTime();
                workload.placeCancelWithoutTimers(counters);
                long iterEnd = System.nanoTime();
                latenciesNanos[i] = iterEnd - iterStart;
                totalOps += 512; // 256 place + 256 cancel
            }

            long totalElapsedNanos = System.nanoTime() - startTimeNanos;
            long endAlloc = threadBean.getThreadAllocatedBytes(threadId);

            Arrays.sort(latenciesNanos);
            double throughputOpsPerSec = (double) totalOps / (totalElapsedNanos / 1_000_000_000.0);
            double avgLatencyMicros = (double) (totalElapsedNanos / totalOps) / 1_000.0;
            double p50Micros = (latenciesNanos[(int) (measureRounds * 0.50)] / 512.0) / 1_000.0;
            double p95Micros = (latenciesNanos[(int) (measureRounds * 0.95)] / 512.0) / 1_000.0;
            double p99Micros = (latenciesNanos[(int) (measureRounds * 0.99)] / 512.0) / 1_000.0;
            long allocatedBytesPerOp = (endAlloc - startAlloc) / totalOps;

            return new BenchmarkStats(
                mode, throughputOpsPerSec, avgLatencyMicros, p50Micros, p95Micros, p99Micros, allocatedBytesPerOp, threadCount
            );
        }
    }

    private void printDetailComparison(String productName, BenchmarkStats pipelined, BenchmarkStats fused) {
        double tpsGain = ((fused.throughput - pipelined.throughput) / pipelined.throughput) * 100.0;
        double p50Reduction = ((pipelined.p50Micros - fused.p50Micros) / pipelined.p50Micros) * 100.0;
        double p99Reduction = ((pipelined.p99Micros - fused.p99Micros) / pipelined.p99Micros) * 100.0;
        double allocReduction = ((double) (pipelined.allocBytesPerOp - fused.allocBytesPerOp) / pipelined.allocBytesPerOp) * 100.0;

        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("Metric                      | PIPELINED (Multi-Thread) | FUSED (Deterministic) | Improvement%n");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("Throughput (ops/sec)        | %18.2f   | %17.2f     | %+6.2f%%%n", 
                pipelined.throughput, fused.throughput, tpsGain);
        System.out.printf("P50 Single Op Latency (µs)  | %18.2f   | %17.2f     | %+6.2f%%%n", 
                pipelined.p50Micros, fused.p50Micros, p50Reduction);
        System.out.printf("P95 Single Op Latency (µs)  | %18.2f   | %17.2f     | %+6.2f%%%n", 
                pipelined.p95Micros, fused.p95Micros, ((pipelined.p95Micros - fused.p95Micros) / pipelined.p95Micros) * 100.0);
        System.out.printf("P99 Single Op Latency (µs)  | %18.2f   | %17.2f     | %+6.2f%%%n", 
                pipelined.p99Micros, fused.p99Micros, p99Reduction);
        System.out.printf("Allocated Bytes / Op (B/op) | %18d   | %17d     | %+6.2f%%%n", 
                pipelined.allocBytesPerOp, fused.allocBytesPerOp, allocReduction);
        System.out.printf("Active JVM Threads          | %18d   | %17d     | %d fewer threads%n", 
                pipelined.threadCount, fused.threadCount, (pipelined.threadCount - fused.threadCount));
        System.out.println("------------------------------------------------------------------------------------------");
    }

    private void printFinalSummaryTable(List<ComparisonResult> results) {
        System.out.println("\n\n=================================================================================================================");
        System.out.println("                               六大产品线综合性能对比总表 (SUMMARY TABLE)");
        System.out.println("=================================================================================================================");
        System.out.printf("%-18s | %-22s | %-22s | %-16s | %-14s%n", 
                "ProductLine", "Pipelined TPS (p50)", "Fused TPS (p50)", "TPS Gain", "Memory Saved");
        System.out.println("-----------------------------------------------------------------------------------------------------------------");
        for (var res : results) {
            double tpsGain = ((res.fused.throughput - res.pipelined.throughput) / res.pipelined.throughput) * 100.0;
            double allocReduction = ((double) (res.pipelined.allocBytesPerOp - res.fused.allocBytesPerOp) / res.pipelined.allocBytesPerOp) * 100.0;
            System.out.printf("%-18s | %10.1f op/s (%4.1fµs) | %10.1f op/s (%4.1fµs) | %+6.2f%%         | %+6.2f%%%n",
                    res.product.name(),
                    res.pipelined.throughput, res.pipelined.p50Micros,
                    res.fused.throughput, res.fused.p50Micros,
                    tpsGain, allocReduction);
        }
        System.out.println("=================================================================================================================\n");
    }

    record BenchmarkStats(
        TradingExecutionMode mode,
        double throughput,
        double avgLatencyMicros,
        double p50Micros,
        double p95Micros,
        double p99Micros,
        long allocBytesPerOp,
        int threadCount
    ) {}

    record ComparisonResult(
        ProductLine product,
        BenchmarkStats pipelined,
        BenchmarkStats fused
    ) {}
}
