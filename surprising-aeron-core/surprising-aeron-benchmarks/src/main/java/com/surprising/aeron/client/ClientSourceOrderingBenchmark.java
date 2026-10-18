package com.surprising.aeron.client;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** Pool and encoded client I/O, with an immediate loopback peer instead of a network/Core. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
@Threads(4)
@Slf4j
public class ClientSourceOrderingBenchmark {
    @State(Scope.Benchmark)
    public static class Shared {
        AeronClientPool pool;
        AtomicLong ids = new AtomicLong();
        AtomicLong offered = new AtomicLong();
        AtomicLong terminal = new AtomicLong();
        private long iterationStartMillis, iterationOffered, iterationTerminal;
        private ClientInterval interval;
        @Setup(Level.Trial) public void setup() {
            pool = new AeronClientPool("source-jmh", ProductLine.LINEAR_PERPETUAL,
                    List.of("localhost", "localhost", "localhost"), "localhost", Duration.ofSeconds(5),
                    "source-jmh", "epoch", new AeronClientCapacity(4, 1, 256, 64, 64, 32, 32),
                    () -> new LoopbackSession(offered, terminal), true);
        }
        @Setup(Level.Iteration) public void beginIteration(IterationParams params) {
            iterationOffered = offered.get();
            iterationTerminal = terminal.get();
            iterationStartMillis = System.currentTimeMillis();
            interval = new ClientInterval();
            interval.phase = params.getType().name();
            interval.begin();
        }
        @TearDown(Level.Iteration) public void endIteration() {
            long accepted = offered.get() - iterationOffered;
            long completed = terminal.get() - iterationTerminal;
            interval.end();
            interval.commit();
            log.info("pipelineInterval phase={} batchSize=1 startEpochMillis={} endEpochMillis={} terminalMessages={} terminalBusinessOperations={} accepted={} unfinished={}",
                    interval.phase, iterationStartMillis, System.currentTimeMillis(), completed, completed,
                    accepted, accepted - completed);
            if (accepted != completed) throw new IllegalStateException("client iteration not drained");
        }
        @TearDown(Level.Trial) public void close() {
            pool.close();
            if (offered.get() != terminal.get()) throw new IllegalStateException("requests not drained");
            log.info("clientSourceOrder=PASS totalWindow=256 offered={} terminal={} unfinished=0", offered.get(), terminal.get());
        }
    }

    @jdk.jfr.Name("surprising.ClientAllocationInterval")
    @jdk.jfr.StackTrace(false)
    public static class ClientInterval extends jdk.jfr.Event { public String phase; }

    /** Only the peer is simulated; SurprisingAeronClient encoding, response decode and ownership are real. */
    private static final class LoopbackSession implements AeronClientPool.Session {
        private final SurprisingAeronClient client;
        private final AtomicLong offered, terminal;
        private final CoreResponse applied = new CoreResponse(ResponseStatus.APPLIED, 1);
        private final byte[] responseBytes = new byte[512];
        private final UnsafeBuffer responseBuffer = new UnsafeBuffer(responseBytes);
        private long previous;

        LoopbackSession(AtomicLong offered, AtomicLong terminal) {
            this.offered = offered;
            this.terminal = terminal;
            client = new SurprisingAeronClient(ProductLine.LINEAR_PERPETUAL, Duration.ofSeconds(5),
                    new SurprisingAeronClient.ClusterOperations() {
                        @Override public long offer(DirectBuffer buffer, int offset, int length) {
                            return replyToOffer(buffer, offset, length);
                        }
                        @Override public int pollEgress() { return 0; }
                        @Override public boolean sendKeepAlive() { return true; }
                        @Override public void close() { }
                    });
        }
        @Override public long offer(CoreMessage message) { return client.offer(message); }
        @Override public int pollEgress(int limit) { return client.pollEgress(limit); }
        @Override public CoreResponse takeResponse(long correlation) {
            CoreResponse response = client.takeResponse(correlation);
            if (response != null) terminal.incrementAndGet();
            return response;
        }
        @Override
        public int drainResponses(java.util.function.BiConsumer<Long, CoreResponse> consumer) {
            return client.drainResponses((correlation, response) -> {
                terminal.incrementAndGet();
                consumer.accept(correlation, response);
            });
        }
        @Override public RuntimeException sessionFailure() { return client.sessionFailure(); }
        @Override public boolean keepAlive() { return client.keepAlive(); }
        @Override public void close() { client.close(); }

        private long replyToOffer(DirectBuffer buffer, int offset, int length) {
            // These peer allocations are included in this component benchmark, never in a Core B/op claim.
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            CoreMessage request = CoreMessageCodec.decode(bytes);
            long next = request.header().sourceSequence();
            if (next <= previous) throw new IllegalStateException("source order regressed");
            previous = next;
            offered.incrementAndGet();
            int responseLength = CoreMessageCodec.encodeResponse(request.header().response(CoreMessageType.COMMAND_RESULT),
                    applied, next, responseBytes);
            client.onMessage(1, 0, responseBuffer, 0, responseLength, null);
            return 1;
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
