package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;
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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
@Threads(1)
public class DerivativeCoreBenchmark {

    @Benchmark
    public long productionMixedWorkload(ProductionState state, Counters counters) {
        long result = state.scenario.run();
        counters.acceptedBusinessOperations += state.scenario.acceptedOperations();
        counters.terminalBusinessOperations += state.scenario.terminalOperations();
        counters.unfinishedBusinessOperations += Math.subtractExact(
                state.scenario.acceptedOperations(), state.scenario.terminalOperations());
        counters.acceptedCoreMessages += state.scenario.acceptedCoreMessages();
        counters.terminalCoreMessages += state.scenario.terminalCoreMessages();
        counters.unfinishedCoreMessages += Math.subtractExact(
                state.scenario.acceptedCoreMessages(), state.scenario.terminalCoreMessages());
        counters.laneOperations += state.scenario.laneOperations();
        counters.laneCommandOperations += state.scenario.laneOperations(0);
        counters.laneSettlementOperations += state.scenario.laneOperations(1);
        counters.laneQueryOperations += state.scenario.laneOperations(2);
        counters.laneRiskOperations += state.scenario.laneOperations(3);
        return result;
    }

    @State(Scope.Thread)
    public static class ProductionState {
        @Param({"LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
        public ProductLine productLine;

        @Param("4")
        public int accountLanes;

        @Param({"1000", "10000"})
        public int activeUsers;

        @Param("256")
        public int symbols;

        @Param("256")
        public int maxInFlight;

        @Param("32")
        public int hftRounds;

        @Param("20")
        public int hftBatchSize;

        private LinearPerpetualBenchmarkSupport.Scenario scenario;

        @Setup(Level.Trial)
        public void setUpTrial() {
            if (maxInFlight != 256 || symbols != 256) {
                throw new IllegalArgumentException("derivative qualification requires 256 symbols/in-flight");
            }
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            var template = DerivativeMixedWorkload.template(
                    productLine, accountLanes, activeUsers, symbols);
            scenario = DerivativeMixedWorkload.scenario(template, hftRounds, hftBatchSize);
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() {
            scenario.verify();
            scenario.close();
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class Counters {
        public long acceptedBusinessOperations;
        public long terminalBusinessOperations;
        public long unfinishedBusinessOperations;
        public long acceptedCoreMessages;
        public long terminalCoreMessages;
        public long unfinishedCoreMessages;
        public long laneOperations;
        public long laneCommandOperations;
        public long laneSettlementOperations;
        public long laneQueryOperations;
        public long laneRiskOperations;

        @Setup(Level.Iteration)
        public void reset() {
            acceptedBusinessOperations = 0;
            terminalBusinessOperations = 0;
            unfinishedBusinessOperations = 0;
            acceptedCoreMessages = 0;
            terminalCoreMessages = 0;
            unfinishedCoreMessages = 0;
            laneOperations = 0;
            laneCommandOperations = 0;
            laneSettlementOperations = 0;
            laneQueryOperations = 0;
            laneRiskOperations = 0;
        }
    }
}
