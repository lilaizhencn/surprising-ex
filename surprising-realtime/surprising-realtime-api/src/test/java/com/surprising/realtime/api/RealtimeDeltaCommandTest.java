package com.surprising.realtime.api;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.RealtimeFrameCodec;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class RealtimeDeltaCommandTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void preservesRedisKeysVersionsAndFrameBytes(ProductLine product) {
        var first = frame(product, 42, 0, 0);
        var second = frame(product, 42, 1, 0);
        byte[][] command = ValkeyReadViewStore.deltaCommand(List.of(first, second), 99);
        String key = "rt:view:{" + product.name() + ":42}";
        String[] expected = {key, key + ":versions", RealtimeVersion.of(99, 0),
                "ORDER:订单😀", RealtimeVersion.of(91, 0), Base64.getEncoder().encodeToString(RealtimeFrameCodec.encode(first)),
                "ORDER:订单😀", RealtimeVersion.of(91, 1), Base64.getEncoder().encodeToString(RealtimeFrameCodec.encode(second))};
        assertThat(command.length).isEqualTo(expected.length);
        for (int i = 0; i < expected.length; i++)
            assertThat(command[i]).containsExactly(expected[i].getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ValkeyReadViewStore.deltaCommand(List.of(first, frame(product, 43, 1, 0)), 99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValkeyReadViewStore.deltaCommand(List.of(frame(product, 42, 0, 1)), 99))
                .isInstanceOf(IllegalArgumentException.class);
        ProductLine other = ProductLine.values()[(product.ordinal() + 1) % ProductLine.values().length];
        assertThatThrownBy(() -> ValkeyReadViewStore.deltaCommand(List.of(first, frame(other, 42, 1, 0)), 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RealtimeFrame frame(ProductLine product, long user, int ordinal, long snapshot) {
        return new RealtimeFrame(product, RealtimeFrame.Kind.ORDER, user, 91, ordinal,
                1000, snapshot, "BTCUSDT", "订单😀", new byte[]{0, 1, -1});
    }
}
