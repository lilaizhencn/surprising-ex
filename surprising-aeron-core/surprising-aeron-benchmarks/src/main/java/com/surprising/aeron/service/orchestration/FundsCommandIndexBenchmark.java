package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures the local funds identity index only; excludes Cluster replication and account settlement. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1)
public class FundsCommandIndexBenchmark {
    private FundsCommandIndex index;
    private CommandFingerprint fingerprint;
    private long sequence;
    @Setup public void setup() {
        index = new FundsCommandIndex();
        fingerprint = CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]);
        for (sequence = 0; sequence < 140_000; sequence++) index.put(new UUID(19, sequence), fingerprint);
    }
    @Benchmark public void append() { index.put(new UUID(19, sequence++), fingerprint); }
    @Benchmark public Object historicalLookup() { return index.get(new UUID(19, (sequence++ & Long.MAX_VALUE) % 140_000)); }
    @TearDown public void close() { index.close(); }
}
