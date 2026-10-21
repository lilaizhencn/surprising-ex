package com.surprising.aeron.service.orchestration.egress;

import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DeferredSessionResponsesTest {
    private final ClusterServiceEgress egress = localEgress();
    @org.junit.jupiter.api.AfterEach void closeEgress() { egress.clear(); }

    private static ClusterServiceEgress localEgress() {
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        });
        var cluster = (io.aeron.cluster.service.Cluster) Proxy.newProxyInstance(
                io.aeron.cluster.service.Cluster.class.getClassLoader(),
                new Class<?>[]{io.aeron.cluster.service.Cluster.class}, (p, method, args) -> switch (method.getName()) {
                    case "role" -> io.aeron.cluster.service.Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> throw new AssertionError(method.getName());
                });
        egress.start(cluster);
        return egress;
    }

    @Test void backpressureCopiesBytesPreservesFifoAndNeverRetriesInline() {
        var queue = new DeferredSessionResponses();
        var session = new Session(1);
        byte[] bytes = {1};
        queue.offer(session.handle, new UnsafeBuffer(bytes), 1, 0);
        bytes[0] = 2;
        queue.offer(session.handle, new UnsafeBuffer(bytes), 1, 0);
        assertThat(session.offers).isOne();
        session.result = 1;
        queue.poll(1, 64);
        assertThat(session.received).containsExactly(1);
        queue.poll(2, 64);
        assertThat(session.received).containsExactly(1, 2);
        assertThat(queue.size()).isZero();
        assertThat(session.closing).isFalse();
    }

    @Test void boundedRoundRobinDoesNotStarveTailBehindSlowSessions() {
        var queue = new DeferredSessionResponses();
        var sessions = new ArrayList<Session>();
        for (int i = 0; i < 130; i++) {
            var session = new Session(i); sessions.add(session);
            queue.offer(session.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        }
        sessions.getLast().result = 1;
        assertThat(queue.poll(1, 64)).isZero();
        assertThat(queue.poll(2, 64)).isZero();
        assertThat(sessions.getLast().received).isEmpty();
        assertThat(queue.poll(3, 64)).isOne();
        assertThat(sessions.getLast().received).containsExactly(1);
        assertThat(queue.size()).isEqualTo(129);
        assertThat(sessions.stream().mapToInt(s -> s.offers).sum()).isEqualTo(130 + 3 * 64);
    }

    @Test void expiryOnlyClosesSlowSessionAndReleasesItsCapacity() {
        var queue = new DeferredSessionResponses();
        var slow = new Session(1); var later = new Session(2);
        queue.offer(slow.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        queue.offer(later.handle, new UnsafeBuffer(new byte[]{2}), 1, 500_000_000);
        later.result = 1;
        queue.poll(1_000_000_000, 64);
        egress.flushSessionClosures();
        assertThat(slow.closing).isTrue();
        assertThat(later.closing).isFalse();
        assertThat(later.received).containsExactly(2);
        assertThat(queue.size()).isZero();
    }

    @Test void removeClearAndFatalOfferReleaseOnlyLocalResponseState() {
        var queue = new DeferredSessionResponses();
        var a = new Session(1); var b = new Session(2);
        queue.offer(a.handle, new UnsafeBuffer(new byte[]{1}), 1, 0);
        queue.offer(b.handle, new UnsafeBuffer(new byte[]{2}), 1, 0);
        queue.remove(1);
        b.result = Publication.CLOSED;
        queue.poll(1, 64);
        egress.flushSessionClosures();
        assertThat(a.closing).isFalse(); assertThat(b.closing).isTrue();
        assertThat(queue.size()).isZero();
        queue.offer(a.handle, new UnsafeBuffer(new byte[]{3}), 1, 2);
        queue.clear();
        assertThat(queue.poll(3, 64)).isZero();
        assertThat(queue.size()).isZero();
    }

    private final class Session {
        long result = Publication.BACK_PRESSURED;
        int offers;
        boolean closing;
        final List<Integer> received = new ArrayList<>();
        final ClusterServiceEgress.TransportSession handle;
        Session(long id) {
            var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (p, method, args) -> switch (method.getName()) {
                        case "id" -> id;
                        case "isClosing" -> closing;
                        case "close" -> { closing = true; yield null; }
                        case "offer" -> {
                            offers++;
                            if (result >= 0) received.add((int) ((DirectBuffer) args[0]).getByte((int) args[1]));
                            yield result;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            handle = egress.transportSession(actual);
        }
    }
}
