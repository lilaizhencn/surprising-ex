package com.surprising.aeron.client;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.*;

/** Actual client admission/dispatch path, with an immediate deterministic session instead of a network/Core. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
@Threads(4)
public class ClientSourceOrderingBenchmark {
    @State(Scope.Benchmark)
    public static class Shared {
        AeronClientPool pool;
        AtomicLong ids = new AtomicLong();
        AtomicLong offered = new AtomicLong();
        AtomicLong terminal = new AtomicLong();
        @Setup(Level.Trial) public void setup() {
            pool = new AeronClientPool("source-jmh", ProductLine.LINEAR_PERPETUAL,
                    List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(5),
                    "source-jmh", "epoch", new AeronClientCapacity(4, 1, 256, 64, 64, 32, 32), () -> new AeronClientPool.Session() {
                        long previous;
                        @Override public long offer(CoreMessage message) {
                            long next = message.header().sourceSequence();
                            if (next <= previous) throw new IllegalStateException("source order regressed");
                            previous = next;
                            offered.incrementAndGet();
                            return 1;
                        }
                        @Override public int pollEgress(int limit) { return 0; }
                        @Override public CoreResponse takeResponse(long correlation) {
                            terminal.incrementAndGet();
                            return new CoreResponse(ResponseStatus.APPLIED, 1, 1);
                        }
                        @Override public RuntimeException sessionFailure() { return null; }
                        @Override public boolean keepAlive() { return true; }
                        @Override public void close() { }
                    }, true);
        }
        @TearDown(Level.Trial) public void close() {
            pool.close();
            if (offered.get() != terminal.get()) throw new IllegalStateException("requests not drained");
            System.out.printf("clientSourceOrder=PASS totalWindow=256 offered=%d terminal=%d unfinished=0%n", offered.get(), terminal.get());
        }
    }

    @State(Scope.Thread)
    public static class Worker {
        final CompletableFuture<?>[] pending = new CompletableFuture<?>[64];
        final byte[] payload = TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1));
    }

    // Four workers each hold at most 64 requests: a fixed GLOBAL window of 256.
    @Benchmark @OperationsPerInvocation(64)
    public long dispatch(Shared shared, Worker worker) {
        for (int i = 0; i < 64; i++) {
            long id = shared.ids.incrementAndGet();
            worker.pending[i] = shared.pool.commandAsync(CoreMessageType.ADJUST_BALANCE, new UUID(1, id), i % 4, worker.payload);
        }
        for (int i = 0; i < 64; i++) { worker.pending[i].join(); worker.pending[i] = null; }
        return shared.terminal.get();
    }
}
