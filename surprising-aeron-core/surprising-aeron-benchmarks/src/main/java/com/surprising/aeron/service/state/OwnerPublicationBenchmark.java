package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.UserRuntime;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures one 20-entity publication/handoff, not trading business operations. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class OwnerPublicationBenchmark {
    private final TradingRuntimeState runtime = new TradingRuntimeState();
    private final LaneCommitDelta delta = new LaneCommitDelta();
    private final RuntimeIndexedChangeBuffer<String, String> lane = new RuntimeIndexedChangeBuffer<>();
    private final OwnerIndexedChanges<String, String> owner = new OwnerIndexedChanges<>();
    private final UserRuntime[] users = new UserRuntime[20];
    private final RuntimeChangeBuffer<UserRuntime> mapChanges = new RuntimeChangeBuffer<>();
    private final org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<UserRuntime> map =
            new org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap<>();
    private final com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedIds =
            new com.surprising.aeron.service.command.support.PrimitiveLongChangeSet(20);
    private long coreSequence;

    @Setup public void setup() {
        for (int i = 0; i < users.length; i++) {
            users[i] = new UserRuntime(i + 1);
        }
    }

    /** Empty recycled buffers must not rescan their retained hash-table capacity. */
    @Benchmark public void clearRecycledBuffers() {
        delta.clear();
        runtime.treasury.clearChangedKeys();
    }

    /** Exercises the actual one-pass clearing drain, including explicit deletions. */
    @Benchmark public Object drainChangesToMap() {
        for (int i = 0; i < users.length; i++) mapChanges.put(i + 1, users[i]);
        mapChanges.put(1, null);
        mapChanges.drainToEclipseMap(map);
        return map.get(20);
    }

    @Benchmark public Object handoffAndQueryPreparedChanges() {
        for (int i = 0; i < 20; i++) lane.putIndexed(i, "position", true, i % 2 == 0 ? null : "index");
        owner.adopt(0, lane);
        Object result = owner.get(19);
        owner.clear();
        return result;
    }

    /** One 20-ID change set with duplicate writes; this is not Core business throughput. */
    @Benchmark public long collectChangedIds() {
        changedIds.clear();
        long first = (++coreSequence) * 20;
        for (int i = 0; i < 20; i++) changedIds.add(first + i);
        for (int i = 0; i < 20; i++) changedIds.add(first + i);
        return changedIds.valueAt(19);
    }

    @TearDown public void close() { runtime.close(); }
}
