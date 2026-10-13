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
    private static byte[] frame(long id) {
        return CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, 7, 1, id), CoreProtocol.probePayload(1)));
    }
    @Test void outputQueueRetainsHeaderUntilEncodingEvenAfterOwnerRetiresAndDecoderWraps() {
        var decoder = new CoreMessageFlyweightDecoder();
        var bytes = frame(1);
        var message = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        var detached = new CoreMessage(message.header(), message.payloadUnsafe());
        var header = message.header();
        var egress = new ClusterServiceEgress();
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
        egress.publishResponse(processor, session, header,
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
    private static Object zero(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }
}
