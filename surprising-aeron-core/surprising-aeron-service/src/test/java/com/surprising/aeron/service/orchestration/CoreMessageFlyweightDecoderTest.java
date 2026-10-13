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

    @Test void borrowedFramesWaitForRetirementAndKeepResponseHeadersStable() {
        var expected = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                new UUID(1, 2), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001, 1, 99), CoreProtocol.probePayload(7));
        var encoded = CoreMessageCodec.encode(expected);
        var buffer = new UnsafeBuffer(encoded);
        var decoder = new CoreMessageFlyweightDecoder();
        var messages = new CoreMessage[512];
        for (int index = 0; index < messages.length; index++) messages[index] = decoder.decodeBorrowed(buffer, 0, encoded.length);
        var headerForEgress = messages[0].header().immutableValue();
        var payload = messages[0].payloadUnsafe();
        var overflow = decoder.decodeBorrowed(buffer, 0, encoded.length);
        assertThat(overflow).isNotSameAs(messages[0]).isEqualTo(expected);
        for (var message : messages) message.releaseDecoderSlot();
        CoreMessage reused = null;
        for (int index = 0; index < 512; index++) {
            var value = decoder.decodeBorrowed(buffer, 0, encoded.length);
            if (value == messages[0]) reused = value;
            assertThat(value).isEqualTo(expected);
        }
        assertThat(reused).isSameAs(messages[0]);
        assertThat(reused.payloadUnsafe()).isSameAs(payload);
        assertThat(headerForEgress).isEqualTo(expected.header());
        assertThat(headerForEgress).isNotSameAs(reused.header());
        encoded[CoreProtocol.HEADER_LENGTH] = 0;
        assertThat(reused.payloadUnsafe()).isEqualTo(expected.payloadUnsafe());
        for (var message : messages) message.releaseDecoderSlot();
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
