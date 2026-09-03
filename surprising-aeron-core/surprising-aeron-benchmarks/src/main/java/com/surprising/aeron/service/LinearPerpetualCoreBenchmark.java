package com.surprising.aeron.service;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
@Threads(1)
public class LinearPerpetualCoreBenchmark {

    @Benchmark
    public long limitOrderPlacement(LimitOrderState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long fullTakerFill(FullFillState state) {
        return state.scenario.run();
    }

    /** Trading-owner commit path after Core-Fact/export materialization was removed. */
    @Benchmark
    public long tradingCommitFullFill(TradingCommitState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long cancelRestingOrder(CancelState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long partialFill(PartialFillState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long multiLaneMatching(MultiLaneState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long riskScan(RiskScanState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long liquidationExecution(LiquidationState state) {
        return state.scenario.run();
    }

    @Benchmark
    public long snapshotRecovery(SnapshotRecoveryState state) {
        return state.scenario.run();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long productionMixedWorkload(ProductionMixedState state, MixedWorkloadCounters counters) {
        LinearPerpetualWorkloadEvent measurement = new LinearPerpetualWorkloadEvent();
        measurement.activeUsers = state.activeUsers;
        measurement.symbols = state.symbols;
        measurement.hftRounds = state.hftRounds;
        measurement.hftBatchSize = state.hftBatchSize;
        measurement.begin();
        long result;
        try {
            result = state.scenario.run();
        } finally {
            measurement.acceptedBusinessOperations = state.scenario.acceptedOperations();
            measurement.terminalBusinessOperations = state.scenario.terminalOperations();
            measurement.acceptedCoreMessages = state.scenario.acceptedCoreMessages();
            measurement.terminalCoreMessages = state.scenario.terminalCoreMessages();
            measurement.maxMatchingBacklog = state.scenario.maxBacklog();
            measurement.commit();
        }
        counters.acceptedBusinessOperations += state.scenario.acceptedOperations();
        counters.terminalBusinessOperations += state.scenario.terminalOperations();
        counters.unfinishedBusinessOperations += Math.subtractExact(
                state.scenario.acceptedOperations(), state.scenario.terminalOperations());
        counters.acceptedCoreMessages += state.scenario.acceptedCoreMessages();
        counters.terminalCoreMessages += state.scenario.terminalCoreMessages();
        counters.terminalTrades += state.scenario.terminalTrades();
        counters.unfinishedCoreMessages += Math.subtractExact(
                state.scenario.acceptedCoreMessages(), state.scenario.terminalCoreMessages());
        counters.laneOperations += state.scenario.laneOperations();
        counters.laneCommandOperations += state.scenario.laneOperations(0);
        counters.laneSettlementOperations += state.scenario.laneOperations(1);
        counters.laneQueryOperations += state.scenario.laneOperations(2);
        counters.laneRiskOperations += state.scenario.laneOperations(3);
        return result;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long scaleMixedWorkload(ScaleMixedState state, MixedWorkloadCounters counters) {
        LinearPerpetualWorkloadEvent measurement = new LinearPerpetualWorkloadEvent();
        measurement.activeUsers = state.activeUsers;
        measurement.symbols = state.activeSymbols;
        measurement.listedSymbols = state.listedSymbols;
        measurement.maxPositionsPerUser = state.maxPositionsPerUser;
        measurement.maxOpenOrdersPerUser = state.maxOpenOrdersPerUser;
        measurement.trafficProfile = state.trafficProfile;
        measurement.hftRounds = state.hftRounds;
        measurement.hftBatchSize = state.hftBatchSize;
        measurement.lifecycleSymbolsPerRun = state.lifecycleSymbolsPerRun;
        measurement.begin();
        long result;
        try {
            result = state.scenario.run();
        } finally {
            measurement.acceptedBusinessOperations = state.scenario.acceptedOperations();
            measurement.terminalBusinessOperations = state.scenario.terminalOperations();
            measurement.acceptedCoreMessages = state.scenario.acceptedCoreMessages();
            measurement.terminalCoreMessages = state.scenario.terminalCoreMessages();
            measurement.maxMatchingBacklog = state.scenario.maxBacklog();
            measurement.commit();
        }
        recordCounters(state.scenario, counters);
        return result;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long saturatedMatchingWorkload(SaturationState state, MixedWorkloadCounters counters) {
        LinearPerpetualSaturationEvent measurement = new LinearPerpetualSaturationEvent();
        boolean profileLatencies = measurement.isEnabled();
        if (profileLatencies) {
            measurement.activeUsers = state.activeUsers;
            measurement.activeSymbols = state.activeSymbols;
            measurement.maxInFlight = state.maxInFlight;
            measurement.operationsPerInvocation = state.operationsPerInvocation;
            measurement.begin();
        }
        long result;
        try {
            result = state.scenario.run();
        } finally {
            if (profileLatencies) {
                measurement.terminalBusinessOperations = state.scenario.terminalOperations();
                measurement.terminalCoreMessages = state.scenario.terminalCoreMessages();
                measurement.maxMatchingBacklog = state.scenario.maxBacklog();
                measurement.averageMatchingBacklog = state.scenario.averageMatchingBacklog();
                measurement.fullWindowPercentage = state.scenario.fullWindowPercentage();
                measurement.windowSamples = state.scenario.windowSamples();
                measurement.fullWindowSamples = state.scenario.fullWindowSamples();
                measurement.refillOperations = state.scenario.refillOperations();
                measurement.producerStarvationSamples = state.scenario.producerStarvationSamples();
                measurement.producerStarvationPercentage = state.scenario.producerStarvationPercentage();
                measurement.p50LatencyNanos = state.scenario.p50LatencyNanos();
                measurement.p99LatencyNanos = state.scenario.p99LatencyNanos();
                measurement.p999LatencyNanos = state.scenario.p999LatencyNanos();
                recordSaturationLatency(measurement, state.scenario.latencyReport());
                measurement.commit();
            }
        }
        recordCounters(state.scenario, counters);
        return result;
    }

    private static void recordSaturationLatency(
            LinearPerpetualSaturationEvent event,
            LinearPerpetualSaturationWorkload.LatencyReport report) {
        var entryAccepted = report.entryAccepted();
        var acceptedTerminal = report.acceptedTerminal();
        var entryTerminal = report.entryTerminal();
        event.businessType = report.businessType();
        event.loadModel = report.loadModel();
        event.targetOperationsPerSecond = report.targetOperationsPerSecond();
        event.scheduledBusinessOperations = entryTerminal.samples();
        event.coordinatedOmissionCorrected = entryTerminal.coordinatedOmissionCorrected();
        event.latencySamples = entryTerminal.samples();
        event.histogramLowestNanos = entryTerminal.rangeLowestNanos();
        event.histogramHighestNanos = entryTerminal.rangeHighestNanos();
        event.timeoutNanos = entryTerminal.timeoutNanos();
        event.latencyUnit = entryTerminal.unit();
        event.classificationSource = "EXHAUSTIVE_CORE_MESSAGE_TYPE_SWITCH";
        event.entryAcceptedHistogramCounts = entryAccepted.histogramCounts();
        event.acceptedTerminalHistogramCounts = acceptedTerminal.histogramCounts();
        event.entryTerminalHistogramCounts = entryTerminal.histogramCounts();
        event.entryAcceptedP50Nanos = entryAccepted.p50();
        event.entryAcceptedP90Nanos = entryAccepted.p90();
        event.entryAcceptedP95Nanos = entryAccepted.p95();
        event.entryAcceptedP99Nanos = entryAccepted.p99();
        event.entryAcceptedP999Nanos = entryAccepted.p999();
        event.entryAcceptedMaxNanos = entryAccepted.max();
        event.acceptedTerminalP50Nanos = acceptedTerminal.p50();
        event.acceptedTerminalP90Nanos = acceptedTerminal.p90();
        event.acceptedTerminalP95Nanos = acceptedTerminal.p95();
        event.acceptedTerminalP99Nanos = acceptedTerminal.p99();
        event.acceptedTerminalP999Nanos = acceptedTerminal.p999();
        event.acceptedTerminalMaxNanos = acceptedTerminal.max();
        event.entryTerminalP50Nanos = entryTerminal.p50();
        event.entryTerminalP90Nanos = entryTerminal.p90();
        event.entryTerminalP95Nanos = entryTerminal.p95();
        event.entryTerminalP99Nanos = entryTerminal.p99();
        event.entryTerminalP999Nanos = entryTerminal.p999();
        event.entryTerminalMaxNanos = entryTerminal.max();
    }

    private static void recordCounters(LinearPerpetualBenchmarkSupport.Scenario scenario,
                                       MixedWorkloadCounters counters) {
        counters.acceptedBusinessOperations += scenario.acceptedOperations();
        counters.terminalBusinessOperations += scenario.terminalOperations();
        counters.unfinishedBusinessOperations += Math.subtractExact(
                scenario.acceptedOperations(), scenario.terminalOperations());
        counters.acceptedCoreMessages += scenario.acceptedCoreMessages();
        counters.terminalCoreMessages += scenario.terminalCoreMessages();
        counters.terminalTrades += scenario.terminalTrades();
        counters.unfinishedCoreMessages += Math.subtractExact(
                scenario.acceptedCoreMessages(), scenario.terminalCoreMessages());
        counters.laneOperations += scenario.laneOperations();
        counters.laneCommandOperations += scenario.laneOperations(0);
        counters.laneSettlementOperations += scenario.laneOperations(1);
        counters.laneQueryOperations += scenario.laneOperations(2);
        counters.laneRiskOperations += scenario.laneOperations(3);
        if (scenario instanceof LinearPerpetualSaturationWorkload.SaturationScenario saturation) {
            counters.matchingWindowSamples += saturation.windowSamples();
            counters.matchingFullWindowSamples += saturation.fullWindowSamples();
            counters.matchingRefillOperations += saturation.refillOperations();
            counters.matchingProducerStarvationSamples += saturation.producerStarvationSamples();
        }
    }

    @State(Scope.Thread)
    public abstract static class InvocationState {
        @Param("4")
        public int accountLanes;

        LinearPerpetualBenchmarkSupport.Scenario scenario;

        @Setup(Level.Invocation)
        public void setUpInvocation() {
            scenario = createScenario();
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            scenario.verify();
            scenario.close();
        }

        abstract LinearPerpetualBenchmarkSupport.Scenario createScenario();
    }

    @State(Scope.Thread)
    public static class LimitOrderState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.limitOrderPlacement(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class FullFillState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.fullTakerFill(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class TradingCommitState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.fullTakerFill(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class CancelState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.cancelRestingOrder(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class PartialFillState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.partialFill(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class MultiLaneState extends SnapshotBackedState {
        @Param({"1000", "10000"})
        public int makerDepth;

        @Override
        LinearPerpetualBenchmarkSupport.SnapshotTemplate createTemplate() {
            return LinearPerpetualBenchmarkSupport.multiLaneMatchingTemplate(accountLanes, makerDepth);
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.multiLaneMatching(template, makerDepth);
        }
    }

    public abstract static class SnapshotBackedState extends InvocationState {
        LinearPerpetualBenchmarkSupport.SnapshotTemplate template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            template = createTemplate();
        }

        abstract LinearPerpetualBenchmarkSupport.SnapshotTemplate createTemplate();
    }

    @State(Scope.Thread)
    public static class RiskScanState extends SnapshotBackedState {
        @Param({"1000", "10000"})
        public int riskUsers;

        @Override
        LinearPerpetualBenchmarkSupport.SnapshotTemplate createTemplate() {
            return LinearPerpetualBenchmarkSupport.riskScanTemplate(accountLanes, riskUsers);
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.riskScan(template);
        }
    }

    @State(Scope.Thread)
    public static class LiquidationState extends InvocationState {
        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.liquidationExecution(accountLanes);
        }
    }

    @State(Scope.Thread)
    public static class SnapshotRecoveryState extends SnapshotBackedState {
        @Param("16")
        public int makerDepth;

        @Override
        LinearPerpetualBenchmarkSupport.SnapshotTemplate createTemplate() {
            return LinearPerpetualBenchmarkSupport.recoveryTemplate(accountLanes, makerDepth);
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualBenchmarkSupport.snapshotRecovery(template);
        }
    }

    @State(Scope.Thread)
    public static class ProductionMixedState extends InvocationState {
        @Param({"1000", "10000"})
        public int activeUsers;

        @Param("4")
        public int symbols;

        @Param("96")
        public int hftRounds;

        @Param("20")
        public int hftBatchSize;

        private LinearPerpetualMixedWorkload.Template template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, symbols);
            scenario = createScenario();
        }

        @Override
        @Setup(Level.Invocation)
        public void setUpInvocation() {
        }

        @Override
        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() {
            scenario.verify();
            scenario.close();
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualMixedWorkload.productionScenario(template, hftRounds, hftBatchSize);
        }
    }

    @State(Scope.Thread)
    public static class ScaleMixedState extends InvocationState {
        @Param("1000")
        public int activeUsers;

        @Param("4")
        public int listedSymbols;

        @Param("4")
        public int activeSymbols;

        @Param("1")
        public int maxPositionsPerUser;

        @Param("0")
        public int maxOpenOrdersPerUser;

        @Param("UNIFORM")
        public String trafficProfile;

        @Param("1")
        public int hftRounds;

        @Param("20")
        public int hftBatchSize;

        @Param("32")
        public int lifecycleSymbolsPerRun;

        private LinearPerpetualMixedWorkload.Template template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            var config = LinearPerpetualScaleConfig.scale(listedSymbols, activeSymbols,
                    maxPositionsPerUser, maxOpenOrdersPerUser,
                    LinearPerpetualTrafficProfile.parse(trafficProfile), lifecycleSymbolsPerRun);
            template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, config);
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualMixedWorkload.scaleScenario(template, hftRounds, hftBatchSize);
        }
    }

    @State(Scope.Benchmark)
    public static class SaturationState {
        @Param("1")
        public int matchingEngines;

        @Param("4")
        public int accountLanes;

        @Param("10000")
        public int activeUsers;

        @Param("512")
        public int listedSymbols;

        @Param("512")
        public int activeSymbols;

        @Param("5")
        public int maxPositionsPerUser;

        @Param("10")
        public int maxOpenOrdersPerUser;

        @Param("256")
        public int maxInFlight;

        @Param("16384")
        public int operationsPerInvocation;

        @Param("100000")
        public int targetOperationsPerSecond;

        private LinearPerpetualSaturationWorkload.SaturationScenario scenario;

        @Setup(Level.Trial)
        public void setUpTrial() {
            if (matchingEngines < 1 || matchingEngines > 64
                    || (matchingEngines & (matchingEngines - 1)) != 0
                    || maxInFlight != 256) {
                throw new IllegalArgumentException(
                        "matching engine count must be a power of two in [1,64] and in-flight must be 256");
            }
            System.setProperty("surprising.aeron.matching-engines", Integer.toString(matchingEngines));
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            var config = LinearPerpetualScaleConfig.scale(listedSymbols, activeSymbols,
                    maxPositionsPerUser, maxOpenOrdersPerUser,
                    LinearPerpetualTrafficProfile.UNIFORM, activeSymbols);
            var template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, config);
            scenario = LinearPerpetualSaturationWorkload.scenario(
                    template, maxInFlight, operationsPerInvocation, targetOperationsPerSecond);
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() {
            try {
                scenario.verify();
                scenario.close();
            } finally {
                System.clearProperty("surprising.aeron.fact-frame-pool-capacity");
            }
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class MixedWorkloadCounters {
        public long acceptedBusinessOperations;
        public long terminalBusinessOperations;
        public long unfinishedBusinessOperations;
        public long rejectedBusinessOperations;
        public long errorBusinessOperations;
        public long timedOutBusinessOperations;
        public long acceptedCoreMessages;
        public long terminalCoreMessages;
        public long terminalTrades;
        public long unfinishedCoreMessages;
        public long laneOperations;
        public long laneCommandOperations;
        public long laneSettlementOperations;
        public long laneQueryOperations;
        public long laneRiskOperations;
        public long matchingWindowSamples;
        public long matchingFullWindowSamples;
        public long matchingRefillOperations;
        public long matchingProducerStarvationSamples;

        @Setup(Level.Iteration)
        public void reset() {
            acceptedBusinessOperations = 0;
            terminalBusinessOperations = 0;
            unfinishedBusinessOperations = 0;
            rejectedBusinessOperations = 0;
            errorBusinessOperations = 0;
            timedOutBusinessOperations = 0;
            acceptedCoreMessages = 0;
            terminalCoreMessages = 0;
            terminalTrades = 0;
            unfinishedCoreMessages = 0;
            laneOperations = 0;
            laneCommandOperations = 0;
            laneSettlementOperations = 0;
            laneQueryOperations = 0;
            laneRiskOperations = 0;
            matchingWindowSamples = 0;
            matchingFullWindowSamples = 0;
            matchingRefillOperations = 0;
            matchingProducerStarvationSamples = 0;
        }
    }
}
