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
        long result = state.scenario.run();
        counters.coreCommands += state.scenario.operations();
        return result;
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

        private LinearPerpetualMixedWorkload.Template template;

        @Setup(Level.Trial)
        public void setUpTrial() {
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            template = LinearPerpetualMixedWorkload.template(accountLanes, activeUsers, symbols);
        }

        @Override
        LinearPerpetualBenchmarkSupport.Scenario createScenario() {
            return LinearPerpetualMixedWorkload.scenario(template);
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class MixedWorkloadCounters {
        public long coreCommands;

        @Setup(Level.Iteration)
        public void reset() {
            coreCommands = 0;
        }
    }
}
