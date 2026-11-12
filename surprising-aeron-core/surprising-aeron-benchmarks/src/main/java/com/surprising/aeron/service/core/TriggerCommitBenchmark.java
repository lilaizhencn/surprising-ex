package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.core.index.*;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.core.index.AdlPositionIndex;
import com.surprising.aeron.service.core.index.AlgoOrderIndex;
import com.surprising.aeron.service.core.index.CancelAllAfterIndex;
import com.surprising.aeron.service.core.index.LiquidationIndex;
import com.surprising.aeron.service.core.index.OpenInterestIndex;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.core.index.BizIndex;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
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
    @Param("2") public int accountLanes;
    private AccountBook accountBook;
    private TriggerOrderIndex triggerIndex;
    private List<Long> users, triggerIds;
    private CoreInstrument instrument;
    private long sequence;

    @Setup(Level.Trial)
    public void setup() {
        var topology = new LaneTopology(1, accountLanes, LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, 256, 256, 256);
        accountBook = new AccountBook(topology);
        var initial = TradingCoreState.empty(ProductLine.SPOT);
        var identities = new IdIndex();
        triggerIndex = new TriggerOrderIndex(initial);
        accountBook.bindFactIndexes(new BizIndex(
                new PositionUserIndex(initial),
                new OpenInterestIndex(initial),
                triggerIndex,
                new AlgoOrderIndex(initial),
                new LiquidationIndex(initial),
                new CancelAllAfterIndex(initial),
                new ActiveOrderIndex(initial),
                new AdlPositionIndex(initial, identities)), identities);
        instrument = CoreInstrument.from(ProductLine.SPOT,
                new RegisterInstrumentCommand(
                        "1", "BTC", "USDT", "USDT", 1, 1, 1, null, 0, 0, 0, -1, 0));
        var accounts = new java.util.ArrayList<Long>();
        var ids = new java.util.ArrayList<Long>();
        for (int lane = 0; lane < accountLanes; lane++) {
            long user = 1;
            while (topology.accountLaneId(user) != lane) user++;
            accounts.add(user);
            ids.add(901L + lane);
            accountBook.putUser(new UserAccount(user));
            accountBook.putBalance(new AccountBalance(user, 3, 1000, 0));
        }
        users = List.copyOf(accounts);
        triggerIds = List.copyOf(ids);
        accountBook.startAccountLanes();
    }

    @Setup(Level.Invocation)
    public void preparePendingFixture() {
        accountBook.acquireOwnerLaneAccess();
        try {
            for (int i = 0; i < users.size(); i++) {
                long id = triggerIds.get(i);
                var trigger = new CoreTriggerOrderState(id, users.get(i),
                        "trigger-" + id, "", instrument, CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS,
                        CoreTriggerCondition.LESS_OR_EQUAL, 90, 0, 0, 0, 0, 0,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                        CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 1, 1, 1);
                accountBook.putTriggerOrder(trigger);
                triggerIndex.apply(id, trigger);
            }
            accountBook.clearChangedKeys();
        } finally {
            accountBook.releaseOwnerLaneAccess();
        }
    }

    @Benchmark
    public long cancelWithSequenceCommit() {
        long before = accountBook.revision();
        long next = ++sequence;

        var event = accountBook.dispatchLaneMutation(next, users, null, triggerIds, next, next);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!accountBook.laneCommitComplete(event)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("trigger commit timeout");
            Thread.onSpinWait();
        }
        accountBook.releaseLaneCommit(event);
        if (accountBook.revision() != before + users.size()) throw new IllegalStateException("trigger version mismatch");
        accountBook.acquireOwnerLaneAccess();
        try {
            for (long id : triggerIds)
                if (accountBook.triggerOrder(id).status() != CoreTriggerOrderStatus.CANCELED)
                    throw new IllegalStateException("trigger not canceled");
        } finally {
            accountBook.releaseOwnerLaneAccess();
        }
        return next;
    }

    @TearDown(Level.Trial)
    public void close() {
        try {
            accountBook.acquireOwnerLaneAccess();
            try {
                for (long user : users) {
                    var balance = accountBook.balance(user, 3);
                    if (balance.availableUnits() != 1000 || balance.lockedUnits() != 0)
                        throw new IllegalStateException("trigger cancellation changed funds");
                }
            } finally {
                accountBook.releaseOwnerLaneAccess();
            }
        } finally { accountBook.close(); }
    }
}
