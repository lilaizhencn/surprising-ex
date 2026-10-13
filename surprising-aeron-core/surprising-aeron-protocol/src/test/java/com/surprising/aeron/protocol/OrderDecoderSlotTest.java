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
}
