package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.state.account.BalanceRuntime;

import com.surprising.aeron.service.state.LaneCommitEvent;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.ReservationRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
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

    /** Diagnostic of metadata fused into the existing Lane commit task, not Cluster capacity. */
    @Benchmark
    @OperationsPerInvocation(256)
    public long metadataAndSequenceFanout(CommitState state) {
        return state.commitBatch(true);
    }

    @State(Scope.Thread)
    public static class CommitState {
        @Param("4")
        public int accountLanes;

        @Param("256")
        public int maxInFlight;

        private TradingRuntimeState runtime;
        private long[] users;
        private java.util.List<Long> metadataUsers;
        private java.util.List<Long> metadataOrders;
        private LaneCommitEvent[] commits;
        private long sequence;
        private String previousAccountLanes;

        @Setup(Level.Trial)
        public void setUp() {
            if (maxInFlight != 256) throw new IllegalArgumentException("maxInFlight must be 256");
            previousAccountLanes = System.getProperty("surprising.aeron.account-lanes");
            System.setProperty("surprising.aeron.account-lanes", Integer.toString(accountLanes));
            LaneTopology topology = LaneTopology.configured(false);
            runtime = new TradingRuntimeState(topology);
            users = new long[accountLanes];
            commits = new LaneCommitEvent[maxInFlight];
            for (int laneId = 0; laneId < accountLanes; laneId++) {
                users[laneId] = userForLane(topology, laneId);
                runtime.putUser(new UserRuntime(users[laneId]));
            }
            var userList = new java.util.ArrayList<Long>(accountLanes);
            var orderList = new java.util.ArrayList<Long>(accountLanes);
            var instrument = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
                    new RegisterInstrumentCommand("BTC-USDT", ContractType.LINEAR_PERPETUAL.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            for (int laneId = 0; laneId < accountLanes; laneId++) {
                long user = users[laneId];
                runtime.putBalance(new com.surprising.aeron.service.state.account.BalanceRuntime(user, 3, 900, 100));
                long orderId = 10000L + laneId;
                var order = new OrderRuntime(orderId, ProductLine.LINEAR_PERPETUAL, user, 5,
                        instrument, CoreOrderSide.BUY, 20_000 + laneId, false, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                        0, 0, 1, 0, 1, false);
                var reservation = new ReservationRuntime(orderId, user, 5,
                        ReservationKind.DERIVATIVE_MARGIN, 3, 100, 0, 0, 1);
                runtime.putOrder(order);
                runtime.putReservation(reservation);
                userList.add(user);
                orderList.add(orderId);
            }
            metadataUsers = java.util.List.copyOf(userList);
            metadataOrders = java.util.List.copyOf(orderList);
            runtime.clearChangedKeys();
            runtime.startAccountLanes();
        }

        long commitBatch() { return commitBatch(false); }

        long commitBatch(boolean metadata) {
            for (int index = 0; index < commits.length; index++) {
                long next = ++sequence;
                commits[index] = metadata
                        ? runtime.dispatchLaneMutation(next, metadataUsers, metadataOrders, next, next)
                        : runtime.dispatchLaneMutation(next, users);
            }
            for (int index = 0; index < commits.length; index++) {
                LaneCommitEvent commit = commits[index];
                while (!runtime.laneCommitComplete(commit)) Thread.onSpinWait();
                runtime.releaseLaneCommit(commit);
                commits[index] = null;
            }
            if (metadata) for (long id : metadataOrders)
                if (runtime.order(id).clusterPosition() != sequence)
                    throw new IllegalStateException("Lane metadata publication is not at the last sequence");
            return sequence;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            try {
                for (long user : users) {
                    var balance = runtime.balance(user, 3);
                    if (balance.availableUnits() != 900 || balance.lockedUnits() != 100)
                        throw new IllegalStateException("metadata commit changed account funds");
                }
            } finally {
                try { runtime.close(); }
                finally {
                    if (previousAccountLanes == null) System.clearProperty("surprising.aeron.account-lanes");
                    else System.setProperty("surprising.aeron.account-lanes", previousAccountLanes);
                }
            }
        }

        private static long userForLane(LaneTopology topology, int laneId) {
            for (long userId = 1; userId < 100_000; userId++) {
                if (topology.accountLaneId(userId) == laneId) return userId;
            }
            throw new IllegalStateException("unable to find user for Account Lane");
        }
    }
}
