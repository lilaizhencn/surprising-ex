package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Isolated Lane commit diagnostic. Invocation setup restores pending fixtures; not Cluster capacity. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {
        "-XX:+UseZGC",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
@Threads(1)
public class TriggerCommitBenchmark {
    @Param("4") public int accountLanes;
    private TradingRuntimeState runtime;
    private List<Long> users, triggerIds;
    private long sequence;

    @Setup(Level.Trial)
    public void setup() {
        var topology = new LaneTopology(LaneTopology.ROUTE_VERSION, 1, 0, 0, accountLanes,
                LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 256, 256, 256);
        runtime = new TradingRuntimeState(topology);
        var accounts = new java.util.ArrayList<Long>();
        var ids = new java.util.ArrayList<Long>();
        for (int lane = 0; lane < accountLanes; lane++) {
            long user = 1;
            while (topology.accountLaneId(user) != lane) user++;
            accounts.add(user);
            ids.add(901L + lane);
            runtime.putUser(new UserRuntime(user));
            runtime.putBalance(new BalanceRuntime(user, 3, 1000, 0));
        }
        users = List.copyOf(accounts);
        triggerIds = List.copyOf(ids);
        runtime.startAccountLanes();
    }

    @Setup(Level.Invocation)
    public void preparePendingFixture() {
        for (int i = 0; i < users.size(); i++) {
            long id = triggerIds.get(i);
            runtime.putTriggerOrder(new CoreTriggerOrderState(id, ProductLine.SPOT, users.get(i),
                    "trigger-" + id, "", "BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS,
                    CoreTriggerCondition.LESS_OR_EQUAL, 90, 0, 0, 0, 0, 0,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "", 0, 0, 1, 1, 1));
        }
        runtime.clearChangedKeys();
    }

    @Benchmark
    public long cancelWithSequenceCommit() {
        long before = runtime.revision();
        long next = ++sequence;
        runtime.enterAsynchronousCommandScope();
        try {
            var event = runtime.dispatchLaneMutation(next, users, List.of(), triggerIds, next, next);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!runtime.laneCommitComplete(event)) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("trigger commit timeout");
                Thread.onSpinWait();
            }
            runtime.releaseLaneCommit(event);
        } finally { runtime.exitAsynchronousCommandScope(); }
        if (runtime.revision() != before + users.size()) throw new IllegalStateException("trigger version mismatch");
        for (long id : triggerIds)
            if (runtime.triggerOrder(id).status() != CoreTriggerOrderStatus.CANCELED)
                throw new IllegalStateException("trigger not canceled");
        return next;
    }

    @TearDown(Level.Trial)
    public void close() {
        try {
            for (long user : users) {
                var balance = runtime.balance(user, 3);
                if (balance.availableUnits() != 1000 || balance.lockedUnits() != 0)
                    throw new IllegalStateException("trigger cancellation changed funds");
            }
        } finally { runtime.close(); }
    }
}
