package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OwnerSessionBoundaryTest {
    @ParameterizedTest @EnumSource(ProductLine.class) @Timeout(20)
    void ownerHandsResponsesBackToServiceThreadAcrossBackpressureAndRoleChanges(ProductLine product) {
        Thread serviceThread = Thread.currentThread();
        var role = new AtomicReference<>(Cluster.Role.LEADER);
        var closing = new AtomicBoolean();
        var result = new AtomicLong(Publication.BACK_PRESSURED);
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        var received = new ArrayList<CoreMessage>();
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> {
                    assertThat(Thread.currentThread()).as("ClientSession.%s", method.getName()).isSameAs(serviceThread);
                    return switch (method.getName()) {
                        case "id" -> 91L;
                        case "isClosing" -> closing.get();
                        case "close" -> { closing.set(true); yield null; }
                        default -> throw new AssertionError("unexpected session API " + method.getName());
                    };
                });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                new Class<?>[]{Cluster.class}, (proxy, method, args) -> {
                    assertThat(Thread.currentThread()).as("Cluster.%s", method.getName()).isSameAs(serviceThread);
                    return switch (method.getName()) {
                        case "role" -> role.get();
                        case "memberId" -> 7;
                        case "time", "logPosition" -> 11L;
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        default -> throw new AssertionError("unexpected Cluster API " + method.getName());
                    };
                });
        // Keep only the publication mocked; use the real Owner, transport facade, output ring and retry queue.
        var egress = new ClusterServiceEgress((ignored, actual) -> {
            assertThat(Thread.currentThread()).isSameAs(serviceThread);
            assertThat(actual).isSameAs(session);
            opened.incrementAndGet();
            return new ClusterServiceEgress.SessionEgress() {
                public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                    assertThat(Thread.currentThread()).isSameAs(serviceThread);
                    if (result.get() >= 0) {
                        byte[] encoded = new byte[length];
                        source.getBytes(offset, encoded);
                        received.add(CoreMessageCodec.decode(encoded));
                    }
                    return result.get();
                }
                public void close() { assertThat(Thread.currentThread()).isSameAs(serviceThread); closed.incrementAndGet(); }
            };
        });
        egress.start(cluster);
        var loop = new TradingOwnerLoop(product, egress);
        try {
            loop.start(cluster, null);
            var transport = egress.transportSession(session);
            loop.enqueue(probe(product, 1), transport.id(), 70, 700, null);
            loop.enqueue(probe(product, 2), transport.id(), 80, 800, null);
            loop.onRoleChange(Cluster.Role.LEADER, 0);
            assertThat(received).isEmpty();
            result.set(1);
            loop.drainResponses();
            loop.drainResponses();
            assertThat(received).extracting(message -> message.header().commandId())
                    .containsExactly(new UUID(72, 1), new UUID(72, 2));

            result.set(Publication.BACK_PRESSURED);
            loop.enqueue(probe(product, 3), transport.id(), 90, 900, null);
            role.set(Cluster.Role.FOLLOWER);
            loop.onRoleChange(role.get(), egress.onRoleChange(role.get()));
            loop.drainResponses();
            assertThat(received).hasSize(2);
            assertThat(closed).hasValue(1);

            result.set(1);
            role.set(Cluster.Role.LEADER);
            loop.onRoleChange(role.get(), egress.onRoleChange(role.get()));
            loop.enqueue(probe(product, 4), transport.id(), 100, 1000, null);
            loop.onRoleChange(Cluster.Role.LEADER, 2);
            loop.drainResponses();
            assertThat(received).hasSize(3);
            assertThat(received.getLast().header().commandId()).isEqualTo(new UUID(72, 4));
            assertThat(CoreProtocol.decodeResponse(received.getLast().payloadUnsafe()).appliedCommandCount()).isEqualTo(4);
            assertThat(opened).hasValue(2);
            transport.close();
            egress.flushSessionClosures();
            assertThat(closing).isTrue();
            egress.closeSession(session);
            assertThat(closed).hasValue(2);
            loop.checkFailure();
        } finally { loop.terminate(); egress.clear(); }
    }

    private static CoreMessage probe(ProductLine product, long sequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(72, sequence), product, CommandSource.OPERATIONS, 72, sequence, 7, 1, sequence),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1)));
    }
}
