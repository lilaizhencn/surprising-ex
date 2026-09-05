package com.surprising.aeron.service;

import com.surprising.aeron.service.state.LaneCommitEvent;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.UserRuntime;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
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
public class AccountLaneCommitBenchmark {

    @Benchmark
    @OperationsPerInvocation(256)
    public long sequenceLocalFanout(CommitState state) {
        return state.commitBatch();
    }

    @State(Scope.Thread)
    public static class CommitState {
        @Param("4")
        public int accountLanes;

        @Param("256")
        public int maxInFlight;

        private TradingRuntimeState runtime;
        private long[] users;
        private LaneCommitEvent[] commits;
        private long sequence;

        @Setup(Level.Trial)
        public void setUp() {
            if (maxInFlight != 256) throw new IllegalArgumentException("maxInFlight must be 256");
            System.setProperty("surprising.aeron.account-lanes", Integer.toString(accountLanes));
            LaneTopology topology = LaneTopology.configured(false);
            runtime = new TradingRuntimeState(topology);
            users = new long[accountLanes];
            commits = new LaneCommitEvent[maxInFlight];
            for (int laneId = 0; laneId < accountLanes; laneId++) {
                users[laneId] = userForLane(topology, laneId);
                runtime.putUser(new UserRuntime(users[laneId]));
            }
            runtime.startAccountLanes();
        }

        long commitBatch() {
            for (int index = 0; index < commits.length; index++) {
                commits[index] = runtime.dispatchLaneMutation(++sequence, users);
            }
            for (int index = 0; index < commits.length; index++) {
                LaneCommitEvent commit = commits[index];
                while (!runtime.laneCommitComplete(commit)) Thread.onSpinWait();
                runtime.releaseLaneCommit(commit);
                commits[index] = null;
            }
            return sequence;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            runtime.close();
        }

        private static long userForLane(LaneTopology topology, int laneId) {
            for (long userId = 1; userId < 100_000; userId++) {
                if (topology.accountLaneId(userId) == laneId) return userId;
            }
            throw new IllegalStateException("unable to find user for Account Lane");
        }
    }
}
