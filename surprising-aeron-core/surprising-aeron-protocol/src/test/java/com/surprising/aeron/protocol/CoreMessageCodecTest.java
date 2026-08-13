package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreMessageCodecTest {

    private static final String V1_GOLDEN = "5845585301000102010001004c000000"
            + "7766554433221100ffeeddccbbaa9988"
            + "0800000000000000"
            + "2a00000000000000e903000000000000"
            + "15cd5b07000000006300000000000000"
            + "080000000700000000000000";

    @Test
    void matchesSchemaVersionOneGoldenMessage() {
        CoreMessage message = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);

        byte[] encoded = CoreMessageCodec.encode(message);

        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(V1_GOLDEN);
        assertThat(CoreMessageCodec.decode(encoded)).isEqualTo(message);
    }

    @Test
    void ignoresKnownHeaderExtensionForBackwardCompatibility() {
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

        CoreMessage decoded = CoreMessageCodec.decode(extended);

        assertThat(decoded.payload()).isEqualTo(CoreProtocol.probePayload(3));
    }

    @Test
    void rejectsUnknownFutureSchema() {
        byte[] encoded = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));
        encoded[4] = 2;

        assertThatThrownBy(() -> CoreMessageCodec.decode(encoded))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("future schema");
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long correlationId, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, sourceSequence, 1001,
                123_456_789, correlationId), CoreProtocol.probePayload(delta));
    }
}
