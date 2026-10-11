package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class CoreMessageFlyweightDecoderTest {

    @Test
    void readersBelongToTheirDecoderAndCanBeReusedWithoutChangingOwnedMessages() {
        byte[] first = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), CoreProtocol.probePayload(7)));
        byte[] second = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, 43, 1002,
                123_456_790, 100), CoreProtocol.probePayload(9)));
        var decoderA = new CoreMessageFlyweightDecoder();
        var decoderB = new CoreMessageFlyweightDecoder();
        var viewA = decoderA.reader(new UnsafeBuffer(first), 0, first.length);
        var viewB = decoderB.reader(new UnsafeBuffer(second), 0, second.length);
        assertThat(viewA).isNotSameAs(viewB);
        assertThat(viewA.userId()).isEqualTo(1001);
        var owned = decoderA.decode(new UnsafeBuffer(first), 0, first.length);
        assertThat(decoderA.reader(new UnsafeBuffer(second), 0, second.length)).isSameAs(viewA);
        assertThat(owned).isEqualTo(CoreMessageCodec.decode(first));
    }

    @Test
    void tracedFixedHeaderExtensionMatchesArrayDecoderAndPreservesPayloadOwnership() {
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99).withTraceId("request-http-1"), CoreProtocol.probePayload(7));
        byte[] encoded = CoreMessageCodec.encode(message);
        var decoded = new CoreMessageFlyweightDecoder().decode(new UnsafeBuffer(encoded), 0, encoded.length);
        assertThat(decoded).isEqualTo(CoreMessageCodec.decode(encoded));
        assertThat(decoded.header().traceId()).isEqualTo("request-http-1");
        encoded[CoreMessageCodec.headerLength(message.header())] = 0;
        assertThat(decoded.payload()).isEqualTo(CoreProtocol.probePayload(7));
    }

    @Test
    void decodesDirectBufferIntoOwnedPayload() {
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), CoreProtocol.probePayload(7));
        byte[] encoded = CoreMessageCodec.encode(message);
        byte[] framed = new byte[encoded.length + 8];
        System.arraycopy(encoded, 0, framed, 4, encoded.length);

        CoreMessage decoded = new CoreMessageFlyweightDecoder().decode(new UnsafeBuffer(framed), 4, encoded.length);
        framed[4 + CoreProtocol.HEADER_LENGTH] = 0;

        assertThat(decoded).isEqualTo(message);
    }
}
