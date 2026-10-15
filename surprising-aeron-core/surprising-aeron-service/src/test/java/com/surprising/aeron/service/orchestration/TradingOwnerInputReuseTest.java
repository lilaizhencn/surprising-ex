package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TradingOwnerInputReuseTest {
    @Test void fullQueueCannotReuseAWrapperUntilOwnerFinishesItsHandoff() throws Exception {
        var waiting = new CountDownLatch(1);
        var egress = new ClusterServiceEgress();
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        Cluster cluster = cluster(waiting);
        egress.start(cluster);
        set(loop, "cluster", cluster);
        var input = queue(loop, "input");
        var available = queue(loop, "availableInput");
        CoreMessage first = probe(1);
        for (int i = 0; i < 8192; i++) loop.enqueue(first, ClusterServiceEgress.NO_SESSION_ID, i, i + 10, null);
        Object owned = input.poll();
        assertThat(available).isEmpty();
        CoreMessage successor = probe(2);
        var failure = new AtomicReference<Throwable>();
        Thread producer = new Thread(() -> {
            try { loop.enqueue(successor, ClusterServiceEgress.NO_SESSION_ID, 99, 199, null); }
            catch (Throwable fatal) { failure.set(fatal); }
        });
        producer.start();
        try {
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(field(owned, "command")).isSameAs(first);
            assertThat(field(owned, "position")).isEqualTo(10L);
            assertThat(input.size()).isEqualTo(8191);
            returnInput(loop, owned);
            producer.join(5_000);
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            for (int i = 1; i < 8192; i++) returnInput(loop, input.poll());
            Object reused = input.poll();
            assertThat(reused).isSameAs(owned);
            assertThat(field(reused, "command")).isSameAs(successor);
            assertThat(field(reused, "timestamp")).isEqualTo(99L);
            assertThat(field(reused, "position")).isEqualTo(199L);
            returnInput(loop, reused);
            assertThat(available.size()).isEqualTo(8192);
            assertThat(field(owned, "command")).isNull();
            assertThat(field(owned, "action")).isNull();
        } finally {
            producer.interrupt(); producer.join(5_000);
            Object pending;
            while ((pending = input.poll()) != null) returnInput(loop, pending);
            egress.clear();
        }
    }

    @Test void interruptedByteBudgetWaitKeepsItsUnpublishedWrapperForTheNextAttempt() throws Exception {
        var egress = new ClusterServiceEgress();
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        Cluster cluster = cluster(new CountDownLatch(1));
        egress.start(cluster); set(loop, "cluster", cluster);
        set(loop, "inputProduced", 64L * 1024 * 1024);
        var failure = new AtomicReference<Throwable>();
        Thread producer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { loop.enqueue(probe(1), ClusterServiceEgress.NO_SESSION_ID, 1, 10, null); }
            catch (Throwable fatal) { failure.set(fatal); }
        });
        producer.start(); producer.join(5_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(org.agrona.concurrent.AgentTerminationException.class);
        Object retained = field(loop, "unpublishedInput");
        assertThat(retained).isNotNull();
        assertThat(field(retained, "command")).isNull();
        assertThat(queue(loop, "availableInput").size()).isEqualTo(8191);
        set(loop, "inputProduced", 0L);
        loop.enqueue(probe(2), ClusterServiceEgress.NO_SESSION_ID, 2, 20, null);
        Object next = queue(loop, "input").poll();
        assertThat(next).isSameAs(retained);
        assertThat(field(loop, "unpublishedInput")).isNull();
        returnInput(loop, next);
        assertThat(queue(loop, "availableInput").size()).isEqualTo(8192);
        egress.clear();
    }

    @Test void actualOwnerPreservesResponsesAcrossTwoCompleteInputPoolWraps() throws Exception {
        var cluster = cluster(new CountDownLatch(1));
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        }); egress.start(cluster);
        var loop = new TradingOwnerLoop(ProductLine.SPOT, egress);
        long[] received = {0};
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 1L;
                    case "isClosing" -> false;
                    case "offer" -> {
                        byte[] bytes = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                        CoreMessage result = CoreMessageCodec.decode(bytes);
                        long expected = ++received[0];
                        assertThat(result.header().commandId()).isEqualTo(new UUID(15, expected));
                        assertThat(CoreProtocol.decodeResponse(result.payloadUnsafe()).commandStatus())
                                .isEqualTo(ResponseStatus.APPLIED);
                        yield 1L;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try {
            loop.start(cluster, null);
            for (long id = 1; id <= 16_384; id++) {
                loop.enqueue(probe(id), egress.transportSession(session).id(), id, id + 10, null);
                if ((id & 255) == 0) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (received[0] < id) {
                        loop.drainResponses(); loop.checkFailure();
                        assertThat(System.nanoTime()).isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                }
            }
            assertThat(received[0]).isEqualTo(16_384);
        } finally { loop.terminate(); egress.clear(); }
        assertThat(queue(loop, "availableInput").size()).isEqualTo(8192);
    }

    private static CoreMessage probe(long id) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, new UUID(15, id),
                ProductLine.SPOT, CommandSource.GATEWAY, 15, id, 7, 1, id), CoreProtocol.probePayload(1));
    }

    private static Cluster cluster(CountDownLatch waiting) {
        var idle = new IdleStrategy() {
            @Override public void idle(int workCount) { if (workCount == 0) idle(); }
            @Override public void idle() { waiting.countDown(); Thread.onSpinWait(); }
            @Override public void reset() { }
        };
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "idleStrategy" -> idle;
                    case "time" -> 1L;
                    case "logPosition" -> 0L;
                    case "memberId" -> 0;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static OneToOneConcurrentArrayQueue<Object> queue(Object owner, String name) throws Exception {
        return (OneToOneConcurrentArrayQueue<Object>) field(owner, name);
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void returnInput(TradingOwnerLoop loop, Object input) throws Exception {
        var method = TradingOwnerLoop.class.getDeclaredMethod("returnInput", input.getClass());
        method.setAccessible(true); method.invoke(loop, input);
    }
}
