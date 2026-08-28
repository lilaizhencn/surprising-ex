package com.surprising.aeron.service;

import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.Harness;
import com.surprising.aeron.service.LinearPerpetualBenchmarkSupport.SnapshotTemplate;
import java.util.Arrays;
import java.util.Locale;

public final class LinearPerpetualScaleProbeMain {

    private LinearPerpetualScaleProbeMain() {
    }

    public static void main(String[] args) {
        ProbeConfig probe = ProbeConfig.parse(args);
        LinearPerpetualBenchmarkSupport.configureAccountLanes(4);
        LinearPerpetualScaleConfig scale = LinearPerpetualScaleConfig.scale(
                probe.listedSymbols(), probe.activeSymbols(), probe.maxPositionsPerUser(),
                probe.maxOpenOrdersPerUser(), probe.trafficProfile(), probe.lifecycleSymbolsPerRun());

        long setupStart = System.nanoTime();
        var template = LinearPerpetualMixedWorkload.template(4, probe.activeUsers(), scale);
        long setupNanos = System.nanoTime() - setupStart;
        for (int cycle = 0; cycle < probe.warmupCycles(); cycle++) runCycle(template, probe, null, false);

        ProbeMetrics metrics = new ProbeMetrics(probe.measuredCycles());
        SnapshotTemplate finalSnapshot = null;
        for (int cycle = 0; cycle < probe.measuredCycles(); cycle++) {
            boolean captureSnapshot = cycle == probe.measuredCycles() - 1;
            finalSnapshot = runCycle(template, probe, metrics, captureSnapshot);
        }
        if (finalSnapshot == null) throw new IllegalStateException("final snapshot is missing");

        long restoreStart = System.nanoTime();
        try (Harness restored = Harness.restore(finalSnapshot)) {
            if (restored.state().tradingState().businessStateHash() != finalSnapshot.businessStateHash()) {
                throw new IllegalStateException("restored scale snapshot hash mismatch");
            }
        }
        metrics.finalRestoreNanos = System.nanoTime() - restoreStart;
        System.out.println(metrics.json(probe, template, setupNanos, finalSnapshot));
    }

    private static SnapshotTemplate runCycle(LinearPerpetualMixedWorkload.Template template,
                                             ProbeConfig probe, ProbeMetrics metrics,
                                             boolean captureSnapshot) {
        long restoreStart = System.nanoTime();
        try (var scenario = LinearPerpetualMixedWorkload.scaleScenario(
                template, probe.hftRounds(), probe.hftBatchSize())) {
            long restoreNanos = System.nanoTime() - restoreStart;
            long runStart = System.nanoTime();
            scenario.run();
            long runNanos = System.nanoTime() - runStart;
            scenario.verify();
            SnapshotTemplate snapshot = null;
            long snapshotNanos = 0;
            if (captureSnapshot) {
                long snapshotStart = System.nanoTime();
                snapshot = scenario.captureSnapshot();
                snapshotNanos = System.nanoTime() - snapshotStart;
            }
            if (metrics != null) metrics.record(scenario, restoreNanos, runNanos, snapshotNanos);
            return snapshot;
        }
    }

    private record ProbeConfig(int activeUsers, int listedSymbols, int activeSymbols,
                               int maxPositionsPerUser, int maxOpenOrdersPerUser,
                               LinearPerpetualTrafficProfile trafficProfile,
                               int hftRounds, int hftBatchSize, int lifecycleSymbolsPerRun,
                               int warmupCycles, int measuredCycles) {
        static ProbeConfig parse(String[] args) {
            if (args.length != 11) {
                throw new IllegalArgumentException("usage: users listed active maxPositions maxOpenOrders "
                        + "profile hftRounds hftBatchSize lifecycleSymbolsPerRun warmupCycles measuredCycles");
            }
            return new ProbeConfig(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                    LinearPerpetualTrafficProfile.parse(args[5]), Integer.parseInt(args[6]),
                    Integer.parseInt(args[7]), positive(args[8], "lifecycleSymbolsPerRun"),
                    positive(args[9], "warmupCycles"), positive(args[10], "measuredCycles"));
        }

        private static int positive(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new IllegalArgumentException(name + " must be positive");
            return parsed;
        }
    }

    private static final class ProbeMetrics {
        private final long[] latencies;
        private int cursor;
        private long runNanos;
        private long restoreNanos;
        private long terminalOperations;
        private long terminalCoreMessages;
        private long maxBacklog;
        private int incompleteRiskScans;
        private int incompleteFundingSettlements;
        private int activeOrders;
        private int positions;
        private int triggerOrders;
        private long snapshotNanos;
        private long finalRestoreNanos;

        private ProbeMetrics(int cycles) {
            latencies = new long[cycles];
        }

        private void record(LinearPerpetualBenchmarkSupport.Scenario scenario, long cycleRestoreNanos,
                            long cycleRunNanos, long cycleSnapshotNanos) {
            if (scenario.acceptedOperations() != scenario.terminalOperations()
                    || scenario.acceptedCoreMessages() != scenario.terminalCoreMessages()) {
                throw new IllegalStateException("scale probe left unfinished operations");
            }
            latencies[cursor++] = cycleRunNanos;
            runNanos = Math.addExact(runNanos, cycleRunNanos);
            restoreNanos = Math.addExact(restoreNanos, cycleRestoreNanos);
            terminalOperations = Math.addExact(terminalOperations, scenario.terminalOperations());
            terminalCoreMessages = Math.addExact(terminalCoreMessages, scenario.terminalCoreMessages());
            maxBacklog = Math.max(maxBacklog, scenario.maxBacklog());
            incompleteRiskScans = Math.max(incompleteRiskScans, scenario.incompleteRiskScans());
            incompleteFundingSettlements = Math.max(
                    incompleteFundingSettlements, scenario.incompleteFundingSettlements());
            activeOrders = scenario.activeOrders();
            positions = scenario.positions();
            triggerOrders = scenario.triggerOrders();
            snapshotNanos = Math.max(snapshotNanos, cycleSnapshotNanos);
        }

        private String json(ProbeConfig probe, LinearPerpetualMixedWorkload.Template template,
                            long setupNanos, SnapshotTemplate finalSnapshot) {
            Arrays.sort(latencies);
            double seconds = runNanos / 1_000_000_000.0;
            long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            return String.format(Locale.ROOT, "{" +
                            "\"status\":\"PASS\",\"fundsInvariant\":true," +
                            "\"activeUsers\":%d,\"listedSymbols\":%d,\"activeSymbols\":%d," +
                            "\"maxPositionsPerUser\":%d,\"maxOpenOrdersPerUser\":%d," +
                            "\"trafficProfile\":\"%s\",\"hftRounds\":%d,\"hftBatchSize\":%d," +
                            "\"lifecycleSymbolsPerRun\":%d," +
                            "\"setupMillis\":%.3f,\"initialSnapshotBytes\":%d," +
                            "\"measuredSeconds\":%.6f,\"terminalBusinessOperations\":%d," +
                            "\"terminalBusinessOpsPerSec\":%.3f,\"terminalCoreMessages\":%d," +
                            "\"terminalCoreMessagesPerSec\":%.3f," +
                            "\"p50Micros\":%.3f,\"p95Micros\":%.3f,\"p99Micros\":%.3f," +
                            "\"maxMicros\":%.3f,\"averageRestoreMillis\":%.3f," +
                            "\"maxMatchingBacklog\":%d,\"incompleteRiskScans\":%d," +
                            "\"incompleteFundingSettlements\":%d,\"activeOrders\":%d," +
                            "\"positions\":%d,\"triggerOrders\":%d," +
                            "\"finalSnapshotBytes\":%d,\"snapshotMillis\":%.3f," +
                            "\"finalRestoreMillis\":%.3f,\"usedHeapBytes\":%d}",
                    probe.activeUsers(), probe.listedSymbols(), probe.activeSymbols(),
                    probe.maxPositionsPerUser(), probe.maxOpenOrdersPerUser(), probe.trafficProfile(),
                    probe.hftRounds(), probe.hftBatchSize(), probe.lifecycleSymbolsPerRun(),
                    millis(setupNanos), template.snapshot().sizeBytes(),
                    seconds, terminalOperations, terminalOperations / seconds, terminalCoreMessages,
                    terminalCoreMessages / seconds, micros(percentile(0.50)), micros(percentile(0.95)),
                    micros(percentile(0.99)), micros(latencies[latencies.length - 1]),
                    millis(restoreNanos) / latencies.length, maxBacklog, incompleteRiskScans,
                    incompleteFundingSettlements, activeOrders, positions, triggerOrders,
                    finalSnapshot.sizeBytes(), millis(snapshotNanos), millis(finalRestoreNanos), usedHeap);
        }

        private long percentile(double percentile) {
            int index = (int) Math.ceil(percentile * latencies.length) - 1;
            return latencies[Math.max(0, index)];
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }

        private static double micros(long nanos) {
            return nanos / 1_000.0;
        }
    }
}
