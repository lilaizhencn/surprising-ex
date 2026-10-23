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

    private static final String CURRENT_GOLDEN = "584558530900020a000148007766554433221100ffeeddccbbaa998808000000000000002a00000000000000e90300000000000015cd5b070000000063000000000000000e0000000400555344540700000000000000";

    @Test
    void matchesCurrentSchemaGoldenMessage() {
        CoreMessage message = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);

        byte[] encoded = CoreMessageCodec.encode(message);

        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(CURRENT_GOLDEN);
        assertThat(CoreMessageCodec.decode(encoded)).isEqualTo(message);
    }

    @Test
    void preservesTheCanonicalCommandFingerprintVector() {
        CoreMessage message = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);

        assertThat(CommandFingerprint.of(message).toString())
                .isEqualTo("e20efc0cbf44de9558f87d9780097f707f2eb458dd95896ae758b2f2c86b66b6");
    }

    @Test
    void reusedFingerprintCalculatorsPreserveCanonicalBytesAndEarlierResults() {
        CoreMessage first = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);
        CoreMessage second = command(UUID.randomUUID(), 43, 100, 9);
        var producerA = new CommandFingerprint.Calculator();
        var producerB = new CommandFingerprint.Calculator();
        var retained = producerA.of(first);
        assertThat(producerB.of(second)).isEqualTo(CommandFingerprint.of(second));
        assertThat(producerA.of(second)).isEqualTo(CommandFingerprint.of(second));
        assertThat(producerA.of(first)).isEqualTo(retained);
        assertThat(retained.toString()).isEqualTo("e20efc0cbf44de9558f87d9780097f707f2eb458dd95896ae758b2f2c86b66b6");
    }

    @Test
    void encodesIntoReusableDestinationWithoutTouchingItsTail() {
        CoreMessage message = command(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 42, 99, 7);
        byte[] destination = new byte[256];
        Arrays.fill(destination, (byte) 0x5a);

        int length = CoreMessageCodec.encode(message, destination);

        assertThat(length).isEqualTo(CoreMessageCodec.encodedLength(message));
        assertThat(Arrays.copyOf(destination, length)).isEqualTo(CoreMessageCodec.encode(message));
        assertThat(destination[length]).isEqualTo((byte) 0x5a);
    }

    @Test
    void encodesCommittedResponseDirectlyIntoReusableDestination() {
        CoreMessageHeader header = command(UUID.randomUUID(), 42, 99, 7).header()
                .response(CoreMessageType.COMMAND_RESULT);
        CoreResponse response = new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 11, 12,
                new byte[]{3, 5, 8, 13});
        byte[] destination = new byte[CoreMessageCodec.encodedResponseLength(response) + 1];
        destination[destination.length - 1] = 0x5a;

        int length = CoreMessageCodec.encodeResponse(header, response, 77, destination);

        CoreMessage decoded = CoreMessageCodec.decode(Arrays.copyOf(destination, length));
        CoreResponse decodedResponse = CoreProtocol.decodeResponse(decoded.payloadUnsafe());
        assertThat(decoded.header()).isEqualTo(header);
        assertThat(decodedResponse.committedCoreSequence()).isEqualTo(77);
        assertThat(decodedResponse.appliedCommandCount()).isEqualTo(12);
        assertThat(decodedResponse.data()).containsExactly(3, 5, 8, 13);
        assertThat(destination[length]).isEqualTo((byte) 0x5a);
    }

    @Test
    void derivesMessageKindFromType() {
        CoreMessage command = command(UUID.randomUUID(), 42, 99, 7);
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.STATE_HASH_QUERY,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY,
                8, 43, 1001, 123_456_790, 100), new byte[0]);
        assertThat(CoreMessageCodec.decode(CoreMessageCodec.encode(command)).header().kind())
                .isEqualTo(WireMessageKind.COMMAND);
        assertThat(CoreMessageCodec.decode(CoreMessageCodec.encode(query)).header().kind())
                .isEqualTo(WireMessageKind.QUERY);
        assertThat(command.header().response(CoreMessageType.COMMAND_RESULT).kind())
                .isEqualTo(WireMessageKind.RESPONSE);
        assertThat(CoreProtocol.HEADER_LENGTH).isEqualTo(72);
    }

    @Test
    void rejectsNonCurrentHeaderLength() {
        byte[] encoded = CoreMessageCodec.encode(command(UUID.randomUUID(), 1, 2, 3));
        byte[] extended = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, extended, 0, CoreProtocol.HEADER_LENGTH);
        extended[10] = (byte) (CoreProtocol.HEADER_LENGTH + 4);
        extended[11] = 0;
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
                .putInt(68, CoreMessageCodec.MAX_PAYLOAD_LENGTH + 1);
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
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId,
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, sourceSequence, 1001,
                123_456_789, correlationId), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", delta)));
    }
}
