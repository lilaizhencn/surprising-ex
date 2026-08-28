package com.surprising.aeron.service;

import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Harness;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.Locale;

public final class LinearPerpetualScaleSoakMain {

    private LinearPerpetualScaleSoakMain() {
    }

    public static void main(String[] args) {
        SoakConfig config = SoakConfig.parse(args);
        LinearPerpetualBenchmarkSupport.configureAccountLanes(4);
        LinearPerpetualScaleConfig scale = LinearPerpetualScaleConfig.scale(
                config.listedSymbols(), config.activeSymbols(), config.maxPositionsPerUser(),
                config.maxOpenOrdersPerUser(), config.trafficProfile(), config.lifecycleSymbolsPerRun());
        long setupStart = System.nanoTime();
        LinearPerpetualMixedWorkload.Template template =
                LinearPerpetualMixedWorkload.template(4, config.activeUsers(), scale);
        long setupNanos = System.nanoTime() - setupStart;
        PrimitiveLatencies latencies = new PrimitiveLatencies();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> "direct".equals(pool.getName())).findFirst().orElse(null);
        long startedAt = System.nanoTime();
        long deadline = Math.addExact(startedAt, java.util.concurrent.TimeUnit.SECONDS.toNanos(config.durationSeconds()));
        long nextSample = Math.addExact(startedAt,
                java.util.concurrent.TimeUnit.SECONDS.toNanos(config.sampleSeconds()));
        long terminalOperations = 0;
        long terminalCoreMessages = 0;
        long sampleOperations = 0;
        long sampleStartedAt = startedAt;
        long maxBacklog = 0;
        long initialHeap = memory.getHeapMemoryUsage().getUsed();
        long maxHeap = initialHeap;
        long initialDirect = direct == null ? 0 : direct.getMemoryUsed();
        long maxDirect = initialDirect;
        LinearPerpetualBenchmarkSupport.SnapshotTemplate snapshot;
        try (LinearPerpetualMixedWorkload.StatefulScenario scenario =
                     LinearPerpetualMixedWorkload.scaleScenario(
                             template, config.hftRounds(), config.hftBatchSize())) {
            while (System.nanoTime() < deadline) {
                long runStart = System.nanoTime();
                scenario.run();
                long runNanos = System.nanoTime() - runStart;
                latencies.add(runNanos);
                terminalOperations = Math.addExact(terminalOperations, scenario.terminalOperations());
                terminalCoreMessages = Math.addExact(terminalCoreMessages, scenario.terminalCoreMessages());
                sampleOperations = Math.addExact(sampleOperations, scenario.terminalOperations());
                maxBacklog = Math.max(maxBacklog, scenario.maxBacklog());
                long now = System.nanoTime();
                if (now >= nextSample) {
                    long heap = memory.getHeapMemoryUsage().getUsed();
                    long directBytes = direct == null ? 0 : direct.getMemoryUsed();
                    maxHeap = Math.max(maxHeap, heap);
                    maxDirect = Math.max(maxDirect, directBytes);
                    double sampleSeconds = (now - sampleStartedAt) / 1_000_000_000.0;
                    System.out.printf(Locale.ROOT,
                            "{\"type\":\"sample\",\"elapsedSeconds\":%.3f,"
                                    + "\"terminalBusinessOpsPerSec\":%.3f,\"usedHeapBytes\":%d,"
                                    + "\"directBytes\":%d,\"incompleteRiskScans\":%d,"
                                    + "\"incompleteFundingSettlements\":%d}%n",
                            (now - startedAt) / 1_000_000_000.0, sampleOperations / sampleSeconds,
                            heap, directBytes, scenario.incompleteRiskScans(),
                            scenario.incompleteFundingSettlements());
                    sampleOperations = 0;
                    sampleStartedAt = now;
                    nextSample = Math.addExact(now,
                            java.util.concurrent.TimeUnit.SECONDS.toNanos(config.sampleSeconds()));
                }
            }
            scenario.verify();
            snapshot = scenario.captureSnapshot();
            long finalHeap = memory.getHeapMemoryUsage().getUsed();
            long finalDirect = direct == null ? 0 : direct.getMemoryUsed();
            maxHeap = Math.max(maxHeap, finalHeap);
            maxDirect = Math.max(maxDirect, finalDirect);
            long restoreStart = System.nanoTime();
            try (Harness restored = Harness.restore(snapshot)) {
                if (restored.state().tradingState().businessStateHash() != snapshot.businessStateHash()) {
                    throw new IllegalStateException("soak snapshot restore changed business state");
                }
            }
            long restoreNanos = System.nanoTime() - restoreStart;
            long elapsedNanos = System.nanoTime() - startedAt;
            System.out.printf(Locale.ROOT,
                    "{\"type\":\"summary\",\"status\":\"PASS\",\"fundsInvariant\":true,"
                            + "\"activeUsers\":%d,\"listedSymbols\":%d,\"activeSymbols\":%d,"
                            + "\"maxPositionsPerUser\":%d,\"maxOpenOrdersPerUser\":%d,"
                            + "\"trafficProfile\":\"%s\",\"lifecycleSymbolsPerRun\":%d,"
                            + "\"elapsedSeconds\":%.3f,\"setupMillis\":%.3f,"
                            + "\"terminalBusinessOperations\":%d,\"terminalBusinessOpsPerSec\":%.3f,"
                            + "\"terminalCoreMessages\":%d,\"terminalCoreMessagesPerSec\":%.3f,"
                            + "\"sweepP50Micros\":%.3f,\"sweepP95Micros\":%.3f,"
                            + "\"sweepP99Micros\":%.3f,\"sweepMaxMicros\":%.3f,"
                            + "\"maxMatchingBacklog\":%d,\"incompleteRiskScans\":%d,"
                            + "\"incompleteFundingSettlements\":%d,\"initialHeapBytes\":%d,"
                            + "\"finalHeapBytes\":%d,\"maxHeapBytes\":%d,\"initialDirectBytes\":%d,"
                            + "\"finalDirectBytes\":%d,\"maxDirectBytes\":%d,"
                            + "\"snapshotBytes\":%d,\"restoreMillis\":%.3f}%n",
                    config.activeUsers(), config.listedSymbols(), config.activeSymbols(),
                    config.maxPositionsPerUser(), config.maxOpenOrdersPerUser(), config.trafficProfile(),
                    config.lifecycleSymbolsPerRun(), elapsedNanos / 1_000_000_000.0,
                    setupNanos / 1_000_000.0, terminalOperations,
                    terminalOperations / (elapsedNanos / 1_000_000_000.0), terminalCoreMessages,
                    terminalCoreMessages / (elapsedNanos / 1_000_000_000.0),
                    latencies.micros(0.50), latencies.micros(0.95), latencies.micros(0.99),
                    latencies.maxMicros(), maxBacklog, scenario.incompleteRiskScans(),
                    scenario.incompleteFundingSettlements(), initialHeap, finalHeap, maxHeap,
                    initialDirect, finalDirect, maxDirect, snapshot.sizeBytes(), restoreNanos / 1_000_000.0);
        }
    }

    private record SoakConfig(int activeUsers, int listedSymbols, int activeSymbols,
                              int maxPositionsPerUser, int maxOpenOrdersPerUser,
                              LinearPerpetualTrafficProfile trafficProfile, int hftRounds,
                              int hftBatchSize, int lifecycleSymbolsPerRun,
                              int durationSeconds, int sampleSeconds) {
        private static SoakConfig parse(String[] args) {
            if (args.length != 11) {
                throw new IllegalArgumentException("usage: users listed active maxPositions maxOpenOrders "
                        + "profile hftRounds hftBatchSize lifecycleSymbolsPerRun durationSeconds sampleSeconds");
            }
            return new SoakConfig(positive(args[0], "activeUsers"), positive(args[1], "listedSymbols"),
                    positive(args[2], "activeSymbols"), positive(args[3], "maxPositionsPerUser"),
                    nonNegative(args[4], "maxOpenOrdersPerUser"),
                    LinearPerpetualTrafficProfile.parse(args[5]), positive(args[6], "hftRounds"),
                    positive(args[7], "hftBatchSize"), positive(args[8], "lifecycleSymbolsPerRun"),
                    positive(args[9], "durationSeconds"), positive(args[10], "sampleSeconds"));
        }

        private static int positive(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
            return parsed;
        }

        private static int nonNegative(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return parsed;
        }
    }

    private static final class PrimitiveLatencies {
        private long[] values = new long[256];
        private int size;
        private boolean sorted;

        private void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
            values[size++] = value;
            sorted = false;
        }

        private double micros(double percentile) {
            sort();
            int index = Math.max(0, (int) Math.ceil(percentile * size) - 1);
            return values[index] / 1_000.0;
        }

        private double maxMicros() {
            sort();
            return values[size - 1] / 1_000.0;
        }

        private void sort() {
            if (size == 0) throw new IllegalStateException("soak produced no measured sweep");
            if (sorted) return;
            Arrays.sort(values, 0, size);
            sorted = true;
        }
    }
}
