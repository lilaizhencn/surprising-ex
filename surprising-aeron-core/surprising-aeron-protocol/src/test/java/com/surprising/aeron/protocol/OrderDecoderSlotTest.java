package com.surprising.aeron.protocol;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderDecoderSlotTest {
    private static PlaceOrderCommand order(long id, String clientId) {
        return new PlaceOrderCommand(id, "1", CoreOrderSide.BUY, 100, 2, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, clientId);
    }
    @Test void singleSlotKeepsWireValidationAndStablePublicValues() {
        var stable = order(1, "客户-1");
        var bytes = TradingCommandCodec.encodePlaceOrder(stable);
        var slot = PlaceOrderCommand.commandSlot();
        assertThat(TradingCommandCodec.decodePlaceOrderInto(bytes, 0, bytes.length, slot)).isSameAs(slot).isEqualTo(stable);
        assertThatThrownBy(() -> TradingCommandCodec.decodePlaceOrderInto(bytes, 0, bytes.length, stable))
                .isInstanceOf(IllegalStateException.class);
        var second = TradingCommandCodec.encodePlaceOrder(order(2, ""));
        TradingCommandCodec.decodePlaceOrderInto(second, 0, second.length, slot);
        assertThat(stable.orderId()).isEqualTo(1);
        assertThat(stable.clientOrderId()).isEqualTo("客户-1");
        assertThat(slot.orderId()).isEqualTo(2);
        assertThat(slot.clientOrderId()).isEmpty();
        var malformed = java.util.Arrays.copyOf(second, second.length + 1);
        assertThatThrownBy(() -> TradingCommandCodec.decodePlaceOrderInto(malformed, 0, malformed.length, slot))
                .isInstanceOf(ProtocolException.class);
        assertThat(slot.orderId()).isEqualTo(2);
        slot.clearCommandSlot();
        assertThat(slot.instrumentId()).isNull();
    }
    @Test void reusedWireWriterMatchesUtf8AndAcceptsOffsetWindows() {
        for (String client : List.of("", "ascii-1", "客户-1", "😀-1", "unpaired-\uD800")) {
            var command = order(3, client);
            int size = TradingCommandCodec.encodedPlaceOrderLength(command);
            var buffer = java.nio.ByteBuffer.allocate(size + 9).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.position(4); buffer.limit(4 + size);
            TradingCommandCodec.writePlaceOrder(buffer, command);
            assertThat(buffer.position()).isEqualTo(4 + size);
            var encoded = java.util.Arrays.copyOfRange(buffer.array(), 4, 4 + size);
            assertThat(encoded).isEqualTo(TradingCommandCodec.encodePlaceOrder(command));
            var decoded = TradingCommandCodec.decodePlaceOrder(encoded);
            assertThat(decoded.clientOrderId()).isEqualTo(new String(client.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test void batchSlotsShrinkRejectMalformedFramesAndRecoverWithoutStaleItems() {
        var slot = PlaceOrderBatchCommand.commandSlot();
        var stable = new PlaceOrderBatchCommand(List.of(order(1, "a"), order(2, "b")));
        var bytes = TradingOrderBatchCodec.encodePlaceOrderBatch(stable);
        TradingOrderBatchCodec.decodePlaceOrderBatchInto(bytes, slot);
        assertThat(slot).isEqualTo(stable);
        var first = slot.orders().getFirst();
        var detached = new PlaceOrderBatchCommand(slot.orders());
        assertThatThrownBy(() -> slot.orders().add(order(3, "c"))).isInstanceOf(UnsupportedOperationException.class);
        var shorter = TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(order(4, ""))));
        TradingOrderBatchCodec.decodePlaceOrderBatchInto(shorter, slot);
        assertThat(slot.orders()).hasSize(1);
        assertThat(slot.orders().getFirst()).isSameAs(first);
        assertThat(first.orderId()).isEqualTo(4);
        assertThat(detached).isEqualTo(stable);
        assertThat(first.clientOrderId()).isEmpty();
        var bad = bytes.clone(); bad[8] = 1;
        assertThatThrownBy(() -> TradingOrderBatchCodec.decodePlaceOrderBatchInto(bad, slot)).isInstanceOf(ProtocolException.class);
        assertThat(slot.orders()).isEmpty();
        TradingOrderBatchCodec.decodePlaceOrderBatchInto(bytes, slot);
        assertThat(slot).isEqualTo(stable);
        assertThat(stable.orders().getFirst().orderId()).isEqualTo(1);
    }

    @Test void utf8ScratchMatchesOwnedJdkDecoderIncludingMalformedInputAndReusedSlots() {
        var slot = PlaceOrderCommand.commandSlot();
        var prefix = TradingCommandCodec.encodePlaceOrder(order(1, ""));
        var random = new java.util.Random(954);
        var samples = new java.util.ArrayList<byte[]>();
        for (String value : List.of("", "ascii", "客户😀-0000000000000001", "é", "\u0000", "\uffff", "a".repeat(64)))
            samples.add(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        samples.add(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80});
        samples.add(new byte[]{(byte) 0xf0, (byte) 0x9f, (byte) 0x98});
        samples.add(new byte[]{(byte) 0xc0, (byte) 0x80, (byte) 0xff, 'a'});
        for (int count = 0; count < 5_000; count++) {
            byte[] bytes = new byte[random.nextInt(65)]; random.nextBytes(bytes); samples.add(bytes);
        }
        String previous = "";
        String previousExpected = "";
        for (byte[] text : samples) {
            byte[] payload = java.util.Arrays.copyOf(prefix, prefix.length + text.length);
            java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putShort(prefix.length - Short.BYTES, (short) text.length);
            System.arraycopy(text, 0, payload, prefix.length, text.length);
            var expected = TradingCommandCodec.decodePlaceOrder(payload);
            var decoded = TradingCommandCodec.decodePlaceOrderInto(payload, 0, payload.length, slot);
            assertThat(decoded).isEqualTo(expected);
            assertThat(previous).isEqualTo(previousExpected);
            previous = decoded.clientOrderId(); previousExpected = expected.clientOrderId();
            slot.clearCommandSlot();
            assertThat(slot.clientOrderId()).isNull();
            assertThat(previous).isEqualTo(previousExpected);
        }
        var valid = TradingCommandCodec.encodePlaceOrder(order(2, "重用😀"));
        var invalid = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThatThrownBy(() -> TradingCommandCodec.decodePlaceOrderInto(invalid, 0, invalid.length, slot))
                .isInstanceOf(ProtocolException.class);
        assertThat(slot.orderId()).isZero();
        assertThat(TradingCommandCodec.decodePlaceOrderInto(valid, 0, valid.length, slot)).isEqualTo(order(2, "重用😀"));
    }
}
