package com.surprising.aeron.support.egress;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.TradingCoreOwner;
import com.surprising.aeron.support.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class BorrowedHeaderEgressTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closedOrRemovedSessionDropsTheResponseAndReturnsItsLease(boolean removed) {
        try (var fixture = new Fixture()) {
            var target = fixture.session(1);
            if (removed) fixture.egress.closeSession(target.actual);
            else target.transport.close();
            var decoder = new CoreMessageFlyweightDecoder();
            var message = decode(decoder, 1);
            fixture.publish(target, message, response(message, 1, new byte[0]));
            message.releaseDecoderSlot();
            assertThat(fixture.egress.drainResponses()).isZero();
            assertThat(target.received).isEmpty();
            assertThat(reuse(decoder, message)).isTrue();
        }
    }

    @Test
    void actualPublisherEncodesBeforeDecoderReuseAndKeepsSentValuesStable() {
        try (var fixture = new Fixture()) {
            var target = fixture.session(1);
            var decoder = new CoreMessageFlyweightDecoder();
            var message = decode(decoder, 1);
            fixture.publish(target, message, response(message, 1, new byte[]{1, 2, 3}));
            assertThat(target.received).hasSize(1);
            byte[] stable = target.received.getFirst();
            assertThat(CoreMessageCodec.decode(stable).header().kind()).isEqualTo(WireMessageKind.RESPONSE);
            message.releaseDecoderSlot();
            assertThat(reuse(decoder, message)).isTrue();
            for (int index = 0; index < 8_300; index++) {
                var next = decode(decoder, index + 2);
                fixture.publish(target, next, response(next, index + 2, new byte[0]));
                next.releaseDecoderSlot();
                assertThat(decodedResponse(target.received.getLast()).appliedCommandCount()).isEqualTo(index + 2);
            }
            assertThat(decodedResponse(stable).data()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void backpressureKeepsEncodedBytesAfterTheHeaderAndResponseAreReused() {
        try (var fixture = new Fixture()) {
            var target = fixture.session(1);
            target.backpressured = true;
            var decoder = new CoreMessageFlyweightDecoder();
            var message = decode(decoder, 1);
            var detached = new CoreMessage(message.header(), message.payloadUnsafe());
            fixture.publish(target, message, response(message, 1, new byte[]{1, 2, 3}));
            message.releaseDecoderSlot();
            assertThat(reuse(decoder, message)).isTrue();
            assertThat(target.received).isEmpty();
            target.backpressured = false;
            assertThat(fixture.egress.drainResponses()).isOne();
            assertThat(target.received).hasSize(1);
            assertThat(CoreMessageCodec.decode(target.received.getFirst()).header().commandId()).isEqualTo(new UUID(1, 1));
            assertThat(decodedResponse(target.received.getFirst()).data()).containsExactly(1, 2, 3);
            assertThat(detached.header().commandId()).isEqualTo(new UUID(1, 1));
        }
    }

    @Test
    void overflowIsolatesToOffendingSessionWithoutDisconnectingHealthySessions() {
        try (var fixture = new Fixture()) {
            var healthy = fixture.session(1);
            var offending = fixture.session(2);
            var message = CoreMessageCodec.decode(frame(1));
            fixture.publish(healthy, message, new CoreResponse(ResponseStatus.APPLIED, 1));
            fixture.publish(offending, message, oversizedResponse());
            assertThat(offending.transport.isClosing()).isTrue();
            assertThat(healthy.transport.isClosing()).isFalse();
            assertThat(healthy.received).hasSize(1);
            assertThat(offending.received).isEmpty();
        }
    }

    @Test
    void repeatedOverflowPublicationClosesEveryOffendingSessionAndKeepsHealthySessions() {
        try (var fixture = new Fixture()) {
            int count = 2_000;
            var targets = new Session[count];
            for (int index = 0; index < count; index++) targets[index] = fixture.session(index + 1);
            var healthy = fixture.session(count + 1);
            var request = CoreMessageCodec.decode(frame(1));
            var response = oversizedResponse();
            for (int round = 0; round < 8; round++)
                for (var target : targets) fixture.publish(target, request, response);
            fixture.egress.flushSessionClosures();
            for (var target : targets) assertThat(target.closed).isTrue();
            assertThat(healthy.transport.isClosing()).isFalse();
            assertThat(healthy.closed).isFalse();
        }
    }

    @Test
    void roleChangeDiscardsDeferredBytesAndRejectsTheOldOwnerEpoch() {
        try (var fixture = new Fixture()) {
            var target = fixture.session(1);
            var request = CoreMessageCodec.decode(frame(1));
            target.backpressured = true;
            fixture.publish(target, request, new CoreResponse(ResponseStatus.APPLIED, 1));
            fixture.role = Cluster.Role.FOLLOWER;
            fixture.egress.onRoleChange(fixture.role);
            fixture.publish(target, request, new CoreResponse(ResponseStatus.APPLIED, 2));
            fixture.role = Cluster.Role.LEADER;
            long epoch = fixture.egress.onRoleChange(fixture.role);
            target.backpressured = false;
            fixture.publish(target, request, new CoreResponse(ResponseStatus.APPLIED, 3));
            assertThat(fixture.egress.drainResponses()).isZero();
            assertThat(target.received).isEmpty();
            fixture.epoch = epoch;
            fixture.publish(target, request, new CoreResponse(ResponseStatus.APPLIED, 4));
            assertThat(target.received).hasSize(1);
            assertThat(decodedResponse(target.received.getFirst()).appliedCommandCount()).isEqualTo(4);
        }
    }

    private static CoreMessage decode(CoreMessageFlyweightDecoder decoder, long id) {
        byte[] bytes = frame(id);
        return decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
    }

    private static boolean reuse(CoreMessageFlyweightDecoder decoder, CoreMessage released) {
        boolean reused = false;
        for (int index = 0; index < 1_024; index++) {
            var next = decode(decoder, index + 2);
            reused |= next == released;
            next.releaseDecoderSlot();
        }
        return reused;
    }

    private static CoreResponse response(CoreMessage message, long count, byte[] data) {
        return message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                count, data, 0, data.length);
    }

    private static CoreResponse oversizedResponse() {
        return CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 1, new byte[17 * 1024 * 1024]);
    }

    private static CoreResponse decodedResponse(byte[] frame) {
        return CoreProtocol.decodeResponse(CoreMessageCodec.decode(frame).payloadUnsafe());
    }

    private static byte[] frame(long id) {
        return CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, 7, 1, id),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
    }

    private static final class Fixture implements AutoCloseable {
        final Map<Long, Session> sessions = new HashMap<>();
        final TradingCoreOwner owner = new TradingCoreOwner(ProductLine.SPOT, (id, header, response, sequence) -> { });
        Cluster.Role role = Cluster.Role.LEADER;
        long epoch;
        final ClusterServiceEgress egress = new ClusterServiceEgress((cluster, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                var session = sessions.get(target.id());
                if (session.backpressured) return Publication.BACK_PRESSURED;
                byte[] bytes = new byte[length];
                source.getBytes(offset, bytes);
                session.received.add(bytes);
                return 1;
            }
            public void close() { }
        });

        Fixture() {
            var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "role" -> role;
                        case "time" -> 1L;
                        default -> throw new AssertionError(method.getName());
                    });
            egress.start(cluster);
        }

        Session session(long id) {
            var session = new Session(id);
            sessions.put(id, session);
            session.transport = egress.transportSession(session.actual);
            return session;
        }

        void publish(Session target, CoreMessage message, CoreResponse response) {
            TradingCoreOwner.publishResponse((id, header, result, sequence) ->
                    egress.publishResponse(owner, id, header, result, sequence, epoch),
                    target.transport.id(), message, response, response.committedCoreSequence());
        }

        public void close() {
            try { egress.clear(); }
            finally { owner.terminate(); }
        }
    }

    private static final class Session {
        final ArrayList<byte[]> received = new ArrayList<>();
        final ClientSession actual;
        ClusterServiceEgress.TransportSession transport;
        boolean backpressured, closed;

        Session(long id) {
            actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "id" -> id;
                        case "isClosing" -> closed;
                        case "close" -> { closed = true; yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
        }
    }
}
