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
    void tracedFixedHeaderExtensionMatchesArrayDecoderAndPreservesPayloadOwnership() {
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99).withTraceId("request-http-1"), CoreProtocol.probePayload(7));
        byte[] encoded = CoreMessageCodec.encode(message);
        var decoded = CoreMessageFlyweightDecoder.decode(new UnsafeBuffer(encoded), 0, encoded.length);
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

        CoreMessage decoded = CoreMessageFlyweightDecoder.decode(new UnsafeBuffer(framed), 4, encoded.length);
        framed[4 + CoreProtocol.HEADER_LENGTH] = 0;

        assertThat(decoded).isEqualTo(message);
    }
}
