package com.surprising.realtime.api;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Current production codec/Redis command boundary; no network or Core throughput claim. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
@State(Scope.Thread)
public class RealtimeAllocationBenchmark {
    @Param({"128", "4096"}) public int payloadBytes;
    private RealtimeFrame frame;
    private byte[] encoded;
    private List<RealtimeFrame> frames;
    @Setup public void setup() {
        frame = new RealtimeFrame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.ORDER,
                42, 100, 0, 1000, 0, "BTCUSDT", "123456789", new byte[payloadBytes]);
        encoded = RealtimeFrameCodec.encode(frame);
        frames = List.of(frame);
        if (RealtimeFrameCodec.decode(encoded).payloadLength() != payloadBytes)
            throw new IllegalStateException("codec round trip failed");
    }
    @Benchmark public byte[] encode() { return RealtimeFrameCodec.encode(frame); }
    @Benchmark public RealtimeFrame decode() { return RealtimeFrameCodec.decode(encoded); }
    @Benchmark public byte[][] redisDelta() { return ValkeyReadViewStore.deltaCommand(frames, 100); }
}
