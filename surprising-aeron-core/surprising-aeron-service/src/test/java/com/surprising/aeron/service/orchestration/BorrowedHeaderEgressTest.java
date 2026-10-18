package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClientSession;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.Proxy;

class BorrowedHeaderEgressTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closedOrRemovedSessionDropsTheResponseAndReturnsItsLeases(boolean removed) {
        var egress = new ClusterServiceEgress((ignored, actual) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                throw new AssertionError("closed session must not send a response");
            }
            public void close() { }
        });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> throw new AssertionError(method.getName());
                });
        var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 0L;
                    case "isClosing" -> false;
                    default -> throw new AssertionError(method.getName());
                });
        var decoder = new CoreMessageFlyweightDecoder();
        var bytes = new UnsafeBuffer(frame(1));
        var processor = new TradingCoreOwner(ProductLine.SPOT, (id, header, response, sequence) -> { });
        egress.start(cluster);
        try {
            var transport = egress.transportSession(actual);
            var message = decoder.decodeBorrowed(bytes, 0, bytes.capacity());
            var response = message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD, 0, 0);
            egress.publishResponse(processor, transport.id(), message.header(), response, 1, 0);
            message.releaseDecoderSlot();
            if (removed) egress.closeSession(actual);
            else transport.close();
            assertThat(egress.drainResponses(processor)).isOne();
            boolean reused = false;
            for (int i = 0; i < 1024; i++) {
                var next = decoder.decodeBorrowed(bytes, 0, bytes.capacity());
                reused |= next == message;
                next.releaseDecoderSlot();
            }
            assertThat(reused).as("discard returns both response and Header leases").isTrue();
        } finally { egress.clear(); }
    }

    @Test void actualPublisherRetainsThePooledResponseAcrossDecoderAndOutputQueueWraps() {
        var decoder = new CoreMessageFlyweightDecoder();
        var bytes = frame(1);
        var message = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        var processor = new TradingCoreOwner(ProductLine.SPOT, (session, header, response, sequence) -> { });
        var sent = new AtomicReference<CoreMessage>();
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id")) return 1L;
                    if (method.getName().equals("offer")) {
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        sent.set(CoreMessageCodec.decode(result)); return 1L;
                    }
                    return zero(method.getReturnType());
                });
        TradingCoreOwner.ResponseSink publisherSink = (transport, header, response, sequence) ->
                egress.publishResponse(processor, transport, header, response, sequence, 0);
        try (var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            var response = message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, new byte[]{1, 2, 3}, 0, 3);
            TradingCoreOwner.publishResponse(publisherSink, egress.transportSession(session).id(),
                    message, response, state.committedCoreSequence());
            message.releaseDecoderSlot();
            for (int i = 0; i < 1024; i++) {
                var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
                assertThat(next).isNotSameAs(message);
                next.releaseDecoderSlot();
            }
            assertThat(response.data()).containsExactly(1, 2, 3);
            egress.drainResponses(processor);
            assertThat(sent.get().header().kind()).isEqualTo(WireMessageKind.RESPONSE);
            assertThat(CoreProtocol.decodeResponse(sent.get().payloadUnsafe()).data()).containsExactly(1, 2, 3);
            byte[] stable = sent.get().payload();
            for (int i = 0; i < 8300; i++) {
                var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
                var reply = next.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                        i + 2, new byte[0], 0, 0);
                TradingCoreOwner.publishResponse(publisherSink, egress.transportSession(session).id(), next, reply, state.committedCoreSequence());
                next.releaseDecoderSlot();
                egress.drainResponses(processor);
                assertThat(CoreProtocol.decodeResponse(sent.get().payloadUnsafe()).appliedCommandCount()).isEqualTo(i + 2);
            }
            assertThat(CoreProtocol.decodeResponse(stable).data()).containsExactly(1, 2, 3);
        } finally { egress.clear(); }
    }

    private static byte[] frame(long id) {
        return CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, 7, 1, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1))));
    }
    @Test void outputQueueRetainsHeaderUntilEncodingEvenAfterOwnerRetiresAndDecoderWraps() {
        var decoder = new CoreMessageFlyweightDecoder();
        var bytes = frame(1);
        var message = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        var detached = new CoreMessage(message.header(), message.payloadUnsafe());
        var header = message.header();
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        var processor = new TradingCoreOwner(ProductLine.SPOT, (session, requestHeader, response, sequence) -> { });
        var sent = new AtomicReference<CoreMessage>();
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id")) return 1L;
                    if (method.getName().equals("offer")) {
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        sent.set(CoreMessageCodec.decode(result)); return 1L;
                    }
                    return zero(method.getReturnType());
                });
        egress.publishResponse(processor, egress.transportSession(session).id(), header,
                new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD), 1, 0);
        message.releaseDecoderSlot();
        for (int index = 0; index < 1024; index++) {
            var nextBytes = frame(index + 2);
            var next = decoder.decodeBorrowed(new UnsafeBuffer(nextBytes), 0, nextBytes.length);
            assertThat(next).isNotSameAs(message);
            next.releaseDecoderSlot();
        }
        assertThat(header.commandId()).isEqualTo(new UUID(1, 1));
        assertThat(detached.header().commandId()).isEqualTo(new UUID(1, 1));
        egress.drainResponses(processor);
        assertThat(sent.get().header().commandId()).isEqualTo(new UUID(1, 1));
        CoreMessage reused = null;
        for (int index = 0; index < 512; index++) {
            var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
            if (next == message) reused = next;
            next.releaseDecoderSlot();
        }
        assertThat(reused).isSameAs(message);
        assertThat(detached.header().commandId()).isEqualTo(new UUID(1, 1));
        egress.clear();
    }
    @Test void overflowIsolatesToOffendingSessionWithoutDisconnectingHealthySessions() {
        var decoder = new CoreMessageFlyweightDecoder();
        var bytes = frame(1);
        var egress = new ClusterServiceEgress((ignored, target) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            public void close() { }
        });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        var processor = new TradingCoreOwner(ProductLine.SPOT, (session, requestHeader, response, sequence) -> { });
        var healthyReceived = new AtomicReference<CoreMessage>();
        var offendingSession = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 100L;
                    default -> zero(method.getReturnType());
                });
        var healthySession = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> 200L;
                    case "offer" -> {
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        healthyReceived.set(CoreMessageCodec.decode(result));
                        yield 1L;
                    }
                    default -> zero(method.getReturnType());
                });

        var offendingTransport = egress.transportSession(offendingSession);
        var healthyTransport = egress.transportSession(healthySession);

        var normalMsg = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        var normalResponse = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD);

        // Healthy session publishes normal response
        egress.publishResponse(processor, healthyTransport.id(), normalMsg.header(), normalResponse, 1, 0);

        // Offending session attempts to overflow with oversized response exceeding 16MB OUTPUT_BYTES
        byte[] hugePayload = new byte[17 * 1024 * 1024];
        var overflowResponse = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 2, hugePayload);
        egress.publishResponse(processor, offendingTransport.id(), normalMsg.header(), overflowResponse, 2, 0);

        normalMsg.releaseDecoderSlot();

        // Drain responses: only offending session should be marked for close; healthy session should receive response
        egress.drainResponses(processor);

        assertThat(offendingTransport.isClosing()).as("Offending session must be marked for closure").isTrue();
        assertThat(healthyTransport.isClosing()).as("Healthy session must stay alive").isFalse();
        assertThat(healthyReceived.get()).isNotNull();
        assertThat(healthyReceived.get().payload()).isNotNull();

        egress.clear();
    }

    @Test void concurrentOverflowPublicationDoesNotLoseDistinctSessionsOrCloseHealthyOnes() throws Exception {
        var egress = new ClusterServiceEgress((ignored, actual) -> new ClusterServiceEgress.SessionEgress() {
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) { return 1; }
            public void close() { }
        });
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> method.getName().equals("role") ? Cluster.Role.LEADER : zero(method.getReturnType()));
        egress.start(cluster);
        int count = 2_000;
        var sessions = new ClusterServiceEgress.TransportSession[count + 1];
        for (int index = 0; index <= count; index++) {
            long id = index + 1_000L;
            var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (proxy, method, args) ->
                            method.getName().equals("id") ? id : zero(method.getReturnType()));
            sessions[index] = egress.transportSession(actual);
        }
        var owner = new TradingCoreOwner(ProductLine.SPOT, (id, header, response, sequence) -> { });
        var request = CoreMessageCodec.decode(frame(1));
        var response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 1, new byte[17 * 1024 * 1024]);
        var failure = new AtomicReference<Throwable>();
        var started = new java.util.concurrent.CountDownLatch(1);
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                started.countDown();
                for (int round = 0; round < 8; round++) for (int index = 0; index < count; index++)
                    egress.publishResponse(owner, sessions[index].id(), request.header(), response, 1, 0);
            } catch (Throwable caught) { failure.set(caught); }
        });
        try {
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (producer.isAlive() && System.nanoTime() - deadline < 0) egress.drainResponses(owner);
            producer.join(1_000);
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            egress.drainResponses(owner);
            for (int index = 0; index < count; index++) assertThat(sessions[index].isClosing()).isTrue();
            assertThat(sessions[count].isClosing()).isFalse();
        } finally {
            producer.interrupt(); producer.join(1_000); egress.clear();
        }
    }

    private static Object zero(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }
}
