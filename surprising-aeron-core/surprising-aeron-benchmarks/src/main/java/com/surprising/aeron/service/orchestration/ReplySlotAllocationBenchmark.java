package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

/** Actual leased ingress -> Owner publisher -> output queue -> encoder, without Aeron I/O.
 * Allocation is per Core message, not per batch item or whole-Core business operation. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class ReplySlotAllocationBenchmark {
    private static final int WINDOW = 256;
    private static final byte[] RESULT = CoreCommandResultCodec.encode(java.util.List.of(), java.util.List.of());
    private final UnsafeBuffer[] frames = new UnsafeBuffer[WINDOW];
    private final CoreMessageFlyweightDecoder decoder = new CoreMessageFlyweightDecoder();
    private final SinkSession session = new SinkSession();
    private final ClusterServiceEgress egress = new ClusterServiceEgress((cluster, target) -> session);
    private long sessionId;
    private TradingCoreRuntime runtime;
    private TradingCoreOwner processor;
    private TradingCoreOwner.ResponseSink publisher;

    @Setup public void setup() {
        runtime = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL);
        processor = new TradingCoreOwner(ProductLine.LINEAR_PERPETUAL, (s, h, r, sequence) -> { });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> null;
                });
        egress.start(cluster);
        var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "isClosing" -> false;
                    case "close" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        sessionId = egress.transportSession(actual).id();
        publisher = (s, h, r, sequence) -> egress.publishResponse(processor, s, h, r, sequence, 0);
        int[] counts = {1, 2, 4, 8, 20};
        for (int i = 0; i < WINDOW; i++) {
            int count = counts[i % counts.length];
            var orders = new ArrayList<PlaceOrderCommand>(count);
            for (int j = 0; j < count; j++) orders.add(new PlaceOrderCommand(i * 32L + j + 1, "1",
                    CoreOrderSide.BUY, 100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "entry-" + i + "-" + j));
            frames[i] = new UnsafeBuffer(CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER_BATCH, new UUID(1, i + 1), ProductLine.LINEAR_PERPETUAL,
                    CommandSource.GATEWAY, 1, i + 1, 7, 1, i + 1),
                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)))));
        }
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long publishMixedReplies() {
        for (int i = 0; i < WINDOW; i++) {
            var frame = frames[i];
            var message = decoder.decodeBorrowed(frame, 0, frame.capacity());
            var response = message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                    CoreResultCode.NONE, i + 1, RESULT, 0, RESULT.length);
            TradingCoreOwner.publishResponse(publisher, sessionId, message, response, runtime.committedCoreSequence());
            message.releaseDecoderSlot();
        }
        if (egress.drainResponses(processor) != WINDOW) throw new IllegalStateException("response handoff did not drain");
        return session.checksum;
    }

    @TearDown public void close() { egress.clear(); runtime.close(); }

    private static final class SinkSession implements ClusterServiceEgress.SessionEgress {
        private long checksum;
        public void close() { }
        public long offer(long term, long timestamp, DirectBuffer buffer, int offset, int length) {
            checksum += buffer.getLong(offset + 24);
            return length;
        }
    }
}
