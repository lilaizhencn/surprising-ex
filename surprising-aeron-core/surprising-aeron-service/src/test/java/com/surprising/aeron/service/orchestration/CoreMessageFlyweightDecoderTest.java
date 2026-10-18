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

    @Test void varyingOrderLengthsReuseCapacityAndExcludeStaleTailFromWireAndFingerprint() {
        var decoder = new CoreMessageFlyweightDecoder();
        CoreMessage previous = null;
        byte[] backing = null;
        for (int round = 0; round < 2; round++) {
            for (int length = 1025; length <= 2048; length++) {
                byte[] payload = new byte[length];
                java.util.Arrays.fill(payload, (byte) (length & 255));
                var expected = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER,
                        new UUID(21, length), ProductLine.SPOT, CommandSource.GATEWAY,
                        21, length, 7, 1, length), payload);
                byte[] frame = CoreMessageCodec.encode(expected);
                var borrowed = decoder.decodeBorrowed(new UnsafeBuffer(frame), 0, frame.length);
                try {
                    if (previous != null) {
                        assertThat(borrowed).isSameAs(previous);
                        assertThat(borrowed.payloadUnsafe()).isSameAs(backing);
                    }
                    assertThat(borrowed.payloadLength()).isEqualTo(length);
                    assertThat(borrowed).isEqualTo(expected);
                    assertThat(borrowed.hashCode()).isEqualTo(expected.hashCode());
                    assertThat(CoreMessageCodec.encode(borrowed)).containsExactly(frame);
                    assertThat(com.surprising.aeron.protocol.CommandFingerprint.of(borrowed))
                            .isEqualTo(com.surprising.aeron.protocol.CommandFingerprint.of(expected));
                    previous = borrowed;
                    backing = borrowed.payloadUnsafe();
                } finally { borrowed.releaseDecoderSlot(); }
            }
        }
    }

    @Test void mixedPayloadLengthsReuseTheirOwnBuffersWithoutChangingLeasedFrames() {
        var decoder = new CoreMessageFlyweightDecoder();
        int[] sizes = {0, 1, 8, 31, 256, 2048, 8192};
        var frames = new byte[sizes.length][];
        var payloads = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            var value = new byte[sizes[i]];
            java.util.Arrays.fill(value, (byte) (i + 1));
            frames[i] = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, new UUID(1, i + 1), ProductLine.SPOT,
                    CommandSource.GATEWAY, 1, i + 1, 7, 1, 1), value));
            var first = decoder.decodeBorrowed(new UnsafeBuffer(frames[i]), 0, frames[i].length);
            payloads[i] = first.payloadUnsafe();
            first.releaseDecoderSlot();
        }
        for (int generation = 0; generation < 1024; generation++) {
            for (int i = 0; i < sizes.length; i++) {
                var value = decoder.decodeBorrowed(new UnsafeBuffer(frames[i]), 0, frames[i].length);
                assertThat(value.payloadUnsafe()).isSameAs(payloads[i]);
                assertThat(value.payloadLength()).isEqualTo(sizes[i]);
                if (sizes[i] != 0) assertThat(value.payloadUnsafe()[sizes[i] - 1]).isEqualTo((byte) (i + 1));
                value.releaseDecoderSlot();
            }
        }
        var held = decoder.decodeBorrowed(new UnsafeBuffer(frames[2]), 0, frames[2].length);
        byte[] expected = held.payload();
        for (int length = 1; length < 640; length++) {
            var frame = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.ADJUST_BALANCE, new UUID(2, length), ProductLine.SPOT,
                    CommandSource.GATEWAY, 1, length, 7, 1, 1), new byte[length]));
            var value = decoder.decodeBorrowed(new UnsafeBuffer(frame), 0, frame.length);
            assertThat(value).isNotSameAs(held);
            value.releaseDecoderSlot();
        }
        assertThat(held.payloadUnsafe()).containsExactly(expected);
        assertThat(held.header().commandId()).isEqualTo(new UUID(1, 3));
        held.releaseDecoderSlot();
    }

    @Test
    void readersBelongToTheirDecoderAndCanBeReusedWithoutChangingOwnedMessages() {
        byte[] first = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7))));
        byte[] second = CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 8, 43, 1002,
                123_456_790, 100), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 9))));
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

    @Test void borrowedFramesWaitForRetirementAndKeepResponseHeadersStable() {
        var expected = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, 2), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001, 1, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
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
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 8, 42, 1001,
                123_456_789, 99), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
        byte[] encoded = CoreMessageCodec.encode(message);
        byte[] framed = new byte[encoded.length + 8];
        System.arraycopy(encoded, 0, framed, 4, encoded.length);

        CoreMessage decoded = new CoreMessageFlyweightDecoder().decode(new UnsafeBuffer(framed), 4, encoded.length);
        framed[4 + CoreProtocol.HEADER_LENGTH] = 0;

        assertThat(decoded).isEqualTo(message);
    }
}
