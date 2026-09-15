package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.LinkedHashMap;
import java.util.UUID;
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
import org.openjdk.jmh.annotations.Warmup;

/** Result retention turnover only; scores are retained results/s, not trading business ops/s. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CommandResultLedgerBenchmark {
    private CommandResultLedger ledger;
    private final UUID[] ids = new UUID[2048];
    private CommandFingerprint fingerprint;
    private long sequence;
    private static final byte[] RESPONSE = new byte[128];

    @Setup
    public void setup() {
        ledger = new CommandResultLedger(new LinkedHashMap<>());
        fingerprint = CommandFingerprint.fromBytes(new byte[CommandFingerprint.LENGTH]);
        for (int i = 0; i < ids.length; i++) ids[i] = new UUID(915, i);
        for (int i = 0; i < 10_000; i++) retainAndQuery();
    }

    @Benchmark
    public Object retainAndQuery() {
        UUID id = ids[(int) sequence++ & (ids.length - 1)];
        ledger.storeOwnedResult(id, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                sequence, 0, sequence, RESPONSE);
        return ledger.get(id);
    }
}
