package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreMessageCodecTest {

    private static final String CURRENT_GOLDEN = "5845585302000102010001004c000100"
            + "7766554433221100ffeeddccbbaa9988"
            + "0800000000000000"
            + "2a00000000000000e903000000000000"
            + "15cd5b07000000006300000000000000"
            + "080000000700000000000000";

    @Test
    void matchesCurrentSchemaGoldenMessage() {
        CoreMessage message = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);

        byte[] encoded = CoreMessageCodec.encode(message);

        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(CURRENT_GOLDEN);
        assertThat(CoreMessageCodec.decode(encoded)).isEqualTo(message);
    }

    @Test
    void roundTripsExplicitDefaultRoute() {
        CoreMessage command = command(UUID.randomUUID(), 42, 99, 7);
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.STATE_HASH_QUERY,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY,
                8, 43, 1001, 123_456_790, 100), new byte[0]);

        CoreMessage restoredCommand = CoreMessageCodec.decode(CoreMessageCodec.encode(command));
        CoreMessage restoredQuery = CoreMessageCodec.decode(CoreMessageCodec.encode(query));

        assertThat(restoredCommand.header().route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(restoredQuery.header().route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(restoredCommand.header().response(CoreMessageType.COMMAND_RESULT).route())
                .isEqualTo(CoreRoute.DEFAULT);
        assertThat(restoredCommand.header().exportEvent(44).route()).isEqualTo(CoreRoute.DEFAULT);
        assertThat(CoreProtocol.HEADER_LENGTH).isEqualTo(76);
    }

    @Test
    void rejectsNonCurrentHeaderLength() {
        byte[] encoded = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));
        byte[] extended = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, extended, 0, CoreProtocol.HEADER_LENGTH);
        extended[12] = (byte) (CoreProtocol.HEADER_LENGTH + 4);
        extended[13] = 0;
        extended[CoreProtocol.HEADER_LENGTH] = 9;
        extended[CoreProtocol.HEADER_LENGTH + 1] = 8;
        extended[CoreProtocol.HEADER_LENGTH + 2] = 7;
        extended[CoreProtocol.HEADER_LENGTH + 3] = 6;
        System.arraycopy(encoded, CoreProtocol.HEADER_LENGTH, extended, CoreProtocol.HEADER_LENGTH + 4,
                encoded.length - CoreProtocol.HEADER_LENGTH);

        assertThatThrownBy(() -> CoreMessageCodec.decode(extended))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("header length");
    }

    @Test
    void rejectsNonCurrentSchema() {
        byte[] encoded = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));
        encoded[4] = 1;

        assertThatThrownBy(() -> CoreMessageCodec.decode(encoded))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported schema");
    }

    @Test
    void rejectsImplicitOrUnknownRoute() {
        byte[] valid = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));
        byte[] implicitRoute = valid.clone();
        implicitRoute[14] = 0;
        assertThatThrownBy(() -> CoreMessageCodec.decode(implicitRoute))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("route version");

        byte[] unknownShard = valid.clone();
        unknownShard[11] = 1;
        assertThatThrownBy(() -> CoreMessageCodec.decode(unknownShard))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("shard");

        byte[] unknownRoute = valid.clone();
        unknownRoute[14] = 2;
        assertThatThrownBy(() -> CoreMessageCodec.decode(unknownRoute))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("route version");

        byte[] unsignedRoute = valid.clone();
        unsignedRoute[14] = 0;
        unsignedRoute[15] = 1;
        assertThatThrownBy(() -> CoreMessageCodec.decode(unsignedRoute))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("256");
    }

    @Test
    void rejectsMalformedTruncatedTrailingAndOversizedFrames() {
        byte[] valid = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));

        assertThatThrownBy(() -> CoreMessageCodec.decode(new byte[CoreProtocol.HEADER_LENGTH - 1]))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("shorter than fixed header");
        assertThatThrownBy(() -> CoreMessageCodec.decode(Arrays.copyOf(valid, valid.length - 1)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("payload length");
        assertThatThrownBy(() -> CoreMessageCodec.decode(Arrays.copyOf(valid, valid.length + 1)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("payload length");

        byte[] oversized = valid.clone();
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(72, CoreMessageCodec.MAX_PAYLOAD_LENGTH + 1);
        assertThatThrownBy(() -> CoreMessageCodec.decode(oversized))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("payload length");

        CoreMessage oversizedMessage = new CoreMessage(command(UUID.randomUUID(), 1, 2, 3).header(),
                new byte[CoreMessageCodec.MAX_PAYLOAD_LENGTH + 1]);
        assertThatThrownBy(() -> CoreMessageCodec.encode(oversizedMessage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload is too large");
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long correlationId, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, sourceSequence, 1001,
                123_456_789, correlationId), CoreProtocol.probePayload(delta));
    }
}
