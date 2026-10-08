package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class RealtimeWireCompatibilityTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void retainsVersionOneBytesForEveryKindAndUtf8Boundary(ProductLine product) {
        for (var kind : RealtimeFrame.Kind.values()) {
            for (String text : new String[]{"", "BTCUSDT", "币种😀", "bad\ud800x\udc00", "币".repeat(42) + "ab"}) {
                byte[] payload = new byte[]{0, 1, -1, 30};
                byte[] symbol = text.getBytes(StandardCharsets.UTF_8);
                byte[] entity = (text + "实体").getBytes(StandardCharsets.UTF_8);
                byte[] expected = ByteBuffer.allocate(64 + symbol.length + entity.length + payload.length)
                        .order(ByteOrder.LITTLE_ENDIAN).putInt(0x52544d31).putInt(1)
                        .putInt(product.ordinal()).putInt(kind.ordinal()).putLong(42).putLong(91)
                        .putInt(3).putLong(1000).putLong(0).putInt(symbol.length).put(symbol)
                        .putInt(entity.length).put(entity).putInt(payload.length).put(payload).array();
                var frame = new RealtimeFrame(product, kind, 42, 91, 3, 1000, 0, text, text + "实体", payload);
                assertThat(RealtimeFrameCodec.encode(frame)).containsExactly(expected);
                var decoded = RealtimeFrameCodec.decode(expected);
                assertThat(decoded.instrumentId()).isEqualTo(new String(symbol, StandardCharsets.UTF_8));
                assertThat(decoded.entityId()).isEqualTo(new String(entity, StandardCharsets.UTF_8));
                expected[expected.length - 1] = 99;
                payload[0] = 99;
                decoded.payload()[0] = 99;
                assertThat(decoded.payload()).containsExactly(0, 1, -1, 30);
                assertThat(frame.payload()).containsExactly(0, 1, -1, 30);
            }
        }
        assertThatThrownBy(() -> RealtimeFrameCodec.encode(new RealtimeFrame(product,
                RealtimeFrame.Kind.ORDER, 1, 1, 0, 0, 0, "币".repeat(43), "", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        var largest = new RealtimeFrame(product, RealtimeFrame.Kind.ORDER, 1, 1, 0, 0, 0,
                "x".repeat(128), "y".repeat(256), new byte[RealtimeFrameCodec.MAX_FRAME_BYTES - 64 - 128 - 256]);
        assertThat(RealtimeFrameCodec.encode(largest)).hasSize(RealtimeFrameCodec.MAX_FRAME_BYTES);
        assertThat(RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(largest)).entityId()).hasSize(256);
        assertThatThrownBy(() -> RealtimeFrameCodec.encode(new RealtimeFrame(product,
                RealtimeFrame.Kind.ORDER, 1, 1, 0, 0, 0, "", "y".repeat(257), new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
